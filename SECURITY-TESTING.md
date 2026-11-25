# Security testing (GrapMail mobile)

This file says what the automated security scan in
`.github/workflows/security-scan.yml` checks, how that maps to the OWASP
**MASVS** (target: **MAS-L2 plus the privacy profile**), and which manual tests
are still needed before anyone calls a build "MASVS-L2 verified". CI handles
the cheap, repeatable static checks. It **cannot** verify L2 alone: most L2
controls only show up when the app is running on a device that an attacker
controls.

Everything here is free. The workflow needs **no repository secrets**.

---

## 1. What CI runs

| Job | Tool | Runs on | Fails the build when | Output |
|---|---|---|---|---|
| `mobsf-android` | MobSF v4.6.9 (Docker service container, pinned by digest) | the debug APK built with the same JDK 17 / `./gradlew assembleDebug` as `android.yml` | any **high** finding not in `security/mobsf-allowlist.yml`, any **expired or incomplete** allowlist entry, or a report that is missing, an error, or in a shape the gate can't read (it fails closed) | artifact `mobsf-android-report` (JSON, PDF, scorecard), `mobsf-android-gate`, plus a table in the job summary |
| `mobsfscan-source` | mobsfscan 1.2.0 | `android/` and `ios/` sources (Kotlin, Swift, XML, plist) | only if mobsfscan crashes and writes no SARIF. Findings are reported, not blocking | Security → Code scanning (category `mobsfscan`) and artifact `mobsfscan-sarif` |
| `secrets` | gitleaks 8.30.1 (checksum-verified binary, no licence key) | PR: the PR's commits. Push: the pushed commits. Weekly/manual: the **full history** | any leak not in `security/gitleaks-baseline.json` (if that file exists) or `.gitleaksignore` | code scanning (category `gitleaks`), artifact `gitleaks-sarif`, summary |
| `deps` | OSV-Scanner (`google/osv-scanner-action` v2.7.1) | a `gradle.lockfile` and a `Package.resolved` that the job generates on the runner (the repo commits neither lockfile) | never: report-only | code scanning (category `osv-scanner`), artifact `osv-scanner-report`, summary |

Triggers: push to `main`, every pull request, weekly (Mondays 03:17 UTC) and
manual dispatch.

### The MobSF gate in detail

`scripts/mobsf-gate.py` reads MobSF's `/api/v1/report_json` output. It
collects findings from `code_analysis`, `manifest_analysis`,
`network_security`, `certificate_analysis`, `binary_analysis`, `firebase_urls`
(plus `ats_analysis` and `js_analysis` when they are present) and gives each
one a stable id such as `code:<rule>`, `manifest:<rule>`, `network:<slug>`,
`certificate:<slug>` or `binary:<check>`. To list every id in a report:

```sh
python3 scripts/mobsf-gate.py --report mobsf-report.json --list
```

* Severity `high` blocks the build. `warning`/`info` are reported only. Change this with `--fail-on`.
* `--debug-build` (used in CI) reports *debuggable* and *signed with debug
  certificate* as "expected (debug build)" instead of blocking. The debug APK
  has both by definition, and the release job in `android.yml` already proves
  the release APK is not debuggable and carries the pinned upload
  certificate.
* Allowlist entries need `id`, `reason`, `owner` and `review_by`
  (YYYY-MM-DD). Once `review_by` has passed, the entry fails the build until
  someone re-reviews it. An entry that matches nothing produces a warning so
  you can remove it.
* Exit codes: `0` pass, `1` blocking findings or allowlist errors, `2`
  unusable report or allowlist.

### Accepting a historical secret (gitleaks)

Rotate the secret first. A secret that reached a public repository is leaked
whether or not the commit is later removed. Then record it in a baseline so
the weekly full-history scan only fails on new leaks:

```sh
gitleaks git --redact --report-path security/gitleaks-baseline.json .
```

Review that file before committing it. It must contain only rotated secrets.

### Action pinning

Every third-party action is pinned to a full commit SHA, with the tag in a
comment. Each SHA was read from the GitHub API on 2026-11-23:
`actions/checkout` v7.0.1, `actions/setup-java` v6.3.0,
`actions/setup-python` v7.1.0, `actions/upload-artifact` v7.0.2,
`github/codeql-action/upload-sarif` v4 (v4.39.4),
`google/osv-scanner-action` v2.7.1. The MobSF image is pinned by tag and index
digest. The gitleaks tarball is pinned by SHA-256. mobsfscan and PyYAML are
pinned by version from PyPI (no hash).

---

## 2. MASVS mapping (L2 + privacy)

✅ = CI gives useful static evidence. ◐ = partial (static hints only). ✖ = CI
cannot test it, manual work required. Test IDs are MASTG v1 test IDs; MASTG is
moving to new `MASTG-TEST-02xx` IDs, so check the current catalogue when you
write the test report.

| MASVS group | What CI covers | Gaps that need manual testing |
|---|---|---|
| **MASVS-STORAGE** (data at rest) | ◐ MobSF: `allowBackup`, exported providers, world-readable files, logging calls, hardcoded data. gitleaks: no credentials in the repo or its history | Whether tokens, mail bodies, attachments and E2E keys actually sit in Keystore/Keychain-protected storage on a real device. Caches, screenshots in the app switcher, keyboard caches, notification content on the lock screen, widget data (`GrapMailWidget`, `WidgetShared`) |
| **MASVS-CRYPTO** | ◐ MobSF/mobsfscan rules for weak algorithms, ECB, static IVs, insecure random, hardcoded keys | Correct use of the X25519 + HKDF-SHA256 + AES-256-GCM envelope (nonce uniqueness, key separation, same bytes on both platforms) needs code review and test vectors, not a scanner |
| **MASVS-AUTH** | ✖ (at most a hint for biometric API use) | Session lifetime, revocation, re-auth before sensitive actions, biometric app-lock bypass, passkey flow, server-side enforcement. All runtime |
| **MASVS-NETWORK** | ◐ MobSF: cleartext traffic, network security config, trust of user CAs. mobsfscan: TLS/hostname-verifier misuse. **MASTG-TEST-0022** (Android) / **MASTG-TEST-0068** (iOS) custom trust stores and certificate pinning: static only | Whether pinning holds against an intercepting proxy. Note that `API_PINS` is empty by default, which means *no pinning* (see `android/app/build.gradle.kts`). Whether a release built without pins still meets the L2 claim |
| **MASVS-PLATFORM** | ◐ MobSF: exported components, intent filters, deep links, WebView settings, permissions. **MASTG-TEST-0027** (Android URL loading in WebViews) and **MASTG-TEST-0076** (iOS WebViews): static flags only. `scripts/mail-honesty.py` in `android.yml` already asserts that no remote HTML body gets a renderer | Deep link / custom URL abuse, IPC from a malicious app, and message-rendering attacks with real malicious mail (§3) |
| **MASVS-CODE** | ✅ OSV-Scanner over the resolved Gradle and SwiftPM dependencies. ◐ MobSF: minSdk, binary protections, debuggable flag (proved for release in `android.yml`) | Input validation in parsers (MIME, SVG via SwiftDraw/AndroidSVG, attachments), and the update-enforcement behaviour |
| **MASVS-RESILIENCE** (L2/R) | ◐ MobSF: APKiD (obfuscator/packer detection), root/emulator detection hints | Whether tamper/root/hook detection and Play Integrity actually change behaviour under Frida/objection |
| **MASVS-PRIVACY** | ◐ MobSF: trackers (Exodus list), dangerous permissions, domains contacted | Matching data minimisation and on-screen disclosures against `DATA-FLOW.md` and the store privacy labels. Push payload contents. Analytics/crash SDK data |

iOS has no MobSF binary scan in CI yet: it would need an IPA, which needs a
macOS build. iOS is covered by mobsfscan (source), OSV (SwiftPM) and gitleaks.
For a binary scan, upload a simulator or ad-hoc IPA to a local MobSF (§4).

---

## 3. Manual passes still required (free tools)

Tools: **Frida** and **objection** (runtime hooking), **mitmproxy**
(interception), a rooted Android emulator (Google APIs image) and, for iOS, a
jailbroken test device or the simulator where possible. Write up results
against the MASTG test IDs in the test report.

| Scenario | What to try | MASVS | Pass criteria |
|---|---|---|---|
| **Session theft** | Copy the app's data dir / Keychain items from a rooted or jailbroken device to another device. Replay tokens captured with mitmproxy. `objection … android keystore list` / `ios keychain dump` | AUTH, STORAGE, NETWORK | Stolen tokens don't work off-device or get revoked quickly. Tokens are in Keystore/Keychain, not in plain prefs. Server sign-out invalidates them |
| **Lost phone** | Locked device with the app installed: notifications, widget, app-switcher snapshot, backups (`adb backup`, iCloud/Files), biometric lock bypass with Frida hooks on `BiometricPrompt` / `LAContext` | STORAGE, AUTH, PRIVACY | No mail content readable without unlock. App lock can't be bypassed by hooking the client alone. Remote sign-out works |
| **Phishing** | Look-alike sender, BIMI/SVG logo spoofing, deep links and custom URL schemes from mail into in-app actions (MASTG-TEST-0027 / 0076 for any WebView in the path) | PLATFORM, PRIVACY | Sender identity is shown honestly. Links leave the app visibly. No deep link performs an action without confirmation |
| **Malicious attachments** | Oversized, malformed and polyglot files, SVG with scripts/external refs, path traversal in filenames, MIME confusion | CODE, PLATFORM, STORAGE | No crash or code execution. Files go to the app sandbox under the expected name. Open is handed to the OS viewer as designed |
| **Message rendering** | HTML/CSS tricks, remote images (tracking pixels) before consent, `javascript:` / `file:` / `intent:` URLs, oversized bodies | PLATFORM, PRIVACY | No remote HTML renderer (pinned by `mail-honesty.py`). Remote images only after the user consents. No script execution |
| **E2E key recovery** | Reinstall, a new device, restore from backup, lost device + recovery path. Inspect where private keys live and whether they leave the device, and how. Try a MITM during key exchange (mitmproxy) | CRYPTO, STORAGE, AUTH | Private keys are never sent in plaintext. Recovery needs a user secret the server doesn't hold. A key swap is visible to the user |
| **Pinning / interception** (MASTG-TEST-0022, MASTG-TEST-0068) | mitmproxy with its CA installed as a user CA and then a system CA. Frida/objection `android sslpinning disable` / `ios sslpinning disable` | NETWORK, RESILIENCE | Without the bypass, interception fails for the API host. Record whether the build under test had `API_PINS` set |

---

## 4. Running MobSF locally (free, Docker)

```sh
# 1. Start MobSF (same image and version as CI). Web UI on http://localhost:8000
#    (v4 asks for a login; the default is mobsf / mobsf, change it if you expose the port).
docker run -it --rm -p 8000:8000 -e MOBSF_API_KEY=local-dev-key \
  opensecurity/mobile-security-framework-mobsf:v4.6.9

# 2. Build the APK (or use the `grapmail-android-debug` artifact from android.yml).
(cd android && ./gradlew assembleDebug)

# 3. Scan through the REST API (or drag the APK/IPA into the web UI).
KEY=local-dev-key
HASH=$(curl -s -H "Authorization: $KEY" -F "file=@android/app/build/outputs/apk/debug/app-debug.apk" \
  http://localhost:8000/api/v1/upload | python3 -c 'import sys,json;print(json.load(sys.stdin)["hash"])')
curl -s -H "Authorization: $KEY" --data "hash=$HASH" http://localhost:8000/api/v1/scan > /dev/null
curl -s -H "Authorization: $KEY" --data "hash=$HASH" http://localhost:8000/api/v1/report_json > mobsf-report.json
curl -s -H "Authorization: $KEY" --data "hash=$HASH" http://localhost:8000/api/v1/download_pdf > mobsf-report.pdf

# 4. Apply the same gate as CI.
pip install PyYAML
python3 scripts/mobsf-gate.py --report mobsf-report.json --debug-build --summary mobsf-summary.md
```

On Windows, run the same `docker` command in PowerShell and the `curl` steps
in Git Bash or WSL. iOS: build an `.ipa` on a Mac and upload it the same way.
MobSF's static iOS analysis runs on Linux and Windows too.

Run mobsfscan, gitleaks and OSV locally:

```sh
pip install mobsfscan==1.2.0 && mobsfscan android/ ios/
gitleaks git --redact .
osv-scanner scan source -r .     # after generating lockfiles as the `deps` job does
```

---

## 5. Limits (read before quoting results)

* CI is **static**. A clean run means "no known static anti-patterns", not
  "MASVS-L2 compliant". L2 needs the manual passes in §3.
* MobSF scans the **debug** APK, which is not minified. Code findings are
  easier to read than in the R8 release build, but the release artifact's
  binary properties are proven separately by `android.yml`.
* OSV coverage depends on the lockfiles the `deps` job manages to generate.
  The job summary lists which lockfiles were scanned and warns when one is
  missing.
* Code-scanning uploads are skipped for pull requests from forks (their token
  is read-only). The SARIF artifacts still carry the results.

---

## 6. Certificate pinning (MASVS-NETWORK-2, MASTG-TEST-0022 / 0068)

### Where it lives

| Piece | File |
|---|---|
| Source of truth (pins, hosts, expiry, on/off) | `security/api-pins.json` |
| Android: JSON -> `BuildConfig.API_PINS`, `API_PIN_HOSTS`, `API_PINS_EXPIRES` | `android/app/build.gradle.kts` |
| Android: enforcement | `apiPinner()` in `android/app/src/main/java/com/grapsee/grapmail/data/Api.kt` (OkHttp `CertificatePinner` on `client`, the token-refresh and avatar-watch clients built from it, and `parkedAccountClient`) |
| iOS: JSON -> Swift constants (generated and committed) | `scripts/generate-pins.py` -> `ios/GrapMail/ApiPins.swift` |
| iOS: enforcement | `ios/GrapMail/PinnedSessionDelegate.swift` (`PinnedSession.shared`, used by `APIClient.swift` and the parked-account unread call in `MailAPI.swift`) |
| Live values for a host | `scripts/print-api-pins.sh <host>` |
| Weekly check + issue | `scripts/check-api-pins.py`, `.github/workflows/pin-expiry.yml` |

### Current state (2026-11-23): pins present, enforcement OFF

`security/api-pins.json` holds six real CA pins taken from the live chains of
`grapsee.com` and `api.iqracsc.com`, plus a placeholder for the founder's
backup key. It has `"enforce": false`, so **neither app pins yet**. Builds
behave exactly as before. The reason is that `grapsee.com` is behind
Cloudflare on a Cloudflare-managed edge certificate. Cloudflare picks and
rotates that CA itself (Let's Encrypt, Google Trust Services or SSL.com, and
Sectigo for backup certificates) and does not support pinning it. The pin set
covers Google Trust Services (live today) and Let's Encrypt. A switch to
SSL.com or Sectigo would break every pinned API call until an update ships.

Turn enforcement on (`"enforce": true`, regenerate, ship both apps) only after:
1. `grapsee.com` serves a certificate whose CA (and ideally key) you control:
   a Cloudflare custom certificate, or an Advanced certificate locked to one CA;
2. the backup pin below is real (not a placeholder);
3. `python3 scripts/check-api-pins.py` passes against the new chain;
4. the interception test below passes on both platforms.

### Rules (identical on both platforms)

* Pinning is enforced only when `enforce` is true, there are **at least two**
  well-formed non-placeholder pins, and today (UTC) is **before** `expires`.
  One pin means no pinning, because one pin with no backup locks everyone out
  at the next key change.
* Only `host` and `additionalHosts` are pinned (exact names; Android also
  pins the `API_BASE` host). Other hosts get normal TLS handling.
* The platform's own certificate validation always runs first. Pinning only
  ever narrows trust; it never accepts a chain the OS rejects.
* A connection passes when **any** certificate in the verified chain (leaf,
  intermediates or the trust anchor) has a pinned SPKI SHA-256. CA keys are
  pinned, not leaves: leaves change about every 90 days.
* **After `expires`, pinning switches off (fail-open)** and a warning is
  logged. Platform validation still applies. This matches Android's own
  Network Security Config `<pin-set expiration>`. Failing closed would cut off
  every install that was not updated in time, through the only channel that
  could tell it to update. Android checks the date once per process (when the
  OkHttp client is built). iOS checks at launch and again on every TLS
  challenge.

### Expected behaviour

| Situation | Android | iOS |
|---|---|---|
| `enforce` false, fewer than 2 pins, or past `expires` | No pinning; normal TLS. Past `expires` logcat shows `W GrapMailApi: Certificate pin set expired (…)` | No pinning; `PinnedSession.shared` **is** `URLSession.shared`. Past `expires` Console shows a warning (subsystem `com.grapsee.grapmail`, category `TLSPinning`) |
| Enforced, genuine server | Works | Works |
| Enforced, proxy CA trusted by the device (mitmproxy as a system CA on a rooted emulator; an installed and trusted profile on iOS) | API calls fail with `SSLPeerUnverifiedException: Certificate pinning failure!`; the app shows its normal network error | API calls fail with `NSURLErrorCancelled` (-999); Console: `Certificate pin mismatch for <host>`; the app shows its normal network error |
| Enforced, proxy CA as a *user* CA only (Android) | Already refused by the platform (apps targeting API 24+ ignore user CAs), before pinning is involved | n/a |
| Enforced, Frida/objection `sslpinning disable` | Bypassed. That is a RESILIENCE finding, not a pinning bug | Bypassed. Same |
| Hosts that are not pinned | Sender images (`MessageHandoff.kt`), `LaunchApi.kt` (own client: **gap**, not pinned yet) | Sender images (`MailHandoff.swift`), `RemoteConfig.swift` app-config and client reports (on purpose, see below), Notification Service extension (`NotificationService.swift`, separate target: **gap**) |

**iOS `RemoteConfig` / crash reports stay on `URLSession.shared` on purpose.**
They carry no bearer token. The app-config call is the forced-update channel:
if a pin set ever goes stale, it is the only path that can still tell users to
update, so it must not fail the same way. Cost: a device that trusts an
attacker's CA could tamper with app-config flags or the store URL. Android
currently routes both through the pinned `client`, which is stricter but loses
that recovery path. Aligning the two is a follow-up.

### How to test (manual, before turning enforcement on)

Do this on a **test build**, never by committing `enforce: true` early.

* Android: `./gradlew assembleDebug -PgrapmailApiPins="sha256/…,sha256/…"`
  forces the pins on for one build. Add `-PgrapmailApiPinsExpires=2020-01-01`
  to check the expired path (expect the logcat warning and working, unpinned
  calls). `-PgrapmailApiPins=none` forces pinning off.
* iOS: temporarily set `"enforce": true` in a local copy of the JSON, run
  `python3 scripts/generate-pins.py`, build, then revert both files. To check
  the expired path, edit `expiresAt` in the generated file to a past time,
  locally only.
* Run the "Pinning / interception" scenario in §3. Expect the table above.
  Also confirm that a pin *mismatch* gives the network error and not a crash:
  put two wrong-but-well-formed hashes in a local build.

### Rotating pins

1. `scripts/print-api-pins.sh grapsee.com` and `… api.iqracsc.com`.
2. Add the new CA keys to `security/api-pins.json` and **keep the old ones**
   until installs that only know the old pins have updated. Move `expires`
   forward (about one year; it must leave time for users to update).
3. `python3 scripts/generate-pins.py && python3 scripts/check-api-pins.py`.
4. Ship both apps **before** the server changes CA or key.

### Backup pin (founder action)

A backup pin is the hash of a key pair that no server holds yet. It lets you
move to a new certificate without an app update if the current CA or key has
to go. Generate it on an offline machine:

```sh
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -aes-256-cbc -out grapmail-backup-p256.key
openssl pkey -in grapmail-backup-p256.key -pubout -outform der | openssl dgst -sha256 -binary | openssl base64 -A
```

Put the printed value in the placeholder entry's `sha256`, delete its
`"placeholder": true`, regenerate, and commit. Store the encrypted key file and
its passphrase **separately and offline** (for example an encrypted USB drive
plus a password manager). It must never be on a server, in this repo, or in
CI. To use it, make a CSR from it
(`openssl req -new -key grapmail-backup-p256.key -subj "/CN=grapsee.com" -addext "subjectAltName=DNS:grapsee.com,DNS:*.grapsee.com" -out backup.csr`),
get it signed by any public CA, and install it as a custom certificate
(Cloudflare for `grapsee.com`, cPanel for `api.iqracsc.com`). A backup key only
helps where you can choose the key the edge serves, which Cloudflare's managed
certificates do not allow.

### Weekly check

`.github/workflows/pin-expiry.yml` runs on Mondays at 04:23 UTC, on manual
dispatch, and on pushes to `main` that touch the pin files. It fails, and opens
or updates the issue *"Certificate pins need attention
(security/api-pins.json)"*, when the JSON is invalid, has fewer than two real
pins, expires within 30 days or has expired, when a pinned host's live chain
matches no pin, or when `ApiPins.swift` is stale. It closes that issue once
the check passes again. A placeholder, `enforce: false` and a leaf about to
expire are warnings only. GitHub disables scheduled workflows after 60 days
without repository activity; re-enable it in the Actions tab if that happens.


## On-device hardening (MASVS-L2 + resilience), 2026-11-23

### Pinning coverage: Android vs iOS

| Traffic | Android | iOS |
|---|---|---|
| Main API (`Api.kt` `client` / `APIClient` via `PinnedSession.shared`) | pinned | pinned |
| Token refresh, parked-account unread | pinned | pinned |
| Delete account, push register/unregister (`LaunchApi.kt`) | **pinned now** (`Api.launchApi` passes an OkHttp client with `apiPinner()`; same no-redirect/no-retry rules) | via APIClient (pinned) |
| Notification preview fetch (NotificationService extension) | n/a (no separate extension process) | **pinned now**: `PinnedSession.shared`; `ApiPins.swift` + `PinnedSessionDelegate.swift` are compiled into the extension target via `project.yml` (no copy) |
| Settings / crash-report requests | not re-audited in this change (Android crash reports: check `logic/CrashReporter.kt`) | **unpinned by design** (`URLSession.shared`; must be able to report a pinning failure) |
| Sender images, links | unpinned (third-party hosts) | unpinned |

`security/api-pins.json` keeps `"enforce": false` (Cloudflare-managed edge
certificates). With enforce false both platforms compile no pins, so every row
above is "pinned" in wiring only; flipping enforce turns all of them on at once.
`JailbreakCheckTests.testPinsStayUnenforcedAsDesigned` guards the current value.

### App-switcher / screenshot privacy

* Android: `FLAG_SECURE` on the window while a session exists
  (`security/ScreenPrivacy.kt`, applied from `MainActivity`). Setting
  "Allow screenshots" (mail settings, below App lock), default **off**.
* iOS: `PrivacyCover` overlay while `scenePhase != .active` and signed in, so the
  app-switcher snapshot shows no mail. iOS cannot block screenshots or
  recording; the app does not claim to.

### Biometric-bound E2E key copy

Android `security/BiometricKeyWrap.kt`, iOS `BiometricKeyWrap.swift`. Additive:
the passphrase record is never modified. With app lock on, a successful
passphrase unlock writes a second copy sealed by an AndroidKeyStore AES-GCM key
(`setUserAuthenticationRequired(true)`, API 30+ `setUserAuthenticationParameters(0,
BIOMETRIC_STRONG|DEVICE_CREDENTIAL)`, `setInvalidatedByBiometricEnrollment(true)`,
StrongBox when present, TEE fallback) / a Keychain item with
`.biometryCurrentSet` + `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`.
If creation fails, nothing changes. A new biometric enrollment invalidates the
copy: it is deleted and the user is told to use the passphrase or recovery key.

### Root / jailbreak heuristics

`security/RootCheck.kt`, `DeviceIntegrityCheck.swift`: su/Magisk paths,
test-keys, rw `/system`; Cydia/Sileo/`/var/jb` paths, sandbox write, injected
dylibs. Dismissible warning card in the security hub, never blocks. Play
Integrity / App Attest stay server-judged and are not duplicated.

### Manual device tests

1. Android signed in: screenshot → system refuses; Recents shows blank. Toggle
   "Allow screenshots" on → works immediately. Sign out → screenshots allowed.
2. iOS signed in: swipe to app switcher → cover, not mail.
3. App lock on, unlock E2E with passphrase (prompt appears once to bind), kill
   app, reopen unlock screen → "Unlock with fingerprint or face"/"Face ID"
   button works.
4. Enroll a new fingerprint / Face ID appearance → biometric unlock shows the
   invalidation note; passphrase unlock still works and re-binds.
5. Remove device screen lock / passcode → no crash, passphrase path works.
6. Rooted (Magisk) / jailbroken test device → warning card, dismiss persists.
7. With a local build `enforce: true` and two wrong pins: delete account / push
   register on Android, and a rich notification on iOS, fail with the network
   error (no crash); iOS crash report still sends.
