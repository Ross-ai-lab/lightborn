package lightborn;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Cage (owner, 2026-11-18): the monster or animal you look at (owner, 2026-11-18: animals too), within
 * {@link #REACH} blocks, is shut in a cage of iron bars for
 * twenty seconds. Inside it cannot move, turn, strike or be hurt (its mind is stilled, it is held where it stood, and
 * {@link #harmless} turns every blow away; owner, 2026-11-18). The cage is real to the eye: four walls of iron bars and a
 * barred lid, drawn full-bright round the monster's own size, with motes of light rising inside. It can't be cast on
 * bosses.
 * <p>
 * Caged monsters live in memory only; one saved mid-cage (a crash, its chunk unloading) carries {@link #TAG} and is
 * set free when it loads again.
 */
public final class Cage {
    private Cage() {}

    static final int REACH = 16, TICKS = 400;
    static final String TAG = "lightborn_caged";

    private record Held(Mob mob, Vec3 at, long until, boolean wasStill, UUID caster, List<Display> bars) {}

    private static final Map<Mob, Held> CAGED = new LinkedHashMap<>();
    /** Long Shadow: monsters freed from a cage, and the tick until which they deal 30% less damage. */
    private static final Map<Entity, Long> SHADOWED = new java.util.WeakHashMap<>();

    static final int SHADOW_TICKS = 200;
    static final float SHADOW_DAMAGE = .7f;

    /** The cast: cages the monster in sight, and with Twin Cage the nearest others beside it. */
    static boolean cast(ServerPlayer p) {
        ServerLevel level = p.level();
        Mob first = target(p, SpellUpgrades.scaleInt(Spell.CAGE, SpellUpgrades.Stat.REACH, REACH));
        if (first == null) {
            p.sendSystemMessage(Component.literal("Nothing in sight to cage.").withStyle(ChatFormatting.AQUA), true);
            return false;
        }
        int ticks = SpellUpgrades.scaleInt(Spell.CAGE, SpellUpgrades.Stat.DURATION, TICKS);
        int count = SpellUpgrades.add(Spell.CAGE, SpellUpgrades.Stat.COUNT, 1);
        var targets = new ArrayList<Mob>(List.of(first));
        if (count > 1)
            level
                    .getEntitiesOfClass(
                            Mob.class,
                            first.getBoundingBox().inflate(6),
                            // Twin Cage keeps to the first one's kind: monsters beside a monster, animals beside an
                            // animal.
                            m -> m != first && cageable(p, m) && (m instanceof Enemy) == (first instanceof Enemy))
                    .stream()
                    .sorted(Comparator.comparingDouble(m -> m.distanceToSqr(first)))
                    .limit(count - 1)
                    .forEach(targets::add);
        for (Mob m : targets) shut(level, p, m, ticks);
        return true;
    }

    /** The first monster or animal the look ray meets before a wall. */
    static Mob target(ServerPlayer p, int reach) {
        var level = p.level();
        Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(reach));
        var wall = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (wall.getType() != HitResult.Type.MISS) end = wall.getLocation();
        Mob best = null;
        double bestD = Double.MAX_VALUE;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(eye, end).inflate(1.5), m -> cageable(p, m))) {
            var hit = m.getBoundingBox().inflate(.35).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = hit.get().distanceToSqr(eye);
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    static boolean cageable(ServerPlayer p, Mob m) {
        // Monsters, and every animal, water creature and bat (not villagers or golems, whose category is MISC).
        return (m instanceof Enemy || m.getType().getCategory() != MobCategory.MISC)
                && m.isAlive()
                && !CAGED.containsKey(m)
                && m.getAttached(ModData.THRALL_MASTER) == null
                && !mighty(m);
    }

    /** Bosses and the vanilla giants: no cage holds them, and no ice ({@link Freeze}). */
    static boolean mighty(Mob m) {
        return Lightborn.isBoss(m)
                || m.getType() == EntityTypes.WITHER
                || m.getType() == EntityTypes.ENDER_DRAGON
                || m.getType() == EntityTypes.WARDEN
                || m.getType() == EntityTypes.ELDER_GUARDIAN;
    }

    private static void shut(ServerLevel level, ServerPlayer p, Mob m, int ticks) {
        boolean wasStill = m.isNoAi();
        m.setNoAi(true);
        m.setTarget(null);
        m.getNavigation().stop();
        m.setDeltaMovement(Vec3.ZERO);
        if (!wasStill) m.addTag(TAG);
        Vec3 at = m.position();
        float w = m.getBbWidth() + .5f, h = m.getBbHeight() + .3f;
        BlockState bars = Blocks.IRON_BARS
                .defaultBlockState()
                .setValue(CrossCollisionBlock.EAST, true)
                .setValue(CrossCollisionBlock.WEST, true);
        var made = new ArrayList<Display>();
        // Four walls: a bars pane w wide and h tall, turned to each side, standing w/2 out from the middle.
        for (int k = 0; k < 4; k++) {
            var q = new Quaternionf().rotateY((float) (k * Math.PI / 2));
            made.add(pane(
                    level, at, bars, q, q.transform(new Vector3f(-w / 2, 0, -w / 2 - .5f)), new Vector3f(w, h, 1)));
        }
        // The lid: two panes laid flat across each other.
        made.add(pane(
                level,
                at,
                bars,
                new Quaternionf().rotateX((float) (Math.PI / 2)),
                new Vector3f(-w / 2, h + .5f, -w / 2),
                new Vector3f(w, w, 1)));
        made.add(pane(
                level,
                at,
                bars,
                new Quaternionf().rotateY((float) (Math.PI / 2)).rotateX((float) (Math.PI / 2)),
                new Vector3f(-w / 2, h + .52f, w / 2),
                new Vector3f(w, w, 1)));
        made.removeIf(d -> d == null);
        CAGED.put(m, new Held(m, at, level.getGameTime() + ticks, wasStill, p.getUUID(), made));
        level.playSound(null, at.x, at.y, at.z, SoundEvents.IRON_DOOR_CLOSE, SoundSource.PLAYERS, 1f, .7f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1f, .8f);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + h / 2, at.z, 40, w / 2, h / 2, w / 2, .4);
    }

    private static Display pane(
            ServerLevel level, Vec3 at, BlockState bars, Quaternionf rot, Vector3f shift, Vector3f size) {
        var d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (d == null) return null;
        d.setBlockState(bars);
        d.setTransformation(new Transformation(shift, rot, size, new Quaternionf()));
        return SpellKit.track(level, d, at);
    }

    /** Each server tick: holds every caged monster in place, runs the perks, and opens cages whose time is up. */
    static void tick(MinecraftServer server) {
        if (CAGED.isEmpty()) return;
        for (var held : List.copyOf(CAGED.values())) {
            Mob m = held.mob();
            if (m.isRemoved() || !m.isAlive() || !(m.level() instanceof ServerLevel level)) {
                close(held, false);
                continue;
            }
            if (level.getGameTime() >= held.until()) {
                close(held, true);
                continue;
            }
            if (m.position().distanceToSqr(held.at()) > .0025) m.teleportTo(held.at().x, held.at().y, held.at().z);
            m.setDeltaMovement(Vec3.ZERO);
            var p = server.getPlayerList().getPlayer(held.caster());
            long t = level.getGameTime();
            if (t % 10 == 0)
                level.sendParticles(
                        ParticleTypes.END_ROD,
                        m.getX(),
                        m.getY() + m.getBbHeight() * .3,
                        m.getZ(),
                        1,
                        m.getBbWidth() * .4,
                        m.getBbHeight() * .3,
                        m.getBbWidth() * .4,
                        .01);
            if (p == null || t % 20 != 0) continue;
            if (SpellUpgrades.perk(p, Spell.CAGE, "Holding Cell"))
                for (Mob near : level.getEntitiesOfClass(
                        Mob.class,
                        m.getBoundingBox().inflate(4),
                        n -> n instanceof Enemy && n != m && !CAGED.containsKey(n)))
                    near.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 1), p);
        }
    }

    /** Opens a cage: the bars vanish and the monster wakes; with Shatter (time up only) the bars burst outward. */
    private static void close(Held held, boolean timeUp) {
        CAGED.remove(held.mob());
        for (Display d : held.bars()) SpellKit.drop(d);
        Mob m = held.mob();
        if (m.isRemoved()) return;
        m.setNoAi(held.wasStill());
        m.removeTag(TAG);
        if (!(m.level() instanceof ServerLevel level)) return;
        var p = level.getServer().getPlayerList().getPlayer(held.caster());
        if (p != null && m.isAlive() && SpellUpgrades.perk(p, Spell.CAGE, "Long Shadow")) {
            SHADOWED.put(m, level.getGameTime() + SHADOW_TICKS);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, m.getX(), m.getY() + 1, m.getZ(), 16, .4, .6, .4, .01);
        }
        if (!timeUp) return;
        level.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.IRON_DOOR_OPEN, SoundSource.PLAYERS, 1f, .7f);
        if (p != null && m.isAlive() && SpellUpgrades.perk(p, Spell.CAGE, "Shatter")) {
            level.playSound(
                    null, m.getX(), m.getY(), m.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.2f, .6f);
            level.sendParticles(ParticleTypes.CRIT, m.getX(), m.getY() + 1, m.getZ(), 30, .8, .8, .8, .5);
            m.hurtServer(level, p.damageSources().playerAttack(p), 8);
            for (Mob near : level.getEntitiesOfClass(
                    Mob.class, m.getBoundingBox().inflate(3), n -> n instanceof Enemy && n.isAlive())) {
                Vec3 away = near.position().subtract(m.position());
                if (near != m && away.lengthSqr() > 1e-4) {
                    Vec3 push = away.normalize().scale(.9);
                    near.push(push.x, .35, push.z);
                    near.needsSync = true;
                }
            }
        }
    }

    /** Opens the cage round {@code m} now, if it is caged (tests). */
    static void release(Mob m) {
        var held = CAGED.get(m);
        if (held != null) close(held, false);
    }

    static boolean caged(Entity e) {
        return e instanceof Mob m && CAGED.containsKey(m);
    }

    /**
     * Damage hook: false while {@code e} is caged, so nothing hurts it inside the bars. Only what bypasses
     * invulnerability (the void, /kill) still gets through.
     */
    static boolean harmless(Entity e, net.minecraft.world.damagesource.DamageSource source) {
        return caged(e) && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    /** Long Shadow: what a monster freed from a shadowed cage deals, 30% less for ten seconds after it opens. */
    static float shadowed(Entity attacker, float amount) {
        Long until = attacker == null ? null : SHADOWED.get(attacker);
        if (until == null) return amount;
        if (attacker.level().getGameTime() > until) {
            SHADOWED.remove(attacker);
            return amount;
        }
        return amount * SHADOW_DAMAGE;
    }

    static int bars(Mob m) {
        var held = CAGED.get(m);
        return held == null ? 0 : held.bars().size();
    }

    /** Chunk load: a monster saved inside a cage this server no longer holds walks free. */
    static void onLoad(Entity e) {
        if (e instanceof Mob m && m.entityTags().contains(TAG) && !CAGED.containsKey(m)) {
            m.setNoAi(false);
            m.removeTag(TAG);
        }
    }
}
