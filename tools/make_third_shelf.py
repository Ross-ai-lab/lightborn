"""The third shelf of spells (owner, 2026-11-21: "add 10 new spells"): their tome art, the two conjured blocks (Stone
Wall's stone, Light Bridge's light), the Spirit Steed's skin and glow, and the names.

The tome emblems live in `make_textures.TOMES` with every other tome (so a full rerun of that tool keeps them); this
tool draws only the new ones, so the rest of the mod's art is never touched. Recipes come from
`make_tome_recipes.py`. Run: py -3 -I tools/make_third_shelf.py
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

NEW = ["sunbeam", "wisps", "halo_disc", "starfall", "stone_wall", "sanctuary", "light_bridge", "owl_sight",
       "spirit_steed"]
NAMES = {"sunbeam": "Sunbeam", "wisps": "Wisps", "halo_disc": "Halo Disc", "starfall": "Starfall",
         "stone_wall": "Stone Wall", "sanctuary": "Ward", "light_bridge": "Light Bridge", "owl_sight": "Owl Sight", "spirit_steed": "Light Steed"}

for spell in NEW:
    cover, glow, core, emblem = mt.TOMES[spell]
    mt.save(mt.tome(cover, glow, core, emblem), f"textures/item/tome_{spell}.png")
    mt.save(mt.open_tome(cover, glow, core, emblem), f"textures/item/tome_{spell}_open.png")
    mt.tome_models(f"tome_{spell}")


def vanilla(name):
    with zipfile.ZipFile(CLIENT) as z:
        return Image.open(io.BytesIO(z.read(name))).convert("RGBA")


# Conjured stone: vanilla stone, a shade warmer, threaded with a few veins of gold light (it is a spell's stone).
stone = mt.gradient_map(vanilla("assets/minecraft/textures/block/stone.png"),
                        [(78, 74, 70), (112, 106, 98), (142, 135, 124), (170, 162, 148)])
for x, y in ((3, 2), (4, 3), (4, 4), (5, 5), (11, 8), (12, 9), (12, 10), (13, 11), (7, 12), (8, 13)):
    stone.putpixel((x, y), (255, 214, 110, 255))
mt.save(stone, "textures/block/conjured_stone.png")

# Light bridge: a pale gold pane of light with a brighter rim, so each block of the path reads underfoot.
light = Image.new("RGBA", (16, 16))
for y in range(16):
    for x in range(16):
        edge = min(x, y, 15 - x, 15 - y)
        if edge == 0:
            c = (255, 236, 170)
        elif edge == 1:
            c = (255, 248, 214)
        else:
            shimmer = ((x * 7 + y * 13) % 9) * 3
            c = (240 + shimmer // 3, 222 + shimmer // 2, 150 + shimmer)
        light.putpixel((x, y), c + (255,))
mt.save(light, "textures/block/light_bridge.png")

for block in ("conjured_stone", "light_bridge"):
    mt.write_json(f"models/block/{block}.json",
                  {"parent": "minecraft:block/cube_all", "textures": {"all": f"lightborn:block/{block}"}})
    mt.write_json(f"blockstates/{block}.json", {"variants": {"": {"model": f"lightborn:block/{block}"}}})

# The Spirit Steed: vanilla's white horse in the spirit wolves' spectral blue, with a dimmer full-bright copy.
src = vanilla("assets/minecraft/textures/entity/horse/horse_white.png")
skin = mt.gradient_map(src, mt.SPIRIT_RAMP)
glow = Image.new("RGBA", src.size, (0, 0, 0, 0))
for y in range(src.height):
    for x in range(src.width):
        p = skin.getpixel((x, y))
        if p[3]:
            glow.putpixel((x, y), tuple(round(c * 0.5) for c in p[:3]) + (255,))
mt.save(skin, "textures/entity/spirit_steed.png")
mt.save(glow, "textures/entity/spirit_steed_glow.png")

lang = mt.ASSETS / "lang/en_us.json"
entries = json.loads(lang.read_text(encoding="utf-8"))
for spell in NEW:
    entries[f"item.lightborn.tome_{spell}"] = f"Tome of {NAMES[spell]}"
entries["block.lightborn.conjured_stone"] = "Conjured Stone"
entries["block.lightborn.light_bridge"] = "Light Bridge"
entries["entity.lightborn.spirit_steed"] = "Spirit Steed"
lang.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("third shelf art written")
