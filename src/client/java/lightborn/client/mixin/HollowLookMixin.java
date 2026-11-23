package lightborn.client.mixin;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The Hollow's kinds wear their dark skins only there (owner, 2026-11-21: "the endermen become darkmen there"): while
 * the player is in the Hollow, every draw that binds an Ender kind's texture binds its dark one instead
 * ({@code textures/entity/hollow/}, written by {@code tools/make_hollow.py}). Swapping at the bind covers each kind's
 * body, glowing eyes and death in one place, a companion mod's Mutant Enderman included, and an Enderman met anywhere
 * else keeps its own skin.
 */
@Mixin(RenderSetup.class)
public abstract class HollowLookMixin {
    private static final Map<Identifier, Identifier> DARK = new HashMap<>();

    static {
        dark("minecraft", "entity/enderman/enderman", "darkman");
        dark("minecraft", "entity/enderman/enderman_eyes", "darkman_eyes");
        dark("minecraft", "entity/endermite/endermite", "darkmite");
        dark("minecraft", "entity/shulker/shulker", "dark_shulker");
        dark("minecraft", "entity/enderdragon/dragon", "dark_dragon");
        dark("minecraft", "entity/enderdragon/dragon_eyes", "dark_dragon_eyes");
        dark("minecraft", "entity/enderdragon/dragon_exploding", "dark_dragon_exploding");
        dark("mutantmonsters", "entity/mutant_enderman/mutant_enderman", "mutant_darkman");
        dark("mutantmonsters", "entity/mutant_enderman/mutant_enderman_eyes", "mutant_darkman_eyes");
        dark("mutantmonsters", "entity/mutant_enderman/mutant_enderman_death", "mutant_darkman_death");
    }

    private static void dark(String namespace, String path, String hollow) {
        DARK.put(
                Identifier.fromNamespaceAndPath(namespace, "textures/" + path + ".png"),
                Identifier.fromNamespaceAndPath("lightborn", "textures/entity/hollow/" + hollow + ".png"));
    }

    @ModifyArg(
            method = "prepareTextures",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/texture/TextureManager;getTexture(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/texture/AbstractTexture;"))
    private Identifier lightborn$hollowSkin(Identifier texture) {
        var dark = DARK.get(texture);
        if (dark == null) return texture;
        var level = Minecraft.getInstance().level;
        return level != null && level.dimension() == Level.END ? dark : texture;
    }
}
