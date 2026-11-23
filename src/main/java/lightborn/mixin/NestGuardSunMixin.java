package lightborn.mixin;

import lightborn.DarkNests;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Dark Nest's day guard, of whatever kind, never burns in the sun ({@link DarkNests#GUARD_TAG}). */
@Mixin(Mob.class)
abstract class NestGuardSunMixin {
    @Inject(method = "isSunBurnTick", at = @At("HEAD"), cancellable = true)
    private void lightborn$guardsSunproof(CallbackInfoReturnable<Boolean> cir) {
        if (((Mob) (Object) this).entityTags().contains(DarkNests.GUARD_TAG)) cir.setReturnValue(false);
    }
}
