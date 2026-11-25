package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.chest;
import static lightborn.SpellKit.knockResist;
import static lightborn.SpellKit.sound;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.AREA;
import static lightborn.SpellUpgrades.Stat.COUNT;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.Spells.canHitArea;
import static lightborn.Spells.hit;
import static lightborn.Spells.lookTarget;
import static lightborn.Spells.sound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Storm spells: lightning, wind and water. Chain Lightning, Ball Lightning, Tempest, Cyclone and Water Splash.
 */
public final class StormSpells {
    private StormSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case CHAIN_LIGHTNING -> {
                int stormcaller = TomeAwakening.awake(p, spell) ? 3 : 0;
                yield chainLightning(level, p, dmg, SpellUpgrades.add(spell, COUNT, 4 + stormcaller));
            }
            case BALL_LIGHTNING -> ballLightning(level, p, dmg);
            case TEMPEST -> tempest(level, p, dmg);
            case CYCLONE -> cyclone(level, p, dmg);
            case WATER_SPLASH ->
                waterSplash(level, p, dmg, SpellUpgrades.special(spell)) && waterSplashBranches(p, spell);
            default -> throw new IllegalArgumentException(spell + " is not a storm spell");
        };
    }

    static void tick(MinecraftServer server) {
        tickWaves();
        tickOrbs();
        tickGales();
        tickTempests();
    }

    static void clear() {
        WAVES.clear();
        ORBS.clear();
        GALES.clear();
        TEMPESTS.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "waves" -> WAVES.size();
            case "orbs" -> ORBS.size();
            case "gales" -> GALES.size();
            case "tempests" -> TEMPESTS.size();
            default -> -1;
        };
    }

    /** Water Splash's tier II branch, acting once as it is cast: Ride the Wave carries you forward. */
    private static boolean waterSplashBranches(ServerPlayer p, Spell spell) {
        if (SpellUpgrades.perk(p, spell, "Ride the Wave")) {
            Vec3 v = p.getLookAngle().multiply(1, 0, 1);
            v = v.lengthSqr() < 1e-4 ? Vec3.ZERO : v.normalize().scale(1.2);
            p.setDeltaMovement(v.add(0, .3, 0));
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
            p.resetFallDistance();
        }
        return true;
    }

    // ---------------------------------------------------------------- Chain Lightning

    static boolean chainLightning(ServerLevel level, ServerPlayer p, double dmg, int jumps) {
        LivingEntity first = lookTarget(p, 24);
        if (first == null) {
            p.sendSystemMessage(Component.literal("No target in sight").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        sound(level, p, SoundEvents.LIGHTNING_BOLT_IMPACT, 1.0f, 1.4f);
        level.playSound(
                null,
                first.getX(),
                first.getY(),
                first.getZ(),
                SoundEvents.TRIDENT_THUNDER,
                SoundSource.PLAYERS,
                0.5f,
                1.6f);
        Set<LivingEntity> hitSet = new HashSet<>();
        Vec3 from = p.getEyePosition().add(0, -0.3, 0);
        LivingEntity current = first, last = first;
        double amount = dmg;
        for (int i = 0; i <= jumps && current != null; i++) {
            Vec3 to = current.position().add(0, current.getBbHeight() * 0.6, 0);
            bolt(level, from, to);
            hit(p, current, amount);
            hitSet.add(current);
            last = current;
            amount *= 0.85;
            from = to;
            LivingEntity next = null;
            double best = 8 * 8;
            for (LivingEntity e : level.getEntitiesOfClass(
                    LivingEntity.class,
                    current.getBoundingBox().inflate(8),
                    e -> canHitArea(p, e) && !hitSet.contains(e))) {
                double d = e.distanceToSqr(current);
                if (d < best) {
                    best = d;
                    next = e;
                }
            }
            current = next;
        }
        if (SpellUpgrades.perk(p, Spell.CHAIN_LIGHTNING, "Overload")) {
            Vec3 at = last.position().add(0, last.getBbHeight() * .5, 0);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 40, 1.2, .6, 1.2, .3);
            Perks.burst(p, at, 3, dmg * .6, ParticleTypes.ELECTRIC_SPARK);
        }
        if (SpellUpgrades.perk(p, Spell.CHAIN_LIGHTNING, "Ball Lightning")) chainBall(level, p, first, dmg * .25);
        return true;
    }

    /** Ball Lightning: an orb at the first target's spot, zapping the nearest monster each half second for 3 s. */
    private static void chainBall(ServerLevel level, ServerPlayer p, LivingEntity first, double zap) {
        Vec3 orb = first.position().add(0, first.getBbHeight() * .6 + .5, 0);
        for (int t = 0; t <= 60; t += 2) {
            int tick = t;
            Scheduler.later(t, () -> {
                if (!p.isAlive() || p.level() != level) return;
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, orb.x, orb.y, orb.z, 6, .2, .2, .2, .05);
                level.sendParticles(new DustParticleOptions(0xBFE6FF, 1.4f), orb.x, orb.y, orb.z, 3, .12, .12, .12, 0);
                if (tick % 10 != 0) return;
                var near = Perks.nearestMonsters(p, null, orb, 5, 1);
                if (near.isEmpty()) return;
                LivingEntity e = near.getFirst();
                bolt(level, orb, e.position().add(0, e.getBbHeight() * .6, 0));
                Perks.quietly(() -> hit(p, e, zap, Spell.CHAIN_LIGHTNING));
                level.playSound(
                        null, orb.x, orb.y, orb.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, .4f, 2f);
            });
        }
    }

    /** A jagged line of sparks between two points. */
    private static void bolt(ServerLevel level, Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a);
        int steps = Math.max(4, (int) (d.length() * 4));
        Vec3 prev = a;
        for (int i = 1; i <= steps; i++) {
            Vec3 point = a.add(d.scale((double) i / steps));
            if (i < steps)
                point = point.add(
                        (level.getRandom().nextDouble() - 0.5) * 0.4,
                        (level.getRandom().nextDouble() - 0.5) * 0.4,
                        (level.getRandom().nextDouble() - 0.5) * 0.4);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 2, 0.02, 0.02, 0.02, 0);
            level.sendParticles(
                    new DustParticleOptions(0xBFE6FF, 0.9f),
                    (prev.x + point.x) / 2,
                    (prev.y + point.y) / 2,
                    (prev.z + point.z) / 2,
                    1,
                    0,
                    0,
                    0,
                    0);
            prev = point;
        }
    }

    // ---------------------------------------------------------------- Water Splash

    /** How far the wave runs and its full width in degrees; upgraded (Tidecaller) it runs further and wider. */
    static final double WAVE_RANGE = 8, WAVE_LONG = 12, WAVE_ARC = 70, WAVE_WIDE = 100;
    /** Blocks the wave front moves each tick. */
    static final double WAVE_SPEED = 1.0;
    /** Fire-born creatures (blazes, magma cubes, striders) and water-shy ones (endermen) take this many times the damage. */
    static final double WAVE_DOUSE = 2;

    private static final class Wave {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        final Vec3 origin, dir;
        final double half, range, dmg;
        final boolean undertow;
        double r = 0.6;
        final Set<UUID> struck = new HashSet<>();
        final Set<BlockPos> quenched = new HashSet<>();

        Wave(ServerPlayer owner, ServerLevel level, Vec3 origin, Vec3 dir, double half, double range, double dmg) {
            this.owner = owner;
            this.level = level;
            this.origin = origin;
            this.dir = dir;
            this.half = half;
            this.range = range;
            this.dmg = dmg;
            this.undertow = SpellUpgrades.perk(owner, Spell.WATER_SPLASH, "Undertow");
        }
    }

    static final List<Wave> WAVES = new ArrayList<>();

    /**
     * A wave bursts from you across a 70-degree arc and runs 8 blocks (upgraded 12, 100 degrees), a block a tick,
     * hugging the ground. Every creature it reaches is struck once, doused and thrown back; fire-born and water-shy
     * creatures take double. It puts out fire and campfires, turns lava it crosses to obsidian (a source) or
     * cobblestone (a flow), and puts you out too.
     */
    static boolean waterSplash(ServerLevel level, ServerPlayer p, double dmg, boolean upgraded) {
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1e-4) flat = Vec3.directionFromRotation(0, p.getYRot());
        flat = flat.normalize();
        p.clearFire();
        if (SpellUpgrades.perk(p, Spell.WATER_SPLASH, "Healing Spring")) {
            p.heal(4);
            for (LivingEntity f : level.getEntitiesOfClass(
                    LivingEntity.class,
                    p.getBoundingBox().inflate(8),
                    f -> f != p && f.isAlive() && !(f instanceof Enemy) && f.distanceTo(p) <= 8)) {
                f.heal(4);
                f.clearFire();
                level.sendParticles(
                        ParticleTypes.HEART, f.getX(), f.getY() + f.getBbHeight(), f.getZ(), 2, .3, .2, .3, 0);
            }
            level.sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + 2, p.getZ(), 3, .4, .2, .4, 0);
        }
        WAVES.add(new Wave(
                p,
                level,
                p.position(),
                flat,
                Math.toRadians((upgraded ? WAVE_WIDE : WAVE_ARC) / 2),
                upgraded ? WAVE_LONG : WAVE_RANGE,
                dmg));
        Vec3 hands = p.getEyePosition().add(flat.scale(0.8)).add(0, -0.4, 0);
        level.sendParticles(ParticleTypes.SPLASH, hands.x, hands.y, hands.z, 40, 0.4, 0.2, 0.4, 0.3);
        sound(level, p.position(), SoundEvents.PLAYER_SPLASH_HIGH_SPEED, 1.4f, 0.8f);
        sound(level, p.position(), SoundEvents.BUCKET_EMPTY, 1.2f, 0.7f);
        return true;
    }

    static void tickWaves() {
        for (Iterator<Wave> it = WAVES.iterator(); it.hasNext(); ) {
            Wave w = it.next();
            ServerLevel level = w.level;
            if (w.owner.isRemoved() || w.owner.level() != level) {
                it.remove();
                continue;
            }
            double r0 = w.r;
            w.r = Math.min(w.range, w.r + WAVE_SPEED);
            // The front: an arc of deep blue water with a white crest, sinking as it spends itself.
            double crest = 1.4 - 0.7 * (w.r / w.range);
            int n = Math.max(6, (int) (w.half * 2 * w.r * 2.4));
            for (int i = 0; i <= n; i++) {
                double a = -w.half + 2 * w.half * i / n;
                Vec3 q = w.origin.add(w.dir.yRot((float) a).scale(w.r));
                BlockPos spot = EarthSpells.standing(level, q.add(0, 0.5, 0));
                double y = spot != null ? spot.getY() : w.origin.y;
                level.sendParticles(new DustParticleOptions(0x1F5FC8, 1.8f), q.x, y + 0.25, q.z, 1, 0.1, 0.12, 0.1, 0);
                level.sendParticles(
                        new DustParticleOptions(0x3F8CF0, 1.5f), q.x, y + crest * 0.6, q.z, 1, 0.1, 0.12, 0.1, 0);
                if (i % 2 == 0)
                    level.sendParticles(
                            new DustParticleOptions(0xE8F6FF, 1.1f), q.x, y + crest, q.z, 1, 0.08, 0.05, 0.08, 0);
                if (i % 3 == 0) level.sendParticles(ParticleTypes.SPLASH, q.x, y + crest, q.z, 4, 0.2, 0.1, 0.2, 0.15);
                if (i % 4 == 0)
                    level.sendParticles(ParticleTypes.FALLING_WATER, q.x, y + crest + 0.2, q.z, 1, 0.15, 0.05, 0.15, 0);
                quench(w, BlockPos.containing(q.x, y, q.z));
            }
            if ((int) (w.r * 2) % 3 == 0)
                sound(
                        level,
                        w.origin.add(w.dir.scale(w.r)),
                        SoundEvents.GENERIC_SPLASH,
                        0.8f,
                        0.8f + level.getRandom().nextFloat() * 0.3f);
            // Everything the front passed this tick, inside the arc.
            for (LivingEntity e : level.getEntitiesOfClass(
                    LivingEntity.class,
                    new AABB(w.origin, w.origin).inflate(w.r + 1, 4, w.r + 1),
                    e -> Spells.canHitArea(w.owner, e))) {
                if (w.struck.contains(e.getUUID())) continue;
                Vec3 to = new Vec3(e.getX() - w.origin.x, 0, e.getZ() - w.origin.z);
                double d = to.length();
                if (d > w.r + e.getBbWidth() / 2 || Math.abs(e.getY() - w.origin.y) > 4) continue;
                if (d > 0.5
                        && Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(w.dir))))
                                > w.half + Math.atan2(e.getBbWidth() / 2, d)) continue;
                w.struck.add(e.getUUID());
                boolean doused = e.fireImmune() || e.isSensitiveToWater();
                Spells.hit(w.owner, e, w.dmg * (doused ? WAVE_DOUSE : 1), Spell.WATER_SPLASH, w.cast);
                e.clearFire();
                Vec3 away = d > 0.3 ? to.normalize() : w.dir;
                double k = 1 - knockResist(e);
                // Undertow: dragged toward the caster instead, never past them.
                double shove = w.undertow ? -Math.min(1.1, d * .25) : 1.1;
                e.setDeltaMovement(e.getDeltaMovement()
                        .scale(0.2)
                        .add(away.scale(shove * k))
                        .add(0, 0.35 * k, 0));
                e.needsSync = true;
                Vec3 at = chest(e);
                level.sendParticles(ParticleTypes.SPLASH, at.x, at.y, at.z, 24, 0.3, 0.4, 0.3, 0.2);
                if (doused) level.sendParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, 8, 0.3, 0.3, 0.3, 0.04);
                sound(level, at, doused ? SoundEvents.FIRE_EXTINGUISH : SoundEvents.PLAYER_SPLASH, 1.0f, 1.0f);
            }
            if (w.r >= w.range) it.remove();
        }
    }

    /** Puts out fire and campfires and hardens lava at and just around the wave's footing. */
    private static void quench(Wave w, BlockPos at) {
        ServerLevel level = w.level;
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos q = at.offset(0, dy, 0);
            if (!w.quenched.add(q)) continue;
            BlockState state = level.getBlockState(q);
            if (state.is(BlockTags.FIRE)) {
                level.removeBlock(q, false);
                sound(level, Vec3.atCenterOf(q), SoundEvents.FIRE_EXTINGUISH, 0.6f, 1.2f);
            } else if (state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT)) {
                level.setBlockAndUpdate(q, state.setValue(CampfireBlock.LIT, false));
                sound(level, Vec3.atCenterOf(q), SoundEvents.FIRE_EXTINGUISH, 0.6f, 1.0f);
            } else if (state.getFluidState().is(FluidTags.LAVA)) {
                level.setBlockAndUpdate(
                        q,
                        state.getFluidState().isSource()
                                ? Blocks.OBSIDIAN.defaultBlockState()
                                : Blocks.COBBLESTONE.defaultBlockState());
                Vec3 c = Vec3.atCenterOf(q);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 0.5, c.z, 4, 0.3, 0.1, 0.3, 0.02);
                sound(level, c, SoundEvents.LAVA_EXTINGUISH, 0.8f, 1.0f);
            }
        }
    }

    // ---------------------------------------------------------------- Ball Lightning

    static final double ORB_SPEED = .16, ORB_ZAP = 5;
    static final int ORB_TICKS = 120, ORB_TARGETS = 3;

    static final class Orb {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        Vec3 pos, dir;
        int ticks, age;
        final double dmg, zap;
        final int targets;

        Orb(ServerPlayer owner, Vec3 pos, Vec3 dir, int ticks, double dmg, double zap, int targets) {
            this.owner = owner;
            this.level = owner.level();
            this.pos = pos;
            this.dir = dir;
            this.ticks = ticks;
            this.dmg = dmg;
            this.zap = zap;
            this.targets = targets;
        }
    }

    static final List<Orb> ORBS = new ArrayList<>();

    /** A crackling orb drifts slowly along your aim, zapping the nearest monsters round it every half second. */
    static boolean ballLightning(ServerLevel level, ServerPlayer p, double dmg) {
        Vec3 look = p.getLookAngle();
        ORBS.add(new Orb(
                p,
                p.getEyePosition().add(look.scale(2.4)),
                look,
                SpellUpgrades.scaleInt(Spell.BALL_LIGHTNING, DURATION, ORB_TICKS),
                dmg,
                SpellUpgrades.scale(Spell.BALL_LIGHTNING, AREA, ORB_ZAP),
                SpellUpgrades.add(Spell.BALL_LIGHTNING, COUNT, ORB_TARGETS)));
        sound(level, p.getEyePosition(), SoundEvents.BEACON_POWER_SELECT, .8f, 1.8f);
        sound(level, p.getEyePosition(), SoundEvents.FIRECHARGE_USE, .5f, 2f);
        return true;
    }

    static void tickOrbs() {
        for (Iterator<Orb> it = ORBS.iterator(); it.hasNext(); ) {
            var o = it.next();
            if (o.owner.isRemoved() || o.owner.level() != o.level || --o.ticks < 0) {
                if (!o.owner.isRemoved() && SpellUpgrades.perk(o.owner, Spell.BALL_LIGHTNING, "Thunderclap")) {
                    for (var e : Perks.nearestMonsters(o.owner, null, o.pos, 4, 64))
                        Spells.hit(o.owner, e, o.dmg * 3, Spell.BALL_LIGHTNING, o.cast);
                    o.level.sendParticles(ParticleTypes.EXPLOSION, o.pos.x, o.pos.y, o.pos.z, 1, 0, 0, 0, 0);
                    o.level.sendParticles(
                            ParticleTypes.ELECTRIC_SPARK, o.pos.x, o.pos.y, o.pos.z, 40, 1.2, 1.2, 1.2, .3);
                    sound(o.level, o.pos, SoundEvents.LIGHTNING_BOLT_IMPACT, 1f, 1.4f);
                } else
                    o.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, o.pos.x, o.pos.y, o.pos.z, 12, .3, .3, .3, .1);
                it.remove();
                continue;
            }
            o.age++;
            if (SpellUpgrades.perk(o.owner, Spell.BALL_LIGHTNING, "Homing Orb")) {
                var prey = Perks.nearestMonsters(o.owner, null, o.pos, 12, 1);
                if (!prey.isEmpty())
                    o.dir = o.dir.lerp(chest(prey.getFirst()).subtract(o.pos).normalize(), .1)
                            .normalize();
            }
            Vec3 next = o.pos.add(o.dir.scale(ORB_SPEED));
            if (o.level
                            .clip(new ClipContext(
                                    o.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, o.owner))
                            .getType()
                    == HitResult.Type.MISS) o.pos = next;
            if (o.age % 10 == 0) {
                var prey = Perks.nearestMonsters(o.owner, null, o.pos, o.zap, o.targets);
                for (var e : prey) {
                    zigzag(o.level, o.pos, chest(e));
                    Spells.hit(o.owner, e, o.dmg, Spell.BALL_LIGHTNING, o.cast);
                    if (SpellUpgrades.perk(o.owner, Spell.BALL_LIGHTNING, "Stunning Arc")) Perks.root(e, o.owner, 20);
                    if (SpellUpgrades.perk(o.owner, Spell.BALL_LIGHTNING, "Mana Spark")) Perks.mana(o.owner, 1);
                }
                if (!prey.isEmpty()) sound(o.level, o.pos, SoundEvents.LIGHTNING_BOLT_IMPACT, .35f, 2f);
            }
            var random = o.level.getRandom();
            for (int k = 0; k < 6; k++) {
                double a = random.nextDouble() * Math.PI * 2, b = random.nextDouble() * Math.PI - Math.PI / 2;
                o.level.sendParticles(
                        new DustParticleOptions(0x9FD8FF, 1.1f),
                        o.pos.x + Math.cos(a) * Math.cos(b) * .45,
                        o.pos.y + Math.sin(b) * .45,
                        o.pos.z + Math.sin(a) * Math.cos(b) * .45,
                        1,
                        0,
                        0,
                        0,
                        0);
            }
            o.level.sendParticles(ParticleTypes.END_ROD, o.pos.x, o.pos.y, o.pos.z, 1, .05, .05, .05, 0);
            o.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, o.pos.x, o.pos.y, o.pos.z, 3, .35, .35, .35, .05);
            if (o.age % 12 == 0) sound(o.level, o.pos, SoundEvents.BEEHIVE_WORK, .5f, 2f);
        }
    }

    /** A jagged bolt of light between two points. */
    static void zigzag(ServerLevel level, Vec3 a, Vec3 b) {
        var random = level.getRandom();
        Vec3 last = a;
        int steps = Math.max(2, (int) (a.distanceTo(b) / 1.2));
        for (int i = 1; i <= steps; i++) {
            Vec3 at = a.lerp(b, (double) i / steps);
            if (i < steps)
                at = at.add(
                        (random.nextDouble() - .5) * .6,
                        (random.nextDouble() - .5) * .6,
                        (random.nextDouble() - .5) * .6);
            SpellKit.line(level, last, at, new DustParticleOptions(0xD8F0FF, .8f), .25);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0, 0, 0, 0);
            last = at;
        }
    }

    // ---------------------------------------------------------------- Cyclone

    static final double GALE_SPEED = .22, GALE_RADIUS = 1.8;
    static final int GALE_TICKS = 80;

    static final class Gale {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        Vec3 pos, dir;
        int ticks, age;
        final double dmg, radius;
        final Map<LivingEntity, Long> cut = new HashMap<>();
        final Set<LivingEntity> held = new HashSet<>();

        Gale(ServerPlayer owner, Vec3 pos, Vec3 dir, int ticks, double dmg, double radius) {
            this.owner = owner;
            this.level = owner.level();
            this.pos = pos;
            this.dir = dir;
            this.ticks = ticks;
            this.dmg = dmg;
            this.radius = radius;
        }
    }

    static final List<Gale> GALES = new ArrayList<>();

    /** A whirlwind spins away along the ground, catching monsters, lifting and cutting them, then flinging them out. */
    static boolean cyclone(ServerLevel level, ServerPlayer p, double dmg) {
        Vec3 dir = flat(p.getLookAngle());
        int ticks = SpellUpgrades.scaleInt(Spell.CYCLONE, DURATION, GALE_TICKS);
        double radius = SpellUpgrades.scale(Spell.CYCLONE, AREA, GALE_RADIUS);
        boolean twin = SpellUpgrades.perk(p, Spell.CYCLONE, "Twin Cyclones");
        for (double turn : twin ? new double[] {-.22, .22} : new double[] {0}) {
            Vec3 d = dir.yRot((float) turn);
            Vec3 at = SpellKit.floor(level, p.position().add(d.scale(2)).add(0, .5, 0), 4);
            GALES.add(new Gale(p, at, d, ticks, dmg, radius));
        }
        sound(level, p.position(), SoundEvents.ELYTRA_FLYING, .6f, 1.6f);
        sound(level, p.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1f, .7f);
        return true;
    }

    static void tickGales() {
        long now = Scheduler.now();
        for (Iterator<Gale> it = GALES.iterator(); it.hasNext(); ) {
            var g = it.next();
            var p = g.owner;
            if (p.isRemoved() || p.level() != g.level || --g.ticks < 0) {
                for (var e : g.held)
                    if (e.isAlive()) throwFrom(e, g.pos, 2.0, 1.1); // twice as hard (owner, 2026-11-22)
                g.level.sendParticles(ParticleTypes.CLOUD, g.pos.x, g.pos.y + 1, g.pos.z, 20, .6, .8, .6, .08);
                it.remove();
                continue;
            }
            g.age++;
            if (SpellUpgrades.perk(p, Spell.CYCLONE, "Steering Wind"))
                g.dir = g.dir.lerp(flat(p.getLookAngle()), .12).normalize();
            Vec3 next = g.pos.add(g.dir.scale(GALE_SPEED));
            Vec3 ground = SpellKit.floor(g.level, next.add(0, 1, 0), 3);
            boolean wall = g.level
                            .clip(new ClipContext(
                                    g.pos.add(0, .6, 0),
                                    next.add(0, .6, 0),
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    p))
                            .getType()
                    != HitResult.Type.MISS;
            if (!wall && Math.abs(ground.y - g.pos.y) <= 1.1) g.pos = ground;
            boolean fire = SpellUpgrades.perk(p, Spell.CYCLONE, "Firestorm");
            g.held.removeIf(e -> !e.isAlive());
            for (var e : g.level.getEntitiesOfClass(
                    LivingEntity.class,
                    new AABB(g.pos, g.pos).inflate(g.radius, 0, g.radius).expandTowards(0, 3.2, 0),
                    e -> monster(p, e))) {
                double dx = e.getX() - g.pos.x, dz = e.getZ() - g.pos.z, d = Math.sqrt(dx * dx + dz * dz);
                if (d > g.radius + e.getBbWidth() / 2) continue;
                g.held.add(e);
                if (SpellUpgrades.perk(p, Spell.CYCLONE, "Dust Devil"))
                    e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, 0, false, true, true), p);
                if (!mighty(e)) {
                    // Round and in toward the funnel's heart, and up to about two blocks.
                    Vec3 in = d < 1e-3 ? Vec3.ZERO : new Vec3(-dx / d, 0, -dz / d);
                    Vec3 round = new Vec3(-in.z, 0, in.x);
                    double lift = e.getY() < g.pos.y + 2.2 ? .16 : -.02;
                    push(
                            e,
                            round.scale(.32)
                                    .add(in.scale(.12 * Math.min(1, d)))
                                    .add(g.dir.scale(GALE_SPEED))
                                    .add(0, lift, 0));
                    e.resetFallDistance();
                }
                if (now - g.cut.getOrDefault(e, -1000L) < 10) continue;
                g.cut.put(e, now);
                Spells.hit(p, e, g.dmg, Spell.CYCLONE, g.cast);
                if (fire) e.igniteForSeconds(3);
                g.level.sendParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), chest(e).y, e.getZ(), 1, 0, 0, 0, 0);
            }
            // The funnel: two arms wound round a column, wider as it rises.
            for (int arm = 0; arm < 2; arm++)
                for (int k = 0; k < 12; k++) {
                    double h = k * .28, r = .25 + h * .32 * g.radius / GALE_RADIUS;
                    double a = g.age * .55 + k * .7 + arm * Math.PI;
                    g.level.sendParticles(
                            new DustParticleOptions(k % 3 == 0 ? 0xB8C8D8 : 0xEEF6FF, 1.3f),
                            g.pos.x + Math.cos(a) * r,
                            g.pos.y + .1 + h,
                            g.pos.z + Math.sin(a) * r,
                            1,
                            0,
                            0,
                            0,
                            0);
                }
            if (g.age % 3 == 0)
                g.level.sendParticles(ParticleTypes.CLOUD, g.pos.x, g.pos.y + .15, g.pos.z, 2, .4, .05, .4, .02);
            if (fire && g.age % 2 == 0)
                g.level.sendParticles(ParticleTypes.FLAME, g.pos.x, g.pos.y + 1, g.pos.z, 3, .4, .8, .4, .02);
            if (g.age % 12 == 0) sound(g.level, g.pos, SoundEvents.PLAYER_ATTACK_SWEEP, .5f, 1.4f);
        }
    }

    // ---------------------------------------------------------------- Tempest

    static final double TEMPEST_RADIUS = 20;
    static final int TEMPEST_TICKS = 240, TEMPEST_BOLTS = 3;

    static final class Tempest {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        final double dmg, radius;
        final int bolts;
        final boolean chain, charged, clap;
        int ticks, age;

        Tempest(
                ServerPlayer owner,
                double dmg,
                double radius,
                int bolts,
                int ticks,
                boolean chain,
                boolean charged,
                boolean clap) {
            this.owner = owner;
            this.level = owner.level();
            this.dmg = dmg;
            this.radius = radius;
            this.bolts = bolts;
            this.ticks = ticks;
            this.chain = chain;
            this.charged = charged;
            this.clap = clap;
        }
    }

    static final List<Tempest> TEMPESTS = new ArrayList<>();

    /** A storm gathers over you: each second lightning strikes the monsters nearest you. */
    static boolean tempest(ServerLevel level, ServerPlayer p, double dmg) {
        TEMPESTS.removeIf(t -> t.owner == p);
        TEMPESTS.add(new Tempest(
                p,
                dmg,
                SpellUpgrades.scale(Spell.TEMPEST, AREA, TEMPEST_RADIUS),
                SpellUpgrades.add(Spell.TEMPEST, COUNT, TEMPEST_BOLTS),
                SpellUpgrades.scaleInt(Spell.TEMPEST, DURATION, TEMPEST_TICKS),
                SpellUpgrades.perk(p, Spell.TEMPEST, "Chain Bolts"),
                SpellUpgrades.perk(p, Spell.TEMPEST, "Charged Air"),
                SpellUpgrades.perk(p, Spell.TEMPEST, "Thunderclap")));
        if (SpellUpgrades.perk(p, Spell.TEMPEST, "Storm Ward"))
            p.addEffect(new MobEffectInstance(
                    MobEffects.RESISTANCE,
                    SpellUpgrades.scaleInt(Spell.TEMPEST, DURATION, TEMPEST_TICKS),
                    0,
                    false,
                    false,
                    true));
        atFeet(p, new DustParticleOptions(0xB8C4FF, 1.3f), ParticleTypes.ELECTRIC_SPARK, 30);
        sound(level, p.position(), SoundEvents.TRIDENT_THUNDER.value(), 1.2f, 1f);
        return true;
    }

    static void tickTempests() {
        for (Iterator<Tempest> it = TEMPESTS.iterator(); it.hasNext(); ) {
            var t = it.next();
            var p = t.owner;
            if (p.isRemoved() || !p.isAlive() || p.level() != t.level || --t.ticks < 0) {
                it.remove();
                continue;
            }
            t.age++;
            var random = t.level.getRandom();
            // A dark roof of cloud turning over you.
            if (t.age % 2 == 0)
                for (int k = 0; k < 8; k++) {
                    double a = random.nextDouble() * Math.PI * 2, r = 1.5 + random.nextDouble() * 6;
                    t.level.sendParticles(
                            ParticleTypes.LARGE_SMOKE,
                            p.getX() + Math.cos(a) * r,
                            p.getY() + 9 + random.nextDouble(),
                            p.getZ() + Math.sin(a) * r,
                            1,
                            .3,
                            .1,
                            .3,
                            .005);
                }
            if (t.age % 5 == 0)
                t.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 9, p.getZ(), 6, 3, .3, 3, .1);
            if (t.age % 20 != 1) continue;
            for (var e : Perks.nearestMonsters(p, null, p.position(), t.radius, t.bolts)) {
                tempestBolt(t, e, t.dmg);
                if (t.chain)
                    for (var next : Perks.nearestMonsters(p, e, e.position(), 5, 1)) {
                        SpellKit.line(t.level, chest(e), chest(next), ParticleTypes.ELECTRIC_SPARK, .3);
                        Spells.hit(p, next, t.dmg / 2, Spell.TEMPEST, t.cast);
                    }
            }
        }
    }

    private static void tempestBolt(Tempest t, LivingEntity e, double dmg) {
        var tempestBolt = EntityTypes.LIGHTNING_BOLT.create(t.level, EntitySpawnReason.TRIGGERED);
        if (tempestBolt != null) {
            tempestBolt.setVisualOnly(true); // the light and the thunder; the harm is the spell's own
            tempestBolt.setPos(e.getX(), e.getY(), e.getZ());
            t.level.addFreshEntity(tempestBolt);
        }
        Spells.hit(t.owner, e, dmg, Spell.TEMPEST, t.cast);
        e.igniteForSeconds(3);
        if (t.clap) e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 3), t.owner);
        if (t.charged) Perks.mana(t.owner, 2);
    }
}
