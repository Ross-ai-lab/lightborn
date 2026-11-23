package lightborn.mixin;

import lightborn.Fishing;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A fish on the line when the rod is reeled in (the bobber bit, nothing hooked) also brings up Lightborn's extras
 * (owner, 2026-11-22: fishing more rewarding). The vanilla catch is untouched.
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Shadow
    private int nibble;

    @Shadow
    private Entity hookedIn;

    @Shadow
    @Final
    private int luck;

    @Inject(method = "retrieve", at = @At("HEAD"))
    private void lightborn$extras(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
        FishingHook self = (FishingHook) (Object) this;
        if (self.level().isClientSide() || hookedIn != null || nibble <= 0) return;
        if (self.getPlayerOwner() instanceof ServerPlayer p && p.isAlive() && p.distanceToSqr(self) < 32 * 32)
            Fishing.caught(p, self.position(), luck);
    }
}
