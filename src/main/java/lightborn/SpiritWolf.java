package lightborn;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.Level;

/**
 * A wolf raised by the Spirit Wolves tome (owner, 2026-11-10): a tame wolf in every way (health, sitting, following,
 * teleporting to you), drawn in glowing spectral blue, that cannot breed and gives up its place in its master's pack
 * when it dies. How many a player may have, and the hunting, live in {@link SummonSpells}.
 */
public class SpiritWolf extends Wolf {
    public SpiritWolf(EntityType<? extends Wolf> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean canMate(Animal other) {
        return false;
    }

    @Override
    public Wolf getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        // A wisp of soul fire now and then, on every client that sees it.
        if (level().isClientSide() && random.nextInt(4) == 0) {
            level().addParticle(
                            ParticleTypes.SOUL_FIRE_FLAME, getRandomX(0.6), getRandomY(), getRandomZ(0.6), 0, 0.02, 0);
        }
    }

    @Override
    public void die(DamageSource source) {
        if (level() instanceof ServerLevel level) {
            if (getOwner() instanceof ServerPlayer owner) SummonSpells.unbindWolf(owner, getUUID());
            level.sendParticles(ParticleTypes.SOUL, getX(), getY() + 0.4, getZ(), 16, 0.3, 0.3, 0.3, 0.05);
        }
        super.die(source);
    }
}
