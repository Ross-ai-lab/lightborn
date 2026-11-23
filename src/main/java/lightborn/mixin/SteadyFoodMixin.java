package lightborn.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
public abstract class SteadyFoodMixin {
    @Shadow
    private float exhaustionLevel;

    @Inject(method = "addExhaustion", at = @At("HEAD"), cancellable = true)
    private void lightborn$noActivityCost(float amount, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void lightborn$clearOldExhaustion(ServerPlayer player, CallbackInfo ci) {
        exhaustionLevel = 0;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;heal(F)V"))
    private void lightborn$halfNaturalHealing(ServerPlayer player, float amount) {
        player.heal(amount * .5f);
    }
}
