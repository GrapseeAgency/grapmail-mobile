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

# ── quiet the device before anything is measured ─────────────────────────────
# One run on the low-memory profile spent 797 recovery cycles on
# "Pixel Launcher isn't responding" and was still cut by the job ceiling with
# four snapshots short. A system dialog is not something the walk can prevent —
# only clean up after — and every cleanup costs a tap, a wait and a fresh dump.
# Suppressing it at the source is what Google's own emulator guidance says to do
# for automation, so the dialog is never raised in the first place.
#
# The trade-off is named rather than glossed: if THIS app crashes, its dialog is
# hidden too. The walk still goes red, because the crashed screen's marker never
# appears and the PNG gate counts the missing frame — the loss is a less specific
# message, not a lost failure. The ANR detector stays in the wait loops as the
# fallback for a runner where this setting is ignored.
#
# The three animation scales are the other half. Every sheet, drawer and tab is
# photographed after it settles, so at 1x each of ~60 screens pays a fixed
# transition tax and the run is bounded by a wall clock instead of by what it
# proves.
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global "$scale" 0 >/dev/null 2>&1 || true
done

FAILURES=0
MUST_MISS=0
must() { # must <description> <cmd...> — a failed interaction fails the walk
  local desc="$1"; shift
  if ! "$@"; then
    echo "FAIL: $desc" >&2
    FAILURES=$((FAILURES + 1))
    # Consecutive, and fatal at eight. The shade run lost `Create Account` at the
    # first tap of the flow and then spent an hour and a half losing everything
    # after it — 107 findings that were one finding, and the ceiling is what
    # stopped it. Eight interactions in a row that the device will not answer is
    # not flakiness; it is the app not being on screen, or not being up at all, so
    # the state is printed with the abort rather than left for someone to
    # reconstruct from an artifact.
    MUST_MISS=$((MUST_MISS + 1))
    if [ "$MUST_MISS" -ge 8 ]; then
      echo "ABORT: $MUST_MISS interactions in a row failed at '$desc'." >&2
      echo "ABORT: foreground dump —" >&2
      adb shell dumpsys activity activities 2>/dev/null | grep -iE "topResumedActivity|mResumedActivity|ResumedActivity:" | head -3 >&2
      adb shell dumpsys window 2>/dev/null | grep -iE "mCurrentFocus|mFocusedApp" | head -3 >&2
      # Is it even installed, and did it crash on the way up? The launcher on
      # screen with our activity never visible has exactly two explanations — the
      # package is not there, or it died after starting — and both are readable
      # from the device in one line each. Asking is the difference between a run
      # that names the cause and a run that ends with a screenshot of someone
      # else's home screen, which is what the last three did.
      echo "ABORT: install/state —" >&2
      adb shell pm list packages "$PKG" >&2
      adb shell dumpsys package "$PKG" 2>/dev/null | grep -m2 -E "versionName|codePath" >&2
      echo "ABORT: crash buffer —" >&2
      # The exception CLASS and MESSAGE are the answer, and they sit at the TOP of
      # a FATAL block: `tail -40` kept the bottom of the stack and threw the answer
      # away, so the run that named `NoSuchMethodException: MailboxViewModel.<init>
      # [class android.app.Application]` had to be dug out of the raw log by hand
      # afterwards. Grab the block by its header and keep enough of it to read.
      adb logcat -d -b crash 2>/dev/null | grep -A24 "FATAL EXCEPTION" | tail -80 >&2
      echo "ABORT: last 25 lines of the main buffer for $PKG —" >&2
      adb logcat -d 2>/dev/null | grep -E "$PKG|AndroidRuntime|FATAL" | tail -25 >&2
      exit 1
    fi
  else
    MUST_MISS=0
  fi
}
must_tap()  { must "tap '$1'"  tap_text "$1"; }
must_type() { must "type into '$1'" type_into "$1" "$2"; }
# The composer's fields are named by testTag, not by a visible label, so the
# type_into path cannot find them. Same must-wrapper contract as must_type: a
# failure here is named, counted, and cannot silently empty the send.
must_type_tag() { must "type into field $1" type_into_tag "$1" "$2"; }
must_tap_low() { # tap the first node with this exact text below MINY px —
                 # dodges same-text chrome above (the CREATE ACCOUNT eyebrow)
  local pos
  pos="$(MINY="$2" wait_nodes "n.get('text','')=='$1'" | head -1)"
  must "tap low '$1'" tap_xy "$pos"
}

# must_shot <name> <predicate> — photograph a screen only once the screen is
# proven to be on display.
#
# Without this, a failed `wait_nodes` still ran `shot`: the drawer that would not
# close left the sort sheet and the search field unopenable, and the walk still
# wrote 69-mail-sort-filter.png and 67-mail-search.png — of the mailbox under a
# scrim. The PNG gate counts those as present, so a frame of the wrong screen
# satisfies a gate meant to prove the right one was reached. Presence of a file
# is not evidence of its content, which is the same lesson the snapshot host
# learned from eleven fake screenshots. A screen that cannot be proven is not
# photographed, and the final gate then names the missing PNG.
must_shot() {
  local name="$1" pred="$2"
  if wait_nodes "$pred" >/dev/null; then
    shot "$name"
  else
    echo "FAIL: $name not photographed — the screen was not on display" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

# must_shot_overlay <name> <predicate> — the same proof, for a sheet or the
# drawer: waits without ever gesturing, because the thing being waited on is a
# thing a gesture can dismiss.
must_shot_overlay() {
  local name="$1" pred="$2"
  if wait_overlay "$pred" >/dev/null; then
    shot "$name"
  else
    echo "FAIL: $name not photographed — the overlay is not on display" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

# must_shot_ime <name> — gate on the keyboard instead of on a node.
#
# The search field is not a destination: tapping it focuses the field on the home
# and raises the IME, so there is no new screen for a node predicate to name —
# and a node cannot prove the home was unobstructed anyway, because the drawer's
# scrim does not remove the nodes underneath it from the dump. That is exactly
# how a tap on 'Inbox' hit the box tab below the open drawer. Whether the
# keyboard came up is the one answer that distinguishes a focus that landed from
# a tap the scrim swallowed.
must_shot_ime() {
  local name="$1" i
  for i in 1 2 3 4 5 6; do
    keyboard_shown && { shot "$name"; return 0; }
    sleep 1
  done
  echo "FAIL: $name not photographed — nothing took keyboard focus" >&2
  FAILURES=$((FAILURES + 1))
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
# Both of these were `>/dev/null`. That is the silence that made eight runs of
# launcher screenshots undiagnosable from CI: `adb install` fails for a reason it
# prints (signature mismatch, missing file, ABI), and `am start -W` prints
# Status/Error for an activity that does not exist — and the walk then carried on
# as if the app were there, because nothing it checked could see that the install
# had never happened. Failures now stop the run and carry their own output.
if ! adb install -r "$APK" >/tmp/install.log 2>&1; then
  echo "FATAL: adb install of $APK failed:" >&2
  tr -d '\r' < /tmp/install.log >&2
  exit 1
fi
if ! adb shell am start -W -n "$PKG/.MainActivity" >/tmp/launch.log 2>&1; then
  echo "FATAL: launching $PKG/.MainActivity failed:" >&2
  tr -d '\r' < /tmp/launch.log >&2
  exit 1
fi
echo "  install ok — $(tr -d '\r' < /tmp/launch.log | grep -E 'Status|Activity' | tr '\n' ' ')"
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
sleep 3

echo "· mailbox — the live home, read-only"
# Three affordances are deliberately absent from this leg, and the reason is each
# one's own: Send writes real mail to a real address and cannot be taken back;
# permanent delete (`DELETE /message/:id`) is the one call with no undo, unlike
# trash; Report spam feeds the suppression list that would stop future CI mail
# reaching the account the next run signs up. Star and archive are reversible and
# would be fair game, but a fresh CI mailbox has no message to act on, so the
# populated states are photographed through the snapshot host instead.
# The signed-in home IS the mailbox now, so the walk's anchors are the mail
# chrome rather than the words the placeholder screen used to print. The account
# CI creates may or may not have a provisioned mailbox; whichever state the app
# shows is the state that gets photographed, and the frame is named for the
# screen, not for an outcome the run cannot control.
must_shot 60-mail-home "n.get('resource-id','').rsplit('/',1)[-1]=='mail-menu'"

# The one observation that would have caught the auth seam weeks earlier: the
# signed-in home either rendered a mailbox or said it could not. Both are
# photographed (that is the frame), but only one of them means the mailbox the
# person was promised exists — so the run summary names which one it saw, in the
# app's own words. Not a FAIL: a server that briefly refuses is a state the walk
# must still photograph. But it stops being invisible.
sleep 3
if [ -n "$(find_nodes "n.get('text','').startswith(\"Couldn't load\")" | head -1)" ]; then
  echo "  [live-mail] the signed-in home shows the load failure"
  sys_summary "* live mailbox read: **failed** — the signed-in home shows \`Couldn't load Inbox\`; the mail API refused or could not be reached"
else
  sys_summary "* live mailbox read: **answered** — the signed-in home rendered the mailbox"
fi
# The home's own chrome comes first, while nothing overlays it. The drawer is
# opened LAST and deliberately left open: the security leg enters through the
# drawer's own Security row, so no step has to dismiss it.
#
# That dismissal was the bug this order removes. A system BACK does not close a
# Material3 ModalNavigationDrawer — it pops the home route — and tapping a node
# called 'Inbox' was no better: the box tab underneath the scrim carries the
# same text, `head -1` took the first node in the dump rather than the one on
# top, the tap landed on the scrim, and the drawer stayed open. Everything after
# it then timed out against a covered screen: nineteen failures from one gesture.
must "sort sheet tap" tap_xy "$(wait_nodes "n.get('content-desc','')=='Sort and filter'" | head -1)"
must_shot_overlay 69-mail-sort-filter "n.get('resource-id','').rsplit('/',1)[-1]=='gm-sheets-sort'"
adb shell input keyevent KEYCODE_BACK
sleep 1
must "search field" tap_xy "$(wait_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-search'" | head -1)"
sleep 2
must_shot_ime 67-mail-search
adb shell input keyevent KEYCODE_BACK
sleep 1
must "drawer tap" tap_xy "$(wait_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-menu'" | head -1)"
# Proof the drawer is open, taken from its pinned footer rather than from a folder
# row. 'Pending approval' sits in the scrolling half, and with the account actions
# pinned below it that half is shorter than the list — on a 1080x2400 profile the
# row is genuinely off-screen, and uiautomator only reports what is visible.
# iOS can still assert on it because XCUITest resolves elements inside a scroller
# whether or not they are on screen; the two harnesses see different things, and
# the marker has to be one both can see.
must_shot_overlay 62-mail-drawer "n.get('resource-id','').rsplit('/',1)[-1]=='mail-drawer-Help & Support'"

echo "· security sector — every hub screen, read-only"
# Mirrors the iOS walk's security leg: open each hub row, photograph the
# screen, press system back. No destructive submits — no password change,
# no revoke, no sign-out-all — the account must survive the walk.
# (2FA on a fresh account: the hub's toggle routes into the ENABLE flow.)
# Account security is a drawer row now — the placeholder home's button is gone,
# and the drawer is already open from the frame above, so the walk continues in
# it rather than closing and reopening the same overlay. The row is in the
# drawer's pinned footer, which is why there is no swipe here any more: the
# account actions were taken out of the scrolling folder list precisely because
# on a short screen the last of them fell below the fold, unreachable by thumb.
must "security row" tap_xy "$(wait_overlay "n.get('resource-id','').rsplit('/',1)[-1]=='mail-drawer-Security'" | head -1)" 
must_shot 20-sec-hub-light "n.get('text','')=='Account Security'"
# hub_present — 0 on the hub, 1 definitely NOT on the hub, 2 could not tell.
# find_nodes prints nothing both when the node is absent AND when the
# uiautomator dump failed, and the first security leg read that second case as
# "the tap navigated", pressed BACK and popped the hub itself — derailing every
# row after it. A caller presses back on 1 only.
#
# "Absent" has a third meaning here, and two runs paid for it: 'Account Security'
# is the hub's header, uiautomator omits nodes scrolled off screen, and the hub
# keeps its scroll position when a row's screen is popped back to it. So after a
# few visits the header is gone, the check says "not on the hub" while the hub is
# plainly on screen (the timeout dumps prove it — they show the hub's own verdict
# chips), and the recovery then looks for the home's drawer handle and reports
# "no way back to the hub" FROM the hub. Scrolling to the top before concluding
# makes the predicate mean what it says, and costs one fling when it was wrong.
hub_present() {
  local out
  if out="$(find_nodes "n.get('text','')=='Account Security'")"; then
    [ -n "$out" ] && return 0
    sleep 2   # one retry: a dump can fail while a transition animates
    out="$(find_nodes "n.get('text','')=='Account Security'")" || return 2
    [ -n "$out" ] && return 0
  else
    sleep 2
    out="$(find_nodes "n.get('text','')=='Account Security'")" || return 2
    [ -n "$out" ] && return 0
  fi
  scroll_to_top
  if out="$(find_nodes "n.get('text','')=='Account Security'")"; then
    [ -n "$out" ] && return 0
    return 1
  fi
  return 2
}
# ensure_hub — get back to the security hub, however far the walk has drifted.
#
# The leg used to assume it was standing on the hub. When one step failed, its
# BACK popped the hub; the next BACK popped further still, and a run ended up
# searching hub rows on the mailbox home, then on the welcome screen, then under a
# pulled-down notification shade — six timeouts of two minutes each, while `shot`
# kept writing frames that made the artifact look complete. Re-entering through the
# drawer is the path a person takes, and it works from any of those places. If even
# that fails, the caller is told: a fast, specific failure beats twenty minutes of
# searching a screen that is not there.
ensure_hub() {
  hub_present && return 0
  echo "  [hub] not on the hub — re-entering through the drawer"
  local pos
  pos="$(wait_overlay "n.get('resource-id','').rsplit('/',1)[-1]=='mail-menu'" | head -1)"
  [ -n "$pos" ] || { echo "  [hub] no way back to the hub on this screen" >&2; return 1; }
  tap_xy "$pos" || return 1
  sleep 2
  pos="$(wait_overlay "n.get('resource-id','').rsplit('/',1)[-1]=='mail-drawer-Security'" | head -1)"
  [ -n "$pos" ] || return 1
  tap_xy "$pos" || return 1
  sleep 2
  hub_present
}
# sec_visit <hub row text> <shot name>: open a clickable hub row, photograph
# the screen, and press back ONLY when the tap truly navigated. A row that did
# not open, or a screen that cannot be read, fails the walk instead of guessing
# with a BACK that might pop the hub.
sec_visit() {
  if ! ensure_hub; then
    echo "FAIL: cannot reach the hub before '$1' — the tap is skipped, not guessed" >&2
    FAILURES=$((FAILURES + 1)); return 0
  fi
  must_tap "$1"; sleep 2; shot "$2"
  hub_present
  case $? in
    0) echo "FAIL: '$1' did not open — still on the hub" >&2; FAILURES=$((FAILURES + 1)) ;;
    1) adb shell input keyevent 4; sleep 1.5 ;;
    2) echo "FAIL: cannot read the screen after '$1'" >&2; FAILURES=$((FAILURES + 1)) ;;
  esac
}
# The Two-Step row's only interactive element is the Switch (Compose exposes it
# as a checkable node; the title text is NOT clickable), so tap the switch
# itself. Its content-desc is the precise handle — 'first checkable node' held
# only while the hub has exactly one switch, and testTag turned out NOT to reach
# the uiautomator dump as resource-id. Both stay as fallbacks.
#
# The hub re-fetches every time it reappears, and while that is in flight the
# switch is DISABLED, so a tap lands on nothing: verify the enable screen
# actually arrived and retry, rather than marching on blind.
#
# The read can also FAIL outright, and then no number of taps helps: the screen
# shows "We couldn't load your security settings" and the switch stays disabled by
# design, because an unknown verdict is never drawn as togglable. One runner did
# exactly this while the other two loaded fine — a transient read failure, not an
# app defect. So take the retry the screen itself offers, the same thing a person
# on that device would do, before deciding the step cannot be done.
open_2fa_flow() {
  local attempt pos
  for attempt in 1 2 3; do
    if [ -n "$(find_nodes "n.get('text','')=='We couldn\'t load your security settings.'")" ]; then
      echo "  [hub] security read failed — using the screen's own Try again"
      tap_text "Try again" || true
      sleep 3
      continue
    fi
    pos="$(find_nodes "n.get('content-desc','')=='two-step switch'" | head -1)"
    [ -n "$pos" ] || pos="$(find_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='sec-2fa-switch'" | head -1)"
    [ -n "$pos" ] || pos="$(find_nodes "n.get('checkable','')=='true'" | head -1)"
    [ -n "$pos" ] && tap_xy "$pos"
    sleep 2
    [ -n "$(find_nodes "n.get('text','')=='Enable Two-Step Verification'")" ] && return 0
    sleep 2   # the hub's fetch lands and re-enables the switch
  done
  return 1
}
must "open 2FA enable flow" open_2fa_flow
shot 21-sec-2fa-enable-light
# Back ONLY when the step actually left the hub. When it fails, the hub is still
# on screen, and a BACK there pops the hub itself — dropping the walk onto the
# mailbox home, where every following hub-row search then times out. One run spent
# twenty minutes on six of those and still photographed all its frames, because
# `shot` fires either way: the artifact looked complete while the walk was
# measuring the wrong screen the whole time.
hub_present
case $? in
  0) echo "  [hub] 2FA flow did not open and the hub is still up — no BACK" ;;
  1) adb shell input keyevent 4; sleep 1.5 ;;
  2) echo "FAIL: screen unreadable after the 2FA step" >&2; FAILURES=$((FAILURES + 1)) ;;
esac
sec_visit "Phone Number" 22-sec-phone-light
sec_visit "Change Password" 23-sec-change-password-light
sec_visit "Backup Codes" 24-sec-backup-codes-light
sec_visit "Active Sessions" 25-sec-sessions-light
sec_visit "Recent Security Activity" 26-sec-activity-light
sec_visit "Sign Out All Devices" 27-sec-signout-all-light
# The enable flow's first gate, photographed on navigation alone: switch →
# Continue → 'Confirm Your Password' → back out twice. Nothing is typed, so no
# action proof is minted and the factor stays off. (The per-session Revoke
# confirmation is NOT reachable: a fresh account has one session, it is this
# device, and the current device deliberately has no revoke affordance.)
must "reopen 2FA flow (gate leg)" open_2fa_flow
must_tap "Continue"
must "confirm-password gate" wait_nodes "n.get('text','')=='Confirm Your Password'" >/dev/null
shot 28-sec-confirm-password-light
adb shell input keyevent 4; sleep 1
adb shell input keyevent 4; sleep 1.5
must "hub after gate leg" wait_nodes "n.get('text','')=='Account Security'" >/dev/null
# back out of the hub to the signed-in home, where the restore leg starts
must "hub back tap" tap_xy "$(wait_nodes "n.get('content-desc','')=='Back'" | head -1)"
sleep 2
must "home after security" wait_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-menu'" >/dev/null

echo "· platform surfaces — observed at runtime, not compiled"
# Everything the §39 batch added is a hook into the system rather than a screen:
# a job the scheduler holds, a channel the notification manager holds, a
# permission the permission manager holds, a haptic the view accepted, a
# shortcut the launcher resolved. A photograph cannot show any of them, so until
# now none of them was OBSERVED anywhere — the honest limit the release notes
# carried. This leg asks each owning service what it actually holds, and asks the
# running app what it actually did, and writes the answer on the run page.
#
# What it proves is split deliberately from what it cannot. Where the answer is
# the app's, a wrong answer fails the walk. Where the answer depends on a server
# that is not answering (see PLATFORM-RUNTIME.md: the mail API refuses the
# session the account API accepts, so the poll's read is refused and the
# notification is never due), the run says which surface it could not observe
# and why, in the same place it says which ones it did. A gate that goes green by
# staying quiet about the second kind is the thing this leg exists to replace.

# ── 1. the schedule the system holds, not the constant the source holds ──
adb shell dumpsys jobscheduler > /tmp/jobs.txt 2>/dev/null || true
# The job id WorkManager holds for this package, read from the system's own
# dump. A function because it is asked twice: once to report what the system
# holds, and again immediately before forcing the job — the id is stable in
# practice but not guaranteed across an app restart, and forcing a stale id
# fails the leg for a reason that is not the app.
# Every scheduler job id the package holds, one per line. A LIST, not one id:
# WorkManager re-enqueues its periodic work under a NEW scheduler id when the
# process restarts (a run watched id 10192 vanish between two forces for this
# reason), the dump spells jobs differently across API levels (Job{...} on old
# releases, JOB #u0aNN/<id>: on 34+), and forcing a stale or misread id must
# never be able to fail a leg the app did not cause. The force loop simply
# tries every id the system currently holds; a wrong id is refused harmlessly.
poll_job_ids() {
  adb shell dumpsys jobscheduler > /tmp/jobs.txt 2>/dev/null || true
  # The package comes from the walk's own PKG, not a second literal: a rename
  # that updated one and not the other would make this leg report "no job" for
  # an app that has one.
  PKG="$PKG" python3 - <<'PY'
import os, re
pkg = os.environ["PKG"]
ids = []
def keep(n):
    if n and n not in ids:
        ids.append(n)
# Line-based, not one mega-regex: dumpsys jobscheduler shifts its record shape
# across API levels, but a line that names the package always carries the id in
# one of three stable places — the #u<user>a<app>/<id> JobInfo reference, the
# WorkManager tag .../<service>#<id>, or a {id @ ...} record header.
for line in open("/tmp/jobs.txt", encoding="utf-8", errors="ignore"):
    if pkg not in line:
        continue
    for m in re.finditer(r'#u\d+a\d+/(\d+)', line):
        keep(m.group(1))
    for m in re.finditer(r'tag=\*job\*/\S*' + re.escape(pkg) + r'\S*?#(\d+)', line):
        keep(m.group(1))
    for m in re.finditer(r'\{(\d+) @ ', line):
        keep(m.group(1))
print("\n".join(ids))
PY
}
JOBS="$(poll_job_ids)"
JOB="$(echo "$JOBS" | head -1)"
if [ -n "$JOBS" ]; then
  echo "  [jobscheduler] $PKG holds job id(s): $(echo $JOBS | tr '\n' ' ')"
  grep -m1 "$PKG" /tmp/jobs.txt | sed 's/^/      /' || true
else
  echo "FAIL: the periodic new-mail job is not in the system's job store" >&2
  FAILURES=$((FAILURES + 1))
  grep -n "$PKG" /tmp/jobs.txt | head -8 | sed 's/^/      /' >&2
fi

# ── 2. the channel, as the notification manager holds it ──
adb shell dumpsys notification --noredact > /tmp/notif.txt 2>/dev/null \
  || adb shell dumpsys notification > /tmp/notif.txt 2>/dev/null || true
if grep -q "grapmail-new-mail" /tmp/notif.txt; then
  echo "  [notification] channel grapmail-new-mail exists in the system"
else
  echo "FAIL: the new-mail channel was never created in the system" >&2
  FAILURES=$((FAILURES + 1))
fi

# ── 3. offline, read from the device ──
# The banner's whole claim is that it comes from a callback the OS fires. With
# the radio off there is nothing to fake it with: either the node appears or the
# callback never ran. Restored immediately — every later leg needs the network.
#
# Airplane mode is not guaranteed to remove the network here. The AVD's default
# route is a virtual ETHERNET interface, which airplane mode does not touch, so
# "the callback never fired" can be the environment rather than the app. The
# system is asked whether a default network survived; if it did, the leg reports
# that it could not force the state instead of failing the app for it.
adb shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || true
sleep 2
STILL_ONLINE=""
# Two signals, because the dump's wording is not a documented contract: the
# system's own idea of a default network, and whether a packet actually leaves.
# Either one still answering means the state could not be forced.
if adb shell dumpsys connectivity 2>/dev/null | grep -q "Active default network"; then
  STILL_ONLINE=1
elif adb shell ping -c 1 -W 2 8.8.8.8 >/dev/null 2>&1; then
  STILL_ONLINE=1
fi
OFF_NODE=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  OFF_NODE="$(find_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-offline'" | head -1)"
  [ -n "$OFF_NODE" ] && break
  sleep 1
done
if [ -n "$OFF_NODE" ]; then
  shot 123-platform-offline
  echo "  [connectivity] airplane mode on -> the offline banner drew"
elif [ -n "$STILL_ONLINE" ]; then
  echo "  [connectivity] the system kept a default network — the offline banner is not provable on this device"
  sys_summary "* offline claim: **not observed** — airplane mode was set but the system still reports a default network, so the callback had nothing to lose. Not a failure; see PLATFORM-RUNTIME.md."
else
  echo "FAIL: radio off, and no offline banner appeared" >&2
  FAILURES=$((FAILURES + 1))
  adb shell dumpsys connectivity 2>/dev/null | grep -m4 -iE "airplane|AgentState|tether" | sed 's/^/      /' >&2
fi
adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
sleep 4
if [ -n "$OFF_NODE" ] && [ -n "$(find_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-offline'" | head -1)" ]; then
  # The other half of the claim: a banner that never leaves is a decoration, not
  # a reading. This is the check that would catch a callback registered once and
  # never updated. Only meaningful when the banner was actually observed.
  echo "FAIL: the offline banner is still up with the radio restored" >&2
  FAILURES=$((FAILURES + 1))
else
  echo "  [connectivity] radio restored -> the banner went away"
fi

# ── 4. the shortcut the launcher resolves, delivered as the launcher delivers it ──
# The action is read out of the file that declares it, so this leg cannot drift
# from the manifest: a renamed action in shortcuts.xml changes the intent the
# walk sends, and the app either still routes it or the leg goes red.
SHORTCUT_ACTION="$(python3 - <<'PY'
import re, pathlib
p = pathlib.Path("android/app/src/main/res/xml/shortcuts.xml")
m = re.search(r'android:action="([^"]+)"', p.read_text(encoding="utf-8")) if p.exists() else None
print(m.group(1) if m else "")
PY
)"
if [ -z "$SHORTCUT_ACTION" ]; then
  echo "FAIL: shortcuts.xml declares no intent action to deliver" >&2
  FAILURES=$((FAILURES + 1))
else
  echo "  [shortcut] declared action: $SHORTCUT_ACTION"
  # What the system parsed out of the declaration, in the system's own words.
  adb shell dumpsys shortcut 2>/dev/null | grep -i -m6 "compose\|shortcutinfo\|$PKG" \
    | sed 's/^/      /' || true
  log_reset
  adb shell am start -W -a "$SHORTCUT_ACTION" -n "$PKG/.MainActivity" >/tmp/shortcut.log 2>&1 \
    || { echo "FAIL: the shortcut intent did not start the activity" >&2; cat /tmp/shortcut.log >&2; FAILURES=$((FAILURES + 1)); }
  if wait_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-to'" >/dev/null; then
    shot 122-platform-compose-shortcut
    echo "  [shortcut] the launcher's intent opened the composer"
  else
    echo "FAIL: the shortcut intent arrived and no composer was drawn" >&2
    FAILURES=$((FAILURES + 1))
  fi
fi

# The composer the shortcut opened is not where §5 begins: the send leg starts
# from the home chrome and taps the compose FAB. Return to home the same way
# every other leg does, bounded, and let the next step fail with its own message
# if the screen never comes back.
for _ in 1 2 3; do
  [ -n "$(find_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-menu'" | head -1)" ] && break
  adb shell input keyevent 4
  sleep 1
done

# ── 5. a real write, and the feel that has to agree with it ──
# The haptic is tied to the server's answer rather than to the tap, which is the
# design decision §39 pinned. This checks it against the answer instead of the
# source: whatever the receipt says happened, the event that played must be the
# one that agrees with it. Both branches are acceptable; a mismatch, or a write
# that played nothing at all, is the failure.
#
# One deterministic DEBUG-build request before the send, for one reason: the
# worker's first run only stores the count it compares against, so without a
# baseline the §7 read can never announce anything. Android's `jobscheduler
# run -f` starts WorkManager's wrapper but WorkManager can still decline a
# not-yet-due periodic worker; the debug-only receiver enqueues this SAME
# MailPollWorker as one-time work. It does not exist in the release manifest.
log_reset
adb shell am broadcast \
  -a com.grapsee.grapmail.DEBUG_RUN_POLL \
  -n "$PKG/.DebugPollReceiver" >/tmp/polltrigger.log 2>&1 || true
if log_wait "GrapMailPoll:" 45 >/dev/null 2>&1; then
  echo "  [poll] baseline stored before the send"
else
  echo "  [poll] no baseline line — the first-read store did not report (continuing)"
  echo "      debug trigger answered: $(tail -2 /tmp/polltrigger.log 2>/dev/null | tr '\n' ' ')" >&2
fi
must "compose tap" tap_xy "$(wait_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-compose'" | head -1)"
if wait_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-to'" >/dev/null; then
  must_type_tag "mail-to" "$HANDLE@grapsee.com"
  must_type_tag "mail-subject" "platform runtime proof"
  must_type_tag "mail-body-field" "One message sent by the CI walk to this account own address, to raise the unread count."
  log_reset
  must "send tap" tap_text "Send"
  RECEIPT=""
  for line in "Delivered" "Sent" "Queued for delivery" "Not delivered"; do
    if [ -n "$(wait_nodes "n.get('text','')=='$line'" 6 2>/dev/null | head -1)" ]; then RECEIPT="$line"; break; fi
  done
  if [ -z "$RECEIPT" ]; then
    # A status word the app has no headline for still names itself on screen —
    # "Server reported: <word>" — and the walk reads that word rather than
    # reporting nothing.
    if [ -n "$(find_nodes "'Server reported:' in n.get('text','')" | head -1)" ]; then
      RECEIPT="Server reported"
    fi
  fi
  PLAYED="$(log_wait "GrapMailHaptics" 12 || true)"
  shot 125-platform-send-receipt
  if [ -z "$RECEIPT" ]; then
    echo "FAIL: the send produced no receipt at all" >&2
    FAILURES=$((FAILURES + 1))
  elif [ -z "$PLAYED" ]; then
    echo "FAIL: '$RECEIPT' and no haptic was played for it" >&2
    FAILURES=$((FAILURES + 1))
  else
    echo "  [haptics] $PLAYED"
    case "$RECEIPT" in
      "Not delivered")
        echo "$PLAYED" | grep -q "played Failure" || {
          echo "FAIL: the server refused the send and the played event was not Failure" >&2
          FAILURES=$((FAILURES + 1)); } ;;
      *)
        echo "$PLAYED" | grep -q "played Success" || {
          echo "FAIL: the server accepted the send and the played event was not Success" >&2
          FAILURES=$((FAILURES + 1)); } ;;
    esac
  fi
  adb shell input keyevent KEYCODE_BACK
  sleep 1
  adb shell input keyevent KEYCODE_BACK
  sleep 1
else
  echo "FAIL: the compose FAB did not open the composer" >&2
  FAILURES=$((FAILURES + 1))
fi

# ── 6. the permission, asked through the app's own row ──
# The row is the only route to the prompt: the app never asks at launch. So the
# prompt appearing IS the proof the row is wired, and the row reading "On"
# afterwards is the proof it re-reads the system rather than remembering the tap.
#
# Small screens first: the send receipt is a dialog there, so the send leg's
# two BACKs can exit the app to the launcher — one BACK past home. The walk
# recovers the way a person does (bring the app back) before requiring the
# chrome; on wider screens the app is already home and this is a no-op.
for _ in 1 2 3 4 5 6; do
  [ -n "$(find_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-menu'" | head -1)" ] && break
  ensure_foreground || true
  sleep 2
done
must "home before settings" wait_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-menu'" >/dev/null
must "drawer for settings" tap_xy "$(wait_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-menu'" | head -1)"
must "settings row" wait_overlay "n.get('resource-id','').rsplit('/',1)[-1]=='mail-drawer-Settings'" >/dev/null
must "settings tap" tap_xy "$(wait_overlay "n.get('resource-id','').rsplit('/',1)[-1]=='mail-drawer-Settings'" | head -1)"
# The drawer's Settings opens the settings hub (MailSettingsScreen), and the
# notification row sits at the BOTTOM of its long scrolling column — below
# Account security, Screener, Labels, Mail settings, Addresses, Connected
# accounts. A uiautomator dump only holds composed nodes, so the walk scrolls
# the way a person does until the row composes, bounded so a missing row still
# fails with its own message.
NOTIFY_ROW="n.get('text','')=='New mail notifications'"
SCR_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | cut -dx -f1)"
SCR_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | cut -dx -f2)"
SCR_W="${SCR_W:-1080}"; SCR_H="${SCR_H:-2000}"
for _ in 1 2 3 4 5 6 7 8; do
  # find_nodes, not wait_overlay: an expired wait_overlay writes a timeout dump,
  # and a poll that succeeds on swipe 4 would still have left four dumps behind
  # for the final evidence check to count as failures.
  [ -n "$(find_nodes "$NOTIFY_ROW" | head -1)" ] && break
  adb shell input swipe "$((SCR_W / 2))" "$((SCR_H * 72 / 100))" "$((SCR_W / 2))" "$((SCR_H * 30 / 100))" 300
  sleep 1
done
if [ -z "$(wait_overlay "$NOTIFY_ROW" 2>/dev/null | head -1)" ]; then
  echo "FAIL: the settings screen shows no new-mail row" >&2
  FAILURES=$((FAILURES + 1))
else
  shot 120-platform-notify-ask
  must "notify row tap" tap_text "New mail notifications"
  # The system's own dialog, from the app's tap. If the grant was already
  # answered the system will not show it again, and the row says so — which is
  # the branch the row's code takes, not a walk fallback.
  if [ -n "$(wait_overlay "n.get('text','')=='Allow'" 2>/dev/null | head -1)" ]; then
    must "allow the notification prompt" tap_text "Allow"
    sleep 2
  else
    echo "  [notifications] no prompt offered — the system already holds an answer"
  fi
  NOTIFY_ON="n.get('text','')=='On — GrapMail checks for new mail in the background'"
  if [ -n "$(wait_nodes "$NOTIFY_ON" 2>/dev/null | head -1)" ]; then
    shot 121-platform-notify-granted
    echo "  [notifications] the row re-read the system and reports On"
  else
    echo "FAIL: after the grant the new-mail row does not report On" >&2
    FAILURES=$((FAILURES + 1))
    adb shell dumpsys package "$PKG" 2>/dev/null | grep -m3 "POST_NOTIFICATIONS" | sed 's/^/      /' >&2
  fi
fi
adb shell input keyevent KEYCODE_BACK
sleep 1

# ── 7. the background read, through the exact worker the scheduler owns ──
# The real periodic schedule was already proven above by the JobScheduler store:
# Android holds SystemJobService for this package with connectivity + timing
# constraints. `jobscheduler run -f` only starts WorkManager's wrapper;
# WorkManager still enforces its own due-time and may finish that wrapper without
# invoking a periodic worker that is not due. Treating wrapper completion as
# proof of MailPollWorker was the defect.
#
# The debug APK carries a debug-source-set-only receiver that enqueues THIS SAME
# MailPollWorker as one-time work, exactly like the production FCM callback does.
# The receiver and manifest entry do not exist in the release variant; the
# release keeps only the real periodic request + push callback. CI asks the debug
# receiver and the worker's own GrapMailPoll line is the evidence.
JOBS="$(poll_job_ids)"
JOB="$(echo "$JOBS" | head -1)"
if [ -z "$JOBS" ]; then
  echo "FAIL: the periodic new-mail job disappeared from JobScheduler" >&2
  FAILURES=$((FAILURES + 1))
fi
log_reset
adb shell am broadcast \
  -a com.grapsee.grapmail.DEBUG_RUN_POLL \
  -n "$PKG/.DebugPollReceiver" >/tmp/polltrigger2.log 2>&1 || true
BRANCH="$(log_wait "GrapMailPoll:" 45 || true)"
if [ -n "$BRANCH" ]; then
  echo "  [poll] $BRANCH"
  case "$BRANCH" in
    *announced*)
      # The post, asked of the notification manager rather than trusted from
      # the call that made it.
      adb shell dumpsys notification --noredact 2>/dev/null > /tmp/notif2.txt || true
      if grep -q "NotificationRecord.*$PKG" /tmp/notif2.txt; then
        must "expand the shade" adb shell cmd statusbar expand-notifications
        sleep 2
        if [ -n "$(find_nodes "'new message' in n.get('text','')" | head -1)" ]; then
          shot 124-platform-notify-shade
          echo "  [notification] the shade shows the posted new-mail line"
          sys_summary "* new-mail notification: **observed** — the worker read rose, and the shade drew the posted line"
        else
          echo "FAIL: the notification was posted but the shade does not show it" >&2
          FAILURES=$((FAILURES + 1))
        fi
        adb shell cmd statusbar collapse-notifications >/dev/null 2>&1 || true
      else
        echo "FAIL: the poll announced a rise and the notification manager holds no record for $PKG" >&2
        FAILURES=$((FAILURES + 1))
      fi ;;
    *"first-read"*)
      sys_summary "* new-mail notification: **not due** — the worker's first run stores its comparison baseline"
      ;;
    *"no-rise"*)
      sys_summary "* new-mail notification: **not due** — the unread count did not rise"
      ;;
    *"read-refused"*)
      echo "  [notification] not provable this run: $(printf '%s' "$BRANCH" | sed 's/.*GrapMailPoll *: //')"
      sys_summary "* new-mail notification: **not observed** — the worker ran and \`GET /stats\` was refused ($(printf '%s' "$BRANCH" | sed 's/.*status=//'))." ;;
    *)
      sys_summary "* background read: **observed** — the worker reported \`$(printf '%s' "$BRANCH" | sed 's/.*GrapMailPoll *: //')\`"
      ;;
  esac
else
  echo "FAIL: the debug runtime trigger enqueued MailPollWorker and it never reported a branch" >&2
  echo "      trigger answered: $(tail -3 /tmp/polltrigger2.log 2>/dev/null | tr '\n' ' ')" >&2
  adb logcat -d 2>/dev/null | grep -E "GrapMailPoll|GrapMailPollTrigger|WM-SystemJobService|WM-WorkerWrapper" | tail -16 | sed 's/^/      /' >&2
  adb shell dumpsys jobscheduler > "$OUT/jobsdump.txt" 2>/dev/null || true
  FAILURES=$((FAILURES + 1))
  sys_summary "* background read: NOT observed — debug trigger enqueued \`MailPollWorker\` and it said nothing"
fi

# One line on the run page for the surfaces this mechanism always settles.
sys_summary "* platform surfaces observed at runtime: periodic job ${JOB:-none} in Android's store, worker branch, notification channel, offline banner (radio toggled), shortcut intent, haptic tied to the send's answer, notification permission asked through the app's own row"


echo "· post-write confirmations — via the app's test-only snapshot hook"
# These screens exist only AFTER a write the walk must not perform: signing out
# everywhere ends the session the restore leg proves, and a password change
# breaks the sign-in leg. So the app renders one per launch, offline, with
# closures that do nothing. The label check proves the name was recognised — a
# typo must not ship a placeholder photographed as if it were the screen.
# Keep this list in sync with the when-block in SecuritySnapshotScreen.kt.
for spec in "two-factor-on:40-sec-two-factor-on" \
            "two-factor-off-form:41-sec-two-factor-off-form" \
            "two-factor-off-done:42-sec-two-factor-off-done" \
            "backup-codes-form:43-sec-backup-codes-form" \
            "backup-codes-saved:44-sec-backup-codes-saved" \
            "change-password:45-sec-change-password" \
            "password-changed:46-sec-password-changed" \
            "sign-out-all-form:47-sec-sign-out-all-form" \
            "sign-out-all-done:48-sec-sign-out-all-done" \
            "session-revoked:49-sec-session-revoked" \
            "session-revoked-suspicious:50-sec-session-revoked-suspicious" \
            "revoke-session-form:51-sec-revoke-session-form" \
            "revoke-session-confirm:52-sec-revoke-session-confirm" \
            "suspicious-alert:53-sec-alert-no-open-alert" \
            "secure-account:54-sec-secure-no-open-alert" \
            "mail-empty:63-mail-empty" \
            "mail-loading:64-mail-loading" \
            "mail-load-failed:65-mail-load-failed" \
            "mail-no-mailbox:66-mail-no-mailbox" \
            "mail-selection:68-mail-selection" \
            "mail-move-sheet:70-mail-move-sheet" \
            "mail-delete-sheet:71-mail-delete-sheet" \
            "mail-labels:72-mail-labels" \
            "mail-label-create:73-mail-label-create" \
            "mail-thread:74-mail-thread" \
            "mail-thread-encrypted:75-mail-thread-encrypted" \
            "mail-thread-images:116-mail-thread-images" \
            "mail-compose:76-mail-compose" \
            "mail-contacts:77-mail-contacts" \
            "mail-files:78-mail-files" \
            "mail-attachment-preview:79-mail-attachment-preview" \
            "mail-settings:80-mail-settings" \
            "mail-screener:81-mail-screener" \
            "mail-send-receipt:82-mail-send-receipt" \
            "mail-assign-label-sheet:83-mail-assign-label-sheet" \
            "mail-snooze-sheet:84-mail-snooze-sheet" \
            "mail-unsubscribe:87-mail-unsubscribe" \
            "mail-contact-detail:85-mail-contact-detail" \
            "mail-sender-sheet:86-mail-sender-sheet" \
            "account-profile:90-account-profile" \
            "account-privacy:91-account-privacy" \
            "mail-notes:92-mail-notes" \
            "mail-tasks:93-mail-tasks" \
            "account-connections:94-account-connections" \
            "mail-settings-prefs:95-mail-prefs" \
            "mail-addresses:96-mail-addresses" \
            "mail-support:97-mail-support" \
            "mail-adv-search:98-mail-adv-search" \
            "mail-undo:99-mail-undo" \
            "e2e-status-none:100-e2e-status-none" \
            "e2e-status-unlocked:101-e2e-status-unlocked" \
            "e2e-setup:102-e2e-setup" \
            "e2e-unlock:103-e2e-unlock" \
            "e2e-details:104-e2e-details" \
            "e2e-publish:105-e2e-publish" \
            "e2e-passphrase:106-e2e-passphrase" \
            "e2e-remove:107-e2e-remove" \
            "e2e-recipients:108-e2e-recipients" \
            "mail-subs-list:109-mail-subs-list" \
            "mail-subs-empty:110-mail-subs-empty" \
            "account-connection-detail:114-account-connection-detail" \
            "account-connection-unknown:115-account-connection-unknown"; do
  nm="${spec%%:*}"; pic="${spec##*:}"
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" --es gmSnapshot "$nm" >/dev/null
  sleep 5
  # The snapshot host must BE the screen on screen, not merely not have failed:
  # iOS shipped exactly that hole (a stored session pushed the signed-in home
  # over the stub and eleven wrong photographs passed). The host names itself
  # with content-desc 'gm-snapshot'; without it the shot proves nothing.
  if [ -z "$(find_nodes "n.get('content-desc','')=='gm-snapshot'")" ]; then
    echo "FAIL: snapshot '$nm' is not the visible screen" >&2; FAILURES=$((FAILURES + 1))
    # Consecutive, and fatal at five. A run whose app never reached the screen —
    # the notification-shade walk, where all twenty-three mailbox frames were the
    # same 117 KB SystemUI capture and 107 assertions timed out — spent its entire
    # two-hour ceiling discovering the same fact once per screen, and the ceiling
    # is what reported it. Five screens of the same photograph is not five
    # findings; it is one, and the walk should say so and stop while there is time
    # to read why.
    SNAP_MISS=$((SNAP_MISS + 1))
    if [ "$SNAP_MISS" -ge 5 ]; then
      echo "ABORT: $SNAP_MISS snapshot screens in a row were not the app." >&2
      echo "ABORT: the emulator is showing someone else's UI — check the foreground" >&2
      echo "ABORT:   (last frame: $nm); every later frame would prove the same thing." >&2
      exit 1
    fi
  else
    SNAP_MISS=0
  fi
  # The suspicious pair fetch on appear, so wait for the loaded branch or the
  # photograph catches "Checking your account…" and proves nothing. Each marker
  # is a label that exists ONLY once the fetch has settled.
  case "$nm" in
    suspicious-alert) settled="Address" ;;
    secure-account) settled="Suspicious Sign-In Details" ;;
    # The seeded screens read the cache and the view model through an effect, so
    # the photograph must wait for the content to land or it catches the empty
    # branch of a screen that has data one frame later.
    mail-thread|mail-thread-encrypted) settled="Q3 facilities invoice" ;;
    # The reader's images gate. The marker is the control's own tag, not a
    # sentence: it exists only once an HTML-only body has landed, stripped for
    # display, and had its `img` addresses read — which is the whole branch this
    # frame is here to photograph.
    mail-thread-images) settled="mail-show-images" ;;
    mail-contacts|mail-contact-detail) settled="amira@northwind.example" ;;
    mail-files|mail-attachment-preview) settled="invoice-q3.pdf" ;;
    mail-settings) settled="GrapMail address" ;;
    mail-screener) settled="Waiting in the queue" ;;
    # "Bills" is a seeded label NAME: it exists only once the labels have landed,
    # which is what makes it a marker. "Manage labels" is a drawer row and never
    # appears on this screen, so the wait would have been on nothing.
    mail-labels) settled="Bills" ;;
    mail-selection) settled="Delete" ;;
    # The four sheets render inside a ModalBottomSheet, which animates in. Without
    # a marker the frame can catch the scrim alone — and a scrim is not a sheet.
    # Each title is text that exists only once that sheet's content has composed.
    mail-move-sheet) settled="gm-sheets-move" ;;
    mail-delete-sheet) settled="gm-sheets-permanent" ;;
    mail-assign-label-sheet) settled="gm-sheets-label" ;;
    mail-snooze-sheet) settled="gm-sheets-snooze" ;;
    mail-unsubscribe) settled="gm-sheets-unsub" ;;
    mail-sender-sheet) settled="gm-sheets-sender" ;;
    # The account band: every marker is a SEEDED VALUE from `sampleProfile()`
    # (MailSnapshot.kt) — text that exists on screen only once the profile
    # document has landed in the model. A photograph of Loading… would prove
    # the frame shipped empty, which is the hole these waits exist to close.
    account-profile) settled="1993-04-12" ;;
    account-privacy) settled="2026-03-14" ;;
    mail-notes) settled="Send the Q3 numbers before Friday" ;;
    mail-tasks) settled="Book the studio visit" ;;
    account-connections) settled="Northwind SSO" ;;
    mail-settings-prefs) settled="Sent with GrapMail" ;;
    mail-addresses) settled="business@grapmail.com" ;;
    # Support is the one frame whose content does not depend on the document:
    # its questions are the web page's copy, and the marker proves the screen
    # composed rather than the fetch settled.
    mail-support) settled="Why can I not create an account for someone under 13?" ;;
    # The Phase-2 frames: the sheet's title proves the sheet composed (sheets
    # animate in -- a scrim is not a sheet), and the banner's note is the exact
    # text the seeded filing state produces.
    mail-adv-search) settled="Search filters" ;;
    mail-undo) settled="Archived 2 messages" ;;
    # The compose frame now waits: the profile seed lands through an effect, and
    # the signature the frame exists to prove arrives with it. Photographed a
    # frame early, compose looks unsigned and the feature ships unseen. The
    # marker is a testTag, not the signature text -- an EditText's uiautomator
    # text is the WHOLE body value, so an exact-match wait on "Sent with
    # GrapMail" could never fire against "-- \nSent with GrapMail". (iOS seeds
    # in init -- synchronous -- so its compose frame needs no wait.)
    mail-compose) settled="gm-signed-body" ;;
    # The encryption band: most markers are a seeded state's sentence -- text
    # that exists only once the identity has landed in the model. The two
    # status frames wait on their own headline because the headline IS the
    # state claim the sector is built on. `e2e-remove` is the exception, like
    # `mail-support`: its warning is the screen's static copy and the marker
    # proves the screen composed, not a fetch settled.
    e2e-status-none) settled="Encryption is not set up on this device" ;;
    e2e-status-unlocked) settled="Your key is unlocked for this session" ;;
    e2e-setup) settled="There is no way to recover a forgotten passphrase." ;;
    e2e-unlock) settled="Your passphrase decrypts the key on this device. It is never sent anywhere." ;;
    e2e-details) settled="Signing key" ;; # design batch reflowed the fingerprint into grouped lines; the exact-match wait now names the row label only the details screen draws (the block itself is proven by the photograph and the §27 seed pins)
    e2e-publish) settled="Published fingerprint" ;;
    # The headline, not the lede: iOS's harness caps string identifiers at
    # 128 characters and the full re-lock sentence is longer, so both walks
    # wait on the same shorter text this screen alone draws.
    e2e-passphrase) settled="Change your passphrase" ;;
    e2e-remove) settled="Remove from this device" ;;
    e2e-recipients) settled="priya@havenworks.example" ;;
    # The subscriptions band. The list frame waits on a seeded sender's NAME
    # -- text that exists only once the cache page has landed and detection
    # has grouped it; the empty frame waits on the screen's own no-mail
    # sentence over the honestly-empty seeded read.
    # The frequency label, not the sender name: a row's name carries
    # maxLines=1, and a truncated element's text is no longer the seed string.
    # The label has no line limit, and it exists only once the cache page
    # landed AND detection grouped it.
    mail-subs-list) settled="10 or fewer emails recently" ;;
    mail-subs-empty) settled="No subscriptions detected." ;;
    # The per-app detail: the linked frame waits on the web's full sharing
    # sentence (text only the recorded scopes draw), the unlinked frame on the
    # screen's own "does not have access" answer over the same seeded record.
    account-connection-detail) settled="Your name, GrapMail address and profile photo" ;;
    # The subtitle, not the card paragraph: walk waits match a text EXACTLY,
    # so a marker can never be a fragment of a sentence that wraps across
    # literals (CI: 115 waited 27 minutes for a clause of a wrapped Text).
    account-connection-unknown) settled="Not connected to this account" ;;
    *) settled="" ;;
  esac
  # A marker may name a visible string OR a resource-id. Text is right for
  # content that exists only once data has landed (a seeded label's NAME); an
  # identifier is right for a screen's own identity, which copy must not be able
  # to break. Accepting both keeps one mechanism instead of two divergent ones.
  marker() { find_nodes "n.get('text','')=='$1' or n.get('resource-id','').rsplit('/',1)[-1]=='$1'" | head -1; }
  if [ -n "$settled" ] && [ -z "$(marker "$settled")" ]; then
    # Bounded retries, not one: the snapshot activity cold-starts into a host
    # that has been alive for half an hour, and a compose + fetch pass can
    # legitimately miss a 4s window. Five retries ≈ 25s of patience; past that
    # the screen genuinely did not settle and the failure is real.
    for _ in 1 2 3 4 5; do
      sleep 4
      [ -n "$(marker "$settled")" ] && break
    done
    if [ -z "$(marker "$settled")" ]; then
      echo "FAIL: snapshot '$nm' never finished loading" >&2; FAILURES=$((FAILURES + 1))
    fi
  fi
  # The mail host names its own miss. Without this a typo in a mail-* name
  # would photograph the fallthrough label and still fill the PNG gate.
  if [ -n "$(find_nodes "n.get('text','')=='Unknown mail snapshot: $nm'")" ]; then
    echo "FAIL: app did not recognise mail snapshot '$nm'" >&2; FAILURES=$((FAILURES + 1))
  fi
  if [ -n "$(find_nodes "n.get('text','')=='Unknown snapshot: $nm'")" ]; then
    echo "FAIL: app did not recognise snapshot '$nm'" >&2; FAILURES=$((FAILURES + 1))
  fi
  # The reader's three reply doors must ALL be on screen. `OutlineButton` fills
  # its width by default, and a Row measures each unweighted child against the
  # space that is LEFT — so the first took the row and "Reply all" and "Forward"
  # were drawn at zero width. A photograph of the reader missing two of its
  # actions still filled every count CI checks, which is why this is asserted
  # and not merely looked at.
  #
  # Matched on text OR content-description: Compose merges a button's label into
  # the button's own semantics node, and which attribute uiautomator carries it
  # in depends on the merge — an assertion that reads only one of the two can
  # fail on a screen that shows the control, and that is how checks get deleted.
  if [ "$nm" = "mail-thread" ]; then
    for door in "Reply all" "Forward"; do
      if [ -z "$(find_nodes "n.get('text','')=='$door' or n.get('content-desc','')=='$door'")" ]; then
        echo "FAIL: the reader drew no '$door' button" >&2
        FAILURES=$((FAILURES + 1))
      fi
    done
  fi
  shot "$pic"
done

echo "· session restore: restart must open signed-in, not at welcome"
adb shell am force-stop "$PKG"
sleep 1
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 6
shot 10-session-restored
# assert, don't tap: the home screen must show the signed-in state
if wait_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-menu'" >/dev/null; then
  echo "  ✓ session restored (the mailbox is the signed-in home)"
else
  echo "FAIL: restart did not open signed-in" >&2
  FAILURES=$((FAILURES + 1))
fi
# Sign out lives in the mailbox drawer now, so the walk opens the drawer and
# taps it there. This is the leg that ends the session, which is why it runs
# after the restore proof.
must "drawer for sign-out" tap_xy "$(wait_nodes "n.get('resource-id','').rsplit('/',1)[-1]=='mail-menu'" | head -1)"
must "drawer open (sign-out)" wait_overlay "n.get('resource-id','').rsplit('/',1)[-1]=='mail-drawer-Help & Support'" >/dev/null
# No swipe: Sign out is in the drawer's pinned footer, inside the drawer's own
# bounds on every screen profile. The walk still waits for the row before
# tapping it, so if it ever stops being reachable this fails as one line naming
# the row rather than as a timeout on whatever comes next.
must "sign-out row" wait_overlay "n.get('resource-id','').rsplit('/',1)[-1]=='sign-out'" >/dev/null
must "sign-out tap" tap_xy "$(wait_overlay "n.get('resource-id','').rsplit('/',1)[-1]=='sign-out'" | head -1)"
sleep 2

echo "· sign-in screen"
must_tap "Sign In"
sleep 2
shot 11-signin-light

echo "· recovery arc — navigation only, nothing submitted"
# 'Forgot password?' opens the reset form, whose own link opens the address
# lookup. Both are reference screens the walk had never photographed, and
# reaching them sends no code and changes no state — the forms are entered and
# left empty. Each tap is failure-gated, so a missing link fails the run.
must_tap "Forgot password?"
must "reset form" wait_nodes "n.get('text','')=='Reset your password'" >/dev/null
shot 30-recovery-start-light
must_tap "Find your GrapMail address"
sleep 2
shot 31-recovery-find-address-light

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

# The mailbox in dark, from the snapshot host: the night flip is already on, so
# this is the same frame the sector's light photograph with the palette resolved
# through the dark branch — the design review needs both, and neither depends on
# a provisioned mailbox.
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --es gmSnapshot "mail-home" >/dev/null
sleep 5
# The dark home carries the SAME two proofs its light sibling and the other two
# dark frames do. `gm-snapshot` alone is not a proof: the host's own "Unknown mail
# snapshot" fallthrough bears that marker, so a mistyped name photographs a
# placeholder and still fills the PNG gate below — the exact failure the marker
# rule exists to prevent, re-imported through a new code path. The content marker
# is a seeded row subject, because the Android snapshot's `listFrame` draws the
# greeting and the rows only: the `mail-search` field iOS waits on for this frame
# does not exist in this host's copy of the screen, and a wait on it would have
# timed out for a reason that had nothing to do with dark mode.
if [ -n "$(find_nodes "n.get('text','')=='Unknown mail snapshot: mail-home'")" ]; then
  echo "FAIL: app did not recognise mail snapshot 'mail-home'" >&2
  FAILURES=$((FAILURES + 1))
fi
if [ -z "$(wait_overlay "n.get('text','')=='Q3 facilities invoice'" | head -1)" ]; then
  echo "FAIL: 61-mail-home-dark not photographed -- the home never showed its rows" >&2
  FAILURES=$((FAILURES + 1))
else
  shot 61-mail-home-dark
fi

# Two more mail screens in dark, chosen for what they cover rather than for
# coverage's sake: the thread carries the body surface, the attachment row, the
# encrypted notice and now the unsubscribe button, and the labels screen is the one
# that draws the server's per-label colour — so these two exercise the largest part
# of the palette that a flat list of rows does not. Every other colour in the mail
# band resolves through the same two token tables, which `scripts/mail-honesty.py`
# pins structurally: the claim "dark mode works for all 30 frames" is carried by one
# check on the tokens plus three photographs, not by thirty relaunches on a runner
# that already takes ninety minutes.
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --es gmSnapshot "mail-thread" >/dev/null
# The dark frames carry the SAME two proofs the light ones do. `gm-snapshot` alone
# is not enough: it is present on the host's own "Unknown mail snapshot" fallthrough,
# so a mistyped name would photograph a placeholder and still fill the PNG gate --
# the exact failure the marker rule exists to prevent, re-imported through a new
# code path. The content marker is the same string the light pass waits on, so the
# two artifacts prove the same thing about the same screen in two palettes.
sleep 5
if [ -n "$(find_nodes "n.get('text','')=='Unknown mail snapshot: mail-thread'")" ]; then
  echo "FAIL: app did not recognise mail snapshot 'mail-thread'" >&2
  FAILURES=$((FAILURES + 1))
fi
if [ -z "$(wait_overlay "n.get('text','')=='Q3 facilities invoice'" | head -1)" ]; then
  echo "FAIL: 88-mail-thread-dark not photographed -- the thread never showed its body" >&2
  FAILURES=$((FAILURES + 1))
else
  shot 88-mail-thread-dark
fi
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --es gmSnapshot "mail-labels" >/dev/null
sleep 5
if [ -n "$(find_nodes "n.get('text','')=='Unknown mail snapshot: mail-labels'")" ]; then
  echo "FAIL: app did not recognise mail snapshot 'mail-labels'" >&2
  FAILURES=$((FAILURES + 1))
fi
if [ -z "$(wait_overlay "n.get('text','')=='Bills'" | head -1)" ]; then
  echo "FAIL: 89-mail-labels-dark not photographed -- the labels never showed" >&2
  FAILURES=$((FAILURES + 1))
else
  shot 89-mail-labels-dark
fi
adb shell am force-stop "$PKG"

# One dark frame for each band added after the 30-frame palette check was
# written -- the plan's Phase-6 rule ("every new band gets a dark frame with
# both proofs"). Same two proofs as the light passes (the host's negative
# name-check, then the settled content the seeded state alone draws), same
# reuse of the existing snapshot names: a `-dark` host case would be a second
# copy of a screen, which §19 refuses. The account band carries the form
# surfaces, the encryption band the state card, subscriptions the row list.
for darkspec in "account-profile:Rather not say:111-account-profile-dark" \
                "e2e-status-unlocked:Your key is unlocked for this session:112-e2e-status-unlocked-dark" \
                "mail-subs-list:10 or fewer emails recently:113-mail-subs-list-dark"; do
  nm="${darkspec%%:*}"; rest="${darkspec#*:}"; marker="${rest%%:*}"; pic="${rest##*:}"
  adb shell am start -W -n "$PKG/.MainActivity" --es gmSnapshot "$nm" >/dev/null
  sleep 5
  if [ -n "$(find_nodes "n.get('text','')=='Unknown mail snapshot: $nm'")" ]; then
    echo "FAIL: app did not recognise dark snapshot '$nm'" >&2
    FAILURES=$((FAILURES + 1))
  fi
  if [ -z "$(wait_overlay "n.get('text','')=='$marker'" | head -1)" ]; then
    echo "FAIL: $pic not photographed -- '$marker' never drew in dark" >&2
    FAILURES=$((FAILURES + 1))
  else
    shot "$pic"
  fi
  adb shell am force-stop "$PKG"
done
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
            20-sec-hub-light 21-sec-2fa-enable-light 22-sec-phone-light \
            23-sec-change-password-light 24-sec-backup-codes-light \
            25-sec-sessions-light 26-sec-activity-light \
            27-sec-signout-all-light 28-sec-confirm-password-light \
            30-recovery-start-light 31-recovery-find-address-light \
            40-sec-two-factor-on 41-sec-two-factor-off-form \
            42-sec-two-factor-off-done 43-sec-backup-codes-form \
            44-sec-backup-codes-saved 45-sec-change-password \
            46-sec-password-changed 47-sec-sign-out-all-form \
            48-sec-sign-out-all-done 49-sec-session-revoked \
            50-sec-session-revoked-suspicious \
            51-sec-revoke-session-form 52-sec-revoke-session-confirm \
            53-sec-alert-no-open-alert 54-sec-secure-no-open-alert \
            10-session-restored 11-signin-light 12-welcome-dark 13-step1-name-dark \
            60-mail-home 61-mail-home-dark 62-mail-drawer 63-mail-empty \
            64-mail-loading 65-mail-load-failed 66-mail-no-mailbox \
            67-mail-search 68-mail-selection 69-mail-sort-filter \
            70-mail-move-sheet 71-mail-delete-sheet 72-mail-labels \
            73-mail-label-create 74-mail-thread 75-mail-thread-encrypted \
            76-mail-compose 77-mail-contacts 78-mail-files \
            79-mail-attachment-preview 80-mail-settings 81-mail-screener \
            82-mail-send-receipt 83-mail-assign-label-sheet \
            84-mail-snooze-sheet 85-mail-contact-detail \
            86-mail-sender-sheet 87-mail-unsubscribe \
            88-mail-thread-dark 89-mail-labels-dark \
            90-account-profile 91-account-privacy 92-mail-notes \
            93-mail-tasks 94-account-connections 95-mail-prefs \
            96-mail-addresses 97-mail-support 98-mail-adv-search \
            99-mail-undo 100-e2e-status-none 101-e2e-status-unlocked \
            102-e2e-setup 103-e2e-unlock 104-e2e-details 105-e2e-publish \
            106-e2e-passphrase 107-e2e-remove 108-e2e-recipients \
            109-mail-subs-list 110-mail-subs-empty \
            111-account-profile-dark 112-e2e-status-unlocked-dark \
            113-mail-subs-list-dark 114-account-connection-detail \
            115-account-connection-unknown 116-mail-thread-images; do
  [ -s "$OUT/$want.png" ] || { echo "FAIL: missing $want.png" >&2; FAILURES=$((FAILURES + 1)); }
done
if [ "$FAILURES" -gt 0 ]; then
  echo "── walk FAILED: $FAILURES problem(s) ──" >&2
  exit 1
fi
echo "── walk PASSED ──"
