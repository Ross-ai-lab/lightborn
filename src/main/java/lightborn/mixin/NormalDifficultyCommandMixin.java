package lightborn.mixin;

import net.minecraft.server.commands.DifficultyCommand;
import net.minecraft.world.Difficulty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(DifficultyCommand.class)
public abstract class NormalDifficultyCommandMixin {
    @ModifyVariable(method = "setDifficulty", at = @At("HEAD"), argsOnly = true)
    private static Difficulty lightborn$normal(Difficulty requested) {
        return Difficulty.NORMAL;
    }
}
