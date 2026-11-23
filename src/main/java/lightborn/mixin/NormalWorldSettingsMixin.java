package lightborn.mixin;

import net.minecraft.world.Difficulty;
import net.minecraft.world.level.LevelSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The canonical world-settings record is shared by creation, loading and difficulty changes. */
@Mixin(LevelSettings.DifficultySettings.class)
public abstract class NormalWorldSettingsMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static Difficulty lightborn$normal(Difficulty requested) {
        return Difficulty.NORMAL;
    }

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static boolean lightborn$notHardcore(boolean requested) {
        return false;
    }

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static boolean lightborn$locked(boolean requested) {
        return true;
    }
}
