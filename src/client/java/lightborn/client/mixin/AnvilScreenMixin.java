package lightborn.client.mixin;

import lightborn.Enchanting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Use the synced lapis/shard price and the server's affordability check for the normal price line. */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin extends AbstractContainerScreen<AnvilMenu> {
    protected AnvilScreenMixin(AnvilMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Redirect(
            method = "extractLabels",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AnvilMenu;getCost()I"))
    private int lightborn$showValidPrice(AnvilMenu menu) {
        return menu.getSlot(2).hasItem() ? Math.max(1, ((Enchanting.AnvilPrice) menu).lightborn$lapis()) : 0;
    }

    @Redirect(
            method = "extractLabels",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent lightborn$priceLine(String key, Object[] args) {
        var price = (Enchanting.AnvilPrice) menu;
        return Component.translatable(
                price.lightborn$lapis() == 0 ? "lightborn.anvil.free" : "container.repair.cost",
                price.lightborn$lapis());
    }
}
