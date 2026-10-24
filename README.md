# GrapMail Mobile — v0.0.0.1

Native GrapMail apps for Android and iOS. The account & authentication sector
of the website, ported 1:1 to each platform's native stack — no cross-platform
framework, no shared UI layer.

- **Android** — Kotlin + Jetpack Compose (`android/`)
- **iOS** — Swift + SwiftUI (`ios/`)

## What v0.0.0.1 contains

The full signup arc plus sign-in, exactly as the founder's designs specify:

1. **Welcome** — "Better Email. A Brighter You."
2. **Sign in** — address or phone + password, real 2-step branch when the
   account has the factor on
3. **Step 1/5 Name** — first + last name with live validation
4. **Step 2/5 Basic Info** — optional photo, birthday (13+ enforced), gender chips
5. **Step 3/5 Address** — live-checked suggestions + custom handle against the
   real availability API
6. **Step 4/5 Password** — strength meter + requirement checklist
7. **Step 5/5 Terms** — scroll-gated agreement, then the real account creation
8. **Human verification** — GrapMail's own slide puzzle (Telegram removed by
   founder's direction). Skippable; the account works either way.
9. **Account created** — both designed variants (what's-next list, and the
   Go-to-Mailbox screen)

Everything hits the **real account API** at `https://grapsee.com/api` — the
same endpoints, token format (`st1`), and rules as the website. No mocks, no
stubs, no fake data anywhere.

## Sessions

- Android: token encrypted with an Android Keystore AES-GCM key before disk
- iOS: token in the Keychain
- Both send the `x-device-id` header the backend counts accounts against

## Building

### Android

```bash
cd android
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # needs signing config for install
```

Requires JDK 17 and an Android SDK with `platforms;android-34` +
`build-tools;34.0.0` (`ANDROID_HOME` set). The debug APK lands in
`android/app/build/outputs/apk/debug/`.

### iOS (requires a Mac with Xcode 15+ and XcodeGen)

```bash
brew install xcodegen
cd ios
xcodegen generate
open GrapMail.xcodeproj   # then Cmd+R on a simulator or device
```

## Design fidelity

`design/DESIGN_TOKENS.md` holds every measured color, radius, and motion
value — sampled pixel-by-pixel from the founder's PNGs (kept in
`raw-designs/`). The illustration assets in `assets/` are crops of the
originals, not redraws. Light and dark themes both match the paired designs.

## Repository layout

```
grapmail-mobile/
├── android/          Kotlin + Compose app
├── ios/              Swift + SwiftUI app (XcodeGen project)
├── assets/           Illustration crops from the founder's PNGs
├── raw-designs/      Per-screen reference renders (light/dark)
└── design/           DESIGN_TOKENS.md — the measured source of truth
```
