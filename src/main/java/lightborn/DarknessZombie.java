package lightborn;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;

/**
 * A darkness zombie (owner, 2026-11-09): a zombie (health, damage, speed, burning by day, drops) whose touch is cold:
 * every hit it lands slows you (Slowness II, 3 seconds; see {@link Monsters#afterDamage}), so running from a pack of
 * them gets harder with each hit. Drawn ash-black with violet eyes that glow in the dark, trailing thin smoke.
 */
public class DarknessZombie extends Zombie {
    public DarknessZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    // Muffled (owner): its groans come low and quiet, as if through cloth; you hear a pack late.
    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.7f;
    }

    @Override
    protected float getSoundVolume() {
        return 0.45f;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide() && random.nextInt(3) == 0) {
            level().addParticle(
                            ParticleTypes.SMOKE,
                            getRandomX(0.4),
                            getY() + random.nextDouble() * getBbHeight(),
                            getRandomZ(0.4),
                            0,
                            0.01,
                            0);
        }
    }
}
