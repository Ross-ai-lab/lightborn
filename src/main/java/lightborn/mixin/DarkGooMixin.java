package lightborn.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import lightborn.DarkGoo;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dark Goo's hold on living things ({@link DarkGoo}): a player sinks in it, cannot swim up and drowns when their air
 * runs out; a monster moves in it as in water and never drowns. Nothing takes fall damage landing in it.
 */
@Mixin(LivingEntity.class)
public abstract class DarkGooMixin {
    @Inject(method = "baseTick", at = @At("HEAD"))
    private void lightborn$softLanding(CallbackInfo ci) {
        var self = (LivingEntity) (Object) this;
        if (DarkGoo.depth(self) > 0) self.resetFallDistance();
    }

    @Inject(method = "travel", at = @At("HEAD"))
    private void lightborn$sink(Vec3 input, CallbackInfo ci) {
        var self = (LivingEntity) (Object) this;
        if (DarkGoo.sinks(self)) DarkGoo.sink(self);
    }

    /** A monster in goo travels as it would in water. */
    @ModifyReturnValue(method = "shouldTravelInFluid", at = @At("RETURN"))
    private boolean lightborn$swimInGoo(boolean travels, FluidState fluid) {
        var self = (LivingEntity) (Object) this;
        return travels || DarkGoo.swimsIn(self) && self.isAffectedByFluids() && !self.canStandOnFluid(fluid);
    }

    /** ...and swims up out of it as out of water. */
    @ModifyExpressionValue(
            method = {"travelInFluid", "aiStep"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isInWater()Z"))
    private boolean lightborn$gooIsWater(boolean inWater) {
        return inWater || DarkGoo.swimsIn((LivingEntity) (Object) this);
    }

    @ModifyExpressionValue(
            method = "aiStep",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/LivingEntity;getFluidHeight(Lnet/minecraft/tags/TagKey;)D"))
    private double lightborn$gooHeight(double height) {
        var self = (LivingEntity) (Object) this;
        return DarkGoo.swimsIn(self) ? Math.max(height, DarkGoo.depth(self)) : height;
    }

    /** A player with their eyes in goo uses up their air as under water (Water Breathing still helps). */
    @ModifyExpressionValue(
            method = "baseTick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/LivingEntity;isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean lightborn$drownInGoo(boolean eyeInWater) {
        var self = (LivingEntity) (Object) this;
        return eyeInWater || self instanceof Player && self.isEyeInFluid(DarkGoo.TAG);
    }
}
