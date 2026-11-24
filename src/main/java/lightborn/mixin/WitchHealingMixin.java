package lightborn.mixin;

import net.minecraft.world.entity.monster.Witch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Witch.class)
abstract class WitchHealingMixin {
    @Redirect(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Witch;getHealth()F"))
    private float lightborn$skipSelfHealing(Witch witch) {
        return witch.getMaxHealth();
    }
}
