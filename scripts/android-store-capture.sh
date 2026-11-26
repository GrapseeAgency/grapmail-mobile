#!/usr/bin/env bash
set -euo pipefail

pkg=com.grapsee.grapmail
mkdir -p store-demo
ui=$(mktemp)
trap 'rm -f "$ui"' EXIT

adb shell wm size 1440x2560
adb shell wm density 560
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
  return 1
}

for palette in light dark; do
  mode=no
  [[ "$palette" == dark ]] && mode=yes
  adb shell cmd uimode night "$mode"
  capture mail-home 'Q3 facilities invoice' "$palette"
  capture mail-thread 'Q3 facilities invoice' "$palette"
  capture mail-thread-images 'mail-show-images' "$palette"
  capture mail-compose 'gm-signed-body' "$palette"
  capture mail-screener 'Waiting in the queue' "$palette"
  capture mail-unsubscribe 'gm-sheets-unsub' "$palette"
  capture mail-labels 'Bills' "$palette"
done
