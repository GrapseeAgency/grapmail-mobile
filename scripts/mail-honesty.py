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
       