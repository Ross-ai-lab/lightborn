package lightborn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import lightborn.Refugee;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/** A refugee looks like any other player: the player model wearing one of the game's nine default skins. */
public final class RefugeeRenderer
        extends HumanoidMobRenderer<Refugee, RefugeeRenderer.State, HumanoidModel<RefugeeRenderer.State>> {
    public static final class State extends HumanoidRenderState {
        int skin;
    }

    private static final Identifier[] SKINS = java.util.stream.Stream.of(
                    "steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri")
            .map(n -> Identifier.withDefaultNamespace("textures/entity/player/wide/" + n + ".png"))
            .toArray(Identifier[]::new);

    public RefugeeRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), .5f);
        addLayer(new HumanoidArmorLayer<>(
                this,
                ModelLayers.PLAYER_ARMOR.map(layer -> new HumanoidModel<State>(ctx.bakeLayer(layer))),
                ctx.getEquipmentRenderer()));
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(Refugee refugee, State state, float partial) {
        super.extractRenderState(refugee, state, partial);
        state.skin = refugee.skin();
    }

    /** Swimming, he lies flat in the water like a swimming player instead of standing upright and gliding. */
    @Override
    protected void setupRotations(State state, PoseStack pose, float bodyRot, float scale) {
        super.setupRotations(state, pose, bodyRot, scale);
        if (state.swimAmount > 0) {
            pose.rotateDegrees(Axis.XP, Mth.lerp(state.swimAmount, 0, -90 - state.xRot));
            if (state.isVisuallySwimming) pose.translate(0, -1, .3f);
        }
    }

    @Override
    public Identifier getTextureLocation(State state) {
        return SKINS[Math.floorMod(state.skin, SKINS.length)];
    }
}
