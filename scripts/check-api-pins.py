#!/usr/bin/env python3
"""Check security/api-pins.json: schema, the live TLS chains, and the expiry.

    python3 scripts/check-api-pins.py                 # full check (needs network)
    python3 scripts/check-api-pins.py --offline       # schema + expiry only
    python3 scripts/check-api-pins.py --report out.md # also write a Markdown report

Exit status 0 = healthy, 1 = something needs a human (the weekly workflow
.github/workflows/pin-expiry.yml opens or updates an issue with the report).

It FAILS when:
  * the JSON is malformed, a pin is not base64 of 32 bytes, a role is unknown,
    or there are fewer than two real (non-placeholder) pins;
  * `expires` is in the past or within --warn-days (default 30) days;
  * a pinned host's live chain matches none of the pins. That is the outage
    case: with enforcement on, every API call from the apps would fail.
It WARNS (exit 0) on: a placeholder backup pin that is still unfilled, enforce
being false, and a pinned CA certificate whose notAfter falls before `expires`.

Why expiry matters (OWASP MASTG-TEST-0022): both apps stop pinning after
`expires` so that installs that were never updated are not locked out. That
switch is silent on the phone, so this check is what makes it visible.

Standard library only. Reading the whole verified chain (including the root the
client trusts) needs Python 3.13+ (ssl.SSLSocket.get_verified_chain). On older
Pythons the certificates the server sends are read with `openssl s_client`;
roots the server does not send are then not seen, which is fine as long as one
pinned intermediate is in the served chain.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import pathlib
import re
import shutil
import socket
import ssl
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_JSON = ROOT / "security" / "api-pins.json"
ROLES = {"leaf", "intermediate", "backup"}
HOST_RE = re.compile(r"^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


# --------------------------------------------------------------------------
# Pin-set loading and validation (also used by scripts/generate-pins.py).
# --------------------------------------------------------------------------

def is_valid_pin(value: object) -> bool:
    """base64 that decodes to exactly 32 bytes, the SHA-256 of an SPKI."""
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9+/]{43}=", value):
        return False
    try:
        return len(base64.b64decode(value, validate=True)) == 32
    except Exception:
        return False


def load_pinset(path: pathlib.Path = DEFAULT_JSON) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def validate(pinset: dict) -> tuple[list[str], list[str]]:
    """Return (errors, warnings) for the pin-set document."""
    errors: list[str] = []
    warnings: list[str] = []

    host = pinset.get("host")
    if not isinstance(host, str) or not HOST_RE.match(host):
        errors.append(f"`host` must be a lowercase DNS name, got {host!r}")
    extra = pinset.get("additionalHosts", [])
    if not isinstance(extra, list) or not all(isinstance(h, str) and HOST_RE.match(h) for h in extra):
        errors.append("`additionalHosts` must be a list of lowercase DNS names")

    if not isinstance(pinset.get("enforce"), bool):
        errors.append("`enforce` must be true or false")
    elif not pinset["enforce"]:
        warnings.append("`enforce` is false: the apps compile the pins but do NOT pin. "
                        "Turn it on only after the edge certificate's CA/key is under your control.")

    expires = pinset.get("expires")
    if not isinstance(expires, str) or not DATE_RE.match(expires):
        errors.append(f"`expires` must be YYYY-MM-DD, got {expires!r}")
    else:
        try:
            dt.date.fromisoformat(expires)
        except ValueError:
            errors.append(f"`expires` is not a real date: {expires!r}")

    pins = pinset.get("pins")
    if not isinstance(pins, list) or not pins:
        errors.append("`pins` must be a non-empty list")
        return errors, warnings

    real = []
    for i, pin in enumerate(pins):
        label = f"pins[{i}]"
        if not isinstance(pin, dict):
            errors.append(f"{label} must be an object")
            continue
        if pin.get("role") not in ROLES:
            errors.append(f"{label}.role must be one of {sorted(ROLES)}, got {pin.get('role')!r}")
        if not isinstance(pin.get("note"), str) or not pin["note"].strip():
            errors.append(f"{label}.note must say which certificate this key belongs to")
        if pin.get("placeholder") is True:
            warnings.append(f"{label} is a PLACEHOLDER ({pin.get('role')}): "
                            "generate the founder's offline backup key and put its SPKI hash here.")
            continue
        if not is_valid_pin(pin.get("sha256")):
            errors.append(f"{label}.sha256 is not base64 of a 32-byte SHA-256: {pin.get('sha256')!r}")
            continue
        if pin.get("role") == "leaf":
            warnings.append(f"{label} pins a LEAF key: it breaks at the next certificate renewal "
                            "unless the same key is reused on purpose.")
        real.append(pin["sha256"])
        not_after = (pin.get("cert") or {}).get("notAfter")
        if isinstance(not_after, str) and DATE_RE.match(not_after) and isinstance(expires, str) \
                and DATE_RE.match(expires) and not_after < expires:
            warnings.append(f"{label} ({(pin.get('cert') or {}).get('subject', '?')}) has notAfter "
                            f"{not_after}, before the pin set's expires {expires}.")

    if len(set(real)) != len(real):
        errors.append("`pins` contains the same sha256 twice")
    if len(set(real)) < 2:
        errors.append(f"only {len(set(real))} real pin(s): at least two (current + backup) are required, "
                      "and both apps refuse to pin with fewer")
    return errors, warnings


def real_pins(pinset: dict) -> list[str]:
    return [p["sha256"] for p in pinset.get("pins", [])
            if isinstance(p, dict) and p.get("placeholder") is not True and is_valid_pin(p.get("sha256"))]


def pinned_hosts(pinset: dict) -> list[str]:
    hosts = [pinset.get("host")] + list(pinset.get("additionalHosts", []))
    out: list[str] = []
    for h in hosts:
        if isinstance(h, str) and h and h.lower() not in out:
            out.append(h.lower())
    return out


# --------------------------------------------------------------------------
# Minimal DER reading: enough to pull SPKI, subject CN and notAfter out of a
# certificate without third-party packages.
# --------------------------------------------------------------------------

def _tlv(buf: bytes, pos: int) -> tuple[int, int, int, int]:
    """Return (tag, header_start, content_start, end) for the element at pos."""
    tag = buf[pos]
    length = buf[pos + 1]
    content = pos + 2
    if length & 0x80:
        n = length & 0x7F
        if n == 0 or n > 4:
            raise ValueError("unsupported DER length")
        length = int.from_bytes(buf[content:content + n], "big")
        content += n
    end = content + length
    if end > len(buf):
        raise ValueError("truncated DER")
    return tag, pos, content, end


def _children(buf: bytes, start: int, end: int) -> list[tuple[int, int, int, int]]:
    out = []
    pos = start
    while pos < end:
        el = _tlv(buf, pos)
        out.append(el)
        pos = el[3]
    return out


def cert_info(der: bytes) -> dict:
    """SPKI hash, subject CN and notAfter of one DER certificate."""
    _, _, c, e = _tlv(der, 0)                    # Certificate
    tbs = _children(der, c, e)[0]                # tbsCertificate
    fields = _children(der, tbs[2], tbs[3])
    if fields and fields[0][0] == 0xA0:          # [0] version
        fields = fields[1:]
    # serial, signature, issuer, validity, subject, subjectPublicKeyInfo
    validity, subject, spki = fields[3], fields[4], fields[5]
    spki_der = der[spki[1]:spki[3]]
    not_after_el = _children(der, validity[2], validity[3])[1]
    raw = der[not_after_el[2]:not_after_el[3]].decode("ascii")
    if not_after_el[0] == 0x17:                  # UTCTime YYMMDDHHMMSSZ
        year = int(raw[:2])
        raw = ("19" if year >= 50 else "20") + raw
    not_after = f"{raw[0:4]}-{raw[4:6]}-{raw[6:8]}"
    cn = "?"
    subject_der = der[subject[2]:subject[3]]
    marker = bytes.fromhex("0603550403")         # OID 2.5.4.3 commonName
    i = subject_der.find(marker)
    if i >= 0:
        _, _, sc, se = _tlv(subject_der, i + len(marker))
        cn = subject_der[sc:se].decode("utf-8", "replace")
    return {
        "cn": cn,
        "notAfter": not_after,
        "sha256": base64.b64encode(hashlib.sha256(spki_der).digest()).decode(),
    }


# --------------------------------------------------------------------------
# Reading the live chain.
# --------------------------------------------------------------------------

def _to_der(cert: object) -> bytes:
    if isinstance(cert, (bytes, bytearray)):
        return bytes(cert)
    # Python 3.13 may hand back _ssl.Certificate objects at some layers.
    encoding = getattr(getattr(ssl, "_ssl", None), "ENCODING_DER", None)
    if encoding is not None and hasattr(cert, "public_bytes"):
        return cert.public_bytes(encoding)
    raise TypeError(f"cannot read certificate of type {type(cert)!r}")


def live_chain(host: str, timeout: float = 15.0) -> tuple[list[bytes], str]:
    """DER certificates for host:443 and a note on how they were read."""
    ctx = ssl.create_default_context()
    with socket.create_connection((host, 443), timeout=timeout) as sock:
        with ctx.wrap_socket(sock, server_hostname=host) as tls:
            if hasattr(tls, "get_verified_chain"):
                return [_to_der(c) for c in tls.get_verified_chain()], "verified chain (incl. trust anchor)"
    # Older Python: the handshake above still proved the chain validates; now
    # read the certificates the server sends.
    if not shutil.which("openssl"):
        raise RuntimeError("Python < 3.13 and no openssl: cannot read the chain")
    out = subprocess.run(
        ["openssl", "s_client", "-connect", f"{host}:443", "-servername", host, "-showcerts"],
        input=b"", capture_output=True, timeout=timeout + 10,
    ).stdout.decode("ascii", "replace")
    ders = [ssl.PEM_cert_to_DER_cert(m.group(0)) for m in
            re.finditer(r"-----BEGIN CERTIFICATE-----.+?-----END CERTIFICATE-----", out, re.S)]
    if not ders:
        raise RuntimeError("openssl returned no certificates")
    return ders, "served chain (openssl; trust anchor not included)"


# --------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--json", type=pathlib.Path, default=DEFAULT_JSON)
    ap.add_argument("--offline", action="store_true", help="skip the live chain check")
    ap.add_argument("--warn-days", type=int, default=30)
    ap.add_argument("--today", help="YYYY-MM-DD, for testing the expiry rule")
    ap.add_argument("--report", type=pathlib.Path, help="write a Markdown report here")
    args = ap.parse_args(argv)

    lines: list[str] = [f"# Certificate pin check: `{args.json.relative_to(ROOT) if args.json.is_relative_to(ROOT) else args.json}`", ""]
    failures: list[str] = []
    warnings: list[str] = []

    try:
        pinset = load_pinset(args.json)
    except Exception as exc:  # noqa: BLE001 - report any read/parse failure
        failures.append(f"cannot read the pin set: {exc}")
        pinset = None

    if pinset is not None:
        errs, warns = validate(pinset)
        failures += errs
        warnings += warns

        today = dt.date.fromisoformat(args.today) if args.today else dt.datetime.now(dt.timezone.utc).date()
        expires_raw = pinset.get("expires")
        try:
            expires = dt.date.fromisoformat(expires_raw)
            days = (expires - today).days
            lines.append(f"- expires: **{expires_raw}** ({days} days from {today.isoformat()})")
            if days <= 0:
                failures.append(f"the pin set EXPIRED on {expires_raw}: installs built from it no longer pin. "
                                "Refresh the pins, move `expires` forward, and ship both apps.")
            elif days <= args.warn_days:
                failures.append(f"the pin set expires in {days} days ({expires_raw}). Refresh it and ship both "
                                "apps before then, or every install silently stops pinning on that date.")
        except (TypeError, ValueError):
            pass  # already reported by validate()

        pins = set(real_pins(pinset))
        lines.append(f"- enforce: **{pinset.get('enforce')}**, real pins: **{len(pins)}**")
        lines.append("")

        if not args.offline:
            for host in pinned_hosts(pinset):
                lines.append(f"## {host}")
                try:
                    ders, how = live_chain(host)
                except Exception as exc:  # noqa: BLE001
                    failures.append(f"{host}: could not read the live chain: {exc}")
                    lines.append(f"- could not read the chain: `{exc}`")
                    lines.append("")
                    continue
                lines.append(f"- read: {how}")
                matched = False
                for n, der in enumerate(ders):
                    try:
                        info = cert_info(der)
                    except Exception as exc:  # noqa: BLE001
                        lines.append(f"  - [{n}] unparseable certificate: {exc}")
                        continue
                    hit = info["sha256"] in pins
                    matched |= hit
                    lines.append(f"  - [{n}] CN={info['cn']}, notAfter {info['notAfter']}, "
                                 f"`sha256/{info['sha256']}`{'  **<- pinned**' if hit else ''}")
                    if n == 0:
                        left = (dt.date.fromisoformat(info["notAfter"]) - today).days
                        if left < 14:
                            warnings.append(f"{host}: leaf certificate expires in {left} days ({info['notAfter']}).")
                if not matched:
                    failures.append(f"{host}: NO certificate in the live chain matches a pin. With enforce on, "
                                    "every API call to this host fails. Run scripts/print-api-pins.sh "
                                    f"{host} and update security/api-pins.json.")
                lines.append("")

    lines.append("## Result")
    lines += [f"- FAIL: {f}" for f in failures] or ["- OK"]
    lines += [f"- warning: {w}" for w in warnings]
    lines.append("")
    lines.append("How to fix: SECURITY-TESTING.md, section 'Certificate pinning'.")
    report = "\n".join(lines) + "\n"
    print(report)
    if args.report:
        args.report.write_text(report, encoding="utf-8")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
