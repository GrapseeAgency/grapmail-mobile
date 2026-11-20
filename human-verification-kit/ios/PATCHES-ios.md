# PATCHES — iOS (grapmail-mobile, XcodeGen project)

Real reCAPTCHA challenge in a WKWebView + server-gated Continue. The "Skip for
now" exit is deleted — no bypass path remains. Anchors verified against the
tree as of 2026-11-17.

## Files to copy / replace

| Kit file | Action | Destination |
|---|---|---|
| `ios/01_HumanVerifyView.swift` | **replace the old `HumanVerifyView`** inside `FinishScreens.swift` (below) — the file also defines the checkpoint URL + WKWebView host | — |
| `ios/02_RecaptchaService.swift` | optional (P2) | `ios/GrapMail/RecaptchaService.swift` (new) |

`project.yml` uses folder sources (`GrapMail/`, `NotificationShared/`), so any
new `.swift` file dropped into `ios/GrapMail/` is auto-included on the next
`xcodegen generate` — no project edits needed.

## Edit 1 — `FinishScreens.swift`: swap the view

Structure verified: `import SwiftUI` (L1) · old `struct HumanVerifyView` (L6,
with its doc comment at L3–5) · closing brace at L129 · then
`// MARK: - Account Created (A) — founder design verbatim` (L131).

1. **Delete** everything from the `/// Human verification — founder design
   verbatim:` comment through the closing brace immediately before that MARK
   (i.e., the whole old view, ≈L3–L129).
2. **Paste** the entire contents of `ios/01_HumanVerifyView.swift` in its place
   (it defines `HumanVerifyView`, `VerifyCheckpoint`, `VerifyWebView`; its own
   import lines are harmless duplicates).
3. Add `import WebKit` next to `import SwiftUI`.
4. Set your deployed checkpoint page in `VerifyCheckpoint.url`:
   `https://YOUR-WEB-APP-DOMAIN/verify/human?embed=ios` (page from `web/04`).

No edit needed at the call site: `RootView.swift` (≈L652–661, `case .humanVerify`)
keeps working because the initializer signature is unchanged
(`HumanVerifyView(model:onBack:onDone:)`). `onDone` now fires **only** from the
server-gated Continue button — the new view never calls it from a skip path
because none exists.

Cleanup: the old view was the only iOS reference to `PuzzleSlider`; after the
replacement, `PuzzleSlider.swift` is unused (keep or delete — safe either way).

## Edit 2 — `SignUpModel.swift`

Verified internals: `struct UiState` (L13) · `var humanVerified = false` (L37) ·
`@Published private(set) var state = UiState()` (L40) ·
`func update(_ transform: (inout UiState) -> Void)` (L44) ·
`func markHumanVerified()` (L184, end of class).

### a) UiState — extend after `var humanVerified = false`

```swift
        var humanVerified = false
        // Human-verification gate (2026-11-17): server-confirmed only.
        var humanVerifying = false
        var humanVerifyError: String?
        var humanVerificationId: String?
    }
```

### b) New method — add right after `markHumanVerified()` (end of class)

```swift
    /// Human verification (2026-11-17 overhaul): exchange the reCAPTCHA token
    /// from the checkpoint page's JS bridge for a server verdict. Continue
    /// stays disabled until {success:true}. Idempotent: duplicates ignored.
    func submitHumanVerification(token: String) {
        guard !token.isEmpty, !state.humanVerified, !state.humanVerifying else { return }
        Task {
            update { $0.humanVerifying = true; $0.humanVerifyError = nil }
            do {
                let resp = try await ApiClient.verifyHuman(token: token)
                if resp.success {
                    update {
                        $0.humanVerifying = false
                        $0.humanVerified = true
                        $0.humanVerificationId = resp.verificationId
                    }
                } else {
                    update { $0.humanVerifying = false; $0.humanVerifyError = "Verification failed — please try again." }
                }
            } catch {
                update { $0.humanVerifying = false; $0.humanVerifyError = "Network error — please try again." }
            }
        }
    }
```

This mirrors the model's existing pattern (`loadSuggestions`):
`Task { … try await ApiClient… ; update { $0… } }`.

`markHumanVerified()` can stay or be deleted (check references — the new view
never calls it).

## Edit 3 — `APIClient.swift`

Verified: response types live in this file — `CreateResponse` (L177),
`CheckResponse` (L213); calls go through
`request<T>(_ path, method:, body:, bearer:)` which builds `"\(base)\(path)"`.

### a) Response type — add near `CreateResponse` / `CheckResponse`

```swift
struct VerifyHumanResponse: Codable {
    let success: Bool
    let verificationId: String?
}
```

### b) Endpoint — add right after `createAccount` (copy its exact call shape)

```swift
    static func verifyHuman(token: String) async throws -> VerifyHumanResponse {
        struct Body: Codable { let token: String }
        return try await request(
            "/grapsee/auth/verify-human",
            method: "POST",
            body: encoder.encode(Body(token: token))
        )
    }
```

(Mirror the encoder/argument pattern used by `createAccount` — if it wraps the
body differently in your current tree, follow that exactly.)

Server endpoint: `POST https://grapsee.com/api/grapsee/auth/verify-human` — same
gateway mount as `/grapsee/auth/register`. See `backend/README-backend.md`.

## Optional P2 — native score layer

1. Add the reCAPTCHA Enterprise mobile SDK via SPM
   (`github.com/GoogleCloudPlatform/recaptcha-enterprise-mobile-sdk`); enable
   the App Attest capability for production builds.
2. Drop in `ios/02_RecaptchaService.swift`; set the Enterprise site key.
3. `await RecaptchaService.shared.warmup()` early in the app lifecycle; attach
   tokens to sensitive calls as an extra signal only.

## Build & test

- `xcodegen generate` (picks up any new files), then build in Xcode
  (scheme `GrapMail`).
- Walk the flow:
  1. The card hosts the real checkbox (image challenges on escalation).
  2. No "Skip for now" anywhere.
  3. Solve → "Checking…" → "Verified — thank you!" → **Continue enables** →
     next screen.
  4. Before the verdict, Continue is disabled (45% opacity).
  5. Airplane mode mid-check → error text appears, Continue stays locked.
  6. Back chevron only goes back — never forward.
- Server replay/expiry checks: `backend/README-backend.md` acceptance tests.
