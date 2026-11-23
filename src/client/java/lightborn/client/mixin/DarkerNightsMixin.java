package lightborn.client.mixin;

import lightborn.Balance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reduce night skylight and ambient fill while preserving torch/chest block lighting; never once the Dragon is a
 * friend ({@link lightborn.DragonStory#nightLifted}: nights are lit as in an ordinary game, owner 2026-11-19).
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class DarkerNightsMixin {
    @Inject(method = "extract", at = @At("RETURN"))
    private void lightborn$nightAmbient(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        if (!state.needsUpdate) return;
        var level = Minecraft.getInstance().level;
        if (level == null || level.dimension() != Level.OVERWORLD || lightborn.DragonStory.nightLifted(level)) return;
        long time = Math.floorMod(level.getOverworldClockTime(), 24000);
        // Ease over dusk and dawn so there is no brightness jump at the night boundary.
        float night = Math.min(Math.clamp((time - 12000) / 1000f, 0, 1), Math.clamp((24000 - time) / 1000f, 0, 1));
        if (night <= 0) return;
        float factor = 1 - night * (1 - Balance.NIGHT_AMBIENT_MULTIPLIER);
        state.skyFactor *= factor;
        state.ambientColor = new Vector3f(state.ambientColor).mul(factor);
    }
}
