# Research Report: New Feature Roadmap for GrapMail's Native iOS and Android Apps

**Date:** November 19, 2026 · **Mode:** deep · **Baseline:** grapmail-mobile @ `0fb5998e` (iOS + Android), grapmail-backend @ `f9c43384` · **Evidence base:** 46 registered sources, 86 quote-level evidence entries

## Executive Summary

GrapMail's mobile apps are confirmed native: the audited snapshot of the repository at commit `0fb5998e` contains 203 source files (48 Swift, 75 Kotlin), no cross-platform UI framework artifacts — both apps are built entirely on first-party toolkits [39]. That foundation is what makes this roadmap practical to execute: nearly every candidate below relies on first-party platform capabilities that are documented, stable, and already shipping on iOS and Android.

This report identifies **27 candidate features** in three tiers (P0 parity-critical, P1 strategic, P2 opportunistic). Cross-referencing platform documentation, competitor products (Apple Mail, Gmail, Spark, Superhuman, Proton, Canary), 2026 threat telemetry, and the internal audit, three gap families dominate:

- **System-integration gap.** GrapMail currently ships none of: home/Lock-screen widgets (WidgetKit on iOS [41], Glance on Android [4]), Live Activities [2], App Intents/Siri/Shortcuts exposure [3], inline notification reply [5], Android per-app language [6], or Material You dynamic color [7]. Each mechanism is first-party, low-risk, and immediately user-visible.
- **Trust-and-safety gap.** 2026 telemetry shows 1 in 3 email messages is malicious or unwanted, with 70% of malicious PDFs carrying QR codes [27], and Microsoft observed roughly 7.3 billion email-based phishing threats in Q3 2026 alone [28]. Rival apps answer with phishing checks [26], tracker blocking [24], and one-click unsubscribe [25]; the audit found none of these user-facing protections in GrapMail yet [39].
- **On-device intelligence opportunity.** Apple now exposes an on-device model for "structured generation, summarization, and classification" to third-party apps [34][30], and Apple Mail itself summarizes and categorizes mail "all on-device" [33]. Gmail's 2026 wave — AI Overviews, mobile summary cards, Suggested Replies — shows the same direction with cloud models [43][44].

**Primary recommendation:** ship the six P0 items first — inline notification reply (F01), home/Lock-screen widgets (F02), one-click unsubscribe completion (F03), block-sender and report-spam flows (F04), app lock (F05), and the snoozed-mailbox rollout (F06) — then commit to an on-device summarization and categorization preview in the following cycle.

**Confidence level:** High for platform feasibility, since every mechanism is documented first-party API surface [1][2][3][4][5][6][7]. Medium for AI features on lower-end devices, where on-device model availability varies by device generation [9][38].

---

## Introduction

### Research Question

What new features should GrapMail's native iOS and Android apps add next, and in what priority order — grounded in the 2026 mail-client landscape, first-party platform capabilities, security evidence, and the apps' current state?

The question follows a completed nativeness audit that confirmed the apps are pure native [39]; the natural next step is deciding what to build on that foundation. This report is research-only: it proposes and substantiates with citations; it does not modify product code.

### Scope & Methodology

**Scope.** Feature candidates implementable with first-party frameworks only (SwiftUI/UIKit, WidgetKit, ActivityKit, App Intents; Jetpack Compose/Glance, Android platform services), consistent with the project's native-only guardrail. Excluded: server architecture changes beyond the already-identified snoozed-mailbox route [39], third-party-provider connectors (GrapMail is its own mail provider, so "import" means leaving other providers for GrapMail), and pricing or business-model questions.

**Method.** Six evidence streams: (1) repository census — 203 files exported at `0fb5998e` and swept for roughly 30 capability markers, producing a present/absent baseline [39]; (2) platform documentation for the exact APIs that would implement each candidate — widgets [41][1], Live Activities [2], App Intents [3], notifications and direct reply [5], per-app language [6], dynamic color [7]; (3) competitor teardown — Apple Mail [9][33], Gmail [43][44], Spark [23], Superhuman [17], Proton [24], Canary [25][26]; (4) security and trust research — Barracuda's 2026 threat report [27], Microsoft's Q3 2026 email landscape [28], FTC consumer guidance [29]; (5) accessibility guidance [31][32]; (6) on-device AI material [30][34][35][36][37][38]. In total, 46 sources are registered for this run, with 86 quote-level evidence entries backing every factual claim used in this report.

**Time period.** Platform documentation and competitor material as of November 2026; security data from 2026 reports; AI capability material from 2024–2026.

### Key Assumptions

1. The audit baseline is commit `0fb5998e`; "absent" means "no implementation found in that snapshot's sources", not a statement about intent or other branches [39].
2. GrapMail is its own provider (no third-party OAuth/IMAP sync in scope), so migration features refer to bringing users from other providers to GrapMail [39].
3. Effort scale: **S** ≈ one sprint for one platform team; **M** ≈ two to four sprints; **L** ≈ a full cycle or multi-team work. Estimates are directional, not commitments.
4. Tier definitions: **P0** = parity-critical, low-to-medium effort (users of rival apps get these today); **P1** = strategic differentiators; **P2** = opportunistic polish.
5. Where platform features have availability caveats — Apple Intelligence device requirements and region limits [9], on-device model sizes on Android [38] — the roadmap treats them as opt-in additions with graceful fallbacks, not hard dependencies.

### How to Read the Roadmap

Each candidate carries an ID (F01–F27), a tier, an effort estimate, the native mechanism that implements it, and the evidence supporting its value. The findings sections below build the case in six groups (baseline, market bar, system integration, trust and safety, on-device intelligence, everyday productivity). The consolidated table in **Prioritized Feature Roadmap** is the actionable list.

---

## Main Analysis

### Finding 1: The Baseline — What GrapMail Already Ships

Before recommending anything, the audit pinned down exactly what exists at commit `0fb5998e`, so that nothing below duplicates shipped work and every recommendation extends a real surface. The repository census exported 203 files (48 Swift, 75 Kotlin) and swept them for roughly 30 capability markers [39].

The present-feature inventory spans five clusters [39]:

- **Core mail actions.** Snooze with undo-triage pills, send-later scheduling wired to the backend `scheduled` route [39][40], an undo-send window, and per-thread mute.
- **Organization.** Labels, search with advanced client-side filters, signatures (global and per-account), and sender aliases.
- **Notifications.** Mail-notification action buttons on both platforms (archive, mark read, reply, more); on iOS, a notification-service extension renders content previews and sender avatars; both apps schedule background refresh (iOS via BGTaskScheduler polling).
- **Account security foundations.** End-to-end encryption plumbing, session management with 2FA and backup codes, and human-verification (CAPTCHA) flows.
- **Platform citizenship.** Share sheet, `mailto:` handling, QuickLook attachment previews, and an update banner.

The same sweep established the gaps this roadmap targets: neither app tree contains widgets, Live Activities, App Intents, per-app language support, dynamic color, biometric app lock, inline notification reply, phishing warnings, templates, smart replies, or mail categories [39]. The snoozed-mailbox screen is implemented in-app but waits on a backend `?box=snoozed` route that is not yet deployed [39].

Two planning consequences follow. First, several P0 candidates are small closures rather than new systems — inline reply extends the existing notification actions [5], one-click unsubscribe completes header parsing the apps already do [8][25], and app lock extends the security layer [24]. Second, the absence list is exactly where rivals concentrate their 2026 differentiation, as the next finding documents.

### Finding 2: The 2026 Market Bar — What Leading Mail Apps Treat as Standard

Six reference products — two platform owners and four independents — define current user expectations.

**Apple Mail (iOS 27).** Long-pressing a message exposes Siri actions that summarize, find items, track packages, or read flight numbers; Write with Siri composes; Smart Replies are "tuned to your writing style"; and search "ranks results by relevance and intent" rather than keywords and recency [9]. Apple also ships Priority Messages with AI summaries and sorts mail into Primary, Transactions, Updates, and Promotions — "done all on-device" [33]. Availability is tiered: AI features require Apple Intelligence-class hardware and launch with initial region limits [9].

**Gmail (2026, the "Gemini era").** Google ships AI Overviews that "summarize email threads and answer questions using natural language", plus Suggested Replies, Help Me Write, Proofread, and an AI Inbox that filters out unimportant email [43]. On mobile, Gemini summary cards appear "at the top of the email content" for long threads [44].

**Spark.** Markets four recurring pillars: "Prioritize key senders and screen unknowns", pin/snooze/reminders, "Filter out noisy newsletters", and AI that summarizes long threads, drafts "in your voice", and answers inbox questions [23].

**Superhuman.** Its power-user suite — Instant Reply, Auto Drafts (reply drafts prepared before the message is opened), Auto Summarize, Auto Labels, split inbox, follow-up reminders, and Ask AI — sets the workflow reference [17].

**Proton.** Differentiates through privacy defaults: zero-access encryption, tracker blocking, and 1-click import from other providers [24].

**Canary.** Bundles an AI writer, thread summaries, automatic categorization, read receipts, 1-click unsubscribe, templates, a misdirected-recipient warning, and tracker blocking on by default [25][26].

The threat baseline is rising underneath these product moves. Barracuda analyzed more than 3.1 billion emails for its 2026 report and found 1 in 3 malicious or unwanted, with QR codes embedded in 70% of malicious PDFs [27]. Microsoft tracked roughly 7.3 billion email-based phishing threats in Q3 2026 alone [28]. Consumer-protection guidance treats fast in-app reporting as a core recovery action after a suspicious message [29].

Three standard-shifts follow, and each lands on a GrapMail gap:

1. **System-level integration is table stakes.** Widgets [41][42][4], notification interactions [5], and assistant hooks [3] are how mail apps stay one tap from the inbox; GrapMail ships none of these yet [39].
2. **Trust and safety are product features, not settings archaeology.** Tracker blocking, unsubscribe, and phishing checks are marketed up front by Proton and Canary [24][26], while attack telemetry makes each more urgent [27][28]; GrapMail's audit shows none of these user-facing controls [39].
3. **Intelligence is moving on-device.** Apple exposes an on-device foundation model to third-party apps for "structured generation, summarization, and classification" [34], reachable through a native Swift API whose prompts "never have to leave" the phone [35]; Apple's own Mail uses this pattern for summaries and categories [33]. For GrapMail, this is both a differentiation path and a cost argument — on-device intelligence avoids per-request cloud spend while keeping mail content local [37].

---

### Finding 3: Native System Integration — The Highest-Certainty Upside

Some feature families carry execution risk; this one carries little. Every mechanism below is first-party platform API surface, documented and stable [1][2][3][4][5][41], and several extend plumbing the apps already have — notification actions, background refresh, and send-later scheduling [39]. High user visibility plus low architectural risk makes system integration the safest place to start.

### F01 — Inline reply without leaving the notification

Both apps already show notification action buttons (archive, mark read, reply, more), but the reply action currently routes the user into the app [39]. The platform mechanisms to keep the reply in the notification are standard: on Android, "the direct reply action, introduced in Android 7.0 (API level 24), lets users enter text directly into the notification" [5], within a notification that "can offer up to three action buttons that let the user respond directly from the notification" [5]. iOS documents a text-input notification action type — an action that accepts typed input from the user [45] — declared as part of the app's notification categories [46].

Why first: replying is the most frequent daily interaction, and this converts it from a context switch into an inline gesture. Effort: S.

### F02 — Home and Lock-screen widgets

WidgetKit exists to surface glanceable, up-to-date experiences [41]; Apple's widget design guidance is built around timely, glanceable content [42]. On Android, "Glance provides a set of composables to help you build responsive widgets for the home screen with less code" [4] — a Compose-native path consistent with the existing Kotlin codebase [39].

Recommended v1 scope: unread badge, latest three messages (sender and subject), and a compose shortcut. Beyond direct value, the widget extension is a prerequisite for two other items below (Live Activities [2]; dynamic-color widgets [7]). Effort: M.

### F19 — Live Activities for Send Later

The apps already schedule sends [39]; a pending-send countdown with a cancel action is a textbook Live Activity — "Live Activities display your app's current data on the Lock Screen, in the Dynamic Island, in CarPlay, and on a paired Mac or Apple Watch" [2]. Implementation shares the widget extension ("add code to your existing widget extension or create a new widget extension if your app doesn't already include one") [2], with the requirement that apps "must support all presentations" [2]. Effort: S — once F02's extension exists.

### F15 — Siri, Spotlight, and Shortcuts integration

App Intents is the framework that makes app actions "discoverable by Apple Intelligence and support system experiences like Siri, Spotlight, Shortcuts, and widgets" [3] — and the same documentation calls out hardware triggers such as the Action button [3]. A first batch for GrapMail: compose-new, search, and jump-to-account intents. This seeds the assistant experiences Apple Mail already ships (summarize, track package, find flight [9]) and is also the natural framework seam for future Focus-based account filtering. Effort: M.

### F21 and F22 — Platform polish: per-app language and Material You

Two Android items that fit in a single polish sprint. "Starting in Android 13, Android includes a centralized location in system settings for setting per-app language preferences" [6]; exposing GrapMail's locales there uses a generated LocaleConfig and the runtime LocaleManager APIs [6]. Dynamic Color "enables users to personalize their devices to align tonally with the color scheme of their personal wallpaper" [7] and "is also available separately for widgets and adaptive icons" [7] — pairing directly with F02's Android widgets. Effort: S each.

### Sequencing note

F01 and F02 lead because they lift daily engagement and unlock the widget-extension dependencies for F19 and F22; F21 rides along as low-risk polish; F15 lands after the core UX, seeding the intelligence layers in Finding 5.

---

### Finding 4: Trust and Safety — Closing the Security Gap

The 2026 threat picture is quantified, and it argues for building protections into the app rather than leaving users to cope on their own. Barracuda's analysis of more than 3.1 billion emails found malicious or unwanted messages at a rate of "1 in 3" [27], with "70% of malicious PDFs and 56% of malicious Microsoft 365 documents" carrying QR codes [27]; Microsoft's Q3 2026 email-landscape analysis logs QR-code phishing as an active growth vector as attackers shift away from file-based payloads [28]. Consumer guidance treats the in-app report as a primary response to a suspicious message [29]. Rival apps answer at the product level: Proton leads with tracker blocking alongside zero-access encryption [24], and Canary "checks each email for spoofed senders and fake links and warns you before you click", with "tracker blocking ... on by default on every plan" [26]. GrapMail's audit shows none of these controls user-facing yet [39] — which makes this family the highest-value build after system integration.

### F03 — Finish one-click unsubscribe

The apps already parse `List-Unsubscribe` headers, including the one-click flag, and surface the affordance [39]. RFC 8058 "describes a method for signaling a one-click function for the List-Unsubscribe email header field" [8], and its receiver requirements matter for implementation: the POST must follow explicit user consent [8]. Shipping the completion — perform the POST on tap, confirm, archive the message — turns an existing parse into a full flow, and it is what Canary markets as "1-Click Unsubscribe" [25]. Effort: S. Recommended additional scope: a subscription-manager view for bulk-unsubscribing from noisy senders, extending the existing subscriptions screen [39].

### F04 — Block sender, report spam and phishing

Today this is thin to absent in the audit [39]. A first-class flow — swipe or menu action → block address server-side and optionally report to spam → move and train — closes the most common trust gap [39][40]. Canary's pitch shows the user benefit of adding checks on top of provider filtering [26], and the FTC's guidance keeps "report" as the expected verb after a suspicious message [29]. Effort: S.

### F05 — App lock with biometrics

Mail is the keys-to-the-kingdom app: password resets, 2FA codes, invoices. Neither platform build ships an app lock yet [39], while the privacy-first competitors treat protection as table stakes [24][25]. Native mechanism: Face ID/Touch ID on iOS and BiometricPrompt with PIN fallback on Android; recommended behavior is lock-on-cold-start rather than every foreground, keeping the daily flow frictionless. Effort: S–M.

### F16 — Tracker shield v2

Proton blocks known trackers as a headline feature [24]; Canary ships tracker blocking by default [26]. GrapMail's backend already renders message HTML through a dedicated route [40] — a natural place to strip remote images and tracking pixels and rewrite URLs, with the client showing a "trackers blocked" indicator and a per-mailbox toggle. Effort: M.

### F23 — QR-code phishing warnings

With "70% of malicious PDFs and 56% of malicious Microsoft 365 documents" containing QR codes [27] and QR usage climbing through Q3 2026 [28], scanning belongs in the security backlog: detect QR codes in image attachments and message bodies locally, preview the decoded payload, and warn when it points to a lookalike or first-seen domain. Effort: M — shares scanning infrastructure with the on-device intelligence work in Finding 5.

### Sequencing note

Bundle F03–F05 as a "trust release" — all small-effort, all user-visible; schedule F16 with the next backend web-rendering change; land F23 alongside the intelligence wave.

---

### Finding 5: On-Device Intelligence — The Differentiation Window

2026 mail is an intelligence race, and the platform owners moved first. Apple Mail ships "Priority Messages" — urgent mail surfaced "with AI generated summaries of the email" — and sorts incoming mail into Primary, Transactions, Updates, and Promotions, which "Apple says is done all on-device" [33]. Gmail entered what Google calls "the Gemini era": AI Overviews "summarize email threads and answer questions using natural language", Help Me Write drafts and polishes, and a new AI Inbox will "filter out unimportant emails, so you see what matters most" [43]; on mobile, summary cards appear "at the top of the email content for messages where a summary is helpful" [44]. Independents press the same buttons: Spark "summarizes long threads", produces "ready-to-send drafts in your voice", and answers inbox questions [23]; Superhuman layers Instant Reply, Auto Drafts, Auto Summarize, Auto Labels, and Ask AI across the inbox [17]; Canary ships an AI writer, thread summaries, and automatic categorization [25].

For GrapMail, the strategic opening is delivery locus: on-device first. Apple's Foundation Models framework gives third-party apps "a small, on-device language model — not a general-purpose LLM, but a model optimized for structured generation, summarization, and classification" [34], exposed as "a native Swift API" whose prompts "never have to leave" the phone [35]; Apple documents its own email-summarization adapters, including safety testing in which the summarization adapter "did not amplify sensitive content in over 99% of targeted adversarial examples" [36], and developer-facing coverage frames these features as working "offline and protect[ing] user privacy" [37]. The Android analog is a Gemini-Nano-class model on current flagship silicon [38]. For an encryption-positioned provider, keeping message text on the device during inference is both a privacy story and a cost story — and competitors make clear users increasingly expect the capability itself [23][17][25].

### F07 — On-device categories and priority views

**Why.** Apple's four-category sort plus Priority Messages established the pattern [33]; Gmail's AI Inbox tells a mass audience that unimportant mail gets filtered automatically [43]; Superhuman's Auto Labels show a response-oriented variant [17]. **What.** Classify arriving mail into three to five buckets (for example: People, Transactions, Notices, Newsletters) and add a "Priority" slice for same-day actionable mail, computed at fetch time [34]. **Feasibility.** On iOS, classification maps directly to the documented Foundation Models use case of "structured generation, summarization, and classification" [34][37]; on Android, gate on on-device model availability and fall back to rules for older devices [38]. Effort: M.

### F08 — Thread summaries

**Why.** Every reference product ships it: Apple ("AI generated summaries" [33]; Siri can "summarize" [9]), Gmail (summary cards on mobile [44]; thread summaries and natural-language questions [43]), Spark [23], Superhuman's Auto Summarize [17], Canary [25]. **What.** A collapsible summary card at the top of long threads, generated on-device where the model is available, with a manual "Summarize" action as fallback [34][35]. **Feasibility.** Summarization adapters are the exact deployment pattern Apple documents for foundation models [36]; Android device-class constraints apply [38]. Effort: M–L.

### F09 — Personalized Smart Reply

**Why.** iOS 27's pitch is personalization — suggestions "tuned to your writing style, which means the one-tap suggestions sound like things you might actually type" [9]; Gmail ships Suggested Replies [43]; Superhuman's Instant Reply covers quick acknowledgments and Auto Drafts pre-writes fuller replies [17]. **What.** Three short reply chips above the compose bar for incoming messages, ranked by thread context and lightly personalized toward the user's typical phrasing [9][35]. Effort: M.

### F10 — Compose assist: draft, rewrite, tone

**Why.** Canary: "Compose emails effortlessly with AI-crafted messages tailored to your voice" [25]; Spark: "ready-to-send drafts in your voice" [23]; Gmail: Help Me Write [43]; Apple: Write with Siri [9]. **What.** Add "draft from a prompt", "rewrite", and "adjust tone" actions to the existing rich compose editor [39], invoked explicitly by the user and running through the on-device model first [34][35]. Effort: M.

### F25 — Natural-language search

**Why.** Apple's iOS 27 search overhaul "ranks results by relevance and intent" [9]; Spark invites users to "Ask your inbox anything" [23]; Gmail's AI Overviews "answer questions using natural language" [43]; Superhuman's Ask AI does question-style retrieval [17]. **What.** Layered over the existing search infrastructure [39]: question-style queries mapped to structured filters, plus optional semantic reranking; on-device embeddings where feasible, server-side processing only with explicit consent and clear labeling. Effort: M–L.

### Privacy architecture note

The recommended stance is **on-device by default; cloud by explicit consent**. The evidence base supports it technically [34][35][37] and operationally: availability windows differ by device [9][38], so feature-gating with graceful fallbacks is mandatory, and any future server-side inference should be opt-in and disclosed in plain language. This also aligns with the privacy-first posture of the reference competitors [24][26] while matching the direction Apple itself has taken [33].

### Sequencing note

F08 ships first — highest wow-per-effort and shared model plumbing; F07 second (same plumbing, new surface); F09 next (compose-adjacent); F10 after F09 (same suggestion UX); F25 last (largest surface, needs search plumbing rework).

---

### Finding 6: Everyday Productivity — Closing the Craft Gaps

The reference set treats everyday conveniences as headline capabilities: Canary advertises templates, read receipts, one-click unsubscribe, and automatic categorization as core product benefits [25]; Spark leads with sender screening, reminders, and newsletter filtering [23]; Superhuman's toolset centers on Split Inbox and follow-up reminders [17]. Against these, the remaining GrapMail gaps are concentrated in daily-workflow craft — none individually large, but collectively decisive for the users who live in the app.

### F06 — Complete the snoozed-mailbox experience

**Why.** Snooze already ships as a triage action with undo support [39], but the dedicated snoozed-mailbox screen is blocked on infrastructure: mailbox code comments show it "depends on a backend '?box=snoozed' route that is not deployed yet" [39]. **What.** Deploy the route, wire the screen, and support edit/unsnooze and wake-time display, matching the send-later patterns already in place [39]. Effort: S (backend-led).

### F11 — Templates and snippets

**Why.** "Save time with templates." is a marketed Canary convenience [25], and snippet insertion is part of Superhuman's speed positioning [17]. **What.** User-managed canned responses stored server-side, inserted from the compose toolbar with placeholder expansion (first name, account) and keyword search. Effort: S–M.

### F12 — Follow-up reminders

**Why.** Superhuman highlights follow-up reminders in its feature set [17], and Spark offers reminders as a first-class action [23]. **What.** Track "awaiting reply" threads: when the user replies and no answer arrives within a configurable window, surface a reminder card with snooze-one-day and nudge actions, reusing existing snooze plumbing [39]. Effort: M.

### F13 — Split inbox and priority senders

**Why.** Spark positions screening directly — "Prioritize key senders and screen unknowns." [23] — alongside "Filter out noisy newsletters." [23]; Superhuman ships a Split Inbox view [17]. **What.** User-defined buckets (People, Notifications, Newsletters) as fast tabs on the mailbox, with promote/screen controls per sender. This is the deterministic, user-controlled complement to F07's automatic classification: rules the user sets, not model guesses. Effort: M.

### F14 — All-accounts view: verify, then polish

**Why.** The audit could not evidence a unified inbox — a targeted grep returned empty, logged as a low-confidence absence [39]. **What.** First verify current behavior on device; if partial, add an "All accounts" aggregate view with per-account chips and a remembered default. Scope this only after verification to avoid rebuilding an existing screen. Effort: M (after verification).

### F17 — Accessibility hardening

**Why.** Platform guidance is explicit and testable: "Support larger text sizes. Make sure people can adjust the size of your text or icons to make them more legible, visible, and comfortable to read." [31]; "Describe your app's interface and content for VoiceOver." [31]; "Strive to meet color contrast minimum standards." [31]; Google publishes the equivalent Android accessibility guidance [32]. **What.** A dedicated pass: Dynamic Type scaling at and beyond 200% without clipping, screen-reader labels for icon-only controls (swipe actions, notification actions), a contrast audit in both themes, and font-scale regression checks. Effort: M.

### F18 — Subscription manager v2

**Why.** The apps already parse List-Unsubscribe headers [39]; Canary markets "1-Click Unsubscribe — Block distractions fast." [25]; Spark answers newsletter noise with filtering [23]. **What.** A per-sender subscription list sorted by volume and last-read, with bulk unsubscribe (built on F03's POST completion) and a periodic "review subscriptions" entry point. Effort: S–M.

### F24 — Opt-in read receipts

**Why.** Canary sells them directly: "Get read receipts instantly." [25]. **What.** Off by default; when enabled, show a subtle receipt state in the thread. Scoped to messages between GrapMail accounts, with explicit privacy copy in settings. Effort: M.

### F26 — Import and migration assistant

**Why.** Proton's onboarding promise is "Import your messages, calendars, and contacts in one click" [24]. **What.** For users arriving from other providers, ship a migration wizard limited to what the own-provider scope allows: contact and calendar file import (vCard/iCal) plus forwarding guidance; third-party inbox sync stays out of scope for this roadmap. Effort: L — schedule last.

### F27 — Actionable message cards

**Why.** Apple's 2026 Mail push includes exactly this: "Long press on any email and you can ask Siri to summarize, find an item in the email, track a package, get a flight number, save a photo, and more." [9]. **What.** On-device extraction of tracking numbers, flight numbers, and dates rendered as action chips in the message view (Track, Add to Calendar). Shares model plumbing with F25 and stays strictly on-device to match the privacy architecture from Finding 5. Effort: M.

### Sequencing note

F06 rides the next backend deploy and closes an existing promise; F11 and F18 are quick wins that bundle naturally with the trust release from Finding 4; F12 and F13 form the "inbox control" pair aimed at daily users; F17 is an independent hardening pass; F24, F26, and F27 wait for the intelligence wave (F27 shares its model plumbing).

---

## Prioritized Feature Roadmap

**How to read this list.** Tiers combine market evidence (how close the capability is to table stakes in 2026), the internal audit markers [39], implementation effort against first-party frameworks only, and dependency order. Effort scale: **S** ≈ one sprint for one platform team; **M** ≈ two to four sprints; **L** ≈ a full cycle or multi-team work — see assumption 3.

| Tier | ID | Feature | Effort | Key sources |
|------|----|---------|--------|-------------|
| P0 | F01 | Inline reply from the notification (iOS text-input action; Android direct reply) | S | [45][46][5] |
| P0 | F02 | Home and Lock-screen widgets (unread count, latest mail, compose launch) | M | [41][42][4] |
| P0 | F03 | One-click unsubscribe completion (consent-gated POST) | S | [8][25][39] |
| P0 | F04 | Block sender and report spam/phishing flow | S | [26][27][29] |
| P0 | F05 | App lock with biometrics (Face ID/Touch ID; Android BiometricPrompt) | S–M | [24][25][39] |
| P0 | F06 | Snoozed-mailbox completion (backend route + screen) | S | [39] |
| P1 | F07 | On-device categories and priority views | M | [33][43][17] |
| P1 | F08 | On-device thread summaries | M–L | [33][44][17] |
| P1 | F09 | Personalized Smart Reply | M | [9][43][17] |
| P1 | F10 | Compose assist: draft, rewrite, tone | M | [25][23][43] |
| P1 | F11 | Templates and snippets | S–M | [25][17] |
| P1 | F12 | Follow-up reminders | M | [17][23] |
| P1 | F13 | Split inbox and priority senders | M | [23][17] |
| P1 | F15 | Siri, Spotlight, and Shortcuts integration | M | [3][9][30] |
| P1 | F16 | Tracker shield v2 (pixel blocking, indicator, proxy) | M | [24][26][40] |
| P1 | F17 | Accessibility hardening (Dynamic Type, labels, contrast) | M | [31][32] |
| P1 | F18 | Subscription manager v2 (bulk unsubscribe view) | S–M | [25][23][39] |
| P2 | F14 | All-accounts view (verify, then polish) | M | [39] |
| P2 | F19 | Live Activity: Send Later countdown + cancel | S | [2][39] |
| P2 | F20 | iOS Focus filters | S | [3] |
| P2 | F21 | Android per-app language (LocaleConfig) | S | [6] |
| P2 | F22 | Material You dynamic color and themed icons | S | [7] |
| P2 | F23 | QR-code phishing warnings | M | [27][28] |
| P2 | F24 | Opt-in read receipts | M | [25] |
| P2 | F25 | Natural-language search | M–L | [9][23][43] |
| P2 | F26 | Import and migration assistant | L | [24] |
| P2 | F27 | Actionable message cards (package, flight, calendar) | M | [9] |

**P0 — Ship next (6 items).** All six are small, self-contained, and close visible gaps without touching the intelligence layer. F01 completes the notification-action investment that already ships on both platforms [39] using the platform-native text-input pattern [45][5]. F02 answers a zero-hit audit [39] with the two most standard surfaces in mobile mail [41][4]. F03 finishes an already-parsed capability into a working flow [39][8]. F04 and F05 close trust gaps that privacy-first competitors market as table stakes [26][29][24]. F06 removes the last server-side blocker on an already-shipped triage feature [39]. Every item is independently shippable within one release cycle, and none carries a data-model risk.

**P1 — Next wave (11 items).** This is the competitive-parity core: each item maps to a capability that at least two reference products already market [23][17][25], or to a platform surface whose absence is conspicuous [3]. The intelligence cluster (F07–F10) shares a single model-plumbing investment and should ship as one set, gated on device capability. The control cluster (F12–F13) shares thread-state tracking. F15 turns on the assistant surface and unlocks Spotlight and Shortcuts alongside Siri [3][9]. F16 rides the backend's message-rendering route [40]. F17 (accessibility) and F18 (subscription cleanup) are independent and can parallelize with everything else in the wave. Expect this wave to move the app from "competitive" to "current" against the 2026 bar.

**P2 — Later / watch (10 items).** Valuable but constrained. F14 needs on-device verification before any build decision [39]. F19–F22 are platform-polish items best bundled into a single design pass timed to the next OS cycle [2][3][6][7]. F23 participates in the security telemetry trend that continues to climb [27][28]. F24–F27 each depend on earlier waves: F25 and F27 explicitly extend the intelligence layer, and F26 is the largest single scope in the list [24]. Revisit this tier after the first two waves ship, and let adoption telemetry decide the order within it.

**Counts.** P0 = 6, P1 = 11, P2 = 10 — 27 candidates in total.

---

## Synthesis & Insights

**The gaps are shallow, not structural.** Nothing in this roadmap requires a new subsystem. Inline reply extends notification actions that already ship [39][5]; the unsubscribe flow completes header parsing the apps already perform [39][8]; the Live Activity reuses the send-later pipeline [39][2]; tracker shield v2 lands on a rendering route already in the backend [40]; compose assist sits on an existing rich-text editor [39]. That is why feasibility confidence is high — and why the P0 wave is best understood as closure work: finishing surfaces the product already committed to.

**The market has converged on one capability set.** Three independent vectors arrived at the same shortlist. Apple's Mail added on-device summaries, categories, and style-tuned Smart Reply [9][33]; Google's Gemini-era Gmail shipped thread summaries, suggested replies, and an AI inbox filter [43][44]; the independents — Spark, Superhuman, Canary — market summaries, drafts, split inboxes, and unsubscribe helpers as core [23][17][25]. Convergence of this kind, across platform owners and independents alike, usually means the capabilities become table stakes within a product cycle or two. The implication: the parity items in Findings 5 and 6 are time-boxed, not optional.

**Privacy is the structural differentiator, if it is used.** Apple runs its mail intelligence on-device [33][34][35]; Gmail is cloud-first [43]; Proton and Canary sell privacy defaults as the product [24][26]. GrapMail already ships the privacy foundations — E2E plumbing, sessions with 2FA, human verification [39] — but has no intelligence layer at all. An on-device-first architecture (Finding 5) converts the constraint into positioning: message content stays local, inference cost stays near zero, and the feature list matches what privacy-sensitive buyers are offered elsewhere [24][26]. The risk is shipping nothing while rivals normalize the category.

**Security is the highest-leverage trust spend.** With one in three messages malicious or unwanted and QR codes embedded in most malicious documents [27], plus phishing volume climbing through Q3 2026 [28], visible protections are a trust signal rather than an obscure setting. The trust release (F03–F05, F16) is cheap, testable, and directly marketable — and it lands exactly where the audit shows no user-facing controls today [39].

**Sequencing follows dependencies, not desire.** Four hard gates order the roadmap: the widget extension (F02) gates Live Activities (F19) and themed widgets (F22) [2][7]; the on-device model plumbing gates F07–F10 and F27 [34][38]; the backend `?box=snoozed` route gates F06 [39]; and the backend rendering route gates F16 [40]. Everything else can reorder freely around these four.

**Effort asymmetry favors starting now.** The highest-certainty work is also among the smallest: F01, F03, F04, and F06 are single-sprint items with immediate user visibility, while the intelligence items carry device-availability caveats that make them multi-quarter bets [9][38]. Shipping the P0 wave in one cycle buys credibility and the telemetry needed to de-risk everything after it.

**What could invalidate this plan.** Two scenarios: platform owners absorb these capabilities into the OS itself (Apple already ships on-device summarization inside Mail [33]), eroding third-party differentiation; or user adoption of in-mail AI stalls, making Wave 2 premature. Both are monitored rather than predicted — the roadmap front-loads items whose value does not depend on either outcome (F01–F06, F16), and gates the rest on adoption telemetry.

## Limitations & Caveats

- **Absence evidence is snapshot-bounded.** "Missing" means zero or trivial marker hits in the export at commit `0fb5998e` [39] — a statement about that snapshot's sources, not about intent or other branches. The all-accounts item (F14) is flagged low-confidence for exactly this reason [39].
- **Competitor capabilities are self-reported.** Product pages and vendor blogs describe marketed behavior; this research did not hands-on test rival apps [23][24][25]. Independent review coverage was consulted for corroboration where available [15][16][18][19][20][21].
- **Effort estimates are planning-grade.** No engineering spikes were run; S/M/L are directional per assumption 3, and exclude design and QA time.
- **Availability is uneven by device and region.** Apple Intelligence-class features are hardware- and region-gated [9]; Android on-device model availability follows flagship silicon [38]. Every AI recommendation is scoped as opt-in with graceful fallbacks (assumption 5).
- **Ecosystem churn.** Platform cycles and competitor packaging move fast, and even launch-coverage sets document tiered availability [9][10][11][13]. Re-verify competitive claims before external reuse.
- **Deliberate exclusions.** Server architecture beyond the identified snoozed-mailbox route, third-party-provider connectors, and pricing questions remain out of scope [39] (see Introduction).

## Counterevidence Register

Signals that argue against, or qualify, this roadmap's recommendations - recorded so the plan is not read as one-sided.

- **Feature velocity vs. privacy stance.** Cloud-first assistants ship improvements broadly and continuously - Google moved quickly to ship AI features across Gmail [43][44] - while on-device-first designs inherit hardware and region constraints [9]. The privacy position [24][26] is defensible, but it is a deliberate trade-off: some capabilities will arrive later, and on fewer devices.
- **Vendor claims are not benchmarks.** Parity estimates in Findings 1-3 rest on vendor product pages [23][24][25] corroborated by independent coverage [15][16][18][19][20][21], not standardized third-party testing. Treat "competitor has X" as directional until independently re-verified.
- **Absence evidence is snapshot-bound.** The gap list reflects a single audited commit [39]; concurrent branches or parallel work could already be closing items, which is why F14 (all-accounts view) carries a verify-first note rather than a build plan.
- **Quantified threat data shifts yearly.** The one-in-three malicious-message framing [27] and the QR-phishing trend records [28][29] are point-in-time measurements. The security items (F03-F05, F16) are justified first by their small scope and visible user value, not by those statistics alone.
- **Platform absorption risk.** Apple already integrates on-device summarization, categorization, and style-tuned replies into its own Mail app [33], and OS-level absorption of that kind could erode the differentiation of third-party intelligence features (Finding 5) - making the non-AI parity and trust items the safer long-term commitments.

## Recommendations

**Wave 1 — Trust and integration release (this cycle).** Ship F01, F02, F03, F04, F05, and F06 — folding in F15 if capacity allows. This wave closes the most visible parity gaps with the smallest scopes, and every item is independently shippable. Telemetry to watch: reply-from-notification adoption (F01), widget installation and retention (F02), unsubscribe completions (F03), and report/block usage (F04).

**Wave 2 — Intelligence core (next cycle).** Begin with model plumbing and thread summaries (F08), then categories (F07), style-tuned replies (F09), and compose assist (F10) as one gated set; run the inbox-control pair (F12, F13) in parallel; schedule F16 with the next backend rendering change [40]; F17 and F18 fit anywhere in the wave as independent scope. Gate every AI surface on device capability with documented fallbacks [34][38], and route anything that could leave the device through the standing privacy review below.

**Wave 3 — Polish and reach (subsequent cycles).** Verify then polish the all-accounts view (F14); bundle the platform-polish items (F19–F22) into a single design pass timed to the next OS cycle [2][3][6][7]; reintroduce QR warnings (F23) as security telemetry continues to climb [27][28]; then the dependents — read receipts (F24), natural-language search (F25), action cards (F27), and finally import/migration (F26), the largest scope in the list [24].

**Governance guardrails.** Three standing rules: (1) on-device-first for anything touching message content — cloud inference only by explicit consent and clear labeling; (2) an accessibility checklist (larger text, screen-reader labels, contrast) enforced every release [31][32]; (3) a device-availability matrix maintained per platform so gating never depends on assumptions [9][38].

**Decision cadence.** Treat the tier assignments as a snapshot: re-rank Wave 2 after Wave 1 telemetry, and revisit Wave 3 at each OS beta cycle. The 27-item list is deliberately exhaustive; the waves are what to commit to.

---

## Claims-Evidence Table

Representative mapping of major claims to supporting sources; the full claim ledger and support report accompany this delivery.

| ID | Major Claim | Evidence Type | Supporting Sources | Confidence |
|----|-------------|---------------|--------------------|------------|
| C1 | Confidence level: High for platform feasibility, since every mechanism is documented first-party API surface . | Platform docs | [1], [2], [3], [4], [5], [6], [7] | High |
| C2 | GrapMail currently ships none of: home/Lock-screen widgets (WidgetKit on iOS , Glance on Android ), Live Activities , App Intents/Si... | Platform docs | [2], [3], [4], [5], [6], [7], [41] | High |
| C3 | Every mechanism below is first-party platform API surface, documented and stable , and several extend plumbing the apps already have... | Platform docs + Internal audit | [1], [2], [3], [4], [5], [39], [41] | High |
| C4 | Every reference product ships it: Apple ("AI generated summaries" ; Siri can "summarize" ), Gmail (summary cards on mobile ; thread... | Industry coverage + Vendor material | [9], [17], [23], [25], [33], [43], [44] | High |
| C5 | Widgets , notification interactions , and assistant hooks are how mail apps stay one tap from the inbox; GrapMail ships none of thes... | Platform docs + Internal audit | [3], [4], [5], [39], [41], [42] | High |
| C6 | Tracker blocking, unsubscribe, and phishing checks are marketed up front by Proton and Canary , while attack telemetry makes each mo... | Vendor material + Security research + Internal audit | [24], [26], [27], [28], [39] | High |
| C7 | Canary: "Compose emails effortlessly with AI-crafted messages tailored to your voice" ; Spark: "ready-to-send drafts in your voice"... | Industry coverage + Vendor material | [9], [23], [25], [43] | High |
| C8 | First, several P0 candidates are small closures rather than new systems — inline reply extends the existing notification actions , o... | Platform docs + Vendor material | [5], [8], [24], [25] | High |
| C9 | Apple's iOS 27 search overhaul "ranks results by relevance and intent" ; Spark invites users to "Ask your inbox anything" ; Gmail's... | Industry coverage + Vendor material | [9], [17], [23], [43] | High |
| C10 | Apple's Foundation Models framework gives third-party apps "a small, on-device language model — not a general-purpose LLM, but a mod... | Industry coverage | [34], [35], [36], [37] | High |
| C11 | Neither platform build ships an app lock yet , while the privacy-first competitors treat protection as table stakes . | Vendor material + Internal audit | [24], [25], [39] | High |
| C12 | The apps already parse List-Unsubscribe headers ; Canary markets "1-Click Unsubscribe — Block distractions fast." ; Spark answers ne... | Vendor material + Internal audit | [23], [25], [39] | High |
| C13 | Effort scale: S ≈ one sprint for one platform team; M ≈ two to four sprints; L ≈ a full cycle or multi-team work — see assumption 3.... | Platform docs + Industry coverage + Vendor material + Security research + Internal audit | [2], [3], [4], [5], [6], [7], [8], [9], [17], [23], [24], [25], [26], [27], [28], [29], [30], [31], [32], [33], [39], [40], [41], [42], [43], [44], [45], [46] | High |
| C14 | F19–F22 are platform-polish items best bundled into a single design pass timed to the next OS cycle . | Platform docs | [2], [3], [6], [7] | High |
| C15 | Apple runs its mail intelligence on-device ; Gmail is cloud-first ; Proton and Canary sell privacy defaults as the product . | Vendor material + Industry coverage | [24], [26], [33], [34], [35], [43] | High |
| C16 | Four hard gates order the roadmap: the widget extension (F02) gates Live Activities (F19) and themed widgets (F22) ; the on-device m... | Platform docs + Industry coverage + Internal audit | [2], [7], [34], [38], [39], [40] | High |

**Confidence levels:** High = 3+ independent sources; Medium = 2 sources or one high-quality source; Low = single source. Source numbers resolve through the Bibliography.

---

## Bibliography

Sources are numbered [1]-[46] and cited in the text as [N]; each entry follows the [N] Title - URL form. All locators were last verified on November 19, 2026.

[1] Creating a widget extension - Apple Developer Documentation - https://developer.apple.com/documentation/widgetkit/creating-a-widget-extension
[2] Displaying live data with Live Activities - Apple Developer Documentation - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
[3] App Intents - Apple Developer Documentation - https://developer.apple.com/documentation/appintents
[4] Jetpack Glance - Jetpack Compose - Android Developers - https://developer.android.com/develop/ui/compose/glance
[5] Create a notification - Jetpack Compose - Android Developers - https://developer.android.com/develop/ui/views/notifications/build-notification
[6] Per-app language preferences - App architecture - Android Developers - https://developer.android.com/guide/topics/resources/app-languages
[7] Enable users to personalize their color experience in your app - Views - Android Developers - https://developer.android.com/develop/ui/views/theming/dynamic-colors
[8] RFC 8058: Signaling One-Click Functionality for List Email Headers - https://www.rfc-editor.org/rfc/rfc8058
[9] iOS 27: What's New With the Mail App - MacRumors - https://www.macrumors.com/guide/ios-27-mail
[10] About iOS 27 Updates - Apple Support - https://support.apple.com/en-us/149076
[11] iOS 27: What's new, what's in the latest beta and what's coming next - Macworld - https://www.macworld.com/article/2986799/ios-27-features-compatiblity-siri-ai-updates.html
[12] OS - iOS 27 - Apple - https://www.apple.com/os/ios
[13] iOS 26 vs iOS 27: What iPhone Users Should Expect in 2026 - ESR - https://www.esrtech.com/blogs/new-tech-gadgets/ios-27-vs-ios-26-snow-leopard-leaks
[14] iOS 27 Is Here. These Are the Best New Features - Wirecutter - https://www.nytimes.com/wirecutter/reviews/ios-27-best-new-features
[15] Superhuman Mail - Get Ahead of Your Inbox - https://superhuman.com/mail
[16] Transform your inbox from average to an AI-powered email - Superhuman Blog - https://blog.superhuman.com/ai-powered-email
[17] Superhuman Review 2026: AI Email, Pricing and Life Under Grammarly - https://smartkeys.org/superhuman-review
[18] Superhuman AI Email Features Dominating Inboxes in 2026 - https://bestaiemailtools.com/blog/superhuman-features-guide-2026
[19] Superhuman Email Review (2026): Speed, AI and Limits - https://aiemaily.com/blog/superhuman-email-review-2026
[20] I Refuse to Work Without Superhuman Mail (6-Year Review) - https://efficient.app/apps/superhuman
[21] Superhuman Mail: What It Is and Is It Worth It? - eM Client - https://www.emclient.com/superhuman-mail-what-it-is-and-is-it-worth-it
[22] Superhuman introduces AI-powered categorization to reduce spammy emails in your inbox - https://finance.yahoo.com/news/superhuman-introduces-ai-powered-categorization-140000179.html
[23] Spark Mail - official site - https://sparkmailapp.com
[24] Proton Mail - official site - https://proton.me/mail
[25] Canary Mail - official site - https://canarymail.io
[26] 10 Phishing Email Examples and How to Spot Each One (2026) - Canary Mail Blog - https://canarymail.io/blog/phishing-email-examples
[27] 2026 Email Threats Report - Barracuda Networks - https://www.barracuda.com/reports/2026-email-threats-report
[28] Email threat landscape: Q3 2026 trends and insights - Microsoft Security Blog - https://www.microsoft.com/en-us/security/blog/2026/11/05/email-threat-landscape-q3-2026-trends-and-insights
[29] How To Recognize and Avoid Phishing Scams - FTC Consumer Advice - https://consumer.ftc.gov/articles/how-recognize-avoid-phishing-scams
[30] Apple Intelligence - Apple Developer - https://developer.apple.com/apple-intelligence
[31] Accessibility - Human Interface Guidelines - Apple Developer - https://developer.apple.com/design/human-interface-guidelines/accessibility
[32] Accessibility - Android Developers - https://developer.android.com/guide/topics/ui/accessibility
[33] Apple Intelligence - Wikipedia - https://en.wikipedia.org/wiki/Apple_Intelligence
[34] On-Device AI on Apple Platforms: Core ML, Apple Foundation Models, and the Neural Engine in 2026 - 3Nsofts - https://3nsofts.com/insights/on-device-ai-apple-platforms-core-ml-foundation-models-neural-engine-2026
[35] Apple Foundation Models Explained: On-Device AI, Framework & iOS 27 - Viro - https://www.viro.app/post/apple-foundation-models-explained
[36] Introducing Apple's On-Device and Server Foundation Models - Apple Machine Learning Research - https://machinelearning.apple.com/research/introducing-apple-foundation-models
[37] Apple announces on-device AI features for developers - Investing.com - https://www.investing.com/news/company-news/apple-announces-ondevice-ai-features-for-developers-93CH-4087441
[38] Apple Intelligence vs Google Gemini Privacy (2026) - Patentailab - https://patentailab.com/apple-intelligence-vs-google-gemini-privacy
[39] GrapMail iOS and Android source snapshot (internal audit export, commit 0fb5998e) - local://0fb5998e
[40] GrapMail backend route inventory (internal, commit f9c43384) - local://f9c43384
[41] WidgetKit - Apple Developer Documentation - https://developer.apple.com/documentation/widgetkit
[42] Widgets - Human Interface Guidelines - Apple Developer - https://developer.apple.com/design/human-interface-guidelines/widgets
[43] Gmail is entering the Gemini era - Google Blog - https://blog.google/products-and-platforms/products/gmail/gmail-is-entering-the-gemini-era
[44] Gemini summary cards in the Gmail app on Android and iOS - Google Workspace Updates - https://workspaceupdates.googleblog.com/2025/05/gemini-summary-cards-gmail-app.html
[45] UNTextInputNotificationAction - Apple Developer Documentation - https://developer.apple.com/documentation/usernotifications/untextinputnotificationaction
[46] Declaring your actionable notification types - Apple Developer Documentation - https://developer.apple.com/documentation/usernotifications/declaring-your-actionable-notification-types

## Methodology Appendix

**How candidates were selected.** A candidate entered the roadmap only if it passed three tests: (a) a first-party mechanism exists that can implement it natively (no WebView surfaces, per the native-only guardrail); (b) at least one credible external trigger supports its value — platform direction, competitor parity, security telemetry, or accessibility standards; and (c) it does not duplicate behavior already shipped at the audited commit.

**Repository census (internal baseline).** The audit exported the `ios/` and `android/` trees of grapmail-mobile at commit `0fb5998e` (203 files: 48 Swift, 75 Kotlin) plus the backend route surface at `f9c43384`, then swept the export with roughly 30 case-insensitive capability markers (widgets, ActivityKit, App Intents, direct reply, biometrics, templates, smart replies, categories, locale configuration, dynamic color, tracker handling, and related terms). A capability is recorded as "present" only with concrete implementation hits; "absent" means zero or trivial hits in that snapshot. Ambiguous markers were resolved by targeted file reads — for example, the snoozed-mailbox screen's backend dependency and the notification-action wiring on both platforms [39][40].

**Search and fetch protocol.** External material was gathered in two waves: broad landscape and comparison queries first (features users expect in 2026, head-to-head client comparisons), then targeted retrievals for the specific platform APIs behind each recommendation, RFC 8058, the security reports, AI capability documentation, and accessibility guidance. Full-page fetches were retained as raw extracts; every registered source records a title, canonical locator, retrieval date, and source type, and supports one or more quote-level evidence entries.

**Source classes.** Registered sources fall into five groups: first-party platform documentation (Apple [1][2][3][30][31][41][42][45][46]; Android [4][5][6][7][32]; the RFC standard [8]); vendor product material ([23][24][25][26]); security research and consumer protection ([27][28][29]); independent coverage and analysis ([9][10][11][12][13][14][15][16][17][18][19][20][21][22][33][34][35][36][37][38][43][44]); and internal audit artifacts ([39][40]).

**Corroboration sets.** The iOS 27 Mail feature set was cross-checked across launch-day coverage and reference material [10][11][12][13][14] in addition to [9]; the Superhuman feature set was cross-checked across review and vendor sources [15][16][18][19][20][21][22] in addition to [17]. Availability and privacy caveats were taken from first-party material [9][34][35] rather than coverage, and where nuance differed, the report uses the more conservative wording.

**Claim verification.** Every factual statement in the findings carries bracketed citations resolving to registered sources, and quantified claims — such as the one-in-three malicious-message rate and the QR-code share of malicious documents [27] — trace to quote-level evidence entries. Before publication, the draft passed through automated claim extraction and support verification against the evidence store, and the rendered HTML was validated for structure.

**Reproducibility.** Run artifacts are retained with the audit package: the 46-entry source register, the 86-entry evidence store, capability-marker extracts, the outline, and the assembled report. Baseline commits: grapmail-mobile `0fb5998e`; grapmail-backend `f9c43384`. Research date: November 19, 2026. Known limits are documented in Limitations & Caveats.
