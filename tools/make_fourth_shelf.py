"""The fourth shelf of spells (owner, 2026-11-21: "build another 10 spells"): their tome art, Wayglow's mote of light
(block texture, model, blockstate) and the names.

The tome emblems live in `make_textures.TOMES` with every other tome (so a full rerun of that tool keeps them); this
tool draws only the new ones, so the rest of the mod's art is never touched. Recipes come from
`make_tome_recipes.py`. Run: py -3 -I tools/make_fourth_shelf.py
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLIENT = next((Path.home() / ".gradle/caches/fabric-loom/minecraftMaven/net/minecraft").glob(
    "minecraft-clientonly-deobf/26.3/*.jar"))
sys.argv = [sys.argv[0], str(CLIENT), str(ROOT)]
sys.path.insert(0, str(ROOT / "tools"))
import make_textures as mt  # noqa: E402  (reads its jar and root from argv)

from PIL import Image  # noqa: E402

NAMES = {"tether": "Tether", "wayglow": "Wayglow", "thornfield": "Thornfield", "lightbrand": "Lightbrand",
         "ball_lightning": "Ball Lightning", "blade_ring": "Blade Ring", "rewind": "Rewind", "twincast": "Twincast"}

for spell in NAMES:
    cover, glow, core, emblem = mt.TOMES[spell]
    mt.save(mt.tome(cover, glow, core, emblem), f"textures/item/tome_{spell}.png")
    mt.save(mt.open_tome(cover, glow, core, emblem), f"textures/item/tome_{spell}_open.png")
    mt.tome_models(f"tome_{spell}")

# The mote: a small seed of light, a white heart in a pale gold skin, flat-lit, with no dark rim that would make it
# read as a crate. The model is a 4-pixel cube in the middle of the block, whose faces read the middle 4 by 4 here.
mote = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
for y in range(6, 10):
    for x in range(6, 10):
        heart = 7 <= x <= 8 and 7 <= y <= 8
        corner = x in (6, 9) and y in (6, 9)
        c = (255, 255, 244) if heart else (255, 226, 150) if corner else (255, 240, 196)
        mote.putpixel((x, y), c + (255,))
mt.save(mote, "textures/block/glow_mote.png")
mt.write_json("models/block/glow_mote.json", {
    "ambientocclusion": False,
    "textures": {"all": "lightborn:block/glow_mote", "particle": "lightborn:block/glow_mote"},
    "elements": [{
        "from": [6, 6, 6], "to": [10, 10, 10], "shade": False, "light_emission": 15,
        "faces": {face: {"texture": "#all", "uv": [6, 6, 10, 10]}
                  for face in ("north", "south", "east", "west", "up", "down")},
    }],
})
# Bright Motes (owner, 2026-11-22): the same mote, twice the size, an 8-pixel cube with a 4-pixel heart.
big = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
for y in range(4, 12):
    for x in range(4, 12):
        heart = 6 <= x <= 9 and 6 <= y <= 9
        corner = x in (4, 11) and y in (4, 11)
        c = (255, 255, 244) if heart else (255, 226, 150) if corner else (255, 240, 196)
        big.putpixel((x, y), c + (255,))
mt.save(big, "textures/block/glow_mote_large.png")
mt.write_json("models/block/glow_mote_large.json", {
    "ambientocclusion": False,
    "textures": {"all": "lightborn:block/glow_mote_large", "particle": "lightborn:block/glow_mote_large"},
    "elements": [{
        "from": [4, 4, 4], "to": [12, 12, 12], "shade": False, "light_emission": 15,
        "faces": {face: {"texture": "#all", "uv": [4, 4, 12, 12]}
                  for face in ("north", "south", "east", "west", "up", "down")},
    }],
})
mt.write_json("blockstates/glow_mote.json", {"variants": {
    "large=false": {"model": "lightborn:block/glow_mote"},
    "large=true": {"model": "lightborn:block/glow_mote_large"},
}})

lang = mt.ASSETS / "lang/en_us.json"
entries = json.loads(lang.read_text(encoding="utf-8"))
for spell, name in NAMES.items():
    entries[f"item.lightborn.tome_{spell}"] = f"Tome of {name}"
entries["block.lightborn.glow_mote"] = "Mote of Light"
lang.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("fourth shelf art written")
