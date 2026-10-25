#!/usr/bin/env bash
# Drives the real GrapMail app on a booted Android emulator, walking the
# signup + sign-in flows and capturing a screenshot of every screen — the
# same walk GitHub Actions runs. Requires: booted emulator (adb connected),
# the built APK, python3.
#
# v2: targets text fields by class+index (EditText nodes) instead of label
# text, retries flaky uiautomator dumps, dismisses the keyboard with BACK
# only when shown, and recaptures blank screenshots.
#
# Usage: android-walk.sh <path-to-apk> <output-dir>
set -uo pipefail

APK="${1:?apk path}"
OUT="${2:?output dir}"
PKG="com.grapsee.grapmail"
mkdir -p "$OUT"
STAMP="$(date +%s)"
HANDLE="e2e${STAMP: -6}"
source "$(dirname "$0")/android-walk-helpers.sh"

echo "── GrapMail emulator walk v2 ──────────────────────────"

adb wait-for-device
for _ in $(seq 1 150); do
  BOOT="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [ "$BOOT" = "1" ] && break
  sleep 2
done
echo "boot completed — settling system 80s (cold emulators ANR under early dump load)"
sleep 80

adb uninstall "$PKG" >/dev/null 2>&1
adb install -r "$APK" >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 6

echo "· welcome"
shot 01-welcome-light

echo "· step 1 — name"
tap_text "Create Account"
type_into "First name" "Arafat"
type_into "Last name" "Hossain"
shot 02-step1-name-light
tap_text "Next"

echo "· step 2 — basic info (dropdowns + gender)"
tap_text "Month"
tap_text "April"
# Day/Year: open the panel and tap the FIRST option by geometry — the item
# sits ~105px below the row's center (row bottom + 4dp gap + half item).
# No dumps between the taps: nothing can go stale.
tap_text "Day"
DY=$(find_nodes "n.get('text','').lower()=='day'" | head -1 | awk '{print $2}' | tr -d '\r')
P1=$(MINY=$((DY+20)) find_nodes "n.get('text')=='1'" | head -1)
if [ -n "$P1" ]; then adb shell input tap $P1; else adb shell input tap 540 $((DY + 140)); fi
sleep 1.4
tap_text "Year"
YY=$(find_nodes "n.get('text','').lower()=='year'" | head -1 | awk '{print $2}' | tr -d '\r')
P2=$(MINY=$((YY+20)) find_nodes "n.get('text')=='2013'" | head -1)
if [ -n "$P2" ]; then adb shell input tap $P2; else adb shell input tap 540 $((YY + 140)); fi
sleep 1.4
tap_text "Female"
shot 03-step2-basicinfo-light
tap_text "Next"

echo "· step 3 — address (custom unique handle)"
type_into "Address" "$HANDLE"
sleep 3   # live availability check
shot 04-step3-address-light
tap_text "Next"

echo "· step 4 — password"
adb shell input swipe 540 1800 540 900 400   # bring the password fields on screen
sleep 1
type_into "Password" "Grapmail%23e2e2026"
type_into "Confirm password" "Grapmail%23e2e2026"
sleep 1
shot 05-step4-password-light
tap_text "Next"

echo "· step 5 — terms → real account"
adb shell input swipe 540 1800 540 900 400   # bring the terms card on screen
sleep 1
adb shell input swipe 540 1200 540 700 500   # scroll the terms document
sleep 1
adb shell input swipe 540 1200 540 700 500
sleep 1
adb shell input swipe 540 1600 540 1000 400  # reveal the agree card
sleep 1
tap_text "I agree to the"
shot 06-step5-terms-light
tap_text "Create Account"

echo "· human verification (account just created + auto-signed-in for real)"
sleep 10
shot 07-human-verify-light
tap_text "Skip for now"
sleep 2

echo "· account created (A)"
shot 08-created-a-light
tap_text "Continue to Onboarding"
sleep 3

echo "· account created (B)"
shot 09-created-b-light

echo "· sign-in screen"
adb shell am force-stop "$PKG"
sleep 1
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 6
tap_text "Sign In"
sleep 2
shot 10-signin-light

echo "· dark pass"
adb shell "cmd uimode night yes"
sleep 3
adb shell am force-stop "$PKG"
sleep 1
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8
shot 11-welcome-dark
tap_text "Create Account"
sleep 2
shot 12-step1-name-dark
adb shell am force-stop "$PKG"
adb shell "cmd uimode night no"

echo "── done: $(ls "$OUT"/*.png 2>/dev/null | wc -l) screenshots in $OUT ────"
