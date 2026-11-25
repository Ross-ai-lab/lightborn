package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.sound;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.AREA;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.Spells.CONTEXT;
import static lightborn.Spells.areaHit;
import static lightborn.Spells.canHitArea;
import static lightborn.Spells.impact;
import static lightborn.Spells.lookTarget;
import static lightborn.Spells.sound;
import static lightborn.Spells.withSpell;
import static lightborn.WorkSpells.GLOWS;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Brightness;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Fire spells: Fireball, Meteor and Emberwalk.
 */
public final class FireSpells {
    private FireSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case FIREBALL -> fireball(level, p, spell, dmg);
            case METEOR -> meteor(level, p, dmg, SpellUpgrades.scale(spell, AREA, METEOR_RADIUS));
            case EMBERWALK -> emberwalk(level, p);
            default -> throw new IllegalArgumentException(spell + " is not a fire spell");
        };
    }

    static void tick(MinecraftServer server) {
        tickWalks();
    }

    static void clear() {
        WALKS.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "walks" -> WALKS.size();
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- Fireball

    static boolean fireball(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Spells.sound(level, p, SoundEvents.BLAZE_SHOOT, 1.0f, 1.0f);
        // A 2-block blast (owner, 2026-11-18: a little stronger), widened by Wider Blast / Firestorm.
        double radius = SpellUpgrades.scale(spell, AREA, 2.0);
        boolean inferno = TomeAwakening.awake(p, spell);
        int balls = SpellUpgrades.perk(p, spell, "Twin Fireball") ? 2 : 1;
        // Twin Fireball: the extra balls leave side by side, a little splayed, so both can land on a pack.
        Vec3 side = look.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
        for (int b = 0; b < balls; b++) {
            double off = balls == 1 ? 0 : (b - (balls - 1) / 2.0);
            Vec3 dir = look.add(side.scale(off * .06)).normalize();
            var ball = new Spells.Projectile(
                    p,
                    spell,
                    eye.add(look.scale(0.5)).add(side.scale(off * .6)),
                    dir.scale(1.4),
                    dmg,
                    inferno ? radius * 1.5 : radius,
                    30);
            ball.burnSeconds = SpellUpgrades.scaleInt(spell, DURATION, inferno ? 8 : 4);
            Spells.PROJECTILES.add(ball);
        }
        return true;
    }

    // ---------------------------------------------------------------- Meteor

    static boolean meteor(ServerLevel level, ServerPlayer p, double dmg, double radius) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(48));
        BlockHitResult hitResult =
                level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        LivingEntity aimed = lookTarget(p, 48);
        Vec3 target;
        if (aimed != null
                && (hitResult.getType() == HitResult.Type.MISS
                        || aimed.distanceToSqr(eye) < hitResult.getLocation().distanceToSqr(eye))) {
            target = aimed.position();
        } else if (hitResult.getType() != HitResult.Type.MISS) {
            target = hitResult.getLocation();
        } else {
            p.sendSystemMessage(
                    Component.literal("Too far: aim at the ground within 48 blocks")
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return false;
        }
        sound(level, p, SoundEvents.EVOKER_CAST_SPELL, 1.2f, 0.7f);
        level.playSound(
                null, target.x, target.y, target.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 2.0f, 0.5f);
        // The rock drops straight down from high above, landing three seconds after the cast: the burning ring
        // marks the spot the whole time, and the long fall gives the eye time to find it coming.
        int fall = Math.max(10, SpellUpgrades.scaleInt(Spell.METEOR, SpellUpgrades.Stat.DURATION, METEOR_FALL));
        int stars = SpellUpgrades.perk(p, Spell.METEOR, "Twin Stars") ? 2 : 1;
        boolean crater = SpellUpgrades.perk(p, Spell.METEOR, "Molten Crater");
        boolean gravity = SpellUpgrades.perk(p, Spell.METEOR, "Gravity Stone");
        for (int s = 0; s < stars; s++) {
            // Twin Stars: the extra stone lands a few blocks off the aimed one, on the ground beneath that spot.
            Vec3 spot = s == 0 ? target : besideOnGround(level, target, s, radius);
            meteorAt(level, spot, fall, radius, () -> {
                if (!p.isAlive() || p.level() != level) return;
                areaHit(p, spot, radius, dmg, e -> {
                    e.igniteForSeconds(5);
                    e.setDeltaMovement(e.getDeltaMovement().add(0, 0.6, 0));
                });
                if (crater) Perks.firePatch(p, Spell.METEOR, spot, radius, 100, dmg * .15);
            });
            // Gravity Stone: the last second and a half of the fall drags monsters near the circle into it.
            if (gravity)
                for (int t = Math.max(0, fall - 30); t < fall; t += 2)
                    Scheduler.later(t, () -> {
                        if (!p.isAlive() || p.level() != level) return;
                        for (LivingEntity e : level.getEntitiesOfClass(
                                LivingEntity.class,
                                new net.minecraft.world.phys.AABB(spot, spot).inflate(radius * 2.5, 3, radius * 2.5),
                                e -> e instanceof net.minecraft.world.entity.monster.Enemy && canHitArea(p, e))) {
                            Vec3 in = spot.subtract(e.position()).multiply(1, 0, 1);
                            if (in.lengthSqr() < .5) continue;
                            double k = 1 - SpellKit.knockResist(e);
                            e.setDeltaMovement(e.getDeltaMovement()
                                    .scale(.5)
                                    .add(in.normalize().scale(.25 * k)));
                        }
                        Bosses.ring(level, spot.add(0, .3, 0), radius * 2.5, ParticleTypes.REVERSE_PORTAL, 24);
                    });
        }
        return true;
    }

    /** Where the {@code n}-th extra meteor lands: a blast-width and a bit away from {@code t}, on the ground there. */
    private static Vec3 besideOnGround(ServerLevel level, Vec3 t, int n, double radius) {
        double a = n * 2.4 + level.getRandom().nextDouble() * .6;
        double d = radius * 1.3 + 1;
        BlockPos column = BlockPos.containing(t.x + Math.cos(a) * d, t.y, t.z + Math.sin(a) * d);
        for (int dy = 4; dy >= -8; dy--) {
            BlockPos at = column.above(dy);
            if (!level.getBlockState(at).isAir()
                    && level.getBlockState(at.above()).isAir()) return Vec3.atBottomCenterOf(at.above());
        }
        return new Vec3(column.getX() + .5, t.y, column.getZ() + .5);
    }

    /** A meteor dropping vertically onto {@code t}, landing after {@code fall} ticks. */
    static void meteorAt(ServerLevel level, Vec3 t, int fall, double radius, Runnable impact) {
        StoryGlow.place(level, BlockPos.containing(t).above(), 15, fall + 60);
        var school = CONTEXT.get();
        final Vec3 start = t.add(0, METEOR_HEIGHT * fall / (double) METEOR_FALL, 0);
        final Display.BlockDisplay rock = meteorRock(level, start);
        meteorOutline(level, t, radius);
        for (int i = 1; i <= fall; i++) {
            int tick = i;
            Scheduler.later(tick, () -> {
                Vec3 prev = start.lerp(t, fallCurve(tick - 1, fall));
                Vec3 at = start.lerp(t, fallCurve(tick, fall));
                if (rock != null && !rock.isRemoved()) {
                    rock.setPos(at.x, at.y, at.z);
                    spin(rock, tick);
                }
                for (int k = 0; k < 4; k++) {
                    Vec3 q = prev.lerp(at, k / 4.0);
                    level.sendParticles(ParticleTypes.FLAME, true, true, q.x, q.y, q.z, 6, 0.5, 0.5, 0.5, 0.02);
                    level.sendParticles(
                            ParticleTypes.LARGE_SMOKE, true, true, q.x, q.y + 0.4, q.z, 3, 0.4, 0.4, 0.4, 0.01);
                }
                if (tick % 2 == 0) meteorOutline(level, t, radius);
            });
        }
        Scheduler.later(fall + 1, () -> {
            if (rock != null) {
                rock.discard();
                ROCKS.remove(rock);
            }
            meteorImpact(level, t, radius);
            withSpell(school, impact);
        });
    }

    // ---- Meteor visuals ----

    /** Marks the falling rock, so one saved into a chunk (crash, stop mid-fall) is removed when it loads again. */
    static final String ROCK_TAG = "lightborn_meteor_rock";

    private static final List<Display.BlockDisplay> ROCKS = new ArrayList<>();
    /** Ticks from cast to impact (owner, 2026-11-15: three seconds, straight down). */
    static final int METEOR_FALL = 60;
    /** Height the player's meteor falls from; shorter falls (a boss's) start proportionally lower. */
    private static final double METEOR_HEIGHT = 60;

    private static final float ROCK_SIZE = 2.4f;
    /** Impact radius before Cataclysm ranks (+1 each, 3 ranks back to the first version's 6). */
    static final double METEOR_RADIUS = 3;

    /** Share of the fall covered after {@code tick} ticks: it speeds up as it comes down. */
    private static double fallCurve(int tick, int fall) {
        return Math.pow(Math.max(0, tick) / (double) fall, 1.6);
    }

    /** A glowing magma block the size of a small boulder, drawn by a block display. */
    private static Display.BlockDisplay meteorRock(ServerLevel level, Vec3 at) {
        return trackedRock(level, at, Blocks.MAGMA_BLOCK.defaultBlockState());
    }

    static void thrownRock(ServerLevel level, Vec3 from, Vec3 to, int ticks, Runnable impact) {
        var school = CONTEXT.get();
        var rock = trackedRock(level, from, Blocks.STONE.defaultBlockState());
        for (int i = 1; i <= ticks; i++) {
            int tick = i;
            Scheduler.later(i, () -> {
                double t = tick / (double) ticks;
                Vec3 at = from.lerp(to, t).add(0, 12 * t * (1 - t), 0);
                if (rock != null && !rock.isRemoved()) {
                    rock.setPos(at.x, at.y, at.z);
                    spin(rock, tick);
                }
                if (tick == ticks) {
                    if (rock != null) {
                        rock.discard();
                        ROCKS.remove(rock);
                    }
                    level.sendParticles(
                            new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                            to.x,
                            to.y,
                            to.z,
                            32,
                            0.7,
                            0.3,
                            0.7,
                            0.12);
                    withSpell(school, impact);
                }
            });
        }
    }

    private static Display.BlockDisplay trackedRock(ServerLevel level, Vec3 at, BlockState block) {
        Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (d == null) return null;
        d.setBlockState(block);
        d.setBrightnessOverride(new Brightness(15, 15));
        d.setPosRotInterpolationDuration(1);
        d.setViewRange(4f);
        d.setPos(at.x, at.y, at.z);
        spin(d, 0);
        d.addTag(ROCK_TAG);
        ROCKS.add(d);
        level.addFreshEntity(d);
        return d;
    }

    /** Tumbles the rock about its own centre; the client interpolates between ticks. */
    private static void spin(Display.BlockDisplay d, int tick) {
        Quaternionf rot = new Quaternionf().rotateAxis(tick * 0.35f, new Vector3f(1, 0.6f, 0.3f).normalize());
        // Display transform is translation * rotation * scale: shift by the rotated half-size to spin round the centre.
        Vector3f offset = rot.transform(new Vector3f(ROCK_SIZE / 2)).negate();
        d.setTransformation(new Transformation(offset, rot, new Vector3f(ROCK_SIZE), new Quaternionf()));
        d.setTransformationInterpolationDelay(0);
        d.setTransformationInterpolationDuration(1);
    }

    /** A meteor rock or ore glow this server spawned and still tracks; any other tagged one is a leftover. */
    static boolean isLiveDisplay(Entity e) {
        return ROCKS.contains(e) || GLOWS.containsKey(e);
    }

    /** Server stopping: no rock or ore glow is saved into the world. */
    static void clearDisplays() {
        for (Display.BlockDisplay d : ROCKS) d.discard();
        ROCKS.clear();
        for (Display.BlockDisplay d : GLOWS.keySet()) d.discard();
        GLOWS.clear();
    }

    /**
     * The warning circle: six close rings of small embers make a thick band, plus a self-lit flame edge. Small embers
     * on purpose: a dust particle lives longer the bigger it is (8 to 40 ticks at scale 1), so the band clears within
     * about a second of the impact instead of hanging around.
     */
    private static void meteorOutline(ServerLevel level, Vec3 c, double radius) {
        DustParticleOptions ember = new DustParticleOptions(0xFF6A00, 1.0f);
        int points = (int) (26 * radius);
        for (int i = 0; i < 6; i++) Bosses.ring(level, c, radius - 0.12 * i, ember, points);
        Bosses.ring(
                level,
                c,
                radius - 0.3,
                ParticleTypes.SMALL_FLAME,
                points / 2); // dust takes the world's light; flame reads at night
    }

    /** A random point inside the impact disc, at most {@code within} from the centre. */
    private static Vec3 inDisc(ServerLevel level, Vec3 c, double within) {
        double a = level.getRandom().nextDouble() * Math.PI * 2;
        double r = Math.sqrt(level.getRandom().nextDouble()) * within;
        return new Vec3(c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r);
    }

    /**
     * Blast, ground debris, a burning shockwave out to the edge and a smoke column. Everything spawns inside the
     * warning circle with little sideways speed, so the splash never spills past the outline.
     */
    private static void meteorImpact(ServerLevel level, Vec3 t, double radius) {
        for (int i = 0; i < 3 + (int) (radius * 1.5); i++) {
            Vec3 q = inDisc(level, t, radius * 0.6);
            level.sendParticles(ParticleTypes.EXPLOSION, true, true, q.x, q.y + 0.6, q.z, 1, 0, 0, 0, 0);
        }
        BlockState ground = level.getBlockState(BlockPos.containing(t.x, t.y - 0.5, t.z));
        if (ground.isAir()) ground = Blocks.DIRT.defaultBlockState();
        BlockParticleOption debris = new BlockParticleOption(ParticleTypes.BLOCK, ground);
        for (int i = 0; i < (int) (radius * 30); i++) {
            Vec3 q = inDisc(level, t, radius * 0.8);
            // count 0: the three numbers are the velocity. Mostly up, barely sideways.
            level.sendParticles(
                    debris,
                    true,
                    true,
                    q.x,
                    q.y + 0.2,
                    q.z,
                    0,
                    (level.getRandom().nextDouble() - 0.5) * 0.1,
                    0.35 + level.getRandom().nextDouble() * 0.35,
                    (level.getRandom().nextDouble() - 0.5) * 0.1,
                    1.0);
        }
        for (int i = 0; i < (int) (radius * 25); i++) {
            Vec3 q = inDisc(level, t, radius * 0.9);
            level.sendParticles(ParticleTypes.FLAME, q.x, q.y + 0.2, q.z, 1, 0, 0.1, 0, 0.01);
        }
        for (int i = 0; i < (int) (radius * 6); i++) {
            Vec3 q = inDisc(level, t, Math.max(0.5, radius - 1.2)); // lava pops hop about a block
            level.sendParticles(ParticleTypes.LAVA, q.x, q.y + 0.3, q.z, 1, 0, 0, 0, 0);
        }
        level.playSound(null, t.x, t.y, t.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 3.0f, 0.6f);
        level.playSound(null, t.x, t.y, t.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.5f, 0.5f);
        for (int i = 0; i <= 6; i++) {
            int step = i;
            Scheduler.later(step + 1, () -> {
                double r = 0.5 + (radius - 0.5) * step / 6.0;
                Bosses.ring(level, t, r, ParticleTypes.FLAME, (int) (12 * r) + 4);
                Bosses.ring(level, t, r, ParticleTypes.SMOKE, (int) (6 * r) + 2);
            });
        }
        for (int i = 0; i < 40; i += 4) {
            Scheduler.later(
                    i + 2,
                    () -> level.sendParticles(
                            ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                            true,
                            true,
                            t.x,
                            t.y + 0.5,
                            t.z,
                            2,
                            0.4,
                            0.1,
                            0.4,
                            0.01));
        }
    }

    // ---------------------------------------------------------------- Emberwalk

    static final double WALK_RADIUS = 1.3;
    static final int WALK_TICKS = 1200;

    static final class Walk {
        int ticks;
        final double radius;

        Walk(int ticks, double radius) {
            this.ticks = ticks;
            this.radius = radius;
        }
    }

    static final Map<ServerPlayer, Walk> WALKS = new IdentityHashMap<>();

    /** For a minute you cannot burn, and lava hardens under your feet as you walk on it. */
    static boolean emberwalk(ServerLevel level, ServerPlayer p) {
        int ticks = SpellUpgrades.scaleInt(Spell.EMBERWALK, DURATION, WALK_TICKS);
        WALKS.put(p, new Walk(ticks, SpellUpgrades.scale(Spell.EMBERWALK, AREA, WALK_RADIUS)));
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, ticks, 0, false, false, true));
        p.clearFire();
        crust(level, p, WALKS.get(p).radius);
        atFeet(p, new DustParticleOptions(0xFF8A3A, 1f), ParticleTypes.FLAME, 14);
        sound(level, p.position(), SoundEvents.FIRECHARGE_USE, .8f, .8f);
        sound(level, p.position(), SoundEvents.BASALT_PLACE, 1f, .7f);
        return true;
    }

    static boolean walking(ServerPlayer p) {
        return WALKS.containsKey(p);
    }

    /** A monster that strikes an Emberwalker, up close or from afar, catches fire (owner, 2026-11-22). */
    static void afterDamage(LivingEntity hurt, DamageSource source, float baseTaken, float taken, boolean blocked) {
        if (!(hurt instanceof ServerPlayer p) || !WALKS.containsKey(p)) return;
        if (!(source.getEntity() instanceof LivingEntity attacker) || !monster(p, attacker)) return;
        attacker.igniteForSeconds(4);
        ((ServerLevel) attacker.level())
                .sendParticles(
                        ParticleTypes.FLAME,
                        attacker.getX(),
                        attacker.getY() + attacker.getBbHeight() * .5,
                        attacker.getZ(),
                        10,
                        .25,
                        .4,
                        .25,
                        .02);
    }

    static void tickWalks() {
        for (var it = WALKS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var p = entry.getKey();
            var w = entry.getValue();
            if (p.isRemoved() || !p.isAlive() || --w.ticks < 0) {
                it.remove();
                continue;
            }
            p.clearFire();
            var level = p.level();
            crust(level, p, w.radius);
            if (w.ticks % 40 == 0) {
                if (SpellUpgrades.perk(p, Spell.EMBERWALK, "Smouldering Aura"))
                    for (var e : Perks.nearestMonsters(p, null, p.position(), 3, 64)) e.igniteForSeconds(3);
                if (SpellUpgrades.perk(p, Spell.EMBERWALK, "Lava Heart")
                        && (p.isInLava()
                                || level.getBlockState(p.blockPosition().below())
                                        .is(EmberCrust.CRUST))) p.heal(1);
            }
            if (w.ticks % 10 == 0) {
                if (SpellUpgrades.perk(p, Spell.EMBERWALK, "Ember Speed")
                        && level.getBlockState(p.blockPosition().below()).is(EmberCrust.CRUST))
                    p.addEffect(new MobEffectInstance(MobEffects.SPEED, 20, 0, false, false, true));
                if (SpellUpgrades.perk(p, Spell.EMBERWALK, "Cinder Shield"))
                    p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20, 0, false, false, true));
            }
            if (w.ticks % 3 == 0)
                level.sendParticles(ParticleTypes.SMALL_FLAME, p.getX(), p.getY() + .1, p.getZ(), 1, .3, 0, .3, .01);
        }
    }

    /** Hardens the lava round and under the walker's feet; each crust melts back on its own once left. */
    static int crust(ServerLevel level, ServerPlayer p, double radius) {
        BlockPos feet = BlockPos.containing(p.getX(), p.getY() - .2, p.getZ());
        int r = (int) Math.ceil(radius), made = 0;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                BlockPos at = feet.offset(dx, 0, dz);
                var fluid = level.getFluidState(at);
                if (!fluid.is(FluidTags.LAVA) || !level.getBlockState(at).is(Blocks.LAVA)) continue;
                if (!level.mayInteract(p, at)) continue;
                level.setBlockAndUpdate(
                        at, EmberCrust.CRUST.defaultBlockState().setValue(EmberCrust.SOURCE, fluid.isSource()));
                level.scheduleTick(at, EmberCrust.CRUST, EmberCrust.HOLD_TICKS);
                made++;
            }
        if (made > 0) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + .05, p.getZ(), 2, .5, 0, .5, .01);
            if (level.getRandom().nextInt(4) == 0) sound(level, p.position(), SoundEvents.LAVA_EXTINGUISH, .3f, 1.4f);
        }
        return made;
    }
}
