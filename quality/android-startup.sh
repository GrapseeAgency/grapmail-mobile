#!/usr/bin/env bash
# Android startup job driver: N cold + N warm `am start -W`, median vs budget.
# Usage: bash quality/android-startup.sh <apk> <outdir> [runs=10]
# cold = process killed (am force-stop) before each launch;
# warm = process alive, activity finished with BACK then relaunched.
set -uo pipefail
APK="${1:?apk}"; OUT="${2:?outdir}"; RUNS="${3:-10}"
PKG=com.grapsee.grapmail; ACT="$PKG/.MainActivity"
mkdir -p "$OUT"; : > "$OUT/cold.txt"; : > "$OUT/warm.txt"
adb install -r -g "$APK"
# Let the baseline profile be compiled the way a Play install would be
# (profileinstaller ships src/main/baseline-prof.txt); best-effort.
adb shell cmd package compile -f -m speed-profile "$PKG" >/dev/null 2>&1 || true
adb shell am start -W -n "$ACT" >/dev/null 2>&1; sleep 3   # first-ever launch is not measured

for i in $(seq 1 "$RUNS"); do
  adb shell am force-stop "$PKG"; sleep 2
  adb shell am start -W -S -n "$ACT" >> "$OUT/cold.txt" 2>&1
  sleep 3
done
for i in $(seq 1 "$RUNS"); do
  adb shell input keyevent KEYCODE_BACK; sleep 2
  adb shell am start -W -n "$ACT" >> "$OUT/warm.txt" 2>&1
  sleep 2
done
grep -h LaunchState "$OUT/warm.txt" | sort | uniq -c || true   # shows if "warm" really was WARM/HOT

{
  echo "### Android startup (emulator-relative, median of $RUNS)"
  echo
  echo "| kind | runs | min | median | max | budget | fail above | verdict |"
  echo "|---|---|---|---|---|---|---|---|"
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
rc=0
python3 quality/startup_median.py --kind cold --file "$OUT/cold.txt" || rc=1
python3 quality/startup_median.py --kind warm --file "$OUT/warm.txt" || rc=1
exit $rc
