package lightborn;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * An ember skeleton (owner, 2026-11-09, the skeleton kinds): charred bones with glowing cracks and eyes, trailing
 * embers, whose arrows fly burning and set you alight. Otherwise a skeleton: bow, health, burning by day, drops.
 */
public class EmberSkeleton extends Skeleton {
    public EmberSkeleton(EntityType<? extends Skeleton> type, Level level) {
        super(type, level);
    }

    @Override
    protected AbstractArrow getArrow(ItemStack arrows, float power, ItemStack bow) {
        AbstractArrow arrow = super.getArrow(arrows, power, bow);
        arrow.igniteForSeconds(100f);
        return arrow;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide() && random.nextInt(4) == 0) {
            level().addParticle(
                            random.nextBoolean() ? ParticleTypes.SMALL_FLAME : ParticleTypes.SMOKE,
                            getRandomX(0.35),
                            getY() + random.nextDouble() * getBbHeight(),
                            getRandomZ(0.35),
                            0,
                            0.02,
                            0);
        }
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.8f;
    }
}
