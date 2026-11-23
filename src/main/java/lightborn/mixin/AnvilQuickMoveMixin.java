package lightborn.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Enforce the resource gate even when another caller invokes the native quick-move entry point directly. */
@Mixin(ItemCombinerMenu.class)
public abstract class AnvilQuickMoveMixin {
    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void lightborn$requirePayment(Player player, int slot, CallbackInfoReturnable<ItemStack> result) {
        if ((Object) this instanceof AnvilMenu menu
                && slot == 2
                && !menu.getSlot(2).mayPickup(player)) result.setReturnValue(ItemStack.EMPTY);
    }
}
