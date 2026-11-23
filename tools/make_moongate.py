"""Moongate's portal (owner, 2026-11-22: "make the portal look like Rick and Morty's portal, and you can't see through
it"): an opaque swirling disc of green light with a bright, wavering rim, as a strip of frames that PortalView
steps through on an upright quad in each gate, at full brightness.

Writes textures/entity/moongate_portal.png (FRAMES frames of SIZE pixels, stacked; PortalView's FRAMES must match).
Run: py -3 -I tools/make_moongate.py
"""
import math
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/lightborn"
SIZE, FRAMES = 64, 24

DEEP = (18, 96, 30)
MID = (62, 190, 52)
LIGHT = (156, 246, 84)
RIM = (205, 255, 112)
EDGE = (240, 255, 186)
HEART = (226, 255, 190)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def pixel(x, y, phase):
    c = (SIZE - 1) / 2
    dx, dy = (x - c) / c, (y - c) / c
    r = math.hypot(dx, dy)
    th = math.atan2(dy, dx)
    # The silhouette wavers: its edge breathes in and out round the rim as the frames turn.
    outer = .965 + .035 * math.sin(9 * th - phase * 2) + .015 * math.sin(4 * th + phase * 3)
    if r > outer:
        return None
    rim = outer - .13 - .03 * math.sin(7 * th + phase * 2)
    if r > rim:
        t = (r - rim) / (outer - rim)
        return mix(RIM, EDGE, abs(t - .5) * -2 + 1)
    # Three arms spiralling into the heart, turning with the frames.
    arm = math.sin(3 * (th + 2.4 * math.log(r + .06)) - phase)
    swirl = mix(DEEP, MID, .5 + .5 * arm)
    swirl = mix(swirl, LIGHT, max(0.0, arm) ** 3 * .8)
    swirl = mix(swirl, LIGHT, max(0.0, (r - rim + .18) / .18) * .7)  # brighter toward the rim
    return mix(swirl, HEART, max(0.0, 1 - r / .2) ** 1.5)  # a glowing heart


sheet = Image.new("RGBA", (SIZE, SIZE * FRAMES), (0, 0, 0, 0))
for f in range(FRAMES):
    phase = f * 2 * math.pi / FRAMES
    for y in range(SIZE):
        for x in range(SIZE):
            c = pixel(x, y, phase)
            if c:
                sheet.putpixel((x, f * SIZE + y), c + (255,))
sheet.save(ASSETS / "textures/entity/moongate_portal.png")
print("moongate portal written")
