package lightborn;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;

/**
 * The great monsters (mutants, vanilla bosses, Lightborn bosses) get a one-time horn the first time a player lays eyes
 * on them. No title card: the name-and-epithet popup was removed (owner, 2026-11-16).
 */
public final class Wilds {
    private Wilds() {}

    private static final Map<UUID, Mob> GREAT = new HashMap<>();
    static final String MET_TAG = "lightborn_met";

    /** Twice a second: introduce any great monster a player has just laid eyes on. */
    static void tick(MinecraftServer server) {
        if (Scheduler.now() % 10 == 5) tickGreat(server);
    }

    // ---------------------------------------------------------------- great monsters

    /** Called from Monsters.onLoad for heavy monsters and Lightborn bosses. */
    static void trackGreat(Mob mob) {
        if (!mob.entityTags().contains(MET_TAG)) GREAT.put(mob.getUUID(), mob);
    }

    /** First sight (within 28 blocks, clear line of sight): a horn for everyone nearby. */
    private static void tickGreat(MinecraftServer server) {
        for (Iterator<Mob> it = GREAT.values().iterator(); it.hasNext(); ) {
            Mob mob = it.next();
            if (mob.isRemoved() || !mob.isAlive() || mob.entityTags().contains(MET_TAG)) {
                it.remove();
                continue;
            }
            if (!(mob.level() instanceof ServerLevel level)) continue;
            ServerPlayer seen = null;
            for (ServerPlayer p : level.players()) {
                if (!p.isSpectator() && p.distanceToSqr(mob) < 28 * 28 && mob.hasLineOfSight(p)) {
                    seen = p;
                    break;
                }
            }
            if (seen == null) continue;
            mob.addTag(MET_TAG);
            it.remove();
            introduce(level, mob);
        }
    }

    static void introduce(ServerLevel level, Mob mob) {
        level.playSound(
                null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.RAID_HORN, SoundSource.HOSTILE, 3.0f, 0.75f);
    }

    static int greatCount() {
        return GREAT.size();
    }

    static void clear() {
        GREAT.clear();
    }
}
