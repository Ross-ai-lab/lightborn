"""Writes every tome's crafting recipe (data/lightborn/recipe/tome_*.json).

Two rules (owner, 2026-11-18), checked by the self-test `tome_recipes_fill_nine_by_tier`:
- every tome recipe fills all nine crafting slots (an ingredient may repeat);
- what it costs follows the spell's tier: the book, the tier's price, then the spell's own theme items.

The tier prices must match `Spell.recipePrice` in Java. Run: py -3 tools/make_tome_recipes.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/data/lightborn/recipe"

# Tier -> the price every tome of that tier pays, on top of the book.
PRICE = {
    1: ["lapis_lazuli"] * 2,
    2: ["lapis_lazuli"] * 3 + ["iron_ingot"] * 2,
    3: ["lapis_lazuli"] * 2 + ["gold_ingot"] * 2 + ["diamond"],
    4: ["lapis_block", "gold_ingot"] + ["diamond"] * 2,
    5: ["lapis_block", "netherite_scrap"] + ["diamond"] * 2,
}

# Spell -> (tier, theme items). The theme fills what the book and the price leave of the nine slots.
SPELLS = {
    "arcane_bolt": (1, ["amethyst_shard"] * 6),
    "reap": (1, ["stone_hoe"] + ["wheat"] * 5),
    "felling": (1, ["stone_axe"] + ["oak_sapling"] * 5),
    "herd_call": (1, ["lead"] + ["wheat"] * 3 + ["carrot"] * 2),
    "gathering": (1, ["chest"] + ["string"] * 5),
    "radiant_pulse": (1, ["glowstone_dust"] * 3 + ["torch"] * 3),
    "fireball": (2, ["fire_charge"] * 3),
    "stoneskin": (2, ["smooth_stone"] * 3),
    "mending_light": (2, ["glistering_melon_slice"] * 2 + ["golden_carrot"]),
    "verdant_surge": (2, ["bone_meal"] * 2 + ["moss_block"]),
    "excavate": (2, ["iron_pickaxe"] + ["gunpowder"] * 2),
    "forge_fire": (2, ["furnace"] + ["coal"] * 2),
    "skies": (2, ["water_bucket"] + ["feather"] * 2),
    "glacial_spike": (2, ["packed_ice"] * 2 + ["arrow"]),
    "water_splash": (2, ["water_bucket"] + ["kelp"] * 2),
    "homeward": (2, ["compass", "ender_pearl", "#minecraft:beds"]),
    "frost_nova": (3, ["blue_ice"] + ["snowball"] * 2),
    "chain_lightning": (3, ["lightning_rod"] + ["redstone"] * 2),
    "blink": (3, ["ender_pearl"] * 3),
    "ore_sight": (3, ["spyglass"] + ["glowstone_dust"] * 2),
    "contagion": (3, ["fermented_spider_eye", "rotten_flesh", "slime_ball"]),
    "treasure_thread": (3, ["glowstone_dust"] * 2 + ["string"]),
    "cage": (3, ["iron_bars"] * 2 + ["iron_chain"]),
    "taunt": (3, ["bell"] + ["glowstone_dust"] * 2),
    "updraft": (3, ["feather"] * 2 + ["phantom_membrane"]),
    "souls": (3, ["soul_sand"] * 2 + ["bone"]),
    "gravity_well": (4, ["crying_obsidian", "ender_pearl"] + ["obsidian"] * 2),
    "earthshatter": (4, ["pointed_dripstone"] * 2 + ["cobblestone"] * 2),
    "siphon": (4, ["glass_bottle", "redstone"] + ["sweet_berries"] * 2),
    "spirit_wolves": (4, ["bone"] * 3 + ["beef"]),
    "mirror_ward": (4, ["glass"] * 2 + ["amethyst_shard"] * 2),
    "meteor": (5, ["magma_block"] * 2 + ["blaze_rod", "fire_charge"]),
    "flight": (5, ["breeze_rod", "phantom_membrane"] + ["feather"] * 2),
    # The third shelf (owner, 2026-11-21).
    "owl_sight": (1, ["spider_eye"] * 2 + ["feather"] * 3 + ["torch"]),
    "wisps": (2, ["glow_berries"] * 2 + ["amethyst_shard"]),
    "halo_disc": (2, ["gold_nugget"] * 2 + ["feather"]),
    "stone_wall": (2, ["cobblestone_wall"] * 2 + ["stone_bricks"]),
    "light_bridge": (2, ["glowstone_dust"] * 2 + ["glass"]),
    "sunbeam": (3, ["glowstone"] + ["sunflower"] * 2),
    "sanctuary": (3, ["gold_block"] + ["glowstone_dust"] * 2),
    "spirit_steed": (3, ["saddle", "golden_carrot", "hay_block"]),
    "starfall": (4, ["firework_star"] * 2 + ["glowstone_dust"] * 2),
    # The fourth shelf (owner, 2026-11-21).
    "wayglow": (1, ["glow_ink_sac"] * 2 + ["torch"] * 4),
    "tether": (2, ["lead", "tripwire_hook", "string"]),
    "thornfield": (2, ["sweet_berries"] * 2 + ["vine"]),
    "lightbrand": (2, ["golden_sword", "glowstone_dust", "blaze_powder"]),
    "ball_lightning": (3, ["copper_block", "redstone", "glowstone_dust"]),
    "blade_ring": (3, ["iron_sword"] * 3),
    "rewind": (3, ["clock", "ender_pearl", "amethyst_shard"]),
    "twincast": (4, ["amethyst_shard"] * 2 + ["ender_eye", "glowstone_dust"]),
    "eclipse": (4, ["ink_sac"] * 2 + ["ender_pearl", "echo_shard"]),
    # The fifth and last shelf (owner, 2026-11-22).
    "quickening": (1, ["wheat"] * 3 + ["carrot"] * 2 + ["egg"]),
    "rune_snare": (2, ["tripwire_hook", "glow_ink_sac", "redstone"]),
    "transpose": (2, ["ender_pearl"] * 2 + ["compass"]),
    "light_bore": (3, ["iron_pickaxe", "glowstone_dust", "redstone"]),
    "cyclone": (3, ["wind_charge"] * 2 + ["feather"]),
    "emberwalk": (3, ["magma_block", "blaze_powder", "leather_boots"]),
    "crystal_shell": (3, ["amethyst_block", "light_blue_stained_glass", "glass"]),
    "moongate": (2, ["ender_pearl", "crying_obsidian", "slime_ball"]),
    "dawnbreak": (5, ["glowstone"] * 2 + ["sunflower", "gold_block"]),
    # The sixth shelf (owner, 2026-11-22): six Legendary, seven Mythic; Dominate gone the same day.
    "sentinel": (4, ["blue_ice", "packed_ice", "ice", "prismarine_crystals"]),
    "blizzard": (4, ["blue_ice", "snow_block"] + ["snowball"] * 2),
    "wellspring": (4, ["feather"] * 2 + ["glow_ink_sac", "ghast_tear"]),
    "banish": (4, ["ender_eye", "end_rod", "ghast_tear", "glowstone_dust"]),
    "soul_link": (4, ["iron_chain"] * 2 + ["soul_sand", "glowstone_dust"]),
    "time_stop": (5, ["clock"] * 2 + ["ender_eye", "amethyst_shard"]),
    "tempest": (5, ["lightning_rod"] * 2 + ["copper_block", "glowstone_dust"]),
    "colossus": (5, ["iron_block"] * 2 + ["carved_pumpkin", "glowstone"]),
    "ascension": (5, ["golden_apple", "glowstone", "feather", "blaze_rod"]),
    "renewal": (5, ["golden_apple"] * 2 + ["gold_block", "apple"]),
    "transmute": (5, ["gold_block", "redstone_block", "emerald", "amethyst_shard"]),
    # The seventh shelf (owner, 2026-11-22): five Legendary beast forms, eleven Basic spells.
    "wolf_form": (4, ["bone"] * 3 + ["porkchop"]),
    "bear_form": (4, ["leather"] * 2 + ["salmon", "sweet_berries"]),
    "spider_form": (4, ["spider_eye", "string", "string", "cobweb"]),
    "blaze_form": (4, ["blaze_rod", "blaze_powder", "blaze_powder", "magma_cream"]),
    "spirit_walk": (1, ["phantom_membrane"] * 2 + ["soul_sand"] * 2 + ["glass_bottle", "feather"]),  # Basic, 2026-11-23
    "shrinking": (1, ["red_mushroom"] * 3 + ["brown_mushroom"] * 2 + ["sugar"]),
    "gills": (1, ["tropical_fish", "pufferfish", "cod", "prismarine_shard"] + ["kelp"] * 2),
    "purify": (1, ["milk_bucket", "honey_bottle"] + ["sugar"] * 2 + ["glass_bottle"] * 2),
    "ender_pocket": (1, ["ender_pearl", "chest", "string", "leather"] + ["obsidian"] * 2),
    "workbench": (1, ["crafting_table"] + ["stick"] * 2 + ["oak_planks"] * 3),
    "enfeeble": (1, ["fermented_spider_eye", "spider_eye", "bone", "sugar"] + ["rotten_flesh"] * 2),
    "splinter": (1, ["prismarine_shard"] * 2 + ["flint"] * 2 + ["stick"] * 2),
    "boulder": (1, ["cobblestone"] * 4 + ["stone"] * 2),
    "frostwalk": (1, ["packed_ice", "ice", "leather_boots"] + ["snowball"] * 3),
    "fright": (1, ["carved_pumpkin", "rotten_flesh", "gunpowder", "ink_sac"] + ["bone"] * 2),
    "warden_form": (5, ["echo_shard"] * 2 + ["sculk_catalyst", "sculk"]),  # in The Dragon's place, 2026-11-23
    # The eighth shelf (2026-11-23).
    "comet_dash": (4, ["firework_rocket"] * 2 + ["feather", "glowstone_dust"]),
    "doom": (4, ["wither_rose", "bone", "soul_sand", "ink_sac"]),
    "lifebloom": (4, ["flowering_azalea", "glow_berries", "bone_meal", "golden_carrot"]),
}


def item(name):
    return name if ":" in name else "minecraft:" + name


def main():
    for old in OUT.glob("tome_*.json"):
        old.unlink()
    for spell, (tier, theme) in SPELLS.items():
        ingredients = ["book"] + PRICE[tier] + theme
        assert len(ingredients) == 9, (spell, len(ingredients))
        recipe = {
            "type": "minecraft:crafting_shapeless",
            "category": "misc",
            "ingredients": [item(i) for i in ingredients],
            "result": {"id": "lightborn:tome_" + spell, "count": 1},
        }
        (OUT / f"tome_{spell}.json").write_text(json.dumps(recipe, indent=2) + "\n", encoding="utf-8")
    print(f"{len(SPELLS)} tome recipes written")


if __name__ == "__main__":
    main()
