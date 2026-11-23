package lightborn.mixin;

import java.util.List;
import lightborn.Enchanting;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keep vanilla's seed, clues and roll costs. Only the payment changes: lapis alone, no experience levels and no Dark
 * Shard (owner, 2026-11-16: Dark Shards are only for the Cleanse).
 */
@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuMixin {
    @Shadow
    @Final
    private Container enchantSlots;

    @Shadow
    @Final
    private ContainerLevelAccess access;

    @Shadow
    @Final
    private DataSlot enchantmentSeed;

    @Shadow
    @Final
    public int[] costs;

    @Shadow
    protected abstract List<EnchantmentInstance> getEnchantmentList(
            RegistryAccess registry, ItemStack stack, int slot, int cost);

    @Shadow
    public abstract void slotsChanged(Container container);

    @Inject(method = "clickMenuButton", at = @At("HEAD"), cancellable = true)
    private void lightborn$payForEnchant(Player player, int slot, CallbackInfoReturnable<Boolean> result) {
        result.setReturnValue(false);
        if (slot < 0 || slot >= costs.length) return;
        ItemStack input = enchantSlots.getItem(0), lapis = enchantSlots.getItem(1);
        int price = Enchanting.lapis(slot);
        if (costs[slot] <= 0 || input.isEmpty()) return;
        if (!player.hasInfiniteMaterials() && (!lapis.is(Items.LAPIS_LAZULI) || lapis.getCount() < price)) return;
        access.execute((level, pos) -> {
            List<EnchantmentInstance> rolls = getEnchantmentList(level.registryAccess(), input, slot, costs[slot]);
            if (rolls.isEmpty()) return;
            player.onEnchantmentPerformed(input, 0);
            ItemStack enchanted = input.is(Items.BOOK) ? input.transmuteCopy(Items.ENCHANTED_BOOK) : input;
            if (enchanted != input) enchantSlots.setItem(0, enchanted);
            for (EnchantmentInstance roll : rolls) enchanted.enchant(roll.enchantment(), roll.level());
            lapis.consume(price, player);
            if (lapis.isEmpty()) enchantSlots.setItem(1, ItemStack.EMPTY);
            player.awardStat(Stats.ENCHANT_ITEM);
            if (player instanceof ServerPlayer sp) CriteriaTriggers.ENCHANTED_ITEM.trigger(sp, enchanted, slot + 1);
            enchantSlots.setChanged();
            enchantmentSeed.set(player.getEnchantmentSeed());
            slotsChanged(enchantSlots);
            level.playSound(
                    null,
                    pos,
                    SoundEvents.ENCHANTMENT_TABLE_USE,
                    SoundSource.BLOCKS,
                    1f,
                    level.getRandom().nextFloat() * .1f + .9f);
        });
        // Client menus have NULL access: approval still sends the normal button packet to the server.
        result.setReturnValue(true);
    }
}
