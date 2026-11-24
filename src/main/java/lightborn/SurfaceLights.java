package lightborn;

import com.mojang.serialization.Codec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

/** Cache only real emitters, then relight them at dusk/dawn. Never scan the world each tick. */
public final class SurfaceLights {
    private SurfaceLights() {}

    private static final AttachmentType<Set<Long>> CONSTRUCTED_EARTH = AttachmentRegistry.create(
            ModItems.id("constructed_earth"),
            b -> b.persistent(Codec.LONG.listOf().xmap(Set::copyOf, s -> new ArrayList<>(s)))
                    .syncWith(
                            ByteBufCodecs.VAR_LONG
                                    .apply(ByteBufCodecs.list())
                                    .map(Set::copyOf, s -> new ArrayList<>(s)),
                            AttachmentSyncPredicate.all()));

    public static void init() {}

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

    private static boolean earth(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.DIRT)
                || s.is(BlockTags.SAND)
                || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.GRAVEL)
                || s.is(Blocks.CLAY)
                || s.is(Blocks.MUD)
                || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.PACKED_ICE)
                || s.is(Blocks.BLUE_ICE);
    }
    /** Natural earth/stone above makes a cave; roofs, leaves and tree canopies remain surface. */
    public static boolean surface(LightChunk chunk, BlockPos pos) {
        Set<Long> built = chunk instanceof LevelChunk c ? c.getAttachedOrElse(CONSTRUCTED_EARTH, Set.of()) : Set.of();
        var at = pos.mutable();
        for (int y = pos.getY() + 1; y < chunk.getMaxY(); y++) {
            at.setY(y);
            if (earth(chunk.getBlockState(at)) && !built.contains(at.asLong())) return false;
        }
        return true;
    }

    public static boolean suppress(LightChunkGetter source, BlockPos pos, BlockState block) {
        if (!ordinary(block) || !(source.getLevel() instanceof Level level) || level.dimension() != Level.OVERWORLD)
            return false;
        var s = state(level);
        boolean exposed = s.sources.computeIfAbsent(pos.asLong(), k -> {
            var c = source.getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
            return c != null && surface(c, pos);
        });
        return exposed && night(level);
    }

    public static void loaded(Level level, LevelChunk chunk) {
        if (level.dimension() != Level.OVERWORLD) return;
        var s = state(level);
        chunk.findBlockLightSources((pos, block) -> {
            if (ordinary(block)) {
                var at = pos.immutable();
                s.sources.put(at.asLong(), surface(chunk, at));
                s.pending.add(at);
            }
        });
        if (level.isClientSide())
            chunk.onAttachedSet(CONSTRUCTED_EARTH).register((before, after) -> {
                var p = chunk.getPos();
                for (long k : s.sources.keySet())
                    if ((BlockPos.getX(k) >> 4) == p.x() && (BlockPos.getZ(k) >> 4) == p.z()) {
                        var at = BlockPos.of(k);
                        s.sources.put(k, surface(chunk, at));
                        s.pending.add(at);
                    }
            });
    }

    public static void unloaded(Level level, LevelChunk chunk) {
        var s = STATES.get(level);
        if (s == null) return;
        var p = chunk.getPos();
        s.sources.keySet().removeIf(k -> (BlockPos.getX(k) >> 4) == p.x() && (BlockPos.getZ(k) >> 4) == p.z());
    }
    /** Block edits invalidate only lights in that vertical column, so a mined cave roof takes effect immediately. */
    public static void changed(LevelChunk chunk, BlockPos pos, BlockState now, BlockState old) {
        var level = chunk.getLevel();
        if (level.dimension() != Level.OVERWORLD) return;
        var s = state(level);
        var built = chunk.getAttachedOrElse(CONSTRUCTED_EARTH, Set.of());
        boolean oldNatural = old != null && earth(old) && !built.contains(pos.asLong());
        if (!level.isClientSide() && !earth(now) && built.contains(pos.asLong())) {
            var next = new HashSet<>(built);
            next.remove(pos.asLong());
            if (!next.equals(built)) chunk.setAttached(CONSTRUCTED_EARTH, Set.copyOf(next));
        }
        if (ordinary(now)) {
            s.sources.put(pos.asLong(), surface(chunk, pos));
        } else if (old != null && ordinary(old)) s.sources.remove(pos.asLong());
        boolean naturalNow = earth(now)
                && !chunk.getAttachedOrElse(CONSTRUCTED_EARTH, Set.of()).contains(pos.asLong());
        if (oldNatural == naturalNow) return;
        refreshColumn(chunk, pos, s);
    }
    /** Record actual block-item construction, not natural grass/dirt changes or world generation. */
    public static void placed(Level level, BlockPos pos) {
        if (level.isClientSide() || level.dimension() != Level.OVERWORLD || !earth(level.getBlockState(pos))) return;
        var chunk = level.getChunkAt(pos);
        var built = chunk.getAttachedOrElse(CONSTRUCTED_EARTH, Set.of());
        if (built.contains(pos.asLong())) return;
        var next = new HashSet<>(built);
        next.add(pos.asLong());
        chunk.setAttached(CONSTRUCTED_EARTH, Set.copyOf(next));
        refreshColumn(chunk, pos, state(level));
    }

    private static void refreshColumn(LevelChunk chunk, BlockPos pos, State s) {
        for (long k : s.sources.keySet())
            if (BlockPos.getX(k) == pos.getX() && BlockPos.getZ(k) == pos.getZ() && BlockPos.getY(k) < pos.getY()) {
                var at = BlockPos.of(k);
                s.sources.put(k, surface(chunk, at));
                s.pending.add(at);
            }
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
