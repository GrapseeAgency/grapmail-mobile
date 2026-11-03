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

# ── 3. nothing destroys mail without the confirmation sheet ──────────────────
KT_BOX = android("ui/screens/mail/MailboxScreen.kt")
SW_BOX = ios("MailboxView.swift")

pin("Android's toolbar Delete opens the confirmation sheet",
    re.search(r"onDelete\s*=\s*\{\s*showPermanentConfirm\s*=\s*true\s*\}", KT_BOX) is not None)
pin("iOS's toolbar Delete opens the confirmation sheet",
    re.search(r"onDelete:\s*\{\s*sheet\s*=\s*\.permanent\s*\}", SW_BOX) is not None)

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

# ── 4. no remote HTML gets a renderer ───────────────────────────────────────
MAIL_SOURCES = ([ANDROID / "ui/screens/mail" / p for p in
                 sorted((ANDROID / "ui/screens/mail").iterdir())]
                + [ANDROID / "ui/components/Mail.kt"]
                + [IOS / p for p in sorted(IOS.iterdir()) if p.name.startswith("Mail")])

for f in MAIL_SOURCES:
    src = f.read_text(encoding="utf-8", errors="ignore")
    lines = [ln for ln in src.splitlines()
             if not ln.lstrip().startswith(("//", "*", "///", "// "))]
    code = "\n".join(lines)
    pin(f"{f.name}: no WebView renders a message body",
        "WebView" not in code and "loadHTMLString" not in code)

# The thread view shows stripped text and says so, rather than silently
# presenting a downgrade as the original.
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

# A decision is a rule for the NEXT delivery, not an action on the message in
# hand, so the queue must be re-read rather than edited locally.
KT_VM = android("logic/MailboxViewModel.kt")
pin("Android re-reads the queue after a decision instead of splicing it",
    "MailStore.invalidate(MailBox.Screener)" in KT_VM)
pin("iOS re-reads the queue after a decision instead of splicing it",
    "invalidate(.screener)" in ios("MailboxModel.swift"))

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

# The other half of the same mistake, on the other side: iOS's attachment byte
# and upload routes ended the ACCOUNT session on a mail 401, while its JSON path
# and Android's interceptor both deliberately carve mail out — the mail service is
# a different backend that refuses a bearer the account endpoints still accept.
# A download was therefore the one action that could sign a person out of a
# working sign-in, with the thread they were reading behind it.
pin("iOS's mail routes end no account session on a 401",
    "signalUnauthorized" not in code_only(SW_API))
pin("iOS's JSON carve-out is still scoped to non-mail paths",
    re.search(r"if http\.statusCode == 401, bearer != nil, !path\.hasPrefix\(mailBase\)",
              code_only(ios("APIClient.swift"))) is not None)
pin("Android's interceptor still carves mail out and keeps the rule for accounts",
    "!isMailCall" in code_only(KT_API) and "SessionEvents.signalExpired()" in code_only(KT_API))

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
# `loadAndSettle` spins on `settleGeneration` because the no-blank rule forbids a
# refresh from raising `loading` over a populated list. That makes every early
# return which does not bump the generation a main-actor spin for the life of the
# process, and `try?` on the sleep swallows the cancellation SwiftUI raises when
# the gesture dies. Both halves are pinned: the exits bump, and the loop is
# bounded and cancellation-aware.
SW_LOAD = body_of(SW_MODEL, "func load(replace")
KT_LOAD = body_of(KT_VM, "fun load(replace")
pin("iOS's `load` slice was located", bool(SW_LOAD.strip()))
pin("Android's `load` slice was located", bool(KT_LOAD.strip()))
pin("iOS bumps the settle signal on every exit from a load",
    SW_LOAD.count("settleGeneration += 1") >= 4)
pin("Android bumps the settle signal on every exit from a load",
    KT_LOAD.count("settleGeneration + 1") >= 3)
pin("iOS's refresh wait cannot spin forever",
    re.search(r"while state\.settleGeneration == before, ticks < \d+", code_only(SW_MODEL)) is not None
    and "if Task.isCancelled { return }" in code_only(SW_MODEL))
# A cancelled read is not a failed read: `load()` cancels the previous call on
# every entry, so the superseded one reaching the failure arm prints "couldn't
# reach the server" over the list the newer read has already filled.
pin("Android rethrows a cancelled read instead of reporting it",
    KT_LOAD.count("catch (e: kotlinx.coroutines.CancellationException)") >= 2
    and "runCatching { Api.mailInbox" not in KT_LOAD)
# Two, not one: the slice holds two rethrows — the message read and the stats
# side read — and the first version of this pin matched the string alone, so
# deleting the OUTER one (the one that matters) left it green on the inner.
# Found by mutating exactly that line and watching the gate not move.
pin("iOS ignores a cancelled read instead of reporting it",
    "if error is CancellationError || Task.isCancelled { return }" in SW_LOAD)

# A flag raised before an `await` must be lowered on the abandoned path too.
pin("iOS's abandoned assignment read resolves its own flag",
    re.search(r"guard sessionIsLive else \{\s*\n?[ \t]*(?://[^\n]*\n)*[ \t]*let stillThere",
              code_only(SW_MODEL)) is not None
    or "assignmentsRead = stillThere" in code_only(SW_MODEL))
pin("iOS's abandoned label-list read clears its spinner",
    re.search(r"guard sessionIsLive else \{[\s\S]{0,400}?labelState.loading = false",
              code_only(SW_MODEL)) is not None)
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
# These are the reference's resolved values, spelled out here rather than read
# from the outer repo, which this repo's CI cannot check out.
AVATAR_LIGHT = ["1A73E8", "0B8043", "A142F4", "C5221F", "E37400", "00796B", "8E24AA", "3949AB"]
AVATAR_DARK = ["8AB4F8", "0B8043", "A142F4", "F2B8B5", "FBBC04", "00796B", "8E24AA", "3949AB"]
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


pin("Android's light avatar ramp is the reference's eight",
    hex_run(KT_COLORS, "val GrapMailAvatarRampLight") == AVATAR_LIGHT)
pin("Android's dark avatar ramp is the reference's eight lifted",
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
pin("iOS's label strip is given the two fields it mirrors",
    re.search(r'LabelStrip\(labels:[\s\S]{0,160}?query: vm\.state\.query, filter: vm\.state\.filter',
              code_only(SW_BOX)) is not None)
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

# -- 22. the sources at least parse as sources ----------------------------------
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
pin("Android's nav holds exactly one ProfileViewModel",
    "ProfileViewModel = viewModel()" in KT_MAIN)
pin("iOS's nav holds exactly one ProfileModel",
    "@StateObject private var account = ProfileModel()" in SW_ROOT)
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
# the signature the compose appends, the density the list draws, the locale
# every timestamp renders in, and the two sender lists the screener reads.
pin("the prefs screen's writes name exactly the five enforced keys",
    {m.group(1) for m in re.finditer(r"ProfileSettingsPatch\(\s*([A-Za-z]+) =",
                                      code_only(KT_ACCT))} ==
    {"signature", "density", "locale", "safeSenders", "blockedSenders", "notes", "tasks"}
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

# DELETE-ACCOUNT: no endpoint exists on either store, so the page must say the
# truth in the web's own words and route to a person -- and no client may call
# a delete-account route that does not exist.
pin("both privacy pages state that self-serve deletion is not built",
    "that path is not built" in KT_ACCT and "that path is not built" in SW_ACCT)
pin("no client calls a delete-account route that does not exist",
    not re.search(r"account/delete|deleteAccount", KT_API + SW_APIC))

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
    "The directory did not answer, so nothing was encrypted" in code_only(KT_COMPOSE)
    and "The directory did not answer, so nothing was encrypted" in code_only(SW_RDR)
    and "cannot be encrypted. Turn encryption off" in code_only(KT_COMPOSE)
    and "cannot be encrypted. Turn encryption off" in code_only(SW_RDR))
pin("a sealed send carries the envelope and an empty bodyText, both platforms",
    'bodyText = if (e2eEnvelope != null) "" else s.body' in code_only(KT_VM)
    and 'bodyText: e2eEnvelope == nil ? compose.body : ""' in code_only(SW_VM))
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
    and "https://" in KT_MAIL[KT_MAIL.find("fun unsubscribeTarget"):][:400]
    and "https://" in SW_COMPS[SW_COMPS.find("func unsubscribeTarget"):][:400])
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
    'in" || m.direction' in code_only(KT_SUBS).replace("in\" || it.direction", "")
    or ('direction' in code_only(KT_SUBS) and 'inbound' in code_only(KT_SUBS)
        and 'direction' in code_only(SW_SUBS) and 'inbound' in code_only(SW_SUBS)))

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
pin("both walks photograph both subscriptions frames with the same waits",
    all(f'settled="{m}"' in KT_WALK for m in ("The Weekly Draft",
                                              "No subscriptions detected."))
    and all(f'"{m}",]' in SW_WALK or f': "{m}"' in SW_WALK for m in
            ("The Weekly Draft", "No subscriptions detected.")))

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
    all(g in DFLOW for g in ("/auth/account/create", "/auth/account/signin",
                             "/auth/account/profile", "e2e/keys", "e2e/plan",
                             "/screener/decision", "/send", "/draft")))
pin("the data-flow doc states the deletion gap in the screens' own words",
    "that path is not built" in DFLOW and "no account-delete endpoint" in DFLOW.lower())
pin("the data-flow doc names every on-device store the code writes",
    all(s in DFLOW for s in ("Keychain", "UserDefaults", "Keystore",
                             "grapmail-e2e-protected-key/1", "MailStore")))
pin("the release job builds and proves the AAB beside the APK",
    "./gradlew assembleRelease bundleRelease" in WF_TEXT and "app-release.aab" in WF_TEXT)

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

print()
if failures:
    print(f"mail-honesty: {len(failures)} pin(s) broken")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"mail-honesty: {checks} pins hold")
