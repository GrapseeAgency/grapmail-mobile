# GrapMail Mobile — v0.0.0.1

Native GrapMail apps for Android and iOS. The account & authentication sector
of the website, ported 1:1 to each platform's native stack — no cross-platform
framework, no shared UI layer.

- **Android** — Kotlin + Jetpack Compose (`android/`)
- **iOS** — Swift + SwiftUI (`ios/`)

## What v0.0.0.1 contains

The full signup arc plus sign-in, exactly as the founder's designs specify:

1. **Welcome** — "Welcome to GrapMail" / "Your world. Your inbox."
2. **Sign in** — address or phone + password, real 2-step branch when the
   account has the factor on
3. **Step 1/5 Name** — first + last name with live validation
4. **Step 2/5 Basic Info** — optional photo, birthday (13+ enforced), gender chips
5. **Step 3/5 Address** — live-checked suggestions + custom handle against the
   real availability API, with the design's bordered @grapsee.com domain chip
6. **Step 4/5 Password** — strength meter + requirement checklist
7. **Step 5/5 Terms** — scroll-gated agreement, then the real account creation
8. **Human verification** — GrapMail's own slide puzzle (Telegram removed by
   founder's direction). Skippable; the account works either way.
9. **Account created** — both designed variants (what's-next list, and the
   Go-to-Mailbox screen)

## Recovery & security sector

The second design batch, built screen-for-screen on both platforms:

1. **Sign in** — the redesigned card, wordmark header and soft corner blobs
2. **Two-step verification** — six code cells, live expiry countdown, resend
3. **Backup code** — the alternative factor
4. **Forgot password** — identifier in, reset started
5. **Verify recovery code** — the six-digit code the reset texted
6. **Set a new password** — strength meter and the four rules
7. **Password reset successful**
8. **Account recovery pending** — the honest outcome when no code can be sent
9. **Find your GrapMail address** — phone in, masked address out
10. **Address found** — mask on screen, real address on the clipboard
11. **Add or verify your phone number** — step 1 of the phone ribbon
12. **Phone verified** — step 2, with the number linked to the account

Everything hits the same real API as the website: `/auth/account/reset/start`,
`/auth/account/reset/confirm`, `/auth/account/twofactor` (code **and** backup
code), `/auth/phone/start`, `/auth/phone/verify` and a `PATCH` on
`/auth/account/profile` that saves the proven number to the signed-in account.
`/auth/account/find` is the one endpoint this batch added — a rate-limited,
verified-number-only lookup behind the address screen.

### Where this batch deliberately leaves the artwork

- **Backup code cells.** The render draws six cells and says codes are eight
  characters. GrapMail issues ten ("ABCDE-FGHIJ"), so the row is two groups of
  five and the note says ten. A six-cell field could never accept a real code.
- **Two links the renders do not draw.** "Use a backup code" under Resend on the
  factor screen, and "Find your GrapMail address" on the forgot-password
  screen. Both screens otherwise exist with no way to reach them.

## Security sector — the 24-screen batch

Built screen-for-screen on both platforms, from `.local/refs-security/`. The hub
is the only entry point; everything below is reached from it.

1. **Account Security** (hub) — one screen for references 01 and 02, switching
   between "secure" and "one thing left to do" from the live profile
2. **Enable Two-Step Verification** — what the flow will ask for
3. **Confirm Your Password** — verified by `POST /auth/account/verify-password`,
   not compared in the client
4. **Verify Your Phone** — code sent to the number already on the account
5. **Two-Step Verification Enabled**
6. **Generate & Save Backup Codes**
7. **Backup Codes Saved** — the only render of the ten codes, copy-all included
8. **Disable Two-Step Verification?** — password required, as the server demands
9. **Two-Step Verification Disabled**
10. **Change Password** / **Change Your Password** (after suspicious activity)
11. **Password Changed** / **Password Updated Successfully**
12. **Sign Out All Devices** / **Signed Out of All Devices**
13. **Manage Active Sessions** — real device list with this-device marking
14. **Revoke Session Access?** / **Confirm Session Revocation**
15. **Session Revoked Successfully** (ordinary and suspicious variants)
16. **Recent Security Activity** — from events written when they happened
17. **Suspicious Sign-In Alert** — new-device sign-in, with time, device and IP
18. **Secure My Account** — password, sessions, second factor, in that order

### Endpoints this batch added

- `GET  /auth/account/sessions` — live sessions, current one flagged
- `DELETE /auth/account/sessions?id=` — revoke one device (409 for the caller's
  own session, so nobody signs themselves out mid-screen)
- `GET  /auth/account/activity` — audit trail, plus `suspicious` that clears once
  the sign-in has been closed by a revocation, password change or 2FA
- `POST /auth/account/verify-password` — proof for the confirm-password step
- `PATCH /auth/account/profile` — now also records `securityEvents` and stores
  each session at mint time (device label, IP, timestamps)

Verified: `:app:assembleDebug` builds; the endpoints were exercised against a
throwaway store (sign-in records a session, revoking one device leaves the other
signed in, wrong password 401, ten codes generated, current-session revoke 409).
**iOS has not been compiled** — there is no Xcode on this host; the Swift needs a
build on a Mac before it goes to a device.

Everything hits the **real account API** at `https://grapsee.com/api` — the
same endpoints, token format (`st1`), and rules as the website. No mocks, no
stubs, no fake data anywhere.

## Sessions

- Android: token encrypted with an Android Keystore AES-GCM key before disk
- iOS: token in the Keychain
- Both send the `x-device-id` header the backend counts accounts against
- Both restore a stored, unexpired session at launch (open signed-in)

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
originals, not redraws — artwork regions only, re-cropped by
`scripts/recrop-illustrations.py` (no baked-in text; screens render all
copy natively). Light and dark themes both match the paired designs.

## Repository layout

```
grapmail-mobile/
├── android/          Kotlin + Compose app
├── ios/              Swift + SwiftUI app (XcodeGen project)
├── assets/           Illustration crops from the founder's PNGs
├── raw-designs/      Per-screen reference renders (light/dark)
│   └── account/      the recovery/security batch
└── design/           DESIGN_TOKENS.md — the measured source of truth
```
