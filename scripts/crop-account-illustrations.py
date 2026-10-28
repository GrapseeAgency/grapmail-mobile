"""Cut the recovery/security illustrations out of the founder's renders.

Same rule as scripts/recrop-illustrations.py: the artwork is cropped from the
original PNGs, never redrawn, and every screen gets a paired light/dark crop
because the founder ships both themes.

Each source render is 1536x1024 with two phone panels side by side (light left,
dark right). Finding the artwork is the interesting part:

  1. Everything that differs from the panel's own background becomes a mask.
  2. The mask is eroded, which deletes the copy - letters are two or three
     pixels wide - while the illustration's shapes survive.
  3. The largest surviving blob is the artwork; its bounding box is the crop.

The crop is then colour-shifted so its background is exactly the app's
background token. Without that, the dark panels' near-black (#000B14) reads as a
faint rectangle against the app's #070F18, and a visible seam is the one thing a
"cropped from the original" asset must never show.

Run: python3 scripts/crop-account-illustrations.py
"""
import os
import pathlib
from collections import deque

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "raw-designs", "account")
OUT = os.path.join(ROOT, "assets")
ANDROID = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable-nodpi")
IOS = pathlib.Path(ROOT) / "ios" / "GrapMail" / "Assets.xcassets"

APP_BG = {"L": (0xFB, 0xFC, 0xFE), "R": (0x07, 0x0F, 0x18)}
PANELS = {"L": (185, 755), "R": (795, 1365)}
BAND = (95, 450)

SHOTS = [
    ("ill_account_2fa", "02-twofactor.png"),
    ("ill_account_backup", "03-backup-code.png"),
    ("ill_account_forgot", "04-forgot-password.png"),
    ("ill_account_recovery_code", "05-verify-recovery.png"),
    ("ill_account_new_password", "06-new-password.png"),
    ("ill_account_reset_ok", "07-reset-success.png"),
    ("ill_account_pending", "08-recovery-pending.png"),
    ("ill_account_find_address", "09-find-address.png"),
    ("ill_account_address_found", "10-address-found.png"),
    ("ill_account_phone", "11-phone-verify.png"),
    ("ill_account_phone_ok", "12-phone-verified.png"),
]

# Two dark panels defeat the detector: their radial glow is a large, connected
# region, so "the biggest thing" is the glow and the crop reaches the wordmark
# above and the stepper below. Measured by hand off the grid overlay instead.
OVERRIDES = {
    ("ill_account_find_address", "R"): (900, 178, 1225, 402),
    ("ill_account_phone", "R"): (868, 156, 1184, 374),
}

# Light panels sit the artwork on a pale glow, so the threshold has to clear the
# glow or the "largest blob" is the glow itself. The dark panels' glow is
# already far from black, so a lower bar keeps the glass rim in one piece.
DIST_MIN = {"L": 62, "R": 46}
ERODE = 3          # kills glyph strokes (2-4px) but keeps shapes
OUT_WIDTH = 520


def panel_background(px, x0, x1):
    """The panel's own backdrop, sampled from the empty margins."""
    patch = px[BAND[0]:BAND[0] + 24, x0:x1]
    return np.median(patch.reshape(-1, 3), axis=0)


def erode(mask, k):
    out = mask.copy()
    for dy in range(-k, k + 1):
        for dx in range(-k, k + 1):
            if dx * dx + dy * dy > k * k:
                continue
            out &= np.roll(np.roll(mask, dy, axis=0), dx, axis=1)
    return out


def blobs(mask):
    """Bounding boxes of the 4-connected components of a boolean mask.

    One blob is not enough: the artwork is several pieces - a shield, and the
    badge floating beside it - and a white check on a white page is invisible to
    a colour-distance mask, so the pieces never join. The five largest are
    returned and the caller unions them, which also drops stray specks.
    """
    seen = np.zeros_like(mask, dtype=bool)
    found = []
    h, w = mask.shape
    for y in range(h):
        row = mask[y]
        for x in np.where(row & ~seen[y])[0]:
            if seen[y, x]:
                continue
            q = deque([(y, x)])
            seen[y, x] = True
            n = 0
            minx = maxx = x
            miny = maxy = y
            while q:
                cy, cx = q.pop()
                n += 1
                minx, maxx = min(minx, cx), max(maxx, cx)
                miny, maxy = min(miny, cy), max(maxy, cy)
                for ny, nx in ((cy - 1, cx), (cy + 1, cx), (cy, cx - 1), (cy, cx + 1)):
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        q.append((ny, nx))
            if n >= 40:
                found.append((n, (minx, miny, maxx + 1, maxy + 1)))
    found.sort(reverse=True)
    return found[:6]


def main():
    os.makedirs(OUT, exist_ok=True)
    tiles = []
    for name, fname in SHOTS:
        im = Image.open(os.path.join(SRC, fname)).convert("RGB")
        px = np.asarray(im).astype(int)
        for tag, (x0, x1) in PANELS.items():
            bg = panel_background(px, x0, x1)
            band = px[BAND[0]:BAND[1], x0:x1]
            dist = np.abs(band - bg).max(axis=2)
            core = erode(dist > DIST_MIN[tag], ERODE)
            found = blobs(core)
            if not found:
                print("!! nothing found for", name, tag)
                continue
            # Start from the artwork itself, then absorb pieces within one
            # short hop (the floating badge). The glow is not chased: a couple of
            # hops would reach the wordmark above and the stepper below, and a
            # crop with either baked in renders that text twice.
            n, box = found[0]
            cx0, cy0, cx1, cy1 = box
            for n2, b in found:
                near = (b[0] <= cx1 + 34 and b[2] >= cx0 - 34 and
                        b[1] <= cy1 + 34 and b[3] >= cy0 - 34)
                if n2 >= max(40, n * 0.05) and near:
                    cx0, cy0 = min(cx0, b[0]), min(cy0, b[1])
                    cx1, cy1 = max(cx1, b[2]), max(cy1, b[3])

            manual = (name, tag) in OVERRIDES
            if manual:
                ax0, ay0, ax1, ay1 = OVERRIDES[(name, tag)]
                cx0, cy0, cx1, cy1 = ax0 - x0, ay0 - BAND[0], ax1 - x0, ay1 - BAND[0]

            # Frame every screen the same way: a fixed margin around the shapes,
            # then padded out to one aspect ratio, so the twelve screens share a
            # rhythm instead of each artwork dictating its own height.
            margin = 0 if manual else 30
            cx0, cy0 = cx0 - margin, cy0 - margin
            cx1, cy1 = cx1 + margin, cy1 + margin
            aspect = 1.45
            w, h = cx1 - cx0, cy1 - cy0
            if not manual and w / h < aspect:
                extra = int(h * aspect - w)
                cx0, cx1 = cx0 - extra // 2, cx1 + extra - extra // 2
            elif not manual:
                extra = int(w / aspect - h)
                cy0, cy1 = cy0 - extra // 2, cy1 + extra - extra // 2

            canvas = np.zeros((cy1 - cy0, cx1 - cx0, 3), dtype=int)
            canvas[:, :] = bg
            sx0, sy0 = max(0, cx0), max(0, cy0)
            sx1, sy1 = min(x1 - x0, cx1), min(BAND[1] - BAND[0], cy1)
            canvas[sy0 - cy0:sy1 - cy0, sx0 - cx0:sx1 - cx0] = band[sy0:sy1, sx0:sx1]
            piece = canvas
            # Re-base the crop onto the app's background token.
            shift = np.array(APP_BG[tag]) - bg
            piece = np.clip(piece + shift, 0, 255).astype(np.uint8)
            tile = Image.fromarray(piece)
            w = OUT_WIDTH
            h = round(tile.height * w / tile.width)
            tile = tile.resize((w, h), Image.LANCZOS)
            # Feather the edges. The artwork's glow is cut off by the crop, and
            # an abrupt end to a gradient is a visible rectangle even when the
            # background colour matches to the byte. A soft alpha ramp lets the
            # screen's own background finish the falloff.
            alpha = Image.new("L", (w, h), 0)
            ImageDraw.Draw(alpha).rounded_rectangle(
                [0, 0, w - 1, h - 1], radius=int(min(w, h) * 0.20), fill=255
            )
            alpha = alpha.filter(ImageFilter.GaussianBlur(int(min(w, h) * 0.10)))
            tile = tile.convert("RGBA")
            tile.putalpha(alpha)
            suffix = "" if tag == "L" else "-dark"
            tile.save(os.path.join(OUT, name + suffix + ".png"))
            tiles.append((name + suffix, tile.copy()))
            print("%-32s %4dx%-4d  box=(%d,%d,%d,%d)" % (
                name + suffix, w, h, x0 + cx0, BAND[0] + cy0, x0 + cx1, BAND[0] + cy1))

    sheet = Image.new("RGB", (600, sum(t.height + 30 for _, t in tiles) + 10), (246, 248, 251))
    d = ImageDraw.Draw(sheet)
    y = 6
    for label, tile in tiles:
        d.text((8, y), label, fill=(20, 26, 40))
        sheet.paste(tile, (40, y + 12))
        y += tile.height + 30
    sheet.save(os.path.join(OUT, "_account-contact-sheet.png"))
    print("contact sheet ->", os.path.join(OUT, "_account-contact-sheet.png"))

    # Both apps ship the same pixels: Android reads drawable-nodpi by resource
    # name, iOS reads an image set whose dark slot carries the paired render.
    for name, _ in SHOTS:
        light = Image.open(os.path.join(OUT, name + ".png"))
        dark = Image.open(os.path.join(OUT, name + "-dark.png"))
        light.save(os.path.join(ANDROID, name + ".png"))
        dark.save(os.path.join(ANDROID, name + "_dark.png"))
        imageset = IOS / (name + ".imageset")
        os.makedirs(imageset, exist_ok=True)
        light.save(imageset / (name + ".png"))
        dark.save(imageset / "dark.png")
        (imageset / "Contents.json").write_text(CONTENTS.format(name=name))
        print("shipped", name)


CONTENTS = '''{{
  "images" : [
    {{
      "filename" : "{name}.png",
      "idiom" : "universal",
      "scale" : "1x"
    }},
    {{
      "appearances" : [
        {{
          "appearance" : "luminosity",
          "value" : "dark"
        }}
      ],
      "filename" : "dark.png",
      "idiom" : "universal",
      "scale" : "1x"
    }}
  ],
  "info" : {{
    "author" : "xcode",
    "version" : 1
  }}
}}
'''


if __name__ == "__main__":
    main()
