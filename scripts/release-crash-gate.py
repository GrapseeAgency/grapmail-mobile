#!/usr/bin/env python3
"""Crash-free gate for staged rollouts (play-rollout.yml, appstore-release.yml).

Asks GrapMail's own crash/session reporting (backend routes/grapmail-client.ts,
fed by android logic/CrashReporter.kt and iOS RemoteConfig.swift's CrashReporter)
for the last N hours of one version, and refuses to widen a rollout unless the
crash-free session rate is at or above the threshold over enough sessions.

    GET $GRAPMAIL_REPORTS_SUMMARY_URL?platform=android&version=0.6.4&build=13&hours=24
    Authorization: Bearer $GRAPMAIL_REPORTS_SECRET
    X-GrapMail-Reports-Secret: $GRAPMAIL_REPORTS_SECRET

Accepted response shapes (the parser is deliberately tolerant; see RELEASING.md
"Crash-free gate contract" for the one the backend should return):

    {"success": true, "windowHours": 24,
     "versions": [{"platform": "android", "appVersion": "0.6.4 (13)",
                   "sessions": 1830, "crashes": 2, "hangs": 1,
                   "crashFreeSessions": 99.89}]}

  or a single already-filtered object with the same per-version keys at the top
  level. `crashFreeSessions` may be a percentage (99.89) or a fraction (0.9989);
  when absent it is computed as (1 - crashes / sessions) * 100.

Exit codes (the workflow decides what an override means):
  0  gate passed
  1  gate FAILED on real data (below threshold, or too few sessions)
  2  gate could not be evaluated (endpoint unreachable, non-2xx, unparseable,
     version absent, secret missing) -- fail closed
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_URL = "https://grapsee.com/api/grapsee/grapmail/client-reports/summary"


def out(key: str, value: str) -> None:
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as fh:
            fh.write(f"{key}={value}\n")


def summary(lines: list[str]) -> None:
    text = "\n".join(lines) + "\n"
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
            fh.write(text)


def num(v) -> float | None:
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def matches(entry: dict, platform: str, version: str, build: str) -> bool:
    """appVersion is what the apps send: "0.6.4 (13)" (marketing version, then build)."""
    p = str(entry.get("platform", platform)).lower()
    if p != platform:
        return False
    app_version = str(entry.get("appVersion") or entry.get("version") or "").strip()
    ver, _, rest = app_version.partition(" ")
    b = str(entry.get("build") or entry.get("versionCode") or entry.get("buildNumber") or "").strip()
    if not b and rest.startswith("(") and rest.endswith(")"):
        b = rest[1:-1].strip()
    if ver and ver != version:
        return False
    if build and b and b != build:
        return False
    return bool(ver or b)


def evaluate(doc, platform: str, version: str, build: str) -> dict | None:
    if isinstance(doc, dict) and isinstance(doc.get("data"), (dict, list)):
        doc = doc["data"]
    entries = []
    if isinstance(doc, dict) and isinstance(doc.get("versions"), list):
        entries = [e for e in doc["versions"] if isinstance(e, dict)]
    elif isinstance(doc, list):
        entries = [e for e in doc if isinstance(e, dict)]
    elif isinstance(doc, dict) and "sessions" in doc:
        entries = [doc]
    hits = [e for e in entries if matches(e, platform, version, build)]
    if not hits and len(entries) == 1 and "appVersion" not in entries[0]:
        hits = entries  # server already filtered by our query string
    if not hits:
        return None
    sessions = sum(num(e.get("sessions")) or 0 for e in hits)
    crashes = sum(num(e.get("crashes")) or 0 for e in hits)
    hangs = sum(num(e.get("hangs")) or 0 for e in hits)
    rate = None
    if len(hits) == 1:
        rate = num(hits[0].get("crashFreeSessions") or hits[0].get("crashFreeRate"))
        if rate is not None and rate <= 1.0:
            rate *= 100.0
    if rate is None and sessions > 0:
        rate = (1.0 - crashes / sessions) * 100.0
    return {"sessions": int(sessions), "crashes": int(crashes), "hangs": int(hangs), "rate": rate}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--platform", choices=["android", "ios"], required=True)
    ap.add_argument("--version", required=True, help="marketing version, e.g. 0.6.4")
    ap.add_argument("--build", default="", help="versionCode / CFBundleVersion, e.g. 13")
    ap.add_argument("--threshold", type=float, default=99.5, help="minimum crash-free sessions, percent")
    ap.add_argument("--min-sessions", type=int, default=50)
    ap.add_argument("--hours", type=int, default=24)
    ap.add_argument("--url", default=os.environ.get("GRAPMAIL_REPORTS_SUMMARY_URL") or DEFAULT_URL)
    args = ap.parse_args()

    label = f"{args.platform} {args.version}" + (f" ({args.build})" if args.build else "")
    head = [f"### Crash-free gate: {label}, last {args.hours}h",
            f"threshold ≥ {args.threshold:.2f}% crash-free sessions over ≥ {args.min_sessions} sessions", ""]

    secret = os.environ.get("GRAPMAIL_REPORTS_SECRET", "")
    if not secret:
        print("::error title=crash gate::GRAPMAIL_REPORTS_SECRET is not set: the gate cannot read the crash "
              "summary, so it fails closed. Add it under Settings › Secrets and variables › Actions.")
        summary(head + ["- ⛔ not evaluated: `GRAPMAIL_REPORTS_SECRET` missing (fail closed)"])
        out("gate", "unavailable")
        return 2

    query = urllib.parse.urlencode({k: v for k, v in {
        "platform": args.platform, "version": args.version, "build": args.build, "hours": args.hours,
    }.items() if v not in ("", None)})
    url = f"{args.url}?{query}"
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {secret}",
        "X-GrapMail-Reports-Secret": secret,
        "Accept": "application/json",
        "User-Agent": "grapmail-release-gate/1 (+github-actions)",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as e:
        print(f"::error title=crash gate::summary endpoint answered HTTP {e.code}; failing closed")
        summary(head + [f"- ⛔ not evaluated: `{args.url}` answered HTTP {e.code} (fail closed)"])
        out("gate", "unavailable")
        return 2
    except Exception as e:  # DNS, TLS, timeout
        print(f"::error title=crash gate::summary endpoint unreachable ({type(e).__name__}: {e}); failing closed")
        summary(head + [f"- ⛔ not evaluated: `{args.url}` unreachable — {type(e).__name__} (fail closed)"])
        out("gate", "unavailable")
        return 2

    try:
        doc = json.loads(body)
    except json.JSONDecodeError:
        print(f"::error title=crash gate::summary endpoint (HTTP {status}) did not return JSON; failing closed")
        summary(head + ["- ⛔ not evaluated: response was not JSON (fail closed)"])
        out("gate", "unavailable")
        return 2
    if isinstance(doc, dict) and doc.get("success") is False:
        print(f"::error title=crash gate::summary endpoint reported success=false: {str(doc)[:300]}")
        summary(head + ["- ⛔ not evaluated: endpoint reported `success: false` (fail closed)"])
        out("gate", "unavailable")
        return 2

    result = evaluate(doc, args.platform, args.version, args.build)
    if result is None:
        print(f"::error title=crash gate::no sessions reported for {label} in the last {args.hours}h — "
              "nobody has run this build yet, or the build does not report")
        summary(head + [f"- ⛔ not evaluated: `{label}` absent from the summary (fail closed)"])
        out("gate", "unavailable")
        return 2

    rate = result["rate"]
    rate_text = "n/a" if rate is None else f"{rate:.3f}%"
    lines = head + [
        "| sessions | crashes | hangs | crash-free sessions |",
        "|---:|---:|---:|---:|",
        f"| {result['sessions']} | {result['crashes']} | {result['hangs']} | {rate_text} |",
        "",
    ]
    out("sessions", str(result["sessions"]))
    out("crash_free", "" if rate is None else f"{rate:.3f}")

    if result["sessions"] < args.min_sessions:
        print(f"::error title=crash gate::only {result['sessions']} sessions for {label} "
              f"(need {args.min_sessions}) — too little evidence to widen the rollout")
        summary(lines + [f"- ❌ FAILED: {result['sessions']} sessions < {args.min_sessions} required"])
        out("gate", "failed")
        return 1
    if rate is None or rate < args.threshold:
        print(f"::error title=crash gate::crash-free sessions {rate_text} < {args.threshold:.2f}% for {label}")
        summary(lines + [f"- ❌ FAILED: {rate_text} < {args.threshold:.2f}%"])
        out("gate", "failed")
        return 1
    summary(lines + [f"- ✅ PASSED: {rate_text} ≥ {args.threshold:.2f}%"])
    out("gate", "passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
