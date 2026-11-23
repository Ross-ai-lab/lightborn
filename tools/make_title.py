"""Draws the title-screen art that replaces vanilla's: the LIGHTBORN logo and a blank edition strip.

Usage: py -3.14 -I tools/make_title.py <project root>
Writes src/main/resources/assets/minecraft/textures/gui/title/minecraft.png (1024x256, vanilla layout:
letters in the top 176 rows) and edition.png (512x64, text in the top 56 rows).
"""
import random
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

# Blocky 7-row glyphs drawn for this logo; the logo letters are 6 wide so their holes stay open.
GLYPHS = {
    "A": ["01110", "11011", "11011", "11111", "11011", "11011", "11011"],
    "B": ["111110", "110011", "110011", "111110", "110011", "110011", "111110"],
    "D": ["11110", "11011", "11011", "11011", "11011", "11011", "11110"],
    "E": ["111111", "110000", "110000", "111110", "110000", "110000", "111111"],
    "G": ["01111", "11000", "11000", "11011", "11011", "11011", "01111"],
    "H": ["110011", "110011", "110011", "111111", "110011", "110011", "110011"],
    "I": ["11111", "01110", "01110", "01110", "01110", "01110", "11111"],
    "L": ["11000", "11000", "11000", "11000", "11000", "11000", "11111"],
    "N": ["110011", "111011", "111111", "110111", "110011", "110011", "110011"],
    "O": ["011110", "110011", "110011", "110011", "110011", "110011", "011110"],
    "P": ["11110", "11011", "11011", "11110", "11000", "11000", "11000"],
    "R": ["111110", "110011", "110011", "111110", "110110", "110011", "110011"],
    "T": ["11111", "11111", "01110", "01110", "01110", "01110", "01110"],
    "U": ["110011", "110011", "110011", "110011", "110011", "110011", "011110"],
    "W": ["11011", "11011", "11011", "11111", "11111", "11111", "01010"],
    "*": ["00000", "00100", "01110", "11111", "01110", "00100", "00000"],
    " ": ["000", "000", "000", "000", "000", "000", "000"],
}


def layout(text):
    """Returns (cells set, width in cells, per-cell glyph index)."""
    cells, owner, x = set(), {}, 0
    for gi, ch in enumerate(text):
        rows = GLYPHS[ch]
        for r, row in enumerate(rows):
            for c, bit in enumerate(row):
                if bit == "1":
                    cells.add((x + c, r))
                    owner[(x + c, r)] = gi
        x += len(rows[0]) + 1
    return cells, x - 1, owner


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def render(text, size, cell, depth, outline, palettes, seed, top=0, region_h=None, cell_h=None):
    rnd = random.Random(seed)
    cells, wcells, owner = layout(text)
    w, h = size
    region_h = region_h or h
    cell_h = cell_h or cell
    text_w, text_h = wcells * cell, 7 * cell_h
    ox = (w - text_w) // 2
    oy = top + (region_h - (text_h + depth)) // 2

    face = Image.new("L", size, 0)
    fd = ImageDraw.Draw(face)
    for (cx, cy) in cells:
        fd.rectangle([ox + cx * cell, oy + cy * cell_h, ox + (cx + 1) * cell - 1, oy + (cy + 1) * cell_h - 1], fill=255)

    side = Image.new("L", size, 0)
    for d in range(1, depth + 1):
        side = ImageChops.lighter(side, ImageChops.offset(face, 0, d))
    side = ImageChops.subtract(side, face)
    body = ImageChops.lighter(face, side)
    ring = body.filter(ImageFilter.MaxFilter(outline * 2 + 1))

    out = Image.new("RGBA", size, (0, 0, 0, 0))
    out.paste((12, 8, 18, 255), mask=ring)

    px = out.load()
    fpx, spx = face.load(), side.load()
    # Per-cell brightness jitter and block seams give the "carved block" look.
    jitter = {c: rnd.uniform(-0.07, 0.07) for c in cells}
    seams = set()
    for (cx, cy) in cells:
        if (cx + 1, cy) in cells and rnd.random() < 0.22:
            seams.add(("v", cx + 1, cy))
        if (cx, cy + 1) in cells and rnd.random() < 0.22:
            seams.add(("h", cx, cy + 1))

    for y in range(h):
        for x in range(w):
            if fpx[x, y]:
                cx, cy = (x - ox) // cell, (y - oy) // cell_h
                pal = palettes[owner.get((cx, cy), 0)]
                t = (y - oy) / max(1, text_h)
                col = lerp(pal[0], pal[1], t)
                j = jitter.get((cx, cy), 0) + rnd.uniform(-0.025, 0.025)
                col = tuple(max(0, min(255, int(v * (1 + j)))) for v in col)
                lx, ly = (x - ox) % cell, (y - oy) % cell_h
                # Bevel: light top/left rim, dark bottom/right rim on the outer edge of the glyph.
                edge_top = not fpx[x, max(0, y - 3)] if y >= 3 else True
                edge_left = not fpx[max(0, x - 3), y] if x >= 3 else True
                edge_bot = not fpx[x, min(h - 1, y + 3)]
                edge_right = not fpx[min(w - 1, x + 3), y]
                if edge_top or edge_left:
                    col = lerp(col, (255, 255, 255), 0.35)
                elif edge_bot or edge_right:
                    col = lerp(col, (0, 0, 0), 0.25)
                seam_w = max(1, cell // 8)
                if (("v", cx, cy) in seams and lx < seam_w) or (("h", cx, cy) in seams and ly < seam_w):
                    col = lerp(col, (0, 0, 0), 0.35)
                px[x, y] = col + (255,)
            elif spx[x, y]:
                # Side face: owner of the face cell directly above, darkened and shaded with depth.
                yy = y
                while yy > 0 and not fpx[x, yy]:
                    yy -= 1
                cx, cy = (x - ox) // cell, (yy - oy) // cell_h
                pal = palettes[owner.get((cx, cy), 0)]
                t = min(1.0, (y - yy) / max(1, depth))
                col = lerp(lerp(pal[1], (0, 0, 0), 0.45), lerp(pal[1], (0, 0, 0), 0.75), t)
                px[x, y] = col + (255,)
    return out


def main():
    root = Path(sys.argv[1])
    dest = root / "src/main/resources/assets/minecraft/textures/gui/title"
    dest.mkdir(parents=True, exist_ok=True)

    gold = ((255, 236, 150), (222, 140, 24))
    silver = ((245, 245, 250), (150, 150, 168))
    arcane = ((226, 196, 255), (112, 52, 206))
    text = "LIGHTBORN"
    # Glyph index -> palette: L I G H T gold, B O R N arcane.
    palettes = [gold] * 5 + [arcane] * 4
    logo = render(text, (1024, 256), cell=16, depth=16, outline=6, palettes=palettes, seed=7, top=0, region_h=176, cell_h=21)
    logo.save(dest / "minecraft.png")

    # No subtitle under the logo (owner, 2026-11-09). A blank strip, not a missing file: a missing one lets
    # vanilla's "Java Edition" show through.
    Image.new("RGBA", (512, 64), (0, 0, 0, 0)).save(dest / "edition.png")
    print("wrote", dest / "minecraft.png", dest / "edition.png")


if __name__ == "__main__":
    main()
