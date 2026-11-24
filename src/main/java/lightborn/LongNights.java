package lightborn;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.WorldClocks;

/**
 * Nights last 1.5 times as long as days (owner, 2026-11-24). The Overworld clock's own rate slows from dusk to
 * dawn and syncs to every client. A bed still skips the night.
 */
final class LongNights {
    private LongNights() {}

    private static final int CYCLE_TICKS = 24_000;
    private static final int NIGHT_TICKS = Balance.DAWN_TICK - Balance.DUSK_TICK;
    private static final float NIGHT_RATE = NIGHT_TICKS / (1.5f * (CYCLE_TICKS - NIGHT_TICKS));

    static float rateAt(long clockTime) {
        long time = Math.floorMod(clockTime, CYCLE_TICKS);
        return time >= Balance.DUSK_TICK && time < Balance.DAWN_TICK ? NIGHT_RATE : 1f;
    }

    static void tick(MinecraftServer server) {
        var level = server.overworld();
        var clock = level.registryAccess().getOrThrow(WorldClocks.OVERWORLD);
        float want = rateAt(level.getOverworldClockTime());
        if (level.clockManager().getInstance(clock).rate() != want)
            level.clockManager().setRate(clock, want);
    }
}
