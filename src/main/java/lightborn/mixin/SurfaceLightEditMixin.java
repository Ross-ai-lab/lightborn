package lightborn.mixin;

import lightborn.SurfaceLights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class SurfaceLightEditMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void lightborn$roofChanged(
            BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> result) {
        if (result.getReturnValue() != null)
            SurfaceLights.changed((LevelChunk) (Object) this, pos, state, result.getReturnValue());
    }

    @Mixin(BlockItem.class)
    abstract static class Placement {
        @Inject(method = "placeBlock", at = @At("RETURN"))
        private void lightborn$builtRoof(
                BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> result) {
            if (result.getReturnValueZ()) SurfaceLights.placed(context.getLevel(), context.getClickedPos());
        }
    }
}
