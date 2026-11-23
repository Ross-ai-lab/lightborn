package lightborn.client.mixin;

import lightborn.ModData;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minions are drawn lit from within (full block light, day or night), so the player's own monsters never pass for
 * foes (owner, 2026-11-16: Light, no names). The warm tint is {@link MinionTintMixin}.
 */
@Mixin(EntityRenderer.class)
public abstract class MinionLightMixin {
    @Inject(method = "getBlockLightLevel", at = @At("HEAD"), cancellable = true)
    private void lightborn$minionLight(Entity e, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (Boolean.TRUE.equals(e.getAttached(ModData.MINION))) cir.setReturnValue(15);
    }
}
