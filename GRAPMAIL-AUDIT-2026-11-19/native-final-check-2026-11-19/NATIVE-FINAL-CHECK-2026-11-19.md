# NATIVE-FINAL-CHECK — GrapMail iOS & Android

*2026-11-19 · native final check · repository `grapmail-mobile` @ `0fb5998e`*

> **Verdict: TRUE NATIVE / PURE NATIVE — both applications, verified against the commit, not assumed.**

## In one paragraph

At `0fb5998e` the mobile repository holds two independent platform codebases — 48 Swift files (1,347,235 bytes) under `ios/` and 75 Kotlin files (1,478,697 bytes) under `android/`, with zero Java files and zero traces of Flutter, React Native, Capacitor, Cordova, Expo, Dart, or JavaScript app code anywhere in the tree. The UI is SwiftUI (36 files) and Jetpack Compose (48 files); the build systems are XcodeGen + SwiftPM and Gradle Kotlin DSL; the app shells are a SwiftUI App entry and a Compose `ComponentActivity`. The only web-engine usage is bounded embedded content — five files per platform, seven single-purpose content loads (a sanitized mail body, a rich-text editor, onboarding scenes, a hero animation, finish-flow pages, and a human-verification page). That is the pattern fully native system software uses, not a hybrid shell.

## What was checked (evidence registry)

- **E1 — Repository census:** 379 files at `0fb5998e`; language counts and sizes; receipts `all.txt`, `tree.txt`.
- **E2 — Framework sweeps:** SwiftUI 36 / UIKit 9 / WebKit 5; `@Composable` 48 / WebView 5; hybrid token and artifact sweeps = 0 files; receipts `ios-*.txt`, `and-*.txt`, `hybrid.txt`.
- **E3 — Build & release infrastructure:** `ios/project.yml` (iOS 16, Swift 5.9, swift-crypto SPM), `android/app/build.gradle.kts` (minSdk 26, compileSdk 36), `MainActivity` (ComponentActivity + setContent), `baseline-prof.txt` (2,139,758 B), CI workflows; receipt `scan-summary.md`.
- **E4 — Executable guardrail:** `tools/native-guardrails.sh`, read-only run against `0fb5998e` → **0 fails / 0 warns**; receipt `guardrail-test-0fb5998e.txt`.
- **E5 — WebView enumeration:** the 5+5 file lists and all seven content-load call sites; receipt `scan-summary.md`.
- **E6 — Entry-point & extension sweep:** iOS `@main`/App struct, Android manifest activity, js-family files = 0, module tokens = 0; receipt `entry-and-ext.txt`.

## Facts at a glance

| Check | Result |
|---|---|
| Swift / Kotlin / Java files | 48 / 75 / 0 |
| SwiftUI / UIKit / WebKit files (iOS) | 36 / 9 / 5 |
| Compose / WebView files (Android) | 48 / 5 |
| Hybrid & cross-platform artifacts (filename + content) | 0 |
| Web content-load call sites | 7, each single-purpose |
| Guardrail run at `0fb5998e` | 0 fails / 0 warns |

## The WebView question

The only web surfaces are five files per platform. Apple's documentation describes WKWebView as a "platform-native view" for embedding web content "alongside your app's native views," and Apple's Mail client itself renders message HTML with WebKit — mail content is HTML by nature, so a content renderer is a requirement, not a hybrid tell. Hybrid, by contrast, is defined by the application living in the web layer (Ionic: "a native shell application that is just the platform's webview component in which it will load your web application") — which nothing in this repository does.

## Benchmark frame (2025–2026, third-party)

Native posts the smallest memory growth (iOS Δ9.74 MB vs Flutter 25.33 MB / React Native 45.13 MB; Android 6.33 MB vs 14.00 / 32.98 MB), the smallest binaries (0.457 MB Swift reference vs 20.2 MB React Native/Expo), and the fastest Android cold start (~248 ms vs ~341 ms Expo/RN). WebView hybrids run at 60–70% of native speed; compiled cross-platform at 85–95% of native. Full citations in the report.

## Gates & reproducibility

The full report passed the workflow's gates: structural validation PASSED, citation verification PASSED (19/19 URLs accessible, 0 suspicious), cross-format HTML check → `HTML_OK`. Archived outputs: `gates/`. To reproduce the verdict: run the archived guardrail script against the repository and compare with `guardrail-test-0fb5998e.txt`; re-run the repository sweeps listed in the report appendix.

## File index

- `report/research_report_20261119_grapmail-native-verification.md` — full report (Markdown)
- `report/research_report_20261119_grapmail-native-verification.html` — full report (standalone HTML)
- `receipts/` — E1–E6 receipts and gate outputs
- `tools/native-guardrails.sh` — the executable guardrail
- Also archived in the project's artifact store (`/mnt/cos/artifacts`).
