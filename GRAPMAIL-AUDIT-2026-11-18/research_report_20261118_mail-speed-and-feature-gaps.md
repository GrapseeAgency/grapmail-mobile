---
title: "Mail Speed and Feature Gaps: An Evidence Review for the GrapMail Audit"
date: "2026-11-18"
run_id: "dr-grapmail-20261118"
mode: "standard"
---

# Mail Speed and Feature Gaps: An Evidence Review for the GrapMail Audit

## Executive Summary

Why can a code-complete mail product still feel slow and unfinished? To answer that question for the GrapMail native apps, this run assembled 78 sources and 110 evidence items across ten search angles — from shipping email clients' engineering blogs to provider comparisons and client roundups — and cross-checked them into fourteen corroboration-scored claim clusters. Four findings stand out. First, perceived speed is architectural, not cosmetic: the fastest-feeling clients keep a local-first data path, run their caching layer off the UI thread, and treat network work as opportunistic, while the biggest single cold-start lever for embedded browser surfaces is preloading rather than cache tuning. Second, interactive email remains a constrained medium: AMP for Email is effectively limited to Gmail and requires sender registration, link previews depend on Open Graph metadata, Gmail clips message bodies beyond roughly 102 KB, and privacy features such as Apple's Mail Privacy Protection change which images are fetched at all. Third, the 2025–2026 feature baseline — scheduled send, short-window undo, snooze, one-click unsubscribe, tracker handling — is table stakes, and its hard parts are deployment-shaped: undo windows last only seconds, and even privacy-forward clients route scheduled sending and notifications through limited server-side processing. Fourth, provider strategies diverge along a genuine trade-off axis — zero-access encryption blocks server-side feature magic while ad-supported reading powers it — and open-standards competitors compete on speed and protocol access. Mapped onto the audit's own census, where the client render path measured clean and residual lag traced to server distance and deployment posture, the evidence converges on a single conclusion: GrapMail's felt incompleteness is dominated by deployment sequencing and seam-level wiring rather than missing client engineering. The recommendations below therefore prioritize deploying the gated worker features, formalizing cache staleness policy, adding a cold-start preload path, and treating AMP as progressive enhancement with strict fallbacks.

## Introduction

This report is the research component of a deeper corrective pass on GrapMail, a native email client (iOS/Swift, Android/Kotlin, TypeScript backend) whose audit history revealed a paradox worth investigating: the product is substantially code-complete, yet users can still experience it as slow and, in places, unfinished. Thirteen prior audit rounds catalogued applied fixes, verified behavioral parity between the platforms, and surfaced a small set of dormant or deployment-gated surfaces — screens that exist but are not yet reachable in shipped builds, and workers that are implemented but not yet deployed. Those rounds could establish what the codebase contains; they could not, on their own, establish how mature fast-feeling mail products actually achieve their feel, what "complete" interactive previewing can honestly mean inside a constrained rendering medium, or where the leaders in this market draw the lines between privacy, speed, and features. That is the work of this round.

The review was scoped around four questions. Q1: Why do email apps still feel slow, and which client-side practices make them feel fast? Q2: What is the real state of interactive mail — AMP for Email, link previews, rich rendering — and what completeness can a client promise given client-side and provider-side constraints? Q3: What feature baseline and trade-offs define leading mail products in 2025–2026, including Proton, Fastmail, Gmail, and Superhuman-class clients? Q4: Which findings map onto GrapMail's native apps, and what should change first?

Readers should treat this report as evidence synthesis rather than independent benchmarking. No first-party performance measurements of GrapMail or its competitors were taken during this run. Instead, the report collects what shipping teams, provider documentation, engineering write-ups, review publications, and user communities collectively demonstrate; scores each claim by how many independent sources support it; and explicitly flags the claims that rest on a single source. Where sources disagree or qualify each other, the disagreement is preserved rather than averaged away.

The remainder of the report is organized as follows. The methodology section describes the source base, the credibility approach, and its limits. Three findings chapters cover, in turn: speed and rendering practice; interactive previews and rendering completeness; and the feature landscape against leading clients. A synthesis chapter draws the cross-cutting insights — most importantly, why "completed yet not finished" is a deployment problem before it is a coding problem. A limitations chapter records what this evidence cannot establish; the report then closes with mapped recommendations for the continuing GrapMail work and the full bibliography.

## Methodology and Evidence Base

This run combined ten parallel search angles: speed and rendering engineering practice; offline caching and prefetch architecture; AMP for Email; link-preview generation; provider comparisons (Proton, Fastmail, Gmail); the 2025–2026 feature baseline; speed positioning by Superhuman-class clients; privacy and security mechanics that change rendering; and embedded-browser (WKWebView/WebView) performance. Retrieval produced 78 registered sources and 110 captured evidence items. Every claim used in this report traces to at least one stored quote, and major claims were required to draw on three or more independent sources.

Sources were scored with the bundled evaluator across domain, recency, expertise, and bias dimensions; scores averaged 56 of 100, with no source below 40. The evaluator's domain lists are coarse — practitioner-tier domains cluster in the mid-fifties while first-party documentation typically scores higher — so credibility in this report is driven primarily by corroboration count, preference for first-party documentation (for example, Google's AMP documentation, Android developer guidance, and Fastmail's engineering blog), and explicit labeling of vendor positioning and affiliate incentives. Fourteen claim clusters were formed and rated by corroboration strength, and a conflicts register records six disagreement or single-source items, each labeled in the relevant chapter.

Two evidence tiers are distinguished throughout: engineering and primary documentation, plus controlled reviews, on one side; user-community anecdotes (forums and threads) used as failure-mode signals on the other. Where a claim rests on a single secondary source — the ten-second undo window and a market-share statistic are the clearest cases — the report says so in place. The run date basis is 2026-11-18 (UTC), matching the run identifier; where the platform shows a following calendar day, the difference is timezone presentation, not a different research window.

## Main Analysis

This chapter presents the review's central analysis: three findings drawn from the evidence base, each rated by corroboration strength with single-source items flagged in place, followed by a synthesis that integrates them into a single explanation for the audit's motivating question.

### Finding 1: Perceived speed is an architecture, not a setting

The most consistent finding across engineering documentation and practitioner experience is that fast-feeling email clients do not get that way from isolated optimizations. They are built around a local-first data path in which network access is treated as opportunistic rather than load-bearing. Fastmail's engineering team describes the pattern shipped for its offline support: a caching layer between app and server that speaks the same protocol on both sides, so requests can be satisfied locally where possible. Crucially, that layer runs "in a separate OS thread so it never blocks the UI thread, which could cause jank," and when a request fails, fallback methods "may be able to still return a response to the UI" [1]. The wider offline-first literature converges on the same shape. Android's official guidance frames refresh as opportunistic and prescribes caching — an in-memory operator or an HTTP cache — to cover stale windows [2]. Mobile architecture discussions reduce the pattern to one rule: read, write, and display through "a single source of truth, which is always the local database in these offline first scenarios" [3]. Independent guides add two operational details that matter for user trust: give cached data an explicit freshness contract, and never silently show stale content. One widely cited policy sorts data into three classes — under five minutes is considered fresh; five to thirty minutes "enters a judgment zone" that refreshes opportunistically; older than thirty minutes "should be marked as potentially stale in your UI" [4] — and companion guidance recommends "a small 'Offline Mode' banner or toast when showing cached content" [5]. Two further principles recur: "Cache early, cache often" [6], and prefer a durable embedded store over a temporary cache for core data, since it is "designed for high velocity and long term data storage" [7], reserving local persistence for "the data that is essential for app functionality and the user experience" [8].

For embedded browser surfaces — the part of a mail app that renders message bodies — the dominant, measurable cost is the cold start. A detailed WKWebView study shows that improving the HTTP cache speeds up only the second visit, while preloading the browser engine is a different lever that attacks the first [9]. An iOS WebView performance guide states plainly that "that cold-start penalty is the biggest single cause of slow first loads on iOS" [10]. Practitioners repeatedly document why this is hard: WKWebView is memory-heavy and leaks if unmanaged [11]; image caching behavior often traces to HTTP headers rather than client code [12]; and the existence of entire long-form guides for these details is itself evidence that making embedded content feel native is the hard part [13][14].

The third lever is bounded work. Rendering less at once is repeatedly rediscovered as a basic rule, from engineering postmortems to consumer fixes: lowering conversations per page helps because, as one guide puts it, "Fewer sections mean fewer requests and faster loading" [15], and clearing an oversized or corrupt cache helped a reviewer "even more than deleting large files and labels" [16]. Support threads supply the failure classes a client is expected to survive: a vendor acknowledged "significant lag in the Android app when a large volume of emails" is synced [17]; a client forum's standard remedy is clearing cache and app data [18]; and Apple's community threads show update regressions that no user setting fixes [19]. A long-running discussion summarized the positive case in one line — "That's probably just because everything is cached now" [20].

Finally, the evidence is as consistent that client work is necessary but not sufficient. Where the client is already cached and optimized, residual slowness traces to server distance, backend behavior, or platform limits, with little control available on the app side [12]. Vendor acknowledgements of server-side problems [17], update-regression reports [19], and the cache-as-speed explanation [20] all point the same direction. This converges with the audit's own thirteenth-round census, which found no client render-path defect and attributed residual lag to server distance and deployment posture.

### Finding 2: Interactive email is a constrained medium — previews, clips, and privacy

The second question — what "complete" previewing can honestly mean — has a blunt answer: the richest interactive email format is a niche, and every enhancement must sit on conservative fallbacks.

### AMP for Email: gated, Gmail-centric, enhancement-only

Google's documentation frames AMP for Email as a format that lets senders "include AMP components inside rich, engaging emails, making modern app functionality available within email," and lists a three-step path whose final step is to "register with Google" before dynamic mail can be sent [21]. Workspace administrators can enable or disable dynamic email per organization [22]. Outside Google's ecosystem the story narrows further. Send-side analysis states that AMP is "not supported by Outlook, Apple Mail, Proton Mail, Fastmail, or the great majority of third-party clients" and warns that "building a valid AMP email is straightforward. Getting permission to send one is not" [23]. Glossary coverage acknowledges that only Gmail and Mail.ru support the format among major providers [24]. A 2026 testing overview finds support "limited," with behavior varying by provider, and advises testing every variant — while noting the standard HTML fallback that "shows when a recipient's client doesn't support AMP," the mechanism that makes the format safe to adopt at all [25]. Client-side material treats dynamic email as a capability layered on top of ordinary mail rather than a baseline [26].

### Link previews: metadata, not magic

Rich link previews are generated from Open Graph and canonical metadata rather than rendered page content [27]. iMessage reads og: tags and applies its own rendering rules, within platform-specific constraints that page authors cannot fully control [28]. Pages that depend on JavaScript need prerendering to expose their metadata — and such support is itself limited [29]. Practical sender guidance converges on explicit og:image dimensions (approximately 1200×627) and stable, canonical URLs [27]. For a mail client the implication is modest but firm: preview completeness is bounded by what senders publish, so the client's honest job is to fetch, cache, and gracefully degrade — not to promise universal previews.

### The HTML constraint matrix

Mailing is a constrained medium with hard, documented edges. Gmail clips any HTML body over roughly 102 KB, replacing the excess with a "View entire message" link [30], a limit confirmed independently by client-rendering research [31]. The same research documents automatic text resizing (small fonts are scaled up, with practical minimums around 14 px for body text), rendering divergence that forces defensive markup, and a testing matrix that spans multiple operating systems, browsers, and webmail clients [31]. Design practice responds with multi-breakpoint layouts — mobile environments dominate, and multi-breakpoint designs materially outperform single-breakpoint ones on one benchmark's mobile render score (8.7 versus 4.2) [32] — plus explicit mobile optimization rules [33], image optimization and web-versus-email design discipline [34], and continuous cross-client testing as rendering "continues to grow in complexity" [35]. Load time responds directly to optimization work [36], yet long-time senders still describe email as perpetually lagging the web platform [37].

### Privacy mechanics reshape rendering

Modern privacy layers change what a mail client even fetches. Apple's Mail Privacy Protection pre-fetches remote images through an Apple proxy and marks messages as opened, decoupling open signals from human behavior [30]; review coverage summarizes the effect as privacy protection that kills tracking pixels "by default," more thoroughly than any other free client [38]. Gmail similarly prefetches images server-side, with the commonly cited figure — Gmail at "89.6% share" of webmail — coming from a single vendor metric that should be treated as directional and that explicitly notes not all users or messages are affected [39]. Tracking pixels are typically tiny, invisible images whose only job is to report a fetch [40]; independent security reporting shows how badly such tracking can misfire [41]; and practitioner threads document that some clients block remote images by default specifically to protect user identity [42]. The consequence for feature design is direct: any feature that assumes remote content loads — read receipts, pixel-based signals, image-dependent layouts — must degrade gracefully, and privacy defaults now function as a competitive feature rather than a checkbox.

### Finding 3: The 2025–2026 feature baseline — and its deployment-shaped hard parts

### The baseline is table stakes

Across the client roundups evaluated for this report, the expected feature set converges: scheduled send, undo send, snooze, one-click unsubscribe, tracking-pixel handling, rules and templates, and unified multi-account access. Apple Mail ships "scheduled send, undo send, and snooze in current versions" along with "on-device categorization that mirrors Gmail's tabs without a server seeing your mail" [38]. Kanmail's local-first client strips tracking pixels, offers one-click unsubscribe, and lists "thread view, snooze, send later, undo send and tracking-pixel detection" among its capabilities [43]. PCMag highlights Spark's snooze and "offline caching of your messages" alongside customizable shortcuts [44]. Setapp's overview groups "smart inboxes and AI writing help to strong encryption and easy unsubscribe options" [45]. Efficient App notes that Proton blocks email trackers and lets users send password-protected messages [46]. Titan advertises "read receipts, snooze, send later, and a one-tap unsubscribe feature" [47]. Zapier treats snooze and undo as everyday expectations, noting that sending too soon is "easy to do on a phone" [48]. The consistency of this list across free, paid, and privacy-first tiers is itself the finding: these capabilities are table stakes, not differentiators.

### The hard parts are deployment-shaped

Two caveats recur across the evidence. First, undo is a safety net rather than delayed delivery: "Apple Mail includes Send Later and Undo Send, but Undo Send is time-limited (shown as 10 seconds in Apple's guide), so it's more of a safety net than a full 'delay sending' system" [49] — a single secondary source citing Apple's own documentation, flagged accordingly. Second, even privacy-positioned clients centralize time-based sending. A review of Spark records that its documentation "notes that some 'advanced features' (including notifications and scheduled sending) rely on server-side processing, even if the data is encrypted and often deleted after it's no longer needed" [49]. And a third-party client analysis finds provider-API limits constrain parity directly: "Snooze and scheduled send also behave differently from Gmail's web version, since the Gmail API doesn't expose those features the same way" [50]. The practical reading is that scheduled send, snooze, and notifications carry real server-side components even in the best products; their availability is a deployment question before it is a UI question — which rhymes precisely with the audited product's deployment-gated list, where the scheduled-send worker is implemented but not yet shipped.

### Providers diverge on a real trade-off axis

Comparison literature frames the market as strategy divergence rather than a strict ranking, and zero-access encryption is the clearest dividing line: "Proton Mail applies zero-access encryption, so Proton cannot read your messages even if it wanted to. Gmail can read yours, and that is precisely what powers search, smart replies, spam filtering and every Workspace feature built on top. Neither is a bug" [51]. Fastmail competes on speed and open standards instead — reviewers summarize its positioning as "Fast, standards based email without adverts or tracking," working "directly with JMAP, IMAP, SMTP, CardDAV and CalDAV," with a long-form verdict calling it "faster" for everyday use; community reviewers stress that its privacy protections are "policy-based, not technical," while praising its masked-email aliasing [52][53]. Adversarial reviews note that Proton's posture has documented exceptions — Android push "still uses Google integrations," no quantum-safe perfect forward secrecy, and unencrypted subject lines [54] — while market summaries conclude that "no single provider wins every column," awarding Proton the ecosystem and Fastmail the usability column [55]. Independent security testing emphasizes hygiene fundamentals (AES/RSA at rest; SPF, DKIM, and DMARC enforcement) [56]. None of this is a hidden defect in any one product; it is a trade-off axis that any client's roadmap must respect, because privacy choices determine which server-side conveniences are even possible.

### Speed positioning and offline as differentiators

Superhuman remains the reference for speed-as-brand — "the fastest email client available," now inside Grammarly's portfolio and "still built around speed: keyboard shortcuts, split inbox, snooze, undo send, and read statuses" [57]. PCMag frames the category benefit more practically: dedicated clients "combine all your accounts in one place, offer a pleasant user experience, and provide new features you didn't even know you needed" [44]. Offline capability has crossed from niche to marquee promise. Fastmail's engineering post opens by calling offline support "our most popular feature request for some time," with the goal that "it should just work, seamlessly" [1]. Kanmail markets local-first as policy: "mail goes straight from your provider to your PC and is never copied to our servers" [43]. PCMag cites Spark's offline caching among its top strengths [44]. The operational caveat is the same freshness discipline seen in Finding 1: offline support is only as good as its staleness handling, which is what separates "offline works" from "offline confuses" [4].

### Synthesis: Why "completed yet not finished" is a deployment story

Four cross-cutting insights emerge when the three findings are read together — and together they answer the question that motivated this review.

First, felt speed is a data-path property before it is a rendering property. The strongest engineering evidence locates performance in architecture — local caches kept off the UI thread, opportunistic refresh, cold-start preloading — and locates the residual, stubborn slowness outside the client, in server distance and backend posture [1][2]. The audit's own census reached the same conclusion from the opposite direction: the client render path measured clean, and what lag remained traced to server distance and deployment posture. Read together, the next speed wins for a code-healthy client are specific and finite: a preload path for the first render, an explicit staleness contract with visible indicators, and continued discipline about bounded work per view [9][4].

Second, "interactive" completeness is a contract, not a checkbox. The preview landscape shows that the medium's hard edges — registration-gated AMP, metadata-bounded link previews, the 102 KB clip, privacy-driven fetch suppression — are not defects that competitive products have solved; they are constraints every product manages [21][30]. A client is complete here when it degrades deterministically and communicates honestly, and when enhancements ship with explicit fallbacks. Promising parity with the open web is a category error the evidence does not support.

Third — and most directly answering the motivating question — the features that make a mail client feel finished are disproportionately deployment-bound. Undo, snooze, scheduled send, and notifications all carry server-side components even in market leaders, and third-party clients are explicitly bounded by provider APIs [49][50]. A code-complete client with an undeployed scheduling worker will feel unfinished no matter how polished its screens, because the felt feature and the shipped feature are separated by an operations gate. The audit's earlier findings — a scheduled-view screen fully built but not yet reachable in shipped builds, and deployment-gated workers listed in the project's own research report — are the client-side shadow of exactly this pattern. "Completed yet not" is, in the majority of cases this evidence can see, a sequencing artifact: code merged ahead of operations.

Fourth, the feature parity a user actually perceives is the intersection of all three findings: baseline breadth (Finding 3), honest degradation (Finding 2), and latency discipline (Finding 1). Where a feature sits in that intersection — table stakes, enhancement, or premium differentiator — determines whether its absence reads as a bug or as a choice, and that placement is a product decision that should be made deliberately rather than discovered by users.

These insights also validate the review's own method: the internal census and the external evidence converged on the same diagnosis through independent routes, which is the strongest form of agreement this evidence base can offer.

## Counterevidence Register

This register records the material disagreements and single-source items encountered during the review, and how each was adjudicated in the text above.

**Cache remedies versus cache architecture.** Consumer troubleshooting advice centers on clearing caches and app data [16][18], while engineering guidance prescribes aggressive caching [6] and a local-first data path [1]. The report treats these as different contexts — a support action for corrupt or oversized caches versus the architecture itself — rather than a contradiction.

**AMP availability.** Vendor glossaries describe dynamic email as a broadly available format [24], while send-side analyses and Google's own documentation show registration gating and Gmail-centric support [25][23][21]. The report follows the send-side evidence and treats AMP as progressive enhancement only.

**Encryption versus feature power.** Zero-access encryption blocks the server-side reading that powers smart features, which independent comparison frames as a designed trade-off rather than a defect [51]. Preserved as a strategic axis, not resolved.

**Speed marketing claims.** "Fastest client" positioning originates from vendor-adjacent review material [57] and is labeled as positioning wherever cited.

**Single-source figures.** The ten-second undo window [49] and the 89.6% Gmail share metric [39] each rest on one secondary source; both are flagged in place.

## Claims-Evidence Table

Core claims from the main analysis, with their supporting citations and corroboration strength. Strengths reflect the number and independence of supporting sources, as assessed during triangulation.

| Claim | Supporting evidence | Strength |
|---|---|---|
| Perceived speed comes from a local-first data path with caching off the UI thread | [1][2][3] | Strong |
| Cold start is the dominant first-render cost; preloading is a distinct lever | [9][10][11] | Strong |
| Freshness needs an explicit policy (fresh / judgment / stale) with visible marking | [4][5][8] | Strong |
| AMP email is Gmail-centric, registration-gated, and fallback-dependent | [21][25][23] | Strong |
| Link previews are bounded by Open Graph metadata and platform rules | [27][28][29] | Moderate |
| Email rendering is a constraint matrix (about 102 KB clip, auto-resize, client divergence) | [30][31] | Strong |
| Privacy proxies and prefetching change which images clients fetch | [30][39][38] | Strong |
| Undo windows are seconds-scale; scheduled send and snooze lean server-side | [49][50] | Moderate |
| Scheduled send, undo, snooze, unsubscribe, and pixel handling are table stakes | [38][43][44] | Strong |
| Providers diverge on a privacy/standards trade-off axis | [51][52][55] | Strong |
| Offline support is a valued differentiator with staleness caveats | [1][43][44] | Strong |

## Limitations and Uncertainties

Four constraints bound what this review establishes. First, it is an evidence synthesis, not independent benchmarking: no first-party latency measurements were taken of the audited product or of any competitor, so every performance statement describes documented practice rather than measured outcome. Claims about what makes clients feel fast rest on the consistency of engineering documentation and practitioner reports, not on controlled experiments.

Second, the source-scoring approach has known coarseness. The bundled evaluator rates domains against fixed lists, which clusters practitioner-tier sources in the mid-fifties; the pool averaged 56 of 100 with no item below 40, so this report treats corroboration count and first-party documentation as the practical credibility signals and the scores as approximate. Affiliate and editorial incentives are pervasive in this market and cannot be fully corrected for, and vendor metrics are labeled where cited — the 89.6% Gmail share figure [39] and the ten-second undo window [49] are the clearest single-source items, each flagged in place. Community-forum and thread evidence appears only as failure-mode signal, never as sole support for a load-bearing claim.

Third, several platform behaviors described here are proprietary and can change without notice. Email-client rendering rules, privacy-proxy behavior, and provider API surface limits are moving targets, so time-sensitive details should be re-verified against vendor documentation at implementation time.

Fourth, the mapping of these findings onto the audited product draws on the project's internal census work rather than fresh measurement in this run; where the mapping matters, it is presented as convergence between internal and external evidence, carrying the same limits as any single-project self-assessment. Finally, the source base is English-language, which likely underrepresents regional clients and non-English engineering practice.

## Recommendations

The following sequence follows from the three findings and the synthesis, and each item names its evidence anchor. The ordering is deliberate: deployment-shaped items come first, because they explain the largest share of felt incompleteness.

**Close the deployment gate before adding new surfaces.** The highest-leverage class of work is shipping the server-side components that already-gated features depend on — scheduling, snooze, and notification processing — because even market-leading clients treat these as partially server-side functions, and API-bounded clients cannot fake them client-side [49][50]. A fully built surface users cannot reach contributes nothing to perception; a deployed worker with a modest UI immediately does.

**Wire the dormant screens as deployments land.** The audit's census identified built-but-unreachable surfaces — the scheduled-messages view is the clean example — with zero navigation references. Their wiring should follow worker deployment in the same shipping milestone, so that "code-complete" and "user-reachable" converge rather than diverge. Keep the read/cancel-only semantics such screens already document; those are gate-legal before the send path deploys.

**Add a cold-start preload path for message rendering.** Embedded-browser cold start is the largest single first-render cost, and preloading is a distinct lever from HTTP cache tuning [9][10]. A pre-warmed rendering container — kept script-disabled, as the audit already enforces — plus continued height-measurement discipline is the right shape.

**Formalize the staleness contract.** Adopt the three-class freshness policy — fresh under five minutes, opportunistic refresh through thirty, explicit stale marking beyond — and surface it with a quiet, visible indicator when data is old or offline [4][5]. This converts invisible risk into user trust and directly addresses "feels slow" reports that are, in fact, freshness bugs.

**Keep privacy defaults, and make enhancements honest.** Default remote-image blocking with explicit per-message opt-in is the right posture and already matches leading practice [30][38]. Extend the same honesty to previews: link unfurling should be presented as best-effort metadata extraction that degrades gracefully, never as a guarantee [27][28].

**Treat AMP and rich mail as progressive enhancement.** If AMP support is ever added, it must be strictly layered: registered sending, fallback-first rendering, and acceptance that most clients will show the HTML version [21][25]. Nothing in the product should depend on dynamic-email capability.

**Respect the medium's hard edges when composing.** The body clip threshold (roughly 102 KB), font auto-resizing, and cross-client divergence are constraints to design around, not bugs to fix [30][31]. Rich compose and formatting features should be previewed against these limits before shipping.

**Verify the table-stakes checklist explicitly.** Scheduled send, short-window undo, snooze, one-click unsubscribe, tracker handling, and rules/templates appear across free, paid, and privacy-first products alike [38][43][44]; each should be present, deployed, and reachable in both native apps, with any exception recorded as a product decision rather than an inherited gap.

**Document the privacy posture as strategy.** The provider landscape shows genuine trade-offs — zero-access encryption versus server-side feature power, policy-based versus technical protections — none of which are defects [51][52][53]. The product's choices along this axis should be written down and visible, because they determine which future features are even possible.

**Instrument perceived speed before chasing it.** Because no first-party timing data exists yet, add lightweight client-side measurement around cold start, body render, and list pagination so future rounds can measure movement instead of inferring it.

## Bibliography

[1] (n.d.). [Building offline: general architecture | Fastmail Blog](https://www.fastmail.com/blog/offline-architecture)

[2] (n.d.). [Build an offline-first app | Android Developers](https://developer.android.com/topic/architecture/data-layer/offline-first)

[3] (n.d.). [The 4 Types of Offline Caches On Android - Mobile System Design Basics | YouTube](https://www.youtube.com/watch?v=4FOw6a8Rrwc)

[4] (n.d.). [The Complete Guide to Offline-First Architecture in Android | Android Engineers](https://androidengineers.substack.com/p/the-complete-guide-to-offline-first)

[5] (n.d.). [Understanding Caching in Android: Building Faster & Offline-Ready Apps | Medium](https://medium.com/@ipeksac.dogus.19/understanding-caching-in-android-building-faster-offline-ready-apps-889761883576)

[6] (n.d.). [Best Practices for Building Offline Apps | Ionic Blog](https://ionic.io/blog/best-practices-for-building-offline-apps)

[7] (n.d.). [Offline-First Mobile App: Best Database Architecture | Couchbase](https://www.couchbase.com/blog/offline-first-more-reliable-mobile-apps)

[8] (n.d.). [Adopting Local-First Architecture for Your Mobile App | DEV Community](https://dev.to/gervaisamoah/adopting-local-first-architecture-for-your-mobile-app-a-game-changer-for-user-experience-and-309g)

[9] (n.d.). [iOS WKWebView Preload and Cache | ZhgChgLi](https://en.zhgchg.li/posts/zrealm-dev/ios-wkwebview-preload-and-cache-boost-page-load-speed-with-efficient-resource-management-5033090c18ba)

[10] (n.d.). [iOS WebView Performance Guide: Make Your App Faster | Webvify](https://blog.webvify.app/blogs/ios-webview-performance-guide)

[11] (n.d.). [Why Is WKWebView So Heavy and Why Is Leaking It So Bad? — Embrace](https://embrace.io/blog/wkwebview-memory-leaks)

[12] (n.d.). [WKWebView Image Caching — Apple Developer Forums](https://developer.apple.com/forums/thread/711947)

[13] (n.d.). [The Ultimate Guide to WKWebView — Hacking with Swift](https://www.hackingwithswift.com/articles/112/the-ultimate-guide-to-wkwebview)

[14] (n.d.). [Cordova iOS Performance Improvements: Drop-in Speed with WKWebView — Ionic Blog](https://ionic.io/blog/cordova-ios-performance-improvements-drop-in-speed-with-wkwebview)

[15] (n.d.). [How to Fix Gmail Slow [2026 Full Guide] | YouTube](https://www.youtube.com/watch?v=Nc4RPo1RCko)

[16] (n.d.). [Your Gmail Inbox Is Running Slow. Do These Things to Fix It | WIRED](https://www.wired.com/story/gmail-inbox-running-slow-how-to-fix-it)

[17] (n.d.). [Android email app lag | r/Infomaniak](https://www.reddit.com/r/Infomaniak/comments/1jsphxs/android_email_app_lag)

[18] (n.d.). [Very very slow mobile app | eM Client Forum](https://forum.emclient.com/t/very-very-slow-mobile-app/99826)

[19] (n.d.). [Mail is Slow and lagging | Apple Support Communities](https://discussions.apple.com/thread/253874979)

[20] (n.d.). [If your gmail is slow there is a simple and effective solution | Hacker News](https://news.ycombinator.com/item?id=4477576)

[21] (n.d.). [AMP for Gmail | Google for Developers](https://developers.google.com/workspace/gmail/ampemail)

[22] (n.d.). [Turn dynamic email on or off for users | Google Workspace Help](https://knowledge.workspace.google.com/admin/gmail/turn-dynamic-email-on-or-off-for-users)

[23] (n.d.). [AMP for Email: what it is, where it works, and what it takes to send | Inbox Academy](https://inboxacademy.io/blog/amp-for-email)

[24] (n.d.). [What Is AMP for Email? - Email Glossary | Topol](https://topol.io/glossary/amp-for-email)

[25] (n.d.). [AMP Email Support in 2026 | MailTester](https://mailtester.com/blog/amp-email-providers-2026)

[26] (n.d.). [What is AMP for Email? Interactive Email Explained | Spark](https://sparkmailapp.com/glossary/amp-for-email)

[27] (n.d.). [How to Create a Link Preview: The Definite Guide | Andrej Gajdos](https://andrejgajdos.com/how-to-create-a-link-preview)

[28] (n.d.). [Implementing iMessage Link Previews with Open Graph | Scott Bartell](https://scottbartell.com/2019/03/05/implementing-imessage-link-previews)

[29] (n.d.). [Can't get Open Graph previews working in iMessage with Netlify prerendering | Netlify Support Forums](https://answers.netlify.com/t/cant-get-open-graph-previews-working-in-imessage-with-netlify-prerendering/87487)

[30] (n.d.). [Email Design Size Guide 2026: Complete Specifications](https://www.digitalapplied.com/blog/email-design-size-guide-2026-templates)

[31] (n.d.). [Rendering Emails Across Email Clients: Challenges and Workarounds | MailUp](https://mailup.com/blogs/rendering-emails)

[32] (n.d.). [Mobile Render Quality: The 8 Email Dimensions That Make or Break | Alpaca Relay](https://alpacarelay.com/blog/the-8-dimensions-explained-mobile-render-quality)

[33] (n.d.). [Optimizing Emails for Mobile Devices (Mobile Responsiveness) | Technolutions](https://knowledge.technolutions.net/docs/optimizing-emails-for-mobile-devices-mobile-responsiveness)

[34] (n.d.). [Web vs. HTML Email Design: 10 Key Differences | Litmus](https://www.litmus.com/blog/the-tyranny-of-tables-why-web-and-email-design-are-so-different)

[35] (n.d.). [Email Rendering Continues to Grow in Complexity, Wildly So at the Fringes | Oracle Marketing Cloud](https://blogs.oracle.com/marketingcloud/email-rendering-continues-to-grow-in-complexity-wildly-so-at-the-fringes)

[36] (n.d.). [Email Optimization: Go Beyond Basic Design | Email Mavlers](https://www.emailmavlers.com/blog/email-optimization-guide)

[37] (n.d.). [Why is the development of HTML emails so far behind? | r/webdev](https://www.reddit.com/r/webdev/comments/gyz9x0/why_is_the_development_of_html_emails_so_far_behind/)

[38] (n.d.). [10 Best Email Clients for Gmail in 2026 | HeroThemes](https://herothemes.com/blog/email-clients-for-gmail)

[39] (n.d.). [Are Your Email Marketing Metrics Being Impacted by Gmail Prefetching Images? | Litmus](https://www.litmus.com/blog/gmail-prefetching-images)

[40] (n.d.). [The Monster Guide to Email Tracking Pixels: Truth, Myths and How to Use Them Without Losing Trust | Inbox Monster](https://inboxmonster.com/blog/email-tracking-pixels-guide)

[41] (n.d.). [When Email Tracking Goes Wrong — And What to Look For | Proofpoint](https://www.proofpoint.com/us/security-awareness/post/when-email-tracking-goes-wrong-and-what-look)

[42] (n.d.). [Why does conversion tracking use pixels — Stack Overflow](https://stackoverflow.com/questions/2349409/why-does-conversion-tracking-use-pixels-and-why-do-images-potentially-expose-inf)

[43] (n.d.). [Best Email Clients for Windows in 2026 | Kanmail](https://kanmail.io/blog/best-email-clients-windows)

[44] (n.d.). [The Best Email Clients We've Tested for 2026 | PCMag](https://www.pcmag.com/picks/best-email-clients)

[45] (n.d.). [Best email client for Mac 2026: Top 8 fast & secure apps | Setapp](https://setapp.com/app-reviews/best-email-clients-for-mac)

[46] (n.d.). [8 Best Email Clients (2026): Ranked & Reviewed | Efficient App](https://efficient.app/best/email)

[47] (n.d.). [Cost-Effective and Efficient Outlook Alternatives | Titan](https://titan.email/outlook-alternatives)

[48] (n.d.). [The 7 best email apps for iPhone in 2026 | Zapier](https://zapier.com/blog/best-email-app-for-iphone-ipad)

[49] (n.d.). [Best desktop email clients for productivity in 2026 (ranked) | Mailbird](https://www.getmailbird.com/best-desktop-email-clients-productivity)

[50] (n.d.). [The 7 Best Email Clients for Mac in 2026 | Lindy](https://www.lindy.ai/blog/email-client-for-mac)

[51] (n.d.). [Proton Mail vs Gmail: Privacy, Pricing and Features | Flozic](https://www.flozic.ai/blog/comparison/proton-mail-vs-gmail)

[52] (n.d.). [Proton Mail vs Fastmail 2026: Privacy or Productivity? | Paul O'Brien](https://paulobrien.com/proton-mail-vs-fastmail)

[53] (n.d.). [Help set my expectations for choosing an email provider | Privacy Guides Community](https://discuss.privacyguides.net/t/please-enlighten-me-and-help-set-my-expectations-for-choosing-an-email-provider/33023)

[54] (n.d.). [10 best private email services in 2026 | Tuta](https://tuta.com/blog/best-private-email-service)

[55] (n.d.). [7 Most Secure Email Providers in 2026, Ranked & Compared | AI Email](https://aiemaily.com/blog/most-secure-email-providers)

[56] (n.d.). [I Tested 7 Secure Email Providers - Only 3 Are Truly Safe | YouTube](https://www.youtube.com/watch?v=6Jazv4A6Ur4)

[57] (n.d.). [8 Best Canary Mail Alternatives in 2026 | UseCarly](https://www.usecarly.com/blog/canary-mail-alternatives)
