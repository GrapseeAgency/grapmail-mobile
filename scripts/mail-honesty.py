#!/usr/bin/env python3
"""Pin the four mailbox promises the design makes and the code can silently break.

    python3 scripts/mail-honesty.py            # report + exit 1 on a broken pin
    python3 scripts/mail-honesty.py --quiet    # only the failures

WHY THIS EXISTS
---------------
This mirrors `src/app/lib/__tests__/grapmail-send-status-honesty.test.ts` from
the web client, which the plan names (T1.3) and which settles the same way for
the same reason: the behaviour lives inside a view closure, so asserting on it
means mounting React and half the app around it. That test therefore asserts on
the SOURCE — the wording exists, the distinction is made — and so does this one.
On mobile the cost of mounting is higher still (an emulator per platform, a
macOS runner for one of them) while the risk being pinned is a word on a screen,
which no UI test is cheaper to write than to read.

WHAT IT PINS, AND WHY EACH ONE MATTERS
--------------------------------------
1. **The send receipt says what the server said.** `/grapmail/send` answers
   `delivered`, `queued` or `failed` and they mean different things: internal
   recipients are already in the other mailbox, external ones are in a queue
   whose outcome is not yet known, `failed` means it did not go. Collapsing
   queued into "Sent" is the defect the web test exists to stop, and it is quiet
   because every send the founder tests is internal — the one case where the
   optimistic sentence happens to be true.
2. **A message action travels in the URL path.** `PATCH /message/:id/:action`
   reads the action from the path; a body-encoded action would be ignored and
   the app would report success for a no-op.
3. **Permanent destruction is never a toolbar tap.** `PATCH .../trash` is
   reversible; `DELETE /message/:id` is not. A bulk bar that could destroy mail
   in one tap needs the confirmation sheet between the tap and the call.
4. **HTML bodies are not rendered.** The route returns the sender's HTML
   verbatim. Rendering it gives a remote message control of the app — remote
   images (a read receipt the sender did not have to ask for), restyling to look
   like a system warning, or script. There is no sanitiser in either app's
   dependencies, and the correct one is not a regex. A WebView appearing in the
   mail sources means someone found a way to skip that decision.
5. **The client does not advertise a box the server cannot read.** Snooze has a
   writer and an inbox-hider but no reader (`snoozed` is absent from the boxes
   the listing answers), so a "Snoozed" drawer row would promise mail the app
   cannot show. It stays out until the backend ships — the plan's T6.2.

Unlike `api-parity.py`, which compares two implementations against each other,
this compares both against the contract. A pair of platforms can agree on the
same wrong thing.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android/app/src/main/java/com/grapsee/grapmail"
IOS = ROOT / "ios/GrapMail"

QUIET = "--quiet" in sys.argv
failures: list[str] = []
checks = 0


def android(rel: str) -> str:
    return (ANDROID / rel).read_text(encoding="utf-8")


def ios(rel: str) -> str:
    return (IOS / rel).read_text(encoding="utf-8")


def pin(desc: str, ok: bool) -> None:
    """Record one assertion. `ok` False fails the run with the reason spelled out."""
    global checks
    checks += 1
    if ok:
        if not QUIET:
            print(f"  ok   {desc}")
        return
    failures.append(desc)
    print(f"  FAIL {desc}")


def code_only(src):
    """The file with its comments removed.

    Several pins forbid a WORD or a PATTERN, and the comments explaining each
    rule necessarily quote the thing they forbid -- `"Unsubscribed"` appears in a
    doc comment precisely to say it must never be displayed, and a hex literal
    appears in a token's comment to record what used to be hardcoded. Testing raw
    text made those pins fail on their own documentation, which is how a gate gets
    disabled — and it is also what made one pin pass on a comment instead of on
    the code it was written against.

    The first version of this helper had the bug it exists to prevent. It opened a
    block on any line starting with `/*` and decided on the same line whether that
    line had CLOSED one — `keep = "*/" not in st` — so a complete one-line doc
    comment (`/** The same object under the name the live envelope uses. */`)
    left it believing it was inside a block, and it stayed there until some later
    comment's `*/` line. Half of `Api.kt` disappeared: the interceptor and its
    mail-path carve-out were gone, which is how the failure surfaced — a new pin
    asking about `isMailCall` reported the code absent from code that plainly
    contains it. Any pin of that section had been reading a file that was not the
    file. A block is only opened when the line does not also close it, and the
    "already inside a block" test comes first, because a line inside a block can
    itself begin with `/*`.
    """
    # Second bug in this helper's history, found by mutation and not by reading:
    # the line-based version below dropped only comments that OWN a line, so
    # `request(...)  // PUT /screener/decision` kept its prose with its code, and
    # a route check was satisfied by the tail of a real line. `api-parity.py` blew
    # a retargeted route that way on a mutation written precisely to see whether it
    # could be missed. It scans characters now: both comment kinds go wherever they
    # sit, string and character literals are passed through untouched (a pin that
    # forbids a word must still see the word where it is rendered), and the `//`
    # inside "https://x" is not a comment.
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if c == '"':
            if src[i:i + 3] == '"""':
                out.append('"""'); i += 3
                while i < n:
                    if src[i] == "\\":
                        out.append(src[i:i + 2]); i += 2; continue
                    if src[i:i + 3] == '"""':
                        out.append('"""'); i += 3; break
                    out.append(src[i]); i += 1
                continue
            out.append('"'); i += 1
            while i < n:
                if src[i] == "\\":
                    out.append(src[i:i + 2]); i += 2; continue
                if src[i] == '"':
                    out.append('"'); i += 1; break
                if src[i] == "\n":
                    break        # unterminated on this line: stop, do not eat the file
                out.append(src[i]); i += 1
            continue
        if c == "'":
            # A character literal in either language, or an apostrophe in prose.
            # Only treat it as a literal when it closes within a few characters;
            # otherwise emit the mark and move on, because swallowing the rest of
            # the file to find the next quote is how a helper eats real code.
            j, run = i + 1, ["'"]
            while j < n:
                if src[j] == "\\":
                    run.append(src[j:j + 2]); j += 2; continue
                if src[j] == "'":
                    run.append("'"); j += 1; break
                if src[j] == "\n":
                    break
                run.append(src[j]); j += 1
            if len(run) > 1 and run[-1] == "'" and sum(len(x) for x in run) <= 6:
                out.append("".join(run)); i = j; continue
            out.append(c); i += 1
            continue
        out.append(c); i += 1
    return "".join(out)


# ── 1. the send receipt prints the server's own three words ──────────────────
# The reader that every "must NOT appear" pin below depends on, tested on its own
# text. Without these two the helper can silently eat half a file -- which is what
# it did, and the only reason it was noticed is that a new pin asked about a line
# the mutilated copy had swallowed.
pin("the comment reader keeps code after a one-line doc comment",
    "val kept = 1" in code_only("/** done. */\nval kept = 1\n"))
pin("the comment reader drops a whole block and keeps what follows",
    "middle" not in code_only("/**\n * middle\n */\nafter = 1\n")
    and "after = 1" in code_only("/**\n * middle\n */\nafter = 1\n"))

def strings_of(src):
    """The file's string literals only: comments and code dropped.

    `code_only` answers "what does the CODE say"; this answers "what does the
    app SAY". A wording pin ("the notification never claims delivery") is about
    text a person can read, and scanning it over code made one fail on a
    cleanup method whose API name carries the forbidden word
    (`removeAllDeliveredNotifications`) -- an identifier nobody reads as a
    sentence. Scanning literals keeps the rule on the wording and leaves the
    plumbing alone.

    A Swift `\\(...)` interpolation may contain further quotes, so the scanner
    can split one source literal into pieces. That can only ever make a pin
    MISS wording, never invent it: a split happens at a quote, and a word a
    pin forbids is contiguous, so no split can hide a fragment mid-word.
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if c == '"':
            # A triple-quoted block is one literal; none of these sources use
            # one, but a plain-quote read would close at the second mark and
            # leak the rest of the block into nothing.
            if src[i:i + 3] == '"""':
                end = src.find('"""', i + 3)
                if end < 0:
                    break
                out.append(src[i:end + 3])
                i = end + 3
                continue
            run, i = ['"'], i + 1
            while i < n:
                if src[i] == "\\":
                    run.append(src[i:i + 2])
                    i += 2
                    continue
                run.append(src[i])
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            out.append("".join(run))
            continue
        i += 1
    return "".join(out)

# Held to the same standard as the comment reader above: tested on its own text
# before any pin leans on it.
pin("the literal reader drops a comment and keeps the literal",
    strings_of("// \"no\"\nlet s = \"yes\"") == '"yes"')
pin("the literal reader keeps wording after an escaped quote",
    strings_of('print("a \\"b c")').find("b c") > 0)

KT_COMPOSE = android("ui/screens/mail/ComposeScreen.kt")
SW_READER = ios("MailReaderViews.swift")

for status in ("sent", "queued", "failed"):
    pin(f"Android receipt branches on the server's `{status}`",
        re.search(rf'"{status}"\s*->', KT_COMPOSE) is not None)
    pin(f"iOS receipt branches on the server's `{status}`",
        re.search(rf'case\s+"{status}"', SW_READER) is not None)

pin("Android has a distinct sentence for a queued send",
    "Queued for delivery" in KT_COMPOSE)
pin("iOS has a distinct sentence for a queued send",
    "Queued for delivery" in SW_READER)

# The collapsed wording the web test was written to prevent. If a future edit
# finds it shorter to say "Sent" for both, that is exactly the regression.
COLLAPSED = re.compile(r"Sent\.\s*Delivering|Delivering now", re.I)
for name, src in (("Android compose", KT_COMPOSE), ("iOS reader", SW_READER)):
    pin(f"{name} never collapses a queued send into 'delivering now'",
        not COLLAPSED.search(src))

# An unknown status must stay unknown rather than default to the good word.
pin("Android prints an unrecognised status verbatim",
    re.search(r'else\s*->\s*"Server reported', code_only(KT_COMPOSE)) is not None)
# Anchored on the arm itself and run through `code_only`. The first version of
# this pin was `re.search(r"(default|otherwise).*Server reported", re.S)`, which
# matched the word "otherwise" in a doc comment 640 lines above the `default:`
# arm — so deleting the arm that was being pinned left the gate green. That is
# the same failure class the label read was: a check that cannot fail is worse
# than no check, because it reports a covered surface.
pin("iOS prints an unrecognised status verbatim",
    re.search(r'default:\s*return\s*"Server reported', code_only(SW_READER)) is not None)

# ── 1b. the receipt frame photographs the live renderer, not a copy of it ────
#
# Both snapshot hosts used to define their own receipt view — `SendReceiptPreview`
# on Android, `SendReceiptView` on iOS — and each copy had already lost something:
# Android's dropped the semibold headline and the `detail` line, iOS's handled two
# of the four status words, so a `sent` frame would have read "Not delivered".
# A frame of a re-implementation looks right in a thumbnail, passes a PNG count and
# proves nothing about the screen a person actually sees — the same failure class as
# the eleven wrong screenshots. A host may invent DATA (a status word, a delivery
# error); it may not re-say the interface's own wording.
KT_SNAP = android("ui/screens/mail/MailSnapshot.kt")
SW_SNAP = ios("MailSnapshot.swift")

pin("Android's host calls the live SendReceipt",
    re.search(r"\bSendReceipt\(status", KT_SNAP) is not None)
pin("iOS's host calls the live SendReceipt",
    re.search(r"\bSendReceipt\(status", SW_SNAP) is not None)
pin("Android's host defines no receipt of its own", "SendReceiptPreview" not in KT_SNAP)
pin("iOS's host defines no receipt of its own", "SendReceiptView" not in SW_SNAP)
# Reachable from the host, because the host is a different file — and private
# would have forced a copy, which is how the drift started.
pin("Android's live receipt is visible to the snapshot host",
    re.search(r"internal fun SendReceipt\(", KT_COMPOSE) is not None)
pin("iOS's live receipt is visible to the snapshot host",
    re.search(r"^struct SendReceipt: View", SW_READER, re.M) is not None)

for label, src in (("Android", KT_SNAP), ("iOS", SW_SNAP)):
    for word in ("Queued for delivery", "Not delivered", "Server reported"):
        pin(f"{label}'s host does not re-say the receipt wording ({word!r})",
            word not in src)

# ── 2. the action is a path segment, on both platforms ───────────────────────
KT_API = android("data/Api.kt")
SW_API = ios("MailAPI.swift")

pin("Android puts the action in the message URL's path",
    re.search(r'/message/\$\{enc\(id\)\}/\$\{action\.apiValue\}', KT_API) is not None)
pin("iOS puts the action in the message URL's path",
    re.search(r'/message/\\\(id\.percentEncodedPath\(\)\)/\\\(action\.apiValue\)',
              SW_API) is not None)

# The only thing allowed into that PATCH body is the snooze deadline.
pin("Android sends a body only for snooze's `until`",
    KT_API.count("MailSnoozeRequest") >= 1 and '"{}"' in KT_API)

# ── autosave ────────────────────────────────────────────────────────────────
# The compose saves itself on a debounce, updating the draft row it created
# rather than inserting a twin per save. Three invariants matter: the request
# carries the draft's own id (the route's owner-scoped select is what keeps a
# stale id from becoming anything other than a new row), an empty compose
# schedules nothing — closing a composer nobody typed into must not leave a
# draft row — and send waits for an in-flight autosave before reading its id.
pin("Android's autosave carries the draft's own id",
    "id = cur.draftId.takeIf { it.isNotEmpty() }" in code_only(android("logic/MailboxViewModel.kt")))
pin("iOS's autosave carries the draft's own id",
    "id: self.compose.draftId.nilIfBlank()" in code_only(ios("MailboxModel.swift")))
pin("Android's autosave refuses an empty compose",
    "s.to.isBlank() && s.cc.isBlank() && s.subject.isBlank() && s.body.isBlank()"
    in code_only(android("logic/MailboxViewModel.kt")))
pin("iOS's autosave refuses an empty compose",
    "s.to.trimmingCharacters(in: .whitespaces).isEmpty"
    in code_only(ios("MailboxModel.swift")))


# ── 3. nothing destroys mail without the confirmation sheet ──────────────────
KT_BOX = android("ui/screens/mail/MailboxScreen.kt")
SW_BOX = ios("MailboxView.swift")

pin("Android's toolbar Delete moves mail to Trash and confirms erasure only inside Trash",
    "if (state.box == MailBox.Trash) showPermanentConfirm = true" in KT_BOX
    and "else vm.act(com.grapsee.grapmail.data.MailAction.Trash)" in KT_BOX)
pin("iOS's toolbar Delete moves mail to Trash and confirms erasure only inside Trash",
    "if vm.state.box == .trash { sheet = .permanent }" in SW_BOX
    and "else { vm.act(.trash) }" in SW_BOX)

# The permanent call must be a DELETE, and must not be reachable from `act`,
# which is the bulk path — otherwise a "Delete" that means "erase" could ride
# along with a multi-select.
pin("Android's permanent delete is its own DELETE call",
    re.search(r"fun mailDeletePermanent.*\.delete\(\)", KT_API, re.S) is not None)
pin("iOS's permanent delete is its own DELETE call",
    re.search(r"func mailDeletePermanent.*method:\s*\"DELETE\"", SW_API, re.S) is not None)
KT_VM = android("logic/MailboxViewModel.kt")
SW_MODEL = ios("MailboxModel.swift")


def body_of(src: str, header: str) -> str:
    """One member's text: from its signature to the next sibling declaration.

    The stop used to be three fixed strings at one indent level (`"\\n    fun "`
    and friends), which silently returned the whole file tail for any member
    written at column 0 — and `MailboxScreen.kt` puts every composable at column
    0, so the swipe slice was 540 lines of drawer, sheet and greeting. A negative
    pin over that slice is fragile in the wrong direction and a positive pin can
    be satisfied by any other member in the tail, which is the opposite of what a
    scope check is for. The stop is now a pattern that accepts any indent and the
    modifier spellings these files actually use.

    `val`/`var` are deliberately NOT stop markers: a function's own local
    `val tint = …` would cut the slice at its first statement.
    """
    tail = src.split(header, 1)
    if len(tail) < 2:
        return ""
    rest = tail[1]
    m = _MEMBER_STOP.search(rest)
    return rest[:m.start()] if m else rest


_MEMBER_STOP = re.compile(
    r"\n[ \t]*(?:@Composable[ \t]*\n[ \t]*|@ViewBuilder[ \t]*\n[ \t]*)?"
    r"(?:private[ \t]+|internal[ \t]+|public[ \t]+|fileprivate[ \t]+|static[ \t]+"
    r"|open[ \t]+|override[ \t]+)*(?:suspend[ \t]+)?(?:fun|func|class|struct|enum|actor)[ \t<(]"
)


def slice_found(src: str, header: str) -> bool:
    """A slice that exists. `"x" not in body_of(..., "missing(")` is True for a
    renamed member, which turns every negative pin into a pin on the header
    string being present — so the headers are checked, once, out loud."""
    return bool(body_of(src, header).strip())


pin("Android's bulk `act` cannot reach permanent deletion",
    "mailDeletePermanent" not in body_of(KT_VM, "fun act("))
pin("iOS's bulk `act` cannot reach permanent deletion",
    "mailDeletePermanent" not in body_of(SW_MODEL, "func act("))
pin("Android's bulk `actOn` cannot reach permanent deletion",
    "mailDeletePermanent" not in body_of(KT_VM, "fun actOn("))
pin("iOS's bulk `actOn` cannot reach permanent deletion",
    "mailDeletePermanent" not in body_of(SW_MODEL, "func actOn("))

# A swipe is the one filing gesture with no confirmation in front of it, so the
# set of things it can do is the safety boundary. Archive, trash and restore all
# undo; DELETE does not, and must not be reachable from a row gesture on either
# platform however convenient that would look.
KT_SWIPE = body_of(KT_BOX, "private fun SwipeableMessageRow")
pin("Android's swipe component cannot reach permanent deletion",
    "mailDeletePermanent" not in KT_SWIPE and "deletePermanently" not in KT_SWIPE)

# The span has to cover BOTH swipeActions blocks. Bounding it at the first
# closing brace at that indent — the obvious way to write it — stops after the
# leading block, so the trailing "Trash" button, which is exactly where a
# destructive edit would land, was outside what got checked. Caught by mutating
# the Trash button to call deletePermanently and watching the gate stay green.
SW_SWIPES = ios("MailboxView.swift")
_start = SW_SWIPES.find("swipeActions(edge: .leading")
_end = SW_SWIPES.find("onAppear", _start)
swipe_span = SW_SWIPES[_start:_end] if _start != -1 and _end > _start else ""
pin("iOS's swipe region was located (both blocks, not just the first)",
    "swipeActions(edge: .trailing" in swipe_span)
pin("iOS's swipe actions cannot reach permanent deletion",
    bool(swipe_span) and "deletePermanently" not in swipe_span
    and "mailDeletePermanent" not in swipe_span)
# Both directions must be conditional: a swipe that fires in a box where the
# action cannot change anything is a gesture wired to a no-op.
pin("Android gates each swipe direction on the box", "leadingTo != null" in KT_SWIPE
    and "trailingTo != null" in KT_SWIPE)
pin("iOS gates each swipe direction on the box",
    ios("MailboxView.swift").count("vm.state.box != .trash") >= 1
    and ios("MailboxView.swift").count("vm.state.box != .archive") >= 1)

# ── 4. only server-sanitized HTML reaches a renderer ────────────────────────
# The reader renders the HTML part through one island per platform
# (`MailHtmlBody.kt` / `MailHtmlBody.swift`). The ONLY string that may reach a
# WebView is the body endpoint's response — the same DOMPurify email profile
# the web's SafeEmailBody renders with, applied server-side before the HTML
# leaves the app's origin. The raw `bodyHtml` the message row carries is never
# a render input, page script is off at the engine level on both platforms,
# and a failed fetch falls back to the stripped-text branch the reader always
# had — never to raw HTML. A WebView anywhere else in mail code is still the
# original defect: the print path must stay WebView-free.
MAIL_SOURCES = ([ANDROID / "ui/screens/mail" / p for p in
                 sorted((ANDROID / "ui/screens/mail").iterdir())]
                + [ANDROID / "ui/components/Mail.kt"]
                + [IOS / p for p in sorted(IOS.iterdir()) if p.name.startswith("Mail")])

webview_files = sorted(f.name for f in MAIL_SOURCES
                       if "WebView" in code_only(f.read_text(encoding="utf-8", errors="ignore"))
                       or "loadHTMLString" in code_only(f.read_text(encoding="utf-8", errors="ignore")))
pin("only the two reader islands carry a WebView",
    webview_files == ["MailHtmlBody.kt", "MailHtmlBody.swift"])

kt_island = code_only(android("ui/screens/mail/MailHtmlBody.kt"))
sw_island = code_only(ios("MailHtmlBody.swift"))
pin("Android's island runs no page script", "javaScriptEnabled = false" in kt_island)
pin("iOS's island runs no page script", "allowsContentJavaScript = false" in sw_island)
pin("Android's island is fed only from the fetched body",
    "message.bodyHtml" not in kt_island)
pin("iOS's island is fed only from the fetched body",
    "message.bodyHtml" not in sw_island)
pin("Android's model loads the body endpoint", "Api.mailBody(" in KT_VM)
pin("iOS's model loads the body endpoint", "ApiClient.mailBody(" in ios("MailboxModel.swift"))
pin("the islands take the images opt-in from the gate",
    "allowImages = shownImages" in android("ui/screens/mail/ThreadScreen.kt")
    and "allowImages: shownImages" in ios("MailReaderViews.swift"))

# The thread view shows stripped text and says so, rather than silently
# presenting a downgrade as the original — now the FALLBACK for a failed
# formatted fetch, still stated rather than hidden.
pin("Android explains an HTML-only body instead of hiding the fact",
    re.search(r"(plain text|stripped|formatting)", KT_BOX if "stripHtml" in KT_BOX else
              android("ui/screens/mail/ThreadScreen.kt"), re.I) is not None)

# ── 5. the drawer offers only boxes the server can read back ────────────────
#
# Pinned against what is DEPLOYED, not against what is written. `?box=snoozed`,
# `boxCount` and the complement test exist in the backend's working history (T6.1)
# and pass their tests there, but that repo has not been pushed or deployed — 36
# other workstreams' commits carry 664 MB of build artifacts, so no push is
# possible from it today. Nothing has been fetched from the live endpoint to
# confirm either way, and no claim is being made about what it answers: the point
# is that the code that would serve the box has not reached a server, so a drawer
# row promising postponed mail would be a control wired to nothing. The two pins
# below stay until the deploy lands. When it does, the change is one commit and
# four edits, in this order: add `Snoozed` to `MailBox` and `drawerOrder` on both
# platforms, decode `stats.snoozed` in both DTOs (the field is already answered by
# the new code, and dropping it is why no badge could appear), invert these two
# pins into "the drawer advertises exactly the boxes the server answers", and
# rewrite the snooze sheet's line that still says the server has no such box — a
# claim `84-mail-snooze-sheet` photographs on both platforms.
KT_DTO = android("data/MailDto.kt")
drawer_kt = re.search(r"val drawerOrder[^=]*=\s*listOf\(([^)]*)\)", KT_DTO)
drawer_sw = re.search(r"static let drawerOrder:\s*\[MailBox\]\s*=\s*\[([^\]]*)\]", SW_API)
pin("Android declares a drawer order", drawer_kt is not None)
pin("iOS declares a drawer order", drawer_sw is not None)
if drawer_kt and drawer_sw:
    pin("Android's drawer does not advertise a Snoozed box",
        "Snoozed" not in drawer_kt.group(1))
    pin("iOS's drawer does not advertise a Snoozed box",
        "snoozed" not in drawer_sw.group(1))

# ── 6. the screener's four decisions are the server's four decisions ─────────
#
# `PUT /screener/decision` is validated by mail-service alone and the proxy
# relays its refusal verbatim, so the client carries a copy of a set it cannot
# enumerate at runtime. Offer a fifth word, or rename one, and every write using
# it fails at the store while the sheet still looks like it offered a reasonable
# choice.
#
# The expected set is stated literally here rather than read out of the backend
# repo, because the two are separate checkouts: a pin that depends on a sibling
# directory silently skips on the runner that is supposed to enforce it, and a
# gate that skips passes. The source of truth is named in the comment and in both
# enums' own docs so a reader can re-derive it.
SERVER_DECISIONS = {"approved", "muted", "later", "blocked"}   # screener.ts:60
SERVER_SCOPES = {"address", "domain"}                          # screener.ts:61


def swift_cases(block: str) -> set:
    r"""The member names declared by a Swift enum's `case` line.

    Two traps, both hit while writing this. `case approved, muted, later, blocked`
    is ONE declaration, so a `case (\w+)` match reads it as a single member and
    reports a set of one — a check that fails against correct code, which is the
    worst outcome for a gate because it teaches the reader to ignore it. And these
    enums also contain `switch` bodies whose `case .approved: return "Approve"`
    lines are not members at all, so dotted names are skipped and anything after a
    colon is dropped.
    """
    out = set()
    for m in re.finditer(r"^\s*case ([^\n{]+)$", block, re.M):
        for part in m.group(1).split(","):
            name = part.split(":")[0].strip()
            if name and not name.startswith(".") and name.isidentifier():
                out.add(name)
    return out


kt_decision_block = re.search(r"enum class MailDecision\((.*?)\n\}", KT_DTO, re.S)
pin("Android declares a MailDecision enum", kt_decision_block is not None)
kt_decisions = set(re.findall(r'\("([a-z]+)"', kt_decision_block.group(1))) \
    if kt_decision_block else set()
pin("Android's decisions are exactly the server's four", kt_decisions == SERVER_DECISIONS)

sw_decision_block = re.search(r"enum MailDecision[^{]*\{(.*?)\n\}", SW_API, re.S)
pin("iOS declares a MailDecision enum", sw_decision_block is not None)
pin("iOS's decisions are exactly the server's four",
    swift_cases(sw_decision_block.group(1)) == SERVER_DECISIONS if sw_decision_block else False)

pin("Android declares both server scopes",
    set(re.findall(r'\("(address|domain)"', KT_DTO)) == SERVER_SCOPES)
sw_scope_block = re.search(r"enum MailSenderScope[^{]*\{(.*?)\n\}", SW_API, re.S)
pin("iOS declares both server scopes",
    swift_cases(sw_scope_block.group(1)) == SERVER_SCOPES if sw_scope_block else False)

# Both calls must send the server's spelling, not the enum's name — a `rawValue`
# on a differently-cased case would silently 400.
pin("Android's decision call sends the api value",
    re.search(r"MailScreenerDecisionRequest\(sender, scope\.apiValue, decision\.apiValue\)",
              KT_API) is not None)
pin("iOS's decision call sends the api value",
    re.search(r"scope: scope\.apiValue, decision: decision\.apiValue", SW_API) is not None)

# A standing rule affects future delivery. Saving it must leave the current
# queue alone and must not spend two reads refreshing unchanged data.
KT_VM = android("logic/MailboxViewModel.kt")
KT_DECISIONS = KT_VM[KT_VM.index("    fun decideSenders("):KT_VM.index("    // ── files")]
SW_SCREENER_VM = ios("MailboxModel.swift")
SW_DECISIONS = SW_SCREENER_VM[SW_SCREENER_VM.index("    func decideSenders("):
                             SW_SCREENER_VM.index("    /// The distinct senders")]
pin("a sender decision does not reload the unchanged queue",
    all("loadScreener()" not in block and "invalidate(" not in block
        for block in (KT_DECISIONS, SW_DECISIONS)))

# The surface writes decisions and cannot read them back, so no option may be
# drawn as the one already in force — that would be an invented fact.
pin("Android's decision sheet marks no option as current",
    re.search(r"SheetOption\(d\.title,\s*false\)", android("ui/screens/mail/MailboxScreen.kt")) is not None)
pin("iOS's decision sheet marks no option as current",
    re.search(r"SheetOption\(label: d\.title,\s*active: false\)", ios("MailSheets.swift")) is not None)

# The receipt must say what happened, which for a DELETE means honouring
# `removed: false` as "there was nothing to forget" rather than as a failure or
# as a confident "forgotten".
pin("Android distinguishes a removed decision from an absent one",
    "removed == true" in KT_VM or "r.removed" in KT_VM)
pin("iOS distinguishes a removed decision from an absent one",
    "removed == true" in ios("MailboxModel.swift"))

# A sender address goes into a query parameter on the delete path. Unencoded, a
# value containing `&` would become a second parameter — so both sides encode.
pin("Android encodes the query values on the remove call",
    re.search(r"\?scope=\$\{enc\(scope\.apiValue\)\}&sender=\$\{enc\(sender\)\}", KT_API) is not None)
pin("iOS encodes the query values on the remove call",
    "percentEncodedQuery()" in SW_API)

# ── 7. the Label sheet reports what it read, and can undo what it did ────────
#
# The label surface shipped with the write wired and the read absent, on both
# platforms at once, so the parity diff that exists to catch asymmetry saw nothing:
# two sides agreeing to omit something passes a name-by-name comparison. These pins
# hold the contract against the SERVER rather than against each other.
SW_SHEET = ios("MailSheets.swift")
SW_VM = ios("MailboxModel.swift")
KT_STORE = android("data/MailStore.kt")
SW_STORE = ios("MailStore.swift")
KT_SECOND = android("ui/screens/mail/MailSecondaryScreens.kt")
SW_SECOND = ios("MailSecondaryViews.swift")
KT_HOST = android("ui/screens/mail/MailSnapshot.kt")
SW_HOST = ios("MailSnapshot.swift")

# Matched on the declaration and the route path, not on the bare name. A first
# version used `"mailLabelAssignments" in src`, and renaming the iOS function to
# `mailLabelAssignmentsREMOVED` still passed it — a substring is satisfied by the
# thing it was meant to forbid. The path is asserted too, because a correctly named
# call against the wrong URL is the failure that actually reaches a user.
pin("Android declares the assignment read against the right route",
    re.search(r"suspend fun mailLabelAssignments\(token", KT_API) is not None
    and '"$MAIL/labels/assignments"' in KT_API)
pin("iOS declares the assignment read against the right route",
    re.search(r"static func mailLabelAssignments\(_ token", SW_API) is not None
    and '"/labels/assignments"' in SW_API)

# The defect itself: every row rendered with a hardcoded `false`, i.e. the sheet
# asserting that no message carries any label, having never asked.
pin("Android's label sheet no longer marks from a constant",
    re.search(r"SheetOption\(l\.name,\s*false\)", KT_BOX) is None
    and re.search(r"marks\[l\.id\]", KT_BOX) is not None)
pin("iOS's label sheet no longer marks from a constant",
    "SheetOption(label: l.name, active: false)" not in SW_SHEET
    and "marks[l.id]" in SW_SHEET)

# "Never asked" must survive as its own state in the cache. Collapsed into an empty
# map, an unread mailbox renders as an unlabelled one — and a tap then removes a
# label on a guess.
pin("Android's cache separates unread from empty",
    re.search(r"private var assignments: Map<String, Set<String>>\? = null", KT_STORE) is not None
    and re.search(r"assignments = null", KT_STORE) is not None)
pin("iOS's cache separates unread from empty",
    re.search(r"private var assignments: \[String: Set<String>\]\?", SW_STORE) is not None
    and "assignments = nil" in SW_STORE)

# A label must be removable, not just addable — the half of the surface that had no
# caller on either platform.
#
# Asserted on the LINE that calls it, not on the file: the first version was a bare
# substring test, and wrapping the call in `if (false)` left the pin green while
# making detach unreachable again — which is precisely the defect this pin exists to
# notice. Reachability is the claim, so reachability is what gets checked.
def call_lines(src, needle):
    return [ln for ln in src.split("\n") if needle in ln]


pin("Android's model detaches on a live branch",
    any("remove" in ln for ln in call_lines(KT_VM, "Api.mailLabelDetach")))
pin("iOS's model detaches on a live branch",
    any("remove" in ln for ln in call_lines(SW_VM, "mailLabelDetach")))

# When the read fails, the sheet says so rather than showing a clean list.
pin("Android's sheet states a failed read",
    "Couldn't read which labels they carry" in KT_BOX)
pin("iOS's sheet states a failed read",
    "Couldn't read which labels they carry" in SW_SHEET)

# An absent mark resolves to unknown, never to off. The qualified name is allowed:
# `MailboxScreen.kt` refers to the data types by full path as a matter of house
# style, and a pin that only matches the short spelling fails for a reason that has
# nothing to do with the behaviour it is meant to hold.
pin("Android resolves a missing mark to Unknown",
    re.search(r"\?:\s*(?:com\.grapsee\.grapmail\.data\.)?LabelMark\.Unknown", KT_BOX) is not None)
pin("iOS resolves a missing mark to unknown",
    "?? .unknown" in SW_SHEET)

# ── 8. the label palette is the server's eight keys, not a local set of colours ─
#
# `normaliseColour` (`grapmail-labels.ts:55-58`) keeps only these words and replaces
# anything else with `slate`. Both apps sent a hex string and parsed the stored value
# as a hex string, so the picker chose one colour, the store kept `slate`, and the
# list drew a third thing (the fallback) — three states for one field, none of them
# matching. The expected set is written literally here, not read out of the backend
# repo: the two are separate checkouts, and a pin that silently skips on the runner
# meant to enforce it is a pin that passes.
SERVER_COLOURS = {"slate", "red", "amber", "green", "teal", "blue", "violet", "pink"}

kt_keys = re.search(r"val LABEL_COLOUR_KEYS = listOf\(([^)]*)\)", android("ui/components/Mail.kt"))
sw_keys = re.search(r"let labelColourKeys = \[([^\]]*)\]", ios("MailComponents.swift"))
pin("Android declares a label palette", kt_keys is not None)
pin("iOS declares a label palette", sw_keys is not None)
if kt_keys and sw_keys:
    got = {k.strip().strip('"') for k in kt_keys.group(1).split(",") if k.strip()}
    pin("Android's label palette is exactly the server's eight keys",
        got == SERVER_COLOURS)
if sw_keys:
    got_sw = {k.strip().strip('"') for k in sw_keys.group(1).split(",") if k.strip()}
    pin("iOS's label palette is exactly the server's eight keys", got_sw == SERVER_COLOURS)

# Neither side may send a colour value where a key is required, nor read one back.
pin("Android's label form sends the key, not a hex string",
    "hexString(" not in KT_SECOND and "createLabel(name.trim(), chosen" in KT_SECOND)
pin("iOS's label form sends the key, not a hex string",
    "Self.hex(" not in SW_SECOND and "colour: colour" in SW_SECOND)

# Both platforms promised a label "becomes a filter on the inbox". Nothing filters:
# the listing carries no label field per row and accepts no label query. A parity
# diff cannot catch this because both sides said the same untrue thing.
#
# Matched on the RENDERED string (the opening quote is part of the pattern), not on
# the phrase alone — the comments explaining the fix quote the old copy, and a pin
# that reads raw file text would then fail on the correction rather than on the
# defect. Any assertion of this kind that cannot tell code from commentary has to
# name the shape of the literal it is actually forbidding.
pin("Neither app promises a label filter it does not have",
    '"Create one and it becomes a filter' not in KT_SECOND
    and '"Create one and it becomes a filter' not in SW_SECOND
    and '"Create one and it becomes available' in KT_SECOND
    and '"Create one and it becomes available' in SW_SECOND)

# The sheet's three states must be photographed, not merely implemented: the tick
# and the dash exist in code that no read-only CI mailbox can reach.
pin("Android's host draws all three label states",
    "LabelMark.Mixed" in KT_HOST)
pin("iOS's host draws all three label states",
    '"l2": .mixed' in SW_HOST)

# -- 9. a snooze can be undone, and the undo is on screen -----------------------
#
# `unsnooze` existed in both action enums with nothing calling it. The reason is a
# genuine limit: `publicRow` carries no `snoozedUntil` and the inbox hides pending
# snoozes, so the app cannot see which messages are postponed. The answer was not to
# leave the verb dangling but to offer the undo where the app DOES know -- immediately
# after it snoozed something.
KT_BOX = android("ui/screens/mail/MailboxScreen.kt")
KT_THREAD = android("ui/screens/mail/ThreadScreen.kt")
SW_READ = ios("MailReaderViews.swift")
SW_BOX = ios("MailboxView.swift")

pin("Android's model sends unsnooze",
    any("Unsnooze" in ln for ln in call_lines(KT_VM, "Api.mailAction")))
pin("iOS's model sends unsnooze",
    any("unsnooze" in ln for ln in call_lines(SW_VM, "mailAction")))
pin("Android's undo is reachable from the screen, not only the model",
    "vm.undoSnooze()" in KT_BOX)
pin("iOS's undo is reachable from the screen, not only the model",
    "vm.undoSnooze()" in SW_BOX)

# -- 10. the unsubscribe offer goes to a browser and claims nothing -------------
#
# `List-Unsubscribe` is the sender's own claim about a URL, read off an untrusted
# header. Two rules are pinned: only http(s) is ever offered, and no screen states
# an outcome the app cannot observe.
for side, src_file, decl in (("Android", android("ui/components/Mail.kt"), "fun parseUnsubscribe"),
                             ("iOS", ios("MailComponents.swift"), "func parseUnsubscribe")):
    # Matched on each language's own declaration keyword. Slicing from the bare name
    # instead finds the first MENTION of it -- which is the doc comment above, so the
    # slice stops at that comment's closing brace and the parser body is never read.
    start = src_file.find(decl)
    parser = src_file[start:src_file.find("\n}", start)] if start >= 0 else ""
    pin(f"{side}'s unsubscribe parser exists and accepts only http(s)",
        "https://" in parser and "http://" in parser and "mailto" not in parser)

# The claim that must never be rendered: that the list removed anyone.
pin("Android's thread never claims the user was unsubscribed",
    '"Unsubscribed' not in code_only(KT_THREAD))
pin("iOS's reader never claims the user was unsubscribed",
    '"Unsubscribed' not in code_only(SW_READ))
for side, src in (("Android", KT_THREAD), ("iOS", SW_READ)):
    pin(f"{side}'s unsubscribe sheet names whose server it goes to",
        "not to GrapMail" in src)
    pin(f"{side}'s unsubscribe sheet states the liveness cost",
        "not risk-free" in src)

# -- 11. dark mode is structural, not aspirational ------------------------------
#
# Three frames are photographed in dark (home, thread, labels). What carries the rest
# of the band is that no screen picks its own colour: every paint comes from a table
# holding a light AND a dark value. A literal at a call site is invisible in a
# light-theme screenshot and wrong on a dark canvas -- which is exactly how the starred
# marker sat, amber-on-white, in both apps until this pin was written.
KT_SCREENS = ["ui/screens/mail/MailboxScreen.kt", "ui/screens/mail/ThreadScreen.kt",
              "ui/screens/mail/ComposeScreen.kt", "ui/screens/mail/MailSecondaryScreens.kt"]
SW_VIEWS = ["MailboxView.swift", "MailChrome.swift", "MailSheets.swift",
            "MailReaderViews.swift", "MailSecondaryViews.swift"]
# The two component files are NOT in these lists, because each holds one table
# that is allowed to name colours: `labelSwatch`, keyed by the server's palette
# key with a value per theme. Section 18 checks them separately — it strips that
# table and then requires no literal to survive, so the claim here has no exempt
# file, only a separately-checked one.

for side, files in (("Android", KT_SCREENS), ("iOS", SW_VIEWS)):
    leaked = []
    for rel in files:
        src = code_only(android(rel) if side == "Android" else ios(rel))
        if re.search(r"Color\(0x[0-9A-Fa-f]{6,8}\)", src):
            leaked.append(rel)
    pin(f"{side}'s mail screens pick no colour of their own: no literals in {len(files)} files",
        not leaked)

pin("Android's label swatch resolves a dark value",
    "isSystemInDarkTheme" in android("ui/components/Mail.kt"))
pin("iOS's label swatch resolves a dark value",
    re.search(r"func labelSwatch.*?dark:", ios("MailComponents.swift"), re.S) is not None)


# -- 12. the two receipts must speak the same words ----------------------------
#
# The send receipt is one screen drawn twice, and the parity gate compares call
# names and DTO fields -- not the bodies of a `when` and a `switch`. Android
# therefore handled sent/queued/failed while iOS also handled `draft`, the word
# the draft-save path reports on success: on Android a successful draft save fell
# through to "Server reported: draft" in warn ink, under a line that said "Saved
# to Drafts". This is the same "both sides agree on the same wrong thing" hole the
# label read was, caught from the other direction -- both sides work, the two
# disagree.
def switch_arms(src, start_marker, pattern):
    i = src.find(start_marker)
    if i < 0:
        return None
    end = src.find("\n    }", i)
    if end < 0:
        # Without this guard `src[i:src.find(...)]` reads `-1` as "up to the last
        # character" and the arm set silently becomes the whole file — a check
        # that reports "both sides agree" about something it never scoped.
        return None
    body = src[i:end]
    # re.M: both patterns are anchored at a line start to pick ARMS rather than
    # every quoted word in the file, and without MULTILINE `^` matches only the
    # beginning of the slice -- which reads as "the switch has no arms" and fails
    # a check written against correct code.
    return {m for m in re.findall(pattern, body, re.M)}


kt_arms = switch_arms(KT_COMPOSE, "when (status)", r'^\s{8}"([a-z]+)" ->')
sw_arms = switch_arms(SW_READER, "var headline: String", r'case "([a-z]+)":')
pin("Android's receipt arms were located", kt_arms is not None)
pin("iOS's receipt arms were located", sw_arms is not None)
if kt_arms is not None and sw_arms is not None:
    pin("Both receipts name the same status words",
        kt_arms == sw_arms,
        )
    pin("The receipts cover the server's four status words",
        {"sent", "queued", "failed", "draft"} <= (kt_arms & sw_arms))

# -- 13. the marks a sheet draws must actually be drawn -----------------------
#
# Android's "on" mark was `Text("", ...)` inside a filled disc: it painted nothing,
# so the state that tells a user a tap will REMOVE a label looked like a solid dot
# while iOS drew a checkmark. Same for the star, which was a hardcoded light amber.
pin("Android's sheet tick is a glyph, not an empty string",
    'Text("", fontSize = 10.sp' not in KT_BOX)
pin("Android's starred row marker comes from the palette",
    "Color(0xFFF5A623)" not in android("ui/components/Mail.kt"))
pin("iOS's starred row marker comes from the token table",
    "GM.warning : GM.textTertiary" not in ios("MailComponents.swift"))

# -- 14. a label list that could not be read is not an empty label list --------
pin("Android's label sheet distinguishes a refused read from an empty list",
    "Couldn't read your labels." in KT_BOX)
pin("iOS's label sheet distinguishes a refused read from an empty list",
    "Couldn't read your labels." in SW_SHEET)

# -- 15. a write the server refused is not a write that succeeded --------------
#
# `execute()` reports the status; it does not judge it, and only `read`/`fail`
# throw. Four Android label calls were written as a bare `execute(req)` with the
# returned pair dropped, so a 409 name collision, a 404 label that is not the
# caller's and a 400 body each came back as success: the sheet printed "Saved."
# over a label the server had rejected, and the cache recorded a pair that does
# not exist — after which the NEXT tap reads its add-or-remove direction from that
# invention. iOS cannot make this mistake, because `ApiClient.request` judges the
# status for every call, and `api-parity.py` cannot SEE it, because it compares
# declarations and fields rather than error behaviour. Two sides agreeing to omit
# something is the hole this whole file exists to cover.
VOID_WRITERS = ["mailRenameLabel", "mailDeleteLabel",
                "mailLabelAssign", "mailLabelDetach"]
pin("Android declares one throwing helper for the bodyless writes",
    re.search(r"private suspend fun executeVoid\(request: Request\)[\s\S]{0,220}?"
              r"if \(status >= 400\) fail\(status, body\)", code_only(KT_API)) is not None)
for _fn in VOID_WRITERS:
    _body = body_of(KT_API, f"suspend fun {_fn}(")
    pin(f"Android's `{_fn}` slice was located", bool(_body.strip()))
    pin(f"Android's `{_fn}` sends its write through the throwing helper",
        "executeVoid(" in _body)
pin("Android drops no HTTP status anywhere in the mail API",
    re.search(r"^\s{4,8}execute\(", code_only(KT_API), re.M) is None)

# Both platforms used to carve mail out of the 401-is-expired rule, because the
# mailbox was a different backend that refused the account API's `st1` bearer.
# Sign-in now goes through the gateway that owns the mailbox, so the two tokens
# are one token: a 401 on any authenticated call means the session is dead, and a
# carve-out would keep an expired session alive behind a screen that cannot load.
# Neither client carves mail out any more. Android's signal carries the token
# the server rejected, and the collector clears only the session that token
# belongs to — a 401 from a request still in flight when a fresh sign-in
# completed must not kill the new session.
pin("iOS's mail routes end no account session on a 401",
    "signalUnauthorized" not in code_only(SW_API))
SW_TRANSPORT = body_of(ios("APIClient.swift"), "static func authenticatedResponse")
pin("iOS retries an authenticated 401 before deciding whether to end the session",
    "http.statusCode == 401" in SW_TRANSPORT
    and "SessionStore.replacementToken(for: rejected, generation: generation)" in SW_TRANSPORT
    and SW_TRANSPORT.index("await renewal(") < SW_TRANSPORT.index("signalUnauthorized(")
    and "case .unavailable:" in SW_TRANSPORT
    and 'ApiError.http(status: 503' in SW_TRANSPORT)
pin("iOS attachment requests use the same authenticated transport as JSON",
    "authenticatedResponse(for: req, bearer: bearer)" in body_of(SW_API, "private static func mailBinary")
    and "authenticatedResponse(for: req, bearer: bearer)" in body_of(ios("APIClient.swift"), "static func request<T:"))
KT_RENEWAL = code_only(android("data/SessionRefresh.kt"))
pin("Android reuses a rotated bearer only for the matching session",
    "rotation.sessionId == current.id" in KT_RENEWAL
    and "rotation.rejected == rejected" in KT_RENEWAL
    and "SessionRefresh.retryToken(ctx, rejected)" in code_only(android("data/Api.kt")))
pin("Android attachment downloads can cancel the underlying HTTP request",
    "executeResponse(" in body_of(android("data/Api.kt"), "suspend fun mailAttachmentBytes")
    and "continuation.invokeOnCancellation { call.cancel() }" in code_only(android("data/Api.kt")))
pin("Android signals a dead session on every authenticated 401",
    "!isMailCall" not in code_only(KT_API)
    and 'removePrefix("Bearer ")' in code_only(KT_API)
    and "fun signalExpired(rejectedToken: String)"
        in code_only(android("data/SessionEvents.kt"))
    and "clearIfToken" in code_only(android("MainActivity.kt")))

# -- 16. the confirmation names the destination the tap will actually reach ----
#
# `List-Unsubscribe` is the sender's own text. A header of
# `<https://grapsee.com@evil.example/u>` sends the browser to `evil.example`,
# because everything before the last `@` of an authority is credentials, not
# host — and the one sentence whose job is to name the stranger would have led
# with our own domain. Both parsers must also read angle-bracketed segments
# FIRST: the header's separator is a comma and a comma is legal inside a query,
# so splitting before validating truncates `?t=abc,def` and opens a request the
# sender never wrote.
KT_MAIL_C = android("ui/components/Mail.kt")
SW_MAIL_C = ios("MailComponents.swift")
pin("Android's host reader strips the credentials from an authority",
    "substringAfterLast('@', authority)" in code_only(KT_MAIL_C))
pin("iOS's host reader strips the credentials from an authority",
    'host.lastIndex(of: "@")' in code_only(SW_MAIL_C))
for side, src, bracket, split in (
        ("Android", KT_MAIL_C, 'Regex("<([^>]+)>")', "raw.split(','"),
        ("iOS", SW_MAIL_C, 'pattern: "<([^>]+)>"', 'raw.split(separator: ",")')):
    code = code_only(src)
    pin(f"{side}'s unsubscribe reader takes bracketed addresses first",
        bracket in code and code.index(bracket) < code.index(split))

# Which opener iOS uses is a fact about the SDK, not a preference: `OpenURLAction`
# has no completion form on the runner ("extra arguments at positions #2, #3") and
# its one-argument call returns `()` ("cannot convert value of type '()' to expected
# condition type 'Bool'"), so it cannot report an outcome at all.
# `UIApplication.shared.open(_:options:completionHandler:)` can, and that is the
# evidence Android has from `startActivity` not throwing. Neither side may claim
# the list acted; both claim only that the device took the link.
pin("iOS hands the unsubscribe link to an API that answers",
    "UIApplication.shared.open(target" in code_only(SW_READER))
pin("iOS does not test a Void-returning opener as a condition",
    re.search(r"if\s+openURL\(", code_only(SW_READER)) is None)
pin("Android's opener still reports a device with no browser",
    "ActivityNotFoundException" in android("ui/screens/mail/ThreadScreen.kt")
    or "No app on this device can open that link" in android("ui/screens/mail/ThreadScreen.kt"))

# -- 17. a wait the user started must be a wait that can end --------------------
#
SW_LOAD = body_of(SW_MODEL, "func load(replace")
KT_LOAD = body_of(KT_VM, "fun load(replace")
SW_REFRESH = body_of(SW_MODEL, "func loadAndSettle")
pin("iOS's refresh awaits the inbox task without polling",
    "await loadTask?.value" in SW_REFRESH and "Task.sleep" not in SW_REFRESH)
pin("Android reports inbox settlement to release the refresh gesture",
    KT_LOAD.count("settleGeneration + 1") >= 3)
pin("Android rethrows a cancelled read instead of reporting it",
    "catch (e: kotlinx.coroutines.CancellationException)" in KT_LOAD
    and "throw e" in KT_LOAD and "runCatching { Api.mailInbox" not in KT_LOAD)
pin("iOS ignores a cancelled read instead of reporting it",
    "if error is CancellationError || Task.isCancelled" in SW_LOAD)
pin("Pagination cannot supersede an in-progress inbox read",
    "loadJob?.isActive == true" in body_of(KT_VM, "fun loadMore")
    and "loadTask == nil" in body_of(SW_MODEL, "func loadMore"))

# A flag raised before an `await` must be lowered on the abandoned path too.
pin("iOS's abandoned assignment read resolves its own flag",
    re.search(r"guard sessionIsLive else \{\s*\n?[ \t]*(?://[^\n]*\n)*[ \t]*let stillThere",
              code_only(SW_MODEL)) is not None
    or "assignmentsRead = stillThere" in code_only(SW_MODEL))
SW_LABEL_REFRESH = body_of(SW_MODEL, "func refreshLabels")
pin("iOS clears the label spinner only when the current read settles",
    "defer {" in SW_LABEL_REFRESH
    and "if !Task.isCancelled {" in SW_LABEL_REFRESH
    and "if self.labelState.loading { self.labelState.loading = false }" in SW_LABEL_REFRESH)
pin("iOS's undo drops the ids that came back",
    "snoozeUndoIds.filter { $0 != id }" in code_only(SW_MODEL))
pin("Android's home entry records the box it fetched",
    "loadedBoxes.add(_state.value.box)" in body_of(KT_VM, "fun ensureLoaded("))
pin("iOS records a memoised body on the cache hit as well as the fetch",
    re.search(r"if let cached = await MailStore\.shared\.body\(id\) {[\s\S]{0,700}?threadLoadedFor = id",
              code_only(SW_MODEL)) is not None)

# -- 18. the sender disc is a token, and it is the reference's token ------------
#
# The avatar ramp sat in the two component files as ten hardcoded light-only
# literals, which those files' exclusion from the no-literals gate made
# invisible: the claim "every paint comes from a table holding a light AND a dark
# value" was printed while the most-visited pixel of every row was exempt. The
# web reference's ramp (`src/app/(mail)/mail/page.tsx:305`) is EIGHT entries and
# three of them are theme variables, so the correct table has a value per theme.
# The native ramps keep sender slots consistent across platforms and use
# slate for the former red slot.
AVATAR_LIGHT = ["1A73E8", "0B8043", "A142F4", "455A64", "E37400", "00796B", "8E24AA", "3949AB"]
AVATAR_DARK = ["8AB4F8", "0B8043", "A142F4", "546E7A", "FBBC04", "00796B", "8E24AA", "3949AB"]
KT_COLORS = android("ui/theme/Color.kt")
SW_THEME = ios("Theme.swift")


def hex_run(src: str, decl: str) -> list[str]:
    """The hexes inside one token list, in order, until the list closes. Not
    capped at eight: a ninth colour is a change to the reference palette, and a
    check that quietly ignored it would report the ramp as verified."""
    i = src.find(decl)
    if i < 0:
        return []
    end = src.find("\n)", i)
    return [m.upper() for m in re.findall(r"0x(?:FF)?([0-9A-Fa-f]{6})\b",
                                          src[i:end if end > i else i + 700])]


pin("Android's light avatar ramp has eight themed colors",
    hex_run(KT_COLORS, "val GrapMailAvatarRampLight") == AVATAR_LIGHT)
pin("Android's dark avatar ramp has eight themed colors",
    hex_run(KT_COLORS, "val GrapMailAvatarRampDark") == AVATAR_DARK)
def list_body(src: str, decl: str) -> str:
    """A literal array/dictionary declaration's own text, from its name to the
    closing bracket on a line of its own. `body_of` cannot scope this one: the
    members of `GM` are `static let`s, and the next stop marker it recognises is
    a function two tokens further down, so a token added AFTER the ramp would
    silently join the count and break a check that has nothing to do with it."""
    i = src.find(decl)
    if i < 0:
        return ""
    j = src.find("\n    ]", i)
    return src[i:j] if j > i else ""


SW_PAIRS = re.findall(r"color\(light: (0x[0-9A-Fa-f]{6}), dark: (0x[0-9A-Fa-f]{6})\)",
                      list_body(SW_THEME, "static let avatarRamp"))
pin("iOS's avatar ramp was located with eight light/dark pairs", len(SW_PAIRS) == 8)
pin("iOS's avatar ramp matches Android value for value",
    [p[0][2:].upper() for p in SW_PAIRS] == AVATAR_LIGHT
    and [p[1][2:].upper() for p in SW_PAIRS] == AVATAR_DARK)
pin("Android's disc picks its ramp by appearance and re-remembers on a flip",
    "isSystemInDarkTheme()" in code_only(KT_MAIL_C)
    and "remember(address, display, appearance)" in code_only(KT_MAIL_C))
# The swatch table is the one place in the components file allowed to hold hexes:
# it IS a token table, keyed by the server's palette key, with a value per theme.
def without_swatch(src: str) -> str:
    return src.replace(body_of(src, "fun labelSwatch("), "")


pin("Android's label swatch table was located", bool(body_of(KT_MAIL_C, "fun labelSwatch(").strip()))
pin("Android's mail components hold no colour outside the swatch table",
    re.search(r"Color\(0x[0-9A-Fa-f]{6,8}\)", code_only(without_swatch(KT_MAIL_C))) is None)
pin("iOS's mail components hold no colour of their own",
    re.search(r"Color\(0x[0-9A-Fa-f]{6,8}\)", code_only(SW_MAIL_C)) is None)

# -- 19. every screen the host can draw is a screen somebody photographs --------
#
# Both hosts carried a `mail-home-dark` case that no walk launched: the dark home
# is the SAME frame taken with the appearance forced, so the extra name was a
# second copy of a screen with no purpose, and nothing could tell it had gone
# stale. Two names for one screen is how a snapshot host starts inventing
# screens, so the name sets are compared against what each walk actually asks
# for, in both directions.
KT_WALK = (ROOT / "scripts/android-walk.sh").read_text(encoding="utf-8")
SW_WALK = (ROOT / "ios/GrapMailUITests/ScreenshotWalkUITests.swift").read_text(encoding="utf-8")
host_kt = set(re.findall(r'^\s+"([a-z0-9-]+)" ->', KT_HOST, re.M))
host_sw = set(re.findall(r'case "([a-z0-9-]+)":', SW_HOST))
launch_kt = set(re.findall(r'--es gmSnapshot "([a-z0-9-]+)"', KT_WALK)) \
    | set(re.findall(r'"([a-z0-9-]+):\d{2,3}-[a-z0-9-]+"', KT_WALK))
# The Android walk launches most frames from its `spec` table (`"mail-empty:63-
# mail-empty"`), and only the dark pass spells out `--es gmSnapshot "name"`. The
# first version of this pin read only the explicit launches and reported 24 of 27
# host screens as dead — a gate that cries wolf about 24 things is a gate that
# gets widened until it says nothing, so both spellings count as a launch.
launch_sw = set(re.findall(r'\("\d{2,3}-[a-z0-9-]+", "([a-z0-9-]+)"', SW_WALK))
pin("Android's snapshot hosts were located", len(host_kt) >= 20)
pin("iOS's snapshot hosts were located", len(host_sw) >= 20)
pin("Both snapshot hosts can draw exactly the same mail screens", host_kt == host_sw)
# One return per arm, and no more arms than returns. Deleting a `case` by its
# header line leaves the arm's `return AnyView(...)` behind as a statement inside
# the PREVIOUS arm — compiles as unreachable code, does not fail the name set,
# and only the compiler names it ("cannot find 'drawerFrame' in scope", on the
# second such orphan). Counting arms against returns catches the shape here, in
# the seconds it takes to run, instead of the minutes a macOS runner costs.
frame_sw = SW_HOST[SW_HOST.find("private var frame: AnyView"):]
frame_sw = frame_sw[:frame_sw.find("\n    }\n") if "\n    }\n" in frame_sw else len(frame_sw)]
arms_sw = len(re.findall(r'^\s+(?:case "[a-z0-9-]+"|default):', frame_sw, re.M))
pin("iOS's snapshot switch returns exactly once per arm", arms_sw > 0 and
    arms_sw == len(re.findall(r"return AnyView\(", frame_sw)))
for side, host, launched in (("Android", host_kt, launch_kt), ("iOS", host_sw, launch_sw)):
    # `account-` joined `mail-` with the Phase-1 band and `e2e-` with Phase 3:
    # screens launched as `NN-account-*` / `NN-e2e-*` would otherwise slip the
    # dead/ghost check entirely -- a name-set check that only watches `mail-*`
    # lets a dead or ghost screen through on either side, the exact hole this
    # section was written for.
    band = ("mail-", "account-", "e2e-")
    dead = {n for n in host if n.startswith(band)} - launched
    pin(f"{side}'s host defines no mail screen its walk never photographs", not dead)
    ghost = {n for n in launched if n.startswith(band)} - host
    pin(f"{side}'s walk asks for no mail screen its host cannot draw", not ghost)

# The dark frames must carry both proofs, and the home's was the missing one:
# a `gm-snapshot` marker is present on the host's own "Unknown mail snapshot"
# fallthrough, so a mistyped name photographs a placeholder and still fills the
# PNG gate. Android spells each frame out, so its home names itself in the text;
# iOS runs one loop over three frames, so its proof is that the home's tuple
# carries a content marker at all — it used to carry `nil`, and the loop's
# fallback was a wait on the `mail-search` FIELD, which the snapshot host's
# `listFrame` never draws. An assertion that can only time out is not a proof.
i = SW_WALK.find('("61-mail-home-dark", "mail-home"')
sw_home_tuple = SW_WALK[i:i + 120] if i >= 0 else ""
pin("iOS's dark home frame names the content it waits on",
    bool(re.match(r'\("61-mail-home-dark", "mail-home", "[^"]+"\)', sw_home_tuple)))
pin("iOS's dark loop proves the host recognised the name for every frame",
    'XCTAssertFalse(app.staticTexts["Unknown mail snapshot: \\(screen)"].firstMatch.exists'
    in SW_WALK)
pin("iOS's dark loop waits on the marker for every frame",
    'app.staticTexts[marker].firstMatch.waitForExistence' in SW_WALK)
i = KT_WALK.find("61-mail-home-dark")
kt_home_window = KT_WALK[max(0, i - 1600):i + 400] if i >= 0 else ""
pin("Android's dark home proves the host recognised the name",
    "Unknown mail snapshot: mail-home" in kt_home_window)
pin("Android's dark home waits on seeded content before the shot",
    "Q3 facilities invoice" in kt_home_window)

# -- 20. a sheet may not deny a capability the same screen is offering ----------
#
# Both snooze sheets still ended "unsnoozing needs the message to be visible
# first" after the Undo banner shipped — a line photographed in
# `84-mail-snooze-sheet` on both platforms, so the false claim is an artifact, not
# just copy. The honest form keeps the half that is still true (the server has no
# box that lists postponed mail) and drops the half the build contradicts.
SNOOZE_PARTS = ("until it is due.", "The server has no",
                "that lists what is postponed", "mail this session snoozed")
for side, src in (("Android", KT_BOX), ("iOS", SW_SHEET)):
    pin(f"{side}'s snooze sheet no longer says unsnoozing needs the message visible",
        "unsnoozing needs the message to be visible" not in code_only(src))
    # Each fragment is checked whole, because both sides write the sentence across
    # concatenated string literals: a single joined sentence would straddle a line
    # break and a whole-quote test would fail copy that is word-for-word correct.
    pin(f"{side}'s snooze sheet states what Undo actually covers",
        all(part in src for part in SNOOZE_PARTS))
pin("Both snooze sheets make the same promise in the same words",
    all(all(part in src for part in SNOOZE_PARTS) for src in (KT_BOX, SW_SHEET)))

# `Set - Set` refused to compile on this runner's toolchain in both spellings, and
# a silent no-op in the detach would have looked like a label that would not come
# off. `remove` has no overload resolution to lose.
pin("iOS's detach mutates the set instead of subtracting from it",
    re.search(r"func markUnassigned\([\s\S]{0,400}?labels\.remove\(labelId\)", code_only(SW_STORE)) is not None
    and re.search(r"func markUnassigned[\s\S]{0,400}?\) - ", code_only(SW_STORE)) is None)

# -- 22. a pill may not light up for a state it was never given -----------------
#
# Item 1 was the Label SHEET claiming an assignment it had not read; the same
# defect lived in the label STRIP, one file away, and the parity gate could not
# see it for the same reason: Android derived the Inbox pill from the list's own
# query and filter, and iOS passed `selected: true` -- the pill stayed
# highlighted through a search term and through the Unread filter. The comment in
# both files justified the missing tap by claiming per-row assignments are not
# readable anywhere, which became false the moment this session wired
# `GET /labels/assignments`; the strip is display-only because the plan asked for
# a strip, and saying otherwise teaches the next reader to trust the wrong reason.
SW_CHROME = ios("MailChrome.swift")
pin("iOS's Inbox pill mirrors the list's query and filter",
    re.search(r'selected:\s*query\.isEmpty && filter == \.all', code_only(SW_CHROME)) is not None)
pin("the inbox header shows the current folder without repeating drawer navigation",
    "Text(vm.state.box.title)" in code_only(SW_BOX)
    and "LabelStrip(labels:" not in code_only(SW_BOX)
    and "BoxTabRow(state:" not in code_only(SW_BOX)
    and "state.box.title" in code_only(KT_BOX)
    and "BoxTabRow(state," not in code_only(KT_BOX))
pin("Android's Inbox pill mirrors the same two fields",
    re.search(r'LabelChip\("Inbox"[^\n]*s\.query\.isEmpty\(\) && s\.filter == MailFilter\.All',
              code_only(KT_BOX)) is not None)
# Kotlin's `isEmpty()` is a function and Swift's is a property, and writing the
# first inside the second is a compile error the local gates cannot see -- the
# pill's pin above passed happily on `query.isEmpty()` for one full runner session
# before the macOS build named it ("cannot call value of non-function type
# 'Bool'"). The class is pinned now, in every iOS source, because a Kotlin-ism is
# not a style slip in a repo where one app is written in each language.
for swift in sorted(IOS.iterdir()):
    if swift.suffix == ".swift":
        pin(f"iOS's {swift.name} calls no `isEmpty()`",
            ".isEmpty(" not in code_only(swift.read_text(encoding="utf-8")))
for side, src in (("Android", KT_MAIL_C), ("iOS", SW_CHROME)):
    # What can be pinned is the positive half: the strip's own documentation names
    # the route that makes a filter possible. A grep cannot tell "asserting a false
    # reason" from "quoting one to refute it", and the rewritten notes do the
    # latter on purpose -- pretending otherwise would add a pin that fails on good
    # prose and passes on a lie.
    pin(f"{side}'s label strip names the assignment sweep a filter would need",
        "/labels/assignments" in src)

# -- 21. the sources at least parse as sources ----------------------------------
#
# Neither app can be compiled on this host, so a missing brace reaches CI and
# costs a runner session to discover — the Android walk alone is two hours. This
# is NOT a parser and does not try to be one: it strips comments and string
# literals in one pass and then asks whether the three bracket kinds still balance
# per file. It caught a `},` that a closure rewrite had dropped in
# `MailReaderViews.swift`, on a line no diff read had questioned, and a regex
# version of the same check had pronounced that file balanced because it paired a
# quote inside a comment with a quote 200 lines later and deleted the code in
# between. Lexing first is the whole difference between the two.
def strip_lex(src: str) -> str:
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if c == '"':
            triple = src[i:i + 3] == '"""'
            i += 3 if triple else 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if triple and src[i:i + 3] == '"""':
                    i += 3
                    break
                if not triple and src[i] == '"':
                    i += 1
                    break
                if not triple and src[i] == "\n":
                    break        # unterminated on this line: stop, do not eat the file
                i += 1
            continue
        if c == "'":
            i += 1
            while i < n and src[i] != "'":
                i += 2 if src[i] == "\\" else 1
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


BALANCE_FILES = [
    ("Android", "data/Api.kt"), ("Android", "data/MailDto.kt"),
    ("Android", "data/MailStore.kt"), ("Android", "data/SessionStore.kt"),
    ("Android", "logic/MailboxViewModel.kt"),
    ("Android", "ui/components/Mail.kt"),
    ("Android", "ui/theme/Color.kt"), ("Android", "ui/theme/Theme.kt"),
    ("Android", "ui/screens/mail/MailboxScreen.kt"),
    ("Android", "ui/screens/mail/ThreadScreen.kt"),
    ("Android", "ui/screens/mail/ComposeScreen.kt"),
    ("Android", "ui/screens/mail/MailSecondaryScreens.kt"),
    ("Android", "ui/screens/mail/MailSnapshot.kt"),
    ("iOS", "MailAPI.swift"), ("iOS", "MailStore.swift"), ("iOS", "MailboxModel.swift"),
    ("iOS", "MailboxView.swift"), ("iOS", "MailChrome.swift"), ("iOS", "MailSheets.swift"),
    ("iOS", "MailReaderViews.swift"), ("iOS", "MailSecondaryViews.swift"),
    ("iOS", "MailComponents.swift"), ("iOS", "MailSnapshot.swift"), ("iOS", "Theme.swift"),
    ("iOS", "GrapMailUITests/ScreenshotWalkUITests.swift"),
]
for side, rel in BALANCE_FILES:
    # The UI-test file lives outside GrapMail/, so it needs the repo path rather
    # than the two source roots the rest of this file reads from.
    path = (ANDROID / rel) if side == "Android" else \
        (ROOT / "ios" / rel) if rel.startswith("GrapMailUITests") else (IOS / rel)
    body = strip_lex(path.read_text(encoding="utf-8"))
    unbalanced = {a: body.count(a) - body.count(b) for a, b in (("{", "}"), ("(", ")"), ("[", "]"))}
    pin(f"{side}'s {rel} has its brackets in balance",
        all(v == 0 for v in unbalanced.values()))

# -- 23. an emulator defence that stays silent is not a defence -----------------
#
# The Android walk's artifact for `a2ae4b6` was twenty-three copies of the
# notification shade: every mailbox frame the same 117 KB SystemUI capture, 107
# assertions timed out, and the two-hour ceiling -- not any check -- is what ended
# it. Two defences existed and both failed quietly: `collapse_shade` asked
# `dumpsys statusbar` whether the shade was down and only collapsed it when a
# version-specific spelling matched, and `ensure_foreground` grepped for
# `topResumedActivity`, one of several spellings, then relaunched without saying a
# word. A 40 KB size floor accepted the shade because byte size cannot tell a real
# screen from someone else's UI.
KT_WALK_HELPERS = (ROOT / "scripts/android-walk-helpers.sh").read_text(encoding="utf-8")


def shell_fn(name):
    i = KT_WALK_HELPERS.find(f"{name}() {{")
    if i < 0:
        return ""
    j = KT_WALK_HELPERS.find("\n}", i)
    return KT_WALK_HELPERS[i:j + 2] if j > i else KT_WALK_HELPERS[i:]


collapse = shell_fn("collapse_shade")
pin("the walk's shade helper was located", bool(collapse))
pin("the shade is collapsed unconditionally, not when a grep agrees",
    "cmd statusbar collapse" in collapse and 'grep -q "mShadeOpen' not in collapse)
pin("the shade helper says when dumpsys disagrees with the screen",
    '[shade]' in collapse)
fg = shell_fn("ensure_foreground")
app_fg = shell_fn("app_in_foreground")
pin("the foreground helper was located", bool(fg) and bool(app_fg))
pin("the foreground check reads two dumpsys sources and matches the package itself",
    app_fg.count("dumpsys") >= 2 and "mCurrentFocus" in app_fg and '"$PKG"' in app_fg)
pin("a lost foreground is reported and counted, not swallowed",
    '[foreground]' in fg and "FOREGROUND_LOSSES" in fg)
pin("the walk aborts on consecutive screens that are not the app",
    re.search(r"SNAP_MISS=\$\(\(SNAP_MISS \+ 1\)\)[\s\S]{0,600}SNAP_MISS\" -ge 5[\s\S]{0,400}?exit 1",
              KT_WALK) is not None)
pin("a recovered snapshot resets that counter",
    re.search(r"else\s*\n\s+SNAP_MISS=0", KT_WALK) is not None)
# The snapshot loop is only the second half of the walk. The shade run died at the
# FIRST tap -- 'Create Account' -- and then crawled through every later leg for an
# hour and a half, so an abort that only watches snapshot markers would have let
# the same run burn its ceiling again. Every `must` counts now (the assertions on
# that counter live just below, in section 23's block reader).
pin("a successful interaction resets that counter",
    re.search(r"else\s*\n\s+MUST_MISS=0", KT_WALK) is not None)
# The recovery itself must not be silent. `ensure_foreground` relaunched with
# `am start -W … >/dev/null`, which threw away the one line that says whether the
# activity exists, installed, or died -- and three runs ended with the Pixel
# launcher photographed as a mailbox screen and no explanation anywhere in the log.
launch_lines = [l for l in fg.split("\n") if "am start" in l]
pin("the foreground relaunch keeps what `am start` said",
    bool(launch_lines) and all(">/dev/null" not in l for l in launch_lines)
    and "[foreground] am start" in fg)
def shell_block(src, start_marker, end_marker):
    """A slice of a shell script from a marker to the next `end_marker` line.

    Used instead of one long regex with a `{0,1600}` window: the abort body has
    grown comments and dumps, and a window that silently stops covering them
    reports the defence as missing when the code simply got more careful. A slice
    with an explicit end says how much it read.
    """
    i = src.find(start_marker)
    if i < 0:
        return ""
    j = src.find(end_marker, i)
    return src[i:j + len(end_marker)] if j > i else src[i:]


must_abort = shell_block(KT_WALK, "MUST_MISS=$((MUST_MISS + 1))", "\n      exit 1")
pin("the walk counts a run of failed interactions and stops on it",
    'MUST_MISS" -ge 8' in must_abort and "exit 1" in must_abort)
pin("the abort prints what the frameworks say is on screen",
    "dumpsys activity activities" in must_abort and "dumpsys window" in must_abort)
pin("the abort asks whether the package is installed and what crashed",
    "pm list packages" in must_abort and "logcat -d -b crash" in must_abort
    and "AndroidRuntime" in must_abort)
pin("the crash dump is taken from its header, so the exception class survives",
    re.search(r'logcat -d -b crash[^|]*\|\s*grep -A\d+ "FATAL EXCEPTION"', must_abort) is not None)

# -- 24. a ViewModel built by reflection must have the constructor the factory asks for
#
# The Android walk has not been photographing mail since `d9868c4`: it has been
# photographing the Pixel launcher, because the app died inside its first
# `setContent` with
#
#   NoSuchMethodException: com.grapsee.grapmail.logic.MailboxViewModel.<init>
#   [class android.app.Application]
#
# Adding `snapshotMode: Boolean = false` to the constructor is what did it. A
# Kotlin default parameter emits ONE Java constructor -- `(Application, boolean)`
# -- unless `@JvmOverloads` asks for the shorter overloads too, and the default
# `ViewModelProvider` factory builds an `AndroidViewModel` subclass by reflecting
# for `<init>(Application)`. Nothing in a build, a type check, a parity diff or a
# screenshot can see this: the code is valid Kotlin, the class is valid, and the
# method it needs is simply absent from the bytecode. Only a run finds out, which
# is exactly why eight runs found out nothing until the walk was made to say so.
pin("MailboxViewModel's reflective constructor is the one the factory looks for",
    re.search(r"class MailboxViewModel @JvmOverloads constructor", KT_VM) is not None)
for kt in sorted(ANDROID.rglob("*.kt")):
    src = kt.read_text(encoding="utf-8")
    for m in re.finditer(r"^class\s+\w+[\s\S]{0,400}?AndroidViewModel\(", src, re.M):
        header = m.group(0)
        has_default = re.search(r":\s*\w+\s*=", header) is not None
        pin(f"Android's {kt.name} ViewModel{'' if not has_default else ' with a default param'} "
            f"emits the Java constructor a factory can call",
            (not has_default) or "@JvmOverloads" in header)

# -- 25. the account-record band -- eight screens, one document, nothing fake ----
#
# Phase 1 put the web client's `/mail/notes`, `/mail/tasks`, `/mail/settings`
# and `/mail/account/{profile,privacy,addresses,connections,support}` on both
# phones. Every screen reads the one `GET /auth/account/profile` document
# (`auth-account-profile.ts:175-214`), and the sector's whole defect history
# says the danger is not the missing field but the invented one: a control the
# server cannot honour, a default that reads as an answer, a delete button in
# front of an endpoint that does not exist. Each pin below can fail: rename a
# screen on one side, add a vacation switch, swap "could not be read" for an
# empty string, and the run goes red naming the file.
KT_ACCT = android("ui/screens/mail/MailAccountScreens.kt")
SW_ACCT = ios("MailAccountViews.swift")
KT_PVM = android("logic/ProfileViewModel.kt")
SW_PVM = ios("ProfileModel.swift")
KT_MAIN = android("MainActivity.kt")
SW_ROOT = ios("RootView.swift")
SW_APIC = ios("APIClient.swift")

# The eight screens, by the names each platform spells, both sides or neither.
for kt_name, sw_name in (("NotesScreen", "NotesView"), ("TasksScreen", "TasksView"),
                         ("MailPrefsScreen", "MailPrefsView"),
                         ("AddressesScreen", "AddressesView"),
                         ("ConnectionsScreen", "ConnectionsView"),
                         ("ProfileEditScreen", "ProfileEditView"),
                         ("PrivacyScreen", "PrivacyView"),
                         ("SupportScreen", "SupportView")):
    pin(f"Android ships its {kt_name}", f"fun {kt_name}(" in KT_ACCT)
    pin(f"iOS ships its {sw_name}", f"struct {sw_name}: View" in SW_ACCT)

# One model per platform holds the document; the nav hosts one instance for all
# eight screens (eight copies of one record is eight chances to disagree).
pin("Android shares the profile through the signed-in home entry",
    "ProfileViewModel = signedInViewModel(nav, entry)" in KT_MAIN
    and "nav.getBackStackEntry(Routes.HOME)" in KT_MAIN
    and "return viewModel(home)" in KT_MAIN)
pin("iOS replaces the profile when the signed-in stack resets",
    "private var account: ProfileModel { signedInModels.account }" in SW_ROOT
    and "account = ProfileModel()" in body_of(SW_ROOT, "func reset()"))
for side, src, guard_word in (("Android", KT_PVM, "snapshotMode"), ("iOS", SW_PVM, "snapshotSeeded")):
    pin(f"{side}'s profile model keeps the snapshot seed out of the boot-read race",
        guard_word in src)

# NO DEAD TOGGLES: the profile stores vacation/forwarding and the sieve
# interpreter supports the actions, but no code turns a setting into a rule,
# so neither the write type nor any screen may carry the switch. The web page
# promises "only settings that do something"; a mobile toggle writing what no
# pipeline reads would break that promise quietly, from a Settings screen.
kt_api_c, sw_apic_c = code_only(KT_API), code_only(SW_APIC)
kt_patch_seg = kt_api_c[kt_api_c.find("class ProfileSettingsPatch"):
                        kt_api_c.find("class ProfilePatch")]
sw_patch_seg = sw_apic_c[sw_apic_c.find("struct ProfileSettingsPatch"):
                          sw_apic_c.find("struct ProfileDto")]
pin("Android's settings patch has no vacation or forwarding key",
    "vacation" not in kt_patch_seg.lower() and "forwarding" not in kt_patch_seg.lower())
pin("iOS's settings patch has no vacation or forwarding key",
    "vacation" not in sw_patch_seg.lower() and "forwarding" not in sw_patch_seg.lower())
pin("neither platform's account screens edit vacation or forwarding",
    not re.search(r"VacationDto\(|ForwardingDto\(|VacationInfo\(|ForwardingInfo\(",
                  code_only(KT_ACCT) + code_only(SW_ACCT)))

# The settings surface edits ONLY fields with a verified enforcement point:
# the signature the compose appends, the per-address overrides compose prefers,
# the density the list draws, the locale every timestamp renders in, and the
# two sender lists the screener reads. notes/tasks join the set with their own
# screens (§25); `signatures` joined when the prefs editor for it shipped —
# compose had been reading the key since day one with no way to write it.
pin("the prefs screen's writes name exactly the enforced keys",
    {m.group(1) for m in re.finditer(r"ProfileSettingsPatch\(\s*([A-Za-z]+) =",
                                      code_only(KT_ACCT))} ==
    {"signature", "density", "locale", "signatures", "safeSenders",
     "blockedSenders", "notes", "tasks"}
    and len({m.group(1) for m in re.finditer(
        r"ProfileSettingsPatch\(\s*([A-Za-z]+) =", code_only(KT_ACCT))}) >= 5)

# UNKNOWN IS NOT EMPTY, on the account record too: a null profile is "could not
# be read", never a blank name; a null alias list is never "No aliases yet".
pin("Android's addresses screen says unreadable and empty differently",
    "Aliases could not be read." in KT_ACCT and "No aliases yet." in KT_ACCT
    and "aliases != null && aliases.isEmpty()" in code_only(KT_ACCT))
pin("iOS's addresses screen says unreadable and empty differently",
    "Aliases could not be read." in SW_ACCT and "No aliases yet." in SW_ACCT)
pin("the privacy screen refuses to invent an unread created date",
    "Could not be read" in KT_ACCT and "Could not be read" in SW_ACCT)

# DELETE-ACCOUNT: the backend contract is `POST /auth/account/delete` (bearer +
# `{password}`, 403 on a wrong password) — the destructive call itself carries
# the credential, so a replayed verify-password proof cannot delete an account,
# and neither client may report a success the server did not confirm. Both
# screens say what deletion removes before any password is asked for.
KT_LAUNCH = android("data/LaunchApi.kt")
KT_DEL = android("ui/screens/security/DeleteAccountScreen.kt")
pin("both deletion screens say what is removed and demand the password",
    "What is deleted" in KT_DEL and "Every message" in KT_DEL
    and "Your aliases" in KT_DEL and "Only the password" in KT_DEL
    and "Delete account" in SW_ACCT and "the mailbox, every message" in SW_ACCT
    and "all aliases" in SW_ACCT and "Account password" in SW_ACCT)
pin("both clients call the real delete route with the password as the proof",
    "/grapsee/auth/account/delete" in code_only(KT_LAUNCH)
    and "/grapsee/auth/account/delete"
        in body_of(code_only(SW_APIC), "static func deleteAccount")
    and re.search(r"suspend fun deleteAccount\(", KT_API) is not None
    and re.search(r"static func deleteAccount\(", SW_APIC) is not None
    and "The server did not confirm the request." in code_only(KT_LAUNCH)
    and "guard response.success else {"
        in body_of(code_only(SW_APIC), "static func deleteAccount"))

# The PATCH is the whole write path, and "only the keys that arrive are
# touched" is only true if the ENCODER omits what the caller did not set:
# Android through jsonSparse, iOS through dictionaries that drop nils.
pin("Android's profile PATCH encodes sparsely",
    "jsonSparse.encodeToString(ProfilePatch.serializer()" in kt_api_c)
pin("iOS's profile PATCH builds bodies that drop untouched keys",
    "if let value { body[key] = value }" in sw_apic_c)

# The avatar routes: the server's answer carries the cache-nonce URL a replaced
# photo needs to show NOW, and both platforms keep it instead of the stale one.
pin("Android adopts the upload's nonce-bearing avatar URL",
    re.search(r"profile = it\.profile\?\.copy\(avatarUrl = resp\.avatarUrl", code_only(KT_PVM)) is not None)
pin("iOS adopts the upload's nonce-bearing avatar URL",
    re.search(r"state\.profile\?\.avatarUrl = url", code_only(SW_PVM)) is not None)
# And the 256 KB ceiling is the route's (`auth-account-profile.ts:256`); the
# screen quotes it before uploading, so a refusal is the person's words first.
pin("both photo pickers honour the server's ceiling before uploading",
    "256 * 1024" in code_only(KT_ACCT) and "256 * 1024" in code_only(SW_ACCT))

pin("opening a profile-backed screen does not fetch the avatar",
    "loadAvatar()" not in body_of(KT_PVM, "fun load(force")
    and "loadAvatar()" not in body_of(SW_PVM, "func load(force")
    and "vm.loadAvatar()" in KT_ACCT and "vm.loadAvatar()" in SW_ACCT)
pin("iOS waits for loaded account data before showing editable fields",
    ".task(id: s != nil)" in SW_ACCT and ".task(id: p != nil)" in SW_ACCT
    and SW_ACCT.count("else if fieldsReady {") >= 2)

# The eight frames must photograph CONTENT, not chrome: every seeded value the
# walks wait on lives in both hosts' `sampleProfile` -- the two platforms
# photograph the same record. (Android's `mail-support` waits on the FAQ copy,
# which is identical on both sides and is checked as screen text above.)
for seeded in ("Send the Q3 numbers before Friday", "Book the studio visit",
               "Sent with GrapMail", "business@grapmail.com", "Northwind SSO",
               "1993-04-12", "2026-03-14", "deals@spamyard.example",
               "hello@northwind.example"):
    pin(f"the seed value {seeded!r} exists in both hosts' account records",
        seeded in KT_HOST and seeded in SW_HOST)
pin("Android waits on seeded content for the account band",
    all(f'settled="{m}"' in KT_WALK for m in
        ("1993-04-12", "Send the Q3 numbers before Friday", "Book the studio visit",
         "Northwind SSO", "Sent with GrapMail", "business@grapmail.com", "2026-03-14")))
pin("iOS waits on seeded content for the account band",
    all(f'"{m}"' in SW_WALK for m in
        ("Send the Q3 numbers before Friday", "Book the studio visit",
         "Northwind SSO", "business@grapmail.com", "2026-03-14")))
# The iOS seed is a JSON literal run through the REAL decoder -- if the shape
# drifts from the route, the photograph stops with a name instead of shipping
# eight empty screens. Android builds the same record typed, so its drift
# defence is the parity diff's profile section, not this file.
pin("iOS's account seed decodes through the live decoder and fails loudly",
    "preconditionFailure" in SW_HOST and "sampleProfile" in SW_HOST)

# The scope words are the web's SHARED map (`connections/page.tsx`), copied to
# both phones so one scope has one phrase on three clients.
pin("both clients copy the web's scope words",
    "name, address and photo" in KT_ACCT and "name, address and photo" in SW_ACCT)

# -- 26. Phase 2: undo, filters, replies -- features whose every claim is a call --
#
# The mailbox features the plan could ship against endpoints that already exist:
# the filing undo (the PATCH is reversible by shape), the advanced search sheet
# (client-side over loaded pages, scope stated like the sort sheet's), reply-all
# and forward (built from the full server row), signature insertion (the profile's
# own text, editable), and attachment removal (two steps, the route's answer).
# Each pin below fails if the feature drifts into a claim the code cannot keep.
KT_MS = android("ui/screens/mail/MailboxScreen.kt")
SW_MS = ios("MailboxView.swift")
KT_SHEETS = KT_MS  # the sheet lives in the same file
KT_COMPOSE = android("ui/screens/mail/ComposeScreen.kt")
SW_RDR = ios("MailReaderViews.swift")
SW_SHEETS = ios("MailSheets.swift")
KT_VM = KT_VM  # the mailbox view model, already read for @JvmOverloads
SW_VM = ios("MailboxModel.swift")
KT_MDTO = android("data/MailDto.kt")
SW_MAPI = ios("MailAPI.swift")

# The undo pairs are the route's own (`grapmail-archive.ts` / `grapmail-boxes.ts`
# answer exactly these action words). A wrong inverse would file the mail
# somewhere the person never asked it to go.
pin("Android's inverse map names the three real pairs",
    all(s in code_only(KT_MDTO) for s in
        ("MailAction.Archive -> MailAction.Unarchive",
         "MailAction.Spam -> MailAction.Unspam",
         "MailAction.Trash -> MailAction.Restore")))
pin("iOS's inverse map names the three real pairs",
    all(s in code_only(SW_MAPI) for s in
        ("case .archive: return .unarchive",
         "case .spam: return .unspam",
         "case .trash: return .restore")))
pin("the undo banner is one component per platform, not a copy per screen",
    "internal fun UndoBanner(" in KT_MS and "struct GMUndoBanner: View" in SW_MS
    and "GMUndoBanner(" in SW_HOST and "UndoBanner(" in KT_HOST
    and "Button(\"Undo\")" not in SW_HOST)  # the host never re-draws the banner
pin("undo is offered only for ids the server accepted, both sides",
    "undoIds = done" in code_only(KT_VM) and "self.state.undoIds = done" in code_only(SW_VM))
pin("both walks photograph the undo banner and the filter sheet",
    all(f'"{n}:9' in KT_WALK for n in ("mail-adv-search", "mail-undo"))
    and all(f'"{n}")' in SW_WALK.replace('"mail-adv-search"', '"mail-adv-search")')
        for n in ("mail-adv-search", "mail-undo")))

# The advanced sheet's whole honesty claim is its scope line, in the same words
# on both phones; and it must never reach the network, because no filter route
# exists to reach.
pin("the advanced filter scope line is one sentence on both platforms",
    "messages loaded on this " in code_only(KT_MS)
    and "messages loaded on " in code_only(SW_SHEETS))
pin("the advanced filters never pretend a server route exists",
    "Api." not in code_only(KT_VM)[code_only(KT_VM).find("fun setAdvanced"):][:300]
    and "ApiClient" not in code_only(SW_VM)[code_only(SW_VM).find("func setAdvanced"):][:300])
pin("the search placeholder names what the term actually covers",
    "Search subject, body or sender" in KT_MS and "Search subject, body or sender" in SW_MS
    and "people, or files" not in KT_MS and "people, or files" not in SW_MS)

# Replies and forwards build from the FULL row; a forward that carried
# In-Reply-To would thread a forwarded message under a reply it is not.
pin("both prepares fetch the full message before composing",
    "Api.mailMessage(t, id)" in code_only(KT_VM)
    and "ApiClient.mailMessage(token: token, id: id)" in code_only(SW_VM))
pin("forward carries no In-Reply-To on either platform",
    re.search(r'"forward"[\s\S]{0,700}?inReplyTo = null', code_only(KT_VM)) is not None
    and re.search(r'case "forward"[\s\S]{0,700}?inReplyTo = nil', code_only(SW_VM)) is not None)
pin("reply-all drops this account's own address, both sides",
    "filterNot { it.lowercase() == me }" in code_only(KT_VM)
    and "$0.lowercased() != me" in code_only(SW_VM))

# The signature is text the person sees and can edit -- appended when compose
# opens, never stitched on at send time. If send() touched the signature the
# receipt would describe bytes the body editor never showed.
pin("the signature arrives as editable body text, not a send-time patch",
    'body = "\\n\\n-- \\n$signature"' in code_only(KT_COMPOSE)
    and 's.body = "\\n\\n-- \\n" + signature' in code_only(SW_RDR)
    and "signature" not in code_only(KT_VM)[code_only(KT_VM).find("fun send("):][:700]
    and "signature" not in code_only(SW_VM)[code_only(SW_VM).find("func send("):][:700])
pin("an empty To is refused before anything is sent, both platforms",
    "Add at least one recipient in To." in code_only(KT_VM)
    and "Add at least one recipient in To." in code_only(SW_VM))

# Attachment removal is two steps and drops the cache on success, or the Files
# screen keeps offering bytes the server threw away.
pin("attachment delete asks first and drops the cached row, both platforms",
    "mail-attachment-confirm" in code_only(android("ui/screens/mail/MailSecondaryScreens.kt"))
    and "mail-attachment-confirm" in code_only(SW_SECOND)
    and "MailStore.forgetAttachment(id)" in code_only(KT_VM)
    and "MailStore.shared.forgetAttachment(id)" in code_only(SW_VM))

pin("attachment removal only leaves the preview after confirmed deletion",
    "deleted = removed" in body_of(android("ui/screens/mail/MailSecondaryScreens.kt"), "fun AttachmentPreviewScreen")
    and "deleted = removed" in body_of(SW_SECOND, "struct AttachmentPreviewView")
    and 'then(true, "Removed.")' in body_of(KT_VM, "fun deleteAttachment")
    and 'then(true, "Removed.")' in body_of(SW_VM, "func deleteAttachment"))
pin("contact Write sends the selected address to the composer",
    'mailCompose("", "reply", to = address)' in code_only(KT_MAIN)
    and 'recipient: address' in code_only(SW_ROOT)
    and 'ComposeState(to = initialRecipient)' in body_of(KT_VM, "fun resetCompose")
    and 'ComposeState(to: initialRecipient)' in body_of(SW_VM, "func resetCompose"))

# Explicitly excluded features stay excluded: no send-later field may appear
# on either client, because no `/send` parameter accepts one.
pin("no scheduling field crept into either client",
    not re.search(r"sendAt|scheduledAt|scheduleAt", code_only(KT_API) + code_only(SW_APIC)))

# -- 27. the encryption band -- the format is the contract, the states are the UI --
#
# Phase 3 put the web's `/mail/account/encryption` on both phones: eight
# screens, the seal-at-send gate, the decrypt path in the thread, and a
# passphrase-protected key store. Three things can rot here silently, and each
# pin below is written so it fails the day it happens:
#
# 1. The ENVELOPE FORMAT is duplicated across three runtimes with no shared
#    module. One drifted string -- an AAD, an HKDF info label, a DER prefix --
#    and mail seals into bytes nothing can open, this app's own store included.
# 2. The THREE STATES (no key / locked / unlocked) are the sector's honesty
#    claim. A screen that offers an action against a locked key offers a
#    failure; a softened no-recovery line lies exactly when it matters.
# 3. The PRIVATE KEY must never exist in plaintext on disk, in a request, or
#    on screen -- and ciphertext must never be shown as a body.
KT_E2E = android("data/MailE2e.kt")
SW_E2E = ios("MailE2e.swift")
KT_E2EVM = android("logic/E2eViewModel.kt")
SW_E2EVM = ios("E2eModel.swift")
KT_E2ES = android("ui/screens/mail/E2eScreens.kt")
SW_E2ES = ios("E2eViews.swift")
KT_THREAD = android("ui/screens/mail/ThreadScreen.kt")
SW_COMPOSE_TAG = "gm-compose-encrypt"

# The frozen format strings, byte for byte, in both crypto modules. These are
# the strings `envelope.ts` (server) and `mailE2e.ts` (browser) froze; a third
# client that invents its own labels produces mail its own next launch cannot
# read, which is the definition of a silent data-loss bug.
for lit in ("grapmail-e2e-v1", "grapmail-e2e-v2-kek",
            "grapmail-e2e-protected-key/1", "PBKDF2-SHA256",
            "X25519+HKDF-SHA256+AES-256-GCM"):
    pin(f"the format literal {lit!r} is byte-identical in both crypto modules",
        lit in code_only(KT_E2E) and lit in code_only(SW_E2E))
pin("both derive 32-byte keys with 600,000 PBKDF2 rounds",
    "600_000" in code_only(KT_E2E) and "600_000" in code_only(SW_E2E)
    and "derivePassphraseKey" in code_only(KT_E2E) and "derivePassphraseKey" in code_only(SW_E2E))
# The X25519 SPKI prefix is RFC 8410's canonical DER, and the two platforms
# reach it by different means: Android round-trips through the JCA's
# `X509EncodedKeySpec` (which emits exactly that encoding), iOS hand-rolls the
# DER header from the RFC. Fingerprints must agree between the two phones, so
# each mechanism is pinned to the artefact it is required to produce.
pin("Android canonicalises SPKI through the JCA's own X509EncodedKeySpec",
    "X509EncodedKeySpec" in code_only(KT_E2E))
pin("iOS hand-rolls the RFC 8410 X25519 SPKI prefix",
    "0x2B, 0x65, 0x6E" in code_only(SW_E2E) and "0x30, 0x2A" in code_only(SW_E2E))

# The identity file: same directory, same name scheme, same container format,
# so a key written on one client is readable by the same client reinstalled --
# and by neither platform is anything else ever written.
pin("both stores name the identity file the same way",
    "grapmail-e2e" in code_only(KT_E2E) and "grapmail-e2e" in code_only(SW_E2E)
    and "identity-" in code_only(KT_E2E) and "identity-" in code_only(SW_E2E))

# NO PLAINTEXT PRIVATE KEY, anywhere it would matter. The store keeps only
# the protected container (format + kdf + opaque ct); the publish request
# carries only public halves -- the server REFUSES a body with private
# material (`grapmail-e2e.ts:151-169`), and a client that sent one would be
# broken by design, not just wrong in review.
pin("Android's publish request has no private field",
    re.search(r"class MailE2ePublishRequest[\s\S]{0,300}?privateKey", KT_API) is None)
pin("iOS's publish request has no private field",
    re.search(r"struct MailE2ePublishRequest[\s\S]{0,300}?privateKey", SW_MAPI) is None)
pin("the unlock session is in-memory only, on both platforms",
    re.search(r"private var session: MailE2e\.Unlocked\?", KT_E2EVM) is not None
    and re.search(r"private var session: MailE2e\.Unlocked\?", SW_E2EVM) is not None)
for side, src in (("Android", KT_E2EVM), ("iOS", SW_E2EVM)):
    seg = code_only(src)
    pin(f"{side}'s lock clears the session rather than copying it",
        re.search(r"session = (?:null|nil)", seg) is not None)

# The passphrase never becomes display text: it rides into the crypto calls
# and out again, and no screen may write it into a note, a receipt or a
# filename. `state.note` is the only text these models can surface, so no
# string literal may interpolate the passphrase variables into it.
for side, src in (("Android", KT_E2EVM), ("iOS", SW_E2EVM)):
    seg = code_only(src)
    pin(f"{side}'s model never interpolates a passphrase into user text",
        re.search(r'"[^"]*\$\{?(passphrase|oldPhrase|newPhrase)', seg) is None)

# THREE STATES, KEPT APART, in the same words on both phones. The hub's
# headline per state and the two hard warnings (no recovery; removal is
# permanent and everywhere) are the sector's contract with the person.
for line in ("Encryption is not set up on this device",
             "Your key is locked",
             "Your key is unlocked for this session",
             "There is no way to recover a forgotten passphrase.",
             "becomes unreadable — here and anywhere else, permanently",
             "The key published for your account is NOT the key on this device",
             "Nothing is wrong with your account"):
    pin(f"the state sentence {line!r} ships identically on both platforms",
        line in KT_E2ES and line in SW_E2ES)
pin("the compose lock-line ships identically on both platforms",
    "Unlock your key to encrypt — it locks when the app closes."
    in code_only(KT_COMPOSE)
    and "Unlock your key to encrypt — it locks when the app closes."
    in code_only(SW_RDR))

# A failed directory read is never drawn as "nothing is published" -- the
# claim would send the person to publish twice, or to believe their key is
# gone. Both publish screens keep the error as its own branch.
for side, src in (("Android", KT_E2ES), ("iOS", SW_E2ES)):
    pin(f"{side}'s publish screen branches on the failed read, not on absence",
        "publishedError" in code_only(src) or "publishedError" in
        code_only(android("logic/E2eViewModel.kt") if side == "Android" else ios("E2eModel.swift")))
pin("the directory's four refusals keep four sentences, both platforms",
    all(w in KT_E2ES for w in ("not an address the directory", "Not a GrapMail address",
                               "no GrapMail account", "not published an encryption key"))
    and all(w in SW_E2ES for w in ("not an address the directory", "Not a GrapMail address",
                                   "no GrapMail account", "not published an encryption key")))

# The compose gate and the thread decrypt: the encrypt row exists only with a
# record, opens only unlocked, and the seal stops the send when the plan is
# not "seal". In the thread, an encrypted row offers decrypt against the
# ENVELOPE, and a flagged message without one says so instead of nothing.
pin("compose's encrypt row is gated on a record and an unlock, both platforms",
    SW_COMPOSE_TAG in code_only(KT_COMPOSE) and SW_COMPOSE_TAG in code_only(SW_RDR)
    and "e2eState.record != null" in code_only(KT_COMPOSE)
    and "e2e.state.record != nil" in code_only(SW_RDR)
    and "unlocked" in code_only(KT_COMPOSE) and "unlocked" in code_only(SW_RDR))
pin("a plain plan or a silent directory stops an encrypted send, both platforms",
    'plan == null -> notice' in code_only(KT_COMPOSE)
    and 'plan.first != "seal" -> notice' in code_only(KT_COMPOSE)
    and 'guard let plan = await vm.encryptionPlan' in code_only(SW_RDR)
    and 'guard plan.mode == "seal" else' in code_only(SW_RDR))
pin("Android sends from both buttons through the same encryption decision",
    "clickable(enabled = sendEnabled, onClick = sendMessage)" in code_only(KT_COMPOSE)
    and 'else "Send", onClick = sendMessage' in code_only(KT_COMPOSE))
for side, model, send, wait, draft_id in (
    ("Android", android("logic/MailboxViewModel.kt"), "fun send(e2eEnvelope", "pending?.join()", "val draftId ="),
    ("iOS", ios("MailboxModel.swift"), "func send(e2eEnvelope", "await pending?.value", "let draft ="),
):
    send_body = body_of(model, send)
    pin(f"{side} waits for in-flight autosave before capturing the sent draft id",
        "stopAutosaveDebounce()" in send_body
        and 0 <= send_body.find(wait) < send_body.find(draft_id))
pin("both native send requests include successfully uploaded attachment IDs",
    "attachmentIds = attachmentIds" in body_of(KT_VM, "fun send(e2eEnvelope")
    and "attachmentIds: attachmentIds" in body_of(SW_VM, "func send(e2eEnvelope"))
pin("a sealed send carries the envelope and an empty bodyText, both platforms",
    'bodyText = if (envelope != null) "" else s.body' in code_only(KT_VM)
    and 'bodyText: envelopeJson == nil ? compose.body : ""' in code_only(SW_VM))
pin("the thread decrypts against the envelope and names the no-envelope case",
    "gm-e2e-decrypt-open" in code_only(KT_THREAD) and "gm-e2e-decrypt-open" in code_only(SW_RDR)
    and "No encrypted content came with this message." in code_only(KT_THREAD)
    and "No encrypted content came with this message." in code_only(SW_RDR))
pin("neither decrypt path ever shows the envelope as a body",
    "e2eEnvelope?.toString()" in code_only(KT_THREAD)
    and not re.search(r'Text\((envelope|e2eEnvelope)', code_only(SW_RDR)))

# The encrypted notice no longer claims the key cannot open -- the decrypt
# path exists now, and the copy that promised otherwise would be a stale lie
# on both platforms at once.
pin("the encrypted notice describes the decrypt path, not its absence",
    "does not yet hold" not in KT_THREAD and "does not yet hold" not in SW_RDR
    and "Only you and the sender can read this." in KT_THREAD
    and "Only you and the sender can read this." in SW_RDR)

# The nine frames photograph SEEDED states: the fingerprint, the published
# fingerprint, both lookup branches. The values live in both hosts, and both
# walks wait on the sentences the states alone draw.
for seeded in ("7C:0F:2A:91:B4:D8:3E:6C:05:AA:1F:BE:D2:48:67:90:13:EC:B6:24:FA:5D:71:09:8C:30:B7:E4:16:9F:A2:C8",
               "priya@havenworks.example", "no-key-published",
               "grapmail-e2e-protected-key/1"):
    pin(f"the encryption seed {repr(seeded)[:40]} exists in both hosts",
        seeded in KT_HOST and seeded in SW_HOST)
pin("Android waits on seeded content for the encryption band",
    all(f'settled="{m}"' in KT_WALK for m in
        ("Encryption is not set up on this device", "Your key is unlocked for this session",
         "There is no way to recover a forgotten passphrase.",
         "Published fingerprint", "Remove from this device", "priya@havenworks.example")))
pin("iOS waits on seeded content for the encryption band",
    all(f'"{m}",]' in SW_WALK or f': "{m}"' in SW_WALK for m in
        ("Encryption is not set up on this device", "Your key is unlocked for this session",
         "There is no way to recover a forgotten passphrase.",
         "Published fingerprint", "Remove from this device", "priya@havenworks.example")))
# (The passphrase frame's wait is the headline, both walks: XCUITest rejects
# string identifiers over 128 characters, which the 139-character lede is —
# CI threw "Invalid query" on it. The screen still SHOWS the lede; the walk
# just cannot ask for it by name.)
pin("the passphrase-change frame waits on the headline on both walks",
    'settled="Change your passphrase"' in KT_WALK
    and '"e2e-passphrase": "Change your passphrase"' in SW_WALK)

# XCUITest's 128-character identifier cap killed one walk for a 139-character
# marker. Pin every settled wait under the cap, so the next long sentence
# fails here instead of at minute twenty-eight of a simulator run.
_sl = SW_WALK[SW_WALK.index("let settledLabels: [String: String] = ["):]
_sl = _sl[:_sl.index("\n        for screen in postWriteScreens")]
_parts = re.split(r",(?=\s*\")", re.sub(r"\s*\+\s*", "", _sl))
# keys sit before a ':' in each part; the value is what follows it
_val_re = re.compile(r":\s*\"((?:[^\"\\]|\\.)*)\"")
_long = [m.group(1) for part in _parts for m in [_val_re.search(part)] if m and len(m.group(1)) > 128]
pin("every iOS settled marker fits XCUITest's 128-character identifier cap",
    not _long)
pin("the plaintext private key exists in neither snapshot host",
    not re.search(r"MI[EHR][A-Za-z0-9+/=]{20,}", KT_HOST)
    and not re.search(r"MI[EHR][A-Za-z0-9+/=]{20,}", SW_HOST))

# -- 28. the subscriptions band -- detection rules, and a handoff that cannot lie --
#
# The web's `/mail/subscriptions` on both phones, over the ONE piece of truth
# the API carries: the sender's List-Unsubscribe offer, parsed by the mail
# service into `{offers, oneClickAdvertised}` (`grapmail.ts:589`,
# `mail-service/src/mime/unsubscribe.ts`). Two failure classes live here: a
# decode that silently nils the object (the `e2eEnvelope` defect, which is how
# the thread's unsubscribe door never appeared on real mail), and a list that
# claims more than the loaded mail supports. Every pin below can fail, and the
# run names the file.
KT_SUBS = android("ui/screens/mail/SubscriptionsScreen.kt")
SW_SUBS = ios("MailSubscriptionsViews.swift")
KT_MAIL = android("ui/components/Mail.kt")
SW_COMPS = ios("MailComponents.swift")

# Sentence checks below read RENDERED strings, not raw text: a long line is
# wrapped across concatenated literals in both languages, and a raw substring
# check would pass only when the prose happened not to wrap -- the kind of pin
# that breaks on a reflow and gets loosened rather than fixed.
_QUOTED = re.compile(r'"((?:[^"\\]|\\.)*)"')
_CAT = re.compile(r'[ \t]*(?:\+|,)[ \t]*\n[ \t]*|\n[ \t]*\+[ \t]*')


def rendered(src: str) -> str:
    # Blank comments first: code in a comment must not forge a sentence.
    src = code_only(src)
    # Glue literal-to-literal concatenations (`"a" +\n  "b"`, Swift's leading
    # `+` on a modifier line, Kotlin's trailing comma before a wrapped
    # argument), then drop every remaining quoted run. Wrapped literals each
    # keep their own trailing space, so the run of whitespace collapses once
    # here -- a sentence is matched as it renders, not as it was typed.
    src = _CAT.sub("", src)
    return re.sub(r"\s+", " ", " ".join(_QUOTED.findall(src)).replace('\\"', '"'))

KT_SUBS_R = rendered(KT_SUBS)
SW_SUBS_R = rendered(SW_SUBS)

# The offer is the server's PARSED record, decoded as such on both platforms.
# A String field nils the object on every real message -- the silent-absence
# class this repo has now caught twice, once per shape.
pin("both clients decode the parsed unsubscribe record, not a header string",
    "class MailUnsubscribeDto" in KT_MDTO and "offers" in KT_MDTO
    and "struct MailUnsubscribe: Decodable" in SW_COMPS
    and "var unsubscribe: MailUnsubscribe?" in SW_MAPI
    and "val unsubscribe: MailUnsubscribeDto? = null" in KT_MDTO)
pin("the web's offer URLs win over the legacy header, both platforms",
    "fun unsubscribeTarget" in KT_MAIL and "func unsubscribeTarget" in SW_COMPS
    # The first version sliced a fixed 400 chars of RAW file after the def —
    # a window that a doc comment could silently push the https test out of,
    # failing on prose rather than behaviour. It reads the member's own
    # code-only body now, same spellings §32's handoff pin checks.
    and 'startsWith("https://' in body_of(code_only(KT_MAIL_C), "fun unsubscribeTarget")
    and 'hasPrefix("https://' in body_of(code_only(SW_MAIL_C), "func unsubscribeTarget("))
pin("the thread opens the door the target helper names, both platforms",
    "unsubscribeTarget(message.unsubscribe)" in code_only(KT_THREAD)
    and "unsubscribeTarget(message.unsubscribe)" in code_only(SW_RDR))

# DETECTION IS THE WEB'S RULE, unchanged: two rows with an offer, three
# without, a service local part decides which bar applies, the newest row wins
# name and offer, the noisiest sender sorts first, three frequency buckets.
# Two phones grouping one mailbox differently would be two answers about the
# same senders.
for phrase in ("20+ emails recently", "10-20 emails recently",
               "10 or fewer emails recently",
               "noreply", "donotreply", "bounce"):
    pin(f"the detection rule text {phrase!r} exists identically on both platforms",
        phrase in KT_SUBS and phrase in SW_SUBS)
pin("both apply the web's two-or-three threshold rule",
    "p.count >= 2" in code_only(KT_SUBS) and "p.count >= 3" in code_only(KT_SUBS)
    and "b.count >= 2" in code_only(SW_SUBS) and "b.count >= 3" in code_only(SW_SUBS))
pin("subscriptions are read from INCOMING mail only, both platforms",
    'setOf("in", "inbound")' in body_of(code_only(KT_SUBS), "fun detectSubscriptions")
    and 'direction == "in" || direction == "inbound"' in body_of(code_only(SW_SUBS), "func detectSubscriptions")
    and slice_found(code_only(KT_SUBS), "fun detectSubscriptions")
    and slice_found(code_only(SW_SUBS), "func detectSubscriptions"))
# (This pin has been dead twice. First version: it searched the Kotlin file
# for a Swift-shaped expression Kotlin never had -- a dead False branch over
# an `or` that only asked two words to appear SOMEWHERE. Second version fixed
# the spellings but still looked at whole files, and the mutation probe
# caught that too: deleting the detector's filter line stayed green because
# the scope-count filter elsewhere in the same file held the same words. A
# rule about WHO counts messages belongs in the body of the function that
# counts them -- hence body_of, and slice_found so a renamed function fails
# out loud rather than as a vacuous absence.)

# THE ACTION IS A HANDOFF. No client may POST to a sender's URL: the web does
# it from ITS server behind the SSRF guard, and the plan says a gateway route
# would be a backend task. If a device aimed the request, "unsubscribe" would
# leak the user's IP and become a request generator for arbitrary hosts. The
# shape is checked against the code: an unsubscribe reached by anything but
# the browser handoff (`ACTION_VIEW` / `UIApplication.open`) fails here.
pin("no client posts to a sender's unsubscribe URL",
    not re.search(r"mailE2e|mailSend|\"POST\"",
                  re.sub(r"\s+", " ", code_only(KT_SUBS) + code_only(SW_SUBS)))
    # The handoff rides the EXISTING browser-handoff helpers: Android's
    # `openExternally` (whose ACTION_VIEW intent lives in ThreadScreen) and
    # iOS's completion-handler `UIApplication.open`; the mailto row hands a
    # draft to the mail app via ACTION_SENDTO. Nothing else exists, and a
    # future POST-shaped unsubscribe would have to introduce one of these.
    and "openExternally" in code_only(KT_SUBS)
    and "ACTION_VIEW" in code_only(android("ui/screens/mail/ThreadScreen.kt"))
    and "ACTION_SENDTO" in code_only(KT_SUBS)
    and "UIApplication.shared.open" in code_only(SW_SUBS))
pin("the handoff refuses to claim the outcome",
    "cannot see what the page said" in KT_SUBS_R
    and "cannot see what the page said" in SW_SUBS_R
    and not re.search(r'"Unsubscribed from', code_only(KT_SUBS) + code_only(SW_SUBS)))
pin("the mailto offer drafts, it does not send",
    "Nothing has left this phone until you send it" in KT_SUBS_R
    and "Nothing has left this phone until you send it" in SW_SUBS_R)
pin("the confirmation is the thread's own sheet on both platforms",
    "UnsubscribeContent(" in code_only(KT_SUBS)
    and "UnsubscribeSheet(" in code_only(SW_SUBS))

# THE SCOPE LINE: the screen says which messages it grouped. A list that
# claimed the whole mailbox would claim more than the cache can answer --
# the same rule the advanced-search sheet is pinned by.
pin("the subscriptions screen states its scope in one sentence on both platforms",
    "This screen grouped the $scopeCount incoming messages the app has loaded" in KT_SUBS_R
    and "incoming messages the app has loaded; a sender whose mail is not on this device" in SW_SUBS_R
    and "incoming inbox mail the app has loaded on this device" in KT_SUBS_R
    and "incoming inbox mail the app has loaded on this device" in SW_SUBS_R
    and "Nothing was sent to any sender until you press Unsubscribe. This screen "
        "never mails on your behalf on its own." in KT_SUBS_R + " " + SW_SUBS_R)
pin("the empty branch states what it is waiting for, both platforms",
    "No subscriptions detected." in KT_SUBS and "No subscriptions detected." in SW_SUBS)

# The doors: Settings row and drawer row, in one phrase per platform pair.
kt_settings = android("ui/screens/mail/MailSecondaryScreens.kt")
sw_settings = ios("MailSecondaryViews.swift")
pin("both settings screens open the subscriptions door with the same words",
    "Subscriptions" in kt_settings and "Senders that mail you most" in kt_settings
    and "Subscriptions" in sw_settings and "Senders that mail you most" in sw_settings)
pin("both drawers name the same row as the web's page",
    '"Subscriptions"' in android("ui/screens/mail/MailboxScreen.kt")
    and '"Subscriptions"' in ios("MailChrome.swift"))

# Frames and seeds: both hosts seed the same two senders; both walks launch
# the same two frames and wait on the same sentences; the empty frame's seed
# is an honest empty READ, never an absent cache dressed as emptiness.
for seeded in ("The Weekly Draft", "noreply@digest.example.org",
               "https://lists.newsletter.example/unsub?id=7f3"):
    pin(f"the subscriptions seed {seeded!r} exists in both hosts", seeded in KT_HOST and seeded in SW_HOST)
pin("the empty subscriptions frame seeds an empty read, not an unread cache",
    'total: Int = 0' in KT_MDTO
    and "MailInboxResponse(success = true, box = \"inbox\", messages = emptyList())" in KT_HOST
    and 'empty.total = 0' in code_only(SW_HOST) and 'empty.messages = []' in code_only(SW_HOST))
# The subscription detector is incoming-only on both platforms, and the seed
# rows must carry the server's own word for arriving mail or every seeded row
# is dropped and the populated frame quietly photographs its own empty branch
# -- exactly what CI caught when iOS's seed helper never set `direction`.
pin("both hosts' subscription seeds carry the incoming direction the detector reads",
    'direction = "inbound"' in KT_HOST and 'm.direction = "in"' in code_only(SW_HOST))
pin("both walks photograph both subscriptions frames with the same waits",
    all(f'settled="{m}"' in KT_WALK for m in ("10 or fewer emails recently",
                                              "No subscriptions detected."))
    and all(f'"{m}",]' in SW_WALK or f': "{m}"' in SW_WALK for m in
            ("10 or fewer emails recently", "No subscriptions detected.")))

# -- 29. the store round -- dark frames, labels, and the two privacy files ------
#
# Phase 6 is the release gate, and its claims are the easiest to make and the
# easiest to silently drop: "dark mode works" without a dark frame, "the
# manifest covers the app" after someone adds a new API, "Data safety filled
# in" from memory rather than from the route list. Each pin below reads the
# artifacts that make the claim true, and fails the day the artifact drifts.
KT_E2EV = KT_E2ES  # the Kotlin encryption screens, already read for §27
SW_E2EV = SW_E2ES

# The plan: every band added after the first palette check carries its own
# dark frame with BOTH proofs. A `-dark` HOST case would be a second copy of
# a screen (§19 refuses one), so the frames reuse the light names — which is
# exactly why the pairing needs pinning: nothing else stops a dark pass from
# launching a name the app never learns.
for dark in ("111-account-profile-dark", "112-e2e-status-unlocked-dark",
             "113-mail-subs-list-dark"):
    # Twice, not once: a frame named only in the launch loop can fail to
    # photograph and still leave the walk green — the PNG gate list is what
    # makes an absent screenshot a failure, so the pin counts both places.
    pin(f"the dark frame {dark} is launched AND gated by both walks",
        KT_WALK.count(dark) >= 2 and SW_WALK.count(dark) >= 1)
pin("the dark frames launch host cases that exist",
    all(n in KT_HOST and n in SW_HOST for n in
        ("account-profile", "e2e-status-unlocked", "mail-subs-list")))

# Two destructive/exit buttons were once the only taps in the band with no
# identifier at all; the fix paired `tag =` on Kotlin with `marker:` on Swift,
# and the walk's markers depend on those names matching.
for twin in ("gm-e2e-remove-keep", "gm-e2e-lookup-compose"):
    pin(f"the button identifier {twin} exists under both platforms' markers",
        f'"{twin}"' in KT_E2EV and f'"{twin}"' in SW_E2EV)
pin("no iOS encryption button falls back to a shared identifier",
    'marker ?? "gm-e2e-button"' not in SW_E2EV)

# The privacy manifest is only worth its filename if it names what the binary
# actually calls. Both required-reason APIs here were audited out of the code
# (SessionStore's UserDefaults, the compose picker's security scope); a new
# required-reason user must extend the manifest, and these two pins are where
# that becomes loud instead of silent.
MANIFEST = (IOS / "PrivacyInfo.xcprivacy").read_text(encoding="utf-8")
pin("the manifest declares UserDefaults exactly where the session store uses it",
    "NSPrivacyAccessedAPICategoryUserDefaults" in MANIFEST
    and "UserDefaults.standard" in ios("SessionStore.swift")
    and "CA92.1" in MANIFEST)
pin("the manifest declares the file-provider scope the attachment picker takes",
    "NSPrivacyAccessedAPICategoryFileProvider" in MANIFEST
    and "startAccessingSecurityScopedResource" in ios("MailReaderViews.swift")
    and "C617.1" in MANIFEST)
pin("the manifest says plainly that nothing tracks",
    re.search(r"<key>NSPrivacyTracking</key>\s*<false/>", MANIFEST) is not None
    and re.search(r"<key>NSPrivacyTrackingDomains</key>\s*<array/>", MANIFEST) is not None)
# The tracking answer rests on one fact: the code talks to one host. The hosts
# are read out of the CODE (comments excluded — a comment about a third-party
# URL is documentation, and an earlier version of this pin proved that by
# failing on one): iOS's `base` literal and Android's `API_BASE` build field.
_API_URLS = (re.findall(r"https?://([A-Za-z0-9.-]+)", code_only(SW_APIC))
             + re.findall(r'https?://([A-Za-z0-9.-]+)',
                          (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")))
pin("both clients address exactly one host and it is the app's own API",
    set(_API_URLS) == {"grapsee.com"})

# The data-flow document is the source of the store forms' answers; it must
# keep naming the endpoints from the clients' own code, the on-device stores,
# and — the honest part — the deletion route that does NOT exist yet, in the
# same words the privacy screens use.
WF_TEXT = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
DFLOW = (ROOT / "DATA-FLOW.md").read_text(encoding="utf-8")
pin("the data-flow doc covers the real endpoint groups",
    all(g in DFLOW for g in ("/grapsee/auth/register", "/grapsee/auth/login",
                             "/grapsee/auth/account/profile", "e2e/keys", "e2e/plan",
                             "/screener/decision", "/send", "/draft")))
pin("the data-flow doc states the deletion gap in the screens' own words",
    "that path is not built" in DFLOW and "no account-delete endpoint" in DFLOW.lower())
pin("the data-flow doc names every on-device store the code writes",
    all(s in DFLOW for s in ("Keychain", "UserDefaults", "Keystore",
                             "grapmail-e2e-protected-key/1", "MailStore")))
pin("the release job builds and proves the AAB beside the APK",
    "./gradlew assembleRelease bundleRelease" in WF_TEXT and "app-release.aab" in WF_TEXT)
pin("the release page carries only the two installable files — no screenshot gallery",
    # The founder's rule, pinned at the workflow: the release `files:` list is
    # exactly the APK and the AAB. Screenshots stay CI artifacts on the run.
    # Add `*.png` back to that list and this breaks.
    ".png" not in body_of(WF_TEXT, "files: |")
    and "app-release.apk" in body_of(WF_TEXT, "files: |")
    and "app-release.aab" in body_of(WF_TEXT, "files: |"))

# The manifest is a plist Apple parses at upload time — a structure error or a
# reason code off Apple's list is an email from App Review two weeks after the
# build was "green". plistlib is stdlib, and the vocabularies below are
# Apple's documented ones, so the same check the validation service runs
# happens before the artifact exists.
import plistlib as _plistlib
try:
    _MAN = _plistlib.load(open(IOS / "PrivacyInfo.xcprivacy", "rb"))
except Exception as e:  # noqa: BLE001 - any parse failure is the finding
    _MAN = None
    pin(f"the privacy manifest parses as a plist ({e})", False)
if _MAN is not None:
    pin("the manifest's top level is exactly Apple's four keys",
        set(_MAN) == {"NSPrivacyTracking", "NSPrivacyTrackingDomains",
                      "NSPrivacyCollectedDataTypes", "NSPrivacyAccessedAPITypes"})
    pin("tracking is declared false with an empty domain list",
        _MAN.get("NSPrivacyTracking") is False and _MAN.get("NSPrivacyTrackingDomains") == [])
    _KNOWN_REASONS = {"CA92.1", "35F9.1", "4868.1", "E174.1", "C617.1",
                      "3B52.1", "DA65.1", "F4C8.1", "0000.1", "DB09.1", "3E3E.1"}
    pin("every required-reason entry has Apple's shape and a documented code",
        all(set(e) == {"NSPrivacyAccessedAPIType", "NSPrivacyAccessedAPITypeReasons"}
            and all(r in _KNOWN_REASONS for r in e["NSPrivacyAccessedAPITypeReasons"])
            for e in _MAN.get("NSPrivacyAccessedAPITypes", [])))
    _CATS = {"NSPrivacyCollectedDataType", "NSPrivacyCollectedDataTypeLinked",
             "NSPrivacyCollectedDataTypeTracking", "NSPrivacyCollectedDataTypePurposes"}
    pin("every collected-data entry has Apple's shape and documented purposes",
        all(set(e) == _CATS
            and e["NSPrivacyCollectedDataTypeTracking"] is False
            and all(p.startswith("NSPrivacyCollectedDataTypePurpose")
                    for p in e["NSPrivacyCollectedDataTypePurposes"])
            for e in _MAN.get("NSPrivacyCollectedDataTypes", [])))
    _declared = {e["NSPrivacyAccessedAPIType"].replace("NSPrivacyAccessedAPICategory", "")
                 for e in _MAN.get("NSPrivacyAccessedAPITypes", [])}
    pin("the manifest declares exactly the two required-reason APIs the code calls",
        _declared == {"UserDefaults", "FileProvider"})

# -- 30. the per-app detail -- the web's links/<product> page, honestly ported ---
#
# The web's account section has a thirteenth page the phones were missing:
# `/mail/account/connections/<product>` (`LinkedAppDetail.tsx`), one linked app
# in full. Porting it raises the two questions this section pins: does the
# screen show the RECORDED sharing (the web's SHARED sentences, not the list's
# short words), and does it distinguish "this name has no entry" from "the
# account has not been read" -- the invention the whole page exists to avoid.
KT_ACCT_C = KT_ACCT  # the Kotlin account screens, read for §25
SW_ACCT_C = SW_ACCT
# Rendered sentences, not raw text: both files wrap the long lines, and a
# raw check would pass on the literal as typed rather than as shown.
KT_ACCT_R = rendered(KT_ACCT_C)
SW_ACCT_R = rendered(SW_ACCT_C)
pin("both platforms ship the per-app detail screen",
    "fun ConnectionDetailScreen" in KT_ACCT_C and "struct ConnectionDetailView: View" in SW_ACCT_C)
for sentence in ("Your name, GrapMail address and profile photo",
                 "Read and send mail in your GrapMail mailbox",
                 "Projects and client records you own on Grapsee products",
                 "The people you have exchanged mail with",
                 "Your password is never shared with"):
    pin(f"the web's sharing sentence {sentence[:34]!r} ships on both platforms",
        sentence in KT_ACCT_R and sentence in SW_ACCT_R)
pin("the detail distinguishes unread from not-connected, both platforms",
    "The account could not be read" in KT_ACCT_R and "The account could not be read" in SW_ACCT_R
    and "does not have access to this account" in KT_ACCT_R
    and "does not have access to this account" in SW_ACCT_R)
pin("removing from the detail rides the same verified write as the list",
    KT_ACCT_C.count("saveConnections(") >= 2 and SW_ACCT_C.count("saveConnections(keep:") >= 2)
pin("the unknown product is answered, not redirected",
    "does not have access to this account" in KT_ACCT_R
    and "does not have access to this account" in SW_ACCT_R)
for fr in ("114-account-connection-detail", "115-account-connection-unknown"):
    pin(f"the detail frame {fr} is launched AND gated by both walks",
        KT_WALK.count(fr) >= 2 and SW_WALK.count(fr) >= 1)
# The exact-match lesson, pinned: a walk wait matches one element's WHOLE
# text, so the marker must be a contiguous literal in the sources -- a
# fragment of a sentence that wraps across literals matches nothing, and
# CI discovers that at minute twenty-seven, not at gate time. (114's
# sharing sentence already lived whole inside one literal; 115's card
# answer did not, and its wait moved to the single-literal subtitle.)
pin("the detail frames' settled waits are contiguous literals in the sources",
    "Not connected to this account" in KT_ACCT_C and "Not connected to this account" in SW_ACCT_C)
# The subscriptions waits, checked for the same two hazards at once: the
# empty sentence must be a whole literal on both platforms, and the
# populated marker must NOT be the sender name -- name rows carry
# lineLimit(1), and a truncated element's text is no longer the seed.
pin("the subscriptions waits are contiguous literals, never the truncated name",
    "No subscriptions detected." in code_only(KT_SUBS)
    and "No subscriptions detected." in code_only(SW_SUBS)
    and 'settled="10 or fewer emails recently"' in KT_WALK
    and '"mail-subs-list": "10 or fewer emails recently"' in SW_WALK
    and 'settled="The Weekly Draft"' not in KT_WALK
    and '"mail-subs-list": "The Weekly Draft"' not in SW_WALK)

# ── 31. the accessibility pass, pinned (§31) ─────────────────────────────────
# The plan's Phase-6 line: "TalkBack/VoiceOver labels on all new screens; 44pt
# targets; existing a11y conventions extended by pins." The pass itself was a
# measurement from the sources, and the one defect it found (the subscriptions
# capsule at ~35dp, the iOS Add circle with no VoiceOver word) is now the
# thing these pins refuse to let back in. A pin here fails by EDITING THE
# SCREEN it guards — change a padding back to 9, drop a label — which is
# exactly the regression class being closed.

# Worded buttons carry their own label; a 13pt-text capsule needs ~13dp of
# vertical padding to clear 44dp. Both platforms chose 13; the pin accepts
# anything at 10+ so a deliberate enlargement cannot trip it. The first
# version of these four pins searched the whole file, and the file has SIX
# other `vertical = 1X.dp` paddings on cards and rows — the mutation that
# made them dead was putting the capsule back to 9.dp and watching the gate
# not blink. Each check is now anchored to the element's own declaration:
# the testTag/accessibilityIdentifier line the padding rides against, or
# the button component's own body.
def _before(src: str, anchor: str, span: int = 260) -> str:
    i = src.find(anchor)
    return src[max(0, i - span):i] if i >= 0 else ""

KT_SUBS_ACTION = _before(code_only(KT_SUBS), '.testTag("gm-subs-action")')
SW_SUBS_ACTION = _before(code_only(SW_SUBS), '.accessibilityIdentifier("gm-subs-action")')
pin("the subscriptions action clears ~44dp on both platforms",
    re.search(r"vertical = 1[0-9]\.dp", KT_SUBS_ACTION) is not None
    and re.search(r"\.padding\(\.vertical, 1[0-9]\)", SW_SUBS_ACTION) is not None)
KT_E2E_BTN = body_of(code_only(KT_E2ES), "private fun E2eButton")
SW_E2E_BTN = body_of(code_only(SW_E2ES), "private struct E2eButton")
pin("the E2E sector's shared button clears ~44dp on both platforms",
    re.search(r"\.padding\(vertical = 1[0-9]\.dp\)", KT_E2E_BTN) is not None
    and re.search(r"\.padding\(\.vertical, 1[0-9]\)", SW_E2E_BTN) is not None)
# iOS hit frames are literal: the add circle declares its own 44pt side.
pin("the iOS add circle declares a 44pt hit frame",
    ".frame(width: 44, height: 44)" in code_only(SW_ACCT))

# Icon-only actions speak; icons beside a sentence stay decorative. The Add
# button found unlabeled this pass (Android's twin always said "Add"); the
# two trash icons were labeled as written. The count of decorative `null`
# descriptions is pinned at four so a NEW clickable icon cannot arrive
# silently unlabeled — it would trip the count and force a label or a
# deliberate, reviewed change to this pin.
pin("the icon-only actions carry words on both platforms",
    'Icons.Rounded.Add, "Add"' in code_only(KT_ACCT)
    and '.accessibilityLabel("Add")' in code_only(SW_ACCT)
    and 'Icons.Rounded.Delete, "Remove"' in code_only(KT_ACCT)
    and '.accessibilityLabel("Remove")' in code_only(SW_ACCT)
    and 'accessibilityLabel("Remove \\(a.address)")' in code_only(SW_ACCT))
pin("the decorative icons are the four reviewed ones — no new silent icon",
    len(re.findall(r",\s*null\b", code_only(KT_ACCT)))
    + len(re.findall(r",\s*null\b", code_only(KT_E2ES))) == 4
    and "Icons." not in code_only(KT_SUBS))
# The subscriptions row says its action in a word, both platforms — the
# capsule is Text("Unsubscribe"), never an icon nobody can name. Android
# wraps that call after the open paren, so the regex allows a newline.
pin("the unsubscribe action is a word, not an icon, on both platforms",
    re.search(r'Text\(\s*"Unsubscribe"', code_only(KT_SUBS)) is not None
    and 'Text("Unsubscribe")' in code_only(SW_SUBS))

# ── 32. the three-perspective review, pinned (§32) ──────────────────────
# A review of this session's own work found four real defects the walking
# frames could not see, because every one of them lives in code the snapshot
# host never executes: the envelope's WIRE type (sealed send only runs against
# the live route), the derivation's THREAD (the walk's seeded state skips it),
# the error's LOCALIZED TEXT (failure states draw from seeds), and the
# SIGN-OUT lifetime (a walk never signs out mid-frame). Each fix below is
# pinned so the class, not just the instance, is closed.

KT_E2EVM = android("logic/E2eViewModel.kt")

# 1. THE WIRE. sanitiseE2eEnvelope (grapmail-e2e.ts:129-130) drops any
# envelope that is not a JSON object; a String here was a silently-dead
# sealed send. Slice-scoped, so the read side's legitimate `e2eEnvelope`
# decodes elsewhere in the same files cannot satisfy it.
KT_SEND_B = body_of(code_only(KT_API), "data class MailSendRequest")
SW_SEND_B = body_of(code_only(SW_API), "struct MailSendRequest")
pin("the sealed send puts the envelope on the wire as an OBJECT, both platforms",
    slice_found(KT_API, "data class MailSendRequest")
    and slice_found(SW_API, "struct MailSendRequest")
    and "val e2eEnvelope: JsonObject? = null" in KT_SEND_B
    and "var e2eEnvelope: RawJSON? = nil" in SW_SEND_B
    and "e2eEnvelope: String?" not in KT_SEND_B
    and "e2eEnvelope: String?" not in SW_SEND_B)
# An unparseable envelope refuses the send; it must never degrade into a
# plaintext one -- that is the leak the envelope exists to prevent.
pin("an unparseable envelope refuses the send whole, both platforms",
    "could not be prepared — nothing was sent." in code_only(KT_VM)
    and "could not be prepared — nothing was sent." in code_only(SW_VM))

# 2. THE THREAD. Every 600k-round derivation runs inside Task.detached; the
# per-function slices are checked so one detached call cannot cover five
# functions by sitting anywhere in the file.
pin("iOS derives every passphrase off the main actor",
    all("Task.detached" in body_of(code_only(SW_E2EVM), h)
        for h in ("func setup(", "func unlock(passphrase:", "func changePassphrase(",
                  "func seal(", "func decrypt("))
    and all(slice_found(code_only(SW_E2EVM), h) for h in ("func setup(", "func changePassphrase(")))
pin("Android derives off the main thread too",
    KT_E2EVM.count("Dispatchers.Default") >= 3)

# 3. THE SENTENCE. E2eError carries LocalizedError so localizedDescription
# is the crafted line, describe() passes it through, and no call site reads
# the raw property any more (the pin that used to be here checked the
# sentence literals and passed while the getter mangled them).
pin("crypto failures reach the screen as their own sentences, both platforms",
    "Error, LocalizedError" in code_only(SW_E2E)
    and "error as? MailE2e.E2eError" in code_only(SW_E2EVM)
    and "note(error.localizedDescription" not in code_only(SW_E2EVM)
    # Android's twin of the same rule: Throwable.message IS the crafted
    # sentence there, and the model reads it -- pinning the read proves the
    # sentence survives to the note on that platform.
    and "exceptionOrNull()?.message" in code_only(KT_E2EVM))

# 4. THE LIFETIME. Sign-out and expiry clear the unlocked session on both
# platforms; three call sites each (drawer door, settings door, expiry).
pin("sign-out drops the unlocked key on both platforms",
    "func clearForSignOut" in code_only(SW_E2EVM)
    and "fun clearForSignOut" in code_only(KT_E2EVM)
    and code_only(SW_ROOT).count("clearForSignOut") >= 3
    and code_only(KT_MAIN).count("clearForSignOut") >= 3)

# 5. THE HANDOFF, HTTPS-ONLY. The web's normalizeUnsubscribe admits /^https:
# /\//i and nothing else; both phones had an http:// branch that claimed to
# copy it. A sender-chosen plain-http page one tap from the browser is the
# interceptable-door class the SSRF guard on the web's own route exists to
# close; the phones close it by not offering the door.
pin("unsubscribe offers open only https targets, both platforms",
    'startsWith("http://"' not in body_of(code_only(KT_MAIL_C), "fun unsubscribeTarget")
    and 'hasPrefix("http://")' not in body_of(code_only(SW_MAIL_C), "func unsubscribeTarget(")
    and 'startsWith("https://", ignoreCase = true)' in body_of(code_only(KT_MAIL_C), "fun unsubscribeTarget")
    and slice_found(code_only(KT_MAIL_C), "fun unsubscribeTarget")
    and slice_found(code_only(SW_MAIL_C), "func unsubscribeTarget("))
# A mailto: whose case the strip missed used to ride into the draft whole
# ("mailto:MAILTO:list@x"); and an unbuildable mailto URL must be said, not
# swallowed. Both live in the subscriptions sector now.
pin("the mailto strip is case-proof and its failure is spoken",
    'while (t.startsWith("mailto:", ignoreCase = true))' in code_only(KT_MAIL_C)
    and "could not be opened, so nothing was drafted" in code_only(SW_SUBS))

# 6. THE VERSION. One release, two apps: whatever the gradle build calls
# itself, the Xcode project must answer the same -- the tag gate proved the
# Android half right by failing a disagreeing tag; nothing yet proves iOS's
# half, so this pin is the proof until a badging step exists.
GRADLE = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
PYML = (ROOT / "ios/project.yml").read_text(encoding="utf-8")
_m = re.search(r'versionName = "([^"]+)"', GRADLE)
_p = re.search(r'MARKETING_VERSION: "([^"]+)"', PYML)
pin("both apps self-report the same marketing version",
    _m is not None and _p is not None and _m.group(1) == _p.group(1))

# ── 33. the second review round, pinned (§33) ───────────────────────────
# Completeness, correctness and impact each re-read the session with fresh
# eyes and found what the first round's fixes had not covered: a promised
# feature with no editor, an excluded feature shipped as dead code, an
# identity file named after a credential that rotates, a server field both
# decoders dropped, a nav bar hidden everywhere except the root, a signal
# that could be emitted into nobody, and a dispatch default that made a
# forgotten route a blank screen instead of a build error. Each is pinned
# so the CLASS closes, not the instance.

KT_SES = android("data/SessionEvents.kt")

# 1. The plan EXCLUDED bulk mark-all-read (no bulk endpoint); Android had
# shipped a silent per-row loop as dead code, violating ground rules 1 and
# 6. The exclusion now has the pin its siblings have.
pin("neither app carries a mark-all-read entry point",
    "markAllRead" not in code_only(KT_VM)
    and "markAllRead" not in code_only(SW_VM)
    and "Mark all read" not in rendered(KT_BOX) + rendered(SW_BOX))

# 2. The prefs screen's own sentence ("unless an override says otherwise")
# promised per-address signatures compose would read; compose DID read them;
# nothing let anyone write one. Editor and reader, pinned as a pair.
pin("the per-address signature override is edited AND read on both platforms",
    'signatures = toSave' in code_only(KT_ACCT)
    and 'ProfileSettingsPatch(signatures: overridesToSave)' in code_only(SW_ACCT)
    and 's.signatures[it]' in code_only(KT_COMPOSE)
    and 's.signatures[$0]' in code_only(SW_RDR))
pin("the per-address section speaks one sentence on both platforms",
    "When you send as an alias, this is what gets added" in rendered(KT_ACCT)
    and "When you send as an alias, this is what gets added" in rendered(SW_ACCT))

# 3. The identity file is named after the address, never the rotating token,
# and the remove path clears both spellings so a migration cannot resurrect
# a deleted key.
pin("the E2E identity file is keyed by the stable address on both platforms",
    'SessionStore.address(getApplication()) ?: token()' in code_only(KT_E2EVM)
    and 'SessionStore.address ?? token' in code_only(SW_E2EVM))
pin("removing the key clears both the stable and the legacy token name",
    code_only(KT_E2EVM).count("clearIdentity") >= 2
    and len(re.findall(r"clearIdentity", body_of(code_only(KT_E2EVM), "fun removeKey"))) == 2
    and len(re.findall(r"clearIdentity", body_of(code_only(SW_E2EVM), "func removeKey"))) == 2
    and code_only(SW_E2EVM).count("clearIdentity") >= 3)

# 4. The server stores the mailto door in `mailtoOneClick` OUTSIDE the https-
# only offers list; both decoders used to drop it, making the subscriptions
# row's email handoff dead on every real message. Field + read + seed, both
# platforms — the seed carries it because a branch no frame photographs is a
# branch that regrows.
pin("the mailtoOneClick field is decoded, read and seeded on both platforms",
    # Not just the field declaration — the serializer must READ the key off
    # the wire. A mutation that kept `val mailtoOneClick` but dropped the
    # `el["mailtoOneClick"]` lookup (so the field is always null) walked
    # through the first version of this pin; the read is named now.
    "val mailtoOneClick: String? = null" in code_only(KT_MDTO)
    and 'el["mailtoOneClick"]' in code_only(KT_MDTO)
    and "u?.mailtoOneClick?.let" in code_only(KT_MAIL_C)
    and "case offers, oneClickAdvertised, mailtoOneClick, raw" in code_only(SW_MAIL_C)
    and "u.mailtoOneClick" in code_only(SW_MAIL_C)
    and 'mailtoOneClick = "mailto:leave@newsletter.example"' in code_only(KT_HOST)
    and '"mailtoOneClick":"mailto:leave@newsletter.example"' in code_only(SW_HOST))

# 5. The navigation bar hides at the STACK, so the root and the snapshot
# host stop wearing a bar the routed screens dropped (the design set was
# internally inconsistent until this moved).
pin("iOS hides the system bar once at the stack, not per destination",
    code_only(SW_ROOT).count(".toolbar(.hidden, for: .navigationBar)") >= 2)

# 6. The expiry signal survives a missing subscriber (replay 1) and is
# retired by the one that handled it.
pin("the 401 signal is sticky until acted and then reset",
    "replay = 1" in code_only(KT_SES)
    and "resetReplayCache()" in code_only(KT_SES)
    and "SessionEvents.resetExpired()" in code_only(KT_MAIN))

# 7. The sector-split `default:` traded the compiler's exhaustiveness for
# type-check speed; this pin takes the check back at the gate. Every case in
# `enum Route` must appear in a case label somewhere in RootView -- a route
# added without a destination fails here instead of drawing blank.
enum_block = re.search(r"enum Route: Hashable \{([\s\S]*?)\n    \}", SW_ROOT)
route_cases: list[str] = []
if enum_block:
    for line in enum_block.group(1).splitlines():
        m = re.match(r"\s*case\s+(.+)", line)
        if not m:
            continue
        # Split on commas that sit OUTSIDE parameter parentheses:
        # `case secRevoke(id: String, device: String)` declares ONE route;
        # a naive comma split read "device" and "String" as case names and
        # the pin failed on payload labels, not on anything real.
        depth = 0
        parts, cur = [], ""
        for ch in m.group(1):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            if ch == "," and depth == 0:
                parts.append(cur); cur = ""
            else:
                cur += ch
        parts.append(cur)
        for part in parts:
            name = re.match(r"\s*(\w+)", part)
            if name:
                route_cases.append(name.group(1))
# The declarations themselves are not dispatches: search the file with the
# enum block removed, so `case welcome, signIn` inside the enum cannot answer
# for a route no switch actually handles.
_dispatch = SW_ROOT.replace(enum_block.group(0), "") if enum_block else ""
_missing = [n for n in route_cases
            if not re.search(r"case\s+(?:let\s+)?\.%s\b" % n, _dispatch)
            and not re.search(r",\s*\.%s\b" % n, _dispatch)
            and not re.search(r"^\s+\.%s\b" % n, _dispatch, re.M)]
pin("every Route case is dispatched by some sector switch",
    enum_block is not None and len(route_cases) >= 60 and not _missing)

pin("the release job's ceiling covers its own full walk",
    "timeout-minutes: 150" in WF_TEXT and "timeout-minutes: 120" in WF_TEXT)
pin("the new signature fields speak to TalkBack and VoiceOver",
    'contentDescription = "Signature for $address"' in code_only(KT_ACCT)
    and 'accessibilityLabel("Signature for \\(address)")' in code_only(SW_ACCT))

# ── 34. the behavior audit opens with the read/unread pair (§34) ────────────
# The plan's Phase-2 line claimed "per-row + selection read/unread already
# exist". They did not: the actions, the wire values and the local flag
# writes were all in place on both platforms, and the toolbar buttons were
# never drawn — so the unread count was a one-way number and the web's own
# "Mark read"/"Mark unread" pair (page.tsx:2646-2647) had no phone twin.
# The web also records the exact opposite-behavior bug this class produces:
# a "Mark unread" that sent /read. Pin the affordance AND the polarity.
KT_MAIL_C2 = android("ui/components/Mail.kt")
SW_BOXV = ios("MailboxView.swift")
pin("the selection toolbar offers Mark read and Mark unread on both platforms",
    'ToolAction(Icons.Rounded.DoneAll, "Mark read", onMarkRead)' in code_only(KT_MAIL_C)
    and 'ToolAction(Icons.Rounded.MarkunreadMailbox, "Mark unread", onMarkUnread)' in code_only(KT_MAIL_C)
    and 'label: "Mark read", onClick: onMarkRead' in code_only(SW_MAIL_C)
    and 'label: "Mark unread", onClick: onMarkUnread' in code_only(SW_MAIL_C))
pin("the read/unread buttons dispatch their own action, not the other's",
    # Polarity, pinned at the call sites: Mark read -> MailAction.Read,
    # Mark unread -> MailAction.Unread. Swap either one and this fails --
    # which is precisely the bug the web's comment says it shipped once.
    'onMarkRead = { vm.act(com.grapsee.grapmail.data.MailAction.Read) }' in code_only(KT_BOX)
    and 'onMarkUnread = { vm.act(com.grapsee.grapmail.data.MailAction.Unread) }' in code_only(KT_BOX)
    and "onMarkRead: { vm.act(.read) }" in code_only(SW_BOXV)
    and "onMarkUnread: { vm.act(.unread) }" in code_only(SW_BOXV))
pin("selection read/unread stays selection-scoped — no whole-box claim",
    "markAllRead" not in code_only(KT_VM) and "markAllRead" not in code_only(SW_VM))

# The compose parity the frame showed: Android's Subject row read
# "Subject / Subject" (label and placeholder the same word) while iOS
# already said "Add a subject"; the body floors were 160 and 180 on a
# screen whose whole point is the message. One language, one floor.
pin("compose states its subject and body the same way on both platforms",
    'placeholder = "Add a subject"' in code_only(android("ui/screens/mail/ComposeScreen.kt"))
    and 'placeholder: "Add a subject"' in code_only(SW_RDR)
    and "heightIn(min = 240.dp)" in code_only(android("ui/screens/mail/ComposeScreen.kt"))
    and "minHeight: 240" in code_only(SW_RDR)
    and 'placeholder = "Subject",' not in code_only(android("ui/screens/mail/ComposeScreen.kt")))

# The frame that followed proved the floor alone was not enough: a 240dp
# body inside a scrollable column left the whole lower half of the screen
# empty on both platforms (frame 76, both artifact sets). The body must
# GROW to the pinned action bar. Remove either growth and the dead zone
# the audit exists to kill comes straight back. The second re-photograph
# then caught the greedy cure: an infinity-proposed editor handed the
# leftover to every sibling and the Send button grew to a 700pt block,
# so the growth is pinned at the exact-height GeometryReader and the
# infinity proposal is pinned OUT of the editor.
pin("the compose body grows to the action bar — no bottom dead zone",
    "Box(Modifier.weight(1f).heightIn(min = 240.dp))" in code_only(android("ui/screens/mail/ComposeScreen.kt"))
    and "bodyEditor.frame(height: max(240, g.size.height))" in code_only(SW_RDR)
    and "maxHeight: .infinity" not in body_of(code_only(SW_RDR), "private var bodyEditor"))

# The Send block was the third shape of the same fault: a bare
# `minHeight: 54` is a GREEDY floor -- measure nil at the top, grow into
# every point handed out below. Under a growing editor that inflated the
# button to 700pt; Android's twin measures EXACTLY 54dp. The shared
# buttons now state the exact height, and no button in the family may
# carry the grow-into-leftover spelling again.
pin("iOS buttons carry an exact 54 height, never a greedy floor",
    ".frame(height: 54)" in body_of(code_only(ios("Components.swift")), "struct GradientButton")
    and ".frame(height: 54)" in body_of(code_only(ios("Components.swift")), "struct OutlineActionButton")
    and ".frame(height: 54)" in body_of(code_only(ios("SecurityComponents.swift")), "struct DestructiveActionButton")
    and "minHeight: 54, maxHeight: 54" in code_only(ios("SecurityViews.swift")))

# The other half of the centering audit: batch 1 centered the short STATUS
# screens (frame 100 proved the bottom-empty class), but frame 104 showed
# the same Arrangement.Center stranding the key RECORD — a scannable table —
# in the screen's middle under a dead toolbar band. Detail and action screens
# are top-anchored on both platforms; the status card keeps its centering.
pin("the key record and the remove action read top-down, not centered",
    "Arrangement.Center" not in body_of(code_only(KT_E2ES), "fun E2eDetailsScreen")
    and "Arrangement.Center" not in body_of(code_only(KT_E2ES), "fun E2eRemoveScreen")
    and "Spacer(minLength: 0)" not in body_of(code_only(SW_E2ES), "struct E2eDetailsScreen")
    and "Spacer(minLength: 0)" not in body_of(code_only(SW_E2ES), "struct E2eRemoveScreen")
    and "Arrangement.Center" in body_of(code_only(KT_E2ES), "fun E2eStatusScreen"))

# Frames 60 and 99, iOS artifact: the fixed tab row shared the phone width
# between five labels and broke every word mid-spelling ("In/bo/x"), and
# the scaffold-less undo frame drew its banner THROUGH the status bar. The
# five boxes are doors that exist; they stay one-line, scrollable, and
# below the clock at every width the walk runs.
pin("the box tabs stay one-line and scrollable at every device width",
    "ScrollView(.horizontal" in body_of(code_only(SW_CHROME), "struct BoxTabRow")
    and ".fixedSize(horizontal: true, vertical: false)" in body_of(code_only(SW_COMPS), "struct BoxTab")
    and "softWrap = false" in body_of(code_only(KT_MAIL_C2), "fun BoxTab(")
    and "horizontalScroll" in body_of(code_only(KT_BOX), "fun BoxTabRow"))
pin("the undo frame sits below the status bar it once drew through",
    "ScrollView(.vertical" in body_of(code_only(ios("MailSnapshot.swift")), 'case "mail-undo"')
    and "GMUndoBanner" in body_of(code_only(ios("MailSnapshot.swift")), 'case "mail-undo"'))

# Frame 62's photograph caught what no marker could: the drawer's Subscriptions
# row — the only phone door to that screen — sat below an unmarked fold. The
# sections are ordered Mail-before-Queue on both platforms now, and this pin
# keeps the fold's victim above it.
KT_CHROME = android("ui/screens/mail/MailboxScreen.kt")
def _before_(src, first, second):
    a, b = src.find(first), src.find(second)
    return a >= 0 and b >= 0 and a < b
pin("the drawer offers Subscriptions above the fold the old order buried",
    _before_(code_only(KT_CHROME), 'DrawerSection("Mail")', 'DrawerSection("Queue")')
    and _before_(code_only(ios("MailChrome.swift")), 'section("Mail")', 'section("Queue")')
    and code_only(KT_CHROME).count('DrawerSection("Mail")') == 1
    and code_only(ios("MailChrome.swift")).count('section("Mail")') == 1)

# The labels header is count + button on both platforms; Android's copy once
# squeezed its own count to zero width with a fillMaxWidth button (frame 72
# proved it from the photograph). The pin keeps the pair visible together.
pin("the labels header shows its count beside a button that shares the row",
    'OutlineButton("New label", onClick = onCreate, fill = false' in code_only(android("ui/screens/mail/MailSecondaryScreens.kt"))
    and r'\(vm.labelState.labels.count) label' in code_only(SW_SECOND))

# Frame 108's photograph: the recipients "Check" button rendered as a clipped
# circle. A fill=false E2e button carries no intrinsic horizontal padding, so
# the 24dp corner shape ate the short label. Both platforms now inset a
# non-filling button by 22dp; drop either inset and the clip comes back.
pin("a non-filling E2E button keeps its label outside the corner radius",
    ".padding(horizontal = if (fill) 0.dp else 22.dp)" in code_only(KT_E2ES)
    and ".padding(.horizontal, fill ? 0 : 22)" in code_only(SW_E2ES))


# ── 35. the artwork is drawn, not cropped (§35) ──────────────────────────────
# The founder's complaint in one line: "you are just cropping everything from
# my reference". The repo's own recorded convention says the opposite —
# illustration is vector shape code with programmatic motion. The account and
# security flows shipped ~50 raster PNGs on Android and 42 imagesets on iOS,
# paired `_dark` crops standing in for theming, one of them a literal
# landscape photograph behind the puzzle slider. These pins are what keeps
# the crop habit from coming back: they forbid the raster read paths, demand
# the vector files draw real path data, require the motion to honor the
# system's reduced-motion switch, and check the deleted assets stay deleted.
KT_ART = android("ui/artwork/Artwork.kt")
KT_ARTP = android("ui/artwork/ArtPrimitives.kt")
SW_ART = ios("Artwork.swift")
KT_UI_ALL = "\n".join(android(p) for p in (
    "ui/components/Account.kt", "ui/components/PuzzleSlider.kt",
    "ui/screens/WelcomeScreen.kt", "ui/screens/NameStepScreen.kt",
    "ui/screens/HumanVerifyScreen.kt", "ui/screens/CreatedScreens.kt"))
SW_UI_ALL = "\n".join(ios(p) for p in (
    "AccountComponents.swift", "AuthScreens.swift", "FinishScreens.swift",
    "WizardSteps.swift", "PuzzleSlider.swift"))

pin("no UI screen reads an illustration raster anymore",
    "painterResource(illRes" not in code_only(KT_UI_ALL)
    and 'Image("ill_' not in code_only(SW_UI_ALL)
    and 'Image(illustration)' not in code_only(SW_UI_ALL)
    and 'Image("puzzle_photo")' not in code_only(SW_UI_ALL)
    and "Artwork(" in code_only(KT_UI_ALL)
    and ("Art.view(" in code_only(SW_UI_ALL) or "Art.fill(" in code_only(SW_UI_ALL)))

pin("the illustration mapper and its raster pipeline are gone, not shadowed",
    not (ROOT / "android/app/src/main/java/com/grapsee/grapmail/ui/components/IllRes.kt").exists()
    and sorted(f.name for f in (ROOT / "android/app/src/main/res/drawable-nodpi").glob("*"))
        == ["grapmail_logo.png"]
    and not list((ROOT / "ios/GrapMail/Assets.xcassets").glob("ill_*.imageset"))
    and not list((ROOT / "ios/GrapMail/Assets.xcassets").glob("puzzle_photo.imageset")))

pin("the artwork draws path data, not pixels, on both platforms",
    "Path().apply" in code_only(KT_ARTP) and "Brush.linearGradient" in code_only(KT_ARTP)
    and "Path(roundedRect:" in code_only(SW_ART)
    and "GraphicsContext.Shading" in code_only(SW_ART)
    and "linearGradient" in code_only(SW_ART))

pin("the artwork animates programmatically and honors reduced motion",
    "delay(80)" in code_only(KT_ARTP)
    and "ANIMATOR_DURATION_SCALE" in code_only(KT_ARTP)
    and "TimelineView(.animation" in code_only(SW_ART)
    and "accessibilityReduceMotion" in code_only(SW_ART))

# The entrance ENDS. This is not taste: a timeline that ticks forever
# re-invalidates the enclosing scroll content, and on the CI simulator that
# silently stopped the security hub's 2FA switch taking the walk's tap —
# 'missing: 2FA enable (gate leg)' on every artwork commit until the
# timeline was paused, which is how it was proven. Revert either platform
# to an endless loop and the walk breaks again, so the settle is pinned.
pin("the artwork settles instead of animating forever",
    "artSettleSeconds" in code_only(KT_ARTP)
    and "coerceAtMost(artSettleSeconds)" in code_only(KT_ARTP)
    and "if (elapsed >= artSettleSeconds) break" in code_only(KT_ARTP)
    and "paused: settled || reduceMotion" in code_only(SW_ART)
    and "settled = true" in code_only(SW_ART))

# The artwork's SIZE is a behavior constraint, not a taste question. Left at
# a fraction of screen width with a 2:1 ratio, the drawn scene stood far
# taller than the PNG it replaced; on the smallest walk device that pushed
# "Skip for now" down onto the puzzle slider, so the walk's tap dragged the
# slider instead ("Almost — try again"), verification never completed, and
# every frame after it failed. A decorative illustration must never be able
# to move an action out of reach: the call sites carry a bounded width, and
# the iOS fill helper caps the height.
_ART_SITES = [android(p) for p in (
    "ui/screens/HumanVerifyScreen.kt", "ui/screens/CreatedScreens.kt",
    "ui/screens/NameStepScreen.kt", "ui/screens/WelcomeScreen.kt")]
# The welcome call site now renders the founder's real logo (bounded at 150dp,
# ContentScale.Fit — never cropped); the remaining Artwork scenes keep their
# bounds, and the iOS fill helper still caps the height.
pin("artwork call sites are bounded, never a screen-width fraction",
    all("fillMaxWidth(0." not in code_only(s) or "Artwork(" not in code_only(s)
        for s in _ART_SITES)
    and all("aspectRatio(2f)" in code_only(s) for s in _ART_SITES
            if s != android("ui/screens/WelcomeScreen.kt"))
    and "Modifier.width(150.dp)" in code_only(android("ui/screens/WelcomeScreen.kt"))
    and "ContentScale.Fit" in code_only(android("ui/screens/WelcomeScreen.kt"))
    and ".frame(maxHeight: 200)" in code_only(SW_ART))

# The old illRes table named every scene; the dispatch must still answer each
# one, or a screen silently photographs the fallback while believing it drew
# its own artwork. The names are checked against the deleted mapper's list.
_ART_NAMES = [
    "welcome", "hero", "step1_name", "verify", "created_a", "created_b", "puzzle",
    "account-2fa", "account-backup", "account-forgot", "account-recovery-code",
    "account-new-password", "account-reset-ok", "account-pending",
    "account-find-address", "account-address-found", "account-phone", "account-phone-ok",
    "ill_sec_hub", "ill_sec_hub_secure", "ill_sec_secure", "ill_sec_2fa", "ill_sec_2fa_on",
    "ill_sec_2fa_off", "ill_sec_2fa_off_confirm", "ill_sec_password",
    "ill_sec_password_confirm", "ill_sec_password_ok", "ill_sec_password_after",
    "ill_sec_password_updated", "ill_sec_phone", "ill_sec_backup_generate",
    "ill_sec_backup_saved", "ill_sec_signout_all", "ill_sec_signout_all_ok",
    "ill_sec_sessions", "ill_sec_session_revoke", "ill_sec_session_revoked",
    "ill_sec_revoke_confirm", "ill_sec_revoked_suspicious", "ill_sec_activity", "ill_sec_alert",
]
pin("every scene name the raster table answered for still dispatches on both platforms",
    all(f'"{n}"' in code_only(KT_ART) for n in _ART_NAMES)
    and all(f'"{n}"' in code_only(SW_ART) for n in _ART_NAMES))

# Dark mode was a second PNG per scene (`ill_*_dark.png`); the vector scenes
# read the live palette instead, so a dark frame is the same code with the
# dark traits. A hardcoded artwork color would silently break that.
pin("the artwork takes its colors from the palette, not from literals",
    "LocalPalette.current" in code_only(KT_ART)
    and "GM.primaryTop" in code_only(SW_ART)
    and "0xFF206BFD" not in code_only(KT_ART)
    and "0x206BFD" not in code_only(SW_ART))

# ── 36. a draft that cannot be finished is not a draft (§36) ─────────────────
# Tapping a draft opened the READ-ONLY thread on both platforms: the person
# saw their own half-written mail with no way to edit it or send it. Every
# mail client opens a draft in the composer; this is the core affordance the
# app was missing, and the cleanup after sending is the half that keeps the
# Drafts box honest.
pin("a draft row opens the composer, never the reader",
    "state.box == MailBox.Drafts" in code_only(KT_BOX)
    and "onOpenDraft(row.id)" in code_only(KT_BOX)
    and "onOpenDraft: (id: String) -> Unit" in code_only(KT_BOX)
    and 'mode: "draft"' in code_only(ios("RootView.swift"))
    and "vm.state.box == .drafts" in code_only(ios("MailboxView.swift"))
    and "onDraft(row.id)" in code_only(ios("MailboxView.swift")))

pin("the draft loads as the person's own text, not as a reply",
    # No quote block, no "Re:", no second signature: the draft already has
    # whatever they saved, and re-quoting it would corrupt the mail.
    '"draft" -> _compose.update' in code_only(KT_VM)
    and 'case "draft":' in code_only(SW_VM)
    and "draftId = full.id" in code_only(KT_VM)
    and "self.compose.draftId = m.id" in code_only(SW_VM))

# The order is the safety property: delete AFTER a confirmed send. A client
# that deleted first would answer a failed send by destroying the only copy
# of the person's unfinished message.
pin("the sent draft is deleted only after the server accepted the send",
    'val sent = r.status == "sent" || r.status == "queued"' in code_only(KT_VM)
    and 'if (draftId.isNotEmpty() && sent)' in code_only(KT_VM)
    and "let sent = r.status == \"sent\" || r.status == \"queued\"" in code_only(SW_VM)
    and "if !draft.isEmpty && sent" in code_only(SW_VM))

pin("the draft cleanup is its own named call, not the Trash destroyer",
    "suspend fun mailDeleteMessage" in code_only(android("data/Api.kt"))
    and "static func mailDeleteMessage" in code_only(ios("MailAPI.swift")))

# ── 37. an attachment you cannot read is only half an attachment (§37) ───────
# The preview screen could save a file and print the path it chose. The code
# said so out loud — "Opening it is deliberately not attempted here" — and
# called it the sector's one unfinished affordance. A person who saves a PDF
# to open it in the app that reads PDFs has not finished anything.
KT_ATTACH = android("logic/MailboxViewModel.kt")
SW_ATTACH = ios("MailboxModel.swift")
KT_ATTACH_UI = android("ui/screens/mail/MailSecondaryScreens.kt")
SW_ATTACH_UI = ios("MailSecondaryViews.swift")

pin("the attachment handoff is wired on both platforms",
    "fun openAttachment(a: MailAttachmentDto" in code_only(KT_ATTACH)
    and "func prepareAttachment(_ a: MailAttachment" in code_only(SW_ATTACH)
    and 'vm.openAttachment(a)' in code_only(KT_ATTACH_UI)
    and 'vm.prepareAttachment(a)' in code_only(SW_ATTACH_UI)
    and 'FileProvider.getUriForFile' in code_only(KT_ATTACH)
    and "QuickLookPresenter" in code_only(SW_ATTACH_UI))

# ACTION_VIEW on a file:// URI is rejected outright on modern Android — a
# FileUriExposedException, which is precisely why the affordance was left
# undone. The provider and its declared paths are what make the handoff
# legal, so both must exist beside the call.
pin("Android's handoff goes through a declared FileProvider, not a file URI",
    "androidx.core.content.FileProvider" in (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    and (ROOT / "android/app/src/main/res/xml/file_paths.xml").exists()
    and "Uri.fromFile" not in code_only(KT_ATTACH))

# The honest failure: no viewer on the device is a real state, and it gets
# said. A silent no-op after a tap is the opposite-behavior class — the
# button appears to do nothing, which reads as a broken app.
pin("a missing viewer is reported rather than swallowed",
    "ActivityNotFoundException" in code_only(KT_ATTACH)
    and "Nothing on this device can open" in code_only(KT_ATTACH)
    and "This attachment could not be opened." in code_only(SW_ATTACH_UI))

# One affordance, one name, on both platforms — the same rule the other
# interactive screens follow, so a walk or an assistive reader finds the
# control under the same name wherever the mail is opened.
pin("the open control answers to the same identifier on both platforms",
    'testTag("mail-attachment-open")' in code_only(KT_ATTACH_UI)
    and 'accessibilityIdentifier("mail-attachment-open")' in code_only(SW_ATTACH_UI))

# ── 38. the reader does the four things a reader is for (§38) ────────────────
# Stepping, undoing the read an open caused, deciding who gets to load remote
# images, and getting the message out. Each of these was a reader that stopped
# short: the person had to go back to the list for the next message, could not
# mark the one they had just read as unread, had no word about the images the
# sender's HTML pointed at, and had no way to hand the text to anything.
#
# The four are pinned together because they share one risk: each is a control
# that can exist on screen and be wired to nothing, or be wired to something
# that quietly costs the person more than the button said.
KT_THREAD = android("ui/screens/mail/ThreadScreen.kt")
SW_READ = ios("MailReaderViews.swift")
KT_HANDOFF = android("ui/screens/mail/MessageHandoff.kt")
SW_HANDOFF = ios("MailHandoff.swift")
KT_SNAP = android("ui/screens/mail/MailSnapshot.kt")
SW_SNAP = ios("MailSnapshot.swift")
KT_WALK = (ROOT / "scripts/android-walk.sh").read_text(encoding="utf-8")
SW_WALK = (ROOT / "ios/GrapMailUITests/ScreenshotWalkUITests.swift") \
    .read_text(encoding="utf-8")

# ── stepping ────────────────────────────────────────────────────────────────
pin("the reader can step on both platforms",
    "onStepPrev: (MailStep) -> Unit" in code_only(KT_THREAD)
    and "onStepNext: (MailStep) -> Unit" in code_only(KT_THREAD)
    and "let onStepPrev: (MailStep) -> Void" in code_only(SW_READ)
    and "let onStepNext: (MailStep) -> Void" in code_only(SW_READ))

# The range the chevrons actually cover. `state.total` is the box's own count,
# which on a paged mailbox is a number this device has never seen: a reader
# saying "3 of 400" while its next button runs out at row 12 is a control
# promising a walk it cannot take. Both sides count LOADED rows and say so.
pin("the stepper names the loaded range, not the box's total",
    'val (prior, next) = vm.neighboursOf(threadId)' in code_only(KT_THREAD)
    and '"$at of $total loaded"' in code_only(KT_THREAD)
    and "let near = vm.neighbours(of: id)" in code_only(SW_READ)
    # Raw string: `\(…)` is not a Python escape, and a future interpreter
    # turns the deprecation warning on this line into a hard error.
    and r'of \(total) loaded' in code_only(SW_READ))

# The target carries the thread flag, because the two routes are different
# resources: `/thread/:id` answers a message id with a 404. A stepper that
# hardcoded "it is a thread" would show "could not be opened" for mail that
# exists — the same dead end the list's own `isThread` flag exists to avoid,
# reopened one button further in.
pin("the step target decides the route from the row, never a hardcoded flag",
    "MailStep(it.threadId ?: it.id, it.threadId != null)" in code_only(KT_VM)
    and "MailStep(id: row.threadId ?? row.id, isThread: row.threadId != nil)"
        in code_only(SW_MODEL)
    and code_only(KT_MAIN).count("Routes.mailThread(step.id, step.isThread)") == 2
    and code_only(SW_ROOT).count(".mailThread(id: step.id, isThread: step.isThread)") == 2
    and "Routes.mailThread(id, true)" not in code_only(KT_MAIN)
    and "isThread: true" not in code_only(SW_ROOT))

# Five nexts must not bury the list under five identical screens the back button
# then has to unwind one at a time. Android replaces the destination; iOS pops
# and re-pushes, which also rebuilds the view so the new message is fetched (a
# reused ThreadView would not fire its appear-time ask again).
pin("stepping replaces the reader instead of stacking another copy",
    code_only(KT_MAIN).count("popUpTo(Routes.MAIL_THREAD) { inclusive = true }") == 2
    and code_only(SW_ROOT).count("path.removeLast()") >= 2)

# ── the read an open caused ─────────────────────────────────────────────────
KT_VIEW_MESSAGE = body_of(KT_VM, "suspend fun viewMessage")
SW_VIEW_MESSAGE = body_of(SW_VM, "func viewMessage")
pin("conversation cards request attachments and confirm read changes for the visible message",
    'vm.viewMessage(message.id)' in code_only(KT_THREAD)
    and 'await vm.viewMessage(message.id)' in code_only(SW_READ)
    and "Api.mailMessage(session.token, id)" in KT_VIEW_MESSAGE
    and "ApiClient.mailMessage(token: token, id: id)" in SW_VIEW_MESSAGE
    and "Api.mailAction(session.token, id, MailAction.Read)" in KT_VIEW_MESSAGE
    and "ApiClient.mailAction(token: token, id: id, action: .read)" in SW_VIEW_MESSAGE)
pin("a thread listing does not mark its first message read locally",
    "patchFlags(" not in body_of(KT_VM, "fun openThread")
    and "patchFlags(" not in body_of(SW_VM, "func openThread"))

# Opening marks read on the server. The only way back out of that state lived in
# the list's selection toolbar — on a screen the person has left.
pin("the reader can mark its own message unread",
    "fun markUnread(id: String, onOutcome: (String?) -> Unit)" in code_only(KT_VM)
    and "func markUnread(_ id: String, onOutcome: @escaping (String?) -> Void)"
        in code_only(SW_MODEL)
    and "vm.markUnread(message.id)" in code_only(KT_THREAD)
    and "vm.markUnread(message.id)" in code_only(SW_READ))

# The control is gated on the flag it changes: "Mark unread" on mail that is
# already unread is a button wired to nothing, and after the write succeeds the
# thread's own copy of the message flips, so the control leaves with the state
# it described.
pin("the unread control appears only while the message is read",
    "if (message.isRead) {" in code_only(KT_THREAD)
    and "if message.isRead {" in code_only(SW_READ)
    and "applyLocally(id, MailAction.Unread)" in body_of(KT_VM, "fun markUnread")
    and "applyLocally(id: id, action: .unread)" in body_of(SW_MODEL, "func markUnread")
    and "isRead = read ?: message.isRead" in body_of(KT_VM, "private suspend fun applyLocally")
    and "message.isRead = flags.read ?? message.isRead" in body_of(SW_MODEL, "private func applyLocally"))

# The reader reports its own outcome. `actOn` files a failure into the list's
# stale-read banner, which is not on screen here — so a refused write would have
# looked exactly like an accepted one.
pin("accepted and refused are two different sentences in the reader",
    'Text("Marked unread."' in code_only(KT_THREAD)
    and 'Text("Marked unread.")' in code_only(SW_READ)
    and "You are signed out, so this could not be saved." in code_only(KT_VM)
    and "You are signed out, so this could not be saved." in code_only(SW_MODEL))

# ── the images the sender hosts ─────────────────────────────────────────────
# The HTML part is not rendered, and that decision already blocks every remote
# image. Blocking silently is the other wrong answer: the person reads a message
# with a picture in it, sees a gap, and is told nothing about why. So the reader
# names the count, says whose server they live on, says what loading them
# reveals, and asks.
pin("remote images are named and gated, per message",
    "withContext(Dispatchers.Default) { remoteImageUrls(message.bodyHtml) }" in code_only(KT_THREAD)
    and "await Self.findRemoteImages(in: message.bodyHtml)" in code_only(SW_READ)
    and "tells the sender this message was opened" in code_only(KT_THREAD)
    and "tells the sender this message was opened" in code_only(SW_READ))

pin("Both readers load bodies only for open conversation cards",
    "val needsFormattedBody = showsBody &&" in code_only(KT_THREAD)
    and "showsBody && !message.isE2eEncrypted" in code_only(SW_READ)
    and ".task(id: needsFormattedBody ? message.bodyHtml : nil)" in code_only(SW_READ))

# The opt-in starts closed and belongs to one message. A shared flag would carry
# a consent given for one sender's mail to the next one's, and Android's card is
# reused across a step — an unkeyed `remember` would have kept it open.
pin("the opt-in starts closed and is keyed to its own message",
    "remember(message.id) { mutableStateOf(false) }" in code_only(KT_THREAD)
    and "@State private var shownImages = false" in code_only(SW_READ))

# Nothing reaches the sender's server until the person asks — and the engine
# agrees with the reader now: the sanctioned HTML carries no remote `src` until
# the opt-in re-fetches the body with `?images=1`, and the island's own
# settings refuse remote image loads while the gate is closed. The images-gate
# fetch is keyed on the opt-in itself, so a closed gate cannot produce a body
# variant with live images in it. The standalone loader the print/share handoff
# uses keeps its own no-cookie client (pinned below).
pin("no image is fetched before the person asks",
    "blockNetworkImage = !allowImages" in code_only(android("ui/screens/mail/MailHtmlBody.kt"))
    and "vm.loadBody(message.id, shownImages)" in code_only(KT_THREAD)
    and ".task(id: needsFormattedBody ? shownImages : nil)" in code_only(SW_READ)
    and "vm.loadBody(message.id, allowImages: allowImages)" in code_only(SW_READ))

# The extraction rule, not the markup: only an absolute http(s) `img src` is
# ever kept. `data:` asks nothing of the network, `cid:` is an attachment the
# rows already offer, and anything else reaches no request.
pin("only an absolute http(s) image address is ever considered",
    'if (scheme != "http" && scheme != "https") return null' in code_only(KT_HANDOFF)
    and 'guard scheme == "http" || scheme == "https" else { return nil }'
        in code_only(SW_HANDOFF))

# A sender's server has no business holding a GrapMail session. The loader is
# its own client on both platforms: no auth header, no cookie jar, and no route
# through the API client that has either.
pin("the image loader carries no session and no cookies",
    "CookieJar.NO_COOKIES" in code_only(KT_HANDOFF)
    and "config.httpCookieAcceptPolicy = .never" in code_only(SW_HANDOFF)
    and "token" not in code_only(KT_HANDOFF).lower()
    and "token" not in code_only(SW_HANDOFF).lower()
    and "ApiClient" not in code_only(SW_HANDOFF)
    and "Api." not in code_only(KT_HANDOFF))

# The content type is checked, not trusted: an <img> tag does not license this
# app to hand a document to a renderer, and a 404 page is the common answer.
pin("a response is only drawn when the server says it is an image",
    'if (!type.startsWith("image/"))' in code_only(KT_HANDOFF)
    and 'if !mime.hasPrefix("image/")' in code_only(SW_HANDOFF)
    and "That address is not an image" in code_only(KT_HANDOFF)
    and "That address is not an image" in code_only(SW_HANDOFF))

# Four answers a fetch can have, and each said as itself. "Could not load" for
# every one of them would let a refused request read as a network problem.
pin("a failed image says which kind of failure it was",
    all(s in code_only(KT_HANDOFF) for s in
        ("refused the image", "could not be reached", "could not be read",
         "not one this app can request"))
    and all(s in code_only(SW_HANDOFF) for s in
        ("refused the image", "could not be reached", "could not be read",
         "not one this app can request")))

# ── getting the message out ─────────────────────────────────────────────────
# One document, both handoffs. Two builders drift invisibly — the shared card
# saying the mail came from somebody while the printed page said nothing, or one
# of them starting to carry the HTML part. Each platform defines the document
# once and reaches it from both controls.
pin("Android shares and prints the same one document",
    re.search(r"^fun messageShareText", code_only(KT_HANDOFF), re.M) is not None
    and "TextPrintAdapter(messageShareText(message), docName)" in code_only(KT_HANDOFF)
    and "EXTRA_TEXT, messageShareText(message)" in code_only(KT_THREAD))
pin("iOS shares and prints the same one document",
    re.search(r"^func messageShareText", code_only(SW_HANDOFF), re.M) is not None
    and "text: messageShareText(message))" in code_only(SW_HANDOFF)
    and "MessageActivityView(text: messageShareText(message))" in code_only(SW_READ))

# The print path has no renderer in it, on either platform. The usual recipe —
# an off-screen web view of the HTML part, or `printHTMLURL` — is the exact thing
# §4 forbids for the screen, moved to a surface this app controls even less.
pin("neither print path renders HTML anywhere",
    "printHTMLURL" not in code_only(SW_HANDOFF)
    and "UIPrintFormatter.from(webView" not in code_only(SW_HANDOFF)
    and "UISimpleTextPrintFormatter" in code_only(SW_HANDOFF)
    and "PrintDocumentAdapter" in code_only(KT_HANDOFF)
    and "WebView" not in code_only(KT_HANDOFF))

# A device that cannot print, or has nothing to hand text to, is a real state
# and gets said. A button that appeared to do nothing reads as a broken app.
pin("a handoff the device refuses is reported, not swallowed",
    "This device has no print service." in code_only(KT_HANDOFF)
    and "This device could not open its print screen." in code_only(KT_HANDOFF)
    and "This device could not open its print screen." in code_only(SW_HANDOFF)
    and "Nothing on this device can receive shared text." in code_only(KT_THREAD))

# The shared item is text, on both platforms: handing a receiver the HTML part
# would make that app do the rendering this one refused to do.
pin("what leaves the app is text, never the HTML part",
    '.setType("text/plain")' in code_only(KT_THREAD)
    and "UIActivityViewController(activityItems: [text]" in code_only(SW_HANDOFF)
    and "EXTRA_TEXT, messageShareText(message)" in code_only(KT_THREAD)
    and "MessageActivityView(text: messageShareText(message))" in code_only(SW_READ))

# One affordance, one name on both platforms — the rule every other interactive
# screen in this sector follows, so a walk or an assistive reader finds the same
# control wherever the mail is opened. iOS names them at the call site of its
# `ReaderOutlineButton`, which is where the identifier is written as a literal.
for ident in ("mail-thread-prev", "mail-thread-next", "mail-mark-unread",
              "mail-share", "mail-print", "mail-show-images"):
    pin(f"the reader control {ident} answers to the same name on both platforms",
        f'testTag("{ident}")' in code_only(KT_THREAD)
        and (f'accessibilityIdentifier("{ident}")' in code_only(SW_READ)
             or f'identifier: "{ident}"' in code_only(SW_READ)))

# The greedy-width defect 74-mail-thread photographed: `OutlineButton` fills its
# width, a Row measures each unweighted child against the space LEFT, and two of
# the reader's three reply doors were drawn at zero width while iOS showed all
# three. The fix is content-sized buttons; a pin keeps it from being "tidied"
# back, because the regression is invisible to every other gate in CI.
pin("the reader's action rows size their buttons to their content",
    code_only(KT_THREAD).count("fill = false") == 7
    and code_only(KT_THREAD).count("OutlineButton(") == 9
    and "trailing = { Icon(Icons.Rounded.Reply" not in code_only(KT_THREAD))

# The frame that proves all of this composes: an HTML-only message opened in the
# middle of a seeded list, named the same on both platforms and launched by both
# walks. The images-shown state is deliberately NOT photographed — it would mean
# CI asking a sender's server for bytes, and a frame of a loading spinner proves
# nothing the gate does not already prove in code.
pin("116-mail-thread-images is a real frame on both platforms",
    KT_WALK.count("116-mail-thread-images") == 2
    and 'mail-thread-images) settled="mail-show-images"' in KT_WALK
    and '("116-mail-thread-images", "mail-thread-images")' in SW_WALK
    and '"mail-thread-images": "This week in letterforms"' in SW_WALK
    and "mail-thread-images" in code_only(KT_SNAP)
    and "mail-thread-images" in code_only(SW_SNAP))

# The reader's own list positions come from the same seeded rows on both sides,
# so the range the frame photographs is the range the code computes.
pin("the thread frames seed the rows the stepper counts",
    "seedRows(vm)" in code_only(KT_SNAP)
    and "s.rows = MailSnapshotView.rows" in code_only(SW_SNAP))

# ── 39. the app is installed on a device, not rendered in a simulator (§39) ──
# The audit's third complaint was "native app has certain rules", and the
# evidence was that every one of those rules was missing: no icon file at all in
# the iOS catalog, no splash, no haptics on the actions that matter, no
# shortcut, no background read, and one reference to offline in the whole
# repository. Each item below is pinned because the failure mode is invisible to
# a screenshot gate — a blank starting window photographs the same as a branded
# one, and a notification that can never be posted runs its code perfectly.
YML = (ROOT / "ios/project.yml").read_text(encoding="utf-8")
KT_MANIFEST = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
KT_GRADLE = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
KT_APP = android("GrapMailApp.kt")
KT_POLL = android("logic/MailPollWorker.kt")
KT_NOTIF = android("logic/Notifications.kt")
KT_HAPTIC = android("logic/GMHaptics.kt")
KT_BOX_UI = android("ui/screens/mail/MailboxScreen.kt")
KT_SEC_UI = android("ui/screens/mail/MailSecondaryScreens.kt")
SW_INT = ios("AppIntegration.swift")
SW_APP = ios("GrapMailApp.swift")
SW_ROOT = ios("RootView.swift")
SW_BOX = ios("MailboxView.swift")
SW_SEC = ios("MailSecondaryViews.swift")
SW_MODEL = ios("MailboxModel.swift")

# ── the icon and the launch window ──────────────────────────────────────────
def splash_before_super(kotlin: str) -> bool:
    """True when `installSplashScreen()` is called ahead of `super.onCreate`.

    Written as a function rather than an inline `.index()` comparison because a
    missing call must FAIL the pin, not raise: `.index()` on an absent
    substring throws a ValueError, the gate dies before printing anything, and
    the section goes quiet while still looking like a broken script.
    """
    body = re.search(r"override fun onCreate\(savedInstanceState: Bundle\?\) \{(.*?)\n    \}",
                    kotlin, re.S)
    if body is None:
        return False
    text = body.group(1)
    splash = text.find("installSplashScreen()")
    super_call = text.find("super.onCreate(savedInstanceState)")
    return splash != -1 and super_call != -1 and splash < super_call


# The catalog entry existed with NO filename: the app installed with the generic
# placeholder and no CI step could see it, because nothing in a screenshot walk
# photographs the home screen. An icon set that names a file is the only proof
# the icon is real.
ICON_SET = ROOT / "ios/GrapMail/Assets.xcassets/AppIcon.appiconset"
icon_json = (ICON_SET / "Contents.json").read_text(encoding="utf-8")
pin("the iOS app icon points at a file that exists",
    '"filename"' in icon_json
    and (ICON_SET / "AppIcon-1024.png").exists()
    and (ICON_SET / "AppIcon-1024.png").stat().st_size > 4096)

pin("the iOS native launch screen uses the branded image and background",
    "UILaunchScreen:" in YML
    and "UIImageName: LaunchBrand" in YML
    and "UIColorName: LaunchBackground" in YML
    and not (ROOT / "ios/GrapMail/LaunchScreen.storyboard").exists()
    and not (ROOT / "ios/GrapMail/LaunchSplashView.swift").exists()
    and (ROOT / "ios/GrapMail/Assets.xcassets/LaunchBrand.imageset/LaunchBrand.png").exists()
    and (ROOT / "ios/GrapMail/Assets.xcassets/LaunchBackground.colorset/Contents.json").exists()
    and "INFOPLIST_KEY_UILaunchScreen_Generation" not in YML)

pin("Android's splash theme is installed before the window content exists",
    'androidx.core:core-splashscreen' in KT_GRADLE
    and "Theme.GrapMail.Splash" in (ROOT / "android/app/src/main/res/values/themes.xml")
        .read_text(encoding="utf-8")
    and 'windowSplashScreenAnimatedIcon">@drawable/grapmail_splash_icon' in (ROOT / "android/app/src/main/res/values/themes.xml")
        .read_text(encoding="utf-8")
    and "androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen" in code_only(KT_MAIN)
    # The order is the whole rule: installed after `super.onCreate`, the system
    # has already drawn its own starting window and the splash never appears.
    # Measured on the onCreate body, and written so a MISSING call fails the pin
    # rather than crashing the gate — a Python ValueError here would abort the
    # run before any other pin reported, which is how a section goes quiet.
    and splash_before_super(code_only(KT_MAIN))
    and 'android:windowSplashScreenBrandingImage">@drawable/grapsee_splash_branding' in
        (ROOT / "android/app/src/main/res/values-v31/splash.xml").read_text(encoding="utf-8")
    and (ROOT / "android/app/src/main/res/drawable/grapsee_splash_branding.xml").is_file()
    and "LaunchSplash" not in code_only(KT_MAIN)
    # The theme the manifest points at is what the system draws the splash
    # from; installing the library under a theme that does not declare it shows
    # the blank window and nothing says so.
    and 'android:theme="@style/Theme.GrapMail.Splash"' in KT_MANIFEST)

# One mark, three surfaces: the launcher, the splash and the iOS launch screen
# are the same two paths, so the brand does not change shape between them.
# The 3D mark cannot be a hand-written vector, so the launcher ships as raster
# adaptive layers: one foreground + one themed monochrome per density, both XMLs
# (adaptive + round), roundIcon in the manifest, the iOS 1024, and the splash
# vector. A missing density or a leaked debug receiver fails here, not in CI.
_DENS = ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
_LAUNCHER_FILES = tuple(
    ROOT / "android/app/src/main/res" / f"mipmap-{d}" / n
    for d in _DENS
    for n in ("ic_launcher_foreground.png", "ic_launcher_monochrome.png"))
_ADAPTIVE = ROOT / "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
_round_xml = ROOT / "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"
_debug_src = ROOT / "android/app/src/debug"
pin("the new logo is the launcher on every density, themed, round, iOS and splash",
    all(f.exists() and f.stat().st_size > 1000 for f in _LAUNCHER_FILES)
    and _ADAPTIVE.exists()
    and "ic_launcher_foreground" in _ADAPTIVE.read_text(encoding="utf-8")
    and "ic_launcher_monochrome" in _ADAPTIVE.read_text(encoding="utf-8")
    and _round_xml.exists()
    and 'android:roundIcon="@mipmap/ic_launcher"' in KT_MANIFEST
    and (ICON_SET / "AppIcon-1024.png").exists()
    and (ICON_SET / "AppIcon-1024.png").stat().st_size > 40000
    and "@drawable/grapmail_splash_icon" in (ROOT / "android/app/src/main/res/values/themes.xml")
        .read_text(encoding="utf-8")
    and not (ROOT / "android/app/src/main/res/drawable/splash_logo.xml").exists()
    and sorted(f.name for f in (ROOT / "android/app/src/main/res/drawable-nodpi").glob("*"))
        == ["grapmail_logo.png"]
    and _debug_src.exists()
    and (_debug_src / "java/com/grapsee/grapmail/DebugPollReceiver.kt").exists()
    and (_debug_src / "AndroidManifest.xml").exists()
    and "DEBUG_RUN_POLL" in (_debug_src / "AndroidManifest.xml").read_text(encoding="utf-8")
    and "DEBUG_RUN_POLL" not in KT_MANIFEST)

# ── haptics that belong to the result ───────────────────────────────────────
pin("filing, starring and sending all have a feel, on both platforms",
    "GMHaptics.filing()" in code_only(SW_MODEL)
    and "GMHaptics.marking()" in code_only(SW_MODEL)
    and "GMHaptics.success()" in code_only(SW_MODEL)
    and "GMHaptics.failure()" in code_only(SW_MODEL)
    and "GMHaptics.filing()" in code_only(KT_VM)
    and "GMHaptics.marking()" in code_only(KT_VM)
    and "GMHaptics.success()" in code_only(KT_VM)
    and "GMHaptics.failure()" in code_only(KT_VM))

# The rule, not just the call: Android's model emits and the view plays, so a
# haptic cannot fire on a tap the server refused. A `performHapticFeedback` in a
# button's own onClick is decoration — it feels identical whether the write
# worked or not, which is the one thing a haptic must never be.
pin("the feel is attached to the write, never to the tap",
    "object GMHaptics" in code_only(KT_HAPTIC)
    and "MutableSharedFlow" in code_only(KT_HAPTIC)
    and "GMHaptics.events.collect" in code_only(KT_MAIN)
    and "performHapticFeedback" in code_only(KT_MAIN)
    and "performHapticFeedback" not in code_only(KT_BOX_UI)
    and "GMHaptics" not in code_only(KT_BOX_UI))

# ── the background read ─────────────────────────────────────────────────────
pin("the new-mail read is registered on both platforms",
    "BGTaskScheduler.shared.register" in code_only(SW_INT)
    # Registered and RE-ASKED FOR: a refresh handler that completes without
    # submitting the next request leaves the app never polling again, and the
    # person sees silence under a row that says "On".
    and "BGTaskScheduler.shared.submit" in code_only(SW_INT)
    and "MailPoll.scheduleNext()" in code_only(SW_INT)
    and "UIBackgroundModes" in YML and "- fetch" in YML
    and "BGAppRefreshTaskRequest" in code_only(SW_INT)
    and "androidx.work:work-runtime-ktx" in KT_GRADLE
    and "PeriodicWorkRequestBuilder<MailPollWorker>" in code_only(KT_APP)
    and "enqueueUniquePeriodicWork" in code_only(KT_APP)
    and "ExistingPeriodicWorkPolicy.KEEP" in code_only(KT_APP))

# Both read the same endpoint the mailbox badge already uses. Nothing here
# invents a source of truth: `GET /stats` is the answer, and a count that came
# from anywhere else would be a second, contradictory tally.
pin("the poll asks the server's own unread count, nothing else",
    "ApiClient.mailStats(token)" in code_only(SW_INT)
    and "Api.mailStats(token)" in code_only(KT_POLL)
    and "inbox?.unread" in code_only(SW_INT)
    and "stats.inbox?.unread" in code_only(KT_POLL))

# The silence rules. A first read has no previous number, so no difference can
# be named; a refused read saw nothing; and a count that did not rise saw no
# arrivals. Announcing on any of those would tell the person the mailbox grew
# when all that happened is that the app looked at it.
pin("a poll that cannot name a difference says nothing",
    "guard didSee, count > previous else { return false }" in code_only(SW_INT)
    and 'if (!hadPrevious) {\n            report("silent first-read unread=$unread")\n            return Result.success()\n        }' in code_only(KT_POLL)
    and 'if (unread <= previous) {\n            report("silent no-rise $previous->$unread")\n            return Result.success()\n        }' in code_only(KT_POLL))

# Both say what the notification is evidence of, in the same two numbers, and
# neither claims delivery — the relay's queue is not the recipient's inbox. The
# forbidden word is scanned over LITERALS (strings_of), not code: the sign-out
# cleanup's removeAllDeliveredNotifications() carries it inside an API name, and
# an identifier is not a sentence any person reads.
for side, src in (("Android", KT_NOTIF), ("iOS", SW_INT)):
    pin(f"{side} announces arrivals as a comparison, never as delivery",
        "unread in the inbox" in code_only(src)
        and "new message" in code_only(src)
        and "delivered" not in strings_of(src).lower())

# The permission is the person's, asked for on a tap in Settings and checked
# again before posting. An app that posts into a denied channel has a feature
# that exists only in its source file.
# The runtime leg's evidence frames. A surface reported as observed but never
# photographed is a claim without a picture, and a renamed frame would silently
# stop being collected while every summary line still said "observed". The pin
# names the frames the walk shoots, so a rename and a deletion fail alike.
pin("the runtime leg photographs the surfaces it observes",
    all(re.search(rf"shot {re.escape(f)}(?:\s|$)", KT_WALK) for f in (
        "120-platform-notify-ask",
        "121-platform-notify-granted",
        "122-platform-compose-shortcut",
        "123-platform-offline",
        "124-platform-notify-shade",
        "125-platform-send-receipt",
    )))

pin("the notification permission is asked, checked, and never assumed",
    "requestAuthorization" in code_only(SW_SEC)
    and "UNAuthorizationStatus" in code_only(SW_SEC)
    and "RequestPermission()" in code_only(KT_SEC_UI)
    and "POST_NOTIFICATIONS" in KT_MANIFEST
    and "if (!allowed(context)) return false" in code_only(KT_NOTIF)
    and "notificationSettings()" in code_only(SW_INT))

# ── offline ─────────────────────────────────────────────────────────────────
pin("both devices watch the network rather than infer it from a failure",
    "registerDefaultNetworkCallback" in code_only(KT_VM)
    and "NWPathMonitor" in code_only(SW_INT)
    and "state.online == false" in code_only(KT_BOX_UI)
    and "network.online == false" in code_only(SW_BOX))

# `null` is not `false`. Before the first reading lands there is no answer to
# give, and a banner that says "offline" about a network nobody checked is the
# same class of claim as a badge that reads 0 because the server was silent.
pin("no offline claim is made before the device has answered",
    "val online: Boolean? = null" in code_only(KT_VM)
    and "@Published private(set) var online: Bool?" in code_only(SW_INT)
    and "online: Bool? = nil" not in code_only(SW_INT))

pin("the connectivity callback does not outlive the screen that registered it",
    "unregisterNetworkCallback" in code_only(KT_VM)
    and "monitor.cancel()" in code_only(SW_INT))

# ── the home-screen shortcut ────────────────────────────────────────────────
pin("Compose mail is reachable from the home screen, both platforms",
    "UIApplicationShortcutItems" in YML
    and "com.grapsee.grapmail.compose" in YML
    and "performActionFor shortcutItem" in code_only(SW_INT)
    and "ShortcutRouter" in code_only(SW_ROOT)
    and (ROOT / "android/app/src/main/res/xml/shortcuts.xml").exists()
    and 'android:name="android.app.shortcuts"' in KT_MANIFEST
    and "COMPOSE_ACTION" in code_only(KT_MAIN))

# A shortcut pressed while signed out goes to sign-in on both platforms. Landing
# on a composer that cannot send is a screen that pretends to work.
#
# Scoped to the shortcut's own effect, not counted across the file: MainActivity
# navigates to sign-in from half a dozen places, so a global "contains SIGN_IN"
# check stayed green when the mutation replaced exactly the one branch that
# decides what a shortcut means.
kt_shortcut_block = re.search(
    r"LaunchedEffect\(composeRequest, dest\) \{(.*?)\n    \}", code_only(KT_MAIN), re.S)
pin("the shortcut never lands on a composer that cannot send",
    kt_shortcut_block is not None
    and "Routes.SIGN_IN" in kt_shortcut_block.group(1)
    and "SessionStore.snapshot(nav.context.applicationContext)?.unexpired == true" in kt_shortcut_block.group(1)
    and "guard SessionStore.hasValidSession else {" in code_only(SW_ROOT)
    and ".signIn]" in code_only(SW_ROOT))

# Consumed, not remembered. A pending request that survives its own navigation
# replays on the next recomposition or launch and opens a composer nobody asked
# for -- the same class of bug as a replayed session-expiry signal.
pin("a shortcut request is answered exactly once",
    "onComposeRequestConsumed()" in (kt_shortcut_block.group(1) if kt_shortcut_block else "")
    and "shortcuts.consume()" in code_only(SW_ROOT)
    and "func consume()" in code_only(SW_INT))

# ── predictive back ─────────────────────────────────────────────────────────
pin("Android is opted into predictive back",
    'android:enableOnBackInvokedCallback="true"' in KT_MANIFEST)

# ── what is NOT shipped, pinned so it cannot sneak in half-done ─────────────
# `https://grapsee.com/.well-known/assetlinks.json` and
# `apple-app-site-association` both answer 404 today. An App Link or a Universal
# Link whose verification file is missing does not degrade — it silently stops
# opening the app from web links, and the app looks broken in the one place a
# person cannot debug it. The rule is endpoint-or-it-doesn't-exist, applied to
# the platform's own verification: no autoVerify filter, no associated-domains
# entitlement, until those two URLs serve real files.
pin("no half-verified deep-link surface is shipped while the backend 404s",
    "autoVerify" not in KT_MANIFEST
    and "associated-domains" not in YML
    and "applinks:" not in YML)

# ── push: the contract both phones are now wired to ─────────────────────────
# The backend registers device tokens and wakes the apps, so the old absence
# pin is gone. What replaces it pins the SHAPE of the thing, so the same
# half-verified surface cannot return from the other direction: the wake mode
# is declared, each phone registers through its own native path (no FCM on
# iOS, no cross-platform push SDK), both write to the app's own
# /grapsee/grapmail/push/register, and BOTH wakes land in the same
# authenticated read — a notification is still a report of a read the device
# made, never a claim of delivery.
KT_PUSH = android("logic/PushService.kt")
pin("push is the backend's own contract, and the wake still reads the stats itself",
    "remote-notification" in YML
    and "BGTaskSchedulerPermittedIdentifiers" in YML
    and "firebase" not in YML.lower()
    and "FCM" not in code_only(SW_INT) and "APNs" not in code_only(SW_INT)
    and "/grapsee/grapmail/push/register" in code_only(KT_LAUNCH)
    and "/grapsee/grapmail/push/register" in code_only(SW_APIC)
    and "didReceiveRemoteNotification" in code_only(SW_INT)
    and "MailPoll.runOnce()" in code_only(SW_INT)
    and "onMessageReceived" in code_only(KT_PUSH)
    and "OneTimeWorkRequestBuilder<MailPollWorker>()" in code_only(KT_PUSH))

# ── 40. settings the server cannot answer are not built (§40) ───────────────
# W6 asked for a compose "From" picker wired to `GET /grapmail/aliases`. The
# read does exist; the SEND does not accept a from-address at all — `/send` and
# `/draft` both build `fromAddress` from the mailbox's own handle, and
# `mail_aliases` has no send-side column of any kind (aliases are addresses that
# DELIVER here). A picker would therefore choose an address, show it in the
# composer, and send from another one: the recipient sees the address the person
# just declined to use, and only they can discover the disagreement.
#
# So the excluded list is documented instead (`FINDINGS.md`), with the exact
# endpoint change each item needs, and the absences below are pinned so the
# half-built version cannot be added later because the code looked nearby.
KT_SEND_DTO = android("data/Api.kt")
SW_SEND_DTO = ios("MailAPI.swift")
KT_COMPOSE = android("ui/screens/mail/ComposeScreen.kt")
SW_COMPOSE = ios("MailReaderViews.swift")
FINDINGS = (ROOT / "FINDINGS.md")


def declared_fields(src: str, marker: str, limit: int = 900) -> str:
    """The text of one type's declaration body, or "" when the marker is gone.

    Returning "" rather than indexing a missing split is the point: a pin whose
    marker was renamed should FAIL with its own reason, not raise and take the
    whole gate's output with it -- the failure mode §39's splash helper had to
    be written around for the same reason.
    """
    at = src.find(marker)
    return "" if at == -1 else src[at:at + limit]


# The wire format is the strongest form of the rule: a client that invented a
# `from` field would be sending a parameter the route ignores, which is a bug
# that looks like a feature in every screenshot.
kt_send_body = declared_fields(code_only(KT_SEND_DTO), "data class MailSendRequest")
kt_draft_body = declared_fields(code_only(KT_SEND_DTO), "data class MailDraftRequest")
sw_send_body = declared_fields(code_only(SW_SEND_DTO), "struct MailSendRequest")
sw_draft_body = declared_fields(code_only(SW_SEND_DTO), "struct MailDraftRequest")
pin("neither send request carries a from-address the server would ignore",
    all(b for b in (kt_send_body, kt_draft_body, sw_send_body, sw_draft_body))
    and not re.search(r"^\s*(?:val|var)\s+from(?:Address)?\s*[:=]",
                       "\n".join((kt_send_body, kt_draft_body)), re.M)
    and not re.search(r"^\s*var\s+from(?:Address)?\s*[:=]",
                      "\n".join((sw_send_body, sw_draft_body)), re.M))

pin("the composer offers no sender it cannot send from",
    "From:" not in code_only(KT_COMPOSE)
    and "pickAlias" not in code_only(KT_COMPOSE)
    and "onPickFrom" not in code_only(SW_COMPOSE)
    and "aliasesState" not in code_only(KT_COMPOSE)
    and "aliasesState" not in code_only(SW_COMPOSE))

pin("the excluded settings are documented with the endpoint each needs",
    FINDINGS.exists()
    and all(name in FINDINGS.read_text(encoding="utf-8") for name in (
        "Undo timeout", "Swipe-action", "Default reply mode",
        "Remote-image policy", "Link previews",
        "mail_aliases", "fromAddress")))

# Aliases ARE shipped where the endpoint is real: minting, removing, and the
# receive-side meaning. The read state is kept distinct from an empty answer, so
# a refused list never reads as "this mailbox has no aliases".
pin("aliases ship for what the server actually does with them",
    "mailCreateAlias" in code_only(android("data/Api.kt"))
    and "mailDeleteAlias" in code_only(android("data/Api.kt"))
    and "mailCreateAlias" in code_only(SW_SEND_DTO)
    and "mailDeleteAlias" in code_only(SW_SEND_DTO)
    and "read = .reading" in code_only(SW_MODEL)
    and "AliasesState" in code_only(KT_VM))

print()
if failures:
    print(f"mail-honesty: {len(failures)} pin(s) broken")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"mail-honesty: {checks} pins hold")
