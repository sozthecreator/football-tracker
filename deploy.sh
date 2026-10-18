#!/usr/bin/env bash
# Build debug APK, then install + launch on every adb device (emulator + phones).
# Starts the AVD first if no emulator is running. Override with AVD=name, NO_EMULATOR=1.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}" # AGP 9 vs JDK 25 untested
AVD="${AVD:-Medium_Phone_API_36.1}"
APK=app/build/outputs/apk/debug/app-debug.apk
ACT=eu.devsrv.samapp/.MainActivity

./gradlew assembleDebug -q

if [ -z "${NO_EMULATOR:-}" ] && ! adb devices | grep -q '^emulator-'; then
  echo "starting emulator $AVD"
  nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" >/tmp/emulator.log 2>&1 &
  until adb devices | grep -q '^emulator-.*device$'; do sleep 2; done
  until [ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
fi

adb devices | awk 'NR>1 && $2=="device" {print $1}' | while read -r s; do
  echo "== $s"
  # </dev/null: adb would otherwise swallow the rest of the device list from stdin
  adb -s "$s" install -r "$APK" </dev/null &&
    adb -s "$s" shell am start -n "$ACT" </dev/null ||
    echo "FAILED on $s, continuing"
done

adb devices | awk 'NR>1 && $2!="device" && NF {print "skipped: " $0 " (unauthorized/offline - check phone USB prompt)"}'
