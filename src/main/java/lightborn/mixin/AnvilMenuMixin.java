package lightborn.mixin;

import lightborn.Balance;
import lightborn.Enchanting;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla computes the result and prior-work penalty; synced prices replace the level payment. */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin extends AbstractContainerMenu implements Enchanting.AnvilPrice {
    @Shadow
    @Final
    private DataSlot cost;

    @Shadow
    private int repairItemCountCost;

    @Shadow
    private boolean onlyRenaming;

    @Shadow
    private String itemName;

    @Unique
    private final DataSlot lightborn$lapisPrice = DataSlot.standalone();

    protected AnvilMenuMixin(MenuType<?> type, int id) {
        super(type, id);
    }

    public int lightborn$lapis() {
        return lightborn$lapisPrice.get();
    }

    @Inject(
            method =
                    "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V",
            at = @At("RETURN"))
    private void lightborn$syncPrice(int id, Inventory inventory, ContainerLevelAccess access, CallbackInfo ci) {
        addDataSlot(lightborn$lapisPrice);
    }

    @Inject(method = "createResult", at = @At("HEAD"), cancellable = true)
    private void lightborn$clearPrice(CallbackInfo ci) {
        lightborn$lapisPrice.set(0);
        ItemStack input = getSlot(0).getItem(), addition = getSlot(1).getItem();
    }

    /** The second cost.get() is solely the Too Expensive rejection, including saturated int costs. */
    @Redirect(
            method = "createResult",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/DataSlot;get()I", ordinal = 1))
    private int lightborn$allowExpensiveResult(DataSlot slot) {
        return 0;
    }

    @Inject(
            method = "createResult",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AnvilMenu;broadcastChanges()V"))
    private void lightborn$priceEnchantments(CallbackInfo ci) {
        ItemStack input = getSlot(0).getItem(), addition = getSlot(1).getItem();
        if (getSlot(2).hasItem()
                && !input.isValidRepairItem(addition)
                && !EnchantmentHelper.getEnchantmentsForCrafting(addition).isEmpty()) {
            lightborn$lapisPrice.set(Math.min(Balance.ANVIL_LAPIS_CAP, cost.get()));
        }
        // Vanilla data slots are signed shorts on the wire. Send the capped price/validity, never a huge prior-work
        // tax.
        cost.set(getSlot(2).hasItem() ? Math.max(1, lightborn$lapis()) : 0);
    }

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void lightborn$canPay(Player player, boolean hasItem, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(hasItem && cost.get() > 0 && Enchanting.canPay(player, lightborn$lapis()));
    }

    @Inject(method = "onTake", at = @At("HEAD"))
    private void lightborn$payOnce(Player player, ItemStack carried, CallbackInfo ci) {
        Enchanting.consume(player, Items.LAPIS_LAZULI, lightborn$lapis());
    }
}
