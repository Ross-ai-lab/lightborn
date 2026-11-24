package lightborn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/** Finds the actual loot inside the guide's destination and marks it with the existing treasure outline. */
final class RefugeeTreasure {
    private RefugeeTreasure() {}

    static final int GLOW_TICKS = 6_000;
    private static final int RANGE = 56, MAX_MARKERS = 16;

    static int reveal(ServerLevel level, BlockPos pin, BlockPos guide, HolderSet<Structure> kinds, boolean buried) {
        var start = findStart(level, pin, kinds);
        var bounds = start == null
                ? List.<BoundingBox>of()
                : start.getPieces().stream().map(StructurePiece::getBoundingBox).toList();
        return revealIn(level, pin, guide, bounds, buried);
    }

    static int revealIn(ServerLevel level, BlockPos pin, BlockPos guide, List<BoundingBox> bounds, boolean buried) {
        List<BlockPos> targets = bounds.isEmpty()
                ? new ArrayList<>()
                : scan(
                        level,
                        pin,
                        RANGE,
                        be -> hiddenLoot(be) && bounds.stream().anyMatch(box -> box.isInside(be.getBlockPos())));
        // A buried cache is placed at terrain height, while its map locator may report a much lower Y.
        if (targets.isEmpty() && buried) {
            targets = scan(
                    level,
                    pin,
                    16,
                    be -> be instanceof RandomizableContainerBlockEntity box
                            && BuiltInLootTables.BURIED_TREASURE.equals(box.getLootTable()));
            targets.sort(Comparator.comparingLong(pos -> horizontalDistance(pos, pin)));
            if (targets.size() > 1) targets = targets.subList(0, 1);
        }
        targets.sort(Comparator.comparingDouble(pos -> pos.distSqr(guide)));
        var nearby = targets.subList(0, Math.min(targets.size(), MAX_MARKERS));
        for (BlockPos pos : nearby) {
            WorkSpells.highlight(level, pos, level.getBlockState(pos), TreasureThread.GOLD_GLOW, GLOW_TICKS);
        }
        return nearby.size();
    }

    private static boolean hiddenLoot(BlockEntity be) {
        return be instanceof BrushableBlockEntity
                || be instanceof RandomizableContainerBlockEntity box && box.getLootTable() != null;
    }

    private static List<BlockPos> scan(ServerLevel level, BlockPos pin, int range, Predicate<BlockEntity> match) {
        var positions = new ArrayList<BlockPos>();
        for (int x = (pin.getX() - range) >> 4; x <= (pin.getX() + range) >> 4; x++) {
            for (int z = (pin.getZ() - range) >> 4; z <= (pin.getZ() + range) >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var be : chunk.getBlockEntities().values()) {
                    if (horizontalDistance(be.getBlockPos(), pin) <= (long) range * range && match.test(be))
                        positions.add(be.getBlockPos());
                }
            }
        }
        return positions;
    }

    /** Use only already loaded starts and references. The map locator does not need to be inside a piece vertically. */
    private static StructureStart findStart(ServerLevel level, BlockPos pin, HolderSet<Structure> kinds) {
        var source = level.getChunkSource();
        var anchor = source.getChunkNow(pin.getX() >> 4, pin.getZ() >> 4);
        if (kinds == null || anchor == null) return null;
        var starts = new LinkedHashSet<StructureStart>();
        for (var holder : kinds) {
            var kind = holder.value();
            starts.add(anchor.getStartForStructure(kind));
            for (long packed : anchor.getReferencesForStructure(kind)) {
                ChunkPos ref = ChunkPos.unpack(packed);
                var chunk = source.getChunkNow(ref.x(), ref.z());
                if (chunk != null) starts.add(chunk.getStartForStructure(kind));
            }
        }
        starts.remove(null);
        return starts.stream()
                .filter(StructureStart::isValid)
                .min(Comparator.comparingLong(start -> distanceToFootprint(pin, start.getBoundingBox())))
                .orElse(null);
    }

    private static long distanceToFootprint(BlockPos pin, BoundingBox box) {
        long dx = (long) pin.getX() - Math.clamp(pin.getX(), box.minX(), box.maxX());
        long dz = (long) pin.getZ() - Math.clamp(pin.getZ(), box.minZ(), box.maxZ());
        return dx * dx + dz * dz;
    }

    private static long horizontalDistance(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX(), dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }
}
