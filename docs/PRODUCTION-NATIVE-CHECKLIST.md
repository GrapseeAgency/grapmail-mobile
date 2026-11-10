# PRODUCTION-NATIVE-CHECKLIST — what a store-ready native build requires, mapped to this repo

Researched 2026-11-10/11 from official Google Play and Apple sources for the
GrapMail launch. This is the standing reference for "what production means"
for BOTH native apps. Items marked ✅ are in this repo today; ⬜ items are
open with their owner in brackets.

═══════════════════════════════════════════════════════════════
ANDROID — Google Play
═══════════════════════════════════════════════════════════════

Build & signing
- ✅ targetSdk / compileSdk 36 (Play's bar in effect since Aug 31, 2026)
- ✅ R8 minify + resource shrinking, non-debuggable release, proguard rules
- ✅ AAB produced (`bundleRelease`), Play's required upload format
- ✅ Founder distribution key consumed from CI secrets
  (CI_KEYSTORE_FILE/PASSWORD/ALIAS), with `keytool -list` validation before
  use and honest "test-signed" labelling when absent
- ✅ Play App Signing: enroll on first upload — Google holds the app signing
  key; the upload key must never change [founder, at Play Console setup]
- ⬜ 16 KB page sizes: this tree is Kotlin-only (no native .so) — compliant
  by construction; re-verify with APK Analyzer if a native SDK is ever added
- ⬜ Edge-to-edge: targetSdk 36 enforces it — visual pass on Android 15/16
  devices before the first release build [agent, one emulator walk]

Permissions
- ✅ INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS only
- ⬜ Never add: SMS/Call Log group, AccessibilityService, QUERY_ALL_PACKAGES,
  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — each is a near-automatic rejection
  for a mail app. OTP reads use the SMS Retriever pattern if ever needed.
- ✅ Contact autocomplete does NOT use READ_CONTACTS (uses typed/derived
  contacts) — keep it that way; the 2026 Contacts Permissions policy pushes
  the picker and requires a declaration for broad access

Policy must-dos for an app with account creation
- ✅ In-app account deletion (proof-gated) + web deletion page
  (https://grapsee.com/account/delete) — the Play Data safety "account
  deletion" URL must point at the web page and it must actually enable
  deletion requests
- ✅ Data safety form source of truth: DATA-FLOW.md (+ docs/DATA-SAFETY-DRAFT.md)
- ⬜ Financial features declaration = "none" (mandatory answer even when none)
  [founder, App content]
- ⬜ IARC content rating questionnaire (UGC = yes; moderation = spam/screener)
  [founder, App content]
- ⬜ Target audience: 13+ — never tick any under-13 group [founder]
- ⬜ App access: reviewer demo credentials [founder, from a production account]

Release process
- ✅ Internal → Closed track pipeline; the 12-tester/14-day opt-in clock only
  applies to personal accounts created after Nov 13, 2023 (this founder's is —
  recruit 15–20, keep ≥12 opted-in continuously)
- ✅ Pre-launch report runs automatically on every upload — read it before
  promoting
- ⬜ First release ships at 100% (staged rollout applies to updates only)
- ✅ One run per ref in CI; releases are tag-gated (v*)

Branding
- ✅ Adaptive icon: full-bleed logo foreground at all 5 densities, background
  color, themed monochrome envelope glyph (Android 13+), roundIcon declared
- ✅ Store listing icon source: design/grapmail-store-icon-512.png
- ✅ Splash screen (core-splashscreen) with brand background

Engineering production bar (standing)
- ✅ Zero TODO/mock/stub surfaces; honesty pins (mail-honesty.py) + API parity
  gate in CI
- ✅ Tokens: Android Keystore-backed AES-GCM before DataStore; single-flight
  refresh rotation on 401
- ✅ Push: FCM with server-driven dispatch + polling fallback; high-priority
  FCM reserved for genuinely visible new-mail notifications
- ⬜ Baseline profiles (startup polish) — future, not a launch blocker
- ⬜ Localization beyond English — post-launch

═══════════════════════════════════════════════════════════════
iOS — App Store (deferred until the Apple Developer account exists)
═══════════════════════════════════════════════════════════════

- ✅ SwiftUI app, iOS 16+, XcodeGen project, single 1024 AppIcon (full-bleed,
  no alpha — Apple's requirement), PrivacyInfo.xcprivacy complete
- ✅ BGTaskSchedulerPermittedIdentifiers registered in Info.plist (the silent
  death defect is fixed)
- ✅ Push entitlement wired (aps-environment) + APNs token registration
- ⬜ Apple Developer Program ($99/yr; organization needs D-U-N-S) [founder]
- ⬜ DEVELOPMENT_TEAM + certificates in CI; the archive/export/TestFlight job
  already exists in ios.yml and refuses loudly without secrets [founder]
- ⬜ First real compile on a Mac or CI mac runner — the Swift has only ever
  run on CI simulators [agent, after Apple account]
- ⬜ Guideline 5.1.1(v): in-app account deletion (already built — mirror the
  Android flow into the iOS screens)
- ⬜ Privacy nutrition labels filled from DATA-FLOW.md [founder, App Store Connect]
- ⬜ Builds must use the current-year Xcode/iOS SDK Apple requires (check the
  Upcoming Requirements page before the first submit)
- ⬜ TestFlight: internal ≤100 testers free of Beta review; external ≤10,000
  requires Beta App Review; builds expire after 90 days

═══════════════════════════════════════════════════════════════
BOTH PLATFORMS — the reliability bar users actually judge (2026 research)
═══════════════════════════════════════════════════════════════
The complaint data across email apps is unambiguous. The Tier-A killers:
search that misses mail (16% of negative reviews), broken/delayed push (11%),
sync gaps (14%), crashes (22%, esp. attachments), no offline (switch trigger),
battery drain from polling. Tier-B (AI summaries, smart replies, categories)
is the marketing layer; Tier-C (deep Google-ecosystem integrations) is
deliberately not copied. GrapMail's current parity: compose/reply/forward,
attachments up+down, labels, threads, archive/trash/restore, spam, subscriptions
hub, E2EE, screener, dark mode ✅ — with push (now wired), sanitized HTML
(shipped), scheduled send (shipped), draft autosave (shipped).

Sources: support.google.com/googleplay/android-developer (11926878, 14151465,
13327111, 10787469, 13849271, 9867159, 16558241, 16926792), developer.android.com
(page-sizes, behavior-changes-15), developer.apple.com (programs, testflight,
app-store/review/guidelines, privacy-manifest-files), blog.google (Gmail Gemini
era), marcoapp.io email review analysis.
