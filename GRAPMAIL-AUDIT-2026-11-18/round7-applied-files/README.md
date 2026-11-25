# Round 7 applied files - The Verification Receipt Reset

1. `SignUpViewModel.kt` -> `android/app/src/main/java/com/grapsee/grapmail/logic/SignUpViewModel.kt`
2. `SignUpModel.swift` -> `ios/GrapMail/SignUpModel.swift`
3. `mail-honesty.py` -> `scripts/mail-honesty.py`

Changes: on `HUMAN_VERIFICATION_REQUIRED`, both models clear `humanVerified` /
`humanVerificationId` / `humanVerifyError` so the wizard re-arms and the next
solved challenge is accepted; the honesty script gains read coverage of both
signup models plus one can-fail pin.
