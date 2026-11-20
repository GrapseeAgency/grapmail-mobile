# ROUND 4 — Expired-Verification Recovery Copy + CI Walk Order Sync

**Date:** 2026-11-18 · **Repo:** `grapmail-mobile` (Android + iOS + CI walks)
**Series:** R1 kit screens → R2 register payload → R3 flow order → **R4 seam copy + walk sync**

## 1. Why this round exists

R1 removed the human-verification skip path; R3 reordered the wizard so the challenge runs **before** account creation — matching the backend gate that consumes the verification id at register (`403` `human-verification-required` otherwise). Three leftovers still lived in the pre-reorder world:

1. **Copy**: neither mobile message catalog mapped the backend's expired-verification signal (`code: HUMAN_VERIFICATION_REQUIRED`). Android showed the generic 403 line ("This action is unavailable for your account."); iOS fell through to the raw server message. The web signup screen already showed the correct recovery sentence — mobile didn't.
2. **CI walks**: both screenshot walks still ran `terms → create → wait for challenge → tap "Skip for now"` — a tap that no longer exists since R1, and an order made impossible by R3. The next screenshots push would fail for the wrong reason.
3. **Doc drift**: two comments still described `human-verify -> created flow`.

## 2. Applied changes — 7 files

| # | File | Pre → Post | Δ | md5 (post) | Verify |
|---|------|-----------:|---:|------------|--------|
| 1 | `Api.kt` | 81,951 → 82,066 | +115 | `c968d36be4719562cc6ac95ffec15b8f` | size+markers |
| 2 | `SignUpViewModel.kt` | 10,514 → 10,649 | +135 | `5bc94bd176a0af2d7ea6f1b6905d1aaf` | size+markers |
| 3 | `MainActivity.kt` | 76,890 → 76,888 | -2 | `9fb7b236e9c629684283a81b83e2ce68` | size+markers |
| 4 | `android-walk.sh` | 72,016 → 73,336 | +1320 | `a0765edc03d3c24508abb4fdf82b1f3f` | size+markers |
| 5 | `APIClient.swift` | 80,684 → 80,817 | +133 | `6f4e60bc00df0278e840cb52278865b3` | size+markers |
| 6 | `SignUpModel.swift` | 9,680 → 9,817 | +137 | `1830aea3ba567c7926981ab4556c123f` | size+markers |
| 7 | `ScreenshotWalkUITests.swift` | 48,166 → 49,264 | +1098 | `20de25bc9ff167e60e7b6d3b9d5e97b3` | size+markers |

**Edit details**

1. **Api.kt** — `apiErrorMessage` gains `"HUMAN_VERIFICATION_REQUIRED"` → recovery sentence, alongside the other code-mapped messages.
2. **SignUpViewModel.kt** — `createAccount`'s code switch gains the same case, so the Terms step shows recovery guidance instead of the generic 403 line.
3. **MainActivity.kt** — comment renamed from `human-verify -> created flow` to `created -> onboarding flow`.
4. **android-walk.sh** — signup leg synced to the new order; the dead `must_tap "Skip for now"` is deleted; created proof waits for `You're all set!`.
5. **APIClient.swift** — `userMessage` gains `case "HUMAN_VERIFICATION_REQUIRED"` (parity with the web copy).
6. **SignUpModel.swift** — explicit case added; comment updated to `created → onboarding flow`.
7. **ScreenshotWalkUITests.swift** — verify block inserted after step 4 (enable-state polling instead of blind taps); post-create branch now expects the created screens; skip tap deleted.

**Exact recovery sentence (cross-surface parity, taken from the web app's own copy):**

> Your verification expired or was already used — complete the check again.

## 3. CI walk semantics under the new order

- The challenge photographs as `07-human-verify-light` **before** the terms step; `06-step5-terms-light` now comes second in time — shot names unchanged, so the honesty suite's name pins keep working.
- Continue is gated server-side: the walks poll the next screen / the button's enable state instead of blind taps (a disabled Material button's child text still reports enabled=true in uiautomator — the walk's own documented trap, honored).
- Automated runs need a solvable challenge: the implementation guide's test-key path (v2 test keys are always-pass) remains the sanctioned mechanism for dev walks; the app intentionally has no debug bypass.
- Live check 2026-11-18: `https://grapsee.com/verify/human` answered **503** — the deploy gate (checkpoint live before mobile builds ship) is still pending; unchanged from the audit.

## 4. Verification

- Build: assert-guarded transforms (7/7); `bash -n` on the rebuilt walk script passed; deltas exact per the table.
- Push: 7/7 writes landed during a read-flake window; patient-window probes re-confirmed new sizes (`android-walk.sh` 72,016 → 73,336; `Api.kt` 81,951 → 82,066). Ladder: md5 settled for 0/7; size+markers for 7/7. All seven files confirmed at their exact post sizes.
- Backups & restore: pre-state recoverable from `/mnt/work/kit/r4cur` (md5-matched to the R2/R3 states before the edits) plus `/mnt/work/audit2/backups/r4pre_*` where the window allowed the copy.

## 5. Deploy-gate reminders (unchanged)

- Backend (verify-human endpoint + register gate) and web checkpoint must be live **before** a mobile build carrying R1–R4 goes out.
- CI-side follow-ups on a build machine: honesty suite (name pins + must-abort shapes), Android assemble, iOS build + both walks.

## 6. Files in this drop

- `ROUND4-VERIFICATION-RECOVERY-AND-WALK-SYNC-2026-11-18.md` (this file)
- `round4-applied-files/` — byte-exact applied copies:
- `Api.kt` — android/app/src/main/java/com/grapsee/grapmail/data/Api.kt
- `SignUpViewModel.kt` — android/app/src/main/java/com/grapsee/grapmail/logic/SignUpViewModel.kt
- `MainActivity.kt` — android/app/src/main/java/com/grapsee/grapmail/MainActivity.kt
- `android-walk.sh` — scripts/android-walk.sh
- `APIClient.swift` — ios/GrapMail/APIClient.swift
- `SignUpModel.swift` — ios/GrapMail/SignUpModel.swift
- `ScreenshotWalkUITests.swift` — ios/GrapMailUITests/ScreenshotWalkUITests.swift
- `round4-applied-files/README.md` — index with md5s.
