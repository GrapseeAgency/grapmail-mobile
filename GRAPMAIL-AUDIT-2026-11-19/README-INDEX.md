# GRAPMAIL-AUDIT-2026-11-19 — Index (Round 15)

This folder collects everything produced by audit round 15 (2026-11-19) on top of the round 14 work.
Both the mobile repo (grapmail-mobile) and the backend repo (Grapsee_All_Time root) carry a copy of the relevant parts.

## Contents
- README-INDEX.md — this file
- R15-AUDIT-SUMMARY.md — findings of round 15 (deploy gap, iOS wiring, perf anatomy)
- DEPLOY-INSTRUCTIONS.md — step-by-step to close the production 404 gap (backend commit f9c43384)
- REPO-HEALTH-2026-11-19.md — repo/index health + recovery recipe for future rounds
- fix/ — the three edited iOS files as applied (RootView, MailSecondaryViews, MailSnapshot) + apply notes
- receipts/ — commit receipts, incident log, diffs, patch exports
- report/ — research report (md + html): 2026 email-client benchmark & performance root causes

## Commits shipped this round
| Repo | Branch | HEAD | Change |
|------|--------|------|--------|
| grapmail-mobile | feat/onboarding-native-scenes | 0fb5998e0689faf73089d7bf8e19fc39573aa8f0 | 21 files, +1211/−201 — iOS Scheduled wiring + attachment thumb + fixpack state |
| Grapsee_All_Time (backend) | main | f9c43384d0d2dff34a56b51714225055ffa42be7 | 2 files, +114 — /grapmail/files + verify-human + notification-preview (deploy prep) |

## Status (2026-11-19)
- Local commits: DONE (no push performed — no credentials in session; deploy instructions provided).
- Production 404 → 401 transition: pending server deploy of f9c43384 (see DEPLOY-INSTRUCTIONS.md).
- Research report: see report/.
- Next rounds (R16+): baseline-profile regeneration; webview warm pool; snapshot-first render; send-later UI parity; release checklist automation.

## Cross-OS note
This folder lives on the shared folder (F:\Grapsee All Time). The same physical files are visible from both mount roots —
verified by write/read probes across /mnt/local/grapmail-mobile and /mnt/local/Grapsee_All_Time/grapmail-mobile (E6).

## Native Final Check — 2026-11-19 (added 2026-11-19_11:21)
- Verdict: TRUE NATIVE / PURE NATIVE — iOS (Swift/SwiftUI) + Android (Kotlin/Compose); grapmail-mobile @ 0fb5998e
- Read first: NATIVE-FINAL-CHECK-2026-11-19.md
- Full report: report/research_report_20261119_grapmail-native-verification.md (+ .html)
- Bundle (receipts, tools, ci, gates): native-final-check-2026-11-19/
