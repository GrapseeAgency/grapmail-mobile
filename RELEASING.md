# Releasing GrapMail (staged rollout runbook)

A store build cannot be rolled back. Once a phone has installed a version it keeps it until the
next update. So a release goes out a little at a time, every step is a human click, every widening
step needs 24 hours of crash-free evidence, and the way to turn something off without a new build
is the server, not the store.

| Piece | Where |
|---|---|
| Build, sign, verify, emulator launch, GitHub Release (Android) | `.github/workflows/android.yml` → `release` job (unchanged) |
| Archive, sign, upload to App Store Connect / TestFlight (iOS) | `.github/workflows/ios.yml` → `release` job (unchanged) |
| Google Play upload + staged rollout + halt | `.github/workflows/play-rollout.yml` |
| TestFlight processing, review submission with phased release, pause/resume | `.github/workflows/appstore-release.yml` |
| Version gate (tag ↔ both platforms, numbers only go up) | `scripts/release-version-check.py` |
| Crash-free gate | `scripts/release-crash-gate.py` → `GET /api/grapsee/grapmail/client-reports/summary` |
| Play / App Store Connect API calls | `scripts/play-track.py`, `scripts/appstore-connect.py` |
| Store release notes | `whatsnew/whatsnew-en-US` (Play, ≤ 500 chars), `whatsnew/ios-en-US.txt` (App Store) |
| Turn things off without a build | `GET /grapmail/app-config` (backend `lib/app-config.ts`) |

---

## 0. Before you tag: one version for both platforms

The version gate refuses to ship unless:

1. the tag equals the shipping platform's own version (`versionName` in
   `android/app/build.gradle.kts`, or `MARKETING_VERSION` in `ios/project.yml`). This one has no override.
2. the tag equals the other platform's version too. You can override this with `version_override`, and the override goes in the run summary.
3. `versionCode` / `CURRENT_PROJECT_VERSION` is higher than at the previous `v*` tag, and the
   marketing version didn't go down. You can override this with `version_override`, and the override goes in the run summary.

The Play upload also checks the version code against the highest one Play has ever seen. App Store Connect
does the same check for build numbers itself.

> **Current state (25 Nov 2026): first public release is fixed at 0.0.1.**
> Android is **0.0.1 (versionCode 1)** and iOS is **0.0.1 (build 1)**.
> Both release workflows accept the same tag, **`v0.0.1`**, and verify the built
> artifact against it. Earlier 0.2.x–0.7.x identifiers were internal/sideload
> lineage. A device with one of those higher test versionCodes must uninstall it
> before installing the public 0.0.1 build.

## 1. The rollout, step by step

The same ladder applies to both stores. Never skip the wait.

| Step | Android (Play) | iOS (App Store) | Wait / gate |
|---|---|---|---|
| **Tag** | push `vX.Y.Z`. `android.yml` builds and verifies. `play-rollout.yml` then uploads automatically to the `internal` track. It waits for approval in `play-testing` only if you added reviewers there. | push `vX.Y.Z`. `ios.yml` uploads. `appstore-release.yml` waits for processing and adds the build to `ASC_BETA_GROUP` if set | — |
| **Internal test** | testers install from the Play internal link | TestFlight (internal testers get the build automatically) | at least 24h, gate on testers' sessions (`min_sessions` small, e.g. 10–20) |
| **5%** | `play-rollout` › `promote`, fraction `0.05` (starts production from internal) | `appstore-release` › `submit` (phased release ON). After Apple approves, press **Release** (default `MANUAL`) | 24h, crash-free ≥ 99.5% |
| **10%** | `promote` `0.10` | Apple: day 1 = 1%, day 2 = 2%, day 3 = 5%, day 4 = 10% | 24h |
| **20%** | `promote` `0.20` | day 5 = 20% | 24h |
| **50%** | `promote` `0.50` | day 6 = 50% | 24h |
| **100%** | `promote` `1.0` (completes the release) | day 7 = 100%, or `phased-complete` | — |

**Every widening step** runs in a protected Environment (`play-production` / `appstore-production`), so
a required reviewer has to click **Approve**. Before it changes anything, it runs the crash-free gate
for that exact version over the last 24 hours:

- it passes only if crash-free sessions are **≥ `crash_free_threshold`** (default **99.5%**) over at least
  `min_sessions` sessions.
- if the summary endpoint is unreachable, answers an error, the secret is missing, or the version has no data,
  it **fails closed**.
- `override_gate: true` plus an `override_reason` lets the step go ahead anyway. The workflow writes the reason,
  your GitHub handle and the run id into the run summary as a warning. Use it for things like "internal
  track only has 6 testers". Don't use it for "it's probably fine".

On iOS, Apple advances the percentage by itself every day. The iOS lever is therefore **pause**, not promote.
The scheduled `watch` job, which runs every 6h, applies the gate to whatever is live and turns the run red
when it fails. That way GitHub emails you. If you set the repository variables `PLAY_AUTO_HALT=true` /
`ASC_AUTO_PAUSE=true`, it also halts or pauses automatically, but only when the gate fails on real data.
An unreachable endpoint never halts anything.

Phased release only throttles **automatic** updates. Anyone can still open the App Store page and update by hand.
Play's staged rollout is a real percentage of eligible users.

### Commands

GitHub › Actions › *Play rollout* / *App Store release* › **Run workflow**. Pick the tag under
"Use workflow from" for `submit`.

| Do | Workflow | Inputs |
|---|---|---|
| See where things are | Play rollout | `action: status` |
| Upload a specific build | Play rollout | `action: upload`, `track`, `android_run_id` (blank = latest tag run), `fraction` (production only) |
| Widen / complete | Play rollout | `action: promote`, `fraction: 0.10 / 0.20 / 0.50 / 1.0` |
| **Stop now** | Play rollout | `action: halt`. No approval needed, on purpose. |
| Restart after a halt | Play rollout | `action: resume` (gated, keeps the halted fraction) |
| Submit for review | App Store release | `action: submit`, `release_type: MANUAL` or `AFTER_APPROVAL` |
| **Stop now** | App Store release | `action: phased-pause`. No approval needed. |
| Restart / finish | App Store release | `phased-resume` / `phased-complete` (gated) |

## 2. When a rollout goes bad

A store can't take a build back. Do these in this order:

1. **Stop the spread.** Run Play `halt` and iOS `phased-pause`. You can also do this by hand in Play Console ›
   Release › Production › *Halt rollout*, or App Store Connect › the version › *Pause Phased Release*.
   Apple allows 30 days of pause in total.
2. **Turn the broken thing off from the server.** No build needed. `GET /grapmail/app-config`, served by backend
   `lib/app-config.ts`, is read by both apps at launch and every 15 minutes while they're in front
   (`RemoteConfig.kt`, `RemoteConfig.swift`):
   - `killSwitches.send | signup | attachments | e2eRecovery | outbox: true` pauses that feature. The
     **server** enforces it, and the app explains it ("paused") before the person tries.
   - `flags.<name>: false` turns off a flagged code path in the new build.
   - `notice: {text, level: "warning"}` shows a banner above every screen ("Sending is paused while we fix…").
3. **Force people off a broken build.** The min-version gate: set `minVersion.android` to a **versionCode**
   (integer) and `minVersion.ios` to a **marketing version** (string) above the bad one. Any build below it
   shows only the "Update GrapMail to continue" screen, which opens `storeUrls.ios` / Play. Only raise it
   **after** a fixed build is live for 100% on that store. Otherwise you lock people out with nothing to update to.
4. **Fix forward.** Bump the version, which must be higher on both stores, tag, and run the ladder again.
   Starting again at 5% is fine. It's what the ladder is for.

## 3. One-time setup

All of this is free except the store developer accounts themselves.

### Google Play

1. In Play Console, the app `com.grapsee.grapmail` must exist, and the **first** AAB has to be uploaded by hand.
   Play's API can't create an app or make its first upload. Use the AAB from the GitHub Release or from the
   `grapmail-android-release` artifact. Enrol in Play App Signing. The upload key is the one `android.yml` pins.
2. Google Cloud Console: create a project, or reuse the Firebase one. Enable the **Google Play Android
   Developer API**. Go to *IAM › Service accounts › Create*. You don't need any GCP roles. Open *Keys › Add key › JSON*
   and download the file.
3. Play Console › *Users and permissions › Invite new users*: enter the service account's e-mail. Under *App
   permissions*, add GrapMail and grant **Release apps to testing tracks** and **Release to production, exclude
   devices, and use Play App Signing**. Invite. It can take up to 24h before the API accepts it.
4. GitHub secret **`PLAY_SERVICE_ACCOUNT_JSON`**: paste the whole JSON file.

### App Store Connect

The archive and upload in `ios.yml` already need these. `appstore-release.yml` reuses the same names:

| Secret | What |
|---|---|
| `APPLE_TEAM_ID` | Membership › Team ID |
| `APPLE_API_KEY_ID` | App Store Connect › Users and Access › Integrations › **Team Keys** › generate a key with role **App Manager** › Key ID |
| `APPLE_API_ISSUER_ID` | the Issuer ID shown on that page |
| `APPLE_API_KEY_P8` | **base64** of the downloaded `AuthKey_XXXX.p8` (`base64 -i AuthKey_XXXX.p8`). You can only download it once. |
| `BUILD_CERTIFICATE_P12`, `BUILD_CERTIFICATE_PASSWORD`, `APPSTORE_PROVISIONING_PROFILE` | already documented in `ios.yml` |

`appstore-release.yml` also accepts `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_KEY_P8`, as raw PEM or base64, if you
prefer those names. Set either set of names, not both.

- The app record for `com.grapsee.grapmail` must exist in App Store Connect. The store listing, screenshots,
  privacy answers and age rating must be filled in once by hand, or review submission is refused.
- **Export compliance:** GrapMail ships end-to-end encryption (X25519 / AES-GCM). Answer the encryption question
  for the first build in App Store Connect › TestFlight. Once you know the answer, you can make it permanent with
  `ITSAppUsesNonExemptEncryption` in `ios/project.yml`. Until it is answered, `submit` stops with an error.

### Crash-free gate

- GitHub secret **`GRAPMAIL_REPORTS_SECRET`**: the service secret the backend's summary route checks.
- Optional variable `GRAPMAIL_REPORTS_SUMMARY_URL` (default
  `https://grapsee.com/api/grapsee/grapmail/client-reports/summary`). Optional variable `CRASH_FREE_THRESHOLD`
  for the watch job (default 99.5).

**Contract the backend route should implement.** The gate script accepts a few close variants.

```
GET /api/grapsee/grapmail/client-reports/summary?platform=android&version=0.0.1&build=1&hours=24
Authorization: Bearer <GRAPMAIL_REPORTS_SECRET>        (also sent as X-GrapMail-Reports-Secret)

200 {"success": true, "windowHours": 24,
     "versions": [{"platform": "android", "appVersion": "0.0.1 (1)",
                   "sessions": 1830, "crashes": 2, "hangs": 1, "crashFreeSessions": 99.89}]}
```

`appVersion` is exactly what the apps send: `"<marketing version> (<build>)"`. `crashFreeSessions` may be a
percentage or a fraction. If it is missing, the gate computes `1 − crashes/sessions`. Hangs are reported but
don't count against the gate.

### GitHub Environments

Settings › Environments. Free on public repositories.

| Environment | Required reviewers | Used by |
|---|---|---|
| `play-production` | founder + one more; untick "allow self-review" if two people are available | Play production upload, `promote`, `resume` |
| `play-testing` | optional | internal/closed uploads |
| `appstore-production` | same as `play-production` | `submit`, `phased-resume`, `phased-complete` |

For each one, set *Deployment branches and tags* to **Selected: `main` and tags `v*`**.

### Repository variables (optional)

| Variable | Default | Effect |
|---|---|---|
| `PLAY_AUTO_TRACK` | `internal` | track the automatic post-tag upload goes to |
| `PLAY_SOURCE_TRACK` | `internal` | where `promote` picks up a build that isn't in production yet |
| `PLAY_AUTO_HALT` | unset | `true`: the watch job halts Play when the gate fails on real data |
| `ASC_AUTO_PAUSE` | unset | `true`: the watch job pauses the phased release when the gate fails on real data |
| `ASC_BETA_GROUP` | unset | TestFlight group the build is added to after processing |

## 4. Costs

| Item | Cost | Needed for |
|---|---|---|
| Google Play developer account | **$25 once** | publishing on Play at all |
| Apple Developer Program | **$99 / year** | publishing on the App Store / TestFlight at all |
| GitHub Actions (Linux and macOS runners), Environments, required reviewers | free (public repository) | everything in this file |
| Google Cloud service account + Play Developer API | free | Play uploads and rollouts |
| App Store Connect API, TestFlight, phased release | free (with the Apple membership) | iOS automation |
| `r0adkll/upload-google-play`, `google-auth`, `PyJWT`, `requests` | free, open source | — |

No paid services (fastlane match storage, Bitrise, Codemagic, Crashlytics alternatives) are needed. Crash and
session data comes from GrapMail's own `/grapmail/client-reports`.
