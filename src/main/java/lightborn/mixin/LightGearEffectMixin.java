package lightborn.mixin;

import lightborn.LightGear;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LightGearEffectMixin {
    @Inject(method = "canBeAffected", at = @At("HEAD"), cancellable = true)
    private void lightborn$lightImmunity(MobEffectInstance effect, CallbackInfoReturnable<Boolean> result) {
        if (LightGear.immune((LivingEntity) (Object) this, effect.getEffect())) result.setReturnValue(false);
    }
}
