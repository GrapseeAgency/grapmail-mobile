#!/usr/bin/env bash
# Android stress job driver: monkey + network chaos + crash/ANR gate.
# Usage: bash quality/android-monkey.sh <apk> <outdir> [seed] [events]
#   seed: arg 3, else $SEED_INPUT (workflow_dispatch input), else quality/budgets.json
# Needs a booted emulator. Artifacts in <outdir>: logcat.txt, monkey.txt,
# net-toggle.log, seed.txt. Exit 1 on any crash/ANR of com.grapsee.grapmail.
set -uo pipefail
APK="${1:?apk}"; OUT="${2:?outdir}"
SEED="${3:-${SEED_INPUT:-}}"
[ -n "$SEED" ] || SEED="$(python3 -c 'import json;print(json.load(open("quality/budgets.json"))["android_monkey"]["seed"])')"
EVENTS="${4:-5000}"
PKG=com.grapsee.grapmail
mkdir -p "$OUT"
echo "seed=$SEED events=$EVENTS" | tee "$OUT/seed.txt"

adb install -r -g "$APK"
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
adb logcat -c || true
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true

bash quality/net-toggle.sh 20 "$OUT/net-toggle.log" &
NET_PID=$!

# --pct-syskeys 0: no HOME/POWER storms; monkey -p keeps events inside the app.
# No --ignore-crashes/--ignore-timeouts: monkey STOPS at the first crash/ANR,
# so the log tail points at it. 5000 events x 100 ms throttle ~ 8-10 min.
adb shell monkey -p "$PKG" -s "$SEED" --throttle 100 \
  --pct-syskeys 0 --pct-anyevent 0 --pct-touch 45 --pct-motion 25 --pct-nav 10 \
  --pct-majornav 10 --pct-appswitch 5 --pct-trackball 5 \
  -v -v "$EVENTS" > "$OUT/monkey.txt" 2>&1
MONKEY_RC=$?

kill "$NET_PID" 2>/dev/null; wait "$NET_PID" 2>/dev/null
sleep 5   # let a late ANR land in logcat
kill "$LOGCAT_PID" 2>/dev/null

echo "monkey exit=$MONKEY_RC; last lines:"; tail -n 5 "$OUT/monkey.txt"
{
  echo "### Monkey stress (seed \`$SEED\`, $EVENTS events, network toggled every 20s)"
  echo
  echo "Monkey exit code: $MONKEY_RC (non-zero alone is not a failure; the crash scan below decides)."
  echo "Reproduce: \`bash quality/android-monkey.sh app-debug.apk out $SEED $EVENTS\`."
  echo
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
python3 quality/logcat_crash.py "$OUT/logcat.txt" --monkey "$OUT/monkey.txt" --pkg "$PKG"
