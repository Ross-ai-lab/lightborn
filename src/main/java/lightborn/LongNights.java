package lightborn;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.WorldClocks;

/** Restore the normal rate if this save was last closed during an extended night. */
final class LongNights {
    private LongNights() {}

    static void tick(MinecraftServer server) {
        var level = server.overworld();
        var clock = level.registryAccess().getOrThrow(WorldClocks.OVERWORLD);
        if (level.clockManager().getInstance(clock).rate() != 1f)
            level.clockManager().setRate(clock, 1f);
    }
}
