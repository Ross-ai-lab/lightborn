package lightborn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import lightborn.Taunt;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Taunt's Totem of Light (owner, 2026-11-18: a real 3D model): an ivory pillar banded in gold, four gold rays and a
 * floating crystal of light ({@code tools/make_totem.py}), about 2.2 blocks tall, drawn full-bright and turning slowly
 * so it reads as a beacon in the dark. The chime and the rising light come from the server.
 */
public final class TotemRenderer extends EntityRenderer<Taunt.Totem, TotemRenderer.State> {
    /** Made on first use: an ItemStack built while renderers load, before item components bind, crashes the reload. */
    private ItemStack totem;
    /** The model is 2 blocks tall; drawn this much larger. */
    private static final float SCALE = 1.1f;
    /** Full brightness, block and sky. */
    private static final int FULL_BRIGHT = 0xF000F0;

    private final ItemModelResolver items;

    public static final class State extends EntityRenderState {
        final ItemStackRenderState totem = new ItemStackRenderState();
        float spin, bob;
    }

    public TotemRenderer(EntityRendererProvider.Context c) {
        super(c);
        items = c.getItemModelResolver();
        shadowRadius = .4f;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(Taunt.Totem e, State s, float partial) {
        super.extractRenderState(e, s, partial);
        if (totem == null) {
            totem = new ItemStack(Items.GOLD_INGOT);
            totem.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("lightborn", "light_totem"));
        }
        items.updateForNonLiving(s.totem, totem, ItemDisplayContext.NONE, e);
        float t = e.tickCount + partial;
        s.spin = t * 1.2f;
        s.bob = Mth.sin(t * .1f) * .04f;
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!s.totem.isEmpty()) {
            pose.pushPose();
            // The model's floor is half a block under its centre: lift it onto the ground.
            pose.translate(0, .5f * SCALE + s.bob, 0);
            pose.rotateDegrees(Axis.YP, s.spin);
            pose.scale(SCALE, SCALE, SCALE);
            s.totem.submit(pose, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, s.outlineColor);
            pose.popPose();
        }
        super.submit(s, pose, collector, camera);
    }
}
