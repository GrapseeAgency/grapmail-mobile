# Round 15 Audit Summary — 2026-11-19

Round 14/15 objective: go strictly deeper than rounds R1–R13 (no repeats), close real gaps end-to-end, and answer the standing question: why does an app with so many features still feel slow/incomplete?

## Headline findings
1. **Deploy gap = the biggest "broken" contributor.** Three routes exist in source but were unreachable in production (404) because the commits carrying them never landed: /grapmail/files, /grapsee/auth/verify-human, /grapmail/notification-preview. Auth-gated siblings answer 401 correctly. Probes (E3): 2026-11-19 04:54 UTC.
   → Fixed in code this round: backend commit f9c43384 (E2). Production flips 404→401 after server deploy (instructions in DEPLOY-INSTRUCTIONS.md).
2. **iOS Scheduled screen was orphaned** — the file existed and Android had the screen wired, but iOS never navigated to it. → Wired end-to-end this round; commit 0fb5998e (E1).
3. **Message preview completeness (from R10/R12) confirmed in code** — sanitized HTML renderer, CSP, blocked-image markers, image opt-in refetch all present; the gap was deploy + build age, not code.
4. **Performance anatomy ("feels slow")** — three levers, evidenced by the R15 benchmark & platform literature (see report/):
   - cold start → Baseline Profiles (double-digit % startup gains reported in official guidance and field studies; caveats on low-end devices);
   - first frame of rendered mail → webview warm-up + HTTP cache + declarative content blocking (loadHTMLString latency is a known cost);
   - offline-first triage → 2026 leader clients act locally first; the network is an optimization, not a dependency.
5. **Repo/index disease discovered & fixed** — the mount .git/index was truncated with stale locks (EIO); earlier commit attempts failed with cache-tree "invalid object" errors. Recovered via the sandbox-index + `--missing-ok` technique (see REPO-HEALTH-2026-11-19.md).
6. **R8/R9 prior notes loss** — the A18 zip archive contains R1–R7 only; documented, cannot be recovered (E5).

## Evidence register (for traceability)
- E1 mobile commit 0fb5998e (+ in-commit markers: RootView.swift L76/L833/L903–904; AttachmentThumb.kt at HEAD)
- E2 backend commit f9c43384 (+ markers: auth-service/src/index.ts L39 import / L131 route)
- E3 production probes 2026-11-19 (404 vs 401 vs 200 table)
- E4 applied-state md5 matrix (~21 files byte-identical to A18/A19 ledgers)
- E5 receipts in this folder (INCIDENT-2026-11-19.md, diff-*.txt, A18 fixpack README)
- E6 mount & cross-OS write/read probes

## What remains (R16+ queue)
1. Apply the backend deploy (user action) → verify 404→401.
2. Baseline profile regeneration with a startup journey + CI hook.
3. Webview warm-pool + time-to-first-render instrumentation on the mail reader.
4. Snapshot-first mailbox render + optimistic actions (offline-first step).
5. Send-later / Snooze UI parity on iOS.
6. Release checklist automation (smoke curls, commit receipts).
