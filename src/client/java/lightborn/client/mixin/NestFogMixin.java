package lightborn.client.mixin;

import lightborn.DarkLand;
import lightborn.DarkNests;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dark fog over a nest's land (owner, 2026-11-17: "like dark fog is covering the area"). Inside the dark round a nest
 * the fog closes in and turns near black, thickest at the core and thinning out past the dark's edge; it eases in and
 * out over a couple of seconds so walking in never pops.
 */
@Mixin(FogRenderer.class)
public abstract class NestFogMixin {
    @Unique
    private static float lightborn$fog;

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void lightborn$nestFog(
            Camera camera,
            int distance,
            DeltaTracker delta,
            float darken,
            ClientLevel level,
            CallbackInfoReturnable<FogData> result) {
        var mc = Minecraft.getInstance();
        var p = mc.player;
        // Eyes in Dark Goo (owner, 2026-11-20): black, a block and a half of sight, as thick as lava.
        var eye = camera.position();
        var eyePos = net.minecraft.core.BlockPos.containing(eye);
        var fluid = level.getFluidState(eyePos);
        if (fluid.is(lightborn.DarkGoo.TAG) && eye.y <= eyePos.getY() + fluid.getHeight(level, eyePos)) {
            var fog = result.getReturnValue();
            fog.color.set(.03f, .01f, .05f, fog.color.w);
            fog.environmentalStart = 0;
            fog.environmentalEnd = 1.5f;
            fog.renderDistanceStart = 0;
            fog.renderDistanceEnd = 1.5f;
            fog.skyEnd = 1.5f;
            fog.cloudEnd = 1.5f;
            return;
        }
        if (p != null && p.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION)) {
            lightborn$fog = 0;
            return;
        }
        float target = 0;
        if (p != null) {
            var nests = p.getAttached(DarkNests.SMOKE);
            if (nests != null) {
                for (var core : nests) {
                    var state = level.getBlockState(core);
                    int stage = state.is(DarkNests.NEST) ? state.getValue(DarkNests.STAGE) : 2;
                    double reach = DarkLand.darkRadius(stage);
                    double dx = core.getX() + .5 - eye.x, dz = core.getZ() + .5 - eye.z;
                    double d = Math.sqrt(dx * dx + dz * dz);
                    // Full inside two thirds of the dark, fading to nothing six blocks past its edge.
                    float f = (float) Math.clamp((reach + 6 - d) / (reach * .34 + 6), 0, 1);
                    target = Math.max(target, f);
                }
            }
        }
        lightborn$fog += (target - lightborn$fog) * .03f;
        float f = lightborn$fog;
        if (f < .01f) return;
        float s = .45f * f * f * (3 - 2 * f);
        var fog = result.getReturnValue();
        fog.color.set(
                fog.color.x + (.05f - fog.color.x) * s,
                fog.color.y + (.03f - fog.color.y) * s,
                fog.color.z + (.07f - fog.color.z) * s,
                fog.color.w);
        fog.environmentalStart = Math.min(fog.environmentalStart, lerp(s, fog.environmentalStart, 1));
        fog.environmentalEnd = Math.min(fog.environmentalEnd, lerp(s, fog.environmentalEnd, 38));
        fog.renderDistanceStart = Math.min(fog.renderDistanceStart, lerp(s, fog.renderDistanceStart, 1));
        fog.renderDistanceEnd = Math.min(fog.renderDistanceEnd, lerp(s, fog.renderDistanceEnd, 38));
        fog.skyEnd = Math.min(fog.skyEnd, lerp(s, fog.skyEnd, 30));
        fog.cloudEnd = Math.min(fog.cloudEnd, lerp(s, fog.cloudEnd, 30));
    }

    @Unique
    private static float lerp(float t, float a, float b) {
        return a + (b - a) * t;
    }
}
