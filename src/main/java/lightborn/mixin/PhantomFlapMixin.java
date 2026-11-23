package lightborn.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The Dark Phantom's wingbeat is its own (DarkPhantom). */
@Mixin(Phantom.class)
public abstract class PhantomFlapMixin {
    @ModifyArg(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;playLocalSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"),
            index = 1)
    private SoundEvent lightborn$darkFlap(SoundEvent sound) {
        return (Object) this instanceof lightborn.DarkPhantom ? lightborn.NightSounds.PHANTOM_FLAP : sound;
    }
}
