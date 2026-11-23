package lightborn.client.mixin;

import lightborn.client.UndeadEyes;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.*;

@Mixin(LivingEntityRenderState.class)
public abstract class UndeadEyesStateMixin implements UndeadEyes.State {
    @Unique
    private Identifier lightborn$eyeTexture;

    public Identifier lightborn$eyes() {
        return lightborn$eyeTexture;
    }

    public void lightborn$eyes(Identifier texture) {
        lightborn$eyeTexture = texture;
    }

    @Unique
    private boolean lightborn$isMinion;

    public boolean lightborn$minion() {
        return lightborn$isMinion;
    }

    public void lightborn$minion(boolean minion) {
        lightborn$isMinion = minion;
    }
}
