# GrapMail mobile — data-flow audit (generated from the code, not from memory)

Purpose: the evidence behind every store answer. Google Play "Data safety"
form entries and the Apple privacy manifest (`ios/GrapMail/PrivacyInfo.xcprivacy`)
must describe the same facts this file lists, and those facts are each traced
to the file that produces them. If code and this document ever disagree, the
code wins and this document is a bug.

Scope: `grapmail-mobile/android` (Kotlin, `data/Api.kt`) and
`grapmail-mobile/ios` (Swift, `GrapMail/APIClient.swift`, `MailAPI.swift`).
Both clients were verified call-for-call equal by `scripts/api-parity.py`
(33 calls / 46 types / 207 shared fields, plus the profile and mail sectors);
the endpoint inventory below is that surface, grouped by what leaves the
device on each call.

## The one network peer

Every request goes to the app's own origin — `grapsee.com` under HTTPS
(the account API at `/api/grapsee/auth/*`, the mailbox through the same-origin
proxy at `/api/grapsee/grapmail/*`). There is no third-party endpoint, no ad
network, no analytics SDK, no crash reporter, and no web socket in either app.
The only permission either platform asks for is the network itself
(`android.permission.INTERNET`; iOS asks for no photo library, no contacts) —
plus notifications, asked for on a tap in the settings row of each platform,
never at first launch (Android `POST_NOTIFICATIONS`, iOS
`UNUserNotificationCenter.requestAuthorization`).

## What leaves the device, per call group

| Group | Endpoints (from the clients' own code) | Data carried |
|---|---|---|
| Sign-up | `POST /grapsee/auth/register`, `GET /grapsee/auth/grapmail/check` | name (+ first/last), chosen handle/domain, password (to derive the credential), terms acceptance, birthday (age gate), `x-device-id` |
| Sign-in & 2FA | `POST /grapsee/auth/login`, `POST /grapsee/auth/account/twofactor` | address or identifier, password, SMS code / backup code, `x-device-id`, user-agent header |
| Recovery | `POST /grapsee/auth/forgot-password`, `POST /grapsee/auth/reset-password`, `POST /grapsee/auth/account/find`, `POST /grapsee/auth/phone/start`, `POST /grapsee/auth/phone/verify` | identifier or recovery address, SMS code, new password, phone number, `x-device-id` |
| Session | `GET /grapsee/auth/account/sessions`, `DELETE /grapsee/auth/account/sessions/:id`, `PATCH` sign-out-everywhere | bearer token only; the answers (device labels, times) come back |
| Profile | `GET/PATCH /grapsee/auth/account/profile`, `POST /grapsee/auth/account/avatar`, `.../avatar/remove` | sparse patches of: name, birthday, gender, signature, density, locale, blocked/safe senders, notes, tasks, phone, recovery email, 2FA toggle; avatar bytes (≤256 KB, the route's ceiling) |
| Security settings | `POST /grapsee/auth/account/twofactor` (enable/disable), `POST /grapsee/auth/account/verify-password`, `GET /grapsee/auth/account/activity` | current password for the proof calls; activity is a read |
| Mailbox read | `GET …/inbox`, `…/me`, `…/stats`, `GET …/thread/:id`, `GET …/message/:id`, `GET …/message/:id/body`, `GET …/attachments`, labels lists | bearer + box/filter/query params; opening a message marks it read server-side (the route's own side effect). `…/message/:id/body` is the reader's RENDER body — the web app sanitizes the message's HTML part with its own `sanitizeEmailHtml` profile before answering, so the raw `bodyHtml` the row carries is never rendered natively; `?images=1` is the images-gate opt-in that unblocks remote images, and nothing on the sender's server is fetched before it |
| Mailbox write | `POST …/send`, `POST …/draft`, `PATCH …/message/:id/:action`, label assignments, `PUT/DELETE …/labels(/:id)`, `POST …/aliases`, `DELETE …/aliases/:address` | recipients (to/cc/bcc), subject, body text/HTML, attachments (upload), In-Reply-To; `e2eEnvelope` instead of the body when the sender sealed the message — the server stores its notice, never the plaintext |
| Screener | `GET/PUT …/screener`, `…/screener/enabled`, `PUT …/screener/decision` | sender address + the user's standing decision |
| E2E directory | `GET/PUT/DELETE …/e2e/keys`, `POST …/e2e/plan`, `POST …/e2e/lookup` | PUBLIC keys only (the server refuses any body carrying private material, `grapmail-e2e.ts:151-169`, and these clients never build one); plan/lookup send recipient addresses to ask which have keys |
| Unsubscribe | none — the action is a browser handoff (`ACTION_VIEW` / `UIApplication.open`) to the sender's own URL, not a call by GrapMail | nothing leaves via the app beyond what the person's browser does visibly |
| Push registration | `POST /grapsee/grapmail/push/register` `{token, platform, deviceId}` → `201 {success, device}`; `DELETE` the same path `{token}` → `204` (idempotent — an already-gone device is a success, `grapmail-push.ts`) | the platform push token, `platform`, the same `x-device-id`, bearer — nothing else; the token is sent only to this backend and never logged (Android parks its copy in the app's own private prefs until a session exists) |

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

There is **no account-delete endpoint on either backend store** as of this
document's last live probe — the route is defined in the backend repo
(`services/shared/src/routes/auth-account-delete.ts`, mounted at
`/auth/account/delete`, `POST` and `DELETE /account`) but not yet deployed to
it, so until that deploy answers, every delete attempt fails honestly with the
server's own refusal — never a client-invented success. The route the mobile
clients are now wired to: `POST /grapsee/auth/account/delete`, bearer +
`{password}`, `200 {success, deleted, message}` on completion, `403
WRONG_PASSWORD` on a wrong password, `429` when the 5-attempts/15-minutes
limiter refuses. The web's privacy page still says
"that path is not built"; the mobile apps are the first surfaces wired to it.

What the client does with it (`DeleteAccountScreen.kt` on Android;
`DeleteAccountView` in `MailAccountViews.swift` on iOS): an explanation of
exactly what deletion removes —
the mailbox, every message, the aliases, the GrapMail address itself,
irreversibly — a password proof sent to the delete route itself (no separate
verify-password ticket: the destructive call carries the credential, so a
replayed proof cannot delete an account on its own), and on a `200`
confirmation the same local teardown a sign-out runs (session, mailbox cache,
security profile cache, notifications, push registration record), landing on
the welcome screen. A wrong password is `403` and says so; a not-yet-deployed
route is the server's `404`, shown as the refusal it is.

Sign-out-everywhere and per-session revoke exist and are reachable. This
section stays where the store form's deletion answer is derived from, and the
§25 pin flips to the real route when the deploy lands.

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

## New-mail notifications: what the alert is evidence of

Two wake paths, one evidence rule. The server can wake the app through its
push provider (the registration contract is the next section), and
`BGAppRefreshTask` / WorkManager remain the fallback when no wake arrives —
but both land in the same authenticated read of `GET /stats`, so a new-mail
alert is not a delivery signal; it is a report of a read the device made
itself.

| Step | Where | What it does |
| --- | --- | --- |
| Schedule | iOS `MailPoll.scheduleNext()` (`BGAppRefreshTaskRequest`), Android `GrapMailApp` (`WorkManager` unique periodic, 30 min floor, connected) | asks the system for a future window; the system owns when it actually runs |
| Read | `GET /grapmail/stats` → `inbox.unread` | the same call the mailbox badge already uses — no second source of truth |
| Compare | stored previous count (`UserDefaults` / `SharedPreferences`) | the notification's "N new" is the difference between two reads this device made |
| Say | `UNUserNotificationCenter` / channel `grapmail-new-mail` | "3 new messages · 12 unread in the inbox", after checking the permission |

**When it stays silent** — and each of these is a lie it would otherwise tell:

- *signed out* — there is no mailbox to report on;
- *the read refused* — nothing was seen, and "no new mail" is a claim about an
  answer the server did not give;
- *first read ever* — there is no previous number, so no difference exists to
  name;
- *the count did not rise* — nothing arrived that this device does not know;
- *the permission is not held* — checked before posting, and the Settings row
  reports the real state rather than a remembered one.

**Interval honesty.** 30 minutes is WorkManager's minimum and
`BGAppRefreshTask`'s request is a *hint* the system may delay further for
battery and usage patterns. No screen says "every 15 minutes" and no string
promises a minute at all; the channel description says what actually happens.
The notification never says "delivered" either — the relay's queue is not the
recipient's inbox, and §39 pins that word out of both notification builders.

## Push: the device-token registration contract

The backend's push provider is the one component that may wake this app while
it is not running. The client's side of the contract is narrow, and every step
is visible in code — iOS below; Android's loop is the subsection after it:

| Step | Where | What it does |
| --- | --- | --- |
| Ask the system | iOS `NotificationRegistration.reconcile()` — at launch, on sign-in (`gmSessionChanged`), after the settings row grants permission | `registerForRemoteNotifications()`, only when a session exists and the permission is already held — the system's real answer, never a remembered one |
| Receive the token | `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)` | the opaque per-install token, kept in memory only: never persisted, never logged |
| Register with the backend | `POST /grapsee/grapmail/push/register` `{token, platform: "ios", deviceId}` with the session bearer (`APIClient.registerPush`) | binds the token to the signed-in account and to the `x-device-id` this install already carries; a refused write is logged, never shown as a working notification |
| Wake → read | `application(_:didReceiveRemoteNotification:fetchCompletionHandler:)` | the payload is a hint, never trusted mailbox data: the app runs the same `MailPoll.runOnce()` the fallback poll runs, and only that read's answer is announced |
| Sign-out | `DELETE /grapsee/grapmail/push/register` `{token}` (`APIClient.unregisterPush`), issued by `SessionStore.clear()` while the bearer is still in the Keychain | the server stops waking the device; the local teardown stays deferred so a sign-in completing in the meantime keeps its registration |
| Refusal | `didFailToRegisterForRemoteNotificationsWithError` → `NotificationRegistration.failed(_:)` | logged honestly; nothing on screen claims push, and the poll still runs |

What the wake can and cannot carry: for ordinary mail the server composes the
alert's sender and subject, which it already holds as mailbox metadata. For
E2EE mail it holds only the envelope, so the alert carries **no content** — no
sender, no subject, no body — and presents generically. No message body, no
E2E key material and no decrypted content ever travels in a push payload, and
the client never treats a payload field as mailbox state either way; every
announcement is recomputed from the authenticated read.

`BGAppRefreshTask` remains the fallback for a device without a push grant (a
denied permission, a refused token, a simulator): the same silence rules and
interval honesty as the table above govern everything it posts.

### Android: FCM (`logic/PushService.kt`, `logic/PushSync.kt`)

The device token is the Firebase cloud-messaging token (`firebase-messaging`
via the Firebase BoM), routed to the same registration route the iOS loop
uses. A build without the client config (`google-services.json`, provisioned
out of the repo) never initializes a Firebase app, and every step below
degrades to a quiet no-op — the feature is absent, not failing, and the
WorkManager poll remains the delivery the person actually gets.

| Step | Where | What it does |
| --- | --- | --- |
| Ask Firebase | `PushSync.register(context)` — at launch with a restored session (`GrapMailApp`), after each completed sign-in (`MainActivity.signedIn`), and on every rotated token (`PushService.onNewToken`) | fetches the FCM token, only when a session exists; no config, no session, or a refused POST each leave the registration where it was |
| Register with the backend | `POST /grapsee/grapmail/push/register` `{token, platform: "android", deviceId}` with the session bearer (`Api.registerPush` → `LaunchApi`, the write path with no authenticator, no redirects and no expiry signal) | binds the token to the signed-in account and this device's id; the token's copy in the app's private `grapmail-push` prefs records what the backend holds, so sign-out can retire exactly that one |
| Wake → read | `PushService.onMessageReceived` | the payload is a signal, never mailbox data: a placeholder notification states only that the backend sent one (no content — see the payload rule above), and a one-time `MailPollWorker` run performs the same authenticated `/stats` read-and-compare the periodic poll makes, replacing the placeholder with the counted summary under the same notification id |
| Sign-out | `PushSync.unregister(context)` → `DELETE /grapsee/grapmail/push/register` `{token}`, issued **before** the session clear while the bearer is still stored, capped at 4s so a slow server never delays the sign-out; the local record is dropped regardless of the answer | the server stops waking the device; an expiry discovered by a 401 has no bearer to authenticate the DELETE with, so it drops the local record and relies on the next sign-in re-registering the same token |
| Account deletion | the same teardown, run after the server confirmed the deletion | nothing on this device survives for an account that no longer exists |

No new permission: the platform's push needs none beyond
`android.permission.POST_NOTIFICATIONS`, which the app already asks for on
its settings row, and both producers of the `grapmail-new-mail` channel check
that grant before posting.
