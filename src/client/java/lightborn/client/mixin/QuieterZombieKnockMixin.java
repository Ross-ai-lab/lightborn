package lightborn.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scale final gain after vanilla clamping, retaining the original pitch, cadence and attenuation range. */
@Mixin(SoundEngine.class)
public abstract class QuieterZombieKnockMixin {
    private static float lightborn$knockGain(float volume, SoundInstance sound) {
        String id = sound.getIdentifier().getPath();
        return id.equals("entity.zombie.attack_wooden_door") || id.equals("entity.zombie.attack_iron_door")
                ? volume / 3f
                : volume;
    }

    @ModifyExpressionValue(
            method = "play",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"))
    private float lightborn$initialKnock(float volume, SoundInstance sound) {
        return lightborn$knockGain(volume, sound);
    }

    @Inject(
            method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
            at = @At("RETURN"),
            cancellable = true)
    private void lightborn$updatedKnock(SoundInstance sound, CallbackInfoReturnable<Float> result) {
        float before = result.getReturnValue(), after = lightborn$knockGain(before, sound);
        if (after != before) result.setReturnValue(after);
    }
}
