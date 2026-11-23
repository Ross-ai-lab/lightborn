"""The Spellstone (owner, 2026-11-18; first the Codex): a stone that holds four tomes and casts all four from the off hand.

A pearl-white opal about the size of a gold nugget (owner: "gold nugget size"), in a thin gold rim, with a gold
four-point rune at its heart and flecks of colour that shift over its face. A rainbow comet of light circles it like a
tilted ring (owner: "rainbowish glow circling over it"): in front of the stone on the lower half of its orbit, behind it
on the upper. No dark or violet tones (owner), so every colour, the comet's included, stays between red and sky blue.

Writes the animated item sprite (textures/item/spellstone.png and its .mcmeta: 32 frames, 2 ticks each, one orbit in
3.2 s), its model and item definition, the crafting recipe (smooth stone ringed with four lapis, two gold ingots and two
amethyst shards) and the English name. Run: py -3 -I tools/make_spellstone.py
"""
from pathlib import Path
import colorsys
import json
import math

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / 'src/main/resources/assets/lightborn'
D = ROOT / 'src/main/resources/data/lightborn'

FRAMES, FRAME_TICKS = 32, 2
SS = 8  # supersampling for the comet's soft edges

# 9 x 8, placed at (4, 4): '.' clear, O gold rim, W pearl, S soft pearl shade, H highlight, g rune gold, Y rune core,
# 1 to 4 the opal flecks whose colour shifts frame by frame.
STONE = [
    "..OOOOO..",
    ".OHH1WWO.",
    "OHWWgW2SO",
    "OW3gYgWSO",
    "OWWWgWSSO",
    "OW4WWS1SO",
    ".OSWW3SO.",
    "..OOOOO..",
]
FIXED = {
    'O': (226, 176, 72), 'W': (246, 242, 234), 'S': (220, 214, 206), 'H': (255, 255, 255),
    'g': (238, 168, 40), 'Y': (255, 238, 160),
}
FLECK_PHASE = {'1': 0.0, '2': 0.3, '3': 0.55, '4': 0.8}
HUE_SPAN = 220 / 360  # red .. sky blue: the rainbow without its violet end


def hue(t):
    """A colour wheel position for t in [0, 1): red to sky blue and back, never through violet."""
    t %= 1.0
    return HUE_SPAN * (1 - abs(2 * t - 1))


def rgb(h, s, v=1.0):
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    return round(r * 255), round(g * 255), round(b * 255)


def comet(frame, front):
    """The orbiting comet's light for one frame, as a 16 x 16 RGBA layer: only its front or its back half."""
    big = Image.new('RGBA', (16 * SS, 16 * SS))
    px = big.load()
    cx, cy, rx, ry, tilt = 8.0, 7.6, 7.0, 2.6, -0.22
    head = 2 * math.pi * frame / FRAMES
    tail = 4.0  # radians of tail behind the head
    steps = 260
    for i in range(steps):
        t = i / (steps - 1)  # 0 at the head, 1 at the tail's end
        a = head - t * tail
        if (math.sin(a) > 0) != front:
            continue
        x0, y0 = rx * math.cos(a), ry * math.sin(a)
        x = cx + x0 * math.cos(tilt) - y0 * math.sin(tilt)
        y = cy + x0 * math.sin(tilt) + y0 * math.cos(tilt)
        strength = (1 - t) ** 1.05
        radius = 0.62 + 0.7 * (1 - t) ** 3  # a round bright head, a thin tail
        colour = rgb(hue(frame / FRAMES + 0.9 * t), 0.12 + 0.8 * min(1, t * 4))
        r = radius * SS
        for yy in range(int((y * SS) - r - 1), int((y * SS) + r + 2)):
            for xx in range(int((x * SS) - r - 1), int((x * SS) + r + 2)):
                if 0 <= xx < 16 * SS and 0 <= yy < 16 * SS:
                    d = math.hypot(xx + .5 - x * SS, yy + .5 - y * SS) / r
                    if d <= 1:
                        alpha = round(255 * strength * math.sqrt(1 - d * d))
                        if alpha > px[xx, yy][3]:
                            px[xx, yy] = colour + (alpha,)
    return big.resize((16, 16), Image.BOX)


def stone(frame):
    layer = Image.new('RGBA', (16, 16))
    px = layer.load()
    for y, row in enumerate(STONE):
        for x, ch in enumerate(row):
            if ch in FIXED:
                px[4 + x, 4 + y] = FIXED[ch] + (255,)
            elif ch in FLECK_PHASE:
                px[4 + x, 4 + y] = rgb(hue(frame / FRAMES + FLECK_PHASE[ch]), 0.42) + (255,)
    return layer


sheet = Image.new('RGBA', (16, 16 * FRAMES))
for f in range(FRAMES):
    frame = Image.new('RGBA', (16, 16))
    frame.alpha_composite(comet(f, front=False))
    frame.alpha_composite(stone(f))
    frame.alpha_composite(comet(f, front=True))
    sheet.paste(frame, (0, 16 * f))
(A / 'textures/item').mkdir(parents=True, exist_ok=True)
sheet.save(A / 'textures/item/spellstone.png')


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


write(A / 'textures/item/spellstone.png.mcmeta', {'animation': {'frametime': FRAME_TICKS}})
write(A / 'models/item/spellstone.json',
      {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'lightborn:item/spellstone'}})
write(A / 'items/spellstone.json', {'model': {'type': 'minecraft:model', 'model': 'lightborn:item/spellstone'}})
write(D / 'recipe/spellstone.json', {
    'type': 'minecraft:crafting_shaped', 'category': 'misc',
    'pattern': ['LGL', 'ASA', 'LGL'],
    'key': {'L': 'minecraft:lapis_lazuli', 'G': 'minecraft:gold_ingot', 'A': 'minecraft:amethyst_shard',
            'S': 'minecraft:smooth_stone'},
    'result': {'id': 'lightborn:spellstone', 'count': 1}})
lang = A / 'lang/en_us.json'
j = json.loads(lang.read_text(encoding='utf-8'))
j.pop('item.lightborn.codex', None)
j['item.lightborn.spellstone'] = 'Spellstone'
lang.write_text(json.dumps(j, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Spellstone: animated sprite, model, item definition, recipe and name written.')
