# GrapMail mobile — data-flow audit (generated from the code, not from memory)

Purpose: the evidence behind every store answer. Google Play "Data safety"
form entries and the Apple privacy manifest (`ios/GrapMail/PrivacyInfo.xcprivacy`)
must describe the same facts this file lists, and those facts are each traced
to the file that produces them. If code and this document ever disagree, the
code wins and this document is a bug.

Scope: `grapmail-mobile/android` (Kotlin, `data/Api.kt`) and
`grapmail-mobile/ios` (Swift, `GrapMail/APIClient.swift`, `MailAPI.swift`).
Both clients were verified call-for-call equal by `scripts/api-parity.py`
(31 calls / 45 types / 200+ shared fields, plus the profile and mail sectors);
the endpoint inventory below is that surface, grouped by what leaves the
device on each call.

## The one network peer

Every request goes to the app's own origin — `grapsee.com` under HTTPS
(the account API at `/api/grapsee/auth/*`, the mailbox through the same-origin
proxy at `/api/grapsee/grapmail/*`). There is no third-party endpoint, no ad
network, no analytics SDK, no crash reporter, and no web socket in either app.
The only permission either platform asks for is the network itself
(`android.permission.INTERNET`; iOS asks for nothing — no photo library, no
contacts, no notifications).

## What leaves the device, per call group

| Group | Endpoints (from the clients' own code) | Data carried |
|---|---|---|
| Sign-up | `POST /auth/account/create`, `GET /auth/grapmail/check` | name (+ first/last), chosen handle/domain, password (to derive the credential), terms acceptance, birthday (age gate), `x-device-id` |
| Sign-in & 2FA | `POST /auth/account/signin`, `POST /auth/account/twofactor` | address or identifier, password, SMS code / backup code, `x-device-id`, user-agent header |
| Recovery | `POST /auth/account/reset/start`, `.../reset/confirm`, `POST /auth/account/find`, `POST /auth/phone/start`, `POST /auth/phone/verify` | identifier or recovery address, SMS code, new password, phone number, `x-device-id` |
| Session | `GET /auth/account/sessions`, `DELETE /auth/account/sessions/:id`, `PATCH` sign-out-everywhere | bearer token only; the answers (device labels, times) come back |
| Profile | `GET/PATCH /auth/account/profile`, `POST /auth/account/avatar`, `.../avatar/remove` | sparse patches of: name, birthday, gender, signature, density, locale, blocked/safe senders, notes, tasks, phone, recovery email, 2FA toggle; avatar bytes (≤256 KB, the route's ceiling) |
| Security settings | `POST /auth/account/twofactor` (enable/disable), `POST /auth/account/verify-password`, `GET /auth/account/activity` | current password for the proof calls; activity is a read |
| Mailbox read | `GET …/inbox`, `…/me`, `…/stats`, `GET …/thread/:id`, `GET …/message/:id`, `GET …/attachments`, labels lists | bearer + box/filter/query params; opening a message marks it read server-side (the route's own side effect) |
| Mailbox write | `POST …/send`, `POST …/draft`, `PATCH …/message/:id/:action`, label assignments, `PUT/DELETE …/labels(/:id)`, `POST …/aliases`, `DELETE …/aliases/:address` | recipients (to/cc/bcc), subject, body text/HTML, attachments (upload), In-Reply-To; `e2eEnvelope` instead of the body when the sender sealed the message — the server stores its notice, never the plaintext |
| Screener | `GET/PUT …/screener`, `…/screener/enabled`, `PUT …/screener/decision` | sender address + the user's standing decision |
| E2E directory | `GET/PUT/DELETE …/e2e/keys`, `POST …/e2e/plan`, `POST …/e2e/lookup` | PUBLIC keys only (the server refuses any body carrying private material, `grapmail-e2e.ts:151-169`, and these clients never build one); plan/lookup send recipient addresses to ask which have keys |
| Unsubscribe | none — the action is a browser handoff (`ACTION_VIEW` / `UIApplication.open`) to the sender's own URL, not a call by GrapMail | nothing leaves via the app beyond what the person's browser does visibly |

Never sent anywhere by either client: the E2E passphrase, the decrypted
private key halves (in-memory only, cleared on lock/sign-out/process death —
`E2eViewModel.kt`, `E2eModel.swift`), the session token as a query parameter,
and any message body that was encrypted for send.

## What is stored on the device

| Store | Content | Sensitive? |
|---|---|---|
| Android: `EncryptedSharedPreferences`-style DataStore + Android Keystore AES-GCM key (`data/SessionStore.kt`) | session token as ciphertext, address, display name, expiry | token never on disk in plaintext |
| iOS: Keychain (`kSecClassGenericPassword`, `AfterFirstUnlockThisDeviceOnly`, `SessionStore.swift`) | session token, `x-device-id` UUID | token never in UserDefaults |
| iOS: UserDefaults | address, display name, expiry — launch-time session decision only | nothing secret; declared CA92.1 in the manifest |
| Mail cache (`MailStore`, both platforms, memory + app-private disk cache) | fetched pages, opened bodies, labels, stats — until sign-out or 401, which clear it whole | message content on device storage; cleared across accounts deliberately |
| E2E identity file (app-support dir; iOS `.completeFileProtection`, Android Keystore-free because the record is already passphrase-encrypted) | `grapmail-e2e-protected-key/1` container: public half in clear, private half AES-GCM under PBKDF2-SHA256 @ 600,000 | ciphertext at rest; plaintext halves exist only in process memory |
| Saved attachments (Documents / app files) | only files the user chose to save | user-initiated |

## Route coverage, audited in both directions

Forward (every mobile screen maps to a route) is enforced by
`scripts/mail-honesty.py` and `scripts/api-parity.py`. The reverse audit was
run against the backend's own mounted surfaces:

- `grapmail*.ts` route files mount 31 method+path surfaces. 28 are client
  mailbox/auth surface and every one is referenced by the phone clients —
  confirmed by grep of `data/Api.kt` + `MailAPI.swift` + `APIClient.swift`,
  with api-parity proving both platforms call the same set, not just one.
- The three exceptions are not client surface by design: `POST /ingest` is
  the MTA's queue-drain entry (a server call, never a UI call), and
  `POST /transactional` + `GET /transactional/senders` carry their own
  rationale in `grapmail-transactional.ts` — a service credential for
  Grapsee products' own sends, whose caller is the web backend's
  `src/app/lib/mailOutbox.ts`, "a separate concern with a separate caller".
  A phone app must not hold that credential, so no screen was built for it.
- `auth-account-profile.ts`: all 14 mounted routes are referenced by both
  clients (one grep hit, `c.get('sessionId')`, is a Hono context read, not
  a route).

Conclusion: there is no mail or account endpoint the product exposes to
users that either app fails to reach, and no app call reaches anything the
backend does not mount.

## Account deletion (Play requirement status — honest)

There is **no account-delete endpoint on either backend store**, and both
apps' privacy screens say so in the web's own words ("that path is not built")
instead of wiring a fake button — pinned by `scripts/mail-honesty.py` §25.
Play's in-app deletion requirement therefore cannot be satisfied by the
mobile apps alone; it requires the backend route first (`grapsee-backend`).
Sign-out-everywhere and per-session revoke exist and are reachable. This
document is where that gap is stated so no store form can claim otherwise;
closing it is a backend task, tracked by the same §25 pin flipping to a real
route when it lands.

## Store answers derived from this file

- **Play Data safety:** collected = account identifiers (name, email address,
  phone), credential data (password hashes never leave in plaintext form — the
  password itself travels only over HTTPS to the auth routes), messages &
  contacts (the mailbox product), device ID (anti-abuse header), avatar photo.
  Not shared with any third party; not used for advertising/analytics; the
  deletion answer must mirror the section above until the route exists.
- **Apple privacy labels:** "Data Used to Track You" — none; "Data Linked to
  You" — the table above; "Data Not Collected" — location, browsing history,
  contacts outside mail addresses, diagnostics beyond what HTTP logs hold.
- **Required-reason APIs:** UserDefaults CA92.1, file-picker security scope
  C617.1 — exactly the two the binary uses, per `PrivacyInfo.xcprivacy`.
