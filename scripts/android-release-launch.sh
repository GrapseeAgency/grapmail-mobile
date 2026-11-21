#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Provide the verified release APK}
package=com.grapsee.grapmail
adb install "$apk"
adb shell am start -W -n "$package/.MainActivity"
adb shell uiautomator dump /sdcard/grapmail-release.xml
adb exec-out cat /sdcard/grapmail-release.xml > /tmp/grapmail-release.xml
if ! grep -q "package=\"$package\"" /tmp/grapmail-release.xml; then
  echo "GrapMail did not reach a visible screen after launch." >&2
  adb logcat -d -s AndroidRuntime:E >&2
  exit 1
fi
mkdir -p screenshots-release
adb exec-out screencap -p > screenshots-release/launch.png
