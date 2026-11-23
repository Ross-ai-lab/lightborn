package lightborn.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import lightborn.LightMaps;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
abstract class LightMapMixin {
    @ModifyVariable(method = "renderMap", at = @At("HEAD"), argsOnly = true)
    private int lightborn$selfLit(
            int light,
            PoseStack pose,
            SubmitNodeCollector collector,
            int original,
            ItemStack stack,
            boolean active,
            FirstPersonHandsAndItemsRenderState state) {
        return LightMaps.isLightMap(stack) ? 15728880 : light;
    }
}
