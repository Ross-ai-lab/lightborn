package lightborn.mixin;

import lightborn.LightWards;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class WardMovementMixin {
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3 lightborn$solidWard(Vec3 movement) {
        return LightWards.limitMovement((Entity) (Object) this, movement);
    }

    @Inject(method = "setPos(DDD)V", at = @At("HEAD"), cancellable = true)
    private void lightborn$teleport(double x, double y, double z, CallbackInfo ci) {
        var e = (Entity) (Object) this;
        if (e.tickCount <= 0) return;
        if (e instanceof Projectile projectile) {
            if (LightWards.intercept(projectile, new Vec3(x, y, z))) ci.cancel();
            return;
        }
        if (!(e instanceof Mob) || !LightWards.blocked(e)) return;
        var target = new Vec3(x, y, z);
        var safe = LightWards.limitDestination(e, target);
        // This position is already the result of vanilla's terrain collision. Rewriting it here could
        // bypass that collision and put the mob inside blocks on a hill.
        if (safe.distanceToSqr(target) > 1e-10) ci.cancel();
    }
}
