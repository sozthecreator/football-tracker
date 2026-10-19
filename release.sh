#!/usr/bin/env bash
# Publish a new version: bump appVersion/appVersionCode in gradle.properties, commit, then run ./release.sh "what changed"
# Builds the Android APK (no API key inside; it uses the score server) and the portable laptop zip,
# pushes the version tag to sozthecreator/football-tracker and creates the GitHub Release (marked latest).
# Then starts the installers workflow, which builds the Windows .msi and Linux .deb and attaches them to the release.
# Running copies of the apps check this release and offer the update to their users.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
REPO="sozthecreator/football-tracker"
VERSION=$(grep '^appVersion=' gradle.properties | cut -d= -f2)
NOTES="${1:-Version $VERSION}"
grep -q '^workerUrl=' local.properties || { echo "workerUrl missing in local.properties"; exit 1; }

rm -rf dist && mkdir dist
./gradlew -q -PfootballDataKey= assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk dist/football-tracker.apk
KEY=$(grep '^footballDataKey=' local.properties | cut -d= -f2- || true)
if [ -n "$KEY" ] && unzip -p dist/football-tracker.apk 'classes*.dex' | grep -aq "$KEY"; then
  echo "refusing to publish: the API key is inside the APK"; exit 1
fi
./gradlew -q -PdesktopOnly :desktop:installDist :desktop:distZip
cp desktop/build/distributions/football-tracker.zip dist/football-tracker-$VERSION.zip

git tag -a "v$VERSION" -m "Football Tracker $VERSION" 2>/dev/null || echo "tag v$VERSION already exists locally"
git push sozthecreator HEAD "v$VERSION"
gh release create "v$VERSION" dist/* --repo "$REPO" --verify-tag --latest --title "Football Tracker $VERSION" --notes "$NOTES"
gh workflow run installers.yml --repo "$REPO" -f tag="v$VERSION"
echo "Released v$VERSION. The .msi and .deb are added by the installers workflow in a few minutes (Actions tab)."
