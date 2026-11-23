"""Light Wings: the vanilla elytra recoloured to luminous ivory with a pale-gold spine, for the chapter 7 gift.

Reads the elytra textures straight from the Minecraft 26.3 client jar in the Loom cache, so nothing vanilla is
committed. Writes the worn wings, the item icons (whole and broken), the equipment asset, item models and the name.
Run: py -3 -I tools/make_wings.py
"""
from pathlib import Path
import io
import json
import zipfile
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / 'src/main/resources/assets/lightborn'
JAR = Path.home() / '.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.3/minecraft-clientonly-deobf-26.3.jar'
# Luminance ramp: shadow is a warm stone, mid is ivory, highlights are near-white with a faint gold blush.
RAMP = [(0.0, (96, 84, 66)), (0.35, (186, 170, 136)), (0.65, (236, 226, 200)), (1.0, (255, 252, 240))]


def ramp(t):
    for (a, ca), (b, cb) in zip(RAMP, RAMP[1:]):
        if t <= b:
            k = (t - a) / (b - a)
            return tuple(round(ca[i] + (cb[i] - ca[i]) * k) for i in range(3))
    return RAMP[-1][1]


def ivory(img):
    img = img.convert('RGBA')
    px = img.load()
    lum = [0.299 * r + 0.587 * g + 0.114 * b for r, g, b, a in img.get_flattened_data() if a]
    lo, hi = min(lum), max(lum)
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a:
                t = ((0.299 * r + 0.587 * g + 0.114 * b) - lo) / max(1, hi - lo)
                px[x, y] = ramp(t) + (a,)
    return img


def write(path, data):
    f = A / path
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


with zipfile.ZipFile(JAR) as z:
    def load(name):
        return Image.open(io.BytesIO(z.read('assets/minecraft/textures/' + name)))
    for src, dst in (('entity/equipment/wings/elytra.png', 'entity/equipment/wings/light_wings.png'),
                     ('item/elytra.png', 'item/light_wings.png'),
                     ('item/elytra_broken.png', 'item/light_wings_broken.png')):
        out = A / 'textures' / dst
        out.parent.mkdir(parents=True, exist_ok=True)
        ivory(load(src)).save(out)

# No player cape texture: the wings always show their own ivory.
write('equipment/light_wings.json', {'layers': {'wings': [{'texture': 'lightborn:light_wings', 'use_player_texture': False}]}})
for name in ('light_wings', 'light_wings_broken'):
    write(f'models/item/{name}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'lightborn:item/{name}'}})
write('items/light_wings.json', {'model': {'type': 'minecraft:condition', 'property': 'minecraft:broken',
                                           'on_false': {'type': 'minecraft:model', 'model': 'lightborn:item/light_wings'},
                                           'on_true': {'type': 'minecraft:model', 'model': 'lightborn:item/light_wings_broken'}}})
lang = A / 'lang/en_us.json'
j = json.loads(lang.read_text(encoding='utf-8'))
j['item.lightborn.light_wings'] = 'Light Wings'
lang.write_text(json.dumps(j, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Light Wings: wings, icons, equipment asset, models and name written.')
