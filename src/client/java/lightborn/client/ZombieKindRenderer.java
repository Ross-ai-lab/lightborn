package lightborn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.resources.Identifier;

/**
 * One of the mod's zombie kinds: a vanilla zombie's model with its own skin ({@code textures/entity/<name>.png} and
 * {@code <name>_baby.png}), drawn {@code size} times larger (the entity type is that much bigger too). Its glowing eyes
 * ({@code <name>_eyes.png}, {@code <name>_baby_eyes.png}) are drawn at night by {@link UndeadEyes}' shared layer.
 */
public class ZombieKindRenderer extends ZombieRenderer {
    private final Identifier skin, babySkin;
    private final float size;

    public ZombieKindRenderer(EntityRendererProvider.Context ctx, String name, float size) {
        super(ctx);
        this.skin = tex(name);
        this.babySkin = tex(name + "_baby");
        this.size = size;
        this.shadowRadius *= size;
    }

    private static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath("lightborn", "textures/entity/" + name + ".png");
    }

    @Override
    public Identifier getTextureLocation(ZombieRenderState state) {
        return state.isBaby ? babySkin : skin;
    }

    @Override
    protected void scale(ZombieRenderState state, PoseStack pose) {
        super.scale(state, pose);
        if (size != 1f) pose.scale(size, size, size);
    }
}
