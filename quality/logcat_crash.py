#!/usr/bin/env python3
"""Find crashes and ANRs of one package in a logcat dump (and a monkey log).

Usage:
  python3 quality/logcat_crash.py LOGCAT.txt [--monkey MONKEY.txt] [--pkg com.grapsee.grapmail]
Exit 0 = clean, 1 = at least one crash/ANR (each printed as a GitHub ::error).
Also appends a markdown section to $GITHUB_STEP_SUMMARY when that is set.

What counts (logcat -v threadtime format):
  * "FATAL EXCEPTION" block whose "Process: <pkg>" line names the package
    (AndroidRuntime prints Process: on the line after FATAL EXCEPTION)
  * "ANR in <pkg>" (ActivityManager)
  * native crash: "*** *** ***" tombstone followed by ">>> <pkg> <<<"
  * monkey's own "// CRASH: <pkg>" or "// NOT RESPONDING: <pkg>"

Self-test with sample input (mentally verified, also: --selftest):
  11-23 14:00:01.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
  11-23 14:00:01.000  1234  1234 E AndroidRuntime: Process: com.grapsee.grapmail, PID: 1234
  11-23 14:00:01.000  1234  1234 E AndroidRuntime: java.lang.IllegalStateException: boom
  11-23 14:00:09.000   500   600 E ActivityManager: ANR in com.android.systemui
  11-23 14:00:10.000   500   600 E ActivityManager: ANR in com.grapsee.grapmail (com.grapsee.grapmail/.MainActivity)
  -> 2 findings: crash (IllegalStateException: boom) and ANR; systemui ignored.
"""
import argparse, os, re, sys

def scan(lines, pkg):
    found = []
    p = re.escape(pkg)
    for i, line in enumerate(lines):
        if "FATAL EXCEPTION" in line:
            window = lines[i + 1:i + 4]
            if any(re.search(r"Process: " + p + r"([,:\s]|$)", w) for w in window):
                cause = next((w.split(": ", 1)[-1].strip() for w in lines[i + 2:i + 6]
                              if re.search(r"(Exception|Error)\b", w) and "Process:" not in w), "unknown")
                found.append(("crash", cause, i + 1))
        elif re.search(r"ANR in " + p + r"(\s|\(|:|$)", line):
            found.append(("anr", line.split("ANR in ", 1)[1].strip(), i + 1))
        elif re.search(r">>> " + p + r"(:\S+)? <<<", line):
            found.append(("native-crash", line.strip()[-120:], i + 1))
    return found

def scan_monkey(lines, pkg):
    out = []
    for i, line in enumerate(lines):
        m = re.search(r"// (CRASH|NOT RESPONDING): (\S+)", line)
        if m and m.group(2).startswith(pkg):
            out.append(("monkey-" + m.group(1).lower().replace(" ", "-"), line.strip(), i + 1))
    return out

SAMPLE = """11-23 14:00:01.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
11-23 14:00:01.000  1234  1234 E AndroidRuntime: Process: com.grapsee.grapmail, PID: 1234
11-23 14:00:01.000  1234  1234 E AndroidRuntime: java.lang.IllegalStateException: boom
11-23 14:00:09.000   500   600 E ActivityManager: ANR in com.android.systemui
11-23 14:00:10.000   500   600 E ActivityManager: ANR in com.grapsee.grapmail (com.grapsee.grapmail/.MainActivity)
""".splitlines()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("logcat", nargs="?")
    ap.add_argument("--monkey")
    ap.add_argument("--pkg", default="com.grapsee.grapmail")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        r = scan(SAMPLE, a.pkg)
        assert [k for k, _, _ in r] == ["crash", "anr"], r
        assert "boom" in r[0][1], r
        print("selftest ok"); return 0
    lines = open(a.logcat, errors="replace").read().splitlines()
    found = scan(lines, a.pkg)
    if a.monkey and os.path.exists(a.monkey):
        found += scan_monkey(open(a.monkey, errors="replace").read().splitlines(), a.pkg)
    for kind, what, ln in found:
        print(f"::error title={kind} in {a.pkg}::{what[:300]} (line {ln})")
    summ = os.environ.get("GITHUB_STEP_SUMMARY")
    if summ:
        with open(summ, "a") as f:
            f.write(f"### Crash/ANR scan ({a.pkg})\n\n")
            if not found:
                f.write("No crash or ANR found in logcat.\n\n")
            else:
                f.write("| kind | detail | line |\n|---|---|---|\n")
                for kind, what, ln in found:
                    f.write(f"| {kind} | `{what[:150].replace('|', '/')}` | {ln} |\n")
                f.write("\n")
    print(f"{len(found)} crash/ANR finding(s)")
    return 1 if found else 0

if __name__ == "__main__":
    sys.exit(main())
