package lightborn.client;

import com.mojang.blaze3d.vertex.*;
import lightborn.RadiantPulse;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

/** An outward translucent sphere and six slender 3D light bands. */
public final class RadiantWaveRenderer extends EntityRenderer<RadiantPulse.Wave, RadiantWaveRenderer.State> {
    private static final RenderType TYPE = RenderTypes.entityTranslucentEmissive(
            Identifier.fromNamespaceAndPath("lightborn", "textures/entity/radiant_wave.png"));
    private static final float[][] DIRECTIONS = directions();
    private static final int[][] CORNERS = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    private static final float[][] NORMALS = {
        {0, 1, 0}, {0, 0, 1}, {1, 0, 0}, {.7071f, -.7071f, 0}, {.7071f, .7071f, 0}, {0, -.7071f, .7071f}
    };
    private static final float[][] TUBE = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
    private static final float[][] SHELL = shell();

    public static final class State extends EntityRenderState {
        float radius, fade;
    }

    public RadiantWaveRenderer(EntityRendererProvider.Context c) {
        super(c);
        shadowRadius = 0;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(RadiantPulse.Wave e, State s, float partial) {
        super.extractRenderState(e, s, partial);
        s.radius = e.radius(partial);
        s.fade = Math.clamp(1 - (e.tickCount + partial) / (RadiantPulse.WAVE_TICKS + 8f), 0, 1);
    }

    @Override
    protected AABB getBoundingBoxForCulling(RadiantPulse.Wave e, float partial) {
        return new AABB(e.position(), e.position()).inflate(RadiantPulse.RANGE);
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (s.radius < 1.5 || s.fade <= 0) return;
        float radius = s.radius;
        int alpha = (int) (210 * Math.sqrt(s.fade));
        collector.submitCustomGeometry(pose, TYPE, (p, out) -> {
            for (float[] unit : SHELL)
                out.addVertex(p, unit[0] * radius, unit[1] * radius, unit[2] * radius)
                        .setColor(255, 239, 171, (int) (12 * s.fade))
                        .setUv(.5f, .5f)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(15728880)
                        .setNormal(p, unit[0], unit[1], unit[2]);
            for (int ring = 0; ring < 6; ring++)
                for (int segment = 0; segment < 96; segment++)
                    for (int face = 0; face < 4; face++)
                        for (int[] corner : CORNERS) {
                            float[] unit = DIRECTIONS[ring * 97 + segment + corner[0]],
                                    normal = NORMALS[ring],
                                    edge = TUBE[(face + corner[1]) % 4];
                            float r = radius + edge[0] * .065f, side = edge[1] * .065f;
                            out.addVertex(
                                            p,
                                            unit[0] * r + normal[0] * side,
                                            unit[1] * r + normal[1] * side,
                                            unit[2] * r + normal[2] * side)
                                    .setColor(255, 247, 208, alpha)
                                    .setUv(.5f, .5f)
                                    .setOverlay(OverlayTexture.NO_OVERLAY)
                                    .setLight(15728880)
                                    .setNormal(p, normal[0], normal[1], normal[2]);
                        }
        });
    }

    private static float[][] shell() {
        var out = new float[32 * 16 * 4][3];
        int n = 0;
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 32; x++)
                for (int[] corner : CORNERS) {
                    double a = (x + corner[0]) * Math.PI * 2 / 32, b = (y + corner[1]) * Math.PI / 16;
                    out[n++] = new float[] {
                        (float) (Math.sin(b) * Math.cos(a)), (float) Math.cos(b), (float) (Math.sin(b) * Math.sin(a))
                    };
                }
        return out;
    }

    private static float[][] directions() {
        var out = new float[6 * 97][3];
        for (int ring = 0; ring < 6; ring++)
            for (int i = 0; i <= 96; i++) {
                double a = i * Math.PI * 2 / 96, c = Math.cos(a), s = Math.sin(a);
                out[ring * 97 + i] = switch (ring) {
                    case 0 -> new float[] {(float) c, 0, (float) s};
                    case 1 -> new float[] {(float) c, (float) s, 0};
                    case 2 -> new float[] {0, (float) c, (float) s};
                    case 3 -> new float[] {(float) (c * .7071), (float) (c * .7071), (float) s};
                    case 4 -> new float[] {(float) (c * .7071), (float) (-c * .7071), (float) s};
                    default -> new float[] {(float) c, (float) (s * .7071), (float) (s * .7071)};
                };
            }
        return out;
    }
}
