package lightborn.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import lightborn.ChestStations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractFurnaceBlockEntity.class)
abstract class ChestFurnaceMixin {
    @WrapOperation(
            method = "serverTick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private static boolean lightborn$keepChestBlock(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            Operation<Boolean> original,
            ServerLevel tickLevel,
            BlockPos tickPos,
            BlockState tickState,
            AbstractFurnaceBlockEntity furnace) {
        return furnace instanceof ChestStations.Furnace || original.call(level, pos, state);
    }

    @WrapOperation(
            method = "serverTick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;setChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private static void lightborn$saveChestFurnace(
            net.minecraft.world.level.Level level,
            BlockPos pos,
            BlockState state,
            Operation<Void> original,
            ServerLevel tickLevel,
            BlockPos tickPos,
            BlockState tickState,
            AbstractFurnaceBlockEntity furnace) {
        if (furnace instanceof ChestStations.Furnace) furnace.setChanged();
        else original.call(level, pos, state);
    }
}
