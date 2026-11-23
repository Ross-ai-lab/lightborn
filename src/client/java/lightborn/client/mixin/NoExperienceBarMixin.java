package lightborn.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.contextualbar.ExperienceBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** There is no experience (owner, 2026-11-16), so the green bar above the hotbar is never drawn. */
@Mixin(ExperienceBar.class)
public abstract class NoExperienceBarMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void lightborn$noBarBackground(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void lightborn$noBarFill(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        ci.cancel();
    }
}
