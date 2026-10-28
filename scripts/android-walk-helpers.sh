#!/usr/bin/env bash
# Shared emulator-walk helpers — sourced by android-walk.sh and debug scripts.
# Requires: adb on PATH, OUT set to the output dir, PKG set to the package.

# ── viewport facts: the walk must pass on any phone/tablet size, so every
#    coordinate is derived from density and screen size, never hardcoded ──
SW=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -d x -f1 | tr -d '\r')
SH=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -d x -f2 | tr -d '\r')
DENS=$(adb shell wm density | grep -oE '[0-9]+' | tail -1 | tr -d '\r')
SW=${SW:-1080}; SH=${SH:-2400}; DENS=${DENS:-420}
dp() { echo $(( $1 * DENS / 160 )); }   # dp → px at this density
CX=$((SW / 2))                           # content center x

scroll_bounds() { # y-range of the largest scrollable node — swipes outside
                  # it land on the pinned bottom bar and scroll nothing
  dump_xml || true
  python3 - "$OUT/.ui.xml" "$SH" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
sh = int(sys.argv[2])
best = None
for m in re.finditer(r'<node[^>]*>', xml):
    a = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(0)))
    if a.get('scrollable') == 'true':
        bm = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get('bounds', ''))
        if bm:
            y1, y2 = int(bm.group(2)), int(bm.group(4))
            if not best or (y2 - y1) > (best[1] - best[0]):
                best = (y1, y2)
if not best: best = (int(sh * 0.28), int(sh * 0.72))
print(best[0], best[1])
PY
}

scroll_up() { # nudge the page down (content up) INSIDE the scrollable body
  read -r Y1 Y2 <<< "$(scroll_bounds)"
  adb shell input swipe $CX $((Y1 + (Y2 - Y1) * 3 / 4)) $CX $((Y1 + (Y2 - Y1) / 6)) 350
  sleep 1
}

scroll_to_top() { # fling back to the page start — searches that scrolled
                  # past a target must be able to find it again
  read -r Y1 Y2 <<< "$(scroll_bounds)"
  for _ in 1 2 3; do
    adb shell input swipe $CX $((Y1 + (Y2 - Y1) / 5)) $CX $((Y1 + (Y2 - Y1) * 4 / 5)) 200
  done
  sleep 1
}


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
  for i in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml "$OUT/.ui.xml" >/dev/null 2>&1
    [ -s "$OUT/.ui.xml" ] && return 0
    sleep 3   # ANR windows clear after a few seconds — keep the cadence gentle
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

wait_nodes() { # wait_nodes <filter> [count] → prints first match.
               # A missed node may simply be below the fold on a small screen:
               # every third attempt scrolls the page down once (harmless
               # overscroll when the content already fits).
  local want="${2:-1}" pos="" n=0
  for _ in $(seq 1 25); do
    pos="$(find_nodes "$1" | head -"$want" | tail -1)"
    [ -n "$pos" ] && { echo "$pos"; return 0; }
    n=$((n + 1))
    # scroll down while searching; once pinned at the bottom, rewind to the
    # top so the next sweep re-scans from the start
    if [ $((n % 8)) -eq 0 ]; then scroll_to_top; elif [ $((n % 3)) -eq 0 ]; then scroll_up; fi
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

type_into() { # type_into <field label> <value> — the label may sit on the
              # EditText itself OR on its semantics wrapper View (Compose
              # moves the content-desc between node levels as the field's
              # internals change). Resolve by containment, and VERIFY the
              # field took focus before typing: a tap that lands during the
              # 320ms step transition misses silently and the old code then
              # typed into nothing.
  local label="$1" value="$2" pos="" i
  for i in $(seq 1 10); do
    dump_xml || true
    pos="$(LABEL="$label" python3 - "$OUT/.ui.xml" <<'PY' | head -1
import re, sys, os
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
label = os.environ["LABEL"]
nodes = []
for m in re.finditer(r'<node[^>]*>', xml):
    nodes.append(dict(re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(0))))
def b(a):
    bm = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
    return tuple(map(int, bm.groups())) if bm else None
edit = [a for a in nodes if "EditText" in a.get("class", "")
        and (a.get("content-desc") == label or a.get("text") == label)]
if edit:
    x = b(edit[0]); print((x[0]+x[2])//2, (x[1]+x[3])//2); sys.exit(0)
for a in nodes:
    if a.get("content-desc") == label:
        wb = b(a)
        if not wb: continue
        for e in nodes:
            if "EditText" in e.get("class", ""):
                eb = b(e)
                # center-in-wrapper, not strict containment: on dense
                # screens the wrapper's bottom padding clips away and
                # containment never holds
                if eb and wb[0] <= (eb[0]+eb[2])//2 <= wb[2] and wb[1] <= (eb[1]+eb[3])//2 <= wb[3]:
                    print((eb[0]+eb[2])//2, (eb[1]+eb[3])//2); sys.exit(0)
PY
)"
    [ -n "$pos" ] || { if [ $((i % 8)) -eq 0 ]; then scroll_to_top; elif [ $((i % 2)) -eq 0 ]; then scroll_up; fi; sleep 1.5; continue; }
    adb shell input tap $pos
    sleep 1
    # Focus proof: the IME coming up IS the focus event. The dump-based check
    # alone fails on short screens where the focused field scrolls behind
    # the keyboard and vanishes from the visible-node dump.
    if keyboard_shown; then
      adb shell input text "$value"
      sleep 0.7
      dismiss_keyboard
      sleep 0.5
      return 0
    fi
    if dump_xml && python3 - "$OUT/.ui.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'<node[^>]*>', xml):
    a = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(0)))
    if "EditText" in a.get("class", "") and a.get("focused") == "true":
        sys.exit(0)
sys.exit(1)
PY
    then
      adb shell input text "$value"
      sleep 0.7
      dismiss_keyboard
      sleep 0.5
      return 0
    fi
    sleep 1
  done
  echo "TIMEOUT: field '$label' never focused" >&2
  return 1
}
