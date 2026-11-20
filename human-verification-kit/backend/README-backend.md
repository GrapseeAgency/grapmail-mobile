# Backend wiring — the unskippable gate

The UI changes in this kit are cosmetic without the server side. This is the
component that turns "a checkbox on the screen" into a real gate: no account is
created, and no onboarding step completes, without a verification record that
the server itself issued and consumed **exactly once**.

## Where this lives

The GrapMail API service behind `API_BASE` — the same service that serves
`/auth/register`. The web app reaches it through its `/api/grapsee` proxy; the
Android/iOS apps reach it directly.

## Endpoints to add / change

### POST /auth/verify-human  (new)

```
Request:  { "token": "<reCAPTCHA response token>" }
200:      { "success": true, "verificationId": "<opaque single-use id>" }
400:      { "success": false, "error": "verification-failed", "codes": [...] }
400/502:  missing-response / vendor_unreachable (retryable)
```

Verifies `token` with Google (siteverify, `RECAPTCHA_V2_SECRET`) and issues a
single-use record valid for 10 minutes. Reference code: `verify-human.server.ts`.

### POST /auth/register  (existing — add the gate)

```
Request:  { email, password, ..., "humanVerificationId": "<from verify-human>" }
```

Before any durable side effect, `consumeVerification(humanVerificationId)` must
return true — otherwise **403 `human-verification-required`**. Cheap validations
(e.g. duplicate email → 409) run first so a taken email does not burn a solved
challenge; the consume itself must be atomic (Redis `GETDEL`, or a conditional
`UPDATE ... WHERE consumed_at IS NULL`).

If onboarding completion is a separate call, give it the same gate.

### Gateway paths (as deployed)

The live gateway (`https://grapsee.com/api`) mounts auth endpoints under
`/grapsee/auth/…` (e.g. `/grapsee/auth/register`). Ship the new endpoint as
`POST /grapsee/auth/verify-human` so every client reaches it consistently:

| Client | Request path |
|---|---|
| Android | `$AUTH/verify-human` where `$AUTH = $BASE/grapsee/auth`, `$BASE = https://grapsee.com/api` |
| iOS | `base + "/grapsee/auth/verify-human"` (same `base` as `/grapsee/auth/register`) |
| Web (browser) | `/api/grapsee/auth/verify-human` → Next proxy → backend `/grapsee/auth/verify-human` |

## Environment

- `RECAPTCHA_V2_SECRET` — server secret of the v2 checkbox key (also used by the
  web app's `web/03_verify-human.route.ts` preview route).
- Optional P2 (layered signals): Play Integrity / App Attest / Enterprise keys.

## How each client reaches it

| Client | Call path |
|---|---|
| Web (browser) | `POST ${API_BASE}/auth/verify-human` → `/api/grapsee` proxy → this service |
| Web checkpoint page (mobile WebViews preview / standalone) | same-origin `POST /api/auth/verify-human` (the `web/03` route in the Next app) |
| Android | `Api.verifyHuman(token)` in the data layer (see `android/PATCHES-android.md`) |
| iOS | via `APIClient` (see `ios/PATCHES-ios.md`) |

## Acceptance tests (all must hold)

1. `register` without `humanVerificationId` → **403**
2. `register` with a replayed id → **403** (second consume fails)
3. `register` with an id older than 10 min → **403**
4. Fresh solve → `verify-human` returns id → `register` succeeds **once**
5. Same reCAPTCHA token sent to `verify-human` twice → second fails
   (`timeout-or-duplicate`)

```bash
# 1) exchange a solved token
curl -sX POST "$API_BASE/auth/verify-human" \
  -H 'Content-Type: application/json' \
  -d '{"token":"<response token>"}'
# → {"success":true,"verificationId":"..."}

# 2) register with it — succeeds once, 403 on replay
curl -sX POST "$API_BASE/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"email":"a@b.co","password":"...","humanVerificationId":"<id>"}'
```

## Observability

- Log `verificationId` issue and consume with outcome (ok / expired / replayed).
- Count failures by siteverify code; alert on spikes of `timeout-or-duplicate`
  and `hostname-mismatch` (replay attempts / origin misconfiguration).

## Optional P2 layering

Attach Play Integrity verdicts (Android) and App Attest assertions (iOS) as
additional fields on sensitive calls, and use them as risk signals alongside —
never instead of — the captcha gate. See the research report's recommendations.
