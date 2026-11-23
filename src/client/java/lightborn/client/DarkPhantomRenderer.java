package lightborn.client;

import net.minecraft.client.model.monster.phantom.PhantomModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.PhantomRenderer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.layers.PhantomEyesLayer;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/** The Dark Phantom: the phantom's shape in black, with dim milky eyes in place of the green glow (it is blind). */
public class DarkPhantomRenderer extends PhantomRenderer {
    private static final Identifier SKIN =
            Identifier.fromNamespaceAndPath("lightborn", "textures/entity/dark_phantom.png");
    private static final RenderType EYES =
            RenderTypes.eyes(Identifier.fromNamespaceAndPath("lightborn", "textures/entity/dark_phantom_eyes.png"));

    public DarkPhantomRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        layers.removeIf(layer -> layer instanceof PhantomEyesLayer);
        addLayer(new EyesLayer<PhantomRenderState, PhantomModel>(this) {
            @Override
            public RenderType renderType() {
                return EYES;
            }
        });
    }

    @Override
    public Identifier getTextureLocation(PhantomRenderState state) {
        return SKIN;
    }
}
