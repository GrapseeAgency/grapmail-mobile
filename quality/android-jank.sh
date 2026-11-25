#!/usr/bin/env bash
# Android jank job driver: scroll the demo mailbox and read gfxinfo.
# Usage: bash quality/android-jank.sh <apk> <outdir>
# Data: the test-only `--es gmSnapshot mail-home` entry (MainActivity) renders
# the mailbox offline with seeded sample mail - the same hook scripts/android-walk.sh
# uses - so no account or server is needed. Warn-only.
set -uo pipefail
APK="${1:?apk}"; OUT="${2:?outdir}"
PKG=com.grapsee.grapmail
mkdir -p "$OUT"
adb install -r -g "$APK"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --es gmSnapshot mail-home >/dev/null
sleep 4
adb exec-out screencap -p > "$OUT/mail-home.png" 2>/dev/null || true

read W H < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -n1 | tr x ' ')
X=$((W / 2)); Y1=$((H * 3 / 4)); Y2=$((H / 4))
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
for i in $(seq 1 15); do
  adb shell input swipe "$X" "$Y1" "$X" "$Y2" 250; sleep 0.6
  adb shell input swipe "$X" "$Y2" "$X" "$Y1" 250; sleep 0.6
done
# A fling-heavy pass as well (short duration = fast fling).
for i in $(seq 1 10); do adb shell input swipe "$X" "$Y1" "$X" "$Y2" 80; sleep 0.4; done
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt"
python3 quality/gfxinfo_jank.py "$OUT/gfxinfo.txt"
