package lightborn.mixin;

import lightborn.Lightborn;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerDeathMixin {
    @Inject(method = "die", at = @At("RETURN"))
    private void lightborn$settleDeath(DamageSource source, CallbackInfo ci) {
        Lightborn.onPlayerDeath((ServerPlayer) (Object) this, source);
    }
}
