package lightborn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import lightborn.Giants;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.BoggedRenderer;
import net.minecraft.client.renderer.entity.CaveSpiderRenderer;
import net.minecraft.client.renderer.entity.CreeperRenderer;
import net.minecraft.client.renderer.entity.DrownedRenderer;
import net.minecraft.client.renderer.entity.EndermanRenderer;
import net.minecraft.client.renderer.entity.HuskRenderer;
import net.minecraft.client.renderer.entity.ParchedRenderer;
import net.minecraft.client.renderer.entity.SkeletonRenderer;
import net.minecraft.client.renderer.entity.SpiderRenderer;
import net.minecraft.client.renderer.entity.StrayRenderer;
import net.minecraft.client.renderer.entity.WitchRenderer;
import net.minecraft.client.renderer.entity.state.BoggedRenderState;
import net.minecraft.client.renderer.entity.state.CreeperRenderState;
import net.minecraft.client.renderer.entity.state.EndermanRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.SkeletonRenderState;
import net.minecraft.client.renderer.entity.state.WitchRenderState;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.world.entity.monster.spider.Spider;

/** The giants: vanilla's own renderer and skin for each kind, drawn at the giant's size (the entity is that big). */
public final class GiantRenderers {
    private GiantRenderers() {}

    public static void register() {
        float s = Giants.SIZE;
        EntityRendererRegistry.register(Giants.SKELETON, ctx -> new SkeletonRenderer(ctx) {
            {
                shadowRadius *= s;
            }

            @Override
            protected void scale(SkeletonRenderState state, PoseStack pose) {
                super.scale(state, pose);
                pose.scale(s, s, s);
            }
        });
        EntityRendererRegistry.register(Giants.STRAY, ctx -> new StrayRenderer(ctx) {
            {
                shadowRadius *= s;
            }

            @Override
            protected void scale(SkeletonRenderState state, PoseStack pose) {
                super.scale(state, pose);
                pose.scale(s, s, s);
            }
        });
        EntityRendererRegistry.register(Giants.BOGGED, ctx -> new BoggedRenderer(ctx) {
            {
                shadowRadius *= s;
            }

            @Override
            protected void scale(BoggedRenderState state, PoseStack pose) {
                super.scale(state, pose);
                pose.scale(s, s, s);
            }
        });
        EntityRendererRegistry.register(Giants.PARCHED, ctx -> new ParchedRenderer(ctx) {
            {
                shadowRadius *= s;
            }

            @Override
            protected void scale(SkeletonRenderState state, PoseStack pose) {
                super.scale(state, pose);
                pose.scale(s, s, s);
            }
        });
        EntityRendererRegistry.register(Giants.HUSK, ctx -> new HuskRenderer(ctx) {
            {
                shadowRadius *= s;
            }

            @Override
            protected void scale(ZombieRenderState state, PoseStack pose) {
                super.scale(state, pose);
                pose.scale(s, s, s);
            }
        });
        EntityRendererRegistry.register(Giants.DROWNED, ctx -> new DrownedRenderer(ctx) {
            {
                shadowRadius *= s;
            }

            @Override
            protected void scale(ZombieRenderState state, PoseStack pose) {
                super.scale(state, pose);
                pose.scale(s, s, s);
            }
        });
        EntityRendererRegistry.register(Giants.SPIDER, ctx -> new SpiderRenderer<Spider>(ctx) {
            {
                shadowRadius *= s;
            }

            @Override
            protected void scale(LivingEntityRenderState state, PoseStack pose) {
                super.scale(state, pose);
                pose.scale(s, s, s);
            }
        });
        float cave = Giants.CAVE_SIZE;
        EntityRendererRegistry.register(Giants.CAVE_SPIDER, ctx -> new CaveSpiderRenderer(ctx) {
            {
                shadowRadius *= cave;
            }

            @Override
            protected void scale(LivingEntityRenderState state, PoseStack pose) {
                super.scale(state, pose);
                pose.scale(cave, cave, cave);
            }
        });
        EntityRendererRegistry.register(Giants.CREEPER, ctx -> new CreeperRenderer(ctx) {
            {
                shadowRadius *= s;
            }

            @Override
            protected void scale(CreeperRenderState state, PoseStack pose) {
                super.scale(state, pose);
                pose.scale(s, s, s);
            }
        });
        float ender = Giants.ENDER_SIZE;
        EntityRendererRegistry.register(Giants.ENDERMAN, ctx -> new EndermanRenderer(ctx) {
            {
                shadowRadius *= ender;
            }

            @Override
            protected void scale(EndermanRenderState state, PoseStack pose) {
                super.scale(state, pose);
                pose.scale(ender, ender, ender);
            }
        });
        EntityRendererRegistry.register(Giants.WITCH, ctx -> new WitchRenderer(ctx) {
            {
                shadowRadius *= s;
            }

            @Override
            protected void scale(WitchRenderState state, PoseStack pose) {
                super.scale(state, pose);
                pose.scale(s, s, s);
            }
        });
    }
}
