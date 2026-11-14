#!/usr/bin/env bash
# Shoot the welcome screen on the running emulator and assert the things that
# actually go wrong with an embedded artwork:
#
#   * exactly ONE hero and ONE WebView (two would paint two tours),
#   * the frame keeps the artwork's authored 400 x 440 ratio,
#   * it sits between the promise line and Create Account, horizontally centred,
#   * it is not blank, and frames across the tour differ (the animation runs).
#
# Runs inside reactivecircus/android-emulator-runner. That action executes its
# `script` input line by line, so this walk lives in a file and is called as
# `bash scripts/welcome-shot.sh` — a heredoc or a for-loop written inline in the
# YAML would be split apart before it ever ran.
set -euo pipefail

PKG=com.grapsee.grapmail
APK=android/app/build/outputs/apk/debug/app-debug.apk
OUT=welcome-shots
mkdir -p "$OUT"

adb install -r "$APK"
adb shell settings put global hide_error_dialogs 1 || true
adb shell pm clear "$PKG"
adb shell cmd uimode night yes
adb shell am start -n "$PKG/.MainActivity"

# Wait for the welcome copy to exist in the real hierarchy, not for a guessed
# number of seconds.
READY=0
for _ in $(seq 1 30); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 || true
  if grep -q 'text="Welcome to"' "$OUT/ui.xml" 2>/dev/null; then READY=1; break; fi
  sleep 2
done
if [ "$READY" -ne 1 ]; then
  echo "::error::the welcome screen never appeared"
  adb exec-out screencap -p > "$OUT/stuck.png" || true
  exit 1
fi

# Dark theme (the founder's frame): a run of frames across the tour, plus a
# short recording so motion can be judged, not just layout.
sleep 2
for i in 1 2 3 4 5 6 7 8; do
  adb exec-out screencap -p > "$OUT/welcome-dark-$i.png"
  sleep 2
done
(adb shell screenrecord --time-limit 10 --bit-rate 8000000 /sdcard/welcome.mp4 || true) &
REC=$!
sleep 12
wait "$REC" || true
adb pull /sdcard/welcome.mp4 "$OUT/welcome-tour.mp4" >/dev/null 2>&1 || true

# Light theme: the same screen on the app's light surface.
adb shell cmd uimode night no
sleep 4
adb exec-out screencap -p > "$OUT/welcome-light.png"

# Re-read the hierarchy at the end: the dump that proved the screen was ready
# happened on the first frame, and the hero is a native view attached by the
# AndroidView factory — assert against the settled tree, not the one from
# before the animation was mounted.
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1

if ! command -v compare >/dev/null || ! command -v convert >/dev/null; then
  sudo apt-get update -qq && sudo apt-get install -y -qq imagemagick
fi

python3 - <<'PY'
import re, subprocess, sys, xml.etree.ElementTree as ET

out = "welcome-shots"
tree = ET.parse(f"{out}/ui.xml")
nodes = list(tree.iter())

def bounds(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None

def one(predicate, label):
    found = [n for n in nodes if predicate(n)]
    if len(found) != 1:
        sys.exit(f"FAIL: expected exactly one {label}, found {len(found)}")
    b = bounds(found[0])
    if not b:
        sys.exit(f"FAIL: {label} has no bounds")
    return b

hero = one(lambda n: (n.get("content-desc") or "").startswith("Animated preview"),
           "welcome hero")
webviews = [n for n in nodes if n.get("class") == "android.webkit.WebView"]
if len(webviews) != 1:
    sys.exit(f"FAIL: expected exactly one WebView, found {len(webviews)}")

create = one(lambda n: n.get("text") == "Create Account", "Create Account")
welcome = one(lambda n: n.get("text") == "Welcome to", "Welcome to")

x1, y1, x2, y2 = hero
w, h = x2 - x1, y2 - y1
ratio = w / h
print(f"hero bounds={hero} size={w}x{h} ratio={ratio:.4f}")
print(f"welcome bottom={welcome[3]} create top={create[1]}")

if abs(ratio - 400 / 440) > 0.02:
    sys.exit(f"FAIL: hero ratio {ratio:.3f} is not the artwork's 400/440")
if abs((x1 + x2) / 2 - 540) > 8:
    sys.exit("FAIL: hero is not horizontally centred")
if not (y1 > welcome[3] and y2 < create[1]):
    sys.exit("FAIL: hero is not between the promise line and Create Account")
if w < 600 or h < 660:
    sys.exit(f"FAIL: hero is too small to be the screen's artwork ({w}x{h})")

a = subprocess.run(
    ["compare", "-metric", "AE", "-fuzz", "2%",
     f"{out}/welcome-dark-1.png", f"{out}/welcome-dark-5.png", "null:"],
    capture_output=True, text=True,
)
changed = int((a.stderr or a.stdout).strip().split()[0])
print(f"pixels changed across the tour: {changed}")
if changed < 1000:
    sys.exit("FAIL: the hero did not move between frames")

sd = subprocess.run(
    ["convert", f"{out}/welcome-dark-3.png", "-crop", "900x900+90+700",
     "+repage", "-format", "%[fx:standard_deviation]", "info:"],
    capture_output=True, text=True,
)
deviation = float(sd.stdout.strip())
print(f"hero crop standard deviation: {deviation:.4f}")
if deviation < 0.02:
    sys.exit("FAIL: the hero crop is blank")

with open("welcome-shots/verdict.txt", "w") as f:
    f.write(f"hero {w}x{h} ratio {ratio:.4f}\n")
    f.write(f"{changed} pixels changed across 8 seconds\n")
    f.write("exactly one hero, one WebView, centred, between copy and CTA\n")
print("PASS: hero placement, ratio, singleness and motion")
PY

ls -la "$OUT"
