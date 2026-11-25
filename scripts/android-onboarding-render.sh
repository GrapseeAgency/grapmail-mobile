#!/usr/bin/env bash
set -euo pipefail

python=${1:?Provide Python with Pillow installed}
package=com.grapsee.grapmail
adb shell wm size 320x740
adb shell wm density 160
adb shell cmd uimode night yes
adb shell 'settings put secure theme_customization_overlay_packages "{\"android.theme.customization.system_palette\":\"e5c500\",\"android.theme.customization.theme_style\":\"VIBRANT\",\"android.theme.customization.color_source\":\"preset\"}"'
mkdir -p screenshots-release

for scene in {1..5}; do
    adb shell am force-stop "$package"
    adb shell am start -W -n "$package/.MainActivity" --es gmSnapshot "onboarding-$scene"
    frame="screenshots-release/onboarding-$scene.png"
    painted=false
    for attempt in {1..6}; do
        sleep 2
        timeout 10 adb exec-out screencap -p > "$frame"
        if "$python" - "$frame" <<'PY'
import sys
from PIL import Image

with Image.open(sys.argv[1]) as image:
    width, height = image.size
    # Exclude the native wordmark, heading and footer. The central crop must
    # contain artwork painted with the emulator's yellow accent.
    art = image.convert("HSV").crop((30, int(height * .20), width - 30, int(height * .50)))
    gold = sum(10 <= h <= 52 and s >= 65 and v >= 65 for h, s, v in art.getdata())
    sys.exit(0 if gold >= 120 else 1)
PY
        then
            painted=true
            echo "Onboarding scene $scene painted with the system accent"
            break
        fi
    done
    if ! "$painted"; then
        echo "::error::Onboarding scene $scene remained blank or did not use the yellow accent"
        exit 1
    fi
done
