# GrapMail Human Verification — Implementation Kit

Companion to the research report (`research_report_20261117_grapmail-human-verification.md` / `.html`).
This kit turns the research recommendation into concrete, apply-ready code for **Web, Android, iOS, and the backend gate**.

## The design in one sentence

Every surface shows a **real reCAPTCHA challenge** (web: the v2 checkbox — the exact "I'm not a robot" + image-challenge UX; Android/iOS: the same challenge hosted on a checkpoint page inside a WebView — the vendor-documented way to show a *visible* challenge on mobile), and **no path completes without a server-side verification record**. The Skip button is deleted, and the backend refuses to advance without proof.

## What was audited (the fake spots being replaced)

| Platform | Current fake implementation | This kit replaces it with |
|---|---|---|
| Android | `ui/screens/HumanVerifyScreen.kt` — decorative `PuzzleSlider`, **"Skip for now"** → `onDone()` advances the flow, `SignUpViewModel.markHumanVerified()` sets a local boolean only | `HumanVerifyScreen.kt` (replacement): real challenge in a WebView, no Skip, Continue gated on server verdict |
| iOS | `FinishScreens.swift` — same fake card, **"Skip for now"** → `onContinueOnboarding`, `markHumanVerified()` local-only | `HumanVerifyView` replacement: WKWebView challenge, no Skip, Continue gated on server verdict |
| Web | `SignUpForm.tsx` — zero bot protection; submits straight to `API_BASE/auth/register` | `RecaptchaV2` checkbox + `verify-human` gate before register |

## Files in this kit

| File | Purpose | Destination in repo |
|---|---|---|
| `web/01_RecaptchaV2.tsx` | reCAPTCHA v2 checkbox component (explicit render, expired/error handling, reset) | `Grapsee All Time/src/components/RecaptchaV2.tsx` *(new)* |
| `web/02_humanVerification.store.ts` | Single-use verification records (issue / consume-exactly-once) | `Grapsee All Time/src/app/lib/humanVerification.ts` *(new)* |
| `web/03_verify-human.route.ts` | Next.js route: verifies token with Google siteverify, issues `verificationId` | `Grapsee All Time/src/app/api/auth/verify-human/route.ts` *(new)* |
| `web/04_verify-human-page.tsx` | Hosted checkpoint page used by the mobile WebViews (also works in a browser) | `Grapsee All Time/src/app/verify/human/page.tsx` *(new)* |
| `web/PATCHES-web.md` | Exact edits to `SignUpForm.tsx` + env keys | — |
| `android/01_HumanVerifyScreen.kt` | Replacement screen: real challenge in a WebView, no Skip, Continue gated on server verdict | `android/app/src/main/java/com/grapsee/grapmail/ui/screens/HumanVerifyScreen.kt` *(replace)* |
| `android/02_RecaptchaManager.kt` | *Optional P2:* native Enterprise SDK client (silent score token, layered signal) | `.../logic/RecaptchaManager.kt` *(new, optional)* |
| `android/PATCHES-android.md` | Exact edits: `SignUpViewModel`, call sites, `build.gradle.kts` (optional dep) | — |
| `ios/01_HumanVerifyView.swift` | Replacement view: WKWebView challenge, no Skip, Continue gated on server verdict | `ios/GrapMail/FinishScreens.swift` *(replace the `HumanVerifyView` struct)* |
| `ios/02_RecaptchaService.swift` | *Optional P2:* native SDK wrapper (silent token) | `ios/GrapMail/RecaptchaService.swift` *(new, optional)* |
| `ios/PATCHES-ios.md` | Exact edits: skip removal, `SignUpModel`, `RootView.swift` call site, `project.yml` | — |
| `backend/verify-human.server.ts` | Reference server gate: siteverify + single-use verification record + register gating | port into the API service behind `API_BASE` |
| `backend/README-backend.md` | Wiring notes for the real backend | — |
| `IMPLEMENTATION-GUIDE.md` | The full how-to: architecture, protocol, per-platform steps, keys, testing, rollout | — |

## Apply order (important)

1. **Backend first** (the gate). Without it, UI changes are cosmetic — the old fake screen was "cosmetically strict" too.
2. **Web** (v2 checkbox + gating).
3. **Deploy the checkpoint page** (`/verify/human`) publicly — before any mobile build that points at it.
4. **Android & iOS** (WebView checkpoint, Skip removal, gating).
5. Run the test checklist in `IMPLEMENTATION-GUIDE.md` § Testing.

## Keys you need

From the reCAPTCHA admin console / Google Cloud (details in `IMPLEMENTATION-GUIDE.md` § Keys & Console Setup):

- **v2 site key + secret** (checkbox) — used by the web widget, the checkpoint page, and the mobile WebViews. Verified via `siteverify`.
- *Optional (P2):* **Enterprise site key** for the Android/iOS mobile SDKs if you layer silent scoring on top.

## What "unskippable" means in this kit

- No Skip control exists anywhere (removed in the provided replacement files).
- `Continue` / `onDone` is only reachable after the server returns `success: true` for a fresh token.
- The backend refuses account creation / onboarding completion without a valid, **single-use** verification record.
- Tokens are consumed exactly once; expired or duplicate tokens fail closed (403 `human-verification-required`).
