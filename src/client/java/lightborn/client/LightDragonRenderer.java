package lightborn.client;

import lightborn.LightDragon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

public final class LightDragonRenderer extends MobRenderer<LightDragon, LightDragonRenderState, LightDragonModel> {
    private static final Identifier SKIN = MonsterKindRenderers.tex("light_dragon");

    public LightDragonRenderer(EntityRendererProvider.Context context) {
        super(context, new LightDragonModel(context.bakeLayer(LightDragonModel.LAYER)), .9f);
        shadowStrength = .35f;
        addLayer(new EyesLayer<LightDragonRenderState, LightDragonModel>(this) {
            @Override
            public RenderType renderType() {
                return RenderTypes.eyes(MonsterKindRenderers.tex("light_dragon_glow"));
            }
        });
    }

    @Override
    public boolean shouldRender(LightDragon d, Frustum f, double x, double y, double z, float partial) {
        var mc = Minecraft.getInstance();
        if (d.role() == 3
                && mc.player != null
                && d.distanceToSqr(mc.player) < 2
                && mc.options.getCameraType().isFirstPerson()) return false;
        return super.shouldRender(d, f, x, y, z, partial);
    }

    /** His shape reaches well past his hitbox (most of all sideways), so cull on the whole of it. */
    @Override
    protected AABB getBoundingBoxForCulling(LightDragon d, float partial) {
        float s = d.getScale();
        return d.getBoundingBox().inflate(1.6 * s, .6 * s, 1.6 * s);
    }

    @Override
    public LightDragonRenderState createRenderState() {
        return new LightDragonRenderState();
    }

    @Override
    public Identifier getTextureLocation(LightDragonRenderState state) {
        return SKIN;
    }

    @Override
    public void extractRenderState(LightDragon dragon, LightDragonRenderState state, float partial) {
        super.extractRenderState(dragon, state, partial);
        state.manifestation = dragon.manifestation(partial);
        state.opacity = dragon.opacity(partial);
    }

    @Override
    protected RenderType getRenderType(
            LightDragonRenderState state, boolean visible, boolean translucent, boolean glowing) {
        return state.opacity < .999f
                ? RenderTypes.entityTranslucentCull(SKIN)
                : super.getRenderType(state, visible, translucent, glowing);
    }

    @Override
    protected int getModelTint(LightDragonRenderState state) {
        return ((int) (255 * state.opacity) << 24) | 0xFFFFFF;
    }

    @Override
    protected boolean shouldRenderLayers(LightDragonRenderState state) {
        return state.opacity > .999f && super.shouldRenderLayers(state);
    }

    @Override
    protected int getBlockLightLevel(LightDragon dragon, BlockPos pos) {
        return 15;
    }
}
