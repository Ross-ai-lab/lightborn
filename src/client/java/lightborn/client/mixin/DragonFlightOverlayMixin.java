package lightborn.client.mixin;

import lightborn.ChestTravel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class DragonFlightOverlayMixin {
    @Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
    private static void lightborn$phaseWithoutSuffocationOverlay(CallbackInfo ci) {
        var p = Minecraft.getInstance().player;
        if (p != null && ChestTravel.flying(p)) ci.cancel();
    }
}
