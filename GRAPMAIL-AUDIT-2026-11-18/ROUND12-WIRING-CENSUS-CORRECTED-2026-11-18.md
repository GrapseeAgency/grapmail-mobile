# Round 12 — Wiring Census, Corrected: The Encryption Band, Subscriptions, and the One Dormant Screen

Round 12's mandate was dormant-surface sweep #2 — hunt the "completed yet not" seam artifacts, the pattern Round 10 established with RemoteImageView. The first pass flagged three iOS families (scheduled, subscriptions, encryption/E2E) as presenter-less orphans. Then the sweep method itself turned out to be lying: recursive greps using `--exclude-dir=GrapMail` on a search root literally named `GrapMail` return false-empty results, and two of the three "orphans" are in fact fully wired. Every zero-count verdict was re-run with clean sweeps and direct file reads. The corrected census settles on a single dormant screen — iOS `ScheduledView` — plus the already-documented RemoteImageView pair. Nothing from the first pass was published; this census is the deliverable.

## Why the first census lied
- Artifact proof: a recursive sweep for `onEncryption` returned empty, while a direct read of `RootView.swift` shows `onEncryption: { path.append(.e2eStatus) }` at L842.
- Mechanism: GNU grep prunes command-line search directories whose basename matches an `--exclude-dir` pattern; when the search root itself is `ios/GrapMail`, the entire tree is skipped before traversal.
- Correction protocol: every zero-count verdict re-executed without exclusion flags (deduplicated against the mirror's nested duplicate tree), then key claims confirmed by direct region reads.
- Standing rule for the remaining rounds: zero-count verdicts must cite clean sweeps; never combine `--exclude-dir` with a pattern equal to any search-root basename.

## Corrected census — iOS
| Surface | Verdict | Evidence (file:line) |
|---|---|---|
| Encryption band (8 screens) | WIRED | Band switch routes all eight cases to `e2eDestination(_:)` (RootView L890-1005; all eight screens constructed at L937-958); entry = settings row "Encryption" (MailSecondaryViews L779) → `onEncryption: { path.append(.e2eStatus) }` (RootView L842); state L113/L117; snapshot seeds MailSnapshot L640-662. |
| Subscriptions | WIRED | Route case `mailSubscriptions` (RootView L93); settings row "Subscriptions" (MailSecondaryViews L752-753); closure bound twice (RootView L735 and L839); drawer door via MailboxView L80 / MailChrome L66; destination `SubscriptionsView(vm: mail, onBack:)` (L861-862); seeds MailSnapshot L621/623. |
| ComposeFormattingEditor | WIRED | Instantiated at MailReaderViews L1137 (earlier pass; unchanged). |
| RemoteImageView family | ORPHAN (R10 stands) | Definition-only under clean sweeps (MailHandoff.swift L169); zero external callers; re-verified this round. |
| ScheduledView | ORPHAN — sole survivor | `ios/GrapMail/ScheduledView.swift` (5477 B); whole-tree clean sweep finds zero UI references outside the file itself; no route case, no settings row, no drawer row; surrounding tokens only internal helpers (`MailStore.scheduleSnapshotWrite`, `MailboxModel.schedule{Reconciliation,Autosave}`), the read/cancel API pair (MailAPI L1830-1858), and background poll scheduling (RootView L249-253; AppIntegration BGTaskScheduler). |

## Corrected census — Android
| Surface | Verdict | Evidence (file:line) |
|---|---|---|
| Encryption band | WIRED | Route consts L351-359; settings entry `onEncryption` L1351; nav composables L1420-1456; state at L412. |
| Subscriptions | WIRED | Nav L1480; drawer row (MailboxScreen L1252-1277); settings row (MailSecondaryScreens L684-685); screens imported in the L58-72 region. |
| Scheduled | WIRED | Route const L330; entry `onScheduled = { nav.navigate(Routes.MAIL_SCHEDULED) }` L1224; drawer row "Scheduled" (MailboxScreen L1260-1261); composable + `ScheduledScreen` L1482-1484 (screen def L39). |
| RemoteImageView family | ORPHAN (R10 stands) | Definition-only (MessageHandoff.kt L186/196/232); zero external callers. |

Net: the asymmetry is now precise and narrow — **iOS `ScheduledView` is the single screen built but never presented**; every sibling band is wired on both platforms, and Android's Scheduled surface is wired.

## The dormant screen — dossier
- `ios/GrapMail/ScheduledView.swift` (5477 B). Its head comment names the missing piece: "The session's bearer, passed by the wiring site: this view never reaches into the model's private session state." — there is no wiring site.
- The surface is deliberately gate-legal: "Gate-legal: no client scheduling field — this screen only reads and cancels mail the server already holds" (docs on the scheduled surface, MailAPI L1830-1858 region).
- Deploy-gate context: the research report's deploy-gated list (RESEARCH-REPORT L136) names the "scheduled send worker" among capabilities that are repo-implemented pending deploy — "search operators, scheduled send worker, snooze, OTP flows, envelope views, push, HTML handling, vacation responder". The worker that promotes scheduled mail server-side is not deployed; the client screen can only read and cancel.

## Disposition
- Documented, not edited — same posture as R10's orphan and the FINDINGS §1-6 exclusions. Wiring the iOS entry blind (no compile feedback in this environment; entry placement undecided) while the backend worker is deploy-gated is the class of speculative change this audit declines.
- Recommended path, when the worker ships: mirror the Subscriptions pattern — a MailSettingsView row ("Scheduled") → Route case → destination rendering `ScheduledView` (session bearer passed at the wiring site, as its comment expects); optionally a drawer row for parity with Android.
- Round 12 made no code edits; no `round12-applied-files` directory (same as R5/R10/R11 precedent). Deliverable = this note.
- CI blind spot reconfirmed: honesty pins cover E2E/Subscriptions logic (crypto constants, session vars, screen-line presence) but not presenter wiring; walk scripts seed snapshot frames for these bands (test-only; they do not navigate). A wiring-presence pin remains the obvious hardening candidate for a future round.

## Round 10 corroboration
RemoteImageView orphan status re-verified under clean sweeps this round (both platforms, definition-only) — the R10 note stands uncorrected. The shipped "Show images" flow (server re-fetch with `remoteImages=true` → WebView render) is unchanged.

## Verification ledger
- All zero-count claims above cite clean sweeps (no exclusion flags), deduplicated against the mirror duplicate tree; key claims additionally confirmed by direct region reads (RootView L826-858 / L925-962; MailSecondaryViews L746-757; MainActivity L1455-1505).
- Evidence files (this round, session notes): r12_recon_a…f, r12_digest_d1/d2, r12_e2e_probe, r12_final_probe, r12_ios_enc_probe, r12_reverify1, r12_reverify2, r12_subs_probe, r12_android_sched_probe.
- Sweep environment: mount-verified mirror at the latest applied revisions; the nested `GrapMail/GrapMail` duplicate tree is a mirror artifact (references counted once).
- Delivery: shipped to the COS artifact root; backfilled to the two local mounts on mount recovery, alongside the queued R8→R11 mount deliveries.
