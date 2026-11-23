"""Spark, the Light's currency: a four-rayed gold glint with a white-hot core and a stray twinkle.

Hand-placed 16x16 pixel art (a currency icon is read at a glance in a stack, so every pixel is chosen, not noise).
Writes the item texture, its model, the item definition and the English name. Run: py -3 -I tools/make_spark.py
"""
from pathlib import Path
import json
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / 'src/main/resources/assets/lightborn'
PALETTE = {
    'W': (255, 253, 236),  # white-hot core
    'Y': (255, 230, 138),  # pale gold
    'G': (242, 186, 58),  # gold
    'O': (184, 116, 26),  # amber rim
    't': (255, 244, 196),  # twinkle
}
ART = [
    '.......O........',
    '.......G....t...',
    '.......Y...tWt..',
    '......GYG...t...',
    '......YWY.......',
    '.....GYWYG......',
    '...OGYWWWYGO....',
    'OGGYYYWWWYYYGGO.',
    '...OGYWWWYGO....',
    '.....GYWYG......',
    '......YWY.......',
    '..t...GYG.......',
    '.......Y........',
    '.......G........',
    '.......O........',
    '................',
]


def write(path, data):
    f = A / path
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


img = Image.new('RGBA', (16, 16))
px = img.load()
for y, row in enumerate(ART):
    for x, c in enumerate(row):
        if c in PALETTE:
            px[x, y] = PALETTE[c] + (255,)
(A / 'textures/item').mkdir(parents=True, exist_ok=True)
img.save(A / 'textures/item/spark.png')
write('models/item/spark.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'lightborn:item/spark'}})
write('items/spark.json', {'model': {'type': 'minecraft:model', 'model': 'lightborn:item/spark'}})
lang = A / 'lang/en_us.json'
j = json.loads(lang.read_text(encoding='utf-8'))
j['item.lightborn.spark'] = 'Spark'
lang.write_text(json.dumps(j, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Spark: texture, model, item definition and name written.')
