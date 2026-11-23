package lightborn;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;

public class FrostbittenZombie extends Zombie {
    public FrostbittenZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        boolean hit = super.doHurtTarget(level, target);
        if (hit && target instanceof LivingEntity living) {
            living.setTicksFrozen(
                    Math.max(living.getTicksFrozen(), living.getTicksRequiredToFreeze() + Balance.FROSTBITE_TICKS));
            living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, Balance.FROSTBITE_TICKS, 1), this);
        }
        return hit;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide() && random.nextInt(6) == 0)
            level().addParticle(ParticleTypes.SNOWFLAKE, getRandomX(.4), getRandomY(), getRandomZ(.4), 0, -.01, 0);
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * .7f;
    }
}
