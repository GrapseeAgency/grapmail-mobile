# Native-Stack Verification Scan — grapmail-mobile @ 0fb5998e — 2026-11-19

Method: read-only git-level census and content greps performed inside an isolated sandbox against the mobile repository (branch `feat/onboarding-native-scenes`), commit under test:

- **0fb5998e0689faf73089d7bf8e19fc39573aa8f0** — "audit 2026-11-19: fixpack applied state + iOS Scheduled wiring + attachment thumb" (2026-11-19 04:23:25 +0000)

## 1. File census (379 files at HEAD)

By top-level area: `android/` 134 · `assets/` 111 (design/QA material) · `ios/` 67 · `raw-designs/` 30 · `scripts/` 11 · `design/` 6 · `.github/` 6 · `Onboarding_Scenes/` 5 · `docs/` 2 · repo root files.

By language (source code under the two app trees):
- **Swift: 48 files, 1,347,235 bytes** (all under `ios/`)
- **Kotlin: 75 files, 1,478,697 bytes** (all under `android/`)
- **Java: 0 files**
- Other extensions at HEAD include design PNGs (169), HTML content assets (19), XML (12), scripts (py/sh), build files (kts/yml), and repo docs.

## 2. Hybrid-framework sweep (the core test)

- Filename sweep for `pubspec.yaml`, `*.dart`, `flutter`, `react-native`, `metro.config`, `capacitor`, `ionic`, `cordova`, `expo`, `node_modules`, `*.jsx`, `*.tsx` → **0 hits** (379 files scanned).
- Content grep for `io.flutter`, `FlutterActivity`, `FlutterEngine`, `facebook.react`, `ReactRootView`, `react-native`, `capacitor`, `cordova` across the whole tree at the commit → **0 files**.
- No `Podfile`/CocoaPods references; iOS dependencies are declared in `ios/project.yml` (XcodeGen) with one Swift Package (`apple/swift-crypto`).
- No JS/TS sources under `ios/` or `android/` app trees.

## 3. Framework usage (content-level counts)

- iOS: `import SwiftUI` in **36 files**; `import UIKit` in **9**; WebKit in **5** (`ComposeFormattingEditor.swift`, `FinishScreens.swift`, `MailHtmlBody.swift`, `OnboardingViews.swift`, `WelcomeHeroView.swift`).
- Android: `@Composable` in **48 files**; WebView in **5** (`WelcomeHero.kt`, `HumanVerifyScreen.kt`, `OnboardingScreen.kt`, `ComposeFormattingEditor.kt`, `MailHtmlBody.kt`).

## 4. WebView surface — fully enumerated (7 load calls)

| Platform | File | Call | Purpose |
|---|---|---|---|
| Android | HumanVerifyScreen.kt:119 | `loadUrl(HUMAN_VERIFY_URL)` | human-verification web page |
| iOS | ComposeFormattingEditor.swift:20 | `loadHTMLString(document)` | rich-text compose editor document |
| iOS | FinishScreens.swift:223, 230 | `load(URLRequest)` | finish/paywall web pages |
| iOS | MailHtmlBody.swift:63 | `loadHTMLString(mailDocument)` | sanitized mail body render |
| iOS | OnboardingViews.swift:193 | `loadHTMLString(html)` | onboarding scene animations |
| iOS | WelcomeHeroView.swift:47 | `loadFileURL(url)` | hero animation asset |

Android carries the mirrored set (compose editor, onboarding, hero, mail body). App **navigation and screens are native**; WebView instances are embedded content surfaces only.

HTML content assets: 19 `.html` files at HEAD — among them, per-platform: `GrapMailComposeEditor.html`, `GrapMail_Autoplay_Mailbox.html`, `onboarding_scene_1..5.html` (both `ios/` and `android/` trees).

## 5. Build systems

- **iOS**: `ios/project.yml` (XcodeGen) — app target `GrapMail` (iOS 16.0 deployment, Swift 5.9, MARKETING_VERSION 0.6.4) plus `GrapMailNotificationService` extension; dependency: `apple/swift-crypto` (SPM). No CocoaPods.
- **Android**: Gradle Kotlin DSL — plugins `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`; namespace `com.grapsee.grapmail`; minSdk 26 / targetSdk 36 / compileSdk 36.
- **Android shell**: `MainActivity.kt` = `ComponentActivity` + `setContent { ... }` — pure Jetpack Compose application shell.

## 6. Verdict

**PURE NATIVE — two independent platform codebases.** Swift/SwiftUI (+UIKit where platform-appropriate) on iOS; Kotlin/Jetpack Compose on Android. No cross-platform or hybrid framework (Flutter, React Native, Capacitor, Cordova, Expo, WebView-shell) is present in file layout, build configuration, or source content. WebView usage is a bounded, enumerated technique for embedded HTML content surfaces — the same architecture used by fully native system apps (e.g., Apple Mail renders mail via WebKit) — and does not constitute a hybrid app shell.

Evidence files in this folder: `all.txt` (git ls-files), `tree.txt` (git ls-tree -r -l HEAD), `ios-swiftui.txt`, `ios-uikit.txt`, `ios-webkit.txt`, `and-compose.txt`, `and-webview.txt`, `hybrid.txt`.
