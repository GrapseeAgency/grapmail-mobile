#!/usr/bin/env python3
"""Release version gate shared by play-rollout.yml and appstore-release.yml.

One release, one version. The store workflows refuse to ship unless:

  1. the tag names the build's own version on the platform being shipped
     (Android versionName / iOS MARKETING_VERSION)                 -- never overridable
  2. the tag names the OTHER platform's version too, so both stores carry
     the same release number                                       -- overridable
  3. the version code / build number is strictly higher than at the previous
     release tag, and the marketing version did not go backwards  -- overridable

"Overridable" means --allow-override turns the error into a warning that is
written to the run summary, so a deliberate exception is on the record instead
of silent. Nothing here edits a version: it reports, and the human decides.

Reads android/app/build.gradle.kts and ios/project.yml at HEAD and at the
previous tag (`git show <tag>:<path>`), so the checkout needs full history and
tags (actions/checkout with fetch-depth: 0).

Exit codes: 0 pass (possibly with recorded overrides), 1 refused.
Outputs (to $GITHUB_OUTPUT when set): android_version_code, android_version_name,
ios_build_number, ios_marketing_version, previous_tag.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE = "android/app/build.gradle.kts"
XCODEGEN = "ios/project.yml"


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)


def android_versions(text: str | None) -> tuple[int | None, str | None]:
    if not text:
        return None, None
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)", text, re.M)
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.M)
    return (int(code.group(1)) if code else None, name.group(1) if name else None)


def ios_versions(text: str | None) -> tuple[int | None, str | None]:
    if not text:
        return None, None
    mv = re.search(r'^\s*MARKETING_VERSION:\s*"?([0-9][0-9A-Za-z.\-]*)"?\s*$', text, re.M)
    bn = re.search(r'^\s*CURRENT_PROJECT_VERSION:\s*"?(\d+)"?\s*$', text, re.M)
    return (int(bn.group(1)) if bn else None, mv.group(1) if mv else None)


def semver(v: str | None) -> tuple[int, ...]:
    if not v:
        return ()
    return tuple(int(p) if p.isdigit() else 0 for p in re.split(r"[.\-]", v))


def show(ref: str, path: str) -> str | None:
    r = git("show", f"{ref}:{path}")
    return r.stdout if r.returncode == 0 else None


def previous_tag(current: str | None, head: str) -> str | None:
    """The most recent v* tag created before this one, on a different commit."""
    head_commit = git("rev-parse", f"{head}^{{commit}}").stdout.strip()
    r = git("for-each-ref", "--sort=-creatordate",
            "--format=%(refname:short) %(creatordate:unix)", "refs/tags/v[0-9]*")
    rows = [line.split() for line in r.stdout.splitlines() if line.strip()]
    ceiling = time.time() + 1
    for name, stamp in rows:
        if name == current:
            ceiling = int(stamp)
    for name, stamp in rows:
        if name == current or int(stamp) > ceiling:
            continue
        if git("rev-parse", f"{name}^{{commit}}").stdout.strip() == head_commit:
            continue
        return name
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--platform", choices=["android", "ios"], required=True,
                    help="the platform this workflow ships; its tag match is never overridable")
    ap.add_argument("--tag", default="", help="release tag, e.g. v0.6.4 (empty for an untagged candidate)")
    ap.add_argument("--head", default="HEAD")
    ap.add_argument("--allow-override", action="store_true",
                    help="downgrade cross-platform and monotonicity failures to recorded warnings")
    args = ap.parse_args()

    a_code, a_name = android_versions(show(args.head, GRADLE))
    i_build, i_ver = ios_versions(show(args.head, XCODEGEN))
    want = args.tag[1:] if args.tag.startswith("v") else args.tag

    hard: list[str] = []      # never overridable
    soft: list[str] = []      # overridable, recorded
    notes: list[str] = []

    own_name = a_name if args.platform == "android" else i_ver
    own_label = f"{GRADLE} versionName" if args.platform == "android" else f"{XCODEGEN} MARKETING_VERSION"
    other_name = i_ver if args.platform == "android" else a_name
    other_label = f"{XCODEGEN} MARKETING_VERSION" if args.platform == "android" else f"{GRADLE} versionName"

    if None in (a_code, a_name):
        hard.append(f"could not read versionCode/versionName from {GRADLE}")
    if None in (i_build, i_ver):
        hard.append(f"could not read MARKETING_VERSION/CURRENT_PROJECT_VERSION from {XCODEGEN}")

    if want:
        if own_name != want:
            hard.append(f"tag {args.tag} but {own_label} is {own_name}: the tag and the build disagree")
        if other_name != want:
            soft.append(f"tag {args.tag} but {other_label} is {other_name}: the two platforms would ship "
                        f"different version numbers for one release")
    else:
        notes.append("no release tag: tag/version agreement not checked (untagged candidate)")
        if a_name and i_ver and a_name != i_ver:
            soft.append(f"Android versionName {a_name} and iOS MARKETING_VERSION {i_ver} differ")

    prev = previous_tag(args.tag or None, args.head)
    pa_code = pa_name = pi_build = pi_ver = None
    if prev:
        pa_code, pa_name = android_versions(show(prev, GRADLE))
        pi_build, pi_ver = ios_versions(show(prev, XCODEGEN))
        if args.platform == "android":
            if pa_code is None:
                notes.append(f"previous tag {prev} has no readable versionCode; monotonicity not checked")
            elif a_code is not None and a_code <= pa_code:
                soft.append(f"versionCode {a_code} is not higher than {pa_code} at {prev}: "
                            f"Play refuses a version code it has seen, and devices refuse a downgrade")
            if pa_name and a_name and semver(a_name) < semver(pa_name):
                soft.append(f"versionName {a_name} is lower than {pa_name} at {prev}")
        else:
            if pi_build is None:
                notes.append(f"previous tag {prev} has no readable CURRENT_PROJECT_VERSION; monotonicity not checked")
            elif i_build is not None and i_build <= pi_build:
                soft.append(f"CURRENT_PROJECT_VERSION {i_build} is not higher than {pi_build} at {prev}: "
                            f"App Store Connect refuses a reused build number")
            if pi_ver and i_ver and semver(i_ver) < semver(pi_ver):
                soft.append(f"MARKETING_VERSION {i_ver} is lower than {pi_ver} at {prev}: "
                            f"App Store review refuses a version below the last approved one")
    else:
        notes.append("no previous v* tag found: monotonicity not checked")

    summary = [
        f"### Version gate ({args.platform})",
        "",
        "| | Android | iOS |",
        "|---|---|---|",
        f"| this build ({args.head}) | {a_name} ({a_code}) | {i_ver} ({i_build}) |",
        f"| previous tag {prev or '—'} | {pa_name} ({pa_code}) | {pi_ver} ({pi_build}) |",
        f"| tag | {args.tag or '—'} | {args.tag or '—'} |",
        "",
    ]
    for h in hard:
        print(f"::error title=version gate::{h}")
        summary.append(f"- ❌ {h}")
    for s in soft:
        if args.allow_override:
            print(f"::warning title=version gate OVERRIDDEN::{s}")
            summary.append(f"- ⚠️ **OVERRIDDEN by `version_override`:** {s}")
        else:
            print(f"::error title=version gate::{s}")
            summary.append(f"- ❌ {s} (re-run with `version_override` to ship anyway, on the record)")
    for n in notes:
        print(f"note: {n}")
        summary.append(f"- ℹ️ {n}")
    if not (hard or soft):
        summary.append("- ✅ versions agree and increase")

    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
            fh.write("\n".join(summary) + "\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as fh:
            fh.write(f"android_version_code={a_code or ''}\n")
            fh.write(f"android_version_name={a_name or ''}\n")
            fh.write(f"ios_build_number={i_build or ''}\n")
            fh.write(f"ios_marketing_version={i_ver or ''}\n")
            fh.write(f"previous_tag={prev or ''}\n")
    print("\n".join(summary))

    if hard or (soft and not args.allow_override):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
