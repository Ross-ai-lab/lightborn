package lightborn.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import lightborn.client.OwlSightView;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Owl Sight's glow: a monster on this player's list draws its outline through walls ({@link OwlSightView}). */
@Mixin(Minecraft.class)
public abstract class OwlSightMixin {
    @ModifyReturnValue(method = "shouldEntityAppearGlowing", at = @At("RETURN"))
    private boolean lightborn$owlSight(boolean glowing, Entity entity) {
        return glowing || OwlSightView.glows(entity);
    }
}
