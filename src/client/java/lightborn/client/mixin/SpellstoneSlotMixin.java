package lightborn.client.mixin;

import lightborn.client.SpellstoneView;
import net.minecraft.client.gui.ItemSlotMouseAction;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every inventory screen learns the Spellstone's scroll-to-pick, beside the bundle's (init clears and refills both). */
@Mixin(AbstractContainerScreen.class)
public abstract class SpellstoneSlotMixin {
    @Shadow
    protected abstract void addItemSlotMouseAction(ItemSlotMouseAction action);

    @Inject(method = "init", at = @At("TAIL"))
    private void lightborn$spellstoneScroll(CallbackInfo ci) {
        addItemSlotMouseAction(new SpellstoneView.MouseActions());
    }
}
