# Round 3 — Human verification moved before account creation ("verify-before-register")

**Date:** 2026-11-18
**Scope:** Android `MainActivity.kt` + iOS `RootView.swift` — 3 navigation edits each (the wizard order fix).
**Status:** applied to both source trees this session; byte-exact files in `round3-applied-files/`.

## Why this round exists

Round 1 replaced the challenge screens; Round 2 taught `createAccount` to carry the server-issued
`humanVerificationId`. This round fixes the last integration piece: the **order**.

- The backend register gate consumes the single-use id **at registration** and fails closed:
  a missing/expired/replayed id answers `403 human-verification-required` before any durable side effect.
- The **web** signup already does it in this order: verify first, then register with the id.
- The **mobile wizards** did the opposite — they created the account at the Terms step and showed the
  challenge afterwards. Against the deployed gate, every **Create Account** tap returns the 403 and the
  funnel has no way forward.
- The kit's patch notes said no changes were needed in `MainActivity` / `RootView`; they had reconciled
  the screen *signature*, not the flow *order*. This round is that reconciliation.

## The new flow

    before:  Password -> Terms [Create Account -> auto sign-in] -> Challenge -> onboarding / HOME
    after:   Password -> Challenge -> Terms [Create Account (id consumed) -> auto sign-in] -> onboarding / HOME

Nothing else changes: same screens, same buttons, same fail-closed semantics — "Continue" on the
challenge is enabled only from the server verdict, and the wizard cleanup now runs on the Create
Account step (the first point where the account actually exists).

## Edits (byte-exact files attached)

### Android — `MainActivity.kt`
1. `STEP_PASSWORD.onNext` -> `nav.navigate(Routes.HUMAN_VERIFY)` (was `STEP_TERMS`).
2. `HUMAN_VERIFY.onDone` -> `nav.navigate(Routes.STEP_TERMS) { popUpTo(Routes.HUMAN_VERIFY) { inclusive = true } }`
   (was the onboarding/HOME finalize).
3. `STEP_TERMS.onCreated` (signed-in branch) -> onboarding/HOME finalize via `OnboardingGate`
   (was `nav.navigate(Routes.HUMAN_VERIFY)`).

### iOS — `RootView.swift`
1. `stepPassword` onNext -> `path.append(.humanVerify)` (was `.stepTerms`).
2. `humanVerify` onDone -> `path.append(.stepTerms)` (was `.onboarding(...)`).
3. `stepTerms` onCreated -> `guard !OnboardingGate.isDone else { return enterMailbox() }` +
   `path.append(.onboarding(address: name:))` (was `path.append(.humanVerify)`); `.humanVerify` added
   to the history-cleanup set so Back can't replay a spent wizard.

## Verification (this session, against the live mount)

    MainActivity.kt  76512 B -> 76890 B   md5 3c42c510beb29b7daa4e55fdf1c4db8d
    RootView.swift   51913 B -> 52076 B   md5 36a709ef86007441fdae0e1635cda9b8

Ladder + marker log:

```
EXPECT MainActivity md5=3c42c510beb29b7daa4e55fdf1c4db8d size=76890
EXPECT RootView md5=36a709ef86007441fdae0e1635cda9b8 size=52076
MainActivity: SIZE-OK (76890)
RootView: SIZE-OK (52076)
MK MAIN-1 OK (=1)
MK MAIN-2 OK (=1)
MK MAIN-3 OK (=1)
MK MAIN-4 OK (=0)
MK MAIN-5 OK (=1)
MK MAIN-6 OK (=1)
MK MAIN-7 OK (=1)
MK RV-1 OK (=1)
MK RV-2 OK (=1)
MK RV-3 OK (=1)
MK RV-4 OK (=0)
MK RV-5 OK (=1)
MK RV-6 OK (=1)
MK RV-7 OK (=3)
MK RV-8 OK (=0)
POSTCOPY MainActivity.kt md5-identical
POSTCOPY RootView.swift md5-identical
== git scoped ==
 M android/app/src/main/java/com/grapsee/grapmail/MainActivity.kt
 M ios/GrapMail/RootView.swift
GATE: PASS
```

## Deploy order (unchanged from Rounds 1–2)

Backend + web first (verify-human endpoint, register gate, checkpoint page) -> then ship the mobile
builds carrying R1+R2+R3. Builds **without** this round must not ship after the gate: their Create
Account call fails closed by design.

## Follow-ups noted (not part of this round)

- Map `human-verification-required` on the Terms step to an explicit "verification expired — re-verify"
  affordance (today the raw message shows; Back + re-solve works).
- The kit patch docs still say "no changes needed" at these two call sites; they can carry an update
  note pointing here if desired.

*Delivered alongside Rounds 1–2 in this folder: `ROUND1-HUMAN-VERIFICATION-APPLIED-2026-11-18.md`,
`ROUND2-REGISTER-GATE-WIRING-2026-11-18.md`, and the matching `roundN-applied-files/` trees.*
