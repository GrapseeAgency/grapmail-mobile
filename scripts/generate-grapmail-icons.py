"""Generate native launchers from the approved, full-bleed platform exports."""
from argparse import ArgumentParser
from pathlib import Path
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "design"
RES = ROOT / "android/app/src/main/res"
IOS = ROOT / "ios/GrapMail/Assets.xcassets/AppIcon.appiconset"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
RESAMPLE = Image.Resampling.LANCZOS


def original(name, size):
    with Image.open(DESIGN / name) as image:
        rgba = image.convert("RGBA")
        if rgba.size != (size, size) or rgba.getchannel("A").getextrema() != (255, 255):
            raise ValueError(f"{name} must be an opaque {size}px square")
        return rgba.convert("RGB")


def adaptive(tile, size):
    # The platform normally displays the central 72dp of the 108dp
    # layer. Extend the original blue edge pixels into the motion bleed.
    inset = (size - tile.width) // 2
    canvas = Image.new("RGB", (size, size))
    canvas.paste(tile, (inset, inset))
    for edge, x in ((0, 0), (tile.width - 1, inset + tile.width)):
        strip = tile.crop((edge, 0, edge + 1, tile.height))
        canvas.paste(strip.resize((inset, tile.height)), (x, inset))
    for edge, y in ((inset, 0), (inset + tile.height - 1, inset + tile.height)):
        strip = canvas.crop((0, edge, size, edge + 1))
        canvas.paste(strip.resize((size, inset)), (0, y))
    return canvas


def icons():
    android = original("grapmail-android-approved-512.png", 512)
    ios = original("grapmail-ios-approved-1024.png", 1024)
    with Image.open(DESIGN / "mono-clean-432.png") as original_mask:
        silhouette = original_mask.convert("RGBA").getchannel("A")
        silhouette = silhouette.crop(silhouette.getbbox())
    for name, scale in DENSITIES.items():
        folder = RES / f"mipmap-{name}"
        visible = int(72 * scale)
        layer = int(108 * scale)
        tile = android.resize((visible, visible), RESAMPLE)
        yield folder / "ic_launcher_foreground.png", adaptive(tile, layer)
        width = round(visible * 0.63)
        height = round(width * silhouette.height / silhouette.width)
        mask = Image.new("L", (layer, layer))
        mask.paste(silhouette.resize((width, height), RESAMPLE),
                   ((layer - width) // 2, (layer - height) // 2))
        mono = Image.new("RGBA", (layer, layer), "white")
        mono.putalpha(mask)
        yield folder / "ic_launcher_monochrome.png", mono
        legacy = int(48 * scale)
        for name in ("ic_launcher", "ic_launcher_round"):
            yield folder / f"{name}.png", android.resize((legacy, legacy), RESAMPLE)
    yield IOS / "AppIcon-1024.png", ios


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    stale = []
    for path, expected in icons():
        if args.check:
            if not path.exists():
                stale.append(path)
                continue
            with Image.open(path) as actual:
                if (actual.mode != expected.mode or actual.size != expected.size
                        or any(high for _, high in ImageChops.difference(actual, expected).getextrema())):
                    stale.append(path)
        else:
            expected.save(path)
    if stale:
        parser.exit(1, "Stale icons:\n" + "\n".join(str(p.relative_to(ROOT)) for p in stale) + "\n")
    print("Native icons verified." if args.check else "Native icons generated.")


if __name__ == "__main__":
    main()
