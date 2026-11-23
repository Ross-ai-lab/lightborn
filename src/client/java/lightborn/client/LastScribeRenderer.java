package lightborn.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.model.monster.zombie.ZombieModel;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.resources.Identifier;

/** Hood and long ink-black robe, distinct from the wandering dead. */
public final class LastScribeRenderer extends ZombieRenderer {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(Identifier.fromNamespaceAndPath("lightborn", "last_scribe"), "main");

    public LastScribeRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        model = new ZombieModel<>(ctx.bakeLayer(LAYER));
    }

    public static LayerDefinition layer() {
        var mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0);
        var root = mesh.getRoot();
        root.addOrReplaceChild(
                "body", CubeListBuilder.create().texOffs(16, 16).addBox(-4, 0, -2, 8, 19, 4), PartPose.ZERO);
        root.addOrReplaceChild(
                "head", CubeListBuilder.create().texOffs(0, 0).addBox(-4.5f, -8.5f, -4.5f, 9, 9, 9), PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public Identifier getTextureLocation(ZombieRenderState s) {
        return MonsterKindRenderers.tex("last_scribe");
    }
}
