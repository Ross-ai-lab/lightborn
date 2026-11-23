package lightborn.client.mixin;

import lightborn.client.MusicDirector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MusicManager.class)
public abstract class SituationalMusicMixin {
    @Shadow
    private SoundInstance currentMusic;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void lightborn$situationalBackground(CallbackInfo ci) {
        if (MusicDirector.overridesVanilla()) {
            if (currentMusic != null) {
                Minecraft.getInstance().getSoundManager().stop(currentMusic);
                currentMusic = null;
            }
            ci.cancel();
        }
    }
}
