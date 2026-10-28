#!/usr/bin/env python3
"""Re-crop artwork-only illustrations from raw-designs/ into drawable-nodpi/.

The previous agent's crops (ill_hero, ill_step1_name, ill_verify,
ill_created_a, ill_created_b) carried baked-in headlines, steppers,
wordmarks and phone bezels, so every screen rendered that text twice
(native Text + baked bitmap). This script crops the founder's artwork
regions only. The puzzle photo gets its two baked notch outlines patched
out — the code draws the real, randomized notch.

Boxes are measured against the raw-design renders; the script prints
each crop for visual verification before anything is wired in.
"""
from PIL import Image
import pathlib

RD = pathlib.Path('raw-designs')
OUT = pathlib.Path('android/app/src/main/res/drawable-nodpi')


def crop(src, box, dst):
    im = Image.open(RD / src).convert('RGBA')
    piece = im.crop(box)
    piece.save(OUT / dst)
    print(f'{dst}: {src} {box} -> {piece.size}')


def patch_notches(src, dst, regions, strip):
    """Cover baked notch outlines by copying clean scenery (explicit source
    boxes, feathered edges), then crop the strip."""
    from PIL import ImageFilter
    im = Image.open(RD / src).convert('RGBA')
    for x0, y0, x1, y1, sx0, sy0 in regions:
        w, h = x1 - x0, y1 - y0
        src_box = (sx0, sy0, sx0 + w, sy0 + h)
        patch = im.crop(src_box).convert('RGB')
        # feathered mask: solid in the middle, blended at the edges
        mask = Image.new('L', (w, h), 255)
        feather = 8
        for i in range(feather):
            a = int(255 * i / feather)
            for edge in range(4):
                if edge == 0:
                    mask.paste(a, (0, i, w, i + 1))
                elif edge == 1:
                    mask.paste(a, (0, h - 1 - i, w, h - i))
                elif edge == 2:
                    mask.paste(a, (i, 0, i + 1, h))
                else:
                    mask.paste(a, (w - 1 - i, 0, w - i, h))
        im.paste(patch, (x0, y0), mask)
    piece = im.crop(strip)
    piece.save(OUT / dst)
    print(f'{dst}: {src} notch-patched strip {strip} -> {piece.size}')


# ── welcome hero dark variant: the shipped crop carried the "Better Email."
#    tagline baked in below the envelope. welcome-R is the "Better Email"
#    phone mockup — the envelope artwork sits at y 240-485. The light
#    ill_welcome.png is already artwork-only and stays as shipped. ──
crop('welcome-R.png', (50, 240, 450, 485), 'ill_welcome_dark.png')

# ── step1 name: person-artwork blob only (below the stepper, above fields) ──
crop('step1-name-L.png', (60, 455, 480, 680), 'ill_step1_name.png')
crop('step1-name-R.png', (60, 455, 490, 690), 'ill_step1_name_dark.png')

# ── human verify: shield blob incl. the Human Verification badge (artwork),
#    excluding back arrow / wordmark / eyebrow ──
crop('human-verify-L.png', (60, 125, 520, 355), 'ill_verify.png')
crop('human-verify-R.png', (60, 125, 520, 355), 'ill_verify_dark.png')

# ── created A: envelope+check with confetti blob, no eyebrow bleed ──
crop('created-a-L.png', (70, 125, 530, 358), 'ill_created_a.png')
crop('created-a-R.png', (70, 125, 530, 358), 'ill_created_a_dark.png')

# ── created B: glowing envelope from inside the phone frame ──
crop('created-b-L.png', (340, 150, 545, 305), 'ill_created_b.png')
crop('created-b-R.png', (350, 150, 555, 305), 'ill_created_b_dark.png')

# ── puzzle photo: strip from the design, notch outlines patched away ──
#    strip x57-485 y663-785; left notch ~x60-140, right notch ~x350-440.
#    Sources are clean scenery columns of equal width, no other outlines.
patch_notches('human-verify-L.png', 'puzzle_photo.png',
              [(50, 650, 150, 800, 150, 650), (340, 650, 450, 800, 240, 650)],
              (57, 663, 485, 785))
patch_notches('human-verify-R.png', 'puzzle_photo_dark.png',
              [(50, 650, 165, 800, 165, 650), (340, 650, 455, 800, 235, 650)],
              (57, 663, 485, 785))

# ── mirror the clean crops into the iOS asset catalogs ──
#    (Contents.json references underscore names; the catalogs shipped hyphen
#    orphans, so Xcode never found the light art. Write the referenced name,
#    drop the orphan.)
import shutil
CAT = pathlib.Path('ios/GrapMail/Assets.xcassets')
for name, imgset, orphan in [
    ('ill_step1_name', 'ill_step1_name.imageset', 'ill-step1-name.png'),
    ('ill_verify', 'ill_verify.imageset', None),
    ('ill_created_a', 'ill_created_a.imageset', 'ill-created-a.png'),
    ('ill_created_b', 'ill_created_b.imageset', 'ill-created-b.png'),
    ('puzzle_photo', 'puzzle_photo.imageset', 'puzzle-photo.png'),
    ('ill_welcome', 'ill_welcome.imageset', None),
]:
    shutil.copy(OUT / f'{name}.png', CAT / imgset / f'{name}.png')
    shutil.copy(OUT / f'{name}_dark.png', CAT / imgset / 'dark.png')
    if orphan and (CAT / imgset / orphan).exists():
        (CAT / imgset / orphan).unlink()
    print(f'ios {imgset}: refreshed')

print('done')
