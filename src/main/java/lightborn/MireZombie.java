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

public class MireZombie extends Zombie {
    public MireZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        boolean hit = super.doHurtTarget(level, target);
        if (hit && target instanceof LivingEntity living)
            living.addEffect(new MobEffectInstance(MobEffects.POISON, Balance.MIRE_POISON_TICKS, 0), this);
        return hit;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide() && random.nextInt(7) == 0)
            level().addParticle(
                            ParticleTypes.SPORE_BLOSSOM_AIR, getRandomX(.4), getRandomY(), getRandomZ(.4), 0, .01, 0);
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * .65f;
    }
}
