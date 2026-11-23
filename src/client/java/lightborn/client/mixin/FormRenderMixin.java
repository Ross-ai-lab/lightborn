package lightborn.client.mixin;

import lightborn.client.FormView;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A player in a beast form is drawn as the beast: in the pass that draws the world's entities, its stand-in's render
 * state is taken in its place. Only that pass: the local player's own state (first-person hands and the like) must stay
 * a player's, or the game stops with "Expected an AvatarRenderState for the local player".
 */
@Mixin(LevelExtractor.class)
public abstract class FormRenderMixin {
    @ModifyArg(
            method = "extractVisibleEntities",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"),
            index = 0)
    private Entity lightborn$drawTheBeast(Entity entity) {
        Entity beast = FormView.standIn(entity);
        return beast != null ? beast : entity;
    }
}
