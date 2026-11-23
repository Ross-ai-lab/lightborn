package lightborn.client.mixin;

import lightborn.ChestTravel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class DragonFlightCameraMixin {
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void lightborn$followTheLight(float distance, CallbackInfoReturnable<Float> result) {
        var p = Minecraft.getInstance().player;
        if (p != null && ChestTravel.flying(p)) result.setReturnValue(distance);
    }
}
