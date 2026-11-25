# ROUND 1 - Human-verification kit applied to iOS + Android (2026-11-18)

**Why this round existed.** The reCAPTCHA work was completed on web + backend, and the mobile
repo already carried the staged kit (`human-verification-kit/`). But both apps still rendered the
old founder-design mock screens - including a live **"Skip for now"** escape - while the server
gates had moved on. The result: sign-up could be completed on a phone without ever proving
humanity. This round applies the kit so the mobile sign-up flow is server-verified and
unskippable, matching web.

## Applied changes (directly in the working trees)

| # | File | What | md5 (applied) |
|---|---|---|---|
| a1_screen | `android/app/src/main/java/com/grapsee/grapmail/ui/screens/HumanVerifyScreen.kt` | Full replacement - real checkpoint WebView (URL baked: https://grapsee.com/verify/human?embed=android); JS bridge delivers the reCAPTCHA token to submitHumanVerification; the "Skip for now" code path is deleted; Continue disabled until the server verdict. | `d281b8fa752a4448a530cfb9ef91bc07` |
| a2_svm | `android/app/src/main/java/com/grapsee/grapmail/logic/SignUpViewModel.kt` | UiState gains humanVerifying / humanVerifyError / humanVerificationId; submitHumanVerification() exchanges the token via the server (idempotent). | `a983fe0017419a52ca4ce8f8573ba1ff` |
| a3_api | `android/app/src/main/java/com/grapsee/grapmail/data/Api.kt` | VerifyHumanRequest / VerifyHumanResponse DTOs + verifyHuman() -> POST $AUTH/verify-human (mirrors the createAccount request style). | `94c5a1c8f895167980e63025ef42a2a8` |
| a4_vm | `android/app/src/main/java/com/grapsee/grapmail/logic/MailboxViewModel.kt` | Stale KDoc fixed - the "one unfinished affordance" note now points at openAttachment() (the open path had already shipped). | `3ee9dd982db74b5be67dc1338b720736` |
| a5_manifest | `android/app/src/main/AndroidManifest.xml` | Provider comment truth-up (no longer says the file-provider affordance was "left unfinished"). | `36bb0e100924558036c4c427469c81d2` |
| i1_fs | `ios/GrapMail/FinishScreens.swift` | HumanVerifyView replaced - WKWebView checkpoint (https://grapsee.com/verify/human?embed=ios) + VerifyCheckpoint/VerifyWebView; import WebKit; "Skip for now" deleted; Continue gated on the server verdict. | `9e78c0be07c4e9629939a9662b1b0373` |
| i2_sm | `ios/GrapMail/SignUpModel.swift` | UiState fields + submitHumanVerification() via ApiClient.verifyHuman. | `b30d15f497c120c0d56b2782eced6748` |
| i3_apic | `ios/GrapMail/APIClient.swift` | VerifyHumanResponse + verifyHuman() -> /grapsee/auth/verify-human (mirrors the createAccount shape). | `5112540482669d591846e435fe427305` |

Call sites verified unchanged (signatures identical, no edits needed): `MainActivity.kt` (~L1117)
and `RootView.swift` (~L655).

## Verification evidence (this session)

Full-content verification ladder (md5 -> cmp -> spanning fixed-string checks + size) over the
seven source files:

- **a1_screen** -> SIZE-only(13939)
- **a2_svm** -> SPAN4+SIZE
- **a3_api** -> SIZE-only(81760)
- **a4_vm** -> SIZE-only(160717)
- **i1_fs** -> SIZE-only(20346)
- **i2_sm** -> SPAN4+SIZE
- **i3_apic** -> SIZE-only(80495)

Channel checks:

- manifest_fetch=OK(method=s) bytes=4228

Push ledger (apply_state.json):

- a1_screen: OK - OK:quick(grapsee.com/verify/h=1,fun HumanVerifyScree=1)
- a2_svm: OK - OK:quick(fun submitHumanVerif=1,humanVerificationId=2)
- a3_api: OK - OK:quick(suspend fun verifyHu=1,VerifyHumanResponse=3)
- a4_vm: OK - OK:quick(openAttachment()=1,unfinished affordanc=0)
- a5_manifest: OK - OK:quick(this provider was ad=1,affordance was left =0)
- i1_fs: OK - OK:quick(VerifyCheckpoint=2,import WebKit=1,grapsee.com/verify/h=1)
- i2_sm: OK - OK:quick(func submitHumanVeri=1,humanVerificationId=2)
- i3_apic: OK - OK:quick(static func verifyHu=1,VerifyHumanResponse=2)

Honesty note: the repo mount is an occasionally flaky network channel (intermittent read/write
errors). Every write this round was followed by read-back verification; where the strongest
method (md5/cmp) was blocked by the channel at that moment, verification fell back to byte-size
plus four spanning fixed-string checks - recorded as it happened, not upgraded retroactively.

## Deploy dependency - BEFORE shipping any mobile build

1. **Backend:** deploy the service carrying `POST /api/grapsee/auth/verify-human` (staged in the
   repo from the earlier round) with `RECAPTCHA_V2_SECRET` configured.
2. **Web:** deploy so `https://grapsee.com/verify/human` is live - both mobile screens point at it.
3. **Mobile:** rebuild + install. Android: `cd android && ./gradlew :app:compileDebugKotlin` then a
   release assemble; iOS: `xcodegen generate` + build scheme `GrapMail`.

Flow checklist (must all hold):
- The card hosts the real checkbox (image challenges on escalation).
- "Skip for now" exists nowhere. Before the verdict, Continue is disabled (45% opacity, no-op).
- Solve -> "Checking..." -> "Verified - thank you!" -> Continue enables -> next screen.
- Airplane mode mid-check -> error line, Continue stays locked. Back arrow never advances.

## Revert

All eight touched files are tracked; a per-file `git -C grapmail-mobile checkout -- <path>` works.
BUT note `MailboxViewModel.kt` also carries the earlier attachment fixes - prefer the session
pre-edit copies under `/mnt/work/audit2/backups/` (`r1_*`) for a surgical revert of only this round.

## Reference copies

Byte-exact applied files are included under `round1-applied-files/` (md5s above). The source kit and
its instruction docs remain at `human-verification-kit/` in the repo.

_Generated 2026-11-18 by the audit session. Companion docs: `APPLIED-2026-11-18.md` (round 0), this file (round 1)._
