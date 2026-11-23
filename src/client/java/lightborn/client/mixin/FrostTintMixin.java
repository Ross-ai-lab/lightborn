package lightborn.client.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Frostbite shows on the creature itself (owner, 2026-11-23: frostbite "does not actually cover the mob in snow"): a
 * creature frozen through is washed white with frost (the overlay creepers flash white with, held part way) and tinted
 * ice blue, on top of vanilla's shaking. A tint alone only darkens, so it barely showed on a dark zombie. Keyed to
 * vanilla's synced frozen ticks, so powder snow frosts a mob the same way; players are left as they are (vanilla frosts
 * their screen instead).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class FrostTintMixin {
    @Unique
    private static final int FROST = 0xFFAED8FF;

    /** How white the frost washes a frozen creature (0 none, 1 a creeper's full flash). */
    @Unique
    private static final float FROST_WASH = .42f;

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "getOverlayCoords(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)I",
            at = @At("HEAD"),
            argsOnly = true)
    private static float lightborn$frostWash(float white, LivingEntityRenderState state) {
        return state.isFullyFrozen && !(state instanceof AvatarRenderState) ? Math.max(white, FROST_WASH) : white;
    }

    @Inject(method = "getModelTint", at = @At("RETURN"), cancellable = true)
    private void lightborn$frostTint(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
        if (!state.isFullyFrozen || state instanceof AvatarRenderState) return;
        int c = cir.getReturnValue();
        int r = (c >> 16 & 255) * (FROST >> 16 & 255) / 255,
                g = (c >> 8 & 255) * (FROST >> 8 & 255) / 255,
                b = (c & 255) * (FROST & 255) / 255;
        cir.setReturnValue(c & 0xFF000000 | r << 16 | g << 8 | b);
    }
}
