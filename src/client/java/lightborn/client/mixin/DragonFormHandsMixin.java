package lightborn.client.mixin;

import lightborn.DragonForm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class DragonFormHandsMixin {
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void lightborn$dragonHasNoHumanHands(CallbackInfo ci) {
        var p = Minecraft.getInstance().player;
        // A beast form has no human hands either (owner, 2026-11-22: the seventh shelf's forms).
        if (p != null && (DragonForm.visual(p) || lightborn.FormSpells.form(p) != lightborn.FormSpells.Form.NONE))
            ci.cancel();
    }
}
