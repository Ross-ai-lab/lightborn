"""Generates every texture and model/item JSON the mod needs.

Tier gear = Minecraft's own netherite sprites gradient-mapped to a tier palette (keeps vanilla shading).
Staffs are drawn procedurally. Re-run after changing a palette:
    py -3.14 -I tools/make_textures.py <minecraft-merged-deobf jar (it reads vanilla data too)> <project root>
"""
import colorsys
import io
import json
import math
import sys
import zipfile
from pathlib import Path

from PIL import Image

JAR, ROOT = Path(sys.argv[1]), Path(sys.argv[2])
ASSETS = ROOT / "src/main/resources/assets/lightborn"



def read(zf, path):
    return Image.open(io.BytesIO(zf.read(path))).convert("RGBA")


def lum(p):
    return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]


def sat(p):
    mx, mn = max(p[:3]), min(p[:3])
    return 0 if mx == 0 else (mx - mn) / mx


def ramp_at(ramp, t):
    t = max(0.0, min(1.0, t)) * (len(ramp) - 1)
    i = min(int(t), len(ramp) - 2)
    f = t - i
    a, b = ramp[i], ramp[i + 1]
    return tuple(round(a[k] + (b[k] - a[k]) * f) for k in range(3))


def gradient_map(img, ramp, handle=None, keep_handle=False):
    px = [p for p in img.get_flattened_data() if p[3] > 0]
    metal = [lum(p) for p in px if not (keep_handle and sat(p) > 0.3)]
    lo, hi = min(metal), max(metal)
    out = Image.new("RGBA", img.size)
    for y in range(img.height):
        for x in range(img.width):
            p = img.getpixel((x, y))
            if p[3] == 0:
                continue
            if keep_handle and sat(p) > 0.3:
                t = (lum(p) - 15) / 80
                c = tuple(round(handle[0][k] + (handle[1][k] - handle[0][k]) * max(0, min(1, t))) for k in range(3))
            else:
                c = ramp_at(ramp, (lum(p) - lo) / max(1, hi - lo))
            out.putpixel((x, y), c + (p[3],))
    return out


def save(img, rel):
    path = ASSETS / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


def write_json(rel, data):
    path = ASSETS / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def item_model(name, parent):
    write_json(f"models/item/{name}.json", {"parent": parent, "textures": {"layer0": f"lightborn:item/{name}"}})
    write_json(f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"lightborn:item/{name}"}})


# Ten hand-drawn staffs. Letters: k outline, 1/2/3 shaft dark/mid/light, a/b/c metal dark/mid/light,
# g/h/i/w gem dark/mid/light/highlight, * soft glow (alpha), x/y/z extra (per staff), '.' empty.
STAFF_ART = {
    # Iron band, one violet shard.
    "apprentice_staff": [
        "............kk..",
        "...........kwik.",
        "..........kwihk.",
        ".........kihhgk.",
        "........kihggk..",
        ".......kbhgkk...",
        "......cbak......",
        ".......31.......",
        "......31........",
        ".....31.........",
        "....31..........",
        "...31...........",
        "..31............",
        ".31.............",
        "a1..............",
        "aa..............",
    ],
    # Gold ring, cyan orb floating inside it.
    "adept_staff": [
        "..........cbbc..",
        ".........b....b.",
        "........c..wi..b",
        "........b.ihhg.b",
        "........b.hhgg.a",
        ".........b.gg.a.",
        "........cba..a..",
        ".......3baa.....",
        "......31........",
        ".....31.........",
        "....31..........",
        "...31...........",
        "..31............",
        ".31.............",
        "c1..............",
        "bb..............",
    ],
    # Dark wood, gold crescent horns cradling a red gem.
    "archmage_staff": [
        ".........c....c.",
        "........cb....bc",
        "........b..w...b",
        "........b.wih..b",
        "........bkihgk.a",
        ".........bkggkb.",
        "........cbbaab..",
        ".......3ab......",
        "......31........",
        ".....31.........",
        "....31..........",
        "...31...........",
        "..31............",
        ".31.............",
        "b1..............",
        "aa..............",
    ],
    # Thornwood: a twisted green-brown branch with thorns and leaves round a green bud.
    "thornwood_staff": [
        "..........xy....",
        ".........xyy.x..",
        "........yx.whyy.",
        ".......z.yxihgx.",
        "......z..xhggz..",
        ".......2.z3gz...",
        "......z21.z.....",
        ".......21z......",
        "......31........",
        ".....312........",
        "...z31..........",
        "...31...........",
        "..31z...........",
        ".31.............",
        "31..............",
        "1...............",
    ],
    # Bone: knuckled bone shaft, a small skull with glowing eyes.
    "bone_staff": [
        "..........xxxx..",
        ".........xxxxxx.",
        ".........xhxxhx.",
        ".........xgxxgy.",
        "..........xxxy..",
        "..........yxyx..",
        ".......xyy......",
        ".......xy.......",
        "......xy........",
        ".....yx.........",
        "....xyy.........",
        "...xy...........",
        "..xy............",
        ".xy.............",
        "yyy.............",
        "yy..............",
    ],
    # Storm rod: banded steel rod, two prongs and a spark arcing between them.
    "storm_staff": [
        "...........c....",
        "..........cb.*..",
        "........c.bw.w..",
        ".......cb..iwiw*",
        ".......b.*.wcbc.",
        "........bcbbba..",
        ".......cbaa.....",
        ".......ba.......",
        "......cb........",
        ".....ba.........",
        "....ab..........",
        "...cb...........",
        "..ba............",
        ".ab.............",
        "cb..............",
        "a...............",
    ],
}

wood = [(58, 38, 20), (112, 76, 42), (150, 108, 62)]
dark_wood = [(30, 18, 14), (70, 40, 30), (98, 62, 44)]
iron = [(90, 90, 96), (170, 170, 178), (230, 230, 236)]
gold = [(120, 70, 10), (220, 170, 40), (255, 236, 120)]
steel = [(60, 66, 80), (130, 140, 158), (210, 218, 232)]
silver = [(110, 116, 130), (190, 196, 210), (245, 248, 255)]

# name: shaft(3), metal(3), gem(4), glow, extras {x,y,z}
STAFF_PAL = {
    "apprentice_staff": (wood, iron, [(70, 30, 120), (140, 80, 210), (210, 170, 255), (255, 240, 255)], (190, 140, 255), {}),
    "adept_staff": (wood, gold, [(20, 90, 110), (60, 200, 210), (170, 250, 245), (255, 255, 255)], (120, 240, 240), {}),
    "archmage_staff": (dark_wood, gold, [(110, 10, 20), (210, 40, 50), (255, 140, 130), (255, 230, 220)], (255, 90, 80), {}),
    "thornwood_staff": ([(40, 46, 18), (78, 70, 30), (112, 104, 46)], iron, [(30, 110, 30), (80, 190, 60), (170, 240, 120), (240, 255, 210)],
                        (140, 255, 120), {"x": (46, 120, 40), "y": (90, 170, 60), "z": (170, 150, 90)}),
    "bone_staff": (wood, iron, [(120, 0, 0), (255, 60, 40), (255, 160, 120), (255, 255, 255)], (255, 80, 60),
                   {"x": (232, 226, 200), "y": (176, 168, 138)}),
    "storm_staff": (steel, steel, [(200, 160, 0), (255, 220, 60), (255, 250, 170), (255, 255, 255)], (255, 240, 120), {}),
}


def staff(name):
    """One staff from its hand-placed grid and palette (owner: every staff must look like a different object)."""
    shaft, metal, gem, glow, extra = STAFF_PAL[name]
    colors = {"k": (24, 16, 30), "1": shaft[0], "2": shaft[1], "3": shaft[2], "a": metal[0], "b": metal[1], "c": metal[2],
              "g": gem[0], "h": gem[1], "i": gem[2], "w": gem[3], **extra}
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(STAFF_ART[name]):
        for x, ch in enumerate(row):
            if ch == "*":
                img.putpixel((x, y), tuple(glow) + (150,))
            elif ch in colors:
                img.putpixel((x, y), tuple(colors[ch]) + (255,))
    return img


def dark_shard():
    rows = [
        "................",
        "..........k.....",
        ".........kck....",
        "........kcgbk...",
        ".......kcgbdk...",
        "......kcwgbdk...",
        ".....kcwgbddk...",
        "....kcggbddk....",
        "...kcggdddk.....",
        "..kcgdddk.......",
        "..kgbddk........",
        ".kgbddk.........",
        ".kdddk..........",
        "..kkk...........",
        "................",
        "................",
    ]
    colors = {
        "k": (24, 13, 37), "d": (55, 29, 79), "b": (91, 49, 126),
        "g": (144, 79, 187), "c": (192, 125, 226), "w": (239, 194, 251),
    }
    img = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        for x, pixel in enumerate(row):
            if pixel in colors:
                img.putpixel((x, y), colors[pixel] + (255,))
    return img


# Every spell's tome art: cover, glow, core colours and a 5x5 emblem.
TOMES = {
    "radiant_pulse": ((92, 70, 25), (255, 213, 100), (255, 251, 222), [".###.", "#...#", "#.o.#", "#...#", ".###."]),
    "arcane_bolt": ((92, 52, 156), (204, 166, 255), (255, 244, 255), ["..#..", ".#o#.", "#ooo#", ".#o#.", "..#.."]),
    "fireball": ((132, 32, 22), (255, 142, 34), (255, 238, 150), ["..#..", ".#o#.", ".#o##", "#ooo#", ".###."]),
    "frost_nova": ((40, 82, 152), (164, 230, 255), (255, 255, 255), ["#.#.#", ".###.", "##o##", ".###.", "#.#.#"]),
    "chain_lightning": ((52, 60, 92), (255, 236, 110), (255, 255, 235), ["..##.", ".##..", "#####", "..##.", ".##.."]),
    "meteor": ((64, 30, 26), (255, 96, 30), (255, 206, 90), [".###.", "#ooo#", "#ooo#", "#ooo#", ".###."]),
    "blink": ((22, 92, 82), (120, 255, 204), (240, 255, 250), [".###.", "#...#", "#.o.#", "#..##", ".##.."]),
    # Defence, farming, mining: a shield, a cross of light, a sprout, a sickle, a pick, an eye.
    "stoneskin": ((84, 88, 96), (214, 218, 226), (255, 255, 255), ["#####", "#ooo#", "#ooo#", ".#o#.", "..#.."]),
    "mending_light": ((150, 120, 40), (255, 236, 140), (255, 255, 230), ["..#..", "..o..", "#ooo#", "..o..", "..#.."]),
    "verdant_surge": ((40, 92, 34), (130, 230, 90), (230, 255, 190), [".#.#.", "#o#o#", ".#o#.", "..o..", "..#.."]),
    "reap": ((110, 82, 34), (240, 204, 90), (255, 248, 200), [".###.", "#..o#", "...o#", "..##.", ".#..."]),
    "excavate": ((92, 58, 34), (196, 160, 120), (240, 228, 210), ["####.", "..o##", ".#o.#", "#...#", "#...."]),
    "ore_sight": ((30, 40, 72), (90, 236, 220), (255, 255, 255), [".###.", "#ooo#", "#o#o#", "#ooo#", ".###."]),
    # Felling an axe, Herd Call a heart, Forge Fire an anvil, Gathering a magnet, Homeward a house, Skies a rain cloud.
    "felling": ((96, 64, 30), (214, 168, 96), (255, 236, 196), ["##...", "##o..", "#.o..", "...o.", "....o"]),
    "herd_call": ((140, 54, 74), (255, 140, 170), (255, 230, 236), [".#.#.", "#o#o#", "#ooo#", ".#o#.", "..#.."]),
    "forge_fire": ((110, 40, 20), (255, 120, 40), (255, 220, 120), ["####.", ".ooo#", "..#..", ".###.", "#####"]),
    "gathering": ((70, 70, 96), (230, 70, 70), (230, 236, 255), ["#...#", "#...#", "#...#", "o#.#o", ".###."]),
    "homeward": ((60, 46, 110), (255, 214, 120), (255, 250, 220), ["..#..", ".#o#.", "#ooo#", ".o#o.", ".o#o."]),
    "skies": ((46, 74, 120), (220, 236, 255), (120, 190, 255), [".##..", "#####", "#####", ".o.o.", "o.o.."]),
    # The second shelf: a black star, an ice lance, a breaking wave, spears from the ground, a drop of blood, plague
    # spots, a paw, a crown, a mirror, a rising gust.
    "gravity_well": ((30, 14, 44), (176, 96, 255), (24, 4, 36), [".###.", "##o##", "#ooo#", "##o##", ".###."]),
    "glacial_spike": ((44, 96, 140), (196, 244, 255), (255, 255, 255), ["...o#", "..o#.", ".o#..", "o#...", "#...."]),
    "water_splash": ((22, 52, 112), (84, 168, 255), (225, 246, 255), ["..##.", ".#..#", "#.o..", "#oo..", "ooooo"]),
    "earthshatter": ((86, 60, 38), (214, 164, 96), (255, 226, 170), ["..#..", "..#.#", "#.#.#", "#o#o#", "ooooo"]),
    "siphon": ((70, 10, 20), (224, 48, 58), (255, 170, 170), ["..#..", ".###.", "#ooo#", "#ooo#", ".###."]),
    "contagion": ((38, 58, 20), (122, 204, 51), (220, 255, 150), ["#...#", ".o.o.", "..#..", ".o.o.", "#...#"]),
    "spirit_wolves": ((18, 38, 70), (90, 200, 255), (230, 250, 255), ["#.#.#", ".....", ".###.", "#ooo#", ".###."]),
    "mirror_ward": ((88, 98, 118), (230, 245, 255), (255, 255, 255), [".###.", "#o..#", "#.o.#", "#..o#", ".###."]),
    "updraft": ((64, 104, 124), (220, 250, 255), (255, 255, 255), ["..#..", ".###.", "#.#.#", "..#..", ".o#o."]),
    "souls": ((30, 54, 66), (92, 224, 218), (236, 255, 238), [".###.", "#o.o#", "#ooo#", ".#o#.", "..#.."]),
    # Treasure Thread: a thread of light running down into a little chest (owner, 2026-11-16).
    "treasure_thread": ((78, 56, 20), (255, 204, 84), (255, 248, 210), ["o....", ".o...", "..###", "..#o#", "..###"]),
    # Flight: a staff riding the wind, gusts trailing behind (owner, 2026-11-18).
    "flight": ((52, 40, 96), (186, 160, 255), (250, 244, 255), ["....#", "o..#.", "..#..", ".#.o.", "#.o.."]),
    # Cage: iron bars with a monster's two red eyes behind them (owner, 2026-11-18).
    "cage": ((62, 66, 76), (206, 212, 224), (255, 96, 70), ["#####", "#.#.#", "#o#o#", "#.#.#", "#####"]),
    # Taunt: a totem of light with its arms spread, glowing at the heart (owner, 2026-11-18).
    "taunt": ((120, 92, 30), (255, 226, 120), (255, 255, 230), [".###.", "##o##", ".#o#.", ".#o#.", ".###."]),
    # The third shelf (owner, 2026-11-21; drawn by make_third_shelf.py): a rayed sun, three motes, a spinning halo,
    # falling stars, a brick wall, a dome, a stair of light, an owl's eyes, a sprout over furrows, a horse's head.
    "sunbeam": ((120, 90, 20), (255, 220, 90), (255, 255, 220), ["#.#.#", ".ooo.", "#ooo#", ".ooo.", "#.#.#"]),
    "wisps": ((30, 60, 90), (170, 230, 255), (255, 255, 255), ["o...#", "..#..", "#...o", "..o..", ".#..."]),
    "halo_disc": ((110, 80, 20), (255, 210, 90), (255, 250, 210), [".#o#.", "o...#", "#...#", "#...o", ".#o#."]),
    "starfall": ((20, 24, 60), (170, 190, 255), (255, 255, 255), ["#..#.", ".o..o", "..#..", "#..o.", ".o..#"]),
    "stone_wall": ((80, 80, 84), (200, 200, 205), (255, 236, 170), ["#####", "#o#o#", "#####", "o#o#o", "#####"]),
    "sanctuary": ((130, 100, 40), (255, 230, 150), (255, 252, 235), ["..#..", ".#o#.", "#ooo#", "#ooo#", "#####"]),
    "light_bridge": ((100, 80, 30), (255, 225, 120), (255, 255, 230), ["....#", "...#o", "..#o.", ".#o..", "#o..."]),
    "owl_sight": ((60, 46, 30), (255, 200, 80), (255, 255, 200), ["#...#", "##.##", "#o#o#", "#####", ".#.#."]),
    "spirit_steed": ((30, 60, 100), (160, 220, 255), (255, 255, 255), ["..##.", ".###o", "####.", "..##.", "..##."]),
    # The fourth shelf (owner, 2026-11-21; drawn by make_fourth_shelf.py): a hook on its line, a hanging mote, thorns,
    # a burning sword, a crackling orb, three circling blades, an anvil, a fish, an hourglass, twin sparks.
    "tether": ((70, 60, 40), (255, 220, 140), (255, 255, 230), ["o....", ".o...", "..o..", "...##", "..#.#"]),
    "wayglow": ((40, 40, 70), (255, 220, 120), (255, 255, 230), ["..#..", ".#o#.", "#ooo#", ".#o#.", "..#.."]),
    "thornfield": ((40, 70, 30), (140, 220, 90), (255, 236, 150), ["o.#.o", ".###.", "#.#.#", ".#.#.", "#####"]),
    "lightbrand": ((110, 70, 20), (255, 200, 90), (255, 250, 220), ["....o", "...o.", "#.o..", ".#...", "#.#.."]),
    "ball_lightning": ((30, 50, 90), (140, 200, 255), (255, 255, 255), ["#...#", ".###.", ".#o#.", ".###.", "#...#"]),
    "blade_ring": ((70, 74, 86), (220, 225, 240), (255, 240, 180), ["o.#..", "....#", "#.o.#", "#....", "..#.o"]),
    "rewind": ((60, 40, 80), (200, 160, 255), (255, 240, 255), ["#####", ".#o#.", "..o..", ".#o#.", "#####"]),
    "twincast": ((70, 30, 90), (220, 140, 255), (255, 230, 255), [".#.#.", "#o#o#", ".#.#.", ".....", "#.#.#"]),
    # The fifth and last shelf (owner, 2026-11-22).
    "cyclone": ((60, 80, 100), (210, 235, 255), (255, 255, 255), ["#####", ".###.", "..##.", "..#..", "..o.."]),
    "rune_snare": ((70, 40, 70), (240, 190, 255), (255, 240, 160), [".###.", "#...#", "#.o.#", "#...#", ".###."]),
    "dawnbreak": ((130, 58, 40), (255, 160, 90), (255, 244, 214), ["..#..", "#...#", ".ooo.", "ooooo", "#####"]),
    "emberwalk": ((90, 30, 20), (255, 120, 40), (255, 220, 120), ["..o..", ".#o#.", "#ooo#", "#####", "#.#.#"]),
    "crystal_shell": ((40, 70, 100), (150, 220, 255), (240, 255, 255), ["..#..", ".#o#.", "#.o.#", ".#o#.", "..#.."]),
    "transpose": ((50, 60, 80), (180, 220, 255), (255, 240, 200), ["o#...", "##...", "..#..", "...##", "...#o"]),
    "moongate": ((40, 30, 70), (200, 180, 255), (240, 230, 255), [".###.", "#...#", "#.o.#", "#.o.#", "#...#"]),
    "quickening": ((60, 90, 40), (180, 240, 120), (255, 250, 200), ["#.o.#", ".#o#.", "..o..", "..o..", "#####"]),
    "light_bore": ((70, 60, 50), (255, 220, 120), (255, 255, 240), ["#####", "#....", "ooooo", "#....", "#####"]),
    # The sixth shelf (owner, 2026-11-22; drawn by make_sixth_shelf.py), as reworked the same day: an ice crystal, a
    # snow cloud, a feather, a pillar from the sky, a chain, a clock face, a storm cloud's bolt, a golem, a towering
    # figure, a golden apple, an ore gem, a dragon's head breathing fire.
    "sentinel": ((30, 60, 100), (150, 220, 255), (240, 250, 255), ["..o..", ".#o#.", ".#o#.", "#.#.#", "#####"]),
    "blizzard": ((50, 80, 120), (220, 240, 255), (255, 255, 255), [".###.", "#####", ".....", "o.o.o", ".o.o."]),
    "wellspring": ((40, 90, 110), (190, 240, 255), (255, 255, 255), ["...o#", "..o#.", ".o#..", "o#...", "#...."]),
    "banish": ((110, 90, 20), (255, 230, 120), (255, 255, 240), ["..o..", "..o..", "..o..", ".#o#.", "#####"]),
    "soul_link": ((30, 50, 60), (120, 230, 220), (240, 255, 250), ["##...", "#o#..", ".#o#.", "..#o#", "...##"]),
    "time_stop": ((60, 50, 30), (255, 215, 130), (255, 255, 230), [".###.", "#.o.#", "#.oo#", "#...#", ".###."]),
    "tempest": ((30, 30, 60), (170, 190, 255), (255, 250, 160), [".###.", "#####", "..o..", ".o...", "..o.."]),
    "colossus": ((90, 80, 70), (230, 220, 200), (255, 230, 140), [".###.", ".#o#.", "#####", "#.#.#", ".#.#."]),
    "ascension": ((110, 90, 30), (255, 230, 120), (255, 255, 255), ["..o..", ".###.", "#.#.#", ".#.#.", ".#.#."]),
    "renewal": ((120, 90, 20), (255, 220, 90), (255, 250, 200), ["..#..", ".ooo.", "ooooo", "ooooo", ".ooo."]),
    "transmute": ((60, 60, 70), (100, 240, 200), (255, 215, 90), ["#.#.#", ".o#o.", "#o#o#", ".o#o.", "#.#.#"]),
    # The seventh shelf (owner, 2026-11-22; drawn by make_sixth_shelf.py): a wolf's head, a paw, a spider, a blaze's
    # core, a ghost; then the Basic eleven.
    "wolf_form": ((70, 70, 80), (220, 220, 230), (255, 220, 120), ["#...#", "##.##", "#o#o#", ".###.", "..#.."]),
    "bear_form": ((90, 60, 40), (240, 230, 210), (255, 255, 255), ["#.#.#", ".....", ".###.", "#####", ".###."]),
    "spider_form": ((50, 20, 30), (200, 60, 60), (255, 90, 90), ["#...#", ".#o#.", "##o##", ".#o#.", "#...#"]),
    "blaze_form": ((120, 60, 10), (255, 200, 60), (255, 240, 150), ["#.#.#", ".ooo.", "#ooo#", ".ooo.", "#.#.#"]),
    "spirit_walk": ((40, 60, 80), (150, 230, 255), (255, 255, 255), [".###.", "#o#o#", "#####", "#####", "#.#.#"]),
    "shrinking": ((100, 30, 30), (255, 120, 120), (255, 255, 255), ["#...#", ".#.#.", "..o..", ".#.#.", "#...#"]),
    "gills": ((20, 70, 90), (90, 210, 230), (255, 255, 255), ["..#..", ".###.", "#o###", ".###.", "..#.#"]),
    "purify": ((70, 80, 110), (230, 240, 255), (255, 255, 255), ["..#..", ".#o#.", "#ooo#", "#ooo#", ".###."]),
    "ender_pocket": ((20, 40, 40), (60, 200, 170), (200, 255, 240), [".....", "#####", "#.o.#", "#####", "#####"]),
    "workbench": ((90, 60, 30), (220, 170, 100), (255, 230, 180), ["#####", "#o#o#", "#####", "#o#o#", "#####"]),
    "enfeeble": ((50, 70, 30), (140, 190, 80), (220, 255, 160), ["..#..", "..#..", "#.#.#", ".###.", "..o.."]),
    "splinter": ((90, 80, 40), (255, 240, 170), (255, 255, 255), ["#.#.#", ".#.#.", "..o..", "..o..", "....."]),
    "boulder": ((70, 70, 70), (180, 180, 180), (240, 240, 240), [".###.", "##o##", "#o###", "#####", ".###."]),
    "frostwalk": ((40, 80, 110), (190, 230, 255), (255, 255, 255), ["#.#.#", ".###.", "##o##", ".###.", "#.#.#"]),
    "fright": ((40, 30, 50), (170, 120, 200), (255, 230, 255), [".###.", "#o#o#", "#####", ".#.#.", "....."]),
    # Warden Form (2026-11-23): the warden's head, its glowing horns and soul.
    "warden_form": ((10, 40, 50), (40, 200, 190), (180, 255, 250), ["#.#.#", "##o##", "#o#o#", "#####", ".#.#."]),
    # The eighth shelf (owner, 2026-11-23; Bounty and Exile deleted the same day).
    "comet_dash": ((20, 30, 70), (255, 150, 50), (255, 240, 190), ["....#", "..o##", ".o##.", "o#...", "o...."]),
    "doom": ((40, 10, 50), (140, 40, 180), (220, 160, 255), [".###.", "#o#o#", "#####", ".#.#.", ".o.o."]),
    "lifebloom": ((40, 70, 30), (240, 130, 200), (255, 220, 240), [".#.#.", "#o#o#", ".#o#.", "..#..", ".###."]),
}


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def tome(cover, glow, core, emblem):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    k = (24, 16, 30, 255)
    for y in range(0, 16):
        for x in range(1, 15):
            img.putpixel((x, y), k)
    gold, gold_dark = (232, 190, 80, 255), (150, 104, 30, 255)
    for y in range(1, 15):
        img.putpixel((2, y), shade(cover, 0.55) + (255,))
        img.putpixel((3, y), shade(cover, 0.75) + (255,))
        for x in range(4, 13):
            c = cover
            if y == 1 or x == 4:
                c = shade(cover, 1.3)
            if y == 14 or x == 12:
                c = shade(cover, 0.8)
            img.putpixel((x, y), c + (255,))
    for y in (3, 12):  # spine bands
        img.putpixel((2, y), gold_dark)
        img.putpixel((3, y), gold)
    for y in range(2, 15):  # page edge
        img.putpixel((13, y), (238, 228, 200, 255) if y % 2 else (206, 192, 160, 255))
    for (x, y) in ((12, 1), (12, 14), (11, 1), (12, 2), (11, 14), (12, 13)):  # gold corners
        img.putpixel((x, y), gold)
    for ey, row in enumerate(emblem):
        for ex, ch in enumerate(row):
            px, py = 6 + ex, 5 + ey
            if ch == "#":
                img.putpixel((px, py), glow + (255,))
            elif ch == "o":
                img.putpixel((px, py), core + (255,))
    return img


def open_tome(cover, glow, core, emblem):
    """The tome as held: open flat, pages up, cover lip below, the spell's emblem glowing on the right page."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    cov = cover + (255,)
    for x in range(0, 16):  # cover lip under the pages, darker toward the outer corners
        img.putpixel((x, 12), cov if 2 <= x <= 13 else shade(cover, 0.75) + (255,))
        img.putpixel((x, 13), shade(cover, 0.7) + (255,))
    for y in range(4, 12):
        img.putpixel((0, y), shade(cover, 0.85) + (255,))
        img.putpixel((15, y), shade(cover, 0.85) + (255,))
    for x in range(1, 15):  # pages, dipping toward the spine
        top = 4 if 6 <= x <= 9 else 3
        bottom = 12 if x in (7, 8) else 11
        for y in range(top, bottom + 1):
            if x in (7, 8):
                c = (150, 128, 96)
            elif x in (6, 9):
                c = (206, 192, 160)
            elif x in (1, 14) or y == bottom:
                c = (222, 210, 180)
            else:
                c = (240, 232, 206)
            img.putpixel((x, y), c + (255,))
    ink = shade(glow, 0.55) + (255,)
    for y, (a, b) in ((5, (2, 5)), (7, (2, 4)), (9, (2, 5))):  # lines of script on the left page
        for x in range(a, b + 1):
            img.putpixel((x, y), ink)
    for ey, row in enumerate(emblem):  # the emblem on the right page, in cover colour so pale glows still read on paper
        for ex, ch in enumerate(row):
            if ch == "#":
                img.putpixel((10 + ex, 5 + ey), shade(cover, 1.5) + (255,))
            elif ch == "o":
                img.putpixel((10 + ex, 5 + ey), glow + (255,))
    k = (24, 16, 30, 255)
    filled = {(x, y) for x in range(16) for y in range(16) if img.getpixel((x, y))[3]}
    for x in range(16):
        for y in range(16):
            if (x, y) not in filled and any((x + dx, y + dy) in filled for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                img.putpixel((x, y), k)
    for (x, y) in ((11, 1), (13, 0), (14, 2)):  # sparks rising off the emblem
        img.putpixel((x, y), glow + (255,))
    return img


# Held tomes: the open sprite, drawn at about half vanilla's hand size so it covers less of the screen.
OPEN_TOME_DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.3, 0.3, 0.3]},
    "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.3, 0.3, 0.3]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 4.6, 1.13], "scale": [0.26, 0.26, 0.26]},
    "firstperson_lefthand": {"rotation": [0, -90, 25], "translation": [1.13, 4.6, 1.13], "scale": [0.26, 0.26, 0.26]},
}
HAND_CONTEXTS = ["firstperson_lefthand", "firstperson_righthand", "thirdperson_lefthand", "thirdperson_righthand"]


def tome_models(name):
    """Closed tome in the inventory, on the ground and in frames; the open one in hand."""
    write_json(f"models/item/{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"lightborn:item/{name}"}})
    write_json(f"models/item/{name}_open.json", {"parent": "minecraft:item/generated",
                                                 "textures": {"layer0": f"lightborn:item/{name}_open"}, "display": OPEN_TOME_DISPLAY})
    intact = {
        "type": "minecraft:select", "property": "minecraft:display_context",
        "cases": [{"when": HAND_CONTEXTS, "model": {"type": "minecraft:model", "model": f"lightborn:item/{name}_open"}}],
        "fallback": {"type": "minecraft:model", "model": f"lightborn:item/{name}"}}
    write_json(f"items/{name}.json", {"model": intact})  # tomes never wear (owner, 2026-11-16)


# A small sun with four rays and four glints, for the chest's sides (owner, 2026-11-18: never draw runes).
SMALL_SUN = ["#.#.#", ".ooo.", "#ooo#", ".ooo.", "#.#.#"]
SUN = ["..#..#..", "...##...", ".#oooo#.", "##oooo##", "##oooo##", ".#oooo#.", "...##...", "..#..#.."]


# Chest of Light, made of flowing light (owner, 2026-11-16). Every face is an animated strip (CHEST_FRAMES frames,
# interpolated), drawn full-bright by the block model (light_emission 15): currents of white light stream up an amber
# body, a bright pulse runs round the gilt rim, the small suns and the big one breathe. Over it a translucent shell of rising
# wisps (sides) and a turning spiral (top); 26.x picks the translucent layer from the sprite's alpha, so no code is
# needed. Faces sit where the 14x14 chest samples them: sides rows 2-15, top/bottom 1-14, columns 1-14.
CHEST_FRAMES = 32
CHEST_DEEP, CHEST_BODY, CHEST_CREST = (238, 164, 58), (255, 216, 128), (255, 253, 240)
CHEST_GOLD, CHEST_GOLD_HI, CHEST_GOLD_LO = (232, 180, 64), (255, 236, 160), (176, 112, 30)


def _mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(round(a[k] + (b[k] - a[k]) * t) for k in range(3))


def _flow(x, y, t, up=True):
    """0..1 brightness of the body's light currents: wavering ribbons whose crests travel up the face."""
    tau = 2 * math.pi
    sway = 0.18 * math.sin(tau * (y / 16 + t)) + 0.08 * math.sin(tau * (2 * y / 16 - t))
    ribbon = 0.5 + 0.5 * math.sin(tau * (2 * x / 16 + sway))
    rise = 0.5 + 0.5 * math.sin(tau * ((y / 16) + (t if up else -t)) * 1 + 2.1 * ribbon)
    return 0.55 * ribbon * rise + 0.45 * rise ** 3


def _rim_pulse(x, y, x0, y0, x1, y1, t):
    """Brightness of a pulse running clockwise round the rim, 0..1."""
    w, h = x1 - x0, y1 - y0
    if y == y0:
        d = x - x0
    elif x == x1:
        d = w + (y - y0)
    elif y == y1:
        d = w + h + (x1 - x)
    else:
        d = 2 * w + h + (y1 - y)
    per = 2 * (w + h)
    best = 0.0
    for head in (t, t + 0.5):  # two pulses, opposite each other
        gap = (d / per - head) % 1.0
        best = max(best, math.exp(-((min(gap, 1 - gap) * per) / 2.2) ** 2))
    return best


def _strip(draw):
    img = Image.new("RGBA", (16, 16 * CHEST_FRAMES), (0, 0, 0, 0))
    for f in range(CHEST_FRAMES):
        t = f / CHEST_FRAMES
        for y in range(16):
            for x in range(16):
                img.putpixel((x, f * 16 + y), draw(x, y, t))
    return img


def _anim(rel, frametime=2):
    path = ASSETS / (rel + ".mcmeta")
    path.write_text(json.dumps({"animation": {"frametime": frametime, "interpolate": True}}) + "\n", encoding="utf-8")


def chest_face(x0, y0, x1, y1, seam=None, glyph=None, radial=False):
    marks = {}
    if glyph:
        gx, gy, rows = glyph
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch != ".":
                    marks[(gx + i, gy + j)] = ch
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2

    def draw(x, y, t):
        if radial:  # rings of light washing outward from the sun
            r = math.hypot(x - cx, y - cy)
            ang = math.atan2(y - cy, x - cx)
            v = 0.5 + 0.5 * math.sin(2 * math.pi * (r / 5 - t) + 0.6 * math.sin(3 * ang + 2 * math.pi * t))
            v = 0.6 * v + 0.4 * v ** 4
        else:
            v = _flow(x, y, t)
        col = _mix(CHEST_DEEP, CHEST_BODY, v * 1.6) if v < 0.62 else _mix(CHEST_BODY, CHEST_CREST, (v - 0.62) / 0.38)
        breathe = 0.5 + 0.5 * math.sin(2 * math.pi * t)
        on_rim = (y in (y0, y1) and x0 <= x <= x1) or (x in (x0, x1) and y0 <= y <= y1)
        if on_rim:
            base = CHEST_GOLD_HI if y == y0 else CHEST_GOLD_LO if y == y1 else CHEST_GOLD
            col = _mix(base, CHEST_CREST, _rim_pulse(x, y, x0, y0, x1, y1, t))
        elif seam is not None and y in (seam, seam + 1) and x0 <= x <= x1:
            p = _rim_pulse(x, y0, x0, y0, x1, y1, t)  # the lid seam carries the top pulse along
            col = _mix(CHEST_GOLD_LO if y == seam else CHEST_GOLD_HI, CHEST_CREST, p)
        elif (x, y) in marks:
            col = _mix(CHEST_GOLD, CHEST_CREST, breathe) if marks[(x, y)] == "#" else _mix(CHEST_CREST, (255, 255, 255), breathe)
        return col + (255,)

    return draw


def _wisps(x, y, t):
    """The translucent shell over the sides: soft wisps of light rising, mostly clear."""
    tau = 2 * math.pi
    lane = 0.5 + 0.5 * math.sin(tau * (3 * x / 16 + 0.25 * math.sin(tau * (y / 16 + 2 * t))))
    climb = 0.5 + 0.5 * math.sin(tau * (y / 16 + 2 * t) + 1.3 * math.sin(tau * x / 16))
    a = lane ** 2 * climb ** 3
    # Crisp, near-opaque streaks: a translucent sprite only tints what is behind it and cannot add light, so soft
    # wisps read as grey smudges against dark walls; bright pixels with a thin soft edge read as light.
    if a < 0.30:
        return (0, 0, 0, 0)
    alpha = 150 if a < 0.38 else 240
    return _mix(CHEST_BODY, CHEST_CREST, 0.4 + a) + (alpha,)


def _spiral(x, y, t):
    """The translucent shell over the lid: three arms of light turning slowly round the sun."""
    r = math.hypot(x - 7.5, y - 7.5)
    ang = math.atan2(y - 7.5, x - 7.5)
    arm = 0.5 + 0.5 * math.sin(3 * ang + r * 0.9 - 2 * math.pi * t)
    a = arm ** 3 * min(1.0, r / 5)
    if a < 0.32:
        return (0, 0, 0, 0)
    alpha = 150 if a < 0.42 else 240
    return _mix(CHEST_BODY, CHEST_CREST, 0.4 + a) + (alpha,)


def light_chest():
    faces = {
        "light_chest_side": chest_face(1, 2, 14, 15, seam=6, glyph=(6, 9, SMALL_SUN)),
        "light_chest_front": chest_face(1, 2, 14, 15, seam=6),
        "light_chest_top": chest_face(1, 1, 14, 14, glyph=(4, 4, SUN), radial=True),
        "light_chest_shell": _wisps,
        "light_chest_shell_top": _spiral,
    }
    for name, draw in faces.items():
        save(_strip(draw), f"textures/block/{name}.png")
        _anim(f"textures/block/{name}.png")
    bottom = Image.new("RGBA", (16, 16), CHEST_GOLD_LO + (255,))
    save(bottom, "textures/block/light_chest_bottom.png")
    latch = Image.new("RGBA", (16, 16), CHEST_GOLD + (255,))
    for y in range(4):
        latch.putpixel((0, y), CHEST_GOLD_HI + (255,))
    latch.putpixel((1, 3), CHEST_GOLD_LO + (255,))
    save(latch, "textures/block/light_chest_latch.png")
    side = {"texture": "#side"}
    shell = {"texture": "#shell"}
    write_json("models/block/light_chest.json", {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "particle": "lightborn:block/light_chest_side",
            "top": "lightborn:block/light_chest_top",
            "side": "lightborn:block/light_chest_side",
            "front": "lightborn:block/light_chest_front",
            "bottom": "lightborn:block/light_chest_bottom",
            "latch": "lightborn:block/light_chest_latch",
            "shell": "lightborn:block/light_chest_shell",
            "shell_top": "lightborn:block/light_chest_shell_top",
        },
        "elements": [
            {"from": [1, 0, 1], "to": [15, 14, 15], "light_emission": 15, "shade": False,
             "faces": {"north": {"texture": "#front"}, "south": side, "east": side, "west": side,
                       "up": {"texture": "#top"}, "down": {"texture": "#bottom", "cullface": "down"}}},
            {"from": [7, 7, 0], "to": [9, 11, 1], "light_emission": 15, "shade": False,
             "faces": {"north": {"uv": [0, 0, 2, 4], "texture": "#latch"},
                       "east": {"uv": [0, 0, 1, 4], "texture": "#latch"},
                       "west": {"uv": [0, 0, 1, 4], "texture": "#latch"},
                       "up": {"uv": [0, 0, 2, 1], "texture": "#latch"},
                       "down": {"uv": [0, 0, 2, 1], "texture": "#latch"}}},
            {"from": [0.5, 0, 0.5], "to": [15.5, 15, 15.5], "light_emission": 15, "shade": False,
             "faces": {"north": shell, "south": shell, "east": shell, "west": shell,
                       "up": {"texture": "#shell_top"}}},
        ],
    })


# Skill-tree backdrop: figured walnut planks (512 px, shown at 2 texels per GUI unit so it is 1:1 at GUI scale 2) and a
# carved, gilded rosette for the frame corners. Everything is periodic, so the planks tile with no visible seam.
def _periodic_noise(rng, n, scale, octaves=4, stretch=1.0):
    """Tileable fractal noise; stretch > 1 draws features out along x, like wood fibres."""
    import numpy as np
    out = np.zeros((n, n))
    amp, total = 1.0, 0.0
    fy, fx = np.meshgrid(np.fft.fftfreq(n), np.fft.fftfreq(n), indexing="ij")
    for o in range(octaves):
        cutoff = (2 ** o) / scale
        f = np.exp(-((fx * stretch) ** 2 + fy ** 2) / (2 * cutoff ** 2))
        layer = np.real(np.fft.ifft2(np.fft.fft2(rng.standard_normal((n, n))) * f))
        layer /= np.abs(layer).max() + 1e-9
        out += layer * amp
        total += amp
        amp *= 0.5
    return out / total


def tree_wood():
    # Owner, 2026-11-10: the figured planks (arches, knots, joints) had so many lines the tree looked blurry. Now one
    # calm dark-walnut surface: long, faint fibres and a slow drift in tone, nothing that draws a line.
    import numpy as np
    n = 512
    rng = np.random.default_rng(1913)
    drift = _periodic_noise(rng, n, 96, 2, stretch=3)
    fibre = _periodic_noise(rng, n, 6, 2, stretch=10)
    fine = _periodic_noise(rng, n, 2, 1, stretch=12)
    light = 0.5 + 0.10 * drift + 0.06 * fibre + 0.025 * fine
    light = np.clip(light, 0, 1)
    # Owner, same day: brighter. Mid-tone walnut, about twice the old light; the dark branch lines read better on it.
    ramp = np.array([(82, 55, 33), (98, 67, 41), (114, 79, 49), (130, 91, 57)], dtype=float)
    pos = light * (len(ramp) - 1)
    i0 = np.clip(np.floor(pos).astype(int), 0, len(ramp) - 2)
    f = (pos - i0)[..., None]
    rgb = ramp[i0] * (1 - f) + ramp[i0 + 1] * f
    img = np.dstack([np.clip(rgb, 0, 255).astype(np.uint8), np.full((n, n), 255, np.uint8)])
    save(Image.fromarray(img, "RGBA"), "textures/gui/tree_wood.png")


def tree_rosette():
    import numpy as np
    n = 64
    y, x = (np.mgrid[0:n, 0:n] + 0.5 - n / 2) / (n / 2)
    r, a = np.hypot(x, y), np.arctan2(y, x)
    petal_r = 0.62 + 0.18 * np.cos(8 * a)
    inner_r = 0.34 + 0.07 * np.cos(8 * a + np.pi / 8)
    h = np.zeros((n, n))
    h += np.clip((0.97 - r) * 30, 0, 1) * 0.35
    h -= np.clip(1 - np.abs(r - 0.86) * 28, 0, 1) * 0.25
    h += np.clip((petal_r - r) * 12, 0, 1) * (0.45 + 0.25 * np.cos(np.clip(r / petal_r, 0, 1) * np.pi))
    h -= np.clip(1 - np.abs(np.sin(8 * a)) * 6, 0, 1) * (r < petal_r) * (r > inner_r) * 0.18
    h += np.clip((inner_r - r) * 14, 0, 1) * 0.3
    gy, gx = np.gradient(h)
    shade = np.clip(0.6 + (-gx - gy) * 6, 0, 1)
    wood = np.array([(40, 22, 10), (110, 70, 38), (188, 138, 86)], dtype=float)
    pos = shade * 2
    i0 = np.clip(np.floor(pos).astype(int), 0, 1)
    f = (pos - i0)[..., None]
    rgb = wood[i0] * (1 - f) + wood[i0 + 1] * f
    gold = np.array([(150, 96, 20), (232, 180, 64), (255, 230, 140)], dtype=float)
    gp = np.clip(shade * 2.2 - 0.2, 0, 1.999)
    gi = np.floor(gp).astype(int)
    gf = (gp - gi)[..., None]
    grgb = gold[gi] * (1 - gf) + gold[gi + 1] * gf
    inlay = (np.abs(r - 0.86) < 0.035) | (r < 0.13) | ((np.abs(r - inner_r) < 0.03) & (r < 0.5))
    rgb = np.where(inlay[..., None], grgb, rgb)
    alpha = np.clip((1.0 - r) * n / 2, 0, 1) * 255
    img = np.dstack([np.clip(rgb, 0, 255), alpha]).astype(np.uint8)
    save(Image.fromarray(img, "RGBA"), "textures/gui/tree_rosette.png")


# Darkness zombie: vanilla's zombie skins shaded ash-black to dusk violet by brightness, eyes lit violet, plus an
# eyes-only layer the renderer draws full-bright (the way spiders' eyes glow). Vanilla eyes are exactly #1a1a1a.
DARK_RAMP = [(6, 4, 12), (20, 15, 34), (38, 30, 60), (62, 50, 92), (92, 78, 128)]
DARK_EYE, DARK_EYE_GLOW = (150, 110, 230), (226, 200, 255)


def darkness_zombie(zf):
    for src_name, out in (("zombie", "darkness_zombie"), ("zombie_baby", "darkness_zombie_baby")):
        src = read(zf, f"assets/minecraft/textures/entity/zombie/{src_name}.png")
        skin = gradient_map(src, DARK_RAMP)
        eyes = Image.new("RGBA", src.size, (0, 0, 0, 0))
        for y in range(src.height):
            for x in range(src.width):
                p = src.getpixel((x, y))
                if p[3] and p[:3] == (0x1a, 0x1a, 0x1a):
                    skin.putpixel((x, y), DARK_EYE + (255,))
                    eyes.putpixel((x, y), DARK_EYE_GLOW + (255,))
        save(skin, f"textures/entity/{out}.png")
        save(eyes, f"textures/entity/{out}_eyes.png")



# The other zombie kinds recolour skin, shirt and trousers separately, told apart by hue on vanilla's zombie skin
# (green skin, teal shirt, blue trousers; greys other than the eyes go with the trousers).
ZOMBIE_KINDS = {
    "vampire_zombie": {
        "skin": [(70, 64, 80), (118, 112, 128), (166, 160, 174), (204, 198, 210), (232, 228, 238)],
        "shirt": [(28, 4, 8), (66, 10, 18), (106, 18, 28), (146, 30, 40), (182, 50, 58)],
        "pants": [(10, 8, 12), (24, 20, 28), (40, 34, 44), (58, 50, 62), (78, 70, 82)],
        "eye": (200, 24, 32), "glow": (255, 80, 80),
    },
    "giant_zombie": {
        "skin": [(34, 38, 26), (58, 64, 42), (84, 92, 60), (110, 118, 80), (138, 144, 100)],
        "shirt": [(30, 18, 10), (58, 36, 20), (88, 56, 30), (118, 78, 44), (146, 102, 60)],
        "pants": [(22, 20, 18), (40, 36, 32), (60, 54, 48), (80, 72, 64), (100, 92, 82)],
        "eye": (232, 150, 40), "glow": None,
    },
}


def zombie_part(p):
    r, g, b = (c / 255 for c in p[:3])
    h, s, _ = colorsys.rgb_to_hsv(r, g, b)
    if s < 0.12:
        return "pants"
    h *= 360
    if 60 <= h < 160:
        return "skin"
    if 160 <= h < 210:
        return "shirt"
    return "pants"


def zombie_kind(zf, name, spec):
    for src_name, out in (("zombie", name), ("zombie_baby", f"{name}_baby")):
        src = read(zf, f"assets/minecraft/textures/entity/zombie/{src_name}.png")
        parts = {}
        for y in range(src.height):
            for x in range(src.width):
                p = src.getpixel((x, y))
                if p[3] and p[:3] != (0x1a, 0x1a, 0x1a):
                    parts.setdefault(zombie_part(p), []).append(lum(p))
        span = {k: (min(v), max(v)) for k, v in parts.items()}
        skin = Image.new("RGBA", src.size, (0, 0, 0, 0))
        eyes = Image.new("RGBA", src.size, (0, 0, 0, 0))
        for y in range(src.height):
            for x in range(src.width):
                p = src.getpixel((x, y))
                if not p[3]:
                    continue
                if p[:3] == (0x1a, 0x1a, 0x1a):
                    skin.putpixel((x, y), spec["eye"] + (255,))
                    if spec["glow"]:
                        eyes.putpixel((x, y), spec["glow"] + (255,))
                    continue
                part = zombie_part(p)
                lo, hi = span[part]
                skin.putpixel((x, y), ramp_at(spec[part], (lum(p) - lo) / max(1, hi - lo)) + (p[3],))
        save(skin, f"textures/entity/{out}.png")
        if spec["glow"]:
            save(eyes, f"textures/entity/{out}_eyes.png")



# Skeleton and spider kinds: vanilla's skin gradient-mapped, plus a full-bright eyes layer. Skeleton eye sockets are
# these four pixels on the head's front face; a skeleton kind with "cracks" also lights its brightest bone pixels.
SKELETON_EYES = [(9, 12), (10, 12), (13, 12), (14, 12)]
MONSTER_KINDS = {
    "ember_skeleton": ("skeleton/skeleton", [(12, 8, 6), (30, 20, 16), (52, 36, 28), (80, 56, 42), (110, 78, 56)],
                       (255, 150, 40), True),
    "deadeye_skeleton": ("skeleton/skeleton", [(36, 42, 54), (78, 88, 104), (132, 142, 158), (188, 196, 208), (232, 236, 244)],
                         (150, 236, 255), False),
    "weaver_spider": ("spider/spider", [(64, 60, 70), (122, 116, 128), (178, 172, 184), (216, 212, 222), (244, 242, 250)],
                      (200, 160, 255), False),
    "hunter_spider": ("spider/spider", [(28, 18, 8), (66, 42, 20), (112, 76, 36), (160, 114, 54), (206, 164, 92)],
                      (255, 196, 60), False),
}


def monster_kind(zf, name, src_path, ramp, glow, cracks):
    src = read(zf, f"assets/minecraft/textures/entity/{src_path}.png")
    skin = gradient_map(src, ramp)
    eyes = Image.new("RGBA", src.size, (0, 0, 0, 0))
    if src_path.startswith("skeleton"):
        lit = list(SKELETON_EYES)
        if cracks:
            top = max(lum(q) for q in src.get_flattened_data() if q[3])
            lit += [(x, y) for y in range(src.height) for x in range(src.width)
                    if src.getpixel((x, y))[3] and lum(src.getpixel((x, y))) >= top - 1]
        for x, y in lit:
            skin.putpixel((x, y), tuple(round(c * 0.8) for c in glow) + (255,))
            eyes.putpixel((x, y), glow + (255,))
    else:
        vanilla = read(zf, "assets/minecraft/textures/entity/spider/spider_eyes.png")
        for y in range(vanilla.height):
            for x in range(vanilla.width):
                q = vanilla.getpixel((x, y))
                if q[3]:
                    k = q[0] / 255
                    eyes.putpixel((x, y), tuple(round(c * (0.45 + 0.55 * k)) for c in glow) + (255,))
    save(skin, f"textures/entity/{name}.png")
    save(eyes, f"textures/entity/{name}_eyes.png")


# Spirit wolf: the tame wolf in pale spectral blue, and a glow layer drawn full-bright over it so it shines at night.
SPIRIT_RAMP = [(18, 44, 84), (44, 110, 170), (104, 186, 236), (184, 236, 255), (240, 252, 255)]


def spirit_wolf(zf):
    src = read(zf, "assets/minecraft/textures/entity/wolf/wolf_tame.png")
    skin = gradient_map(src, SPIRIT_RAMP)
    glow = Image.new("RGBA", src.size, (0, 0, 0, 0))
    for y in range(src.height):
        for x in range(src.width):
            p = skin.getpixel((x, y))
            if p[3]:
                glow.putpixel((x, y), tuple(round(c * 0.55) for c in p[:3]) + (255,))
    # Eyes: the wolf's two dark eye pixels on the face front ((5, 6) and (8, 6) on wolf_tame) light up white.
    for y in range(5, 8):
        for x in range(4, 10):
            if lum(src.getpixel((x, y))) < 40 and src.getpixel((x, y))[3]:
                skin.putpixel((x, y), (235, 255, 255, 255))
                glow.putpixel((x, y), (200, 250, 255, 255))
    save(skin, "textures/entity/spirit_wolf.png")
    save(glow, "textures/entity/spirit_wolf_glow.png")


# Spawn eggs (the Bestiary shows them as each kind's portrait): vanilla's egg for the base monster, recoloured.
EGGS = {
    "frostbitten_zombie": ("zombie", [(20, 42, 62), (55, 102, 135), (102, 165, 194), (184, 224, 239), (249, 254, 255)]),
    "mire_zombie": ("zombie", [(27, 30, 9), (64, 76, 25), (111, 133, 42), (176, 180, 69), (241, 224, 130)]),
    "dune_skeleton": ("skeleton", [(58, 38, 21), (114, 77, 39), (175, 131, 72), (226, 191, 132), (255, 237, 190)]),
    "canopy_spider": ("spider", [(20, 39, 21), (42, 84, 35), (91, 137, 45), (176, 198, 71), (246, 226, 108)]),
    "deep_stalker_spider": ("spider", [(12, 13, 26), (35, 32, 57), (72, 55, 88), (82, 149, 160), (171, 230, 222)]),
    "drowned_captain": ("zombie", [(10, 30, 39), (27, 66, 78), (57, 116, 127), (164, 159, 82), (246, 219, 124)]),
    "darkness_zombie": ("zombie", [(10, 6, 18), (30, 22, 48), (58, 46, 86), (98, 84, 132), (150, 136, 186)]),
    "vampire_zombie": ("zombie", [(40, 4, 10), (104, 16, 26), (158, 44, 54), (210, 170, 176), (244, 232, 236)]),
    "giant_zombie": ("zombie", [(22, 26, 16), (46, 54, 32), (76, 88, 54), (112, 122, 80), (156, 162, 112)]),
    "ember_skeleton": ("skeleton", [(16, 8, 4), (52, 26, 14), (120, 54, 18), (226, 120, 34), (255, 200, 90)]),
    "deadeye_skeleton": ("skeleton", [(30, 36, 50), (70, 82, 100), (126, 140, 160), (150, 236, 255), (236, 250, 255)]),
    "weaver_spider": ("spider", [(54, 50, 62), (110, 104, 120), (168, 162, 178), (200, 160, 255), (244, 240, 252)]),
    "hunter_spider": ("spider", [(26, 16, 6), (66, 42, 18), (122, 82, 36), (200, 150, 60), (255, 206, 90)]),
    "bat_zombie": ("bat", [(18, 26, 18), (44, 60, 40), (82, 104, 70), (140, 40, 40), (180, 200, 150)]),
}


def spawn_eggs(zf):
    for name, (base, ramp) in EGGS.items():
        src = read(zf, f"assets/minecraft/textures/item/{base}_spawn_egg.png")
        save(gradient_map(src, ramp), f"textures/item/{name}_spawn_egg.png")
        item_model(f"{name}_spawn_egg", "minecraft:item/generated")


def boss_trophies(zf):
    palettes = {
        "unbroken_plate": [(28, 23, 22), (76, 68, 61), (143, 132, 112), (195, 163, 105), (250, 227, 168)],
    }
    for name, source, parent in (
            ("unbroken_plate", "netherite_chestplate", "minecraft:item/generated"),):
        sprite = gradient_map(read(zf, f"assets/minecraft/textures/item/{source}.png"), palettes[name])
        accents = {
            "unbroken_plate": ((6, 8), (7, 7), (7, 9), (8, 7), (8, 9), (9, 8)),
        }
        for x, y in accents[name]:
            if sprite.getpixel((x, y))[3]:
                sprite.putpixel((x, y), palettes[name][-1] + (255,))
        save(sprite, f"textures/item/{name}.png")
        item_model(name, parent)
        if True:
            original = read(zf, "assets/minecraft/textures/entity/equipment/humanoid/netherite.png")
            save(gradient_map(original, palettes[name]), f"textures/entity/equipment/humanoid/{name}.png")
            write_json(f"equipment/{name}.json", {"layers": {"humanoid": [{"texture": f"lightborn:{name}"}]}})

    staff_rows = {
        "bonecallers_staff": [
            "..........kkk...", ".........kwwkk..", ".........kwpwk..", ".........kppwk..",
            "..........kwk...", "........kkwk....", ".......kwwk.....", "......kwwk......",
            ".....kwwk.......", "....kwwk........", "...kwwk.........", "..kwwk..........",
            ".kwwk...........", "kwwk............", "kkk.............", "................",
        ],
        "ashbringer": [
            "............k...", "...........kpk..", ".........kkpwk..", "........kpwwpk..",
            ".........kwwkk..", "........kpwpk...", ".......kkppk....", "......kwwkk.....",
            ".....kwwk.......", "....kwwk........", "...kwwk.........", "..kwwk..........",
            ".kwwk...........", "kwwk............", "kkk.............", "................",
        ],
    }
    colors = {
        "bonecallers_staff": {"k": (37, 25, 44), "w": (217, 197, 154), "p": (164, 79, 206)},
        "ashbringer": {"k": (45, 17, 19), "w": (240, 156, 65), "p": (255, 83, 34)},
    }
    for name, rows in staff_rows.items():
        sprite = Image.new("RGBA", (16, 16))
        for y, row in enumerate(rows):
            for x, pixel in enumerate(row):
                if pixel in colors[name]:
                    color = colors[name][pixel]
                    if pixel == "w" and (x + y) % 3 == 0:
                        color = tuple(min(255, c + 33) for c in color)
                    sprite.putpixel((x, y), color + (255,))
        save(sprite, f"textures/item/{name}.png")
        item_model(name, "minecraft:item/handheld")

    eclipse = ((40, 26, 71), (160, 111, 217), (239, 217, 255), [".##..", "#o...", "#o...", "#o...", ".##.."])
    save(tome(*eclipse), "textures/item/tome_eclipse.png")
    save(open_tome(*eclipse), "textures/item/tome_eclipse_open.png")
    tome_models("tome_eclipse")


def simple_staff():
    """The starting staff, 32 px like the Light staff: a long gnarled walking staff of dark ash wood, its head bent
    into a crook that cradles a small pale stone, a leather grip bound at hand height, knots along the shaft and an
    iron-shod foot. Drawn pixel by pixel with a lit upper edge and a shaded lower edge, like vanilla's tools."""
    import math
    img = Image.new("RGBA", (32, 32))
    px = img.load()
    OUT, DARK, MID, LIGHT, SHINE = (30, 19, 12), (74, 48, 29), (112, 76, 45), (150, 108, 66), (186, 146, 96)

    def put(x, y, c):
        if 0 <= x < 32 and 0 <= y < 32:
            px[x, y] = c + (255,) if len(c) == 3 else c

    # Shaft: from the foot (bottom left) up to the crook's neck, a slight wander so it reads as grown wood.
    shaft = []
    for i in range(0, 23):
        t = i / 22
        x = 3 + i
        y = 28 - i + round(math.sin(t * 5.5) * 0.6)
        shaft.append((x, y))
    for x, y in shaft:  # outline, then body, then light
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, 1), (1, -1), (-1, 1)):
            put(x + dx, y + dy, OUT)
    for x, y in shaft:
        put(x, y, MID)
        put(x + 1, y, DARK)
        put(x, y + 1, DARK)
        put(x - 1, y, LIGHT)
        put(x, y - 1, LIGHT)
    for x, y in shaft[2:-3:3]:
        put(x - 1, y, SHINE)
    # Knots: darker rings with a lit lip.
    for k in (6, 15):
        x, y = shaft[k]
        put(x, y, DARK)
        put(x + 1, y - 1, OUT)
        put(x - 1, y + 1, OUT)
        put(x - 1, y - 1, SHINE)
    # Leather grip bound at hand height: alternating wraps.
    for k in range(9, 13):
        x, y = shaft[k]
        c1, c2 = ((96, 58, 36), (140, 92, 58)) if k % 2 else ((70, 40, 24), (118, 76, 46))
        put(x, y, c1)
        put(x - 1, y, c2)
        put(x, y - 1, c2)
        put(x + 1, y, (52, 30, 18))
        put(x, y + 1, (52, 30, 18))
    # Iron-shod foot.
    for k in range(0, 2):
        x, y = shaft[k]
        put(x, y, (120, 124, 130))
        put(x - 1, y, (178, 182, 188))
        put(x, y - 1, (178, 182, 188))
        put(x + 1, y, (70, 72, 78))
        put(x, y + 1, (70, 72, 78))
    put(2, 30, (44, 44, 50))
    # The crook: the wood curls back over in a hook (an arc around a centre above and left of the neck).
    cx, cy, r = 24.5, 6.5, 4.2
    arc = []
    for a in range(-40, 230, 6):
        rad = math.radians(a)
        arc.append((round(cx + math.cos(rad) * r), round(cy - math.sin(rad) * r)))
    arc = list(dict.fromkeys(arc))
    for x, y in arc:
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, 1), (1, -1), (-1, 1)):
            if px[min(31, max(0, x + dx)), min(31, max(0, y + dy))][3] == 0:
                put(x + dx, y + dy, OUT)
    for x, y in arc:
        put(x, y, MID)
    for x, y in arc:
        # light on the outer, upper side of the curl; shade inside
        if y <= cy:
            put(x, y - 1 if px[x, max(0, y - 1)][:3] != OUT else y, LIGHT)
    for x, y in arc[::4]:
        put(x, y, SHINE if y < cy else DARK)
    # A curled tip ending in a small bud.
    tx, ty = arc[-1]
    put(tx, ty + 1, DARK)
    put(tx + 1, ty + 1, MID)
    # The stone the crook cradles: pale quartz with a warm glint.
    for (x, y), c in {
        (24, 6): (226, 220, 204), (25, 6): (204, 196, 178), (24, 7): (196, 186, 166), (25, 7): (168, 156, 136),
        (23, 7): (232, 226, 212), (24, 5): (246, 240, 226),
    }.items():
        put(x, y, c)
    for x, y in ((23, 6), (26, 6), (24, 8), (25, 8), (26, 7), (23, 8), (25, 5), (23, 5)):
        if px[x, y][3] == 0:
            put(x, y, OUT)
    put(24, 5, (255, 248, 230))
    return img


def phase6_assets(zf):
    from PIL import ImageDraw

    save(simple_staff(), "textures/item/simple_staff.png"); item_model("simple_staff", "minecraft:item/handheld")

    data_root = ROOT / "src/main/resources/data/lightborn"
    # The Book of Souls has no recipe: every player is given it once.
    # Every variant keeps its vanilla body's ingredient drops, in addition to Lightborn's rolled rewards.
    bodies = {"darkness_zombie": "zombie", "vampire_zombie": "zombie", "giant_zombie": "zombie",
              "ember_skeleton": "skeleton", "deadeye_skeleton": "skeleton", "weaver_spider": "spider", "hunter_spider": "spider",
              "frostbitten_zombie": "zombie", "mire_zombie": "zombie", "dune_skeleton": "skeleton",
              "canopy_spider": "spider", "deep_stalker_spider": "spider", "drowned_captain": "drowned"}
    for variant, body in bodies.items():
        target = data_root / f"loot_table/entities/{variant}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(zf.read(f"data/minecraft/loot_table/entities/{body}.json"))

    language = ASSETS / "lang/en_us.json"
    entries = json.loads(language.read_text(encoding="utf-8"))
    names = {"frostbitten_zombie": "Frostbitten Zombie", "mire_zombie": "Mire Zombie", "dune_skeleton": "Dune Skeleton",
             "canopy_spider": "Canopy Spider", "deep_stalker_spider": "Deep Stalker Spider", "drowned_captain": "Drowned Captain"}
    for kind, name in names.items():
        entries[f"entity.lightborn.{kind}"] = name
        entries[f"item.lightborn.{kind}_spawn_egg"] = f"{name} Spawn Egg"
    entries["item.lightborn.tome_souls"] = "Book of Souls"
    entries["item.lightborn.simple_staff"] = "Simple Staff"
    language.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


    for name, ramp in (("frostbitten_zombie", [(24, 42, 64), (55, 101, 131), (111, 172, 194), (214, 238, 245), (255, 255, 255)]),
                       ("mire_zombie", [(23, 29, 11), (55, 71, 24), (99, 118, 40), (163, 166, 68), (218, 211, 124)])):
        for source, suffix in (("zombie", ""), ("zombie_baby", "_baby")):
            img = gradient_map(read(zf, f"assets/minecraft/textures/entity/zombie/{source}.png"), ramp)
            d = ImageDraw.Draw(img)
            if name == "frostbitten_zombie":
                d.line([(8, 9), (12, 10), (10, 13), (14, 15)], fill=(205, 250, 255), width=1)
                d.line([(20, 21), (27, 21), (25, 23), (22, 23)], fill=(235, 251, 255), width=1)
                for x in (21, 24, 27): d.line([(x, 25), (x + 1, 28), (x, 31)], fill=(162, 220, 239))
                eyes_color = (225, 255, 255)
            else:
                d.line([(8, 8), (9, 11), (8, 14)], fill=(29, 49, 17), width=2)
                for x in (20, 23, 26): d.line([(x, 20), (x + 1, 25), (x, 30)], fill=(77, 124, 32))
                for x, y in ((11, 9), (14, 13), (22, 24), (25, 29)): d.rectangle((x, y, x + 1, y + 1), fill=(218, 169, 56))
                eyes_color = (244, 219, 89)
            eyes = Image.new("RGBA", img.size)
            for x in (9, 13):
                if img.height > 12:
                    img.putpixel((x, 12), eyes_color + (255,)); eyes.putpixel((x, 12), eyes_color + (255,))
            save(img, f"textures/entity/{name}{suffix}.png")
            save(eyes, f"textures/entity/{name}{suffix}_eyes.png")

    sand = [(62, 40, 23), (116, 80, 42), (173, 129, 77), (223, 189, 138), (255, 239, 199)]
    img = gradient_map(read(zf, "assets/minecraft/textures/entity/skeleton/skeleton.png"), sand)
    d = ImageDraw.Draw(img)
    d.rectangle((8, 8, 15, 10), fill=(232, 205, 160)); d.line((8, 9, 15, 9), fill=(149, 105, 61))
    for y in (20, 24, 28): d.line((20, y, 27, y + 2), fill=(238, 213, 168))
    d.rectangle((12, 13, 15, 14), fill=(110, 72, 40))
    eyes = Image.new("RGBA", img.size)
    for x in (9, 10, 13, 14): eyes.putpixel((x, 12), (255, 211, 105, 255))
    save(img, "textures/entity/dune_skeleton.png"); save(eyes, "textures/entity/dune_skeleton_eyes.png")

    for name, ramp in (("canopy_spider", [(17, 35, 16), (39, 74, 28), (76, 120, 39), (134, 167, 52), (213, 207, 96)]),
                       ("deep_stalker_spider", [(10, 11, 23), (25, 24, 43), (47, 38, 64), (76, 62, 99), (125, 97, 145)])):
        img = gradient_map(read(zf, "assets/minecraft/textures/entity/spider/spider.png"), ramp)
        d = ImageDraw.Draw(img)
        if name == "canopy_spider":
            for x in (4, 12, 20, 28):
                d.line([(x, 18), (x + 4, 22), (x, 26)], fill=(191, 187, 70), width=1)
                d.polygon([(x, 18), (x + 5, 18), (x + 3, 21)], fill=(49, 102, 32))
            eye_color = (242, 216, 101)
        else:
            for x in (4, 12, 20, 28): d.line([(x, 18), (x + 3, 20), (x + 1, 23)], fill=(64, 155, 162))
            eye_color = (118, 224, 220)
        eyes = read(zf, "assets/minecraft/textures/entity/spider/spider_eyes.png")
        for y in range(eyes.height):
            for x in range(eyes.width):
                if eyes.getpixel((x, y))[3] > 0:
                    eyes.putpixel((x, y), eye_color + (255,)); img.putpixel((x, y), eye_color + (255,))
        save(img, f"textures/entity/{name}.png"); save(eyes, f"textures/entity/{name}_eyes.png")

    img = gradient_map(read(zf, "assets/minecraft/textures/entity/zombie/drowned.png"),
                       [(11, 30, 39), (24, 57, 73), (44, 99, 111), (96, 162, 164), (179, 217, 200)])
    d = ImageDraw.Draw(img)
    d.rectangle((8, 8, 15, 9), fill=(42, 48, 60)); d.line((8, 10, 15, 10), fill=(220, 181, 79))
    d.rectangle((20, 20, 27, 31), fill=(32, 47, 66)); d.line((23, 20, 23, 30), fill=(208, 168, 72))
    d.rectangle((16, 29, 39, 30), fill=(161, 124, 55))
    for x, y in ((9, 14), (14, 15), (21, 23), (26, 27)): d.rectangle((x, y, x + 1, y + 1), fill=(194, 214, 179))
    save(img, "textures/entity/drowned_captain.png")



# Merged monsters (owner, 2026-11-15). Rotting zombie flesh for both; red eyes that glow.
ROT = [(18, 30, 18), (38, 62, 34), (66, 98, 54), (96, 132, 76), (138, 170, 106)]
RAGS = [(14, 40, 52), (24, 74, 92), (40, 112, 130), (64, 150, 166), (110, 190, 196)]
EYE_RED = (255, 62, 44)


def merged_monsters(zf):
    """The bat zombie: skin, glowing eyes, egg, name and drops (the spider zombie was deleted, owner 2026-11-18)."""
    import random
    rnd = random.Random(1511)
    # Bat zombie: rot-green fur, wing membranes gone grey-green and torn through, burning red eyes.
    bat = read(zf, "assets/minecraft/textures/entity/bat/bat.png")
    skin = gradient_map(bat, ROT)
    eyes = Image.new("RGBA", bat.size, (0, 0, 0, 0))
    for y in range(bat.height):
        for x in range(bat.width):
            q = bat.getpixel((x, y))
            if not q[3]:
                continue
            if q[2] > q[1] + 12 and q[0] > 60:  # vanilla's violet eye and ear-lining pixels
                if y < 16 and x < 12:
                    skin.putpixel((x, y), (200, 40, 30, 255))
                    eyes.putpixel((x, y), EYE_RED + (255,))
            elif x >= 12 and rnd.random() < .07:  # tears in the wings
                skin.putpixel((x, y), (0, 0, 0, 0))
    save(skin, "textures/entity/bat_zombie.png")
    save(eyes, "textures/entity/bat_zombie_eyes.png")

    for kind in ("bat_zombie",):
        base, ramp = EGGS[kind]
        save(gradient_map(read(zf, f"assets/minecraft/textures/item/{base}_spawn_egg.png"), ramp),
             f"textures/item/{kind}_spawn_egg.png")
        item_model(f"{kind}_spawn_egg", "minecraft:item/generated")
    language = ASSETS / "lang/en_us.json"
    entries = json.loads(language.read_text(encoding="utf-8"))
    for kind, name in (("bat_zombie", "Bat Zombie"),):
        entries[f"entity.lightborn.{kind}"] = name
        entries[f"item.lightborn.{kind}_spawn_egg"] = f"{name} Spawn Egg"
    language.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # Drops: a bat zombie only rotten flesh.
    data_root = ROOT / "src/main/resources/data/lightborn/loot_table/entities"
    data_root.mkdir(parents=True, exist_ok=True)

    def pool(item, lo, hi, looting=True, chance=None):
        entry = {"type": "minecraft:item", "name": item, "functions": [
            {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}}]}
        if looting:
            entry["functions"].append({"function": "minecraft:enchanted_count_increase", "enchantment": "minecraft:looting",
                                       "count": {"type": "minecraft:uniform", "min": 0, "max": 1}})
        p = {"rolls": 1, "entries": [entry]}
        if chance:
            p["conditions"] = [{"condition": "minecraft:killed_by_player"},
                               {"condition": "minecraft:random_chance", "chance": chance}]
        return p

    for kind, pools in (
            ("bat_zombie", [pool("minecraft:rotten_flesh", 0, 1)])):
        (data_root / f"{kind}.json").write_text(json.dumps({"type": "minecraft:entity", "pools": pools,
                                                            "random_sequence": f"lightborn:entities/{kind}"}, indent=2) + "\n",
                                                encoding="utf-8")


def main():
    with zipfile.ZipFile(JAR) as zf:
        phase6_assets(zf)
        boss_trophies(zf)
        spirit_wolf(zf)
        spawn_eggs(zf)
        merged_monsters(zf)
        darkness_zombie(zf)
        for name, spec in ZOMBIE_KINDS.items():
            zombie_kind(zf, name, spec)
        for name, spec in MONSTER_KINDS.items():
            monster_kind(zf, name, *spec)

    for name in STAFF_ART:
        save(staff(name), f"textures/item/{name}.png")
        item_model(name, "minecraft:item/handheld")
    for spell, (cover, glow, core, emblem) in TOMES.items():
        save(tome(cover, glow, core, emblem), f"textures/item/tome_{spell}.png")
        save(open_tome(cover, glow, core, emblem), f"textures/item/tome_{spell}_open.png")
        tome_models(f"tome_{spell}")
    light_chest()
    tree_wood()
    tree_rosette()
    save(dark_shard(), "textures/item/dark_shard.png")
    item_model("dark_shard", "minecraft:item/generated")
    print("textures written to", ASSETS)


if __name__ == "__main__":
    main()
