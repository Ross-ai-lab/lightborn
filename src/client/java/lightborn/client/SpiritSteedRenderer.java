package lightborn.client;

import lightborn.SpiritSteed;
import net.minecraft.client.model.animal.equine.BabyHorseModel;
import net.minecraft.client.model.animal.equine.EquineSaddleModel;
import net.minecraft.client.model.animal.equine.HorseModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.AbstractHorseRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.layers.SimpleEquipmentLayer;
import net.minecraft.client.renderer.entity.state.EquineRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.Identifier;

/**
 * The Spirit Steed: vanilla's horse model and saddle in spectral white and blue, its whole body drawn a second time
 * full-bright from {@code spirit_steed_glow.png} so it shines at night, like the spirit wolves (vanilla's horse
 * renderer is final, so this one is built the same way without the coat markings).
 */
public class SpiritSteedRenderer extends AbstractHorseRenderer<SpiritSteed, EquineRenderState, HorseModel> {
    private static final Identifier SKIN =
            Identifier.fromNamespaceAndPath("lightborn", "textures/entity/spirit_steed.png");
    private static final RenderType GLOW =
            RenderTypes.eyes(Identifier.fromNamespaceAndPath("lightborn", "textures/entity/spirit_steed_glow.png"));

    public SpiritSteedRenderer(EntityRendererProvider.Context ctx) {
        super(
                ctx,
                new HorseModel(ctx.bakeLayer(ModelLayers.HORSE)),
                new BabyHorseModel(ctx.bakeLayer(ModelLayers.HORSE_BABY)));
        addLayer(new SimpleEquipmentLayer<>(
                this,
                ctx.getEquipmentRenderer(),
                EquipmentClientInfo.LayerType.HORSE_SADDLE,
                state -> state.saddle,
                new EquineSaddleModel(ctx.bakeLayer(ModelLayers.HORSE_SADDLE)),
                null,
                2));
        addLayer(new EyesLayer<EquineRenderState, HorseModel>(this) {
            @Override
            public RenderType renderType() {
                return GLOW;
            }
        });
    }

    @Override
    public Identifier getTextureLocation(EquineRenderState state) {
        return SKIN;
    }

    @Override
    public EquineRenderState createRenderState() {
        return new EquineRenderState();
    }
}
