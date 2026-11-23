package lightborn;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;

/**
 * A vampire zombie (owner, 2026-11-09): a zombie whose bite feeds it. Every hit it lands heals it by 40% of the damage
 * dealt (see {@link Monsters#afterDamage}), so a long fight against one drags on unless you hit harder than it bites.
 * Otherwise a zombie: health, damage, speed, burning by day, drops. Drawn grave-pale with blood-red clothes and red
 * eyes that glow in the dark.
 */
public class VampireZombie extends Zombie {
    /** Share of the damage it deals that it heals. */
    public static final float FEED = 0.4f;

    public VampireZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    // Hissier (owner): a higher, thinner voice, a hiss between groans, and a bite you can hear when it lands.
    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 1.35f;
    }

    @Override
    public void playAmbientSound() {
        if (random.nextBoolean()) {
            playSound(SoundEvents.CAT_HISS_BABY.value(), getSoundVolume(), 0.55f + random.nextFloat() * 0.15f);
        } else {
            super.playAmbientSound();
        }
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        boolean hit = super.doHurtTarget(level, target);
        if (hit) level.playSound(null, getX(), getY(), getZ(), SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 1f, 0.8f);
        return hit;
    }
}
