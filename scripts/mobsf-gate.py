#!/usr/bin/env python3
"""Apply security/mobsf-allowlist.yml to a MobSF static-analysis JSON report.

Used by .github/workflows/security-scan.yml (job `mobsf-android`). It reads the
JSON that MobSF's REST API returns from POST /api/v1/report_json, pulls every
finding out of the sections that carry a severity, and fails when a finding at
the gating severity (default: high) is not covered by a valid allowlist entry.

The script fails CLOSED. A report that is missing, empty, an API error, or a
shape it cannot recognise is an error (exit 2), not a pass. A scanner that
quietly reports nothing proves nothing.

Finding ids (the strings you put in the allowlist) are stable and readable:

    code:<rule_id>             code_analysis.findings.<rule_id>
    manifest:<rule>            manifest_analysis.manifest_findings[].rule
    network:<slug>             network_security.network_findings[] (slug of description)
    certificate:<slug>         certificate_analysis.certificate_findings[] (slug of title)
    binary:<check>             binary_analysis[].<check> (e.g. binary:stack_canary)
    firebase:open-database     firebase_urls[] with open == true
    ats:<slug> / ios-binary:<slug>   (iOS reports, if an IPA is ever scanned)

Run `python3 scripts/mobsf-gate.py --report r.json --list` to print every id
in a report, which is the easy way to write an allowlist entry.

Exit codes: 0 pass, 1 blocking findings or expired/invalid allowlist entries,
2 unusable report or allowlist file.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
from pathlib import Path

SEVERITY_ORDER = {"info": 0, "secure": 0, "good": 0, "hotspot": 1,
                  "warning": 2, "medium": 2, "high": 3, "critical": 4}
ALLOWLIST_REQUIRED = ("id", "reason", "owner", "review_by")

# A debug APK is debuggable and signed with the Android debug key by
# definition. With --debug-build those two findings are reported as
# "expected (debug build)" instead of blocking. Everything else still gates.
# The release job in android.yml separately proves the release manifest is not
# debuggable and that the APK carries the real upload certificate.
DEBUG_ONLY_PATTERNS = (
    re.compile(r"debuggable|debug\s+enabled", re.I),
    re.compile(r"debug\s+certificate|signed\s+with\s+(a\s+)?debug", re.I),
)


def slug(text: object, limit: int = 80) -> str:
    s = re.sub(r"<[^>]+>", " ", str(text or ""))
    s = re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")
    return (s[:limit].rstrip("-")) or "unnamed"


def norm_sev(value: object) -> str:
    s = str(value or "").strip().lower()
    if s in ("med", "moderate"):
        return "medium"
    return s or "unknown"


def sev_rank(value: str) -> int:
    return SEVERITY_ORDER.get(norm_sev(value), -1)


def short(text: object, limit: int = 140) -> str:
    s = re.sub(r"<[^>]+>", " ", str(text or ""))
    s = re.sub(r"\s+", " ", s).strip()
    s = s.replace("|", "\\|")
    return s if len(s) <= limit else s[: limit - 1] + "…"


def as_list(value: object) -> list:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    if isinstance(value, dict):
        return [value]
    return []


class Finding:
    __slots__ = ("id", "section", "severity", "title", "where", "masvs", "status", "note")

    def __init__(self, fid, section, severity, title, where="", masvs=""):
        self.id = fid
        self.section = section
        self.severity = norm_sev(severity)
        self.title = title
        self.where = where
        self.masvs = masvs
        self.status = ""
        self.note = ""


# ---------------------------------------------------------------- extraction

def extract_code(report: dict, key: str, prefix: str) -> list[Finding] | None:
    """code_analysis: {"findings": {rule: {"files": {...}, "metadata": {...}}}, "summary": {...}}.
    Older MobSF versions put the rule dict directly under code_analysis."""
    sec = report.get(key)
    if not isinstance(sec, dict):
        return None
    rules = sec.get("findings") if isinstance(sec.get("findings"), dict) else sec
    out = []
    for rule, details in rules.items():
        if rule in ("summary",) or not isinstance(details, dict):
            continue
        meta = details.get("metadata") if isinstance(details.get("metadata"), dict) else details
        sev = meta.get("severity") or details.get("level")
        if sev is None:
            continue
        files = details.get("files")
        nfiles = len(files) if isinstance(files, (dict, list)) else 0
        title = meta.get("description") or meta.get("title") or rule
        out.append(Finding(f"{prefix}:{rule}", key, sev, title,
                           f"{nfiles} file(s)" if nfiles else "",
                           meta.get("masvs") or ""))
    return out


def extract_manifest(report: dict) -> list[Finding] | None:
    sec = report.get("manifest_analysis")
    if sec is None:
        return None
    items = sec.get("manifest_findings") if isinstance(sec, dict) else sec
    out = []
    for item in as_list(items):
        if not isinstance(item, dict):
            continue
        title = item.get("title") or item.get("name") or item.get("description")
        rule = item.get("rule") or slug(title)
        comp = item.get("component") or item.get("name") or ""
        if isinstance(comp, list):
            comp = ", ".join(map(str, comp[:3]))
        out.append(Finding(f"manifest:{rule}", "manifest_analysis",
                           item.get("severity") or item.get("stat"), title or rule,
                           short(comp, 60)))
    return out


def extract_network(report: dict) -> list[Finding] | None:
    sec = report.get("network_security")
    if sec is None:
        return None
    items = sec.get("network_findings") if isinstance(sec, dict) else sec
    out = []
    for item in as_list(items):
        if not isinstance(item, dict):
            continue
        desc = item.get("description") or item.get("title") or ""
        scope = item.get("scope") or ""
        if isinstance(scope, list):
            scope = ", ".join(map(str, scope[:3]))
        out.append(Finding(f"network:{slug(desc, 60)}", "network_security",
                           item.get("severity"), desc, short(scope, 60)))
    return out


def extract_certificate(report: dict) -> list[Finding] | None:
    sec = report.get("certificate_analysis")
    if sec is None:
        return None
    items = sec.get("certificate_findings") if isinstance(sec, dict) else sec
    out = []
    for item in as_list(items):
        # MobSF emits [severity, description, title]; accept dicts too.
        if isinstance(item, (list, tuple)) and len(item) >= 2:
            sev, desc = item[0], item[1]
            title = item[2] if len(item) > 2 else desc
        elif isinstance(item, dict):
            sev = item.get("severity")
            desc = item.get("description", "")
            title = item.get("title") or desc
        else:
            continue
        out.append(Finding(f"certificate:{slug(title, 60)}", "certificate_analysis",
                           sev, f"{title}: {desc}" if title != desc else desc))
    return out


def extract_binary(report: dict) -> list[Finding] | None:
    sec = report.get("binary_analysis")
    if sec is None:
        return None
    out = []
    # Android: list of {"name": lib, "<check>": {"severity", "description", ...}}
    # iOS:     {"findings": {title: {"severity", "detailed_desc", ...}}, "summary": {...}}
    if isinstance(sec, dict) and isinstance(sec.get("findings"), dict):
        for title, d in sec["findings"].items():
            if isinstance(d, dict) and "severity" in d:
                out.append(Finding(f"ios-binary:{slug(title, 60)}", "binary_analysis",
                                   d.get("severity"), d.get("detailed_desc") or title))
        return out
    for lib in as_list(sec):
        if not isinstance(lib, dict):
            continue
        name = lib.get("name") or ""
        for check, d in lib.items():
            if isinstance(d, dict) and "severity" in d:
                out.append(Finding(f"binary:{check}", "binary_analysis", d.get("severity"),
                                   d.get("description") or check, short(name, 60)))
    return out


def extract_firebase(report: dict) -> list[Finding] | None:
    sec = report.get("firebase_urls")
    if sec is None:
        return None
    out = []
    for item in as_list(sec):
        if isinstance(item, dict) and item.get("open"):
            out.append(Finding("firebase:open-database", "firebase_urls", "high",
                               "Firebase database is publicly readable",
                               short(item.get("url"), 60)))
    return out


def extract_ats(report: dict) -> list[Finding] | None:
    sec = report.get("ats_analysis")
    if sec is None:
        return None
    items = sec.get("ats_findings") if isinstance(sec, dict) else sec
    out = []
    for item in as_list(items):
        if isinstance(item, dict):
            title = item.get("issue") or item.get("title") or item.get("description")
            out.append(Finding(f"ats:{slug(title, 60)}", "ats_analysis",
                               item.get("severity"), title))
    return out


EXTRACTORS = (
    lambda r: extract_code(r, "code_analysis", "code"),
    extract_manifest,
    extract_network,
    extract_certificate,
    extract_binary,
    extract_firebase,
    extract_ats,
    lambda r: extract_code(r, "js_analysis", "js"),
)


def extract_all(report: dict) -> tuple[list[Finding], int]:
    findings, recognised = [], 0
    for fn in EXTRACTORS:
        try:
            got = fn(report)
        except Exception as exc:  # a changed shape must be loud, not fatal-silent
            print(f"::warning::mobsf-gate: could not parse a report section ({exc!r})")
            got = None
        if got is not None:
            recognised += 1
            findings.extend(got)
    return findings, recognised


# ----------------------------------------------------------------- allowlist

def load_allowlist(path: Path, today: dt.date) -> tuple[dict, list[str], list[str]]:
    """Return ({id: entry}, errors, warnings). Missing file == empty allowlist."""
    if not path.exists():
        return {}, [], [f"allowlist {path} not found; treating as empty"]
    try:
        import yaml  # PyYAML
    except ImportError:
        print("mobsf-gate: PyYAML is required to read the allowlist (pip install PyYAML)")
        sys.exit(2)
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except Exception as exc:
        print(f"mobsf-gate: cannot parse {path}: {exc}")
        sys.exit(2)
    entries = data.get("entries", []) if isinstance(data, dict) else data
    if entries is None:
        entries = []
    if not isinstance(entries, list):
        print(f"mobsf-gate: {path}: 'entries' must be a list")
        sys.exit(2)
    allow, errors, warnings = {}, [], []
    for i, e in enumerate(entries, 1):
        if not isinstance(e, dict):
            errors.append(f"entry #{i} is not a mapping")
            continue
        missing = [k for k in ALLOWLIST_REQUIRED if not str(e.get(k) or "").strip()]
        label = e.get("id") or f"#{i}"
        if missing:
            errors.append(f"entry {label}: missing {', '.join(missing)}")
            continue
        rb = e["review_by"]
        if isinstance(rb, dt.datetime):
            rb = rb.date()
        if not isinstance(rb, dt.date):
            try:
                rb = dt.date.fromisoformat(str(rb).strip())
            except ValueError:
                errors.append(f"entry {label}: review_by '{e['review_by']}' is not YYYY-MM-DD")
                continue
        if rb < today:
            errors.append(f"entry {label}: EXPIRED (review_by {rb.isoformat()}, owner {e['owner']}) "
                          "- re-review the finding and renew or remove the entry")
            continue
        fid = str(e["id"]).strip()
        if fid in allow:
            warnings.append(f"entry {fid} is listed twice; the first one is used")
            continue
        e = dict(e, review_by=rb)
        allow[fid] = e
    return allow, errors, warnings


# --------------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--report", required=True, help="MobSF report_json output")
    ap.add_argument("--allowlist", default="security/mobsf-allowlist.yml")
    ap.add_argument("--fail-on", default="high", help="lowest severity that blocks (default high)")
    ap.add_argument("--debug-build", action="store_true",
                    help="treat debuggable / debug-certificate findings as expected")
    ap.add_argument("--summary", default=os.environ.get("GITHUB_STEP_SUMMARY", ""),
                    help="markdown summary file to append to (default $GITHUB_STEP_SUMMARY)")
    ap.add_argument("--result-json", default="", help="write the gate decision as JSON here")
    ap.add_argument("--today", default="", help="override today's date (YYYY-MM-DD) for testing")
    ap.add_argument("--list", action="store_true", help="print every finding id and exit 0")
    args = ap.parse_args()

    today = dt.date.fromisoformat(args.today) if args.today else dt.date.today()
    threshold = sev_rank(args.fail_on)
    if threshold < 0:
        print(f"mobsf-gate: unknown --fail-on severity '{args.fail_on}'")
        return 2

    rp = Path(args.report)
    try:
        report = json.loads(rp.read_text(encoding="utf-8"))
    except FileNotFoundError:
        print(f"::error::mobsf-gate: report {rp} does not exist - the scan did not produce a report")
        return 2
    except json.JSONDecodeError as exc:
        print(f"::error::mobsf-gate: report {rp} is not JSON ({exc})")
        return 2
    if not isinstance(report, dict) or not report:
        print("::error::mobsf-gate: report is empty")
        return 2
    if "error" in report and len(report) <= 3:
        print(f"::error::mobsf-gate: MobSF returned an error instead of a report: {report.get('error')}")
        return 2

    findings, recognised = extract_all(report)
    if recognised == 0:
        print("::error::mobsf-gate: none of the expected report sections were found "
              "(code_analysis, manifest_analysis, ...). MobSF's JSON shape changed or the "
              "report is not a static-analysis report; refusing to pass on an unread report.")
        print("top-level keys:", ", ".join(sorted(report.keys())[:40]))
        return 2

    if args.list:
        for f in sorted(findings, key=lambda f: (-sev_rank(f.severity), f.id)):
            print(f"{f.severity:8} {f.id:55} {short(f.title, 90)}")
        return 0

    allow, errors, warnings = load_allowlist(Path(args.allowlist), today)
    used = set()
    blocking, allowed, expected = [], [], []
    for f in findings:
        if sev_rank(f.severity) < threshold:
            continue
        text = f"{f.id} {f.title}"
        if args.debug_build and f.section in ("manifest_analysis", "certificate_analysis") \
                and any(p.search(text) for p in DEBUG_ONLY_PATTERNS):
            f.status, f.note = "expected (debug build)", "release job proves the release artifact"
            expected.append(f)
            continue
        entry = allow.get(f.id)
        if entry:
            used.add(f.id)
            f.status = "allowlisted"
            f.note = f"{entry['owner']} until {entry['review_by'].isoformat()}: {entry['reason']}"
            allowed.append(f)
        else:
            f.status = "BLOCKING"
            blocking.append(f)
    stale = sorted(set(allow) - used)
    for s in stale:
        warnings.append(f"allowlist entry {s} matched nothing in this report (fixed? remove it)")

    counts = {}
    for f in findings:
        counts[f.severity] = counts.get(f.severity, 0) + 1

    failed = bool(blocking or errors)
    app = report.get("app_name") or report.get("file_name") or "?"
    ver = report.get("version_name") or "?"

    # Console output (also lands in the job log).
    print(f"MobSF gate: {app} {ver} - {len(findings)} findings, "
          f"{len(blocking)} blocking, {len(allowed)} allowlisted, {len(expected)} expected-debug")
    for f in blocking:
        print(f"::error title=MobSF {f.severity}::{f.id} - {short(f.title, 200)}")
    for e in errors:
        print(f"::error title=MobSF allowlist::{e}")
    for w in warnings:
        print(f"::warning title=MobSF allowlist::{w}")

    if args.summary:
        lines = [
            "## MobSF static analysis (Android debug APK)",
            "",
            f"App **{short(app, 60)}** version `{short(ver, 30)}`, MobSF `{report.get('version', '?')}`. "
            f"Gate: severity >= `{args.fail_on}` must be fixed or allowlisted in "
            f"`{args.allowlist}`.",
            "",
            "| Severity | Count |",
            "|---|---|",
        ]
        for sev in sorted(counts, key=lambda s: -sev_rank(s)):
            lines.append(f"| {sev} | {counts[sev]} |")
        lines += ["", f"**Result: {'FAIL' if failed else 'PASS'}** - "
                  f"{len(blocking)} blocking, {len(allowed)} allowlisted, "
                  f"{len(expected)} expected for a debug build, "
                  f"{len(errors)} allowlist error(s).", ""]
        gated = blocking + allowed + expected
        if gated:
            lines += ["| Status | Severity | Finding id | Title | Where | MASVS | Note |",
                      "|---|---|---|---|---|---|---|"]
            for f in gated:
                lines.append(f"| {f.status} | {f.severity} | `{f.id}` | {short(f.title, 110)} | "
                             f"{short(f.where, 50)} | {short(f.masvs, 30)} | {short(f.note, 110)} |")
            lines.append("")
        if errors:
            lines += ["### Allowlist errors", ""] + [f"- {short(e, 300)}" for e in errors] + [""]
        if warnings:
            lines += ["### Allowlist warnings", ""] + [f"- {short(w, 300)}" for w in warnings] + [""]
        with open(args.summary, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")

    if args.result_json:
        Path(args.result_json).write_text(json.dumps({
            "result": "fail" if failed else "pass",
            "counts": counts,
            "blocking": [{"id": f.id, "severity": f.severity, "title": f.title} for f in blocking],
            "allowlisted": [f.id for f in allowed],
            "expected_debug": [f.id for f in expected],
            "allowlist_errors": errors,
            "allowlist_warnings": warnings,
        }, indent=2), encoding="utf-8")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
