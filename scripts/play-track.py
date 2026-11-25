#!/usr/bin/env python3
"""Google Play track control for play-rollout.yml (Play Developer API v3, free).

The upload itself is done by r0adkll/upload-google-play; this script does what
that action does not: read the tracks, and change an existing release's staged
rollout (widen, halt, resume, complete) or start a production rollout from a
version code already on a testing track, without re-uploading anything.

Auth: the JSON key of a Google Cloud service account that is invited in Play
Console (Users and permissions) with "Release to production..." permission,
passed in PLAY_SERVICE_ACCOUNT_JSON. Requires `google-auth` and `requests`.

Commands
  status                         print every track and release
  staged                         the in-progress/halted release on --track, if any (outputs)
  max-version-code               highest version code on any track (stdout + output)
  plan   --fraction F|keep [--version-code N] [--source-track internal]
         work out what `set` would do, without changing anything (outputs JSON)
  set    --fraction F|keep [--version-code N] [--source-track internal]
         F < 1.0 -> status inProgress at userFraction F; F == 1.0 -> completed;
         keep -> inProgress at the staged release's current fraction (resume after halt)
  halt                           in-progress release -> halted
  resume                         halted release -> inProgress at its last fraction

Every change happens inside one Play "edit" that is committed only at the end,
so a refused change leaves Play exactly as it was.
"""
from __future__ import annotations

import argparse
import json
import os
import sys

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"


def fail(msg: str, code: int = 1) -> None:
    print(f"::error title=play::{msg}")
    sys.exit(code)


def output(key: str, value: str) -> None:
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as fh:
            fh.write(f"{key}={value}\n")


def session():
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw.strip():
        fail("PLAY_SERVICE_ACCOUNT_JSON is not set. Create a service account in Google Cloud, invite it in "
             "Play Console › Users and permissions with release permission, and add its JSON key as a "
             "repository secret (see RELEASING.md › One-time setup).")
    try:
        info = json.loads(raw)
    except json.JSONDecodeError:
        fail("PLAY_SERVICE_ACCOUNT_JSON is not valid JSON — paste the whole downloaded key file, unmodified.")
    from google.oauth2 import service_account
    from google.auth.transport.requests import AuthorizedSession
    creds = service_account.Credentials.from_service_account_info(
        info, scopes=["https://www.googleapis.com/auth/androidpublisher"])
    return AuthorizedSession(creds)


class Edit:
    def __init__(self, http, package: str):
        self.http, self.base = http, f"{API}/{package}/edits"
        r = http.post(self.base, json={})
        self.check(r, "open an edit")
        self.id = r.json()["id"]
        self.committed = False

    @staticmethod
    def check(r, what: str) -> None:
        if r.status_code >= 300:
            detail = r.text[:800].replace("\n", " ")
            hint = ""
            if r.status_code in (401, 403):
                hint = (" — the service account is not authorised for this app: invite it in Play Console › "
                        "Users and permissions and grant it this app with release permissions")
            elif r.status_code == 404:
                hint = " — no such app on this Play account yet (the first upload must be made by hand in Play Console)"
            fail(f"could not {what}: HTTP {r.status_code}{hint}. {detail}")

    def tracks(self) -> list[dict]:
        r = self.http.get(f"{self.base}/{self.id}/tracks")
        self.check(r, "list tracks")
        return r.json().get("tracks", [])

    def track(self, name: str) -> dict:
        r = self.http.get(f"{self.base}/{self.id}/tracks/{name}")
        if r.status_code == 404:
            return {"track": name, "releases": []}
        self.check(r, f"read track {name}")
        return r.json()

    def put_track(self, name: str, releases: list[dict]) -> None:
        r = self.http.put(f"{self.base}/{self.id}/tracks/{name}", json={"track": name, "releases": releases})
        self.check(r, f"update track {name}")

    def commit(self) -> None:
        r = self.http.post(f"{self.base}/{self.id}:commit")
        self.check(r, "commit the edit")
        self.committed = True

    def close(self) -> None:
        if not self.committed:
            self.http.delete(f"{self.base}/{self.id}")


def codes(release: dict) -> list[int]:
    return [int(c) for c in release.get("versionCodes", [])]


def describe(release: dict) -> str:
    frac = release.get("userFraction")
    frac_text = f" at {float(frac) * 100:g}%" if frac is not None else ""
    return f"{release.get('name') or '(unnamed)'} codes={codes(release)} {release.get('status')}{frac_text}"


def staged_release(edit: Edit, track: str) -> dict | None:
    return next((r for r in edit.track(track).get("releases", [])
                 if r.get("status") in ("inProgress", "halted")), None)


def plan(edit: Edit, track: str, fraction_arg: str, version_code: int | None, source_track: str) -> dict:
    keep = fraction_arg == "keep"
    fraction = 0.0 if keep else float(fraction_arg)
    if not keep and not (0 < fraction <= 1.0):
        fail(f"fraction must be in (0, 1] or 'keep', got {fraction_arg}")
    current = edit.track(track)
    releases = current.get("releases", [])
    staged = [r for r in releases if r.get("status") in ("inProgress", "halted")]
    completed = [r for r in releases if r.get("status") == "completed"]

    target = None
    if version_code is not None:
        target = next((r for r in staged if version_code in codes(r)), None)
        if target is None and any(version_code in codes(r) for r in completed):
            fail(f"version code {version_code} is already fully rolled out on {track}")
    elif staged:
        target = staged[0]

    if target is not None:
        old = float(target.get("userFraction") or 0.0)
        if keep:
            fraction = old
        if fraction < old:
            fail(f"{track} is at {old * 100:g}%; Play cannot lower a staged rollout. Halt it instead "
                 f"(action: halt) and ship a fixed build.")
        return {"track": track, "mode": "update", "release": target, "name": target.get("name") or "",
                "version_code": codes(target)[-1], "current_fraction": old, "new_fraction": fraction,
                "is_increase": fraction > old or target.get("status") == "halted",
                "status_now": target.get("status")}

    if keep:
        fail(f"nothing staged on {track} to keep at its current fraction")
    # Nothing staged for that code on this track: start it from a testing track.
    src = edit.track(source_track)
    candidates = src.get("releases", [])
    if version_code is not None:
        source = next((r for r in candidates if version_code in codes(r)), None)
    else:
        source = max(candidates, key=lambda r: max(codes(r) or [0]), default=None)
    if source is None:
        fail(f"nothing to roll out: {track} has no staged release"
             + (f" for version code {version_code}" if version_code else "")
             + f", and {source_track} has no matching release. Upload one first (action: upload).")
    if staged:
        live = staged[0]
        if live.get("status") == "inProgress":
            fail(f"{track} already has an in-progress rollout ({describe(live)}). Complete it (fraction 1.0) or "
                 f"halt it before starting another version.")
    code = max(codes(source))
    if completed and code <= max(max(codes(r)) for r in completed):
        fail(f"version code {code} is not newer than the build fully live on {track}")
    return {"track": track, "mode": "start", "release": source, "name": source.get("name") or "",
            "version_code": code, "current_fraction": 0.0, "new_fraction": fraction, "is_increase": True,
            "status_now": "absent", "source_track": source_track}


def apply_set(edit: Edit, p: dict) -> None:
    track, fraction = p["track"], p["new_fraction"]
    current = edit.track(track).get("releases", [])
    code = p["version_code"]
    others = [r for r in current if code not in codes(r) and r.get("status") == "completed"]
    base = dict(p["release"])
    new = {k: base[k] for k in ("name", "versionCodes", "releaseNotes", "inAppUpdatePriority") if k in base}
    if fraction >= 1.0:
        new["status"] = "completed"
        releases = [new]  # the previous completed release is superseded
    else:
        new["status"] = "inProgress"
        new["userFraction"] = round(fraction, 4)
        releases = others + [new]
    edit.put_track(track, releases)


def set_status(edit: Edit, track: str, want: str) -> dict:
    releases = edit.track(track).get("releases", [])
    frm = "inProgress" if want == "halted" else "halted"
    target = next((r for r in releases if r.get("status") == frm), None)
    if target is None:
        fail(f"{track} has no {frm} release to {'halt' if want == 'halted' else 'resume'} "
             f"(releases: {[describe(r) for r in releases] or 'none'})")
    target["status"] = want
    edit.put_track(track, releases)
    return target


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["status", "max-version-code", "plan", "set", "halt", "resume"])
    ap.add_argument("--package", default="com.grapsee.grapmail")
    ap.add_argument("--track", default="production")
    ap.add_argument("--fraction", type=float)
    ap.add_argument("--version-code", type=int)
    ap.add_argument("--source-track", default="internal")
    args = ap.parse_args()

    edit = Edit(session(), args.package)
    try:
        if args.command == "status":
            lines = ["### Google Play tracks", ""]
            for t in edit.tracks():
                for r in t.get("releases", []) or [{}]:
                    lines.append(f"- **{t['track']}**: {describe(r) if r else '(empty)'}")
            print("\n".join(lines))
            if os.environ.get("GITHUB_STEP_SUMMARY"):
                with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
                    fh.write("\n".join(lines) + "\n")
        elif args.command == "max-version-code":
            top = max((c for t in edit.tracks() for r in t.get("releases", []) for c in codes(r)), default=0)
            print(top)
            output("max_version_code", str(top))
        elif args.command in ("plan", "set"):
            if args.fraction is None:
                fail("--fraction is required")
            p = plan(edit, args.track, args.fraction, args.version_code, args.source_track)
            public = {k: v for k, v in p.items() if k != "release"}
            print(json.dumps(public, indent=2))
            for k in ("mode", "name", "version_code", "current_fraction", "new_fraction", "status_now"):
                output(k, str(public.get(k, "")))
            output("is_increase", "true" if p["is_increase"] else "false")
            output("version_name", (p["name"].split(" ")[0] if p["name"] else ""))
            if args.command == "set":
                apply_set(edit, p)
                edit.commit()
                pct = p["new_fraction"] * 100
                state = "completed (100%)" if p["new_fraction"] >= 1 else f"inProgress at {pct:g}%"
                print(f"{args.track}: version code {p['version_code']} now {state}")
        elif args.command in ("halt", "resume"):
            r = set_status(edit, args.track, "halted" if args.command == "halt" else "inProgress")
            edit.commit()
            print(f"{args.track}: {describe(r)}")
    finally:
        edit.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
