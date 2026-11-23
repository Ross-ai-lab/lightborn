package lightborn.client.mixin;

import lightborn.client.DragonTransitView;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Solid rock becomes a light passage during Nether phasing; no world blocks are changed. */
@Mixin(ChunkSectionsToRender.class)
public abstract class DragonFlightTerrainMixin {
    @Inject(
            method = {"renderGroup", "renderOit"},
            at = @At("HEAD"),
            cancellable = true)
    private void lightborn$throughTheLight(CallbackInfo ci) {
        if (DragonTransitView.phasing()) ci.cancel();
    }
}
