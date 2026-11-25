#!/usr/bin/env bash
# Android accessibility lint driver: uiautomator dumps of main screens, then
# quality/a11y_dump.py (48dp targets, unnamed clickables). Warn-only.
# Usage: bash quality/android-a11y.sh <apk> <outdir>
# Screens: the real first screen (welcome / sign-in) and the offline
# gmSnapshot screens used by the CI walk. Missing snapshot names simply render
# whatever MailSnapshotScreen falls back to; the PNG beside each dump shows it.
set -uo pipefail
APK="${1:?apk}"; OUT="${2:?outdir}"
PKG=com.grapsee.grapmail
mkdir -p "$OUT"
adb install -r -g "$APK"
DENS="$(adb shell wm density | grep -oE '[0-9]+' | tail -n1)"
dump() { # $1 name, rest = extra am args
  local nm="$1"; shift
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" "$@" >/dev/null 2>&1
  sleep 4
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 \
    && adb pull /sdcard/ui.xml "$OUT/$nm.xml" >/dev/null 2>&1
  adb exec-out screencap -p > "$OUT/$nm.png" 2>/dev/null || true
}
dump launch
for s in mail-home mail-thread mail-labels; do dump "$s" --es gmSnapshot "$s"; done
ls "$OUT"/*.xml >/dev/null 2>&1 || { echo "::warning title=a11y::no uiautomator dump captured"; exit 0; }
python3 quality/a11y_dump.py --density "${DENS:-420}" --pkg "$PKG" "$OUT"/*.xml
