package lightborn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One night-time Dragon for each loaded Chest of Light. Chest positions themselves are already saved by LightWards. */
public final class LightDragons {
    private LightDragons() {}

    private static final Map<ServerLevel, Map<BlockPos, LightDragon>> LIVE = new HashMap<>();

    public static boolean night(ServerLevel level) {
        long time = Math.floorMod(level.getServer().overworld().getOverworldClockTime(), 24000);
        return time >= Balance.DUSK_TICK && time < Balance.DAWN_TICK;
    }

    /**
     * His lowest point above the chest's base: half a block clear of the lid, never resting on it (owner rule,
     * 2026-11-16).
     */
    static final double ABOVE = 1.5;
    /**
     * His shape (measured by tools/generate_dragon.py): half his width, how far he reaches behind and in front of his
     * feet (the head and the pearl lean out over the room), and his height. One fixed size (owner, 2026-11-16: at most
     * 3 blocks wide and 4 tall, no shrinking to fit): 2.6 wide, 3.45 tall, so from half a block over the lid he clears
     * the starter hut's five-high room.
     */
    static final double HALF_X = 1.32, BACK = .64, FRONT = .86, HEIGHT = 3.45;

    /** Where the echo hovers (his size is always 1). */
    public record Spot(Vec3 at, double size) {}

    /**
     * Only ever directly over his chest, his back flush with the chest's back (so a chest against a wall keeps him out
     * of it), and only where his whole shape fits in open air. A chest without that room has no echo until there is
     * space; he never shrinks and never moves to the side.
     */
    public static Spot spot(ServerLevel level, BlockPos chest) {
        Direction front = level.getBlockState(chest).getValue(HorizontalDirectionalBlock.FACING);
        Vec3 base = Vec3.atBottomCenterOf(chest);
        // His back sits on the chest's back face; his width runs along the chest's front face.
        double shift = BACK - .5 + .02;
        Vec3 p = base.add(front.getStepX() * shift, ABOVE, front.getStepZ() * shift);
        Vec3 a = p.add(front.getStepX() * -BACK, 0, front.getStepZ() * -BACK);
        Vec3 b = p.add(front.getStepX() * FRONT, HEIGHT, front.getStepZ() * FRONT);
        Vec3 side = new Vec3(Math.abs(front.getStepZ()) * HALF_X, 0, Math.abs(front.getStepX()) * HALF_X);
        AABB space = new AABB(a.subtract(side), b.add(side));
        boolean loaded = level.hasChunksAt(
                BlockPos.containing(space.minX, space.minY, space.minZ),
                BlockPos.containing(space.maxX, space.maxY, space.maxZ));
        if (!loaded || level.getBlockCollisions(null, space).iterator().hasNext()) return null;
        return new Spot(p, 1);
    }

    public static Vec3 position(ServerLevel level, BlockPos chest) {
        Spot s = spot(level, chest);
        return s == null ? null : s.at();
    }

    public static void reconcile(ServerLevel level, BlockPos chest) {
        Map<BlockPos, LightDragon> dragons = LIVE.computeIfAbsent(level, k -> new HashMap<>());
        if (!level.isLoaded(chest) || !level.getBlockState(chest).is(ModItems.LIGHT_CHEST)) {
            remove(level, chest);
            return;
        }
        if (DragonStory.ending(level) == 2 || DragonStory.ending(level) == 3) {
            remove(level, chest);
            return;
        }
        // By day, and while a Cleanse burns at this ward (owner, 2026-11-17: the Dragon is gone for the whole of it).
        if ((!night(level) && DragonStory.ending(level) != 1) || Cleanse.at(level, chest)) {
            var d = dragons.get(chest);
            if (d != null) d.depart();
            return;
        }
        Spot spot = spot(level, chest);
        if (spot == null) {
            remove(level, chest);
            return;
        }
        Vec3 at = spot.at();
        var dragon = dragons.get(chest);
        if (dragon == null || dragon.isRemoved()) {
            dragon = new LightDragon(ModEntities.LIGHT_DRAGON, level);
            dragons.put(chest.immutable(), dragon);
            dragon.bind(chest);
            dragon.size(spot.size());
            dragon.setPos(at);
            face(level, chest, dragon);
            if (level.addFreshEntity(dragon)) dragon.arrive();
            else dragons.remove(chest);
        } else {
            dragon.remain();
            dragon.size(spot.size());
            dragon.setPos(at);
            face(level, chest, dragon);
        }
    }

    private static void face(ServerLevel level, BlockPos chest, LightDragon dragon) {
        float yaw = level.getBlockState(chest)
                .getValue(HorizontalDirectionalBlock.FACING)
                .toYRot();
        dragon.setYRot(yaw);
        dragon.setYBodyRot(yaw);
        if (dragon.tickCount == 0) dragon.setYHeadRot(yaw);
    }

    public static void remove(ServerLevel level, BlockPos chest) {
        var dragons = LIVE.get(level);
        if (dragons == null) return;
        var old = dragons.remove(chest);
        if (old != null) old.discard();
    }

    public static LightDragon get(ServerLevel level, BlockPos chest) {
        var m = LIVE.get(level);
        var d = m == null ? null : m.get(chest);
        return d == null || d.isRemoved() ? null : d;
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            var dragons = LIVE.get(level);
            if (dragons != null)
                for (BlockPos pos : List.copyOf(dragons.keySet())) {
                    if (!level.isLoaded(pos)
                            || !level.getBlockState(pos).is(ModItems.LIGHT_CHEST)
                            || dragons.get(pos).isRemoved()) remove(level, pos);
                    else if (!night(level) && DragonStory.ending(level) != 1)
                        dragons.get(pos).depart();
                }
            if (night(level) || DragonStory.ending(level) == 1)
                for (BlockPos pos : level.getAttachedOrElse(ModData.LIGHT_WARDS, List.of()))
                    if (level.isLoaded(pos)) reconcile(level, pos);
        }
    }

    static void clear() {
        LIVE.values().forEach(m -> m.values().forEach(LightDragon::discard));
        LIVE.clear();
    }
}
