package lightborn;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Owner rule (2026-11-09): crops grow 5 times as fast while it rains on them. Vanilla gives every chunk section
 * {@code randomTickSpeed} random block ticks a tick; while it rains, this gives the crops among them four more, so a
 * crop out in the rain gets five chances to grow for every one it would get. Only crops the rain actually falls on
 * (open sky, a biome where it rains rather than snows) and only in chunks within {@link #RADIUS} of a player, which is
 * where crops tick anyway at the default simulation distance.
 */
public final class RainGrowth {
    private RainGrowth() {}

    static final int EXTRA = 4;
    static final int RADIUS = 8;

    static void tick(ServerLevel level) {
        if (!level.isRaining() || level.players().isEmpty()) return;
        int speed = level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        if (speed <= 0) return;
        RandomSource random = level.getRandom();
        Set<Long> done = new HashSet<>();
        for (ServerPlayer p : level.players()) {
            ChunkPos c = p.chunkPosition();
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    int cx = c.x() + dx, cz = c.z() + dz;
                    if (!done.add(ChunkPos.pack(cx, cz))) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk != null) growIn(level, chunk, speed * EXTRA, random);
                }
            }
        }
    }

    static void growIn(ServerLevel level, LevelChunk chunk, int picks, RandomSource random) {
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir() || !section.isRandomlyTickingBlocks()) continue;
            int baseY = (chunk.getMinSectionY() + i) << 4;
            for (int n = 0; n < picks; n++) {
                int x = random.nextInt(16), y = random.nextInt(16), z = random.nextInt(16);
                BlockState state = section.getBlockState(x, y, z);
                if (!isCrop(state)) continue;
                BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                if (level.isRainingAt(pos.above())) state.randomTick(level, pos, random);
            }
        }
    }

    static boolean isCrop(BlockState state) {
        return state.isRandomlyTicking()
                && (state.is(BlockTags.CROPS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.COCOA));
    }
}
