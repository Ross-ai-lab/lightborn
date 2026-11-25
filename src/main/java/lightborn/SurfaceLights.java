package lightborn;

import com.mojang.serialization.Codec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

/** Cache only real emitters, then relight them at dusk/dawn. Never scan the world each tick. */
public final class SurfaceLights {
    private SurfaceLights() {}

    // Retire the old construction records on first load so older chunks deserialize cleanly.
    private static final AttachmentType<List<Long>> CONSTRUCTED_EARTH =
            AttachmentRegistry.create(ModItems.id("constructed_earth"), b -> b.persistent(Codec.LONG.listOf()));

    public static void init() {}

    private static final int WALL_RANGE = 7;
    private static final int ROOF_WIDTH = 3;

    private static final Map<Level, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class State {
        final Map<Long, Boolean> sources = new ConcurrentHashMap<>();
        final ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        boolean night;
    }

    private static State state(Level l) {
        return STATES.computeIfAbsent(l, k -> {
            var s = new State();
            s.night = night(l);
            return s;
        });
    }

    public static boolean night(Level l) {
        long t = Math.floorMod(l.getOverworldClockTime(), 24000);
        return l.dimension() == Level.OVERWORLD && t >= Balance.DUSK_TICK && t < Balance.DAWN_TICK;
    }

    private static boolean ordinary(BlockState s) {
        return s.getLightEmission() > 0 && !s.is(Blocks.LIGHT) && !s.is(ModItems.LIGHT_CHEST);
    }

    private static boolean covers(LightChunk chunk, BlockPos pos, BlockState state) {
        if (state.isAir() || state.is(BlockTags.LEAVES)) return false;
        var shape = state.getCollisionShape(chunk, pos);
        return Block.isFaceFull(shape, Direction.UP) || Block.isFaceFull(shape, Direction.DOWN);
    }

    private static LightChunk chunkAt(LightChunkGetter source, BlockPos pos) {
        return source.getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static boolean coveredAt(LightChunkGetter source, BlockPos pos) {
        var chunk = chunkAt(source, pos);
        return chunk != null && covers(chunk, pos, chunk.getBlockState(pos));
    }

    private static boolean terrainAt(LightChunkGetter source, BlockPos pos) {
        var chunk = chunkAt(source, pos);
        if (chunk == null) return false;
        return naturalTerrain(chunk.getBlockState(pos));
    }

    private static boolean naturalTerrain(BlockState state) {
        return state != null
                && (state.is(BlockTags.BASE_STONE_OVERWORLD)
                        || state.is(BlockTags.DIRT)
                        || state.is(BlockTags.SAND)
                        || state.is(BlockTags.TERRACOTTA)
                        || state.is(Blocks.GRAVEL)
                        || state.is(Blocks.CLAY)
                        || state.is(Blocks.MUD));
    }

    public static boolean surface(LevelChunk chunk, BlockPos pos) {
        return surface(chunk.getLevel().getChunkSource(), chunk, pos);
    }

    /** A low overhang alone is outdoors. Buildings require a roof and walls; caves may have thick rock overhead. */
    private static boolean surface(LightChunkGetter source, LightChunk chunk, BlockPos pos) {
        var probe = pos.mutable();
        for (int y = pos.getY() + 1; y <= chunk.getMaxY(); y++) {
            probe.setY(y);
            if (!covers(chunk, probe, chunk.getBlockState(probe))) continue;
            if (sheltered(source, pos, probe.immutable())) return false;
        }
        return true;
    }

    private static boolean sheltered(LightChunkGetter source, BlockPos light, BlockPos roof) {
        int depth = roof.getY() - light.getY();
        int tiles = 0, thickTerrain = 0;
        for (var tile : BlockPos.betweenClosed(roof.offset(-1, 0, -1), roof.offset(1, 0, 1))) {
            if (coveredAt(source, tile)
                    || coveredAt(source, tile.above())
                    || depth >= 2 && coveredAt(source, tile.below())) tiles++;
            if (terrainAt(source, tile) && terrainAt(source, tile.above()) && terrainAt(source, tile.above(2)))
                thickTerrain++;
        }
        if (thickTerrain >= 7) return true;
        if (tiles < (ROOF_WIDTH * ROOF_WIDTH + 1) / 2 || depth > 9) return false;
        var floorLevel = light.atY(Math.min(light.getY(), roof.getY() - 2));
        int walls = 0;
        for (var direction : Direction.Plane.HORIZONTAL) {
            var across = direction.getClockWise();
            for (int distance = 1; distance <= WALL_RANGE; distance++) {
                var center = floorLevel.relative(direction, distance);
                int filled = 0;
                for (int side = -1; side <= 1; side++) {
                    var at = center.relative(across, side);
                    if (coveredAt(source, at) && coveredAt(source, at.above())) filled++;
                }
                if (filled < 2) continue;
                if (++walls >= 3) return true;
                break;
            }
        }
        return false;
    }

    public static boolean suppress(LightChunkGetter source, BlockPos pos, BlockState block) {
        if (!ordinary(block) || !(source.getLevel() instanceof Level level) || level.dimension() != Level.OVERWORLD)
            return false;
        var s = state(level);
        boolean exposed = s.sources.computeIfAbsent(pos.asLong(), k -> {
            var c = source.getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
            return c != null && surface(source, c, pos);
        });
        return exposed && night(level);
    }

    public static void loaded(Level level, LevelChunk chunk) {
        if (level.dimension() != Level.OVERWORLD) return;
        if (!level.isClientSide() && chunk.getAttached(CONSTRUCTED_EARTH) != null)
            chunk.removeAttached(CONSTRUCTED_EARTH);
        var s = state(level);
        chunk.findBlockLightSources((pos, block) -> {
            if (ordinary(block)) {
                var at = pos.immutable();
                s.sources.put(at.asLong(), surface(chunk, at));
                s.pending.add(at);
            }
        });
        var p = chunk.getPos();
        int minX = p.x() << 4, minZ = p.z() << 4;
        for (long key : s.sources.keySet()) {
            int x = BlockPos.getX(key), z = BlockPos.getZ(key);
            if (x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16) continue;
            if (x >= minX - WALL_RANGE
                    && x < minX + 16 + WALL_RANGE
                    && z >= minZ - WALL_RANGE
                    && z < minZ + 16 + WALL_RANGE) refresh(level.getChunkSource(), s, key);
        }
    }

    public static void unloaded(Level level, LevelChunk chunk) {
        var s = STATES.get(level);
        if (s == null) return;
        var p = chunk.getPos();
        s.sources.keySet().removeIf(k -> (BlockPos.getX(k) >> 4) == p.x() && (BlockPos.getZ(k) >> 4) == p.z());
    }
    /** Roof and wall edits can change the shelter of any nearby emitter. */
    public static void changed(LevelChunk chunk, BlockPos pos, BlockState now, BlockState old) {
        var level = chunk.getLevel();
        if (level.dimension() != Level.OVERWORLD) return;
        var s = state(level);
        if (ordinary(now)) s.sources.put(pos.asLong(), surface(chunk, pos));
        else if (old != null && ordinary(old)) s.sources.remove(pos.asLong());
        boolean coveredBefore = old != null && covers(chunk, pos, old);
        if (coveredBefore == covers(chunk, pos, now) && naturalTerrain(old) == naturalTerrain(now)) return;
        for (long key : s.sources.keySet())
            if (Math.abs(BlockPos.getX(key) - pos.getX()) <= WALL_RANGE
                    && Math.abs(BlockPos.getZ(key) - pos.getZ()) <= WALL_RANGE
                    && BlockPos.getY(key) <= pos.getY() + 1) refresh(level.getChunkSource(), s, key);
    }

    private static void refresh(LightChunkGetter source, State s, long key) {
        var pos = BlockPos.of(key);
        var chunk = chunkAt(source, pos);
        if (chunk == null) return;
        boolean exposed = surface(source, chunk, pos);
        var previous = s.sources.replace(key, exposed);
        if (previous != null && previous != exposed) s.pending.add(pos);
    }

    public static void tick(Level level) {
        var s = STATES.get(level);
        if (s == null) return;
        boolean n = night(level);
        if (n != s.night) {
            s.night = n;
            for (var e : s.sources.entrySet()) if (e.getValue()) s.pending.add(BlockPos.of(e.getKey()));
        }
        for (int i = 0; i < 256 && !s.pending.isEmpty(); i++) {
            var at = s.pending.removeFirst();
            if (level.hasChunkAt(at)) level.getLightEngine().checkBlock(at);
        }
    }
}
