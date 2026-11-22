#!/usr/bin/env python3
"""Generate the iOS AppIcon from the shared launcher mark. Requires Pillow.

Run from any directory: python3 scripts/make-ios-icon.py

Apple App Store / HIG rules applied here (system does the masking):
- Single 1024 x 1024 PNG, sRGB, opaque, full-bleed edge to edge.
- No transparency, no pre-rounded corners, no drop shadow.
- iOS applies the squircle mask (~22.37% continuous corner radius),
  so key artwork stays centered in the safe zone.
- Same stable palette as Android (navy #142234, cyan #5CD9EC) with
  the same centered silhouette, drawn larger (68% canvas width vs
  Android's 56 dp / 108 dp layer) for legibility — still inside the
  squircle safe zone.

Source of truth for the mark silhouette:
  assets/icons/launcher-mark-mask.png
Output (wired as ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon):
  ios/MyWhisper/Assets.xcassets/AppIcon.appiconset/Icon-1024.png
Xcode thins the 1024 px universal icon down to every required
iPhone/iPad/Spotlight/Settings size at build/archive time.
"""

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
MASK = ROOT / "assets/icons/launcher-mark-mask.png"
OUT = ROOT / "ios/MyWhisper/Assets.xcassets/AppIcon.appiconset/Icon-1024.png"
SIZE = 1024
BACKGROUND = (0x14, 0x22, 0x34)  # #142234 stable navy
FOREGROUND = (0x5C, 0xD9, 0xEC)  # #5CD9EC stable cyan
# Deliberately larger than Android's 56/108 adaptive geometry: a
# full-bleed iOS icon has no launcher mask shrinking the visible area,
# so 68% width stays inside the squircle safe zone while reading
# bigger at small sizes. Same silhouette, palette, and centering.
MARK_WIDTH_PX = round(SIZE * 0.68)


def main():
    with Image.open(MASK) as source:
        mark_height = round(MARK_WIDTH_PX * source.height / source.width)
        mark = source.resize((MARK_WIDTH_PX, mark_height), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (SIZE, SIZE), BACKGROUND)
    layer = Image.new("RGB", (MARK_WIDTH_PX, mark_height), FOREGROUND)
    canvas.paste(
        layer,
        ((SIZE - MARK_WIDTH_PX) // 2, (SIZE - mark_height) // 2),
        mark,
    )
    OUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUT, "PNG")
    print(f"wrote {OUT.relative_to(ROOT)} ({SIZE} x {SIZE}, opaque RGB)")


if __name__ == "__main__":
    main()
