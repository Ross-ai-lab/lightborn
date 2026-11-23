package lightborn.client.mixin;

import lightborn.client.DragonTransitView;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class DragonFlightOutlineMixin {
    @Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
    private void lightborn$noPhasingBlockOutline(CallbackInfo ci) {
        if (DragonTransitView.phasing()) ci.cancel();
    }
}
