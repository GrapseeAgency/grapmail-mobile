# Round 10 - Remote-Image Parity, the Reader Opt-In, and the Dormant Native Loader

**Date:** 2026-11-18 | **Platforms:** iOS + Android (+ honesty suite) | **Code changes:** none (evidence round)

## 1. What this round audited
The whole remote-image path on both platforms: the reader's images gate (the "N remote image(s) hidden" chip, the "Show images" button, and the warning that loading them tells the sender this message was opened), the opt-in plumbing that re-fetches the body with images allowed, the HTML surface's rules while the gate is closed, the native loader that lives in the handoff files, and the honesty suite's coverage of all of it. Reply-all recipient assembly and bcc were re-confirmed in passing. Verdict: parity holds; one dormant component found and documented (sections 3-4); no code change was justified this round.

## 2. Parity matrix (evidence index below)
| area | Android | iOS | verdict |
|---|---|---|---|
| gate copy + chip | ThreadScreen: hidden-count chip, "Show images", warning | MailReaderViews: same sentences | identical; pinned |
| opt-in keying | remember(message.id) per message card | @State private var shownImages = false | pinned |
| opt-in plumbing | vm.loadBody(message.id, shownImages); re-fetch with remoteImages=true | vm.loadBody(message.id, allowImages: allowImages); same contract | pinned |
| closed-gate fetch block | blockNetworkImage = !allowImages | same rule on the WebView surface | pinned |
| img extraction rule | only absolute http(s) kept | guard scheme == "http" || scheme == "https" | pinned |
| loader privacy | CookieJar.NO_COOKIES; own client, no auth header, no Api. route | config.httpCookieAcceptPolicy = .never | pinned |
| content-type discipline | if (!type.startsWith("image/")) | if !mime.hasPrefix("image/") | pinned |
| failure taxonomy | four named reasons, each said as itself | same sentences | pinned |
| reply-all assembly + bcc | VM 1600-1695 | MBM 1915-2005 | byte-parity (re-confirmed) |
| screen census | formatting editor, scheduled, subscriptions, e2e, empty states | same set present | census parity |

## 3. Finding: a policy-complete loader nobody can reach
Android `MessageHandoff.kt:186` / iOS `MailHandoff.swift:169` define `RemoteImageView` (with `remoteImageUrls` at :123/:66 and `fetchRemoteImage` at :232/:135; handoff files 21,059 B / 13,820 B). The component says three states honestly - loading, ready, or which of four failures it hit - runs its own no-cookie client (10 s connect / 15 s read), validates the content type before drawing, and carries a dedicated failure tag (mail-remote-image-failed).

The whole-tree sweep (all .kt/.swift in the audit mirror) found zero references outside the definition files on either platform - the only hits are the definitions and the component's own internal call:

```
-- [2a] per-file counts: RemoteImageView (kt+swift; nested+prof excluded) --
      1 /mnt/work/honrun/android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/MessageHandoff.kt
      1 /mnt/work/honrun/android/app/src/src/main/java/com/grapsee/grapmail/ui/screens/mail/MessageHandoff.kt
      1 /mnt/work/honrun/ios/GrapMail/MailHandoff.swift
-- [2b] per-file counts: remoteImageUrls( --
      1 /mnt/work/honrun/android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/MessageHandoff.kt
      1 /mnt/work/honrun/android/app/src/src/main/java/com/grapsee/grapmail/ui/screens/mail/MessageHandoff.kt
      1 /mnt/work/honrun/ios/GrapMail/MailHandoff.swift
-- [2c] per-file counts: fetchRemoteImage( --
      2 /mnt/work/honrun/android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/MessageHandoff.kt
      2 /mnt/work/honrun/android/app/src/src/main/java/com/grapsee/grapmail/ui/screens/mail/MessageHandoff.kt
      2 /mnt/work/honrun/ios/GrapMail/MailHandoff.swift
```

Meanwhile the reader's shipped opt-in goes elsewhere: tapping "Show images" re-fetches the body with `remoteImages=true` and lets the sanitized document render. `RemoteImageView` is never constructed.

Why this matters (the "completed yet not" thesis): in review the feature looks done - component, privacy policy, copy all exist and are polished. In the product, a different route keeps the user-visible promise, so this code stands as a seam artifact: dead weight that will drift away from the shipped path. The honesty suite pins the component's hygiene (no-cookie loader, content-type, failure sentences) but no pin checks a call site - it would not notice either way if the component gained or lost a caller.

## 4. Disposition (why nothing was changed)
- **Not removed:** the honesty suite reads the component's internals; retiring the component means retiring those pins in the same deliberate change - not a drive-by edit, and it cannot be verified against the tree while the mounts are away (section 6).
- **Not wired:** wiring would swap the reader's opt-in mechanism (per-image native render vs the shipped sanitized-document render). The shipped route already keeps the promise; that swap needs a design decision and device testing.
- **Recorded instead:** this note is the audit trail. At mount recovery, a confirmation sweep of the two unmirrored iOS extension trees plus any test source sets runs before any wire/remove decision.

## 5. Hygiene finding
`ios/GrapMail/ProfileModel.swift.pre-avfix` - a 22,928-byte stale backup of ProfileModel.swift sitting in the app folder (the extension is not a source type; builds unaffected). Recommend: check git tracking at recovery, then delete or ignore.

## 6. Scope, residuals, delivery status
- Sweep scope: all sources in the audit mirror - android/app/src (main + debug), ios/GrapMail, ios/GrapMailUITests, scripts, .github.
- Residual scope: ios/NotificationService, ios/NotificationShared and any Android test source sets were not in the mirror; the preview extension was fully read in Round 5 and shows no remote-image usage; confirmation sweep queued for mount recovery.
- Delivery: this note is in COS now. The mounts (grapmail-mobile, Grapsee_All_Time) have been detached since 10:40 UTC (sixteen consecutive probe rounds; window cadence continues). Queued for recovery, in order: R8 files + note, R9 files + note, this note.

## 7. Ledger
- Requirement: remote-image parity audit (gate, plumbing, rules, privacy, taxonomy) + treatment of any divergence | observed: parity verified; one dormant component documented; no divergence requiring a code change | evidence: recons a-f + honesty suite pin body | status: closed (evidence round); no files applied; CI unaffected (suite unchanged; after R8+R9, python3 scripts/mail-honesty.py holds at 574 pins - see R9 note).

## 8. Evidence index (sandbox)
- /mnt/work/audit2/notes/r10_recon_a.txt ... r10_recon_f.txt (token sweeps, census, handoff reads, call-site counts, pin body, strays)
- /mnt/work/audit2/notes/r89_window*.txt (recovery cadence logs)
- Staging: /mnt/work/kit/r8new/, /mnt/work/kit/r9new/ (pending mount application)
