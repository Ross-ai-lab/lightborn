package lightborn.mixin;

import lightborn.BossTrophies;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class WebMovementMixin {
    @Inject(method = "makeStuckInBlock", at = @At("HEAD"), cancellable = true)
    private void lightborn$webWalker(BlockState state, Vec3 multiplier, CallbackInfo ci) {
        if (state.is(Blocks.COBWEB) && BossTrophies.webWalker((Entity) (Object) this)) ci.cancel();
    }
}
