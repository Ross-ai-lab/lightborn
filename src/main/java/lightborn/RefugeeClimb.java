package lightborn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** A short in-world dirt stair to a clear landing when an escort's walking path stalls. */
final class RefugeeClimb {
    private static final int MAX_RISE = 3, TIMEOUT_TICKS = 120;
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

    private final BlockPos start;
    private final BlockPos landing;
    private final List<BlockPos> fill;
    private int ticks;
    private boolean built;

    private RefugeeClimb(BlockPos start, BlockPos landing, List<BlockPos> fill) {
        this.start = start;
        this.landing = landing;
        this.fill = fill;
    }

    static RefugeeClimb find(Refugee guide, ServerLevel level, double dx, double dz) {
        if (!guide.onGround() || guide.isInWater() || !level.getGameRules().get(GameRules.MOB_GRIEFING)) return null;
        var directions = new ArrayList<>(List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST));
        directions.sort(Comparator.comparingDouble(dir -> -toward(dir, dx, dz)));
        BlockPos at = guide.blockPosition();
        for (Direction dir : directions) {
            if (toward(dir, dx, dz) <= 0) break;
            for (int run = 1; run <= MAX_RISE; run++) {
                for (int rise = 2; rise <= MAX_RISE; rise++) {
                    BlockPos landing = at.relative(dir, run).above(rise);
                    BlockPos start = at.relative(dir, run - rise);
                    if (!canStand(level, start) || !canStand(level, landing)) continue;
                    List<BlockPos> fill = stairFill(level, start, dir, rise);
                    if (!fill.isEmpty()) return new RefugeeClimb(start, landing, fill);
                }
            }
        }
        return null;
    }

    private static double toward(Direction dir, double dx, double dz) {
        return dx * dir.getStepX() + dz * dir.getStepZ();
    }

    private static List<BlockPos> stairFill(ServerLevel level, BlockPos start, Direction dir, int rise) {
        var fill = new ArrayList<BlockPos>();
        for (int step = 1; step < rise; step++) {
            BlockPos foot = start.relative(dir, step).above(step);
            if (!clearForPerson(level, foot)) return List.of();
            BlockPos support = foot.below();
            while (!solidTop(level, support)) {
                if (support.getY() < start.getY()
                        || !level.getBlockState(support).isAir()) return List.of();
                fill.add(support);
                support = support.below();
            }
        }
        return List.copyOf(fill.reversed());
    }

    /** Move to the foot of the stair first, so even a ledge right in front of the guide leaves room to place dirt. */
    boolean advance(Refugee guide, ServerLevel level) {
        if (++ticks > TIMEOUT_TICKS || !level.getGameRules().get(GameRules.MOB_GRIEFING) || !canStand(level, landing))
            return false;
        if (built) {
            if (near(guide, landing)) return false;
            walk(guide, landing);
            return true;
        }
        if (!canStand(level, start)) return false;
        Vec3 foot = Vec3.atBottomCenterOf(start);
        if (guide.distanceToSqr(foot) > .035) {
            if (guide.blockPosition().equals(start)) {
                guide.getNavigation().stop();
                guide.getMoveControl().setWantedPosition(foot.x, foot.y, foot.z, .8);
            } else {
                walk(guide, start);
            }
            return true;
        }
        for (BlockPos pos : fill) {
            if (!level.hasChunkAt(pos)
                    || !level.getBlockState(pos).isAir()
                    || !level.isUnobstructed(DIRT, pos, CollisionContext.empty())) return false;
        }
        for (BlockPos pos : fill) level.setBlock(pos, DIRT, Block.UPDATE_ALL);
        level.playSound(null, fill.getLast(), SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.8f, 1f);
        built = true;
        moveTo(guide, landing);
        return true;
    }

    private void walk(Refugee guide, BlockPos pos) {
        if (ticks == 1 || ticks % 10 == 0) moveTo(guide, pos);
    }

    private static void moveTo(Refugee guide, BlockPos pos) {
        var navigation = guide.getNavigation();
        // The coordinate overload stops within one block, which can leave him standing where the dirt needs to go.
        navigation.moveTo(navigation.createPath(pos, 0), 1.1);
    }

    private static boolean near(Refugee guide, BlockPos pos) {
        return guide.distanceToSqr(Vec3.atBottomCenterOf(pos)) < .4;
    }

    private static boolean canStand(ServerLevel level, BlockPos foot) {
        return clearForPerson(level, foot) && solidTop(level, foot.below());
    }

    private static boolean clearForPerson(ServerLevel level, BlockPos foot) {
        return level.hasChunkAt(foot)
                && level.getWorldBorder().isWithinBounds(foot)
                && level.getBlockState(foot).isAir()
                && level.getBlockState(foot.above()).isAir();
    }

    private static boolean solidTop(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP);
    }
}
