# GrapMail — Human Verification Implementation Guide

**Date:** 2026-11-17 · **Companion documents:** `research_report_20261117_grapmail-human-verification.md` (the why — evidence and vendor analysis) and the code kit in this folder (the how).

---

## 0. TL;DR — what you do, and when you're done

1. **Backend** — add the two-endpoint gate (`backend/`). *Do this first; everything else depends on it.*
2. **Web** — drop in the v2 checkbox component + apply `web/PATCHES-web.md`.
3. **Deploy the checkpoint page** (`web/04`) — the challenge page the mobile apps host in a WebView. Must be live BEFORE any mobile build points at it.
4. **Android** — replace the screen + apply `android/PATCHES-android.md`.
5. **iOS** — replace the view + apply `ios/PATCHES-ios.md`.

**Done checklist**

- [ ] Register without a consumed `verificationId` → **403** (fail closed).
- [ ] Replayed / expired id → **403**.
- [ ] Web signup: SUBMIT impossible without solving; body carries `humanVerificationId`.
- [ ] Android & iOS: the card hosts the **real checkbox/image challenge**; "Skip for now" no longer exists anywhere; Continue enables **only** after the server verdict.
- [ ] Branding disclosure line present (web + mobile).

---

## 1. Architecture — one protocol, three surfaces

```
   ┌──────────────┐    ┌───────────────────┐    ┌───────────────────┐
   │ Web (browser)│    │ Android app       │    │ iOS app           │
   │ v2 checkbox  │    │ WebView ⇄ JS      │    │ WebView ⇄ Msg     │
   │ widget       │    │ bridge            │    │ bridge            │
   └──────┬───────┘    │ (checkpoint page) │    │ (checkpoint page) │
          │            └─────────┬─────────┘    └─────────┬─────────┘
          │                      │                        │
          └────────────┬─────────┴────────────┬───────────┘
                       ▼  (single-use reCAPTCHA token, ~2 min)
        POST /grapsee/auth/verify-human  ──── siteverify ───▶  Google
                       │
                       ▼  verificationId (single-use, 10 min)
     register / gate presents the id ── consume exactly once ──▶ account / next step
```

**The one rule: the client never decides.** The server issues a `verificationId`
only after Google confirms the token, and the protected action (account creation
on web; screen advance on mobile) proceeds only after that id is consumed
exactly once. Anything missing, expired, or replayed → `403 human-verification-required`.

**Flow details**

1. *Challenge shown* — Web: the genuine v2 checkbox (escalates to image challenges on risk). Mobile: the same widget on the checkpoint page inside a WebView (Google's documented way to show a visible challenge on mobile).
2. *Solve* — the widget returns a response token (single-use, ~2 min TTL).
   - Web: token → `POST ${API_BASE}/auth/verify-human` (proxy → backend).
   - Android: bridge `GrapMailAndroid.onVerifyToken` → `Api.verifyHuman()`.
   - iOS: bridge `grapmailVerify` → `ApiClient.verifyHuman()`.
3. *Server verdict* — backend calls siteverify, issues the single-use record.
4. *Gate* — web register includes `humanVerificationId`; mobile flips `humanVerified` only from the server response.
5. *Fail closed* — every error path leaves Continue locked / register rejected.

**Why this shape for mobile** (from the research report §F3): native mobile
reCAPTCHA SDKs are frictionless/score-based only — Google documents that the
visual "I'm not a robot" challenge is not available natively on iOS, and shows
visual challenges on mobile **via WebView**. Cloudflare Turnstile is also
WebView-only on mobile, with reported iOS WKWebView instability. Reusing the web
checkpoint page therefore gives *identical visual behavior* on all three
platforms — "exactly like the real one" — with one place to maintain.

---

## 2. The old screens vs. now (audit summary)

| Platform | Before (fake) | After (this kit) |
|---|---|---|
| Android | `HumanVerifyScreen.kt`: decorative `PuzzleSlider`; local `markHumanVerified()`; **"Skip for now" → `onDone()` advanced the flow** | WebView challenge; `submitHumanVerification()` → server verdict; Continue gated; skip deleted |
| iOS | `FinishScreens.swift`: same fake card; **"Skip for now" → `onContinueOnboarding`** | WKWebView challenge; `submitHumanVerification(token:)`; Continue gated; skip deleted |
| Web | No bot protection at all; register accepted anyone | v2 checkbox on step 4; `humanVerificationId` required at register |
| Backend | `/auth/register` gate-free | **403** without a consumed single-use verification record |

---

## 3. Keys & console setup

1. In the **reCAPTCHA admin console** (or Google Cloud console → reCAPTCHA), create a key of type **reCAPTCHA v2 → "I'm not a robot" Checkbox**. It issues:
   - **Site key** (public) — used by the web widget, the checkpoint page, and both mobile WebViews (they load the same page).
   - **Secret key** (server) — used by siteverify wherever `/grapsee/auth/verify-human` runs.
2. **Allowed domains**: add your production and staging web hosts (the widget validates the page origin; mobile WebViews count as the web origin serving the checkpoint page).
3. **Environment**
   - Web: `NEXT_PUBLIC_RECAPTCHA_V2_SITE_KEY` (browser) and `RECAPTCHA_V2_SECRET` (server; keep it only on the API service / Next server env, never in the client bundle).
   - Android/iOS: **no v2 keys ship in the apps** (the challenge lives on the web page). Only the optional Enterprise site key if you add the P2 layer.
4. **Local development**: use Google's v2 test keys (widget always passes, sends the test token):
   - site `6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI`, secret `6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe` — re-check the current values in the reCAPTCHA FAQ's developer section.
5. **Branding**: you may hide the badge only if you visibly show "This site is protected by reCAPTCHA and the Google Privacy Policy and Terms of Service apply." — the disclosure line is already shipped in the kit's components. Mobile apps do not require a badge (optional "This app is protected by reCAPTCHA…" line — also included).

---

## 4. Apply steps (detailed)

### Step 1 — Backend gate (FIRST)

Follow `backend/README-backend.md`: add `POST /grapsee/auth/verify-human`
(siteverify → issue single-use id) and gate `/grapsee/auth/register` on
`consumeVerification(humanVerificationId)`. Use `backend/verify-human.server.ts`
as the reference (swap the in-memory map for Redis `GETDEL` or an atomic SQL
update in production). Run the five acceptance tests at the bottom of that
README — they define "unskippable".

### Step 2 — Web

1. Copy `web/01_RecaptchaV2.tsx` → `src/components/RecaptchaV2.tsx`, and
   `web/04_verify-human-page.tsx` → `src/app/verify/human/page.tsx`.
2. Apply `web/PATCHES-web.md` to `src/app/(auth)/signup/SignUpForm.tsx`
   (widget on step 4, exchange handler, submit guard, `humanVerificationId` in
   the register body, 403 → re-solve branch).
3. Set the env keys (dev: test keys; prod: real keys + allowed domains).
4. Deploy. Verify: step 4 renders the checkbox; SUBMIT blocked before solve;
   solving → account created; replay → 403 and the widget resets.

### Step 3 — Deploy the checkpoint page

The page is already part of step 2's deployment (`/verify/human`). Confirm:

- HTTPS works (reCAPTCHA requirement) and the origin is in the key's allowed list.
- Standalone browser hit shows the challenge and "Verified — you can continue."
- `?embed=android` / `?embed=ios` just changes chrome (transparent background, no card) — the widget is identical.

### Step 4 — Android

1. Replace `ui/screens/HumanVerifyScreen.kt` with `android/01_HumanVerifyScreen.kt`; set `HUMAN_VERIFY_URL` to your deployment.
2. Apply `android/PATCHES-android.md`: `data/Api.kt` (`verifyHuman` + DTOs),
   `logic/SignUpViewModel.kt` (UiState fields + `submitHumanVerification`).
3. Optional P2: add the Enterprise SDK dependency + `RecaptchaManager.kt`.
4. Build, install, run the walkthrough in the patch doc.

### Step 5 — iOS

1. Replace the old `HumanVerifyView` in `FinishScreens.swift` with
   `ios/01_HumanVerifyView.swift`; add `import WebKit`; set `VerifyCheckpoint.url`.
2. Apply `ios/PATCHES-ios.md`: `SignUpModel.swift` (fields + method),
   `APIClient.swift` (`VerifyHumanResponse` + `verifyHuman`).
3. Optional P2: SPM package + `RecaptchaService.swift`.
4. `xcodegen generate`, build, run the walkthrough.

---

## 5. Testing & acceptance

**Server (authoritative — run first)** — see `backend/README-backend.md`:
no id → 403 · replayed id → 403 · expired id → 403 · fresh id → success once ·
same token to verify-human twice → second fails (`timeout-or-duplicate`).

**Client walkthroughs** — one list, three surfaces:

1. Challenge appears (checkbox; image challenge when Google decides).
2. No skip affordance exists (visual + code grep: `Skip for now`, `skip`).
3. Solve → verified state → Continue/Register proceeds.
4. Locked state before verdict (web: submit guard; mobile: disabled Continue).
5. Offline mid-verification → error message; no state advance; retry works.
6. Fresh token per submission (don't cache ids across attempts; token TTL ~2 min, id TTL 10 min — test both expiries).

**Cross-surface parity** — the visual challenge, the copy, and the
fail-closed behavior should be identical on web, Android, and iOS.

Tip: develop with the v2 **test keys** (always-pass) so automated walkthroughs
don't consume real challenges; test with real keys manually before release.

---

## 6. Rollout & monitoring

- **Stages:** staging → internal dogfood → gradual → 100%. The gate is
  fail-closed, so watch for false 403s right after each stage bump.
- **Metrics:** solve rate, siteverify error-code mix, 403 rate on
  register/continue, time-to-verify, drop-off at the challenge.
- **Alerts:** spikes in `timeout-or-duplicate` (replay attempts or a client
  reusing tokens) and `hostname-mismatch` (origin misconfiguration).
- **Kill-switch consideration:** if Google is unreachable, verify-human returns
  a retryable 502 (client shows retry) — do not silently pass users.

---

## 7. Accessibility, privacy, branding

- **WCAG 1.1.1** requires a sensory alternative when a visual CAPTCHA blocks
  access. Note the industry reality (report §F8): audio reCAPTCHA has been
  widely broken (W3C-published research shows ~90% crack rates via speech
  recognition) and vendors increasingly replace audio with "accessibility
  coding" flows (e.g., hCaptcha's accessibility cookie, WCAG 2.2 AA claim).
  Recommendations: (a) most users see only a checkbox — keep friction there;
  (b) provide a documented support contact for users who cannot complete the
  challenge; (c) if strict AA procurement matters, evaluate an accessibility-
  cookie-capable vendor — the same checkpoint-page architecture swaps in.
- **Privacy:** mention Google reCAPTCHA processing in your privacy policy
  (it processes device/usage signals to score risk).
- **Branding:** the disclosure line is shipped in all four components; keep it
  visible if you hide the badge (web); mobile needs no badge.

---

## 8. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Widget blank on web | wrong site key; origin not allowed; ad-blocker | check key + allowed domains; test page with test keys |
| `invalid-input-response` on every solve | secret mismatch between widget and siteverify | align `RECAPTCHA_V2_SECRET` with the site key's secret |
| `timeout-or-duplicate` | token reused / older than ~2 min | fetch a fresh token at submit; reset the widget after failures |
| Mobile WebView shows nothing | checkpoint page not deployed / not HTTPS | deploy `web/04`, verify URL in a phone browser first |
| Bridge never fires | name mismatch | Android interface must be `GrapMailAndroid`; iOS handler `grapmailVerify` — both are used by the shipped page |
| 403 right after solving | id expired (>10 min) or already consumed | re-solve; check server storage atomicy (Redis `GETDEL` / conditional UPDATE) |
| Works in dev, fails in prod | prod origin missing from allowed domains | add it in the console |

---

## 9. Kit file map

| Path | Role |
|---|---|
| `README.md` | Kit overview + apply order |
| `web/01_RecaptchaV2.tsx` | v2 checkbox React component (drop-in) |
| `web/02_humanVerification.store.ts` | Single-use record store (reference; preview route) |
| `web/03_verify-human.route.ts` | Next-local verify route (reference/preview) |
| `web/04_verify-human-page.tsx` | **Checkpoint page** — the challenge inside both mobile WebViews |
| `web/PATCHES-web.md` | Exact edits for `SignUpForm.tsx` + env |
| `android/01_HumanVerifyScreen.kt` | Replacement screen (no skip; gated Continue) |
| `android/02_RecaptchaManager.kt` | Optional P2 native score layer |
| `android/PATCHES-android.md` | Exact edits (Api.kt, SignUpViewModel.kt) |
| `ios/01_HumanVerifyView.swift` | Replacement view (no skip; gated Continue; bridge) |
| `ios/02_RecaptchaService.swift` | Optional P2 native score layer |
| `ios/PATCHES-ios.md` | Exact edits (FinishScreens, SignUpModel, APIClient) |
| `backend/verify-human.server.ts` | Reference server gate (siteverify + single-use) |
| `backend/README-backend.md` | Wiring + acceptance tests + gateway paths |

---

## 10. Notes & next steps

- **Economics:** real CAPTCHAs are not silver bullets (farms solve at ~$1–5 per
  1,000; report §F7). This kit raises attacker cost and removes the free bypass;
  the P2 layer (Play Integrity / App Attest / Enterprise scores) and ordinary
  rate limiting are the next hardening steps — never instead of this gate.
- **iOS platform constraint:** the native checkbox limitation is Google's
  documented behavior, not a shortcut in this kit — the WebView path is
  Google's own documented way to show the visual challenge on mobile.
- **One maintenance point:** the challenge lives on the checkpoint page — update
  it once (e.g., branding changes on 2026-04-02 when Google drops badge links)
  and all three platforms inherit the change.
