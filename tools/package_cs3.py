#!/usr/bin/env python3
"""Package the dexed plugin in the current directory as a .cs3.

Run from the build/cs3 directory, which holds manifest.json and dex/. Used by
plugins/<module>/build_cs3.sh; tools/package_cs3.ps1 does the same thing for
build_cs3.bat.

Every field the zip format would otherwise take from the filesystem is pinned
here. A rebuild of unchanged sources then produces identical bytes, so the
published fileHash only moves when the code actually does, and the publisher can
tell a real change from a rebuild. The two constants below are what .NET's
ZipArchive writes, which is what keeps a Windows build and a CI build of the same
sources byte for byte identical.
"""

import hashlib
import json
import os
import sys
import zipfile

ZIP_EPOCH = (1980, 1, 1, 0, 0, 0)
EXTERNAL_ATTR = 0x81A40000


def main():
    module = sys.argv[1] if len(sys.argv) > 1 else "?"
    with open("manifest.json", "r", encoding="utf-8") as handle:
        manifest = json.load(handle)

    cs3 = manifest["name"].replace(" ", "") + ".cs3"
    dexes = sorted(f for f in os.listdir("dex") if f.endswith(".dex"))
    if not dexes:
        raise SystemExit("no dex in ./dex, refusing to package a .cs3 with no code in it")
    if os.path.exists(cs3):
        os.remove(cs3)

    sources = [(d, os.path.join("dex", d)) for d in dexes]
    sources.append(("manifest.json", "manifest.json"))

    with zipfile.ZipFile(cs3, "w", zipfile.ZIP_DEFLATED) as bundle:
        for arcname, source in sources:
            info = zipfile.ZipInfo(arcname, date_time=ZIP_EPOCH)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 0
            info.external_attr = EXTERNAL_ATTR
            with open(source, "rb") as handle:
                bundle.writestr(info, handle.read())

    with open(cs3, "rb") as handle:
        data = handle.read()

    print("  dex: " + ", ".join(dexes))
    print("  cs3: plugins/%s/build/cs3/%s" % (module, cs3))
    print('  "fileSize": "%d",' % len(data))
    print('  "fileHash": "sha256-%s"' % hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main()