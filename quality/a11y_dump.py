#!/usr/bin/env python3
"""Lightweight accessibility lint over `uiautomator dump` XML files.

Usage:
  python3 quality/a11y_dump.py --density 420 SCREEN1.xml [SCREEN2.xml ...] [--pkg com.grapsee.grapmail]
  (density from `adb shell wm density`, the "Physical density: 420" number)
Warn-only: prints ::warning annotations and a summary table, always exits 0.

Checks, for nodes of the app package only:
  1. touch target: clickable/long-clickable/checkable node smaller than 48x48 dp
     (px -> dp = px * 160 / density). Nodes clipped by the screen edge can read small;
     those touching x=0 / top are still reported, it is a lint.
  2. no accessible name: clickable node with empty text AND empty content-desc AND
     no descendant carrying text/content-desc (Compose merges child text into
     the clickable parent, so a labelled child counts as a name).

Sample (density 420 -> 48dp = 126px):
  <node class="android.view.View" package="com.grapsee.grapmail" clickable="true"
        text="" content-desc="" bounds="[0,0][100,100]"/>
  -> 100px = 38dp: "small target 38x38dp" AND "no accessible name".
  <node ... clickable="true" text="" content-desc="" bounds="[0,200][1080,400]">
     <node text="Inbox" content-desc="" bounds="[40,250][300,350]"/></node>
  -> 1080x200px = 411x76dp, named by child -> no finding.
"""
import argparse, os, re, sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")

def has_name(n):
    if (n.get("text") or "").strip() or (n.get("content-desc") or "").strip():
        return True
    return any(has_name(c) for c in n.findall("node"))

def lint(path, density, pkg):
    out = []
    root = ET.parse(path).getroot()
    for n in root.iter("node"):
        if n.get("package") != pkg:
            continue
        actionable = any(n.get(k) == "true" for k in ("clickable", "long-clickable", "checkable"))
        if not actionable:
            continue
        m = BOUNDS.match(n.get("bounds") or "")
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        w, h = (x2 - x1) * 160 / density, (y2 - y1) * 160 / density
        label = (n.get("text") or n.get("content-desc") or n.get("resource-id") or n.get("class") or "?")[:40]
        if w <= 0 or h <= 0:
            continue
        if w < 48 or h < 48:
            out.append(("small-target", f"{label} is {w:.0f}x{h:.0f}dp at {n.get('bounds')}"))
        if not has_name(n):
            out.append(("no-name", f"{n.get('class')} at {n.get('bounds')} has no text/content-desc"))
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--density", type=float, required=True)
    ap.add_argument("--pkg", default="com.grapsee.grapmail")
    a = ap.parse_args()
    summ = os.environ.get("GITHUB_STEP_SUMMARY")
    out = open(summ, "a") if summ else sys.stdout
    out.write("### Android accessibility lint (uiautomator dump, warn-only)\n\n| screen | check | detail |\n|---|---|---|\n")
    total = 0
    for f in a.files:
        screen = os.path.splitext(os.path.basename(f))[0]
        try:
            found = lint(f, a.density, a.pkg)
        except ET.ParseError as e:
            print(f"::warning title=a11y::{screen}: dump not parseable ({e})"); continue
        for kind, detail in found:
            total += 1
            out.write(f"| {screen} | {kind} | {detail.replace('|', '/')} |\n")
            print(f"::warning title=a11y {kind} ({screen})::{detail}")
    out.write(f"\n{total} finding(s) across {len(a.files)} screen(s).\n\n")
    print(f"{total} a11y finding(s)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
