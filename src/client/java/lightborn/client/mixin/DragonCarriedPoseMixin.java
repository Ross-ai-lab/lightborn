package lightborn.client.mixin;

import lightborn.ChestTravel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class DragonCarriedPoseMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("RETURN"))
    private void lightborn$hangFromClaws(HumanoidRenderState state, CallbackInfo ci) {
        var l = Minecraft.getInstance().level;
        if (!(state instanceof AvatarRenderState s)
                || l == null
                || !(l.getEntity(s.id) instanceof LivingEntity p)
                || !ChestTravel.flying(p)
                || ChestTravel.transformed(p)) return;
        var m = (HumanoidModel<?>) (Object) this;
        m.leftLeg.xRot = m.rightLeg.xRot = 0;
        m.leftLeg.yRot = m.rightLeg.yRot = 0;
        m.leftLeg.zRot = .025f;
        m.rightLeg.zRot = -.025f;
        m.leftArm.xRot = m.rightArm.xRot = -2.55f;
        m.leftArm.zRot = -.18f;
        m.rightArm.zRot = .18f;
    }
}
