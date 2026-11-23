package lightborn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.Map;
import lightborn.TravelSpells;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;

/**
 * Moongate's portals (owner, 2026-11-22: "make the portal look like Rick and Morty's portal, and you can't see through
 * it"): an upright oval of swirling green light, opaque, drawn on both faces at full brightness. The server marks each
 * gate with an empty item display named {@link TravelSpells#PORTAL_NAME} and turned to the gate's facing; this draws the
 * swirl there itself, because an item model on a display is shaded like a lit object and lost half its glow on a
 * standing face. The swirl is a strip of {@link #FRAMES} frames ({@code tools/make_moongate.py}) stepped through here;
 * a portal grows open over its first ticks and shrinks shut when the server renames it closing.
 */
public final class PortalView {
    private PortalView() {}

    /**
     * Culled, and drawn as two one-sided faces: a back face in 26.3's entity shader is lit from below and came out at
     * half brightness, so each side gets a front face of its own.
     */
    private static final RenderType PORTAL = RenderTypes.entityCutoutCull(
            Identifier.fromNamespaceAndPath("lightborn", "textures/entity/moongate_portal.png"));

    private static final int FRAMES = 24, FRAME_TICKS = 2, GROW = 8;
    /** When each portal seen closing began to close, by entity id, so it shrinks over a few ticks. */
    private static final Map<Integer, Float> CLOSING = new HashMap<>();

    static void submit(LevelRenderContext ctx) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float now = mc.player.tickCount + partial;
        var cam = ctx.levelState().cameraRenderState.pos;
        int frame = (int) ((mc.level.getGameTime() / FRAME_TICKS) % FRAMES);
        float v0 = frame / (float) FRAMES, v1 = (frame + 1) / (float) FRAMES;
        PoseStack pose = ctx.poseStack();
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Display.ItemDisplay d) || !d.hasCustomName()) continue;
            String name = d.getCustomName().getString();
            boolean closing = name.equals(TravelSpells.PORTAL_CLOSING);
            if (!closing && !name.equals(TravelSpells.PORTAL_NAME)) {
                continue;
            }
            if (e.distanceToSqr(cam) > 128 * 128) continue;
            float size = Math.min(1, (d.tickCount + partial) / GROW);
            if (closing) {
                float since = now - CLOSING.computeIfAbsent(d.getId(), k -> now);
                size = Math.max(0, 1 - since / 6f);
            } else CLOSING.remove(d.getId());
            if (size <= .01f) continue;
            size = 1 - (1 - size) * (1 - size); // eases open
            float w = TravelSpells.PORTAL_WIDE * size / 2, h = TravelSpells.PORTAL_TALL * size / 2;
            double yaw = Math.toRadians(d.getYRot());
            float sx = (float) Math.cos(yaw), sz = (float) Math.sin(yaw); // across the gate's face
            float x = (float) (d.getX() - cam.x), z = (float) (d.getZ() - cam.z);
            float y = (float) (d.getY() + TravelSpells.PORTAL_TALL / 2 + .05 - cam.y);
            ctx.submitNodeCollector().submitCustomGeometry(pose, PORTAL, (p, out) -> {
                vertex(p, out, x - sx * w, y - h, z - sz * w, 0, v1);
                vertex(p, out, x + sx * w, y - h, z + sz * w, 1, v1);
                vertex(p, out, x + sx * w, y + h, z + sz * w, 1, v0);
                vertex(p, out, x - sx * w, y + h, z - sz * w, 0, v0);
                // The other side, wound the other way, mirrored so the swirl turns the same way seen from there.
                vertex(p, out, x - sx * w, y + h, z - sz * w, 1, v0);
                vertex(p, out, x + sx * w, y + h, z + sz * w, 0, v0);
                vertex(p, out, x + sx * w, y - h, z + sz * w, 0, v1);
                vertex(p, out, x - sx * w, y - h, z - sz * w, 1, v1);
            });
        }
        if (CLOSING.size() > 64) CLOSING.clear();
    }

    /** Full light and a normal facing up, so the entity shader's sun-and-sky shading leaves the glow whole. */
    private static void vertex(PoseStack.Pose p, VertexConsumer out, float x, float y, float z, float u, float v) {
        out.addVertex(p, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(15728880)
                .setNormal(p, 0, 1, 0);
    }
}
