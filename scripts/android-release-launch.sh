#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Provide the verified release APK}
package=com.grapsee.grapmail
adb install "$apk"
launch=$(adb shell am start -W -n "$package/.MainActivity")
printf '%s\n' "$launch"
grep -q '^Status: ok' <<< "$launch"

# The boot callback and am start can finish before the window exposes a UI tree.
for attempt in {1..12}; do
  if adb shell pidof "$package" >/dev/null &&
     adb shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' > /tmp/grapmail-release-activity.txt &&
     grep -Fq "$package/" /tmp/grapmail-release-activity.txt; then
    if adb shell uiautomator dump /sdcard/grapmail-release.xml >/dev/null 2>&1 &&
       adb exec-out cat /sdcard/grapmail-release.xml > /tmp/grapmail-release.xml &&
       grep -Fq "package=\"$package\"" /tmp/grapmail-release.xml; then
      mkdir -p screenshots-release
      adb exec-out screencap -p > screenshots-release/launch.png
      exit 0
    fi
  fi
  sleep 2
done

echo "GrapMail did not expose a visible screen after launch." >&2
cat /tmp/grapmail-release-activity.txt >&2 || true
adb logcat -d -s AndroidRuntime:E >&2
exit 1
