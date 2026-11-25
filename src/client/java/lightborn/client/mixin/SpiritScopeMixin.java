package lightborn.client.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
abstract class SpiritScopeMixin {
    @Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true)
    private void lightborn$spiritZoom(boolean firstPerson, float effects, CallbackInfoReturnable<Float> result) {
        if (firstPerson && lightborn.client.LightbornClient.spiritScoping(this)) result.setReturnValue(.1f);
    }

    @Mixin(net.minecraft.world.entity.player.Player.class)
    abstract static class Overlay {
        @Inject(method = "isScoping", at = @At("RETURN"), cancellable = true)
        private void lightborn$spiritScope(CallbackInfoReturnable<Boolean> result) {
            if (lightborn.client.LightbornClient.spiritScoping(this)) result.setReturnValue(true);
        }
    }
}
