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

ANDROID_CLASS = re.compile(r"data class\s+(Mail[A-Za-z0-9_]*?)Dto\s*\((.*?)\n\)", re.S)
SWIFT_STRUCT = re.compile(r"struct\s+(Mail[A-Za-z0-9_]*)[^{]*\{(.*?)\n\}", re.S)
ANDROID_FIELD = re.compile(r"^\s*val\s+([a-zA-Z0-9_]+)\s*:", re.M)
# A stored Swift property is `var name: T` or `var name = ...`; a computed one
# carries a `{`, a getter, or a `get`, and those are behaviour, not wire shape.
SWIFT_FIELD = re.compile(r"^\s{4}var\s+([a-zA-Z0-9_]+)\s*[:=]", re.M)
SWIFT_COMPUTED = re.compile(r"^\s{4}var\s+([a-zA-Z0-9_]+)[^{\n]*\{", re.M)


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

    a_types = {normalise(m.group(1)): android_fields(m.group(2))
               for m in ANDROID_CLASS.finditer(android_src)}
    i_types = {m.group(1): swift_fields(m.group(2))
               for m in SWIFT_STRUCT.finditer(ios_src)}

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
