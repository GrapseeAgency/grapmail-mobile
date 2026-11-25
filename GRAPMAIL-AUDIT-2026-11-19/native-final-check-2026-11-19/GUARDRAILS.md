# Native Guardrails — Keeping GrapMail Pure-Native

*Companion to the 2026-11-19 native final check · ref `0fb5998e` · executable tool: `tools/native-guardrails.sh` (sha256 `3be3b74f20ba2aa8d0a36c5fdf52bce67698b0a83a56786d8e15139264679f73`)*

## What this is

GrapMail's iOS and Android apps are pure native: Swift/SwiftUI and Kotlin/Jetpack Compose codebases with no cross-platform or hybrid framework anywhere (verified 2026-11-19; see the final-check report). "Pure native" is an architectural property that can be eroded one dependency at a time, so this guide turns the property into an executable check plus a short discipline.

The check — `tools/native-guardrails.sh` — is a bash + git script that fails the moment a cross-platform framework artifact or token appears, or when a native build manifest goes missing. It needs no Xcode or Android toolchains and runs in seconds.

## What it checks (four invariant groups)

1. **Hybrid artifact sweep (file tree).** Fails if any of these appear: `pubspec.yaml` / `*.dart` (Flutter/Dart), `metro.config.*` (React Native), `capacitor.config*`, `ionic.config.json` (Capacitor/Ionic), `ios/Podfile` (CocoaPods hybrid flows), or `*.jsx` / `*.tsx` components.
2. **Hybrid framework tokens (sources).** Fails if any of `io.flutter`, `FlutterActivity`, `FlutterEngine`, `facebook.react`, `ReactRootView`, `react-native`, `Capacitor`, or `Cordova` appear under `ios/` or `android/`.
3. **WebView budget.** Counts WebKit files on iOS (`WKWebView` / `import WebKit`) and WebView files on Android (`android.webkit.WebView` / `WebView(`). Budget: **5 and 5**. At or under budget: informational. Over budget: WARN — the signal to review that the app shell stayed native (web surfaces must remain bounded content rendering; the app itself must never become a browser shell).
4. **Native toolchain manifests.** Requires `ios/project.yml` (XcodeGen), `android/app/build.gradle.kts` (Gradle Kotlin DSL), Swift sources, and Kotlin sources. Missing → FAIL. Java sources → WARN (still native; review — Kotlin is the project standard).

## How to run

```bash
# from the repository root
bash tools/native-guardrails.sh

# or point it at any checkout and a specific ref
bash tools/native-guardrails.sh /path/to/grapmail-mobile 0fb5998e
```

Usage: `bash native-guardrails.sh [repo-path] [ref]` — defaults: `.` and `HEAD`.
Exit codes: `0` PASS · `1` FAIL (guard violation) · `2` setup error (repo/ref not found).

Expected output on the verified commit (full receipt: `receipts/guardrail-test-0fb5998e.txt`):

```
PASS: no hybrid/cross-platform artifacts in the file tree
PASS: no hybrid framework tokens in ios/ + android/ sources
INFO: WebKit usage iOS=5 (budget 5) | WebView usage Android=5 (budget 5)
PASS: XcodeGen manifest present (ios/project.yml)
PASS: Gradle Kotlin DSL present (android/app/build.gradle.kts)
PASS: Swift sources present
PASS: Kotlin sources present
PASS: no Java sources
----
RESULT: fails=0 warns=0
NATIVE-GUARDRAIL: PASS
```

## CI integration (recommended, ~10 minutes)

1. Commit `tools/native-guardrails.sh` to the repository.
2. Copy the workflow from this bundle (`ci/native-guardrails.yml`) to `.github/workflows/native-guardrails.yml`.
3. Done — every push and pull request on the app branches now runs the guard, and each run uploads its receipt as a build artifact.

## Changing the budget — the one rule

The WebView budgets (5 + 5) and the token lists are part of the architecture decision record, not a nuisance setting. If a legitimate new content surface is added, update the budget **in the same pull request** that adds the surface — and say why in the PR description. Never widen the budget silently; the guard exists to make drift visible, not to block deliberate evolution.

## Release discipline (short version)

- Run the guard before every release tag; attach the receipt to the release notes.
- Re-run the quarterly native check (guard + receipts refresh) — see the report's Recommendations.
- Keep the Android Baseline Profile regenerated with releases (native-only optimization; already in-tree).
- Treat any "wrap it in cross-platform" proposal as a re-architecture checkpoint, not a routine library choice.
