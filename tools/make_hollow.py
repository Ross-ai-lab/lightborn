"""The Hollow (owner, 2026-11-21): the End made into the Hollow. Everything here is derived from the vanilla 26.3 files
in the Gradle cache (and Mutant Monsters' jar for its Mutant Enderman), so a rerun after a game update picks up
Mojang's changes and reapplies ours.

Writes:
- the Hollow's land bleached: end stone and its bricks ash-white bone, purpur faded to ash, chorus dead black;
- its sky: the End's sky texture made starless black with faint violet tears;
- the dark kinds' skins, which the client swaps in only while the player is in the Hollow (`HollowLook`): Darkman,
  Darkmite, Dark Shulker, the Dark Dragon (body, eyes, death) and the Mutant Darkman;
- Spark Ore (the Hollow's bone stone with spark glints), its model, item, loot table, tool tags and ore feature;
- the five End biomes and the dimension type with the Hollow's fog, light, ash motes and near silence;
- the names: "the Hollow" wherever the game names the End, the Dark Dragon, and Lightborn's new names.
Run: py -3 -I tools/make_hollow.py
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
MUTANTS = next((ROOT / 'Game/mods').glob('mutantmonsters-*.jar'), None)

# Palettes, darkest to brightest, picked by a vanilla pixel's brightness.
BONE = [(112, 110, 108), (146, 144, 141), (180, 178, 175), (208, 207, 204), (234, 233, 231)]
ASH = [(84, 80, 90), (116, 111, 122), (148, 143, 154), (180, 175, 186), (208, 204, 214)]
CHAR = [(5, 4, 7), (14, 12, 17), (27, 23, 31), (44, 38, 50), (66, 58, 72)]
VOID = [(2, 2, 3), (7, 6, 9), (14, 12, 17), (24, 21, 28)]
CRACK = [(70, 66, 76), (128, 124, 134), (186, 183, 192)]
DRAGON = [(3, 2, 5), (9, 8, 12), (19, 16, 24), (34, 29, 42), (96, 90, 104), (200, 196, 208)]
WHITE_EYE = [(120, 100, 170), (196, 184, 236), (246, 244, 255)]
SPARK = [(184, 116, 26), (242, 186, 58), (255, 230, 138), (255, 253, 236)]


def jar_bytes(jar, name):
    with zipfile.ZipFile(jar) as z:
        return z.read(name)


def png(name, jar=CLIENT):
    return Image.open(io.BytesIO(jar_bytes(jar, name))).convert('RGBA')


def block(name):
    return png(f'assets/minecraft/textures/block/{name}.png')


def block_meta(name):
    try:
        return jar_bytes(CLIENT, f'assets/minecraft/textures/block/{name}.png.mcmeta')
    except KeyError:
        return None


def vanilla_json(name):
    return json.loads(jar_bytes(COMMON, name))


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def merge_json(path, data):
    old = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
    old.update(data)
    write_json(path, old)


def ramp(palette, t):
    t = max(0.0, min(1.0, t)) * (len(palette) - 1)
    i = min(int(t), len(palette) - 2)
    f = t - i
    a, b = palette[i], palette[i + 1]
    return tuple(round(a[k] + (b[k] - a[k]) * f) for k in range(3))


def luma(c):
    return (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255


def hsv(c):
    return colorsys.rgb_to_hsv(*(x / 255 for x in c[:3]))


def glowing(c):
    """The Ender kinds' magenta and violet eye and wing glow."""
    h, s, v = hsv(c)
    return s > 0.35 and 0.7 < h < 0.95 and v > 0.3


def recolour(img, palette, only=None):
    """Each pixel (or only those the test picks) mapped by brightness onto the palette, stretched over the picked
    pixels' own brightness range so the pattern keeps its contrast."""
    px = img.load()
    picks = [(x, y) for y in range(img.height) for x in range(img.width)
             if px[x, y][3] and (only is None or only(px[x, y]))]
    ls = [luma(px[p]) for p in picks]
    if not ls:
        return img
    lo, hi = min(ls), max(ls)
    for p, l in zip(picks, ls):
        px[p] = ramp(palette, (l - lo) / max(1e-6, hi - lo)) + (px[p][3],)
    return img


def cracks(img, seed, share, only):
    """Hairline ash cracks over the body: short random walks of pale pixels, brightest at their start."""
    rnd = random.Random(seed)
    px = img.load()
    cells = [(x, y) for y in range(img.height) for x in range(img.width) if px[x, y][3] and only(px[x, y])]
    for _ in range(int(len(cells) * share)):
        x, y = rnd.choice(cells)
        for i in range(rnd.randint(2, 4)):
            if 0 <= x < img.width and 0 <= y < img.height and px[x, y][3] and only(px[x, y]):
                px[x, y] = CRACK[max(0, 2 - i)] + (px[x, y][3],)
            x += rnd.choice((-1, 0, 1))
            y += rnd.choice((-1, 1))
    return img


def save(path, img):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


def save_block(name, img, meta=None):
    save(MC / f'textures/block/{name}.png', img)
    if meta is not None:
        (MC / f'textures/block/{name}.png.mcmeta').write_bytes(meta)


# ------------------------------------------------------------------ the bleached land
for name in ('end_stone', 'end_stone_bricks'):
    save_block(name, recolour(block(name), BONE), block_meta(name))
for name in ('purpur_block', 'purpur_pillar_side', 'purpur_pillar_top'):
    save_block(name, recolour(block(name), ASH), block_meta(name))
for name in ('chorus_plant', 'chorus_flower', 'chorus_flower_dead'):
    save_block(name, recolour(block(name), CHAR), block_meta(name))
STONE = recolour(block('end_stone'), BONE)

# ------------------------------------------------------------------ the sky: starless black, faint violet tears
# The End's sky is drawn at about a sixth of the texture's brightness and tiled sixteen times a face, so the tears are
# bright here to read faintly there.
rnd = random.Random(20261121)
sky = Image.new('RGBA', (128, 128), (14, 12, 18, 255))
spx = sky.load()
for y in range(128):
    for x in range(128):
        n = rnd.randint(-3, 3)
        spx[x, y] = (14 + n, 12 + n, 18 + n, 255)
for _ in range(9):
    x, y, length = rnd.randrange(128), rnd.randrange(128), rnd.randint(10, 26)
    for i in range(length):
        f = (1 - i / length) ** 1.5
        c = tuple(round(14 + (t - 14) * f) for t in (170, 120, 230))
        spx[x, (y + i) % 128] = c + (255,)
        if i < length // 3:
            spx[(x + 1) % 128, (y + i) % 128] = tuple(round(v * .55 + 6) for v in c) + (255,)
save(MC / 'textures/environment/end_sky.png', sky)

# ------------------------------------------------------------------ the dark kinds' skins
HOLLOW = LB / 'textures/entity/hollow'
body = lambda c: not glowing(c)


def eyes_white(img):
    return recolour(img, WHITE_EYE, glowing)


man = png('assets/minecraft/textures/entity/enderman/enderman.png')
man = cracks(recolour(man, VOID, body), 1, .035, body)
save(HOLLOW / 'darkman.png', eyes_white(man))
save(HOLLOW / 'darkman_eyes.png', eyes_white(png('assets/minecraft/textures/entity/enderman/enderman_eyes.png')))

mite = png('assets/minecraft/textures/entity/endermite/endermite.png')
save(HOLLOW / 'darkmite.png', cracks(recolour(mite, CHAR), 3, .03, lambda c: True))  # it has no eyes to keep

# The shell goes dead black, the soft head inside it bleached bone like the land, so it reads against both.
shulker = png('assets/minecraft/textures/entity/shulker/shulker.png')
spx = shulker.load()
head_rows = range(52, 64)  # the head box sits in the texture's bottom rows
out = shulker.copy()
recolour(out, CHAR)
opx = out.load()
head = recolour(shulker.copy(), BONE)
hpx = head.load()
for y in head_rows:
    for x in range(shulker.width):
        if spx[x, y][3]:
            opx[x, y] = hpx[x, y]
save(HOLLOW / 'dark_shulker.png', out)

for src, dst in (('dragon', 'dark_dragon'), ('dragon_exploding', 'dark_dragon_exploding')):
    img = png(f'assets/minecraft/textures/entity/enderdragon/{src}.png')
    save(HOLLOW / f'{dst}.png', eyes_white(cracks(recolour(img, DRAGON, body), 4, .012, body)))
save(HOLLOW / 'dark_dragon_eyes.png', eyes_white(png('assets/minecraft/textures/entity/enderdragon/dragon_eyes.png')))

if MUTANTS:
    base = 'assets/mutantmonsters/textures/entity/mutant_enderman/'
    mutant = png(base + 'mutant_enderman.png', MUTANTS)
    save(HOLLOW / 'mutant_darkman.png', eyes_white(cracks(recolour(mutant, VOID, body), 2, .02, body)))
    save(HOLLOW / 'mutant_darkman_eyes.png', eyes_white(png(base + 'mutant_enderman_eyes.png', MUTANTS)))
    death = png(base + 'mutant_enderman_death.png', MUTANTS)
    save(HOLLOW / 'mutant_darkman_death.png', eyes_white(recolour(death, VOID, body)))

# ------------------------------------------------------------------ Spark Ore
ore = STONE.copy()
px = ore.load()
for cx, cy in ((3, 3), (11, 4), (6, 10), (12, 12), (2, 13)):
    px[cx, cy] = SPARK[3] + (255,)  # a bright glint with a warm halo
    for dx, dy, shade in ((1, 0, 2), (-1, 0, 1), (0, 1, 1), (0, -1, 2), (1, 1, 0)):
        x, y = cx + dx, cy + dy
        if 0 <= x < 16 and 0 <= y < 16:
            px[x, y] = SPARK[shade] + (255,)
save(LB / 'textures/block/spark_ore.png', ore)
write_json(LB / 'models/block/spark_ore.json',
           {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'lightborn:block/spark_ore'}})
write_json(LB / 'blockstates/spark_ore.json', {'variants': {'': {'model': 'lightborn:block/spark_ore'}}})
write_json(LB / 'items/spark_ore.json', {'model': {'type': 'minecraft:model', 'model': 'lightborn:block/spark_ore'}})
write_json(RES / 'data/lightborn/loot_table/blocks/spark_ore.json', {
    'type': 'minecraft:block',
    'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:alternatives', 'children': [
        {'type': 'minecraft:item', 'condition': 'minecraft:tool/can_silk_touch', 'name': 'lightborn:spark_ore'},
        {'type': 'minecraft:item', 'name': 'lightborn:spark', 'functions': [
            {'function': 'minecraft:apply_bonus', 'enchantment': 'minecraft:fortune', 'formula': 'minecraft:ore_drops'},
            {'function': 'minecraft:explosion_decay'}]}]}]}],
    'random_sequence': 'lightborn:blocks/spark_ore'})


def add_tag(path, value):
    data = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'values': []}
    if value not in data['values']:
        data['values'].append(value)
    write_json(path, data)


add_tag(RES / 'data/minecraft/tags/block/mineable/pickaxe.json', 'lightborn:spark_ore')
add_tag(RES / 'data/minecraft/tags/block/needs_iron_tool.json', 'lightborn:spark_ore')
# Rare (owner, 2026-11-21: "Spark Ore, rare"): six tries a chunk for a vein of up to three, inside the islands' height,
# so an island chunk holds one or two (measured: two tries gave one in two chunks, too few to be worth the trip), about
# a diamond's rarity, and the trip pays without outpaying fighting.
write_json(RES / 'data/lightborn/worldgen/feature/ore_spark.json', {
    'type': 'minecraft:ore', 'discard_chance_on_air_exposure': 0.0, 'size': 3,
    'targets': [{'state': 'lightborn:spark_ore',
                 'target': {'predicate_type': 'minecraft:block_match', 'block': 'minecraft:end_stone'}}]})
write_json(RES / 'data/lightborn/worldgen/placed_feature/ore_spark.json', {
    'feature': 'lightborn:ore_spark',
    'placement': [{'type': 'minecraft:count', 'count': 6}, {'type': 'minecraft:in_square'},
                  {'type': 'minecraft:height_range', 'height': {'type': 'minecraft:uniform',
                   'min_inclusive': {'absolute': 20}, 'max_inclusive': {'absolute': 80}}},
                  {'type': 'minecraft:biome'}]})

# ------------------------------------------------------------------ the Hollow's air: fog, light, ash, silence
MOTES = [{'particle': {'type': 'minecraft:white_ash'}, 'probability': 0.006}]
for biome in ('the_end', 'end_highlands', 'end_midlands', 'end_barrens', 'small_end_islands'):
    b = vanilla_json(f'data/minecraft/worldgen/biome/{biome}.json')
    b['attributes']['minecraft:visual/ambient_particles'] = {'argument': MOTES, 'modifier': 'append'}
    write_json(RES / f'data/minecraft/worldgen/biome/{biome}.json', b)
dim = vanilla_json('data/minecraft/dimension_type/the_end.json')
a = dim['attributes']
a['minecraft:visual/fog_color'] = '#1d1c21'
a['minecraft:visual/ambient_light_color'] = '#76767f'  # bright enough that the bleached land reads white
a['minecraft:visual/sky_light_color'] = '#b8aed4'  # the End's flashes, paled
# Near silence (owner's pick): the End's music only once in a long while, and never cutting into another track.
a['minecraft:audio/background_music'] = {'default': {'max_delay': 72000, 'min_delay': 36000,
                                                     'sound': 'minecraft:music.end'}}
write_json(RES / 'data/minecraft/dimension_type/the_end.json', dim)

# ------------------------------------------------------------------ names
merge_json(LB / 'lang/en_us.json', {
    'block.lightborn.spark_ore': 'Spark Ore',
    'entity.lightborn.darkman': 'Darkman',
    'entity.lightborn.darkmite': 'Darkmite',
    'entity.lightborn.dark_shulker': 'Dark Shulker',
    'entity.lightborn.giant_darkman': 'Giant Darkman',
    'entity.lightborn.mutant_darkman': 'Mutant Darkman',
})
merge_json(MC / 'lang/en_us.json', {
    'advancements.end.root.title': 'The Hollow',
    'advancements.story.enter_the_end.title': 'The Hollow?',
    'advancements.story.enter_the_end.description': 'Enter the Portal to the Hollow',
    'advancements.end.kill_dragon.title': 'Free the Hollow',
    'advancements.end.respawn_dragon.title': 'The Hollow... Again...',
    'advancements.end.respawn_dragon.description': 'Respawn the Dark Dragon',
    'advancements.end.find_end_city.title': 'The City at the Bottom of the Hollow',
    'advancements.end.levitate.description': 'Levitate up 50 blocks from the attacks of a Dark Shulker',
    'advancements.adventure.spyglass_at_dragon.description': 'Look at the Dark Dragon through a Spyglass',
    'biome.minecraft.the_end': 'The Hollow',
    'biome.minecraft.end_highlands': 'Hollow Highlands',
    'biome.minecraft.end_midlands': 'Hollow Midlands',
    'biome.minecraft.end_barrens': 'Hollow Barrens',
    'biome.minecraft.small_end_islands': 'Small Hollow Islands',
    'block.minecraft.end_portal': 'Portal to the Hollow',
    'block.minecraft.end_gateway': 'Hollow Gateway',
    # The dragon lives only in the Hollow, so it is the Dark Dragon everywhere, its boss bar included.
    'entity.minecraft.ender_dragon': 'Dark Dragon',
})
print('the Hollow written')
