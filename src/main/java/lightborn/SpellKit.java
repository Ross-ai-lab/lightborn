package lightborn;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Helpers every school of spells shares: target checks, particles, pushes, conjured blocks,
 * the daze, and the spell displays every school tracks (never kept in a saved world).
 */
public final class SpellKit {
    private SpellKit() {}

    static void tick(MinecraftServer server) {
        tickDazed();
    }

    /** Server stopping, or a self-test's clean slate: every display goes, and with it the daze and conjured blocks. */
    static void clear() {
        for (Display d : new ArrayList<>(DISPLAYS)) d.discard();
        DISPLAYS.clear();
        DAZED.clear();
        ConjuredBlock.ORIGINAL.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "displays" -> DISPLAYS.size();
            case "dazed" -> DAZED.size();
            default -> -1;
        };
    }

    /** Chunk load: a spell display this server does not track is a crash leftover. True when it was one. */
    static boolean onLoad(Entity e) {
        if (!e.entityTags().contains(FX_TAG)) return false;
        if (!(e instanceof Display d && DISPLAYS.contains(d))) e.discard();
        return true;
    }

    // ---------------------------------------------------------------- Shared

    /** Whether the caster owns a spell's upgrade branch (a short name for {@link SpellUpgrades#perk}). */
    static boolean perk(ServerPlayer p, Spell spell, String name) {
        return SpellUpgrades.perk(p, spell, name);
    }

    /** Spell displays: never kept in a saved world. */
    static final String FX_TAG = "lightborn_fx";

    static void sound(ServerLevel level, Vec3 at, SoundEvent s, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, s, SoundSource.PLAYERS, volume, pitch);
    }

    static Vec3 chest(Entity e) {
        return e.position().add(0, e.getBbHeight() * 0.6, 0);
    }

    static double knockResist(LivingEntity e) {
        AttributeInstance kb = e.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        return kb == null ? 0 : Math.min(1, Math.max(0, kb.getValue()));
    }

    /** Where the player aims: the creature looked at, else the block, within range; null if neither. */
    static Vec3 aimPoint(ServerPlayer p, double range) {
        ServerLevel level = p.level();
        Vec3 eye = p.getEyePosition();
        BlockHitResult hr = level.clip(new ClipContext(
                eye, eye.add(p.getLookAngle().scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        LivingEntity aimed = Spells.lookTarget(p, range);
        if (aimed != null
                && (hr.getType() == HitResult.Type.MISS
                        || aimed.distanceToSqr(eye) < hr.getLocation().distanceToSqr(eye))) return aimed.position();
        return hr.getType() == HitResult.Type.MISS ? null : hr.getLocation();
    }

    /** A line of particles from a to b, one every {@code spacing} blocks. */
    static void line(ServerLevel level, Vec3 a, Vec3 b, ParticleOptions particle, double spacing) {
        double len = a.distanceTo(b);
        int n = Math.max(1, (int) (len / spacing));
        for (int i = 0; i <= n; i++) {
            Vec3 q = a.lerp(b, i / (double) n);
            level.sendParticles(particle, q.x, q.y, q.z, 1, 0, 0, 0, 0);
        }
    }

    static final List<Display> DISPLAYS = new ArrayList<>();

    static <T extends Display> T track(ServerLevel level, T d, Vec3 at) {
        d.setPos(at.x, at.y, at.z);
        d.setBrightnessOverride(new Brightness(15, 15));
        d.setPosRotInterpolationDuration(1);
        d.addTag(FX_TAG);
        DISPLAYS.add(d);
        level.addFreshEntity(d);
        return d;
    }

    static void drop(Display d) {
        if (d == null) return;
        d.discard();
        DISPLAYS.remove(d);
    }

    /** A block drawn {@code size} big, turned by {@code rot} about its own centre. */
    static void centred(Display d, float size, Quaternionf rot, int interpolate) {
        Vector3f offset = rot.transform(new Vector3f(size / 2)).negate();
        d.setTransformation(new Transformation(offset, rot, new Vector3f(size), new Quaternionf()));
        d.setTransformationInterpolationDelay(0);
        d.setTransformationInterpolationDuration(interpolate);
    }

    static boolean monster(ServerPlayer caster, LivingEntity e) {
        return e instanceof Enemy && Spells.canHitArea(caster, e);
    }

    static double distToSegment(Vec3 q, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double t = ab.lengthSqr() < 1e-9
                ? 0
                : Math.max(0, Math.min(1, q.subtract(a).dot(ab) / ab.lengthSqr()));
        return q.distanceTo(a.add(ab.scale(t)));
    }

    static Vec3 hand(ServerPlayer p) {
        Vec3 look = p.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0, look.x);
        right = right.lengthSqr() < 1e-6 ? Vec3.ZERO : right.normalize();
        return p.getEyePosition().add(look.scale(.5)).add(right.scale(.38)).add(0, -.45, 0);
    }

    /** A little ahead of the caster's hand: where a line drawn from the hand starts, clear of the caster's view. */
    static Vec3 ahead(ServerPlayer p) {
        return hand(p).add(p.getLookAngle().scale(1.2));
    }

    /** Particles only the players round the caster see: at the caster's own hand they would fill the caster's view. */
    static void forOthers(ServerPlayer p, ParticleOptions particle, Vec3 at, int count, double spread, double speed) {
        for (var viewer : p.level().players())
            if (viewer != p && viewer.distanceToSqr(p) < 48 * 48)
                p.level()
                        .sendParticles(
                                viewer, particle, false, false, at.x, at.y, at.z, count, spread, spread, spread, speed);
    }

    /** A cast's flourish round the caster's feet, where the caster sees it without it blinding them. */
    static void atFeet(ServerPlayer p, ParticleOptions ring, ParticleOptions sparks, int count) {
        Spells.ring(p.level(), p.position().add(0, .1, 0), .9, ring);
        p.level().sendParticles(sparks, p.getX(), p.getY() + .2, p.getZ(), count, .45, .1, .45, .03);
    }

    static void pushPlayer(ServerPlayer p, Vec3 v) {
        p.setDeltaMovement(v);
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
    }

    /** The floor under a point, looking down as far as {@code depth} blocks. */
    static Vec3 floor(ServerLevel level, Vec3 at, int depth) {
        BlockPos b = BlockPos.containing(at);
        for (int i = 0;
                i < depth
                        && level.getBlockState(b.below())
                                .getCollisionShape(level, b.below())
                                .isEmpty();
                i++) b = b.below();
        return new Vec3(at.x, b.getY(), at.z);
    }

    /** Where the caster aims: the block hit, or the end of the reach. */
    static Vec3 aim(ServerLevel level, ServerPlayer p, double reach) {
        Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(reach));
        BlockHitResult hit =
                level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
    }

    static boolean mighty(LivingEntity e) {
        return e instanceof Mob m && Cage.mighty(m);
    }

    static void say(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY), true);
    }

    static void push(Entity e, Vec3 v) {
        e.setDeltaMovement(v);
        if (e instanceof ServerPlayer sp) sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
        else e.needsSync = true;
    }

    /** Throws {@code e} away from {@code from}, less for what resists knockback; bosses are never thrown. */
    static void throwFrom(LivingEntity e, Vec3 from, double out, double up) {
        if (mighty(e)) return;
        Vec3 away = e.position().subtract(from).multiply(1, 0, 1);
        away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
        double keep = 1 - SpellKit.knockResist(e);
        push(e, away.scale(out * keep).add(0, up * keep, 0));
    }

    static boolean clearLine(ServerLevel level, Vec3 a, Vec3 b, Entity by) {
        return level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, by))
                        .getType()
                == HitResult.Type.MISS;
    }

    /** Removes every harmful effect and puts out fire. */
    static void cleanse(LivingEntity e) {
        for (var effect : new ArrayList<>(e.getActiveEffects()))
            if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL)
                e.removeEffect(effect.getEffect());
        e.clearFire();
        e.setTicksFrozen(0);
    }

    static List<ServerPlayer> withFriends(ServerPlayer p, boolean shared) {
        List<ServerPlayer> all = new ArrayList<>(List.of(p));
        if (shared)
            for (var o : p.level()
                    .getEntitiesOfClass(ServerPlayer.class, p.getBoundingBox().inflate(8)))
                if (o != p && o.isAlive() && !o.isSpectator()) all.add(o);
        return all;
    }

    static void flourish(ServerPlayer p, int colour) {
        Spells.ring(p.level(), p.position().add(0, .1, 0), .9, new DustParticleOptions(colour, 1.3f));
        p.level().sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + .2, p.getZ(), 10, .45, .1, .45, .03);
    }

    /** Throws {@code e} along {@code dir} (flattened), as hard as its knockback resistance allows. */
    static void fling(LivingEntity e, Vec3 dir, double pushPlayer, double up) {
        Vec3 flat = dir.multiply(1, 0, 1);
        flat = flat.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : flat.normalize();
        double keep = 1 - SpellKit.knockResist(e);
        e.setDeltaMovement(flat.scale(pushPlayer * keep).add(0, up * keep, 0));
        e.needsSync = true;
    }

    // ---------------------------------------------------------------- Conjured blocks (Stone Wall, Light Bridge)

    /** Puts a conjured block where nothing solid stands, remembering what it replaced; true when placed. */
    static boolean conjure(ServerLevel level, BlockPos pos, Block block, int ticks) {
        BlockState old = level.getBlockState(pos);
        if (old.getBlock() instanceof ConjuredBlock) {
            level.scheduleTick(pos, old.getBlock(), ticks);
            return true;
        }
        boolean fluid = !old.getFluidState().isEmpty()
                && old.getFluidState().isSource()
                && old.isAir() == false
                && old.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
        if (!(old.isAir() || old.canBeReplaced() || fluid) || level.getBlockEntity(pos) != null) return false;
        ConjuredBlock.remember(level, pos, old);
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
        level.scheduleTick(pos, block, ticks);
        return true;
    }

    // ---------------------------------------------------------------- The daze (Trap)

    static final int DAZE_TICKS = 100;
    static final Map<Mob, Integer> DAZED = new IdentityHashMap<>();

    /**
     * Blinds {@code e} and leaves it lost for {@code ticks}: slowed, with no target, wandering and turning about at
     * random (the Trap's burst, Blinding Shatter). Bosses only take the blindness.
     */
    static void daze(LivingEntity e, ServerPlayer by, int ticks) {
        if (!e.isAlive()) return;
        e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, ticks, 0), by);
        e.addEffect(new MobEffectInstance(MobEffects.NAUSEA, ticks, 0), by);
        if (mighty(e) || !(e instanceof Mob m)) return;
        e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 1), by);
        DAZED.merge(m, ticks, Math::max);
        m.setTarget(null);
        m.getNavigation().stop();
    }

    static boolean dazed(LivingEntity e) {
        return e instanceof Mob m && DAZED.containsKey(m);
    }

    static void tickDazed() {
        for (var it = DAZED.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var m = entry.getKey();
            int left = entry.getValue() - 1;
            if (m.isRemoved() || !m.isAlive() || left < 0) {
                it.remove();
                continue;
            }
            entry.setValue(left);
            m.setTarget(null);
            var random = m.getRandom();
            if (left % 15 == 0) {
                // Stumbles a few steps somewhere, then turns about.
                double a = random.nextDouble() * Math.PI * 2;
                m.getNavigation().moveTo(m.getX() + Math.cos(a) * 3, m.getY(), m.getZ() + Math.sin(a) * 3, .7);
            }
            if (left % 5 == 0) {
                float turn = m.getYHeadRot() + (random.nextFloat() - .5f) * 120;
                m.setYHeadRot(turn);
                ((ServerLevel) m.level())
                        .sendParticles(
                                new DustParticleOptions(0xE7B8FF, .8f),
                                m.getX(),
                                m.getY() + m.getBbHeight() + .25,
                                m.getZ(),
                                2,
                                .25,
                                .05,
                                .25,
                                0);
            }
        }
    }

    static Vec3 flat(Vec3 v) {
        Vec3 f = v.multiply(1, 0, 1);
        return f.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : f.normalize();
    }
}
