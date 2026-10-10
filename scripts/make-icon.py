#!/usr/bin/env python3
# Build Android launcher PNGs from the designed desktop icon
# (assets/icons/app-icon.svg in the MyWhisper repo). No hand-drawn
# paths: the mark is rendered straight from source with rsvg, fitted
# into the adaptive-icon safe zone. Re-run after icon redesigns.
import os
import re
import subprocess

DESK = (
    "/home/aaronroque/.t3/worktrees/MyWhisper/t3code-8d7dc4a2/assets/icons/app-icon.svg"
)
RES = "/home/aaronroque/Projects/MyWhisperMobile/android/app/src/main/res"
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# Mark target: 62dp wide, centered (safe for circle masks).
MARK_W_DP = 62.0


def main():
    svg = open(DESK).read()
    mark = re.search(r"<g fill=\"url\(#mark\)\">.*?</g>", svg, re.S).group(0)
    grads = re.search(r"<defs>.*?</defs>", svg, re.S).group(0)
    # Mark bbox in 1254 space (bars + swoosh).
    x0, x1, y0, y1 = 195.0, 1058.0, 273.0, 953.0
    s = MARK_W_DP / (x1 - x0)
    w_dp = 108.0
    # Center the scaled mark in a 108dp square.
    dx = w_dp / 2 - (x0 + x1) / 2 * s
    dy = w_dp / 2 - (y0 + y1) / 2 * s
    fg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" '
        f'width="864" height="864" fill="none" shape-rendering="geometricPrecision">'
        f"{grads}"
        f'<g transform="translate({dx:.3f},{dy:.3f}) scale({s:.5f})">{mark}</g>'
        f"</svg>"
    )
    os.makedirs("/tmp/mwicon", exist_ok=True)
    open("/tmp/mwicon/fg.svg", "w").write(fg)
    for name, px in DENSITIES.items():
        d = f"{RES}/mipmap-{name}"
        os.makedirs(d, exist_ok=True)
        out = f"{d}/ic_launcher_foreground.png"
        subprocess.run(
            [
                "rsvg-convert",
                "-w",
                str(px),
                "-h",
                str(px),
                "/tmp/mwicon/fg.svg",
                "-o",
                out,
            ],
            check=True,
        )
        print("wrote", out)


if __name__ == "__main__":
    main()
