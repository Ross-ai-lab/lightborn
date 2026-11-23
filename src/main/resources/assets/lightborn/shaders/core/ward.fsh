#version 330
#extension GL_ARB_separate_shader_objects : require

// The Light ward's dome, every layer worked out per pixel (owner, 2026-11-18: no flicker, fewer frames spent, the
// same whole-sphere light). Two passes share this file:
//   WARD_CORE  the crisp deep-gold lines, opaque and writing depth, so a cloud behind cannot wash the gold out;
//   (default)  the soft light, premultiplied: glow halo and faint sheet (alpha), comets, veils, twinkling crossings.
//
// Why it no longer flickers: every line, comet and star is filtered to the screen pixel it lands in. Their widths
// are widened by the pixel's footprint (fwidth) and their brightness lowered to match, so nothing ever becomes thinner
// than a pixel and breaks into crawling dots as the camera moves; far enough away the lattice settles into its even
// average glow. The textures this replaced had no smaller copies for distance, so their 1 to 2 px lines aliased.
//
// The lattice: two families of diagonal lines in lattice space, u - v = k / 16 ("\") and u + v = k / 16 ("/"), sizes
// in the old 512 px tile's pixels so the look matches the textures it replaces.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>

uniform sampler2D Sampler0; // ward_veil.png: soft warm noise whose RGB is its light

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 lattice;
layout(location = 3) in float polar;
layout(location = 4) in float rim;
layout(location = 5) flat in float tilesRound;

layout(location = 0) out vec4 fragColor;

const float LINES = 16.0;             // lines of each family per tile
const float GAP = 22.627417;          // px between neighbouring lines, square to them (32 / sqrt 2)
const float ALONG = 362.03867;        // px along a line per unit of u + v (512 / sqrt 2)
const float LOOP = 1200.0;            // GameTime repeats every 20 minutes: every rhythm fits it a whole number of times
const float TAU = 6.2831853;
const float CORE_HALF = 1.05;         // the core line's half width
const float CORE_FAR = 5.0;           // px per screen pixel past which the core lines hand over to the soft pass

const vec3 DEEP_GOLD = vec3(0.925, 0.675, 0.275);
const vec3 GOLD = vec3(1.0, 0.831, 0.396);
const vec3 WHITE_GOLD = vec3(1.0, 0.965, 0.839);

float hash(vec2 p) {
    p = fract(p * vec2(0.1031, 0.1030));
    p += dot(p, p.yx + 33.33);
    return fract((p.x + p.y) * p.x);
}

// A line's soft profile exp(-(d / s)^2) seen through a pixel f px wide: wider and dimmer to match, with the next line
// over counted too, and the plain average once lines crowd under a pixel.
float band(float d, float s, float f) {
    float s2 = s * s + 0.5 * f * f;
    float a = s * inversesqrt(s2);
    float near = a * (exp(-d * d / s2) + exp(-(GAP - d) * (GAP - d) / s2));
    return mix(near, s * 1.7724539 / GAP, smoothstep(0.35 * GAP, 0.9 * GAP, f));
}

// How much of a pixel f px wide, centred d px from a line, the core line (half width CORE_HALF) covers.
float coverage(float d, float f) {
    float lo = max(d - 0.5 * f, -CORE_HALF), hi = min(d + 0.5 * f, CORE_HALF);
    return clamp((hi - lo) / f, 0.0, 1.0);
}

// Comets running up one line: two heads per line, each on its own phase, length and strength, heads toward the crown.
// along: position down the line in units of (u + v) or (v - u), two units per tile.
vec2 comets(float along, float line, float d, float f, float t) {
    float across = band(d, 1.4, f) + 0.45 * band(d, 4.5, f);
    float value = 0.0, head = 0.0;
    for (int k = 0; k < 2; k++) {
        float seed = float(k) * 17.0;
        float len = mix(120.0, 230.0, hash(vec2(line, 13.0 + seed)));
        float power = mix(0.8, 1.0, hash(vec2(line, 29.0 + seed)));
        float phase = fract(0.5 * (along + 0.11 * t) + hash(vec2(line, 7.0 + seed)));
        float behind = phase * 2.0 * ALONG, ahead = (1.0 - phase) * 2.0 * ALONG;
        float tail = exp(-behind / len) - exp(-2.0 * ALONG / len) + exp(-ahead / 2.5);
        float glow = exp(-(behind / 6.0) * (behind / 6.0)) + exp(-(ahead / 6.0) * (ahead / 6.0));
        value = max(value, power * (0.85 * tail + 1.2 * glow) * across);
        head = max(head, power * glow * across);
    }
    return vec2(value, head);
}

void main() {
    // Derivatives first: they must be taken before any pixel is discarded.
    vec2 c = vec2(lattice.x - lattice.y, lattice.x + lattice.y) * LINES; // line index, "\" and "/"
    vec2 f = max(fwidth(c) * GAP, vec2(1e-3));                         // old-tile px under this screen pixel
    vec2 idx = floor(c + 0.5);
    vec2 sd = (c - idx) * GAP;                                          // signed px to the nearest line of each
    vec2 d = abs(sd);

#ifdef WARD_CORE
    bool onA = f.x < CORE_FAR && d.x < max(CORE_HALF, 0.75 * f.x);
    bool onB = f.y < CORE_FAR && d.y < max(CORE_HALF, 0.75 * f.y);
    if (!onA && !onB) discard;
    fragColor = apply_fog(vec4(DEEP_GOLD, 1.0), sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#else
    float t = GameTime * LOOP; // seconds, looping every 20 minutes

    // The whole dome breathes; every eight seconds a ring of light rises from the lowest point to the crown.
    float breath = 0.92 + 0.08 * sin(t * TAU * 172.0 / LOOP);
    float beat = fract(t / 8.0);
    float pulse = 0.0;
    if (beat < 0.4) {
        float ring = 1.0 - beat / 0.4, k = (polar - ring) / 0.045;
        pulse = sin(3.1415927 * beat / 0.4) * exp(-k * k);
    }
    float p = min(polar, 1.0 - polar);
    float poles = p >= 0.12 ? 1.0 : 0.35 + 0.65 * p / 0.12; // every line meets at the poles: soften them

    // 1. Glow: a tight bright line in a wide soft halo over a faint sheet (alpha), brighter where the eye grazes.
    float glowA = 0.0, warm = 0.0;
    for (int i = 0; i < 2; i++) {
        float di = d[i], fi = f[i];
        glowA = max(glowA, (9.0 + 170.0 * band(di, 1.2, fi) + 46.0 * band(di, 3.2, fi) + 18.0 * band(di, 8.0, fi)) / 255.0);
        warm = max(warm, band(di, 1.4, fi));
    }
    glowA = clamp(glowA * breath * (0.42 + 0.58 * rim + 0.5 * pulse), 0.0, 1.0);
    vec3 rgb = mix(DEEP_GOLD, GOLD, clamp(warm, 0.0, 1.0)) * glowA;

    // The core lines' own light once they are too fine to draw as lines (the core pass stops there).
    for (int i = 0; i < 2; i++)
        rgb += DEEP_GOLD * coverage(d[i], f[i]) * smoothstep(CORE_FAR - 1.0, CORE_FAR, f[i]);

    // 2. Comets up both line families. A line's index is taken round the dome, so one crossing the seam is one line.
    float lineCount = LINES * tilesRound;
    vec2 a = comets(lattice.x + lattice.y, mod(idx.x, lineCount), d.x, f.x, t);
    vec2 b = comets(lattice.y - lattice.x, mod(idx.y, lineCount), d.y, f.y, t);
    float flowShade = poles * breath * (0.95 + 0.3 * rim + 1.4 * pulse);
    rgb += mix(GOLD, WHITE_GOLD, clamp(a.y, 0.0, 1.0)) * clamp(a.x, 0.0, 1.0) * flowShade;
    rgb += mix(GOLD, WHITE_GOLD, clamp(b.y, 0.0, 1.0)) * clamp(b.x, 0.0, 1.0) * flowShade;

    // 3. Two veils of warm shimmer drifting against each other, each a whole number of tiles round the dome.
    float second = max(1.0, floor(tilesRound / 6.0 + 0.5)) / tilesRound;
    vec3 veil = texture(Sampler0, lattice / 4.0 + vec2(7.0, -5.0) / LOOP * t).rgb
            + texture(Sampler0, lattice * second + vec2(0.37, 0.61) + vec2(-5.0, -8.0) / LOOP * t).rgb;
    rgb += veil * poles * (0.55 + 0.35 * rim + 0.6 * pulse);

    // 4. A star at every crossing, each twinkling on its own rhythm.
    float r2 = dot(sd, sd);
    float dx = (sd.x + sd.y) * 0.70710678, dy = (sd.y - sd.x) * 0.70710678;
    float fm = max(f.x, f.y), s1 = 2.2 + 0.5 * fm * fm, s2 = 18.0 + 0.5 * fm * fm;
    float glint = exp(-dy * dy / (0.49 + 0.5 * fm * fm)) * exp(-abs(dx) / 3.2)
            + exp(-dx * dx / (0.49 + 0.5 * fm * fm)) * exp(-abs(dy) / 3.2);
    float star = 2.2 / s1 * exp(-r2 / s1) + 0.45 * 18.0 / s2 * exp(-r2 / s2) + 0.5 * glint * 0.7 / sqrt(0.49 + 0.5 * fm * fm);
    vec2 node = vec2(mod(idx.x + idx.y, 2.0 * lineCount), idx.y - idx.x);
    float seed = hash(node + 0.5);
    float rate = floor((1.1 + seed) * LOOP / TAU + 0.5) * TAU / LOOP;
    float wave = 0.5 + 0.5 * sin(t * rate + seed * TAU);
    rgb += WHITE_GOLD * clamp(star, 0.0, 1.0) * poles * (0.3 + 0.8 * wave * wave * wave + 1.2 * pulse);

    // Fog fades the light toward the fog colour, as much of it as the glow covers (premultiplied).
    float fog = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd) * FogColor.a;
    fragColor = vec4(mix(rgb, FogColor.rgb * glowA, fog), glowA);
#endif
}
