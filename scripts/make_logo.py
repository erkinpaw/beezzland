"""Draw the BeezzLand pixel bee: SVG for the site, PNGs for the server icon and favicon.

Usage: python make_logo.py <out_dir>
"""
import os, sys
from PIL import Image

# 16x16 bee, side view, facing right, classic yellow/black.
GRID = [
    "................",
    "...ww..ww.......",
    "..wWWwwWWw......",
    "..wWWWwWWw.K...K",
    "...wwwwww...K.K.",
    "............K.K.",
    "..lLLllLLlLLLLL.",
    "..BBKKBBKKBBBBBB",
    "..BBKKBBKKBBBEeB",
    ".SBBKKBBKKBBBeKB",
    "SSBBKKBBKKBBBBBB",
    ".SDDKKDDKKDDDDDB",
    "..DDKKDDKKDDDDDD",
    "...K...K...K....",
    "...K...K...K....",
    "................",
]
COLORS = {
    "B": "#facc15",  # body yellow
    "l": "#fde047",  # top highlight
    "L": "#fef08a",
    "D": "#ca8a04",  # shaded belly
    "K": "#0a0f1c",  # black stripes, legs, antennae
    "S": "#0a0f1c",  # stinger
    "E": "#f8fafc",  # eye white
    "e": "#0a0f1c",  # pupil
    "w": "#a9c8ff",  # wing edge
    "W": "#e6efff",  # wing inner
}
ALPHA = {}
OUTLINE = "#cfe0ff"  # sticker-style edge so black parts stay visible on a dark site


def outlined():
    """Pad the grid by 1 and add an outline ('o') around the whole silhouette."""
    n = len(GRID) + 2
    g = [["."] * n for _ in range(n)]
    for y, row in enumerate(GRID):
        for x, c in enumerate(row):
            g[y + 1][x + 1] = c
    for y in range(n):
        for x in range(n):
            if g[y][x] == "." and any(0 <= y + dy < n and 0 <= x + dx < n and g[y + dy][x + dx] not in ".o"
                                      for dy, dx in ((0, 1), (1, 0), (0, -1), (-1, 0))):
                g[y][x] = "o"
    return ["".join(r) for r in g]


def svg():
    rects = []
    grid = outlined()
    n = len(grid)
    for y, row in enumerate(grid):
        for x, c in enumerate(row):
            if c in COLORS or c == "o":
                op = f' fill-opacity="{ALPHA[c]}"' if c in ALPHA else ""
                rects.append(f'<rect x="{x}" y="{y}" width="1.02" height="1.02" fill="{COLORS.get(c, OUTLINE)}"{op}/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {n} {n}" shape-rendering="crispEdges">'
            + "".join(rects) + "</svg>\n")


def png(size, bg=None):
    grid = outlined()
    n = len(grid)
    img = Image.new("RGBA", (n, n), bg or (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, c in enumerate(row):
            if c in COLORS or c == "o":
                h = COLORS.get(c, OUTLINE).lstrip("#")
                a = int(255 * ALPHA.get(c, 1))
                px = tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (a,)
                if bg:  # blend onto background so the icon has no transparency
                    px = tuple(round(px[i] * a / 255 + bg[i] * (1 - a / 255)) for i in range(3)) + (255,)
                img.putpixel((x, y), px)
    return img.resize((size, size), Image.NEAREST)


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    open(os.path.join(out, "bee.svg"), "w").write(svg())
    icon = Image.new("RGBA", (64, 64), (10, 15, 28, 255))
    icon.paste(png(54, (10, 15, 28)), (5, 5))
    icon.save(os.path.join(out, "server-icon.png"))  # Minecraft needs exactly 64x64
    png(36).save(os.path.join(out, "favicon.png"))
    png(252).save(os.path.join(out, "bee-256.png"))
    print("logo written to", out)


if __name__ == "__main__":
    main()
