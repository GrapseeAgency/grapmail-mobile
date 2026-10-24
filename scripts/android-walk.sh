#!/usr/bin/env bash
# Drives the real GrapMail app on a booted Android emulator, walking the
# signup + sign-in flows and capturing a screenshot of every screen — the
# same walk GitHub Actions runs. Requires: booted emulator (adb connected),
# the built APK, python3.
#
# Usage: android-walk.sh <path-to-apk> <output-dir>
set -uo pipefail

APK="${1:?apk path}"
OUT="${2:?output dir}"
PKG="com.grapsee.grapmail"
mkdir -p "$OUT"
STAMP="$(date +%s)"
HANDLESUFFIX="e2e${STAMP: -6}"

adb() { command adb "$@"; }

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "  ↳ $1.png"; }

dump_xml() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/.ui.xml" >/dev/null 2>&1; }

# Find a node whose text/content-desc contains $1; print "cx cy" of its center.
find_text() {
  dump_xml
  python3 - "$OUT/.ui.xml" "$1" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
needle = sys.argv[2].lower()
for m in re.finditer(r'<node[^>]*/>|<node[^>]*>', xml):
    tag = m.group(0)
    tm = re.search(r'text="([^"]*)"', tag)
    dm = re.search(r'content-desc="([^"]*)"', tag)
    hay = ((tm.group(1) if tm else "") + " " + (dm.group(1) if dm else "")).lower()
    if needle in hay:
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if bm:
            x = (int(bm.group(1)) + int(bm.group(3))) // 2
            y = (int(bm.group(2)) + int(bm.group(4))) // 2
            print(x, y)
            break
PY
}

wait_for_text() {
  for _ in $(seq 1 30); do
    POS="$(find_text "$1")"
    [ -n "$POS" ] && { echo "$POS"; return 0; }
    sleep 1
  done
  echo "TIMEOUT waiting for: $1" >&2
  return 1
}

tap() { # tap <text>  — finds and taps
  POS="$(wait_for_text "$1")" || return 1
  adb shell input tap $POS
  sleep 1.2
}

type_into() { # type_into <label> <value>
  POS="$(wait_for_text "$1")" || return 1
  adb shell input tap $POS
  sleep 0.9
  adb shell input text "$2"
  sleep 0.4
  adb shell input keyevent 111  # ESC closes keyboard
  sleep 0.4
}

echo "── GrapMail emulator walk ─────────────────────────────"

adb wait-for-device
for _ in $(seq 1 150); do
  BOOT="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [ "$BOOT" = "1" ] && break
  sleep 2
done
echo "boot completed"

adb install -r -g "$APK" >/dev/null 2>&1 || adb install -r "$APK" >/dev/null
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 5

echo "· welcome"
shot 01-welcome-light

echo "· step 1 — name"
tap "Create Account"
type_into "First Name" "Arafat"
type_into "Last Name" "Hossain"
shot 02-step1-name-light
tap "Continue"

echo "· step 2 — basic info"
type_into "Birthday" "04121998"
tap "Female"
shot 03-step2-basicinfo-light
tap "Continue"

echo "· step 3 — address"
tap "Create your own"
type_into "Custom address" "$HANDLESUFFIX"
sleep 3   # live availability check
shot 04-step3-address-light
tap "Continue"

echo "· step 4 — password"
type_into "Password" "Grapmail%23e2e2026"   # -> Grapmail#e2e2026
type_into "Confirm password" "Grapmail%23e2e2026"
sleep 1
shot 05-step4-password-light
tap "Continue"

echo "· step 5 — terms"
adb shell input swipe 540 1500 540 900 400   # scroll the terms box
sleep 1
tap "I have read and agree"
shot 06-step5-terms-light
tap "Create Account"

echo "· human verification (real account just created)"
sleep 8   # real network: create + auto sign-in
shot 07-human-verify-light
tap "Skip for now"
sleep 2

echo "· account created (A)"
shot 08-created-a-light
tap "Continue to Onboarding"
sleep 2

echo "· account created (B)"
shot 09-created-b-light

echo "· sign in screen"
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 4
tap "Sign In"
sleep 2
shot 10-signin-light

echo "· dark mode pass"
adb shell "cmd uimode night yes"
sleep 2
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 4
shot 11-welcome-dark
tap "Create Account"
sleep 2
shot 12-step1-name-dark

adb shell "cmd uimode night no"
echo "── done: $(ls "$OUT"/*.png | wc -l) screenshots in $OUT ───────────────"
