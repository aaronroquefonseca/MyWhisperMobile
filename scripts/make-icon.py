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

    # White symbolic status icon (notification small icons must be a
    # white silhouette, and VectorDrawable's arc parsing proved
    # unreliable — so ship density PNGs rendered from source).
    sym = open(
        "/home/aaronroque/.t3/worktrees/MyWhisper/t3code-8d7dc4a2/assets/icons/symbolic.svg"
    ).read()
    srects = re.findall(
        r'<rect x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" '
        r'height="([\d.]+)" rx="([\d.]+)"',
        sym,
    )
    sswoosh = re.search(r"<path d=\"(M 634.*?)\"/>", sym, re.S).group(1)
    assert len(srects) == 4, srects

    def rrect(x, y, w, h, r):
        hw, hh = w - 2 * r, h - 2 * r
        return (
            f"M{x:g},{y:g}h{hw:g}a{r:g},{r:g} 0 0,1 {r:g},{r:g}"
            f"v{hh:g}a{r:g},{r:g} 0 0,1 {-r:g},{r:g}"
            f"h{-hw:g}a{r:g},{r:g} 0 0,1 {-r:g},{-r:g}"
            f"v{-hh:g}a{r:g},{r:g} 0 0,1 {r:g},{-r:g}z"
        )

    s2 = 20.0 / (1058.0 - 195.0)
    dx2 = 12 - 626.5 * s2
    dy2 = 12 - 613.0 * s2
    stat_paths = [rrect(*map(float, m)) for m in srects] + [
        re.sub(r"\s+", " ", sswoosh).replace(" ,", ",")
    ]
    stat_svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" '
        'width="384" height="384">'
        f'<g transform="translate({dx2:.3f} {dy2:.3f}) scale({s2:.5f})" fill="white">'
        + "".join(f'<path d="{p}"/>' for p in stat_paths)
        + "</g></svg>"
    )
    open("/tmp/mwicon/stat.svg", "w").write(stat_svg)
    for sname, spx in {
        "mdpi": 24,
        "hdpi": 36,
        "xhdpi": 48,
        "xxhdpi": 72,
        "xxxhdpi": 96,
    }.items():
        sdir = f"{RES}/drawable-{sname}"
        os.makedirs(sdir, exist_ok=True)
        sout = f"{sdir}/ic_stat_sync.png"
        subprocess.run(
            [
                "rsvg-convert",
                "-w",
                str(spx),
                "-h",
                str(spx),
                "/tmp/mwicon/stat.svg",
                "-o",
                sout,
            ],
            check=True,
        )
        print("wrote", sout)


if __name__ == "__main__":
    main()
