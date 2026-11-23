package lightborn.mixin;

import lightborn.LightHome;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ServerPlayer.class)
abstract class LightHomeMixin {
    @Inject(method = "setRespawnPosition", at = @At("HEAD"), cancellable = true)
    private void lightborn$noBedSpawn(ServerPlayer.RespawnConfig config, boolean message, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("HEAD"), cancellable = true)
    private void lightborn$home(
            boolean alive,
            TeleportTransition.PostTeleportTransition after,
            CallbackInfoReturnable<TeleportTransition> result) {
        result.setReturnValue(LightHome.destination((ServerPlayer) (Object) this, after));
    }
}
