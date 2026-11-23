package lightborn.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import lightborn.DarkGoo;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Striders take Dark Goo for lava (owner, 2026-11-20: "can walk on goo"): they stand and walk on it, are warm on it,
 * float up through it, seek it out and are born on the Dark's goo seas as on lava.
 */
@Mixin(Strider.class)
public abstract class StriderGooMixin {
    @ModifyReturnValue(method = "canStandOnFluid", at = @At("RETURN"))
    private boolean lightborn$standOnGoo(boolean stands, FluidState fluid) {
        return stands || fluid.is(DarkGoo.TAG);
    }

    @ModifyExpressionValue(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/monster/Strider;getFluidHeight(Lnet/minecraft/tags/TagKey;)D"))
    private double lightborn$warmInGoo(double lava) {
        return Math.max(lava, DarkGoo.depth((Strider) (Object) this));
    }

    @ModifyExpressionValue(
            method = {"floatStrider", "checkFallDamage"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Strider;isInLava()Z"))
    private boolean lightborn$gooIsLava(boolean inLava) {
        return inLava || DarkGoo.depth((Strider) (Object) this) > 0;
    }

    @WrapOperation(
            method = {"floatStrider", "getWalkTargetValue"},
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean lightborn$gooCountsAsLava(FluidState fluid, TagKey<Fluid> tag, Operation<Boolean> op) {
        return lava(fluid, tag, op);
    }

    @WrapOperation(
            method = "checkStriderSpawnRules",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z"))
    private static boolean lightborn$gooSpawnsLikeLava(FluidState fluid, TagKey<Fluid> tag, Operation<Boolean> op) {
        return lava(fluid, tag, op);
    }

    private static boolean lava(FluidState fluid, TagKey<Fluid> tag, Operation<Boolean> op) {
        return op.call(fluid, tag) || tag == FluidTags.LAVA && fluid.is(DarkGoo.TAG);
    }

    /** Its goal of going back to lava looks for goo too. */
    @Mixin(targets = "net.minecraft.world.entity.monster.Strider$StriderGoToLavaGoal")
    public abstract static class Seek {
        @WrapOperation(
                method = "isValidTarget",
                at =
                        @At(
                                value = "INVOKE",
                                target = "Lnet/minecraft/world/level/block/state/BlockState;is(Ljava/lang/Object;)Z"))
        private boolean lightborn$gooTarget(BlockState state, Object block, Operation<Boolean> op) {
            return op.call(state, block) || block == Blocks.LAVA && state.is(DarkGoo.BLOCK);
        }
    }

    /** Its paths may end on goo as on lava. */
    @Mixin(targets = "net.minecraft.world.entity.monster.Strider$StriderPathNavigation")
    public abstract static class Path {
        @WrapOperation(
                method = "isStableDestination",
                at =
                        @At(
                                value = "INVOKE",
                                target = "Lnet/minecraft/world/level/block/state/BlockState;is(Ljava/lang/Object;)Z"))
        private boolean lightborn$gooGround(BlockState state, Object block, Operation<Boolean> op) {
            return op.call(state, block) || block == Blocks.LAVA && state.is(DarkGoo.BLOCK);
        }
    }

    /** Striders are born on goo as on lava. */
    @Mixin(SpawnPlacements.class)
    public abstract static class Birth {
        @Inject(method = "isSpawnPositionOk", at = @At("HEAD"), cancellable = true)
        private static void lightborn$onGoo(
                EntityType<?> type, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> result) {
            if (type == EntityTypes.STRIDER && level.getFluidState(pos).is(DarkGoo.TAG)) result.setReturnValue(true);
        }
    }
}
