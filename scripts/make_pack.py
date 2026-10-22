"""Build the BeezzLand resource pack: pixel glyphs for the logo and rank badges.

Usage: python3 make_pack.py <out_dir>   ->  <out_dir>/beezzland-pack.zip (+ prints its SHA-1)

The glyphs are private-use characters added to Minecraft's default font, so any text that
contains them (TAB, chat, LuckPerms prefixes, titles) draws the picture:
     logo «BEEZZLAND»    small bee
     owner   admin   moder   helper
     Silver  Gold    Diamond
Font files from resource packs are merged with vanilla, so nothing else in the font changes.
"""
import hashlib, io, json, os, sys, zipfile
from PIL import Image

GLYPHS = {
    "logo": "", "bee": "",
    "owner": "", "admin": "", "moder": "", "helper": "",
    "silver": "", "gold": "", "diamond": "",
}

# 3x5 pixel letters for the wordmark (wide letters 4 px)
LETTERS = {
    "B": ["###.", "#..#", "###.", "#..#", "###."],
    "E": ["###", "#..", "##.", "#..", "###"],
    "Z": ["####", "...#", ".##.", "#...", "####"],
    "L": ["#..", "#..", "#..", "#..", "###"],
    "A": [".##.", "#..#", "####", "#..#", "#..#"],
    "N": ["#..#", "##.#", "#.##", "#..#", "#..#"],
    "D": ["###.", "#..#", "#..#", "#..#", "###."],
}

BEE = [  # 9x9 bee, body colour B / shade D, black K, wing W, eye E
    "...WW....",
    "..WWWW...",
    "...WW.K.K",
    ".BBKBBKB.",
    "BBBKBBKEB",
    "BBBKBBKBB",
    ".DDKDDKD.",
    "..K..K...",
    ".........",
]

SHIELD = [  # 9x9 shield, fill F, rim R, symbol S
    "RRRRRRRRR",
    "RFFFFFFFR",
    "RFFFFFFFR",
    "RFFFFFFFR",
    "RFFFFFFFR",
    ".RFFFFFR.",
    ".RFFFFFR.",
    "..RFFFR..",
    "....R....",
]
SYMBOLS = {  # drawn on top of the shield (S)
    "owner": [(2, 2), (4, 2), (6, 2), (2, 3), (3, 3), (4, 3), (5, 3), (6, 3), (2, 4), (3, 4), (4, 4), (5, 4), (6, 4)],  # crown
    "admin": [(4, 1), (4, 2), (3, 3), (4, 3), (5, 3), (2, 4), (3, 4), (4, 4), (5, 4), (6, 4), (4, 5), (4, 6)],      # star/spark
    "moder": [(4, 2), (4, 3), (4, 4), (4, 5), (3, 5), (5, 5), (4, 6)],                                                # sword
    "helper": [(3, 2), (5, 2), (2, 3), (3, 3), (4, 3), (5, 3), (6, 3), (3, 4), (4, 4), (5, 4), (4, 5)],              # heart
}

RGB = lambda h: tuple(int(h.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4)) + (255,)
K, WING, EYE = RGB("#0a0f1c"), RGB("#e6efff"), RGB("#f8fafc")


def draw(grid, colors):
    img = Image.new("RGBA", (len(grid[0]), len(grid)), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, c in enumerate(row):
            if c in colors:
                img.putpixel((x, y), colors[c])
    return img


def bee(body, shade):
    return draw(BEE, {"B": RGB(body), "D": RGB(shade), "K": K, "W": WING, "E": EYE})


def shield(fill, rim, symbol, sym_color="#ffffff"):
    img = draw(SHIELD, {"F": RGB(fill), "R": RGB(rim)})
    for x, y in SYMBOLS[symbol]:
        img.putpixel((x, y), RGB(sym_color))
    return img


def wordmark():
    """«BEEZZLAND»: honey BEEZZ + sky LAND, 1 px dark shadow, with the bee in front."""
    parts = [(ch, "#facc15") for ch in "BEEZZ"] + [(ch, "#60a5fa") for ch in "LAND"]
    width = 11 + sum(len(LETTERS[c][0]) + 1 for c, _ in parts)
    img = Image.new("RGBA", (width + 1, 10), (0, 0, 0, 0))
    img.alpha_composite(bee("#facc15", "#ca8a04"), (0, 0))
    x = 11
    for ch, color in parts:
        for y, row in enumerate(LETTERS[ch]):
            for dx, c in enumerate(row):
                if c == "#":
                    img.putpixel((x + dx + 1, y + 3), RGB("#1e293b"))  # shadow
        for y, row in enumerate(LETTERS[ch]):
            for dx, c in enumerate(row):
                if c == "#":
                    img.putpixel((x + dx, y + 2), RGB(color))
        x += len(LETTERS[ch][0]) + 1
    return img


def build():
    images = {
        "logo": wordmark(),
        "bee": bee("#facc15", "#ca8a04"),
        "silver": bee("#e2e8f0", "#94a3b8"),
        "gold": bee("#fbbf24", "#b45309"),
        "diamond": bee("#67e8f9", "#0891b2"),
        "owner": shield("#facc15", "#a16207", "owner", "#7c2d12"),
        "admin": shield("#f87171", "#991b1b", "admin"),
        "moder": shield("#60a5fa", "#1e3a8a", "moder"),
        "helper": shield("#34d399", "#065f46", "helper"),
    }
    providers = []
    for name, img in images.items():
        big = name == "logo"
        providers.append({"type": "bitmap", "file": f"beezz:font/{name}.png",
                          "height": 20 if big else 9, "ascent": 16 if big else 8, "chars": [GLYPHS[name]]})
    meta = {"pack": {"description": "BeezzLand: логотип и значки рангов",
                     "pack_format": 75, "min_format": 75, "max_format": [120, 0]}}
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps(meta, ensure_ascii=False, indent=2))
        z.writestr("assets/minecraft/font/default.json", json.dumps({"providers": providers}, indent=2))
        for name, img in images.items():
            png = io.BytesIO()
            img.save(png, "PNG")
            z.writestr(f"assets/beezz/textures/font/{name}.png", png.getvalue())
        icon = io.BytesIO()
        bee("#facc15", "#ca8a04").resize((64, 64), Image.NEAREST).save(icon, "PNG")
        z.writestr("pack.png", icon.getvalue())
    return buf.getvalue(), images


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    data, images = build()
    path = os.path.join(out, "beezzland-pack.zip")
    with open(path, "wb") as f:
        f.write(data)
    if "--preview" in sys.argv:  # a big PNG of every glyph, to look at them
        w = sum(i.width * 6 + 12 for i in images.values())
        sheet = Image.new("RGBA", (w, 130), (16, 22, 38, 255))
        x = 6
        for img in images.values():
            big = img.resize((img.width * 6, img.height * 6), Image.NEAREST)
            sheet.alpha_composite(big, (x, 6))
            x += big.width + 12
        sheet.save(os.path.join(out, "pack-preview.png"))
    print(path, hashlib.sha1(data).hexdigest())


if __name__ == "__main__":
    main()
