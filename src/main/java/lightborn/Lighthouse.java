package lightborn;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Far from home, a gold pillar stands on the horizon where the player's ward is (owner, 2026-11-16; 2026-11-23: day
 * and night). The server tells each player where their nearest ward lies while they are out of sight of it; the client
 * draws the pillar ({@code LighthouseView}).
 */
public final class Lighthouse {
    private Lighthouse() {}

    /** Beyond this many blocks (horizontally) from home the pillar shows. */
    public static final int FROM = 128;

    /** The home ward chest to draw the pillar over; absent when none should show. */
    public static final AttachmentType<BlockPos> HOME = AttachmentRegistry.<BlockPos>create(
            ModItems.id("lighthouse_home"),
            b -> b.syncWith(BlockPos.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));

    static void init() {}

    /** Once a second, from the story tick. */
    static void tick(ServerPlayer p) {
        var home = nearestOwned(p.level(), p);
        if (home != null) {
            double dx = home.getX() + .5 - p.getX(), dz = home.getZ() + .5 - p.getZ();
            if (dx * dx + dz * dz < FROM * FROM) home = null;
        }
        if (Objects.equals(home, p.getAttached(HOME))) return;
        if (home == null) p.removeAttached(HOME);
        else p.setAttached(HOME, home);
    }

    /** The player's own ward chest nearest to them in this world, at any distance. */
    static BlockPos nearestOwned(ServerLevel level, ServerPlayer p) {
        var owners = level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of());
        BlockPos best = null;
        double nearest = Double.MAX_VALUE;
        for (var chest : level.getAttachedOrElse(ModData.LIGHT_WARDS, List.<BlockPos>of())) {
            var owner = owners.get(Long.toString(chest.asLong()));
            if (owner == null || !owner.player().equals(p.getUUID())) continue;
            double d = chest.distToCenterSqr(p.position());
            if (d < nearest) {
                nearest = d;
                best = chest;
            }
        }
        return best;
    }
}
