package lightborn;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** A peaceful, ephemeral apparition. Its chest, rather than wandering AI, owns its position and lifetime. */
public final class LightDragon extends PathfinderMob {
    static void init() {} // Register synced attachments before a client can receive an entity's spawn data.

    private static final AttachmentType<Integer> ROLE = AttachmentRegistry.create(
            ModItems.id("dragon_role"),
            b -> b.initializer(() -> 0).syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.all()));

    public int role() {
        return getAttachedOrElse(ROLE, 0);
    }

    void role(int role) {
        setAttached(ROLE, role);
    }

    private static final AttachmentType<Long> FADE_AT = AttachmentRegistry.create(
            ModItems.id("dragon_fade_at"), b -> b.syncWith(ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all()));
    private BlockPos chest;
    private int phaseTicks;
    /** The pearl's centre from his feet at full size, in blocks, facing south (tools/generate_dragon.py prints it). */
    private static final double PEARL_X = 0, PEARL_Y = 1.55, PEARL_Z = .49;

    public float opacity(float partial) {
        float left = phaseTicks - partial;
        return left <= 0 ? 1 : left >= 10 ? .25f : 1 - left * .075f;
    }

    public void showRefraction() {
        phaseTicks = 30;
    }

    @Override
    public void handleEntityEvent(byte event) {
        if (event == 97) {
            showRefraction();
            return;
        }
        super.handleEntityEvent(event);
    }

    public void refract() {
        showRefraction();
        if (level() instanceof ServerLevel l) {
            l.broadcastEntityEvent(this, (byte) 97);
            l.sendParticles(ParticleTypes.GLOW, getX(), getY() + 1, getZ(), 12, .35, .7, .35, .02);
            l.playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, .3f, 1.4f);
        }
    }

    /** His size against his full shape (the SCALE attribute, so his hitbox and model follow, synced to clients). */
    public void size(double size) {
        var scale = getAttribute(Attributes.SCALE);
        if (scale != null && scale.getBaseValue() != size) scale.setBaseValue(size);
    }

    void bind(BlockPos pos) {
        chest = pos.immutable();
    }

    public BlockPos chest() {
        return chest;
    }

    public boolean departing() {
        return getAttachedOrElse(FADE_AT, -1L) >= 0;
    }

    public float manifestation(float partial) {
        long fade = getAttachedOrElse(FADE_AT, -1L);
        return fade >= 0
                ? Math.clamp(1 - (level().getGameTime() - fade + partial) / 40f, 0, 1)
                : Math.clamp((tickCount + partial) / 40f, 0, 1);
    }

    void arrive() {
        if (!(level() instanceof ServerLevel l)) return;
        l.playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_AMBIENT, SoundSource.AMBIENT, .85f, .7f);
        l.playSound(null, blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.AMBIENT, 1.1f, .75f);
    }

    void depart() {
        if (departing() || isRemoved()) return;
        setAttached(FADE_AT, level().getGameTime());
        if (level() instanceof ServerLevel l)
            l.playSound(null, blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.AMBIENT, .65f, 1.1f);
    }

    void remain() {
        if (departing()) removeAttached(FADE_AT);
    }

    public LightDragon(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setNoAi(true);
        setNoGravity(true);
        setPermanentlyInvulnerable(true);
        setPersistenceRequired();
        noPhysics = true;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (role() == 1) return super.hurtServer(level, source, amount);
        if (source.getEntity() instanceof Player) refract();
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (role() == 5) {
            if (player instanceof ServerPlayer p && DragonStory.validGate(p))
                ServerPlayNetworking.send(p, DragonStory.prisonReply(p, 0));
            return InteractionResult.SUCCESS;
        }
        if (role() != 0) return InteractionResult.PASS;
        // Sneak-use with something in hand offers it as a gift (the owner's sneak key is Ctrl).
        if (player instanceof ServerPlayer p
                && !departing()
                && !(hand == InteractionHand.MAIN_HAND && p.isSecondaryUseActive() && DragonFavours.offer(p, this)))
            DragonTalk.open(p, this);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void push(Entity other) {}

    @Override
    public void push(double x, double y, double z) {}

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        boolean transit = passenger instanceof LivingEntity l && ChestTravel.flying(l);
        return role() == 7
                ? new Vec3(0, -.8, 0)
                : role() == 6 || transit
                        ? new Vec3(0, -.8, -.35)
                        : super.getPassengerAttachmentPoint(passenger, dimensions, scale);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public void checkDespawn() {}

    @Override
    public void tick() {
        if (phaseTicks > 0) phaseTicks--;
        setDeltaMovement(0, 0, 0);
        super.tick();
        setDeltaMovement(0, 0, 0);
        if (level() instanceof ServerLevel l) {
            var player = l.getNearestPlayer(this, 14);
            if (player != null && role() != 3 && role() != 6 && role() != 7) {
                double dx = player.getX() - getX(), dz = player.getZ() - getZ();
                float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
                float pitch =
                        (float) -Math.toDegrees(Math.atan2(player.getEyeY() - getEyeY(), Math.sqrt(dx * dx + dz * dz)));
                setYHeadRot(getYRot() + Math.clamp(Mth.wrapDegrees(yaw - getYRot()), -65, 65));
                setXRot(Math.clamp(pitch, -25, 35));
            }
            long fade = getAttachedOrElse(FADE_AT, -1L);
            boolean leaving = fade >= 0;
            float age = leaving ? l.getGameTime() - fade : tickCount;
            if (leaving && age >= 40) {
                discard();
                return;
            }
            if (age < 40) {
                double progress = age / 40, radius = leaving ? .3 + progress : 1.3 * (1 - progress);
                for (int arm = 0; arm < 3; arm++) {
                    double angle = age * .38 + arm * Math.PI * 2 / 3;
                    if ((int) age % (leaving ? 4 : 3) == 0)
                        l.sendParticles(
                                ParticleTypes.END_ROD,
                                getX() + Math.cos(angle) * radius,
                                getY() + progress * 2.5,
                                getZ() + Math.sin(angle) * radius,
                                1,
                                0,
                                .025,
                                0,
                                .005);
                    if (!leaving)
                        l.sendParticles(
                                ParticleTypes.ELECTRIC_SPARK,
                                getX() + Math.cos(angle) * radius,
                                getY() + progress * 2,
                                getZ() + Math.sin(angle) * radius,
                                1,
                                0,
                                0,
                                0,
                                0);
                }
                if (age == 20)
                    l.playSound(
                            null,
                            blockPosition(),
                            SoundEvents.AMETHYST_BLOCK_CHIME,
                            SoundSource.AMBIENT,
                            leaving ? .5f : 1.2f,
                            leaving ? 1.2f : .65f);
            }
        }
        if (level().isClientSide() && tickCount % 5 == 0) {
            // Motes drift off his body, and the pearl in his claw sheds light (offsets from tools/generate_dragon.py).
            float size = getScale(), yaw = -yBodyRot * Mth.DEG_TO_RAD;
            Vec3 body = new Vec3(
                            (random.nextDouble() - .5) * 4.2,
                            .3 + random.nextDouble() * 2.6,
                            (random.nextDouble() - .2) * 1.4)
                    .scale(size)
                    .yRot(yaw);
            level().addParticle(ParticleTypes.END_ROD, getX() + body.x, getY() + body.y, getZ() + body.z, 0, .012, 0);
            if (tickCount % 10 == 0) {
                Vec3 pearl = new Vec3(PEARL_X, PEARL_Y, PEARL_Z).scale(size).yRot(yaw);
                level().addParticle(
                                ParticleTypes.ELECTRIC_SPARK,
                                getX() + pearl.x + (random.nextDouble() - .5) * .3,
                                getY() + pearl.y + (random.nextDouble() - .5) * .3,
                                getZ() + pearl.z + (random.nextDouble() - .5) * .3,
                                0,
                                .02,
                                0);
            }
        }
    }
}
