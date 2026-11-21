#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Provide the verified release APK}
python=${2:?Provide Python with Pillow installed}
package=com.grapsee.grapmail
adb shell wm size 360x800
adb shell wm density 160
adb install "$apk"
launch=$(adb shell am start -W -n "$package/.MainActivity")
printf '%s\n' "$launch"
grep -q '^Status: ok' <<< "$launch"

mkdir -p screenshots-release
frame=screenshots-release/launch.png
for attempt in {1..8}; do
    if adb shell pidof "$package" >/dev/null &&
       adb shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' > /tmp/grapmail-release-activity.txt &&
       grep -Fq "$package/" /tmp/grapmail-release-activity.txt &&
       adb shell dumpsys window | grep -E 'mCurrentFocus=' > /tmp/grapmail-release-window.txt &&
       grep -Fq "$package/" /tmp/grapmail-release-window.txt &&
       timeout 8 adb exec-out screencap -p > "$frame" &&
       "$python" - "$frame" <<'PY'
import sys
from PIL import Image

with Image.open(sys.argv[1]) as image:
    width, height = image.size
    center = image.convert("RGB").crop((24, int(height * .15), width - 24, int(height * .8)))
    colors = center.getcolors(maxcolors=1000000)
    sys.exit(0 if colors and len(colors) > 60 else 1)
PY
    then
        exit 0
    fi
    sleep 2
done

echo "GrapMail did not show a visible screen after launch." >&2
cat /tmp/grapmail-release-activity.txt >&2 || true
cat /tmp/grapmail-release-window.txt >&2 || true
adb logcat -d -s AndroidRuntime:E >&2
exit 1
