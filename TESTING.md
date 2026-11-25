# Testing GrapMail mobile

Two layers, kept deliberately separate:

| Layer | What it proves | Where | Runs in CI as |
|---|---|---|---|
| **Unit tests** (this document) | The pure logic does what its comments say: validators, notification rules, the Outbox retry table, idempotency keys, remote-config parsing, the on-device intelligence and trust engines, the API client's request/response shapes, and **byte-compatibility of end-to-end encryption** with Android, iOS and the server. | `android/app/src/test/…`, `ios/GrapMailTests/` | `unit-tests` job in `android.yml` and `ios.yml` (minutes, every push / PR / tag) |
| Screenshot walks | The real app, on emulators and simulators, reaches every screen. | `scripts/android-walk.sh`, `ios/GrapMailUITests/` | `build-test-screenshot` jobs (long) |

The release jobs in both workflows have `needs: unit-tests`: **nothing is signed, archived or uploaded unless the unit suite is green on that commit.**

## What is covered

### Android — JVM unit tests (no emulator)

| File | Tests | Covers |
|---|---:|---|
| `logic/ValidatorsTest.kt` | 17 | names, birthday (leap days, roll-over refused, 13+ boundary, future), handle alphabet / candidates, the server's four password rules with ASCII classes |
| `logic/NotificationPolicyTest.kt` | 13 | `decide()`: security mail always alerts, muted beats VIP/watched, `none`/`priority` modes, VIP address/domain/subdomain, quiet hours across midnight in the saved zone, inclusive start / exclusive end, UTC fallback, malformed windows |
| `logic/RemoteConfigTest.kt` | 5 | `AppConfigDto` parsing (unknown keys, nulls), `isPaused`, `flag` defaults, `updateRequired` vs `BuildConfig.VERSION_CODE` |
| `logic/OutboxRetryPolicyTest.kt` | 8 | no connection / 5xx / 408 / 429 → retry; other 4xx → give up; accepted states; 3-day expiry; the stored `Idempotency-Key` survives attempts and the on-disk JSON |
| `data/SendKeysTest.kt` | 7 | same message ⇒ same key inside 10 min (inclusive), settle ⇒ new key, expiry, key format |
| `data/ApiTest.kt` | 17 | MockWebServer: bearer + `x-device-id` + app User-Agent, no DPoP on non-token routes, inbox `q` only from 3 chars, sparse PATCH bodies, notification settings sent whole, error envelope → `ApiException` (app wording vs server-worded codes, `error` key, non-JSON 5xx), `success:false` listing is an error, authenticated 401 signals `SessionEvents` once / anonymous 401 does not, no connection → `IOException`, undecodable 200, Outbox key reused across a 5xx retry, direct-send key settled after 2xx/4xx |
| `data/MailE2eTest.kt` | 29 | fixed cross-platform vectors (v1, v2 with our wrap second, fingerprint, PBKDF2 over a UTF-8 + emoji passphrase, recovery kit), RFC 8410 key encodings, signatures (unexpected key, tampered, unsigned), v1/v2 round trips, recipient reorder/drop breaks the body, tamper, refusals, envelope parsing, wire field order, passphrase records (AAD binding, UTF-16 length rule, change passphrase, JCA PBKDF2 cross-check), recovery keys, a real 600k-round recovery kit |
| `features/MailIntelligenceTest.kt` | 36 | categorizer, thread summaries, smart replies (incl. voice), compose tones / proofread / rewrite / draft, templates, natural-language search |
| `features/MailTrustTest.kt` | 28 | URL/QR verdicts (official, http, IP, `user@host`, punycode, shorteners, brand look-alikes in subdomains and on scam TLDs, ccSLDs), tracker shield (1px by attribute or CSS, tracker hosts, removal, local images), action cards (UPS/FedEx/DHL/USPS, flights, calendar dates, dedupe) |
| `features/KnownBugsTest.kt` | 8 | **expected to fail** — see below |
| `data/MailTimestampTest.kt` | 3 | (pre-existing) |

### iOS — XCTest `GrapMailTests` target (hosted in the app, `@testable import GrapMail`)

| File | Tests | Covers |
|---|---:|---|
| `ValidatorsTests.swift` | 11 | as Android (same handle candidates for the same name) |
| `NotificationPolicyTests.swift` | 11 | as Android, plus lenient decoding / round-trip of `NotificationSettingsInfo` |
| `RemoteConfigSendKeysOutboxTests.swift` | 15 | `RemoteConfig.compare` (numeric, uneven lengths, antisymmetry), `SendKeys`, `Outbox` failure table + `OutboxItem` round-trip |
| `MailE2eTests.swift` | 27 | **the same vectors as Android**, plus restore-from-recovery-kit, round trips, tamper and refusal cases |
| `MailIntelligenceTests.swift` | 23 | iOS categorizer, summaries, smart replies, natural search, rewrite/templates |
| `MailTrustTests.swift` | 21 | iOS link scoring, tracker counting, action cards |
| `KnownBugsTests.swift` | 5 | **expected to fail** — see below |

The iOS and Android intelligence / trust engines are **different implementations** (different buckets, scores and wording), so those suites mirror the same areas with each platform's own rules. Validators, NotificationPolicy, SendKeys, the Outbox table and MailE2e are meant to be identical and are tested as such.

### End-to-end encryption vectors

`testvectors/e2e-vectors.json` is the contract: a v1 envelope, a v2 envelope (two recipients), a passphrase-protected key and a recovery kit, generated by `testvectors/gen_e2e_vectors.py` from fixed seeds with OpenSSL — an implementation independent of both apps — following `services/mail-service/src/e2e/envelope.ts` byte for byte. The same values are embedded in `E2eTestVectors.kt` and `E2eTestVectors.swift`. If either app drifts by one byte (AAD strings, HKDF labels, salt, PBKDF2 encoding, DER wrappers) its vector tests fail. **Never regenerate the vectors to make a test pass**: the script refuses to overwrite a file whose contents changed, because that would mean the wire format changed. The backend's own `envelope.test.ts` has no fixed vectors; it could open these too.

## Running locally

Android (JDK 17; Android SDK with platform 36):

```bash
cd android
./gradlew :app:testDebugUnitTest                       # everything
./gradlew :app:testDebugUnitTest --tests '*MailE2eTest' # one class
open app/build/reports/tests/testDebugUnitTest/index.html
```

iOS (Xcode 16, XcodeGen):

```bash
cd ios
xcodegen generate
xcodebuild test -project GrapMail.xcodeproj -scheme GrapMail \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:GrapMailTests
# or ⌘U in Xcode with the GrapMailTests target selected
```

Notes:
- `android/app/build.gradle.kts` sets `testOptions.unitTests.isReturnDefaultValues = true` so `android.util.Log` in `ApiException` does not throw on the JVM. No Robolectric is needed.
- Two Android test seams exist, both used only by tests: `Api.baseOverrideForTests` (points the real OkHttp client at MockWebServer) and `MailE2e.sdkIntOverrideForTests` (the stub `android.jar` reports SDK 0, the JDK has X25519/Ed25519). The Outbox retry table was moved verbatim into `OutboxRetryPolicy` (Android) and `Outbox.isFinalRefusal/accepted/expired` (iOS), which the workers call.
- Localized strings: in JVM tests `CoreStrings` returns the English fallback, so assertions on English wording are the shipped English text.
- `MailE2eTests.testRestoringTheVectorKitGivesBackTheSameKeysUnderANewPassphrase` writes one identity file for a test-only account (`unit-tests@grapmail.invalid`) in the simulator app's Application Support. Nothing is deleted by any test.
- The 600k-round PBKDF2 tests take a few seconds each (deliberately: they are the real parameters).

## How CI runs it

- `android.yml` → job **`unit-tests`**: `./gradlew :app:testDebugUnitTest`, uploads `android-unit-test-report` (HTML + JUnit XML) on every run and prints each failure as an annotation. Runs on every push, PR, tag and dispatch, independent of the emulator walk. `release` has `needs: unit-tests` (and still runs `testReleaseUnitTest` itself).
- `ios.yml` → job **`unit-tests`**: `xcodegen generate`, then `xcodebuild test … -only-testing:GrapMailTests` on an available iPhone simulator, uploads `ios-unit-test-results` (the `.xcresult`). The screenshot walk now passes `-skip-testing:GrapMailTests` so its verdict is about the walk alone. `release` has `needs: unit-tests`.
- GitHub Actions minutes are free for this public repository.

## Known bugs (tests expected to FAIL until the code is fixed)

Each lives in `KnownBugsTest.kt` / `KnownBugsTests.swift`, asserts the correct behaviour, and documents the cause. Because the release jobs depend on the unit suite, **these will block releases until fixed** — that is intended. To ship before a fix, mark the single test `@Ignore("KNOWN BUG …")` (Android) or start it with `throw XCTSkip("KNOWN BUG …")` (iOS); do not delete it.

Android:
1. NaturalSearch — `about <topic>` swallows later filters: "emails about the budget with attachments" loses `hasAttachment`.
2. NaturalSearch — `from bob@example.com` (natural form) gives `from = "bob"` (`[\w.+-]` excludes `@`).
3. NaturalSearch — "past N weeks/months" and "older than N days/weeks" set the window but stay in the free-text term, so the search also requires the literal words.
4. Action cards — airline codes with a digit (B6, F9) are listed but can never match `[A-Z]{2}`.
5. Tracker shield — the hint `facebook.com/tr` is compared to the host only and never matches.
6. URL/QR safety — a raw Unicode homograph host (`https://аpple.com`, Cyrillic а) is rated "Looks safe": `java.net.URI` gives no host, the fallback keeps the Unicode, and no check looks at non-ASCII hosts.
7. Compose assist FRIENDLY — "Kind regards" becomes "Kind Best" (the `regards` rule runs before `kind regards`).
8. Compose assist — a PROFESSIONAL draft ends "Best\n\nBest regards" (two sign-offs).

iOS:
1. NaturalSearch — the doc comment's own example "invoice from alice last week with attachments" loses the date window and the attachment filter (the `from` matcher takes four words and removes them).
2. NaturalSearch — "about the budget" yields no subject ("the" is a stop word, so the topic stops before it starts).
3. Action cards — "$1,250.00" is read as "$1,25".
4. Action cards — the flight pattern is case-insensitive, so "departs on 2026-12-01" yields a flight "on 2026".
5. Categorizer — `knownSenders` is compared case-sensitively against a lower-cased sender (Android lowercases both).

Observations that are **not** asserted as bugs (product decisions, listed for review): Android's brand look-alike check flags some genuine domains (`amazonaws.com`, `microsoftonline.com`, `googleusercontent.com`); iOS rates a bare IP or a `user@host` link MEDIUM where Android rates it HIGH; iOS requires "code:"-style wording for a code card ("Your code is 482913" yields none); iOS handle candidates drop digits from names, Android keeps them.

## Not included, and why

**Compose UI instrumentation tests** were not added to the emulator job. The project has no `testInstrumentationRunner` or `androidTest` dependencies; adding them means compiling and installing a second (test) APK on each of the three matrix devices, and `connectedAndroidTest` uninstalls the app under test when it finishes, which would interfere with `scripts/android-walk.sh`'s installed state unless carefully ordered — on a job that already runs close to its 120-minute ceiling. The walk already exercises the real Compose UI on three screen sizes. A follow-up could add a separate, single-device `instrumented-smoke` job.
