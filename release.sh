#!/usr/bin/env bash
# Publish a new version: bump appVersion/appVersionCode in gradle.properties, commit, then run ./release.sh "what changed"
# Builds the Android APK (no API key inside; it uses the score server), the Linux .deb and portable zip,
# and creates the GitHub Release. Pushing the tag also makes GitHub build the Windows .msi and attach it.
# Running copies of the apps then offer the update to their users.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
JPACKAGE="${JPACKAGE:-/usr/lib/jvm/java-25-openjdk-amd64/bin/jpackage}"
VERSION=$(grep '^appVersion=' gradle.properties | cut -d= -f2)
NOTES="${1:-Version $VERSION}"
grep -q '^workerUrl=' local.properties || { echo "workerUrl missing in local.properties"; exit 1; }

rm -rf dist && mkdir dist
./gradlew -q -PfootballDataKey= assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk dist/football-tracker.apk
if unzip -p dist/football-tracker.apk 'classes*.dex' | grep -aq "$(grep '^footballDataKey=' local.properties | cut -d= -f2-)"; then
  echo "refusing to publish: the API key is inside the APK"; exit 1
fi
./gradlew -q -PdesktopOnly :desktop:installDist :desktop:distZip
cp desktop/build/distributions/football-tracker.zip dist/football-tracker-$VERSION.zip
"$JPACKAGE" --type deb --name football-tracker --app-version "$VERSION" --vendor "Football Tracker" \
  --description "Live football scores, goal sounds and notifications" \
  --input desktop/build/install/football-tracker/lib --main-jar desktop.jar \
  --main-class eu.devsrv.samapp.desktop.Main --icon desktop/packaging/icon.png \
  --add-modules java.desktop,java.net.http,java.xml,java.prefs,java.logging \
  --linux-shortcut --linux-menu-group Sports --dest dist

git push origin HEAD
gh release create "v$VERSION" dist/* --title "Football Tracker $VERSION" --notes "$NOTES"
echo "Released v$VERSION. The Windows .msi is attached by GitHub in a few minutes."
