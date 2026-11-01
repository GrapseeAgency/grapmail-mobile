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
