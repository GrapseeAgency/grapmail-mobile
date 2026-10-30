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

print()
if failures:
    print(f"mail-honesty: {len(failures)} pin(s) broken")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"mail-honesty: {checks} pins hold")
