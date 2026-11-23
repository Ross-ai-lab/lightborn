package lightborn.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(MinecraftServer.class)
public abstract class NormalDifficultyMixin {
    @ModifyVariable(method = "setDifficulty", at = @At("HEAD"), argsOnly = true)
    private Difficulty lightborn$normal(Difficulty requested) {
        return Difficulty.NORMAL;
    }

    @ModifyVariable(method = "setDifficultyLocked", at = @At("HEAD"), argsOnly = true)
    private boolean lightborn$keepLocked(boolean requested) {
        return true;
    }
}
