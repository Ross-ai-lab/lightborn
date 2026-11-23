#version 330
#extension GL_ARB_separate_shader_objects : require

// The Light ward's dome (WardAuraRenderer). The mesh is a plain sphere; every effect is worked out per pixel in
// ward.fsh. Vertex data: UV0 = lattice coordinates (tiles round the dome, tiles crown to foot), UV1 = (tiles round,
// mesh rows), UV2 = (mesh column, mesh row), Normal = the sphere's outward normal, Position = camera-relative.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

// Both passes (core lines and soft light) must land on exactly the same depth, so the soft pass's equal-or-nearer
// test settles every tie the same way, every frame.
invariant gl_Position;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 lattice;
layout(location = 3) out float polar;
layout(location = 4) out float rim;
layout(location = 5) flat out float tilesRound;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    lattice = UV0;
    polar = float(UV2.y) / float(max(UV1.y, 1));
    // Rim light: the wall brightens where the eye grazes it, so from inside it reads as a close, solid shelter.
    float facing = abs(dot(normalize(Normal), normalize(-Position)));
    rim = pow(1.0 - facing, 1.5);
    tilesRound = float(UV1.x);
}
