package lightborn.client.mixin;

import lightborn.ChestTravel;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public abstract class DragonFlightFogMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void lightborn$lightThroughStone(
            Camera camera,
            int distance,
            DeltaTracker delta,
            float darken,
            ClientLevel level,
            CallbackInfoReturnable<FogData> result) {
        var p = Minecraft.getInstance().player;
        if (p == null || !ChestTravel.flying(p) || level.dimension() != Level.NETHER) return;
        boolean buried = level.getBlockState(camera.blockPosition()).isSolidRender()
                || level.getBlockState(p.blockPosition()).isSolidRender();
        if (!buried) return;
        var fog = result.getReturnValue();
        fog.color.set(.78f, .7f, .48f, 1);
        fog.environmentalStart = 3;
        fog.environmentalEnd = 18;
        fog.renderDistanceStart = 3;
        fog.renderDistanceEnd = 18;
        fog.skyEnd = fog.cloudEnd = 18;
    }
}
