"""The Dark Nest's smoke pillar texture (NestSmokeView): textures/entity/nest_smoke.png.

128 x 512, seamless top to bottom (the renderer scrolls it upward), soft at both sides so the strip has no edge.
Billows come from band-limited noise at three scales, each stretched upward so the smoke reads as rising;
alpha is the density, RGB a near-black with a faint bruise of violet in the thin parts.
"""
from pathlib import Path

import numpy as np
from PIL import Image

W, H = 128, 512
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/lightborn/textures/entity/nest_smoke.png"


def band(rng, lo, hi, stretch=1.0):
    """Seamless noise keeping wavelengths between lo and hi pixels (vertical ones stretched)."""
    fy = np.fft.fftfreq(H)[:, None] * stretch
    fx = np.fft.fftfreq(W)[None, :]
    f = np.sqrt(fx * fx + fy * fy)
    keep = (f >= 1 / hi) & (f <= 1 / lo)
    spec = np.fft.fft2(rng.standard_normal((H, W))) * keep
    n = np.real(np.fft.ifft2(spec))
    return (n - n.mean()) / (n.std() + 1e-9)


def main():
    rng = np.random.default_rng(1957)
    n = .72 * band(rng, 36, 128, 2.4) + .28 * band(rng, 12, 36, 1.6)
    density = 1 / (1 + np.exp(-(n * 1.8 + 1.7)))  # dense smoke, thinner where the billows part
    x = (np.arange(W) + .5) / W * 2 - 1
    sides = np.clip(1 - np.abs(x) ** 2.2, 0, 1) ** 1.3  # thick in the middle, gone at the edges
    # The edges wander with the billows, so the column's outline is ragged rather than ruled.
    ragged = np.clip(sides[None, :] + .35 * band(rng, 24, 96, 2.0) * sides[None, :] * (1 - sides[None, :]) * 4, 0, 1)
    alpha = np.clip(density * ragged, 0, 1)
    thin = 1 - alpha
    r = 14 + 22 * thin * .5
    g = 11 + 12 * thin * .5
    b = 17 + 30 * thin * .5
    img = np.dstack([r * np.ones_like(alpha), g * np.ones_like(alpha), b * np.ones_like(alpha), alpha * 255])
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(OUT)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
