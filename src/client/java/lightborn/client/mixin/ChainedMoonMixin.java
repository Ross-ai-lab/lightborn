package lightborn.client.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import lightborn.DragonStory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The chained moon, until the Dragon is freed as a friend (owner, 2026-11-19: then the moon's lock goes). The chained
 * phases are the mod's own sprites ({@code lightborn:moon/<phase>}, from {@code tools/make_chained_moon.py}) in the same
 * celestial atlas as vanilla's; a second set of moon quads is built from them exactly as vanilla builds its own, and the
 * sky draws that set unless {@link DragonStory#nightLifted} holds for the world in view.
 */
@Mixin(SkyRenderer.class)
abstract class ChainedMoonMixin {
    /** The namespace vanilla's moon builder reads its sprites from while the chained set is built. */
    @Unique
    private static String lightborn$moonNamespace;

    @Shadow
    @Final
    private TextureAtlas celestialsAtlas;

    @Shadow
    @Final
    private GpuBuffer moonBuffer;

    @Unique
    private GpuBuffer lightborn$chained;

    @Shadow
    private static GpuBuffer buildMoonPhases(TextureAtlas atlas) {
        throw new AssertionError();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void lightborn$buildChained(CallbackInfo ci) {
        lightborn$moonNamespace = "lightborn";
        try {
            lightborn$chained = buildMoonPhases(celestialsAtlas);
        } finally {
            lightborn$moonNamespace = null;
        }
    }

    @Redirect(
            method = "buildMoonPhases",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/resources/Identifier;withDefaultNamespace(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"))
    private static Identifier lightborn$phaseSprite(String path) {
        return lightborn$moonNamespace == null
                ? Identifier.withDefaultNamespace(path)
                : Identifier.fromNamespaceAndPath(lightborn$moonNamespace, path);
    }

    @Redirect(
            method = "renderMoon",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/client/renderer/SkyRenderer;moonBuffer:Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"))
    private GpuBuffer lightborn$whichMoon(SkyRenderer self) {
        var level = Minecraft.getInstance().level;
        boolean freed = level != null && DragonStory.nightLifted(level);
        return freed || lightborn$chained == null ? moonBuffer : lightborn$chained;
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void lightborn$closeChained(CallbackInfo ci) {
        if (lightborn$chained != null) lightborn$chained.close();
    }
}
