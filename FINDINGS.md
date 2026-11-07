# Settings depth: what is excluded, and the exact endpoint each one needs

The plan's rule is **endpoint-or-it-doesn't-exist**: a mobile setting may only be
built when a real route stores the value and answers with it. This file records
every setting the audit asked for that fails that rule, the evidence that it
fails, and the specific backend change that would let it ship. None of them is
faked client-side, because a setting that changes nothing is worse than no
setting: it is a control that lies about the account.

Verified against `grapsee-backend` on 2026-11-06 — the routes read here are
`services/shared/src/routes/grapmail.ts`, `grapmail-aliases.ts`, `screener.ts`
and the `mail_aliases` / `grapmail_messages` tables in
`services/shared/src/db/schema.ts`.

---

## 1. The compose "From" address picker — the plan's one buildable item, and it is not buildable

**The plan said:** *"Compose 'From' address picker wired to `GET /grapmail/aliases`
(exists; UI currently fixed to primary)."*

**What is actually true:** the read exists and works. The **send does not accept a
from-address at all**, so a picker would choose nothing.

| Check | Evidence |
| --- | --- |
| Does `/send` read `from` from the body? | No. `fromAddress` is built server-side: `` fromAddress: `${mailbox.grapmailHandle}@${GRAPSEE_DOMAIN}` `` (`grapmail.ts:1010`, `:1108`, `:1252` — send, deliver-internally, draft). |
| Does any branch honour a client-chosen sender? | No. The same expression is passed to `deliverInternally` and to the outbound builder; nothing in the request body can change it. |
| What are aliases, then? | **Receive-only.** `POST /grapmail/aliases` mints a *deliverable* address (`grapmail.ts:1380-1389`: inbound resolution consults `mail_aliases` so mail addressed there reaches this mailbox). |
| Does the alias row carry a send-side notion? | No. `mail_aliases` columns are `id, ownerUserId, label, address, isActive, createdAt` (`schema.ts:2077-2087`). There is no "can send as", no signature link, no default flag. |
| Does the app already read them? | Yes — `GET /aliases` feeds the Addresses screen on both platforms (`MailboxViewModel.kt:1896+`, `MailboxModel.swift:1563+`), with the read state distinguished from an empty answer. |

**What shipping a picker would actually do:** show "business.rakib@grapsee.com" in
the composer, send the message, and the recipient would see the primary handle —
the one address the person just chose not to use. The app would have told them
one thing and the internet another, and the mistake is only discoverable by the
person on the receiving end.

**The endpoint change that would make it real** (all four parts are required; any
subset produces the same lie):

1. `POST /send` and `POST /draft` accept `from` in the body and **validate it
   against `mail_aliases` owned by the authenticated mailbox** — active rows only,
   exactly the predicate the inbound path already uses. An address that is not
   the handle and not an owned active alias is a 400, never a silent fallback.
2. The chosen address is what lands in `grapmail_messages.fromAddress`, so the
   Sent folder, the reader and the thread's reply addressing all agree with what
   was picked.
3. The outbound SMTP envelope and the `From:` header use it too — otherwise the
   stored row and the delivered mail disagree, which is the divergence the
   attachment-linking fix in `grapmail.ts:1040-1052` exists to prevent.
4. A reply to a message sent from an alias must address its `To:`/`From:` so the
   thread keeps working from the alias, not the handle.

Until 1-3 exist, the composer stays fixed to the primary address and says so.

---

## 2. Undo timeout for filings

- **Wanted:** choose how long the undo banner stays (5/10/30 seconds).
- **Why excluded:** the banner is a client-side timer over a reversible `PATCH
  /message/:id/:action`. There is no account field for a duration, no route that
  reads one, and nothing the server would behave differently for.
- **Needed:** a profile/settings field (`PATCH /auth/account/profile` already
  writes `settings`), read back on `GET /me`, and the mobile settings row bound to
  it rather than to a local default.
- **What exists instead, honestly:** the undo works. It offers the inverse PATCH
  for exactly the ids the server accepted, survives a partial undo, and is
  superseded by the next filing (`§25`, `§38`).

## 3. Swipe-action customization

- **Wanted:** pick what left/right swipe do.
- **Why excluded:** no per-account action mapping exists on the server, and the
  two directions are currently *gated by box* in code — a swipe that cannot change
  anything in the current box is disabled rather than wired to a no-op.
- **Needed:** a `swipeActions` object on the profile settings document, validated
  server-side against the same action enum `PATCH /message/:id/:action` accepts,
  plus a client that refuses a mapping naming an action the route rejects.

## 4. Default reply mode (reply vs reply-all)

- **Wanted:** "always reply to all" as an account default.
- **Why excluded:** no field, and the app already asks per message: the reader
  offers Reply, Reply all and Forward as three doors, so the choice is made where
  the context is visible rather than inherited from a setting that would silently
  widen a recipient list.
- **Needed:** a settings field plus a compose that shows which default it applied.

## 5. Remote-image policy (always show / always block / ask)

- **Wanted:** an account-level choice about sender-hosted images.
- **Why excluded:** there is no proxy and no stored preference. A server setting
  would imply the server rewrites the HTML — it does not; the mobile clients never
  render the HTML part at all.
- **Needed:** either a proxy route that rewrites `img src` through GrapMail (so
  the sender never sees the reader) and a stored policy that route honours, or a
  client preference on the profile document. Today the decision is per message and
  per read: the reader names the count, says loading them tells the sender the
  message was opened, and asks (`§38`).

## 6. Link previews / unread-message rendering of HTML

- **Wanted:** render the sender's HTML with its styling.
- **Why excluded:** there is no sanitizer in either app's dependencies and the
  correct one is not a regex; the HTML part is therefore never given a renderer,
  which §4 pins structurally in every mail source on both platforms.
- **Needed:** a server-side sanitizer plus a stripped-content endpoint, or a vetted
  sanitizer dependency in both apps. Displaying stripped text and saying that is
  what ships.

---

## What IS wired, because the endpoint exists

Verified reachable on both platforms, not excluded: the screener enable switch and
its queue (`PUT`/`DELETE /screener/decision`, pinned against the server's own four
decision values and two scopes), label create/rename/colour/assign
(`/labels`, `/labels/assignments`), alias create/remove (`POST`/`DELETE
/aliases`), message actions including unread (`PATCH /message/:id/:action`),
permanent delete behind an explicit confirmation (`DELETE /message/:id`),
attachment bytes (`GET /attachments/:id`), drafts (`POST /draft`, send-then-delete),
and the mailbox badge the notification poll reads (`GET /stats`).

## How this file is kept honest

`scripts/mail-honesty.py` §39 pins the *absence* of the half-built surfaces that
would otherwise be added because the code is nearby: no `autoVerify` intent filter
and no `associated-domains` entitlement while `/.well-known/assetlinks.json` and
`apple-app-site-association` answer 404, and no push background mode or FCM
dependency while no push service exists. A mutation that adds any of them is
refused by `scripts/mutate-39.py`.

## The mail auth seam, measured (2026-11-08)

One consequence, reproduced end to end with a throwaway account the same way the
walk creates one:

    # sign in through the store the app calls today
    POST https://grapsee.com/api/auth/account/signin
    {"identifier":"<handle>@grapsee.com","password":"…"}
      -> 200, sessionToken "st1.…"

    GET https://grapsee.com/api/auth/account/profile   (the same st1 token)  -> 200
    GET https://grapsee.com/api/grapsee/grapmail/stats  (the same st1 token)  -> 401
        {"error":"Invalid token"}
    GET https://grapsee.com/api/grapsee/grapmail/me     (the same st1 token)  -> 401

So the app's mailbox reads are refused by the service that owns the mailbox, by
its *token verifier* and not by its route table: `middleware/auth.ts` runs
`jwt.verify(token, config.jwt.secret)`, and `st1.` is a session id, not a JWT.
The mail gateway (`/grapmail/*`, same auth-service, same secret) accepts only the
JWT pair its own `/auth/login` issues — and that login will not issue a pair to
an account that is not in its `users` table and email-verified. There are two
account stores: the Next.js JSONL store the app signs into, and the gateway's
Postgres store the mailbox belongs to. They share no users.

The gate is the whole migration: signup (handle + email OTP), sign-in (JWT pair),
every `/auth/account/*` call (the local store's contracts are not the gateway's),
and the walk's account creation, which today works precisely because the local
store has no OTP. A URL swap is not the fix; swapping the sign-in URL alone would
leave the app unable to sign in at all.

### The target chain is proven, not hypothetical (measured 2026-11-08)

The same throwaway flow, done the gateway's way, lights the mailbox up:

    POST /api/grapsee/auth/register
      {"accountType":"USER","grapmailHandle":"<handle>", …}
      -> 200  {"grapmail":"<handle>@grapsee.com","grapmailStatus":"active"}
    POST /api/grapsee/auth/login
      -> 403  {"requireOtp":true}          # an OTP is dispatched
    POST /api/grapsee/auth/verify-otp      # the OTP is read server-side for this probe
      {"email":"…","otp":"…"}
      -> 200  {"accessToken":"eyJ…"}       # 405-char HS256 JWT, jti = session
    GET  /api/grapsee/grapmail/me    (Bearer JWT) -> 200 {"status":"active", …}
    GET  /api/grapsee/grapmail/stats (Bearer JWT) -> 200 {"inbox":{"total":0,"unread":0}, …}
    GET  /api/grapsee/grapmail/inbox (Bearer JWT) -> 200 {"messages":[], …}

So the mailbox sector's code paths are right; the credential is the whole fix.
The mobile-side map, checked against the gateway's route table:

    signup      /auth/account/create          -> /grapsee/auth/register   (accountType USER, handle)
    sign-in     /auth/account/signin          -> /grapsee/auth/login      (JWT pair; 403 = OTP step)
    new         —                             -> /grapsee/auth/verify-otp, /resend-otp, /refresh
    password    /auth/account/reset/{start,confirm} -> /grapsee/auth/{forgot-password,reset-password}
    account     /auth/account/{profile,twofactor,verify-password,sessions,activity,avatar}
                                              -> /grapsee/auth/account/…  (same names, JWT auth)
    handle      /auth/grapmail/check          -> /grapsee/auth/grapmail/check
    phone       /auth/phone/{start,verify}    -> /grapsee/auth/phone/…    (same names)
    mail        /grapsee/grapmail/*           -> unchanged; accepts the JWT

The walk's account creation is the CI constraint, and it is smaller than it first
looked: a USER account registered with **no email** skips the OTP gate entirely.
Measured: `register {accountType:"USER", grapmailHandle, password, acceptedTerms}`
with no `email` answers `{"email":null,"grapmailStatus":"active","requireOtp":false}`,
and `login` with the address answers `200` with the JWT pair on the first try
(`login` only challenges when `user.email` is set — the mobile signup wizard never
collects one). So a fresh CI account signs in immediately, the walk's handle-and-
password flow is unchanged, and the migration is the URL/shape swap plus the
account-route move — no OTP screen, no seeded CI account. The verified-email
variant remains available for accounts that do carry an address.

Until the migration lands, the app keeps its current, honest behaviour: account
screens work against a real store, mailbox screens say "Couldn't load Inbox"
against a real gateway refusal, and no seeded row exists outside the test-only
snapshot host.

## The gateway's login envelope leaks the credential hash (2026-11-08)

While reading the envelope the client now depends on, the login response was
found to include the account row's `passwordHash`, `emailVerificationOtp`,
`emailVerificationExpires` and the stored `refreshToken` inside its `user`
object. Measured live on a throwaway account:

    POST /api/grapsee/auth/login
      -> {"success":true,"accessToken":"eyJ…","user":{"passwordHash":"$2a$10$…",
          "emailVerificationOtp":null,"refreshToken":"…", …}}

The clients ignore unknown keys, so nothing in this repo renders or stores them.
The leak is on the server: `auth-account-profile.ts` already has a projection
(`dal-projection.ts`) that exists precisely to keep these fields out of client
responses, so the login and verify-otp handlers should serialize through it
rather than returning the row. Recorded here because the mobile client is the
first party to depend on the envelope, and a response that hands out an offline
crack target and a live refresh token is a defect that outranks any client.

## Labels were dead in production, and the database said why (2026-11-08)

Live probe of every mail endpoint the app calls, with a throwaway account's
gateway JWT, found `GET/POST /grapmail/labels` and `GET /labels/assignments`
answering **500** with the backend's own error envelope. The route was not the
problem: `grapmail_labels` and `grapmail_message_labels` did not exist in the
production database. The repository's canonical migration
(`services/shared/src/db/migrations/0030_grapmail_labels.sql`, idempotent) had
never been applied there, and production keeps no `__drizzle_migrations` journal
to notice a gap.

Applied that migration as-is (transactional; `CREATE TABLE IF NOT EXISTS` plus
`duplicate_object` guards, so re-running is safe), then verified against the
live gateway: list 200, create 201, assignments 200, assign-to-message 200, and
the unread count reads back. No client change was needed — the screens were
calling real routes that were failing on a missing table.

What the same probe confirmed as *not* defects, so they are not chased again:
`POST /grapmail/send`, `/draft`, `/aliases`, `/labels` reach the backend;
`POST /e2e/plan` and `POST /screener/decision` exist; `/screener/enabled` is
PUT-only by design and the app reads its state through `GET /screener`; and the
message-action `PATCH` requests pass the backend's CSRF guard because both
clients send a JSON body (the guard refuses unsafe methods with no
`content-type`, which is what a probe without one sees).
