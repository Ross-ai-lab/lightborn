package lightborn.mixin;

import lightborn.LightWards;
import lightborn.WardCombat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Projectile.class)
abstract class WardProjectileMixin {
    @Inject(method = "setOwner(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void lightborn$rememberSanctuary(Entity owner, CallbackInfo ci) {
        var projectile = (Projectile) (Object) this;
        if (!projectile.level().isClientSide()) WardCombat.mark(projectile, owner);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void lightborn$stopAtWard(CallbackInfo ci) {
        var p = (Projectile) (Object) this;
        if (!p.level().isClientSide()) LightWards.intercept(p, p.position().add(p.getDeltaMovement()));
    }

    @Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
    private void lightborn$wardBeforeImpact(HitResult hit, CallbackInfoReturnable<ProjectileDeflection> result) {
        if (LightWards.intercept((Projectile) (Object) this, hit.getLocation()))
            result.setReturnValue(ProjectileDeflection.NONE);
    }
}
