package lightborn.client.mixin;

import lightborn.client.WardGlow;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The client world's block light reads as {@link WardGlow#LEVEL} inside a ward. Both read paths: single blocks (vanilla
 * terrain and every entity) and whole section copies (Sodium's terrain).
 */
@Mixin(LightEngine.class)
public abstract class WardGlowLightMixin {
    @Inject(method = "getLightValue", at = @At("RETURN"), cancellable = true)
    private void lightborn$wardGlow(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!WardGlow.isClientBlockEngine(this)) return;
        int glow = WardGlow.at(pos);
        if (glow > cir.getReturnValueI()) cir.setReturnValue(glow);
    }

    @Inject(method = "getDataLayerData", at = @At("RETURN"), cancellable = true)
    private void lightborn$wardGlowSection(SectionPos pos, CallbackInfoReturnable<DataLayer> cir) {
        if (WardGlow.isClientBlockEngine(this) && WardGlow.touches(pos))
            cir.setReturnValue(WardGlow.raise(pos, cir.getReturnValue()));
    }
}
