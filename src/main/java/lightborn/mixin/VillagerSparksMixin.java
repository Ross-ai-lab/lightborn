package lightborn.mixin;

import java.util.Optional;
import lightborn.ModItems;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Villagers and wandering traders deal in Sparks (owner, 2026-11-17): every emerald a trade asks or pays becomes the
 * same number of Sparks. Offers are rewritten as they are read, so villagers from before the rule change over too.
 */
@Mixin(AbstractVillager.class)
public abstract class VillagerSparksMixin {
    @Inject(method = "getOffers", at = @At("RETURN"))
    private void lightborn$sparks(CallbackInfoReturnable<MerchantOffers> result) {
        MerchantOffers offers = result.getReturnValue();
        if (offers == null || ModItems.SPARK == null) return;
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer o = offers.get(i);
            boolean a = emerald(o.getItemCostA());
            boolean b = o.getItemCostB().map(VillagerSparksMixin::emerald).orElse(false);
            boolean r = o.getResult().is(Items.EMERALD);
            if (!a && !b && !r) continue;
            ItemCost costA = a ? new ItemCost(ModItems.SPARK, o.getItemCostA().count()) : o.getItemCostA();
            Optional<ItemCost> costB =
                    b ? o.getItemCostB().map(c -> new ItemCost(ModItems.SPARK, c.count())) : o.getItemCostB();
            ItemStack out = r ? new ItemStack(ModItems.SPARK, o.getResult().getCount()) : o.getResult();
            var swapped = new MerchantOffer(
                    costA, costB, out, o.getUses(), o.getMaxUses(), o.getXp(), o.getPriceMultiplier(), o.getDemand());
            swapped.setSpecialPriceDiff(o.getSpecialPriceDiff());
            offers.set(i, swapped);
        }
    }

    private static boolean emerald(ItemCost cost) {
        return cost.item().value() == Items.EMERALD;
    }
}
