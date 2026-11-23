package lightborn.mixin;

import lightborn.SurfaceLights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(BlockLightEngine.class)
abstract class SurfaceBlockLightMixin {
    @Inject(method = "getEmission", at = @At("RETURN"), cancellable = true)
    private void lightborn$nightSource(long pos, BlockState state, CallbackInfoReturnable<Integer> result) {
        if (result.getReturnValueI() > 0
                && SurfaceLights.suppress(((Access) this).lightborn$chunks(), BlockPos.of(pos), state))
            result.setReturnValue(0);
    }

    @Inject(method = "lambda$propagateLightSources$0", at = @At("HEAD"), cancellable = true)
    private void lightborn$initialNightSource(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (SurfaceLights.suppress(((Access) this).lightborn$chunks(), pos, state)) ci.cancel();
    }

    @Mixin(LightEngine.class)
    interface Access {
        @Accessor("chunkSource")
        LightChunkGetter lightborn$chunks();
    }
}
