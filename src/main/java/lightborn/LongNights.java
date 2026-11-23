package lightborn;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.WorldClocks;

/**
 * Nights last twice as long (owner, 2026-11-16): from dusk to dawn the Overworld clock runs at half speed, through
 * the clock's own rate, which the game syncs to every client so the sky moves smoothly. Days keep their length; a
 * bed still skips the night.
 */
final class LongNights {
    private LongNights() {}

    static final float NIGHT_RATE = 0.5f;

    static float rateAt(long clockTime) {
        long time = Math.floorMod(clockTime, 24000);
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
