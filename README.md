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

> **Qualification, added while investigating the mailbox.** "The same … as the
> website" is true of the account and security surface and is *not* true of
> mail. The website's mail traffic goes to the gateway (`api.iqracsc.com`,
> Postgres + MTA) and its login issues the token that API accepts; this app's
> account traffic goes to the Next.js handlers backed by a JSONL file store,
> whose tokens the gateway rejects. The two stores do not share users — see
> "Mailbox sector" below. So "no fake data" still holds, but it holds because
> the app talks to a real store, not because that store is the one the mail
> service reads.

## Mailbox sector — built, and what still gates it

The mailbox is built on both platforms and is the signed-in home: the placeholder
screen that used to print *"The mailbox arrives in the next build phase."* is
deleted from both apps, and `.home` / `Routes.HOME` open the list, the drawer, the
thread, compose, labels, contacts, files, settings and the screener.

What has changed since this section was written is the server, and it changed in
one place and not another.

**The account backend answers.** `POST /api/auth/account/create` with an empty body
returns `422 {"code":"INVALID_NAME"}` and `POST /api/auth/account/signin` with a
nonexistent address returns `401 {"code":"INVALID_CREDENTIALS"}` — validation and
lookup running against the real `users` table, not a `500`. CI proves the same from
inside the app: the walk creates an account for real, signs in, and the security
hub renders server verdicts ("One thing left to do", "Off", "Add").

An earlier version of this section cited `POST /auth/register` and `/auth/login` on
`api.iqracsc.com` failing with a missing-columns `500`. Those are not the paths this
app calls — it uses `/auth/account/create` and `/auth/account/signin` on
`grapsee.com/api` — so that evidence was measuring a surface the client never
touches. Recorded here because the correction matters more than the original claim
looking right.

**The mailbox still does not serve the signed-in account.** Its routes are
deployed: `GET /api/grapsee/grapmail/inbox` and `/stats` answer `401
{"error":"Unauthorized"}` with no bearer, which is a live route asking for
authentication, not a missing one. But the app's authenticated mail request fails,
and it fails as the *generic* load error — "Couldn't load Inbox / Your mailbox could
not be reached. Nothing has been deleted." — not as `66-mail-no-mailbox`. So the
server is refusing or erroring on a request that carries a token the account routes
accept. Why is visible in the gateway's logs and not from a client, and the client
will not guess at it.

That distinction is the point of this paragraph. The sector was built against the
verified contract, so the day the mailbox path is fixed it lights up with no code
change; until then the live walk leg photographs whatever the app actually says,
and the two failure states — unreachable, and reachable-but-unprovisioned — stay
visibly different. Neither is presented as the other, and no seeded row exists
outside the test-only snapshot host, so a broken network layer cannot read as a
working mailbox.

### What shipped, and how CI proves it

- **Data layer.** `MailDto.kt` / `MailAPI.swift` mirror the route's own row
  shapes (`publicRow`, `fullRow`), every DTO hand-decoded with the lenient
  helpers so an omitted key keeps its null instead of throwing. Response types
  are `Decodable`, never `Codable`.
- **State.** `MailStore` (per-box pages, memoised bodies, stats, labels,
  aliases) plus `MailboxViewModel` / `MailboxModel`. A failed refresh never
  blanks a list that is already on screen — the hub's defect, designed out here
  rather than rediscovered. The cache clears wherever the session does.
- **Screens.** Mailbox home, thread, compose, sort/filter, move, delete,
  assign-label and snooze sheets, labels, label create (the same screen edits an
  existing label — a label's row is the rename affordance, so the row is not a
  control that goes nowhere), contacts, contact detail, files, attachment
  preview, settings, screener, send receipt, and the no-mailbox state. Same
  screen set on both platforms.
- **Affordances say what they can actually do.** Swipe-to-file exists on both
  platforms with the same two directions and the same two colours: leading is
  archive, or back out of Trash/Spam; trailing is trash. Nothing a swipe can reach
  destroys mail — permanent deletion stays behind its confirmation sheet, reachable
  only from Trash — and the honesty gate proves that by failing when the Trash
  button is rewired to `deletePermanently` on either side. One mechanic cannot be
  matched: SwiftUI refuses a full fling toward Trash, Material3 has no such mode,
  which is a reason to keep the gesture reversible rather than a reason to hide it.
  Three controls were removed instead of faked: the label chips are not tappable
  (the listing returns no label field and takes no label query, so a chip could
  filter nothing), the bottom bar's Mail tab takes no click while Mail is the
  screen it stands on, and the Android box-tab row — which drew an active underline
  and did nothing — is now wired to the same open-the-box call the drawer uses.
  No screenshot shows a swiped row: the coloured band exists only while a finger is
  holding the row open, there is no reference render for it, and the CI mailbox has
  no message to swipe. What is proven instead is the safety boundary, by the gate.
- **Labels are read as well as written.** `GET /labels/assignments` is implemented
  on both platforms (`mailLabelAssignments`) and held in the cache as a
  messageId→labelIds map with *nil meaning never-read* — distinct from "read, and
  nothing is labelled". The Label sheet therefore shows three states (on / some but
  not all / off) and, when the read has not answered or has failed, says so and marks
  nothing. A tap on a label that is on takes it off (`DELETE
  /labels/message/:id/:labelId`, which existed on both platforms with nothing calling
  it); a tap when the state is unknown only ever adds, because removing on an unknown
  baseline can un-tag mail nobody meant to touch.
- **A label's colour is the server's palette key, never a hex value.**
  `normaliseColour` (`grapmail-labels.ts:55-58`) stores one of eight words and
  replaces anything else with `slate`. Both apps used to *send* a hex string built
  from a locally invented ramp of five or six colours and to *read* the stored value
  back with a hex parser — so the request succeeded with 201, the server stored
  `slate`, the parser found nothing, and every label drew the same fallback. The
  eight keys are now declared once per platform, mapped to the web theme's own
  light/dark pair (`--gm-label-*`), and pinned against the server's set by
  `scripts/mail-honesty.py`. Android also needed `@SerialName("colour")`: without it
  the field decoded as null for every label on that platform alone.
- **A snooze can be undone.** `unsnooze` sat in both action enums with no caller, and
  the reason is a real limit rather than an oversight: `publicRow` carries no
  `snoozedUntil` and the inbox hides pending snoozes, so the app cannot see which
  messages are postponed (`?box=snoozed` is implemented but not deployed). The undo is
  therefore offered for exactly the mail the app just snoozed — the one set it can
  honestly name — naming the instant it will return, and it survives a partial undo
  rather than forgetting the rest.
- **The unsubscribe offer is a confirmation, not a one-tap.** A message carrying the
  sender's `List-Unsubscribe` header shows a button that opens a sheet naming the
  host the request will go to (not GrapMail), stating that visiting such a link can
  itself confirm the address is live, and stating that this app cannot undo it. Only
  http(s) is ever offered — a `mailto:` or a `javascript:` in an untrusted header gets
  nothing. Afterwards the line reports that the link was handed to a browser; **no
  screen claims anyone was unsubscribed**, because the outcome is not observable here.
- **Dark mode is proven structurally, then photographed.** Three band frames are shot
  in dark (home, thread, labels). The claim for the other 27 rests on a check, not on
  relaunches: no mail screen may name a colour at all (`Color(0x…)` is forbidden in
  the four Android screens and the five iOS views), so every paint comes from a token
  table holding a light *and* a dark value. That pin found a real defect the first day
  it was written — the starred marker was a hardcoded light amber on both platforms,
  in the row as well as the thread, which is what the dark home frame photographs.
- **API parity is a gate, not a review habit.** `scripts/api-parity.py` diffs
  the two mail client surfaces — every `mail*` call name and every decoded DTO
  field — and both workflows run it. Three mismatches were caught by hand while
  this sector was built; a reviewer cannot re-derive them from one file.

  It compared far less than it claimed until the label work: its Android pattern
  required the `Dto` suffix, so the response envelopes (`MailInboxResponse`,
  `MailStatsResponse` and the other twenty of them) landed in the "plumbing, not
  compared" list and their fields were never diffed — 8 types instead of 29, printed
  as a green run. Its body matcher also ran to
  the next `)` at column zero, which walks straight through one-line declarations and
  reports the *following* type's fields as drift. Both fixed: 29 types, 139 fields,
  balance-scanned. The asymmetry it then found for real — iOS's inbox envelope
  declaring `error`/`code` that Android's did not — is now matched on both sides, and
  `mailInbox` refuses a `success: false` listing at the transport so a box the server
  did not produce can never be drawn as an empty one.
- **The two receipts are compared, not just the two APIs.** The send receipt is
  one screen drawn twice, and a name-and-field diff cannot see the bodies of a
  `when` and a `switch`: Android handled `sent`/`queued`/`failed` while iOS also
  handled `draft`, so a successful draft save read "Server reported: draft" in
  warn ink under a line saying "Saved to Drafts". `scripts/mail-honesty.py` now
  extracts the status words from both arms and requires the sets to agree.

- **The snapshot host owns its seeded fields.** A seeded screen and a live read
  cannot both write the same state: the host seeds first, the CI account's real
  (empty) label list arrives afterwards, and the frame photographs the wrong
  branch while a file-count gate calls it a pass. Android passes a
  `snapshotMode` flag into the view model's constructor; iOS sets
  `snapshotSeeded` at the start of `loadAccount` and `refreshLabels`, which are
  the two reads the host seeds. The list loader is deliberately NOT suppressed on
  either side: it reads into the cache the host has already written, and the
  no-blank rule keeps the seeded rows on screen when the CI mailbox refuses the
  call. Suppressing it would hide a real code path from the photograph, and an
  earlier version of this bullet claimed every loader checked the flag, which was
  not true of the one that mattered least.

- **A host case is only worth writing if something photographs it.** Both hosts
  carried `mail-home-dark` — never launched, because the dark home is the same
  screen with the appearance forced — and three more (`mail-drawer`,
  `mail-search`, `mail-sort-filter`) whose frames come from the live walk on both
  platforms. Four dead names per side, each a second copy of a screen nobody
  looks at. `scripts/mail-honesty.py` now compares each host's name set against
  the names its own walk launches, in both directions, and requires the two hosts
  to draw the same set — which is how all four were found rather than by reading.

- **Every dark frame carries both proofs.** The host recognised the name (the
  `Unknown mail snapshot: <name>` fallthrough is absent) and the screen's own
  content drew (a seeded row subject is on screen). Presence of `gm-snapshot`
  alone proves nothing: the fallthrough bears that marker, so a mistyped name
  photographs a placeholder and still fills the PNG gate. The dark home was the
  frame checked only the weak way; iOS's dark loop went further and waited on a
  `mail-search` text field the snapshot host never draws, which could only ever
  time out and blame dark mode for it.

- **A refused write cannot be reported as saved.** Android's four bodyless label
  calls were `execute(req)` with the returned status dropped, so 400/404/409 all
  read as success — the sheet said "Saved.", the cache recorded a pair the server
  never wrote, and the next tap chose its direction from that invention. They now
  go through `executeVoid`, which throws on `status >= 400` like every iOS call
  already did. `api-parity.py` compares declarations, not error behaviour, so
  this split is exactly the class of disagreement a name-and-field diff certifies
  as matching.

- **The sender disc is the reference's palette, with a value per theme.** The
  ramp was ten hardcoded light-only hexes in the two components files, which those
  files' exclusion from the no-literals gate kept off every radar. The web
  reference's list is eight entries and three of them are theme variables, so the
  disc lifts from `#1a73e8` to `#8ab4f8` in dark exactly as the web does; the
  values now live in `GrapMailAvatarRampLight`/`…Dark` and `GM.avatarRamp`, the
  components files hold no colour outside the label-swatch table, and both sides
  are pinned against the same list.

- **The sources are at least balanced.** Neither app can be compiled here, so a
  dropped brace costs a whole runner session to find. `scripts/mail-honesty.py`
  lexes each mail source (comments and string literals removed in one pass) and
  requires the three bracket kinds to balance per file. It caught a `},` that a
  closure rewrite had dropped in `MailReaderViews.swift`; a regex version of the
  same check had pronounced that file balanced, because it paired a quote inside
  a comment with a quote 200 lines later and deleted the code between them.

- **A write that arrives after sign-out is dropped.** iOS clears the cache
  synchronously and cannot await it, so the guard sits on the writer: no cache
  write lands once the bearer is gone. Android's `clear()` now takes the same
  mutex every mutator takes, and its caller wraps it in `NonCancellable`.

- **The screener decides senders, and says what it cannot know.** `PUT
  /screener/decision` and `DELETE /screener/decision` are implemented on both
  platforms (`mailScreenerDecision`, `mailScreenerDecisionRemove`), reached from a
  "Sender" action that appears only while the queue is open — deciding a sender
  means nothing for a box whose mail already arrived. Three limits are encoded
  rather than papered over. No option is marked as the decision currently in
  force, because the surface writes decisions and has no read, so that fact is
  unknown. The queue is re-read after a decision instead of edited locally,
  because a decision governs the *next* delivery and the message in hand does not
  move. And `removed: false` from the DELETE is reported as "there was nothing to
  forget", not as a failure and not as a confident "forgotten". The four decision
  values and two scopes are pinned against the server's set (`screener.ts:60-61`)
  by `scripts/mail-honesty.py`, so a client that renames one fails CI rather than
  400ing at the store behind a sheet that looked reasonable.
- **The contract is pinned separately from parity.** `scripts/mail-honesty.py`
  asserts both platforms against the *design*, not against each other — two
  platforms can agree on the same wrong thing. It pins that a queued send is
  never drawn as delivered, a message action travels in the URL path where the
  route reads it, permanent destruction is never one bulk tap away, no remote
  HTML body is given a renderer, and the drawer offers no box the server cannot
  read back. Structural source assertions, mirroring the web client's
  `grapmail-send-status-honesty.test.ts`. Its own gate: three seeded regressions
  each had to fail the run before the script was trusted to hold anything.
- **A frame is only taken of a screen that is proven on display.** The walk's
  `must_shot` waits for the screen before it photographs it, and the search
  field is proven by the keyboard rather than by a node — a drawer's scrim does
  not remove what is underneath it from the dump, so a node can be present on a
  screen you cannot see. Without this, a run that reached no screen at all still
  filled its PNG gate with pictures of the wrong ones.
- **The band is 30 frames, `60`–`89`.** The plan called it 25; the list it
  printed runs 60 to 85 inclusive, which is 26. Four names were added after that,
  each for a screen the plan's own surface list required and no frame proved:
  `86-mail-sender-sheet` (the `screenerDecision` action), `87-mail-unsubscribe` (the
  sender's `List-Unsubscribe` offer, reachable only from mail that carries it) and
  `88`/`89`, the thread and the labels screen in dark. The number in the list is what
  CI enforces, and each discrepancy is recorded rather than quietly closed.
- **Snapshot hosts** (`MailSnapshot.kt` / `MailSnapshot.swift`) render every
  frame that needs a message to exist, from literals, unreachable without the
  launch argument. The view models expose seed seams that only that host calls.
  The hosts invent **data** and never a second copy of a **screen**: the receipt
  frame used to draw the host's own `SendReceiptPreview` / `SendReceiptView`, and
  both copies had already drifted from the live one (Android lost the semibold
  headline and the detail line; iOS handled two of four status words, so a `sent`
  receipt would have photographed "Not delivered"). A frame of a re-implementation
  looks correct, passes a file count, and proves nothing. `scripts/mail-honesty.py`
  now pins that the hosts call the live renderer and hold none of its wording.
- **Gates.** The Android walk's fixed PNG list carries all 30 names; the iOS
  floor is 62 of 66, and the iOS artifact is additionally required to contain
  every `NN-mail-*` name the test source lists — a count says the walk finished,
  not which screens it reached. Both workflows diff the two name lists and fail on
  asymmetry, because a count and a fixed list can each be satisfied by two
  different screen sets — which is exactly how drift would arrive.
- **The walk never** taps Send, permanent delete, or Report spam: one is
  irreversible, one destroys mail, and one feeds the suppression list that would
  stop future CI mail. Those three are photographed from the snapshot host.
- **Anchors** are identifiers the screens set on themselves (`mail-home`,
  `mail-menu`, `mail-search`, `mail-drawer-*`, `gm-sheets-*`, `sign-out`), not
  sentences they happen to print. On Android that needs
  `testTagsAsResourceId` (in `androidx.compose.ui.semantics`, and
  `@ExperimentalComposeUiApi`), set on the root **and again inside every sheet** —
  a `ModalBottomSheet` composes into its own window and the property does not cross
  that boundary. Predicates match the trailing segment, because uiautomator reports
  the id as `package:id/tag`, and they unescape XML entities, or a label containing
  `&` can never match at all.
- **Dark is photographed from the test-only branch.** The plan chose
  `XCUIDevice.forceDarkMode`; that API is not on this runner's SDK, so the app
  reads a `-gmDark` launch argument and applies `.preferredColorScheme(.dark)`
  inside the snapshot branch only, where no live screen can pick it up. Colours
  still resolve through `GM`'s trait lookup, so the frame goes through the same
  path a real dark-mode user does.

### Which backend, and the two halves of it

| Surface | Where it actually lives | State |
| --- | --- | --- |
| `auth/account/*` (profile, sessions, activity, twofactor, verify-password) | Next.js handlers, JSONL file store | working — what the security sector uses today, and what the walk signs up with |
| `grapmail/*` (the mail API) | gateway `api.iqracsc.com`, Postgres + MTA, reached through `grapsee.com/api/grapsee/grapmail` | routes live (`401`, not `404`) — the app's authenticated request still fails |

They do not share users: an account created through the app's path is not a
gateway identity. That is the structural reason the mail surface is the one that
does not light up while every account surface does — and it is a decision to make,
not a migration to run. The earlier explanation of this failure ("the auth DB is
missing its GrapMail columns, blocked on a Neon quota") no longer holds: the
account routes now validate and look up against the real table and return
`422`/`401` rather than `500`.

What the client can say and cannot say, precisely: the failure is not
`NO_MAILBOX`, because that has its own screen and the app is showing the generic
load error instead. Whether the gateway is refusing the bearer or erroring behind
it is visible only in the gateway's logs, so the app reports the first and
withholds the second rather than guessing.

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
`83-mail-assign-label-sheet`, `84-mail-snooze-sheet`, `85-mail-contact-detail`,
`86-mail-sender-sheet`.

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
