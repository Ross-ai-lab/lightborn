package lightborn.client.mixin;

import lightborn.ChestTravel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class DragonTransitInputMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void lightborn$carriedInput(CallbackInfo ci) {
        var p = Minecraft.getInstance().player;
        if (p != null && ChestTravel.flying(p)) ((ClientInput) (Object) this).keyPresses = Input.EMPTY;
    }
}
