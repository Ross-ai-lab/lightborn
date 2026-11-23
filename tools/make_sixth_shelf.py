"""The sixth and seventh shelves of spells (owner, 2026-11-22: "create 6 legendary spells and 7 mythic spells", then
five Legendary beast forms and eleven Basic spells): their tome art and names.

The tome emblems live in `make_textures.TOMES` with every other tome (so a full rerun of that tool keeps them); this
tool draws only the new ones, so the rest of the mod's art is never touched. Recipes come from
`make_tome_recipes.py`. Run: py -3 -I tools/make_sixth_shelf.py
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

NAMES = {"sentinel": "Sentinel", "blizzard": "Blizzard", "wellspring": "Freedom", "banish": "Smite",
         "soul_link": "Soul Link", "time_stop": "Time Stop", "tempest": "Tempest", "colossus": "the Giant Golem",
         "ascension": "Gigantify", "renewal": "the Golden Apple", "transmute": "Transmute",
         # The seventh shelf (owner, 2026-11-22).
         "wolf_form": "the Wolf", "bear_form": "the Bear", "spider_form": "the Spider", "blaze_form": "the Blaze",
         "spirit_walk": "Spirit Walk", "shrinking": "Shrinking", "gills": "Gills", "purify": "Purify",
         "ender_pocket": "the Ender Pocket", "workbench": "the Workbench", "enfeeble": "Weaken",
         "splinter": "Splinters", "boulder": "the Boulder", "frostwalk": "Frostwalk",
         "fright": "Fright",
         # In The Dragon's place (owner, 2026-11-23); Haste went the same day.
         "warden_form": "Warden Form", "comet_dash": "Comet Dash", "doom": "Doom", "lifebloom": "Lifebloom"}

for spell in NAMES:
    cover, glow, core, emblem = mt.TOMES[spell]
    mt.save(mt.tome(cover, glow, core, emblem), f"textures/item/tome_{spell}.png")
    mt.save(mt.open_tome(cover, glow, core, emblem), f"textures/item/tome_{spell}_open.png")
    mt.tome_models(f"tome_{spell}")

lang = mt.ASSETS / "lang/en_us.json"
entries = json.loads(lang.read_text(encoding="utf-8"))
for spell, name in NAMES.items():
    entries[f"item.lightborn.tome_{spell}"] = f"Tome of {name}"
lang.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("sixth shelf art written")
