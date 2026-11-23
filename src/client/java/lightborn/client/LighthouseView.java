package lightborn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lightborn.Lighthouse;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * The lighthouse: a gold pillar on the horizon over the player's ward, far from home, day and night (owner,
 * 2026-11-16; 2026-11-23: at all hours). By day it burns brighter, so it still reads against a pale sky. Home is usually beyond the render distance, so the pillar stands along the true bearing at a distance
 * the world still draws, slim enough to read as far off, with light drifting slowly up it.
 */
public final class LighthouseView {
    private LighthouseView() {}

    private static final Identifier BEAM = Identifier.withDefaultNamespace("textures/entity/beacon/beacon_beam.png");

    static void submit(LevelRenderContext ctx) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        var home = mc.player.getAttached(Lighthouse.HOME);
        if (home == null) return;
        var cam = ctx.levelState().cameraRenderState.pos;
        double dx = home.getX() + .5 - cam.x, dz = home.getZ() + .5 - cam.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1) return;
        // Fades in over the first stretch past the threshold, so it never pops.
        float fade = (float) Math.clamp((dist - Lighthouse.FROM) / 64.0, 0, 1);
        if (fade <= 0) return;
        double reach = mc.options.getEffectiveRenderDistance() * 16 * .7;
        double shown = Math.min(dist, reach);
        float ux = (float) (dx / dist), uz = (float) (dz / dist);
        float x = ux * (float) shown, z = uz * (float) shown;
        // The same angular width whatever the distance: about two thirds of a degree.
        float half = (float) shown * .006f;
        float sx = -uz * half, sz = ux * half;
        float bottom = (float) (home.getY() - cam.y) - 8, top = bottom + 320;
        float time = (mc.player.tickCount + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20f;
        float scroll = (time * .04f) % 1f;
        float glow = lightborn.SurfaceLights.night(mc.level) ? 1 : 1.6f;
        PoseStack pose = ctx.poseStack();
        ctx.submitNodeCollector()
                .submitCustomGeometry(
                        pose,
                        WardAuraRenderer.swirl(BEAM, 0, -scroll),
                        (p, out) -> pillar(p, out, x, z, sx, sz, bottom, top, .55f * glow * fade));
        ctx.submitNodeCollector()
                .submitCustomGeometry(
                        pose,
                        WardAuraRenderer.swirl(BEAM, .5f, -scroll * .6f),
                        (p, out) -> pillar(p, out, x, z, sx * 3, sz * 3, bottom, top, .18f * glow * fade));
    }

    /** A camera-facing strip, gold at the foot and gone at the top, in a few bands so the fade is smooth. */
    private static void pillar(
            PoseStack.Pose p,
            VertexConsumer out,
            float x,
            float z,
            float sx,
            float sz,
            float bottom,
            float top,
            float strength) {
        int bands = 6;
        for (int i = 0; i < bands; i++) {
            float f0 = i / (float) bands, f1 = (i + 1) / (float) bands;
            float y0 = bottom + (top - bottom) * f0, y1 = bottom + (top - bottom) * f1;
            float b0 = strength * (1 - f0) * (1 - f0), b1 = strength * (1 - f1) * (1 - f1);
            vertex(p, out, x - sx, y0, z - sz, 0, f0 * 8, b0);
            vertex(p, out, x + sx, y0, z + sz, 1, f0 * 8, b0);
            vertex(p, out, x + sx, y1, z + sz, 1, f1 * 8, b1);
            vertex(p, out, x - sx, y1, z - sz, 0, f1 * 8, b1);
        }
    }

    private static void vertex(
            PoseStack.Pose p, VertexConsumer out, float x, float y, float z, float u, float v, float b) {
        out.addVertex(p, x, y, z)
                .setColor(Math.round(255 * b), Math.round(216 * b), Math.round(120 * b), 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(15728880)
                .setNormal(p, 0, 1, 0);
    }
}
