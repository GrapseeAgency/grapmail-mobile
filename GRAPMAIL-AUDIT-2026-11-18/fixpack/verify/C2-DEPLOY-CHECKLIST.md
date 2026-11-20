# C2 — Deploy checklist (founder actions)

Everything here is environment/credentials/deployment — the code for all of it was verified **already present in the repo** during the audit. Only the files route (B1/B2) needed new code.

## Backend

- [ ] Deploy current `grapsee-backend` main. Verified in-repo fixes it carries:
  - search operators (`graymail-search.ts`: `from:` `to:` `subject:` `has:` `is:` `before:` `after:` `in:` `label:`)
  - scheduled send (`grapmail-scheduled.ts` + `worker.ts` pickup/promotion)
  - snooze handling (box-terms predicates)
  - phone-OTP honesty (throws → `{ok:false}` → route answers `502 SMS_DELIVERY_UNAVAILABLE`)
  - login-envelope allowlist (`toPublicUser` — `passwordHash`/`refreshToken`/`otp` excluded; test green)
  - notification preview routes (credential-gated)
- [ ] Mount fixpack B1 + B2 (files list-all) and deploy auth-service.
- [ ] Run `fixpack/backend/B3-VERIFY-api.md` and keep the output.

## Environment / credentials

- [ ] `SMS_PROVIDER` + provider credentials → live phone OTP. Until then `phone/start` correctly answers `502 SMS_DELIVERY_UNAVAILABLE` (that is the designed honest failure — do not "fix" it by reverting to a 500).
- [ ] FCM service account for push send (Android).
- [ ] APNs key for push send (iOS; the app already declares `aps-environment`).
- [ ] Any remaining secrets referenced through the config module's `resolveSecret` call sites.

## Clients

- [ ] Android: apply fixpack A, build, ship the **release** APK (debug builds of pre-fix commits are what made the app "feel slow" — see C1).
- [ ] iOS: nothing to patch for this audit; install the signed build as usual.

## After deploy — spot checks

- [ ] `GET /grapmail/files` returns the account's attachments (B3 §2).
- [ ] Search with `from:`/`has:` operators returns filtered results (B3 §4).
- [ ] Schedule a message "send later", confirm it appears under the scheduled list and cancels back to Drafts (B3 §4 + app).
- [ ] Login response contains no `passwordHash`/`refreshToken` fields (B3 §5).
