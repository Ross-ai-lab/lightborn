"""The Light Sword and the Light Shield (owner, 2026-11-17: the sword more epic and overflowing with light, a new
Light Shield).

Sword: a 32 px icon in make_light_gear's shaded style, longer and broader than the first one, with swept sun-wing
quillons and a glowing teal heart; animated, a pulse of light running up the blade. Its item is a composite of the
plain handheld sprite and a glow card (the blade and its light only) drawn full-bright, so the blade shines in the
dark. The motes spilling off it in hand are client particles (LightArmsView).

Shield: a 3D shield in vanilla's shield shape (plate 12 x 22 x 1, grip 2 x 6 x 6) with its own 32 px texture
(1 texel per model pixel): an ivory face in a gold rim with a radiant sun, whose sun is a second card drawn
full-bright. It keeps vanilla's shield display and blocking poses.

Run: py -3 -I tools/make_light_arms.py
"""
import io
import json
import math
import sys
import zipfile
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_light_gear as lg  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/lightborn"
T = ASSETS / "textures/item"
JAR = Path.home() / ".gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar"
FRAMES = 16


def write_json(rel, data):
    p = ASSETS / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


# ---------------------------------------------------------------- sword

O = (2, 29)


def blade_test(u, v):
    if u < 10 or u > 28.5:
        return False
    half = 2.0 if u < 23 else 2.0 * (28.5 - u) / 5.5
    return abs(v) <= half


def fuller_test(u, v):
    return 11.5 <= u <= 24 and abs(v) <= 0.5


def sword_icon():
    ic = lg.Icon()
    ic.shape("ivory", lg.diag(*O, blade_test), base=2.7, grad=0.7)
    ic.shape("teal", lg.diag(*O, fuller_test), base=3.0, bevel=False)
    # Sun-wing quillons swept up toward the blade.
    guard = lg.diag(*O, lambda u, v: abs(v) <= 6
                    and 7.2 + max(0, abs(v) - 2.5) * 0.75 <= u <= 9.3 + max(0, abs(v) - 2.5) * 0.95)
    ic.shape("gold", guard, base=2.4)
    ic.shape("teal", lg.diag(*O, lambda u, v: abs(u - 8.3) + abs(v) <= 1.3), base=3.0, grad=1.2)  # heart stone
    grip = lg.diag(*O, lambda u, v: 3.6 <= u < 7.2 and abs(v) <= 0.7)
    ic.shape("leather", grip, base=2, bevel=False)
    for (x, y) in grip:
        if (x + y) % 2 == 0:
            ic.glint(x, y, 3)
    ic.shape("gold", lg.diag(*O, lambda u, v: 1.9 <= u < 3.6 and abs(v) <= 1.3), base=2.2)
    ic.shape("teal", lg.diag(*O, lambda u, v: 0.2 <= u < 1.9 and abs(v) <= 1.0), base=2.7)
    for u in range(12, 27, 2):  # a bright upper edge
        ic.glint(O[0] + u - 2, O[1] - u - 1)
    return ic.render()


def uv_of(x, y):
    dx, dy = x - O[0], O[1] - y
    return (dx + dy) / 2, (dx - dy) / 2


def pulse(u, t):
    """Brightness of the light running up the blade at u, frame phase t in [0, 1)."""
    head = 6 + t * 30
    return math.exp(-((u - head) ** 2) / 6.0)


def lift(c, k, to=(255, 255, 248)):
    return tuple(round(a + (b - a) * k) for a, b in zip(c, to))


def sword_frames(icon):
    sheet = Image.new("RGBA", (32, 32 * FRAMES))
    glow = Image.new("RGBA", (32, 32 * FRAMES))
    px = icon.load()
    for f in range(FRAMES):
        t = f / FRAMES
        fr = icon.copy()
        fp = fr.load()
        gl = Image.new("RGBA", (32, 32))
        gp = gl.load()
        for y in range(32):
            for x in range(32):
                r, g, b, a = px[x, y]
                if not a:
                    continue
                u, v = uv_of(x, y)
                on_blade = blade_test(u, v) or abs(u - 8.3) + abs(v) <= 1.3
                if not on_blade:
                    continue
                k = pulse(u, t) * (0.75 if fuller_test(u, v) else 0.5)
                fp[x, y] = lift((r, g, b), k) + (a,)
                # The glow card: the blade itself, a little brighter, full-bright in the dark.
                base = 0.25 if fuller_test(u, v) else 0.12
                gp[x, y] = lift((r, g, b), base + k * 0.6) + (255,)
        sheet.paste(fr, (0, 32 * f))
        glow.paste(gl, (0, 32 * f))
    return sheet, glow


def mcmeta(name, frametime):
    (T / f"{name}.png.mcmeta").write_text(
        json.dumps({"animation": {"frametime": frametime, "interpolate": True}}, indent=2) + "\n", encoding="utf-8")


def card(texture, z0, z1, emission=15):
    """A full-item card: north and south faces showing {texture}, drawn at light {emission}."""
    return {
        "from": [0, 0, z0], "to": [16, 16, z1], "light_emission": emission,
        "faces": {
            "south": {"uv": [0, 0, 16, 16], "texture": texture},
            "north": {"uv": [16, 0, 0, 16], "texture": texture},
        },
    }


def sword():
    icon = sword_icon()
    sheet, glow = sword_frames(icon)
    sheet.save(T / "light_sword.png")
    glow.save(T / "light_sword_glow.png")
    mcmeta("light_sword", 2)
    mcmeta("light_sword_glow", 2)
    write_json("models/item/light_sword.json",
               {"parent": "minecraft:item/handheld", "textures": {"layer0": "lightborn:item/light_sword"}})
    write_json("models/item/light_sword_glow.json", {
        "parent": "minecraft:item/handheld",
        "textures": {"glow": "lightborn:item/light_sword_glow", "particle": "lightborn:item/light_sword"},
        "elements": [card("#glow", 7.45, 8.55)],
    })
    write_json("items/light_sword.json", {"model": {"type": "minecraft:composite", "models": [
        {"type": "minecraft:model", "model": "lightborn:item/light_sword"},
        {"type": "minecraft:model", "model": "lightborn:item/light_sword_glow"},
    ]}})


# ---------------------------------------------------------------- shield

IVORY, GOLD, TEAL, LEATHER = lg.RAMPS["ivory"], lg.RAMPS["gold"], lg.RAMPS["teal"], lg.RAMPS["leather"]


def shield_textures():
    tex = Image.new("RGBA", (32, 32))
    sun = Image.new("RGBA", (32, 32))
    p, sp = tex.load(), sun.load()
    W, H = 12, 22
    cx, cy = 5.5, 9.0
    # Front face, x 0..11, y 0..21.
    for y in range(H):
        for x in range(W):
            rim = x == 0 or y == 0 or x == W - 1 or y == H - 1
            inner_rim = x == 1 or y == 1 or x == W - 2 or y == H - 2
            if rim:
                c = GOLD[3] if (x == 0 or y == 0) else GOLD[1]
            elif inner_rim:
                c = GOLD[2] if (x == 1 or y == 1) else GOLD[1]
            else:
                shade = 1 - ((x - 2) / 8 + (y - 2) / 18) * 0.5
                c = IVORY[2 + (1 if shade > 0.75 else 0)]
                # A narrow gold spine down the face, broken by the sun.
                if x in (5, 6) and abs(y - cy) > 5.5:
                    c = GOLD[2] if x == 5 else GOLD[1]
                if y > 16 and (x + y) % 5 == 0:
                    c = IVORY[1]  # a faint grain toward the foot
            p[x, y] = c + (255,)
            # The sun: a teal heart in a gold disc, eight gold rays; its card glows.
            d = math.hypot(x - cx, y - cy)
            ang = math.atan2(y - cy, x - cx)
            near = round(ang / (math.pi / 4)) * (math.pi / 4)
            long_ray = round(ang / (math.pi / 4)) % 2 == 0
            ray = 2.6 < d <= (5.4 if long_ray else 4.2) and d * abs(math.sin(ang - near)) <= 0.6
            if d <= 1.6:
                sp[x, y] = p[x, y] = TEAL[4 if d < 1 else 3] + (255,)
            elif d <= 2.7:
                sp[x, y] = p[x, y] = GOLD[4 if d < 2.2 else 3] + (255,)
            elif ray and not rim and not inner_rim:
                sp[x, y] = p[x, y] = GOLD[4 if d < 4 else 3] + (255,)
    # Back face, x 12..23: oak-dark ivory with the rim.
    for y in range(H):
        for x in range(W):
            rim = x == 0 or y == 0 or x == W - 1 or y == H - 1
            c = GOLD[1] if rim else (IVORY[1] if (y // 3) % 2 else IVORY[0])
            p[12 + x, y] = c + (255,)
    # Edges: left/right strips at x 24, 25 (1 x 22); top/bottom strips at y 22, 23 (12 x 1).
    for y in range(H):
        p[24, y] = GOLD[2] + (255,)
        p[25, y] = GOLD[1] + (255,)
    for x in range(W):
        p[x, 22] = GOLD[3] + (255,)
        p[x, 23] = GOLD[1] + (255,)
    # Grip: leather wrapped in gold, 6 x 6 at x 26..31, y 0..5.
    for y in range(6):
        for x in range(6):
            p[26 + x, y] = (GOLD[2] if y in (0, 5) else LEATHER[2 + (x + y) % 2]) + (255,)
    return tex, sun


def u(px_):
    return px_ / 2  # 32 px texture: two texels per uv unit


def shield_models(zf):
    plate_faces = {
        # The face points to model north (vanilla's shield is drawn flipped on y and z): the sun on north.
        "north": {"uv": [u(0), u(0), u(12), u(22)], "texture": "#shield"},
        "south": {"uv": [u(12), u(0), u(24), u(22)], "texture": "#shield"},
        "east": {"uv": [u(24), u(0), u(25), u(22)], "texture": "#shield"},
        "west": {"uv": [u(25), u(0), u(26), u(22)], "texture": "#shield"},
        "up": {"uv": [u(0), u(22), u(12), u(23)], "texture": "#shield"},
        "down": {"uv": [u(0), u(23), u(12), u(24)], "texture": "#shield"},
    }
    grip = {"uv": [u(26), u(0), u(32), u(6)], "texture": "#shield"}
    grip_n = {"uv": [u(26), u(0), u(28), u(6)], "texture": "#shield"}
    elements = [
        {"from": [-6, -11, 1], "to": [6, 11, 2], "faces": plate_faces},
        {"from": [-6, -11, 0.94], "to": [6, 11, 0.94], "light_emission": 15,
         "faces": {"north": {"uv": [u(0), u(0), u(12), u(22)], "texture": "#sun"}}},
        {"from": [-1, -3, -5], "to": [1, 3, 1], "faces": {
            "east": grip, "west": grip, "north": grip_n, "south": grip_n, "up": grip_n, "down": grip_n}},
    ]
    for name in ("shield", "shield_blocking"):
        vanilla = json.loads(zf.read(f"assets/minecraft/models/item/{name}.json"))
        write_json(f"models/item/light_{name}.json", {
            "gui_light": "front",
            "textures": {"shield": "lightborn:item/light_shield", "sun": "lightborn:item/light_shield_sun",
                         "particle": "lightborn:item/light_shield"},
            "display": vanilla["display"],
            "elements": elements,
        })
    write_json("items/light_shield.json", {"model": {
        "type": "minecraft:condition", "property": "minecraft:using_item",
        "on_false": {"type": "minecraft:model", "model": "lightborn:item/light_shield"},
        "on_true": {"type": "minecraft:model", "model": "lightborn:item/light_shield_blocking"},
    }})


def main():
    sword()
    tex, sun = shield_textures()
    tex.save(T / "light_shield.png")
    sun.save(T / "light_shield_sun.png")
    with zipfile.ZipFile(JAR) as zf:
        shield_models(zf)
    lang = ASSETS / "lang/en_us.json"
    j = json.loads(lang.read_text(encoding="utf-8"))
    j["item.lightborn.light_shield"] = "Light Shield"
    lang.write_text(json.dumps(j, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("drew the Light Sword and the Light Shield")


if __name__ == "__main__":
    main()
