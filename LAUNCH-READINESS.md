# GrapMail — launch readiness (App Store + Google Play)

Written 25 Nov 2026. This is the founder's checklist: what is already done in the
repository, what has actually been verified, what only you can do, and what is
still known to be unfinished. It is deliberately blunt — a store build cannot be
recalled.

---

## 1. Where the build stands

| | Android | iOS |
|---|---|---|
| Version | **0.0.1** (`versionCode` 1) | **0.0.1** (build 1) |
| Min OS | Android 8.0 (API 26) | iOS 16.0 (widget/Live Activity 16.1) |
| Target | API 36 | Xcode 16 / iOS 18 SDK |
| Package / bundle | `com.grapsee.grapmail` | `com.grapsee.grapmail` |
| Release tag | `v0.0.1` | `v0.0.1` |

Both platforms share the first public version and one tag. Earlier 0.2.x–0.7.x
identifiers belonged to internal/sideload builds, not the public store line.
A device carrying a higher sideloaded versionCode must uninstall that test build
before installing production 0.0.1; the public line intentionally starts at 1.

## 2. What was verified on this machine (25 Nov)

| Check | Command | Result |
|---|---|---|
| Android debug build | `gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** |
| Android JVM unit tests | `gradlew :app:testDebugUnitTest` | **238 tests, 0 failed, 0 skipped** |
| Android release build (R8 + lintVital) | `gradlew :app:assembleRelease` | **BUILD SUCCESSFUL**, `app-release-unsigned.apk` (9.9 MB) |
| Backend typecheck | `tsc -p services/shared --noEmit` | 22 errors, **all pre-existing** (mail-service typings, missing `imapflow` types, one test tsconfig); **0 in the code this round touched** |
| Backend tests | `node --import tsx --test src/__tests__/*.node-test.ts` | 780 tests: 714 pass, 26 fail, 29 cancelled, 11 skipped. Every failure is environment-shaped (no `DATABASE_URL`/`JWT_SECRET`, no Postgres, two PGlite suites that time out only when 75 files run in parallel — both pass alone: 26/26) |
| New security suites in isolation | `account-security-state` + `security-center` | **26/26 pass** |
| Web tests | `node --import tsx --test "src/app/**/__tests__/*.test.ts"` | 565 tests: 548 pass, 13 skipped, 2 todo, **1 environment failure** (`resolvePublicAddresses` needs DNS in the sandbox) |
| Web typecheck | `tsc --noEmit` | clean for the files changed this round (the reader's case-colliding module was renamed) |
| API parity | `python scripts/api-parity.py` | **passes**: 42/42 mail calls, 52 DTOs, 236 shared fields, 10 profile types |
| Honesty gate | `python scripts/mail-honesty.py` | **646 pins hold, exit 0** |

**Not compiled: iOS.** There is no macOS/Xcode on this machine, so the Swift
changes are reviewed, not built. Expect the first `ios.yml` run to surface
compile errors; that is the honest state, not a claim of green.

### Bugs fixed during this round

- `android/app/build.gradle.kts` did not compile at all: `java.time.LocalDate`
  inside the `android {}` block resolves to the Java plugin extension, not the
  JDK package (`Unresolved reference: time`). Fixed with top-level imports.
- Play Billing 8 call sites used `billing-ktx` coroutine extensions that the
  `billing` artifact does not ship: `queryProductDetails` / `queryPurchasesAsync`
  now wrap the callback API, and the purchases callback shape is the 8.0 one
  (`List<Purchase>`, not `PurchasesResult`).
- Credential Manager 1.3.0 has no `response.credential`; the registration JSON
  lives on `CreatePublicKeyCredentialResponse.registrationResponseJson`.
- `DeviceKeyManager` asked `KeyFactory` for a `KeyGenParameterSpec` (not a
  `KeySpec`); it now reads `KeyInfo`, which is the readable spec and carries
  `isUserAuthenticationRequired`.
- `KeyPairGenerator` output failed the X25519 check on the JVM because the JDK
  names the key family `XDH`; both names are accepted now (Android still
  reports `X25519`).
- Assorted Kotlin type errors: a `suspendCancellableCoroutine` with an
  uninferrable element type, four smart casts on a delegated `fatal` property,
  and a `maxWidth` read through the wrong receiver.
- Web: the reader's pure helper and its React component both resolved to
  `quotedText.ts` on a case-insensitive filesystem; the helper is now
  `quotedTextSplit.ts`. `pdf.js` 5 dropped `isEvalSupported`.
- One attachment contract test asserted against `page.tsx` for code that moved
  into `AttachmentGrid.tsx`; it now asserts against the component that
  implements it (blob URL, revocation, no SVG), so the guarantee is unchanged.

---

## 3. What only you can do

### 3.1 Apple

1. **Apple Team ID** (10 characters, developer.apple.com → Membership details).
   Two places need it:
   - `public/.well-known/apple-app-site-association` — replace **`TEAMID`** in
     both `appIDs` and `webcredentials.apps`, then deploy the site. Universal
     Links and passkeys stay dead until this is live; `ios.yml` warns on every
     run while the placeholder is there.
   - GitHub secret **`APPLE_TEAM_ID`** (consumed by `ios.yml`).
2. GitHub secrets for the archive/upload job:
   `APPLE_API_KEY_ID`, `APPLE_API_ISSUER_ID`, `APPLE_API_KEY_P8`,
   `BUILD_CERTIFICATE_P12`, `BUILD_CERTIFICATE_PASSWORD`,
   `APPSTORE_PROVISIONING_PROFILE`.
3. Enable **Associated Domains** on the App ID `com.grapsee.grapmail` (signing
   refuses the entitlement otherwise) and **Push Notifications** +
   **Communication Notifications** capability.
4. In App Store Connect: create the app record, set the privacy answers from
   `PrivacyInfo.xcprivacy`, and answer export compliance (the app declares
   `ITSAppUsesNonExemptEncryption = true` because of end-to-end encryption).

### 3.2 Google Play

1. **Play App Signing SHA-256 fingerprint** — the value currently in
   `public/.well-known/assetlinks.json`
   (`34:40:05:…:7B:0F`) must be the *Play App Signing* certificate, not your
   upload key. If it is the upload certificate, App Links verification fails
   for every Play-installed build. Replace it and redeploy the site.
2. Upload keystore as GitHub secrets: `CI_KEYSTORE_FILE` (base64 of the .jks),
   `CI_KEYSTORE_PASSWORD`, `CI_KEYSTORE_ALIAS`.
3. `PLAY_SERVICE_ACCOUNT_JSON` for `play-rollout.yml`, and (optional)
   `vars.PLAY_AUTO_TRACK`, `vars.PLAY_SOURCE_TRACK`.
4. Play Console: create the app, complete **Data safety** and **Content
   rating**, upload the first AAB to **internal testing**, add testers, and
   confirm the *in-app account deletion* answer (the app has
   `POST /auth/account/delete` with a password proof).

### 3.3 Server (before anyone can use the new features)

1. Run migrations **0051**, **0052**, **0053**:
   `0051_password_change_required.sql`, `0052_security_center_contract.sql`,
   `0053_session_device_id.sql`. The app degrades without them (device
   recognition falls back to the pre-0053 check), but "This wasn't me" and the
   security score need 0052.
2. Turn on the two server flags **after** you have tested the screens:
   `welcome_back` and `security_center`. They are ON in debug builds and OFF in
   release on both apps; users see the old screens until the flags are set.
3. Cloudflare: if you want city-level locations in the device list (today it
   shows the country only), enable `cf-ipcity`/`cf-region` passthrough and add
   the columns the backend expects.

---

## 4. Store-compliance state (already in the repo)

| Requirement | Android | iOS |
|---|---|---|
| In-app account deletion | ✅ Settings → Security → Delete account | ✅ same, with password proof |
| Privacy manifest | n/a | ✅ app + widget + notification extension, 14 collected types, 3 required-reason APIs |
| Encryption declaration | n/a | ✅ `ITSAppUsesNonExemptEncryption = true` |
| Permission strings | ✅ `POST_NOTIFICATIONS`, no storage/contacts permissions | ✅ Face ID, photo-library add-only; no camera/mic (`PhotosPicker` needs none) |
| Edge-to-edge (Android 15+) | ✅ `enableEdgeToEdge()` in `MainActivity` | n/a |
| Predictive back | ✅ `android:enableOnBackInvokedCallback="true"` | n/a |
| Backup excludes secrets | ✅ `allowBackup=false`, `dataExtractionRules`, `fullBackupContent=false` | keychain-backed, no plaintext token in the app group |
| App Links / Universal Links | ✅ `autoVerify` filter + `assetlinks.json` (fingerprint must be Play's) | ✅ associated domains + AASA (needs the Team ID) |
| 20 languages | ✅ 21 locale dirs, `locales_config.xml` | ✅ 21 `.lproj`, `CFBundleLocalizations` |
| 64-bit | ✅ no ABI filters → arm64-v8a included | ✅ arm64 |
| Notifications (Android 13+) | ✅ runtime permission + channels | ✅ APNs + communication notifications |

---

## 5. How to ship (order)

1. Push to GitHub. CI builds Android (debug + tests + signed release) and, on a
   macOS runner, archives iOS.
2. Create tag **`v0.0.1`**. `android.yml` builds the signed APK/AAB and attaches
   them to the GitHub Release; `ios.yml` archives, verifies the version against
   the tag, and uploads to App Store Connect (TestFlight).
3. Play: `play-rollout.yml` uploads the AAB to **internal** automatically after
   the tag build; then `action=promote` to closed/production with a staged
   rollout (5 → 10 → 20 → 50 → 100 %, gated on the crash-free check).
4. App Store: `appstore-release.yml` → `testflight`, then `submit` (attaches the
   build, turns on phased release, submits for review).
5. Watch the crash-free gate for 24 h before each widening step. To stop a
   release without a new build: `phased-pause` / `halt`, or the server kill
   switches (`grapmail-abuse-gate` env, `send_paused`, `signup_paused`).

---

## 6. Known gaps — read before you promise a date

1. **iOS has never been compiled.** Every Swift change in the last two rounds is
   static-reviewed only.
2. **Security center and welcome-back text is English-only in 20 languages.**
   Both features are behind server flags that are off in release; the pipeline
   to translate them is in place (`translations/source.json` + generator) and
   the 154 iOS `secL` keys need a `SecurityCenter.strings` table per locale.
   Do not enable those flags for non-English users until that is done.
3. **The other 61 live strings are translated** into all 20 locales
   (compose formatting bar, reader folds, plan prices, appearance setting).
4. **Four new-device alerts**: accounts with two-step verification now get alerts
   for sign-ins from a new device; the copy and fan-out are covered by
   `security-center.node-test.ts`. A device that never reported a
   `x-device-id` still cannot be named beyond its user agent.
5. **Widget buttons are queued**, not delivered instantly: the widget cannot
   reach the session safely, so taps are applied locally and sent on next open.
6. **Quick reply opens the full composer** (by design) so every send still goes
   through the encryption check and undo.
7. **Foldables**: layouts adapt to window size, but there is no hinge-aware
   layout (no `androidx.window` dependency).
8. **Screenshots**: `screenshots-release/` has 6 images (onboarding 1–5 and a
   launch card). App Store needs 6.7"/6.5" and Play needs phone + 7"/10" tablet
   shots; capture them from the real build before submitting.

### 6.9 The honesty gate is green — and what the re-pin actually changed

`mail-honesty.py` runs inside the **release** job of `android.yml` (and the
equivalent step in `ios.yml`), so a red gate meant a tag build published nothing.
It was red in 46 places when this round started, because the round-8/9 redesign
moved copy into resources and replaced whole components while the pins still
described the old shapes.

All 46 are now resolved, **646 pins hold**. Every change is a pin that now reads
the *new* implementation while asserting the same promise — none was deleted or
weakened to pass:

| Area | What the pin now asserts |
|---|---|
| Copy that moved into resources | Pins read `res/values/*.xml` (and `en.lproj/*.strings`) instead of the Swift/Kotlin literal, so a sentence still has to *ship* |
| Avatar ramp | The 10-hue palette from `round8-spec.md` A1.6, matched value-for-value across Android and iOS (was 8 hues) |
| iOS button heights | A 54pt floor that may grow with Dynamic Type; the forbidden thing is the unbounded `maxHeight: .infinity` that once inflated a button to 700pt |
| Inbox structure | Category chips *and* folder tabs both exist — categories complement, never replace |
| Reader reply footer | The new `ReaderReplyButton(label, icon, weight)` signature; iOS's third action is a labelled `Button("Reply all")` |
| Shortcut | The pending-flag mechanism (`shortcuts.pendingCompose`) that consumes the request only after a renewal and a valid session |
| Composer sender | A sender *picker* is forbidden; `aliasesState` is allowed in the reader, where it marks the account's own messages |
| Swipe directions | User-chosen, but the box still decides: Trash/Spam keep one gesture, Archive never offers Archive, a selection suspends both |
| Privacy manifest | Three required-reason APIs (UserDefaults, FileTimestamp, SystemBootTime), each with Apple's own code |
| Compose body | The 240 floor on both iOS editor branches; Android's editor fills the remaining height with a 176dp short-window form |

Two of the old pins were also factually wrong rather than merely stale, and were
corrected: the label-filter pin read a phrase that appears in the file's own
correction comment, and the step-target pin forbade `isThread: true` everywhere,
including the deep-link arm where a `.thread` link legitimately declares its own
type.

## 7. Rollback

- Server flags: `welcome_back`, `security_center` — turn off and both apps fall
  back to the old screens on the next launch.
- Kill switches: `signup_paused`, `send_paused`, plus the `grapmail-abuse-gate`
  env. They answer 503 with a code the apps already render as a banner.
- App binaries: Play `halt` stops a staged rollout; App Store `phased-pause`
  stops automatic updates. Neither can pull an installed build.
