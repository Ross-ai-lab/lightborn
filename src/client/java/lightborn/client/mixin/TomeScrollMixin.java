package lightborn.client.mixin;

import lightborn.client.TomeStripView;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sneak + scroll with a staff turns the off-hand tome instead of the hotbar. */
@Mixin(MouseHandler.class)
public abstract class TomeScrollMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void lightborn$turnTome(long window, double x, double y, CallbackInfo ci) {
        if (TomeStripView.scroll(y)) ci.cancel();
    }
}
