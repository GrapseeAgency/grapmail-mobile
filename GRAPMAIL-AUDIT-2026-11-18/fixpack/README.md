# GrapMail fixpack — 2026-11-18

Implementation pack for the full native / web / backend audit.
Companion report: `../GRAPMAIL-APP-AUDIT-2026-11-18.md` (same folder tree).

## Apply order

1. **Android attachment thumbnails** — `android/A1` → `A2` → `A3` → `A4` → `A5`, then build + test per `verify/C1`.
2. **Backend files endpoint** — `backend/B1` → `B2`, deploy auth-service, then run `backend/B3`.
3. **Environment & credentials** — `verify/C2` (these are the deploy-gated items the audit found; code for them is already in the repo).

## File map

| file | what it does | kind |
|------|--------------|------|
| `android/A1-PATCH-gradle.md` | Adds Coil 3.0.4 to `app/build.gradle.kts` | patch |
| `android/A2-AttachmentThumb.kt` | New `AttachmentThumb` composable (authenticated, session-safe) | drop-in |
| `android/A3-PATCH-FilesScreen.md` | Files rows: paperclip icon → real thumbnail | patch |
| `android/A4-PATCH-AttachmentPreviewScreen.md` | Attachment screen hero image + doc-comment rewrite | patch |
| `android/A5-PATCH-MailboxViewModel.md` | `attachmentPreviewFile()` — the cached, account-checked fetch | patch |
| `backend/B1-grapmail-files.ts` | `GET /grapmail/files` — list every attachment in a mailbox | drop-in |
| `backend/B2-PATCH-auth-service-mount.md` | Mount the route behind the session gate | patch |
| `backend/B3-VERIFY-api.md` | curl checks incl. the deploy-gated features | verify |
| `verify/C1-ANDROID-VERIFY.md` | Android build + manual + perf acceptance checks | verify |
| `verify/C2-DEPLOY-CHECKLIST.md` | Env vars, credentials, deploy steps | verify |

## The three audit answers, one line each

- **Why slow/lagging:** the APKs that felt slow were *debug builds of pre-fix commits*. The release config (R8 + minify + Baseline Profile installer) is already correct — ship and install a release build (`verify/C1`).
- **Why preview was incomplete:** Android had no image loader — the code itself said so: an image was "offered as a download, not a thumbnail … pretending otherwise would show a broken box". Kit A is that loader, wired through the app's own authenticated fetch.
- **What was actually missing:** one real code gap — an endpoint that lists every file in a mailbox (kit B). Everything else the audit flagged (search operators, scheduled send, snooze, phone-OTP honesty, login-envelope) is already fixed in the repo and waiting on deployment + credentials (`verify/C2`).

## Honesty notes

- The audit sandbox cannot compile Kotlin or TypeScript: every patch was written against exact captured code (anchors included on every page) and must be built once locally. `C1` and `B3` are the acceptance tests.
- `B1`'s two table names / column spellings come from the 2026-11-18 schema snapshot; the file header documents how to re-verify in five seconds before mounting.
- Nothing here touches the reCAPTCHA work (user confirmed complete; audit noted only).
