"""The chained moon: every vanilla moon phase redrawn with iron chains crossing its face.

Story: someone chained the moon's light, which is why the nights are so dark; the darker it is, the stronger the
monsters. The sky draws the moon additively (black adds nothing), so a chain over the moon is a black cut-out with
faint, cold highlights where moonlight catches the links, and beyond the disc the links show only as dim steel
against the sky.

Reads the vanilla 32 px phases from the deobfuscated 26.3 client jar in the Gradle cache, redraws them at 128 px
(the moon's 8 px face becomes 32) as the mod's own sprites beside vanilla's, drawn until the Dragon is freed
(ChainedMoonMixin):
  src/main/resources/assets/lightborn/textures/environment/celestial/moon/<phase>.png

Usage: python tools/make_chained_moon.py [preview.png]
"""
import io
import math
import sys
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/assets/lightborn/textures/environment/celestial/moon"
JAR = next((Path.home() / ".gradle/caches/fabric-loom/minecraftMaven/net/minecraft").glob(
    "minecraft-clientonly-deobf/26.3/*.jar"))
PHASES = ["full_moon", "waning_gibbous", "third_quarter", "waning_crescent", "new_moon", "waxing_crescent",
          "first_quarter", "waxing_gibbous"]
S = 128  # output size; the vanilla 32 px sprite at 4x, so links can be fine

STEEL = [(18, 20, 26), (34, 38, 48), (58, 64, 80), (92, 100, 122)]  # shadow -> highlight, as additive light


def face_box(img):
    """The lit face's centre and half-size in the sprite (the vanilla face is a small square in the middle)."""
    w, h = img.size
    xs, ys = [], []
    for y in range(h):
        for x in range(w):
            if sum(img.getpixel((x, y))) > 200:  # the face, not its soft halo
                xs.append(x)
                ys.append(y)
    if not xs:
        return w / 2, h / 2, w / 8
    return (min(xs) + max(xs) + 1) / 2, (min(ys) + max(ys) + 1) / 2, (max(max(xs) - min(xs), max(ys) - min(ys)) + 1) / 2


def chain_mask(size, cx, cy, r):
    """Two chains crossing in an X over the face and a third slung low, kept to the face itself with a short overhang
    (owner, 2026-11-16: the first chains spanned the whole sprite, about three times the moon). Pixels: (x, y) ->
    shade 0..3, or -1 for a link's dark body. Links alternate face-on (an open oval) and edge-on (a short bar)."""
    px = {}
    k = r / 12  # link scale: about four links across the face

    def link(lx, ly, ang, face):
        ca, sa = math.cos(ang), math.sin(ang)
        for i in range(-int(4 * k) - 1, int(4 * k) + 2):
            for j in range(-int(2.4 * k) - 1, int(2.4 * k) + 2):
                u, v = i / (3.6 * k), j / ((2.1 if face else 0.9) * k)
                rr = u * u + v * v
                if face:
                    if not 0.42 <= rr <= 1.0:
                        continue
                    shade = 3 if (v < -0.2 and rr > 0.6) else 2 if v < 0.2 else 1
                else:
                    if rr > 1.0:
                        continue
                    shade = 3 if v < -0.3 else 1 if v > 0.3 else 2
                x = round(lx + i * ca - j * sa)
                y = round(ly + i * sa + j * ca)
                if 0 <= x < size and 0 <= y < size:
                    px[(x, y)] = max(px.get((x, y), -1), shade)
                    for dx, dy in ((1, 0), (0, 1)):
                        q = (x + dx, y + dy)
                        if q not in px and q[0] < size and q[1] < size:
                            px[q] = -1

    def chain(x0, y0, x1, y1, phase):
        length = math.hypot(x1 - x0, y1 - y0)
        ang = math.atan2(y1 - y0, x1 - x0)
        step = 6.0 * k
        n = int(length / step) + 1
        for i in range(n):
            t = i * step / length
            link(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, ang, (i + phase) % 2 == 0)

    over = r * 0.35  # how far a chain runs past the face's edge
    chain(cx - r - over, cy - r * 0.7, cx + r + over, cy + r * 0.75, 0)
    chain(cx - r - over, cy + r * 0.75, cx + r + over, cy - r * 0.7, 1)
    chain(cx - r - over, cy + r * 0.5, cx + r + over, cy + r * 0.6, 0)
    return px


def chained(phase_png, box):
    base = Image.open(io.BytesIO(phase_png)).convert("RGB").resize((S, S), Image.NEAREST)
    img = base.copy()
    p = img.load()
    for (x, y), shade in chain_mask(S, *box).items():
        lit = sum(base.getpixel((x, y))) / 3
        if shade < 0:
            p[x, y] = (4, 5, 8)
        else:
            c = STEEL[shade]
            # Over the bright face, the near edge catches more light; off it, the links stay dim.
            boost = 1.0 + (0.9 if lit > 90 and shade == 3 else 0.35 if lit > 90 else 0)
            p[x, y] = tuple(min(255, int(ch * boost)) for ch in c)
    return img


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    made = []
    with zipfile.ZipFile(JAR) as zf:
        read = lambda name: zf.read(f"assets/minecraft/textures/environment/celestial/moon/{name}.png")
        # Every phase uses the full moon's face box, so the chain sits in the same place all month.
        full = Image.open(io.BytesIO(read("full_moon"))).convert("RGB").resize((S, S), Image.NEAREST)
        box = face_box(full)
        for name in PHASES:
            img = chained(read(name), box)
            img.save(OUT / f"{name}.png")
            made.append(img)
    if len(sys.argv) > 1:
        sheet = Image.new("RGB", (S * 4 * 4, S * 4 * 2))
        for i, img in enumerate(made):
            sheet.paste(img.resize((S * 4, S * 4), Image.NEAREST), ((i % 4) * S * 4, (i // 4) * S * 4))
        sheet.save(sys.argv[1])
    print("chained moon phases written to", OUT)


if __name__ == "__main__":
    main()
