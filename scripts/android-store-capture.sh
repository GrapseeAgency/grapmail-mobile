#!/usr/bin/env bash
set -euo pipefail

pkg=com.grapsee.grapmail
mkdir -p store-demo/diagnostics
failures=0
ui=$(mktemp)
trap 'rm -f "$ui"' EXIT

adb shell wm size 4320x7680
adb shell wm density 1680
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

capture() {
  local scene=$1 marker=$2 palette=$3 attempt
  adb shell am force-stop "$pkg"
  adb shell am start -W -n "$pkg/.MainActivity" --es gmSnapshot "$scene"

  for attempt in {1..12}; do
    if adb shell uiautomator dump /sdcard/store-demo.xml >/dev/null &&
       adb pull /sdcard/store-demo.xml "$ui" >/dev/null 2>&1 &&
       grep -Fq 'gm-snapshot' "$ui" &&
       grep -Fq "$marker" "$ui"; then
      adb exec-out screencap -p > "store-demo/$scene-$palette.png"
      return
    fi
    sleep 1
  done
  echo "Expected screen did not render: $scene ($palette)" >&2
  cp "$ui" "store-demo/diagnostics/$scene-$palette.xml"
  adb exec-out screencap -p > "store-demo/diagnostics/$scene-$palette.png"
  adb logcat -d -b crash > "store-demo/diagnostics/$scene-$palette-crash.txt" || true
  failures=$((failures + 1))
}

adb shell cmd uimode night yes
capture mail-thread 'Q3 facilities invoice' dark
(( failures == 0 ))

python3 - <<'PNG'
from pathlib import Path
import struct

shot = Path("store-demo/mail-thread-dark.png")
with shot.open("rb") as stream:
    header = stream.read(24)
if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
    raise SystemExit("Capture is not a PNG")
width, height = struct.unpack(">II", header[16:24])
print(f"Native capture: {width} × {height}")
if (width, height) != (4320, 7680):
    raise SystemExit("Capture did not render at 8K portrait resolution")
PNG
