#!/usr/bin/env python3
"""Build every published plugin and publish its .cs3 together with repo/plugins.json.

A "published" plugin is a module directory under plugins/ that already has a
repo/plugins.json: that file is what Settings -> Extensions -> Add repository
reads, and the hash in it is what the app verifies a download against.

The version is the part that is easy to get wrong. PluginManager only replaces
an installed plugin when the online version is greater than the version recorded
inside the installed .cs3 (OnlinePluginData.isOutdated in PluginManager.kt), so a
new build needs a higher number in *both* manifest.json -- which is baked into the
.cs3 -- and repo/plugins.json. Republishing with a fresh hash but an unchanged
version leaves users on the build they already have.

Modules without repo/plugins.json (plugins/anime) are source only and skipped.

Usage:
    tools/publish_plugins.py                    build, bump and publish everything
    tools/publish_plugins.py --modules hianime  publish just those modules
    tools/publish_plugins.py --bump none        rebuild without a version bump
    tools/publish_plugins.py --check            validate only, change nothing
    tools/publish_plugins.py --branch test      point the manifest URLs at a branch
"""

import argparse
import hashlib
import json
import os
import pathlib
import posixpath
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLUGINS = ROOT / "plugins"
DEFAULT_BASE = "https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins"
WINDOWS = os.name == "nt"


def log(message):
    print(message, flush=True)


def run(command, cwd=ROOT, env=None):
    log("  $ " + " ".join(str(c) for c in command))
    result = subprocess.run(
        [str(c) for c in command], cwd=str(cwd), env=env, check=False
    )
    if result.returncode != 0:
        raise SystemExit("command failed (%d): %s" % (result.returncode, " ".join(str(c) for c in command)))


def read_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path, payload):
    # A one element list has to stay a list: CloudStream's repository parser
    # expects an array of entries and breaks on a bare object.
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, indent=2, ensure_ascii=False)
        handle.write("\n")


def published_modules():
    names = []
    for directory in sorted(PLUGINS.iterdir()):
        if (directory / "repo" / "plugins.json").is_file():
            names.append(directory.name)
    return names


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cs3_name(built_dir, manifest):
    """The archive name build_cs3 produced: manifest name without spaces."""
    candidates = sorted(built_dir.glob("*.cs3"))
    if len(candidates) != 1:
        raise SystemExit("%s: expected exactly one .cs3 in %s, found %d" % (manifest, built_dir, len(candidates)))
    return candidates[0]


def check_module(name, base_url, branch):
    """Validate one module without touching anything. Returns a list of problems."""
    module = PLUGINS / name
    manifest_path = module / "manifest.json"
    repo_dir = module / "repo"
    plugins_json = repo_dir / "plugins.json"
    problems = []

    manifest = read_json(manifest_path)
    parsed = read_json(plugins_json)
    if not isinstance(parsed, list):
        problems.append("plugins.json is %s, not a list" % type(parsed).__name__)
        return problems
    if len(parsed) != 1:
        problems.append("plugins.json holds %d entries, expected 1" % len(parsed))
        return problems

    entry = parsed[0]
    if str(entry.get("version")) != str(manifest.get("version")):
        problems.append(
            "version mismatch: manifest.json %s, plugins.json %s"
            % (manifest.get("version"), entry.get("version"))
        )

    archive_name = os.path.basename(entry.get("url", ""))
    if not archive_name:
        problems.append("plugins.json url is empty")
        return problems

    # The URL has to point at the archive sitting next to this manifest, otherwise
    # the app downloads one file and verifies the hash of another.
    expected_prefix = "%s/%s/plugins/%s/repo/" % (base_url, branch, name)
    if entry.get("url") != expected_prefix + archive_name:
        problems.append("url %s does not look like %s" % (entry.get("url"), expected_prefix + archive_name))

    archive = repo_dir / archive_name
    if not archive.is_file():
        problems.append("%s is missing" % archive.relative_to(ROOT))
        return problems

    # An icon hosted in this repository is a path in plugins.json with no hash to
    # back it up, so check the file it names actually exists and belongs to this
    # module.
    icon_url = entry.get("iconUrl")
    if icon_url and "/plugins/" in icon_url:
        icon_prefix = "%s/%s/plugins/" % (base_url, branch)
        if not icon_url.startswith(icon_prefix):
            problems.append("iconUrl %s does not start with %s" % (icon_url, icon_prefix))
        else:
            relative = "plugins/" + icon_url[len(icon_prefix):].split("?", 1)[0]
            module_prefix = "plugins/%s/repo/" % name
            # normpath first: without it a "../repo/icon.png" still starts with
            # module_prefix and slips through.
            normalised = posixpath.normpath(relative)
            if not normalised.startswith(module_prefix):
                problems.append("iconUrl %s is not inside %s" % (relative, module_prefix))
            elif not (ROOT / normalised).is_file():
                problems.append("iconUrl points at missing file %s" % relative)

    if entry.get("fileSize") != str(archive.stat().st_size):
        problems.append(
            "fileSize %s does not match %s on disk (%d)"
            % (entry.get("fileSize"), archive.name, archive.stat().st_size)
        )
    digest = "sha256-" + sha256(archive)
    if entry.get("fileHash") != digest:
        problems.append(
            "fileHash %s does not match %s (%s)" % (entry.get("fileHash"), archive.name, digest)
        )

    # A .cs3 that is not a readable zip would make the app fail at load time, so
    # report it instead of letting the zip reader raise.
    try:
        bundle = zipfile.ZipFile(archive)
    except zipfile.BadZipFile as problem:
        problems.append("%s is not a readable .cs3 (%s)" % (archive.name, problem))
        return problems

    with bundle:
        names = bundle.namelist()
        if "manifest.json" not in names:
            problems.append("%s has no manifest.json inside" % archive.name)
        elif not any(n.endswith(".dex") for n in names):
            problems.append("%s has no dex inside" % archive.name)
        else:
            inside = json.loads(bundle.read("manifest.json").decode("utf-8"))
            if str(inside.get("version")) != str(entry.get("version")):
                problems.append(
                    "version inside %s is %s, plugins.json says %s"
                    % (archive.name, inside.get("version"), entry.get("version"))
                )
    return problems


def bump_manifest_version(name, bump):
    manifest_path = PLUGINS / name / "manifest.json"
    manifest = read_json(manifest_path)
    if bump == "none":
        return int(manifest["version"])
    previous = int(manifest["version"])
    manifest["version"] = str(previous + 1)
    write_json(manifest_path, manifest)
    log("  %s: manifest.json version %d -> %d" % (name, previous, previous + 1))
    return previous + 1


def publish(name, version, base_url, branch):
    """Copy the freshly built .cs3 into repo/ and rewrite repo/plugins.json."""
    module = PLUGINS / name
    repo_dir = module / "repo"
    built = cs3_name(module / "build" / "cs3", name)
    manifest_name = read_json(module / "manifest.json")["name"].replace(" ", "")

    if built.name != manifest_name + ".cs3":
        raise SystemExit("%s: built %s but the name should be %s.cs3" % (name, built.name, manifest_name))

    target = repo_dir / built.name
    for stale in repo_dir.glob("*.cs3"):
        if stale.name != built.name:
            stale.unlink()
    target.write_bytes(built.read_bytes())

    entry = read_json(repo_dir / "plugins.json")[0]
    entry["url"] = "%s/%s/plugins/%s/repo/%s" % (base_url, branch, name, built.name)
    entry["version"] = str(version)
    entry["fileSize"] = str(target.stat().st_size)
    entry["fileHash"] = "sha256-" + sha256(target)
    write_json(repo_dir / "plugins.json", [entry])

    log(
        "  %-14s v%-3s %-18s %7d bytes  %s"
        % (name, version, built.name, target.stat().st_size, entry["fileHash"][:19] + "...")
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--modules", help="comma separated module names (default: every published plugin)")
    parser.add_argument("--bump", choices=("patch", "none"), default="patch", help="version bump, default patch")
    parser.add_argument("--branch", default="main", help="branch the published URLs point at, default main")
    parser.add_argument("--base-url", default=DEFAULT_BASE, help="raw base URL, default " + DEFAULT_BASE)
    parser.add_argument("--check", action="store_true", help="validate the published state and exit")
    args = parser.parse_args()

    available = published_modules()
    selected = [m.strip() for m in args.modules.split(",")] if args.modules else available
    unknown = [m for m in selected if m not in available]
    if unknown:
        raise SystemExit("not a published plugin: %s (published: %s)" % (", ".join(unknown), ", ".join(available)))

    base_url = args.base_url.rstrip("/")

    if args.check:
        log("==> checking %d published plugin(s)" % len(available))
        failed = False
        for name in available:
            problems = check_module(name, base_url, args.branch)
            if problems:
                failed = True
                for problem in problems:
                    log("  %-14s FAIL %s" % (name, problem))
            else:
                log("  %-14s ok" % name)
        if failed:
            raise SystemExit("published state is not consistent")
        log("==> all published plugins are consistent")
        return

    log("==> building %d plugin(s): %s" % (len(selected), ", ".join(selected)))
    versions = {name: bump_manifest_version(name, args.bump) for name in selected}

    # One Gradle run for every module: the per module build_cs3 scripts are
    # skipped for the compile step and only do dex + packaging.
    if WINDOWS:
        run(["cmd", "/c", "gradlew.bat"] + [":plugins:%s:jar" % name for name in selected] + ["--quiet"])
    else:
        run(["sh", "./gradlew"] + [":plugins:%s:jar" % name for name in selected] + ["--quiet"])

    log("==> packaging")
    env = dict(os.environ, CS3_SKIP_COMPILE="1")
    for name in selected:
        if WINDOWS:
            run(["cmd", "/c", "plugins\\%s\\build_cs3.bat" % name], env=env)
        else:
            run(["bash", "plugins/%s/build_cs3.sh" % name], env=env)

    log("==> publishing")
    for name in selected:
        publish(name, versions[name], base_url, args.branch)

    log("==> validating")
    failed = False
    for name in available:
        problems = check_module(name, base_url, args.branch)
        if problems:
            failed = True
            for problem in problems:
                log("  %-14s FAIL %s" % (name, problem))
        else:
            log("  %-14s ok" % name)
    if failed:
        raise SystemExit("published state is not consistent, refusing to publish")

    log("")
    log("Run 'git status' and commit the .cs3, manifest.json and plugins.json changes together.")


if __name__ == "__main__":
    main()