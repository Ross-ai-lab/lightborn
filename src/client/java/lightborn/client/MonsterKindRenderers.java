package lightborn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ambient.BatModel;
import net.minecraft.client.model.animal.wolf.WolfModel;
import net.minecraft.client.model.monster.spider.SpiderModel;
import net.minecraft.client.renderer.entity.BatRenderer;
import net.minecraft.client.renderer.entity.DrownedRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.SkeletonRenderer;
import net.minecraft.client.renderer.entity.SpiderRenderer;
import net.minecraft.client.renderer.entity.WolfRenderer;
import net.minecraft.client.renderer.entity.layers.DrownedOuterLayer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.layers.SpiderEyesLayer;
import net.minecraft.client.renderer.entity.state.BatRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.SkeletonRenderState;
import net.minecraft.client.renderer.entity.state.WolfRenderState;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.monster.spider.Spider;

/**
 * The skeleton and spider kinds: vanilla's own model with the kind's skin ({@code textures/entity/<name>.png}) and
 * its own glowing eyes ({@code <name>_eyes.png}, drawn full-bright; a spider kind's replace vanilla's red ones).
 */
public final class MonsterKindRenderers {
    private MonsterKindRenderers() {}

    static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath("lightborn", "textures/entity/" + name + ".png");
    }

    public static class Skeleton extends SkeletonRenderer {
        private final Identifier skin;

        public Skeleton(EntityRendererProvider.Context ctx, String name) {
            super(ctx);
            this.skin = tex(name);
            // Its <name>_eyes.png is drawn by UndeadEyes' shared night layer.
        }

        @Override
        public Identifier getTextureLocation(SkeletonRenderState state) {
            return skin;
        }
    }

    public static class SpiderKind extends SpiderRenderer<Spider> {
        private final Identifier skin;

        public SpiderKind(EntityRendererProvider.Context ctx, String name) {
            super(ctx);
            this.skin = tex(name);
            RenderType eyes = RenderTypes.eyes(tex(name + "_eyes"));
            layers.removeIf(layer -> layer instanceof SpiderEyesLayer);
            if (!name.equals("deep_stalker_spider")) addLayer(new SpiderEyes(this, eyes));
        }

        @Override
        public Identifier getTextureLocation(LivingEntityRenderState state) {
            return skin;
        }
    }

    /** The bat zombie: vanilla's bat half again as big, in rot, with torn wings and red eyes that glow. */
    public static class BatZombieRenderer extends BatRenderer {
        static final float SCALE = 1.5f;

        public BatZombieRenderer(EntityRendererProvider.Context ctx) {
            super(ctx);
            RenderType eyes = RenderTypes.eyes(tex("bat_zombie_eyes"));
            addLayer(new EyesLayer<BatRenderState, BatModel>(this) {
                @Override
                public RenderType renderType() {
                    return eyes;
                }
            });
        }

        @Override
        protected void scale(BatRenderState state, PoseStack pose) {
            pose.scale(SCALE, SCALE, SCALE);
        }

        @Override
        public Identifier getTextureLocation(BatRenderState state) {
            return tex("bat_zombie");
        }
    }

    public static class Captain extends DrownedRenderer {
        public Captain(EntityRendererProvider.Context ctx) {
            super(ctx);
            layers.removeIf(layer -> layer instanceof DrownedOuterLayer);
        }

        @Override
        public Identifier getTextureLocation(ZombieRenderState state) {
            return tex("drowned_captain");
        }
    }

    /**
     * The spirit wolf: vanilla's wolf (collar and all) in spectral blue, with its whole body drawn a second time
     * full-bright from {@code spirit_wolf_glow.png}, so it shines in the dark.
     */
    public static class SpiritWolf extends WolfRenderer {
        private static final Identifier SKIN = tex("spirit_wolf");

        public SpiritWolf(EntityRendererProvider.Context ctx) {
            super(ctx);
            RenderType glow = RenderTypes.eyes(tex("spirit_wolf_glow"));
            addLayer(new EyesLayer<WolfRenderState, WolfModel>(this) {
                @Override
                public RenderType renderType() {
                    return glow;
                }
            });
        }

        @Override
        public Identifier getTextureLocation(WolfRenderState state) {
            return SKIN;
        }
    }

    private static final class SpiderEyes extends EyesLayer<LivingEntityRenderState, SpiderModel> {
        private final RenderType type;

        SpiderEyes(RenderLayerParent<LivingEntityRenderState, SpiderModel> parent, RenderType type) {
            super(parent);
            this.type = type;
        }

        @Override
        public RenderType renderType() {
            return type;
        }
    }
}
