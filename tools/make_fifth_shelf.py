"""The fifth and last shelf of spells (owner, 2026-11-22: "add 10 more spells, this will be the final 10"): their tome
art, Emberwalk's crust (its texture, baked from the game's own animated magma, model and blockstate)
and the names.

The tome emblems live in `make_textures.TOMES` with every other tome (so a full rerun of that tool keeps them); this
tool draws only the new ones, so the rest of the mod's art is never touched. Recipes come from
`make_tome_recipes.py`. Run: py -3 -I tools/make_fifth_shelf.py
"""
import io
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLIENT = next((Path.home() / ".gradle/caches/fabric-loom/minecraftMaven/net/minecraft").glob(
    "minecraft-clientonly-deobf/26.3/*.jar"))
sys.argv = [sys.argv[0], str(CLIENT), str(ROOT)]
sys.path.insert(0, str(ROOT / "tools"))
import make_textures as mt  # noqa: E402  (reads its jar and root from argv)

from PIL import Image  # noqa: E402

NAMES = {"cyclone": "Cyclone", "rune_snare": "Trap", "dawnbreak": "Dawnbreak", "emberwalk": "Emberwalk",
         "crystal_shell": "Crystal Shell", "transpose": "Transpose", "moongate": "Moongate",
         "quickening": "Quickening", "light_bore": "Light Bore"}

for spell in NAMES:
    cover, glow, core, emblem = mt.TOMES[spell]
    mt.save(mt.tome(cover, glow, core, emblem), f"textures/item/tome_{spell}.png")
    mt.save(mt.open_tome(cover, glow, core, emblem), f"textures/item/tome_{spell}_open.png")
    mt.tome_models(f"tome_{spell}")

# The crust: cracked dark basalt plates over the game's own magma, every frame of its animation, so the cracks glow and
# flicker; it reads as lava that has skinned over (and not as a magma block, which burns what stands on it). Baked
# opaque, so the block needs no cut-out render layer.
with zipfile.ZipFile(CLIENT) as zf:
    magma = Image.open(io.BytesIO(zf.read("assets/minecraft/textures/block/magma.png"))).convert("RGBA")
    meta = zf.read("assets/minecraft/textures/block/magma.png.mcmeta").decode("utf-8")
# Plates are the cells round a few fixed seeds (tiling, so the block repeats seamlessly); a pixel lies on a seam where
# its nearest two seeds are about as near, and there the magma shows through.
SEEDS = [(2, 3), (9, 1), (14, 6), (6, 8), (11, 12), (3, 13), (8, 15)]


def near(x, y):
    d = sorted(min((x - sx - ox) ** 2 + (y - sy - oy) ** 2 for ox in (-16, 0, 16) for oy in (-16, 0, 16))
               for sx, sy in SEEDS)
    return d[0] ** .5, d[1] ** .5


crust = magma.copy()
for frame in range(magma.height // 16):
    for y in range(16):
        for x in range(16):
            a, b = near(x + .5, y + .5)
            if b - a < 1.1:
                r, g, bl, _ = magma.getpixel((x, frame * 16 + y))
                crust.putpixel((x, frame * 16 + y), (min(255, r + 40), min(255, g + 25), bl, 255))
                continue
            edge = b - a < 2.2  # the plate's rim, warmed by the seam
            shade = 46 + ((x * 7 + y * 13) % 4) * 5 - int(min(a, 5)) * 2
            crust.putpixel((x, frame * 16 + y), (shade + (34 if edge else 12), shade + (6 if edge else 0), shade - 6, 255))
mt.save(crust, "textures/block/ember_crust.png")
(mt.ASSETS / "textures/block/ember_crust.png.mcmeta").write_text(meta, encoding="utf-8")
mt.write_json("models/block/ember_crust.json", {"parent": "minecraft:block/cube_all",
                                                 "textures": {"all": "lightborn:block/ember_crust"}})
mt.write_json("blockstates/ember_crust.json", {"variants": {
    "source=true": {"model": "lightborn:block/ember_crust"},
    "source=false": {"model": "lightborn:block/ember_crust"},
}})

lang = mt.ASSETS / "lang/en_us.json"
entries = json.loads(lang.read_text(encoding="utf-8"))
for spell, name in NAMES.items():
    entries[f"item.lightborn.tome_{spell}"] = f"Tome of {name}"
entries["block.lightborn.ember_crust"] = "Ember Crust"
lang.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("fifth shelf art written")
