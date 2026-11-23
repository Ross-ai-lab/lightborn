package lightborn.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Window title: "Minecraft 26.3 · Lightborn". */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
    private void lightborn$title(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(cir.getReturnValue() + "  ·  Lightborn");
    }
}
