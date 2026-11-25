package lightborn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.chunk.LevelChunk;

/** Temporary world light for magic, with chunk records to clean up lights after a restart. */
final class StoryGlow {
    static void init() {}

    private static final AttachmentType<List<CollectionLight.Placed>> SAVED = AttachmentRegistry.create(
            ModItems.id("staff_impact_lights"), b -> b.persistent(CollectionLight.Placed.CODEC.listOf()));

    private record Site(ServerLevel level, BlockPos pos) {}

    private record Glow(int strength, long expires) {}

    private static final Map<Site, Glow> LIVE = new HashMap<>();

    static void place(ServerLevel level, BlockPos at) {
        place(level, at, 15, 60);
    }

    static boolean place(ServerLevel level, BlockPos at, int strength, int ticks) {
        if (!level.hasChunkAt(at)) return false;
        var site = new Site(level, at.immutable());
        var before = level.getBlockState(at);
        var old = LIVE.get(site);
        boolean extending = old != null && before.is(Blocks.LIGHT) && before.getValue(LightBlock.LEVEL) == old.strength;
        if (extending) {
            strength = Math.max(strength, old.strength);
        } else if (!before.isAir()) {
            return false;
        } else {
            var chunk = level.getChunkAt(at);
            var records = new ArrayList<>(chunk.getAttachedOrElse(SAVED, List.of()));
            records.removeIf(r -> r.pos().equals(site.pos));
            records.add(new CollectionLight.Placed(site.pos, before));
            chunk.setAttached(SAVED, List.copyOf(records));
        }
        long expiry = Math.max(Scheduler.now() + ticks, old == null ? 0 : old.expires);
        var glow = new Glow(strength, expiry);
        if (extending && glow.equals(old)) return true;
        LIVE.put(site, glow);
        level.setBlockAndUpdate(at, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, strength));
        Scheduler.later((int) (expiry - Scheduler.now()), () -> {
            if (LIVE.get(site) == glow) remove(site);
        });
        return true;
    }

    private static void remove(Site site) {
        var glow = LIVE.remove(site);
        if (!site.level.hasChunkAt(site.pos)) return;
        var chunk = site.level.getChunkAt(site.pos);
        var entries = chunk.getAttachedOrElse(SAVED, List.of());
        var current = site.level.getBlockState(site.pos);
        if (current.is(Blocks.LIGHT) && current.getValue(LightBlock.LEVEL) == glow.strength)
            for (var record : entries)
                if (record.pos().equals(site.pos)) site.level.setBlockAndUpdate(site.pos, record.original());
        chunk.setAttached(
                SAVED,
                entries.stream()
                        .filter(record -> !record.pos().equals(site.pos))
                        .toList());
    }

    static void loaded(ServerLevel level, LevelChunk chunk) {
        var keep = new ArrayList<CollectionLight.Placed>();
        for (var record : chunk.getAttachedOrElse(SAVED, List.of())) {
            if (LIVE.containsKey(new Site(level, record.pos()))) {
                keep.add(record);
                continue;
            }
            var block = chunk.getBlockState(record.pos());
            if (block.is(Blocks.LIGHT) && block.getValue(LightBlock.LEVEL) >= 12) {
                chunk.setBlockState(record.pos(), record.original(), 0);
                level.getChunkSource().getLightEngine().checkBlock(record.pos());
            }
        }
        chunk.setAttached(SAVED, List.copyOf(keep));
    }

    static void clear() {
        for (var site : List.copyOf(LIVE.keySet())) remove(site);
    }
}
