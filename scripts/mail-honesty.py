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


# ── 1. the send receipt prints the server's own three words ──────────────────
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
    re.search(r'else\s*->\s*"Server reported', KT_COMPOSE) is not None)
pin("iOS prints an unrecognised status verbatim",
    re.search(r"(default|otherwise).*Server reported", SW_READER, re.S) is not None)

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
    """One function's text: from its signature to the next sibling declaration.

    Crude on purpose. These files put every member at one indent level, so the
    next `\n    fun `/`\n    func ` is the end of this one — and a scope check
    that reads a whole file would trivially find the call it was told to look
    for in some other function.
    """
    tail = src.split(header, 1)
    if len(tail) < 2:
        return ""
    rest = tail[1]
    for stop in ("\n    fun ", "\n    func ", "\n    private func "):
        cut = rest.find(stop)
        if cut != -1:
            rest = rest[:cut]
    return rest


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

# ── 5. the drawer offers only boxes the server can read back ─────────────────
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

def code_only(src):
    """The file with its comments removed.

    Two pins in this section forbid a WORD or a PATTERN, and the comments explaining
    each rule necessarily quote the thing they forbid -- `"Unsubscribed"` appears in a
    doc comment precisely to say it must never be displayed, and a hex literal appears
    in a token's comment to record what used to be hardcoded. Testing raw text made
    those pins fail on their own documentation, which is how a gate gets disabled.
    """
    out, keep = [], True
    for line in src.split("\n"):
        s = line.strip()
        if s.startswith("/*"):
            keep = "*/" not in s
            continue
        if not keep:
            if s.endswith("*/"):
                keep = True
            continue
        if s.startswith("//") or s.startswith("*") or s.startswith("///"):
            continue
        out.append(line)
    return "\n".join(out)


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

print()
if failures:
    print(f"mail-honesty: {len(failures)} pin(s) broken")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"mail-honesty: {checks} pins hold")
