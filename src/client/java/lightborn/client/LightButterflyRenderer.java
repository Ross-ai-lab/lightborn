package lightborn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import lightborn.LightButterfly;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The Light Butterfly: two wings of living light ({@code tools/make_butterfly.py}), one mirrored, hinged on its body
 * and beating fast, the body flying nose up. Each wing is a quad drawn by hand at full light with an upward normal and a
 * front face on each side: an item model, even full-bright, is shaded like a lit object, and its back face (seen from
 * below) is lit from below and went dull at night. The motes it trails come from the server.
 */
public final class LightButterflyRenderer extends EntityRenderer<LightButterfly, LightButterflyRenderer.State> {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("lightborn", "textures/entity/light_butterfly_wing.png");
    /** One wing's size in blocks: a span of about a block. */
    private static final float WING = .48f;
    /**
     * The body flies nose up, as a butterfly's does: its wings then show some face from every side, never only a thin
     * edge from straight ahead or behind.
     */
    private static final float PITCH = 38;

    public static final class State extends EntityRenderState {
        float flap, yaw;
    }

    public LightButterflyRenderer(EntityRendererProvider.Context c) {
        super(c);
        shadowRadius = 0;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(LightButterfly e, State s, float partial) {
        super.extractRenderState(e, s, partial);
        float t = e.tickCount + partial + e.getId() * 3.1f;
        // Up from flat to steep and back, about three beats a second; each id beats on its own phase.
        s.flap = 12 + 58 * (.5f + .5f * Mth.sin(t * 1.1f));
        s.yaw = Mth.rotLerp(partial, e.yRotO, e.getYRot());
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        var type = net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutCull(TEXTURE);
        pose.pushPose();
        pose.translate(0, .25f, 0);
        pose.rotateDegrees(Axis.YP, -s.yaw);
        pose.rotateDegrees(Axis.XP, -PITCH);
        for (int side : new int[] {1, -1}) {
            pose.pushPose();
            pose.scale(side, 1, 1);
            pose.rotateDegrees(Axis.ZP, s.flap); // the wing lifts about the body's line
            collector.submitCustomGeometry(pose, type, LightButterflyRenderer::wing);
            pose.popPose();
        }
        pose.popPose();
        super.submit(s, pose, collector, camera);
    }

    /**
     * One wing laid out from its hinge on the body (x 0) to its tip (x {@link #WING}), its top edge forward (z -),
     * drawn as two front faces so either side is lit whole.
     */
    private static void wing(PoseStack.Pose p, VertexConsumer out) {
        float w = WING, h = WING / 2;
        vertex(p, out, 0, -h, 0, 0);
        vertex(p, out, 0, h, 0, 1);
        vertex(p, out, w, h, 1, 1);
        vertex(p, out, w, -h, 1, 0);
        vertex(p, out, w, -h, 1, 0);
        vertex(p, out, w, h, 1, 1);
        vertex(p, out, 0, h, 0, 1);
        vertex(p, out, 0, -h, 0, 0);
    }

    /** Full light and a world-up normal, so the entity shader's sun-and-sky shading leaves the glow whole. */
    private static void vertex(PoseStack.Pose p, VertexConsumer out, float x, float z, float u, float v) {
        out.addVertex(p, x, 0, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(15728880)
                .setNormal(0, 1, 0);
    }
}
