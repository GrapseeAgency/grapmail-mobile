#!/usr/bin/env python3
"""Janky-frame percentage from `adb shell dumpsys gfxinfo <pkg>` output.

Usage:
  adb shell dumpsys gfxinfo com.grapsee.grapmail > /tmp/gfx.txt
  python3 quality/gfxinfo_jank.py /tmp/gfx.txt [--budgets quality/budgets.json]
Warn-only: always exits 0 unless the file has no frame data (exit 2 is NOT used
either - a missing number is a ::warning, so the job never goes red on jank).

Parsed lines (first "Graphics info" block for the app; Android 10+ format):
  Total frames rendered: 240
  Janky frames: 18 (7.50%)
  50th percentile: 9ms
  90th percentile: 14ms
  99th percentile: 31ms
Sample above -> 18/240 = 7.50% (<= 10% budget) -> ok.
"Janky frames: 40 (16.67%)" with 240 total -> warning.
If the percentage is absent it is recomputed from the two counts.
"""
import argparse, json, os, re, sys

def parse(text):
    r = {}
    m = re.search(r"Total frames rendered:\s*(\d+)", text);  r["total"] = int(m.group(1)) if m else None
    m = re.search(r"Janky frames:\s*(\d+)(?:\s*\(([\d.]+)%\))?", text)
    if m:
        r["janky"] = int(m.group(1))
        r["pct"] = float(m.group(2)) if m.group(2) else (100.0 * r["janky"] / r["total"] if r.get("total") else None)
    for p in ("50", "90", "95", "99"):
        m = re.search(p + r"th percentile:\s*(\d+)ms", text)
        if m: r[f"p{p}"] = int(m.group(1))
    return r

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--budgets", default="quality/budgets.json")
    a = ap.parse_args()
    warn_pct = json.load(open(a.budgets))["android_jank"]["janky_frames_warn_pct"]
    r = parse(open(a.file, errors="replace").read())
    summ = os.environ.get("GITHUB_STEP_SUMMARY")
    out = open(summ, "a") if summ else sys.stdout
    out.write("### Scroll smoothness (dumpsys gfxinfo)\n\n")
    if not r.get("total") or r.get("pct") is None:
        print("::warning title=jank::no frame data in gfxinfo output (did the list scroll?)")
        out.write("No frame data captured.\n\n"); return 0
    verdict = "ok" if r["pct"] <= warn_pct else "warn"
    out.write("| frames | janky | janky % | p50 | p90 | p99 | warn above | verdict |\n|---|---|---|---|---|---|---|---|\n")
    out.write(f"| {r['total']} | {r['janky']} | {r['pct']:.2f}% | {r.get('p50','-')}ms | {r.get('p90','-')}ms | {r.get('p99','-')}ms | {warn_pct}% | {verdict} |\n\n")
    print(f"janky {r['janky']}/{r['total']} = {r['pct']:.2f}% -> {verdict}")
    if verdict == "warn":
        print(f"::warning title=jank above {warn_pct}%::{r['pct']:.2f}% janky frames while scrolling the mailbox (emulator, software GPU)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
