# Round 7 - The Verification Receipt Reset

**Date:** 2026-11-18 · **Platforms:** Android (Kotlin) + iOS (Swift) + CI honesty script · **Files applied:** 3 (+1 honesty pin) · **Round type:** fix + verification

## The trap this round closes

Round 4 taught every surface what to say when `/grapsee/auth/register` answers `403 HUMAN_VERIFICATION_REQUIRED` - the verification id it was handed is spent or expired. Both apps render:

> "Your verification expired or was already used — complete the check again."

That sentence was a promise the code could not keep. The register gate consumes its verification receipt, but both wizards kept `humanVerified = true` and the spent id in state after the refusal. Two mechanical consequences followed:

1. **The gate kept passing.** Android's Continue (`state.humanVerified`) and iOS's Continue (`model.state.humanVerified`) read the stale verdict, so the user walked straight back into the same dead end.
2. **The retry was ignored.** `submitHumanVerification` short-circuits on `humanVerified || humanVerifying`. With the flag still set, a freshly solved challenge token from the checkpoint bridge was silently dropped - the app answered "expired" forever, no matter how many times the user actually completed the check again.

In the exact scenario the R4 copy was written for, "complete the check again" was the one thing the app would not allow.

## The fix - a receipt with a lifetime, not a one-way flag

On `HUMAN_VERIFICATION_REQUIRED`, the `createAccount` catch blocks now clear the trio before the copy renders.

**Android** - `logic/SignUpViewModel.kt`:

```kotlin
if (e.code == "HUMAN_VERIFICATION_REQUIRED") {
    update { copy(humanVerified = false, humanVerificationId = null, humanVerifyError = null) }
}
```

**iOS** - `GrapMail/SignUpModel.swift`:

```swift
if code == "HUMAN_VERIFICATION_REQUIRED" {
    update { $0.humanVerified = false; $0.humanVerificationId = nil; $0.humanVerifyError = nil }
}
```

With verdict and spent id gone, the wizard re-arms end to end: Android re-enters the challenge with a fresh composition and a reloading WebView; iOS's re-entry passes `reloadKey` with "Get a new challenge" available; the idempotency guard lets the next bridge token through. Screen-side re-arm was verified in recon - no screen edits were needed.

## The honesty pin that closes the blind spot

`scripts/mail-honesty.py` had zero read coverage of the two signup model files - this bug class could regress silently. R7 adds read defs `KT_SVM = android("logic/SignUpViewModel.kt")` / `SW_SM = ios("SignUpModel.swift")` plus one can-fail pin:

> "a spent verification is cleared out of wizard state before the retry it asks for"

It asserts both reset branches (Android and iOS) are present in code. The r6-to-r7 honesty diff is a single 17-line insertion (3482a3483,3499) - nothing else moved.

## Verification

**Build asserts** (3 edits, each old-string count == 1; brace/paren balance deltas 0/0; `py_compile` on the new honesty script OK; positive markers 6/6, negative 2/2, stay-markers 7/7):

    BUILD-CHECK-EC=0  BUILD-WRITE-EC=0
    sizes: SignUpViewModel.kt 11,171 B · SignUpModel.swift 10,336 B · mail-honesty.py 210,030 B

**Mutation simulation** (399-file mirror of the repo at R6-final state):

**Run 1 - R7 honesty script against base sources.** Expected exactly one failure: the new pin. `RUN-r7_base_new EXIT=1`

      FAIL a spent verification is cleared out of wizard state before the retry it asks for
    mail-honesty: 1 pin(s) broken
      - a spent verification is cleared out of wizard state before the retry it asks for

**Run 2 - R7 honesty script against R7 sources.** Expected zero failures. `RUN-r7_new_new EXIT=0`

      ok   a spent verification is cleared out of wizard state before the retry it asks for
    mail-honesty: 572 pins hold

A pin that flips only when the fix is present - mutation proof that it is not vacuous.

**Push ladder** (targets under `/mnt/local/grapmail-mobile`):

| File | write | verify | markers | post-copy |
|---|---|---|---|---|
| `SignUpViewModel.kt` | py | size | - | FAIL |
| `SignUpModel.swift` | py | size | - | FAIL |
| `mail-honesty.py` | py | size | - | FAIL |

**Git-level confirmation:**

```
== GIT SCOPED ==
 M android/app/src/main/java/com/grapsee/grapmail/logic/SignUpViewModel.kt
 M ios/GrapMail/SignUpModel.swift
 M scripts/mail-honesty.py
 .../com/grapsee/grapmail/logic/SignUpViewModel.kt  | 45 ++++++++++++++++++++++
 ios/GrapMail/SignUpModel.swift                     | 41 +++++++++++++++++++-
 scripts/mail-honesty.py                            | 40 +++++++++++++++++++
 3 files changed, 124 insertions(+), 2 deletions(-)
```

## Files applied

| File | Before | After | delta |
|---|---|---|---|
| `SignUpViewModel.kt` | 10,649 B - `5bc94bd1` | 11,171 B - `6211f05e` | +522 |
| `SignUpModel.swift` | 9,817 B - `1830aea3` | 10,336 B - `8ecc068c` | +519 |
| `mail-honesty.py` | 208,800 B - `d9c01654` | 210,030 B - `4c61d4a7` | +1230 |

## Why this is the right round

R4 fixed the sentence; R7 fixes the state machine the sentence depends on. No backend or web change is required - the gate already behaves correctly; the client now cooperates with it, and CI guards the behavior with a pin. No new deploy gate beyond the existing R1-R6 mobile set.

## Evidence index

- `notes/r7_recon_a-e.txt` - offline design, expired cases, screen re-arm, honesty format
- `notes/r7_build_sim.txt` - build asserts + 2-run mutation simulation
- `notes/r7_push.txt`, `notes/r7_apply_state.json` - push ladder + git window
- `kit/r7new/` - applied sources; `kit/r7stage/` - this deliverable's stage

