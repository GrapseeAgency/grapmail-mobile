"""Measure the founder's account-recovery reference renders.

The twelve PNGs handed over for the recovery/security sector each carry two
phone panels side by side: the light theme on the left, the dark theme on the
right. This script finds both panels and reports the colours and geometry the
native screens are built from, so every value in design/DESIGN_TOKENS.md is
sampled from the artwork rather than guessed.

Run: python3 scripts/measure-account-screens.py
"""
import os
import sys
from PIL import Image

SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "raw-designs", "account")

FILES = [
    ("signin", "01-signin.png"),
    ("twofactor", "02-twofactor.png"),
    ("backup", "03-backup-code.png"),
    ("forgot-password", "04-forgot-password.png"),
    ("verify-recovery", "05-verify-recovery.png"),
    ("new-password", "06-new-password.png"),
    ("reset-success", "07-reset-success.png"),
    ("recovery-pending", "08-recovery-pending.png"),
    ("find-address", "09-find-address.png"),
    ("address-found", "10-address-found.png"),
    ("phone-verify", "11-phone-verify.png"),
    ("phone-verified", "12-phone-verified.png"),
]


def hexc(p):
    return "#%02X%02X%02X" % p[:3]


def scan_panels(im):
    """Find the two phone panels by walking the horizontal middle of the image."""
    w, h = im.size
    px = im.convert("RGB").load()
    y = h // 2
    runs, prev, start = [], None, 0
    for x in range(w):
        c = px[x, y]
        key = (c[0] // 8, c[1] // 8, c[2] // 8)
        if prev is None:
            prev, start = key, x
        elif key != prev:
            runs.append((start, x - 1))
            prev, start = key, x
    runs.append((start, w - 1))
    return [r for r in runs if r[1] - r[0] > 100]


def sample(im, x, y):
    return hexc(im.convert("RGB").load()[x, y])


def main():
    for name, f in FILES:
        path = os.path.join(SRC, f)
        if not os.path.exists(path):
            print("missing", path)
            continue
        im = Image.open(path)
        print("==", name, f, im.size)
        for a, b in scan_panels(im):
            mid = (a + b) // 2
            print("   panel x %d..%d  mid %s  bg %s" % (
                a, b, hexc(im.convert("RGB").load()[mid, im.size[1] // 2]),
                hexc(im.convert("RGB").load()[mid, 40])))


if __name__ == "__main__":
    main()
