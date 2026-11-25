# Quality checks (`.github/workflows/quality.yml`)

Free, automated, separate from the release workflows: nothing here gates a release. Runs **nightly (02:17 UTC)**, on **manual dispatch** (optional `monkey_seed` input), and on **PRs touching `android/**`, `ios/**`, `quality/**`** or the workflow itself. The repo is public, so these runner minutes cost nothing.

| Job | Checks | Fails when | Artifacts |
|---|---|---|---|
| `android-build` | debug APK + `logcat_crash.py --selftest` | build fails | `quality-debug-apk` |
| `Android monkey` | `adb shell monkey`, fixed seed (default `20261123`), 5,000 events, 100 ms throttle, restricted to `com.grapsee.grapmail`, while `net-toggle.sh` cycles offline / online / airplane / wifi-only / data-only every 20 s | any `FATAL EXCEPTION` with `Process: com.grapsee.grapmail`, `ANR in com.grapsee.grapmail`, native tombstone for the app, or monkey's own `// CRASH` / `// NOT RESPONDING` | `logcat.txt`, `monkey.txt`, `net-toggle.log`, `seed.txt` |
| `Android startup` | 10 cold (`am force-stop` + `am start -W -S`) and 10 warm (BACK + `am start -W`) launches, median `TotalTime` | median > budget × 1.25. Over budget but within 25 % = warning | `cold.txt`, `warm.txt` |
| `Android jank` | Opens the offline demo mailbox (`--es gmSnapshot mail-home`, the same test-only hook `scripts/android-walk.sh` uses), resets gfxinfo, 30 slow swipes + 10 flings, reads `dumpsys gfxinfo` | never fails. Warns above 10 % janky frames | `gfxinfo.txt`, `mail-home.png` |
| `Android a11y` | `uiautomator dump` of launch, `mail-home`, `mail-thread`, `mail-labels`. Flags clickable/checkable nodes under 48×48 dp and clickables with no text/content-desc on themselves or any child | never fails (warn-only to start with) | `*.xml`, `*.png` |
| `ios-stress-a11y` | `QualityStressUITests`: `performAccessibilityAudit()` on launch + the three `-gmSnapshot` screens (warn-only), then about 3 min of seeded random taps, drags and edge-swipes | the app is not running after any gesture (crash), or the build/test fails | `quality.xcresult`, log |

All results show up in each job's **summary**.

## Budgets (`quality/budgets.json`)

Cold < **2000 ms**, warm < **1000 ms**, jank warning above **10 %**. These are **relative to the CI emulator** (x86_64, API 34, pixel_6, swiftshader software GPU), not to real phones. Watch a few nightlies before you tighten them. Change the numbers in the JSON, not in the scripts.

## Helpers (`quality/`)

| File | Role |
|---|---|
| `net-toggle.sh` | background network chaos. Restores online when killed |
| `logcat_crash.py` | crash/ANR parser (`--selftest` has sample input) |
| `startup_median.py` | median + budget verdict |
| `gfxinfo_jank.py` | janky % + p50/p90/p99 |
| `a11y_dump.py` | 48 dp + unnamed-clickable lint |
| `android-{monkey,startup,jank,a11y}.sh` | per-job drivers (the emulator action runs them) |

## Running locally

Android (boot an emulator, `adb devices` shows one device):

```bash
cd android && ./gradlew assembleDebug && cd ..
APK=android/app/build/outputs/apk/debug/app-debug.apk
bash quality/android-monkey.sh  "$APK" out/monkey 20261123 5000   # same seed = same event stream
bash quality/android-startup.sh "$APK" out/startup 10
bash quality/android-jank.sh    "$APK" out/jank
bash quality/android-a11y.sh    "$APK" out/a11y
```

iOS:

```bash
cd ios && xcodegen generate
QUALITY_STRESS_SEED=20261123 QUALITY_STRESS_SECONDS=60 \
TEST_RUNNER_QUALITY_STRESS_SEED=20261123 TEST_RUNNER_QUALITY_STRESS_SECONDS=60 \
xcodebuild test -project GrapMail.xcodeproj -scheme GrapMail \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:GrapMailUITests/QualityStressUITests
```

## Known limits

- **iOS network drops are not simulated.** The app has no launch-argument offline mode, and a simulator cannot be taken offline from inside XCUITest. The Android monkey job covers network drops.
- The monkey stops at the first crash. The seed in `seed.txt` replays the same event stream, but network timing is wall-clock based, so a replay is close to the original run, not identical.
- The a11y lint is heuristic. Compose's merged semantics usually name a clickable through its child text. If a target is clipped by the screen edge, it can show up as "small".
- Monkey starts at the real launch screen, which is signed out, so it stresses onboarding/sign-in and not the signed-in mailbox.
