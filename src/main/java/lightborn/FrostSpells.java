package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.chest;
import static lightborn.SpellKit.line;
import static lightborn.SpellKit.sound;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.AREA;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.SpellUpgrades.Stat.REACH;
import static lightborn.Spells.areaHit;
import static lightborn.Spells.ring;
import static lightborn.Spells.sound;
import static lightborn.Spells.withSpell;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Frost spells: ice and cold, each building frostbite ({@link Frostbite}). Frost Nova, Glacial Spike, Blizzard,
 * Sentinel and Frostwalk.
 */
public final class FrostSpells {
    private FrostSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case FROST_NOVA -> {
                frostNova(level, p, dmg, SpellUpgrades.scale(spell, AREA, 6));
                yield true;
            }
            case GLACIAL_SPIKE -> glacialSpike(level, p, dmg, SpellUpgrades.special(spell));
            case BLIZZARD -> blizzard(level, p, dmg);
            case SENTINEL -> sentinel(level, p, dmg);
            case FROSTWALK -> frostwalk(level, p);
            default -> throw new IllegalArgumentException(spell + " is not a frost spell");
        };
    }

    static void tick(MinecraftServer server) {
        tickSpikes();
        tickSentries();
        tickBlizzards();
        tickChill();
        tickFrost();
    }

    static void clear() {
        SPIKES.clear();
        SENTRIES.forEach(s -> s.parts.forEach(SpellKit::drop));
        SENTRIES.clear();
        BLIZZARDS.clear();
        CHILLED.clear();
        FROST.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "spikes" -> SPIKES.size();
            case "sentries" -> SENTRIES.size();
            case "blizzards" -> BLIZZARDS.size();
            case "chilled" -> CHILLED.size();
            case "frost" -> FROST.size();
            default -> -1;
        };
    }

    /** Before armour: a monster the Sentinel chilled takes more from everything but the crystal's own shard. */
    static float sentryIncoming(LivingEntity target, float amount) {
        if (!shardLanding && sentryChilled(target)) amount *= (float) CHILL_HARM;
        return amount;
    }

    // ---------------------------------------------------------------- Frost Nova

    /** Frost Nova freezes what it hits solid, in a block of ice, for 3 seconds; 5 with Ice Prison (owner, 2026-11-18). */
    static final int NOVA_FREEZE_TICKS = 60, ICE_PRISON_TICKS = 100;

    static void frostNova(ServerLevel level, ServerPlayer p, double dmg, double radius) {
        frostNova(level, p, dmg, radius, true);
    }

    /** {@code first}: the cast itself, not Second Ring's echo, which never echoes again. */
    private static void frostNova(ServerLevel level, ServerPlayer p, double dmg, double radius, boolean first) {
        sound(level, p, SoundEvents.GLASS_BREAK, 1.2f, 0.6f);
        sound(level, p, SoundEvents.POWDER_SNOW_BREAK, 1.5f, 0.8f);
        Vec3 c = p.position();
        for (int ring = 1; ring <= 3; ring++) {
            double r = radius * ring / 3.0;
            Bosses.ring(level, c, r, ParticleTypes.SNOWFLAKE, 24 + ring * 12);
            Bosses.ring(level, c.add(0, 0.6, 0), r, new DustParticleOptions(0x9FE8FF, 1.2f), 16 + ring * 8);
        }
        level.sendParticles(ParticleTypes.ITEM_SNOWBALL, c.x, c.y + 1, c.z, 40, radius / 3, 0.4, radius / 3, 0.1);
        int[] struck = {0};
        int freeze = SpellUpgrades.perk(p, Spell.FROST_NOVA, "Ice Prison") ? ICE_PRISON_TICKS : NOVA_FREEZE_TICKS;
        areaHit(p, c, radius, dmg, e -> {
            Vec3 away = e.position().subtract(c).multiply(1, 0, 1);
            if (away.lengthSqr() > 0.01)
                e.push(away.normalize().scale(0.9).add(0, 0.3, 0)); // twice the 0.45 shove (owner, 2026-11-18)
            e.setTicksFrozen(Math.max(e.getTicksFrozen(), freeze + 80));
            Freeze.freeze(level, p, e, freeze); // after the shove: it slides back as a block of ice
            Frostbite.add(e, p, Frostbite.FULL); // one blow bites (owner, 2026-11-23)
            if (e instanceof net.minecraft.world.entity.monster.Enemy) struck[0]++;
        });
        if (struck[0] > 0 && SpellUpgrades.perk(p, Spell.FROST_NOVA, "Frost Armour")) {
            float shield = Math.min(10, 2 * struck[0]);
            p.addEffect(new MobEffectInstance(
                    MobEffects.ABSORPTION, 200, Math.max(0, (int) Math.ceil(shield / 4) - 1)));
            p.setAbsorptionAmount(Math.max(p.getAbsorptionAmount(), shield));
        }
        if (first && SpellUpgrades.perk(p, Spell.FROST_NOVA, "Second Ring"))
            Scheduler.later(20, () -> {
                if (p.isAlive() && p.level() == level)
                    withSpell(Spell.FROST_NOVA, () -> frostNova(level, p, dmg * .6, radius, false));
            });
    }

    // ---------------------------------------------------------------- Glacial Spike

    /** A chilled target (slowed or frozen) takes this many times the damage, and the chill is spent. */
    static final double SHATTER = 3;
    /** Upgraded: share of a shatter that splinters into every other monster within SPLINTER_RADIUS. */
    static final double SPLINTER = 0.5, SPLINTER_RADIUS = 3;
    /** How long a spike's chill lasts: longer than the cooldown, so a second spike can shatter it. */
    static final int CHILL_TICKS = 50;

    private static final class Spike {
        final ServerPlayer owner;
        final ServerLevel level;
        Vec3 pos;
        final Vec3 vel;
        final double dmg;
        final boolean splinter;
        int life = 20;

        Spike(ServerPlayer owner, ServerLevel level, Vec3 pos, Vec3 vel, double dmg, boolean splinter) {
            this.owner = owner;
            this.level = level;
            this.pos = pos;
            this.vel = vel;
            this.dmg = dmg;
            this.splinter = splinter;
        }
    }

    static final List<Spike> SPIKES = new ArrayList<>();

    /**
     * A fast lance of ice. It chills what it hits (Slowness, frost on the screen edge); a target already chilled, by a
     * spike, Frost Nova or powder snow, shatters for three times the damage. Upgraded, the shards fly on into every
     * monster within 3 blocks.
     */
    static boolean glacialSpike(ServerLevel level, ServerPlayer p, double dmg, boolean splinter) {
        Vec3 look = p.getLookAngle();
        SPIKES.add(new Spike(p, level, p.getEyePosition().add(look.scale(0.5)), look.scale(2.6), dmg, splinter));
        if (SpellUpgrades.perk(p, Spell.GLACIAL_SPIKE, "Icicle Volley"))
            for (float yaw : new float[] {.21f, -.21f}) {
                Vec3 side = look.yRot(yaw);
                SPIKES.add(
                        new Spike(p, level, p.getEyePosition().add(side.scale(0.5)), side.scale(2.6), dmg, splinter));
            }
        sound(level, p.position(), SoundEvents.SNOW_GOLEM_SHOOT, 1.0f, 0.6f);
        sound(level, p.position(), SoundEvents.GLASS_HIT, 1.0f, 1.6f);
        return true;
    }

    static boolean chilled(LivingEntity e) {
        return e.hasEffect(MobEffects.SLOWNESS) || e.getTicksFrozen() > 0;
    }

    static void tickSpikes() {
        for (Iterator<Spike> it = SPIKES.iterator(); it.hasNext(); ) {
            Spike s = it.next();
            if (s.owner.isRemoved() || s.owner.level() != s.level || --s.life < 0) {
                it.remove();
                continue;
            }
            ServerLevel level = s.level;
            Vec3 next = s.pos.add(s.vel);
            BlockHitResult block = level.clip(
                    new ClipContext(s.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, s.owner));
            Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
            LivingEntity struck = firstHit(s.owner, s.pos, end, 0.3, null);
            Vec3 eye = s.owner.getEyePosition();
            for (int i = 0; i < 5; i++) {
                Vec3 q = s.pos.lerp(end, i / 5.0);
                if (q.distanceToSqr(eye) < 6) continue;
                level.sendParticles(new DustParticleOptions(0xBFF4FF, 1.1f), q.x, q.y, q.z, 1, 0.02, 0.02, 0.02, 0);
                if (i % 2 == 0) level.sendParticles(ParticleTypes.SNOWFLAKE, q.x, q.y, q.z, 1, 0.04, 0.04, 0.04, 0.005);
            }
            if (struck != null) {
                spikeHit(s, struck);
                it.remove();
            } else if (block.getType() != HitResult.Type.MISS) {
                level.sendParticles(
                        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()),
                        end.x,
                        end.y,
                        end.z,
                        12,
                        0.15,
                        0.15,
                        0.15,
                        0.1);
                sound(level, end, SoundEvents.GLASS_BREAK, 0.5f, 1.6f);
                it.remove();
            } else {
                s.pos = next;
            }
        }
    }

    private static void spikeHit(Spike s, LivingEntity e) {
        ServerLevel level = s.level;
        Vec3 at = chest(e);
        if (chilled(e)) {
            Spells.hit(s.owner, e, s.dmg * SHATTER, Spell.GLACIAL_SPIKE);
            e.removeEffect(MobEffects.SLOWNESS);
            e.setTicksFrozen(0);
            Frostbite.clear(e); // the shatter spends the frost
            level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()),
                    at.x,
                    at.y,
                    at.z,
                    50,
                    0.4,
                    0.5,
                    0.4,
                    0.3);
            level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, 20, 0.5, 0.5, 0.5, 0.1);
            sound(level, at, SoundEvents.GLASS_BREAK, 1.4f, 0.7f);
            sound(level, at, SoundEvents.AMETHYST_CLUSTER_BREAK, 1.2f, 0.6f);
            if (s.splinter) {
                for (LivingEntity o : level.getEntitiesOfClass(
                        LivingEntity.class,
                        e.getBoundingBox().inflate(SPLINTER_RADIUS),
                        o -> o != e && o instanceof Enemy && Spells.canHitArea(s.owner, o))) {
                    line(level, at, chest(o), new DustParticleOptions(0xDDF8FF, 0.8f), 0.4);
                    Spells.hit(s.owner, o, s.dmg * SHATTER * SPLINTER, Spell.GLACIAL_SPIKE);
                }
            }
            // Frozen Tomb: the shatter chills the monsters round it, ready to shatter in turn.
            if (SpellUpgrades.perk(s.owner, Spell.GLACIAL_SPIKE, "Frozen Tomb"))
                for (LivingEntity o : level.getEntitiesOfClass(
                        LivingEntity.class,
                        e.getBoundingBox().inflate(4),
                        o -> o != e && o.isAlive() && o instanceof Enemy && Spells.canHitArea(s.owner, o))) {
                    Perks.chill(o, s.owner, CHILL_TICKS, 50);
                    level.sendParticles(ParticleTypes.SNOWFLAKE, o.getX(), o.getY() + 1, o.getZ(), 10, .3, .5, .3, .02);
                }
        } else {
            Spells.hit(s.owner, e, s.dmg, Spell.GLACIAL_SPIKE);
            if (e.isAlive()) {
                e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, CHILL_TICKS, 0), s.owner);
                e.setTicksFrozen(Math.max(e.getTicksFrozen(), 60));
                Frostbite.add(e, s.owner, 50);
            }
            level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, 14, 0.3, 0.4, 0.3, 0.05);
            sound(level, at, SoundEvents.PLAYER_HURT_FREEZE, 1.0f, 1.2f);
        }
        if (SpellUpgrades.perk(s.owner, Spell.GLACIAL_SPIKE, "Permafrost"))
            Perks.frostPatch(s.owner, e.position(), 2.5, 100);
    }

    /** The nearest creature the segment a to b passes through (box grown by {@code pad}), skipping {@code skip}. */
    private static LivingEntity firstHit(ServerPlayer owner, Vec3 a, Vec3 b, double pad, Set<UUID> skip) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : owner.level()
                .getEntitiesOfClass(
                        LivingEntity.class, new AABB(a, b).inflate(1 + pad), e -> Spells.canHitDirect(owner, e))) {
            if (skip != null && skip.contains(e.getUUID())) continue;
            var clip = e.getBoundingBox().inflate(pad).clip(a, b);
            if (clip.isEmpty() && !e.getBoundingBox().inflate(pad).contains(a)) continue;
            double d = clip.map(v -> v.distanceToSqr(a)).orElse(0.0);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- Sentinel

    static final double SENTRY_REACH = 24, SENTRY_SEEK = 16, CHILL_HARM = 1.3;
    static final int SENTRY_TICKS = 600, SENTRY_PERIOD = 20, SENTRY_CHILL_TICKS = 80;
    /** How tall the crystal stands; its shots leave from near the top. */
    static final float SPIRE = 3.6f;

    static final class Sentry {
        final ServerPlayer owner;
        final ServerLevel level;
        final Vec3 pos, tip;
        final List<Display.BlockDisplay> parts = new ArrayList<>();
        final List<Transformation> shapes = new ArrayList<>();
        final double dmg, seek;
        final boolean split, shatter;
        int ticks, age;

        Sentry(ServerPlayer owner, Vec3 pos, double dmg, double seek, int ticks, boolean split, boolean shatter) {
            this.owner = owner;
            this.level = owner.level();
            this.pos = pos;
            this.tip = pos.add(0, SPIRE * .85, 0);
            this.dmg = dmg;
            this.seek = seek;
            this.ticks = ticks;
            this.split = split;
            this.shatter = shatter;
        }
    }

    static final List<Sentry> SENTRIES = new ArrayList<>();

    /** A chilled monster: slowed, and taking more harm from everything but the crystal that chilled it. */
    record Chill(ServerPlayer owner, long until, double dmg, boolean shatter) {}

    static final Map<LivingEntity, Chill> CHILLED = new IdentityHashMap<>();
    /** True while a crystal's own shard lands, so the chill it leaves does not raise that same blow. */
    private static boolean shardLanding;

    /** A great crystal of ice rises where you look and shoots chilling shards at the nearest monster it sees. */
    static boolean sentinel(ServerLevel level, ServerPlayer p, double dmg) {
        Vec3 aim = SpellKit.aimPoint(p, SpellUpgrades.scale(Spell.SENTINEL, REACH, SENTRY_REACH));
        if (aim == null) {
            say(p, "Look at a place within " + Math.round(SENTRY_REACH) + " blocks to raise the Sentinel");
            return false;
        }
        // One cast's crystals at a time: a new cast takes the old ones down.
        for (Iterator<Sentry> it = SENTRIES.iterator(); it.hasNext(); ) {
            var s = it.next();
            if (s.owner == p) {
                s.parts.forEach(SpellKit::drop);
                it.remove();
            }
        }
        Vec3 ground = SpellKit.floor(level, aim.add(0, .5, 0), 6);
        Vec3 look = p.getLookAngle().multiply(1, 0, 1);
        Vec3 side = look.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : new Vec3(-look.z, 0, look.x).normalize();
        boolean twin = SpellUpgrades.perk(p, Spell.SENTINEL, "Twin Crystals");
        int ticks = SpellUpgrades.scaleInt(Spell.SENTINEL, DURATION, SENTRY_TICKS);
        double seek = SpellUpgrades.scale(Spell.SENTINEL, AREA, SENTRY_SEEK);
        for (double off : twin ? new double[] {-2.2, 2.2} : new double[] {0}) {
            Vec3 at = SpellKit.floor(level, ground.add(side.scale(off)).add(0, .5, 0), 4);
            var s = new Sentry(
                    p,
                    at,
                    dmg,
                    seek,
                    ticks,
                    SpellUpgrades.perk(p, Spell.SENTINEL, "Splitting Shards"),
                    SpellUpgrades.perk(p, Spell.SENTINEL, "Shatter"));
            raise(s, level.getRandom().nextFloat() * (float) Math.PI);
            SENTRIES.add(s);
            if (SpellUpgrades.perk(p, Spell.SENTINEL, "Chilling Rise"))
                for (var e : Perks.nearestMonsters(p, null, at, 4, 32)) Perks.chill(e, p, 60, 50);
            level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 1, at.z, 40, .6, 1, .6, .05);
            level.sendParticles(
                    new net.minecraft.core.particles.BlockParticleOption(
                            ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()),
                    at.x,
                    at.y + .2,
                    at.z,
                    40,
                    .8,
                    .1,
                    .8,
                    .1);
        }
        sound(level, ground, SoundEvents.GLASS_PLACE, 1.4f, .5f);
        sound(level, ground, SoundEvents.AMETHYST_CLUSTER_PLACE, 1.2f, .6f);
        sound(level, ground, SoundEvents.POWDER_SNOW_PLACE, 1.2f, .6f);
        return true;
    }

    /**
     * Builds the crystal out of block displays of ice: a tall spire of blue ice capped by a point, four leaning shards
     * of packed ice and clear ice round it, and chunks at its foot. Each starts flat in the ground and rises.
     */
    private static void raise(Sentry s, float turn) {
        Quaternionf spin = new Quaternionf().rotateY(turn);
        shard(s, Blocks.BLUE_ICE, new Vector3f(), .95f, SPIRE, new Quaternionf(spin).rotateY((float) Math.PI / 4));
        // The point: a cube standing on its corner, on top of the spire.
        float c = .62f;
        var point = new Quaternionf(spin)
                .rotateY((float) Math.PI / 4)
                .rotateX((float) Math.atan(1 / Math.sqrt(2)))
                .rotateZ((float) Math.PI / 4);
        Vector3f centre = new Vector3f(0, SPIRE + c * .35f, 0);
        Vector3f off = point.transform(new Vector3f(c / 2)).negate().add(centre);
        part(s, Blocks.BLUE_ICE, new Transformation(off, point, new Vector3f(c), new Quaternionf()));
        float[][] leaners = {
            {0, .62f, 2.3f, .34f}, {1.6f, .5f, 1.8f, .42f}, {3.1f, .55f, 2.6f, .3f}, {4.6f, .45f, 1.5f, .5f}
        };
        for (int i = 0; i < leaners.length; i++) {
            float yaw = turn + leaners[i][0], w = leaners[i][1], h = leaners[i][2], lean = leaners[i][3];
            var rot = new Quaternionf().rotateY(yaw).rotateZ(-lean).rotateY((float) Math.PI / 4);
            Vector3f foot = new Vector3f((float) Math.cos(-yaw) * .45f, 0, (float) Math.sin(-yaw) * .45f);
            shard(s, i % 2 == 0 ? Blocks.PACKED_ICE : Blocks.ICE, foot, w, h, rot);
        }
        for (int i = 0; i < 5; i++) {
            float yaw = turn + i * 1.26f + .4f;
            Vector3f foot = new Vector3f((float) Math.cos(yaw) * 1.05f, -.12f, (float) Math.sin(yaw) * 1.05f);
            var rot = new Quaternionf().rotateY(yaw * 2).rotateX(.3f).rotateZ(.25f);
            shard(s, i % 2 == 0 ? Blocks.PACKED_ICE : Blocks.BLUE_ICE, foot, .42f, .42f, rot);
        }
    }

    /** One upright shard {@code w} across and {@code h} tall, its foot's centre at {@code foot}, turned by {@code rot}. */
    private static void shard(Sentry s, Block block, Vector3f foot, float w, float h, Quaternionf rot) {
        Vector3f t = rot.transform(new Vector3f(w / 2, 0, w / 2)).negate().add(foot);
        part(s, block, new Transformation(t, rot, new Vector3f(w, h, w), new Quaternionf()));
    }

    private static void part(Sentry s, Block block, Transformation shape) {
        var d = EntityTypes.BLOCK_DISPLAY.create(s.level, EntitySpawnReason.MOB_SUMMONED);
        if (d == null) return;
        d.setBlockState(block.defaultBlockState());
        // Born squashed into the ground; tickSentries lets it rise to its full shape.
        d.setTransformation(new Transformation(
                new Vector3f(shape.translation()),
                new Quaternionf(shape.leftRotation()),
                new Vector3f(shape.scale()).mul(1, .04f, 1),
                new Quaternionf(shape.rightRotation())));
        s.parts.add(SpellKit.track(s.level, d, s.pos));
        s.shapes.add(shape);
    }

    static void tickSentries() {
        for (Iterator<Sentry> it = SENTRIES.iterator(); it.hasNext(); ) {
            var s = it.next();
            if (s.owner.isRemoved()
                    || s.owner.level() != s.level
                    || --s.ticks < 0
                    || s.parts.isEmpty()
                    || s.parts.get(0).isRemoved()) {
                s.level.sendParticles(
                        new net.minecraft.core.particles.BlockParticleOption(
                                ParticleTypes.BLOCK, Blocks.BLUE_ICE.defaultBlockState()),
                        s.pos.x,
                        s.pos.y + 1.5,
                        s.pos.z,
                        60,
                        .6,
                        1.2,
                        .6,
                        .1);
                sound(s.level, s.pos, SoundEvents.GLASS_BREAK, 1.2f, .7f);
                s.parts.forEach(SpellKit::drop);
                it.remove();
                continue;
            }
            s.age++;
            if (s.age == 2)
                for (int i = 0; i < s.parts.size(); i++) {
                    var d = s.parts.get(i);
                    d.setTransformation(s.shapes.get(i));
                    d.setTransformationInterpolationDelay(0);
                    d.setTransformationInterpolationDuration(10);
                }
            // Frost breathes off it: snowflakes drift from the crystal, a glint now and then at the point.
            if (s.age % 5 == 0)
                s.level.sendParticles(ParticleTypes.SNOWFLAKE, s.pos.x, s.pos.y + 1.6, s.pos.z, 2, .5, .9, .5, .01);
            if (s.age % 16 == 0)
                s.level.sendParticles(ParticleTypes.END_ROD, s.tip.x, s.tip.y + .5, s.tip.z, 1, .15, .2, .15, .01);
            if (s.age < 12 || s.age % SENTRY_PERIOD != 0) continue;
            LivingEntity target = null;
            for (var e : Perks.nearestMonsters(s.owner, null, s.tip, s.seek, 8))
                if (clearLine(s.level, s.tip, chest(e), s.owner)) {
                    target = e;
                    break;
                }
            if (target == null) continue;
            strike(s, s.tip, target, s.dmg);
            if (s.split)
                for (var next : Perks.nearestMonsters(s.owner, target, target.position(), 6, 1))
                    strike(s, chest(target), next, s.dmg / 2);
        }
    }

    /** An ice shard flies from the crystal to {@code e}, strikes it and leaves it chilled. */
    private static void strike(Sentry s, Vec3 from, LivingEntity e, double dmg) {
        SpellKit.line(s.level, from, chest(e), ParticleTypes.SNOWFLAKE, .3);
        SpellKit.line(s.level, from, chest(e), ParticleTypes.END_ROD, 1.4);
        shardLanding = true;
        try {
            Spells.hit(s.owner, e, dmg, Spell.SENTINEL);
        } finally {
            shardLanding = false;
        }
        chill(s.owner, e, s.dmg, s.shatter);
        s.level.sendParticles(
                new net.minecraft.core.particles.BlockParticleOption(
                        ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()),
                e.getX(),
                chest(e).y,
                e.getZ(),
                12,
                .25,
                .3,
                .25,
                .1);
        sound(s.level, from, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.9f);
        sound(s.level, chest(e), SoundEvents.PLAYER_HURT_FREEZE, .8f, 1.3f);
    }

    /** Slowed and open to harm for four seconds (renewed by each shard); never frozen in a block. */
    static void chill(ServerPlayer owner, LivingEntity e, double dmg, boolean shatter) {
        if (!e.isAlive()) return;
        CHILLED.put(e, new Chill(owner, e.level().getGameTime() + SENTRY_CHILL_TICKS, dmg, shatter));
        e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SENTRY_CHILL_TICKS, 1, false, false, false));
        Frostbite.add(e, owner, 34);
    }

    static boolean sentryChilled(LivingEntity e) {
        var c = CHILLED.get(e);
        return c != null && e.level().getGameTime() < c.until();
    }

    static void tickChill() {
        for (Iterator<Map.Entry<LivingEntity, Chill>> it = CHILLED.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            LivingEntity e = en.getKey();
            if (e.isRemoved()
                    || !e.isAlive()
                    || e.level().getGameTime() >= en.getValue().until()) {
                it.remove();
                continue;
            }
            if ((e.level().getGameTime() + e.getId()) % 5 == 0 && e.level() instanceof ServerLevel l)
                l.sendParticles(
                        ParticleTypes.SNOWFLAKE, e.getX(), e.getY() + e.getBbHeight() * .6, e.getZ(), 2, .3, .4, .3, 0);
        }
    }

    /** Shatter: a chilled monster that dies bursts into shards that strike and chill the monsters round it. */
    static void shatter(LivingEntity dying) {
        var c = CHILLED.remove(dying);
        if (c == null || !c.shatter() || c.owner().isRemoved() || !(dying.level() instanceof ServerLevel level)) return;
        Vec3 at = chest(dying);
        level.sendParticles(
                new net.minecraft.core.particles.BlockParticleOption(
                        ParticleTypes.BLOCK, Blocks.BLUE_ICE.defaultBlockState()),
                at.x,
                at.y,
                at.z,
                40,
                .5,
                .5,
                .5,
                .2);
        sound(level, at, SoundEvents.GLASS_BREAK, 1.2f, 1.2f);
        for (var e : Perks.nearestMonsters(c.owner(), dying, at, 3, 16)) {
            SpellKit.line(level, at, chest(e), ParticleTypes.SNOWFLAKE, .4);
            shardLanding = true;
            try {
                Spells.hit(c.owner(), e, c.dmg(), Spell.SENTINEL);
            } finally {
                shardLanding = false;
            }
            chill(c.owner(), e, c.dmg(), false);
        }
    }

    // ---------------------------------------------------------------- Blizzard

    static final double STORM_REACH = 32, STORM_RADIUS = 6;
    static final int STORM_TICKS = 160;

    static final class Blizzard {
        final ServerPlayer owner;
        final ServerLevel level;
        Vec3 pos;
        final double dmg, radius;
        final boolean hail, whiteout, eye;
        int ticks, age;

        Blizzard(
                ServerPlayer owner,
                Vec3 pos,
                double dmg,
                double radius,
                int ticks,
                boolean hail,
                boolean whiteout,
                boolean eye) {
            this.owner = owner;
            this.level = owner.level();
            this.pos = pos;
            this.dmg = dmg;
            this.radius = radius;
            this.ticks = ticks;
            this.hail = hail;
            this.whiteout = whiteout;
            this.eye = eye;
        }
    }

    static final List<Blizzard> BLIZZARDS = new ArrayList<>();

    /** A snowstorm rages over a place: monsters in it are slowed, frozen through and bitten every half second. */
    static boolean blizzard(ServerLevel level, ServerPlayer p, double dmg) {
        boolean eye = SpellUpgrades.perk(p, Spell.BLIZZARD, "Eye of the Storm");
        Vec3 at;
        if (eye) at = p.position();
        else {
            Vec3 aim = SpellKit.aimPoint(p, SpellUpgrades.scale(Spell.BLIZZARD, REACH, STORM_REACH));
            if (aim == null) {
                say(p, "Look at a place within " + Math.round(STORM_REACH) + " blocks to call the Blizzard");
                return false;
            }
            at = SpellKit.floor(level, aim.add(0, .5, 0), 8);
        }
        BLIZZARDS.add(new Blizzard(
                p,
                at,
                dmg,
                SpellUpgrades.scale(Spell.BLIZZARD, AREA, STORM_RADIUS),
                SpellUpgrades.scaleInt(Spell.BLIZZARD, DURATION, STORM_TICKS),
                SpellUpgrades.perk(p, Spell.BLIZZARD, "Hailstones"),
                SpellUpgrades.perk(p, Spell.BLIZZARD, "Whiteout"),
                eye));
        if (SpellUpgrades.perk(p, Spell.BLIZZARD, "Snowblind"))
            for (var e :
                    Perks.nearestMonsters(p, null, at, SpellUpgrades.scale(Spell.BLIZZARD, AREA, STORM_RADIUS), 64))
                if (e instanceof Mob m && m.getTarget() == p) m.setTarget(null);
        sound(level, at, SoundEvents.ELYTRA_FLYING, 1f, .6f);
        sound(level, at, SoundEvents.POWDER_SNOW_STEP, 1.5f, .5f);
        return true;
    }

    static void tickBlizzards() {
        for (Iterator<Blizzard> it = BLIZZARDS.iterator(); it.hasNext(); ) {
            var b = it.next();
            if (b.owner.isRemoved() || b.owner.level() != b.level || --b.ticks < 0) {
                it.remove();
                continue;
            }
            b.age++;
            if (b.eye) b.pos = b.owner.position();
            var random = b.level.getRandom();
            // Snow driven sideways through the whole storm, a pale cloud roof over it, and gusts at the edge.
            for (int k = 0; k < 26; k++) {
                double a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble()) * b.radius;
                double x = b.pos.x + Math.cos(a) * r, z = b.pos.z + Math.sin(a) * r;
                double y = b.pos.y + random.nextDouble() * 4.5;
                b.level.sendParticles(ParticleTypes.SNOWFLAKE, x, y, z, 0, .35, -.25, .12, 1);
            }
            if (b.age % 3 == 0)
                for (int k = 0; k < 6; k++) {
                    double a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble()) * b.radius;
                    b.level.sendParticles(
                            ParticleTypes.CLOUD,
                            b.pos.x + Math.cos(a) * r,
                            b.pos.y + 4.8,
                            b.pos.z + Math.sin(a) * r,
                            1,
                            .4,
                            .1,
                            .4,
                            .01);
                }
            if (b.age % 2 == 0)
                Spells.ring(b.level, b.pos.add(0, .15, 0), b.radius, new DustParticleOptions(0xE6F4FF, 1.2f));
            if (b.age % 30 == 0) sound(b.level, b.pos, SoundEvents.ELYTRA_FLYING, .6f, .5f);
            if (b.age % 10 != 0) continue;
            List<LivingEntity> inside = new ArrayList<>();
            for (var e : b.level.getEntitiesOfClass(
                    LivingEntity.class,
                    new AABB(b.pos, b.pos).inflate(b.radius, 5, b.radius),
                    e -> monster(b.owner, e))) {
                double dx = e.getX() - b.pos.x, dz = e.getZ() - b.pos.z;
                if (dx * dx + dz * dz > b.radius * b.radius || e.getY() < b.pos.y - 2) continue;
                inside.add(e);
                Spells.hit(b.owner, e, b.dmg, Spell.BLIZZARD);
                e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 2), b.owner);
                // Frozen through, as powder snow leaves them: Glacial Spike shatters what is frozen.
                e.setTicksFrozen(Math.max(e.getTicksFrozen(), e.getTicksRequiredToFreeze() + 20));
                Frostbite.add(e, b.owner, 20);
                if (b.whiteout && e instanceof Mob m && m.getTarget() instanceof Player) m.setTarget(null);
            }
            if (b.hail && b.age % 20 == 0 && !inside.isEmpty()) {
                var e = inside.get(random.nextInt(inside.size()));
                Vec3 top = e.position().add(0, 6, 0);
                SpellKit.line(b.level, top, chest(e), ParticleTypes.SNOWFLAKE, .4);
                b.level.sendParticles(
                        new net.minecraft.core.particles.BlockParticleOption(
                                ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()),
                        e.getX(),
                        e.getY() + e.getBbHeight(),
                        e.getZ(),
                        14,
                        .3,
                        .2,
                        .3,
                        .1);
                Spells.hit(b.owner, e, b.dmg * 3, Spell.BLIZZARD);
                sound(b.level, e.position(), SoundEvents.GLASS_BREAK, .8f, 1.4f);
            }
        }
    }

    // ---------------------------------------------------------------- Frostwalk

    static final int FROST_TICKS = 1200;
    /** Owner, 2026-11-23: wider (was 3), so a quick jump still lands on ice. */
    static final double FROST_RADIUS = 5;

    /** How far below a jumping player Frostwalk looks for the water, and how many ticks ahead it freezes. */
    static final int FROST_DROP = 4, FROST_LEAD = 3;

    record Frost(
            long until, double radius, boolean skates, boolean lasting, boolean chill, boolean warm, boolean armour) {}

    static final Map<ServerPlayer, Frost> FROST = new IdentityHashMap<>();

    /** Water freezes under your feet as you walk, as on Frost Walker boots, and melts behind you. */
    static boolean frostwalk(ServerLevel level, ServerPlayer p) {
        int ticks = SpellUpgrades.scaleInt(Spell.FROSTWALK, DURATION, FROST_TICKS);
        FROST.put(
                p,
                new Frost(
                        level.getGameTime() + ticks,
                        SpellUpgrades.scale(Spell.FROSTWALK, AREA, FROST_RADIUS),
                        perk(p, Spell.FROSTWALK, "Ice Skates"),
                        perk(p, Spell.FROSTWALK, "Lasting Ice"),
                        perk(p, Spell.FROSTWALK, "Chill Steps"),
                        perk(p, Spell.FROSTWALK, "Warm Heart"),
                        perk(p, Spell.FROSTWALK, "Ice Armour")));
        flourish(p, 0xBFE8FF);
        level.sendParticles(ParticleTypes.SNOWFLAKE, p.getX(), p.getY() + .2, p.getZ(), 20, .5, .1, .5, .02);
        sound(level, p.position(), SoundEvents.POWDER_SNOW_STEP, 1.2f, .8f);
        return true;
    }

    /**
     * Freezes the water surface round {@code at}: the block under your feet, or, in the air, the first water surface up
     * to {@link #FROST_DROP} blocks below.
     */
    private static void freeze(ServerLevel level, ServerPlayer p, Frost f, Vec3 at) {
        BlockPos feet = BlockPos.containing(at.x, at.y - .05, at.z); // the block under your feet
        for (int down = 0; down < FROST_DROP && !p.onGround(); down++) {
            var s = level.getBlockState(feet);
            if (s.getFluidState().is(Fluids.WATER) || !s.isAir()) break;
            feet = feet.below();
        }
        int r = (int) Math.ceil(f.radius());
        var random = level.getRandom();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, 0, -r), feet.offset(r, 0, r))) {
            if (pos.distToCenterSqr(at.x, pos.getY() + .5, at.z) > f.radius() * f.radius()) continue;
            var state = level.getBlockState(pos);
            if (!state.is(Blocks.WATER)
                    || !state.getFluidState().is(Fluids.WATER)
                    || !state.getFluidState().isSource()) continue;
            if (!level.getBlockState(pos.above()).isAir() || !level.mayInteract(p, pos)) continue;
            frost(level, pos, Mth.nextInt(random, 60, 120) * (f.lasting() ? 3 : 1));
        }
    }

    /**
     * Turns the water at {@code pos} to frosted ice that melts after {@code melt} ticks, without the block's own "placed"
     * step. The Sounds mod (`FrostWalkerSoundsMixin`) plays a client sound from that step on the server's thread; a spell
     * freezing dozens of blocks a tick set that off often enough to crash the game's sound engine (owner, 2026-11-23,
     * jumping into water with Frostwalk). Frosted ice needs nothing from that step.
     */
    static void frost(ServerLevel level, BlockPos pos, int melt) {
        level.setBlock(
                pos,
                Blocks.FROSTED_ICE.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL
                        | net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE);
        level.scheduleTick(pos.immutable(), Blocks.FROSTED_ICE, melt);
    }

    static void tickFrost() {
        for (Iterator<Map.Entry<ServerPlayer, Frost>> it = FROST.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            ServerPlayer p = en.getKey();
            Frost f = en.getValue();
            ServerLevel level = p.level();
            long now = level.getGameTime();
            if (p.isRemoved() || !p.isAlive() || now >= f.until()) {
                it.remove();
                continue;
            }
            if (!p.isUnderWater() && !p.isPassenger()) {
                // Where you stand now, and where you will be in a moment: a jump lands on ice already made.
                Vec3 v = p.getDeltaMovement();
                freeze(level, p, f, p.position());
                freeze(
                        level,
                        p,
                        f,
                        p.position().add(v.x * FROST_LEAD, Math.min(0, v.y) * FROST_LEAD, v.z * FROST_LEAD));
            }
            if (f.skates()
                    && now % 10 == 0
                    && level.getBlockState(p.blockPosition().below()).is(net.minecraft.tags.BlockTags.ICE))
                p.addEffect(new MobEffectInstance(MobEffects.SPEED, 20, 1, false, false, true));
            if (f.warm()) p.setTicksFrozen(0);
            if (f.armour()
                    && now % 10 == 0
                    && level.getBlockState(p.blockPosition().below()).is(net.minecraft.tags.BlockTags.ICE))
                p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20, 0, false, false, true));
            if (f.chill() && now % 20 == 0)
                for (var e : Perks.nearestMonsters(p, null, p.position(), 3, 16)) {
                    e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 1, false, true, true));
                    Frostbite.add(e, p, 20);
                }
        }
    }
}
