package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.chest;
import static lightborn.SpellKit.sound;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.AREA;
import static lightborn.SpellUpgrades.Stat.COUNT;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.SpellUpgrades.Stat.REACH;
import static lightborn.Spells.ring;
import static lightborn.Spells.sound;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Light spells: radiance that strikes, guards and carries. Light, Sunbeam, Wisps, Halo Disc, Starfall, Splinter,
 * Smite, Dawnbreak, Lightbrand, Blade Ring, Ward, Light Bridge and Light Steed.
 */
public final class LightSpells {
    private LightSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case MENDING_LIGHT -> {
                light(level, p, dmg, SpellUpgrades.special(spell));
                yield true;
            }
            case SUNBEAM -> sunbeam(level, p, dmg);
            case WISPS -> wisps(level, p, dmg);
            case HALO_DISC -> haloDisc(level, p, dmg);
            case STARFALL -> starfall(level, p, dmg);
            case SPLINTER -> splinter(level, p, dmg);
            case BANISH -> smite(level, p, dmg);
            case DAWNBREAK -> dawnbreak(level, p, dmg);
            case LIGHTBRAND -> lightbrand(level, p, dmg);
            case BLADE_RING -> bladeRing(level, p, dmg);
            case SANCTUARY -> sanctuary(level, p);
            case LIGHT_BRIDGE -> lightBridge(level, p);
            case SPIRIT_STEED -> spiritSteed(level, p);
            default -> throw new IllegalArgumentException(spell + " is not a light spell");
        };
    }

    static void tick(MinecraftServer server) {
        tickRays();
        tickWisps();
        tickDiscs();
        tickStars();
        tickDomes();
        tickBridges();
        tickSteeds();
        tickBrands();
        tickRings();
        tickDawns();
    }

    static void clear() {
        for (var p : new ArrayList<>(RAYS.keySet())) letGo(p);
        WISPS.clear();
        DISCS.clear();
        STARS.clear();
        DOMES.forEach(d -> {
            if (d.aura != null) d.aura.discard();
        });
        DOMES.clear();
        BRIDGES.clear();
        for (var s : new ArrayList<>(STEEDS.values())) s.horse.discard();
        STEEDS.clear();
        BRANDS.keySet().forEach(p -> p.removeAttached(ModData.BRANDED));
        BRANDS.clear();
        RINGS.values().forEach(LightSpells::sheathe);
        RINGS.clear();
        DAWNS.clear();
        SUNS.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "wisps" -> WISPS.size();
            case "discs" -> DISCS.size();
            case "stars" -> STARS.size();
            case "domes" -> DOMES.size();
            case "bridges" -> BRIDGES.size();
            case "steeds" -> STEEDS.size();
            case "brands" -> BRANDS.size();
            case "rings" -> RINGS.size();
            case "dawns" -> DAWNS.size();
            case "suns" -> SUNS.size();
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- Light

    static int lightRadius() {
        return SpellUpgrades.scaleInt(Spell.MENDING_LIGHT, SpellUpgrades.Stat.AREA, LIGHT_RADIUS);
    }

    static final int LIGHT_RADIUS = 8;

    /**
     * Heals the caster and every friendly creature within 8 blocks (other players, pets, villagers, animals; never a
     * monster); with Purifying Light, it also cures every harmful effect. Always casts: the ring of light shows even when nobody
     * is hurt (owner rule: a spell that does nothing visible reads as broken).
     */
    static void light(ServerLevel level, ServerPlayer p, double power, boolean cures) {
        List<LivingEntity> friends = new ArrayList<>();
        friends.add(p);
        int radius = lightRadius();
        friends.addAll(friendsNear(level, p, p.position(), radius));
        for (LivingEntity e : friends) {
            boolean hurt = e.getHealth() < e.getMaxHealth();
            e.heal((float) power);
            if (cures) {
                for (var fx : List.copyOf(e.getActiveEffects())) {
                    if (fx.getEffect().value().getCategory() == MobEffectCategory.HARMFUL)
                        e.removeEffect(fx.getEffect());
                }
            }
            if (hurt) {
                e.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 0, false, false, true));
                level.sendParticles(
                        ParticleTypes.HEART, e.getX(), e.getY() + e.getBbHeight() + 0.3, e.getZ(), 4, 0.4, 0.2, 0.4, 0);
            }
            level.sendParticles(
                    ParticleTypes.END_ROD,
                    e.getX(),
                    e.getY() + e.getBbHeight() * 0.5,
                    e.getZ(),
                    12,
                    0.4,
                    0.6,
                    0.4,
                    0.03);
        }
        ring(level, p.position().add(0, 0.2, 0), radius, ParticleTypes.END_ROD);
        if (SpellUpgrades.perk(p, Spell.MENDING_LIGHT, "Smite"))
            Spells.areaHit(
                    p,
                    p.position().add(0, 1, 0),
                    radius,
                    power * .5,
                    e -> level.sendParticles(
                            ParticleTypes.END_ROD, e.getX(), e.getY() + e.getBbHeight(), e.getZ(), 8, .2, .3, .2, .1));
        if (SpellUpgrades.perk(p, Spell.MENDING_LIGHT, "Second Wind")) {
            Perks.mana(p, 25);
            p.getFoodData().eat(3, .6f);
        }
        if (SpellUpgrades.perk(p, Spell.MENDING_LIGHT, "Beacon of Hope")) {
            Vec3 at = p.position();
            for (int t = 0; t <= 160; t += 5) {
                int tick = t;
                Scheduler.later(t, () -> {
                    if (p.isRemoved() || p.level() != level) return;
                    for (int k = 0; k < 6; k++)
                        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + k * .8, at.z, 1, .08, .2, .08, .01);
                    if (tick % 20 == 0) for (LivingEntity e : friendsNear(level, p, at, LIGHT_RADIUS)) e.heal(1);
                    if (tick % 20 == 0 && p.position().distanceTo(at) <= LIGHT_RADIUS) p.heal(1);
                });
            }
        }
        level.sendParticles(
                ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF4C0),
                p.getX(),
                p.getY() + 1,
                p.getZ(),
                1,
                0,
                0,
                0,
                0);
        sound(level, p, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, 1.4f);
        sound(level, p, SoundEvents.BEACON_POWER_SELECT, 0.5f, 1.8f);
    }

    /** Friendly creatures round a point (other players, pets, villagers, animals; never a monster), not the caster. */
    private static List<LivingEntity> friendsNear(ServerLevel level, ServerPlayer p, Vec3 c, int r) {
        return level.getEntitiesOfClass(
                LivingEntity.class,
                new AABB(c, c).inflate(r),
                e -> e != p
                        && e.isAlive()
                        && !(e instanceof Enemy)
                        && !(e instanceof ArmorStand)
                        && e.position().distanceToSqr(c) <= r * r);
    }

    // ---------------------------------------------------------------- Sunbeam

    static final int BEAM_REACH = 24;
    static final double BEAM_WIDTH = .8;

    /**
     * One pulse of the beam: held, the tome recasts every fifth of a second, so it pours. It runs from the book to the
     * first wall, striking every creature on the way; the undead take double.
     */
    static boolean sunbeam(ServerLevel level, ServerPlayer p, double dmg) {
        double reach = SpellUpgrades.scale(Spell.SUNBEAM, REACH, BEAM_REACH);
        double width = SpellUpgrades.scale(Spell.SUNBEAM, AREA, BEAM_WIDTH);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        var first = beam(level, p, eye.add(0, -.15, 0), look, reach, width, dmg, true);
        if (first != null && SpellUpgrades.perk(p, Spell.SUNBEAM, "Prism")) {
            Vec3 at = chest(first);
            for (double turn : new double[] {-.45, .45}) {
                Vec3 d = look.yRot((float) turn);
                beam(level, p, at.add(d.scale(.6)), d, reach / 2, width, dmg * .5, false);
            }
        }
        if (Scheduler.now() % 3 == 0) sound(level, eye, SoundEvents.BEACON_AMBIENT, .6f, 1.9f);
        return true;
    }

    /** One beam; the first creature it strikes, or null. */
    private static LivingEntity beam(
            ServerLevel level,
            ServerPlayer p,
            Vec3 from,
            Vec3 dir,
            double reach,
            double width,
            double dmg,
            boolean main) {
        BlockHitResult wall = level.clip(new ClipContext(
                from, from.add(dir.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 end = wall.getType() == HitResult.Type.MISS ? from.add(dir.scale(reach)) : wall.getLocation();
        var struck = new ArrayList<LivingEntity>();
        for (LivingEntity e : level.getEntitiesOfClass(
                LivingEntity.class, new AABB(from, end).inflate(width + 1), e -> Spells.canHitDirect(p, e))) {
            if (distToSegment(e.getBoundingBox().getCenter(), from, end) <= width + e.getBbWidth() / 2) struck.add(e);
        }
        struck.sort((a, b) -> Double.compare(a.distanceToSqr(from), b.distanceToSqr(from)));
        boolean healed = false;
        for (var e : struck) {
            boolean undead = e.getType().builtInRegistryHolder().is(EntityTypeTags.UNDEAD);
            Spells.hit(p, e, undead ? dmg * 2 : dmg, Spell.SUNBEAM);
            if (SpellUpgrades.perk(p, Spell.SUNBEAM, "Kindling Light")) e.igniteForSeconds(3);
            if (SpellUpgrades.perk(p, Spell.SUNBEAM, "Blinding Beam") && e instanceof Mob m && m.getTarget() == p)
                m.setTarget(null);
            if (SpellUpgrades.perk(p, Spell.SUNBEAM, "Solar Mana")) Perks.mana(p, 1);
            if (!healed && SpellUpgrades.perk(p, Spell.SUNBEAM, "Sun's Gift")) {
                p.heal(.5f);
                healed = true;
            }
            level.sendParticles(
                    ParticleTypes.END_ROD, e.getX(), e.getY() + e.getBbHeight() * .6, e.getZ(), 3, .2, .2, .2, .02);
        }
        if (main) ray(level, p, end, width);
        else {
            // A Prism's split beam: a thin line of light from where it splits (never near the caster's eyes).
            SpellKit.line(level, from, end, new DustParticleOptions(0xFFF6D0, .6f), .35);
        }
        level.sendParticles(ParticleTypes.END_ROD, end.x, end.y, end.z, 1, .1, .1, .1, .02);
        return struck.isEmpty() ? null : struck.getFirst();
    }

    /**
     * The beam each caster pours: a white-hot core inside a gold glow, two block displays stretched from the book to
     * where the beam ends and turned along it. Each pulse moves them (smoothly, over the pulse); with no pulse for a
     * few ticks the beam is let go. Sparks along it are few and self-lit, so it reads as light, not smoke.
     */
    static final class Ray {
        Display.BlockDisplay core, glow;
        long last;
    }

    static final Map<ServerPlayer, Ray> RAYS = new IdentityHashMap<>();
    static final int RAY_LINGER = 6;

    private static void ray(ServerLevel level, ServerPlayer p, Vec3 end, double width) {
        Vec3 look = p.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0, look.x);
        right = right.lengthSqr() < 1e-6 ? Vec3.ZERO : right.normalize();
        // The book is in the off hand: the beam leaves that side of the view, low, and runs to the crosshair's mark.
        double side = p.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? -1 : 1;
        Vec3 book = p.getEyePosition()
                .add(look.scale(.5))
                .add(right.scale(.36 * side))
                .add(0, -.42, 0);
        Vec3 span = end.subtract(book);
        if (span.length() < 2.2) {
            letGo(p);
            return;
        }
        Vec3 from = book.add(span.normalize().scale(1.4));
        var r = RAYS.computeIfAbsent(p, k -> new Ray());
        if (r.core == null || r.core.isRemoved() || r.core.level() != level) {
            letGo(p);
            r = new Ray();
            RAYS.put(p, r);
            r.glow = shaft(level, from, Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.YELLOW));
            r.core = shaft(level, from, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.WHITE));
        }
        r.last = Scheduler.now();
        boolean fresh = r.core.tickCount == 0;
        stretch(r.glow, from, end, (float) (.14 + width * .12), fresh);
        stretch(r.core, from, end, (float) (.05 + width * .03), fresh);
        var random = level.getRandom();
        double len = from.distanceTo(end);
        for (int k = 0; k < 2; k++) {
            Vec3 at = from.add(end.subtract(from).scale(.15 + random.nextDouble() * .85));
            if (at.distanceToSqr(p.getEyePosition()) < 2.5 * 2.5 || len < 2) continue;
            level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 1, .05, .05, .05, .005);
        }
    }

    private static Display.BlockDisplay shaft(ServerLevel level, Vec3 at, Block block) {
        var d = net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.MOB_SUMMONED);
        d.setBlockState(block.defaultBlockState());
        return SpellKit.track(level, d, at);
    }

    /** A square shaft {@code thick} across, from {@code from} to {@code to}: the block's z axis turned onto the line. */
    private static void stretch(Display.BlockDisplay d, Vec3 from, Vec3 to, float thick, boolean fresh) {
        Vec3 v = to.subtract(from);
        float len = (float) v.length();
        var rot = new Quaternionf()
                .rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) v.x, (float) v.y, (float) v.z).normalize());
        Vector3f offset = rot.transform(new Vector3f(-thick / 2, -thick / 2, 0));
        d.setPos(from.x, from.y, from.z);
        d.setTransformation(new Transformation(offset, rot, new Vector3f(thick, thick, len), new Quaternionf()));
        d.setTransformationInterpolationDelay(0);
        d.setTransformationInterpolationDuration(fresh ? 0 : 3);
    }

    private static void letGo(ServerPlayer p) {
        var r = RAYS.remove(p);
        if (r == null) return;
        SpellKit.drop(r.core);
        SpellKit.drop(r.glow);
    }

    /** A beam no longer poured (the button let go, the mana run dry) goes out. */
    static void tickRays() {
        long now = Scheduler.now();
        for (var p : new ArrayList<>(RAYS.keySet())) {
            var r = RAYS.get(p);
            if (p.isRemoved() || !p.isAlive() || now - r.last > RAY_LINGER) letGo(p);
        }
    }

    // ---------------------------------------------------------------- Wisps

    /**
     * Owner, 2026-11-23: wisps keep attacking instead of fading at their first strike: each lives 10 s and strikes once
     * a second for 1, bouncing off its prey between strikes.
     */
    static final int WISP_COUNT = 5, WISP_TICKS = 200, WISP_REST = 20;

    static final double WISP_SEEK = 16;

    static final class Wisp {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        Vec3 pos, vel;
        LivingEntity target;
        int life, rest;
        final double dmg;
        final boolean child;
        final int index;

        Wisp(ServerPlayer owner, Vec3 pos, Vec3 vel, int life, double dmg, boolean child, int index) {
            this.owner = owner;
            this.level = owner.level();
            this.pos = pos;
            this.vel = vel;
            this.life = life;
            this.dmg = dmg;
            this.child = child;
            this.index = index;
        }
    }

    static final List<Wisp> WISPS = new ArrayList<>();

    /** Five motes of light leave the book and each hunts the nearest monster, spreading over the pack, again and again. */
    static boolean wisps(ServerLevel level, ServerPlayer p, double dmg) {
        int n = SpellUpgrades.add(Spell.WISPS, COUNT, WISP_COUNT);
        int life = SpellUpgrades.scaleInt(Spell.WISPS, DURATION, WISP_TICKS);
        Vec3 look = p.getLookAngle();
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            Vec3 at = p.getEyePosition().add(Math.cos(a) * .8, -.2 + Math.sin(a) * .3, Math.sin(a) * .8);
            Vec3 v = look.scale(.35).add(Math.cos(a) * .15, .12, Math.sin(a) * .15);
            WISPS.add(new Wisp(p, at, v, life, dmg, false, i));
        }
        sound(level, p.getEyePosition(), SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 1.9f);
        sound(level, p.getEyePosition(), SoundEvents.ALLAY_ITEM_TAKEN, .8f, 1.6f);
        return true;
    }

    static void tickWisps() {
        var chased = new IdentityHashMap<LivingEntity, Integer>();
        for (var w : WISPS) if (w.target != null && w.target.isAlive()) chased.merge(w.target, 1, Integer::sum);
        var born = new ArrayList<Wisp>();
        for (Iterator<Wisp> it = WISPS.iterator(); it.hasNext(); ) {
            var w = it.next();
            if (w.owner.isRemoved() || w.owner.level() != w.level || --w.life < 0) {
                w.level.sendParticles(ParticleTypes.END_ROD, w.pos.x, w.pos.y, w.pos.z, 3, .1, .1, .1, .02);
                it.remove();
                continue;
            }
            if (w.target == null || !w.target.isAlive() || w.target.distanceToSqr(w.pos) > 24 * 24) {
                if (w.target != null) chased.merge(w.target, -1, Integer::sum);
                w.target = null;
                LivingEntity best = null;
                double bestScore = Double.MAX_VALUE;
                for (var e : w.level.getEntitiesOfClass(
                        LivingEntity.class, new AABB(w.pos, w.pos).inflate(WISP_SEEK), e -> monster(w.owner, e))) {
                    double score = e.distanceToSqr(w.pos) + chased.getOrDefault(e, 0) * 64;
                    if (score < bestScore) {
                        bestScore = score;
                        best = e;
                    }
                }
                if (best != null) {
                    w.target = best;
                    chased.merge(best, 1, Integer::sum);
                }
            }
            if (w.rest > 0) {
                // Bounced off its prey: it drifts a moment before it dives again.
                w.rest--;
                w.vel = w.vel.scale(.85).add(0, .01, 0);
            } else if (w.target != null) {
                Vec3 dir = chest(w.target).subtract(w.pos).normalize();
                w.vel = w.vel.scale(.72).add(dir.scale(.3));
            } else {
                // Nothing to hunt: circle the caster's shoulders and wait.
                double a = Scheduler.now() * .15 + w.index * 1.3;
                Vec3 home = w.owner.position().add(Math.cos(a) * 1.3, 1.9, Math.sin(a) * 1.3);
                w.vel = w.vel.scale(.6).add(home.subtract(w.pos).scale(.12));
            }
            if (w.vel.length() > .8) w.vel = w.vel.normalize().scale(.8);
            w.pos = w.pos.add(w.vel);
            w.level.sendParticles(
                    new DustParticleOptions(0xCFF4FF, 1.2f), w.pos.x, w.pos.y, w.pos.z, 1, .02, .02, .02, 0);
            if (w.life % 2 == 0)
                w.level.sendParticles(ParticleTypes.END_ROD, w.pos.x, w.pos.y, w.pos.z, 1, .03, .03, .03, .005);
            if (w.rest == 0
                    && w.target != null
                    && w.target.getBoundingBox().inflate(.35).contains(w.pos)) {
                var t = w.target;
                Spells.hit(w.owner, t, w.dmg, Spell.WISPS, w.cast);
                if (SpellUpgrades.perk(w.owner, Spell.WISPS, "Bursting Wisps"))
                    Spells.withSpell(
                            Spell.WISPS,
                            () -> Perks.burst(w.owner, w.pos, 2, w.dmg * .5, new DustParticleOptions(0xCFF4FF, 1.2f)));
                if (SpellUpgrades.perk(w.owner, Spell.WISPS, "Leeching Motes")) Perks.mana(w.owner, 2);
                if (SpellUpgrades.perk(w.owner, Spell.WISPS, "Glowing Wisps") && t.isAlive())
                    t.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0, false, false, false));
                if (!t.isAlive() && !w.child && SpellUpgrades.perk(w.owner, Spell.WISPS, "Splitting Light"))
                    for (int k = 0; k < 2; k++)
                        born.add(new Wisp(w.owner, w.pos, new Vec3(k == 0 ? .2 : -.2, .2, 0), 60, w.dmg, true, k));
                sound(w.level, w.pos, SoundEvents.AMETHYST_CLUSTER_BREAK, .6f, 1.8f);
                w.rest = WISP_REST;
                Vec3 off = w.pos.subtract(chest(t));
                off = off.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : off.normalize();
                w.vel = off.scale(.45).add(0, .25, 0);
            }
        }
        WISPS.addAll(born);
    }

    // ---------------------------------------------------------------- Halo Disc

    static final double DISC_REACH = 14, DISC_SPEED = 1.1, DISC_WIDTH = 1.0;

    static final class Disc {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        Vec3 pos, dir;
        double travelled;
        int phase; // 0 out, 1 ricochet, 2 back
        LivingEntity ricochet;
        final double reach, width, dmg;
        final Set<LivingEntity> hitOut = new HashSet<>(), hitBack = new HashSet<>();
        int life = 160;

        Disc(ServerPlayer owner, Vec3 pos, Vec3 dir, double reach, double width, double dmg) {
            this.owner = owner;
            this.level = owner.level();
            this.pos = pos;
            this.dir = dir;
            this.reach = reach;
            this.width = width;
            this.dmg = dmg;
        }
    }

    static final List<Disc> DISCS = new ArrayList<>();

    /** A spinning blade of light flies out to its reach (or a wall) and comes back, cutting each creature both ways. */
    static boolean haloDisc(ServerLevel level, ServerPlayer p, double dmg) {
        double reach = SpellUpgrades.scale(Spell.HALO_DISC, REACH, DISC_REACH);
        double width = SpellUpgrades.scale(Spell.HALO_DISC, AREA, DISC_WIDTH);
        Vec3 look = p.getLookAngle(), from = p.getEyePosition().add(0, -.3, 0).add(look.scale(.8));
        boolean twin = SpellUpgrades.perk(p, Spell.HALO_DISC, "Twin Halos");
        for (double turn : twin ? new double[] {-.2, .2} : new double[] {0})
            DISCS.add(new Disc(p, from, look.yRot((float) turn), reach, width, dmg));
        sound(level, from, SoundEvents.PLAYER_ATTACK_SWEEP, 1f, 1.4f);
        sound(level, from, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.5f);
        return true;
    }

    static void tickDiscs() {
        for (Iterator<Disc> it = DISCS.iterator(); it.hasNext(); ) {
            var d = it.next();
            if (d.owner.isRemoved() || d.owner.level() != d.level || --d.life < 0) {
                it.remove();
                continue;
            }
            if (d.phase == 0) {
                Vec3 next = d.pos.add(d.dir.scale(DISC_SPEED));
                var wall = d.level.clip(
                        new ClipContext(d.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, d.owner));
                d.travelled += DISC_SPEED;
                if (wall.getType() != HitResult.Type.MISS || d.travelled >= d.reach) {
                    if (wall.getType() != HitResult.Type.MISS)
                        d.pos = wall.getLocation().subtract(d.dir.scale(.3));
                    else d.pos = next;
                    d.phase = 2;
                    if (SpellUpgrades.perk(d.owner, Spell.HALO_DISC, "Ricochet")) {
                        LivingEntity best = null;
                        for (var e : d.level.getEntitiesOfClass(
                                LivingEntity.class, new AABB(d.pos, d.pos).inflate(8), e -> monster(d.owner, e)))
                            if (best == null || e.distanceToSqr(d.pos) < best.distanceToSqr(d.pos)) best = e;
                        if (best != null) {
                            d.ricochet = best;
                            d.phase = 1;
                        }
                    }
                } else d.pos = next;
            } else if (d.phase == 1) {
                if (d.ricochet == null || !d.ricochet.isAlive()) d.phase = 2;
                else {
                    Vec3 to = chest(d.ricochet).subtract(d.pos);
                    d.pos = d.pos.add(
                            to.length() < DISC_SPEED * 1.3 ? to : to.normalize().scale(DISC_SPEED * 1.3));
                    if (d.ricochet.getBoundingBox().inflate(d.width * .5).contains(d.pos)) {
                        Spells.hit(d.owner, d.ricochet, d.dmg, Spell.HALO_DISC, d.cast);
                        bleed(d.owner, d.ricochet, d.dmg);
                        d.phase = 2;
                    }
                }
            } else {
                Vec3 to = chest(d.owner).subtract(d.pos);
                if (to.length() < 1.4) {
                    sound(d.level, d.pos, SoundEvents.AMETHYST_BLOCK_RESONATE, .8f, 1.6f);
                    it.remove();
                    continue;
                }
                d.pos = d.pos.add(to.normalize().scale(Math.min(to.length(), DISC_SPEED * 1.2)));
            }
            var hit = d.phase == 0 ? d.hitOut : d.hitBack;
            for (var e : d.level.getEntitiesOfClass(
                    LivingEntity.class,
                    new AABB(d.pos, d.pos).inflate(d.width + 1),
                    e -> Spells.canHitDirect(d.owner, e) && !hit.contains(e))) {
                if (e.getBoundingBox().inflate(d.width * .6).contains(d.pos) || chest(e).distanceTo(d.pos) < d.width) {
                    hit.add(e);
                    Spells.hit(d.owner, e, d.dmg, Spell.HALO_DISC, d.cast);
                    bleed(d.owner, e, d.dmg);
                }
            }
            // A spinning ring of gold light, flat, with a bright heart.
            double spin = Scheduler.now() * .9;
            for (int k = 0; k < 8; k++) {
                double a = spin + k * Math.PI / 4;
                d.level.sendParticles(
                        new DustParticleOptions(0xFFD86B, .9f),
                        d.pos.x + Math.cos(a) * .55 * d.width,
                        d.pos.y,
                        d.pos.z + Math.sin(a) * .55 * d.width,
                        1,
                        0,
                        0,
                        0,
                        0);
            }
            d.level.sendParticles(ParticleTypes.END_ROD, d.pos.x, d.pos.y, d.pos.z, 1, 0, 0, 0, 0);
            if (d.life % 5 == 0) sound(d.level, d.pos, SoundEvents.TRIDENT_RIPTIDE_1.value(), .25f, 2f);
        }
    }

    /** Bleeding Edge: a cut bleeds a fifth of the hit each second for 4 seconds. */
    private static void bleed(ServerPlayer owner, LivingEntity e, double dmg) {
        if (SpellUpgrades.perk(owner, Spell.HALO_DISC, "Dazzling Halo") && e.isAlive())
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1, false, true, true), owner);
        if (!SpellUpgrades.perk(owner, Spell.HALO_DISC, "Bleeding Edge")) return;
        for (int k = 1; k <= 4; k++)
            Scheduler.later(20 * k, () -> {
                if (!e.isAlive() || owner.isRemoved()) return;
                Spells.hit(owner, e, dmg * .2, Spell.HALO_DISC);
                ((ServerLevel) e.level())
                        .sendParticles(
                                new DustParticleOptions(0xB01020, 1f),
                                e.getX(),
                                e.getY() + 1,
                                e.getZ(),
                                4,
                                .2,
                                .3,
                                .2,
                                0);
            });
    }

    // ---------------------------------------------------------------- Starfall

    static final double STAR_AREA = 6, STAR_BURST = 1.8;
    static final int STAR_TICKS = 120, STAR_COUNT = 24, STAR_FALL = 10;
    // owner, 2026-11-22: 6 s, was 3

    static final class Star {
        final ServerPlayer owner;
        final ServerLevel level;
        final Vec3 from, to;
        int t;
        final double dmg, radius;

        Star(ServerPlayer owner, Vec3 from, Vec3 to, double dmg, double radius) {
            this.owner = owner;
            this.level = owner.level();
            this.from = from;
            this.to = to;
            this.dmg = dmg;
            this.radius = radius;
        }
    }

    static final List<Star> STARS = new ArrayList<>();

    /** For 3 seconds small stars fall over the area you aim at, each bursting where it lands. */
    static boolean starfall(ServerLevel level, ServerPlayer p, double dmg) {
        Vec3 aim = SpellKit.aimPoint(p, 32);
        if (aim == null) return false;
        Vec3 centre = Perks.ground(level, aim);
        double area = SpellUpgrades.scale(Spell.STARFALL, AREA, STAR_AREA);
        int ticks = SpellUpgrades.scaleInt(Spell.STARFALL, DURATION, STAR_TICKS);
        StoryGlow.place(level, BlockPos.containing(centre.add(0, .4, 0)), 15, ticks + 40);
        int count = Math.max(1, Math.round(STAR_COUNT * ticks / (float) STAR_TICKS));
        boolean guiding = SpellUpgrades.perk(p, Spell.STARFALL, "Guiding Stars");
        var random = level.getRandom();
        for (int i = 0; i < count; i++) {
            Scheduler.later(1 + i * ticks / count, () -> {
                if (p.isRemoved() || p.level() != level) return;
                Vec3 to = null;
                if (guiding) {
                    var near = level.getEntitiesOfClass(
                            LivingEntity.class, new AABB(centre, centre).inflate(area, 4, area), e -> monster(p, e));
                    if (!near.isEmpty())
                        to = near.get(random.nextInt(near.size())).position();
                }
                if (to == null) {
                    double a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble()) * area;
                    to = Perks.ground(level, centre.add(Math.cos(a) * r, 2, Math.sin(a) * r));
                }
                launch(p, to, dmg, STAR_BURST);
            });
        }
        if (SpellUpgrades.perk(p, Spell.STARFALL, "Shooting Star"))
            Scheduler.later(ticks + 6, () -> {
                if (!p.isRemoved() && p.level() == level) launch(p, centre, dmg * 3, 3);
            });
        Bosses.ring(level, centre.add(0, .1, 0), area, new DustParticleOptions(0x9FC8FF, 1f), (int) (area * 8));
        sound(level, p.getEyePosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, .7f);
        return true;
    }

    static void launch(ServerPlayer p, Vec3 to, double dmg, double radius) {
        var random = p.level().getRandom();
        Vec3 from = to.add((random.nextDouble() - .5) * 8, 22, (random.nextDouble() - .5) * 8);
        STARS.add(new Star(p, from, to, dmg, radius));
    }

    static void tickStars() {
        for (Iterator<Star> it = STARS.iterator(); it.hasNext(); ) {
            var s = it.next();
            if (s.owner.isRemoved()) {
                it.remove();
                continue;
            }
            s.t++;
            Vec3 a = s.from.lerp(s.to, (s.t - 1) / (double) STAR_FALL), b = s.from.lerp(s.to, s.t / (double) STAR_FALL);
            SpellKit.line(s.level, a, b, new DustParticleOptions(0xBFD8FF, 1.4f), .5);
            s.level.sendParticles(ParticleTypes.END_ROD, b.x, b.y, b.z, 2, .05, .05, .05, .01);
            if (s.t < STAR_FALL) continue;
            it.remove();
            s.level.sendParticles(ParticleTypes.FIREWORK, s.to.x, s.to.y + .3, s.to.z, 18, .2, .2, .2, .18);
            s.level.sendParticles(
                    ParticleTypes.END_ROD, s.to.x, s.to.y + .3, s.to.z, 10, s.radius / 3, .2, s.radius / 3, .05);
            Bosses.ring(s.level, s.to.add(0, .15, 0), s.radius, new DustParticleOptions(0xDDE9FF, 1f), (int)
                    (s.radius * 10));
            sound(s.level, s.to, SoundEvents.FIREWORK_ROCKET_BLAST, 1.2f, 1.3f);
            boolean dust = SpellUpgrades.perk(s.owner, Spell.STARFALL, "Stardust");
            boolean dazed = SpellUpgrades.perk(s.owner, Spell.STARFALL, "Stardazed");
            Spells.withSpell(
                    Spell.STARFALL,
                    () -> Spells.areaHit(s.owner, s.to.add(0, .5, 0), s.radius, s.dmg, e -> {
                        if (dust) e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1));
                        if (dazed && e instanceof Mob m && m.getTarget() == s.owner) m.setTarget(null);
                    }));
        }
    }

    // ---------------------------------------------------------------- Ward

    static final double DOME_RADIUS = 5;
    static final int DOME_TICKS = 400;
    // owner, 2026-11-22: 20 s, was 6

    static final class Dome {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        final Vec3 centre;
        final double radius;
        int ticks;
        final Set<LivingEntity> smitten = new HashSet<>();
        /** The dome itself, drawn as the Chest of Light's ward is (its own GPU shader). */
        WardAura aura;

        Dome(ServerPlayer owner, Vec3 centre, double radius, int ticks) {
            this.owner = owner;
            this.level = owner.level();
            this.centre = centre;
            this.radius = radius;
            this.ticks = ticks;
        }
    }

    static final List<Dome> DOMES = new ArrayList<>();

    /** A ward of light where you stand: monsters are thrust out and cannot step in, and their shots stop at its edge. */
    static boolean sanctuary(ServerLevel level, ServerPlayer p) {
        var dome = new Dome(
                p,
                p.position(),
                Math.round(SpellUpgrades.scale(Spell.SANCTUARY, AREA, DOME_RADIUS)),
                SpellUpgrades.scaleInt(Spell.SANCTUARY, DURATION, DOME_TICKS));
        DOMES.add(dome);
        StoryGlow.place(level, BlockPos.containing(dome.centre.add(0, .75, 0)), 15, dome.ticks);
        dome.aura = new WardAura(ModEntities.WARD_AURA, level);
        dome.aura.spell(dome.centre, (int) Math.round(dome.radius));
        level.addFreshEntity(dome.aura);
        Bosses.ring(level, dome.centre.add(0, .1, 0), dome.radius, new DustParticleOptions(0xFFE9A8, 1.3f), 60);
        Bosses.ring(
                level,
                dome.centre.add(0, .6, 0),
                dome.radius,
                ParticleTypes.END_ROD,
                40); // at the rim, not in your eyes
        sound(level, dome.centre, SoundEvents.BEACON_ACTIVATE, 1.2f, 1.4f);
        sound(level, dome.centre, SoundEvents.BELL_RESONATE, .8f, 1.6f);
        return true;
    }

    static boolean inDome(Dome d, Vec3 at) {
        return at.distanceTo(d.centre) < d.radius;
    }

    static void tickDomes() {
        for (Iterator<Dome> it = DOMES.iterator(); it.hasNext(); ) {
            var d = it.next();
            if (--d.ticks < 0 || d.owner.isRemoved() || d.owner.level() != d.level) {
                sound(d.level, d.centre, SoundEvents.BEACON_DEACTIVATE, 1f, 1.4f);
                if (d.aura != null) d.aura.discard();
                Bosses.ring(d.level, d.centre.add(0, .3, 0), d.radius, ParticleTypes.END_ROD, 40);
                it.remove();
                continue;
            }
            var box = new AABB(d.centre, d.centre).inflate(d.radius + 2);
            for (var e : d.level.getEntitiesOfClass(LivingEntity.class, box, e -> monster(d.owner, e))) {
                Vec3 c = e.position().add(0, e.getBbHeight() / 2, 0);
                double dist = c.distanceTo(d.centre), edge = d.radius + e.getBbWidth() / 2 + .4;
                if (dist >= edge) continue;
                Vec3 out = e.position().subtract(d.centre).multiply(1, 0, 1);
                out = out.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : out.normalize();
                if (dist < d.radius - .5) {
                    // Already inside (it was there when the dome rose): set at the edge.
                    Vec3 rim = d.centre.add(out.scale(edge));
                    e.teleportTo(rim.x, e.getY(), rim.z);
                }
                e.setDeltaMovement(out.scale(.7).add(0, .15, 0));
                e.needsSync = true;
                if (e instanceof Mob m && m.getNavigation().isInProgress())
                    m.getNavigation().stop();
                if (d.smitten.add(e) && SpellUpgrades.perk(d.owner, Spell.SANCTUARY, "Smiting Edge"))
                    Spells.hit(d.owner, e, Perks.flat(d.owner, 3), Spell.SANCTUARY, d.cast);
            }
            for (var shot : d.level.getEntitiesOfClass(Projectile.class, box)) {
                if (shot.getOwner() instanceof Player) continue;
                if (!inDome(d, shot.position())) continue;
                d.level.sendParticles(ParticleTypes.END_ROD, shot.getX(), shot.getY(), shot.getZ(), 6, .1, .1, .1, .05);
                shot.discard();
            }
            if (d.ticks % 20 == 0 && SpellUpgrades.perk(d.owner, Spell.SANCTUARY, "Healing Ground"))
                for (var friend : d.level.getEntitiesOfClass(
                        LivingEntity.class,
                        box,
                        e -> inDome(d, e.position())
                                && (e instanceof Player
                                        || e instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame())))
                    friend.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 50, 0, true, true));
            if (d.ticks % 20 == 0
                    && (SpellUpgrades.perk(d.owner, Spell.SANCTUARY, "Cleansing Ward")
                            || SpellUpgrades.perk(d.owner, Spell.SANCTUARY, "Swift Ward"))) {
                boolean cleansing = SpellUpgrades.perk(d.owner, Spell.SANCTUARY, "Cleansing Ward");
                for (var pl : d.level.getEntitiesOfClass(Player.class, box, e -> inDome(d, e.position()))) {
                    if (cleansing)
                        for (var fx : new ArrayList<>(pl.getActiveEffects()))
                            if (fx.getEffect().value().getCategory()
                                    == net.minecraft.world.effect.MobEffectCategory.HARMFUL) {
                                pl.removeEffect(fx.getEffect());
                                break;
                            }
                    if (SpellUpgrades.perk(d.owner, Spell.SANCTUARY, "Swift Ward"))
                        pl.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 0, true, false, true));
                }
            }
            if (inDome(d, d.owner.position()) && SpellUpgrades.perk(d.owner, Spell.SANCTUARY, "Mana Spring"))
                Perks.mana(d.owner, Balance.MANA_REGEN_PER_SECOND / 20.0);
        }
    }

    // ---------------------------------------------------------------- Light Bridge

    static final int BRIDGE_LENGTH = 24, BRIDGE_TICKS = 600;

    static final class Bridge {
        final ServerPlayer owner;
        final ServerLevel level;
        final Set<BlockPos> cells;
        int ticks;

        Bridge(ServerPlayer owner, Set<BlockPos> cells, int ticks) {
            this.owner = owner;
            this.level = owner.level();
            this.cells = cells;
            this.ticks = ticks;
        }
    }

    static final List<Bridge> BRIDGES = new ArrayList<>();

    /** The self-test raises a wall and lays a bridge at once instead of over a few ticks. */
    static boolean instant;

    /**
     * A path of light runs from your feet along your gaze, two blocks a tick, up or down a block in every two as you
     * look: over gaps, water, lava and goo, never through a wall. It fades from its start after 30 seconds.
     */
    static boolean lightBridge(ServerLevel level, ServerPlayer p) {
        int length = SpellUpgrades.scaleInt(Spell.LIGHT_BRIDGE, REACH, BRIDGE_LENGTH);
        int ticks = SpellUpgrades.scaleInt(Spell.LIGHT_BRIDGE, DURATION, BRIDGE_TICKS);
        int half = SpellUpgrades.special(Spell.LIGHT_BRIDGE) ? 1 : 0;
        boolean rails = SpellUpgrades.perk(p, Spell.LIGHT_BRIDGE, "Guard Rails");
        if (SpellUpgrades.perk(p, Spell.LIGHT_BRIDGE, "Warm Path"))
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0, false, false, true));
        Vec3 look = p.getLookAngle();
        Vec3 flat = look.multiply(1, 0, 1);
        if (flat.lengthSqr() < 1e-4) return false; // looking straight up or down
        flat = flat.normalize();
        double slope = Math.max(-.5, Math.min(.5, look.y / Math.sqrt(1 - look.y * look.y)));
        Vec3 side = new Vec3(-flat.z, 0, flat.x);
        Vec3 start = new Vec3(p.getX(), p.getBlockY() - .5, p.getZ());
        var plan = new ArrayList<List<BlockPos>>();
        BlockPos last = null;
        for (int i = 1; i <= length; i++) {
            Vec3 q = start.add(flat.scale(i)).add(0, Math.round(slope * i), 0);
            BlockPos c = BlockPos.containing(q);
            if (c.equals(last)) continue;
            last = c;
            // Stop at a wall: the space a walker needs above the path is blocked.
            if (!level.getBlockState(c.above())
                            .getCollisionShape(level, c.above())
                            .isEmpty()
                    || !level.getBlockState(c.above(2))
                            .getCollisionShape(level, c.above(2))
                            .isEmpty()) break;
            var step = new ArrayList<BlockPos>();
            for (int w = -half; w <= half; w++) step.add(BlockPos.containing(q.add(side.scale(w))));
            if (rails) {
                step.add(BlockPos.containing(q.add(side.scale(half + 1))).above());
                step.add(BlockPos.containing(q.add(side.scale(-half - 1))).above());
            }
            plan.add(step);
        }
        boolean any = false;
        for (var step : plan)
            for (var c : step) {
                var s = level.getBlockState(c);
                if (s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty()) any = true;
            }
        if (!any) return false;
        var cells = new HashSet<BlockPos>();
        var bridge = new Bridge(p, cells, ticks + plan.size() / 2 + 2);
        BRIDGES.add(bridge);
        for (int i = 0; i < plan.size(); i++) {
            var step = plan.get(i);
            int at = i / 2;
            Runnable lay = () -> {
                for (var c : step)
                    if (conjure(level, c, ConjuredBlock.LIGHT, ticks)) {
                        cells.add(c);
                        level.sendParticles(
                                ParticleTypes.END_ROD,
                                c.getX() + .5,
                                c.getY() + 1,
                                c.getZ() + .5,
                                2,
                                .25,
                                .1,
                                .25,
                                .01);
                    }
                sound(level, Vec3.atCenterOf(step.getFirst()), SoundEvents.AMETHYST_BLOCK_PLACE, .7f, 1.4f);
            };
            if (at == 0 || instant) lay.run();
            else Scheduler.later(at, lay);
        }
        sound(level, p.position(), SoundEvents.BEACON_POWER_SELECT, 1f, 1.6f);
        return true;
    }

    static void tickBridges() {
        for (Iterator<Bridge> it = BRIDGES.iterator(); it.hasNext(); ) {
            var b = it.next();
            if (--b.ticks < 0 || b.owner.isRemoved()) {
                it.remove();
                continue;
            }
            var p = b.owner;
            if (p.level() != b.level) continue;
            if (SpellUpgrades.perk(p, Spell.LIGHT_BRIDGE, "Swift Bridge")
                    && b.cells.contains(p.blockPosition().below()))
                p.addEffect(new MobEffectInstance(MobEffects.SPEED, 15, 1, true, false, true));
            if (SpellUpgrades.perk(p, Spell.LIGHT_BRIDGE, "Featherfall")
                    && !p.onGround()
                    && p.getDeltaMovement().y < -.5
                    && b.cells.stream().anyMatch(c -> c.distSqr(p.blockPosition()) < 64))
                p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 60, 0, true, false, true));
        }
    }

    // ---------------------------------------------------------------- Light Steed

    static final int STEED_TICKS = 2400, STEED_IDLE = 2;
    // gone the moment you leave it (owner, 2026-11-22)

    static final class Steed {
        final CastContext.Cast cast = CastContext.elemental();
        final SpiritSteed horse;
        int ticks, idle;
        final Map<LivingEntity, Long> trampled = new HashMap<>();

        Steed(SpiritSteed horse, int ticks) {
            this.horse = horse;
            this.ticks = ticks;
        }
    }

    static final Map<ServerPlayer, Steed> STEEDS = new IdentityHashMap<>();

    /**
     * A ghostly horse of light rises under you for 2 minutes: as fast as the swiftest horse, it never tires, takes no
     * fall and no player's blow, and fades the moment you leave it (or when you cast again, which calls a new one).
     */
    static boolean spiritSteed(ServerLevel level, ServerPlayer p) {
        var old = STEEDS.remove(p);
        if (old != null) vanish(old.horse);
        var horse = ModEntities.SPIRIT_STEED.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (horse == null) return false;
        horse.snapTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0);
        horse.setTamed(true);
        horse.setOwner(p);
        horse.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        horse.getAttribute(Attributes.MAX_HEALTH).setBaseValue(30);
        horse.setHealth(30);
        horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.3375);
        boolean windborne = SpellUpgrades.special(Spell.SPIRIT_STEED);
        horse.getAttribute(Attributes.JUMP_STRENGTH).setBaseValue(windborne ? 1.3 : 1.0);
        if (SpellUpgrades.perk(p, Spell.SPIRIT_STEED, "Swift Steed"))
            horse.addEffect(new MobEffectInstance(MobEffects.SPEED, 20 * 60 * 30, 1, false, false, false));
        level.addFreshEntity(horse);
        p.startRiding(horse);
        STEEDS.put(p, new Steed(horse, SpellUpgrades.scaleInt(Spell.SPIRIT_STEED, DURATION, STEED_TICKS)));
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + .8, p.getZ(), 30, .6, .5, .6, .03);
        level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + .8, p.getZ(), 20, .6, .5, .6, .05);
        sound(level, p.position(), SoundEvents.HORSE_AMBIENT, 1f, 1.4f);
        sound(level, p.position(), SoundEvents.BEACON_ACTIVATE, .7f, 1.8f);
        return true;
    }

    static void vanish(SpiritSteed horse) {
        if (horse.level() instanceof ServerLevel level && !horse.isRemoved()) {
            level.sendParticles(ParticleTypes.SOUL, horse.getX(), horse.getY() + 1, horse.getZ(), 24, .5, .6, .5, .04);
            sound(level, horse.position(), SoundEvents.HORSE_DEATH, .5f, 1.6f);
        }
        horse.ejectPassengers();
        horse.discard();
    }

    /** Riding one's own Light Steed: it lights the way like a Light piece. */
    static boolean riding(ServerPlayer p) {
        var horse = steedOf(p);
        return horse != null && p.getVehicle() == horse;
    }

    static SpiritSteed steedOf(ServerPlayer p) {
        var s = STEEDS.get(p);
        return s == null ? null : s.horse;
    }

    static void tickSteeds() {
        for (var it = STEEDS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var p = entry.getKey();
            var s = entry.getValue();
            var horse = s.horse;
            if (horse.isRemoved() || !horse.isAlive()) {
                it.remove();
                continue;
            }
            boolean riding = p.getVehicle() == horse;
            s.idle = riding ? 0 : s.idle + 1;
            if (--s.ticks < 0 || s.idle > STEED_IDLE || p.isRemoved() || !p.isAlive() || p.level() != horse.level()) {
                vanish(horse);
                it.remove();
                continue;
            }
            if (!riding) continue;
            var level = (ServerLevel) horse.level();
            if (s.ticks % 60 == 0 && SpellUpgrades.perk(p, Spell.SPIRIT_STEED, "Healing Ride")) p.heal(2);
            if (SpellUpgrades.perk(p, Spell.SPIRIT_STEED, "Trample")
                    && horse.getDeltaMovement().horizontalDistance() > .2) {
                long now = Scheduler.now();
                for (var e : level.getEntitiesOfClass(
                        LivingEntity.class, horse.getBoundingBox().inflate(.6), e -> monster(p, e))) {
                    if (now - s.trampled.getOrDefault(e, -100L) < 20) continue;
                    s.trampled.put(e, now);
                    Spells.hit(p, e, Perks.flat(p, 6), Spell.SPIRIT_STEED, s.cast);
                    Vec3 away = e.position().subtract(horse.position()).multiply(1, 0, 1);
                    away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                    e.setDeltaMovement(away.scale(.9).add(0, .4, 0));
                    e.needsSync = true;
                }
            }
            if (SpellUpgrades.perk(p, Spell.SPIRIT_STEED, "Ice Hooves")) {
                BlockPos under = horse.blockPosition().below();
                for (var q : BlockPos.betweenClosed(under.offset(-2, 0, -2), under.offset(2, 0, 2))) {
                    if (q.distSqr(under) > 5) continue;
                    var st = level.getBlockState(q);
                    if (st.is(Blocks.WATER)
                            && st.getFluidState().isSource()
                            && level.getBlockState(q.above()).isAir()) {
                        FrostSpells.frost(level, q, 60 + level.getRandom().nextInt(60));
                    }
                }
            }
        }
    }

    /** Steadfast: you take 30% less damage while riding your steed. */
    static float incoming(ServerPlayer p, DamageSource source, float amount) {
        var horse = steedOf(p);
        return horse != null && p.getVehicle() == horse && Perks.has(p, Spell.SPIRIT_STEED, "Steadfast")
                ? amount * .7f
                : amount;
    }

    // ---------------------------------------------------------------- Lightbrand

    static final int BRAND_TICKS = 1200;
    // a minute (owner, 2026-11-22)

    static final class Brand {
        final CastContext.Cast cast = CastContext.elemental();
        int ticks;
        final double dmg;

        Brand(int ticks, double dmg) {
            this.ticks = ticks;
            this.dmg = dmg;
        }
    }

    static final Map<ServerPlayer, Brand> BRANDS = new IdentityHashMap<>();
    private static boolean branding;

    /** Your weapon burns with light: every blow you strike deals more, as light, and sets what it hits alight. */
    static boolean lightbrand(ServerLevel level, ServerPlayer p, double dmg) {
        int brandTicks = SpellUpgrades.scaleInt(Spell.LIGHTBRAND, DURATION, BRAND_TICKS);
        BRANDS.put(p, new Brand(brandTicks, dmg));
        if (SpellUpgrades.perk(p, Spell.LIGHTBRAND, "Brand Guard"))
            p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, brandTicks, 0, false, false, true));
        p.setAttached(ModData.BRANDED, true);
        Vec3 at = hand(p);
        atFeet(p, new DustParticleOptions(0xFFD86B, 1f), ParticleTypes.SMALL_FLAME, 10);
        sound(level, at, SoundEvents.FIRECHARGE_USE, .7f, 1.5f);
        sound(level, at, SoundEvents.BEACON_ACTIVATE, .6f, 1.9f);
        return true;
    }

    static boolean branded(ServerPlayer p) {
        return BRANDS.containsKey(p);
    }

    static void tickBrands() {
        for (var it = BRANDS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            var p = e.getKey();
            if (p.isRemoved() || !p.isAlive() || --e.getValue().ticks < 0) {
                if (!p.isRemoved()) {
                    p.removeAttached(ModData.BRANDED);
                    sound(p.level(), p.position(), SoundEvents.FIRE_EXTINGUISH, .4f, 1.6f);
                }
                it.remove();
            }
        }
    }

    /** A weapon the brand lights: swords, axes, maces and tridents, and the Light Sword. */
    public static boolean weapon(ItemStack s) {
        return s.is(net.minecraft.tags.ItemTags.SWORDS)
                || s.is(net.minecraft.tags.ItemTags.AXES)
                || s.is(Items.MACE)
                || s.is(Items.TRIDENT)
                || LightGear.ITEMS.indexOf(s.getItem()) == 1; // the Light Sword
    }

    /** Whether the brand lights the weapon {@code p} holds now (one Light piece's glow at night). */
    static boolean shining(ServerPlayer p) {
        return BRANDS.containsKey(p) && weapon(p.getMainHandItem());
    }

    /** A blow a branded player struck: the brand's light follows it. */
    static void afterDamage(LivingEntity hurt, DamageSource source, float taken) {
        if (branding || taken <= 0 || !source.is(DamageTypes.PLAYER_ATTACK)) return;
        if (!(source.getEntity() instanceof ServerPlayer p) || source.getDirectEntity() != p) return;
        var brand = BRANDS.get(p);
        if (brand == null || !Spells.canHitDirect(p, hurt) && hurt.isAlive()) return;
        branding = true;
        try {
            if (hurt.isAlive()) {
                Spells.hit(p, hurt, brand.dmg, Spell.LIGHTBRAND, brand.cast);
                hurt.igniteForSeconds(3);
            }
            ((ServerLevel) hurt.level())
                    .sendParticles(
                            ParticleTypes.END_ROD,
                            hurt.getX(),
                            hurt.getY() + hurt.getBbHeight() * .6,
                            hurt.getZ(),
                            6,
                            .25,
                            .3,
                            .25,
                            .06);
            if (SpellUpgrades.perk(p, Spell.LIGHTBRAND, "Cleave"))
                for (var e : Perks.nearestMonsters(p, hurt, hurt.position(), 2.5, 6)) {
                    Spells.hit(p, e, brand.dmg, Spell.LIGHTBRAND, brand.cast);
                    e.igniteForSeconds(3);
                }
            if (SpellUpgrades.perk(p, Spell.LIGHTBRAND, "Radiant Thirst")) p.heal(1);
            if (SpellUpgrades.perk(p, Spell.LIGHTBRAND, "Mana Edge")) Perks.mana(p, 2);
            if (SpellUpgrades.perk(p, Spell.LIGHTBRAND, "Searing Brand")) hurt.igniteForSeconds(3);
        } finally {
            branding = false;
        }
    }

    // ---------------------------------------------------------------- Blade Ring

    static final double RING_RADIUS = 2.5;
    static final int RING_TICKS = 200, RING_BLADES = 3;

    static final class Ring {
        final CastContext.Cast cast = CastContext.elemental();
        int ticks;
        final double dmg, radius;
        final int blades;
        double angle;
        final Map<LivingEntity, Long> cut = new HashMap<>();
        final List<Display.ItemDisplay> swords = new ArrayList<>();

        Ring(int ticks, double dmg, double radius, int blades) {
            this.ticks = ticks;
            this.dmg = dmg;
            this.radius = radius;
            this.blades = blades;
        }
    }

    static final Map<ServerPlayer, Ring> RINGS = new IdentityHashMap<>();

    /** Blades of light circle you, cutting every monster that comes near. */
    static boolean bladeRing(ServerLevel level, ServerPlayer p, double dmg) {
        var ring = new Ring(
                SpellUpgrades.scaleInt(Spell.BLADE_RING, DURATION, RING_TICKS),
                dmg,
                SpellUpgrades.scale(Spell.BLADE_RING, AREA, RING_RADIUS),
                SpellUpgrades.add(Spell.BLADE_RING, COUNT, RING_BLADES));
        var old = RINGS.put(p, ring);
        if (old != null) sheathe(old);
        var blade = new ItemStack(Items.IRON_SWORD);
        blade.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        for (int k = 0; k < ring.blades; k++) {
            var sword = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (sword == null) continue;
            sword.setItemStack(blade.copy());
            sword.setItemTransform(ItemDisplayContext.NONE);
            lay(sword, Math.PI * 2 * k / ring.blades, ring.radius);
            ring.swords.add(SpellKit.track(level, sword, p.position()));
        }
        atFeet(p, new DustParticleOptions(0xE8EEFF, 1f), ParticleTypes.END_ROD, 12);
        sound(level, p.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1f, 1.2f);
        sound(level, p.position(), SoundEvents.AMETHYST_BLOCK_RESONATE, .8f, 1.6f);
        return true;
    }

    static final float RING_HEIGHT = 1.0f, SWORD_SIZE = 1.3f;

    /**
     * A sword lying flat at angle {@code a} round the caster, its tip pointing out: the item's diagonal blade turned
     * along x, laid flat, then swung round; set from the caster's feet so the display can follow them.
     */
    static void lay(Display.ItemDisplay sword, double a, double radius) {
        var rot = new Quaternionf()
                .rotateY((float) -a)
                .rotateX((float) (-Math.PI / 2))
                .rotateZ((float) (-Math.PI / 4));
        double mid = radius * .62;
        sword.setTransformation(new Transformation(
                new Vector3f((float) (Math.cos(a) * mid), RING_HEIGHT, (float) (Math.sin(a) * mid)),
                rot,
                new Vector3f(SWORD_SIZE),
                new Quaternionf()));
        sword.setTransformationInterpolationDelay(0);
        sword.setTransformationInterpolationDuration(1);
    }

    static void sheathe(Ring r) {
        r.swords.forEach(SpellKit::drop);
        r.swords.clear();
    }

    static void tickRings() {
        long now = Scheduler.now();
        for (var it = RINGS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var p = entry.getKey();
            var r = entry.getValue();
            var level = p.level();
            if (p.isRemoved()
                    || !p.isAlive()
                    || --r.ticks < 0
                    || r.swords.stream().anyMatch(s -> s.level() != level)) {
                sheathe(r);
                if (!p.isRemoved() && p.isAlive() && SpellUpgrades.perk(p, Spell.BLADE_RING, "Bladestorm")) {
                    Vec3 from = p.position().add(0, 1, 0);
                    for (var e : Perks.nearestMonsters(p, null, from, 12, r.blades)) {
                        SpellKit.line(level, from, chest(e), new DustParticleOptions(0xE8EEFF, 1f), .3);
                        Spells.hit(p, e, r.dmg * 2, Spell.BLADE_RING, r.cast);
                    }
                    sound(level, from, SoundEvents.PLAYER_ATTACK_SWEEP, 1f, .8f);
                }
                it.remove();
                continue;
            }
            r.angle += .3;
            double y = p.getY() + RING_HEIGHT;
            for (int k = 0; k < r.swords.size(); k++) {
                double a = r.angle + Math.PI * 2 * k / r.swords.size();
                var sword = r.swords.get(k);
                sword.setPos(p.getX(), p.getY(), p.getZ());
                lay(sword, a, r.radius);
                if (r.ticks % 2 == 0)
                    level.sendParticles(
                            ParticleTypes.END_ROD,
                            p.getX() + Math.cos(a) * r.radius,
                            y,
                            p.getZ() + Math.sin(a) * r.radius,
                            1,
                            0,
                            0,
                            0,
                            0);
            }
            int every = Math.max(4, 30 / r.blades);
            boolean throwBack = SpellUpgrades.perk(p, Spell.BLADE_RING, "Whirling Guard");
            for (var e : level.getEntitiesOfClass(
                    LivingEntity.class,
                    p.getBoundingBox().inflate(r.radius + .6, 1.5, r.radius + .6),
                    e -> monster(p, e))) {
                double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
                if (dx * dx + dz * dz > (r.radius + .6) * (r.radius + .6)) continue;
                if (now - r.cut.getOrDefault(e, -1000L) < every) continue;
                r.cut.put(e, now);
                Spells.hit(p, e, r.dmg, Spell.BLADE_RING, r.cast);
                if (SpellUpgrades.perk(p, Spell.BLADE_RING, "Bleeding Blades")) FormSpells.bleed(p, e, 60);
                level.sendParticles(
                        ParticleTypes.SWEEP_ATTACK, e.getX(), e.getY() + e.getBbHeight() * .5, e.getZ(), 1, 0, 0, 0, 0);
                if (throwBack) {
                    Vec3 away = new Vec3(dx, 0, dz);
                    away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                    e.setDeltaMovement(away.scale(.8).add(0, .3, 0));
                    e.needsSync = true;
                }
            }
            if (r.ticks % 6 == 0) sound(level, p.position(), SoundEvents.PLAYER_ATTACK_SWEEP, .25f, 1.8f);
            if (SpellUpgrades.perk(p, Spell.BLADE_RING, "Parry"))
                for (var shot : level.getEntitiesOfClass(
                        Projectile.class,
                        p.getBoundingBox().inflate(r.radius + 1),
                        s -> !(s.getOwner() instanceof Player) && s.isAlive())) {
                    level.sendParticles(ParticleTypes.CRIT, shot.getX(), shot.getY(), shot.getZ(), 6, .1, .1, .1, .2);
                    sound(level, shot.position(), SoundEvents.SHIELD_BLOCK.value(), .6f, 1.5f);
                    shot.discard();
                }
        }
    }

    // ---------------------------------------------------------------- Dawnbreak

    static final double DAWN_RADIUS = 16;
    static final int DAWN_GATHER = 30, SUN_TICKS = 120;

    static final class Dawn {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        final double dmg;
        int ticks = DAWN_GATHER;

        Dawn(ServerPlayer owner, double dmg) {
            this.owner = owner;
            this.level = owner.level();
            this.dmg = dmg;
        }
    }

    static final class Sun {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        final Vec3 pos;
        final double dmg;
        int ticks = SUN_TICKS;

        Sun(ServerPlayer owner, Vec3 pos, double dmg) {
            this.owner = owner;
            this.level = owner.level();
            this.pos = pos;
            this.dmg = dmg;
        }
    }

    static final List<Dawn> DAWNS = new ArrayList<>();
    static final List<Sun> SUNS = new ArrayList<>();

    /** You gather the light for a moment, then a sun bursts from you. */
    static boolean dawnbreak(ServerLevel level, ServerPlayer p, double dmg) {
        DAWNS.add(new Dawn(p, dmg));
        StoryGlow.place(level, p.blockPosition().above(), 15, DAWN_GATHER + 40);
        sound(level, p.position(), SoundEvents.BEACON_ACTIVATE, 1f, 1.4f);
        sound(level, p.position(), SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, .6f);
        return true;
    }

    static void tickDawns() {
        for (Iterator<Dawn> it = DAWNS.iterator(); it.hasNext(); ) {
            var d = it.next();
            var p = d.owner;
            if (p.isRemoved() || !p.isAlive() || p.level() != d.level) {
                it.remove();
                continue;
            }
            if (--d.ticks > 0) {
                // Light streams in toward the caster's feet from all round, never at the eyes.
                var random = d.level.getRandom();
                Vec3 heart = p.position().add(0, .2, 0);
                for (int k = 0; k < 6; k++) {
                    double a = random.nextDouble() * Math.PI * 2, r = 4 + random.nextDouble() * 2.5;
                    Vec3 from = heart.add(Math.cos(a) * r, random.nextDouble() * .5 - .2, Math.sin(a) * r);
                    Vec3 v = heart.subtract(from).scale(.07);
                    d.level.sendParticles(ParticleTypes.END_ROD, from.x, from.y, from.z, 0, v.x, v.y, v.z, 1);
                }
                Spells.ring(
                        d.level,
                        p.position().add(0, .1, 0),
                        .4 + (DAWN_GATHER - d.ticks) * .03,
                        new DustParticleOptions(0xFFE27A, 1.2f));
                if (d.ticks % 8 == 0)
                    sound(
                            d.level,
                            p.position(),
                            SoundEvents.BEACON_POWER_SELECT,
                            .6f,
                            1f + (DAWN_GATHER - d.ticks) * .03f);
                continue;
            }
            it.remove();
            breakDawn(d);
        }
        for (Iterator<Sun> it = SUNS.iterator(); it.hasNext(); ) {
            var s = it.next();
            if (s.owner.isRemoved() || s.owner.level() != s.level || --s.ticks < 0) {
                it.remove();
                continue;
            }
            var random = s.level.getRandom();
            for (int k = 0; k < 8; k++) {
                double a = random.nextDouble() * Math.PI * 2, b = random.nextDouble() * Math.PI - Math.PI / 2;
                s.level.sendParticles(
                        new DustParticleOptions(k % 2 == 0 ? 0xFFF4C0 : 0xFFC94A, 1.5f),
                        s.pos.x + Math.cos(a) * Math.cos(b) * .7,
                        s.pos.y + Math.sin(b) * .7,
                        s.pos.z + Math.sin(a) * Math.cos(b) * .7,
                        1,
                        0,
                        0,
                        0,
                        0);
            }
            s.level.sendParticles(ParticleTypes.END_ROD, s.pos.x, s.pos.y, s.pos.z, 1, .2, .2, .2, .01);
            if (s.ticks % 20 != 0) continue;
            for (var e : Perks.nearestMonsters(s.owner, null, s.pos.add(0, -3, 0), 8, 64)) {
                SpellKit.line(s.level, s.pos, chest(e), new DustParticleOptions(0xFFE27A, .9f), .35);
                Spells.hit(s.owner, e, s.dmg, Spell.DAWNBREAK, s.cast);
                e.igniteForSeconds(3);
            }
            sound(s.level, s.pos, SoundEvents.FIRECHARGE_USE, .5f, 1.6f);
        }
    }

    private static void breakDawn(Dawn d) {
        var p = d.owner;
        var level = d.level;
        Vec3 at = p.position();
        double radius = SpellUpgrades.scale(Spell.DAWNBREAK, AREA, DAWN_RADIUS);
        boolean blind = SpellUpgrades.perk(p, Spell.DAWNBREAK, "Blinding Dawn");
        for (var e :
                level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(radius), e -> monster(p, e))) {
            if (e.distanceToSqr(p) > radius * radius) continue;
            boolean undead = e.getType().builtInRegistryHolder().is(EntityTypeTags.UNDEAD);
            Spells.hit(p, e, undead ? d.dmg * 1.5 : d.dmg, Spell.DAWNBREAK, d.cast);
            if (undead) e.igniteForSeconds(5);
            double near = 1 - Math.min(1, e.distanceTo(p) / radius);
            throwFrom(e, at, .6 + near * 1.2, .35 + near * .3);
            if (blind && e.isAlive()) {
                e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0), p);
                e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 1), p);
                if (e instanceof Mob m && m.getTarget() == p) m.setTarget(null);
            }
            level.sendParticles(ParticleTypes.END_ROD, e.getX(), chest(e).y, e.getZ(), 4, .2, .3, .2, .05);
        }
        if (SpellUpgrades.perk(p, Spell.DAWNBREAK, "Morning Warmth"))
            for (var friend : level.getEntitiesOfClass(
                    ServerPlayer.class, p.getBoundingBox().inflate(radius), Player::isAlive)) friend.heal(6);
        if (SpellUpgrades.perk(p, Spell.DAWNBREAK, "Lingering Sun"))
            CastContext.resume(d.cast, () -> SUNS.add(new Sun(p, at.add(0, 4, 0), d.dmg / 3)));
        if (SpellUpgrades.perk(p, Spell.DAWNBREAK, "Sun Ward"))
            p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 1, false, false, true));
        if (SpellUpgrades.perk(p, Spell.DAWNBREAK, "Purging Light")) {
            for (var effect : new ArrayList<>(p.getActiveEffects()))
                if (effect.getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL)
                    p.removeEffect(effect.getEffect());
            p.clearFire();
        }
        // The sunrise: rings rush out along the ground and a little above it; the flash itself only others see.
        for (int step = 0; step < 8; step++) {
            double r = 1.5 + (radius - 1.5) * step / 7.0;
            int s = step;
            Scheduler.later(step, () -> {
                if (p.isRemoved() || p.level() != level) return;
                Spells.ring(level, at.add(0, .15, 0), r, new DustParticleOptions(0xFFE27A, 1.6f));
                Spells.ring(level, at.add(0, .3, 0), r, ParticleTypes.END_ROD);
                if (s % 2 == 0) Spells.ring(level, at.add(0, 1.2, 0), r * .9, new DustParticleOptions(0xFFF6D0, 1.2f));
            });
        }
        for (int k = 0; k < 48; k++) {
            double a = k * Math.PI * 2 / 48;
            level.sendParticles(
                    ParticleTypes.END_ROD, at.x, at.y + .3, at.z, 0, Math.cos(a) * .7, .02, Math.sin(a) * .7, 1);
        }
        forOthers(
                p,
                net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF4C0),
                at.add(0, 1, 0),
                1,
                0,
                0);
        sound(level, at, SoundEvents.LIGHTNING_BOLT_THUNDER, .7f, 1.8f);
        sound(level, at, SoundEvents.BEACON_POWER_SELECT, 1f, 2f);
        sound(level, at, SoundEvents.TOTEM_USE, .5f, 1.6f);
    }

    // ---------------------------------------------------------------- Smite

    static final double BANISH_REACH = 32;

    /** A pillar of light strikes the one monster looked at for a huge blow. Only that monster. */
    static boolean smite(ServerLevel level, ServerPlayer p, double dmg) {
        LivingEntity target = Spells.lookTarget(p, SpellUpgrades.scale(Spell.BANISH, REACH, BANISH_REACH));
        if (target == null || !monster(p, target)) {
            say(p, "Look at a monster to smite it");
            return false;
        }
        if (SpellUpgrades.perk(p, Spell.BANISH, "Execution") && target.getHealth() < target.getMaxHealth() / 2)
            dmg *= 2;
        pillarOfLight(level, target.position(), target.getBbHeight());
        Spells.hit(p, target, dmg, Spell.BANISH);
        if (SpellUpgrades.perk(p, Spell.BANISH, "Searing Light") && target.isAlive()) {
            target.igniteForSeconds(6);
            target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false, false));
        }
        if (SpellUpgrades.perk(p, Spell.BANISH, "Soul Price") && !target.isAlive()) Perks.mana(p, 20);
        if (SpellUpgrades.perk(p, Spell.BANISH, "Stunning Light") && target.isAlive()) Perks.root(target, p, 40);
        if (SpellUpgrades.perk(p, Spell.BANISH, "Holy Ground"))
            for (var e : Perks.nearestMonsters(p, target, target.position(), 3, 32)) {
                if (mighty(e)) continue;
                Vec3 away = e.position().subtract(target.position()).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                double keep = 1 - SpellKit.knockResist(e);
                e.setDeltaMovement(away.scale(1.1 * keep).add(0, .4 * keep, 0));
                e.needsSync = true;
            }
        sound(level, target.position(), SoundEvents.TRIDENT_THUNDER.value(), 1f, 1.6f);
        sound(level, target.position(), SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, .7f);
        return true;
    }

    private static void pillarOfLight(ServerLevel level, Vec3 at, double height) {
        for (double y = 0; y < 14; y += .35) {
            double w = .25 + Math.max(0, 1.4 - y * .1);
            level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + y, at.z, 1, w * .2, .05, w * .2, 0);
            if ((int) (y / .35) % 2 == 0)
                level.sendParticles(new DustParticleOptions(0xFFF1B0, 1.6f), at.x, at.y + y, at.z, 2, .18, .05, .18, 0);
        }
        Spells.ring(level, at.add(0, .1, 0), 1.2, new DustParticleOptions(0xFFE27A, 1.4f));
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + height / 2, at.z, 40, .3, height / 3, .3, .25);
    }

    // ---------------------------------------------------------------- Splinter

    static final int SPLINTERS = 5;
    static final double SPLINTER_REACH = 12, SPLINTER_SPEED = 2;

    /** A fan of five splinters of light. */
    static boolean splinter(ServerLevel level, ServerPlayer p, double dmg) {
        int n = SPLINTERS + SpellUpgrades.add(Spell.SPLINTER, COUNT, 0);
        int life = (int) Math.ceil(SpellUpgrades.scale(Spell.SPLINTER, REACH, SPLINTER_REACH) / SPLINTER_SPEED);
        Vec3 look = p.getLookAngle(), eye = p.getEyePosition();
        Vec3 side = look.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
        int pierce = perk(p, Spell.SPLINTER, "Piercing Splinters") ? 1 : 0;
        for (int i = 0; i < n; i++) {
            double off = i - (n - 1) / 2.0;
            // About eight degrees between splinters.
            Vec3 dir = look.add(side.scale(off * .14)).normalize();
            Spells.shoot(p, Spell.SPLINTER, eye.add(look.scale(.6)), dir.scale(SPLINTER_SPEED), dmg, 0, life).pierce =
                    pierce;
        }
        sound(level, p.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.8f);
        sound(level, p.position(), SoundEvents.ARROW_SHOOT, .8f, 1.4f);
        return true;
    }

    /** Splinter's perks on each struck creature: Bleeding Splinters and Mana Splinters. */
    static void splinterHit(ServerPlayer p, LivingEntity e) {
        if (perk(p, Spell.SPLINTER, "Bleeding Splinters")) FormSpells.bleed(p, e, 60);
        if (perk(p, Spell.SPLINTER, "Mana Splinters")) Perks.mana(p, 1);
        if (perk(p, Spell.SPLINTER, "Chilling Splinters") && e.isAlive()) {
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, true, true));
            Frostbite.add(e, p, 34);
        }
    }
}
