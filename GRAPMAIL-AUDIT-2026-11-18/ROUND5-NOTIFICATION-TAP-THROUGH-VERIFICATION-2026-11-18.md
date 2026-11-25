# Round 5 — Notification Tap-Through Verification (Android + iOS + Backend)

**Date:** 2026-11-18
**Round:** 5 of the deepening series ("completed yet not" re-mandate; tiny-issue sweep)
**Scope:** The full notification pipeline — backend payload construction, APNs/FCM
transport shaping, Android push intake + notification build + tap/action routing
(cold and warm), iOS delegate + routing + Notification Service Extension, and
sign-out/cleanup parity.
**Verdict:** VERIFIED — the end-to-end wiring is correct on both platforms.
**Code changes:** none. This is an evidence round: every hop was read and
cross-checked against the backend contract; five failure hypotheses were raised
and all five were refuted with file:line evidence.

---

## 1. Why this round exists

Notifications are the only part of the mail loop that crosses *all* process
boundaries: backend → push provider → OS → app cold start → navigation → data
refresh. It is the exact class of seam where "completed yet not" hides — a
feature that demos fine on a warm device, then drops taps on a cold start, or
shows actions that route nowhere. So the standard here was: **follow one push
from the dispatcher to the seat of navigation on both platforms, and prove every
hop.**

## 2. The five hypotheses (all refuted)

| # | Hypothesis | Verdict | Evidence |
|---|-----------|---------|----------|
| H1 | Cold-start taps are dropped (extras only handled on warm start) | Refuted | `MainActivity.kt` intake runs in `onCreate` and `onNewIntent` through the same `takeComposeRequest(intent)`; `notificationTarget` state (L132) receives `Notifications.target(intent)` (set at L239) before any composable exists. |
| H2 | Tap opens generic home instead of the message | Refuted | `Notifications.pendingOpen` builds `OPEN_MAIL` + `messageId`/`threadId`/`sessionId`/`mailAction`; `GrapMailNav` consumes it (L389 param; L556-557 effect): `open`/`more` → `mailThread(...)`, `reply` → `mailCompose(reply)`, cross-account taps switch sessions first (`switchTo` + pop to home). |
| H3 | iOS delegate does not route taps (log-only) | Refuted | `AppIntegration.swift` `didReceive` (remote branch): reads `mailbox` + `messageId`, guards `isRemote || category == MailPoll.actionCategory`; `default/reply/more` → `ShortcutRouter.pendingMail`; `archive/read` → `ApiClient.mailAction` + store invalidation. `RootView.openNotificationMail()` consumes `pendingMail` into `[.home, .mailThread(...)]` (+ optional reply compose). |
| H4 | Backend payload keys don't match what the apps read | Refuted | APNs: `aps.category = 'grapmail.message'` (equals `MailPoll.actionCategory`), `thread-id`, `mutable-content` + `content-available`; data `{messageId, threadId?, mailbox, encrypted?}`. FCM data-only path adds `sender`/`subject`. Android `PushService.kt` reads the same keys verbatim; iOS reads `mailbox`/`messageId`/`threadId`. Key-for-key match on both platforms. |
| H5 | Platform parity divergence (actions, cleanup, switching) | Refuted | Same four actions both platforms: Android "Archive / Mark as read / Reply / More" (`Notifications.kt` L154-157) vs iOS `archive/read/reply/more`. Both switch accounts on tap. Sign-out cleanup exists on both: iOS `removeDeliveredNotifications` / `removeAll...` (L412, L435-436, L451-452); Android tags notifications with `grapmail.session` (L150) and `SessionStore.kt` L427-429 cancels exactly the removed sessions' live notifications; `PushSync.unregister` at the sign-out door. |

## 3. End-to-end trace (what was read, in order)

**Backend**
- `services/shared/src/routes/grapmail-push.ts` — POST/DELETE `/grapmail/push/register`,
  `push_devices` upsert (unique `user_id,token`), platform normalization
  (`ios` → `apns`), iOS preview-secret issuance.
- `services/shared/src/lib/push-dispatch.ts` — `buildMailPushNotification`
  (title = sender, body = subject or "New encrypted message", data keys above);
  device loop (≤25, `last_seen` DESC); dead-token removal on provider failure;
  no-ops cleanly when credentials are absent (deploy-gated, not a code gap).
- `services/shared/src/lib/mail-push.ts` — FCM HTTP v1 (data-only when
  `messageId` present; high priority; 3600s TTL) and APNs (ES256/HTTP2; alert +
  category + thread-id; 1h expiry).

**Android**
- `logic/PushService.kt` — `onMessageReceived` → `MailPollWorker` with the five
  data keys; KEEP work, expedited on high priority.
- `logic/MailPollWorker.kt` — pushed-id path: `notifyExact` for the addressed
  account, guarded by `Notifications.allowed` + session freshness; silent
  branches are logged, never announced.
- `logic/Notifications.kt` — build (channel, monochrome icon, sender avatar,
  `VISIBILITY_PRIVATE`, BigPicture/BigText), the four actions, the
  `grapmail.session` tag.
- `logic/MailNotificationActionReceiver` — archive/read → `Api.mailAction` +
  `Notifications.dismiss` + local store patch (goAsync, 8s timeout).
- `data/LaunchApi.kt` + `logic/PushSync.kt` — register/unregister parity with the
  backend route; race-guard when a session disappears mid-register.
- `MainActivity.kt` — cold/warm intake, nav consumption, account switch.

**iOS**
- `AppIntegration.swift` — registration (POST/DELETE with `platform: ios` +
  `deviceId`), category + action registration (four actions), 
  `didReceiveRemoteNotification` (cache for the current account + baseline
  refresh), `willPresent` (account-gated banner/sound/badge, silent otherwise;
  L569+), `didReceive` routing, sign-out cleanup.
- `NotificationService/NotificationService.swift` + `NotificationShared/PreviewCredential.swift`
  — the NSE: `previewTicket` + device secret → `GET
  /api/grapsee/grapmail/notification-preview` (`Authorization: GrapMailPreview
  {ticket}.{secret}`), 200 + size caps, snippet append, ≤2MB thumbnail
  attachment, credential-rotation guard, time-expiry fallback. Declared in
  `ios/project.yml` as `GrapMailNotificationService` (bundle
  `com.grapsee.grapmail.NotificationService`).
- `RootView.swift` — `openNotificationMail()` consumer (account switch + thread
  focus + reply compose).

**Honesty suite (pins re-verified post-R1)**
- Android `Artwork(` totals: 6 across Account/Puzzle/Name/HumanVerify/Created;
  illustration raster refs: 0.
- iOS `Art.view/fill` totals: 5; `Image("ill_")` / placeholder-raster refs: 0.
- No pin predicate regressed from the Round-1 screen replacement; no honesty
  edits needed this round.

## 4. What this round does not cover
- Live delivery against real FCM/APNs credentials (the dispatcher no-ops without
  them — deploy-gated, same caveat as every round).
- The CI build of the NSE target (project.yml is the source of truth; no
  `.xcodeproj` is checked in).

## 5. Deliverable note
No file in the repository was changed in Round 5 — there is no
`round5-applied-files/` directory, by design. This note is the deliverable.

## 6. Evidence index (sandbox notes)
- `r5_notif_a.txt`, `r5_notif_b.txt` — first-pass recon.
- `r5_nav_a.txt` — Android intake/nav/worker verbatim.
- `r5_nav_b.txt` — iOS consumer + backend route discovery.
- `r5_push_src.txt`, `r5_push_be.txt` — dispatcher + route + schema.
- `r5_contract.txt`, `r5_contract2.txt`, `r5_contract3.txt` — key contract,
  Android actions/LaunchApi, iOS categories/NSE/project.yml.
- `r5_pins.txt` — honesty pin predicate counts.
- `r5_final.txt` — SessionStore cleanup verbatim + final action parity.
- `r5_honesty.txt` — pin audit.
