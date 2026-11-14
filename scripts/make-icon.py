#!/usr/bin/env python3
"""Generate flat Android adaptive launcher layers. Requires Pillow.

Run from any directory: python3 scripts/make-icon.py
The checked-in mask preserves the original launcher mark's alpha silhouette.
Launcher shape is supplied by Android, so the background fills the entire layer.
Notification icons are separate assets and are not generated here.
"""

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
MASK = ROOT / "assets/icons/launcher-mark-mask.png"
RES = ROOT / "android/app/src/main/res"
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
BACKGROUND = "#142234"
FOREGROUND = "#5CD9EC"
LAYER_DP = 108
MARK_WIDTH_DP = 56


def main():
    # Render above the largest output density to retain centered subpixel edges.
    render_px = 864
    with Image.open(MASK) as source:
        mark_width = round(render_px * MARK_WIDTH_DP / LAYER_DP)
        mark_height = round(mark_width * source.height / source.width)
        mark = source.resize((mark_width, mark_height), Image.Resampling.LANCZOS)
    alpha = Image.new("L", (render_px, render_px))
    alpha.paste(mark, ((render_px - mark_width) // 2, (render_px - mark_height) // 2))

    for density, size in DENSITIES.items():
        destination = RES / f"mipmap-{density}"
        destination.mkdir(parents=True, exist_ok=True)
        foreground = Image.new("RGBA", (size, size), FOREGROUND)
        foreground.putalpha(alpha.resize((size, size), Image.Resampling.LANCZOS))
        foreground.save(destination / "ic_launcher_foreground.png")
        Image.new("RGB", (size, size), BACKGROUND).save(
            destination / "ic_launcher_background.png"
        )
        print(f"wrote {density} launcher layers ({size} × {size})")


if __name__ == "__main__":
    main()
