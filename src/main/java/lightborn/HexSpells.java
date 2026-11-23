package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.chest;
import static lightborn.SpellKit.line;
import static lightborn.SpellKit.sound;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.AREA;
import static lightborn.SpellUpgrades.Stat.COUNT;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.SpellUpgrades.Stat.REACH;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Hexes: curses, fear and binding laid on monsters. Siphon, Contagion, Soul Link, Doom, Weaken and Fright
 * (Eclipse, Cage and Taunt live in their own classes and are dispatched from here).
 */
public final class HexSpells {
    private HexSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case SIPHON -> siphon(level, p, dmg, SpellUpgrades.add(spell, COUNT, 3));
            case CONTAGION -> contagion(level, p, dmg, SpellUpgrades.scaleInt(spell, REACH, SPREAD));
            case SOUL_LINK -> soulLink(level, p, dmg);
            case DOOM -> doom(level, p);
            case ENFEEBLE -> enfeeble(level, p);
            case FRIGHT -> fright(level, p);
            case ECLIPSE -> {
                BossTrophies.eclipse(level, p);
                yield true;
            }
            case CAGE -> Cage.cast(p);
            case TAUNT -> Taunt.cast(p);
            default -> throw new IllegalArgumentException(spell + " is not a hex");
        };
    }

    static void tick(MinecraftServer server) {
        tickTethers();
        tickInfections();
        tickLinks();
        tickEnfeebled();
        tickFright();
        tickDoom();
    }

    static void clear() {
        TETHERS.clear();
        INFECTED.clear();
        LINKS.clear();
        ENFEEBLED.clear();
        AFRAID.clear();
        DOOMED.values().forEach(c -> SpellKit.drop(c.skull));
        DOOMED.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "tethers" -> TETHERS.size();
            case "infected" -> INFECTED.size();
            case "links" -> LINKS.size();
            case "enfeebled" -> ENFEEBLED.size();
            case "afraid" -> AFRAID.size();
            case "doomed" -> DOOMED.size();
            default -> -1;
        };
    }

    /** Soul Link: harm you do to one of a bound pack is shared with the rest. */
    static void soulLinkStruck(LivingEntity entity, ServerPlayer by, float taken) {
        if (sharing) return;
        for (var l : List.copyOf(LINKS)) {
            if (l.owner != by || !l.bound.contains(entity)) continue;
            sharing = true;
            try {
                for (var e : List.copyOf(l.bound))
                    if (e != entity && e.isAlive()) {
                        SpellKit.line(l.level, chest(entity), chest(e), new DustParticleOptions(0xB9FFF4, 1f), .35);
                        Spells.hit(by, e, taken * l.share, Spell.SOUL_LINK);
                    }
            } finally {
                sharing = false;
            }
        }
    }

    /**
     * Before armour: an enfeebled monster deals half (none while disarmed, and Sapping gives you mana back); a
     * Brittle one takes more; a frightened monster with Cowering deals half.
     */
    static float weakenIncoming(LivingEntity target, DamageSource source, float amount) {
        var weak = ENFEEBLED.get(target);
        if (weak != null && weak.brittle() && target.level().getGameTime() < weak.until()) amount *= 1.15f;
        if (source.getEntity() instanceof LivingEntity attacker && attacker != target) {
            var f = ENFEEBLED.get(attacker);
            long now = attacker.level().getGameTime();
            if (f != null && now < f.until()) {
                if (now < f.disarmedUntil()) return 0;
                amount *= .5f;
                if (f.sapping() && target == f.owner()) Perks.mana(f.owner(), 5);
            }
            if (attacker instanceof Mob m
                    && AFRAID.containsKey(m)
                    && AFRAID.get(m).cowering()
                    && afraid(m)) amount *= .5f;
        }
        return amount;
    }

    /** Before armour: a doomed creature takes 35% more from everything, and deals 30% less under Weakness. */
    static float doomIncoming(LivingEntity target, DamageSource source, float amount) {
        var doomed = DOOMED.get(target);
        if (doomed != null) amount *= (float) DOOM_HARM;
        if (source.getEntity() instanceof LivingEntity attacker && attacker != target) {
            var c = DOOMED.get(attacker);
            if (c != null && c.curses.contains(Curse.WEAKNESS)) amount *= .7f;
        }
        return amount;
    }

    // ---------------------------------------------------------------- Siphon

    static final double SIPHON_RANGE = 12, SIPHON_BREAK = 18;
    static final int SIPHON_TICKS = 80;
    /** Share of the life drained that flows back to the caster. */
    static final double SIPHON_HEAL = 0.5;

    private static final class Tether {
        final ServerPlayer owner;
        final ServerLevel level;
        final LivingEntity target;
        final double dmg;
        int age;

        Tether(ServerPlayer owner, ServerLevel level, LivingEntity target, double dmg) {
            this.owner = owner;
            this.level = level;
            this.target = target;
            this.dmg = dmg;
        }
    }

    static final List<Tether> TETHERS = new ArrayList<>();

    /**
     * Blood-red threads latch onto up to three creatures in sight within 12 blocks (upgraded five), the one you look
     * at first. For 4 s each pulse (every half second) drains them, and half of what it takes heals you. A thread
     * snaps beyond 18 blocks or out of sight.
     */
    static boolean siphon(ServerLevel level, ServerPlayer p, double dmg, int count) {
        List<LivingEntity> picks = new ArrayList<>();
        LivingEntity looked = Spells.lookTarget(p, SIPHON_RANGE);
        if (looked != null) picks.add(looked);
        List<LivingEntity> near = level.getEntitiesOfClass(
                LivingEntity.class,
                p.getBoundingBox().inflate(SIPHON_RANGE),
                e -> e != looked
                        && Spells.canHitDirect(p, e)
                        && e.distanceTo(p) <= SIPHON_RANGE
                        && p.hasLineOfSight(e));
        near.sort(Comparator.comparingDouble(e -> (e instanceof Enemy ? 0 : 1000) + e.distanceToSqr(p)));
        for (LivingEntity e : near) {
            if (picks.size() >= count) break;
            picks.add(e);
        }
        if (picks.isEmpty()) {
            p.sendSystemMessage(
                    Component.literal("Nothing to siphon within 12 blocks").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        for (LivingEntity e : picks) TETHERS.add(new Tether(p, level, e, dmg));
        sound(level, p.position(), SoundEvents.SOUL_ESCAPE.value(), 2.0f, 0.6f);
        sound(level, p.position(), SoundEvents.EVOKER_CAST_SPELL, 0.8f, 0.5f);
        return true;
    }

    static void tickTethers() {
        for (Iterator<Tether> it = TETHERS.iterator(); it.hasNext(); ) {
            Tether t = it.next();
            t.age++;
            ServerPlayer p = t.owner;
            LivingEntity e = t.target;
            if (!p.isAlive()
                    || p.level() != t.level
                    || !e.isAlive()
                    || e.isRemoved()
                    || e.level() != t.level
                    || e.distanceTo(p) > SIPHON_BREAK
                    || !p.hasLineOfSight(e)
                    || t.age > SIPHON_TICKS) {
                it.remove();
                continue;
            }
            ServerLevel level = t.level;
            Vec3 from = chest(e), to = chest(p);
            if (t.age % 2 == 0) {
                // Beads run from the victim toward the caster: the thread visibly flows to you.
                double len = from.distanceTo(to);
                int n = Math.max(2, (int) (len * 2));
                double phase = (t.age % 8) / 8.0;
                for (int i = 0; i < n; i++) {
                    double k = (i + phase) / n;
                    Vec3 q = from.lerp(to, k);
                    if (q.distanceToSqr(p.getEyePosition()) < 2.0) continue;
                    level.sendParticles(
                            new DustParticleOptions(i % 3 == 0 ? 0xE0303A : 0x8A0F1F, 0.9f),
                            q.x,
                            q.y,
                            q.z,
                            1,
                            0,
                            0,
                            0,
                            0);
                }
            }
            if (t.age % 10 == 0) {
                float before = e.getHealth();
                Spells.hit(p, e, t.dmg, Spell.SIPHON);
                float drained = Math.max(0, before - Math.max(0, e.getHealth()));
                if (drained > 0) {
                    float heal = (float) (drained * SIPHON_HEAL), room = p.getMaxHealth() - p.getHealth();
                    p.heal(heal);
                    // Blood Shield: what the heal could not use becomes a shield, up to 8.
                    if (heal > room && SpellUpgrades.perk(p, Spell.SIPHON, "Blood Shield")) {
                        float shield = Math.min(8, p.getAbsorptionAmount() + heal - Math.max(0, room));
                        p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 1, false, false, true));
                        p.setAbsorptionAmount(shield);
                    }
                }
                level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, from.x, from.y, from.z, 2, 0.2, 0.2, 0.2, 0.05);
                sound(level, from, SoundEvents.SOUL_ESCAPE.value(), 0.6f, 1.4f);
            }
        }
    }

    // ---------------------------------------------------------------- Contagion

    static final double CONTAGION_RANGE = 24, CONTAGION_SPLASH = 2.5;
    static final int SPREAD = 5;
    static final int PLAGUE_TICKS = 160;

    private static final class Infection {
        final ServerPlayer owner;
        final double dmg, spread;
        int ticks = PLAGUE_TICKS;

        Infection(ServerPlayer owner, double dmg, double spread) {
            this.owner = owner;
            this.dmg = dmg;
            this.spread = spread;
        }
    }

    static final Map<LivingEntity, Infection> INFECTED = new IdentityHashMap<>();

    /**
     * A sickly green plague on the creature you look at (24 blocks) and every monster within 2.5 blocks of it: it
     * hurts each second for 8 s. When a sick creature dies, the plague leaps to every monster within 5 blocks
     * (upgraded 8) with a fresh 8 s, so a pack can rot away in a chain. It only ever spreads to monsters.
     */
    static boolean contagion(ServerLevel level, ServerPlayer p, double dmg, double spread) {
        LivingEntity target = Spells.lookTarget(p, CONTAGION_RANGE);
        if (target == null) {
            p.sendSystemMessage(
                    Component.literal("Look at a creature within 24 blocks").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        infect(target, p, dmg, spread);
        for (LivingEntity e : level.getEntitiesOfClass(
                LivingEntity.class,
                target.getBoundingBox().inflate(CONTAGION_SPLASH),
                e -> e != target && e instanceof Enemy && Spells.canHitArea(p, e))) {
            infect(e, p, dmg, spread);
        }
        Vec3 at = chest(target);
        line(level, chest(p).add(p.getLookAngle().scale(1.5)), at, new DustParticleOptions(0x6FBF2A, 0.8f), 0.5);
        level.sendParticles(ParticleTypes.SNEEZE, at.x, at.y, at.z, 30, 0.6, 0.6, 0.6, 0.05);
        sound(level, at, SoundEvents.PUFFER_FISH_BLOW_UP, 1.2f, 0.6f);
        sound(level, p.position(), SoundEvents.WITCH_THROW, 1.0f, 0.7f);
        return true;
    }

    private static void infect(LivingEntity e, ServerPlayer owner, double dmg, double spread) {
        Infection old = INFECTED.get(e);
        if (old != null) {
            old.ticks = PLAGUE_TICKS;
            return;
        }
        INFECTED.put(e, new Infection(owner, dmg, spread));
        rot(e, owner);
    }

    /** Rot: a sick creature is weakened while the plague lasts. */
    private static void rot(LivingEntity e, ServerPlayer owner) {
        if (SpellUpgrades.perk(owner, Spell.CONTAGION, "Rot"))
            e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, PLAGUE_TICKS, 1), owner);
    }

    static boolean infected(LivingEntity e) {
        return INFECTED.containsKey(e);
    }

    static void tickInfections() {
        for (Iterator<Map.Entry<LivingEntity, Infection>> it =
                        INFECTED.entrySet().iterator();
                it.hasNext(); ) {
            var en = it.next();
            LivingEntity e = en.getKey();
            Infection inf = en.getValue();
            if (!e.isAlive()
                    || e.isRemoved()
                    || inf.owner.isRemoved()
                    || --inf.ticks < 0
                    || !(e.level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }
            if (inf.ticks % 4 == 0) {
                level.sendParticles(
                        new DustParticleOptions(0x7ACC33, 1.0f),
                        e.getX(),
                        e.getY() + e.getBbHeight() * 0.5,
                        e.getZ(),
                        3,
                        e.getBbWidth() * 0.4,
                        e.getBbHeight() * 0.3,
                        e.getBbWidth() * 0.4,
                        0);
                level.sendParticles(
                        ParticleTypes.SNEEZE,
                        e.getX(),
                        e.getY() + e.getBbHeight() * 0.8,
                        e.getZ(),
                        1,
                        0.2,
                        0.1,
                        0.2,
                        0.01);
            }
            if (inf.ticks % 20 == 0) Spells.hit(inf.owner, e, inf.dmg, Spell.CONTAGION);
        }
    }

    /** A sick creature died: the plague jumps to every monster within reach. */
    static void onDeath(LivingEntity dead) {
        Infection inf = INFECTED.remove(dead);
        if (inf == null || !inf.owner.isAlive() || !(dead.level() instanceof ServerLevel level)) return;
        Vec3 at = chest(dead);
        int caught = 0;
        for (LivingEntity e : level.getEntitiesOfClass(
                LivingEntity.class,
                dead.getBoundingBox().inflate(inf.spread),
                e -> e != dead && e instanceof Enemy && Spells.canHitArea(inf.owner, e) && !INFECTED.containsKey(e))) {
            if (e.distanceTo(dead) > inf.spread) continue;
            INFECTED.put(e, new Infection(inf.owner, inf.dmg, inf.spread));
            rot(e, inf.owner);
            line(level, at, chest(e), new DustParticleOptions(0x6FBF2A, 0.7f), 0.5);
            caught++;
        }
        // Pestilence Cloud: for 5 seconds, a sickly cloud where it fell catches every monster that walks in.
        if (SpellUpgrades.perk(inf.owner, Spell.CONTAGION, "Pestilence Cloud")) {
            Vec3 spot = dead.position();
            for (int t = 0; t <= 100; t += 10)
                Scheduler.later(t, () -> {
                    if (!inf.owner.isAlive() || inf.owner.level() != level) return;
                    level.sendParticles(
                            new DustParticleOptions(0x7ACC33, 1.6f), spot.x, spot.y + .6, spot.z, 25, 1.2, .4, 1.2, 0);
                    level.sendParticles(ParticleTypes.SNEEZE, spot.x, spot.y + .5, spot.z, 6, 1, .3, 1, .01);
                    for (LivingEntity e : level.getEntitiesOfClass(
                            LivingEntity.class,
                            new net.minecraft.world.phys.AABB(spot, spot).inflate(2.5, 1.5, 2.5),
                            e -> e instanceof Enemy && Spells.canHitArea(inf.owner, e) && !INFECTED.containsKey(e))) {
                        INFECTED.put(e, new Infection(inf.owner, inf.dmg, inf.spread));
                        rot(e, inf.owner);
                    }
                });
        }
        level.sendParticles(ParticleTypes.SNEEZE, at.x, at.y, at.z, 40, 0.5, 0.5, 0.5, 0.1);
        Bosses.ring(level, dead.position().add(0, 0.2, 0), inf.spread, new DustParticleOptions(0x5AA826, 1.2f), (int)
                (inf.spread * 10));
        sound(level, at, SoundEvents.SLIME_SQUISH, 1.2f, 0.5f);
        if (caught > 0) sound(level, at, SoundEvents.PUFFER_FISH_BLOW_UP, 1.0f, 0.8f);
    }

    // ---------------------------------------------------------------- Soul Link

    static final double LINK_REACH = 24, LINK_GATHER = 8, LINK_SHARE = .6;
    static final int LINK_MORE = 5, LINK_TICKS = 200;

    static final class Link {
        final ServerPlayer owner;
        final ServerLevel level;
        final Set<LivingEntity> bound = new LinkedHashSet<>();
        final double share;
        final boolean fall, drag;
        int ticks, age;

        Link(ServerPlayer owner, double share, int ticks, boolean fall, boolean drag) {
            this.owner = owner;
            this.level = owner.level();
            this.share = share;
            this.ticks = ticks;
            this.fall = fall;
            this.drag = drag;
        }
    }

    static final List<Link> LINKS = new ArrayList<>();
    private static boolean sharing;

    /** Chains of light bind a pack together: harm you do to one is shared with the rest. */
    static boolean soulLink(ServerLevel level, ServerPlayer p, double dmg) {
        LivingEntity first = Spells.lookTarget(p, SpellUpgrades.scale(Spell.SOUL_LINK, REACH, LINK_REACH));
        if (first == null || !monster(p, first)) {
            say(p, "Look at a monster to bind its pack");
            return false;
        }
        var link = new Link(
                p,
                SpellUpgrades.perk(p, Spell.SOUL_LINK, "Full Share") ? 1 : LINK_SHARE,
                SpellUpgrades.scaleInt(Spell.SOUL_LINK, DURATION, LINK_TICKS),
                SpellUpgrades.perk(p, Spell.SOUL_LINK, "Chained Fall"),
                SpellUpgrades.perk(p, Spell.SOUL_LINK, "Dragging Chains"));
        link.bound.add(first);
        link.bound.addAll(Perks.nearestMonsters(
                p,
                first,
                first.position(),
                SpellUpgrades.scale(Spell.SOUL_LINK, AREA, LINK_GATHER),
                SpellUpgrades.add(Spell.SOUL_LINK, COUNT, LINK_MORE)));
        // A monster bound by an older chain of yours moves to this one.
        for (var old : LINKS) if (old.owner == p) old.bound.removeAll(link.bound);
        LINKS.add(link);
        sharing = true; // the binding strike lands on each once, never shared
        try {
            for (var e : link.bound) {
                SpellKit.line(level, chest(first), chest(e), new DustParticleOptions(0x7FE8DA, 1.2f), .25);
                Spells.hit(p, e, dmg, Spell.SOUL_LINK);
            }
        } finally {
            sharing = false;
        }
        sound(level, first.position(), SoundEvents.CHAIN_PLACE, 1.5f, .6f);
        sound(level, first.position(), SoundEvents.SOUL_ESCAPE.value(), 1.2f, .8f);
        return true;
    }

    static void tickLinks() {
        for (Iterator<Link> it = LINKS.iterator(); it.hasNext(); ) {
            var l = it.next();
            l.age++;
            List<LivingEntity> fallen = new ArrayList<>();
            for (Iterator<LivingEntity> bt = l.bound.iterator(); bt.hasNext(); ) {
                var e = bt.next();
                if (!e.isAlive() || e.isRemoved()) {
                    if (!e.isAlive()) fallen.add(e);
                    bt.remove();
                }
            }
            if (l.fall && !l.owner.isRemoved() && !fallen.isEmpty()) {
                sharing = true; // each takes Chained Fall's strike once
                try {
                    for (var dead : fallen)
                        for (var e : List.copyOf(l.bound)) {
                            SpellKit.line(l.level, chest(dead), chest(e), new DustParticleOptions(0x7FE8DA, 1f), .3);
                            Spells.hit(l.owner, e, Perks.flat(l.owner, 6), Spell.SOUL_LINK);
                        }
                } finally {
                    sharing = false;
                }
            }
            if (l.owner.isRemoved() || --l.ticks < 0 || l.bound.size() < 2) {
                it.remove();
                continue;
            }
            if (l.drag && l.age % 10 == 0)
                for (var e : l.bound) e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15, 1), l.owner);
            if (l.age % 4 != 0) continue;
            // Chains: each bound monster to the next, round the pack.
            List<LivingEntity> ring = List.copyOf(l.bound);
            for (int i = 0; i < ring.size(); i++) {
                var a = ring.get(i);
                var b = ring.get((i + 1) % ring.size());
                if (ring.size() == 2 && i == 1) break;
                SpellKit.line(l.level, chest(a), chest(b), new DustParticleOptions(0x7FE8DA, .8f), .45);
            }
        }
    }

    /** Whether {@code e} is bound in one of {@code owner}'s chains, for the self-test. */
    static boolean linked(LivingEntity e) {
        for (var l : LINKS) if (l.bound.contains(e)) return true;
        return false;
    }

    // ---------------------------------------------------------------- Weaken

    static final double ENFEEBLE_REACH = 24;
    static final int ENFEEBLE_TICKS = 200, DISARM_TICKS = 60;

    record Feeble(ServerPlayer owner, long until, long disarmedUntil, boolean sapping, boolean brittle) {}

    static final Map<LivingEntity, Feeble> ENFEEBLED = new IdentityHashMap<>();

    /** The monster looked at deals half its harm and moves slower for a while. */
    static boolean enfeeble(ServerLevel level, ServerPlayer p) {
        LivingEntity target = Spells.lookTarget(p, SpellUpgrades.scale(Spell.ENFEEBLE, REACH, ENFEEBLE_REACH));
        if (target == null || !SpellKit.monster(p, target)) {
            say(p, "Look at a monster to weaken it");
            return false;
        }
        int ticks = SpellUpgrades.scaleInt(Spell.ENFEEBLE, DURATION, ENFEEBLE_TICKS);
        long now = level.getGameTime();
        List<LivingEntity> all = new ArrayList<>(List.of(target));
        if (perk(p, Spell.ENFEEBLE, "Spreading Weakness"))
            all.addAll(Perks.nearestMonsters(p, target, target.position(), 4, 16));
        boolean disarm = perk(p, Spell.ENFEEBLE, "Disarm");
        for (var e : all) {
            ENFEEBLED.put(
                    e,
                    new Feeble(
                            p,
                            now + ticks,
                            disarm ? now + DISARM_TICKS : 0,
                            perk(p, Spell.ENFEEBLE, "Sapping"),
                            perk(p, Spell.ENFEEBLE, "Brittle")));
            e.addEffect(new MobEffectInstance(
                    MobEffects.SLOWNESS, ticks, perk(p, Spell.ENFEEBLE, "Heavy Limbs") ? 1 : 0, false, true, true));
            SpellKit.line(
                    level,
                    p.getEyePosition().add(p.getLookAngle().scale(2.5)),
                    chest(e),
                    new DustParticleOptions(0x6B8F3A, 1f),
                    .4);
            level.sendParticles(ParticleTypes.SMOKE, e.getX(), chest(e).y, e.getZ(), 16, .3, .4, .3, .02);
        }
        sound(level, target.position(), SoundEvents.EVOKER_CAST_SPELL, 1f, .6f);
        return true;
    }

    static void tickEnfeebled() {
        for (Iterator<Map.Entry<LivingEntity, Feeble>> it = ENFEEBLED.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            var e = en.getKey();
            long now = e.level().getGameTime();
            if (e.isRemoved() || !e.isAlive() || now >= en.getValue().until()) {
                it.remove();
                continue;
            }
            if ((now + e.getId()) % 8 == 0 && e.level() instanceof ServerLevel l)
                l.sendParticles(
                        new DustParticleOptions(0x6B8F3A, 1f),
                        e.getX(),
                        e.getY() + e.getBbHeight() + .2,
                        e.getZ(),
                        2,
                        .2,
                        .05,
                        .2,
                        0);
        }
    }

    // ---------------------------------------------------------------- Fright

    static final double FRIGHT_RADIUS = 7;
    // owner, 2026-11-23: was 8
    static final int FRIGHT_TICKS = 60, FLEET_TICKS = 100;
    // and 3 s, was 4

    record Fear(ServerPlayer from, long until, boolean cowering, boolean dread) {}

    static final Map<Mob, Fear> AFRAID = new IdentityHashMap<>();

    /** Every monster near runs from you for a while. Bosses stand. */
    static boolean fright(ServerLevel level, ServerPlayer p) {
        double r = SpellUpgrades.scale(Spell.FRIGHT, AREA, FRIGHT_RADIUS);
        int ticks = SpellUpgrades.scaleInt(Spell.FRIGHT, DURATION, FRIGHT_TICKS);
        int scared = 0;
        boolean blow = perk(p, Spell.FRIGHT, "Scattering Blow");
        for (var e : Perks.nearestMonsters(p, null, p.position(), r, 64)) {
            if (blow) {
                Spells.hit(p, e, Perks.flat(p, 3), Spell.FRIGHT);
                Vec3 away = e.position().subtract(p.position()).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                double keep = 1 - SpellKit.knockResist(e);
                e.setDeltaMovement(away.scale(.8 * keep).add(0, .3 * keep, 0));
                e.needsSync = true;
            }
            if (frighten(p, e, ticks, perk(p, Spell.FRIGHT, "Cowering"), perk(p, Spell.FRIGHT, "Dread"))) {
                scared++;
                if (perk(p, Spell.FRIGHT, "Marked Prey"))
                    e.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0, false, false, false));
            }
        }
        if (perk(p, Spell.FRIGHT, "Fleet Foot"))
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, FLEET_TICKS, 0, false, false, true));
        Spells.ring(level, p.position().add(0, .2, 0), r * .5, new DustParticleOptions(0x3A2A4A, 1.4f));
        level.sendParticles(ParticleTypes.SCULK_SOUL, p.getX(), p.getY() + 1.2, p.getZ(), 12, .6, .4, .6, .05);
        sound(level, p.position(), SoundEvents.WARDEN_ROAR, .4f, 1.6f);
        if (scared == 0) say(p, "No monster near to frighten");
        return true;
    }

    /** Makes {@code e} run from {@code from} for {@code ticks}; false for bosses, which do not scare. */
    static boolean frighten(ServerPlayer from, LivingEntity e, int ticks, boolean cowering, boolean dread) {
        if (!(e instanceof Mob m) || Cage.mighty(m)) return false;
        AFRAID.put(m, new Fear(from, m.level().getGameTime() + ticks, cowering, dread));
        m.setTarget(null);
        return true;
    }

    static void tickFright() {
        for (Iterator<Map.Entry<Mob, Fear>> it = AFRAID.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Mob m = en.getKey();
            Fear f = en.getValue();
            long now = m.level().getGameTime();
            if (m.isRemoved() || !m.isAlive() || f.from().isRemoved() || now >= f.until()) {
                if (f.dread() && m.isAlive()) {
                    m.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0, false, true, true));
                    m.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 0, false, true, true));
                }
                it.remove();
                continue;
            }
            m.setTarget(null);
            if ((now + m.getId()) % 10 == 0) {
                Vec3 away = m.position().subtract(f.from().position()).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                m.getNavigation().moveTo(m.getX() + away.x * 10, m.getY(), m.getZ() + away.z * 10, 1.4);
                if (m.level() instanceof ServerLevel l)
                    l.sendParticles(
                            ParticleTypes.SMOKE,
                            m.getX(),
                            m.getY() + m.getBbHeight() + .2,
                            m.getZ(),
                            2,
                            .2,
                            .05,
                            .2,
                            0);
            }
        }
    }

    static boolean afraid(LivingEntity e) {
        var f = e instanceof Mob m ? AFRAID.get(m) : null;
        return f != null && e.level().getGameTime() < f.until();
    }

    // ---------------------------------------------------------------- Doom

    /**
     * Doom's curses (owner, 2026-11-23): each upgrade node is one the doomed creature always suffers, and one more is
     * drawn at random from the rest, so a tome with three upgrades in use lays four.
     */
    enum Curse {
        SLOWNESS("Slowness", "It always suffers Slowness II."),
        WEAKNESS("Weakness", "It always deals 30% less harm."),
        GLOWING("Glowing", "It always glows, seen through walls."),
        POISON("Poison", "It always suffers Poison (Wither for the undead)."),
        BURNING("Burning", "It always burns."),
        FROSTBITE("Frostbite", "It always freezes: slowed hard and shaking with cold."),
        BLEEDING("Bleeding", "It always bleeds, 1 harm a second."),
        ROOTED("Rooted", "It always stands rooted, unable to walk."),
        TERROR("Terror", "It always runs from you in terror.");
        final String title, text;

        Curse(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    static final double DOOM_REACH = 24, DOOM_HARM = 1.35;
    static final int DOOM_TICKS = 300;
    static final float SKULL_SIZE = .85f;

    static final class Doomed {
        final ServerPlayer owner;
        final Set<Curse> curses;
        final Display.ItemDisplay skull;
        final Vec3 seat;
        int left;

        Doomed(ServerPlayer owner, Set<Curse> curses, Display.ItemDisplay skull, Vec3 seat, int left) {
            this.owner = owner;
            this.curses = curses;
            this.skull = skull;
            this.seat = seat;
            this.left = left;
        }
    }

    static final Map<LivingEntity, Doomed> DOOMED = new IdentityHashMap<>();

    /** The curses a cast lays: the ones its upgrades choose, and one more at random from the rest. */
    static Set<Curse> curses(ServerPlayer p, net.minecraft.util.RandomSource random) {
        Set<Curse> chosen = EnumSet.noneOf(Curse.class);
        for (Curse c : Curse.values()) if (perk(p, Spell.DOOM, c.title)) chosen.add(c);
        List<Curse> rest = new ArrayList<>();
        for (Curse c : Curse.values()) if (!chosen.contains(c)) rest.add(c);
        if (!rest.isEmpty()) chosen.add(rest.get(random.nextInt(rest.size())));
        return chosen;
    }

    /**
     * A skull of doom over the creature you look at (monster or animal): for a while it takes 35% more harm from
     * everything and suffers its curses.
     */
    static boolean doom(ServerLevel level, ServerPlayer p) {
        LivingEntity target = Spells.lookTarget(p, SpellUpgrades.scale(Spell.DOOM, REACH, DOOM_REACH));
        if (target == null) {
            say(p, "Look at a creature to doom it");
            return false;
        }
        int ticks = SpellUpgrades.scaleInt(Spell.DOOM, DURATION, DOOM_TICKS);
        var old = DOOMED.remove(target);
        if (old != null) SpellKit.drop(old.skull);
        Set<Curse> curses = curses(p, level.getRandom());
        var skull = skull(level, target);
        DOOMED.put(
                target,
                skull == null
                        ? new Doomed(p, curses, null, Vec3.ZERO, ticks)
                        : new Doomed(p, curses, skull.getKey(), skull.getValue(), ticks));
        for (Curse curse : curses) lay(p, target, curse, ticks);
        SpellKit.line(level, p.getEyePosition().add(p.getLookAngle().scale(2)), chest(target), ParticleTypes.SOUL, .5);
        level.sendParticles(
                ParticleTypes.SCULK_SOUL, target.getX(), chest(target).y, target.getZ(), 14, .3, .5, .3, .04);
        sound(level, target.position(), SoundEvents.BELL_RESONATE, 1f, .6f);
        sound(level, target.position(), SoundEvents.WITHER_SPAWN, .25f, 1.6f);
        StringBuilder names = new StringBuilder();
        for (Curse curse : curses) names.append(names.isEmpty() ? "" : ", ").append(curse.title);
        say(p, "Doomed: " + names);
        return true;
    }

    /** A wither skull riding the creature, floating over its head. Returns it and its seat, or null. */
    private static Map.Entry<Display.ItemDisplay, Vec3> skull(ServerLevel level, LivingEntity target) {
        var d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (d == null) return null;
        d.setItemStack(new ItemStack(Items.WITHER_SKELETON_SKULL));
        d.setItemTransform(ItemDisplayContext.NONE);
        SpellKit.track(level, d, target.position());
        // The skull rides its creature, so the client draws both from one position (it never runs ahead of a
        // creature that is knocked about).
        Vec3 seat = Vec3.ZERO;
        if (d.startRiding(target, true, false)) {
            target.positionRider(d);
            seat = d.position().subtract(target.position());
        }
        placeSkull(d, target, seat, 0, 0);
        return Map.entry(d, seat);
    }

    private static void placeSkull(Display d, LivingEntity target, Vec3 seat, float turn, int interpolate) {
        // A skull item draws low in its box: lifted well clear, it floats over the head.
        float y = (float) (target.getBbHeight() + 1.15 - seat.y);
        d.setTransformation(new Transformation(
                new Vector3f((float) -seat.x, y, (float) -seat.z),
                new Quaternionf().rotateY(turn),
                new Vector3f(SKULL_SIZE),
                new Quaternionf()));
        d.setTransformationInterpolationDelay(0);
        d.setTransformationInterpolationDuration(interpolate);
    }

    private static void lay(ServerPlayer p, LivingEntity e, Curse c, int ticks) {
        switch (c) {
            case SLOWNESS -> e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 1, false, true, true), p);
            case WEAKNESS -> {} // in incoming(): 30% less harm dealt
            case GLOWING -> e.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0, false, false, true), p);
            case POISON -> {
                boolean undead = e.getType().builtInRegistryHolder().is(EntityTypeTags.UNDEAD);
                e.addEffect(
                        new MobEffectInstance(
                                undead ? MobEffects.WITHER : MobEffects.POISON, ticks, 0, false, true, true),
                        p);
            }
            case BURNING -> e.igniteForTicks(ticks);
            case FROSTBITE -> Frostbite.bite(e, p, ticks);
            case BLEEDING -> FormSpells.bleed(p, e, ticks);
            case ROOTED -> Perks.root(e, p, ticks);
            case TERROR -> HexSpells.frighten(p, e, ticks, false, false);
        }
    }

    static void tickDoom() {
        for (Iterator<Map.Entry<LivingEntity, Doomed>> it = DOOMED.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            LivingEntity e = en.getKey();
            Doomed c = en.getValue();
            if (e.isRemoved() || !e.isAlive() || --c.left <= 0 || !(e.level() instanceof ServerLevel level)) {
                if (e.level() instanceof ServerLevel level && c.skull != null)
                    level.sendParticles(
                            ParticleTypes.SOUL,
                            c.skull.getX(),
                            e.getY() + e.getBbHeight() + .6,
                            c.skull.getZ(),
                            8,
                            .2,
                            .2,
                            .2,
                            .03);
                SpellKit.drop(c.skull);
                it.remove();
                continue;
            }
            if (c.curses.contains(Curse.BURNING) && e.getRemainingFireTicks() < 20) e.igniteForTicks(c.left);
            // The skull turns slowly over its head; a creature that would not carry it is followed by hand.
            if (c.skull != null && c.left % 4 == 0) placeSkull(c.skull, e, c.seat, c.left * .06f, 4);
            if (c.skull != null && !c.skull.isPassenger()) c.skull.setPos(e.getX(), e.getY(), e.getZ());
            if ((c.left + e.getId()) % 6 == 0)
                level.sendParticles(
                        ParticleTypes.SOUL_FIRE_FLAME,
                        e.getX(),
                        e.getY() + e.getBbHeight() + .45,
                        e.getZ(),
                        1,
                        .12,
                        .05,
                        .12,
                        0);
        }
    }

    static Doomed doomed(LivingEntity e) {
        return DOOMED.get(e);
    }

    /** For the client test: whether some doomed creature carries its skull as a rider. */
    public static boolean skullRiding() {
        return DOOMED.values().stream().anyMatch(d -> d.skull != null && d.skull.isPassenger());
    }
}
