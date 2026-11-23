package lightborn.client.mixin;

import lightborn.client.DragonTransitView;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sodium draws terrain through its own path instead of ChunkSectionsToRender. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class DragonFlightSodiumTerrainMixin {
    @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true, remap = false)
    private void lightborn$lightPassage(CallbackInfo ci) {
        if (DragonTransitView.phasing()) ci.cancel();
    }
}
