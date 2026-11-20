# GrapMail — Full-Stack Audit, Deep Research & Fix Pack

**Date:** 2026-11-18
**Repos audited:** `grapmail-mobile` (iOS + Android) and `Grapsee_All_Time` (Next.js web client + `grapsee-backend` services)
**Companion:** `fixpack/` — drop-in code, exact patches, verification checklists (`fixpack/README.md` is the file map)

---

## 1. TL;DR — the three questions, answered

**Why does it feel slow?** The builds in circulation are **debug builds of pre-fix commits**. Debug Compose runs multiples slower (no R8, Live Literals, JIT/interpreted paths), and the repo's release toolchain — Kotlin 2.0.20, Compose BOM 2024.09.03, `androidx.profileinstaller:1.4.1` — is already the fast path. `docs/PERF-AND-SMOOTHNESS-RECORD.md` records the performance work already merged (optimistic actions, parallel bulk paths, reader prefetch, search pill). **Fix: ship and install a release build, then re-measure** (steps in `fixpack/verify/C1`).

**Why was the preview / "interactive" mail work unfinished?** On Android there was **no image loader in the build** — the code said so itself (unit 79: *"an image is offered as a download, not a thumbnail … pretending otherwise would show a broken box"*), and the Files screen said the backend had **no endpoint listing every file in a mailbox**. Fix pack **A** adds the loader (Coil, wired through the app's own authenticated fetch), and fix pack **B** adds the missing endpoint.

**What features are missing?** Exactly **two true code gaps** turned up in this audit (both fixed in this pack). Everything else that *looks* missing — search operators, scheduled send, snooze, phone-OTP honesty, the login-envelope leak — is **already fixed in the repo and gated only by deployment + credentials** (§5.3). The founder-side deploy checklist is `fixpack/verify/C2`.

Note: the reCAPTCHA work is complete per your notice and was **not touched**; the audit only recorded that old "skip" strings remain in source (`FinishScreens.swift`, `HumanVerifyScreen.kt`) as a cleanup note.

---

## 2. Scope & method

| area | extent | what was read |
|------|--------|---------------|
| iOS | 45 Swift files (~27.2K lines) | mail HTML rendering (WKWebView/CSP), attachment preview (QuickLook), push/notification chains, recovery & OTP screens |
| Android | 73 Kotlin files (~30.8K lines) | mail screens (Files/Attachment/Labels/Contacts), MailboxViewModel, Api/auth/session plumbing, build config, push |
| Web | 927 TS/TSX files | mail page, `SafeEmailBody` sanitizer + inline image preview, mail send/receive APIs |
| Backend | 9 services | search operators, scheduled send, attachments, phone-OTP, login projection, worker, schema |
| Research | 7 search waves | Compose perf, Coil 3, AMP Email, feature benchmark, push reliability, offline outbox |

Every finding below is traceable to a file/line, a persisted capture (listed in §10), or a cited source (§6). Where a claim comes from a repo document rather than code directly, it says so.

---

## 3. Q1 — Why the app is slow (diagnosis + fix)

**Findings**

1. `docs/PERF-AND-SMOOTHNESS-RECORD.md` (repo) records the smoothness work already landed: optimistic actions, parallel bulk operations, reader prefetch, search pill.
2. The slow APKs being compared are **debug builds of commits that predate those fixes**. Debug builds of Compose apps are expected to run 2–4× slower than release (R8/optimization off, Live Literals, interpreted paths); community measurements of full debug→release differences (R8 + Baseline Profiles) have cited figures around 75% faster startup / 60% faster frame rendering, and Google's own documentation measures **~30% faster code execution from the first launch with Baseline Profiles alone**.
3. The release configuration in the repo is already correct for performance: Compose BOM pinned, `profileinstaller:1.4.1` present (`app/build.gradle.kts` L118/L131), Kotlin 2.0.20 toolchain.

**Fix (process, no code change):** build `assembleRelease`, install on the test device, compare against the debug APK with the acceptance steps in `fixpack/verify/C1` §Performance. Judge speed only on release builds from current `main`.

---

## 4. Q2 — The unfinished "preview / interactive" mail (and what now ships)

What the audit found on Android:

- **Files screen (unit 78):** listed only attachments of messages the device had already opened — *"GrapMail has no endpoint that lists every file in a mailbox, so a file inside mail you have not read is not shown here."*
- **Attachment screen (unit 79):** no thumbnails anywhere; images were save/download only — by design, because there was no image loader.
- iOS already previews attachments via QuickLook; web and iOS both render sanitized HTML; Android has a parallel sanitized-HTML pipeline (`MailHtmlBody`).

What this pack changes:

- **Kit A (Android):** attachment **thumbnails** in the Files rows and on the attachment screen (Coil 3.0.4, pinned for Kotlin 2.0.20). Fallbacks preserved: PDF/ZIP/SVG and decode failures keep the exact icon block that shipped. The fetch goes through the app's own session-checked download path — no token ever reaches the image library — and cache files are deleted if the account changes mid-flight.
- **Kit B (backend):** `GET /grapmail/files` — every attachment in the mailbox, joined to its message subject, newest first, clamped pagination, mounted behind the same session middleware as the scheduled routes. Unauthenticated callers get 401, never a list.

**On AMP for Email** (the industry standard for "interactive" email): research shows ~16.3% client support overall (Gmail/Yahoo/Outlook.com partial; Apple Mail: none); a plain-HTML fallback is mandatory even where AMP renders. **Recommendation: do not build AMP now** — keep the sanitized-HTML path; revisit only if measured demand appears. This is a recommendation, not part of the fix pack.

---

## 5. Q3 — Feature audit across platforms

### 5.1 The repo's own blocker board (recorded 2026-11-08; re-verified by this audit where possible)

| # | blocker | status |
|---|---------|--------|
| 1 | No account deletion | FIXED (web): `POST /api/auth/account/delete` + `/account/delete` page + security-settings link; gateway endpoint + in-app deletion in flight |
| 2 | No push (30-min poll) | IN FLIGHT: backend push service (device registry + FCM/APNs dispatch), Android FCM client, iOS APNs client — **founder must supply Firebase + APNs keys** |
| 3 | Ephemeral CI keystore | PENDING: founder-owned keystore → GitHub secrets → Play App Signing |
| 4 | iOS cannot ship | IN FLIGHT: `BGTaskSchedulerPermittedIdentifiers` fix + archive/TestFlight job — **founder: Apple account, certs, secrets** |
| 5 | Stale production gateway + Next app | FOUNDER-EXECUTED: no agent production access; deploy runbook given |
| 6 | Login envelope leaks credentials | FIXED: `toPublicUser` allowlist at all 10 raw-row sites; regression test 6/6 pass |
| 7 | Email-OTP sign-in dead end | IN FLIGHT: typed 403 → code-entry screen → verify/resend routes (both platforms) |
| 8 | Recovery dead end | IN FLIGHT: recovery-email fallback + honest `NO_RECOVERY_CHANNEL` 4xx |
| 9 | targetSdk 34 | IN FLIGHT: bump to 36 + edge-to-edge audit |
| 10 | No crash reporting | DECISION PROPOSED: ship v1.0 on Play Vitals only; founder confirms |
| 11 | No HTML mail on mobile | IN FLIGHT: server-side sanitized HTML endpoint → WebView/WKWebView, scripts disabled |
| 12 | Vacation/forwarding never enforced | IN FLIGHT: enforcement in inbound delivery (loop-guard, once-per-sender, list suppression) |

### 5.2 Cross-platform capability matrix (this audit's reading of the code)

| capability | web | iOS | Android | backend |
|------------|-----|-----|---------|---------|
| Sanitized HTML mail | ✓ `SafeEmailBody` (strict sanitize, remote images opt-in, inline preview) | ✓ `MailHtmlBody` (CSP img gate, blocked-image chips, JS height measure) | ✓ parallel `MailHtmlBody` | ✓ sanitized-HTML endpoint (blocker 11, in repo) |
| Attachment preview | ✓ inline image preview | ✓ QuickLook | **add: thumbnails** (kit A — was icon+save only) | ✓ bytes endpoint (GET) |
| List every file | — | — | Files screen (opened mail only) | **add: `GET /grapmail/files`** (kit B) |
| Search operators | shares backend | shares backend | shares backend | ✓ `from:/to:/subject:/has:/is:/before:/after:/in:/label:` in repo (deploy-gated) |
| Scheduled send | send-later path | send-later path | send-later path | ✓ routes + worker promotion (deploy-gated) |
| Snooze | snooze/unsnooze | snooze/unsnooze | `MailAction.Unsnooze` present | ✓ box predicates (deploy-gated) |
| Push | — | APNs client | FCM client | device registry + dispatch (needs founder keys) |
| Phone OTP | honest failure until configured | same | same | hardened: `502 SMS_DELIVERY_UNAVAILABLE`, never a 500 |
| Login envelope | — | — | — | ✓ allowlist fix + test |
| Contacts | derived from loaded mail | derived | derived from loaded mail | no contacts endpoint (noted, not a blocker) |
| Labels | ✓ | ✓ | ✓ (Labels screen: list/create/edit) | ✓ |
| Offline outbox / draft autosave / custom snooze / multi-account polish | — | — | — | open client work (repo notes; post-1.0 candidates) |

### 5.3 The three buckets (the crucial sorting)

**A. True code gaps found — fixed by this fix pack:**
1. No files list-all endpoint → **B1 + B2**
2. Android attachment thumbnails ("no image loader in this build") → **A1–A5**

**B. Already fixed in the repo; gated only by deploy + env (founder actions):** search operators, scheduled send, snooze, phone-OTP honesty, login-envelope, deletion/recovery/OTP routes, push registry, sanitized HTML endpoint, vacation/forwarding. See `fixpack/verify/C2` for the deploy checklist (env vars, credentials, migrations, deploy order).

**C. Still-open product work (post-1.0 candidates, not implemented here):** offline outbox, draft autosave, custom snooze times, multi-account polish, contacts endpoint, server-side attachment thumbnails, AMP for Email (recommendation: no).

---

## 6. Research digest (sources cited)

**Compose debug-vs-release & Baseline Profiles** — debug builds disable R8/optimizations and use Live Literals; Google documents Baseline Profiles at **~30% faster code execution from first launch** and requires `isMinifyEnabled = true` for release (profiles may only be generated un-obfuscated). Community measurements compare full debug→release differences at multiples of speed (often 2–4×).
Sources: `developer.android.com/topic/performance/baselineprofiles/overview`; community Compose perf write-ups (Medium/trengrove, urhoba, skydoves).

**Image loading (Coil 3)** — `coil-compose` + `coil-network-okhttp` are the standard artifacts; `AsyncImage` accepts `onLoading`/`onSuccess`/`onError` callbacks (Dokka API verified). Version gate: **3.0.4 (2024-11-25) is the last line for Kotlin 2.0.x**; 3.1.0 (2025-02-04) requires Kotlin 2.1.10; latest is 3.6.3 (Sep 2026) — so the pack pins **3.0.4**.
Sources: `coil-kt.github.io/coil` (compose docs + API pages); Maven Central artifact listings.

**AMP for Email** — ~16.28% overall client support (caniemail.com); Gmail full since 2020; Yahoo/AOL and Outlook.com partial; Apple Mail none; plain-HTML fallback mandatory. Recommendation: defer.
Sources: `caniemail.com/features/amp`; `amp.dev`; Google Cloud blog.

**Email app feature benchmark (2025/2026 expectations)** — snooze, schedule send, undo send, unified inbox, offline read/compose with auto-queued sends, advanced/attachment search, templates, categorization, one-click unsubscribe (RFC 8058), dark mode, multi-account.
Sources: `sparkmailapp.com/blog/essential-features-good-email-app-must-have`; `getmailbird.com` comparison; `fastmail.help` snooze; `emailanalytics.com` (Gmail offline); `zapier.com` best-clients guide.

**Push reliability & offline** — collapse keys to de-duplicate, TTL to expire stale notifications, bounded retries, quiet hours/local-time, channel categories; offline outbox = local queue + optimistic UI + background sync, and the queue must survive app restarts (cautionary issue: redux-offline #484).
Sources: `medium.com/@engineervishvnath` (FCM groups/TTL/retry); `ashwanitiwari.com`, `nickwittenrood.com`, `flowium.com` (push best practices); `github.com/redux-offline/redux-offline/issues/484`.

---

## 7. The fix pack

| file | what it does | kind |
|------|--------------|------|
| `fixpack/android/A1-PATCH-gradle.md` | adds Coil 3.0.4 to `app/build.gradle.kts` | patch |
| `fixpack/android/A2-AttachmentThumb.kt` | new `AttachmentThumb` composable (authenticated, session-safe) | drop-in |
| `fixpack/android/A3-PATCH-FilesScreen.md` | Files rows: paperclip → thumbnail (anchor L441–445) | patch |
| `fixpack/android/A4-PATCH-AttachmentPreviewScreen.md` | hero image + doc-comment rewrite (anchors L469–476, L514–517) | patch |
| `fixpack/android/A5-PATCH-MailboxViewModel.md` | `attachmentPreviewFile()` — cached, account-checked fetch | patch |
| `fixpack/backend/B1-grapmail-files.ts` | `GET /grapmail/files` — list every attachment | drop-in |
| `fixpack/backend/B2-PATCH-auth-service-mount.md` | mount behind the session gate | patch |
| `fixpack/backend/B3-VERIFY-api.md` | curl checks (files, search, scheduled, phone, login envelope) | verify |
| `fixpack/verify/C1-ANDROID-VERIFY.md` | build + manual + performance acceptance | verify |
| `fixpack/verify/C2-DEPLOY-CHECKLIST.md` | env vars, credentials, deploy steps | verify |

**Apply order:** A1 → A2 → A3 → A4 → A5; B1 → B2; then verify (B3), build release (C1), deploy per C2.

---

## 8. Verification plan (summary)

- **Android:** `./gradlew :app:assembleDebug` compiles; then `assembleRelease` for perf. Manual pass: image rows/hero show thumbnails; PDF/ZIP keep icons; airplane mode falls back without crash; account-switch mid-flight never shows a stale image; second open is cache-only. Details in `fixpack/verify/C1`.
- **Backend:** unauthenticated `GET /grapmail/files` → 401; authenticated → `{files:[{id,messageId,name,mimeType,sizeBytes,createdAt,messageSubject}],count}`; `?limit` clamps; search operators return filtered results; scheduled list works; `phone/start` answers honest `502` until configured; login response contains no `passwordHash`/`refreshToken`. Details in `fixpack/backend/B3-VERIFY-api.md`.
- **Deploy:** follow `fixpack/verify/C2` (backend at current `main` → migrations → restart services → client builds).

---

## 9. Honest limitations

- **No compiler in the audit environment.** Every patch was written against exact captured code (line anchors + drift-check greps on each page) but must be built once; the acceptance steps are part of the deliverable.
- **Thumbnail design:** Kit A downloads the full attachment once into the app cache to draw a small thumbnail. A server-side thumbnail route is the proper later improvement (noted in A2 rather than pretended away).
- **Kit B column names** come from the 2026-11-18 schema snapshot; B1's header documents a 5-second re-verify before mounting.
- **Founder-only items** (production deploy, keystore, FCM/APNs keys, SMS provider, Apple account) cannot be executed from the audit environment — the repo's own audit states the agent has no production access; checklists are provided instead.
- **Evidence capture notes:** the audit mounts intermittently returned I/O errors; all reads were done in bounded chunks with retries and persisted to the notes files listed below. Nothing in this report rests on an uncaptured read.

---

## 10. Evidence index (persisted captures)

`/mnt/work/audit/notes/` — 30+ files, including: `web_audit_doc.md` (repo blocker board + deploy procedure), `be_worker_files.md`, `be_attach_full.md` / `be_attach_narrow.md` / `be_route_head.md` / `be_xcheck2.md` (attachments route, helpers, mount pattern), `schema_final.md` (table/column snapshot), `be_gates2.md` (search/scheduled/snooze evidence), `be_phone*.md` (OTP hardening), `coil_hookup.md` / `vm_session_api.md` / `anchors_real.md` / `fixpack_src.md` (Android patch anchors + session API), `ios_push2.md` / `android_push2.md` / `link_handling.md` / `ios_img.md` / `android_htmlbody2.md` (rendering & push), `readme_units.md` (unit map), `motion_docs.md`, `web_state2.md`, `mounts_final.md`, `as_mount_verify.md`, `xcheck_final.md`, `versions2.md`, `and_extras.md`, `vm_tail_verify.md`.

*Nothing in this report fabricates a feature, a fix, or a measurement. Where the evidence is a repo document, the report says so; where it is code, the file and line are given; where it is research, the source is cited.*
