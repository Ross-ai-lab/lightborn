package lightborn;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;

/** Threatening noise and arm swings, with no block-damage or break path. Works on locked Normal difficulty. */
public final class DoorPoundGoal extends Goal {
    private final Zombie zombie;
    private BlockPos door;
    private int ticks;

    public DoorPoundGoal(Zombie zombie) {
        this.zombie = zombie;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean closed(BlockPos at) {
        var state = zombie.level().getBlockState(at);
        return state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN);
    }

    @Override
    public boolean canUse() {
        if (!(zombie.getTarget() instanceof Player p) || !p.isAlive() || Monsters.isAtmosphericProp(zombie))
            return false;
        Vec3 toward = p.position().subtract(zombie.position()).normalize();
        BlockPos best = null;
        double nearest = 4;
        for (BlockPos at : BlockPos.betweenClosed(
                zombie.blockPosition().offset(-2, 0, -2), zombie.blockPosition().offset(2, 1, 2))) {
            if (!closed(at)) continue;
            double distance = at.distToCenterSqr(zombie.position());
            Vec3 toDoor = Vec3.atCenterOf(at).subtract(zombie.position());
            if (distance >= nearest
                    || new Vec3(toDoor.x, 0, toDoor.z).normalize().dot(new Vec3(toward.x, 0, toward.z).normalize())
                            < .35) continue;
            best = at.immutable();
            nearest = distance;
        }
        door = best;
        return best != null;
    }

    @Override
    public boolean canContinueToUse() {
        return door != null
                && closed(door)
                && zombie.getTarget() instanceof Player p
                && p.isAlive()
                && door.distToCenterSqr(zombie.position()) < 5;
    }

    @Override
    public void start() {
        ticks = 0;
        zombie.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (door == null) return;
        zombie.getNavigation().stop();
        zombie.getLookControl().setLookAt(door.getX() + .5, door.getY() + .6, door.getZ() + .5);
        if (ticks++ % 24 == 0 || ticks % 24 == 5) {
            zombie.level().levelEvent(1019, door, 0);
            zombie.swingForAttack(InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void stop() {
        door = null;
    }
}
