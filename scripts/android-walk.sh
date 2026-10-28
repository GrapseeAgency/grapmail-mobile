#!/usr/bin/env bash
# Drives the real GrapMail app on a booted Android emulator, walking the
# signup + sign-in flows and capturing a screenshot of every screen — the
# same walk GitHub Actions runs. Requires: booted emulator (adb connected),
# the built APK, python3.
#
# v3: every tap/type is FAILURE-GATED (a miss fails the run — the v2 walk
# stalled at screen 08 with five timeout dumps and still exited green),
# the restart now proves session restore (signed-in home → sign out →
# sign-in screen), and the final gate requires the full screenshot set.
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

FAILURES=0
must() { # must <description> <cmd...> — a failed interaction fails the walk
  local desc="$1"; shift
  if ! "$@"; then
    echo "FAIL: $desc" >&2
    FAILURES=$((FAILURES + 1))
  fi
}
must_tap()  { must "tap '$1'"  tap_text "$1"; }
must_type() { must "type into '$1'" type_into "$1" "$2"; }
must_tap_low() { # tap the first node with this exact text below MINY px —
                 # dodges same-text chrome above (the CREATE ACCOUNT eyebrow)
  local pos
  pos="$(MINY="$2" wait_nodes "n.get('text','')=='$1'" | head -1)"
  must "tap low '$1'" tap_xy "$pos"
}

echo "── GrapMail emulator walk v3 ──────────────────────────"

adb wait-for-device
for _ in $(seq 1 150); do
  BOOT="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [ "$BOOT" = "1" ] && break
  sleep 2
done
echo "boot completed — settling system 80s (cold emulators ANR under early dump load)"
sleep 80

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r "$APK" >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 6

echo "· welcome"
shot 01-welcome-light

echo "· step 1 — name"
must_tap "Create Account"
must_type "First name" "Arafat"
must_type "Last name" "Hossain"
shot 02-step1-name-light
must_tap "Next"

echo "· step 2 — basic info (dropdowns + gender)"
# All three picks take the panel's FIRST item by geometry (January 1, 2013 is
# a valid 13-year-old date): the inline panel is clipped by the body viewport
# when the row sits low on screen, so only item 1 is guaranteed visible.
# Fallback taps sit one row-item below the measured row center — dp-aware.
# Row/item lookups use wait_nodes so a below-the-fold panel scrolls into view.
must_tap "Month"
MROW=$(wait_nodes "n.get('text','').lower()=='month'" | head -1 | tr -d '\r') || MROW=""
MY=${MROW#* }; MY=${MY:-0}
P0=$(MINY=$((MY+20)) wait_nodes "n.get('text')=='January'" | head -1) || P0=""
if [ -n "$P0" ]; then adb shell input tap $P0
elif [ -n "$MROW" ]; then adb shell input tap ${MROW%% *} $((MY + $(dp 55)))
else must "month row visible" false; fi
sleep 1.4
must_tap "Day"
DROW=$(wait_nodes "n.get('text','').lower()=='day'" | head -1 | tr -d '\r') || DROW=""
DY=${DROW#* }; DY=${DY:-0}
P1=$(MINY=$((DY+20)) wait_nodes "n.get('text')=='1'" | head -1) || P1=""
if [ -n "$P1" ]; then adb shell input tap $P1
elif [ -n "$DROW" ]; then adb shell input tap ${DROW%% *} $((DY + $(dp 55)))
else must "day row visible" false; fi
sleep 1.4
must_tap "Year"
YROW=$(wait_nodes "n.get('text','').lower()=='year'" | head -1 | tr -d '\r') || YROW=""
YY=${YROW#* }; YY=${YY:-0}
P2=$(MINY=$((YY+20)) wait_nodes "n.get('text')=='2013'" | head -1) || P2=""
if [ -n "$P2" ]; then adb shell input tap $P2
elif [ -n "$YROW" ]; then adb shell input tap ${YROW%% *} $((YY + $(dp 55)))
else must "year row visible" false; fi
sleep 1.4
must_tap "Female"
shot 03-step2-basicinfo-light
must_tap "Next"

echo "· step 3 — address (custom unique handle)"
must_type "Choose your own address" "$HANDLE"
sleep 3   # live availability check
shot 04-step3-address-light
must_tap "Next"

echo "· step 4 — password"
scroll_up   # bring the fields on screen
sleep 1
must_type "Password" "Grapmail%23e2e2026"
must_type "Confirm password" "Grapmail%23e2e2026"
sleep 1
shot 05-step4-password-light
must_tap "Next"

echo "· step 5 — terms → real account"
# Anchor on the document heading BEFORE any page scroll (an outer-body swipe
# first pushes the heading out of the dump and the anchored scrolls then miss
# the card entirely). The gate is real scroll detection: the agree card only
# toggles after the document reached its bottom.
TH=$(wait_nodes "n.get('text','')=='GrapMail Terms of Service'")
read -r HX HY <<< "$TH"
for _ in 1 2 3 4; do
  adb shell input swipe $CX $((HY + $(dp 240))) $CX $((HY + $(dp 35))) 450
  sleep 0.9
done
scroll_up  # reveal the agree card
sleep 1
must_tap "I agree to the"
shot 06-step5-terms-light
# The disabled Material button's child text node still reports enabled=true
# in uiautomator, and the "CREATE ACCOUNT" eyebrow equals the button text
# case-insensitively — so tap the button by position (bottom half) and prove
# the gate opened by reaching the next screen.
must_tap_low "Create Account" $((SH / 2))
sleep 6
if wait_nodes "n.get('text','')=='Just one more step!'" >/dev/null; then
  echo "  ✓ terms gate opened on real scroll (account created for real)"
else
  echo "FAIL: human verification never reached — gate or creation broken" >&2
  FAILURES=$((FAILURES + 1))
fi

echo "· human verification (account just created + auto-signed-in for real)"
sleep 10
shot 07-human-verify-light
must_tap "Skip for now"
sleep 2

echo "· account created (A)"
shot 08-created-a-light
must_tap "Continue to Onboarding"
sleep 3

echo "· account created (B)"
shot 09-created-b-light
must_tap "Go to Mailbox"
sleep 2

echo "· security sector — every hub screen, read-only"
# Mirrors the iOS walk's security leg: open each hub row, photograph the
# screen, press system back. No destructive submits — no password change,
# no revoke, no sign-out-all — the account must survive the walk.
# (2FA on a fresh account: the hub's toggle routes into the ENABLE flow.)
must_tap "Account security"
must "hub opens" wait_nodes "n.get('text','')=='Account Security'" >/dev/null
shot 20-sec-hub-light
sec_visit() { # sec_visit <hub row text> <shot name>
  must_tap "$1"; sleep 2; shot "$2"; adb shell input keyevent 4; sleep 1.5
}
sec_visit "Two-Step Verification" 21-sec-2fa-enable-light
sec_visit "Change Password" 22-sec-change-password-light
sec_visit "Backup Codes" 23-sec-backup-codes-light
sec_visit "Active Sessions" 24-sec-sessions-light
sec_visit "Recent Security Activity" 25-sec-activity-light
sec_visit "Sign Out All Devices" 26-sec-signout-all-light
# back out of the hub to the signed-in home, where the restore leg starts
# (the pipe lives INSIDE the command substitution — `must cmd >/dev/null`
# would run must in a subshell and lose the FAILURES counter)
must "hub back tap" tap_xy "$(wait_nodes "n.get('content-desc','')=='Back'" | head -1)"
sleep 2
must "home after security" wait_nodes "n.get('text','')=='Signed in'" >/dev/null

echo "· session restore: restart must open signed-in, not at welcome"
adb shell am force-stop "$PKG"
sleep 1
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 6
shot 10-session-restored
# assert, don't tap: the home screen must show the signed-in state
if wait_nodes "n.get('text','')=='Signed in'" >/dev/null; then
  echo "  ✓ session restored"
else
  echo "FAIL: restart did not open signed-in" >&2
  FAILURES=$((FAILURES + 1))
fi
# sign out returns to welcome
must_tap "Sign out"
sleep 2

echo "· sign-in screen"
must_tap "Sign In"
sleep 2
shot 11-signin-light

echo "· dark pass"
adb shell "cmd uimode night yes"
sleep 3
adb shell am force-stop "$PKG"
sleep 1
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8
shot 12-welcome-dark
must_tap "Create Account"
sleep 2
shot 13-step1-name-dark
adb shell am force-stop "$PKG"
adb shell "cmd uimode night no"

echo "── done: $(ls "$OUT"/*.png 2>/dev/null | wc -l) screenshots in $OUT ────"

# ── hard gates: no timeout dumps, no failed interactions, full PNG set ──
if compgen -G "$OUT/timeout-*.xml" > /dev/null; then
  echo "FAIL: timeout dumps present — a node never appeared (see $OUT/timeout-*.xml)" >&2
  FAILURES=$((FAILURES + 1))
fi
for want in 01-welcome-light 02-step1-name-light 03-step2-basicinfo-light \
            04-step3-address-light 05-step4-password-light 06-step5-terms-light \
            07-human-verify-light 08-created-a-light 09-created-b-light \
            20-sec-hub-light 21-sec-2fa-enable-light 22-sec-change-password-light \
            23-sec-backup-codes-light 24-sec-sessions-light 25-sec-activity-light \
            26-sec-signout-all-light \
            10-session-restored 11-signin-light 12-welcome-dark 13-step1-name-dark; do
  [ -s "$OUT/$want.png" ] || { echo "FAIL: missing $want.png" >&2; FAILURES=$((FAILURES + 1)); }
done
if [ "$FAILURES" -gt 0 ]; then
  echo "── walk FAILED: $FAILURES problem(s) ──" >&2
  exit 1
fi
echo "── walk PASSED ──"
