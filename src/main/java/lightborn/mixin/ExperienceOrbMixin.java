package lightborn.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * There is no experience in Lightborn (owner, 2026-11-16: a Sparks-and-gear economy, no levels). Kills, smelting,
 * trades, bottles and the dragon award nothing, and any orb a world still holds from before disappears.
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private static void lightborn$noOrbs(ServerLevel level, Vec3 pos, int amount, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "awardWithDirection", at = @At("HEAD"), cancellable = true)
    private static void lightborn$noAimedOrbs(
            ServerLevel level, Vec3 pos, Vec3 direction, int amount, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void lightborn$fadeOldOrb(CallbackInfo ci) {
        ExperienceOrb orb = (ExperienceOrb) (Object) this;
        if (!orb.level().isClientSide()) orb.discard();
        ci.cancel();
    }
}
