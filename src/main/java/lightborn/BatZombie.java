package lightborn;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

/**
 * A bat zombie (owner, 2026-11-15): a bat and a zombie merged, found only in caves. Bigger than a bat, its fur gone to
 * rotting green-grey, its wings torn, eyes burning red. It flutters like a bat until it hears you, then dives in
 * jerky swoops, bites, and wheels away to come again. Undead: it burns in daylight.
 */
public class BatZombie extends Bat implements Enemy {
    /** Ticks it wheels away after a bite before diving again. */
    static final int RECOIL = 18;
    /** Caves only: never above this height, never under open sky. */
    static final int MAX_SPAWN_Y = 40;

    private int recoil;
    private Player prey;

    public BatZombie(EntityType<? extends Bat> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Bat.createAttributes()
                .add(Attributes.MAX_HEALTH, 12)
                .add(Attributes.ATTACK_DAMAGE, 3)
                .add(Attributes.FOLLOW_RANGE, 20);
    }

    /** Dark cave air below {@link #MAX_SPAWN_Y}, out of the sky's sight, as dark as any monster needs. */
    public static boolean checkSpawnRules(
            EntityType<BatZombie> type,
            ServerLevelAccessor level,
            EntitySpawnReason reason,
            BlockPos pos,
            RandomSource random) {
        return level.getDifficulty() != Difficulty.PEACEFUL
                && (EntitySpawnReason.isSpawner(reason)
                        || pos.getY() < MAX_SPAWN_Y
                                && !level.canSeeSky(pos)
                                && Monster.isDarkEnoughToSpawn(level, pos, random));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        if (prey == null || !prey.isAlive() || prey.isSpectator() || prey.isCreative() || distanceToSqr(prey) > 400)
            prey = level.getNearestPlayer(this, 16);
        if (prey != null && (prey.isCreative() || prey.isSpectator() || !hasLineOfSight(prey))) prey = null;
        if (prey == null) {
            super.customServerAiStep(level);
            return;
        }
        if (isResting()) setResting(false);
        Vec3 to = prey.getEyePosition().subtract(0, .4, 0).subtract(position());
        double dist = to.length();
        Vec3 dir = to.scale(1 / Math.max(.001, dist));
        // Jerky bat flight: a steering pull toward (or, recoiling, away from) the prey, with a flutter on top.
        float t = tickCount * .45f;
        Vec3 flutter = new Vec3(Math.sin(t) * .06, Math.cos(t * 1.3) * .08, Math.cos(t) * .06);
        Vec3 pull = dir.scale(recoil > 0 ? -.07 : .09);
        Vec3 v = getDeltaMovement().add(pull).add(flutter);
        double cap = recoil > 0 ? .4 : .5;
        if (v.length() > cap) v = v.normalize().scale(cap);
        setDeltaMovement(v);
        setYRot(-(float) Math.toDegrees(Math.atan2(v.x, v.z)));
        yBodyRot = getYRot();
        if (recoil > 0) recoil--;
        else if (dist < 1.4 && !LightWards.shields(level, blockPosition())) {
            if (doHurtTarget(level, prey)) {
                level.playSound(
                        null,
                        getX(),
                        getY(),
                        getZ(),
                        SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR,
                        SoundSource.HOSTILE,
                        .25f,
                        1.9f);
            }
            recoil = RECOIL;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel l && isAlive() && burnsNow(l, this)) igniteForSeconds(8);
    }

    @Override
    public SoundEvent getAmbientSound() {
        return random.nextInt(3) == 0 ? SoundEvents.ZOMBIE_AMBIENT : SoundEvents.BAT_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ZOMBIE_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ZOMBIE_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * .8f;
    }

    /** A zombie's sunburn: bright day, open sky, not in water, rain or shade of a helmet. */
    static boolean burnsNow(ServerLevel l, LivingEntity e) {
        if (!l.isBrightOutside() || e.isInWaterOrRain() || e.isOnFire()) return false;
        var at = e.blockPosition();
        return l.canSeeSky(at)
                && e.getLightLevelDependentMagicValue() > .5f
                && e.getRandom().nextFloat() * 30 < 1;
    }
}
