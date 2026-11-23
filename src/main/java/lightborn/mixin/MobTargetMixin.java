package lightborn.mixin;

import lightborn.BossTrophies;
import lightborn.Monsters;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
abstract class MobTargetMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void lightborn$eclipseTarget(LivingEntity target, CallbackInfo ci) {
        var self = (Mob) (Object) this;
        if (Monsters.isMonster(self) && BossTrophies.concealed(target)) ci.cancel();
        // A monster prowling round a ward cannot take the one inside again until its prowl ends (owner, 2026-11-23).
        else if (target != null
                && lightborn.LightWards.prowling(self)
                && target.level() instanceof net.minecraft.server.level.ServerLevel l
                && lightborn.LightWards.contains(l, target.getBoundingBox().getCenter())) ci.cancel();
    }
}
