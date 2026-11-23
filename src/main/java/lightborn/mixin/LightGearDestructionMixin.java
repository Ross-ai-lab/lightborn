package lightborn.mixin;

import lightborn.LightGear;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
public abstract class LightGearDestructionMixin {
    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void lightborn$lostGift(
            ServerLevel l, DamageSource source, float amount, CallbackInfoReturnable<Boolean> result) {
        var self = (ItemEntity) (Object) this;
        if (self.isRemoved()) LightGear.destroyed(self);
    }
}
