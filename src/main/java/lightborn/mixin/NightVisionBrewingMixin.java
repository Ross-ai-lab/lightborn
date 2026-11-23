package lightborn.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.BrewingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Night Vision costs one reagent per bottle (owner, 2026-11-23: "1 per potion"; then "a golden apple to brew, and golden
 * carrot to extend": the recipes in {@code data/minecraft/recipe/brewing} put a golden apple where the golden carrot was
 * and a golden carrot where the redstone was). Vanilla spends one reagent on up to three bottles, so such a brew first
 * spends one more for each further bottle it turns, and bottles beyond the reagents in the slot are set aside untouched
 * until the brew is done. Neither reagent brews anything else here.
 */
@Mixin(BrewingStandBlockEntity.class)
public abstract class NightVisionBrewingMixin {
    @Unique
    private static final ThreadLocal<ItemStack[]> lightborn$heldBack = new ThreadLocal<>();

    @Inject(method = "doBrew", at = @At("HEAD"))
    private static void lightborn$sparkPerBottle(
            ServerLevel level, BlockPos pos, BrewingStandBlockEntity stand, CallbackInfo ci) {
        lightborn$heldBack.remove();
        ItemStack reagent = stand.getItem(3);
        if (!reagent.is(Items.GOLDEN_APPLE) && !reagent.is(Items.GOLDEN_CARROT)) return;
        int sparks = reagent.getCount(), brewed = 0;
        ItemStack[] held = new ItemStack[3];
        for (int i = 0; i < 3; i++) {
            ItemStack bottle = stand.getItem(i);
            if (bottle.isEmpty()
                    || level.recipeAccess()
                            .getRecipeFor(RecipeType.BREWING, new BrewingInput(bottle, reagent), level)
                            .isEmpty()) continue;
            if (brewed < sparks) brewed++;
            else {
                held[i] = bottle;
                stand.setItem(i, ItemStack.EMPTY);
            }
        }
        // Vanilla takes the last one itself.
        if (brewed > 1) reagent.shrink(brewed - 1);
        lightborn$heldBack.set(held);
    }

    @Inject(method = "doBrew", at = @At("RETURN"))
    private static void lightborn$giveBack(
            ServerLevel level, BlockPos pos, BrewingStandBlockEntity stand, CallbackInfo ci) {
        ItemStack[] held = lightborn$heldBack.get();
        lightborn$heldBack.remove();
        if (held == null) return;
        for (int i = 0; i < 3; i++) if (held[i] != null) stand.setItem(i, held[i]);
    }
}
