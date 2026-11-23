package lightborn;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Verdant Surge takes the same fertility path as a normal crop tick, including greenhouse protection. */
final class SeasonCompat {
    private static java.lang.reflect.Method growth;
    private static boolean checked;

    static boolean allows(ServerLevel l, BlockPos pos, BlockState state) {
        if (!checked) {
            checked = true;
            if (FabricLoader.getInstance().isModLoaded("sereneseasons"))
                try {
                    growth = Class.forName("sereneseasons.season.SeasonalCropGrowthHandler")
                            .getMethod(
                                    "onCropGrowth", Level.class, BlockPos.class, BlockState.class, CallbackInfo.class);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Serene Seasons fertility API changed", e);
                }
        }
        if (growth == null) return true;
        var callback = new CallbackInfo("lightborn_verdant_growth", true);
        try {
            growth.invoke(null, l, pos, state, callback);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Seasonal fertility check failed", e);
        }
        return !callback.isCancelled();
    }
}
