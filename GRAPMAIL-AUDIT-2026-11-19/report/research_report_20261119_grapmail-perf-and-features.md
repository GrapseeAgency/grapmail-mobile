# GrapMail Round 15 — Why the App Feels Slow: 2026 Email-Client Benchmark and Performance Root Causes

*Audit round 15 · 2026-11-19 · Prepared for the GrapMail project (iOS / Android / backend)*
*Companion internal documents (same folder): R15-AUDIT-SUMMARY.md · DEPLOY-INSTRUCTIONS.md · REPO-HEALTH-2026-11-19.md · receipts/ (commit receipts, incident log, diffs)*

## Executive Summary

Three findings dominate the answer to "why does GrapMail still feel slow and unfinished even though the features are built?"

- **The largest gap is a release gap, not a code gap.** Production answers 404 on three endpoints that exist in source — `/grapmail/files`, `/grapsee/auth/verify-human`, `/grapmail/notification-preview` — while auth-gated siblings correctly answer 401; clients fall back to degraded states that read as broken or slow (E2, E3). Backend commit `f9c43384` closes the gap in code; production flips 404→401 on server deploy.
- **An entire iOS screen was unreachable.** The Scheduled screen existed but had no navigation wiring on iOS (Android had it wired). Commit `0fb5998e` wires it end-to-end (E1) — the round's cleanest proof that "exists in code" is not "usable in app".
- **Perceived speed has three measurable levers**, evidenced by the 2026 benchmark and platform literature: cold-start compilation (Baseline Profiles — double-digit improvements reported [9][10][12][13][14]); first-frame rendering of mail bodies (webview warm-up, HTTP cache, declarative blocking [16][17][18][19]); and offline-first behavior (the fastest clients work with no network at all [1][2][8]).

**Primary Recommendation:** Apply backend commit `f9c43384` to production now (documented 404→401 smoke test), then run the R16 performance program: startup-profile regeneration, reader warm-up with time-to-first-render instrumentation, and an offline-first milestone.

**Confidence Level:** High for the deploy-gap and wiring findings (commit markers and live probes); Medium for performance magnitudes (directional literature values — GrapMail-specific baselines are the next measurement step).

## Introduction

### Research Question

Why does an app whose feature code is largely complete still feel slow and unfinished in daily use — and what, specifically, must change to fix that? The question matters because the project's audit history shows relaunches and polish rounds have not removed the sensation; this round tests whether the cause lies in the code, in the shipping layer, or in both.

### Scope & Methodology

Rounds R14–R15 of the audit program were mandated to go strictly deeper than R1–R13 — no repeats — and to make every change durable: all artifacts, receipts, and commits persisted to the shared local drive (F:\Grapsee All Time) so work survives a change of operating system or machine.

**Method.** Three evidence streams. (1) Repository verification at commit level: marker greps with `git show` at HEAD, a ~21-file md5 matrix against the applied-state ledger, and tree-vs-index listing diffs executed before every commit (379 entries for the mobile repo, 2,949 for the backend — both identical). (2) Production probes: anonymous HTTPS requests against `api.iqracsc.com` distinguishing 200 (healthy), 401 (route exists, auth-gated), and 404 (no handler) — a credential-free way to audit the shipped surface. (3) Public benchmarks and platform documentation: 2026 email-client comparisons for feature and architecture baselines, plus first-party Android/iOS guidance for cold-start and webview performance. Internal evidence is registered as E1–E6 in the Methodology Appendix; external sources are numbered [1]–[22] in the Bibliography (22 sources consulted; coverage: platform guidance 2022–2025, market comparisons 2026).

### Key Assumptions

- **Source of truth.** The shared local drive is authoritative for "done"; production behavior is authoritative for "shipped". Where the two disagree, the report says so explicitly.
- **Anonymous probing.** No credentials exist in this environment, so route existence (401 vs 404) is used as the shipped-surface signal; authenticated behavior is inferred, not exercised.
- **Directional external numbers.** Performance figures from literature are treated as indicative ranges, not GrapMail measurements; no on-device profiling was performed this round.
- **Release actions are documented, not executed.** Push/deploy steps requiring server credentials are handed off with exact instructions (DEPLOY-INSTRUCTIONS.md).
- **Mount reliability constrained methods.** The shared drive exhibits intermittent I/O errors; the recovery recipe (sandbox index + `--missing-ok` + listing diff + compare-and-swap) is captured in REPO-HEALTH-2026-11-19.md.
## Main Analysis

### Finding 1: The Deploy Illusion — feature-complete code that production never received

On 2026-11-19 at 04:54 UTC, anonymous probes of the production API returned a precise picture of what is actually shipped (E3). Seven sibling grapmail routes — labels, aliases, screener, scheduled, attachments, message HTML, and push — answered 401, the expected response for a route that exists and is auth-gated. Three endpoints answered 404: `/grapmail/files`, `/grapsee/auth/verify-human`, and `/grapmail/notification-preview`. A 404 is not an auth refusal; it means no handler exists in production at all. For a client this is the difference between "log in to continue" and "this feature does nothing".

The three routes exist in source — and had existed for many rounds. The reason they never reached production is a repository-level failure that until this round blocked every attempt to commit: the `.git/index` files of both repositories on the shared drive were damaged — truncated content, a stale `index.lock`, and intermittent I/O errors on the mount. Every commit attempt failed while writing trees, with cache-tree verification errors of the form `invalid object <sha> for '<dir>'`. The practical consequence: the R1–R13 fixpack was applied to the working trees but remained uncommitted, so the deployment pipeline had nothing new to pick up for these routes (E5, E6).

Round 15 broke that logjam. Using a sandbox-index technique — build the index outside the damaged mount, write the tree with `--missing-ok`, verify the tree listing against the index listing before committing, update the branch ref with compare-and-swap, then heal the mount index — both repositories now carry real commits (E1, E2, E6). For the backend, commit `f9c43384` (2 files, +114 lines) adds the missing route module `services/shared/src/routes/grapmail-files.ts` and mounts it in `services/auth-service/src/index.ts` (import at L39, mount at L131). Verification before landing: a tree-vs-index listing diff over 2,949 entries returned identical.

Why a single deploy finding explains so much of the "unfinished" feeling: each 404 converts a finished client-side feature into a degraded path — attachment flows that spin and fail, notification previews that never render, human-verification that dead-ends. Users do not experience "route not deployed"; they experience "this app is broken" or "this app is slow" — spinners that wait for a 404 and then fall back. The same logic explains the R10/R12 preview investigation, which found the sanitized HTML pipeline (CSP, blocked-image markers, opt-in refetch) to be code-complete: the missing ingredients were deployment and build age, not missing implementation.

Current status, stated precisely: the gap is closed in code and open in production until the server applies commit `f9c43384` (apply the exported patch or push with credentials, rebuild, restart). The documented smoke test expects the three routes to move from 404 to 401 — "exists, auth-gated" — while `/health` stays 200. After that transition, the features behind those routes become exercisable, and any remaining "incomplete" reports can be re-checked against a genuinely current deployment instead of a stale one.

### Finding 2: Cold start — the highest-leverage Android lever

The moment that defines an app as "slow" is tap-to-first-frame, and on Android that window is dominated by runtime work: class loading, interpretation, and JIT compilation of the code paths the app actually touches. Whatever those hot paths are — application startup, the first mailbox render, the first image decode — the runtime must discover them on a cold launch unless it is told in advance.

Baseline Profiles change that negotiation. Shipped inside the APK, a profile lists the classes and methods that matter for key journeys; the runtime compiles those ahead of time at install (AOT) instead of discovering them during use. The mechanism was introduced and scaled by the Android team [10], and is documented officially, including generation via Macrobenchmark's `BaselineProfileRule` and per-API-level optimizations [11][12]. The effect lands precisely in the first seconds users judge: Meta's engineering team reports double-digit percentage improvements in cold start time across its Android apps after adopting baseline profiles [9]; Google's codelab and platform talks walk teams through measuring it on real devices [12][15]; production apps outside the largest publishers document the same direction [13].

The caveats matter as much as the promise. A baseline profile helps only the journeys it covers — if the profile's startup journey does not match the real cold-start path into the inbox, the gains go missing [14]. Device class matters: low- and mid-tier hardware shows the largest improvements, while high-end devices benefit least. And a stale profile is worse than a fresh one, which makes CI integration — regenerate on release — as important as initial generation [14][15].

GrapMail's position (E4): the applied-state matrix shows Baseline Profile infrastructure already partially present — build configuration and Android walk scripts exist in the repository. What is missing is a startup-journey profile measured on a device/emulator matrix that matches GrapMail's user fleet, plus a CI step that keeps it fresh so the compilation windows stay aligned with the code. This is the cheapest high-leverage lever available: it requires no architecture change, targets exactly the sensation users report, and compounds across every feature added later.
### Finding 3: First frame — what the mail reader costs before it draws

Reading mail is the app's core loop, and on both platforms message bodies render in a webview: sanitized HTML with a locked-down content-security policy, remote images annotated as blocked and refetched only on explicit opt-in (the R10/R12 parity work; E4). That architecture is correct for security; the remaining question is how many milliseconds pass between "user taps a message" and "text is legible".

That path has a well-documented cost profile. Practitioners measure a significant share of webview display latency inside the gap between `loadHTMLString()` and `didFinishNavigation()` — the engine parses and lays out the document before the first frame, and a cold webview process adds its own startup [17]. Production case studies report webview shows around the three-second mark and roughly halving them through resource-level optimization rather than rewrites — targeted fixes, not architectural ones [18].

Three mitigations have public track records. First, warm instances: keep one bounded, reusable webview near the user's path and preload an inert shell early; preloading the URLs users actually open seeds the HTTP cache so the subsequent real load is a cache hit [16]. The same literature warns against blanket preloading — every warm webview costs memory and startup time, so the pool must be small and deliberate [18]. Second, caching: second and subsequent loads served from the HTTP cache are materially faster, which argues for caching sanitized markup per message and revalidating rarely [16][19]. Third, declarative blocking: WKWebView content rule lists compile once into bytecode and run far more cheaply than injected JavaScript blockers, making them the right mechanism for image and tracker policy [19][21].

Two API-level details are easy wins: `loadHTMLString(_:baseURL:)` should receive a local base URL when the document references bundled assets (templates, fonts, CSS) so those loads stay on-device [21][22]; and the web view should be installed as the view controller's own view via `loadView` rather than nested into a deeper hierarchy, a pattern that avoids avoidable layout cost at presentation time [22]. File-based shells via `loadFileURL`/`loadFileRequest` are the alternative when the document body is injected after the shell is already warm [21].

Memory discipline is the constraint that keeps these wins from backfiring: WKWebView runs in its own content process and is heavyweight; leaks and unbounded instance growth are known to degrade memory and responsiveness over a session [20]. GrapMail's plan is therefore a single warm reader instance per surface with an explicit teardown policy — sanitized shell cached, body injected on open, and time-to-first-render instrumented so the change is measured rather than assumed (see Recommendations).

### Finding 4: Offline-first — the architecture 2026's fastest clients converged on

The 2026 benchmark makes offline behavior a first-class speed feature, not a checkbox. Field reports describe Superhuman's offline mode as effectively invisible — "you just get a little banner" — with read, search, compose, snooze, archive, and schedule all working offline and sends queued until reconnect [2]. Companion reviews highlight "super fast offline search" and the same full action set offline [1]; Fabric's comparison states offline access across desktop and mobile apps with sync on reconnect [8]. Spark, meanwhile, needs no explicit enablement but caches only recent mail — and the same reviews note users complaining that it is slow, a direct consequence of cache limits meeting large mailboxes [1][2].

The sync architectures underneath differ, and the difference is instructive. Spark keeps a server-side layer that can fire send-later and snooze timers even while the device is offline; Superhuman's model requires reaching the provider for send and sync, so a queued send waits for reconnection [6]. Both work because the client treats the local store — metadata, bodies, indexes — as the primary surface, and the network as a synchronization and planning mechanism.

The anti-patterns are equally visible in the benchmark: clients that require an explicit "enable offline" toggle (by the time users need it, it is too late [2]), and cache-limited offline that leaves holes for archive-everything workflows [1]. The speed property users perceive is architectural: if the first screen can render from disk, network latency never enters the interaction; if it waits on the network, no amount of server-side optimization removes the pause.

Where GrapMail stands (E4): the mobile clients carry model layers with cached state (Android's `MailboxViewModel`, iOS's `MailboxModel` both present in the applied-state matrix), and earlier rounds fixed a real cache artifact in avatar fetching. What R16–R17 must instrument and finish: snapshot-first rendering (paint the mailbox from the local store before any refresh request), optimistic triage with an outbox queue for archive/move/send actions, and a local search index that answers without the network. Those changes are additive to the current architecture — no rewrite required — and they directly address the "feels slow" half of the user complaint that deploy fixes alone cannot.

For market context: the two leaders optimize for different axes — Superhuman for single-user speed at a premium price, Spark for cross-platform breadth and team features with a free tier [3][5][7]; one widely cited comparison is vendor-authored and should be read with that in mind [4]. The common denominator remains the one GrapMail should copy: local-first speed.
### Finding 5: Feature parity — the iOS Scheduled screen and the perception gap

The cleanest example of "complete but unusable" in this round is the iOS Scheduled screen. The view file existed and was tracked in the repository — ScheduledView.swift, 5,477 bytes — but nothing navigated to it: no route case, no navigation row, no dispatch in the iOS navigation switch. Android had the equivalent screen wired and included in its baseline-profile journeys; iOS users had no way to reach the feature at all (R12 census; E5). A finished piece of work was invisible to every user.

Round 15 fixed the wiring end-to-end and verified it inside the commit (E1): the navigation enum gains `mailScheduled` (RootView.swift L76), the settings panel gains an `onScheduled` row, and the switch dispatches to `ScheduledView` (L903–904), with a snapshot seed added for the screenshot walk. The verification pattern matters as much as the fix: the check was performed against the commit object itself (`git show HEAD:...`), not against the working tree — so the receipt proves what shipped rather than what was left on disk.

Why parity gaps amplify perceived incompleteness: like the deploy gap in Finding 1, an unreachable screen converts "already built" into "missing" at the user's fingertips. Users do not audit code; they audit what happens when they tap. Two of this round's biggest complaints traced to exactly this category — a screen nobody could open and routes nobody could call.

The 2026 benchmark sets the parity bar. Snooze and send-later are table stakes: field reviews of Superhuman describe the full action set working offline [2], and comparisons treat scheduling and offline triage as core speed features, not extras [1][6]. Team-oriented clients add shared inboxes and delegation [6]; AI-assistant tiers now sit above the client layer entirely [7]. Cross-platform breadth also scores: reviewers rate Spark's mobile apps higher on fluency than Superhuman's [5], while Superhuman optimizes single-user speed at a premium [3]; one widely cited comparison is vendor-authored and should be read accordingly [4].

With iOS Scheduled now wired, the remaining parity work is a repeatable census rather than a hunt: apply the R12 method (route table vs navigation graph vs dispatch switch) to every platform after each release, and verify newly reachable screens on device builds. That method is what caught this one; run per-round, it prevents the next one. Whether additional iOS orphans or feature-depth gaps (for example, send-later UX polish) remain is the first item of the R16 census.

### Finding 6: Release engineering — make "done" mean deployed

Every major finding in this round shares one shape: the code was mostly right, and the shipping layer was wrong. The fixpack existed but was uncommitted. The routes existed but were undeployed. The screen existed but was unreachable. The builds were older than the code. None of these are feature failures; all of them are release-engineering failures — and from a user's chair they are indistinguishable from "the app is broken, slow, or incomplete".

The mechanism that kept the fixpack uncommitted deserves a precise record (E5, E6). Both repositories' `.git/index` files on the shared drive were damaged — truncated content, a stale `index.lock`, and intermittent I/O errors. Every commit attempt failed during tree writing with cache-tree verification errors of the form `invalid object <sha> for '<dir>'`. The working trees carried the fixes for rounds; the history did not. This round's recovery — sandbox-side index construction, `write-tree --missing-ok`, tree-vs-index listing verification before every commit, compare-and-swap ref updates, and an index heal-copy back to the mount — is documented as a repeatable recipe in REPO-HEALTH-2026-11-19.md, because the disease can recur on this class of storage.

The verification culture that worked should be preserved deliberately: probes that distinguish 200/401/404 so "route missing" is provable without credentials; in-commit marker checks so receipts describe shipped objects rather than intentions; md5 matrices so "applied state" is a fact; and receipts written to the shared drive so evidence survives an OS change (E6 cross-mount probes). Each artifact in this folder is reproducible from the commands embedded in it.

The forward-looking recommendation is therefore a release checklist that turns "done" into a verified state: commit → build → deploy → smoke → receipt, automated where possible. Two specific rails: (1) a post-deploy smoke script embedding the 404→401 table from DEPLOY-INSTRUCTIONS.md, and (2) a build-age check for each environment, alarming when production is older than the latest release. These are cheap, and they directly prevent the failure class behind this round's two biggest findings.
## Synthesis & Insights

Three patterns organize everything this round found.

First, **the boundary between repository and user is where completeness broke** — not the code inside it. Three independent evidence streams (commit history, live probes, benchmark) each show the same failure shape: work finished at the desk, lost in transit — uncommitted, undeployed, unreachable. The practical consequence: any future "is it done?" audit should start by verifying the last mile, because that is where this project's answers have gone wrong most often.

Second, **perceived speed is a stack of three layers, each with a different owner and a measurable fix**. Launch speed belongs to cold-start compilation (Baseline Profiles [9]–[15]); the mail-reading moment belongs to the webview and its cache/warm-up strategy ([16]–[22]); interactions between fetches belong to the offline-first architecture ([1], [2], [8]). None of the three requires a rewrite, and each can be measured before it is changed. The investment order by leverage is: deploy fixes first (hours; removes literal breakage), then startup profiles (days; affects the first second users feel), then reader warm-up (days; affects the core loop), then offline-first depth (weeks; removes network latency from perception).

Third, **verification discipline compounds**. The techniques used this round — listing diffs before commit, compare-and-swap ref updates, probe semantics, commit-object marker greps, receipts on shared storage — are not overhead; they are what prevented silent corruption and false "done" claims. Small rails, installed permanently, outperform heroics repeated each round.

The user-facing bet this report makes: "slow" and "incomplete" are currently conflated symptoms of network-gated reads, missing-route fallbacks, and cold-start friction. Separate them, fix the layers in the order above, and the perception should improve visibly after the first two waves — before any new feature work.

## Limitations & Caveats

No on-device profiling was performed this round: the performance magnitudes cited are directional values from platform literature and public case studies, not GrapMail measurements; establish before/after baselines (cold start, time-to-first-render) before making quantitative claims about GrapMail. Production probing was anonymous only — route existence (401 vs 404) is the evidence; authenticated end-to-end flows were neither exercised nor possible without credentials. Competitor evidence is third-party and occasionally vendor-authored (source [4] is Spark's own blog; flagged where used); market features and pricing drift quickly, and video-review quotes paraphrase by nature. The platform guidance cited is general — GrapMail's specific stacks (SwiftUI + WKWebView on iOS; Kotlin + WebView on Android) may respond differently, so measurement precedes conviction. The shared drive's intermittent I/O errors constrained repository operations and could recur; the mitigation recipe is documented, but the underlying storage behavior is outside this audit's control. Finally, prior-session notes R8/R9 are permanently lost (the archived zip contains R1–R7 only; E5), so this round's evidence is complete only with respect to what the current repository and receipts record.

## Recommendations

1. **Deploy backend commit `f9c43384` to production now.** Apply the exported patch (or push with credentials), rebuild, restart; run the smoke table — `/grapmail/files`, `/grapsee/auth/verify-human`, `/grapmail/notification-preview` must flip 404→401 and `/health` stay 200; store the output as a receipt. Hours of work; removes the "broken feature" class entirely. (E2, E3)
2. **Regenerate Android Baseline Profiles around the real startup journey** (cold start → inbox first frame), add the generation step to CI so profiles track releases, and measure on low/mid-tier devices where gains are largest. Re-run Macrobenchmark before/after to capture GrapMail-specific numbers. [9][11][12][13][14]
3. **Warm the reader.** Keep one bounded WKWebView instance alive off-screen, preload an inert shell when a mailbox opens, cache sanitized markup per message, keep declarative content rules compiled once, and instrument time-to-first-render so the improvement is measured, not assumed. [16][17][18][19][20][21][22]
4. **Ship offline-first milestone 1**: snapshot-first mailbox render (paint from the local store before any refresh), optimistic triage with an outbox for archive/move/send, and a local search index. Additive to the current model layers; removes network latency from the perception loop. [1][2][8]
5. **Run the parity census every release**: route table vs navigation graph vs dispatch switch across both platforms (the method that caught the iOS Scheduled orphan), verify newly reachable screens on device builds, and review send-later/snooze UX against the benchmark set. [1][2][6]
6. **Install release rails**: automate commit → build → deploy → smoke → receipt; add build-age monitoring per environment; keep the listing-diff and compare-and-swap practices from REPO-HEALTH-2026-11-19.md as permanent release hygiene. (E5, E6)
7. **Continue the iterative rounds (R16+) with the same receipt discipline**: each round refreshes probes, records evidence, and treats "done" as deployed-plus-verified. The queue: send-later UI parity, search operators, push deployment verification, and the instrumentation named above.
## Bibliography

All URLs accessed 2026-11-19.

[1] Superhuman vs Spark: Which Should You Choose? — Efficient App. https://efficient.app/compare/superhuman-vs-spark
[2] Best Email Clients 2026: Superhuman vs Notion Mail vs Spark vs Gmail — video review, YouTube. https://www.youtube.com/watch?v=nd5zP5acUBs
[3] Spark vs Superhuman: Which Email App in 2026? — UseCarly. https://www.usecarly.com/blog/spark-vs-superhuman
[4] Spark vs Superhuman: which one to choose in 2026? — Spark Mail blog (vendor-authored). https://sparkmailapp.com/blog/spark-vs-superhuman
[5] Best Superhuman Alternatives in 2026 (Free & Cheaper Options) — ToolFinder. https://toolfinder.com/comparisons/superhuman-vs-spark-mail
[6] Superhuman vs Spark on Mac: Speed, Cost and Account Support — aiemaily. https://aiemaily.com/blog/superhuman-vs-spark-on-mac
[7] Superhuman vs Spark: Is the Price Gap Worth It? — Catch. https://www.catchagent.ai/compare/superhuman-vs-spark
[8] Does Superhuman work offline? Superhuman vs Microsoft Outlook — Fabric. https://fabric.so/comparison/superhuman-vs-outlook
[9] Accelerating our Android apps with baseline profiles — Meta Engineering. https://engineering.fb.com/2025/10/01/android/accelerating-our-android-apps-with-baseline-profiles/
[10] Improving app performance with Baseline Profiles — Android Developers Blog. https://android-developers.googleblog.com/2022/01/improving-app-performance-with-baseline.html
[11] Baseline Profiles overview — Android Developers documentation. https://developer.android.com/topic/performance/baselineprofiles/overview
[12] Android Baseline Profiles codelab — Google Developers. https://codelabs.developers.google.com/android-baseline-profiles-improve
[13] Expensify App issue #99839 — baseline profile startup measurement, GitHub. https://github.com/Expensify/App/issues/99839
[14] Baseline Profile, where art thou? — ProAndroidDev. https://proandroiddev.com/baseline-profile-where-art-thou-7d995d9e86f2
[15] Baseline profiles — Android Developers talk, YouTube. https://www.youtube.com/watch?v=WWdDzXgrkmg
[16] iOS WKWebView Preload and Cache: Boost Page Load Speed — ZhgChgLi. https://en.zhgchg.li/posts/zrealm-dev/ios-wkwebview-preload-and-cache-boost-page-load-speed-with-efficient-resource-management-5033090c18ba
[17] Tactics to speed up WKWebView rendering of loadHTMLString content — Stack Overflow. https://stackoverflow.com/questions/26684558/tactics-to-speed-up-wkwebview-rendering-of-loadhtmlstring-d-content
[18] Preload strategies using WKWebView — Jonatán Urquiza, talk (YouTube). https://www.youtube.com/watch?v=ZQvyfFieBfs
[19] WKWebView — NSHipster. https://nshipster.com/wkwebview
[20] Why Is WKWebView So Heavy and Why Is Leaking It So Bad? — Embrace. https://embrace.io/blog/wkwebview-memory-leaks
[21] WKWebView — Apple Developer Documentation. https://developer.apple.com/documentation/webkit/wkwebview
[22] The Ultimate Guide to WKWebView — Hacking with Swift. https://www.hackingwithswift.com/articles/112/the-ultimate-guide-to-wkwebview

## Methodology Appendix

**Pipeline.** This report follows a standard deep-research pipeline (scope → plan → retrieve → triangulate → synthesize → critique → package) with citation tracking throughout. Internal audit evidence was integrated alongside external retrieval: repository verification and production probing supply the project-specific facts; public sources supply market and platform references. Every factual claim is cited immediately — internal claims to the evidence register below, external claims to numbered sources.

**Internal evidence register.**

- **E1 — Mobile commit.** `0fb5998e0689faf73089d7bf8e19fc39573aa8f0` (branch `feat/onboarding-native-scenes`; 21 files, +1211/−201). In-commit markers verified: RootView.swift L76 (`mailScheduled`), L833 (closure), L903–904 (dispatch); AttachmentThumb.kt present at HEAD. Receipt: receipts/COMMIT-RECEIPT-MOBILE.md.
- **E2 — Backend commit.** `f9c43384d0d2dff34a56b51714225055ffa42be7` (branch `main`; 2 files, +114). Markers: auth-service index.ts L39 (import) / L131 (route mount); grapmail-files.ts added. Receipt: receipts/COMMIT-RECEIPT-BACKEND.md.
- **E3 — Production probes.** 2026-11-19, anonymous: `/grapmail/files`, `/grapsee/auth/verify-human`, `/grapmail/notification-preview` → 404; `/grapmail/labels` → 401; `/health` → 200. Reproduce via the commands in DEPLOY-INSTRUCTIONS.md.
- **E4 — Applied-state matrix.** ~21 files byte-identical (md5) to the A18/A19 applied-state ledgers across both repositories, including build.gradle.kts, MailboxViewModel.kt, RootView.swift, MailboxModel.swift, and the backend auth index.ts / grapmail-files.ts.
- **E5 — Receipts & archive.** A19 receipts folder (incident log, diffs, commit receipts) plus the A18 fixpack archive; R8/R9 notes are absent from the archive (documented loss).
- **E6 — Storage probes & recovery.** Cross-mount write/read verification (same physical files visible from both mount roots) and the index-recovery recipe now recorded in REPO-HEALTH-2026-11-19.md.

**External evidence handling.** 22 sources were registered in citation order [1]–[22] and cited with immediate attribution. Source mix: market comparisons and reviews [1][3][5][6][7], video reviews [2][18], a vendor comparison flagged as such [4], platform documentation and engineering posts [9][10][11][12][13][14], talks [15][18], a forum thread [17], and web guides/docs [8][16][19][20][21][22]. Claim-support mapping was maintained during writing; vendor bias and paraphrase risks are flagged in Limitations.

**Reproducibility.** All internal claims trace to commits, probes, and receipts on the shared drive; all external claims trace to the numbered sources above. Commands for the key verifications are embedded in the receipts and in REPO-HEALTH-2026-11-19.md.
