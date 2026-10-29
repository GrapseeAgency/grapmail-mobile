# GrapMail Mobile — v0.0.0.1

Native GrapMail apps for Android and iOS. The account & authentication sector
of the website, ported 1:1 to each platform's native stack — no cross-platform
framework, no shared UI layer.

- **Android** — Kotlin + Jetpack Compose (`android/`)
- **iOS** — Swift + SwiftUI (`ios/`)

## What v0.0.0.1 contains

The full signup arc plus sign-in, exactly as the founder's designs specify:

1. **Welcome** — "Welcome to GrapMail" / "Your world. Your inbox."
2. **Sign in** — address or phone + password, real 2-step branch when the
   account has the factor on
3. **Step 1/5 Name** — first + last name with live validation
4. **Step 2/5 Basic Info** — optional photo, birthday (13+ enforced), gender chips
5. **Step 3/5 Address** — live-checked suggestions + custom handle against the
   real availability API, with the design's bordered @grapsee.com domain chip
6. **Step 4/5 Password** — strength meter + requirement checklist
7. **Step 5/5 Terms** — scroll-gated agreement, then the real account creation
8. **Human verification** — GrapMail's own slide puzzle (Telegram removed by
   founder's direction). Skippable; the account works either way.
9. **Account created** — both designed variants (what's-next list, and the
   Go-to-Mailbox screen)

## Recovery & security sector

The second design batch, built screen-for-screen on both platforms:

1. **Sign in** — the redesigned card, wordmark header and soft corner blobs
2. **Two-step verification** — six code cells, live expiry countdown, resend
3. **Backup code** — the alternative factor
4. **Forgot password** — identifier in, reset started
5. **Verify recovery code** — the six-digit code the reset texted
6. **Set a new password** — strength meter and the four rules
7. **Password reset successful**
8. **Account recovery pending** — the honest outcome when no code can be sent
9. **Find your GrapMail address** — phone in, masked address out
10. **Address found** — mask on screen, real address on the clipboard
11. **Add or verify your phone number** — step 1 of the phone ribbon
12. **Phone verified** — step 2, with the number linked to the account

Everything hits the same real API as the website: `/auth/account/reset/start`,
`/auth/account/reset/confirm`, `/auth/account/twofactor` (code **and** backup
code), `/auth/phone/start`, `/auth/phone/verify` and a `PATCH` on
`/auth/account/profile` that saves the proven number to the signed-in account.
`/auth/account/find` is the one endpoint this batch added — a rate-limited,
verified-number-only lookup behind the address screen.

### Where this batch deliberately leaves the artwork

- **Backup code cells.** The render draws six cells and says codes are eight
  characters. GrapMail issues ten ("ABCDE-FGHIJ"), so the row is two groups of
  five and the note says ten. A six-cell field could never accept a real code.
- **Two links the renders do not draw.** "Use a backup code" under Resend on the
  factor screen, and "Find your GrapMail address" on the forgot-password
  screen. Both screens otherwise exist with no way to reach them.

## Security sector — the 24-screen batch

Built screen-for-screen on both platforms, from `.local/refs-security/`. The hub
is the only entry point; everything below is reached from it.

1. **Account Security** (hub) — one screen for references 01 and 02, switching
   between "secure" and "one thing left to do" from the live profile
2. **Enable Two-Step Verification** — what the flow will ask for
3. **Confirm Your Password** — verified by `POST /auth/account/verify-password`,
   not compared in the client
4. **Verify Your Phone** — code sent to the number already on the account
5. **Two-Step Verification Enabled**
6. **Generate & Save Backup Codes**
7. **Backup Codes Saved** — the only render of the ten codes, copy-all included
8. **Disable Two-Step Verification?** — password required, as the server demands
9. **Two-Step Verification Disabled**
10. **Change Password** / **Change Your Password** (after suspicious activity)
11. **Password Changed** / **Password Updated Successfully**
12. **Sign Out All Devices** / **Signed Out of All Devices**
13. **Manage Active Sessions** — real device list with this-device marking
14. **Revoke Session Access?** / **Confirm Session Revocation**
15. **Session Revoked Successfully** (ordinary and suspicious variants)
16. **Recent Security Activity** — from events written when they happened
17. **Suspicious Sign-In Alert** — new-device sign-in, with time, device and IP
18. **Secure My Account** — password, sessions, second factor, in that order

### Endpoints this batch added

- `GET  /auth/account/sessions` — live sessions, current one flagged
- `DELETE /auth/account/sessions?id=` — revoke one device (409 for the caller's
  own session, so nobody signs themselves out mid-screen)
- `GET  /auth/account/activity` — audit trail, plus `suspicious` that clears once
  the sign-in has been closed by a revocation, password change or 2FA
- `POST /auth/account/verify-password` — proof for the confirm-password step
- `PATCH /auth/account/profile` — now also records `securityEvents` and stores
  each session at mint time (device label, IP, timestamps)

Verified: `:app:assembleDebug` builds; the endpoints were exercised against a
throwaway store (sign-in records a session, revoking one device leaves the other
signed in, wrong password 401, ten codes generated, current-session revoke 409).
**iOS has not been compiled** — there is no Xcode on this host; the Swift needs a
build on a Mac before it goes to a device.

Everything hits the **real account API** at `https://grapsee.com/api` — the
same endpoints, token format (`st1`), and rules as the website. No mocks, no
stubs, no fake data anywhere.

## Mailbox sector — not built, and why

The mailbox is the next batch. Before any of it is written, one fact has to be
true and currently is not: **the backend that owns mailboxes cannot authenticate
a user.**

`https://api.iqracsc.com/auth/register` answers `500` on the `SELECT` that runs
before any insert, because it asks for `account_type`, `grapmail_handle` and
`grapmail_status` and the production `users` table does not have them.
`POST /auth/login` fails the same way. Applying the migrations that add them
(`0012_grapmail_mailbox.sql`, `0022_users_columns.sql`) is the fix, and it is
currently blocked one level further out: the Neon project has exceeded its quota,
so the database refuses writes outright —

```
PostgresError: Your account or project has exceeded the quota.
code 53000 · failed on: CREATE SCHEMA IF NOT EXISTS "drizzle"
```

Nothing in the migration chain has been applied. Until the quota is cleared and
`/auth/register` returns `200`, a mailbox screen would compile, render and
screenshot while every live call returned `401`. That is the failure mode
`src/app/lib/localAuthFallback.ts:63-67` was written to prevent, so it is not
reproduced here on purpose.

### Which backend, and the two halves of it

| Surface | Where it actually lives | State |
| --- | --- | --- |
| `auth/account/*` (profile, sessions, activity, twofactor, verify-password) | Next.js handlers, JSONL file store | working — what the security sector uses today |
| `grapmail/*` (the mail API) | gateway `api.iqracsc.com`, Postgres + MTA | routes live (`401`, not `404`) — unreachable without a gateway token |

They do not share users: an account created through the app's path is rejected by
the gateway. Mail therefore requires the app to hold a gateway identity, which is
what the quota fix unblocks.

The mail base path for the app is `$BASE/grapsee/grapmail` — the same same-origin
proxy the website uses (`src/app/api/grapsee/grapmail/[...path]/route.ts:130`
forwards to `<upstream>/grapmail/*` and passes the bearer through unchanged).

### The contract the screens will be built against

Verified by reading the handlers, not inferred:

- `GET /me` → `{success, address, handle, status, name, memberSince}`;
  `404 {code:"NO_MAILBOX"}` when the account has no mailbox
- `GET /stats` → `{success, inbox:{total,unread}, sent, drafts, spam, trash}`
- `GET /inbox?box=&q=&page=&pageSize=` → `{success, box, page, pageSize, total,
  messages[]}`; default page size 25, floor 5
- `GET /message/:id` → `{success, message}` where `message` is `fullRow` plus
  `attachments[]`, and reading it sets `\Seen` server-side
- `GET /thread/:threadId` → `{success, messages[fullRow]}`
- `GET /labels` → `{success, labels[{…, unread}]}`
- `PATCH /message/:id/:action` — **the action is in the path, never the body.**
  `read|unread|star|unstar|trash|restore` (`grapmail-boxes.ts:265-270`) plus
  `archive|unarchive|spam|unspam|snooze|unsnooze` (`grapmail-archive.ts:146-173`)
- `DELETE /message/:id` — permanent, and only valid from Trash. Trash itself is
  reversible; these two must never be conflated in the UI.

`publicRow` (`grapmail.ts:579`) is the list shape and deliberately carries no
body: `id, threadId, direction, status, from{address,name}, to[], cc[], subject,
snippet, isRead, isStarred, hasAttachments, unsubscribe, isE2eEncrypted,
createdAt`. `fullRow` adds `bodyText, bodyHtml, inReplyTo, sizeBytes,
e2eEnvelope`.

### What the reference PNGs show that the server cannot answer

The design is the visual contract; these five elements are the agreed exceptions,
each with the proof. They are omitted rather than invented.

| Reference element | Reality |
| --- | --- |
| Tabs `Primary / Social / Promotions / Updates` | No `category` column on `grapmail_messages`, and the web client's own tab list has no categories — it is exactly `inbox, starred, sent, drafts, archive, spam, trash, others` (`src/app/(mail)/mail/page.tsx:199-208`). Replaced by that same real box list. |
| Chips `All / Personal / Work / +2` | Same unbacked axis. Replaced by one strip from `GET /labels`. |
| Drawer row `Important` | No column, no heuristic. Omitted. |
| Drawer row `Snoozed` | `snooze` writes `snoozedUntil` and the inbox hides future-snoozed mail, but `BOXES` has no `snoozed` entry — so a snoozed message is currently unreachable. The **action** ships; the row does not, until the backend adds the box. Filed as a real product bug, not a UI gap. |
| Bottom tab `Calendar` | No calendar backend exists. Omitted rather than rendered dead. |
| Account switcher | One session per device. Rendered as the current account plus its `GET /aliases`, which is a real same-account feature. |
| Sort by sender / subject | The server orders by UID descending only (`grapmail-boxes.ts:34-39`). Sort is client-side over the **loaded** set, and the sheet states that scope. |

**Badge rule.** A count renders nothing when the server did not answer it, never
`0`. `/stats` reports only inbox/sent/drafts/spam/trash, so Starred, Archive and
All Mail carry no badge — the same call the web client already makes
(`page.tsx:2306`).

### Screen set, identical on both platforms

`60-mail-home`, `61-mail-home-dark`, `62-mail-drawer`, `63-mail-empty`,
`64-mail-loading`, `65-mail-load-failed`, `66-mail-no-mailbox`, `67-mail-search`,
`68-mail-selection`, `69-mail-sort-filter`, `70-mail-move-sheet`,
`71-mail-delete-sheet`, `72-mail-labels`, `73-mail-label-create`,
`74-mail-thread`, `75-mail-thread-encrypted`, `76-mail-compose`,
`77-mail-contacts`, `78-mail-files`, `79-mail-attachment-preview`,
`80-mail-settings`, `81-mail-screener`, `82-mail-send-receipt`,
`83-mail-assign-label-sheet`, `84-mail-snooze-sheet`, `85-mail-contact-detail`.

Contacts is derived from `?box=inbox` + `?box=sent` (there is no contacts
endpoint), and Files is aggregated from message pages (`GET /attachments/:id`
exists; a list-all route does not). Both limitations are stated in code where
they are implemented.

## Sessions

- Android: token encrypted with an Android Keystore AES-GCM key before disk
- iOS: token in the Keychain
- Both send the `x-device-id` header the backend counts accounts against
- Both restore a stored, unexpired session at launch (open signed-in)

## Building

### Android

```bash
cd android
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # needs signing config for install
```

Requires JDK 17 and an Android SDK with `platforms;android-34` +
`build-tools;34.0.0` (`ANDROID_HOME` set). The debug APK lands in
`android/app/build/outputs/apk/debug/`.

### iOS (requires a Mac with Xcode 15+ and XcodeGen)

```bash
brew install xcodegen
cd ios
xcodegen generate
open GrapMail.xcodeproj   # then Cmd+R on a simulator or device
```

## Design fidelity

`design/DESIGN_TOKENS.md` holds every measured color, radius, and motion
value — sampled pixel-by-pixel from the founder's PNGs (kept in
`raw-designs/`). The illustration assets in `assets/` are crops of the
originals, not redraws — artwork regions only, re-cropped by
`scripts/recrop-illustrations.py` (no baked-in text; screens render all
copy natively). Light and dark themes both match the paired designs.

## Repository layout

```
grapmail-mobile/
├── android/          Kotlin + Compose app
├── ios/              Swift + SwiftUI app (XcodeGen project)
├── assets/           Illustration crops from the founder's PNGs
├── raw-designs/      Per-screen reference renders (light/dark)
│   └── account/      the recovery/security batch
└── design/           DESIGN_TOKENS.md — the measured source of truth
```
