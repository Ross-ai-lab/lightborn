package lightborn.client.mixin;

import lightborn.DragonStory;
import lightborn.FormSpells;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Right-click in a beast form is the form's power (owner, 2026-11-23: "shouldn't be V but right click"), so while a
 * form is on, right-click uses, places and casts nothing else.
 */
@Mixin(Minecraft.class)
public abstract class FormUseMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void lightborn$formPower(CallbackInfo ci) {
        var p = ((Minecraft) (Object) this).player;
        if (p == null || FormSpells.form(p) == FormSpells.Form.NONE) return;
        ClientPlayNetworking.send(new DragonStory.Power(3));
        ci.cancel();
    }
}
