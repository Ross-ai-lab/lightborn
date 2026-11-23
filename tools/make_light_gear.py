"""Light gear art: the six pieces of the Light set and the Light Wings icon, hand-built at 32 px with real shading,
plus the worn armour, which is vanilla's diamond armour layer recoloured to ivory and gold so it keeps vanilla's
own panel shading.

Each icon is a stack of shapes, each shape one material (ivory, gold, teal light, leather, shadow). A shading pass
then lights every shape from the top left: a lit lip where the shape's top or left edge meets anything else, a
shaded lip at its bottom or right edge, and a gentle gradient across the shape, all read off a five-step ramp per
material. Pixels left empty next to the art get a dark outline tinted by the material they touch, the way vanilla
outlines its items. Hand-placed glints go on last.

Run: py -3 -I tools/make_light_gear.py   (reads vanilla textures from the Minecraft 26.3 client jar in the Loom cache)
"""
import io
import math
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/lightborn/textures"
JAR = Path.home() / ".gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.3/minecraft-clientonly-deobf-26.3.jar"

N = 32
# Five tones per material, dark to light.
RAMPS = {
    "ivory": [(118, 98, 76), (176, 156, 120), (224, 210, 176), (247, 238, 214), (255, 253, 244)],
    "gold": [(96, 56, 18), (156, 104, 32), (210, 160, 58), (240, 204, 98), (255, 240, 170)],
    "teal": [(16, 76, 88), (28, 146, 156), (70, 220, 214), (166, 250, 240), (244, 255, 252)],
    "leather": [(40, 26, 22), (70, 46, 36), (104, 70, 52), (138, 96, 70), (170, 126, 92)],
    "shadow": [(18, 14, 24), (30, 24, 38), (44, 36, 56), (60, 50, 74), (80, 68, 96)],
}
OUTLINE = (30, 22, 28)


class Icon:
    def __init__(self):
        self.layer = [[-1] * N for _ in range(N)]  # which shape owns the pixel
        self.shapes = []  # (material, base tone, gradient strength, bevel)
        self.tone_override = {}

    def shape(self, material, mask, base=2, grad=0.9, bevel=True):
        """Adds a shape from a set of pixels; later shapes cover earlier ones."""
        i = len(self.shapes)
        self.shapes.append((material, base, grad, bevel, mask))
        for x, y in mask:
            if 0 <= x < N and 0 <= y < N:
                self.layer[y][x] = i
        return i

    def poly(self, material, points, **kw):
        return self.shape(material, poly_mask(points), **kw)

    def ellipse(self, material, box, **kw):
        return self.shape(material, ellipse_mask(box), **kw)

    def glint(self, x, y, tone=4):
        self.tone_override[(x, y)] = tone

    def render(self):
        img = Image.new("RGBA", (N, N))
        px = img.load()
        boxes = []
        for material, base, grad, bevel, mask in self.shapes:
            own = [(x, y) for x, y in mask if 0 <= x < N and 0 <= y < N]
            xs, ys = [p[0] for p in own] or [0], [p[1] for p in own] or [0]
            boxes.append((min(xs), min(ys), max(xs), max(ys)))
        for y in range(N):
            for x in range(N):
                i = self.layer[y][x]
                if i < 0:
                    continue
                material, base, grad, bevel, _ = self.shapes[i]
                x0, y0, x1, y1 = boxes[i]
                w, h = max(1, x1 - x0), max(1, y1 - y0)
                g = 1 - ((x - x0) / w + (y - y0) / h)  # +1 at the top left, -1 at the bottom right
                t = base + g * grad
                if bevel:
                    other = lambda dx, dy: not (0 <= x + dx < N and 0 <= y + dy < N) or self.layer[y + dy][x + dx] != i
                    if other(0, -1) or other(-1, 0):
                        t += 1
                    if other(0, 1) or other(1, 0):
                        t -= 1
                t = self.tone_override.get((x, y), t)
                px[x, y] = RAMPS[material][max(0, min(4, round(t)))] + (255,)
        # Outline: empty pixels touching the art take a dark tone of what they touch.
        out = {}
        for y in range(N):
            for x in range(N):
                if self.layer[y][x] >= 0:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    qx, qy = x + dx, y + dy
                    if 0 <= qx < N and 0 <= qy < N and self.layer[qy][qx] >= 0:
                        dark = RAMPS[self.shapes[self.layer[qy][qx]][0]][0]
                        out[(x, y)] = tuple((a + b) // 2 for a, b in zip(OUTLINE, (c // 2 for c in dark)))
                        break
        for (x, y), c in out.items():
            px[x, y] = c + (255,)
        return img


def poly_mask(points):
    m = Image.new("1", (N, N))
    ImageDraw.Draw(m).polygon(points, fill=1)
    return {(x, y) for y in range(N) for x in range(N) if m.getpixel((x, y))}


def ellipse_mask(box):
    m = Image.new("1", (N, N))
    ImageDraw.Draw(m).ellipse(box, fill=1)
    return {(x, y) for y in range(N) for x in range(N) if m.getpixel((x, y))}


def diag(ox, oy, test):
    """Pixels of a shape laid along the 45-degree item diagonal from (ox, oy) up to the top right, given in
    (u along the axis, v across it) coordinates."""
    out = set()
    for y in range(N):
        for x in range(N):
            dx, dy = x - ox, oy - y
            if test((dx + dy) / 2, (dx - dy) / 2):
                out.add((x, y))
    return out


def mirror(points):
    return [(N - 1 - x, y) for x, y in points]


# ---------------------------------------------------------------------------------------------------- the icons


def helm():
    ic = Icon()
    # Side wings first, so the shell overlaps their roots.
    for pts in ([(9, 13), (4, 9), (1, 3), (3, 3), (6, 6), (5, 2), (8, 5), (10, 9)],):
        ic.poly("gold", pts, base=2.2)
        ic.poly("gold", mirror(pts), base=1.8)
    ic.poly("ivory", [(7, 26), (6, 17), (7, 11), (10, 7), (14, 5), (18, 5), (22, 7), (25, 11), (26, 17), (25, 26),
                      (21, 28), (20, 19), (12, 19), (11, 28)], base=2.2, grad=1.1)
    ic.poly("shadow", [(12, 19), (20, 19), (20, 28), (12, 28)], base=1, grad=0.6)  # the open face, dark inside
    ic.poly("ivory", [(13, 20), (19, 20), (19, 21), (13, 21)], base=1, bevel=False)  # inner rim catching a little light
    ic.poly("gold", [(7, 16), (25, 16), (26, 18), (6, 18)], base=2)  # brow band
    ic.poly("gold", [(15, 3), (16, 2), (17, 3), (17, 15), (15, 15)], base=2.4)  # crest ridge
    ic.poly("gold", [(7, 25), (11, 25), (11, 28), (7, 26)], base=1.6)  # cheek-guard trims
    ic.poly("gold", [(21, 25), (25, 25), (25, 26), (21, 28)], base=1.4)
    ic.poly("teal", [(16, 14), (18, 16), (16, 18), (14, 16)], base=2.4, grad=1.2)
    for x, y in ((10, 9), (11, 8), (12, 7), (8, 12), (16, 3), (15, 16)):
        ic.glint(x, y)
    return ic.render()


def chestplate():
    ic = Icon()
    ic.poly("ivory", [(8, 9), (12, 7), (14, 11), (18, 11), (20, 7), (24, 9), (24, 26), (20, 29), (12, 29), (8, 26)],
            base=2.2, grad=1.1)
    for pts, b in (([(1, 14), (2, 10), (6, 8), (12, 8), (13, 12), (10, 15), (4, 16)], 2.0),
                   ([(30, 14), (29, 10), (25, 8), (19, 8), (18, 12), (21, 15), (27, 16)], 1.6)):
        ic.poly("gold", pts, base=b)  # pauldrons: low, swept plates with a gold rim under an ivory face
        ic.poly("ivory", [(x + (1 if x < 16 else -1) * (1 if x in (1, 2, 30, 29) else 0), y - (1 if y > 13 else 0))
                          for x, y in pts], base=b + 0.4)
    ic.poly("gold", [(12, 7), (14, 11), (18, 11), (20, 7), (19, 6), (17, 9), (15, 9), (13, 6)], base=2.2)  # collar
    ic.poly("shadow", [(14, 8), (18, 8), (17, 10), (15, 10)], base=1)  # neck opening
    # Sunburst emblem: gold rays round a teal heart.
    rays = set()
    for k in range(8):
        a = k * math.pi / 4
        for r in range(3, 6):
            rays.add((round(16 + math.cos(a) * r), round(18 + math.sin(a) * r)))
    ic.shape("gold", rays, base=2.4, bevel=False)
    ic.ellipse("gold", (13, 15, 19, 21), base=2.2)
    ic.ellipse("teal", (14, 16, 18, 20), base=2.4, grad=1.3)
    ic.poly("gold", [(9, 24), (23, 24), (23, 25), (9, 25)], base=2)  # waist band
    ic.poly("ivory", [(10, 26), (15, 26), (15, 29), (12, 29)], base=1.6)  # faulds
    ic.poly("ivory", [(17, 26), (22, 26), (20, 29), (17, 29)], base=1.4)
    for x, y in ((3, 9), (4, 8), (5, 8), (22, 9), (23, 8), (9, 11), (15, 17), (10, 13)):
        ic.glint(x, y)
    return ic.render()


def greaves():
    ic = Icon()
    ic.poly("ivory", [(7, 8), (25, 8), (25, 13), (7, 13)], base=2.2)  # hips
    ic.poly("ivory", [(7, 12), (15, 12), (15, 29), (8, 29)], base=2.3, grad=1.0)
    ic.poly("ivory", [(17, 12), (25, 12), (24, 29), (17, 29)], base=1.9, grad=1.0)
    ic.poly("gold", [(6, 4), (26, 4), (26, 8), (6, 8)], base=2.1)  # belt
    ic.poly("teal", [(15, 5), (17, 5), (17, 7), (15, 7)], base=2.6)  # buckle stone
    for box, b in (((8, 15, 14, 21), 2.3), ((18, 15, 24, 21), 1.9)):  # knee cops
        ic.ellipse("gold", box, base=b)
        x0, y0, x1, y1 = box
        ic.ellipse("teal", (x0 + 2, y0 + 2, x1 - 2, y1 - 2), base=2.4, bevel=False)
    for x0 in (8, 18):  # shin ridge
        ic.poly("gold", [(x0 + 2, 23), (x0 + 4, 23), (x0 + 4, 28), (x0 + 2, 28)], base=1.6)
    ic.poly("gold", [(8, 27), (15, 27), (15, 29), (8, 29)], base=1.8)
    ic.poly("gold", [(17, 27), (24, 27), (24, 29), (17, 29)], base=1.5)
    for x, y in ((8, 13), (8, 14), (9, 22), (8, 9), (9, 9), (18, 13), (7, 5), (8, 5)):
        ic.glint(x, y)
    return ic.render()


def boots():
    ic = Icon()

    def boot(ox, oy, b):
        ic.poly("gold", [(ox + 11, oy + 5), (ox + 15, oy - 3), (ox + 14, oy + 2), (ox + 17, oy - 1), (ox + 15, oy + 4),
                         (ox + 17, oy + 3), (ox + 12, oy + 8)], base=b)  # the ankle wing
        ic.poly("ivory", [(ox + 4, oy), (ox + 11, oy), (ox + 11, oy + 17), (ox, oy + 17), (ox, oy + 13),
                          (ox + 4, oy + 11)], base=b + 0.2, grad=1.0)
        ic.poly("gold", [(ox + 4, oy), (ox + 11, oy), (ox + 11, oy + 2), (ox + 4, oy + 2)], base=b)  # cuff
        ic.poly("gold", [(ox, oy + 13), (ox + 4, oy + 12), (ox + 4, oy + 17), (ox, oy + 17)], base=b)  # toe cap
        ic.poly("leather", [(ox, oy + 17), (ox + 11, oy + 17), (ox + 11, oy + 18), (ox, oy + 18)], base=1.5)  # sole
        ic.poly("teal", [(ox + 6, oy + 6), (ox + 8, oy + 6), (ox + 8, oy + 8), (ox + 6, oy + 8)], base=2.5)
        ic.glint(ox + 5, oy + 3)
        ic.glint(ox + 5, oy + 4)
        ic.glint(ox + 1, oy + 14)

    boot(15, 9, 1.7)  # the far boot, a little higher and dimmer
    boot(3, 12, 2.1)
    return ic.render()


def sword():
    ic = Icon()
    o = (3, 28)
    blade = diag(*o, lambda u, v: 9.5 <= u <= 25 and abs(v) <= (1.5 if u < 21 else 1.5 * (25 - u) / 4))
    ic.shape("ivory", blade, base=2.6, grad=0.8)
    ic.shape("teal", diag(*o, lambda u, v: 11 <= u <= 21 and abs(v) < 0.3), base=2.6, bevel=False)  # glowing fuller
    guard = diag(*o, lambda u, v: 7.5 <= u <= 9 + max(0, abs(v) - 2.5) * 0.6 and abs(v) <= 4.5)
    ic.shape("gold", guard, base=2.3)
    ic.shape("leather", diag(*o, lambda u, v: 3 <= u < 7.5 and abs(v) <= 0.6), base=2, bevel=False)
    for (x, y) in diag(*o, lambda u, v: 3 <= u < 7.5 and abs(v) <= 0.6):
        if (x + y) % 2 == 0:
            ic.glint(x, y, 3)
    ic.shape("gold", diag(*o, lambda u, v: 1.5 <= u < 3 and abs(v) <= 1), base=2)
    ic.shape("teal", diag(*o, lambda u, v: 0 <= u < 1.5 and abs(v) <= 1), base=2.6)  # pommel stone
    for u in range(12, 24, 3):  # a bright edge along the blade's upper side
        x, y = o[0] + u - 1, o[1] - u - 1
        ic.glint(x, y)
    return ic.render()


def staff():
    ic = Icon()
    o = (2, 29)
    ic.shape("ivory", diag(*o, lambda u, v: 1 <= u <= 18 and abs(v) <= 0.6), base=2.4, grad=0.6)
    for a in (1, 6, 11.5, 16.5):  # gold bands and foot cap
        ic.shape("gold", diag(*o, lambda u, v, a=a: a - 0.6 <= u <= a + 0.6 and abs(v) <= 1.1), base=2.2)
    ic.shape("gold", diag(*o, lambda u, v: 0 <= u < 1 and abs(v) <= 0.6), base=1.6)
    cx, cy, r = 22, 7, 5.0
    ring = {(x, y) for y in range(N) for x in range(N)
            if r - 1.4 <= math.hypot(x - cx, y - cy) <= r and not (x > cx + 1 and y < cy - 1)}
    ic.shape("gold", ring, base=2.4)  # an open sun ring, gap to the top right where the light spills out
    for a in (-0.4, 0.9, 2.2):  # three ivory talons gripping the stone
        tip = (round(cx + math.cos(a) * 2.2), round(cy - math.sin(a) * 2.2))
        base_ = (round(cx + math.cos(a) * 4.3), round(cy - math.sin(a) * 4.3))
        ic.shape("ivory", {tip, base_, ((tip[0] + base_[0]) // 2, (tip[1] + base_[1]) // 2)}, base=3, bevel=False)
    crystal = diag(*o, lambda u, v: 16.5 <= u <= 24.5 and abs(v) <= 1.6 * (1 - abs(u - 20) / 4.5))
    ic.shape("teal", crystal, base=2.5, grad=1.4)
    for x, y in ((26, 3), (28, 2), (27, 1), (29, 4), (25, 1)):  # light spilling from the gap
        ic.shape("teal", {(x, y)}, base=3.4, bevel=False)
    ic.glint(21, 6)
    ic.glint(20, 7)
    ic.glint(18, 3)
    ic.glint(17, 4)
    return ic.render()


def wings(broken=False):
    ic = Icon()
    left = [(15, 4), (10, 2), (5, 4), (2, 9), (1, 17), (3, 24), (6, 30), (8, 27), (10, 21), (12, 16), (14, 11), (15, 8)]
    for side in (left, mirror(left)):
        flip = side is not left
        mask = poly_mask(side)
        coverts = {(x, y) for x, y in mask if y <= 9 + (x if not flip else N - 1 - x) // 4}
        rest = mask - coverts
        ic.shape("ivory", coverts, base=2.6 if not flip else 2.2, grad=0.8)
        feathers = {}
        for x, y in rest:
            lx = x if not flip else N - 1 - x
            feathers.setdefault(int((lx + (y - 9) * 0.45) // 3), set()).add((x, y))
        for k, f in sorted(feathers.items()):
            tip = {(x, y) for x, y in f if y >= max(q[1] for q in f) - 2}
            ic.shape("ivory", f - tip, base=(2.3 if k % 2 else 2.0) + (0 if not flip else -0.3), grad=0.7)
            ic.shape("gold", tip, base=2.2 + (0 if not flip else -0.3))
        ic.poly("gold", [(14, 3) if not flip else (17, 3), (15, 4) if not flip else (16, 4),
                         (15, 10) if not flip else (16, 10), (14, 10) if not flip else (17, 10)], base=2.2)
    for x, y in ((6, 4), (7, 3), (4, 6), (3, 8)):
        ic.glint(x, y)
    img = ic.render()
    if broken:  # dulled to stone grey, with a crack across each wing
        px = img.load()
        for y in range(N):
            for x in range(N):
                r, g, b, a = px[x, y]
                if a:
                    l = int(0.3 * r + 0.59 * g + 0.11 * b)
                    px[x, y] = (int(l * 0.62), int(l * 0.6), int(l * 0.6), a)
        for x, y in ((4, 12), (5, 13), (6, 13), (7, 14), (8, 16), (27, 12), (26, 13), (25, 13), (24, 14), (23, 16)):
            px[x, y] = (24, 20, 22, 255)
    return img


# ---------------------------------------------------------------------------------------------------- worn armour


def worn(img):
    """Vanilla diamond armour recoloured: its darkest seams and rims become gold trim, the rest ivory."""
    img = img.convert("RGBA")
    px = img.load()
    lums = [0.299 * r + 0.587 * g + 0.114 * b for r, g, b, a in img.get_flattened_data() if a]
    lo, hi = min(lums), max(lums)
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if not a:
                continue
            t = (0.299 * r + 0.587 * g + 0.114 * b - lo) / max(1, hi - lo)
            if t < 0.16:
                c = RAMPS["gold"][1 + min(2, int(t / 0.16 * 3))]
            else:
                c = RAMPS["ivory"][min(4, 1 + int((t - 0.16) / 0.84 * 4))]
            px[x, y] = c + (a,)
    return img


def main():
    icons = {"light_helm": helm(), "light_chestplate": chestplate(), "light_greaves": greaves(),
             "light_boots": boots(), "light_staff": staff(),  # the sword: make_light_arms.py
             "light_wings": wings(), "light_wings_broken": wings(broken=True)}
    for name, img in icons.items():
        img.save(A / f"item/{name}.png")
    with zipfile.ZipFile(JAR) as z:
        for layer in ("humanoid", "humanoid_leggings"):
            src = Image.open(io.BytesIO(z.read(f"assets/minecraft/textures/entity/equipment/{layer}/diamond.png")))
            worn(src).save(A / f"entity/equipment/{layer}/light.png")
    print("drew", ", ".join(icons))


if __name__ == "__main__":
    main()
