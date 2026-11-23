"""Draws the Lightborn icon: a pixel-art home with lit windows, safe under the ward's bright gold dome on a dark night
sky (owner, 2026-11-18: a home inside a ward; darker sky, brighter yellow-gold ward).

Writes tools/lightborn-<hash>.ico (desktop shortcut, every Windows size), removes older ones, points the Desktop's
Lightborn.lnk at it, and writes the mod's icon.png (mod list). The hash is in the name because Windows caches a
shortcut's icon by file path: new pixels under an old name keep showing the old picture. Run from anywhere:
    python tools/make_icon.py
"""
import hashlib
import io
import math
import subprocess
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
# The home, 16 x 16 pixel art: roof and chimney, plank walls, two lit windows, a door, a strip of grass.
HOME = [
    "................",
    "...........cc...",
    ".......rr..cc...",
    "......rRRr.cc...",
    ".....rRRRRrcc...",
    "....rRRRRRRr....",
    "...rRRRRRRRRr...",
    "..rRRRRRRRRRRr..",
    ".rrrrrrrrrrrrrr.",
    "..WwWwWwWwWwWW..",
    "..WyyWwyyWwddW..",
    "..WyyWwyyWwddW..",
    "..WwWwWwWwWddW..",
    "..WwWwWwWwWddW..",
    ".GGGGGGGGGGGGGG.",
    "..gggggggggggg..",
]
COLOURS = {
    "r": (104, 36, 30), "R": (168, 60, 42), "c": (92, 84, 84), "W": (150, 98, 54), "w": (128, 82, 44),
    "y": (255, 222, 120), "d": (86, 52, 28), "G": (84, 156, 66), "g": (58, 104, 46),
}


def home_sprite():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(HOME):
        for x, ch in enumerate(row):
            if ch in COLOURS:
                img.putpixel((x, y), COLOURS[ch] + (255,))
    return img
S = 1024  # drawn large, scaled down per size


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def tile():
    # Night sky: deep indigo at the top to violet-black at the foot.
    sky = Image.new("RGBA", (S, S))
    px = sky.load()
    top, mid, foot = (4, 5, 18), (13, 9, 32), (2, 2, 8)
    for y in range(S):
        t = y / (S - 1)
        c = lerp(top, mid, t / .55) if t < .55 else lerp(mid, foot, (t - .55) / .45)
        for x in range(S):
            px[x, y] = c + (255,)

    # A few stars, fixed so every build draws the same sky.
    stars = ImageDraw.Draw(sky)
    for i in range(46):
        x = (i * 7919 % 997) / 997 * S
        y = (i * 104729 % 991) / 991 * S * .62
        r = 2 + (i % 3)
        stars.ellipse((x - r, y - r, x + r, y + r), fill=(255, 240, 210, 70 + (i * 37) % 110))

    # The ward: a bright yellow-gold dome of meridians and parallels, centred low, glowing.
    dome = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(dome)
    cx, base, rad = S / 2, S * .86, S * .44
    gold = (255, 216, 60)
    for k in range(1, 6):  # parallels
        h = rad * k / 6
        w = math.sqrt(max(rad * rad - h * h, 0))
        d.arc((cx - w, base - h - w * .18, cx + w, base - h + w * .18), 180, 360, fill=gold + (170,), width=6)
    for k in range(-4, 5):  # meridians
        w = rad * abs(k) / 4.4
        d.arc((cx - w, base - rad, cx + w, base + rad), 180, 360, fill=gold + (150,), width=6)
    d.arc((cx - rad, base - rad, cx + rad, base + rad), 180, 360, fill=(255, 236, 120, 255), width=12)
    sky = Image.alpha_composite(sky, dome.filter(ImageFilter.GaussianBlur(28)))
    sky = Image.alpha_composite(sky, dome.filter(ImageFilter.GaussianBlur(9)))
    sky = Image.alpha_composite(sky, dome)

    # Warm light inside the ward, brightest round the home.
    bloom = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    b = ImageDraw.Draw(bloom)
    for r in range(int(S * .5), 0, -6):
        t = r / (S * .5)
        b.ellipse((S / 2 - r, S * .62 - r, S / 2 + r, S * .62 + r), fill=(255, 214, 80, int(130 * (1 - t) ** 2.2)))
    sky = Image.alpha_composite(sky, bloom.filter(ImageFilter.GaussianBlur(24)))

    # The home, crisp pixel art standing on the dome's floor, with a soft golden halo of its own shape.
    size = int(S * .44)
    big = home_sprite().resize((size, size), Image.NEAREST)
    at = ((S - size) // 2, int(S * .86) - size * 15 // 16)
    halo = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    halo.paste(big, at, big)
    halo = halo.filter(ImageFilter.GaussianBlur(26))
    sky = Image.alpha_composite(sky, ImageChops.multiply(halo, Image.new("RGBA", (S, S), (255, 220, 140, 200))))
    sky.alpha_composite(big, at)

    # Rounded tile with a thin gold rim.
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), radius=int(S * .2), fill=255)
    rim = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(rim).rounded_rectangle((8, 8, S - 9, S - 9), radius=int(S * .2) - 8, outline=(232, 182, 84, 255), width=14)
    sky = Image.alpha_composite(sky, rim)
    sky.putalpha(ImageChops.multiply(sky.getchannel("A"), mask))
    return sky


def main():
    art = tile()
    sizes = [256, 128, 64, 48, 32, 24, 16]
    frames = [art.resize((n, n), Image.LANCZOS) for n in sizes]
    buf = io.BytesIO()
    frames[0].save(buf, format="ICO", sizes=[(n, n) for n in sizes], append_images=frames[1:])
    ico = ROOT / f"tools/lightborn-{hashlib.sha256(buf.getvalue()).hexdigest()[:8]}.ico"
    for old in (ROOT / "tools").glob("lightborn*.ico"):
        if old != ico:
            old.unlink()
    ico.write_bytes(buf.getvalue())
    art.resize((128, 128), Image.LANCZOS).save(ROOT / "src/main/resources/assets/lightborn/icon.png")
    art.resize((512, 512), Image.LANCZOS).save(ROOT / ".preview/lightborn-icon-512.png")
    link = Path.home() / "Desktop/Lightborn.lnk"
    if link.exists():
        ps = (f"$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{link}');"
              f"$s.IconLocation='{ico},0';$s.Save();ie4uinit.exe -show")
        subprocess.run(["powershell", "-NoProfile", "-Command", ps], check=True)
    print("ok", ico.name)


if __name__ == "__main__":
    main()
