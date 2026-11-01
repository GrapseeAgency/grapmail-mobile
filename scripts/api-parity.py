#!/usr/bin/env python3
"""Name-diff the two mailbox API surfaces: Android's `object Api` and iOS's
`ApiClient` extension, plus the DTO fields each side decodes.

    python3 scripts/api-parity.py            # report + exit 1 on drift
    python3 scripts/api-parity.py --quiet    # only the drift lines

WHY THIS EXISTS
---------------
The sector was built one platform first and translated to the other. Every
mismatch found that way so far was found by a human reading both files, and
three of them were real: iOS had `subjectOrNone` where Kotlin had none, Kotlin
had `tabOrder` where Swift had none, and the model called two attachment
functions that only existed on one side. A reviewer cannot re-derive that from
one file, and "the same API on both platforms" is not something a build can
check on its own.

WHAT IT COMPARES
----------------
  * every `mail*` function name (Android's suspend fns, iOS's static funcs),
  * every field of every DTO whose name exists on both sides, after normalising
    Kotlin's `...Dto` suffix.

WHAT IT DELIBERATELY DOES NOT COMPARE
-------------------------------------
Request bodies and helper types that exist on one platform because its HTTP
layer needs them (iOS's `MailAttachmentResponseJson` twin, Kotlin's internal
serializer wrappers). Those are reported as unshared names, not as failures —
the function surface is the contract; the plumbing is the platform's.
"""
import re
import sys
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID = os.path.join(ROOT, "android/app/src/main/java/com/grapsee/grapmail/data/Api.kt")
ANDROID_DTO = os.path.join(ROOT, "android/app/src/main/java/com/grapsee/grapmail/data/MailDto.kt")
IOS_API = os.path.join(ROOT, "ios/GrapMail/MailAPI.swift")
QUIET = "--quiet" in sys.argv


def say(msg):
    if not QUIET:
        print(msg)


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


# ── functions ─────────────────────────────────────────────────────────────────

def android_functions(src):
    """`suspend fun mailInbox(` — every mail call the Android client exposes."""
    return set(re.findall(r"suspend fun\s+(mail[A-Za-z0-9_]+)\s*[(<]", src))


def ios_functions(src):
    """`static func mailInbox(` — the iOS equivalent.

    `private static func` names are skipped: they are this file's transport
    helpers (`mailGet`, `mailBinary`, `mailFailure`), the platform's own plumbing,
    and comparing them would report the HTTP layer as a contract difference.
    """
    public = re.findall(r"^\s{4}static func\s+(mail[A-Za-z0-9_]+)\s*[(<]", src, re.M)
    private = set(re.findall(r"private static func\s+(mail[A-Za-z0-9_]+)\s*[(<]", src))
    return {n for n in public if n not in private}


# ── DTO fields ────────────────────────────────────────────────────────────────

# Every `data class Mail…` on the Android side — NOT only the ones carrying the
# `Dto` suffix. Written as `Mail…Dto` it silently matched 8 of the 26 wire types:
# every response envelope is named `MailLabelsResponse`, `MailInboxResponse` and so
# on, without the suffix, so all of them fell into the “one side only (plumbing, not
# compared)” list and their fields were never diffed. A gate that prints a checked
# number while quietly skipping half the surface is worse than no gate, because the
# green run reads as evidence — and this is the same blind spot that let a call
# missing from BOTH platforms pass. `normalise()` still strips the suffix for matching.
#
# Both sides are read by bracket balance rather than by “run to the next `)` at the
# start of a line”. The first version of that ran straight through single-line
# declarations — `struct MailSnoozeRequest: Encodable { var until: String }`,
# `data class MailSnoozeRequest(val until: String)` — and collected the FOLLOWING
# type's fields as its own, reporting invented drift against correct code. A gate
# that cries wolf teaches the reader to distrust it, which is how a real omission
# gets waved through next time.
ANDROID_DECL = re.compile(r"^\s*data class\s+(Mail[A-Za-z0-9_]+)")
SWIFT_DECL = re.compile(r"^\s*(?:public |private )?struct\s+(Mail[A-Za-z0-9_]+)")
ANDROID_FIELD = re.compile(r"^\s*(?:@SerialName\([^)]*\)\s*)?val\s+([a-zA-Z0-9_]+)\s*:", re.M)
# A stored Swift property is `var name: T` or `var name = ...`; a computed one
# carries a `{`, a getter, or a `get`, and those are behaviour, not wire shape.
SWIFT_FIELD = re.compile(r"^[ \t]{4,}var\s+([a-zA-Z0-9_]+)\s*[:=]", re.M)
SWIFT_COMPUTED = re.compile(r"^[ \t]{4,}var\s+([a-zA-Z0-9_]+)[^{\n]*\{", re.M)


def _scrub(line):
    """Blank out a line's string literals and trailing comment.

    Without this, `"\\u{2026}"` and `"(no subject)"` contribute brackets and the
    balance goes wrong. Only the brackets are trusted, so the text itself may be
    destroyed here.
    """
    if "//" in line:
        line = line[:line.index("//")]
    out, i, n = [], 0, len(line)
    while i < n:
        ch = line[i]
        if ch == '"':
            i += 1
            while i < n:
                if line[i] == "\\":
                    i += 2
                    continue
                if line[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def block_body(lines, start, opener, closer):
    """The text of a declaration, from its header line to its matching closer."""
    depth, collected = 0, []
    for idx in range(start, len(lines)):
        s = _scrub(lines[idx])
        for ch in s:
            if ch == opener:
                depth += 1
            elif ch == closer:
                depth -= 1
        collected.append(lines[idx])
        if depth <= 0:
            return "\n".join(collected)
    return "\n".join(collected)


def _types(src, decl, opener, closer, field_of):
    lines = src.split("\n")
    out = {}
    for i, line in enumerate(lines):
        m = decl.match(line)
        if not m:
            continue
        name = normalise(m.group(1))
        if name not in out:                      # first declaration wins
            out[name] = field_of(block_body(lines, i, opener, closer))
    return out


def swift_fields(body):
    """Stored properties of a decoded struct, minus the computed ones."""
    computed = {m.group(1) for m in SWIFT_COMPUTED.finditer(body)}
    cut = body.find("private enum CodingKeys")
    declared = body if cut < 0 else body[:cut]
    names = {m.group(1) for m in SWIFT_FIELD.finditer(declared)}
    return {n for n in names if n not in computed}


def android_fields(body):
    return {m.group(1) for m in ANDROID_FIELD.finditer(body)}


def normalise(name):
    """`MailMessageDto` and `MailMessage` are the same row on the wire."""
    return re.sub(r"Dto$", "", name)


def code_only(src):
    """The file with its comments removed, by the same line rules
    `scripts/mail-honesty.py` applies to it (and the same fix: a one-line
    `/** ... */` opens nothing).

    This exists because the route check below was satisfied by a doc comment, and
    then by the tail of a real line of code. Pointing Android's assignment call at
    `/labels/assignmentz` left the gate green twice over: the function's KDoc cites
    `GET /labels/assignments`, and `request(...)  // PUT /screener/decision` keeps
    its prose with its statement when the stripper walks line by line. A contract
    check has to read the text that can only be executed.

    Same scanner as `code_only` in `scripts/mail-honesty.py`, deliberately
    duplicated rather than imported: these two scripts run in CI steps that must
    each work if the other is broken, and a shared helper file would make one
    import failure silence both gates.
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


# ── the spec's own checklist, checked against BOTH clients ───────────────────
#
# T1.2 names the calls the mailbox client owes the server. `labelAssignments` sat
# on that list unwired on BOTH platforms for a whole review cycle, and this file
# could not see it: two sides agreeing to omit something produces a clean diff.
# That is the blind spot item 1 of the mailbox audit was made of, so the spec's
# list is checked here as its own contract, not as a name comparison.
#
# Each entry is the name the plan used, and the implementation name(s) that
# satisfy it. An alias has to be written down: a plan reader hunting for
# `mailFiling` finds nothing in either client, and there are three such names
# (`mailFiling`, `attachmentDownload`, `screenerSetEnabled`) plus the bare
# `labels`/`labelAssignments` forms. Recording them as renames is what keeps
# "renamed" from quietly meaning "missing" the next time somebody checks.
#
#   mailFiling(id, action, until)  -- there is no `/filing` route on the server;
#       filing is the same `PATCH /message/:id/:action` with a body carrying the
#       snooze instant (`grapmail-archive.ts:171`), and `mailAction`'s third
#       parameter IS that call. Checked further: the `until` argument must exist.
#   attachmentDownload             -- `mailAttachmentBytes`, because the point is
#       that the bytes are not decoded (`GET /attachments/:id`).
#   screenerSetEnabled             -- `mailScreenerSet` (`PUT /screener/enabled`).
SPEC_CALLS = {
    "mailMe": ["mailMe"],
    "mailStats": ["mailStats"],
    "mailInbox": ["mailInbox"],
    "mailMessage": ["mailMessage"],
    "mailThread": ["mailThread"],
    "mailSend": ["mailSend"],
    "mailDraft": ["mailDraft"],
    "mailAction": ["mailAction"],
    "mailFiling": ["mailAction"],
    "mailDeletePermanent": ["mailDeletePermanent"],
    "labels": ["mailLabels"],
    "labelAssignments": ["mailLabelAssignments"],
    "labelCreate": ["mailCreateLabel"],
    "labelRename": ["mailRenameLabel"],
    "labelDelete": ["mailDeleteLabel"],
    "labelAttach": ["mailLabelAssign"],
    "labelDetach": ["mailLabelDetach"],
    "aliases": ["mailAliases"],
    "attachmentUpload": ["mailAttachmentUpload"],
    "attachmentDownload": ["mailAttachmentBytes"],
    "screener": ["mailScreener"],
    "screenerSetEnabled": ["mailScreenerSet"],
    "screenerDecision": ["mailScreenerDecision"],
}

# The routes those calls must actually reach. Names can be argued about; a path
# in the source of both clients is the wire contract, and a call renamed on one
# side but pointed somewhere else is exactly what the name diff would pass.
SPEC_ROUTES = [
    "/me", "/stats", "/inbox", "/thread", "/send", "/draft",
    "/labels", "/labels/assignments", "/labels/message/",
    "/aliases", "/attachments", "/screener", "/screener/enabled",
    "/screener/decision",
]


def spec_check(af, _if, android_src, ios_src, problems):
    """Every capability the plan listed must be callable on BOTH sides."""
    a_code, i_code = code_only(android_src), code_only(ios_src)
    for spec, impls in sorted(SPEC_CALLS.items()):
        on_android = [n for n in impls if n in af]
        on_ios = [n for n in impls if n in _if]
        if not on_android:
            problems.append(f"spec T1.2 `{spec}` has no Android call "
                            f"(accepted: {', '.join(impls)})")
        if not on_ios:
            problems.append(f"spec T1.2 `{spec}` has no iOS call "
                            f"(accepted: {', '.join(impls)})")
    # The `until` half of `mailFiling`: a signature that cannot carry a snooze
    # instant would make the alias above a lie.
    if "mailFiling" in SPEC_CALLS:
        for side, src, pat in (("Android", a_code, r"fun mailAction\([^)]*until"),
                               ("iOS", i_code, r"func mailAction\([^)]*until")):
            if not re.search(pat, src, re.S):
                problems.append(f"{side}'s mailAction takes no `until`, so "
                                f"`mailFiling(id, action, until)` is not satisfied by it")
    # Routes are looked for in code only. Stripping the comments is the whole
    # fix: a path that survives it is a path inside a string the client builds a
    # request from, and both platforms spell theirs the same way once the prose
    # is gone -- Kotlin `"$MAIL/labels/assignments"`, iOS `mailGet("/labels/...")`
    # or `"\(mailBase)/screener/enabled"`, whose char before the slash is `)`, not
    # a quote. Requiring a prefix there would have failed on correct code, so the
    # check is the route itself, in text that can only be executed.
    for route in SPEC_ROUTES:
        for side, code in (("Android", a_code), ("iOS", i_code)):
            if route not in code:
                problems.append(f"{side} never calls {route} in code "
                                f"(a T1.2 route this surface owes the server)")
    say(f"spec T1.2 capabilities — {len(SPEC_CALLS)} named, "
        f"{len(SPEC_ROUTES)} routes, both platforms")


def main():
    missing = [p for p in (ANDROID, ANDROID_DTO, IOS_API) if not os.path.exists(p)]
    if missing:
        print("api-parity: source file(s) not found: " + ", ".join(missing))
        return 2

    android_src = read(ANDROID) + "\n" + read(ANDROID_DTO)
    ios_src = read(IOS_API)

    problems = []

    af, _if = android_functions(android_src), ios_functions(ios_src)
    for name in sorted(_if - af):
        problems.append(f"call only on iOS: {name}()")
    for name in sorted(af - _if):
        problems.append(f"call only on Android: {name}()")
    say(f"mail calls — android {len(af)}  ios {len(_if)}  "
        f"shared {len(af & _if)}")

    spec_check(af, _if, android_src, ios_src, problems)

    a_types = _types(android_src, ANDROID_DECL, "(", ")", android_fields)
    i_types = _types(ios_src, SWIFT_DECL, "{", "}", swift_fields)

    shared = sorted(set(a_types) & set(i_types))
    checked = 0
    for typeName in shared:
        a, i = a_types[typeName], i_types[typeName]
        for field in sorted(i - a):
            problems.append(f"{typeName}: field only on iOS: {field}")
        for field in sorted(a - i):
            problems.append(f"{typeName}: field only on Android: {field}")
        checked += len(a & i)
    say(f"DTOs compared — {len(shared)} types, {checked} shared fields")

    only_android = sorted(set(a_types) - set(i_types))
    only_ios = sorted(set(i_types) - set(a_types))
    if only_android or only_ios:
        say("types on one side only (plumbing, not compared): "
            + ", ".join(only_android + only_ios))

    if problems:
        print(f"\napi-parity FAILED: {len(problems)} asymmetry(ies)")
        for p in problems:
            print("  - " + p)
        return 1
    say("api-parity: the two mail API surfaces name the same calls and fields")
    return 0


if __name__ == "__main__":
    sys.exit(main())
