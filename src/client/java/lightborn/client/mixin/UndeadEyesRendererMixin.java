package lightborn.client.mixin;

import lightborn.client.UndeadEyes;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class UndeadEyesRendererMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "<init>", at = @At("TAIL"))
    private void lightborn$addEyes(CallbackInfo ci) {
        ((Accessor) (Object) this).lightborn$addLayer(new UndeadEyes.Layer((LivingEntityRenderer) (Object) this));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(
            method =
                    "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void lightborn$eyeState(LivingEntity e, LivingEntityRenderState state, float partial, CallbackInfo ci) {
        var renderer = (LivingEntityRenderer) (Object) this;
        boolean show = UndeadEyes.family(e) && UndeadEyes.lit(e.level());
        ((UndeadEyes.State) state)
                .lightborn$eyes(
                        show ? UndeadEyes.texture(e, renderer.getTextureLocation(state), renderer.getModel()) : null);
    }

    @Mixin(LivingEntityRenderer.class)
    public interface Accessor {
        @Invoker("addLayer")
        boolean lightborn$addLayer(RenderLayer<?, ?> layer);
    }
}
