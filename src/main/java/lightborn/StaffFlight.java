package lightborn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Flight (owner, 2026-11-18): you ride your staff through the air, like a witch's broom. The cast lifts you onto it;
 * forward flies where you look (up and down too), back slows, the side keys drift, Space rises, Shift gets off, and
 * with no key held it hangs in the air. Every second aloft costs {@link #MANA_PER_SECOND} mana; when the mana runs out
 * (or you get off) you drift down under Slow Falling. Casting it again while riding lands you, for free.
 * <p>
 * The ride is a vehicle the rider's own client steers (like a boat), so it answers the keys without lag; the server
 * only charges the mana, lands the rider and runs the perks. It is never saved with the world.
 */
public final class StaffFlight {
    private StaffFlight() {}

    /** Mana a second aloft (owner, 2026-11-18). */
    static final float MANA_PER_SECOND = 15;
    /** Top speed in blocks a tick before upgrades: 16 blocks a second, about a sprinting horse. */
    static final float BASE_SPEED = .8f;
    /** Slow Falling on getting off, in ticks; Soft Landing makes it 20 seconds. */
    static final int FALL_TICKS = 120, SOFT_FALL_TICKS = 400;

    public static EntityType<Mount> TYPE;

    static void init() {
        var key = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("staff_mount"));
        TYPE = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                key,
                EntityType.Builder.<Mount>of(Mount::new, MobCategory.MISC)
                        .sized(.8f, 1.8f)
                        .clientTrackingRange(10)
                        .updateInterval(1)
                        .build(key));
    }

    /** The cast: up onto the staff, or, already riding, down again for free (returns false so nothing is spent). */
    static boolean cast(ServerPlayer p) {
        if (p.getVehicle() instanceof Mount) {
            p.stopRiding();
            return false;
        }
        if (p.isPassenger()) {
            p.sendSystemMessage(
                    Component.literal("Get off what you are riding first.").withStyle(ChatFormatting.AQUA), true);
            return false;
        }
        ServerLevel level = p.level();
        var mount = new Mount(TYPE, level);
        mount.snapTo(p.getX(), p.getY() + .3, p.getZ(), p.getYRot(), 0);
        mount.setStaff(p.getMainHandItem().copyWithCount(1));
        mount.setSpeed((float) SpellUpgrades.scale(Spell.FLIGHT, SpellUpgrades.Stat.REACH, BASE_SPEED));
        mount.cost = (float) SpellUpgrades.scale(Spell.FLIGHT, SpellUpgrades.Stat.COST, MANA_PER_SECOND);
        mount.freeHover = SpellUpgrades.special(Spell.FLIGHT);
        if (!level.addFreshEntity(mount)) return false;
        if (!p.startRiding(mount, true, true)) {
            mount.discard();
            return false;
        }
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, 1f, 1.3f);
        level.playSound(
                null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.6f);
        level.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + .2, p.getZ(), 14, .5, .1, .5, .05);
        return true;
    }

    /** Wind Shield: a rider with it takes 30% less damage. */
    static float incoming(ServerPlayer p, float amount) {
        return p.getVehicle() instanceof Mount && SpellUpgrades.perk(p, Spell.FLIGHT, "Wind Shield")
                ? amount * .7f
                : amount;
    }

    /** Before a rider leaves the game: the staff is not saved, so the rider drifts down when they come back. */
    static void onDisconnect(ServerPlayer p) {
        if (p.getVehicle() instanceof Mount) fall(p);
    }

    static void fall(ServerPlayer p) {
        int ticks = SpellUpgrades.perk(p, Spell.FLIGHT, "Soft Landing") ? SOFT_FALL_TICKS : FALL_TICKS;
        p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, ticks, 0, false, false, true), p);
    }

    /** The staff in flight, with its rider on it. */
    public static final class Mount extends Entity {
        private static final EntityDataAccessor<ItemStack> STAFF =
                SynchedEntityData.defineId(Mount.class, EntityDataSerializers.ITEM_STACK);
        private static final EntityDataAccessor<Float> SPEED =
                SynchedEntityData.defineId(Mount.class, EntityDataSerializers.FLOAT);

        /** Mana each second aloft and whether hanging still is free (Still Air); set by the cast, server side. */
        float cost = MANA_PER_SECOND;

        boolean freeHover;
        /** Distance flown this second (server side), for Still Air. */
        private double flown;

        private Vec3 last;
        /** Monsters Ram has struck, to the tick it may strike them again. */
        private final Map<UUID, Integer> rammed = new HashMap<>();

        public Mount(EntityType<? extends Mount> type, Level level) {
            super(type, level);
            setNoGravity(true); // a vehicle with gravity off is never taken for a floating cheat
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder b) {
            b.define(STAFF, ItemStack.EMPTY);
            b.define(SPEED, BASE_SPEED);
        }

        public ItemStack staff() {
            return entityData.get(STAFF);
        }

        void setStaff(ItemStack stack) {
            entityData.set(STAFF, stack);
        }

        public float speed() {
            return entityData.get(SPEED);
        }

        void setSpeed(float speed) {
            entityData.set(SPEED, speed);
        }

        @Override
        protected void readAdditionalSaveData(ValueInput in) {}

        @Override
        protected void addAdditionalSaveData(ValueOutput out) {}

        @Override
        public boolean shouldBeSaved() {
            return false;
        }

        @Override
        public boolean isPickable() {
            return false;
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            return false;
        }

        @Override
        protected InterpolationHandler createInterpolationHandler() {
            return LinearInterpolationHandler.create(this, 3);
        }

        @Override
        public LivingEntity getControllingPassenger() {
            return getFirstPassenger() instanceof Player p ? p : null;
        }

        @Override
        protected Vec3 getPassengerAttachmentPoint(
                Entity passenger, net.minecraft.world.entity.EntityDimensions size, float scale) {
            return new Vec3(0, .55, 0); // the rider sits astride the staff, the box round them both
        }

        @Override
        public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
            return passenger.position(); // straight down off the staff, never onto the top of its box
        }

        @Override
        protected void removePassenger(Entity passenger) {
            super.removePassenger(passenger);
            if (level() instanceof ServerLevel level && passenger instanceof ServerPlayer p) {
                if (!p.onGround()) fall(p);
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.BREEZE_LAND, SoundSource.PLAYERS, .8f, 1.2f);
                if (!isRemoved()) discard();
            }
        }

        @Override
        public void tick() {
            super.tick();
            if (level().isClientSide()) {
                if (isLocalInstanceAuthoritative()) steer();
                return;
            }
            if (!(getFirstPassenger() instanceof ServerPlayer p) || !p.isAlive()) {
                discard();
                return;
            }
            Vec3 at = position(), step = last == null ? Vec3.ZERO : at.subtract(last);
            double moved = step.length();
            last = at;
            flown += moved;
            if (tickCount % 20 == 0) second(p);
            if (moved > .5 && SpellUpgrades.perk(p, Spell.FLIGHT, "Ram")) ram(p, step);
            if (moved > .15 && tickCount % 2 == 0)
                ((ServerLevel) level())
                        .sendParticles(ParticleTypes.END_ROD, getX(), getY() + .45, getZ(), 1, .15, .05, .15, .005);
        }

        /** Once a second aloft: the mana, unless it hung still with Still Air; out of mana, the rider comes down. */
        void second(ServerPlayer p) {
            boolean still = freeHover && flown < 1.5;
            flown = 0;
            if (still || p.isCreative()) return;
            float mana = Progression.mana(p);
            if (mana < cost) { // no message (owner, 2026-11-18): the slow fall says it
                p.stopRiding();
                return;
            }
            Progression.setMana(p, mana - cost);
        }

        /** Ram: flying fast into a monster strikes it for 6 and knocks it away. */
        private void ram(ServerPlayer p, Vec3 step) {
            var level = (ServerLevel) level();
            double moved = step.length();
            Vec3 heading = step.normalize();
            for (Mob m : level.getEntitiesOfClass(
                    Mob.class, getBoundingBox().inflate(.6), m -> m instanceof Enemy && m.isAlive())) {
                if (rammed.getOrDefault(m.getUUID(), 0) > tickCount) continue;
                rammed.put(m.getUUID(), tickCount + 20);
                m.hurtServer(level, p.damageSources().playerAttack(p), 6);
                m.push(heading.x * (.6 + moved), .3, heading.z * (.6 + moved));
                m.needsSync = true;
                level.playSound(
                        null, m.getX(), m.getY(), m.getZ(), SoundEvents.MACE_SMASH_AIR, SoundSource.PLAYERS, 1f, 1.1f);
            }
        }

        /**
         * The rider's client: forward flies along the look (pitch too), back slows, the side keys drift, Space rises;
         * with nothing held it eases to a stop and bobs.
         */
        private void steer() {
            if (!(getControllingPassenger() instanceof Player rider)) {
                setDeltaMovement(Vec3.ZERO);
                return;
            }
            setYRot(rider.getYRot());
            float speed = speed();
            Vec3 look = rider.getLookAngle();
            Vec3 right = new Vec3(-look.z, 0, look.x);
            if (right.lengthSqr() > 1e-6) right = right.normalize();
            double forward = rider.zza, side = rider.xxa;
            Vec3 want = look.scale(forward * (forward > 0 ? 1 : .35) * speed)
                    .add(right.scale(-side * .55 * speed))
                    .add(0, rider.isJumping() ? .45 * speed : 0, 0);
            Vec3 v = getDeltaMovement();
            if (want.lengthSqr() < 1e-6) {
                v = v.scale(.86);
                v = new Vec3(v.x, v.y + Math.sin(tickCount * .12) * .004, v.z); // hanging in the air, gently
            } else v = v.lerp(want, .14);
            setDeltaMovement(v);
            move(MoverType.SELF, v);
        }
    }
}
