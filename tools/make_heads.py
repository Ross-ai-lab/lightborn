"""Mounted boss heads: each boss's own face, cut from its body's skin (with its glowing eyes), on an oak and ivory plaque.

Writes textures/block/<boss>_head.png (the 32x16 head net at the top of a 32x32 sheet), block and item models,
block states, self-drops and English names. Run: py -3 -I tools/make_heads.py
"""
from pathlib import Path
import json
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / 'src/main/resources/assets/lightborn'
D = ROOT / 'src/main/resources/data/lightborn'
# boss id -> (body skin, display name, head net origin in the skin: zombies/skeletons at 0,0; spiders at 32,4)
BOSSES = {
    'gorath': ('giant_zombie', 'Gorath', (0, 0)),
    'vexmora': ('deadeye_skeleton', 'Vexmora', (0, 0)),
    'maelis': ('weaver_spider', 'Maelis', (32, 4)),
    'syrith': ('vampire_zombie', 'Syrith', (0, 0)),
    'ozrik': ('ember_skeleton', 'Ozrik', (0, 0)),
    'nyx': ('darkness_zombie', 'Nyx', (0, 0)),
}


def write(base, path, data):
    f = base / path
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def face(uv, rotation=0):
    out = {'texture': '#head', 'uv': uv}
    if rotation:
        out['rotation'] = rotation
    return out


def plank(a, b, tex):
    dx, dy, dz = [b[i] - a[i] for i in range(3)]
    return {'from': a, 'to': b, 'faces': {s: {'texture': '#' + tex, 'uv': [0, 0, dx if s in ('north', 'south', 'up', 'down') else dz, dz if s in ('up', 'down') else dy]}
                                          for s in ('north', 'south', 'east', 'west', 'up', 'down')}}


# 8x8x8 head facing north (toward the viewer once rotated), set on a plaque against the south side.
# Head net on a 32x32 sheet: 1 model UV unit = 2 px. Front (8,8)-(16,16), top (8,0)-(16,8), bottom (16,0)-(24,8),
# entity-right (0,8)-(8,16) shows on the west face, entity-left (16,8)-(24,16) on the east, back (24,8)-(32,16).
HEAD = {'from': [4, 4, 6], 'to': [12, 12, 14], 'faces': {
    'north': face([4, 4, 8, 8]), 'south': face([12, 4, 16, 8]),
    'west': face([0, 4, 4, 8]), 'east': face([8, 4, 12, 8]),
    'up': face([4, 0, 8, 4]), 'down': face([8, 0, 12, 4])}}
PLAQUE = [plank([2, 2, 14], [14, 14, 16], 'oak'), plank([3, 3, 13.5], [13, 4, 14], 'ivory'),
          plank([3, 12, 13.5], [13, 13, 14], 'ivory'), plank([7, 1, 13], [9, 2, 16], 'teal')]

for boss, (skin, name, (ox, oy)) in BOSSES.items():
    body = Image.open(A / f'textures/entity/{skin}.png').convert('RGBA')
    sheet = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    sheet.paste(body.crop((ox, oy, ox + 32, oy + 16)), (0, 0))
    eyes = A / f'textures/entity/{skin}_eyes.png'
    if eyes.exists():
        glow = Image.open(eyes).convert('RGBA').crop((ox, oy, ox + 32, oy + 16))
        layer = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
        layer.paste(glow, (0, 0))
        sheet = Image.alpha_composite(sheet, layer)
    sheet.save(A / f'textures/block/{boss}_head.png')
    block = f'{boss}_head'
    model = {'parent': 'minecraft:block/block',
             'textures': {'head': f'lightborn:block/{block}', 'oak': 'minecraft:block/oak_planks',
                          'ivory': 'minecraft:block/quartz_block_side', 'teal': 'minecraft:block/cyan_concrete',
                          'particle': 'minecraft:block/oak_planks'},
             'elements': PLAQUE + [HEAD],
             'display': {'gui': {'rotation': [20, 200, 0], 'translation': [0, 0, 0], 'scale': [.8, .8, .8]},
                         'fixed': {'rotation': [0, 180, 0], 'scale': [.8, .8, .8]}}}
    write(A, f'models/block/{block}.json', model)
    write(A, f'models/item/{block}.json', {'parent': f'lightborn:block/{block}'})
    write(A, f'items/{block}.json', {'model': {'type': 'minecraft:model', 'model': f'lightborn:item/{block}'}})
    write(A, f'blockstates/{block}.json', {'variants': {f'facing={f}': {'model': f'lightborn:block/{block}', 'y': y}
                                                        for f, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270))}})
    write(D, f'loot_table/blocks/{block}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [
        {'type': 'minecraft:item', 'name': f'lightborn:{block}'}], 'condition': {'type': 'minecraft:survives_explosion'}}]})

lang = A / 'lang/en_us.json'
j = json.loads(lang.read_text(encoding='utf-8'))
j.update({f'block.lightborn.{boss}_head': f'Mounted Head of {name}' for boss, (_, name, _) in BOSSES.items()})
lang.write_text(json.dumps(j, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Mounted six boss heads: textures, models, block states, drops and names.')
