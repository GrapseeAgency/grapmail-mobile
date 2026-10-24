#!/usr/bin/env bash
# Shared emulator-walk helpers — sourced by android-walk.sh and debug scripts.
# Requires: adb on PATH, OUT set to the output dir, PKG set to the package.


# Cold swiftshader boots occasionally ANR system_server; the dialog covers
# everything. Dismiss with "Wait" whenever present.
dismiss_anr() {
  W="$(find_nodes "n.get('text')=='Wait'" | head -1)"
  if [ -n "$W" ]; then
    echo "  [anr] dismissing system dialog"
    adb shell input tap $W
    sleep 3
  fi
}

ensure_foreground() {
  adb shell dumpsys activity activities 2>/dev/null | grep -q "topResumedActivity.*$PKG" || {
    adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
    sleep 4
  }
}

shot() {
  local i
  dismiss_anr
  ensure_foreground
  for i in 1 2 3; do
    adb exec-out screencap -p > "$OUT/$1.png"
    local size
    size=$(stat -c %s "$OUT/$1.png" 2>/dev/null || echo 0)
    [ "$size" -gt 40000 ] && break
    sleep 3   # blank capture: activity still recreating — wait and retry
  done
  echo "  ↳ $1.png ($(stat -c %s "$OUT/$1.png") bytes)"
}

dump_xml() {
  local i
  for i in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml "$OUT/.ui.xml" >/dev/null 2>&1
    [ -s "$OUT/.ui.xml" ] && return 0
    sleep 2   # known API-34 flake: dump times out under contention — retry
  done
  return 1
}

# ── node finders ────────────────────────────────────────────────────────────
# find_nodes <python-filter-expression on node dict>  → prints "x y" per match
find_nodes() {
  dump_xml || return 1
  python3 - "$OUT/.ui.xml" "$1" <<'PY' | tr -d '\r'
import re, sys, os
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
filt = sys.argv[2]
miny = int(os.environ.get("MINY", "0"))
for m in re.finditer(r'<node[^>]*/?>', xml):
    tag = m.group(0)
    attrs = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', tag))
    try:
        if eval(filt, {}, {"n": attrs}):
            b = attrs.get("bounds", "")
            bm = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
            if bm:
                y1 = int(bm.group(2))
                if y1 >= miny:
                    x = (int(bm.group(1)) + int(bm.group(3))) // 2
                    y = (y1 + int(bm.group(4))) // 2
                    print(x, y)
    except Exception:
        pass
PY
}

wait_nodes() { # wait_nodes <filter> [count] → prints first match
  local want="${2:-1}" pos=""
  for _ in $(seq 1 25); do
    pos="$(find_nodes "$1" | head -"$want" | tail -1)"
    [ -n "$pos" ] && { echo "$pos"; return 0; }
    sleep 1.5
  done
  echo "TIMEOUT: $1" >&2
  [ -f "$OUT/.ui.xml" ] && cp "$OUT/.ui.xml" "$OUT/timeout-$(date +%s%3N).xml"
  return 1
}


keyboard_shown() {
  adb shell dumpsys input_method 2>/dev/null | grep -q "mInputShown=true"
}

dismiss_keyboard() {
  keyboard_shown && { adb shell input keyevent 111; sleep 0.8; }
}

tap_xy() {
  [ -n "${1:-}" ] || return 1
  adb shell input tap $1
  sleep 1.2
}

tap_text() { # close the keyboard FIRST, then locate — coordinates captured
              # with the IME open go stale the moment it closes.
              # Exact text match wins ("Day" must not hit "Birthday (Required)",
              # which contains "day" and precedes it in document order);
              # substring match is the fallback for long labels.
  dismiss_keyboard
  sleep 0.6
  local pos
  pos="$(find_nodes "n.get('text','').lower()=='${1,,}' or n.get('content-desc','').lower()=='${1,,}'" | head -1)"
  [ -z "$pos" ] && pos="$(wait_nodes "'${1,,}'.lower() in (n.get('text','')+' '+n.get('content-desc','')).lower()")"
  [ -n "$pos" ] || return 1
  adb shell input tap $pos
  sleep 1.4
}

type_into() { # type_into <field label> <value> — the EditText node carries
              # the label as content-desc; the label TextView above matches
              # too, so prefer the field by class
  local pos
  pos="$(wait_nodes "'EditText' in n.get('class','') and (n.get('content-desc')=='$1' or n.get('text')=='$1')")" || pos="$(wait_nodes "n.get('content-desc')=='$1' or n.get('text')=='$1'")"
  [ -n "$pos" ] || return 1
  adb shell input tap $pos
  sleep 1.4
  adb shell input text "$2"
  sleep 0.7
  dismiss_keyboard
  sleep 0.5
}
