package lightborn.client.mixin;

import lightborn.DragonForm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class DragonFormBodyMixin {
    @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true)
    private void lightborn$onlyDragonBody(
            LivingEntityRenderState s,
            boolean visible,
            boolean translucent,
            boolean glowing,
            CallbackInfoReturnable<RenderType> result) {
        var l = Minecraft.getInstance().level;
        if (s instanceof AvatarRenderState a
                && l != null
                && l.getEntity(a.id) instanceof LivingEntity p
                && DragonForm.visual(p)) result.setReturnValue(null);
    }
}
