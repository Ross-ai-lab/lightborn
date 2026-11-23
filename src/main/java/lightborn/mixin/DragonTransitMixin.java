package lightborn.mixin;

import lightborn.ChestTravel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class DragonTransitMixin {
    @Inject(method = "stopRiding", at = @At("HEAD"), cancellable = true)
    private void lightborn$stayInClaws(CallbackInfo ci) {
        if (ChestTravel.lockDismount((Entity) (Object) this)) ci.cancel();
    }
}
