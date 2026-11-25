# Round 11 — Reply, Forward, and the Threading Contract

**Date:** 2026-11-18 | **Platforms:** iOS + Android (+ honesty suite, CI walks) | **Code changes:** none (evidence round)

Every reply, reply-all, and forward surface was re-read end to end on both apps: the doors, the recipient assembly, the subject rules, the In-Reply-To discipline, the quote and forward formats, the failure paths, and the attachment behavior. **Result: full parity, no divergence.** The one threading defect this area ever had — iOS's case-sensitive subject-prefix check, fixed in Round 9 — remains fixed, and the contract the code documents for itself holds on both platforms: the quote is built from what the server actually said the message contains, never from the snippet the list row carries, and reply-all never silently drops the thread's other recipients.

---

## Why this round

Threading is the first thing a person judges in a mail client: does the reply reach the right people, does the subject stay clean, does the conversation stay one thread. Round 6 closed the outbound half of compose (receipt truth, draft hygiene, version gate). This round closes the inbound half — what the composer is filled with when Reply, Reply all, a saved draft, or Forward is opened.

## The doors

| Surface | Android | iOS |
|---|---|---|
| Reader footer trio | ThreadScreen L334–338 (the Reply / Reply all / Forward doors) | MailReaderViews L224–227; Reply all / Forward also at L117–118 |
| Reader overflow | ThreadScreen L461–500: Reply, Share (`mail-share`), Print (`mail-print`) | MailReaderViews L540–542: Share / Print |
| CI walks | android-walk checks the trio | iOS walk checks the trio |

## The assembly matrix

Android read verbatim at MailboxViewModel.prepareReply (L1621+, region captured L1595–1741); iOS at MailboxModel.prepareReply (captured; quote/signature/subject helpers byte-verified L2010–2065).

| Mode | To | Subject | Body | In-Reply-To |
|---|---|---|---|---|
| Saved draft | stored To list, verbatim | stored subject, verbatim | stored body, verbatim — no quote block, no "Re:", no second signature | stored In-Reply-To |
| Reply | sender's address | `replySubject()` | quote + signature | message id |
| Reply all | sender + To + Cc, trimmed, minus this account's own address, deduped | `replySubject()` | quote + signature | message id |
| Forward | empty — the person picks recipients | `forwardSubject()` | forwarded head + quote + signature | **null** |

Drafts: both platforms deliberately leave Bcc empty — the message DTO has no bcc field (the route strips it before storing), and the code comment says re-adding is better than showing a list it invented. The draft branch's comment is worth keeping: "A draft is not a reply: the row IS the person's own half-written message, so it loads verbatim… a saved message could never be finished or sent" (before this leg existed, tapping a draft opened a read-only thread).

## The quote block — byte parity

Both platforms build the same block:

- a blank line, then `On <createdAt | "an unknown date">, <display | address> wrote:`
- every body line prefixed `> `; an empty line becomes a bare `>`
- a trailing newline; an empty body quotes nothing — header only ("inventing a blank line under the header would claim the message had a body")

The signature block matches as well: the `-- ` line and the signature when set, nothing when blank; appended on reply / reply-all / forward, deliberately absent on drafts (a saved draft already carries the signature it was saved with).

## Subject rules

- replySubject: empty → `Re:`; already `Re:` or `Fwd:` (case-insensitive) → kept as-is; else `Re: <subject>`
- forwardSubject: empty → `Fwd:`; already `Fwd:` (case-insensitive) → kept; else `Fwd: <subject>`
- Android uses `startsWith(..., true)`; iOS uses `lowercased().hasPrefix(...)` — parity achieved by the Round 9 fix, whose subject these helpers were.

## Forward head

Android builds this block (verbatim); iOS mirrors it, with the raw `createdAt` string on the Date line:

```
Forwarded message
From: <display name or address>
Date: <createdAt>
Subject: <subject or "(no subject)">
To: <To list, comma-joined>

```

Then quote + signature. Forward carries **no In-Reply-To** — the server threads by that header, and "a forwarded message is not a reply" — and copies none of the original attachments: the forward is text; re-attaching is the sender's move.

## Failure paths (when the original cannot load)

Both platforms: cache-first — the notification cache (`MailStore`), then `GET /message/:id`; a generation guard discards a late fill if the composer was reset meanwhile (both platforms).

- `Could not load the original message.` — byte-identical, both platforms
- `Could not open this draft.` — byte-identical, both platforms
- Android additionally guards the draft-HTML leg with `Could not load the draft formatting.`

## Attachment handling

- Forward copies no original attachments (both platforms; by design — text carries over, files do not)
- Attachment management plumbing exists on both: Android `downloadAttachment` / `openAttachment` (L2064–2109); iOS `deleteAttachment` / `downloadAttachment` (L1396–1426)

## Honesty and CI coverage

The honesty suite already pins this area: forward never carries In-Reply-To (both platforms, ×2), reply-all drops the account's own address, the three doors are reserved and named, and a draft loads as its own text. CI walks exercise the three doors. No pin was added this round — no behavior changed, and the Round 9 pin already covers the subject helpers this round re-read.

## Evidence index

All files under `/mnt/work/audit2/notes/` (recon run at the applied revision; VM 161679 bytes = Round 6 applied, MBM 131137 bytes = Round 9 applied — both sizes match the delivered files):

| What | Where |
|---|---|
| Doors; iOS prepareReply; forward call sites; threading-header sweep | `r11_recon_a.txt` |
| Android assembly verbatim (L1595–1741; all four branches); iOS quote/signature/subject helpers (L2010–2065); reader overflow (L461–500); attachment refs | `r11_recon_a2.txt` |
| Recovery-string byte-parity; iOS cache-first; Share/Print parity | `r11_recon_a3.txt` |

## Status

**Verified clean. No code changes.** Deliverable: this note only (no `round11-applied-files/` directory, by design — Round 5 precedent). The mount-side copies of the Round 8–11 deliverables remain queued behind the detached mount; the guarded recovery windows keep re-running the push chains (20 consecutive dead probe rounds as of this writing; COS delivery of every round remains current).

Threading contract: **holds.**
