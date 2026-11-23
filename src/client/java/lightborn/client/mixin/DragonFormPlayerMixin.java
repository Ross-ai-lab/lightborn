package lightborn.client.mixin;

import lightborn.DragonForm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(AvatarRenderer.class)
public abstract class DragonFormPlayerMixin {
    private boolean lightborn$form(AvatarRenderState s) {
        var l = Minecraft.getInstance().level;
        return l != null && l.getEntity(s.id) instanceof LivingEntity e && DragonForm.visual(e);
    }

    @Inject(
            method = "shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void lightborn$noHumanArmour(AvatarRenderState s, CallbackInfoReturnable<Boolean> result) {
        if (lightborn$form(s)) result.setReturnValue(false);
    }
}
