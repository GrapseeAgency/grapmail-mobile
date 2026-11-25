# Launcher icons

Edit `launcher-envelope.svg` to change the native app icon. Its paths follow
the envelope silhouette in `../mono-clean-432.png`. The original Canva export
`grapmail-icon.svg` contains embedded bitmap artwork and remains a design
reference. The generator uses the reference's 264 px envelope width on a
432 px visible tile.

Android's adaptive foreground and monochrome images use the same centered
silhouette at about 44 dp wide on a 108 dp canvas. The launcher supplies the
mask over the central 72 dp and the separate blue background fills the layer.
The iOS AppIcon is a full-bleed, opaque 1024 px RGB image. iOS applies corner
rounding when it displays the icon.

Install `rsvg-convert` (`librsvg2-bin` on Ubuntu or `librsvg` on macOS) and
`Pillow==11.3.0` in a Python virtual environment. From the repository root run:

```sh
python3 scripts/app-icons.py
python3 scripts/app-icons.py --check
```

Commit the SVG and regenerated PNGs together. Android and iOS CI verify the
committed images before both test and release builds. Local Gradle and Xcode
builds use the same committed files without requiring the generator.
