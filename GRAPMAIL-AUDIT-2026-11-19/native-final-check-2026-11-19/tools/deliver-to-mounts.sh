#!/usr/bin/env bash
# deliver-to-mounts.sh — copy the GrapMail native final-check bundle into the project folders.
# Usage:  bash deliver-to-mounts.sh [bundle-dir] [mount-root ...]
# Defaults: bundle=/mnt/cos/artifacts/native-final-check-2026-11-19
#           mounts=/mnt/local/grapmail-mobile /mnt/local/Grapsee_All_Time
# Locates <mount>/GRAPMAIL-AUDIT-2026-11-19 (fallback search), copies the bundle + headline
# files, verifies every file (copy-back + compare), prints a receipt.
# Exit: 0 = delivered on at least one mount; 3 = nothing delivered; 2 = setup error.
set -u
BUNDLE=${1:-/mnt/cos/artifacts/native-final-check-2026-11-19}
shift 2>/dev/null || true
MOUNTS=("$@")
if [ ${#MOUNTS[@]} -eq 0 ]; then
  MOUNTS=(/mnt/local/grapmail-mobile /mnt/local/Grapsee_All_Time)
fi
[ -d "$BUNDLE" ] || { echo "SETUP-FAIL: bundle not found: $BUNDLE"; exit 2; }
DELIVERED=0
for ROOT in "${MOUNTS[@]}"; do
  if [ ! -d "$ROOT" ]; then echo "SKIP: mount not present: $ROOT"; continue; fi
  T="$ROOT/GRAPMAIL-AUDIT-2026-11-19"
  if [ ! -d "$T" ]; then
    T=$(find "$ROOT" -maxdepth 2 -type d -iname "GRAPMAIL-AUDIT*" 2>/dev/null | head -1)
  fi
  if [ -z "${T:-}" ] || [ ! -d "$T" ]; then
    echo "NO-TARGET: $ROOT (no audit folder found; nothing copied)"; continue
  fi
  echo "== target: $T"
  D="$T/native-final-check-2026-11-19"
  mkdir -p "$D/report" "$D/receipts" "$D/tools" "$D/ci" "$D/gates" 2>/dev/null
  cp -f "$BUNDLE/README.md" "$BUNDLE/GUARDRAILS.md" "$BUNDLE/NATIVE-FINAL-CHECK-2026-11-19.md" "$D/" 2>/dev/null
  cp -f "$BUNDLE"/report/* "$D/report/" 2>/dev/null
  cp -f "$BUNDLE"/receipts/* "$D/receipts/" 2>/dev/null
  cp -f "$BUNDLE"/tools/* "$D/tools/" 2>/dev/null
  cp -f "$BUNDLE"/ci/* "$D/ci/" 2>/dev/null
  cp -f "$BUNDLE"/gates/* "$D/gates/" 2>/dev/null
  mkdir -p "$T/report" 2>/dev/null
  cp -f "$BUNDLE/report/research_report_20261119_grapmail-native-verification.md" "$BUNDLE/report/research_report_20261119_grapmail-native-verification.html" "$T/report/" 2>/dev/null
  if [ ! -f "$T/NATIVE-FINAL-CHECK-2026-11-19.md" ]; then
    cp -f "$BUNDLE/NATIVE-FINAL-CHECK-2026-11-19.md" "$T/" 2>/dev/null
  fi
  BK=$(mktemp -d); BC="$BK/back"; mkdir -p "$BC"
  find "$BUNDLE" -type f > "$BK/flist"
  OK=0; FAIL=0
  while IFS= read -r f; do
    rel=${f#"$BUNDLE"/}
    mkdir -p "$BC/$(dirname "$rel")" 2>/dev/null
    if cp "$D/$rel" "$BC/$rel" 2>/dev/null && cmp -s "$f" "$BC/$rel"; then
      OK=$((OK+1))
    else
      echo "VERIFY-FAIL: $rel"; FAIL=$((FAIL+1))
    fi
  done < "$BK/flist"
  for bn in research_report_20261119_grapmail-native-verification.md research_report_20261119_grapmail-native-verification.html; do
    if cp "$T/report/$bn" "$BC/head-$bn" 2>/dev/null && cmp -s "$BUNDLE/report/$bn" "$BC/head-$bn"; then OK=$((OK+1)); else echo "VERIFY-FAIL(head): $bn"; FAIL=$((FAIL+1)); fi
  done
  echo "verify: ok=$OK fail=$FAIL"
  echo "target=$T files_ok=$OK files_fail=$FAIL"
  rm -rf "$BK"
  DELIVERED=1
done
if [ "$DELIVERED" -eq 1 ]; then echo "deliver-to-mounts: DONE"; exit 0; else echo "deliver-to-mounts: NOTHING-DELIVERED"; exit 3; fi
