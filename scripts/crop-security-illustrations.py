"""Cut the security sector's artwork out of the founder's renders.

Same rule as crop-account-illustrations.py: the artwork is cropped from the
original PNGs, never redrawn, and every screen gets a paired light/dark crop
because the founder ships both themes.

The geometry differs from the earlier twelve: those renders are 1536x1024 with
the phone panels at 185..755 and 795..1365, while this batch is mostly
1312x1199 with the panels at 47..633 and 679..1265. The two odd sizes
(1214x1295 and 1536x1024) are scaled from those numbers rather than special
cased, so a future re-export at another size still lands.

Detection is the same three steps as the account set: everything unlike the
panel's own background becomes a mask, the mask is eroded so text disappears
while shapes survive, and the largest surviving blob is the crop. Screens whose
"artwork" is a small badge get a square frame instead of a sliver, because a
crop the width of an icon reads as a printing error.

Run: python3 scripts/crop-security-illustrations.py
"""
import glob
import json
import os
import pathlib
import sys
from collections import deque

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "..", ".local", "refs-security")
OUT = os.path.join(ROOT, "assets")
ANDROID = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable-nodpi")
IOS = pathlib.Path(ROOT) / "ios" / "GrapMail" / "Assets.xcassets"

APP_BG = {"L": (0xFB, 0xFC, 0xFE), "R": (0x07, 0x0F, 0x18)}
DIST_MIN = {"L": 55, "R": 45}
ERODE = 3
OUT_WIDTH = 460

SHOTS = [
    ("ill_sec_hub", "01-account-security-board.png"),
    ("ill_sec_hub_secure", "02-account-security-secure.png"),
    ("ill_sec_2fa", "03-two-step-enable.png"),
    ("ill_sec_password_confirm", "04-confirm-password.png"),
    ("ill_sec_phone", "05-verify-phone.png"),
    ("ill_sec_2fa_on", "06-two-step-enabled.png"),
    ("ill_sec_backup_generate", "07-generate-backup-codes.png"),
    ("ill_sec_backup_saved", "08-backup-codes-saved.png"),
    ("ill_sec_2fa_off_confirm", "09-disable-two-step.png"),
    ("ill_sec_2fa_off", "10-two-step-disabled.png"),
    ("ill_sec_password", "11-change-password.png"),
    ("ill_sec_password_ok", "12-password-changed.png"),
    ("ill_sec_signout_all", "13-sign-out-all-devices.png"),
    ("ill_sec_signout_all_ok", "14-signed-out-all-devices.png"),
    ("ill_sec_sessions", "15-manage-active-sessions.png"),
    ("ill_sec_session_revoke", "16-revoke-session.png"),
    ("ill_sec_session_revoked", "17-session-revoked.png"),
    ("ill_sec_activity", "18-recent-security-activity.png"),
    ("ill_sec_alert", "19-suspicious-signin-alert.png"),
    ("ill_sec_secure", "20-secure-my-account.png"),
    ("ill_sec_revoke_confirm", "21-confirm-session-revocation.png"),
    ("ill_sec_revoked_suspicious", "22-session-revoked-suspicious.png"),
    ("ill_sec_password_after", "23-change-password-after-suspicious.png"),
    ("ill_sec_password_updated", "24-password-updated.png"),
]

# Screens whose "artwork" is a badge above the title, not a scene. They are cut
# tight — a square frame around an icon that sits beside a 28pt heading drags the
# heading into the crop, and the screen then prints its own title twice.
BADGES = {
    "ill_sec_hub", "ill_sec_hub_secure", "ill_sec_2fa_off_confirm", "ill_sec_password",
    "ill_sec_signout_all", "ill_sec_sessions", "ill_sec_session_revoke", "ill_sec_activity",
    "ill_sec_revoke_confirm", "ill_sec_password_after", "ill_sec_secure",
}

CONTENTS = {
    "images": [
        {"filename": "{name}.png", "idiom": "universal", "scale": "1x"},
        {
            "appearances": [{"appearance": "luminosity", "value": "dark"}],
            "filename": "dark.png",
            "idiom": "universal",
            "scale": "1x",
        },
    ],
    "info": {"author": "xcode", "version": 1},
}


def panels(width, height):
    """Where the two phone panels sit, for this canvas size."""
    if width >= 1500 and height < 1100:
        return {"L": (185, 755), "R": (795, 1365)}
    # The 1312x1199 layout, scaled for the two renders that are a different size.
    scale = width / 1312.0
    return {
        "L": (round(47 * scale), round(633 * scale)),
        "R": (round(679 * scale), round(min(1265 * scale, width - 1))),
    }


def erode(mask, k):
    out = mask.copy()
    for dy in range(-k, k + 1):
        for dx in range(-k, k + 1):
            if dx * dx + dy * dy > k * k:
                continue
            out &= np.roll(np.roll(mask, dy, axis=0), dx, axis=1)
    return out


def blobs(mask):
    seen = np.zeros_like(mask, dtype=bool)
    found = []
    h, w = mask.shape
    for y in range(h):
        for x in np.where(mask[y] & ~seen[y])[0]:
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
            if n >= 60:
                found.append((n, (minx, miny, maxx + 1, maxy + 1)))
    found.sort(reverse=True)
    return found[:6]


def main():
    if not os.path.isdir(SRC):
        sys.exit("missing reference folder: " + SRC)
    os.makedirs(OUT, exist_ok=True)
    tiles = []

    for name, fname in SHOTS:
        path = os.path.join(SRC, fname)
        if not os.path.exists(path):
            print("!! missing", path)
            continue
        im = Image.open(path).convert("RGB")
        px = np.asarray(im).astype(int)
        height, width, _ = px.shape
        band_top, band_bottom = int(height * 0.09), int(height * 0.365)

        for tag, (x0, x1) in panels(width, height).items():
            sample = px[band_top:band_top + 16, x0:x1]
            bg = np.median(sample.reshape(-1, 3), axis=0)
            band = px[band_top:band_bottom, x0:x1]
            dist = np.abs(band - bg).max(axis=2)
            found = blobs(erode(dist > DIST_MIN[tag], ERODE))
            if not found:
                print("!! nothing found for", name, tag)
                continue

            n, box = found[0]
            cx0, cy0, cx1, cy1 = box
            for n2, b in found:
                near = (b[0] <= cx1 + 16 and b[2] >= cx0 - 16 and
                        b[1] <= cy1 + 16 and b[3] >= cy0 - 16)
                if n2 >= max(60, n * 0.05) and near:
                    cx0, cy0 = min(cx0, b[0]), min(cy0, b[1])
                    cx1, cy1 = max(cx1, b[2]), max(cy1, b[3])

            badge = name in BADGES
            margin = 18 if badge else 24
            cx0, cy0 = cx0 - margin, cy0 - margin
            cx1, cy1 = cx1 + margin, cy1 + margin
            out_width = 240 if badge else OUT_WIDTH

            canvas = np.zeros((cy1 - cy0, cx1 - cx0, 3), dtype=int)
            canvas[:, :] = bg
            sx0, sy0 = max(0, cx0), max(0, cy0)
            sx1 = min(x1 - x0, cx1)
            sy1 = min(band_bottom - band_top, cy1)
            canvas[sy0 - cy0:sy1 - cy0, sx0 - cx0:sx1 - cx0] = band[sy0:sy1, sx0:sx1]

            # Re-base onto the app's own background so no rectangle seam shows.
            piece = np.clip(canvas + (np.array(APP_BG[tag]) - bg), 0, 255).astype(np.uint8)
            tile = Image.fromarray(piece)
            w = out_width
            h = round(tile.height * w / tile.width)
            tile = tile.resize((w, max(1, h)), Image.LANCZOS)

            alpha = Image.new("L", (w, max(1, h)), 0)
            ImageDraw.Draw(alpha).rounded_rectangle(
                [0, 0, w - 1, max(1, h) - 1], radius=int(min(w, h) * 0.20), fill=255
            )
            alpha = alpha.filter(ImageFilter.GaussianBlur(int(min(w, h) * 0.10)))
            tile = tile.convert("RGBA")
            tile.putalpha(alpha)

            suffix = "" if tag == "L" else "-dark"
            tile.save(os.path.join(OUT, name + suffix + ".png"))
            tiles.append((name + suffix, tile.copy()))
            print("%-34s %4dx%-4d box=(%d,%d,%d,%d)" % (
                name + suffix, w, h, x0 + cx0, band_top + cy0, x0 + cx1, band_top + cy1))

    if tiles:
        sheet = Image.new("RGB", (560, sum(t.height + 26 for _, t in tiles) + 10), (246, 248, 251))
        d = ImageDraw.Draw(sheet)
        y = 6
        for label, tile in tiles:
            d.text((8, y), label, fill=(20, 26, 40))
            sheet.paste(tile, (30, y + 12))
            y += tile.height + 26
        sheet.save(os.path.join(OUT, "_security-contact-sheet.png"))
        print("contact sheet ->", os.path.join(OUT, "_security-contact-sheet.png"))

    for name, _ in SHOTS:
        light_path = os.path.join(OUT, name + ".png")
        dark_path = os.path.join(OUT, name + "-dark.png")
        if not (os.path.exists(light_path) and os.path.exists(dark_path)):
            continue
        light = Image.open(light_path)
        dark = Image.open(dark_path)
        light.save(os.path.join(ANDROID, name + ".png"))
        dark.save(os.path.join(ANDROID, name + "_dark.png"))
        imageset = IOS / (name + ".imageset")
        os.makedirs(imageset, exist_ok=True)
        light.save(imageset / (name + ".png"))
        dark.save(imageset / "dark.png")
        contents = json.loads(json.dumps(CONTENTS).replace("{name}", name))
        (imageset / "Contents.json").write_text(json.dumps(contents, indent=2))
        print("shipped", name)


if __name__ == "__main__":
    main()
