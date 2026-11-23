package lightborn;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Saved journey counters; the removed Bestiary's and regions' legacy packet fields stay zero. */
public final class CollectionLedger {
    private CollectionLedger() {}

    public record Snapshot(
            int bestiary,
            int bestiaryTotal,
            int journal,
            int journalTotal,
            int bosses,
            int bossTotal,
            int regions,
            int regionTotal,
            long hunts,
            int wardRadius,
            boolean luminous) {}

    public static Snapshot snapshot(ServerPlayer player) {
        return new Snapshot(
                0,
                0,
                DragonStory.chapters(player),
                9,
                player.getAttachedOrElse(ModData.BOSS_KILLS, List.of()).size()
                        + (DragonStory.hasFlag(player, 16) ? 1 : 0),
                BossKind.ROSTER.size() + 1,
                0,
                0,
                player.getAttachedOrElse(ModData.HUNTS_CLEARED, 0L),
                LightWards.radiusFor(player),
                Boolean.TRUE.equals(player.getAttached(ModData.LIGHT_COMPLETE)));
    }

    public static void read(ServerPlayer player, ItemStack book) {} // Only spoken chapters advance the story.
}
