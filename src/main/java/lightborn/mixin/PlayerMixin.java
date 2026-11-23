package lightborn.mixin;

import lightborn.ChestTravel;
import lightborn.ModAttributes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives every player the mod's attributes (spell power, mana, ...) and switches vanilla experience off: nothing
 * grants points or levels, and nothing is spent or dropped.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "canDropItems", at = @At("HEAD"), cancellable = true)
    private void lightborn$noDropsInFlight(CallbackInfoReturnable<Boolean> cir) {
        if (ChestTravel.flying((Player) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "createAttributes", at = @At("RETURN"))
    private static void lightborn$addAttributes(CallbackInfoReturnable<AttributeSupplier.Builder> cir) {
        AttributeSupplier.Builder builder = cir.getReturnValue();
        for (var attribute : ModAttributes.ALL) {
            builder.add(attribute);
        }
    }

    /** No experience exists (owner, 2026-11-16): nothing grants points or levels, and /xp does nothing. */
    @Inject(method = "giveExperiencePoints", at = @At("HEAD"), cancellable = true)
    private void lightborn$noXp(int points, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "giveExperienceLevels", at = @At("HEAD"), cancellable = true)
    private void lightborn$noLevels(int levels, CallbackInfo ci) {
        ci.cancel();
    }

    /** Enchanting is paid in lapis and spends no levels (the enchantment seed still rerolls). */
    @ModifyVariable(method = "onEnchantmentPerformed", at = @At("HEAD"), argsOnly = true)
    private int lightborn$freeEnchanting(int levels) {
        return 0;
    }

    @Inject(method = "getBaseExperienceReward", at = @At("HEAD"), cancellable = true)
    private void lightborn$noDeathXp(ServerLevel level, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(0);
    }
}
