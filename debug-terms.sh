#!/usr/bin/env bash
# Focused debug: walk to step 5, then exercise the terms gate and agree card
# with explicit state dumps at each stage.
set -u
cd "$(dirname "$0")"
export OUT=/tmp/gmterms
export PKG=com.grapsee.grapmail
source ./scripts/android-walk-helpers.sh
mkdir -p "$OUT"

STAMP="$(date +%s)"; HANDLE="dbg${STAMP: -6}"
adb shell am force-stop $PKG
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 5

tap_text "Create Account"
type_into "First name" "Arafat" || exit 9
type_into "Last name" "Hossain" || exit 9
tap_text "Next"; sleep 1
# step 2: January / 1 / 2013 via geometry first-item picks
tap_text "Month"; sleep 1
MY=$(find_nodes "n.get('text','').lower()=='month'" | head -1 | awk '{print $2}')
adb shell input tap 240 $((MY + 120)); sleep 1
tap_text "Day"; sleep 1
DY=$(find_nodes "n.get('text','').lower()=='day'" | head -1 | awk '{print $2}')
adb shell input tap 480 $((DY + 120)); sleep 1
tap_text "Year"; sleep 1
YY=$(find_nodes "n.get('text','').lower()=='year'" | head -1 | awk '{print $2}')
adb shell input tap 780 $((YY + 120)); sleep 1
tap_text "Female"
tap_text "Next"; sleep 1
type_into "Choose your own address" "$HANDLE" || exit 9
sleep 3
tap_text "Next"; sleep 1
adb shell input swipe 540 1800 540 900 400; sleep 1
type_into "Password" "Grapmail%23dbg${STAMP: -4}" || exit 9
type_into "Confirm password" "Grapmail%23dbg${STAMP: -4}" || exit 9
tap_text "Next"; sleep 1

echo "=== step 5 reached; scroll the document ==="
adb shell input swipe 540 1800 540 900 400; sleep 1
TH=$(find_nodes "n.get('text','')=='GrapMail Terms of Service'" | head -1)
echo "heading at: $TH"
read -r HX HY <<< "$TH"
for i in 1 2 3 4; do
  adb shell input swipe 540 $((HY + 520)) 540 $((HY + 90)) 450; sleep 0.9
done
adb shell input swipe 540 1600 540 1000 400; sleep 1   # reveal agree card

echo "=== agree card + button state BEFORE tap ==="
dump_xml
python3 - "$OUT/.ui.xml" <<'PY'
import re
xml = open("/tmp/gmterms/.ui.xml", encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'<node[^>]*>', xml):
    a = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(0)))
    t = a.get('text') or ''
    if 'I agree' in t or t == 'Create Account':
        print(repr(t[:40]), '| enabled=', a.get('enabled'), '| clickable=', a.get('clickable'), '| bounds=', a.get('bounds'))
PY

AG=$(find_nodes "n.get('text','').startswith('I agree')" | head -1)
echo "tapping agree at: $AG"
read -r AX AY <<< "$AG"
adb shell input tap "$AX" "$AY"; sleep 1.5

echo "=== state AFTER agree tap ==="
dump_xml
python3 - "$OUT/.ui.xml" <<'PY'
import re
xml = open("/tmp/gmterms/.ui.xml", encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'<node[^>]*>', xml):
    a = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(0)))
    t = a.get('text') or ''
    if t == 'Create Account':
        print(repr(t), '| enabled=', a.get('enabled'), '| bounds=', a.get('bounds'))
PY
adb exec-out screencap -p > "$OUT/after-agree.png"
echo "saved $OUT/after-agree.png"
