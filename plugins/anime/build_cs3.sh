#!/usr/bin/env bash
# Build this CloudStream plugin module as a .cs3 file.
#
# .cs3 format (per recloudstream docs + the app's PluginManager):
#   zip archive containing classes*.dex and manifest.json
#
# The module compiles against :library (compileOnly), so the jar holds only
# the plugin's own classes — exactly what the app expects in a .cs3.
#
# The same script works in every plugins/<module> directory: it derives the
# repository root and module name from its own location and the .cs3 file name
# from manifest.json. On Windows use build_cs3.bat instead.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
MODULE="$(basename "$HERE")"

if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /usr/lib/jvm/*21* /usr/lib/jvm/*17*; do
    [ -d "$candidate" ] && JAVA_HOME="$candidate" && break
  done
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# ANDROID_HOME / ANDROID_SDK_ROOT, else local.properties, else the usual spot.
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  if [ -f "$REPO_ROOT/local.properties" ]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | sed 's/\\:/:/g; s/\\\\/\//g')"
    [ -n "$sdk" ] && export ANDROID_HOME="$sdk"
  fi
fi
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
export ANDROID_HOME

cd "$REPO_ROOT"
echo "==> module: plugins/$MODULE"

echo "==> Compiling plugin module"
# drop stale jars (e.g. from a previous module name) so we never dex the wrong one
rm -rf "plugins/$MODULE/build/libs"
./gradlew ":plugins:$MODULE:jar" -q

BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
API="$(ls "$ANDROID_HOME/platforms" | sed 's/^android-//' | sort -n | tail -1)"
ANDROID_JAR="$ANDROID_HOME/platforms/android-$API/android.jar"
D8="$BT/d8"

JAR="$(find "plugins/$MODULE/build/libs" -name '*.jar' | head -1)"
echo "==> jar: $JAR"

OUT="plugins/$MODULE/build/cs3"
rm -rf "$OUT"
mkdir -p "$OUT/dex"

echo "==> dex (d8, min-api 24)"
"$D8" --release --lib "$ANDROID_JAR" --min-api 24 --output "$OUT/dex" "$JAR"

cp "plugins/$MODULE/manifest.json" "$OUT/manifest.json"

echo "==> packaging .cs3"
cd "$OUT"
MODULE="$MODULE" python3 - <<'PY'
import hashlib, json, os, zipfile

name = json.load(open("manifest.json"))["name"]
dexes = sorted(f for f in os.listdir("dex") if f.endswith(".dex"))
cs3 = name.replace(" ", "") + ".cs3"
with zipfile.ZipFile(cs3, "w", zipfile.ZIP_DEFLATED) as z:
    for d in dexes:
        z.write(os.path.join("dex", d), d)
    z.write("manifest.json", "manifest.json")

data = open(cs3, "rb").read()
print("  dex:", ", ".join(dexes))
print("  cs3: plugins/%s/build/cs3/%s" % (os.environ["MODULE"], cs3))
print('  "fileSize": "%d",' % len(data))
print('  "fileHash": "sha256-%s"' % hashlib.sha256(data).hexdigest())
PY

cd "$REPO_ROOT"
echo "==> done: $OUT (copy the .cs3 into plugins/$MODULE/repo/ and update repo/plugins.json)"