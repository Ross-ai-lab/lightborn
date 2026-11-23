package lightborn.mixin;

import java.util.List;
import lightborn.LightWards;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
    private void lightborn$protectWard(CallbackInfoReturnable<List<BlockPos>> result) {
        LightWards.protectBlast((ServerExplosion) (Object) this, result.getReturnValue());
    }
}
