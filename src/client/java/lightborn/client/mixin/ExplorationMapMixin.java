package lightborn.client.mixin;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Use Xaero's recorded-chunk format even in singleplayer; never read unseen terrain from region saves. */
@Pseudo
@Mixin(targets = "xaero.map.world.MapDimension")
public abstract class ExplorationMapMixin {
    @Inject(
            method = {"isUsingWorldSave", "isFutureUsingWorldSaveUnsynced"},
            at = @At("HEAD"),
            cancellable = true)
    private void lightborn$recordExplorationOnly(CallbackInfoReturnable<Boolean> result) {
        result.setReturnValue(false);
    }

    @Inject(
            method = {"getCurrentMultiworld", "getFutureMultiworldUnsynced"},
            at = @At("RETURN"),
            cancellable = true)
    private void lightborn$namedExplorationRecord(CallbackInfoReturnable<String> result) {
        if (result.getReturnValue() == null || result.getReturnValue().isEmpty())
            result.setReturnValue("cm$lightborn_explored");
    }
}
