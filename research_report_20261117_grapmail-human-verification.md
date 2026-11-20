---
title: "Real Human Verification for GrapMail - Replacing the Fake 'ACCOUNT PROTECTION' Screen with a Genuine, Unskippable Anti-Bot Experience (Web - iOS - Android)"
date: 2026-11-17
mode: deep
run_id: 20261117-humanverify
sources: 42
evidence_quotes: 64
status: final
---

# Real Human Verification for GrapMail

*Replacing the fake "ACCOUNT PROTECTION" screen with a genuine, unskippable anti-bot experience across web, native iOS, and native Android.*

## Executive Summary

GrapMail's current "ACCOUNT PROTECTION" screen is a mock-up: a slider puzzle that sets a local boolean, and a "Skip for now" link whose callback simply advances signup. The web signup form has no human-verification step at all - it posts credentials straight to the registration endpoint. This report establishes what "real" actually means - the pipeline behind the reCAPTCHA-class checkpoints shown in the reference screenshots - and maps it onto all three GrapMail surfaces so that verification is genuine in logic, not just in appearance [31][1][2].

Three findings drive the recommendations. First, realness is a pipeline, not a widget: challenge issuance, a one-time response token (valid for about two minutes), server-side verification (siteverify or an Enterprise assessment), and a policy gate that decides whether the user proceeds [1][2][29]. Second, platform capabilities differ: on the web, the classic "I'm not a robot" checkbox plus image challenges is available as the reference experience [31]; on mobile, Google's official reCAPTCHA Enterprise SDKs are deliberately frictionless (risk-scored, no visible puzzle), and a native "I'm not a robot" checkbox is not available on iOS - the industry path to a visible challenge on phones is a WebView-hosted checkpoint, exactly as Google's own official samples demonstrate [12][26][17]. Third, skipping can only truly be killed on the server: when the backend records "human verified" solely after a successful siteverify/assessment, a client-side skip becomes harmless; without server enforcement, any UI change is cosmetic [29][32].

The recommended stack for GrapMail: **Web** - reCAPTCHA v2 checkbox with image challenges, verified server-side before the account-creation call completes [31][2]. **Android and iOS** - the reCAPTCHA Enterprise mobile SDK for a frictionless baseline signal (with Play Integrity and App Attest signals available where configured), plus an optional WebView-delivered visual challenge for visual parity with the reference screens, and a mandatory server-side gate for every path [26][12][32][33]. Accessibility alternatives and branding obligations are specified throughout [37][38][41][42].

A companion implementation guide, delivered with this report, contains the concrete code and step-by-step integration for all four components (web, Android, iOS, backend gating) - including the removal of the Skip button and the server-side enforcement that makes verification strictly unskippable.

## Introduction

### Why this report exists

On 2026-11-17 the project owner flagged GrapMail's "ACCOUNT PROTECTION" screen as "sooooo unreal": it opens with "Just one more step!", offers a "Slide to verify" puzzle and a "Get a new challenge" link, and - the dealbreaker - a "Skip for now" escape hatch. Nothing about it resembles the checkpoints users meet on major platforms, where a genuine "I'm not a robot" checkbox spawns image challenges and gates the next step. The mandate for this research: make human verification real and pure in logic on all three surfaces (web, native iOS, native Android), make it strictly unskippable ("skip button cut it out"), and implement it the way real platforms implement it - "exactly like the real one."

### Why a fake verification screen is worse than none

A decorative checkpoint is not neutral. It costs user trust the moment it is recognized as theater; it consumes onboarding time; it trains users to look for the skip; and it provides zero protection against automation while advertising a gate that does not exist. Security-wise, the current screen is the equivalent of a turnstile painted on a wall: a bad actor walks through, and honest users learn that the checkpoint is meaningless. The reference platforms avoid this trap because their widgets are backed by a real verification service and their client flows are backed by server-side enforcement.

### What was audited (source-code evidence)

Every claim about the current state comes from the actual repositories:

- **Android** (`grapmail-mobile/android`): the fake screen is `app/src/main/java/com/grapsee/grapmail/ui/screens/HumanVerifyScreen.kt`; the source comment marks the Skip callback ("onDone is Skip's callback only"); the slider component (`ui/components/PuzzleSlider.kt`) is decorative; and `logic/SignUpViewModel.kt` merely flips a local boolean via `markHumanVerified()`. Nothing contacts a server.
- **iOS** (`grapmail-mobile/ios`): the fake screen is `GrapMail/FinishScreens.swift` ("ACCOUNT PROTECTION" at L39, "Skip for now" at L110 wiring into `onContinueOnboarding`), presented from `GrapMail/RootView.swift` (`.humanVerify` route, around L655). No server call exists anywhere in the flow.
- **Web** (`Grapsee_All_Time`): a full scan of `src/` finds zero captcha/robot/human-verification code. The signup form (`src/app/(auth)/signup/SignUpForm.tsx`, L151) fetches `${API_BASE}/auth/register` directly.

The detailed audit is in Finding 6.

### Method

This report follows a deep-research pipeline: scope definition; multi-wave retrieval; primary-source documentation fetched directly from Google (reCAPTCHA and reCAPTCHA Enterprise docs, Play Integrity), Apple (App Attest), Cloudflare (Turnstile), hCaptcha, Friendly Captcha, and the W3C; 42 sources registered with stable identifiers and 64 evidence quotes persisted with locators; and a triangulation pass over every load-bearing claim (summarized in Appendix A). Platform facts were preferred from primary documentation; vendor marketing language was treated as claim, not fact. Prices and quotas are as published at retrieval time (2026-11).

### Key terms used in this report

- **Challenge issuance** - the consumer-facing step where a widget decides how much friction to impose (no interaction, a checkbox click, or an image puzzle).
- **Response token** - the short-lived, single-use cryptographic token the widget produces; it means nothing until verified server-side.
- **Siteverify / assessment** - the server-to-server call that validates the token (classic siteverify) or evaluates a risk score (reCAPTCHA Enterprise assessments).
- **Attestation** - platform-level proof that the app and device are genuine (Google Play Integrity, Apple App Attest, Firebase App Check).
- **Gating** - the server-side rule that refuses to complete the protected action (account creation, onboarding completion, first mailbox access) until verification succeeds.

### How to read this report

Findings 1-5 build the model: the anatomy of real verification, the web reference implementation, the mobile reality on iOS and Android, the device-attestation layer, and server-side gating. Finding 6 audits why the current screen is fake. Findings 7-9 cover bypass economics, accessibility and compliance, and the vendor/cost landscape. The Synthesis and Recommendations distill the target architecture; the companion implementation guide contains the code.

## Main Analysis

### Finding 1 - The Anatomy of Real Human Verification

Every platform that handles this problem well - Google's reCAPTCHA across its versions, hCaptcha, Cloudflare Turnstile - runs the same three-gate pipeline: **a challenge is issued, a short-lived token is minted, and the token is verified server-side before the protected action proceeds** [1][2][3]. The widget is the part users see; the gates they do not see are the part that actually protects anything.

#### Gate 1: Challenge issuance (what users experience)

The widget performs its own risk triage. In the classic checkbox flow, the user clicks "I'm not a robot"; the widget either passes the user immediately or escalates to image challenges [31]. In score-based systems, no interaction is requested at all - the client gathers signals and produces a token in the background [29]. Crucially, the output of Gate 1 is never a verdict: it is a **response token**. Nothing about the user's click has been proven yet.

#### Gate 2: Token validation (what servers do)

The token is inert until the backend exchanges it. In classic flows, the server posts the token, its secret key, and optionally the user's IP to the siteverify endpoint, which answers with `success`, a timestamp, the hostname, and error codes [1][2]. Enterprise flows instead create an *assessment* that enriches the exchange with request context (user agent, IP address, JA3/JA4 TLS fingerprints) and returns both whether the token is valid and a normalized risk score between 0.0 and 1.0 [29]. Two properties make this gate load-bearing:

- **Expiration.** reCAPTCHA documents that a response token is valid for about two minutes [1][2]. A token cannot be harvested and replayed later.
- **Single use.** Verification consumes the token; a second attempt to verify the same value fails (the documented error is `timeout-or-duplicate`) [1][2][29].

This is what a captcha farm's product actually is: freshly minted tokens, sold while still inside their validity window [5][6]. The token's short life is the entire reason farming is an economically bounded business rather than an infinite one.

#### Gate 3: The policy decision (what actually protects you)

The last gate belongs to the application: *what happens after the verdict*. A valid token with a high score proceeds; a low score or failed challenge triggers the app's own escalation policy - block, require email confirmation, require MFA, add rate limits - as Google's own iOS documentation recommends ("implement your own tiered enforcement strategy, such as an MFA flow") [12][29]. This gate must sit on the server that owns the protected action - account creation, first sign-in, sending mail - because anything client-side can be patched, proxied, or skipped [29][32].

The failure taxonomy follows directly: if Gate 1 is missing, there is no fresh evidence; if Gate 2 is missing, tokens are decorative and replayable; if Gate 3 is missing, both of the first two are theater. GrapMail's current screen fails all three (Finding 6).

#### What "the real one" is not

- **It is not a puzzle that looks like reCAPTCHA.** A slider puzzle is solved by trivial automation ("finding the gap is easy for software; the real test is how you drag"), and security vendors are explicit that "a solved slider alone is not proof of humanity" [5]. Visual similarity without the token pipeline is cosplay.
- **It is not a client-side flag.** Any boolean a client sets about itself is attacker-controlled. Verification state that matters must live in a database the attacker cannot write [29][32].
- **It is not optional friction.** The moment a skip exists, the checkpoint's effective strength is zero - honest users skip it and bots skip it faster.

#### A moving target, named accurately

Google now presents "reCAPTCHA" as the brand for its visual bot-defense technology inside a broader "Google Cloud Fraud Defense" platform [21]. The documented integration surface this report relies on - v2 widgets, siteverify, Enterprise assessments - remains the canonical path, and the product naming change does not alter the pipeline described above [2][21].

### Finding 2 - The Web Reference: Checkbox, Challenge, and the Exact Integration Contract

#### What the user sees, and why it is v2

The reference screenshots show the classic reCAPTCHA v2 checkbox: a bordered box labeled "I'm not a robot" that either passes instantly or opens an image challenge such as "select all squares with motorcycles" [31]. Score-based v3 exists and is better suited to background protection, but it deliberately removes the visible checkpoint [5]. For GrapMail's web surface, whose requirement is "exactly like the real one," the visible checkbox is the correct primitive - and it is also the easiest to prove correct, because its contract is fully documented.

#### The integration contract, as documented by Google

**Widget side.** The page loads `https://www.google.com/recaptcha/api.js`; the widget renders automatically from a `g-recaptcha` element with `data-sitekey`, or explicitly via `grecaptcha.render(container, {...})` with parameters for `sitekey`, `callback`, `expired-callback`, `error-callback`, `theme` (light/dark), `size` (normal/compact), and `tabindex` [31]. The `callback` receives the response token when the challenge completes. The `expired-callback` fires when the token's roughly two-minute life runs out; the widget must then be reset via `grecaptcha.reset()`. The `error-callback` handles script/network failures so the UI can react instead of silently stalling [31].

**Server side.** The backend posts `secret`, `response` (the token), and optionally `remoteip` to `https://www.google.com/recaptcha/api/siteverify`; the JSON answer carries `success`, `challenge_ts`, `hostname`, and `error-codes` [1][2]. Only `success == true` - plus a hostname sanity check - may unlock the protected action, and every terminal error (`timeout-or-duplicate`, `invalid-input-secret`, `bad-response`) must **fail closed** [1][2].

**Timing discipline.** Verification happens at submit time, not at page load. Starting the challenge when the user is about to need it avoids expiration churn, and the submit handler must tolerate "token expired while filling the form" by resetting the widget and asking the user to verify again [31][1]. This is exactly how the reference platforms behave in practice: the checkbox remains live, and a stale token produces a fresh challenge rather than a dead end.

#### Branding and disclosure obligations

Google documents the disclosure rules in both directions. The badge may be hidden only if reCAPTCHA branding stays visible in the user flow, with the prescribed text: "This site is protected by reCAPTCHA" [30][41]. Effective April 2, 2026, Google removes Privacy Policy and Terms of Use references from the badge itself and recommends customers remove matching reCAPTCHA-specific references from their sites [42]. For GrapMail's web signup, the cleanest compliant configuration is: badge visible (default), no extra text needed; if the design hides it, add the sentence verbatim near the submit button [30][41].

#### What this means for GrapMail web

There is nothing to migrate - the web app currently has no bot protection at all (Finding 6). The work is additive: load the script, render the checkbox inside the signup form, gate the submit button on a completed challenge, pass the token into the registration payload, and verify it server-side before the account-creation call returns success. Error and expiry paths are part of the contract, not edge cases [31][1][2]. The companion implementation guide contains the exact components, route code, and environment variables for this flow.

### Finding 3 - Mobile Reality on iOS and Android: Frictionless SDKs, WebView Challenges, and Version Discipline

The reference screenshots for this project show the web checkbox flow. That flow cannot simply be transplanted to phones: platform vendors have drawn the mobile line differently, and an honest implementation plan has to respect it.

#### Google's official mobile SDKs are deliberately invisible

reCAPTCHA Enterprise's Mobile SDK - the officially supported native path - is built for a frictionless experience: it scores clients in the background and explicitly avoids "picking fire hydrants from a grid" [15]. On Android the integration contract is: depend on `com.google.android.recaptcha:recaptcha:18.9.4` (or newer), keep the minimum SDK at API 23, initialize the client as early as possible because initialization takes several seconds, and call `execute()` once per protected action with a `RecaptchaAction` [26]. On iOS the requirements are a minimum of iOS 15 and the App Attest capability; the SDK is distributed via Swift Package Manager and CocoaPods (current pod line 18.10.0-beta01) [12][16].

Neither SDK paints a checkbox. Google states the constraint plainly for iOS: "the visual checkbox reCAPTCHA challenge (I'm not a robot) is not available for iOS mobile apps," and recommends a tiered enforcement strategy - such as an MFA step - for suspicious results instead [12]. The same frictionless posture holds on Android [15].

#### If a visible challenge is required, it runs in a WebView - by design

Google's own mobile SDK repository demonstrates the pattern for teams that want visible challenges inside apps: its visual example applications "incorporate visual challenges into a mobile application using a webview" [17]. That is the supported way to show a puzzle inside a native app without pretending a nonexistent native widget exists. Cloudflare's Turnstile documents the same architecture for its own product - native apps embed a WebView that hosts the widget - while warning that WebView configuration is fragile (origin allowlists, consistent user agents), and its iOS WKWebView path carries open reliability complaints from the community [35][40].

#### Natively rendered visible-challenge alternative

If a natively rendered visible challenge is a hard requirement, the vendor to study is hCaptcha: it ships real native Android and iOS SDKs, with the Android one documented as a drop-in replacement for Google's legacy SafetyNet reCAPTCHA API - which historically did serve humans a solvable challenge [19][27]. Friendly Captcha also shipped native Android (Compose) and iOS (SwiftUI/UIKit) SDKs with sub-hour integration claims [13]. Both are credible paths, at the cost of moving the trust relationship away from Google's platform-tied stack [19][13].

#### Version discipline is a security property

reCAPTCHA Enterprise's SDKs are not a set-and-forget dependency: an earlier release carried a documented critical vulnerability that forced a version floor, and community guidance attributes elevated low-score volume partly to outdated SDKs that miss modern signals (device integrity, app attestation, network metadata) [24][25]. GrapMail's mobile apps currently carry no captcha dependency at all, so this is a clean-slate decision - pin current versions from day one.

#### Token rules apply on mobile too

Whatever the client, the server exchange has the same shape: the response token must be verified inside its two-minute window [2], consumed exactly once [29], and treated as a proposal - not a verdict - until the backend assessment returns [29]. Finding 5 turns this into the enforcement design.

### Finding 4 - The Attestation Layer: Play Integrity, App Attest, and App Check

A captcha answers "is a human driving this session?" Attestation answers a different question - "is this a genuine, unmodified app running on a genuine device?" - and mature fraud stacks layer both. Apple's own documentation opens with the reason: "You can't rely on your app's logic to perform security checks on itself because a compromised app can falsify the results" [33].

#### Google Play Integrity (Android)

Play Integrity lets a backend check "that user actions and server requests are coming from your genuine app, installed by Google Play, running on a genuine Android device" [32]. Its documented response pattern is exactly the tiered model GrapMail needs: "Allow, Allow with limits, Allow with limits after CAPTCHA completion, and Deny" [32]. Three engineering notes matter. First, the default quota is 10,000 requests per day across all installs - sufficient for onboarding-scale verification, not for per-request vetting [32]. Second, "attestation farms" generate valid attestation from real devices and relay it to bots, which is why verdicts must be judged in context rather than treated as absolute [32]. Third, for teams already on reCAPTCHA Enterprise, Play Integrity signals are folded into its assessments automatically [32].

#### Apple App Attest (iOS)

App Attest generates a hardware-backed key in the Secure Enclave and produces signatures only that device can create [33]. Two properties shape integration: attestation embeds the hash of a one-time server challenge to resist replay, and the key is tied to the app instance - reinstallation means re-attestation [33]. Google's own iOS guidance confirms the convergence: reCAPTCHA "uses Apple's App Attest as part of its detection engine" [12].

#### Firebase App Check: the convenience wrapper, and its visibility trade-off

Firebase App Check wraps attestation providers (including reCAPTCHA Enterprise) behind one API [22][28]. The trade-off is stated in its own documentation: App Check "requires the use of score-based reCAPTCHA keys, which makes it invisible to users" [22]. That is excellent for quietly protecting API surfaces - and wrong for a screen whose entire purpose is to show a checkpoint.

#### Why attestation does not replace the captcha

Attestation proves the app and device are genuine; it cannot prove the operator is human - farms exist precisely because genuine devices can be rented or relayed [32]. The captcha proves a fresh human attempt; attestation raises the cost of automated attempts and strengthens each risk score. A correct stack uses both: attestation as a signal, captcha as a checkpoint, and server policy as the gate. That gate is the subject of Finding 5.

### Finding 5 - Unskippable by Construction: Server-Side Gating

The mandate "skip button cut it out" is a UI requirement, but unskippability is an architecture property. A checkpoint is truly unskippable only when the protected action cannot complete anywhere - UI path, deeplink, API call, retry loop - without a server-side record that verification succeeded. Everything else is styling.

#### Three enforcement planes, only one of which is binding

1. **Presentation plane (necessary, not sufficient).** The screen shows the real widget with no skip affordance; the continue/submit control stays inert until the widget reports a completed challenge [31][3]. This is what users experience - and it is where the current fake implementation lives (Finding 6).
2. **Request plane (necessary, not sufficient).** The credential the client submits includes the fresh response token, which the backend exchanges in the same request that performs the protected action [1][2]. A client that omits the token simply cannot complete registration.
3. **State plane (the binding one).** The backend keeps its own verification record - for example `human_verified_at` on the signup session - written **only** after a successful server-to-server validation [29][23]. Any attempt to advance the flow without that record fails server-side, no matter which client path is used. This is the plane that survives adversarial clients: you cannot rely on app logic to self-enforce security, because a compromised client can falsify results [33].

#### Validation semantics that make tokens meaningful

- **Verify at the moment of use, inside the window.** Tokens live about two minutes; verification must happen when the action is submitted [1][2]. Issuing the challenge too early and parking the token invites expiration.
- **Consume exactly once.** The backend may redeem a token only once; reuse fails (`timeout-or-duplicate`) and must be treated as a failure, never retried into a pass [1][2][29].
- **Fail closed.** Network errors, missing secrets, mismatched hostnames - every terminal condition must deny the action rather than wave it through [1][2][4]. The widget itself handles expiry and errors by resetting and re-challenging, not by degrading to "pass" [31][3][4].
- **Give mobile assessments full context.** When using Enterprise assessments, pass the recommended request context (user agent, IP address, TLS fingerprints) so scores are computed on complete signals [29].

#### The policy gate is tiered, not binary

A passing verification need not produce a single verdict. Play Integrity's documented ladder - "Allow, Allow with limits, Allow with limits after CAPTCHA completion, and Deny" - is the generalizable policy pattern for GrapMail's backend [32]: full access for clean verdicts; friction-added paths (email confirmation, rate limits, a visible challenge) for ambiguous ones; denial for clear bot signals. The critical property is that **every rung is decided server-side**; clients only render the outcome.

#### "Removing Skip" is a server change

Concretely, cutting the skip out of GrapMail means: (a) delete the skip affordance on every client; (b) refuse to mark signup/onboarding complete unless the server-side verification record exists; (c) require the token on the registration/continue calls and validate it before writing that record [29][23][1][2]. The companion implementation guide contains the exact diffs, endpoints, and environment variables for all three platforms.

### Finding 6 - Audit: Why the Current Implementation Is Fake

Verdict up front: **the current flow passes zero of the three gates** defined in Finding 1. There is no challenge service, no token, no server validation, and no server-side state anywhere on the path. The audit below reflects the live repositories on 2026-11-17.

#### Android (`grapmail-mobile/android`)

- `app/src/main/java/com/grapsee/grapmail/ui/screens/HumanVerifyScreen.kt` renders the "ACCOUNT PROTECTION" screen. Its own source comment documents the hole: the `onDone` callback is "Skip's callback only". Line 179 shows `Text("Skip for now")` with a `clickable { onDone() }` - tapping it advances onboarding directly. Lines 180-183 add the reassuring caption: "You can always complete verification later from your account settings."
- The puzzle is cosmetic: `ui/components/PuzzleSlider.kt` animates a slider, but nothing derives a token from it and nothing evaluates where or how the user dragged.
- `logic/SignUpViewModel.kt` holds `humanVerified: Boolean = false` (L59) plus `markHumanVerified()` (L196) that flips the boolean locally. No network call, no token, no exchange - anywhere.
- Net effect: the flow is fully scriptable. Tap Skip (or set the boolean in memory) and proceed; the screen costs an attacker nothing.

#### iOS (`grapmail-mobile/ios`)

- `GrapMail/FinishScreens.swift` defines `HumanVerifyView` (L6): "ACCOUNT PROTECTION" (L39), "Just one more step!" (L44), and the same escape hatch at L110 - `Text("Skip for now")` wired to `onContinueOnboarding`. The Continue buttons that follow (L136/209/258/330) accept that callback with no verification condition attached.
- `GrapMail/RootView.swift` (L655) presents the view at the `.humanVerify` step with a closure that simply continues.
- `GrapMail/PuzzleSlider.swift` is likewise decorative. No App Attest call, no reCAPTCHA SDK, no server exchange exists in the flow.

#### Web (`Grapsee_All_Time`)

- The web app has **no verification at all**. A scan of `src/` for captcha / robot / human-verification terms returns nothing.
- `src/app/(auth)/signup/SignUpForm.tsx` posts `email`/`password` straight to `${API_BASE}/auth/register` (L151). Nothing gates that call - client or server.

#### The gap, stated plainly

Across three platforms the current design provides: no challenge issuance, no tokens, no validation, no gating state, and - on mobile - an explicit bypass. It is not a weak captcha; it is a screenshot of a captcha. The symptoms the owner flagged ("sooooo unreal", the skip button) are the surface; the root cause is that verification was implemented as decoration instead of as a pipeline. Findings 1-5 define the pipeline that must replace it; the companion implementation guide specifies the replacement code, module by module.

### Finding 7 - The Economics of Bypass: What a Real Checkpoint Can and Cannot Promise

Real verification is not about making automated abuse impossible; it is about making it *uneconomic*. The published economics are blunt: human captcha farms solve challenges for a fraction of a US dollar per 1,000 solves, and bypass services advertise roughly $1-$5 per thousand solves [6][7]. A protection whose only property is "a human could pass it" is a commodity. The pipeline described in Findings 1-5 changes the economics by attacking the parts of the flow that farms cannot commoditize cheaply.

#### Why the current slider design is indefensible

Slider puzzles fail on exactly the axis that matters: automation cost. "Finding the gap is easy for software; the real test is how you drag" - and the drag heuristic degrades to a probabilistic check that scripts have repeatedly beaten [8]. Security analysts are explicit that "a solved slider alone is not proof of humanity", because farms sell solved tokens and forged verification responses on demand [5]. Layered on top, AI-era abuse now operates at enormous scale - the April 2025 AkiraBot framework targeted 420,000+ websites [9] - so any checkpoint whose bypass cost is near zero will be bypassed at whatever rate the attacker chooses [10].

#### Three properties that actually raise the attacker's cost

1. **Short-lived, single-use tokens.** A solved token is useful only for about two minutes and can be redeemed exactly once [1][2][29]. Farms must deliver fresh solves in real time, integrating with the target's flow rather than stockpiling a reusable credential - converting a one-off solve into an ongoing operational dependency, and a detectable one, since replay attempts surface as verification errors (`timeout-or-duplicate`) [1][2].
2. **Server-side assessment with context.** Mobile assessments enrich scoring with request context (user agent, IP address, TLS fingerprints) [29], and Play Integrity folds in device and app genuineness signals that attackers must defeat at the device layer - where the platform pushes back with attestation-farm detection [32].
3. **Tiered policy.** When a signal is weak the response is not just pass/fail: documented practice includes "Allow, Allow with limits, Allow with limits after CAPTCHA completion, and Deny" [32]. An automated attempt that slips through still lands in a lane with a smaller blast radius.

#### The friction trade-off, honestly stated

Visible challenges cost conversions - a documented effect, and worst on mobile [7]. This is exactly why the industry's default mobile posture is frictionless scoring [15][22]. The correct reading is not "avoid visible challenges" but "spend friction where the stakes are": a one-time visible checkpoint at account creation (GrapMail's exact case) is a defensible trade; the same friction on every session is not. What must never ship is *decorative* friction - it pays the full conversion cost and buys no security.

#### What cannot be promised

No vendor promises complete bot elimination; the honest claims are relative - bypass cost rises, farm delivery becomes fragile, and residual abuse gets flagged and throttled. GrapMail's success criterion should be stated the same way: not "bots cannot register", but "bulk automated registration costs more than it is worth, and suspicious attempts are slowed, flagged, and visible".

### Finding 8 - Accessibility, Compliance, and Privacy: The Non-Negotiable Edge Cases

A visible human-verification checkpoint creates obligations that run against the grain of security engineering: the users most harmed by a badly built checkpoint are often those least able to complain loudly. Regulatory frameworks and platform policies both treat the accommodation path as non-optional.

#### WCAG: what compliance actually requires

WCAG does not ban CAPTCHAs; it conditions them. They are permitted provided "text alternatives that identify and describe the purpose of the non-text content are provided, and alternative forms of CAPTCHA using output modes for different types of sensory perception are provided" [36]. Deque's checklist operationalizes this: a sensory alternative "MUST be provided in another sensory modality (e.g. audio)" if a blind user cannot pass the original - and pointedly lists "Avoid CAPTCHAs if Possible" as best practice [38].

#### The audio problem is real

The naive answer - "we'll just offer an audio challenge" - is weaker than it sounds. A University of Maryland study achieved a 90% success rate cracking Google's audio reCAPTCHA using Google's own speech recognition service, and Google's v2 service has "begun declining to actually provide the audio CAPTCHA alternative clearly proffered onscreen" - the fallback is both crackable and not always available [36]. An accessibility plan that depends solely on audio is fragile in both directions.

#### What modern vendors offer instead

The industry has moved toward alternatives that let accommodation users avoid challenge-solving entirely for users who need it:
- hCaptcha states WCAG 2.2 AA compliance based on internal and external audits, and provides dedicated "accessibility challenges" and "accessibility cookies" as alternatives wherever visual challenges cannot be made fully accessible [37].
- Friendly Captcha is "100% invisible" - no clicking, no puzzle solving - works with screen readers and keyboard navigation, and claims WCAG 2.2 AA [39].

For GrapMail this yields a concrete requirement: whichever widget ships, the flow must document and enable the provider's accessibility accommodation path (for reCAPTCHA v2: the widget's built-in assistive option where offered, plus a documented support route; for hCaptcha: accessibility-cookie provisioning) [36][37][38]. Error paths must never dead-end a legitimate user: expiry and failures reset and re-challenge rather than silently block [31][3][4] - while the protected action itself still fails closed whenever verification never succeeds [1][2].

#### Privacy and data-protection posture

Verification vendors process personal data (IP addresses, browser and device characteristics, sometimes interaction telemetry). Google's current framing places the customer as data controller and Google as data processor of customer data [42]; hCaptcha advertises GDPR, CCPA, LGPD, and PIPL compliance even on its free tier [34]. Before rollout, GrapMail's privacy policy should disclose the verification provider, its purpose, and the data categories involved - and the implementation should avoid collecting more than necessary (invisible/score-based paths collect less user-visible data) [34][39][42].

#### Branding and disclosure duties (summary)

- **Web:** if the reCAPTCHA badge is hidden, the text "This site is protected by reCAPTCHA" must appear visibly in the user flow; from April 2, 2026 Google also removes Privacy/Terms links from the badge and recommends removing matching references from customer sites [30][41][42].
- **Mobile:** no badge is required for the invisible SDK; an optional disclosure such as "This app is protected by reCAPTCHA" can live in the about screen, user flow, or footer [42].

#### The rule that ties it together

Every accessibility and compliance accommodation is an *alternative path to the same server-side gate* - never a bypass of it. The verification record is written only after whichever path (visual, audio, cookie, or assisted support) produces a valid verdict [29][37].

### Finding 9 - Vendor Landscape and the Cost Model for GrapMail

#### The candidate matrix

| Option | Web surface | Mobile approach | Visible challenge on mobile? | Free tier | Key caveats |
|---|---|---|---|---|---|
| reCAPTCHA v2 (web reference) | Checkbox + image challenges - the exact reference UX [31] | - | - | Yes (classic) | Badge rules; hideable only with visible text [30][41] |
| reCAPTCHA Enterprise + Mobile SDK | Score/checkbox variants | Frictionless score-based native SDKs [26][12][15] | No native option; webview samples only [17][12] | 10,000 assessments/month [29] | iOS checkbox not available [12]; version floor from past vulnerability [24] |
| hCaptcha | Checkbox + challenges | Native Android/iOS SDKs [19] | Yes (native SDKs; SafetyNet-style) [19] | Basic free tier (GDPR/CCPA/LGPD/PIPL) [34] | Third-party trust; "drop-in replacement" is vendor-stated [19] |
| Cloudflare Turnstile | Managed/invisible widget modes | WebView only [35] | Via webview | Free | iOS WKWebView reliability complaints [40]; UA/origin constraints [35] |
| Friendly Captcha | Invisible proof-of-work-style widget | Native Compose/SwiftUI SDKs [13] | No (invisible posture) [13][39] | Free plan | Visible challenge is not its model |
| Play Integrity API (attestation) | - | Android verdicts [32] | - | 10,000 requests/day [32] | A signal, not a captcha replacement [32] |
| App Attest (attestation) | - | iOS attestation [33] | - | Platform API | Requires custom challenge handling [33] |

#### What each option costs, in practice

- **reCAPTCHA Enterprise (mobile):** under the free monthly assessment allowance (10,000/month) [29], GrapMail's onboarding-scale verification is effectively $0; the Play Integrity quota (10,000/day) likewise covers onboarding volume [32].
- **hCaptcha:** workable free tier; Pro at $139/month includes 100,000 monthly evals, then $0.99 per 1,000 [34]. Its "up to 50% more cost-effective than reCAPTCHA" line is customer-reported comparison data - treat it as marketing weight, not measurement [34].
- **Turnstile:** free, but the WebView-only mobile story and the iOS reliability reports are the real cost [35][40].
- **Friendly Captcha:** positioned as anti-friction; sub-hour native integration claims [13].

All pricing and quota figures are as published at retrieval time (2026-11); re-verify at procurement.

#### Recommended stack for GrapMail

1. **Web:** reCAPTCHA v2 checkbox - the literal reference experience - with siteverify gating before account creation [31][1][2]. Badge visible by default; if hidden, include the required text [30][41].
2. **Android/iOS:** reCAPTCHA Enterprise Mobile SDK as the frictionless baseline [26][12], **plus a WebView-hosted reCAPTCHA checkpoint for the onboarding "ACCOUNT PROTECTION" step** to reproduce the visible checkbox/challenge UX with vendor-backed verification [17]; Play Integrity and App Attest signals layer in where available [32][33].
3. **Fallback vendor:** if a natively rendered mobile challenge ever becomes a hard requirement, hCaptcha's native SDKs are the documented alternative [19][27], with explicit accessibility accommodations [37].

Everything - every platform, every path - validates through one shared backend gate (Finding 5). The companion implementation guide specifies this stack component by component.

## Synthesis & Insights

Five conclusions emerge when the findings are read together - each goes beyond what any single source states.

**1. "Fake vs real" is a three-gate checklist, not an aesthetic judgment.** The audit in Finding 6 shows the current screen fails challenge issuance, token validation, and server gating simultaneously. It is the conjunction of gate failures that makes a verification screen feel wrong to users, and the checklist gives GrapMail a repeatable test for any future change: if a design change cannot name which gate it strengthens, it is decoration [1][2][29].

**2. The repair path is adoption, not construction.** No product team builds its own challenge service; the cryptographic and detection machinery lives with specialist vendors, and the realness of a screen is inherited from *which pipeline stands behind it* plus *what the server does with the verdict* [1][29][32]. GrapMail's engineering effort belongs in integration and enforcement - precisely where the current code does nothing.

**3. The mobile parity paradox has one honest resolution.** Google's documentation forbids expecting a native iOS checkbox [12]; the only path to a screenshot-matching mobile challenge is a WebView-hosted one [17]; and the platform-native default is frictionless scoring [15]. The recommended design embraces both truths at once - visible WebView checkpoint at the high-stakes onboarding moment, invisible signals everywhere else, one server gate behind everything. That satisfies "exactly like the real one" in the only way reality permits: identical verification *semantics*, platform-appropriate *presentation*.

**4. Unskippability is a server property - so "cutting the skip button" must be a server change.** Removing the skip link is the visible quarter of the work; the rest is the verification record that clients cannot write and endpoints that refuse to advance without it [29][33]. This ordering matters: ship the gate first, or the UI change merely makes the fake look stricter.

**5. The economics supply GrapMail's success metric.** With farms solving at $1-$5 per thousand [7] and AI frameworks operating at 420,000-site scale [9], "real" should be defined economically: make a fake GrapMail account cost more than it is worth, keep honest onboarding friction near zero, and treat verification error telemetry (expirations, duplicates, low scores) as the early-warning instrument [1][2][29]. That metric survives contact with attackers better than any promise of elimination.

## Limitations & Caveats

**Scope of validation.** This run produced a research report plus an implementation blueprint and code. The code specifies exact integration points and follows vendor-documented contracts, but live end-to-end smoke tests against production reCAPTCHA/Play Integrity/App Attest backends were not performed inside this research workflow; the companion implementation guide carries the pre-launch test checklist that closes this gap.

**Evidence quality tiers.** Load-bearing platform facts come from primary documentation (Google, Apple, Cloudflare, W3C, Deque) [1][2][12][26][31][32][33][35][36][38]. Bypass economics and industry posture come from security-industry analysis and vendor research pages and are directional rather than measured universals [5][6][7][8][9][10]. Operational anecdotes - the WKWebView failures, the low-score volume reports - are community reports, flagged as such, not controlled measurements [40][25]. Vendor claims (WCAG 2.2 AA compliance, cost comparisons, "drop-in" compatibility) are recorded as claims; organizations should reproduce them under their own compliance and load-testing regimes [34][37][39][19].

**Time-sensitivity.** All pricing and quota figures (10,000 assessments/month; 10,000 Play Integrity calls/day; hCaptcha Pro at $139/month) reflect published values at retrieval time (2026-11) and change without notice [29][32][34]. Google's product naming continues to evolve - "reCAPTCHA" now lives inside the broader "Google Cloud Fraud Defense" platform - so document URLs and product labels may drift even though the integration surfaces used here remain documented [21]. Third-party tutorials written before April 2, 2026 may still instruct showing Google Privacy/Terms links on the badge; current guidance removes them [42].

**Technical caveats.** The "about two minutes" token lifetime is a design constraint, not a guarantee - verify at submit and treat expirations as a normal UX event, not an error [1][2]. The WebView challenge path is demonstrated by Google's own mobile samples, but production hardening is required: origin allowlists, consistent user agents, and device-matrix testing before rollout - the same WebView mechanics have produced documented reliability failures for comparable widgets on some iOS devices [17][35][40]. On iOS, "identical to the web checkbox" cannot be delivered natively; the closest legitimate parity is the WebView-hosted checkpoint, and acceptance criteria should be stated as identical verification semantics with comparable presentation [12][17]. Accessibility accommodations cannot rely on audio alone (90% crack rate; availability withdrawn in some deployments), so non-audio alternatives and a human support route are part of the requirement, not extras [36][38]. Attestation signals have their own limits - attestation farms exist, quotas are small, and keys are lost on reinstall [32][33].

**Code-audit caveats.** The audit in Finding 6 reflects repository state on 2026-11-17 with specific line numbers; routine refactoring may shift them. All behavioral claims about the current implementation were verified directly against the source files during this run.

**Out of scope.** Jurisdiction-specific privacy/legal reviews (several regulators have examined captcha data flows), enterprise contract negotiations, adversarial penetration testing of the new stack, and abuse vectors unrelated to signup (for example credential stuffing against existing accounts) are not covered here.

**Residual uncertainty.** No vendor has committed to shipping a native visible-checkbox widget on iOS; if that changes, the mobile design simplifies. Until then, the WebView checkpoint is the documented path to visible mobile challenges [17][12].

## Recommendations

Ordered for execution; the companion implementation guide turns each into concrete code and configuration.

### P0 - Make the gate real before making the screen pretty (server + skip removal)

1. **Backend gate first.** Add a verification endpoint pair (issue/validate) that consumes the vendor response token exactly once inside its validity window and writes an authoritative, server-side verification record; then modify the registration/onboarding-complete endpoints to refuse any request lacking that record [1][2][29]. Fail closed on all errors; log error codes (`timeout-or-duplicate`, `invalid-input-secret`, and so on) as security telemetry [1][2].
2. **Remove every skip path.** Android: delete the `Text("Skip for now")` block and its caption (L179-183 region) and retire `markHumanVerified()`'s local-only semantics; iOS: delete the "Skip for now" control (L110 region) and ensure the continue callbacks cannot advance without the server-confirmed state; Web: nothing to remove - but the submit path must gain the gate [3][4]. Acceptance test: with the vendor check forced to fail, no client path completes signup or onboarding; with it passing, all paths complete within one interaction.

### P1 - Ship real widgets on every surface

3. **Web:** render the reCAPTCHA v2 checkbox in `SignUpForm` with the documented callback/expired/error contract, gate submit on a completed challenge, include the token in the register payload, and verify server-side before creating the account; keep badge/text branding compliant [31][1][2][30][41].
4. **Android:** add `com.google.android.recaptcha:recaptcha:18.9.4` (or newer), initialize the client in `Application.onCreate` (early, asynchronous), and call `execute()` with an action name at the checkpoint; replace `HumanVerifyScreen`'s decorative slider with the WebView-hosted challenge for the visible UX, and wire "continue" to the server validation result [26][17][24].
5. **iOS:** add the SPM dependency (minimum iOS 15, App Attest capability), initialize and `execute()` per the official pattern, host the visible challenge in the same WebView approach, and rewire `FinishScreens`' continue path to require the server-confirmed state [12][16][17].
6. **Backend (mobile):** create assessments with the recommended context fields (user agent, IP, fingerprints) for Enterprise tokens, and enforce single-use consumption across all platforms through one shared gate [29][23].

### P2 - Harden, measure, comply

7. **Layer attestation and policy:** add Play Integrity verdicts on Android and App Attest assertions on iOS as scoring signals; implement the tiered policy (allow / allow with limits / allow with limits after challenge / deny) server-side; keep quotas in mind (10k/day Play Integrity) [32][33].
8. **Instrument the economics:** dashboards for challenge completion rate, token expiry rate, duplicate-validation attempts, and score distributions; alert on anomalies - these are the sensors for farm activity [29][25].
9. **Accessibility and disclosure:** document and enable the provider's accommodation paths (accessibility cookies/alternatives where applicable), provide a support route for users who cannot complete any challenge, and add the prescribed branding text wherever badges are hidden [37][38][41][42].
10. **Re-verify commercials at scale and keep a fallback:** re-check pricing/quotas at contract time; if a natively rendered mobile challenge becomes a hard requirement, hCaptcha's native SDKs are the evidenced alternative [34][19][27].

### Success criteria (economic framing)

The rollout is successful when: (a) a fake account costs more to create than the farm market rate implies it should ($1-$5 per 1,000 solves is the attacker baseline to beat in effort terms) [7]; (b) honest onboarding conversion shows no material regression (visible challenges are the most expensive friction, so measure, do not assume) [7]; (c) zero flows complete without a server-side verification record (enforced by canary tests in CI); and (d) verification error telemetry is quiet and monitored as the early-warning system [1][2][29].

## Bibliography

All sources were retrieved on 2026-11-17; entries are in citation order.

[1] Verify user response tokens with legacy SiteVerify (Google Cloud). https://docs.cloud.google.com/recaptcha/docs/verify
[2] Verifying the user's response (reCAPTCHA docs). https://developers.google.com/recaptcha/docs/verify
[3] hCaptcha Developer Guide. https://docs.hcaptcha.com
[4] Recaptcha Verification Failed: Causes, Fixes, And Quick Checks. https://www.ituonline.com/blogs/troubleshooting-recaptcha-verification-failed-errors
[5] reCAPTCHA v2 vs. v3: An Outdated Approach to Bot Protection (DataDome). https://datadome.co/guides/captcha/recaptchav2-recaptchav3-efficient-bot-protection
[6] CAPTCHA Security in 2026: Proof-of-Work and Risk Signals (FriendlyCaptcha). https://friendlycaptcha.com/insights/captcha-security
[7] Is CAPTCHA still effective, and what should you use instead? (Arcjet). https://arcjet.com/learn/captcha-alternatives
[8] What Is CAPTCHA? How It Works and 11 Types (Scrape.do). https://scrape.do/blog/captcha
[9] CAPTCHAs Lost to AkiraBot on 80,000 Sites (Louis Wright). https://louiswright.co.uk/writing/captcha-free-form-protection
[10] What is CAPTCHA? Types and Examples (Wallarm). https://www.wallarm.com/what/what-is-captcha-types-and-examples
[11] Integrating reCAPTCHA Enterprise into an iOS app (Ping Identity). https://developer.pingidentity.com/orchsdks/journey/use-cases/recaptcha-enterprise/ios-recaptcha-enterprise.html
[12] Integrate Google Cloud Fraud Defense with iOS apps (Google Cloud). https://docs.cloud.google.com/recaptcha/docs/instrument-ios-apps
[13] Mobile SDK for Android and iOS (Friendly Captcha). https://developer.friendlycaptcha.com/blog/mobile-sdk-for-android-ios
[14] ReCaptcha implementation in iOS Native app (Stack Overflow). https://stackoverflow.com/questions/75873895/recaptcha-implementation-in-ios-native-app
[15] Introducing reCAPTCHA Enterprise's Mobile SDK (Google Cloud Blog). https://cloud.google.com/blog/products/identity-security/introducing-recaptcha-enterprises-mobile-sdk-to-help-protect-ios-android-apps
[16] RecaptchaEnterprise - Swift Package Registry. https://swiftpackageregistry.com/GoogleCloudPlatform/recaptcha-enterprise-mobile-sdk
[17] recaptcha-enterprise-mobile-sdk (GitHub). https://github.com/googlecloudplatform/recaptcha-enterprise-mobile-sdk
[18] Developer's Guide - reCAPTCHA (Google for Developers). https://developers.google.com/recaptcha/intro
[19] Mobile App SDKs (hCaptcha docs). https://docs.hcaptcha.com/mobile_app_sdks
[20] Integrate with Google reCAPTCHA Enterprise (Ping SDKs). https://docs.pingidentity.com/sdks/latest/sdks/integrations/integrate-with-recaptcha-enterprise.html
[21] reCAPTCHA website security and fraud protection (Google Cloud). https://cloud.google.com/security/products/recaptcha
[22] App Check with reCAPTCHA Enterprise on Android (Firebase). https://firebase.google.com/docs/app-check/android/recaptcha-enterprise-provider
[23] Setup overview for mobile applications (Google Cloud). https://docs.cloud.google.com/recaptcha/docs/setup-overview-mobile
[24] Critical security vulnerability in reCAPTCHA Enterprise (Stack Overflow). https://stackoverflow.com/questions/77798636/critical-security-vulnerability-in-recaptcha-enterprise
[25] Increase in low reCAPTCHA Enterprise scores (Google Cloud Community). https://security.googlecloudcommunity.com/fraud-defense-recaptcha-6/increase-in-low-recaptcha-enterprise-scores-7583
[26] Integrate Google Cloud Fraud Defense with Android apps (Google Cloud). https://docs.cloud.google.com/recaptcha/docs/instrument-android-apps
[27] SafetyNet reCAPTCHA API (Android Developers). https://developer.android.com/privacy-and-security/safetynet/recaptcha
[28] App Check with reCAPTCHA Enterprise in web apps (Firebase). https://firebase.google.com/docs/app-check/web/recaptcha-enterprise-provider
[29] Create assessments for mobile applications (Google Cloud). https://docs.cloud.google.com/recaptcha/docs/create-assessment-mobile
[30] reCAPTCHA branding guidelines (Google for Developers). https://developers.google.com/recaptcha/docs/branding
[31] reCAPTCHA v2 (display) - Google for Developers. https://developers.google.com/recaptcha/docs/display
[32] Overview of the Play Integrity API (Android Developers). https://developer.android.com/google/play/integrity/overview
[33] Establishing your app's integrity (Apple Developer). https://developer.apple.com/documentation/devicecheck/establishing-your-app-s-integrity
[34] hCaptcha Pricing - Pro & Enterprise Plans. https://www.hcaptcha.com/pricing
[35] Mobile implementation - Cloudflare Turnstile docs. https://developers.cloudflare.com/turnstile/get-started/mobile-implementation
[36] Inaccessibility of CAPTCHA - W3C Working Group Note. https://www.w3.org/TR/turingtest
[37] Accessibility - hCaptcha. https://www.hcaptcha.com/accessibility
[38] CAPTCHA - Web Accessibility Checklist (Deque). https://dequeuniversity.com/checklists/web/captcha
[39] Accessible CAPTCHA - Friendly Captcha. https://friendlycaptcha.com/accessibility
[40] Turnstile no longer working inside of iOS apps (in WKWebView) - Cloudflare Community. https://community.cloudflare.com/t/turnstile-no-longer-working-inside-of-ios-apps-in-wkwebview/637940
[41] reCAPTCHA FAQ - Google for Developers. https://developers.google.com/recaptcha/docs/faq
[42] Frequently Asked Questions - Google Cloud Fraud Defense. https://docs.cloud.google.com/recaptcha/docs/faq

## Appendix A - Research Methodology

This report was produced under the deep-research workflow on 2026-11-17, in four stages. **Scope**: eight sub-questions fixed the research space - verification mechanics; mobile SDK reality; alternative vendors; server-side enforcement; bypass economics; accessibility and compliance; vendor selection and costs; and the GrapMail implementation blueprint. **Collection**: a multi-wave search program covered each sub-question, with primary vendor documentation (Google Cloud, Apple, Cloudflare, hCaptcha, W3C, Deque) preferred over tutorials and community reports; 42 sources were registered with stable identifiers and 64+ verbatim evidence quotes were extracted with locators, preserved as machine-readable records (`sources.jsonl`, `evidence.jsonl`) alongside readable indexes (`citation_map.md`, `bibliography.md`) in the research workspace. **Triangulation**: each load-bearing claim requires either multiple independent sources or a primary vendor document; disagreements - vendor cost comparisons, Cloudflare community reliability reports, low-score volume anecdotes - were recorded as caveats rather than averaged away. **Audit**: the assessment of the existing implementation (Finding 6) was performed directly against the GrapMail codebase - Android Kotlin, iOS Swift, and the Next.js web application - with file-and-line evidence captured during this run; no behavior was asserted without reading the corresponding source.

All web retrieval occurred on 2026-11-17; time-sensitive figures (pricing, quotas, SDK versions) reflect that date and should be re-verified at procurement. This report is the research half of the engagement; the companion implementation kit (guide + code) is delivered alongside it.

## Appendix B - Deliverables and File Map

The engagement deliverable set, and where each item lives:

1. **Research report** - `research_report_20261117_grapmail-human-verification.md` together with its rendered `research_report_20261117_grapmail-human-verification.html`; delivered in `/mnt/cos/artifacts/` and copied into the `@Grapsee All Time` and `@grapmail-mobile` project folders.
2. **Implementation guide** - `IMPLEMENTATION-GUIDE.md`: architecture, per-platform step-by-step integration (web, Android, iOS, backend), exact integration points from the audited repositories, key management, and the pre-launch test checklist.
3. **Code kit** - ready-to-apply source files per platform: web (reCAPTCHA v2 component, `SignUpForm` integration, `verify-human` API route), Android (`RecaptchaManager`, `HumanVerifyScreen` replacement, `SignUpViewModel` changes), iOS (`HumanVerificationViewModel`, `FinishScreens` changes, `RootView` wiring), and backend (shared verification gate + register gating).
4. **Evidence data** - `sources.jsonl` (42 registered sources), `evidence.jsonl` (64+ quotes), `citation_map.md`, `bibliography.md`, `triangulation.md`, and the fetched vendor documentation notes under `deep-research/20261117-humanverify/`.

Acceptance framing: every numbered citation in this report traces to a URL in the Bibliography; the audit's line references can be checked against the repositories; and the code kit's integration points match the audited file paths.

<!--END-->
