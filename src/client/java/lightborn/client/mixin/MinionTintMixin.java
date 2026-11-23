package lightborn.client.mixin;

import lightborn.ModData;
import lightborn.client.UndeadEyes;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A minion's skin washed with warm light, on top of {@link MinionLightMixin}'s inner light. */
@Mixin(LivingEntityRenderer.class)
public abstract class MinionTintMixin {
    @Unique
    private static final int LIGHT_TINT = 0xFFFFF1C8;

    @Inject(
            method =
                    "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void lightborn$minionState(LivingEntity e, LivingEntityRenderState state, float partial, CallbackInfo ci) {
        ((UndeadEyes.State) state).lightborn$minion(Boolean.TRUE.equals(e.getAttached(ModData.MINION)));
    }

    @Inject(method = "getModelTint", at = @At("RETURN"), cancellable = true)
    private void lightborn$minionTint(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
        if (!((UndeadEyes.State) state).lightborn$minion()) return;
        int c = cir.getReturnValue();
        int r = (c >> 16 & 255) * (LIGHT_TINT >> 16 & 255) / 255,
                g = (c >> 8 & 255) * (LIGHT_TINT >> 8 & 255) / 255,
                b = (c & 255) * (LIGHT_TINT & 255) / 255;
        cir.setReturnValue(c & 0xFF000000 | r << 16 | g << 8 | b);
    }
}
