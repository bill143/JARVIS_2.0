#!/usr/bin/env bash
#
# Validates the jpackage configuration by building a self-contained *app-image*
# (application + bundled private JRE, but no OS-specific installer wrapper) on the
# current platform. jpackage can produce an app-image on any OS, so this is what
# CI/dev use to confirm the main-class / jar / runtime wiring is correct before the
# Windows-only .msi build (package-windows.ps1) is run on a Windows box.
#
# Usage (from the java/ directory):
#   packaging/package-appimage.sh [version]
#
# Output: packaging/dist/JARVIS/  (run it via packaging/dist/JARVIS/bin/JARVIS)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
java_root="$(dirname "$here")"
version="${1:-0.1.0}"
dist="$here/dist"
app_out="$java_root/app/target"

echo "==> Building the application fat-jar..."
( cd "$java_root" && ./mvnw -q -pl app -am clean package )

jar="$app_out/jarvis.jar"
[ -f "$jar" ] || { echo "fat jar missing: $jar" >&2; exit 1; }

# Stage ONLY the fat jar into a clean input dir. jpackage puts every jar in --input on the
# classpath, so pointing it at target/ would also bundle original-jarvis.jar (unshaded) + build junk.
stage="$here/staging"
rm -rf "$dist" "$stage"
mkdir -p "$stage" "$dist"
cp "$jar" "$stage/jarvis.jar"

echo "==> jpackage --type app-image (bundles a private JRE via jlink)..."
jpackage \
  --type app-image \
  --name JARVIS \
  --app-version "$version" \
  --input "$stage" \
  --main-jar jarvis.jar \
  --main-class com.jarvis.app.Main \
  --dest "$dist"

rm -rf "$stage"
echo "==> app-image built at: $dist/JARVIS"
