package lightborn.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Finishes a brew at once, for the self-test of Night Vision's Spark price. */
@Mixin(BrewingStandBlockEntity.class)
public interface BrewingStandInvoker {
    @Invoker("doBrew")
    static void lightborn$doBrew(ServerLevel level, BlockPos pos, BrewingStandBlockEntity stand) {
        throw new AssertionError();
    }
}
