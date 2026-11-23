"""The Dark (owner, 2026-11-20): the Nether made into the Dark. Everything here is derived from the vanilla 26.3 files
in the Gradle cache, so a rerun after a game update picks up Mojang's changes and reapplies ours.

Writes:
- Dark Goo's liquid textures (vanilla lava's frames, recoloured black-violet) and its block model and state;
- the Nether's rock, crimson growth, ores and portal retextured into the Dark's near-black violets (only reddish
  pixels change on ores and plants, so gold and quartz still read);
- Dark Shard ore (the Dark's rock with shards in it), its model, item, loot table and tool tags, and its ore feature;
- the Nether's world generation with goo for its seas, springs and deltas (noise settings and features);
- the five Nether biomes and the dimension type with the Dark's fog, light, motes and music;
- the names: "the Dark" wherever the game names the Nether, and Lightborn's new blocks.
Run: py -3 -I tools/make_dark_realm.py
"""
from pathlib import Path
import colorsys
import io
import json
import random
import zipfile
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'src/main/resources'
LB = RES / 'assets/lightborn'
MC = RES / 'assets/minecraft'
CACHE = Path.home() / '.gradle/caches/fabric-loom/minecraftMaven/net/minecraft'
CLIENT = next(CACHE.glob('minecraft-clientonly-deobf/26.3/*.jar'))
COMMON = next(CACHE.glob('minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar'))

# Palettes, darkest to brightest, picked by a vanilla pixel's brightness.
GOO = [(4, 2, 8), (12, 6, 20), (26, 12, 40), (48, 22, 70), (86, 44, 118), (150, 96, 196)]
ROCK = [(12, 9, 16), (24, 18, 32), (38, 29, 50), (56, 43, 72), (80, 62, 100)]
BRUISE = [(18, 6, 22), (40, 12, 46), (66, 22, 74), (98, 36, 106), (138, 62, 146)]
PORTAL = [(4, 2, 10), (22, 8, 40), (54, 20, 92), (102, 46, 160), (168, 110, 230)]
SHARD = [(24, 13, 37), (55, 29, 79), (91, 49, 126), (144, 79, 187), (192, 125, 226), (239, 194, 251)]


def jar_bytes(jar, name):
    with zipfile.ZipFile(jar) as z:
        return z.read(name)


def vanilla_png(name):
    return Image.open(io.BytesIO(jar_bytes(CLIENT, f'assets/minecraft/textures/block/{name}.png'))).convert('RGBA')


def vanilla_meta(name):
    try:
        return jar_bytes(CLIENT, f'assets/minecraft/textures/block/{name}.png.mcmeta')
    except KeyError:
        return None


def vanilla_json(name, jar=COMMON):
    return json.loads(jar_bytes(jar, name))


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def ramp(palette, t):
    """A colour along the palette, t from 0 (darkest) to 1 (brightest)."""
    t = max(0.0, min(1.0, t)) * (len(palette) - 1)
    i = min(int(t), len(palette) - 2)
    f = t - i
    a, b = palette[i], palette[i + 1]
    return tuple(round(a[k] + (b[k] - a[k]) * f) for k in range(3))


def luma(c):
    return (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255


def reddish(c):
    h, s, v = colorsys.rgb_to_hsv(*(x / 255 for x in c[:3]))
    return s > 0.18 and (h < 0.07 or h > 0.86) and v > 0.08


def recolour(img, palette, only=None, lo=None, hi=None):
    """Each pixel (or only those the test picks) mapped by brightness onto the palette, stretched over the image's
    own brightness range so the pattern keeps its contrast."""
    px = img.load()
    picks = [(x, y) for y in range(img.height) for x in range(img.width)
             if px[x, y][3] and (only is None or only(px[x, y]))]
    ls = [luma(px[p]) for p in picks]
    if not ls:
        return img
    lo = min(ls) if lo is None else lo
    hi = max(ls) if hi is None else hi
    for p, l in zip(picks, ls):
        px[p] = ramp(palette, (l - lo) / max(1e-6, hi - lo)) + (px[p][3],)
    return img


def save_block(base, name, img, meta=None):
    out = base / f'textures/block/{name}.png'
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    if meta is not None:
        (base / f'textures/block/{name}.png.mcmeta').write_bytes(meta)


# ------------------------------------------------------------------ Dark Goo, the liquid
for src, dst in (('lava_still', 'dark_goo_still'), ('lava_flow', 'dark_goo_flow')):
    save_block(LB, dst, recolour(vanilla_png(src), GOO), vanilla_meta(src))
write_json(LB / 'models/block/liquid_dark_goo.json', {'textures': {'particle': 'lightborn:block/dark_goo_still'}})
write_json(LB / 'blockstates/liquid_dark_goo.json', {'variants': {'': {'model': 'lightborn:block/liquid_dark_goo'}}})

# ------------------------------------------------------------------ the Dark's rock, growth and portal
RACK = vanilla_png('netherrack')
RACK_DARK = recolour(RACK.copy(), ROCK)
# Every netherrack colour and what the Dark turns it into, so the rock inside ores and under nylium matches exactly.
AS_ROCK = {RACK.getpixel((x, y))[:3]: RACK_DARK.getpixel((x, y))[:3] for y in range(16) for x in range(16)}


def rock_inside(img):
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            c = px[x, y]
            if c[3] and c[:3] in AS_ROCK:
                px[x, y] = AS_ROCK[c[:3]] + (c[3],)
    return img


save_block(MC, 'netherrack', RACK_DARK, vanilla_meta('netherrack'))
for name in ('nether_gold_ore', 'nether_quartz_ore'):  # only the rock round the ore changes
    save_block(MC, name, rock_inside(vanilla_png(name)), vanilla_meta(name))
side = vanilla_png('crimson_nylium_side')  # the growth bruises, the rock under it matches the Dark's rock
growth = recolour(side.copy(), BRUISE, lambda c: c[:3] not in AS_ROCK)
save_block(MC, 'crimson_nylium_side', rock_inside(growth), vanilla_meta('crimson_nylium_side'))
for name in ('crimson_nylium', 'nether_wart_block', 'crimson_stem', 'crimson_stem_top', 'crimson_roots',
             'crimson_fungus', 'weeping_vines', 'weeping_vines_plant'):
    only = None if name in ('nether_wart_block', 'crimson_nylium') else reddish
    save_block(MC, name, recolour(vanilla_png(name), BRUISE, only), vanilla_meta(name))
save_block(MC, 'nether_portal', recolour(vanilla_png('nether_portal'), PORTAL), vanilla_meta('nether_portal'))

# ------------------------------------------------------------------ Dark Shard ore
rnd = random.Random(20261120)
ore = RACK_DARK.copy()
px = ore.load()
for cx, cy, length in ((3, 4, 4), (10, 3, 3), (7, 11, 4), (13, 12, 3)):
    for i in range(length):  # a shard leaning up and right, lit along one edge
        x, y = cx + i // 2, cy - i
        if 0 <= x < 15 and 0 <= y < 16:
            px[x, y] = SHARD[2 + min(3, i)] + (255,)
            px[x + 1, y] = SHARD[1 + min(2, i // 2)] + (255,)
    if 0 <= cy + 1 < 16:
        px[cx, cy + 1] = SHARD[0] + (255,)
save_block(LB, 'dark_shard_ore', ore)
write_json(LB / 'models/block/dark_shard_ore.json',
           {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'lightborn:block/dark_shard_ore'}})
write_json(LB / 'blockstates/dark_shard_ore.json', {'variants': {'': {'model': 'lightborn:block/dark_shard_ore'}}})
write_json(LB / 'items/dark_shard_ore.json',
           {'model': {'type': 'minecraft:model', 'model': 'lightborn:block/dark_shard_ore'}})
write_json(RES / 'data/lightborn/loot_table/blocks/dark_shard_ore.json', {
    'type': 'minecraft:block',
    'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:alternatives', 'children': [
        {'type': 'minecraft:item', 'condition': 'minecraft:tool/can_silk_touch', 'name': 'lightborn:dark_shard_ore'},
        {'type': 'minecraft:item', 'name': 'lightborn:dark_shard', 'functions': [
            {'function': 'minecraft:apply_bonus', 'enchantment': 'minecraft:fortune', 'formula': 'minecraft:ore_drops'},
            {'function': 'minecraft:explosion_decay'}]}]}]}],
    'random_sequence': 'lightborn:blocks/dark_shard_ore'})


def add_tag(path, value):
    data = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'values': []}
    if value not in data['values']:
        data['values'].append(value)
    write_json(path, data)


add_tag(RES / 'data/minecraft/tags/block/mineable/pickaxe.json', 'lightborn:dark_shard_ore')
add_tag(RES / 'data/minecraft/tags/block/needs_iron_tool.json', 'lightborn:dark_shard_ore')
# Veins of up to four, about four a chunk, in the Dark's rock at any height: rarer than its gold.
write_json(RES / 'data/lightborn/worldgen/feature/ore_dark_shard.json', {
    'type': 'minecraft:ore', 'discard_chance_on_air_exposure': 0.0, 'size': 4,
    'targets': [{'state': 'lightborn:dark_shard_ore',
                 'target': {'predicate_type': 'minecraft:block_match', 'block': 'minecraft:netherrack'}}]})
write_json(RES / 'data/lightborn/worldgen/placed_feature/ore_dark_shard.json', {
    'feature': 'lightborn:ore_dark_shard',
    'placement': [{'type': 'minecraft:count', 'count': 4}, {'type': 'minecraft:in_square'},
                  {'type': 'minecraft:height_range', 'height': {'type': 'minecraft:uniform',
                   'min_inclusive': {'above_bottom': 10}, 'max_inclusive': {'below_top': 10}}},
                  {'type': 'minecraft:biome'}]})

# ------------------------------------------------------------------ goo seas, springs and deltas
GOO_STATE = 'lightborn:liquid_dark_goo'
noise = vanilla_json('data/minecraft/worldgen/noise_settings/nether.json')
noise['default_fluid'] = GOO_STATE
write_json(RES / 'data/minecraft/worldgen/noise_settings/nether.json', noise)
for name in ('spring_lava_nether', 'spring_nether_open', 'spring_nether_closed'):
    f = vanilla_json(f'data/minecraft/worldgen/feature/{name}.json')
    f['state'] = {'id': 'lightborn:dark_goo', 'properties': {'falling': 'true'}}
    write_json(RES / f'data/minecraft/worldgen/feature/{name}.json', f)
delta = vanilla_json('data/minecraft/worldgen/feature/delta.json')
delta['contents'] = GOO_STATE
write_json(RES / 'data/minecraft/worldgen/feature/delta.json', delta)

# ------------------------------------------------------------------ the Dark's air: fog, light, motes, music
FOG = {'nether_wastes': '#0e0714', 'crimson_forest': '#1a0717', 'warped_forest': '#06111a',
       'soul_sand_valley': '#101320', 'basalt_deltas': '#1d1a24'}
MOTES = [{'particle': {'type': 'minecraft:ash'}, 'probability': 0.012},
         {'particle': {'type': 'minecraft:reverse_portal'}, 'probability': 0.002}]
for biome, fog in FOG.items():
    b = vanilla_json(f'data/minecraft/worldgen/biome/{biome}.json')
    a = b['attributes']
    a['minecraft:visual/fog_color'] = fog
    own = a.get('minecraft:visual/ambient_particles', {}).get('argument', [])
    keep = [p for p in own if p['particle']['type'] in ('minecraft:warped_spore', 'minecraft:white_ash')]
    a['minecraft:visual/ambient_particles'] = {'argument': MOTES + keep, 'modifier': 'append'}
    a['minecraft:audio/background_music'] = {'default': {'max_delay': 24000, 'min_delay': 12000,
                                                         'sound': 'minecraft:music.overworld.deep_dark'}}
    write_json(RES / f'data/minecraft/worldgen/biome/{biome}.json', b)
dim = vanilla_json('data/minecraft/dimension_type/the_nether.json')
dim['attributes']['minecraft:visual/ambient_light_color'] = '#3a2c52'
dim['attributes']['minecraft:visual/fog_start_distance'] = 6.0
dim['attributes']['minecraft:visual/fog_end_distance'] = 72.0
write_json(RES / 'data/minecraft/dimension_type/the_nether.json', dim)

# ------------------------------------------------------------------ fluid tags
write_json(RES / 'data/lightborn/tags/fluid/dark_goo.json', {'values': ['lightborn:dark_goo', 'lightborn:flowing_dark_goo']})
add_tag(RES / 'data/minecraft/tags/fluid/entity_floatable.json', '#lightborn:dark_goo')

# ------------------------------------------------------------------ names
lang = LB / 'lang/en_us.json'
names = json.loads(lang.read_text(encoding='utf-8'))
names.update({'block.lightborn.liquid_dark_goo': 'Dark Goo', 'block.lightborn.dark_shard_ore': 'Dark Shard Ore'})
lang.write_text(json.dumps(names, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
mc_lang = MC / 'lang/en_us.json'  # merged: the Hollow's generator writes names here too
mc_names = json.loads(mc_lang.read_text(encoding='utf-8')) if mc_lang.exists() else {}
mc_names.update({
    'advancements.nether.root.title': 'The Dark',
    'advancements.nether.root.description': 'Bring a light',
    'advancements.story.enter_the_nether.description': 'Build, light, and enter a Portal to the Dark',
    'advancements.nether.explore_nether.description': 'Explore every biome of the Dark',
    'advancements.nether.fast_travel.description': 'Use the Dark to travel 7 km in the Overworld',
    'advancements.nether.find_fortress.description': 'Break your way into a fortress of the Dark',
    'advancements.nether.uneasy_alliance.description':
        'Rescue a Ghast from the Dark, bring it safely home to the Overworld... and then kill it',
    'biome.minecraft.nether_wastes': 'Dark Wastes',
    'block.minecraft.nether_portal': 'Portal to the Dark',
    'gamerule.allowEnteringNetherUsingPortals': 'Allow the Dark',
    'gamerule.allowEnteringNetherUsingPortals.description': 'Controls whether players are allowed to enter the Dark.',
})
write_json(mc_lang, mc_names)
print('the Dark written')
