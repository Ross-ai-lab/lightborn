package lightborn.client.mixin;

import lightborn.client.BenchView;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stepping between Workbench stations: the next screen replaces the old at once, with no close between. */
@Mixin(LocalPlayer.class)
public abstract class BenchSwitchMixin {
    @Inject(method = "clientSideCloseContainer", at = @At("HEAD"), cancellable = true)
    private void lightborn$keepOpenWhileStepping(CallbackInfo ci) {
        if (BenchView.swallowClose()) ci.cancel();
    }
}
