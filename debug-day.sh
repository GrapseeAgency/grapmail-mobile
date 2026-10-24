#!/usr/bin/env bash
# Use the walk's own helpers to reach step 2 and open the Day menu.
set -u
cd "$(dirname "$0")"
export OUT=/tmp/gmdbg2
export PKG=com.grapsee.grapmail
source ./scripts/android-walk-helpers.sh

mkdir -p "$OUT"

adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 6

tap_text "Create Account"
type_into "First name" "Arafat"
type_into "Last name" "Hossain"
tap_text "Next"
sleep 2

echo "=== on step 2: tapping Day ==="
tap_text "Day"
sleep 2

echo "=== dump after Day tap ==="
dump_xml
python3 - "$OUT/.ui.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
nums = []
for m in re.finditer(r'<node[^>]*>', xml):
    t = m.group(0)
    tm = re.search(r'text="(\d+)"', t)
    bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
    if tm and bm:
        nums.append((tm.group(1), bm.group(0)))
print("numeric nodes on screen:", nums[:20])
PY
