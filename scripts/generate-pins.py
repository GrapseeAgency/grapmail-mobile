#!/usr/bin/env python3
"""Generate ios/GrapMail/ApiPins.swift from security/api-pins.json.

    python3 scripts/generate-pins.py            # (re)write the Swift file
    python3 scripts/generate-pins.py --check    # exit 1 if the committed file is stale
    python3 scripts/generate-pins.py --stdout   # print instead of writing

The Swift file is committed (the iOS build has no step that could run this),
and the weekly pin workflow runs --check so a JSON edit without a regenerate
is caught. Placeholder pins are left out; a malformed pin set is refused.
Output is deterministic (no timestamps) so --check compares byte for byte.
"""
from __future__ import annotations

import argparse
import calendar
import datetime as dt
import importlib.util
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "ios" / "GrapMail" / "ApiPins.swift"

# Reuse the checker's loader and validator so the two can never disagree.
_spec = importlib.util.spec_from_file_location("check_api_pins", ROOT / "scripts" / "check-api-pins.py")
check = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(check)


def swift_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def render(pinset: dict) -> str:
    expires = pinset["expires"]
    d = dt.date.fromisoformat(expires)
    expires_at = calendar.timegm((d.year, d.month, d.day, 0, 0, 0))
    hosts = check.pinned_hosts(pinset)
    pin_lines = []
    for p in pinset["pins"]:
        if p.get("placeholder") is True or not check.is_valid_pin(p.get("sha256")):
            continue
        cert = p.get("cert") or {}
        comment = f"{p['role']}: {cert.get('subject', '?')}, notAfter {cert.get('notAfter', '?')}"
        pin_lines.append(f"        {swift_string(p['sha256'])}, // {comment}")
    placeholders = sum(1 for p in pinset["pins"] if p.get("placeholder") is True)

    return "\n".join([
        "// GENERATED FILE - DO NOT EDIT BY HAND.",
        "// Source: security/api-pins.json   Generator: scripts/generate-pins.py",
        "// Regenerate with `python3 scripts/generate-pins.py` and commit the result;",
        "// .github/workflows/pin-expiry.yml fails when this file is stale.",
        "//",
        "// Read by PinnedSessionDelegate.swift. Android reads the same JSON in",
        "// android/app/build.gradle.kts, so both apps pin the same keys.",
        "",
        "import Foundation",
        "",
        "enum ApiPins {",
        "    /// Hosts whose TLS chain must contain one of `sha256` (exact host match).",
        "    static let hosts: [String] = [",
        *[f"        {swift_string(h)}," for h in hosts],
        "    ]",
        "",
        "    /// `enforce` from the JSON. While false the apps do not pin at all.",
        f"    static let enforce: Bool = {'true' if pinset['enforce'] else 'false'}",
        "",
        "    /// Pinning switches itself off from this day (UTC) on: fail-open, platform",
        "    /// trust only, so installs that outlive their pin set are not locked out.",
        f"    static let expires: String = {swift_string(expires)}",
        "",
        f"    /// `expires` as seconds since 1970-01-01T00:00:00Z ({expires}T00:00:00Z).",
        f"    static let expiresAt: TimeInterval = {expires_at}",
        "",
        "    /// base64(SHA-256(DER SubjectPublicKeyInfo)): the OkHttp `sha256/` value",
        "    /// without the prefix. Placeholders are omitted"
        + (f" ({placeholders} in the JSON)." if placeholders else "."),
        "    static let sha256: [String] = [",
        *pin_lines,
        "    ]",
        "}",
        "",
    ])


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--json", type=pathlib.Path, default=check.DEFAULT_JSON)
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--stdout", action="store_true")
    args = ap.parse_args(argv)

    pinset = check.load_pinset(args.json)
    errors, _ = check.validate(pinset)
    if errors:
        for e in errors:
            print(f"error: {e}", file=sys.stderr)
        print("refusing to generate from an invalid pin set", file=sys.stderr)
        return 1
    text = render(pinset)

    if args.stdout:
        sys.stdout.write(text)
        return 0
    if args.check:
        current = args.out.read_text(encoding="utf-8") if args.out.is_file() else ""
        if current.replace("\r\n", "\n") != text:
            print(f"{args.out} is out of date: run python3 scripts/generate-pins.py and commit it", file=sys.stderr)
            return 1
        print(f"{args.out.name} is in sync with {args.json.name}")
        return 0
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
