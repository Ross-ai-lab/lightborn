"""The Light Dragon (the hovering Seraph form), built in one place: shape, motion, texture and preview renders.

Writes
  src/client/resources/lightborn/light_dragon_shape.txt   every part, box and motion channel (LightDragonModel reads it)
  src/main/resources/assets/lightborn/textures/entity/light_dragon.png        the skin, 4 texels per model pixel
  src/main/resources/assets/lightborn/textures/entity/light_dragon_glow.png   the light he gives off (eyes layer)
  <out dir>/dragon_*.png   flat-shaded preview renders (front, side, top, three-quarter) for judging the shape

Usage: python tools/generate_dragon.py [preview dir]

Everything is designed in "root space": model pixels, x to the Dragon's right as the viewer facing him sees it, y DOWN,
z toward his back (his face looks along -z). Each part is placed by its world position and orientation; the writer
converts that to the parent-relative pose ModelPart uses (translate, then rotate Z*Y*X). The UV layout is a 256 unit
atlas painted at 4x, so the Dragon reads at 64 texels a block where vanilla mobs have 16.
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
TEX = ROOT / "src/main/resources/assets/lightborn/textures/entity"
SHAPE = ROOT / "src/client/resources/lightborn/light_dragon_shape.txt"
UV = 256
S = 4  # texels per UV unit

# Material swatches in the atlas: u, v, width, height (UV units). Every box samples a swatch from its corner, so a
# swatch only has to be bigger than the largest box that uses it (checked in box()).
REG = {
    "body": (0, 0, 64, 24),
    "head": (64, 0, 56, 28),
    "snout": (120, 0, 48, 24),
    "frill": (168, 0, 32, 24),
    "belly": (200, 0, 56, 16),
    "jaw": (200, 16, 56, 20),
    "crest": (0, 36, 32, 16),
    "mane": (32, 36, 32, 16),
    "antler": (64, 36, 24, 16),
    "tine": (88, 36, 16, 16),
    "eye": (104, 36, 16, 8),
    "claw": (104, 44, 16, 8),
    "leg": (120, 36, 40, 16),
    "pearl": (160, 36, 24, 16),
    "whisker": (184, 36, 16, 8),
    "halo": (184, 44, 24, 8),
    "nose": (208, 36, 32, 12),
    "dark": (0, 56, 40, 16),
    "tooth": (40, 56, 16, 8),
    "wing": (0, 76, 64, 40),
    "horn": (64, 76, 24, 32),
}

# ------------------------------------------------------------------------------------------------ math


def vec(*a):
    return np.array(a, float)


def unit(a):
    return a / np.linalg.norm(a)


def frame(ydir, xhint):
    """Rotation whose local y is ydir and whose local x lies as close to xhint as it can."""
    y = unit(np.asarray(ydir, float))
    x = np.asarray(xhint, float) - np.dot(xhint, y) * y
    if np.linalg.norm(x) < 1e-6:
        x = np.cross(y, vec(0, 0, 1))
    x = unit(x)
    z = np.cross(x, y)
    return np.column_stack([x, y, z])


def rot(xr, yr, zr):
    cx, sx, cy, sy, cz, sz = math.cos(xr), math.sin(xr), math.cos(yr), math.sin(yr), math.cos(zr), math.sin(zr)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


def euler(m):
    """Z*Y*X Euler angles (xRot, yRot, zRot) of a rotation matrix, the order ModelPart applies them."""
    b = -math.asin(max(-1.0, min(1.0, m[2, 0])))
    a = math.atan2(m[2, 1], m[2, 2])
    g = math.atan2(m[1, 0], m[0, 0])
    return a, b, g


def smooth(e0, e1, x):
    t = min(1.0, max(0.0, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


# ------------------------------------------------------------------------------------------------ parts


class Part:
    def __init__(self, name, parent, pos, m):
        self.name, self.parent, self.pos, self.m = name, parent, np.asarray(pos, float), np.asarray(m, float)
        self.boxes = []
        self.motion = []


PARTS = {}
ORDER = []


def part(name, parent, pos, m=None):
    assert name not in PARTS, name
    p = Part(name, parent, pos, np.eye(3) if m is None else m)
    PARTS[name] = p
    ORDER.append(p)
    return p


def box(p, region, x, y, z, w, h, d):
    u, v, rw, rh = REG[region]
    assert 2 * d + 2 * w <= rw + 1e-6 and d + h <= rh + 1e-6, (p.name, region, w, h, d)
    p.boxes.append((region, x, y, z, w, h, d))


def cbox(p, region, cx, cy, cz, w, h, d):
    """A box given by its centre."""
    box(p, region, cx - w / 2, cy - h / 2, cz - d / 2, w, h, d)


def motion(p, freq, phase, rx=0, ry=0, rz=0, tx=0, ty=0, tz=0, spin=0):
    p.motion.append((freq, phase, rx, ry, rz, tx, ty, tz, spin))


def at(p, local):
    """World position of a point given in part p's local frame."""
    return p.pos + p.m @ np.asarray(local, float)


def strand(name, parent, base, dirs, lengths, width, thick, region, xhint, flap, freq, phase, lag=0.8):
    """A chain of flat ribbons: each link starts where the last one ended. Broad along local x, thin along z, so a
    flap about x (the innermost rotation) ripples it like cloth. Amplitude grows toward the tip."""
    prev, pos = parent, np.asarray(base, float)
    links = []
    for i, (d, length) in enumerate(zip(dirs, lengths)):
        m = frame(d, xhint)
        p = part(f"{name}_{i}", prev, pos, m)
        taper = 1 - 0.18 * i
        box(p, region, -width * taper / 2, 0, -thick / 2, width * taper, length, thick)
        motion(p, freq, phase - i * lag, rx=flap * (0.55 + 0.45 * i))
        pos = at(p, (0, length * 0.92, 0))
        prev = p.name
        links.append(p)
    return links


def bend(d0, toward, n, amount):
    """n directions starting at d0 and turning progressively toward 'toward'."""
    d0, toward = unit(np.asarray(d0, float)), unit(np.asarray(toward, float))
    return [unit(d0 * (1 - amount * i / max(1, n - 1)) + toward * (amount * i / max(1, n - 1))) for i in range(n)]


# ------------------------------------------------------------------------------------------------ the body

# The Seraph (owner, 2026-11-17: the upright Guardian sat like a statue of a lizard; he asked for something majestic
# that still fits a house, over the chest). Everything about him is vertical, so he fills a tall narrow space the way
# a figure fills a shrine: a swan's S from the hanging tail up through a proud chest and an arched neck to a head bowed
# toward the room, two great wings raised behind him and draped down both sides like a mantle, a thin ring of light
# behind his head, a mane and long whiskers of light that drift, and the tail hanging beneath him in a crosier's curl.
# Still at most 3 blocks wide and 4 tall, the pearl of light held before his heart, the eyes set wider than the muzzle.
#
# The spine from the tail tip to the base of the skull, each point with the body's radius there.
CTRL = [
    (8.5, -9.5, 1.5, 0.55),
    (12.0, -8.5, 1.0, 0.8),
    (14.6, -5.5, 0.2, 1.1),
    (14.0, -1.8, -0.8, 1.45),
    (10.0, 0.2, -1.8, 1.8),
    (5.5, -1.2, -1.6, 2.2),
    (2.2, -5.0, 0.4, 2.7),
    (0.6, -10.0, 3.0, 3.3),
    (0, -15.5, 5.6, 4.3),
    (0, -21, 5.4, 5.5),
    (0, -27, 2.6, 5.3),
    (0, -33, -0.8, 6.8),
    (0, -38.5, 0.0, 6.9),
    (0, -43, 3.2, 4.8),
    (0, -48, 6.2, 3.7),
    (0, -53, 6.0, 3.2),
    (0, -56.8, 3.0, 2.9),
    (0, -58.6, -1.0, 2.9),
]
HIP, SHOULDER, NECK = 8, 12, 13  # indices into CTRL
PER = 60


def catmull(points, per=PER):
    pts = [np.array(p, float) for p in points]
    pts = [2 * pts[0] - pts[1]] + pts + [2 * pts[-1] - pts[-2]]
    out = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for k in range(per):
            t = k / per
            out.append(
                0.5
                * (
                    2 * p1
                    + (-p0 + p2) * t
                    + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                    + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t
                )
            )
    out.append(pts[-2])
    return np.array(out)


dense = catmull(CTRL)
arc = np.concatenate([[0], np.cumsum(np.linalg.norm(np.diff(dense[:, :3], axis=0), axis=1))])
S_HIP, S_SHOULDER, S_NECK = (arc[k * PER] / arc[-1] for k in (HIP, SHOULDER, NECK))
SPACING = 1.3
N = int(arc[-1] / SPACING) + 1
samples = np.linspace(0, arc[-1], N)
spine_pos = np.array([[np.interp(s, arc, dense[:, a]) for a in range(3)] for s in samples])
spine_r = np.array([np.interp(s, arc, dense[:, 3]) for s in samples])
spine_tan = np.array([unit(t) for t in np.gradient(spine_pos, axis=0)])

root = part("dragon", None, (0, 0, 0))
spine = []
for i in range(N):
    s = i / (N - 1)
    r = float(spine_r[i])
    tan = spine_tan[i]
    up = abs(tan[1])
    # Belly down along the tail, toward the room (-z) up the upright chest and neck.
    ventral = unit(vec(0, 1, 0) * (1 - up) + vec(0, 0.25, -1) * up)
    m = frame(tan, ventral)
    seg = part(f"spine{i}", "dragon", spine_pos[i], m)
    seg.r, seg.s = r, s
    length = SPACING * 2.1
    # The chest is broader than it is deep: a proud breast, not a tube.
    broad = smooth(4.4, 6.0, r)
    wf, df = 1 + 0.2 * broad, 1 - 0.08 * broad
    seg.front = r * df
    cbox(seg, "body", 0, 0, 0, 2 * r * df, length, 1.5 * r * wf)
    cbox(seg, "body", 0, 0, 0, 1.5 * r * df, length, 2 * r * wf)
    if i % 2 == 0 and s < 0.98:
        cbox(seg, "belly", r * df + 0.05, 0, 0, 0.9, SPACING * 1.8, (1.2 + 0.25 * broad) * r)
    # The tail sways, more toward its tip; the chest breathes.
    tail = 1 - smooth(S_HIP * 0.6, S_HIP, s)
    amp = 0.6 * tail * (1 - s / S_HIP if s < S_HIP else 0) + 0.05
    side = m[:, 2] * amp
    motion(seg, 0.045, -i * 0.22, tx=side[0], ty=side[1], tz=side[2])
    spine.append(seg)


def nearest(y, z):
    """The spine segment nearest a point on the centre line."""
    return spine[int(np.argmin([np.linalg.norm(p - vec(0, y, z)) for p in spine_pos]))]


# Dorsal fins of light down the back, tallest between the hips and the shoulders, small along the tail.
for i in range(2, N - 2, 2):
    seg = spine[i]
    s = seg.s
    if s >= S_NECK:
        break
    hf = 0.7 + 2.3 * math.sin(math.pi * min(1, max(0, (s - 0.12) / (S_NECK - 0.12)))) ** 1.2
    m = seg.m @ rot(0, 0, 0.5)
    fin = part(f"fin{i}", seg.name, at(seg, (-seg.front + 0.4, 0, 0)), m)
    box(fin, "crest", -hf, -1.0, -0.2, hf, 2.0, 0.4)
    motion(fin, 0.09, -i * 0.35, rz=0.12)

# The mane: ribbons of light down the back of the neck, in pairs, falling away behind him and drifting.
n_mane = 0
for i in range(N - 2, 0, -2):
    seg = spine[i]
    if seg.s < S_NECK - 0.02:
        break
    dorsal = -seg.m[:, 0]
    for side in (-1, 1):
        d0 = unit(dorsal * 0.4 + vec(side * 0.3, 0.9, 0.15))
        dirs = bend(d0, vec(side * 0.1, 1.0, 0.25), 3, 0.8)
        strand(f"mane{n_mane}", seg.name, at(seg, (-seg.front + 0.3, 0, side * 0.9)), dirs, [3.8, 3.4, 2.8], 2.4, 0.3,
               "mane", vec(side, 0, 0.5), 0.1, 0.085, n_mane * 0.7)
        n_mane += 1

# The tail's end: a spade of light, three blades fanning from the tip.
tip = spine[0]
for n, spread in enumerate((-0.7, 0, 0.7)):
    d0 = unit(-spine_tan[0] + tip.m[:, 2] * spread)
    dirs = bend(d0, d0 + vec(0, -0.5, 0), 3, 0.5)
    strand(f"spade{n}", tip.name, tip.pos, dirs, [2.6, 2.2, 1.7], 1.9, 0.4, "mane", tip.m[:, 2], 0.2, 0.12, n * 0.9)


# ------------------------------------------------------------------------------------------------ limbs


def two_bone(hip, target, a, b, pole):
    """Knee and foot for a two-bone limb reaching from hip toward target, bending toward pole."""
    d = target - hip
    dist = min(np.linalg.norm(d), (a + b) * 0.97)
    n = unit(d)
    cos_h = (a * a + dist * dist - b * b) / (2 * a * dist)
    sin_h = math.sqrt(max(0.0, 1 - cos_h * cos_h))
    bend_dir = unit(pole - np.dot(pole, n) * n)
    return hip + n * a * cos_h + bend_dir * a * sin_h, hip + n * dist


def limb(name, seg, hip, target, pole, a, b, thick, grip=None, phase=0.0):
    tan = seg.m[:, 1]
    knee, foot_at = two_bone(hip, target, a, b, pole)
    upper = part(f"{name}_thigh", seg.name, hip, frame(knee - hip, tan))
    cbox(upper, "leg", 0, a / 2, 0, thick * 1.25, a + 1.0, thick)
    cbox(upper, "leg", 0, a / 2, 0, thick, a + 1.0, thick * 1.25)
    motion(upper, 0.04, phase, rx=0.05, rz=0.03)
    lower = part(f"{name}_shin", upper.name, knee, frame(foot_at - knee, tan))
    cbox(lower, "leg", 0, b / 2, 0, thick * 0.95, b + 0.6, thick * 0.8)
    cbox(lower, "leg", 0, b / 2, 0, thick * 0.8, b + 0.6, thick * 0.95)
    down = unit(foot_at - knee) if grip is None else unit(grip - foot_at)
    foot = part(f"{name}_foot", lower.name, foot_at, frame(down, tan))
    cbox(foot, "leg", 0, 0.8, 0, thick * 1.1, 1.8, thick * 1.05)
    for t in range(3):  # three talons, curling inward
        ang = t / 3 * 2 * math.pi + 0.5
        c, sn = math.cos(ang), math.sin(ang)
        d1 = unit(foot.m @ vec(c * 0.7, 1.0, sn * 0.7))
        t1 = part(f"{name}_talon{t}", foot.name, at(foot, (c * 0.9, 1.5, sn * 0.9)), frame(d1, foot.m[:, 1]))
        cbox(t1, "claw", 0, 0.9, 0, 0.7, 1.9, 0.7)
        d2 = unit(foot.m @ vec(-c * 0.4, 1.0, -sn * 0.4))
        t2 = part(f"{name}_talon{t}b", t1.name, at(t1, (0, 1.7, 0)), frame(d2, foot.m[:, 1]))
        cbox(t2, "claw", 0, 0.8, 0, 0.5, 1.6, 0.5)
    return foot


# The pearl of light before his heart, held in the near front claw, the far claw cupped beneath it.
pearl_at = vec(0.0, -31.0, -10.6)
chest = nearest(-36, 0.6)
for side, name, grip_at in ((1, "frontNear", pearl_at + vec(1.6, 0.6, 0.2)), (-1, "frontFar", pearl_at + vec(-1.4, 2.4, 0.4))):
    hip = vec(side * 6.9, -36.5, -3.0)
    limb(name, chest, hip, grip_at + vec(side * 1.5, 1.5, -0.2), vec(side * 1.0, 0.5, 0.2), 5.2, 4.8, 2.1,
         grip=pearl_at, phase=side)
hips = nearest(-20, 4.6)
for side in (-1, 1):
    hip = vec(side * 5.2, -20.0, 4.0)
    limb("rearNear" if side > 0 else "rearFar", hips, hip, vec(side * 6.0, -15.0, -3.4), vec(side * 0.4, -0.8, -1.0),
         5.8, 5.4, 2.6, phase=2 + side)

pearl = part("pearl", "frontNear_foot", pearl_at, np.eye(3))
cbox(pearl, "pearl", 0, 0, 0, 3.4, 3.4, 3.4)
gem = part("pearl_gem", "pearl", pearl_at, rot(0.785, 0.785, 0))
cbox(gem, "pearl", 0, 0, 0, 2.9, 2.9, 2.9)
motion(pearl, 0, 0, spin=0.045)
halo = part("halo", "pearl", pearl_at, rot(0.5, 0, 0.35))
for h in range(10):
    ang = h / 10 * 2 * math.pi
    hs = part(f"halo{h}", "halo", at(halo, (math.cos(ang) * 3.4, 0, math.sin(ang) * 3.4)), halo.m @ rot(0, -ang, 0))
    cbox(hs, "halo", 0, 0, 0, 0.3, 0.3, 2.0)
motion(halo, 0, 0, spin=-0.11)

# The ring of light behind his head: a thin circle facing the room, turning slowly, with short rays.
AUREOLE_AT, AUREOLE_R = vec(0, -58.5, 10.4), 9.0
aureole = part("aureole", "dragon", AUREOLE_AT, frame(vec(0, 0, 1), vec(1, 0, 0)))
RING = 18
for h in range(RING):
    ang = h / RING * 2 * math.pi
    rs = part(f"aureole{h}", "aureole", at(aureole, (math.cos(ang) * AUREOLE_R, 0, math.sin(ang) * AUREOLE_R)),
              aureole.m @ rot(0, -ang, 0))
    cbox(rs, "halo", 0, 0, 0, 0.5, 0.4, 2 * math.pi * AUREOLE_R / RING + 0.15)
    if h % 3 == 0:
        cbox(rs, "halo", 1.9, 0, 0, 2.8, 0.3, 0.5)
motion(aureole, 0, 0, spin=0.012)

# ------------------------------------------------------------------------------------------------ wings

# Each wing is designed flat in its own plane, (a outward, b up) from the shoulder, then stood up behind him. The arm
# climbs to a wrist level with his crown and the fingers fall from it, so the membrane hangs down each side of him
# like a mantle: tall and narrow, the shape of the room he lives in. The wings lean a little forward over him. The
# membrane is cut into level bands that never overlap (no flicker where boxes would share a plane); the bones are
# gold-ringed ivory; the whole wing breathes slowly from the shoulder.
ELBOW, WRIST = (6.0, 10.0), (13.0, 23.0)
WING_TIPS = [(22.0, 15.5), (25.0, 2.5), (23.5, -11.5), (18.0, -22.5), (10.5, -27.0)]
WING_ROOT = (2.0, -12.0)
WING_HEART = (9.0, 2.0)  # the scallops between the finger tips draw in toward here


def wing_outline():
    pts = [(0, 0), ELBOW, WRIST]
    prev = None
    for t in WING_TIPS + [WING_ROOT]:
        if prev is not None:
            mid = ((prev[0] + t[0]) / 2, (prev[1] + t[1]) / 2)
            pull = 0.24 if t is not WING_ROOT else 0.12
            pts.append((mid[0] + (WING_HEART[0] - mid[0]) * pull, mid[1] + (WING_HEART[1] - mid[1]) * pull))
        pts.append(t)
        prev = t
    return pts


def span_at(poly, b):
    xs = []
    for (a0, b0), (a1, b1) in zip(poly, poly[1:] + poly[:1]):
        if (b0 <= b < b1) or (b1 <= b < b0):
            xs.append(a0 + (b - b0) * (a1 - a0) / (b1 - b0))
    xs.sort()  # entering and leaving the outline in turn: one span, or two where a scallop cuts the band
    return [(xs[i], xs[i + 1]) for i in range(0, len(xs) - 1, 2)]


OUTLINE = wing_outline()
WING_V, WING_T = 120, 0.3  # the first atlas row of the wing strips; the membrane's thickness
WING_BANDS = {}
for side in (-1, 1):
    shoulder = vec(side * 3.6, -39.0, 6.0)
    U = unit(vec(side * 1.0, 0, -0.16))
    V = unit(vec(0, -1.0, -0.07))
    m = frame(V, U)
    wing = part(f"wing{side}", chest.name, shoulder, m)
    motion(wing, 0.04, 0.0, ry=-side * 0.07, rz=side * 0.03)
    band = 1.5
    b = -27 + band / 2
    k = 0
    while b < 23:
        for j, sp in enumerate(span_at(OUTLINE, b)):
            if sp[1] - sp[0] <= 0.3:
                continue
            # Every band has its own strip of the atlas, so the wing can carry one picture across all of them.
            reg = f"wingband{k}_{j}"
            if reg not in REG:
                REG[reg] = (64 * j, WING_V + 2 * k, math.ceil(2 * (sp[1] - sp[0]) + 0.6), 2)
                assert REG[reg][2] <= 64 and j < 4, reg
                WING_BANDS[reg] = (sp[0], sp[1], b - band / 2)
            box(wing, reg, sp[0], b - band / 2, -WING_T / 2, sp[1] - sp[0], band, WING_T)
        k += 1
        b += band

    def plane(a, bb):  # a point of the wing's own frame (the membrane boxes above are laid out in it)
        return shoulder + m[:, 0] * a + m[:, 1] * bb

    bones = [((0, 0), ELBOW, 1.7), (ELBOW, WRIST, 1.4)]
    bones += [(WRIST, t, 0.9 - 0.1 * n) for n, t in enumerate(WING_TIPS)]
    for n, (p0, p1, th) in enumerate(bones):
        w0, w1 = plane(*p0), plane(*p1)
        bone = part(f"wingbone{side}_{n}", wing.name, w0, frame(w1 - w0, m[:, 2]))
        length = np.linalg.norm(w1 - w0)
        pieces = math.ceil(length / 24)  # the horn swatch is 32 long
        for q in range(pieces):
            cbox(bone, "horn", 0, length * (q + 0.5) / pieces, 0, th, length / pieces + 0.4, th)
    claw = part(f"wingclaw{side}", wing.name, plane(*WRIST), frame(V * 1.0 + U * 0.35, m[:, 2]))
    cbox(claw, "claw", 0, 1.0, 0, 0.9, 2.4, 0.9)

# ------------------------------------------------------------------------------------------------ the head

neck = spine[-1]
head_at = neck.pos + spine_tan[-1] * 0.8
# Designed around its own origin (the neck joint), then scaled and bowed toward whoever stands below.
HEAD_SCALE = 1.12
HEAD_PITCH = 0.36
EYE_W, EYE_H, EYE_D = 2.1, 1.0, 0.6
head = part("head", "dragon", (0, 0, 0))


def hb(region, x0, y0, z0, x1, y1, z1, p=head):
    box(p, region, x0, y0, z0, x1 - x0, y1 - y0, z1 - z0)


# Skull: a long, fine head. A rounded crown, a brow ridge over each eye, then a muzzle that narrows in three steps
# (never wider than the space between the eyes) to a small nose with a raised bridge.
hb("head", -3.4, -5.8, -5.6, 3.4, 0.0, 2.4)
hb("head", -2.6, -6.6, -4.6, 2.6, -5.4, 1.6)
hb("snout", -2.3, -4.4, -9.6, 2.3, -0.4, -5.2)
hb("snout", -1.8, -3.8, -13.2, 1.8, -0.6, -9.4)
hb("nose", -1.3, -3.5, -15.0, 1.3, -0.9, -13.0)
hb("nose", -0.8, -4.0, -14.6, 0.8, -3.4, -13.3)
hb("dark", -1.0, -3.0, -15.1, -0.4, -2.4, -14.7)
hb("dark", 0.4, -3.0, -15.1, 1.0, -2.4, -14.7)
hb("dark", -2.0, -0.6, -13.0, 2.0, 0.1, -5.8)
for side in (-1, 1):
    hb("tooth", side * 1.4 - 0.3, -0.5, -12.8, side * 1.4 + 0.3, 1.0, -12.2)
    hb("tooth", side * 1.7 - 0.25, -0.5, -10.4, side * 1.7 + 0.25, 0.5, -9.9)
    # Brow ridge: a gold-edged shelf over the eye, swept back into the crown.
    brow = part(f"brow{side}", "head", vec(side * 3.0, -5.3, -5.1), rot(0.22, -side * 0.3, side * 0.25))
    cbox(brow, "jaw", 0, 0, 0, 2.6, 0.7, 3.6)
    # The eye sits outside the muzzle's line, angled forward, so it shows from in front, below and either side.
    # Narrow and half-lidded under the brow (owner, 2026-11-17: big round eyes made him look dorky).
    eye = part(f"eye{side}", "head", vec(side * 3.2, -4.25, -5.75), rot(0, -side * 0.42, side * 0.12))
    cbox(eye, "eye", 0, 0, 0, EYE_W, EYE_H, EYE_D)
    # Cheek plates behind the eye.
    hb("jaw", side * 3.1 - (0.9 if side > 0 else 0), -3.0, -4.8, side * 3.1 + (0 if side > 0 else 0.9), 0.2, 0.2)
    # A fan of three light fins behind each cheek, like swept-back ears.
    for n in range(3):
        d = unit(vec(side * 0.7, -0.75 + n * 0.55, 0.85))
        fr = part(f"frill{side}_{n}", "head", vec(side * 3.3, -2.9 + n * 0.9, 0.4), frame(d, vec(0, 1, 0.2)))
        box(fr, "frill", -0.9, 0, -0.15, 1.8, 5.0 - n * 0.9, 0.3)
        motion(fr, 0.07, n * 0.8 + side, rx=0.09)
    # Whiskers of light from the sides of the muzzle: long, trailing down and back, always drifting.
    wd = bend(vec(side * 1.0, 0.35, -0.1), vec(side * 0.2, 1.0, 0.6), 5, 1.0)
    strand(f"whisker{side}", "head", vec(side * 1.7, -1.5, -12.3), wd, [3.0, 3.2, 3.2, 3.0, 2.6], 0.5, 0.4, "whisker",
           vec(0, 0, 1), 0.1, 0.075, side * 1.3, lag=0.7)
jaw = part("jaw", "head", vec(0, -0.2, -5.2), rot(0.07, 0, 0))
hb("snout", -1.9, 0, -7.5, 1.9, 1.2, 0.4, jaw)
hb("jaw", -1.5, 1.2, -7.0, 1.5, 1.7, 0.2, jaw)
motion(jaw, 0.03, 0, rx=0.04)
# A short beard of light under the chin.
strand("beard", "jaw", at(jaw, (0, 1.6, -4.6)), bend(vec(0, 1.0, 0.1), vec(0, 0.7, 1.0), 3, 0.7), [2.4, 2.0, 1.6], 1.2,
       0.3, "whisker", vec(1, 0, 0), 0.12, 0.08, 0.4)
# Horns: two long ivory horns swept back from the crown in a slow S, ringed in gold, and a shorter pair below them.
for side in (-1, 1):
    for tag, base, dirs, lens, thick in (
        ("horn", vec(side * 2.2, -6.2, 0.4),
         [(side * 0.3, -0.45, 0.9), (side * 0.38, -0.1, 1.0), (side * 0.3, 0.15, 1.0), (side * 0.15, -0.2, 1.0)],
         (3.4, 3.4, 3.0, 2.4), (1.6, 1.3, 1.0, 0.7)),
        ("horn2_", vec(side * 3.0, -4.9, 1.8),
         [(side * 0.85, -0.1, 1.0), (side * 0.6, 0.1, 1.0)], (3.0, 2.4), (1.1, 0.7)),
    ):
        prev, pos = "head", base
        for i, (d, length, t) in enumerate(zip(dirs, lens, thick)):
            h = part(f"{tag}{side}_{i}", prev, pos, frame(unit(vec(*d)), vec(1, 0, 0)))
            cbox(h, "horn", 0, length / 2, 0, t, length + 0.3, t)
            pos = at(h, (0, length, 0))
            prev = h.name
# A central ridge of light fins from brow to crown.
for n in range(3):
    fin = part(f"crownfin{n}", "head", vec(0, -6.4 - 0.15 * n, -3.2 + n * 2.1), rot(-0.5, 0, 0))
    box(fin, "crest", -0.2, -2.0 + 0.4 * n, -0.8, 0.4, 2.0 - 0.4 * n, 1.6)


def subtree(name):
    out = [PARTS[name]]
    for q in ORDER:
        if q.parent is not None and any(q.parent == o.name for o in out) and q not in out:
            out.append(q)
    return out


tip_down = rot(HEAD_PITCH, 0, 0)
for q in subtree("head"):
    q.pos = head_at + tip_down @ (HEAD_SCALE * q.pos)
    q.m = tip_down @ q.m
    q.boxes = [(r, *(c * HEAD_SCALE for c in b)) for r, *b in q.boxes]


# ------------------------------------------------------------------------------------------------ world geometry


def world_quads():
    """Every box face in root space (rest pose), with the material it shows."""
    quads = []
    for p in ORDER:
        for region, x, y, z, w, h, d in p.boxes:
            c = [vec(x + w * i, y + h * j, z + d * k) for i in (0, 1) for j in (0, 1) for k in (0, 1)]
            wc = [p.pos + p.m @ q for q in c]
            idx = lambda i, j, k: wc[i * 4 + j * 2 + k]
            faces = [
                [idx(0, 0, 0), idx(0, 1, 0), idx(0, 1, 1), idx(0, 0, 1)],
                [idx(1, 0, 0), idx(1, 0, 1), idx(1, 1, 1), idx(1, 1, 0)],
                [idx(0, 0, 0), idx(0, 0, 1), idx(1, 0, 1), idx(1, 0, 0)],
                [idx(0, 1, 0), idx(1, 1, 0), idx(1, 1, 1), idx(0, 1, 1)],
                [idx(0, 0, 0), idx(1, 0, 0), idx(1, 1, 0), idx(0, 1, 0)],
                [idx(0, 0, 1), idx(0, 1, 1), idx(1, 1, 1), idx(1, 0, 1)],
            ]
            for f in faces:
                quads.append((np.array(f), region))
    return quads


# ------------------------------------------------------------------------------------------------ texture

H = W = UV * S
skin = np.zeros((H, W, 4))
glow = np.zeros((H, W, 4))
rng = np.random.default_rng(402)


def area(name):
    u, v, w, h = REG[name]
    return slice(v * S, (v + h) * S), slice(u * S, (u + w) * S), h * S, w * S


def put(name, rgb, alpha=255, glow_rgb=None, glow_a=None):
    ys, xs, h, w = area(name)
    skin[ys, xs, :3] = rgb
    skin[ys, xs, 3] = alpha
    if glow_rgb is not None:
        glow[ys, xs, :3] = glow_rgb
        glow[ys, xs, 3] = glow_a


def mix(a, b, t):
    t = np.asarray(t, float)[..., None]
    return np.asarray(a, float) * (1 - t) + np.asarray(b, float) * t


def scales(h, w, cw, ch, seed):
    """Overlapping fish scales: radial distance inside each scale (0 centre, 1 rim) and a per-scale random value."""
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    dist = np.ones((h, w))
    tint = np.zeros((h, w))
    r = np.random.default_rng(seed)
    rows = int(h / ch) + 3
    for j in range(-2, rows):
        cy = j * ch
        off = (j % 2) * cw / 2
        col = np.round((xx - off) / cw)
        cx = col * cw + off
        dx, dy = xx - cx, yy - cy
        rad = cw * 0.64
        d = np.sqrt(dx * dx + dy * dy) / rad
        inside = (d <= 1) & (dy >= -rad * 0.2)
        dist = np.where(inside, d, dist)
        rnd = r.random(int(w / cw) + 4)
        tint = np.where(inside, rnd[(col.astype(int) + 2) % len(rnd)], tint)
    return dist, tint


def smooth_arr(e0, e1, x):
    t = np.clip((np.asarray(x, float) - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


wing_rng = np.random.default_rng(1117)
IVORY = vec(240, 238, 232)
PEARL_HI = vec(255, 254, 250)
WARM_RIM = vec(206, 176, 132)
COOL = vec(198, 208, 238)
ROSE = vec(255, 158, 150)
VIOLET = vec(138, 92, 196)
# The wings' ramp, from the wrist (0) to the trailing edge (1).
DAWN = [
    (0.0, vec(26, 36, 96)),
    (0.38, vec(58, 52, 146)),
    (0.6, vec(132, 76, 172)),
    (0.76, vec(228, 118, 150)),
    (0.89, vec(255, 190, 108)),
    (1.0, vec(255, 246, 212)),
]


def dawn(t):
    t = np.clip(t, 0, 1)
    out = np.zeros(t.shape + (3,))
    for (t0, c0), (t1, c1) in zip(DAWN, DAWN[1:]):
        k = np.clip((t - t0) / (t1 - t0), 0, 1)[..., None]
        out = np.where((t >= t0)[..., None], c0 * (1 - k) + c1 * k, out)
    return out
GOLD = vec(232, 186, 88)
GOLD_HI = vec(255, 226, 150)
GOLD_LO = vec(150, 104, 44)
LIGHT = vec(255, 240, 186)
LIGHT_HI = vec(255, 253, 238)
TEAL = vec(70, 214, 206)


def scale_skin(name, cw, ch, seed, flip=True, glow_rim=46):
    ys, xs, h, w = area(name)
    d, t = scales(h, w, cw, ch, seed)
    if flip:
        d, t = d[::-1], t[::-1]
    base = mix(IVORY, COOL, 0.35 * t)  # pearly variation scale to scale
    col = mix(PEARL_HI, base, np.clip(d / 0.7, 0, 1))
    rim = np.clip((d - 0.78) / 0.22, 0, 1)
    col = mix(col, WARM_RIM, rim * 0.85)
    shadow = np.clip((d - 0.93) / 0.07, 0, 1)
    col = mix(col, GOLD_LO, shadow * 0.5)
    skin[ys, xs, :3] = col
    skin[ys, xs, 3] = 255
    glow[ys, xs, :3] = GOLD_HI
    glow[ys, xs, 3] = glow_rim * rim + 10


scale_skin("body", 9, 6, 1)
scale_skin("head", 6, 4, 2, flip=False, glow_rim=30)
scale_skin("leg", 6, 4, 3, glow_rim=30)

# Snout: smooth ivory with fine dappled scales, warm toward the nose.
ys, xs, h, w = area("snout")
d, t = scales(h, w, 4, 3, 4)
col = mix(IVORY, PEARL_HI, 0.5 * (1 - d))
col = mix(col, WARM_RIM, np.clip((d - 0.85) / 0.15, 0, 1) * 0.5)
skin[ys, xs, :3] = col
skin[ys, xs, 3] = 255
glow[ys, xs, :3] = GOLD_HI
glow[ys, xs, 3] = 8

# Nose: burnished gold.
ys, xs, h, w = area("nose")
yy, xx = np.mgrid[0:h, 0:w].astype(float)
skin[ys, xs, :3] = mix(GOLD, GOLD_HI, 0.5 + 0.5 * np.sin(xx * 0.7) * np.sin(yy * 0.9))
skin[ys, xs, 3] = 255
put("dark", vec(40, 52, 58))
ys, xs, h, w = area("dark")
glow[ys, xs, :3] = vec(255, 200, 120)
glow[ys, xs, 3] = 40  # a faint ember-light in the mouth and nostrils
put("tooth", vec(250, 246, 232), glow_rgb=vec(255, 250, 230), glow_a=40)

# Belly and jaw: gold plates with bright leading edges and dark seams.
for name, period in (("belly", 7.0), ("jaw", 5.0)):
    ys, xs, h, w = area(name)
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    ph = (yy % period) / period
    col = mix(GOLD_HI, GOLD, ph)
    col = mix(col, GOLD_LO, np.clip((ph - 0.82) / 0.18, 0, 1) * 0.8)
    col = mix(col, vec(250, 236, 196), 0.18 * np.sin(xx * 0.35) ** 2)
    skin[ys, xs, :3] = col
    skin[ys, xs, 3] = 255
    glow[ys, xs, :3] = GOLD_HI
    glow[ys, xs, 3] = 26 * (1 - ph)

# Crest, frills and elbow flames: luminous membranes with fine veins.
for name in ("crest", "frill"):
    ys, xs, h, w = area(name)
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    veins = 0.5 + 0.5 * np.cos(xx * 1.1 + np.sin(yy * 0.3) * 1.5)
    if name == "frill":  # the cheek fins repeat the wings' dawn, violet at the root to gold light at the tip
        along = np.clip(yy / (5.6 * HEAD_SCALE * S), 0, 1)
        col = dawn(0.5 + 0.5 * along + 0.04 * (veins - 0.5))
        glow_a = 40 + 200 * along
    else:  # the dorsal fins: rose-gold light
        col = mix(mix(ROSE, LIGHT, 0.55), LIGHT_HI, veins * 0.7)
        glow_a = 150 + 90 * veins
    skin[ys, xs, :3] = col
    skin[ys, xs, 3] = 255
    glow[ys, xs, :3] = col
    glow[ys, xs, 3] = glow_a

# Mane: streaming ribbons of light.
ys, xs, h, w = area("mane")
yy, xx = np.mgrid[0:h, 0:w].astype(float)
streak = 0.5 + 0.5 * np.sin(xx * 1.7 + rng.normal(0, 0.4, (h, w)).cumsum(axis=0) * 0.05)
col = mix(mix(ROSE, VIOLET, 0.25), LIGHT_HI, 0.25 + 0.75 * streak)
skin[ys, xs, :3] = col
skin[ys, xs, 3] = 255
glow[ys, xs, :3] = mix(ROSE, LIGHT_HI, streak)
glow[ys, xs, 3] = 150 + 90 * streak

# Antlers: ivory beams ringed in gold, warming upward; the tines are pure light.
ys, xs, h, w = area("antler")
yy, xx = np.mgrid[0:h, 0:w].astype(float)
ring = 0.5 + 0.5 * np.cos(yy * 0.9)
col = mix(IVORY, GOLD_HI, 0.25 + 0.4 * ring)
col = mix(col, WARM_RIM, 0.25 * (np.sin(xx * 2.1) > 0.85))
skin[ys, xs, :3] = col
skin[ys, xs, 3] = 255
glow[ys, xs, :3] = GOLD_HI
glow[ys, xs, 3] = 40 + 50 * ring
ys, xs, h, w = area("tine")
yy, xx = np.mgrid[0:h, 0:w].astype(float)
col = mix(LIGHT, LIGHT_HI, 0.5 + 0.5 * np.cos(yy * 0.6))
skin[ys, xs, :3] = col
skin[ys, xs, 3] = 255
glow[ys, xs, :3] = LIGHT_HI
glow[ys, xs, 3] = 235

# Wings: membranes of light, pale gold at the root, brightening outward, with fine veins running the length of each
# band; they glow strongly, so at night the wings are the brightest thing in the room after the pearl.
# Dawn (owner, 2026-11-17: other colours than white and gold are welcome). One picture runs across the whole wing:
# deep twilight blue at the wrist and arm, through violet and rose, to gold and then pale light at the trailing edge,
# with faint streaks along the fingers and a few stars in the blue. Only the bright edge glows, so in the dark the
# wings read as a rim of sunrise around him and his pale body stands out against them.
def wing_reach(a, b):
    """How far each point lies from the wrist toward the wing's edge: 0 at the wrist, 1 on the outline."""
    wa, wb = WRIST
    da, db = a - wa, b - wb
    best = np.full(a.shape, np.inf)
    for (a0, b0), (a1, b1) in zip(OUTLINE, OUTLINE[1:] + OUTLINE[:1]):
        ea, eb = a1 - a0, b1 - b0
        den = da * eb - db * ea
        den = np.where(np.abs(den) < 1e-9, 1e-9, den)
        t = ((a0 - wa) * eb - (b0 - wb) * ea) / den  # along the ray from the wrist
        u = ((a0 - wa) * db - (b0 - wb) * da) / den  # along the edge
        hit = (t > 0.97) & (u >= -1e-6) & (u <= 1 + 1e-6)
        best = np.where(hit & (t < best), t, best)
    return np.clip(1 / np.maximum(best, 1.0), 0, 1)


for reg, (a0, a1, b0) in WING_BANDS.items():
    ys, xs, h, w = area(reg)
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    span = a1 - a0
    uu = xx / S - WING_T
    # The front face runs outward along the strip, the back face (after it) runs back inward.
    a = np.where(uu <= span + WING_T / 2, a0 + uu, a1 - (uu - span - WING_T))
    a = np.clip(a, a0, a1)
    b = b0 + np.clip(yy / S - WING_T, 0, 1.5)
    reach = wing_reach(a, b)
    ang = np.arctan2(b - WRIST[1], a - WRIST[0])
    streak = 0.5 + 0.5 * np.cos(ang * 46)
    col = dawn(reach + 0.035 * (streak - 0.5))
    col = mix(col, LIGHT_HI, 0.10 * streak * smooth_arr(0.5, 0.95, reach))
    star = (wing_rng.random((h, w)) < 0.012) & (reach < 0.55)
    col = np.where(star[..., None], vec(236, 240, 255), col)
    skin[ys, xs, :3] = col
    skin[ys, xs, 3] = 255
    edge = smooth_arr(0.62, 1.0, reach)
    glow[ys, xs, :3] = np.where(star[..., None], vec(210, 225, 255), dawn(reach))
    glow[ys, xs, 3] = np.where(star, 255, 14 + 226 * edge)

# Horns and wing bones: ivory, ringed in gold every few texels, a warm rim down one side.
ys, xs, h, w = area("horn")
yy, xx = np.mgrid[0:h, 0:w].astype(float)
ring = np.clip(np.cos(yy * 0.55) * 2 - 1.2, 0, 1)
col = mix(mix(IVORY, PEARL_HI, 0.4 + 0.3 * np.sin(xx * 0.7)), GOLD, ring * 0.85)
col = mix(col, WARM_RIM, 0.2 * (np.sin(xx * 1.9) > 0.8))
skin[ys, xs, :3] = col
skin[ys, xs, 3] = 255
glow[ys, xs, :3] = GOLD_HI
glow[ys, xs, 3] = 30 + 70 * ring

# Eyes: a molten-gold iris ringed in teal light around a slit pupil, on the front face of each eye box.
ys, xs, h, w = area("eye")
skin[ys, xs, :3] = vec(196, 160, 82)
skin[ys, xs, 3] = 255
glow[ys, xs, :3] = vec(255, 214, 120)
glow[ys, xs, 3] = 120
u0, v0, _, _ = REG["eye"]
ed = EYE_D * HEAD_SCALE  # the eye box depth: its front face starts this far into the swatch
fu0, fv0, fw, fh = (u0 + ed) * S, (v0 + ed) * S, EYE_W * HEAD_SCALE * S, EYE_H * HEAD_SCALE * S
yy, xx = np.mgrid[0:H, 0:W].astype(float)
cx, cy = fu0 + fw / 2, fv0 + fh / 2
ex, ey = (xx - cx) / (fw / 2), (yy - cy) / (fh / 2)
rr = np.sqrt(ex * ex + ey * ey)
face = (xx >= fu0) & (xx < fu0 + fw) & (yy >= fv0) & (yy < fv0 + fh)
iris = mix(vec(255, 246, 196), vec(255, 186, 70), np.clip(np.abs(ex), 0, 1))
iris = mix(iris, vec(190, 96, 60), np.clip((np.abs(ex) - 0.8) / 0.2, 0, 1))
slit = np.abs(xx - cx) < 0.9
iris = np.where(slit[..., None], vec(28, 30, 34), iris)
for img, colr, a in ((skin, iris, 255), (glow, np.where(slit[..., None], vec(0, 0, 0), iris), None)):
    img[face, :3] = colr[face]
    img[face, 3] = 255 if a else np.where(slit[face], 30, 255)

# Talons: dark gold roots to pale tips.
ys, xs, h, w = area("claw")
yy, xx = np.mgrid[0:h, 0:w].astype(float)
skin[ys, xs, :3] = mix(vec(126, 92, 42), vec(246, 230, 186), yy / h)
skin[ys, xs, 3] = 255

# The pearl: white light with gold swirls; the halo: a thread of light.
ys, xs, h, w = area("pearl")
yy, xx = np.mgrid[0:h, 0:w].astype(float)
swirl = 0.5 + 0.5 * np.sin(xx * 0.55 + yy * 0.35 + np.sin(yy * 0.4) * 2)
skin[ys, xs, :3] = mix(LIGHT_HI, vec(255, 214, 120), swirl * 0.45)
skin[ys, xs, 3] = 255
glow[ys, xs, :3] = mix(LIGHT_HI, vec(255, 220, 140), swirl * 0.4)
glow[ys, xs, 3] = 255
put("halo", LIGHT_HI, glow_rgb=LIGHT_HI, glow_a=255)
ys, xs, h, w = area("whisker")
yy, xx = np.mgrid[0:h, 0:w].astype(float)
skin[ys, xs, :3] = mix(GOLD, GOLD_HI, 0.5 + 0.5 * np.sin(yy * 0.8))
skin[ys, xs, 3] = 255
glow[ys, xs, :3] = GOLD_HI
glow[ys, xs, 3] = 170

# ------------------------------------------------------------------------------------------------ output

quads = world_quads()
allpts = np.concatenate([q for q, _ in quads])
lo, hi = allpts.min(axis=0), allpts.max(axis=0)
# The lowest point rests on the entity's feet: model y 24 is the feet, so the root sits at 24 - lowest.
# Then the whole Dragon is scaled to his height in the world: 3.45 blocks, so from half a block over the chest's lid
# he clears a five-high room (owner, 2026-11-16: at most 3 wide and 4 tall, fixed size).
TARGET_H = 3.45 * 16
sc = TARGET_H / (hi[1] - lo[1])
root_y = 24 - sc * hi[1]
blo, bhi = lo * sc, hi * sc
blo[1], bhi[1] = (lo[1] - hi[1]) * sc, 0.0
for q in ORDER:
    for r, x, y, z, w, h, d in q.boxes:
        _, _, rw, rh = REG[r]
        assert 2 * d + 2 * w <= rw + 1e-6 and d + h <= rh + 1e-6, ("swatch too small", q.name, r, w, h, d)

lines = [
    "# Generated by tools/generate_dragon.py; do not edit. One line per part (world poses converted to parent-relative),",
    "# then its boxes; motion lines add amplitude * sin(age * freq + phase) to a part's pose (spin adds age * spin).",
    f"root {root_y:.4f} {sc:.5f}",
    f"bounds {blo[0]:.3f} {blo[1]:.3f} {blo[2]:.3f} {bhi[0]:.3f} {bhi[1]:.3f} {bhi[2]:.3f}",
]
for p in ORDER[1:]:
    parent = PARTS[p.parent]
    off = parent.m.T @ (p.pos - parent.pos)
    xr, yr, zr = euler(parent.m.T @ p.m)
    lines.append(f"part {p.name} {p.parent} {off[0]:.4f} {off[1]:.4f} {off[2]:.4f} {xr:.5f} {yr:.5f} {zr:.5f}")
    for region, x, y, z, w, h, d in p.boxes:
        u, v, _, _ = REG[region]
        lines.append(f"box {u} {v} {x:.4f} {y:.4f} {z:.4f} {w:.4f} {h:.4f} {d:.4f}")
    for m in p.motion:
        lines.append("motion " + p.name + " " + " ".join(f"{x:.5f}" for x in m))
pw = (pearl_at[0] * sc / 16, -(pearl_at[1] - hi[1]) * sc / 16, -pearl_at[2] * sc / 16)
lines.append(f"pearl {pw[0]:.3f} {pw[1]:.3f} {pw[2]:.3f}")  # blocks from his feet, facing south
SHAPE.parent.mkdir(parents=True, exist_ok=True)
SHAPE.write_text("\n".join(lines) + "\n", encoding="utf-8")
Image.fromarray(np.clip(skin, 0, 255).astype(np.uint8), "RGBA").save(TEX / "light_dragon.png")
Image.fromarray(np.clip(glow, 0, 255).astype(np.uint8), "RGBA").save(TEX / "light_dragon_glow.png")

boxes = sum(len(p.boxes) for p in ORDER)
print(f"{len(ORDER)} parts, {boxes} boxes, {N} spine segments")
print(f"design px: x {lo[0]:.1f}..{hi[0]:.1f}  y {lo[1]:.1f}..{hi[1]:.1f}  z {lo[2]:.1f}..{hi[2]:.1f}  scale {sc:.3f}")
print(f"blocks: width {(bhi[0] - blo[0]) / 16:.2f}  height {-blo[1] / 16:.2f}  depth {(bhi[2] - blo[2]) / 16:.2f}")
print(f"x {blo[0] / 16:.2f}..{bhi[0] / 16:.2f}  front reach (-z) {-blo[2] / 16:.2f}  back reach (+z) {bhi[2] / 16:.2f}")
print(f"LightDragon PEARL_X = {pw[0]:.2f}, PEARL_Y = {pw[1]:.2f}, PEARL_Z = {pw[2]:.2f}")

# ------------------------------------------------------------------------------------------------ preview

if len(sys.argv) > 1:
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    mean = {k: skin[area(k)[0], area(k)[1], :3].reshape(-1, 3).mean(axis=0) for k in REG}
    emissive = {k: glow[area(k)[0], area(k)[1], 3].mean() / 255 for k in REG}
    light = unit(vec(-0.4, -0.8, -0.6))

    def render(name, view, size=900):
        pts = []
        for q, region in quads:
            pq = (view @ q.T).T
            n = np.cross(q[1] - q[0], q[3] - q[0])
            if np.linalg.norm(n) < 1e-9:
                continue
            n = unit(n)
            vn = view @ n
            if vn[2] > 0:  # facing away from a viewer looking along +z
                n, vn = -n, -vn
            shade = 0.45 + 0.55 * max(0, -np.dot(n, light))
            e = emissive[region]
            col = mean[region] * (shade * (1 - e) + e)
            pts.append((pq[:, 2].mean(), pq[:, :2], col))
        pts.sort(key=lambda t: -t[0])
        span = 90
        img = Image.new("RGB", (size, size), (14, 16, 26))
        dr = ImageDraw.Draw(img)
        k = size / (span * 1.05)
        for _, p2, col in pts:
            poly = [(size / 2 + x * k, size / 2 + (y - (lo[1] + hi[1]) / 2) * k) for x, y in p2]
            dr.polygon(poly, fill=tuple(int(c) for c in np.clip(col, 0, 255)))
        img.save(out / f"dragon_{name}.png")

    front = np.diag([1, 1, 1.0])  # viewer at -z looking +z: x right, y down
    side = rot(0, -math.pi / 2, 0).T
    top = rot(math.pi / 2, 0, 0).T
    threeq = (rot(0, -0.6, 0) @ rot(-0.25, 0, 0)).T
    hut = (rot(0, -0.45, 0) @ rot(0.4, 0, 0)).T  # roughly where a player stands: in front, to one side, below
    for n, v in (("front", front), ("side", side), ("top", top), ("threeq", threeq), ("hut", hut)):
        render(n, v)
    print("previews in", out)
