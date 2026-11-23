"""Dark Nests (block ids dark_*): the core, a big dark shard in four growth stages, the creeping growth it spreads
round itself, and the goo still water turns to in its dark.

The core block draws only the pool of darkness at its foot; the crystal is its own model (dark_crystal_N, with an
item definition) that DarkNests stands over the core as a display scaled up to a person's height and more, since a
block model stops at two blocks (owner, 2026-11-18). Textures are drawn procedurally: the shard in the Dark Shard
item's violets, animated; the growth and the goo as slow black ooze. Writes the textures, block and item models,
block states and English names. Run: py -3 -I tools/make_dark_nest.py
"""
from pathlib import Path
import json
import random
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / 'src/main/resources/assets/lightborn'
FLESH = [(34, 18, 44), (48, 24, 58), (62, 30, 70), (78, 38, 82)]
VEIN, VEIN_DIM, SAC, SAC_RIM = (127, 191, 58), (82, 128, 44), (214, 220, 140), (150, 160, 86)


def write(path, data):
    f = A / path
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def flesh(seed, veins=3, sacs=0, alpha=255, holes=0.0):
    rnd = random.Random(seed)
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = FLESH[min(3, int(abs(rnd.gauss(1.3, 0.9))))] + (alpha,)
    for _ in range(veins):  # a wandering vein
        x, y = rnd.randrange(16), rnd.randrange(16)
        for _ in range(rnd.randint(8, 16)):
            px[x % 16, y % 16] = (VEIN if rnd.random() < .6 else VEIN_DIM) + (alpha,)
            x += rnd.choice((-1, 0, 1))
            y += rnd.choice((0, 1))
    for _ in range(sacs):  # a 2x2 egg sac with a darker rim pixel
        x, y = rnd.randrange(1, 14), rnd.randrange(1, 14)
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            px[x + dx, y + dy] = SAC + (255,)
        px[x + 1, y + 1] = SAC_RIM + (255,)
    for y in range(16):
        for x in range(16):
            if rnd.random() < holes:
                px[x, y] = (0, 0, 0, 0)
    return img


(A / 'textures/block').mkdir(parents=True, exist_ok=True)

# The core (owner, 2026-11-17): a big dark shard, the Dark Shard item's crystal grown huge, with smaller shards
# breaking out round it as it grows and a pool of darkness welling at its foot. Elements use 26.x's free XYZ rotation
# (any angle, origin at each shard's base) and their own light level, so the crystal reads at night.
K, D, B, G, C, W = (24, 13, 37), (55, 29, 79), (91, 49, 126), (144, 79, 187), (192, 125, 226), (239, 194, 251)
FRAMES = 16


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def strip(frames):
    """Frames stacked top to bottom: the layout an animated (.mcmeta) texture wants."""
    out = Image.new('RGBA', (16, 16 * len(frames)))
    for i, f in enumerate(frames):
        out.paste(f, (0, 16 * i))
    return out


def anim(name, img, frametime):
    img.save(A / f'textures/block/{name}.png')
    (A / f'textures/block/{name}.png.mcmeta').write_text(
        json.dumps({'animation': {'frametime': frametime, 'interpolate': True}}) + '\n', encoding='utf-8')


def shard_face(f):
    """A crystal face: near-black at the root, deep violet up top, a lit facet and a crack; a pulse climbs it."""
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    glow = 15 - f  # the pulse rises one pixel a frame and wraps
    for y in range(16):
        t = y / 15  # 0 at the top of the strip, 1 at the root
        for x in range(16):
            c = mix(D, K, t * 1.1)
            if x + (15 - y) * .35 < 7:  # the lit facet
                c = mix(c, B, .45 * (1 - t) + .12)
            if x == int(4 + y * .5) or x == int(11 - y * .3):  # cracks of light inside the crystal
                c = mix(c, G, .55 * (1 - t) + .2)
            if x == 0:
                c = mix(c, C, .8 * (1 - t) + .15)  # the bright leading edge
            elif x == 15:
                c = K
            d = min(abs(y - glow), 16 - abs(y - glow))
            c = mix(c, G, max(0.0, 1 - d / 2.5) * .5)
            px[x, y] = c + (255,)
    for (x, y, at) in ((3, 2, 3), (9, 5, 9), (5, 9, 13)):  # glints that flare one frame in sixteen
        if f == at:
            px[x, y] = W + (255,)
    return img


def shard_tip():
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5)) / 8
            px[x, y] = mix(W, mix(G, D, d), d * 1.4) + (255,)
    return img


def tileable_noise(seed, cell=4):
    rnd = random.Random(seed)
    n = 16 // cell
    grid = [[rnd.random() for _ in range(n)] for _ in range(n)]

    def at(x, y):
        gx, gy = x / cell, y / cell
        x0, y0 = int(gx) % n, int(gy) % n
        x1, y1 = (x0 + 1) % n, (y0 + 1) % n
        fx, fy = gx - int(gx), gy - int(gy)
        fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        top = grid[y0][x0] * (1 - fx) + grid[y0][x1] * fx
        bot = grid[y1][x0] * (1 - fx) + grid[y1][x1] * fx
        return top * (1 - fy) + bot * fy
    return at


def ooze(f):
    """The pool at the foot: black flowing on black, two currents crossing, violet flecks; a round, ragged rim."""
    a, b = tileable_noise(7), tileable_noise(11, cell=8)
    edge = tileable_noise(5)
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            r = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** .5
            if r > 6.2 + edge(x, y) * 1.8:
                continue
            v = a((x + f) % 16, y) * .55 + b(x, (y - f) % 16) * .45
            c = mix((8, 4, 12), D, v * 1.2 - .2)
            if v > .72:
                c = mix(c, G, (v - .72) * 2.5)
            px[x, y] = c + (255,)
    return img


(A / 'textures/block').mkdir(parents=True, exist_ok=True)
anim('dark_core_face', strip([shard_face(f) for f in range(FRAMES)]), 3)
shard_tip().save(A / 'textures/block/dark_core_tip.png')
anim('dark_core_ooze', strip([ooze(f) for f in range(FRAMES)]), 4)


def growth():
    """The creeping growth: the pool's black ooze laid flat and whole. Cut-out holes read as pale speckle in game."""
    a, b = tileable_noise(21), tileable_noise(23, cell=8)
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            v = a(x, y) * .6 + b(x, y) * .4
            c = mix((10, 5, 15), D, v * 1.1 - .15)
            if v > .74:
                c = mix(c, B, (v - .74) * 2)
            px[x, y] = c + (255,)
    return img


growth().save(A / 'textures/block/dark_growth.png')


def shard(bx, bz, h, w, rx, rz, ry=45.0, light=4):
    """One crystal standing on (bx, 0, bz): three tapering lengths sharing one rotation about its root."""
    out = []
    for a, b, s in ((0, .62, 1.0), (.62, .86, .66), (.86, 1.0, .3)):
        half = w * s / 2
        frm, to = [bx - half, h * a, bz - half], [bx + half, h * b, bz + half]
        for v in frm + to:
            assert -16 <= v <= 32, (frm, to)
        uv = [0, round(16 * (1 - b), 2), 16, round(16 * (1 - a), 2)]
        faces = {s_: {'texture': '#face', 'uv': uv} for s_ in ('north', 'south', 'east', 'west')}
        # Every face names its uv: a default one follows the element's bounds, which pass the block's 0-16.
        faces['up'] = {'texture': '#tip' if b == 1.0 else '#face', 'uv': [0, 0, 16, 16]}
        if a == 0:
            faces['down'] = {'texture': '#face', 'uv': [0, 0, 16, 16]}
        out.append({'from': frm, 'to': to, 'light_emission': light,
                    'rotation': {'origin': [bx, 0, bz], 'x': rx, 'y': ry, 'z': rz},
                    'faces': faces})
    return out


def pool(radius):
    """Three thin round slabs turned against each other: a puddle of darkness, never a square."""
    out = []
    for i, turn in enumerate((0, 30, 60)):
        y = 1.1 + i * .08
        out.append({'from': [8 - radius, y, 8 - radius], 'to': [8 + radius, y + .1, 8 + radius],
                    'rotation': {'origin': [8, y, 8], 'x': 0, 'y': turn, 'z': 0},
                    'faces': {'up': {'texture': '#ooze', 'uv': [0, 0, 16, 16]}}})
    return out


# stage: (pool radius, main shard (h, w, rx, rz), side shards [(bx, bz, h, w, rx, rz)])
STAGES = {
    1: (8, (16, 7, 6, -4), []),
    2: (11, (22, 9, 7, -5), [(13, 4, 11, 4.5, -18, 22)]),
    3: (14, (28, 10.5, 8, -5), [(14, 3, 15, 5.5, -20, 25), (2, 13, 12, 4.5, 22, -18), (3, 2, 8, 3.5, -25, -25)]),
    4: (16, (32, 12, 8, -5), [(15, 2, 19, 6, -22, 26), (1, 14, 16, 5.5, 24, -20), (2, 1, 11, 4, -28, -28),
                              (15, 15, 13, 4.5, 26, 24), (8, 16, 8, 3.5, 34, 6)]),
}
TEXTURES = {'face': 'lightborn:block/dark_core_face', 'tip': 'lightborn:block/dark_core_tip',
            'ooze': 'lightborn:block/dark_core_ooze', 'particle': 'lightborn:block/dark_core_tip'}
for stage, (radius, (h, w, rx, rz), sides) in STAGES.items():
    crystal = shard(8, 8, h, w, rx, rz, light=5)
    for (bx, bz, sh, sw, srx, srz) in sides:
        crystal += shard(bx, bz, sh, sw, srx, srz, ry=20.0 + bx * 3, light=3)
    write(f'models/block/dark_crystal_{stage}.json', {
        'parent': 'minecraft:block/block', 'ambientocclusion': False, 'textures': TEXTURES, 'elements': crystal})
    write(f'items/dark_crystal_{stage}.json', {'model': {'type': 'minecraft:model',
                                                         'model': f'lightborn:block/dark_crystal_{stage}'}})
    # The pool widens with the crystal over it (its display's scale), as far as a block model reaches.
    write(f'models/block/dark_nest_{stage}.json', {
        'parent': 'minecraft:block/block', 'ambientocclusion': False, 'textures': TEXTURES,
        'elements': pool(min(24, round(radius * 1.4)))})
write('blockstates/dark_nest.json', {'variants': {f'stage={s}': {'model': f'lightborn:block/dark_nest_{s}'}
                                                    for s in STAGES}})
write('models/block/dark_growth.json', {'parent': 'minecraft:block/carpet',
                                          'textures': {'wool': 'lightborn:block/dark_growth',
                                                       'particle': 'lightborn:block/dark_growth'}})
write('blockstates/dark_growth.json', {'variants': {'': [{'model': 'lightborn:block/dark_growth', 'y': y}
                                                           for y in (0, 90, 180, 270)]}})


def goo(f):
    """Still water the dark took (owner, 2026-11-18: it turns to goo): thick black-violet ooze, a slow sheen sliding
    over it and a bubble swelling and bursting now and then."""
    a, b = tileable_noise(31), tileable_noise(37, cell=8)
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            v = a((x + f) % 16, y) * .5 + b(x, (y + f // 2) % 16) * .5
            c = mix((12, 6, 18), D, v * 1.15 - .25)
            if v > .7:
                c = mix(c, B, (v - .7) * 2.2)
            px[x, y] = c + (255,)
    for (bx, by, at) in ((4, 5, 2), (11, 10, 9), (7, 13, 14)):
        age = (f - at) % FRAMES
        if age < 4:  # a bubble swells three frames and bursts on the fourth
            r = (0.6, 1.0, 1.4, 0.0)[age]
            for y in range(16):
                for x in range(16):
                    d = ((x - bx) ** 2 + (y - by) ** 2) ** .5
                    if r and d <= r + .2:
                        px[x, y] = (mix(B, C, .5) if d > r - .8 else mix(D, B, .4)) + (255,)
                    elif not r and abs(d - 1.6) < .5:
                        px[x, y] = mix(G, D, .3) + (255,)
    return img


anim('dark_goo', strip([goo(f) for f in range(FRAMES)]), 5)
write('models/block/dark_goo.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'lightborn:block/dark_goo'}})
write('blockstates/dark_goo.json', {'variants': {'': {'model': 'lightborn:block/dark_goo'}}})
lang = A / 'lang/en_us.json'
j = json.loads(lang.read_text(encoding='utf-8'))
j.update({'block.lightborn.dark_nest': 'Dark Nest', 'block.lightborn.dark_growth': 'Dark Growth',
          'block.lightborn.dark_goo': 'Dark Goo'})
lang.write_text(json.dumps(j, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Dark Nest: textures, pool and crystal models, growth carpet, goo, block states and names written.')
