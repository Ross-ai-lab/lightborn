package lightborn.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The grindstone's result slot (anonymous class GrindstoneMenu$4) gives back no XP. With enchanting free, grinding
 * enchantments off would turn lapis into power XP without end.
 */
@Mixin(targets = "net.minecraft.world.inventory.GrindstoneMenu$4")
public abstract class GrindstoneResultSlotMixin {
    @Inject(method = "getExperienceAmount", at = @At("HEAD"), cancellable = true)
    private void lightborn$noGrindXp(Level level, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(0);
    }
}
