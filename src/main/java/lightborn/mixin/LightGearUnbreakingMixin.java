package lightborn.mixin;

import java.util.function.Consumer;
import lightborn.LightGear;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Dragon's Light gear never vanishes (owner, 2026-11-20), nor does any gear upgraded at the Chest of Light (owner,
 * 2026-11-23): worn to its last point it breaks but stays, at full damage, until an anvil mends it (glowstone dust for
 * Light gear, the piece's own material for the rest). Vanilla already drops a broken worn piece's stats and a broken
 * glider's flight; {@link LightGear#broken} covers the rest (perks, immunities, the staff, the shield).
 */
@Mixin(ItemStack.class)
public abstract class LightGearUnbreakingMixin {
    @Inject(method = "applyDamage", at = @At("HEAD"), cancellable = true)
    private void lightborn$breakButStay(int damage, ServerPlayer player, Consumer<ItemStack> onBreak, CallbackInfo ci) {
        var self = (ItemStack) (Object) this;
        if (!LightGear.keeps(self) || damage < self.getMaxDamage()) return;
        ci.cancel();
        if (self.isBroken()) return;
        self.setDamageValue(self.getMaxDamage());
        LightGear.hideStatsWhileBroken(self);
        onBreak.accept(self.copy()); // the break sound and particles, nothing taken away
    }

    /** A broken tool digs no faster than a bare hand until mended. */
    @Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
    private void lightborn$brokenDigsSlow(
            net.minecraft.world.level.block.state.BlockState state,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        if (LightGear.broken((ItemStack) (Object) this)) cir.setReturnValue(1f);
    }

    /** A broken bow, crossbow, trident or shield cannot be drawn, thrown or raised until mended. */
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void lightborn$brokenUnused(
            net.minecraft.world.level.Level level,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.InteractionResult>
                    cir) {
        if (LightGear.broken((ItemStack) (Object) this)) cir.setReturnValue(net.minecraft.world.InteractionResult.FAIL);
    }
}
