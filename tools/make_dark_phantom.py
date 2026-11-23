"""The Dark Phantom's look: the vanilla phantom redrawn black with a violet sheen, its glowing eyes turned milky and
dim (it is blind), and a spawn egg to match.

Reads the vanilla textures from the deobfuscated 26.3 client jar in the Gradle cache and writes:
  src/main/resources/assets/lightborn/textures/entity/dark_phantom.png
  src/main/resources/assets/lightborn/textures/entity/dark_phantom_eyes.png
  src/main/resources/assets/lightborn/textures/item/dark_phantom_spawn_egg.png

Usage: python tools/make_dark_phantom.py
"""
import io
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/lightborn/textures"
JAR = next((Path.home() / ".gradle/caches/fabric-loom/minecraftMaven/net/minecraft").glob(
    "minecraft-clientonly-deobf/26.3/*.jar"))

# Shadow to sheen, by the vanilla pixel's brightness: soot black up to a bruised violet.
DARK = [(8, 7, 12), (20, 16, 30), (36, 28, 52), (62, 48, 86), (96, 78, 128)]


def ramp(lum):
    t = min(.999, max(0.0, lum / 255)) * (len(DARK) - 1)
    i = int(t)
    f = t - i
    a, b = DARK[i], DARK[min(i + 1, len(DARK) - 1)]
    return tuple(round(a[k] + (b[k] - a[k]) * f) for k in range(3))


def darken(img):
    img = img.convert("RGBA")
    out = Image.new("RGBA", img.size)
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = img.getpixel((x, y))
            if a == 0:
                continue
            lum = .3 * r + .59 * g + .11 * b
            # The wing membranes (vanilla's pale see-through blue-grey) stay a little lighter and thinner.
            out.putpixel((x, y), ramp(lum * 1.1) + (a,))
    return out


def blind_eyes(img):
    """The eyes layer draws full-bright: vanilla's green becomes a dim milky white with a grey pupil-less film."""
    img = img.convert("RGBA")
    out = Image.new("RGBA", img.size)
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = img.getpixel((x, y))
            if a == 0:
                continue
            lum = max(r, g, b)
            v = 120 + round(lum / 255 * 70)
            out.putpixel((x, y), (v, v, min(255, v + 12), a))
    return out


def main():
    with zipfile.ZipFile(JAR) as z:
        body = Image.open(io.BytesIO(z.read("assets/minecraft/textures/entity/phantom/phantom.png")))
        eyes = Image.open(io.BytesIO(z.read("assets/minecraft/textures/entity/phantom/phantom_eyes.png")))
        egg = Image.open(io.BytesIO(z.read("assets/minecraft/textures/item/phantom_spawn_egg.png")))
    (ASSETS / "entity").mkdir(parents=True, exist_ok=True)
    (ASSETS / "item").mkdir(parents=True, exist_ok=True)
    darken(body).save(ASSETS / "entity/dark_phantom.png")
    blind_eyes(eyes).save(ASSETS / "entity/dark_phantom_eyes.png")
    # The egg: the phantom egg's base turned soot, its spots a milky white.
    egg = egg.convert("RGBA")
    out = Image.new("RGBA", egg.size)
    for y in range(egg.height):
        for x in range(egg.width):
            r, g, b, a = egg.getpixel((x, y))
            if a == 0:
                continue
            lum = .3 * r + .59 * g + .11 * b
            green = g > r + 25 and g > b  # vanilla's green spots
            out.putpixel((x, y), ((150 + int(lum / 4),) * 2 + (170 + int(lum / 4),) if green else ramp(lum)) + (a,))
    out.save(ASSETS / "item/dark_phantom_spawn_egg.png")
    print("wrote dark phantom textures")


if __name__ == "__main__":
    main()
