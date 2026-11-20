#!/usr/bin/env python3
"""Generate committed launcher icons, or verify the files selected by builds."""

import argparse
from io import BytesIO
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"
CATALOG = ROOT / "ios/GrapMail/Assets.xcassets/AppIcon.appiconset"
MASTER = ROOT / "design/logo/launcher-envelope.svg"
BLUE = "#146DF5"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
NS = "http://schemas.android.com/apk/res/android"


def envelope(width):
    png = subprocess.check_output(
        ["rsvg-convert", "--width", str(width), "--height", str(width), str(MASTER)]
    )
    return Image.open(BytesIO(png)).convert("RGBA")


def tile(size, visible_size, background=None, monochrome=False):
    mark = envelope(round(visible_size * 264 / 432))
    if monochrome:
        alpha = mark.getchannel("A")
        mark = Image.new("RGBA", mark.size, "white")
        mark.putalpha(alpha)
    image = Image.new("RGBA", (size, size), background or (0, 0, 0, 0))
    left, top, right, bottom = mark.getbbox()
    image.alpha_composite(mark, ((size - left - right) // 2, (size - top - bottom) // 2))
    return image.convert("RGB") if background else image


def assets():
    for density, scale in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        # Android displays the central 72dp of the 108dp adaptive layers.
        for name, monochrome in (("foreground", False), ("monochrome", True)):
            yield folder / f"ic_launcher_{name}.png", tile(
                round(108 * scale), 72 * scale, monochrome=monochrome
            )
        for name in ("ic_launcher", "ic_launcher_round"):
            size = round(48 * scale)
            yield folder / f"{name}.png", tile(size, size, BLUE)
    yield CATALOG / "AppIcon-1024.png", tile(1024, 1024, BLUE)


def verify_wiring():
    manifest = ET.parse(RES.parent / "AndroidManifest.xml").find("application")
    for attr in ("icon", "roundIcon"):
        if manifest.get(f"{{{NS}}}{attr}") != "@mipmap/ic_launcher":
            raise ValueError(f"Manifest {attr} must use the generated launcher")
    for name in ("ic_launcher", "ic_launcher_round"):
        layers = ET.parse(RES / f"mipmap-anydpi-v26/{name}.xml").getroot()
        expected = {
            "background": "@drawable/ic_launcher_background",
            "foreground": "@mipmap/ic_launcher_foreground",
            "monochrome": "@mipmap/ic_launcher_monochrome",
        }
        if layers.tag != "adaptive-icon" or {
            entry.tag: entry.get(f"{{{NS}}}drawable") for entry in layers
        } != expected:
            raise ValueError(f"Wrong adaptive layers in {name}.xml")
    solid = ET.parse(RES / "drawable/ic_launcher_background.xml").find("solid")
    if solid.get(f"{{{NS}}}color") != f"#FF{BLUE[1:]}":
        raise ValueError("Adaptive background must match the generated blue")
    images = json.loads((CATALOG / "Contents.json").read_text())["images"]
    if images != [{
        "filename": "AppIcon-1024.png", "idiom": "universal",
        "platform": "ios", "size": "1024x1024",
    }]:
        raise ValueError("AppIcon must select the generated 1024px image")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    verify_wiring()
    stale = []
    for path, expected in assets():
        if not args.check:
            expected.save(path)
            continue
        if not path.is_file():
            stale.append(path)
            continue
        with Image.open(path) as actual:
            if actual.mode != expected.mode or actual.size != expected.size:
                stale.append(path)
                continue
            # Permit subpixel renderer rounding across librsvg platforms.
            extrema = ImageChops.difference(actual, expected).getextrema()
            if any(high > 4 for _, high in extrema):
                stale.append(path)
    if stale:
        for path in stale:
            print(f"Stale icon: {path.relative_to(ROOT)}")
        parser.exit(1, "Run python3 scripts/app-icons.py and commit the generated icons.\n")
    print("App icons verified." if args.check else "App icons generated.")


if __name__ == "__main__":
    main()
