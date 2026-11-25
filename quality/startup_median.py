#!/usr/bin/env python3
"""Median of `adb shell am start -W` TotalTime values, compared with a budget.

Usage:
  python3 quality/startup_median.py --kind cold --file /tmp/cold.txt [--budgets quality/budgets.json]
The file is the concatenated output of N `am start -W` runs. Each run prints e.g.
  Status: ok
  LaunchState: COLD
  Activity: com.grapsee.grapmail/.MainActivity
  TotalTime: 812
  WaitTime: 815
Only TotalTime lines are used (WaitTime adds am's own overhead).

Verdict:  median <= budget            -> ok
          budget < median <= budget*(1+tolerance) -> ::warning, exit 0
          median >  budget*(1+tolerance)          -> ::error,   exit 1
Sample: TotalTime 900,1100,1000,2100,950 -> sorted 900,950,1000,1100,2100 -> median 1000.
        Even count 900,1000,1100,1300 -> (1000+1100)/2 = 1050.
        cold budget 2000, tolerance 0.25: 2400 warns, 2600 fails.
"""
import argparse, json, os, re, statistics, sys

def parse(text):
    return [int(m) for m in re.findall(r"^\s*TotalTime:\s*(\d+)", text, re.M)]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kind", required=True, choices=["cold", "warm"])
    ap.add_argument("--file", required=True)
    ap.add_argument("--budgets", default="quality/budgets.json")
    a = ap.parse_args()
    b = json.load(open(a.budgets))["android_startup"]
    budget = b[f"{a.kind}_ms"]; tol = b.get("fail_tolerance", 0.25)
    vals = parse(open(a.file, errors="replace").read())
    if not vals:
        print(f"::error title={a.kind} start::no TotalTime values found in {a.file}")
        return 1
    med = statistics.median(vals)
    limit = budget * (1 + tol)
    verdict = "ok" if med <= budget else ("warn" if med <= limit else "FAIL")
    line = f"| {a.kind} | {len(vals)} | {min(vals)} | {med:.0f} | {max(vals)} | {budget} | {limit:.0f} | {verdict} |"
    print(f"{a.kind}: runs={vals} median={med:.0f}ms budget={budget}ms fail>{limit:.0f}ms -> {verdict}")
    summ = os.environ.get("GITHUB_STEP_SUMMARY")
    if summ:
        with open(summ, "a") as f:
            f.write(line + "\n")
    if verdict == "warn":
        print(f"::warning title={a.kind} start over budget::median {med:.0f} ms > {budget} ms (fails above {limit:.0f} ms)")
    if verdict == "FAIL":
        print(f"::error title={a.kind} start over budget::median {med:.0f} ms > {limit:.0f} ms ({budget} ms +{int(tol*100)}%)")
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
