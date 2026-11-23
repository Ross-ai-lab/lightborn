package lightborn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lightborn.DarkLand;
import lightborn.DarkNests;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;

/**
 * A Dark Nest's smoke: a pillar of black smoke rising from its core, day and night, the dark answer to the home's
 * lighthouse (owner, 2026-11-17). The server sends each player the nests near them ({@link DarkNests#SMOKE}). A nest
 * inside the render distance gets its pillar where it stands; one beyond it stands on the true bearing at seven tenths
 * of the distance, at the same apparent width. The column widens and thins as it climbs and its billows roll upward.
 */
public final class NestSmokeView {
    private NestSmokeView() {}

    private static final RenderType SMOKE = RenderTypes.entityTranslucent(
            Identifier.fromNamespaceAndPath("lightborn", "textures/entity/nest_smoke.png"));

    /** Height of the column above the core, and its half-width at the foot and the crown, in blocks. */
    private static final float HEIGHT = 150, FOOT = 1.6f, CROWN = 9f;

    private static int lastPuff;

    static void submit(LevelRenderContext ctx) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        var nests = mc.player.getAttached(DarkNests.SMOKE);
        if (nests == null || nests.isEmpty()) return;
        var cam = ctx.levelState().cameraRenderState.pos;
        float time = (mc.player.tickCount + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20f;
        double reach = mc.options.getEffectiveRenderDistance() * 16 * .7;
        boolean puff = mc.player.tickCount != lastPuff && mc.player.tickCount % 4 == 0;
        if (puff) lastPuff = mc.player.tickCount;
        PoseStack pose = ctx.poseStack();
        for (BlockPos core : nests) {
            double dx = core.getX() + .5 - cam.x, dz = core.getZ() + .5 - cam.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 2) continue;
            double shown = Math.min(dist, reach);
            float scale = (float) (shown / dist); // far ones shrink with their stand-in distance: same angular size
            float ux = (float) (dx / dist), uz = (float) (dz / dist);
            float x = ux * (float) shown, z = uz * (float) shown;
            float bottom = (float) ((core.getY() + .4 - cam.y) * scale);
            float px = -uz, pz = ux; // across the line of sight
            float seed = (core.getX() * 7 + core.getZ() * 13) % 97 / 97f;
            float scroll = (time * .05f + seed) % 1f;
            float sway = (float) Math.sin(time * .21 + seed * 6.28) * 2.5f * scale;
            ctx.submitNodeCollector()
                    .submitCustomGeometry(
                            pose,
                            SMOKE,
                            (p, out) -> column(p, out, x, z, px, pz, bottom, scale, sway, seed, scroll, 1f));
            // Darkness overflows the shard (owner, 2026-11-17): black spills from its whole height and slides down
            // and out, ink wells over the pool at its foot, and now and then a violet mote rises off the crystal.
            if (dist < 48 && mc.player.tickCount % 3 == 0) {
                var rnd = mc.level.getRandom();
                var state = mc.level.getBlockState(core);
                int stage = state.is(DarkNests.NEST) ? state.getValue(DarkNests.STAGE) : 1;
                double height = new double[] {.9, 1.25, 1.6, 1.95}[stage - 1];
                double pool = new double[] {.45, .6, .8, 1}[stage - 1];
                for (int i = 0; i < 1 + stage / 2; i++) {
                    // Off the crystal's skin, not in front of it: the shard stays in view.
                    double a = rnd.nextDouble() * Math.PI * 2, up = rnd.nextDouble();
                    double rr = .3 + (1 - up) * .25 * stage;
                    mc.level.addParticle(
                            new net.minecraft.core.particles.DustParticleOptions(
                                    rnd.nextInt(4) == 0 ? 0x2A1240 : 0x07030C, .7f + rnd.nextFloat() * .8f),
                            true,
                            true,
                            core.getX() + .5 + Math.cos(a) * rr,
                            core.getY() + .1 + up * height,
                            core.getZ() + .5 + Math.sin(a) * rr,
                            Math.cos(a) * .05,
                            -.05,
                            Math.sin(a) * .05);
                }
                double a = rnd.nextDouble() * Math.PI * 2, rr = rnd.nextDouble() * pool;
                mc.level.addParticle(
                        ParticleTypes.SQUID_INK,
                        true,
                        true,
                        core.getX() + .5 + Math.cos(a) * rr,
                        core.getY() + .1,
                        core.getZ() + .5 + Math.sin(a) * rr,
                        Math.cos(a) * .03,
                        .005,
                        Math.sin(a) * .03);
                if (rnd.nextInt(6) == 0)
                    mc.level.addParticle(
                            ParticleTypes.REVERSE_PORTAL,
                            true,
                            true,
                            core.getX() + .5 + (rnd.nextDouble() - .5) * .6,
                            core.getY() + .4 + rnd.nextDouble() * height,
                            core.getZ() + .5 + (rnd.nextDouble() - .5) * .6,
                            0,
                            .02,
                            0);
            }
            // Heavy smoke lies over the whole dark land round the nest (owner, 2026-11-17: smoky, then heavier):
            // soft campfire smoke kept low and slow, thickest near the core, out to the dark's edge.
            if (puff && dist < 80) {
                var rnd = mc.level.getRandom();
                var here = mc.level.getBlockState(core);
                int st = here.is(DarkNests.NEST) ? here.getValue(DarkNests.STAGE) : 1;
                double dark = DarkLand.darkRadius(st);
                for (int i = 0; i < 4 + st * 2; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2, r = Math.pow(rnd.nextDouble(), .8) * dark;
                    double sx = core.getX() + .5 + Math.cos(a) * r, sz = core.getZ() + .5 + Math.sin(a) * r;
                    int ground = mc.level.getHeight(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            (int) Math.floor(sx),
                            (int) Math.floor(sz));
                    // Dark motes only: a light smoke reads as white cotton inside the dark fog.
                    mc.level.addParticle(
                            new net.minecraft.core.particles.DustParticleOptions(
                                    i % 4 == 0 ? 0x1E0E2C : 0x08040C, 2.5f + rnd.nextFloat() * 1.5f),
                            true,
                            true,
                            sx,
                            ground + .2 + rnd.nextDouble() * 1.6,
                            sz,
                            (rnd.nextDouble() - .5) * .02,
                            .004,
                            (rnd.nextDouble() - .5) * .02);
                }
            }
            // One layer only: a second, coplanar one fought it for depth and cut rectangles out of the column.
        }
    }

    /** One camera-facing strip, in bands: widening and leaning with the wind as it climbs, fading out at the crown. */
    private static void column(
            PoseStack.Pose p,
            VertexConsumer out,
            float x,
            float z,
            float px,
            float pz,
            float bottom,
            float scale,
            float sway,
            float vShift,
            float scroll,
            float strength) {
        int bands = 12;
        for (int i = 0; i < bands; i++) {
            float f0 = i / (float) bands, f1 = (i + 1) / (float) bands;
            band(p, out, x, z, px, pz, bottom, scale, sway, vShift, scroll, strength, f0, f1);
        }
    }

    private static void band(
            PoseStack.Pose p,
            VertexConsumer out,
            float x,
            float z,
            float px,
            float pz,
            float bottom,
            float scale,
            float sway,
            float vShift,
            float scroll,
            float strength,
            float f0,
            float f1) {
        float h = HEIGHT * scale;
        float y0 = bottom + h * f0, y1 = bottom + h * f1;
        float w0 = (FOOT + (CROWN - FOOT) * (float) Math.sqrt(f0)) * scale,
                w1 = (FOOT + (CROWN - FOOT) * (float) Math.sqrt(f1)) * scale;
        float l0 = sway * f0 * f0, l1 = sway * f1 * f1; // the wind bends the top, not the root
        float a0 = alpha(f0) * strength, a1 = alpha(f1) * strength;
        // The texture repeats about every 40 blocks of column and scrolls upward.
        float v0 = -f0 * HEIGHT / 40 + scroll + vShift, v1 = -f1 * HEIGHT / 40 + scroll + vShift;
        vertex(p, out, x + px * (l0 - w0), y0, z + pz * (l0 - w0), 0, v0, a0);
        vertex(p, out, x + px * (l0 + w0), y0, z + pz * (l0 + w0), 1, v0, a0);
        vertex(p, out, x + px * (l1 + w1), y1, z + pz * (l1 + w1), 1, v1, a1);
        vertex(p, out, x + px * (l1 - w1), y1, z + pz * (l1 - w1), 0, v1, a1);
    }

    /** Dense from the root, thinning past a third of the way up and gone at the crown. */
    private static float alpha(float f) {
        float rise = Math.min(1, f * 12); // a soft foot, so it grows out of the core rather than starting at a line
        float fall = f < .3f ? 1 : (1 - f) / .7f;
        return rise * fall * fall;
    }

    private static void vertex(
            PoseStack.Pose p, VertexConsumer out, float x, float y, float z, float u, float v, float a) {
        out.addVertex(p, x, y, z)
                .setColor(255, 255, 255, Math.round(255 * a))
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(15728880)
                .setNormal(p, 0, 1, 0);
    }
}
