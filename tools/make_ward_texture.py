"""Textures for the Light ward (WardAuraRenderer, WardThreshold). Every dome tile is 512 px and seamless.

The dome's lattice, glow, comets and stars are drawn by the shader (shaders/core/ward.fsh), filtered per pixel so
they never flicker; only the soft veil is still a texture:

  ward_veil.png        band-limited warm noise, the slow shimmering light between the lines (drawn additively, so
                       its RGB is its brightness)
  ward_crack.png       a Cleanse scar strip, tinted at draw time

A screen overlay for crossing the ward (WardThreshold), in textures/gui:
  ward_membrane.png    golden, viscous light pressing in from the screen's edges while the ward holds you back
"""
from pathlib import Path

import numpy as np
from PIL import Image

N = 512
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/lightborn/textures/entity"


def save(rgba, name):
    Image.fromarray(np.clip(rgba, 0, 255).astype(np.uint8), "RGBA").save(OUT / name)


rng = np.random.default_rng(1847)

# Veil: seamless band-limited noise (filtered in frequency space, so it tiles by construction), soft warm light.
fx = np.fft.fftfreq(N)[None, :]
fy = np.fft.fftfreq(N)[:, None]
f = np.sqrt(fx * fx + fy * fy)
spectrum = np.exp(-((f - 0.012) / 0.010) ** 2) + 0.35 * np.exp(-((f - 0.035) / 0.015) ** 2)
noise = np.real(np.fft.ifft2(np.fft.fft2(rng.normal(size=(N, N))) * spectrum))
noise = (noise - noise.min()) / (noise.max() - noise.min())
veil = np.clip((noise - 0.35) / 0.65, 0, 1) ** 1.6
veil_img = np.zeros((N, N, 4))
veil_img[..., :3] = np.array([255, 196, 92]) * (0.55 * veil)[..., None]
veil_img[..., 3] = 255
save(veil_img, "ward_veil.png")
# Smooth (linear) filtering for the veil: nearest sampling turns its gradients into blocks up close.
for name in ["ward_veil"]:
    (OUT / (name + ".png.mcmeta")).write_text('{"texture": {"blur": true}}\n', encoding="utf-8")

# Screen overlays: 512 px, alpha carries the shape (drawn tinted with ordinary alpha blending).
GUI = OUT.parent / "gui"
GUI.mkdir(parents=True, exist_ok=True)
M = 512
gy, gx = np.mgrid[0:M, 0:M].astype(float)
u, v = (gx + 0.5) / M * 2 - 1, (gy + 0.5) / M * 2 - 1
ang = np.arctan2(v, u)
# A rounded-rectangle "radius" so the effect hugs every edge of a wide screen, not just the corners.
edge = np.maximum(np.abs(u), np.abs(v)) * 0.55 + np.sqrt(u * u + v * v) * 0.45


def lobes(seed, count, depth):
    g = np.random.default_rng(seed)
    w = np.zeros_like(ang)
    for k in range(1, count + 1):
        w += g.normal() / k * np.cos(k * ang + g.uniform(0, 2 * np.pi))
    return w / np.abs(w).max() * depth


def smooth(a, b, t):
    t = np.clip((t - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


# The membrane: thick gold syrup at the rim, long drips and folds reaching in, a bright meniscus where it ends.
def blobs(seed, freq, width):
    g = np.random.default_rng(seed)
    fu, fv = np.fft.fftfreq(M)[None, :], np.fft.fftfreq(M)[:, None]
    ff = np.sqrt(fu * fu + fv * fv)
    n = np.real(np.fft.ifft2(np.fft.fft2(g.normal(size=(M, M))) * np.exp(-((ff - freq) / width) ** 2)))
    return (n - n.min()) / (n.max() - n.min())


reach = 0.6 + lobes(5, 9, 0.07) + 0.16 * (blobs(6, 0.004, 0.004) - 0.5)
fold = blobs(8, 0.012, 0.008)
inner = smooth(reach, reach + 0.32, edge)
meniscus = np.exp(-((edge - reach - 0.03) / 0.025) ** 2) * (0.6 + 0.4 * fold)
alpha = np.clip(inner * (0.55 + 0.45 * fold) + 0.55 * meniscus, 0, 1)
membrane = np.zeros((M, M, 4))
warm = np.array([255, 196, 86], float)
hot = np.array([255, 244, 205], float)
mix = np.clip(meniscus * 1.4, 0, 1)[..., None]
membrane[..., :3] = warm * (1 - mix) + hot * mix
membrane[..., 3] = alpha * 255
save(membrane, "../gui/ward_membrane.png")

# A Cleanse scar: a white strip, opaque down its middle and fading to both edges (tinted at draw time), with a
# ragged inner edge so a crack reads as torn, not ruled.
CW, CH = 64, 16
v = (np.arange(CH) + 0.5) / CH
u = np.arange(CW)
rag = 0.5 + 0.08 * np.sin(u * 0.9) + 0.05 * np.sin(u * 2.3 + 1.0)
width = np.abs(v[:, None] - rag[None, :]) * 2
crack = np.zeros((CH, CW, 4))
crack[..., :3] = 255
crack[..., 3] = np.clip(1 - width, 0, 1) ** 1.6 * 255
save(crack, "ward_crack.png")
for name in ["ward_membrane"]:
    (GUI / (name + ".png.mcmeta")).write_text('{"texture": {"blur": true}}\n', encoding="utf-8")
print("ward textures written to", OUT)
