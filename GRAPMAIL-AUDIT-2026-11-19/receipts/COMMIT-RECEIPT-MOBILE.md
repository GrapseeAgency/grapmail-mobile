# Commit Receipt — grapmail-mobile — 2026-11-19 (audit round 15)

- Repo (user path): F:\Grapsee All Time\grapmail-mobile   (sandbox: /mnt/local/grapmail-mobile)
- Branch: feat/onboarding-native-scenes
- HEAD before: 87a51362ba0022486f1322b2c81132ee034d2533
- HEAD after:  0fb5998e0689faf73089d7bf8e19fc39573aa8f0
- Message: "audit 2026-11-19: fixpack applied state + iOS Scheduled wiring + attachment thumb"
- Diffstat: 21 files changed, +1211 / -201     (full list: `git -C <repo> show --name-status HEAD`)
- Root tree at commit: 665a16f5… (tree-vs-index listing diff verified identical, 379 entries)

## What ships in this commit
1. iOS Scheduled screen wiring (the screen was orphaned — Android already had it wired):
   - ios/GrapMail/RootView.swift — route enum case `mailScheduled` (L76), navigation closure `onScheduled` (L833), dispatch `case .mailScheduled: ScheduledView(...)` (L903–904)
   - ios/GrapMail/MailSecondaryViews.swift — `onScheduled` property + navigation row
   - ios/GrapMail/MailSnapshot.swift — seeded navigation entry
2. Android: attachment thumbnail component (AttachmentThumb.kt) + fixpack applied-state files.
3. A18 fixpack applied-state files carried into the commit (ledger: GRAPMAIL-AUDIT-2026-11-18/APPLIED-2026-11-18.md).

## Verification performed (all reproducible)
- In-commit marker grep at HEAD: `mailScheduled` (RootView L76), closure L833, dispatch L903–904; AttachmentThumb.kt present → OK
- Index rebuilt via sandbox-index technique (GIT_INDEX_FILE), `write-tree --missing-ok` (cache-tree workaround on this mount; see REPO-HEALTH-2026-11-19.md)
- Mount index healed to 44,011 bytes; path-scoped status (`git status --porcelain -- ios android`) clean at 2026-11-19 04:52 UTC
- Branch ref updated by compare-and-swap (`git update-ref refs/heads/... <new> <old>`) — no blind overwrite

## Notes
- No `git push` was performed (no credentials in this session). The commit exists on the local drive only.
- Ready-to-apply patch: receipts/mobile-0fb5998e.patch (when present in this folder); otherwise regenerate:
  `git -C "<repo>" format-patch 87a51362ba0022486f1322b2c81132ee034d2533..0fb5998e0689faf73089d7bf8e19fc39573aa8f0 --stdout > mobile-0fb5998e.patch`
- Do not hard-reset this branch back to 87a51362 unless the 21-file state is preserved elsewhere.
