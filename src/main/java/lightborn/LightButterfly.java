package lightborn;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * A Light Butterfly (owner, 2026-11-23: "add light butterflies; hitting them gives 1 (always at least) to 3 (rare)
 * sparks"; day and night). A small butterfly of living light that drifts over the Overworld's surface near players,
 * full-bright with a trail of tiny motes ({@code LightButterflyRenderer}), and darts away from anyone who comes within
 * {@link #SHY} blocks. One blow catches it: it bursts into light and leaves 1 Spark, 2 one time in four, 3 one time
 * in eight. It drops nothing else, makes no sound but the catch's chime, and fades on its own after
 * {@link #LIFE} ticks.
 */
public class LightButterfly extends AmbientCreature {
    /** How close a player comes before it darts away; how long it lives (3 minutes). */
    static final double SHY = 5;

    static final int LIFE = 3600;

    private Vec3 goal;

    public LightButterfly(EntityType<? extends LightButterfly> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 1).add(Attributes.FLYING_SPEED, .6);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity e) {}

    @Override
    protected void pushEntities() {}

    @Override
    protected void checkFallDamage(
            double dy, boolean onGround, net.minecraft.world.level.block.state.BlockState state, BlockPos pos) {}

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    protected MovementEmission getMovementEmission() {
        return MovementEmission.NONE;
    }

    @Override
    public void tick() {
        super.tick();
        // Fluttering: a small dip and lift every few ticks over the steady drift.
        setDeltaMovement(getDeltaMovement().multiply(1, .6, 1).add(0, Mth.sin(tickCount * .7f) * .02, 0));
        if (level() instanceof ServerLevel level) {
            if (tickCount % 10 == 0)
                level.sendParticles(ParticleTypes.END_ROD, getX(), getY() + .15, getZ(), 1, .08, .05, .08, .002);
            if (tickCount > LIFE) {
                level.sendParticles(ParticleTypes.END_ROD, getX(), getY() + .2, getZ(), 8, .2, .2, .2, .02);
                discard();
            }
        }
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        Player near = level.getNearestPlayer(this, SHY);
        boolean fleeing = near != null && !near.isSpectator();
        if (fleeing) {
            var away = position().subtract(near.position()).multiply(1, 0, 1);
            away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
            goal = position().add(away.scale(7)).add(0, 1 + random.nextDouble() * 2, 0);
        } else if (goal == null || goal.distanceToSqr(position()) < 1 || random.nextInt(40) == 0) {
            double x = getX() + random.nextInt(13) - 6, z = getZ() + random.nextInt(13) - 6;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
            goal = new Vec3(x, ground + 1 + random.nextDouble() * 3, z);
        }
        double speed = fleeing ? .55 : .25;
        Vec3 d = goal.subtract(position()), v = getDeltaMovement();
        Vec3 nv = v.add(
                (Math.signum(d.x) * speed - v.x) * .1,
                (Math.signum(d.y) * speed * 1.2 - v.y) * .1,
                (Math.signum(d.z) * speed - v.z) * .1);
        setDeltaMovement(nv);
        float yaw = (float) (Mth.atan2(nv.z, nv.x) * Mth.RAD_TO_DEG) - 90;
        setYRot(getYRot() + Mth.wrapDegrees(yaw - getYRot()) * .5f);
        setYBodyRot(getYRot());
        zza = .5f;
    }

    /** One blow from a player catches it: a burst of light and its Sparks. Anything else just ends it. */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (isRemoved() || isInvulnerableTo(level, source)) return false;
        if (source.getEntity() instanceof Player) {
            int roll = random.nextInt(40);
            int sparks = roll < 5 ? 3 : roll < 15 ? 2 : 1; // three 1 in 8, two 1 in 4, else one (of 40)
            var item = new ItemEntity(level, getX(), getY(), getZ(), new ItemStack(ModItems.SPARK, sparks));
            item.setDefaultPickUpDelay();
            level.addFreshEntity(item);
            level.sendParticles(ParticleTypes.END_ROD, getX(), getY() + .2, getZ(), 14, .15, .15, .15, .08);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getY() + .2, getZ(), 10, .2, .2, .2, .05);
            level.playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1f, 1.8f);
        } else level.sendParticles(ParticleTypes.END_ROD, getX(), getY() + .2, getZ(), 6, .15, .15, .15, .03);
        discard();
        return true;
    }

    // ---------------------------------------------------------------- spawning

    /**
     * Each minute one appears near each player in the Overworld (owner, 2026-11-23: "make it 1 each minute"), while none
     * is within this reach.
     */
    static final int EVERY = 1200, NEAR = 48;

    static final float CHANCE = 1f;

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % EVERY != 0) return;
        ServerLevel level = server.overworld();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || level.getRandom().nextFloat() >= CHANCE) continue;
            if (!level.getEntitiesOfClass(
                            LightButterfly.class, p.getBoundingBox().inflate(NEAR))
                    .isEmpty()) continue;
            spawnNear(level, p);
        }
    }

    /** One butterfly 12 to 28 blocks from the player, over open ground or water, never inside a block. */
    static LightButterfly spawnNear(ServerLevel level, ServerPlayer p) {
        var random = level.getRandom();
        for (int tries = 0; tries < 8; tries++) {
            double a = random.nextDouble() * Math.PI * 2, r = 12 + random.nextDouble() * 16;
            int x = Mth.floor(p.getX() + Math.cos(a) * r), z = Mth.floor(p.getZ() + Math.sin(a) * r);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1 + random.nextInt(3);
            var at = new BlockPos(x, y, z);
            if (!level.isEmptyBlock(at) || !level.canSeeSky(at)) continue;
            var b = ModEntities.LIGHT_BUTTERFLY.create(level, EntitySpawnReason.NATURAL);
            if (b == null) return null;
            b.snapTo(x + .5, y + .2, z + .5, random.nextFloat() * 360, 0);
            if (level.addFreshEntity(b)) return b;
        }
        return null;
    }
}
