"""The Totem of Light (Taunt, owner 2026-11-18: a real 3D model, not the vanilla totem's flat sprite).

An ivory pillar on a gold plinth, banded in gold, crowned by four gold rays leaning out like a sunburst, and over them
a floating crystal of white-gold light. Drawn full-bright and turning slowly by TotemRenderer; about 2.2 blocks tall.
Writes the textures (textures/item/totem_*.png, the item atlas), the model (models/item/light_totem.json, block-model elements,
26.x free rotations) and its item definition (items/light_totem.json). Run: py -3 -I tools/make_totem.py
"""
from pathlib import Path
import json
import random

from PIL import Image

A = Path(__file__).resolve().parent.parent / 'src/main/resources/assets/lightborn'


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def save(img, name):
    p = A / f'textures/item/{name}.png'
    p.parent.mkdir(parents=True, exist_ok=True)
    img.save(p)


IVORY, IVORY_DARK, GOLD, GOLD_DARK, GOLD_LIGHT, WHITE = (
    (246, 238, 214), (214, 200, 168), (255, 206, 84), (196, 132, 40), (255, 236, 150), (255, 252, 238))


def stone():
    """Ivory with soft vertical fluting and a few gold flecks."""
    rnd = random.Random(3)
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            flute = (x % 4) in (0, 3)
            c = mix(IVORY, IVORY_DARK, (.45 if flute else .1) + rnd.random() * .12)
            if rnd.random() < .035:
                c = GOLD_LIGHT
            px[x, y] = c + (255,)
    return img


def gold():
    """Gold, bright along the top edge and in a diagonal sheen."""
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            c = mix(GOLD_LIGHT, GOLD_DARK, y / 15)
            if abs((x + y) % 11 - 5) < 1:
                c = mix(c, WHITE, .45)
            if y == 0:
                c = GOLD_LIGHT
            px[x, y] = c + (255,)
    return img


def core():
    """The crystal: white at the heart, warm gold at the edges."""
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5)) / 8
            px[x, y] = mix(WHITE, GOLD, d ** 1.6) + (255,)
    return img


save(stone(), 'totem_stone')
save(gold(), 'totem_gold')
save(core(), 'totem_core')

FULL = [0, 0, 16, 16]


def box(frm, to, tex, rot=None, light=0):
    for v in frm + to:
        assert -16 <= v <= 32, (frm, to)
    e = {'from': frm, 'to': to, 'faces': {f: {'texture': '#' + tex, 'uv': FULL}
                                         for f in ('north', 'south', 'east', 'west', 'up', 'down')}}
    if rot:
        e['rotation'] = rot
    if light:
        e['light_emission'] = light
    return e


elements = [
    box([2, 0, 2], [14, 2, 14], 'gold'),  # plinth
    box([3, 2, 3], [13, 4, 13], 'stone'),
    box([5, 4, 5], [11, 22, 11], 'stone', light=6),  # the pillar
    box([4.5, 9, 4.5], [11.5, 10, 11.5], 'gold'),  # bands
    box([4.5, 16, 4.5], [11.5, 17, 11.5], 'gold'),
    box([3.5, 22, 3.5], [12.5, 24, 12.5], 'gold'),  # capital
]
# Four rays leaning out from the capital like a sunburst.
for (frm, to, origin, axis, angle) in (
        ([11.5, 20, 7], [13, 29, 9], [11.5, 20, 8], 'z', -24),
        ([3, 20, 7], [4.5, 29, 9], [4.5, 20, 8], 'z', 24),
        ([7, 20, 11.5], [9, 29, 13], [8, 20, 11.5], 'x', 24),
        ([7, 20, 3], [9, 29, 4.5], [8, 20, 4.5], 'x', -24)):
    rot = {'origin': origin, 'x': 0, 'y': 0, 'z': 0}
    rot[axis] = angle
    elements.append(box(frm, to, 'gold', rot, light=8))
# The crystal of light floating over the capital: a cube stood on its point, and a spark on top.
elements.append(box([5.5, 24.5, 5.5], [10.5, 29.5, 10.5], 'core',
                    {'origin': [8, 27, 8], 'x': 35.26, 'y': 45, 'z': 0}, light=15))
elements.append(box([7.25, 30.5, 7.25], [8.75, 32, 8.75], 'core', {'origin': [8, 31, 8], 'x': 0, 'y': 45, 'z': 0},
                    light=15))

model = {'textures': {'stone': 'lightborn:item/totem_stone', 'gold': 'lightborn:item/totem_gold',
                      'core': 'lightborn:item/totem_core', 'particle': 'lightborn:item/totem_gold'},
         'elements': elements}
(A / 'models/item').mkdir(parents=True, exist_ok=True)
(A / 'models/item/light_totem.json').write_text(json.dumps(model, indent=2) + '\n', encoding='utf-8')
(A / 'items').mkdir(parents=True, exist_ok=True)
(A / 'items/light_totem.json').write_text(
    json.dumps({'model': {'type': 'minecraft:model', 'model': 'lightborn:item/light_totem'}}, indent=2) + '\n',
    encoding='utf-8')
print('Totem of Light: textures, model and item definition written.')
