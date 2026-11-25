# GrapMail — Device & Security Center API (as built)

Companion to `round8-spec.md` §6, which stays the source of truth. This page
records exactly what the backend answers today, where it differs from the
spec's example, and the rules the three clients can rely on. The machine-
readable form is `security-overview.schema.json` in this folder.

Implementation: `grapsee-backend/services/shared/src/routes/auth-security-center.ts`
(mounted by the auth service at `/auth`; the gateway serves it as
`/grapsee/auth/...`). Tests: `services/shared/src/__tests__/security-center.node-test.ts`
and `account-security-state.node-test.ts`.

---

## 1. Common rules

| Rule | Detail |
|---|---|
| Auth | `Authorization: Bearer <access token>` on every call. |
| Current device | Apps send `x-device-id` (installation id). The website's `/api/grapsee` proxy sends the browser's `gm_did` id in the same header. A browser is also recognised as current by its own session. |
| Caching | Every answer has `Cache-Control: no-store`. |
| Time | ISO 8601 UTC with `Z`. |
| IDs | Opaque strings. `td_<uuid>` trusted device, `ss_<16 hex>` browser/app session group, `ev_<uuid>` security event, `al_<uuid>` account-activity row. |
| Enums | Open. Unknown `type` / `id` / `platform` / `level` / `state` must render the generic fallback, never fail to decode. |
| Copy | All user-facing text is the client's. Only `device.label`, `event.deviceLabel` and `location.label` are written by the server (English names and places). |
| Errors | `{ "success": false, "error": "<English>", "code": "<CODE>", "message": "<English>" }` |

## 2. What the server never sends

* **No network address of any kind.** `ipMasked` is optional in the spec ("or
  omit"); this server always omits it, on devices and on events. The event
  sheet's "Approximate network" row therefore never shows. Clients already
  treat the field as optional, so no client change is needed.
* **No map data and no coordinates.** Location is text only.
* **No city yet.** The only location source is the country the edge
  (Cloudflare `CF-IPCountry`) reports at sign-in. So `location.label` is the
  English country name (`"Bangladesh"`), `city` and `region` are `null`, and
  `country` is the ISO code. `location` is `null` when no country was recorded.
  The client shows "Location unavailable" in that case (§3.2).

## 3. `GET /grapsee/auth/security/overview`

Query: `eventsLimit` 1–100 (default 30), `eventsCursor` (from
`eventsNextCursor`), `days` 1–180 (default 30). Out-of-range values are clamped.

```json
{
  "success": true,
  "generatedAt": "2026-11-24T07:00:00.000Z",
  "account": { "address": "olivier@grapmail.com", "displayName": "Olivier", "hasPassword": true, "passwordChangeRequired": false },
  "score": { "version": 1, "value": 55, "level": "needs_attention", "earnedPoints": 55, "possiblePoints": 100 },
  "checklist": [
    { "id": "two_step", "state": "done", "points": 25, "earned": 25, "action": "security.two_step" },
    { "id": "passkey", "state": "todo", "points": 20, "earned": 0, "action": "security.passkeys", "meta": { "count": 0 } }
  ],
  "devices": [
    {
      "id": "td_5b1c…", "kind": "app", "platform": "android", "formFactor": "phone",
      "label": "Pixel 8", "browser": null, "os": "Android 15",
      "current": true, "trusted": true, "attestation": "play_integrity",
      "location": { "label": "Bangladesh", "city": null, "region": null, "country": "BD" },
      "firstSeenAt": "2026-03-12T09:14:00.000Z", "lastActiveAt": "2026-11-24T06:59:10.000Z",
      "activeSessionCount": 1, "removal": null, "notMeAllowed": false
    },
    {
      "id": "ss_9f2e…", "kind": "browser", "platform": "windows", "formFactor": "desktop",
      "label": "Chrome on Windows", "browser": "Chrome", "os": "Windows",
      "current": false, "trusted": false, "attestation": null, "location": null,
      "firstSeenAt": "2026-11-20T15:02:00.000Z", "lastActiveAt": "2026-11-23T18:40:00.000Z",
      "activeSessionCount": 1, "removal": { "via": "session", "id": "sess_8c1a…" }, "notMeAllowed": true
    }
  ],
  "events": [
    {
      "id": "ev_31d0…", "type": "signin_new_device", "occurredAt": "2026-11-20T15:02:00.000Z",
      "deviceId": "ss_9f2e…", "deviceLabel": "Chrome on Windows",
      "location": { "label": "Bangladesh", "city": null, "region": null, "country": "BD" },
      "risk": "medium", "action": "ALERT", "factor": null,
      "acknowledged": false, "reported": false, "notMeAllowed": true
    }
  ],
  "eventsNextCursor": null
}
```

### Field notes

| Field | Rule |
|---|---|
| `checklist` | Always all 8 ids in §4.2 table order. Points: two_step 25, passkey 20, recovery_codes 15, recovery_email 10, second_device 10, password_health 10, recovery_phone 5, alerts_reviewed 5. `not_applicable` (password_health on an account with no password) earns 0 and leaves `possiblePoints`. |
| `score.value` | `round(100 × earned / possible)`, half up; 100 when `possible` is 0. Levels: 0–59 `needs_attention`, 60–84 `good`, 85–100 `strong`. |
| `password_health` | `todo` only when the sign-in password was found in a public breach list (`meta.status: "breached"`); otherwise `done` with `meta.status: "ok"`, including before any check has run. |
| `devices` | One per live trusted device (`td_`), plus one per group of signed-in sessions not tied to a trusted device (`ss_`). Ended sessions and revoked devices are not listed. Current first, then most recently active. |
| `label` | App: the device name. Browser: `"{browser} on {os}"`. `"Unknown device"` appears only for an old session that recorded no browser details at all. |
| `removal` | `null` on the current device. `trusted_device` → `POST /grapsee/auth/devices/{id}/revoke`; `session` → `DELETE /grapsee/auth/account/sessions?id={id}` (the newest session of the group; refetch afterwards). |
| `events[].type` | §6.2 mapping. `otp.*` and attestation events are not listed. Unmapped catalog entries pass through as a flat open value. |
| `events[].risk` | From the risk score: 0–29 low, 30–59 medium, ≥ 60 high. Account-activity (`al_`) rows are always `low` with `action: null`. |
| `events[].deviceId` | The overview id when that device is still listed, else `null` (`deviceLabel` still filled when known). |
| `events[].notMeAllowed` | `true` for sign-in, password, passkey, 2-step, recovery and device-added events that did not come from the current device and are not already reported. |
| `account.passwordChangeRequired` | Also returned by `GET /grapsee/auth/me` (top level and inside `user`) and `GET /grapsee/auth/account/profile` (inside `profile`), so the mailbox banner can show without loading the overview. |

Errors: `401 UNAUTHORIZED`, `429 RATE_LIMITED` (60 per 10 minutes per user, with `Retry-After`).

## 4. `POST /grapsee/auth/security/not-me`

Body (JSON only; a form or plain-text body is refused with `415`): exactly one
of `deviceId` / `eventId`, plus `proof` on the retry after a step-up.

```json
{ "deviceId": "ss_9f2e…" }
{ "eventId": "ev_31d0…" }
{ "deviceId": "td_5b1c…", "proof": "pf2.…" }
```

What the server does, in order:

1. **Repeat check.** The same target reported within 24 hours → `200` with
   `alreadyReported: true`; nothing else happens (no sign-outs, no new event,
   no second alert or email). This works even before migration 0052, because
   the report remembers its target.
2. **Find the target** among this account's own devices and events only.
   Another account's id, a malformed id or a device that is already gone →
   `404 NOT_FOUND`.
3. **Never the device in use** → `409 CANNOT_TARGET_CURRENT`.
4. **Step-up:** a session younger than 15 minutes that is not a trusted app
   device (judged by the session's own push registration, never by the
   `x-device-id` header) needs `proof` → otherwise `403 STEP_UP_REQUIRED`. Get it
   from `POST /grapsee/auth/account/verify-password` with
   `{ "password": "…", "purpose": "secure-account" }`; the answer carries
   `proof`. A proof is single use, lasts 10 minutes and works for this purpose
   only. (Accounts that sign in only with Google/Apple and have no password get
   the proof without a password, as for account deletion.)
5. Revoke the reported device (a trusted device loses its trust, sessions and
   push registration; a session group's sessions end).
6. Sign out **every other session** except the caller's, and kill the old
   refresh token.
7. Mark the account "password change required" (accounts with a password only).
8. Record `security.not_me.reported` (action `REVOKE`), mark the disputed event
   answered and reported, alert the account's other devices, and email the
   security notice to the inbox and the verified recovery email.

`200`:

```json
{
  "success": true,
  "result": {
    "reportId": "ev_77aa…",
    "target": { "kind": "device", "id": "ss_9f2e…" },
    "revokedDeviceIds": ["ss_9f2e…"],
    "signedOutSessions": 3,
    "passwordChangeRequired": true,
    "alreadyReported": false
  },
  "nextSteps": ["change_password", "review_recovery"]
}
```

`nextSteps` is `["review_recovery"]` for an account with no password. On a
repeat, `revokedDeviceIds` is `[]`, `signedOutSessions` is `0` and `reportId`
names the original report.

| `code` | HTTP | When |
|---|---|---|
| `TARGET_REQUIRED` | 400 | neither id sent |
| `TARGET_AMBIGUOUS` | 400 | both sent |
| `NOT_FOUND` | 404 | not this account's, malformed, or already gone |
| `CANNOT_TARGET_CURRENT` | 409 | the target is the caller's own device |
| `STEP_UP_REQUIRED` | 403 | step 4 |
| `RATE_LIMITED` | 429 | more than 5 reports per hour per user (every attempt counts) |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | form or text body |

The "change password required" flag clears on any successful password change
(Settings, `PATCH /grapsee/auth/account/profile`) or reset (email link, reset
request, trusted-device recovery, recovery code).

## 5. Device recognition ("is this a new device?")

* Apps send their installation id as `x-device-id`; the website's proxy sends
  the browser's `gm_did` id the same way. After a sign-in has fully passed the
  password (and 2-step verification when it is on), the id is stored on the new
  session (`user_sessions.device_id`, migration 0053).
* A later password sign-in from an id this account has seen before (an earlier
  session, or one of its trusted devices) is **not** reported as a new device.
* If the 0053 column does not exist yet, or the database errors, the id is
  ignored and the old rule decides: a new IP **and** a different browser family
  means a new-device alert. Sign-in never fails because of this.
* The id never signs anyone in, never skips the password or 2-step
  verification, and never relaxes a rate limit. A stolen id buys only a quieter
  alert. "This wasn't me" forgets the reported device's id, so it is a stranger
  again at its next sign-in.

## 6. Database changes (not yet applied anywhere)

| Migration | Adds |
|---|---|
| 0051 | `users.password_change_required_at` |
| 0052 | `users.password_breached_at`, `security_events.reported_at`, `security_events.target_ref` (+ index) |
| 0053 | `user_sessions.device_id`, `user_sessions.country` (+ index) |

All columns are nullable and additive. None is declared in `db/schema.ts` on
purpose; the code checks they exist before using them, so the code can be
deployed before the migrations and simply runs with the features dark.
