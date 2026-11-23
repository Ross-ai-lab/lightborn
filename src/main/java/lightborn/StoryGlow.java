package lightborn;

import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.LevelChunk;

/** Brief spell-impact light, with persistent chunk records so a crash cannot leave lights behind. */
final class StoryGlow {
    static void init() {}

    private static final AttachmentType<List<CollectionLight.Placed>> SAVED = AttachmentRegistry.create(
            ModItems.id("staff_impact_lights"), b -> b.persistent(CollectionLight.Placed.CODEC.listOf()));

    private record Site(ServerLevel level, BlockPos pos) {}

    private static final Set<Site> LIVE = new HashSet<>();

    static void place(ServerLevel l, BlockPos at) {
        at = at.immutable();
        if (!l.getBlockState(at).isAir()) return;
        var site = new Site(l, at);
        var c = l.getChunkAt(at);
        var records = new ArrayList<>(c.getAttachedOrElse(SAVED, List.of()));
        records.add(new CollectionLight.Placed(at, l.getBlockState(at)));
        c.setAttached(SAVED, List.copyOf(records));
        l.setBlockAndUpdate(at, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 12));
        LIVE.add(site);
        Scheduler.later(60, () -> remove(site));
    }

    private static void remove(Site s) {
        LIVE.remove(s);
        var c = s.level.getChunkAt(s.pos);
        var entries = c.getAttachedOrElse(SAVED, List.of());
        for (var r : entries)
            if (r.pos().equals(s.pos)
                    && s.level.getBlockState(s.pos).is(Blocks.LIGHT)
                    && s.level.getBlockState(s.pos).getValue(LightBlock.LEVEL) == 12)
                s.level.setBlockAndUpdate(s.pos, r.original());
        c.setAttached(
                SAVED, entries.stream().filter(r -> !r.pos().equals(s.pos)).toList());
    }

    static void loaded(ServerLevel l, LevelChunk c) {
        var keep = new ArrayList<CollectionLight.Placed>();
        for (var r : c.getAttachedOrElse(SAVED, List.of())) {
            if (LIVE.contains(new Site(l, r.pos()))) {
                keep.add(r);
                continue;
            }
            var block = c.getBlockState(r.pos());
            if (block.is(Blocks.LIGHT) && block.getValue(LightBlock.LEVEL) == 12) {
                c.setBlockState(r.pos(), r.original(), 0);
                l.getChunkSource().getLightEngine().checkBlock(r.pos());
            }
        }
        c.setAttached(SAVED, List.copyOf(keep));
    }

    static void clear() {
        for (var s : List.copyOf(LIVE)) remove(s);
    }
}
