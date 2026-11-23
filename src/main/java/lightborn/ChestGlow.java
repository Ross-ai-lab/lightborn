package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.*;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Static magical fill owned by its chest: a broad outer ring plus even room/doorway light, and torchlight over the
 * whole ground under the ward's dome (owner, 2026-11-15: the whole surface lit as if by torches, not just the hut).
 *
 * <p>No light reaches past the ward (owner, 2026-11-16: outside, only the monsters' eyes should show). Block light falls
 * by one per block of taxicab distance, which is never shorter than the straight line, so a source whose level is at
 * most its straight-line distance to the ward's edge leaves every block outside dark. Sources near the edge are
 * therefore dimmer, and a small ward is lit less brightly than a large one.
 */
final class ChestGlow {
    private ChestGlow() {}

    private record Source(BlockPos chest, BlockState original) {
        static final Codec<Source> CODEC = RecordCodecBuilder.create(i -> i.group(
                        BlockPos.CODEC.fieldOf("chest").forGetter(Source::chest),
                        BlockState.CODEC.fieldOf("original").forGetter(Source::original))
                .apply(i, Source::new));
    }

    private static final AttachmentType<Map<String, Source>> SOURCES = AttachmentRegistry.create(
            ModItems.id("chest_glow_sources"), b -> b.persistent(Codec.unboundedMap(Codec.STRING, Source.CODEC)));
    private static final int[][] RING = {{4, 0}, {-4, 0}, {0, 4}, {0, -4}, {3, 3}, {3, -3}, {-3, 3}, {-3, -3}};
    private static final int[] HEIGHTS = {1, 2, 0, 3, -1};
    // x = viewer-right, z = chest front; high interior fill and a soft, symmetrical doorway fill.
    private static final int[][] FILL = {
        {0, 2, 0},
        {0, 2, 1},
        {-1, 2, 1},
        {1, 2, 1},
        {-1, 1, 0},
        {1, 1, 0},
        {0, 1, 2},
        {0, 1, 4},
        {-1, 1, 3},
        {1, 1, 3},
        {0, 0, 2},
        {1, 0, 1},
        {0, 0, 4}
    };

    static void init() {
        ServerChunkEvents.CHUNK_LOAD.register((l, c, generated) -> loaded(l, c));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.player;
            Scheduler.later(40, () -> {
                if (player.isRemoved()) return;
                for (var chest : player.level().getAttachedOrElse(ModData.LIGHT_WARDS, List.of()))
                    if (player.distanceToSqr(Vec3.atCenterOf(chest)) < 128 * 128) refresh(player.level(), chest);
            });
        });
    }
    /** Send settled light after the generated hut's chunks have reached the joining client. */
    static void refresh(ServerLevel level, BlockPos chest) {
        for (int x = (chest.getX() - 4) >> 4; x <= (chest.getX() + 4) >> 4; x++)
            for (int z = (chest.getZ() - 4) >> 4; z <= (chest.getZ() + 4) >> 4; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) continue;
                var pos = new ChunkPos(x, z);
                var packet = new ClientboundLightUpdatePacket(pos, level.getLightEngine(), null, null);
                for (var player : PlayerLookup.tracking(level, pos)) player.connection.send(packet);
            }
    }

    /** The brightest a source here may be and still leave everything outside a ward of this radius dark. */
    static int levelAt(BlockPos chest, BlockPos pos, int radius) {
        return Math.clamp((int) Math.floor(radius - Math.sqrt(chest.distSqr(pos))), 0, 15);
    }

    static void place(ServerLevel l, BlockPos chest) {
        for (int[] spot : RING) {
            boolean lit = false;
            for (int y : HEIGHTS)
                if (l.hasChunkAt(chest.offset(spot[0], y, spot[1]))
                        && isLight(l.getBlockState(chest.offset(spot[0], y, spot[1])))) {
                    put(l, chest, chest.offset(spot[0], y, spot[1])); // keeps its level fitted to the ward
                    lit = true;
                    break;
                }
            if (!lit) for (int y : HEIGHTS) if (put(l, chest, chest.offset(spot[0], y, spot[1]))) break;
        }
        var front = l.getBlockState(chest).getValue(HorizontalDirectionalBlock.FACING);
        var right = front.getCounterClockWise();
        for (int[] at : FILL)
            put(
                    l,
                    chest,
                    chest.offset(
                            right.getStepX() * at[0] + front.getStepX() * at[2],
                            at[1],
                            right.getStepZ() * at[0] + front.getStepZ() * at[2]));
    }

    /** Columns between the dome's light sources: every surface spot sits within four steps of one (light 11+). */
    static final int FIELD_STEP = 5;

    private static final int FIELD_BATCH = 32;
    private static final Map<BlockPos, Integer> CURSOR = new HashMap<>();

    /**
     * Lays the dome's torchlight a few columns at a time (called every half second per loaded chest), cycling forever
     * so new ground (a hill dug away, a tree felled) is lit too. A source sits in the first air at a column's surface,
     * under leaves rather than on them; a block placed there simply replaces it.
     */
    static void field(ServerLevel l, BlockPos chest, int radius) {
        int n = radius / FIELD_STEP, side = 2 * n + 1, total = side * side;
        int c = CURSOR.getOrDefault(chest, 0) % total;
        double reach = (radius - 1.0) * (radius - 1.0);
        for (int k = 0; k < FIELD_BATCH; k++, c = (c + 1) % total) {
            int x = chest.getX() + (c % side - n) * FIELD_STEP, z = chest.getZ() + (c / side - n) * FIELD_STEP;
            double dx = x - chest.getX(), dz = z - chest.getZ();
            if (dx * dx + dz * dz > reach) continue;
            var column = new BlockPos(x, chest.getY(), z);
            if (!l.hasChunkAt(column)) continue;
            int top = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = top; y <= top + 2; y++) {
                var pos = new BlockPos(x, y, z);
                if (pos.distSqr(chest) > reach) break;
                if (put(l, chest, pos)) break;
            }
        }
        CURSOR.put(chest.immutable(), c);
    }

    private static boolean put(ServerLevel l, BlockPos chest, BlockPos pos) {
        if (!l.hasChunkAt(pos)) return false;
        var old = l.getBlockState(pos);
        int level = levelAt(chest, pos, LightWards.radiusAt(l, chest));
        if (isLight(old)) {
            // Ours and the ward has changed size since: re-dim (or brighten) it to fit. Another chest's: leave it.
            var owned = l.getChunkAt(pos).getAttachedOrElse(SOURCES, Map.of()).get(Long.toString(pos.asLong()));
            if (owned != null && owned.chest().equals(chest) && old.getValue(LightBlock.LEVEL) != level)
                l.setBlockAndUpdate(pos, level == 0 ? owned.original() : old.setValue(LightBlock.LEVEL, level));
            return true;
        }
        if (!old.isAir() || level == 0) return false;
        var chunk = l.getChunkAt(pos);
        var next = new HashMap<>(chunk.getAttachedOrElse(SOURCES, Map.of()));
        next.put(Long.toString(pos.asLong()), new Source(chest.immutable(), old));
        chunk.setAttached(SOURCES, Map.copyOf(next));
        l.setBlockAndUpdate(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level));
        return true;
    }

    /** Takes back every light this chest owns, the dome's field included (wards reach 48, so look 64 out). */
    static void remove(ServerLevel l, BlockPos chest) {
        CURSOR.remove(chest);
        for (int x = (chest.getX() - 64) >> 4; x <= (chest.getX() + 64) >> 4; x++)
            for (int z = (chest.getZ() - 64) >> 4; z <= (chest.getZ() + 64) >> 4; z++) {
                var c = l.getChunkSource().getChunkNow(x, z);
                if (c == null) continue;
                var before = c.getAttachedOrElse(SOURCES, Map.of());
                var next = new HashMap<>(before);
                for (var entry : before.entrySet())
                    if (entry.getValue().chest.equals(chest)) {
                        var pos = BlockPos.of(Long.parseLong(entry.getKey()));
                        if (isLight(l.getBlockState(pos))) l.setBlockAndUpdate(pos, entry.getValue().original);
                        next.remove(entry.getKey());
                    }
                if (next.size() != before.size()) c.setAttached(SOURCES, Map.copyOf(next));
            }
    }

    private static void loaded(ServerLevel l, LevelChunk c) {
        var old = c.getAttachedOrElse(SOURCES, Map.of());
        if (old.isEmpty()) return;
        var next = new HashMap<>(old);
        var chests = l.getAttachedOrElse(ModData.LIGHT_WARDS, List.of());
        for (var entry : old.entrySet())
            if (!chests.contains(entry.getValue().chest)) {
                var pos = BlockPos.of(Long.parseLong(entry.getKey()));
                if (isLight(c.getBlockState(pos))) {
                    c.setBlockState(pos, entry.getValue().original, 0);
                    l.getLightEngine().checkBlock(pos);
                }
                next.remove(entry.getKey());
            }
        if (next.size() != old.size()) c.setAttached(SOURCES, Map.copyOf(next));
    }

    /** A magical light: any light block (the chest's own are graded down toward the ward's edge). */
    private static boolean isLight(BlockState state) {
        return state.is(Blocks.LIGHT);
    }
}
