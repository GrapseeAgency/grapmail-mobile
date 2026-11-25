#!/usr/bin/env bash
# Network chaos for the Android monkey job: cycles the emulator through
# "all off", "wifi only", "data only", "airplane mode" and "all on" every
# INTERVAL seconds until killed (or until the optional stop file exists).
#
# Usage:  bash quality/net-toggle.sh [INTERVAL_SECONDS=20] [LOG=/tmp/net-toggle.log] &
#         ... run monkey ...
#         kill $!            # the EXIT trap restores wifi + data, airplane off
#
# Needs: adb with one device. `svc wifi|data` works on every emulator API;
# `cmd connectivity airplane-mode` exists from API 30 (the job uses 34), and
# the older `settings put global airplane_mode_on` broadcast is the fallback.
# Every command is best-effort (|| true): a refused toggle is logged, never fatal,
# because this script is the weather, not the test.
#
# Sample log line (one per step):
#   2026-11-23T14:00:20Z step=1 state=offline
set -u
INTERVAL="${1:-20}"
LOG="${2:-/tmp/net-toggle.log}"

airplane() { # $1 = enable|disable
  adb shell cmd connectivity airplane-mode "$1" >/dev/null 2>&1 && return
  local v=0; [ "$1" = enable ] && v=1
  adb shell settings put global airplane_mode_on "$v" >/dev/null 2>&1 || true
  adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state "$([ $v = 1 ] && echo true || echo false)" >/dev/null 2>&1 || true
}
set_state() {
  case "$1" in
    offline)  airplane disable; adb shell svc wifi disable; adb shell svc data disable ;;
    wifi)     airplane disable; adb shell svc wifi enable;  adb shell svc data disable ;;
    data)     airplane disable; adb shell svc wifi disable; adb shell svc data enable ;;
    airplane) airplane enable ;;
    online)   airplane disable; adb shell svc wifi enable;  adb shell svc data enable ;;
  esac >/dev/null 2>&1 || echo "$(date -u +%FT%TZ) toggle to $1 refused" >>"$LOG"
}
restore() { set_state online; echo "$(date -u +%FT%TZ) restored online" >>"$LOG"; exit 0; }
trap restore EXIT INT TERM

STATES=(offline online airplane online wifi data online)
step=0
while :; do
  s="${STATES[$((step % ${#STATES[@]}))]}"
  set_state "$s"
  echo "$(date -u +%FT%TZ) step=$step state=$s" >>"$LOG"
  step=$((step + 1))
  sleep "$INTERVAL" &
  wait $!            # wait (not plain sleep) so TERM interrupts promptly and the trap runs
done
