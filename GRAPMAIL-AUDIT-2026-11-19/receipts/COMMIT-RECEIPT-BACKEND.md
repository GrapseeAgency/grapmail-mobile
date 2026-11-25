# Commit Receipt — Grapsee_All_Time (backend) — 2026-11-19 (audit round 15)

- Repo (user path): F:\Grapsee All Time   (repo root; sandbox: /mnt/local/Grapsee_All_Time)
- Branch: main
- HEAD before: 00e07443f4f8b691fdb64364e52b1c6f67c74daf
- HEAD after:  f9c43384d0d2dff34a56b51714225055ffa42be7
- Message: "grapmail(auth): mount files + scheduled + message-html + notification-preview + verify-human routes (audit 2026-11-19, deploy prep)"
- Diffstat: 2 files changed, +114

## Files
- grapsee-backend/services/auth-service/src/index.ts (modified)
  - imports grapmail-files routes (L39) and mounts them: `app.route('/grapmail/files', grapmailFilesRoutes())` (L131)
- grapsee-backend/services/shared/src/routes/grapmail-files.ts (added)
  - attachment files upload/download/serve route module backing /grapmail/files

## Verification performed
- Listing diff tree-vs-index at commit: 2,949 entries, identical → OK
- Mount index healed to 376,126 bytes; path-scoped status (`-- grapsee-backend/services`) clean
- Ref updated by compare-and-swap — no blind overwrite
- Ready-to-apply patch: receipts/backend-f9c43384.patch (7,568 bytes; `git format-patch`, git 2.47.3)

## Deploy status (production)
Probes 2026-11-19 04:54 UTC (before server deploy):
- /grapmail/files → 404            (route in code, not deployed)
- /grapsee/auth/verify-human → 404
- /grapmail/notification-preview → 404
- /grapmail/labels → 401           (auth-gated, as expected)
- /health → 200
→ Apply DEPLOY-INSTRUCTIONS.md to flip the three 404s to 401 (route exists, auth-gated), then live feature use.

## Notes
- No push performed (no credentials in this session).
- Orphan object 894ac485e6487b275ea8dfb8a16afcb4b57292f9 (empty artifact of an earlier failed commit surgery, now unreferenced) — safe to ignore; git gc will collect it.
