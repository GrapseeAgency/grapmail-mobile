#!/usr/bin/env python3
"""App Store Connect control for appstore-release.yml (App Store Connect API, free).

The archive/sign/upload already lives in ios.yml's `release` job (xcodebuild
-exportArchive with destination=Upload). This script does everything AFTER the
binary reaches App Store Connect, through the public REST API, with no paid tool:

  wait-build        --version 0.6.4 --build 13      poll until the build is VALID (processed)
  max-build-number                                  highest build number ever uploaded
  add-to-group      --version V --build N --group "External testers"
  submit            --version V --build N [--release-type AFTER_APPROVAL|MANUAL]
                    [--whats-new-file whatsnew/ios-en-US.txt]
                    create/reuse the App Store version, attach the build, turn ON
                    phased release (appStoreVersionPhasedReleases), and submit for review
  phased            --action status|pause|resume|complete [--version V]
                    Apple's 7-day 1/2/5/10/20/50/100% rollout; without --version the
                    version currently in a phased release is used

Auth: an App Store Connect API key (Users and Access › Integrations › Team Keys,
role App Manager). Env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_P8 (the .p8 contents,
raw PEM or base64 of it). Requires `pyjwt[crypto]` and `requests`.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time

API = "https://api.appstoreconnect.apple.com/v1"
BUNDLE_ID = "com.grapsee.grapmail"
PHASE_PERCENT = {1: 1, 2: 2, 3: 5, 4: 10, 5: 20, 6: 50, 7: 100}
EDITABLE = {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED", "INVALID_BINARY"}


def fail(msg: str) -> None:
    print(f"::error title=app store connect::{msg}")
    sys.exit(1)


def output(key: str, value) -> None:
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as fh:
            fh.write(f"{key}={value}\n")


def step_summary(lines: list[str]) -> None:
    text = "\n".join(lines) + "\n"
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
            fh.write(text)


class ASC:
    def __init__(self) -> None:
        import requests
        self.requests = requests
        missing = [v for v in ("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_P8") if not os.environ.get(v, "").strip()]
        if missing:
            fail(f"missing App Store Connect API key secrets: {', '.join(missing)}. Create a Team Key in "
                 "App Store Connect › Users and Access › Integrations and add it (RELEASING.md › One-time setup).")
        self.kid = os.environ["ASC_KEY_ID"].strip()
        self.iss = os.environ["ASC_ISSUER_ID"].strip()
        raw = os.environ["ASC_KEY_P8"].strip()
        if "BEGIN PRIVATE KEY" not in raw:
            try:
                raw = base64.b64decode(raw).decode("utf-8")
            except Exception:
                fail("ASC_KEY_P8 is neither a PEM private key nor base64 of one")
        self.key = raw
        self._app_id: str | None = None

    def token(self) -> str:
        import jwt
        now = int(time.time())
        return jwt.encode({"iss": self.iss, "iat": now, "exp": now + 900, "aud": "appstoreconnect-v1"},
                          self.key, algorithm="ES256", headers={"kid": self.kid, "typ": "JWT"})

    def call(self, method: str, path: str, body: dict | None = None, params: dict | None = None,
             ok404: bool = False):
        url = path if path.startswith("https://") else f"{API}{path}"
        r = self.requests.request(method, url, params=params, json=body, timeout=60,
                                  headers={"Authorization": f"Bearer {self.token()}"})
        if ok404 and r.status_code == 404:
            return None
        if r.status_code >= 300:
            detail = r.text[:1200].replace("\n", " ")
            hint = " — check the API key's role (App Manager or Admin)" if r.status_code in (401, 403) else ""
            fail(f"{method} {path} answered HTTP {r.status_code}{hint}. {detail}")
        return r.json() if r.text else {}

    def app_id(self) -> str:
        if self._app_id is None:
            data = self.call("GET", "/apps", params={"filter[bundleId]": BUNDLE_ID}).get("data", [])
            if not data:
                fail(f"no App Store Connect app with bundle id {BUNDLE_ID} — create the app record first")
            self._app_id = data[0]["id"]
        return self._app_id

    def build(self, version: str, number: str) -> dict | None:
        data = self.call("GET", "/builds", params={
            "filter[app]": self.app_id(), "filter[version]": number,
            "filter[preReleaseVersion.version]": version, "limit": 1}).get("data", [])
        return data[0] if data else None

    def version(self, version: str) -> dict | None:
        data = self.call("GET", f"/apps/{self.app_id()}/appStoreVersions", params={
            "filter[platform]": "IOS", "filter[versionString]": version, "limit": 1}).get("data", [])
        return data[0] if data else None

    def phased(self, version_id: str) -> dict | None:
        res = self.call("GET", f"/appStoreVersions/{version_id}/appStoreVersionPhasedRelease", ok404=True)
        return (res or {}).get("data")


def state_of(v: dict) -> str:
    a = v.get("attributes", {})
    return a.get("appStoreState") or a.get("appVersionState") or "?"


def cmd_wait_build(asc: ASC, version: str, number: str, timeout: int) -> None:
    deadline = time.time() + timeout
    while True:
        b = asc.build(version, number)
        st = (b or {}).get("attributes", {}).get("processingState", "NOT_FOUND")
        print(f"build {version} ({number}): {st}")
        if st == "VALID":
            break
        if st in ("FAILED", "INVALID"):
            fail(f"App Store Connect marked build {version} ({number}) {st} — see the email Apple sent")
        if time.time() > deadline:
            fail(f"build {version} ({number}) still {st} after {timeout}s")
        time.sleep(60)
    enc = b["attributes"].get("usesNonExemptEncryption")
    output("build_id", b["id"])
    lines = [f"### TestFlight: {version} ({number}) processed", ""]
    if enc is None:
        print("::warning title=export compliance::the build has no encryption answer. GrapMail ships E2E "
              "encryption (X25519/AES-GCM): answer export compliance in App Store Connect › TestFlight, or set "
              "ITSAppUsesNonExemptEncryption in ios/project.yml once you know the answer. Submission fails until then.")
        lines.append("- ⚠️ export compliance unanswered (TestFlight external testing and review are blocked until it is)")
    else:
        lines.append(f"- usesNonExemptEncryption = {enc}")
    lines.append("- internal testers (App Store Connect users) receive it automatically")
    step_summary(lines)


def cmd_max_build(asc: ASC) -> None:
    top, url, params = 0, "/builds", {"filter[app]": asc.app_id(), "limit": 200, "fields[builds]": "version"}
    for _ in range(10):
        res = asc.call("GET", url, params=params)
        for b in res.get("data", []):
            v = b.get("attributes", {}).get("version", "")
            if v.isdigit():
                top = max(top, int(v))
        url, params = res.get("links", {}).get("next"), None
        if not url:
            break
    print(top)
    output("max_build_number", top)


def cmd_add_to_group(asc: ASC, version: str, number: str, group: str) -> None:
    b = asc.build(version, number) or fail(f"build {version} ({number}) not found")
    groups = asc.call("GET", "/betaGroups", params={"filter[app]": asc.app_id(), "filter[name]": group}).get("data", [])
    if not groups:
        fail(f"no TestFlight group named {group!r}")
    asc.call("POST", f"/betaGroups/{groups[0]['id']}/relationships/builds",
             body={"data": [{"type": "builds", "id": b["id"]}]})
    print(f"build {version} ({number}) added to TestFlight group {group!r}")


def cmd_submit(asc: ASC, version: str, number: str, release_type: str, whats_new: str | None) -> None:
    b = asc.build(version, number)
    if not b or b["attributes"].get("processingState") != "VALID":
        fail(f"build {version} ({number}) is not a processed (VALID) build in App Store Connect yet")
    if b["attributes"].get("usesNonExemptEncryption") is None:
        fail("export compliance is unanswered for this build; answer it in App Store Connect › TestFlight first")

    v = asc.version(version)
    if v is None:
        v = asc.call("POST", "/appStoreVersions", body={"data": {
            "type": "appStoreVersions",
            "attributes": {"platform": "IOS", "versionString": version, "releaseType": release_type},
            "relationships": {"app": {"data": {"type": "apps", "id": asc.app_id()}},
                              "build": {"data": {"type": "builds", "id": b["id"]}}}}})["data"]
        print(f"created App Store version {version}")
    else:
        st = state_of(v)
        if st not in EDITABLE:
            fail(f"App Store version {version} is {st}; it can no longer be edited or submitted")
        asc.call("PATCH", f"/appStoreVersions/{v['id']}", body={"data": {
            "type": "appStoreVersions", "id": v["id"], "attributes": {"releaseType": release_type}}})
        asc.call("PATCH", f"/appStoreVersions/{v['id']}/relationships/build",
                 body={"data": {"type": "builds", "id": b["id"]}})
        print(f"reused App Store version {version} ({st}), attached build {number}")

    if asc.phased(v["id"]) is None:
        asc.call("POST", "/appStoreVersionPhasedReleases", body={"data": {
            "type": "appStoreVersionPhasedReleases", "attributes": {"phasedReleaseState": "INACTIVE"},
            "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": v["id"]}}}}})
        print("phased release ON (starts at 1% when the version is released)")
    else:
        print("phased release already ON")

    if whats_new and os.path.isfile(whats_new):
        text = open(whats_new, encoding="utf-8").read().strip()
        locs = asc.call("GET", f"/appStoreVersions/{v['id']}/appStoreVersionLocalizations").get("data", [])
        for loc in locs:
            r = asc.requests.patch(f"{API}/appStoreVersionLocalizations/{loc['id']}", timeout=60,
                                   headers={"Authorization": f"Bearer {asc.token()}"},
                                   json={"data": {"type": "appStoreVersionLocalizations", "id": loc["id"],
                                                  "attributes": {"whatsNew": text[:4000]}}})
            if r.status_code >= 300:  # the very first version of an app has no "What's New"
                print(f"::warning::could not set What's New for {loc['attributes'].get('locale')}: HTTP {r.status_code}")

    open_subs = asc.call("GET", "/reviewSubmissions", params={
        "filter[app]": asc.app_id(), "filter[platform]": "IOS",
        "filter[state]": "READY_FOR_REVIEW"}).get("data", [])
    if open_subs:
        sub = open_subs[0]
    else:
        sub = asc.call("POST", "/reviewSubmissions", body={"data": {
            "type": "reviewSubmissions", "attributes": {"platform": "IOS"},
            "relationships": {"app": {"data": {"type": "apps", "id": asc.app_id()}}}}})["data"]
    items = asc.call("GET", f"/reviewSubmissions/{sub['id']}/items", params={"include": "appStoreVersion"}).get("data", [])
    if not any(((i.get("relationships", {}).get("appStoreVersion", {}) or {}).get("data") or {}).get("id") == v["id"]
               for i in items):
        asc.call("POST", "/reviewSubmissionItems", body={"data": {
            "type": "reviewSubmissionItems",
            "relationships": {"reviewSubmission": {"data": {"type": "reviewSubmissions", "id": sub["id"]}},
                              "appStoreVersion": {"data": {"type": "appStoreVersions", "id": v["id"]}}}}})
    asc.call("PATCH", f"/reviewSubmissions/{sub['id']}", body={"data": {
        "type": "reviewSubmissions", "id": sub["id"], "attributes": {"submitted": True}}})
    step_summary([f"### Submitted {version} ({number}) for App Store review", "",
                  f"- release type: `{release_type}`" + (" (you press Release in App Store Connect)" if release_type == "MANUAL" else " (released as soon as Apple approves)"),
                  "- phased release: ON — 1% → 2% → 5% → 10% → 20% → 50% → 100% over 7 days",
                  "- pause / resume / complete: run this workflow with `action: phased-pause | phased-resume | phased-complete`"])


def current_phased(asc: ASC, version: str | None) -> tuple[dict, dict | None]:
    if version:
        v = asc.version(version) or fail(f"no App Store version {version}")
        return v, asc.phased(v["id"])
    res = asc.call("GET", f"/apps/{asc.app_id()}/appStoreVersions", params={"filter[platform]": "IOS", "limit": 10})
    for v in res.get("data", []):
        p = asc.phased(v["id"])
        if p and p["attributes"].get("phasedReleaseState") in ("ACTIVE", "PAUSED"):
            return v, p
    return {}, None


def cmd_phased(asc: ASC, action: str, version: str | None) -> None:
    v, p = current_phased(asc, version)
    if not v:
        print("no version is in a phased release right now")
        output("phase_state", "NONE")
        step_summary(["### Phased release", "", "- no version is in a phased release right now"])
        return
    vs = v["attributes"].get("versionString")
    build = asc.call("GET", f"/appStoreVersions/{v['id']}/build", ok404=True) or {}
    number = ((build.get("data") or {}).get("attributes") or {}).get("version", "")
    if p is None:
        fail(f"version {vs} has no phased release (it was submitted without one)")
    a = p["attributes"]
    state, day = a.get("phasedReleaseState"), a.get("currentDayNumber") or 0
    pct = PHASE_PERCENT.get(day, 0)
    output("version", vs)
    output("build", number)
    output("phase_state", state)
    output("phase_day", day)
    output("phase_percent", pct)

    want = {"pause": "PAUSED", "resume": "ACTIVE", "complete": "COMPLETE"}.get(action)
    if want:
        allowed = {"PAUSED": {"ACTIVE"}, "ACTIVE": {"PAUSED"}, "COMPLETE": {"ACTIVE", "PAUSED"}}[want]
        if state not in allowed:
            fail(f"cannot {action} {vs}: its phased release is {state}")
        asc.call("PATCH", f"/appStoreVersionPhasedReleases/{p['id']}", body={"data": {
            "type": "appStoreVersionPhasedReleases", "id": p["id"], "attributes": {"phasedReleaseState": want}}})
        print(f"{vs}: phased release {state} -> {want}")
        state = want
    step_summary([f"### Phased release: {vs} ({number})", "",
                  f"- state: **{state}**, day {day} of 7 (~{pct}% of automatic updates)",
                  f"- total paused so far: {a.get('totalPauseDuration', 0)} day(s) (Apple allows 30)"])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["wait-build", "max-build-number", "add-to-group", "submit", "phased"])
    ap.add_argument("--version")
    ap.add_argument("--build")
    ap.add_argument("--timeout", type=int, default=3600)
    ap.add_argument("--group")
    ap.add_argument("--release-type", default="AFTER_APPROVAL", choices=["AFTER_APPROVAL", "MANUAL"])
    ap.add_argument("--whats-new-file")
    ap.add_argument("--action", default="status", choices=["status", "pause", "resume", "complete"])
    args = ap.parse_args()
    asc = ASC()
    need = lambda *names: [fail(f"--{n} is required for {args.command}") for n in names if not getattr(args, n)]
    if args.command == "wait-build":
        need("version", "build"); cmd_wait_build(asc, args.version, args.build, args.timeout)
    elif args.command == "max-build-number":
        cmd_max_build(asc)
    elif args.command == "add-to-group":
        need("version", "build", "group"); cmd_add_to_group(asc, args.version, args.build, args.group)
    elif args.command == "submit":
        need("version", "build"); cmd_submit(asc, args.version, args.build, args.release_type, args.whats_new_file)
    elif args.command == "phased":
        cmd_phased(asc, args.action, args.version or None)
    return 0


if __name__ == "__main__":
    sys.exit(main())
