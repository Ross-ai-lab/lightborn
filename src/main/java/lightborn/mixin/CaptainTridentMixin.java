package lightborn.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import lightborn.DrownedCaptain;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ThrownTrident.class)
public abstract class CaptainTridentMixin {
    @WrapOperation(
            method = "onHitEntity",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/Entity;hurtOrSimulate(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean lightborn$reelIn(Entity victim, DamageSource source, float damage, Operation<Boolean> original) {
        ThrownTrident trident = (ThrownTrident) (Object) this;
        boolean hit = original.call(victim, source, damage);
        if (hit && !trident.level().isClientSide() && trident.getOwner() instanceof DrownedCaptain captain)
            DrownedCaptain.pull(victim, captain);
        return hit;
    }
}
