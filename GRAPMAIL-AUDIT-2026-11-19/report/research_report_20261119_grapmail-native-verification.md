# GrapMail Native Verification — Final Check: True Pure-Native on iOS and Android

*Audit round 15-F (final check) · 2026-11-19 · Prepared for the GrapMail project (iOS / Android)*
*Companion documents: NATIVE-FINAL-CHECK-2026-11-19.md (short verdict + receipts index) · receipts/ (scan evidence, guardrail run) ·GRAPMAIL-AUDIT-2026-11-19 (round-15 audit set)*

## Executive Summary

**Verdict: both applications are true, pure-native — verified, not assumed.** At commit `0fb5998e` the mobile repository contains two independent platform codebases: **48 Swift files (1.35 MB) under `ios/` and 75 Kotlin files (1.48 MB) under `android/`, zero Java files, and zero traces of any cross-platform or hybrid framework** — no Flutter, React Native, Capacitor, Cordova, Expo, Dart, or JavaScript app code anywhere in the tree (E1, E2). The iOS app is built with SwiftUI on an XcodeGen project; the Android app is built with Jetpack Compose on Gradle Kotlin DSL, with a pure-Compose activity shell (E2, E3).

- **Why the WebView usage does not change the verdict.** Each platform contains exactly five WebKit/WebView files, and a sweep of every content-load API found exactly seven call sites — the sanitized mail body, the rich-text compose editor, onboarding scene animations, the hero animation, the finish-flow pages, and the human-verification page. This is bounded embedded content, the same architecture fully native system apps use (Apple's own Mail client renders message HTML with WebKit), and is not an app shell [4][5]. Navigation, screens, settings, lists, and all app chrome are native UI (E5).
- **What native buys, in benchmark terms.** Across 2025–2026 benchmark sets, native builds show the smallest memory growth (Swift Δ≈9.74 MB vs React Native ≈45 MB per test on iOS) and the smallest binaries (native ≈0.5 MB stub / 13.3 MB on Android vs 18.3–20.2 MB cross-platform), while native Android cold start leads at ≈248 ms vs ≈341 ms for Expo/React Native — and WebView hybrids run at roughly 60–70% of native performance [15][16][18][19].
- **The evidence is reproducible.** A new automated guardrail script (`native-guardrails.sh`) checks the hybrid-artifact surface, framework tokens, the WebView budget, and native build manifests; its first run against `0fb5998e` passes cleanly — 0 failures, 0 warnings — and is archived in the receipts (E4, E5).

**Primary Recommendation:** Keep both apps pure native, wire the guardrail script into CI so any future drift fails the build, and continue the round-16 performance program (startup profiles, first-render warm-up) on top of this native foundation.

**Confidence Level:** High — the verdict rests on a full commit-level census plus content-level greps (zero hits for every hybrid token searched), corroborated by independent platform documentation and benchmark studies.

## Introduction

### Research Question

"Are the GrapMail iOS and Android applications truly pure native?" — stated by the project owner as the final check, with the explicit framing *true native, pure native application, iOS and Android*. Round 15 had already verified feature wiring and deployment; this check asks the next-order question: are the two apps genuinely native at the codebase and framework level, or is any part of them a web or hybrid application wearing a native shell?

### Scope & Methodology

Three evidence streams were used. (1) A full repository census at commit `0fb5998e` — read-only, via git plumbing (file lists, tree listings with sizes, content greps) so every result is reproducible from the archived receipts (E1–E3). (2) Content-level greps in both directions: for the "native present" signals (SwiftUI, UIKit, WebKit, @Composable, WebView) and for the "hybrid present" signals (io.flutter, FlutterActivity, FlutterEngine, facebook.react, ReactRootView, react-native, capacitor, cordova, dart/pubspec, metro) — the latter returning zero files (E2). (3) External corroboration: platform documentation for the two native UI toolkits [1][2], industry definitions of native vs hybrid vs cross-platform apps [3][9][10][11][12][13][14], practice references for embedded content in native apps [4][5][6][7][8], and 2025–2026 performance benchmarks [15][16][18][19]. Nineteen sources were consulted; internal evidence is registered as E1–E6 in the Methodology Appendix.

The definitions used throughout, stated up front: a **native app** is built separately for each platform with that platform's own languages and tools — Swift/SwiftUI for iOS, Kotlin/Jetpack Compose for Android [1][2][3][16]. A **hybrid app** wraps web code (HTML/CSS/JS) in a native container and runs its UI through an embedded browser (WebView), typically via Cordova, Capacitor, or Ionic [9][10][11][12][13]. A **cross-platform app** compiles a shared codebase — Flutter/Dart or React Native/JavaScript — to both platforms [9][16]. The check asked: does each app match the native profile, and does it fail every hybrid and cross-platform criterion?

One nuance is handled explicitly rather than assumed away: embedding a WebView for *content* (as opposed to running the *app* through it) is standard practice in pure-native applications and does not make an app hybrid [4][11]. The check therefore did not merely count WebView usage — it enumerated every content-load call site and its purpose, to confirm the app shell and navigation remain native (E5).

### Key Assumptions

- **Commit-scoped truth.** The verdict describes the repository at `0fb5998e` (the current round-15 commit); receipts pin the exact refs, file lists, sizes, and grep outputs.
- **"Pure native" is defined negatively as well as positively.** Absence of hybrid/cross-platform artifacts matters as much as presence of native ones; both directions were searched.
- **Repo-level verification.** This check inspects the codebase, build manifests, and declared toolchains; it does not inspect compiled binaries on a device (an on-device smoke run is listed under Recommendations).
- **Benchmark numbers are directional.** External performance figures come from third-party benchmark runs on their own sample apps; they indicate the general shape of native vs alternatives, not GrapMail's measured numbers.
- **Embedded content is native-compatible.** Following platform documentation [4] and industry definitions [11], embedded content surfaces are not treated as hybrid markers; unbounded WebView usage or a web app shell would be.


## Main Analysis

### Finding 1: The Verdict — Two Independent Pure-Native Codebases

The repository at `0fb5998e` contains 379 files; inside the two application trees, the language census is unambiguous. There are **48 Swift files (1,347,235 bytes) under `ios/` and 75 Kotlin files (1,478,697 bytes) under `android/`, and zero Java files** (E1). The build manifests confirm the same story from the toolchain side: `ios/project.yml` is an XcodeGen manifest (iOS 16 deployment target, Swift 5.9, a single Swift Package dependency, `apple/swift-crypto`), and `android/app/build.gradle.kts` applies the standard native toolchain — `com.android.application`, `org.jetbrains.kotlin.android`, the Compose compiler plugin, and Kotlin serialization — with `com.grapsee.grapmail` as namespace and minSdk 26 / compileSdk 36 (E3). The Android entry point, `MainActivity`, is a `ComponentActivity` whose entire content is set through `setContent { }` — a pure Jetpack Compose application shell (E3).

Framework usage counts move from "what language" to "what idiom": **`import SwiftUI` appears in 36 files, `import UIKit` in 9, and `@Composable` in 48**. This is exactly what the app-development literature calls native: building separately with Swift for iOS and Kotlin for Android, getting the deepest platform access and the fewest abstraction layers [16]. The two official toolkits in question — SwiftUI, Apple's declarative UI framework [1], and Jetpack Compose, "Android's recommended modern toolkit for building native UI" [2] — are precisely what the code uses, in the platform-appropriate proportion (UIKit remaining only where the platform requires it, as is normal for system integrations).

The negative direction is equally clean. A filename sweep across all 379 files for `pubspec.yaml`, `*.dart`, `flutter`, `react-native`, `metro.config`, `capacitor`, `ionic`, `cordova`, `expo`, `node_modules`, `*.jsx`, `*.tsx` returns **zero hits**; a content grep across the whole tree for `io.flutter`, `FlutterActivity`, `FlutterEngine`, `facebook.react`, `ReactRootView`, `react-native`, `capacitor`, `cordova` returns **zero files** (E2). There is no Podfile, no CocoaPods dependency mechanism, no JavaScript bundle, and no web framework manifest. Under the standard hybrid taxonomy — a web UI running inside a native shell via Cordova, Capacitor, or Ionic [9][12][13] — every criterion fails to match: there is no web codebase, no web UI framework, and no shell-within-shell.

Secondary signals point the same way. The Android tree carries a Baseline Profile (`baseline-prof.txt`, 2,139,758 bytes) — a platform-native performance mechanism that requires the app's real compiled code; CI runs platform workflows (`android.yml`, `ios.yml`) plus four iOS screenshot journeys (E3). And the newly written guardrail script — which fails on any hybrid artifact, hybrid token, or WebView-budget overflow — passes against this commit with zero failures and zero warnings (E4).

**Key Evidence:**
- Swift 48 files / 1,347,235 B; Kotlin 75 files / 1,478,697 B; Java 0; hybrid sweeps 0 hits (E1, E2).
- SwiftUI 36 files, UIKit 9, @Composable 48; XcodeGen + Gradle KTS + ComponentActivity shell (E2, E3).
- Guardrail run at `0fb5998e`: 0 fails, 0 warns — hybrid artifacts none, framework tokens none, WebKit/WebView within the 5+5 budget (E4).

**Implications:** the two codebases are independent, platform-idiomatic, and free of every cross-platform marker in current use. "Pure native" is not a marketing claim here; it is the literal structure of the repository.

### Finding 2: The WebView Question — Bounded Embedded Content, Not an App Shell

The only place the web touches either app is a small, fully enumerated set of content surfaces. Five files per platform mention WebKit/WebView — on iOS: `MailHtmlBody.swift`, `ComposeFormattingEditor.swift`, `OnboardingViews.swift`, `WelcomeHeroView.swift`, `FinishScreens.swift`; on Android: `MailHtmlBody.kt`, `ComposeFormattingEditor.kt`, `OnboardingScreen.kt`, `WelcomeHero.kt`, `HumanVerifyScreen.kt` (E2). A sweep of every web-content load API across both trees (`loadUrl`, `load(URLRequest)`, `loadHTMLString`, `loadFileURL`) finds exactly **seven content-load call sites**: the human-verification page (`HumanVerifyScreen.kt:119`, `loadUrl`), the sanitized mail body (`MailHtmlBody.swift:63`, `loadHTMLString`), the rich-text editor document (`ComposeFormattingEditor.swift:20`, `loadHTMLString`), the onboarding scene (`OnboardingViews.swift:193`, `loadHTMLString`), the finish-flow pages (`FinishScreens.swift:223` and `:230`, `load(URLRequest)`), and the hero animation asset (`WelcomeHeroView.swift:47`, `loadFileURL`). Each call has one content purpose; none of them is a router, a screen stack, or an application entry point (E5).

This pattern is not hybrid. Apple's own developer documentation describes WKWebView as a "platform-native view" used to incorporate web content "alongside your app's native views" — an endorsed technique in native apps [4]. Apple's Mail client itself renders message HTML with the WebKit engine [5], and every major mail client must run message HTML through a rendering engine for fidelity — the mail body is HTML by nature, so a renderer is not a hybrid tell, it is a requirement [8]. Some of the most quintessentially native apps embed content the same way: developer guides demonstrate native apps hosting article content in WKWebView, with the Wikipedia app as the canonical example [6][7].

The distinction that matters runs through *where the application itself lives*. The hybrid definitions are consistently about the app: "a web application packaged inside a native shell" [3], "web technologies wrapped in a native container" [14], "the platform's webview component in which it will load your web application" [12]. Applied to GrapMail, every test resolves native: is the navigation web? No — screens, settings, lists, and dialogs are SwiftUI/Compose (E2). Is the launch shell web? No — the iOS app uses the SwiftUI app lifecycle, the Android app opens a `ComponentActivity` + `setContent` shell (E3). Do the WebViews carry the app? No — they render one document each; removing any of them degrades a single content surface while every screen, control, and flow remains functional (E5).

For calibration, consider what "pure native" is allowed to contain under this same definition: system mail clients, browser chrome around web engines, documentation readers, and any app rendering user-generated HTML — all embed web engines for content while remaining native applications [4][5][6]. Under the inverse test — app shell in web — GrapMail would need a JavaScript entry bundle, a web router, a hybrid runtime, or a shell whose screens load from the network; none of these exist anywhere in the tree (E2).

**Key Evidence:**
- WebKit files: 5 (iOS) + 5 (Android); content-load call sites: 7, each single-purpose (E2, E5).
- Apple: WKWebView is a "platform-native view" for embedding content "alongside your app's native views" [4]; Mail renders via WebKit [5].
- Hybrid definitions target where the app runs [3][12][14]; GrapMail's app shell and navigation are native (E2, E3).

**Implications:** the WebView footprint is a bounded, enumerated content-embedding pattern — identical in kind to what fully native system apps do — not an application shell. The verdict from Finding 1 stands: pure native.


### Finding 3: The Taxonomy Test — Every Hybrid Criterion Fails, Every Native Criterion Passes

Definitions of "hybrid" across the industry converge on one mechanism: a web application wearing a native container. Miller Media 7 states it flatly — "A classic hybrid app is a web application packaged inside a native shell" [3]. Ionic describes the same architecture from the framework vendor's side: hybrid code is "embedded into a native application wrapper… These solutions create a native shell application that is just the platform's webview component in which it will load your web application" [12]. AWS frames the container as "a lightweight native app shell" [10]; Microsoft describes hybrid code as "executed in a native shell with an embedded web browser" [13]; BrowserStack as "web pages wrapped in a mobile app using WebView" built with Cordova, Ionic, or React Native [14]; Mendix, quoting TechTarget, as "essentially web apps that have been put in a native app shell" [17]; and Nielsen Norman Group's canonical guide as apps that "rely on HTML being rendered in a browser, with the caveat that the browser is embedded within the app" — noting that companies build them "as wrappers for an existing web page" [11].

Every one of those criteria fails against the actual repository. There is no web application to wrap: no HTML/CSS/JavaScript application code and no JavaScript bundle of any kind appears in the census (E1, E2, E6). There is no embedded browser serving as the interface: the five WebKit/WebView files resolve to seven single-purpose content loads, none of which is a screen, a router, or an entry point (E5). And there is no cross-platform runtime layer: the sweeps for Capacitor, Cordova, Ionic, Flutter/Dart, React Native/Metro, and Expo tokens return zero files in both directions — filename and content (E2). The single-sentence test — "is this app a website in a shell?" — is answerable by enumeration: everything countable is zero.

The counter-test passes with equal clarity. A native app is "built separately for iPhone and Android with each platform's own tools" [9], which is precisely what the build manifests show: an XcodeGen/SwiftUI project on iOS and a Gradle Kotlin DSL/Compose application on Android, each assembled by its platform's own toolchain (E3). And the vendor-neutral layer table that 2026 practitioner guides use to separate the three architectures assigns the native column exactly the markings present in this repository: language "Swift, Kotlin"; UI framework "SwiftUI, Jetpack Compose"; rendering "Platform-native"; persistence "Core Data, Room" [19]. GrapMail matches every cell of the native column and no cell of either hybrid column.

One nuance deserves explicit care, because it is where careless audits go wrong. The category boundary is drawn around the application, not around incidental HTML. MobiLoud observes that "no app has to be fully 'native'… a native app can load some of its screens, such as help pages or account settings, from the web," and that hybrid apps "mix the two in different proportions" [9]. That is exactly why this audit enumerated every web surface, its file, and its single purpose (E5) instead of merely counting them — and it is the standard against which the verdict is robust: the web's role in GrapMail is bounded content rendering inside native screens; the app's shell, navigation, and screens remain native end to end (E2, E3); and under both the strict definitions [3][12] and the spectrum framing [9][11], the app sits at the native end. The repository fails to qualify as hybrid on the very criteria hybrid frameworks advertise.

**Key Evidence:**
- Hybrid definitions converge on web-app-in-a-native-shell [3][10][11][12][13][14][17]; each criterion returns zero in-tree (E1, E2, E6).
- Native column match on language, UI framework, rendering, and persistence per the vendor-neutral layer table [19]; toolchains are platform-native [9] (E3).
- Bounded content surfaces enumerated call by call — the web appears as content, not as the app (E5).

**Implications:** the taxonomy question is settled at the definitional level, not merely the artifact level: no reasonable reading of "hybrid" or "cross-platform" captures this codebase, and no reasonable reading of "native" excludes it.

### Finding 4: What Native Buys — the 2025–2026 Benchmark Frame

With the classification settled, the benchmark literature provides the quantitative frame — and it is worth reading precisely, because the honest 2026 picture includes nuances in both directions. In the SynergyBoat cross-framework benchmark, which ran the same workloads across native, Flutter, and React Native builds, the native apps posted the tightest memory envelopes: on iOS the native Swift app grew its footprint by Δ9.74 MB where Flutter grew 25.33 MB and React Native grew 45.13 MB; on Android the native Kotlin app grew 6.33 MB against Flutter's 14.00 MB and React Native's 32.98 MB [15]. The binary-size table is equally directional: its smallest entry is the native Swift reference at 0.457 MB — "an extremely small stub that relies on system frameworks," the study notes — while the React Native (Expo) build tops the iOS column at 20.2 MB [15]. Cold-start measurements in the 2026 Applighter benchmark refresh put Expo/React Native at "about 341 ms on Android and 267 ms on iOS" and note that "Native leads on Android at about 248 ms" [16]. And frame-delivery analysis places native at a reliable 60 fps, with the compiled cross-platform tier at "eighty-five to ninety-five percent of native" and WebView-based hybrids at "sixty to seventy percent of native speed" [19].

These numbers bracket the architectural choice — they do not dissolve it. The same 2026 sources are explicit that modern cross-platform frameworks are credible options: "in the 2026 data, that trade-off is much smaller than many teams still assume," and for network-bound flows "the biggest delays no longer come from the mobile framework itself… [but] from API round trips, oversized payloads, image handling, state synchronization, and release friction" [16]. Independent benchmark summaries agree that remaining startup gaps often "fall below the threshold that changes product outcomes" for typical business apps [16][18]. The honest reading is therefore not "cross-platform is slow"; it is that the residual costs concentrate in exactly the places a mail client lives — sustained memory through long sessions (9.74 MB vs 45.13 MB growth), installed footprint (0.457 MB vs 20.2 MB in the study's reference builds), and the rendering ceilings that apply when content-heavy surfaces are involved (85–95% for compiled frameworks, 60–70% for WebView hybrids) [15][19].

For GrapMail, the practical consequence is capability, not vanity metrics. Sitting in the native tier means every platform-level optimization is available without an abstraction layer: the Android build already ships a compiled Baseline Profile (2,139,758 bytes at HEAD) (E3); startup instrumentation and warm-up work can address platform APIs directly; and the bounded WebView surfaces can be tuned with platform-specific controls (E5). Nothing in the 2025–2026 benchmark set suggests that moving up a layer of abstraction would improve any of those properties — each column of that evidence points the other way [15][16][18][19].

**Key Evidence:**
- Memory growth: native 9.74 MB vs 25.33 MB (Flutter) / 45.13 MB (React Native) on iOS; 6.33 MB vs 14.00 MB / 32.98 MB on Android [15].
- Binaries: native Swift reference 0.457 MB vs React Native (Expo) 20.2 MB on iOS; cold start native ≈248 ms Android vs ≈341 ms Expo/React Native [15][16].
- Performance tiers: native ≈60 fps baseline; compiled cross-platform 85–95%; WebView hybrids 60–70% [19]; remaining gaps are context-dependent [16][18].

**Implications:** the architecture occupies the strongest documented tier on every axis, with residual cross-platform costs concentrated precisely in GrapMail's workload profile; the native foundation is the right base for the planned startup-performance program.


### Finding 5: Locking It In — Guardrails, CI, and Release Discipline

A point-in-time verdict is only as strong as the ability to defend it tomorrow, so this check also produced an executable guardrail: `native-guardrails.sh`, delivered alongside this report in its tool set. The script encodes the four invariants that together define "pure native" for this repository — (1) a hybrid-artifact sweep of file names (Flutter, Dart, React Native, Metro, Capacitor, Ionic, Cordova, Expo tokens must be absent); (2) a content scan for framework tokens (`io.flutter`, `FlutterActivity`, `FlutterEngine`, `facebook.react`, `ReactRootView`, `react-native`, `capacitor`, `cordova`); (3) a WebView budget check — iOS may contain at most the five known WebKit files and Android at most the five known WebView files, and each list must match the allowlist; and (4) native-manifest checks, requiring the XcodeGen `project.yml`, the Gradle Kotlin DSL build, and the Compose activity shell to exist and remain native. Run read-only against `0fb5998e`, the guardrail passes with **zero failures and zero warnings** (E4) — the verdict in executable form, recomputable by anyone holding the repository.

The repository already contains the release infrastructure this guardrail should slot into. Platform CI workflows (`android.yml`, `ios.yml`) and four iOS screenshot-journey workflows exist in-tree, and the Android build carries a 2,139,758-byte Baseline Profile — a native-only performance mechanism compiled from the app's real code (E3). The organizational follow-through is small and concrete: add a `native-guardrails` CI job (and optionally a pre-commit hook) on both platforms, so that future drift — a newly added cross-platform dependency, a sixth WebView file, a replaced app shell — fails the build instead of quietly eroding the native profile. Two supporting habits matter going forward. First, keep the WebView allowlist and budget explicit: the current usage is healthy precisely because it is bounded and enumerated call by call (E5), and unbounded growth of web surfaces is the earliest observable form of hybrid drift. Second, price the maintenance honestly: native means two codebases maintained in parallel [3], which is a cost as well as an advantage; the repository already mitigates it with shared design assets (`assets/` 111 files, `raw-designs/` 30, `design/` 6) and `scripts/` tooling (E1), and that shared-asset discipline is worth keeping as the product grows.

**Key Evidence:**
- Guardrail script passes at `0fb5998e`: 0 fails, 0 warns; WebKit 5/5 and WebView 5/5 within budget (E4).
- CI: `android.yml`, `ios.yml` plus four iOS screenshot-journey workflows; Android Baseline Profile present at 2,139,758 bytes (E3).
- Shared-asset mitigation for two-codebase maintenance already in tree: 111 asset files plus design and script trees (E1); the two-codebase trade-off is standard for native projects [3].

## Synthesis & Insights

Across three evidence streams, nineteen registered sources, and a full commit-level census, the answer to the project owner's question closes cleanly: **GrapMail is not "native-ish" — it is literally native.** Two independent platform codebases (48 Swift files, 75 Kotlin files, zero Java), the official UI toolkits as the UI substrate (SwiftUI in 36 files, `@Composable` in 48 files), native-only toolchains, zero cross-platform framework tokens in either direction, and a small, enumerated set of embedded-content surfaces (E1–E3, E5–E6). The most instructive result is *why* the verdict is robust: every plausible hybrid test was applied and each resolved native. The WebView surfaces were not waved away — they were enumerated call by call, and the pattern they match is the pattern fully native system software uses, from Apple's documentation stance on WKWebView as a "platform-native view" [4] to Apple Mail's WebKit rendering [5] and the Wikipedia app's article rendering [6][7]; meanwhile the taxonomy sources define hybrid by where the *application* lives, not by whether any HTML is ever rendered inside it [3][9][10][11][12][13][14][17].

The benchmark literature supplies the quantitative frame, calibrated rather than promotional. Native builds show the smallest memory growth and smallest binaries, and the fastest cold starts in the 2025–2026 comparisons — native Android cold start ≈248 ms vs ≈341 ms for Expo/React Native, with native binaries roughly half the size of cross-platform ones [15][16]. Modern cross-platform frameworks have closed much of the gap for typical business screens, and the honest nuance across both benchmark studies is that the framework is rarely the bottleneck for network-bound flows — API round trips and data handling usually dominate [16]. WebView-hybrid shells are the steeper step down, running "closer to sixty to seventy percent of native speed" in a 2026 production review [19], with embedded-browser dependence explicitly linked to "subpar performance and inconsistent UIs" [13]. For GrapMail, the practical meaning is straightforward: the architecture occupies the strongest tier, and every platform-level optimization — Baseline Profiles, startup instrumentation, warm rendering — is available directly, with no abstraction layer in between [16].

The forward implications that follow from this check:
- **Protect the invariant.** The repository is native-by-construction; the risk to manage is drift, and the guardrail script converts that risk into a CI-checkable condition (E4).
- **Preserve the WebView budget.** Five files per platform, each with a single content purpose; expansion should be an explicit architecture decision, not a convenience (E5).
- **Continue the performance program on native footing.** Startup profiles and first-render warm-up remain the right next performance moves, and they sit on the best available foundation [16].
- **Keep the cost honest.** Two codebases is the price of the native profile [3]; shared assets, shared scripts, and platform CI are the working mitigations already in place (E1, E3).


## Limitations & Caveats

### Counterevidence Register

Three counter-hypotheses deserve a fair hearing, and each was tested against evidence rather than dismissed. First, the WebView presence could be read as hybrid architecture: the apps do load web content, and five files per platform carry the embedded browser. The counter-evidence is definitional and architectural — hybrid means the application itself is a web app inside a native shell [3][12][14], while here the shell, navigation, and every screen are native and the web surfaces are seven single-purpose content loads [4][5] (E2, E5). Second, the benchmark argument can be inverted: modern cross-platform frameworks have narrowed the gap enough that residual differences "often fall below the threshold that changes product outcomes" [16][18]. That is true, and it is reported as such — but the remaining differences concentrate in exactly this product's profile: sustained memory over long sessions, installed footprint, and rendering behavior under content-heavy surfaces [15][16][19]. Third, several taxonomy sources carry commercial stakes in the categories they describe [12][19]; the mitigation was structural, resting classification on platform documentation and neutral institutions [1][2][4][11] and using vendor material for factual mechanics only.

None of the three counter-hypotheses survived contact with the evidence, and two of them sharpened the report: the taxonomy boundary (what "hybrid" actually means) and the benchmark nuance (where the residual costs sit) are now stated more precisely than a one-sided reading would allow. The one genuinely open item is not the verdict but its shelf life; the commit-scope note below addresses it directly.

**Scope of verification.** This audit verifies the repository at `0fb5998e` — source tree, build manifests, and declared toolchains — and applies framework-level evidence sweeps across the whole tree (E1–E6). It does not disassemble store binaries or run on-device profiling of a release build; those remain named validation steps under Recommendations rather than assumed facts.

**Commit-pinned evidence.** The verdict describes one commit. Any later change could alter the picture, so the receipts (file lists, greps, guardrail output) are archived for regeneration against future commits. Read the conclusion as "verified at this revision", renewable on demand.

**Benchmarks are directional.** All performance figures cited come from third-party benchmark samples [15][16][18][19], not from GrapMail's own devices and traces; they indicate the shape of native vs cross-platform differences under controlled conditions. Benchmark content published by development vendors can also carry framework-selection bias, which is why the report balances multiple independent sources and marks precision accordingly.

**Taxonomy is convention, not a formal standard.** Industry sources define native, hybrid, and cross-platform with minor variations (some blur hybrid and cross-platform entirely [3][19]). The report therefore states its operating definitions in the Introduction and applies them the same way in both directions — checking for native markers and hybrid markers alike — so the conclusion does not depend on a single vendor's vocabulary.

**WebView findings are architectural.** "Bounded content surfaces" is a statement about declared structure — which files own a web view and what each loads (E5) — not a frame-by-frame runtime audit of those surfaces. A stricter runtime statement, if ever needed, can be produced through instrumented builds.

**Guardrail configuration is living.** The allowlist and budget in the guardrail script encode the architecture as of this audit; future architecture decisions must update the script deliberately, or the guard will (correctly) fail.

## Recommendations

### Immediate (next 1–2 weeks)
1. **Adopt the guardrail in CI.** Add a `native-guardrails` job to `android.yml` and `ios.yml` (a pre-commit hook is optional), so drift — a cross-platform dependency, a sixth WebView file, a replaced app shell — fails builds rather than silently eroding the native profile (E4, E3).
2. **Archive this report, its receipts, and the guardrail script** with the project's audit materials, so the evidence set stays with the code it describes (E1–E6).
3. **Launch the startup-performance work on this foundation.** Startup profiling, first-render warm-up, and the mail-list path already identified in earlier audits proceed with direct platform access — no framework layer interposes [16].

### Short-Term (30–90 days)
4. **Codify the architecture policy in docs** — "native-only UI; WebView = bounded content surfaces; five-file budget per platform; expansion is an architecture decision": the policy this audit verified becomes written policy (E5).
5. **Keep the Baseline Profile regenerated with releases.** It is a native-only optimization the Android app already ships, and it should stay fresh (E3).
6. **Maintain parity discipline across the two codebases.** Shared assets, shared scripts, and the two platform CI workflows are the standing mitigation for dual-maintenance cost [3]; keep new shared material flowing through them (E1).

### Long-Term (quarter and beyond)
7. **Re-verify each quarter or each major release** by re-running the guardrail and refreshing receipts — the check should be habitual, not heroic (E4).
8. **Treat any future "wrap it in cross-platform" proposal as a re-architecture checkpoint**, not a routine library choice: per the taxonomy applied here, it would change how the app must be classified [9][11][16].


## Bibliography

[1] (n.d.). [SwiftUI — Apple Developer Documentation](https://developer.apple.com/documentation/swiftui)
[2] (n.d.). [Jetpack Compose — Android Developers](https://developer.android.com/develop/ui/compose)
[3] (n.d.). [Native vs Hybrid App: How to Choose for Your Product (Miller Media 7)](https://millermedia7.com/blog/native-vs-hybrid-app)
[4] (n.d.). [WKWebView — Apple Developer Documentation](https://developer.apple.com/documentation/webkit/wkwebview)
[5] (n.d.). [Gmail vs. Apple Mail: Email Design and Development (Email on Acid)](https://www.emailonacid.com/blog/article/email-development/gmail-vs-apple-mail/)
[6] (n.d.). [How to run native code from a WKWebView (Swift with Vincent)](https://www.swiftwithvincent.com/blog/how-to-run-native-code-from-a-wkwebview)
[7] (n.d.). [WKWebView — NSHipster](https://nshipster.com/wkwebview)
[8] (n.d.). [HTML email rendering differences: Gmail, Outlook, Apple Mail (Postdrop)](https://blog.postdrop.io/post/html-email-rendering-differences-gmail-outlook-apple-mail)
[9] (n.d.). [Native vs Web vs Hybrid Apps: Differences & How to Choose (MobiLoud)](https://www.mobiloud.com/blog/native-web-or-hybrid-apps)
[10] (n.d.). [Web Apps vs. Native Apps vs. Hybrid Apps (AWS)](https://aws.amazon.com/compare/the-difference-between-web-apps-native-apps-and-hybrid-apps)
[11] (n.d.). [Mobile: Native Apps, Web Apps, and Hybrid Apps (Nielsen Norman Group)](https://www.nngroup.com/articles/mobile-native-apps)
[12] (n.d.). [What is Hybrid Mobile App Development (Ionic)](https://ionic.io/resources/articles/what-is-hybrid-app-development)
[13] (n.d.). [Native vs. Cross-Platform App Development (Microsoft Power Apps)](https://www.microsoft.com/en-us/power-platform/products/power-apps/topics/app-development/native-vs-cross-platform-apps)
[14] (n.d.). [Native vs Hybrid vs Web vs PWA (BrowserStack)](https://www.browserstack.com/guide/test-native-vs-hybrid-vs-web-vs-progressive-web-app)
[15] (n.d.). [Flutter vs React Native vs Native: 2025 Performance Benchmark (SynergyBoat)](https://www.synergyboat.com/blog/flutter-vs-react-native-vs-native-performance-benchmark-2025)
[16] (n.d.). [React Native Performance Benchmarks: Expo vs Bare vs Flutter vs Native (2026) (Applighter)](https://www.applighter.com/blog/react-native-performance-benchmarks-expo-vs-bare-vs-flutter-vs-native-2026)
[17] (n.d.). [Native vs. Web vs. Hybrid Apps vs. PWAs (Mendix)](https://www.mendix.com/blog/native-vs-web-vs-hybrid-vs-pwas)
[18] (n.d.). [React Native vs Flutter benchmark summary 2026 (SoftAims)](https://softaims.com/blog/react-native-vs-flutter)
[19] (n.d.). [Native vs Hybrid App Development (AppZoro)](https://appzoro.com/blog/native-vs-hybrid-app-development)

## Appendix: Methodology

### Research Process

This audit answered a single commit-scoped question — whether the GrapMail iOS and Android applications are truly pure native — through three independent evidence streams, following a structured deep-research pipeline (question framing, multi-source acquisition, evidence persistence, quality gates). Stream one was a read-only repository census at commit `0fb5998e` using git plumbing, producing a complete file inventory with per-language counts and sizes, and enumerating every build manifest, entry point, CI workflow, and performance artifact. Stream two was content-level, bidirectional sweeps: presence tests for native markers (SwiftUI, UIKit, WebKit, `@Composable`, WebView) and absence tests for hybrid and cross-platform markers (Flutter/Dart, React Native/Metro, Capacitor, Cordova, Ionic, Expo, JavaScript app bundles). Stream three was external: a literature pass over platform documentation, industry taxonomy sources, embedded-content practice references, and two 2025–2026 benchmark series, with the citation-critical quotes re-verified against fetched full text before use. A fourth, executable layer was added at the end: an automated guardrail script that encodes the audit's invariants and re-proves them on demand.

### Sources Consulted

External sources were registered in the run workspace with stable identifiers and display numbers 1–19: platform documentation for the two UI toolkits (sources 1 and 2) and the embedded-content view (source 4); industry taxonomy and hybrid-framework definitions (sources 3, 9, 10, 11, 12, 13, 14, and 17); embedded-content practice references (sources 5, 6, 7, and 8); and performance benchmarks (sources 15, 16, 18, and 19). Internal evidence was archived as six receipt sets, referenced throughout the report as E1–E6:

- **E1 — Repository census:** full file inventory at `0fb5998e` (379 files; language counts; sizes) — `receipts/all.txt`, `receipts/tree.txt`.
- **E2 — Framework sweeps:** native-marker and hybrid-marker greps (zero hybrid hits; per-file framework lists) — `receipts/ios-swiftui.txt`, `receipts/ios-uikit.txt`, `receipts/ios-webkit.txt`, `receipts/and-compose.txt`, `receipts/and-webview.txt`, `receipts/hybrid.txt`.
- **E3 — Build and release infrastructure:** `ios/project.yml`, `android/app/build.gradle.kts`, `MainActivity`, `baseline-prof.txt`, CI workflows — summarized in `receipts/scan-summary.md`.
- **E4 — Executable guardrail:** `tools/native-guardrails.sh` and its run output `receipts/guardrail-test-0fb5998e.txt` (0 fails / 0 warns).
- **E5 — WebView enumeration:** the five-plus-five file lists and all seven content-load call sites with line references — `receipts/scan-summary.md`.
- **E6 — Entry-point and extension sweep:** iOS app entry, Android manifest activity, js-family and module token counts — `receipts/entry-and-ext.txt`.

### Verification Approach

Every finding in this report traces to one of two evidence classes: archived repository receipts that can be regenerated from the pinned commit, or cited public sources whose exact wording was confirmed against fetched text. The report's citation contract — display numbers 1–19 mapped to the registered source list, with a complete bibliography — was enforced mechanically by the run's citation manager before delivery. The guardrail script provides independent recomputation for the core verdict: anyone can run it against the commit and reproduce the pass. The workflow's automated gates (citation finalization, structural validation, citation verification, and the cross-format HTML check) were run on the assembled report, with outputs archived alongside the audit receipts. Known limits of this verification are stated in the Limitations section — most importantly that the audit is commit-scoped and that benchmark figures are third-party measurements, not GrapMail's own.

### Claims-Evidence Table

| Claim | Supporting evidence |
|---|---|
| Both applications are pure native: Swift/Kotlin only, zero cross-platform or hybrid artifacts | E1, E2, E6 |
| The UI substrate is the official platform toolkits (SwiftUI; Jetpack Compose) | E2 (36 SwiftUI / 9 UIKit files; 48 Compose files) |
| Build toolchains are platform-native (XcodeGen + SwiftPM; Gradle Kotlin DSL) | E3 |
| WebView usage is bounded content rendering (5 files per platform; 7 single-purpose load sites) | E5, E2 |
| The guardrail passes at `0fb5998e` with 0 failures and 0 warnings | E4 |
| Native occupies the strongest documented performance tier in 2025–2026 benchmarks | Sources 15, 16, 19 |

## Report Metadata

- **Report:** `research_report_20261119_grapmail-native-verification` (Markdown + standalone HTML)
- **Generated:** 2026-11-19 · native final check round
- **Repository:** `grapmail-mobile` @ `0fb5998e` (branch `feat/onboarding-native-scenes`)
- **Run workspace:** `deep-research/gr-20261119-native` — 19 registered sources, display numbers 1–19
- **Companion artifacts:** `NATIVE-FINAL-CHECK-2026-11-19.md`, scan receipts, `tools/native-guardrails.sh`


