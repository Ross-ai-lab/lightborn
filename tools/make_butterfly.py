"""Draws the Light Butterfly's wing (owner, 2026-11-23: "add light butterflies").

One wing, 32 px, hinged on the left edge: a broad forewing over a smaller hindwing, warm white at the root running
to gold and a pale teal rim, gold veins and two bright eyespots, with a darker amber outline so it reads against
bright sky. The renderer draws it twice, mirrored, full-bright, so the left column (half the body) meets its twin.

Writes src/main/resources/assets/lightborn/textures/entity/light_butterfly_wing.png. LightButterflyRenderer draws it
as its own full-bright quads, not as an item model: those are shaded like lit objects and went dull at night.
"""

import math
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/lightborn"
N = 32


def inside(x, y):
    """Which part of the wing a pixel centre falls in: 'fore', 'hind', or None."""
    fx, fy = (x - 14.5) / 15.5, (y - 9.5) / 9.0
    # The forewing leans back toward its tip.
    tilt = .35 * (x / N)
    if fx * fx + (fy + tilt) ** 2 <= 1 and y <= 17:
        return "fore"
    hx, hy = (x - 10.5) / 10.5, (y - 22.0) / 8.0
    if hx * hx + hy * hy <= 1 and y >= 14:
        return "hind"
    return None


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


CORE = (255, 251, 232)
GOLD = (255, 207, 96)
RIM = (156, 236, 224)
VEIN = (226, 160, 52)
OUTLINE = (176, 112, 30)
BODY = (120, 78, 28)
BODY_LIGHT = (196, 140, 60)


def main():
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = img.load()
    for y in range(N):
        for x in range(N):
            part = inside(x + .5, y + .5)
            if part is None:
                continue
            # Distance out from the root, 0 at the hinge to 1 at the tip.
            d = math.hypot(x / N, (y - 15.5) / N * 1.2) / .9
            colour = mix(CORE, GOLD, d * 1.4) if d < .7 else mix(GOLD, RIM, (d - .7) / .3)
            # Veins fanning out from the root.
            angle = math.atan2(y - 15.5, x + 1)
            if part == "fore" and any(abs(angle - a) < .05 for a in (-1.05, -.65, -.3)) and x > 3:
                colour = VEIN
            if part == "hind" and any(abs(angle - a) < .06 for a in (.45, .9)) and x > 3:
                colour = VEIN
            px[x, y] = colour + (255,)
    # Eyespots: a bright ring with a white heart on the forewing, a small one on the hindwing.
    for cx, cy, r in ((20, 8, 2.6), (12, 22, 1.8)):
        for y in range(N):
            for x in range(N):
                if px[x, y][3] == 0:
                    continue
                d = math.hypot(x + .5 - cx, y + .5 - cy)
                if d <= r * .55:
                    px[x, y] = (255, 255, 255, 255)
                elif d <= r:
                    px[x, y] = RIM + (255,)
    # The outline: wing pixels that touch empty space (the hinge column stays open for the body).
    edge = []
    for y in range(N):
        for x in range(1, N):
            if px[x, y][3] == 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < N and 0 <= ny < N and px[nx, ny][3] == 0 and nx > 0:
                    edge.append((x, y))
                    break
    for x, y in edge:
        px[x, y] = OUTLINE + (255,)
    # Half the body down the hinge: the twin wing's column makes it whole.
    for y in range(8, 25):
        px[0, y] = (BODY_LIGHT if y % 3 == 0 else BODY) + (255,)
    for y in range(6, 8):
        px[0, y] = BODY + (255,)  # the head
    out = ASSETS / "textures/entity/light_butterfly_wing.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print("wrote", out)


if __name__ == "__main__":
    main()
