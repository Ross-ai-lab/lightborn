package lightborn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import lightborn.StaffFlight;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * The staff under a Flight rider: the rider's own staff, drawn big and lying along the way they face, under their
 * seat, its head a little raised, like a broom.
 */
public final class StaffMountRenderer extends EntityRenderer<StaffFlight.Mount, StaffMountRenderer.State> {
    private final ItemModelResolver items;

    public static final class State extends EntityRenderState {
        final ItemStackRenderState staff = new ItemStackRenderState();
        float yaw, bob;
    }

    public StaffMountRenderer(EntityRendererProvider.Context c) {
        super(c);
        items = c.getItemModelResolver();
        shadowRadius = 0;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(StaffFlight.Mount e, State s, float partial) {
        super.extractRenderState(e, s, partial);
        items.updateForNonLiving(s.staff, e.staff(), ItemDisplayContext.FIXED, e);
        s.yaw = Mth.rotLerp(partial, e.yRotO, e.getYRot());
        s.bob = Mth.sin((e.tickCount + partial) * .12f) * .03f;
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!s.staff.isEmpty()) {
            pose.pushPose();
            pose.translate(0, .42 + s.bob, 0);
            pose.rotateDegrees(Axis.YP, -s.yaw);
            // The flat item sprite runs corner to corner, and the fixed view turns it about, so its head sits top-left
            // (-x, +y): stand it on edge along the facing (-x forward), then turn that diagonal down to lie forward,
            // head first and a touch up.
            pose.rotateDegrees(Axis.YP, 90);
            pose.rotateDegrees(Axis.ZP, 37);
            pose.scale(2.2f, 2.2f, 2.2f);
            s.staff.submit(pose, collector, s.lightCoords, OverlayTexture.NO_OVERLAY, s.outlineColor);
            pose.popPose();
        }
        super.submit(s, pose, collector, camera);
    }
}
