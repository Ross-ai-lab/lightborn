package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.aimPoint;
import static lightborn.SpellKit.centred;
import static lightborn.SpellKit.chest;
import static lightborn.SpellKit.drop;
import static lightborn.SpellKit.knockResist;
import static lightborn.SpellKit.line;
import static lightborn.SpellKit.sound;
import static lightborn.SpellKit.track;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.AREA;
import static lightborn.SpellUpgrades.Stat.COUNT;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.SpellUpgrades.Stat.REACH;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Arcane spells: pure spellcraft that bends force, time and casting itself. Arcane Bolt, Gravity Well, Trap,
 * Mirror Ward, Rewind, Twincast, Freedom and Time Stop.
 */
public final class ArcaneSpells {
    private ArcaneSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case ARCANE_BOLT -> arcaneBolt(level, p, spell, dmg);
            case GRAVITY_WELL -> gravityWell(level, p, dmg, SpellUpgrades.scaleInt(spell, DURATION, WELL_TICKS));
            case RUNE_SNARE -> runeSnare(level, p, dmg);
            case MIRROR_WARD -> {
                int ticks = SpellUpgrades.scaleInt(spell, DURATION, WARD_TICKS);
                yield mirrorWard(level, p, dmg, ticks) && mirrorWardBranches(p, spell, ticks);
            }
            case REWIND -> rewind(level, p);
            case TWINCAST -> twincast(level, p);
            case WELLSPRING -> freedom(level, p);
            case TIME_STOP -> timeStop(level, p);
            default -> throw new IllegalArgumentException(spell + " is not an arcane spell");
        };
    }

    static void tick(MinecraftServer server) {
        ArcaneSpells.server = server;
        tickWards();
        tickWells();
        tickRunes();
        tickMarks();
        tickTwins();
        tickFreedom();
        tickHeld();
    }

    static void clear() {
        WARDS.clear();
        WELLS.clear();
        RUNES.clear();
        MARKS.clear();
        TWINS.clear();
        ECHOES.clear();
        FREEDOMS.clear();
        new ArrayList<>(HELD.keySet()).forEach(ArcaneSpells::release);
        HELD.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "wards" -> WARDS.size();
            case "wells" -> WELLS.size();
            case "runes" -> RUNES.size();
            case "marks" -> MARKS.size();
            case "twins" -> TWINS.size();
            case "freedoms" -> FREEDOMS.size();
            case "held" -> HELD.size();
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- Arcane Bolt

    static boolean arcaneBolt(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Spells.sound(level, p, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 1.6f);
        Spells.sound(level, p, SoundEvents.ILLUSIONER_CAST_SPELL, 0.5f, 1.8f);
        var bolt = new Spells.Projectile(
                p, spell, eye.add(look.scale(0.5)), look.scale(2.2), dmg, 0, SpellUpgrades.scaleInt(spell, REACH, 20));
        bolt.pierce = SpellUpgrades.add(spell, COUNT, TomeAwakening.awake(p, spell) ? 3 : 0); // Arcane Lance
        Spells.PROJECTILES.add(bolt);
        if (SpellUpgrades.perk(p, spell, "Arcane Echo")) {
            int pierce = bolt.pierce, reach = SpellUpgrades.scaleInt(spell, REACH, 20);
            Scheduler.later(8, () -> {
                if (!p.isAlive() || p.level() != level) return;
                Vec3 l = p.getLookAngle();
                var echoBolt = new Spells.Projectile(
                        p, Spell.ARCANE_BOLT, p.getEyePosition().add(l.scale(.5)), l.scale(2.2), dmg, 0, reach);
                echoBolt.pierce = pierce;
                Spells.PROJECTILES.add(echoBolt);
                Spells.sound(level, p, SoundEvents.AMETHYST_BLOCK_CHIME, .9f, 1.9f);
            });
        }
        return true;
    }

    /** Mirror Ward's tier II branches, acting once as it is cast (owner, 2026-11-23: one aspect per upgrade tree). */
    private static boolean mirrorWardBranches(ServerPlayer p, Spell spell, int ticks) {
        if (SpellUpgrades.perk(p, spell, "Glass Skin"))
            p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 0, false, false, true));
        if (SpellUpgrades.perk(p, spell, "Mirror Mend")) p.heal(4);
        return true;
    }

    /** Time Stop's Stored Blows: what a held monster takes is counted, to land again when time runs on. */
    static void storeBlow(LivingEntity entity, float taken) {
        var h = HELD.get(entity);
        if (h != null && h.stored && !releasing) h.harm += taken;
    }

    /** A creature saved while Time Stop held it wakes when it loads again. */
    static void wakeHeld(Entity e) {
        if (e.entityTags().contains(HELD_TAG) && !HELD.containsKey(e)) {
            e.removeTag(HELD_TAG);
            e.setNoGravity(false);
            if (e instanceof Mob m) m.setNoAi(false);
        }
    }

    /** Before armour: Fierce Tempest raises your spells; Free Fall spares you falls. */
    static float freedomIncoming(LivingEntity target, DamageSource source, float amount) {
        if (source.getEntity() instanceof ServerPlayer by && source.getEntity() != target) {
            var w = freedomOf(by);
            if (w != null && w.fierce && source.is(DamageTypes.INDIRECT_MAGIC)) amount *= 1.25f;
        }
        if (target instanceof ServerPlayer p && source.is(DamageTypes.FALL)) {
            var w = freedomOf(p);
            if (w != null && w.softFall) return 0;
        }
        return amount;
    }

    // ---------------------------------------------------------------- Mirror Ward

    static final int WARD_TICKS = 160;
    /** Share of a creature's hit the ward stops, and how much of the hit (before the ward) flies back. */
    static final double WARD_SOAK = 0.5, WARD_REFLECT = 1.5;

    private static final class Ward {
        int ticks;
        final double power;

        Ward(int ticks, double power) {
            this.ticks = ticks;
            this.power = power;
        }
    }

    /** Keyed by the player object itself: a respawn or logout makes a new one, and the old ward lapses with it. */
    static final Map<ServerPlayer, Ward> WARDS = new IdentityHashMap<>();

    /**
     * A shell of living glass for 8 s (upgraded 12): hits from creatures (blows, arrows, blasts) do half damage, and
     * the one who struck takes one and a half times the full hit back, plus the spell's power, as spell damage.
     */
    static boolean mirrorWard(ServerLevel level, ServerPlayer p, double power, int ticks) {
        WARDS.put(p, new Ward(ticks, power));
        if (SpellUpgrades.perk(p, Spell.MIRROR_WARD, "Glass Haste"))
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 0, false, false, true));
        Vec3 c = chest(p);
        level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 40, 0.6, 0.8, 0.6, 0.08);
        Bosses.ring(level, p.position().add(0, 0.1, 0), 1.2, new DustParticleOptions(0xDDF6FF, 1.2f), 24);
        sound(level, c, SoundEvents.BEACON_ACTIVATE, 1.0f, 1.8f);
        sound(level, c, SoundEvents.AMETHYST_BLOCK_CHIME, 1.5f, 1.2f);
        return true;
    }

    static boolean warded(Player p) {
        return p instanceof ServerPlayer sp && WARDS.containsKey(sp);
    }

    /** Called for every hit before armour: a warded player takes half from a creature's hit. */
    static float wardSoak(LivingEntity target, DamageSource source, float amount) {
        if (!(target instanceof ServerPlayer p)
                || !(source.getEntity() instanceof LivingEntity)
                || source.getEntity() == p) return amount;
        return WARDS.containsKey(p) ? (float) (amount * (1 - WARD_SOAK)) : amount;
    }

    /**
     * After a hit landed on a warded player: it flies back at whoever struck. Judged on the hit itself (after the
     * ward's half), not on what got through armour and absorption, so a hit a Stoneskin shield soaks still reflects.
     */
    static void afterDamage(LivingEntity target, DamageSource source, float hit, boolean blocked) {
        if (hit <= 0 || blocked || !(target instanceof ServerPlayer p)) return;
        Ward w = WARDS.get(p);
        if (w == null || !(source.getEntity() instanceof LivingEntity attacker) || attacker == p || !attacker.isAlive())
            return;
        if (!Spells.canHitDirect(p, attacker)) return;
        double full = hit / (1 - WARD_SOAK);
        Spells.hit(p, attacker, full * WARD_REFLECT + w.power, Spell.MIRROR_WARD);
        ServerLevel level = p.level();
        line(level, chest(p), chest(attacker), ParticleTypes.END_ROD, 0.6);
        Vec3 at = chest(attacker);
        level.sendParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y, at.z, 16, 0.3, 0.3, 0.3, 0.3);
        sound(level, chest(p), SoundEvents.SHIELD_BLOCK.value(), 1.0f, 1.4f);
        sound(level, at, SoundEvents.AMETHYST_CLUSTER_BREAK, 1.0f, 1.6f);
    }

    static void tickWards() {
        for (Iterator<Map.Entry<ServerPlayer, Ward>> it = WARDS.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            ServerPlayer p = en.getKey();
            Ward w = en.getValue();
            if (p.isRemoved() || !p.isAlive() || --w.ticks <= 0) {
                // Shard Burst: the glass flies outward as the ward ends.
                if (!p.isRemoved() && p.isAlive() && SpellUpgrades.perk(p, Spell.MIRROR_WARD, "Shard Burst"))
                    Spells.withSpell(
                            Spell.MIRROR_WARD, () -> Perks.burst(p, chest(p), 5, w.power * 2, ParticleTypes.END_ROD));
                if (!p.isRemoved() && p.isAlive())
                    p.level()
                            .playSound(
                                    null,
                                    p.getX(),
                                    p.getY(),
                                    p.getZ(),
                                    SoundEvents.GLASS_BREAK,
                                    SoundSource.PLAYERS,
                                    0.6f,
                                    1.6f);
                it.remove();
                continue;
            }
            // A slow shimmer of motes on a shell around the body; it flickers in the last two seconds.
            if (w.ticks % 3 == 0 && (w.ticks > 40 || (w.ticks / 4) % 2 == 0)) {
                ServerLevel level = p.level();
                var r = level.getRandom();
                for (int i = 0; i < 2; i++) {
                    double a = r.nextDouble() * Math.PI * 2, y = r.nextDouble() * 2 - 1;
                    double rr = Math.sqrt(1 - y * y);
                    level.sendParticles(
                            ParticleTypes.END_ROD,
                            p.getX() + Math.cos(a) * rr * 0.9,
                            p.getY() + 1 + y * 1.1,
                            p.getZ() + Math.sin(a) * rr * 0.9,
                            1,
                            0,
                            0,
                            0,
                            0);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Gravity Well

    static final int WELL_TICKS = 60;
    static final double WELL_RANGE = 32, WELL_PULL = 7, WELL_BURST = 4;
    /** Each half second inside the well costs this share of the spell's damage; the collapse costs the whole. */
    static final double WELL_TICK_SHARE = 0.3;

    private static final class Well {
        final ServerPlayer owner;
        final ServerLevel level;
        Vec3 c;
        final double dmg;
        final int life;
        final boolean nova, drift;
        int age;
        Display.BlockDisplay core;

        Well(ServerPlayer owner, ServerLevel level, Vec3 c, double dmg, int life) {
            this.owner = owner;
            this.level = level;
            this.c = c;
            this.dmg = dmg;
            this.life = life;
            this.nova = SpellUpgrades.perk(owner, Spell.GRAVITY_WELL, "Supernova");
            this.drift = SpellUpgrades.perk(owner, Spell.GRAVITY_WELL, "Drifting Well");
        }
    }

    static final List<Well> WELLS = new ArrayList<>();

    /**
     * A collapsing star at the aimed point (32 blocks): for 3 s (upgraded 5) it drags every creature within 7 blocks
     * into its heart, biting each half second, then implodes, hurling them skyward. Heavy monsters resist the pull.
     */
    static boolean gravityWell(ServerLevel level, ServerPlayer p, double dmg, int life) {
        Vec3 aim = aimPoint(p, WELL_RANGE);
        if (aim == null) {
            p.sendSystemMessage(
                    Component.literal("Too far: aim at the ground within 32 blocks")
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return false;
        }
        Vec3 c = aim.add(0, 1.2, 0);
        Well w = new Well(p, level, c, dmg, life);
        Display.BlockDisplay core = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (core != null) {
            core.setBlockState(Blocks.CRYING_OBSIDIAN.defaultBlockState());
            core.setViewRange(4f);
            w.core = track(level, core, c);
            centred(core, 0.3f, new Quaternionf(), 0);
        }
        WELLS.add(w);
        sound(level, c, SoundEvents.WARDEN_SONIC_CHARGE, 2.0f, 1.3f);
        sound(level, p.position(), SoundEvents.EVOKER_CAST_SPELL, 1.0f, 0.6f);
        return true;
    }

    static void tickWells() {
        for (Iterator<Well> it = WELLS.iterator(); it.hasNext(); ) {
            Well w = it.next();
            w.age++;
            ServerLevel level = w.level;
            if (w.owner.isRemoved() || w.owner.level() != level) {
                drop(w.core);
                it.remove();
                continue;
            }
            // Drifting Well: it creeps toward the nearest monster within 10 blocks.
            if (w.drift) {
                var near = Perks.nearestMonsters(w.owner, null, w.c, 10, 1);
                if (!near.isEmpty()) {
                    Vec3 to = chest(near.getFirst()).subtract(w.c);
                    if (to.lengthSqr() > 1) w.c = w.c.add(to.normalize().scale(.08));
                    if (w.core != null && !w.core.isRemoved()) w.core.setPos(w.c.x, w.c.y, w.c.z);
                }
            }
            Vec3 c = w.c;
            // The core swells and tumbles while the well lives.
            if (w.core != null && !w.core.isRemoved()) {
                float t = Math.min(1f, w.age / (float) w.life);
                Quaternionf rot = new Quaternionf().rotateAxis(w.age * 0.4f, new Vector3f(0.3f, 1f, 0.5f).normalize());
                centred(w.core, 0.4f + 0.6f * t, rot, 1);
            }
            // A spiral of reversed portal motes falling inward, and ink at the heart.
            for (int k = 0; k < 4; k++) {
                double a = w.age * 0.45 + k * Math.PI / 2;
                double r = WELL_PULL * (1 - ((w.age * 0.06 + k * 0.25) % 1.0));
                level.sendParticles(
                        ParticleTypes.REVERSE_PORTAL,
                        true,
                        true,
                        c.x + Math.cos(a) * r,
                        c.y - 0.6 + (k % 2) * 0.5,
                        c.z + Math.sin(a) * r,
                        2,
                        0.1,
                        0.1,
                        0.1,
                        0);
            }
            level.sendParticles(ParticleTypes.PORTAL, c.x, c.y, c.z, 6, 0.3, 0.3, 0.3, 1.2);
            if (w.age % 3 == 0) level.sendParticles(ParticleTypes.SQUID_INK, c.x, c.y, c.z, 2, 0.15, 0.15, 0.15, 0.01);
            if (w.age % 20 == 0) sound(level, c, SoundEvents.PORTAL_AMBIENT, 0.8f, 1.8f);
            boolean bite = w.age % 10 == 0;
            for (LivingEntity e : level.getEntitiesOfClass(
                    LivingEntity.class, new AABB(c, c).inflate(WELL_PULL), e -> Spells.canHitArea(w.owner, e))) {
                Vec3 to = c.subtract(chest(e));
                double d = to.length();
                if (d > WELL_PULL) continue;
                if (d > 0.7) {
                    double pull = 0.2 * (1 - knockResist(e));
                    e.setDeltaMovement(
                            e.getDeltaMovement().scale(0.5).add(to.normalize().scale(pull)));
                    e.resetFallDistance();
                }
                if (bite) Spells.hit(w.owner, e, w.dmg * WELL_TICK_SHARE, Spell.GRAVITY_WELL);
            }
            if (w.age >= w.life) {
                drop(w.core);
                it.remove();
                collapse(w);
            }
        }
    }

    private static void collapse(Well w) {
        ServerLevel level = w.level;
        Vec3 c = w.c;
        level.sendParticles(ParticleTypes.SONIC_BOOM, true, true, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 4, 1.0, 0.6, 1.0, 0);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, true, true, c.x, c.y, c.z, 120, 0.4, 0.4, 0.4, 0.6);
        double burst = w.nova ? WELL_BURST * 2 : WELL_BURST;
        Bosses.ring(level, c.subtract(0, 1.1, 0), burst, new DustParticleOptions(0x5B1E8C, 1.4f), 48);
        if (w.nova) {
            Bosses.ring(level, c.subtract(0, 1.1, 0), burst, ParticleTypes.FLAME, 64);
            level.sendParticles(ParticleTypes.FLAME, true, true, c.x, c.y, c.z, 80, burst / 3, .5, burst / 3, .1);
        }
        sound(level, c, SoundEvents.WARDEN_SONIC_BOOM, 2.0f, 1.2f);
        Spells.withSpell(
                Spell.GRAVITY_WELL,
                () -> Spells.areaHit(w.owner, c, burst, w.dmg, e -> {
                    e.setDeltaMovement(
                            e.getDeltaMovement().multiply(0.2, 0, 0.2).add(0, 0.95 * (1 - knockResist(e)), 0));
                    if (w.nova) e.igniteForSeconds(5);
                }));
    }

    // ---------------------------------------------------------------- Rewind

    static final int REWIND_TICKS = 200;
    // ten seconds (owner, 2026-11-22)

    static final class Mark {
        final ServerLevel level;
        final Vec3 pos;
        final float yRot, xRot, health, mana;
        int ticks;

        Mark(ServerPlayer p, int ticks) {
            this.level = p.level();
            this.pos = p.position();
            this.yRot = p.getYRot();
            this.xRot = p.getXRot();
            this.health = p.getHealth();
            this.mana = Progression.mana(p);
            this.ticks = ticks;
        }
    }

    static final Map<ServerPlayer, Mark> MARKS = new IdentityHashMap<>();

    /** Marks the moment: a few seconds later you are pulled back to it, with the health you had, if it was more. */
    static boolean rewind(ServerLevel level, ServerPlayer p) {
        MARKS.put(p, new Mark(p, SpellUpgrades.scaleInt(Spell.REWIND, DURATION, REWIND_TICKS)));
        atFeet(p, new DustParticleOptions(0xC9B0FF, 1f), ParticleTypes.REVERSE_PORTAL, 30);
        sound(level, p.position(), SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, .7f);
        sound(level, p.position(), SoundEvents.BEACON_POWER_SELECT, .6f, .8f);
        return true;
    }

    static void tickMarks() {
        for (var it = MARKS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var p = entry.getKey();
            var m = entry.getValue();
            if (p.isRemoved() || !p.isAlive()) {
                it.remove();
                continue;
            }
            if (p.level() != m.level) {
                p.sendSystemMessage(
                        Component.literal("Your Rewind lost its moment").withStyle(ChatFormatting.GRAY), true);
                it.remove();
                continue;
            }
            if (--m.ticks >= 0) {
                if (m.ticks % 2 == 0) {
                    for (int k = 0; k < 7; k++)
                        m.level.sendParticles(
                                new DustParticleOptions(0xC9B0FF, 1f),
                                m.pos.x,
                                m.pos.y + k * .3,
                                m.pos.z,
                                1,
                                .08,
                                0,
                                .08,
                                0);
                    Spells.ring(m.level, m.pos.add(0, .1, 0), .5, new DustParticleOptions(0xE6D8FF, .8f));
                }
                if (m.ticks % 20 == 0) {
                    sound(m.level, m.pos, SoundEvents.NOTE_BLOCK_HAT.value(), .7f, 1.6f);
                    // A small countdown above the hotbar, as Homeward's (owner, 2026-11-22): 10, 9, 8...
                    p.sendSystemMessage(
                            Component.literal("Rewind · " + (m.ticks / 20 + 1)).withStyle(ChatFormatting.LIGHT_PURPLE),
                            true);
                }
                continue;
            }
            it.remove();
            back(p, m);
        }
    }

    /**
     * A death while a Rewind waits is undone: the caster is taken back to the mark at once, with the health they had
     * there (owner, 2026-11-22). Registered before the gear's Second Dawn, so the rewind is spent first. Not /kill.
     */
    static boolean allowDeath(LivingEntity dying, DamageSource source, float amount) {
        if (!(dying instanceof ServerPlayer p) || source.is(DamageTypes.GENERIC_KILL)) return true;
        var m = MARKS.get(p);
        if (m == null || p.level() != m.level) return true;
        MARKS.remove(p);
        p.setHealth(Math.max(1, Math.min(m.health, p.getMaxHealth())));
        p.clearFire();
        back(p, m);
        p.sendSystemMessage(
                Component.literal("Rewind pulled you back from death").withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return false;
    }

    private static void back(ServerPlayer p, Mark m) {
        var level = m.level;
        Vec3 left = p.position();
        if (SpellUpgrades.perk(p, Spell.REWIND, "Parting Shock")) {
            for (var e : Perks.nearestMonsters(p, null, left, 4, 64)) {
                Spells.hit(p, e, Perks.flat(p, 6), Spell.REWIND);
                Vec3 away = e.position().subtract(left).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                e.setDeltaMovement(away.scale(1.1).add(0, .45, 0));
                e.needsSync = true;
            }
            level.sendParticles(ParticleTypes.SONIC_BOOM, left.x, left.y + 1, left.z, 1, 0, 0, 0, 0);
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, left.x, left.y + 1, left.z, 40, .4, .8, .4, .1);
        p.teleportTo(level, m.pos.x, m.pos.y, m.pos.z, Set.of(), m.yRot, m.xRot, false);
        pushPlayer(p, Vec3.ZERO);
        p.resetFallDistance();
        if (m.health > p.getHealth()) p.setHealth(Math.min(m.health, p.getMaxHealth()));
        if (SpellUpgrades.perk(p, Spell.REWIND, "Mana Rewind") && m.mana > Progression.mana(p))
            Progression.setMana(p, m.mana);
        if (SpellUpgrades.perk(p, Spell.REWIND, "Cleansing Return")) {
            for (var effect : new ArrayList<>(p.getActiveEffects()))
                if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL)
                    p.removeEffect(effect.getEffect());
            p.clearFire();
        }
        if (SpellUpgrades.perk(p, Spell.REWIND, "Safe Return"))
            p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 60, 1, false, false, true));
        if (SpellUpgrades.perk(p, Spell.REWIND, "Swift Return"))
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, 80, 1, false, false, true));
        if (SpellUpgrades.perk(p, Spell.REWIND, "Shadow Return")) {
            p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, false, false, true));
            for (var mob :
                    level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32)))
                if (mob.getTarget() == p) mob.setTarget(null);
        }
        atFeet(p, new DustParticleOptions(0xC9B0FF, 1f), ParticleTypes.REVERSE_PORTAL, 40);
        forOthers(p, ParticleTypes.END_ROD, m.pos.add(0, 1, 0), 16, .4, .05);
        sound(level, m.pos, SoundEvents.CHORUS_FRUIT_TELEPORT, 1f, 1.3f);
        sound(level, m.pos, SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, 1.4f);
    }

    // ---------------------------------------------------------------- Twincast

    static final int TWIN_TICKS = 100, TWIN_DELAY = 12, TWIN_QUICK = 6;
    // 5 s (owner, 2026-11-22)
    /** Spells a second cast of would waste: they last, refresh, summon one thing, or are a single pulse. */
    static final Set<Spell> NO_ECHO = EnumSet.of(
            Spell.TWINCAST,
            Spell.REWIND,
            Spell.HOMEWARD,
            Spell.FLIGHT,
            Spell.SPIRIT_STEED,
            Spell.SPIRIT_WOLVES,
            Spell.UPDRAFT,
            Spell.SOULS,
            Spell.SKIES,
            Spell.ECLIPSE,
            Spell.SUNBEAM,
            Spell.LIGHTBRAND,
            Spell.OWL_SIGHT,
            Spell.ORE_SIGHT,
            Spell.MIRROR_WARD,
            Spell.TREASURE_THREAD,
            // The fifth shelf: a second of these would undo the first, refresh it, or find nothing left to do.
            Spell.TRANSPOSE,
            Spell.MOONGATE,
            Spell.EMBERWALK,
            Spell.CRYSTAL_SHELL,
            Spell.QUICKENING,
            Spell.LIGHT_BORE,
            // The sixth shelf: these hold, refresh or refill something a second cast would only repeat.
            Spell.SENTINEL,
            Spell.WELLSPRING,
            Spell.TIME_STOP,
            Spell.TEMPEST,
            Spell.COLOSSUS,
            Spell.ASCENSION,
            Spell.RENEWAL,
            Spell.TRANSMUTE,
            // The seventh shelf: a shape, a size or a menu, which a second cast would only take again.
            Spell.WOLF_FORM,
            Spell.BEAR_FORM,
            Spell.SPIDER_FORM,
            Spell.BLAZE_FORM,
            Spell.SPIRIT_WALK,
            Spell.SHRINKING,
            Spell.GILLS,
            Spell.PURIFY,
            Spell.ENDER_POCKET,
            Spell.WORKBENCH,
            Spell.FROSTWALK,
            Spell.WARDEN_FORM,
            // The eighth shelf: a second dash, curse or tree would only replace the first.
            Spell.COMET_DASH,
            Spell.DOOM,
            Spell.LIFEBLOOM);

    static final class Twin {
        int ticks, charges;
        final int delay;

        Twin(int ticks, int charges, int delay) {
            this.ticks = ticks;
            this.charges = charges;
            this.delay = delay;
        }
    }

    static final Map<ServerPlayer, Twin> TWINS = new IdentityHashMap<>();

    /** Your next spell (within 20 seconds) is cast a second time a moment later, free. */
    static boolean twincast(ServerLevel level, ServerPlayer p) {
        int charges = SpellUpgrades.add(Spell.TWINCAST, COUNT, 1);
        int delay = SpellUpgrades.special(Spell.TWINCAST) ? TWIN_QUICK : TWIN_DELAY;
        TWINS.put(p, new Twin(SpellUpgrades.scaleInt(Spell.TWINCAST, DURATION, TWIN_TICKS), charges, delay));
        boolean triple = SpellUpgrades.perk(p, Spell.TWINCAST, "Triple Cast");
        p.sendSystemMessage(
                Component.literal((charges > 1 ? "Your next two spells" : "Your next spell") + " will be cast "
                                + (triple ? "three times" : "twice"))
                        .withStyle(ChatFormatting.LIGHT_PURPLE),
                true);
        atFeet(p, new DustParticleOptions(0xD59BFF, 1f), ParticleTypes.WITCH, 14);
        sound(level, p.position(), SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, 1.8f);
        sound(level, p.position(), SoundEvents.ILLUSIONER_PREPARE_MIRROR, .7f, 1.4f);
        return true;
    }

    static boolean twinned(ServerPlayer p) {
        return TWINS.containsKey(p);
    }

    /** After a cast: a waiting twin echoes it, once more (or twice, with Triple Cast). */
    static void afterCast(ServerPlayer p, Spell spell, ItemStack tome, double power) {
        var twin = TWINS.get(p);
        if (twin == null || NO_ECHO.contains(spell)) return;
        if (--twin.charges <= 0) TWINS.remove(p);
        ItemStack copy = tome.copy();
        double echo = power * (SpellUpgrades.perk(p, Spell.TWINCAST, "Empowered Echo") ? 1.25 : 1);
        int times = SpellUpgrades.perk(p, Spell.TWINCAST, "Triple Cast") ? 2 : 1;
        for (int k = 1; k <= times; k++) ECHOES.add(new Echo(p, spell, copy, echo, twin.delay * k));
    }

    record Echo(ServerPlayer p, Spell spell, ItemStack tome, double power, int[] left) {
        Echo(ServerPlayer p, Spell spell, ItemStack tome, double power, int ticks) {
            this(p, spell, tome, power, new int[] {ticks});
        }
    }

    static final List<Echo> ECHOES = new ArrayList<>();

    static void tickTwins() {
        var due = new ArrayList<Echo>();
        for (Iterator<Echo> it = ECHOES.iterator(); it.hasNext(); ) {
            var e = it.next();
            if (--e.left()[0] > 0) continue;
            it.remove();
            due.add(e);
        }
        for (var e : due) {
            var p = e.p();
            if (p.isRemoved() || !p.isAlive()) continue;
            atFeet(p, new DustParticleOptions(0xD59BFF, 1f), ParticleTypes.WITCH, 8);
            sound(p.level(), p.position(), SoundEvents.AMETHYST_BLOCK_RESONATE, .8f, 2f);
            Spells.recast(p, e.spell(), e.tome(), e.power());
            if (SpellUpgrades.perk(p, Spell.TWINCAST, "Mana Echo")) Perks.mana(p, 5);
            if (SpellUpgrades.perk(p, Spell.TWINCAST, "Echo Ward"))
                p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0, false, false, true));
            if (SpellUpgrades.perk(p, Spell.TWINCAST, "Echo Sparks"))
                for (var near : Perks.nearestMonsters(p, null, p.position(), 8, 2)) {
                    Spells.hit(p, near, Perks.flat(p, 3), Spell.TWINCAST);
                    p.level()
                            .sendParticles(
                                    ParticleTypes.WITCH, near.getX(), chest(near).y, near.getZ(), 8, .2, .3, .2, .1);
                }
        }
        for (var it = TWINS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var p = entry.getKey();
            var t = entry.getValue();
            if (p.isRemoved() || !p.isAlive() || --t.ticks < 0) {
                it.remove();
                continue;
            }
            if (t.ticks % 3 != 0) continue;
            double a = t.ticks * .25;
            for (int k = 0; k < 2; k++) {
                double b = a + Math.PI * k;
                p.level()
                        .sendParticles(
                                new DustParticleOptions(0xD59BFF, 1f),
                                p.getX() + Math.cos(b) * .8,
                                p.getY() + .3,
                                p.getZ() + Math.sin(b) * .8,
                                1,
                                0,
                                0,
                                0,
                                0);
            }
        }
    }

    // ---------------------------------------------------------------- Trap

    static final double RUNE_REACH = 24, RUNE_BURST = 3;
    static final int RUNE_MAX = 3, RUNE_ARM = 10;
    // traps wait until set off (owner, 2026-11-22: no time limit)

    static final class Rune {
        final ServerPlayer owner;
        final ServerLevel level;
        final Vec3 pos;
        final double dmg, radius;
        int age, bursts;

        Rune(ServerPlayer owner, Vec3 pos, double dmg, double radius, int bursts) {
            this.owner = owner;
            this.level = owner.level();
            this.pos = pos;
            this.dmg = dmg;
            this.radius = radius;
            this.bursts = bursts;
        }
    }

    static final List<Rune> RUNES = new ArrayList<>();

    /** Sets a rune trap on the ground in your aim; a monster that steps on it sets it off. */
    static boolean runeSnare(ServerLevel level, ServerPlayer p, double dmg) {
        Vec3 eye = p.getEyePosition();
        double reach = RUNE_REACH;
        BlockHitResult hit = level.clip(new ClipContext(
                eye, eye.add(p.getLookAngle().scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) {
            say(p, "Look at the ground to set a trap");
            return false;
        }
        Vec3 at = SpellKit.floor(level, hit.getLocation().add(0, .05, 0), 6);
        BlockPos under = BlockPos.containing(at).below();
        if (level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
            say(p, "Look at the ground to set a trap");
            return false;
        }
        int max = SpellUpgrades.add(Spell.RUNE_SNARE, COUNT, RUNE_MAX);
        var mine = RUNES.stream().filter(r -> r.owner == p).toList();
        for (int i = 0; i <= mine.size() - max; i++) RUNES.remove(mine.get(i)); // the oldest fade first
        RUNES.add(new Rune(
                p,
                at,
                dmg,
                SpellUpgrades.scale(Spell.RUNE_SNARE, AREA, RUNE_BURST),
                SpellUpgrades.perk(p, Spell.RUNE_SNARE, "Echo Rune") ? 2 : 1));
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + .1, at.z, 10, .4, .02, .4, .02);
        sound(level, at, SoundEvents.ENCHANTMENT_TABLE_USE, 1f, 1.3f);
        sound(level, at, SoundEvents.AMETHYST_BLOCK_CHIME, .8f, .9f);
        return true;
    }

    static void tickRunes() {
        for (Iterator<Rune> it = RUNES.iterator(); it.hasNext(); ) {
            var r = it.next();
            if (r.owner.isRemoved() || r.owner.level() != r.level) {
                it.remove();
                continue;
            }
            r.age++;
            if (r.age % 4 == 0) drawRune(r);
            if (r.age < RUNE_ARM) continue;
            boolean stepped = !r.level
                    .getEntitiesOfClass(
                            LivingEntity.class,
                            new AABB(r.pos, r.pos).inflate(.9, 0, .9).expandTowards(0, 1.2, 0),
                            e -> monster(r.owner, e) && e.getY() < r.pos.y + 1)
                    .isEmpty();
            if (!stepped) continue;
            burst(r);
            if (--r.bursts <= 0) it.remove();
            else r.age = 0; // re-arms
        }
    }

    private static void burst(Rune r) {
        var p = r.owner;
        boolean bind = SpellUpgrades.perk(p, Spell.RUNE_SNARE, "Binding Rune");
        boolean sear = SpellUpgrades.perk(p, Spell.RUNE_SNARE, "Searing Rune");
        for (var e : r.level.getEntitiesOfClass(
                LivingEntity.class, new AABB(r.pos, r.pos).inflate(r.radius, 2, r.radius), e -> monster(p, e))) {
            if (e.distanceToSqr(r.pos) > (r.radius + .5) * (r.radius + .5)) continue;
            Spells.hit(p, e, r.dmg, Spell.RUNE_SNARE);
            throwFrom(e, r.pos, .25, .75);
            if (bind) Perks.root(e, p, 60);
            if (sear) e.igniteForSeconds(4);
            if (SpellUpgrades.perk(p, Spell.RUNE_SNARE, "Frost Rune")) Perks.chill(e, p, 60, Frostbite.FULL);
            daze(e, p, DAZE_TICKS);
        }
        r.level.sendParticles(
                net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF4C0),
                r.pos.x,
                r.pos.y + .5,
                r.pos.z,
                1,
                0,
                0,
                0,
                0);
        r.level.sendParticles(ParticleTypes.END_ROD, r.pos.x, r.pos.y + .3, r.pos.z, 30, .3, .1, .3, .25);
        r.level.sendParticles(
                new DustParticleOptions(0xE7B8FF, 1.4f), r.pos.x, r.pos.y + .8, r.pos.z, 30, .5, .8, .5, 0);
        Spells.ring(r.level, r.pos.add(0, .2, 0), r.radius, new DustParticleOptions(0xE7B8FF, 1.2f));
        sound(r.level, r.pos, SoundEvents.EVOKER_CAST_SPELL, 1f, 1.5f);
        sound(r.level, r.pos, SoundEvents.AMETHYST_CLUSTER_BREAK, 1.2f, .8f);
    }

    /** The rune: a ring with a turning triangle in it and a bright heart, flat on the ground. */
    private static void drawRune(Rune r) {
        var ring = new DustParticleOptions(r.age >= RUNE_ARM ? 0xD9A8FF : 0x8A6AAA, .9f);
        Vec3 c = r.pos.add(0, .08, 0);
        Spells.ring(r.level, c, .85, ring);
        double turn = r.age * .05;
        for (int side = 0; side < 3; side++) {
            double a = turn + side * Math.PI * 2 / 3, b = a + Math.PI * 2 / 3;
            Vec3 from = c.add(Math.cos(a) * .7, 0, Math.sin(a) * .7), to = c.add(Math.cos(b) * .7, 0, Math.sin(b) * .7);
            SpellKit.line(r.level, from, to, new DustParticleOptions(0xFFE8A0, .7f), .22);
            if (r.age >= RUNE_ARM && r.age % 8 == 0)
                r.level.sendParticles(ParticleTypes.END_ROD, from.x, from.y + .02, from.z, 1, 0, 0, 0, 0);
        }
        if (r.age % 12 == 0) r.level.sendParticles(ParticleTypes.END_ROD, c.x, c.y + .05, c.z, 1, 0, .02, 0, 0);
    }

    // ---------------------------------------------------------------- Freedom

    private static MinecraftServer server;

    static final int FREEDOM_TICKS = 360;

    static final class Freedom {
        long until;
        final boolean rush, fierce, softFall;

        Freedom(long until, boolean rush, boolean fierce, boolean softFall) {
            this.until = until;
            this.rush = rush;
            this.fierce = fierce;
            this.softFall = softFall;
        }
    }

    static final Map<UUID, Freedom> FREEDOMS = new HashMap<>();

    /** For a while every spell costs no mana. */
    static boolean freedom(ServerLevel level, ServerPlayer p) {
        int ticks = SpellUpgrades.scaleInt(Spell.WELLSPRING, DURATION, FREEDOM_TICKS);
        FREEDOMS.put(
                p.getUUID(),
                new Freedom(
                        level.getGameTime() + ticks,
                        SpellUpgrades.perk(p, Spell.WELLSPRING, "Swift Hands"),
                        SpellUpgrades.perk(p, Spell.WELLSPRING, "Fierce Tempest"),
                        SpellUpgrades.perk(p, Spell.WELLSPRING, "Free Fall")));
        if (SpellUpgrades.perk(p, Spell.WELLSPRING, "Second Wind"))
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, 0, false, true, true));
        if (SpellUpgrades.perk(p, Spell.WELLSPRING, "Light Feet"))
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 0, false, true, true));
        if (SpellUpgrades.perk(p, Spell.WELLSPRING, "Spell Ward"))
            p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ticks, 0, false, true, true));
        if (SpellUpgrades.perk(p, Spell.WELLSPRING, "Mana Surge"))
            Scheduler.later(ticks, () -> {
                if (!p.isRemoved() && p.isAlive()) Progression.setMana(p, (float) Progression.maxMana(p));
            });
        atFeet(p, new DustParticleOptions(0xBFF3FF, 1.3f), ParticleTypes.CLOUD, 20);
        forOthers(p, ParticleTypes.END_ROD, p.position().add(0, 1, 0), 30, .5, .05);
        sound(level, p.position(), SoundEvents.BEACON_POWER_SELECT, 1f, 1.6f);
        sound(level, p.position(), SoundEvents.PHANTOM_FLAP, 1f, 1.3f);
        return true;
    }

    private static Freedom freedomOf(ServerPlayer p) {
        var w = FREEDOMS.get(p.getUUID());
        return w != null && p.level().getGameTime() < w.until ? w : null;
    }

    /** Whether {@code p}'s spells cost nothing just now (Freedom). */
    static boolean flowing(ServerPlayer p) {
        return freedomOf(p) != null;
    }

    /** A cooldown, halved while Swift Hands lasts. */
    static int cooldown(ServerPlayer p, Spell spell, int ticks) {
        var w = freedomOf(p);
        return w != null && w.rush && spell != Spell.WELLSPRING ? ticks / 2 : ticks;
    }

    static void tickFreedom() {
        for (Iterator<Map.Entry<UUID, Freedom>> it = FREEDOMS.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            var w = en.getValue();
            ServerPlayer p = player(en.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            long now = p.level().getGameTime();
            long left = w.until - now;
            if (left <= 0) {
                p.sendSystemMessage(Component.literal("Mana Tempest ends").withStyle(ChatFormatting.AQUA), true);
                it.remove();
                continue;
            }
            if (left % 20 == 0)
                p.sendSystemMessage(
                        Component.literal("Mana Tempest · " + left / 20 + " s, spells cost no mana")
                                .withStyle(ChatFormatting.AQUA),
                        true);
            if (now % 6 == 0)
                p.level()
                        .sendParticles(
                                new DustParticleOptions(0xBFF3FF, 1f),
                                p.getX(),
                                p.getY() + .2,
                                p.getZ(),
                                3,
                                .4,
                                .05,
                                .4,
                                0);
        }
    }

    private static ServerPlayer player(UUID id) {
        return server == null ? null : server.getPlayerList().getPlayer(id);
    }

    // ---------------------------------------------------------------- Time Stop

    static final double STOP_RADIUS = 24;
    static final int STOP_TICKS = 200;
    static final String HELD_TAG = "lightborn_time_stopped";

    static final class Held {
        final ServerPlayer owner;
        final Vec3 pos, motion;
        final boolean noAi, noGravity, stored;
        int ticks;
        double harm;

        Held(ServerPlayer owner, Entity e, int ticks, boolean stored) {
            this.owner = owner;
            this.pos = e.position();
            this.motion = e.getDeltaMovement();
            this.noAi = e instanceof Mob m && m.isNoAi();
            this.noGravity = e.isNoGravity();
            this.ticks = ticks;
            this.stored = stored;
        }
    }

    static final Map<Entity, Held> HELD = new IdentityHashMap<>();
    private static boolean releasing;

    /** Every monster and every shot in the air round you hangs still for a while. Bosses are slowed instead. */
    static boolean timeStop(ServerLevel level, ServerPlayer p) {
        double r = SpellUpgrades.scale(Spell.TIME_STOP, AREA, STOP_RADIUS);
        int ticks = SpellUpgrades.scaleInt(Spell.TIME_STOP, DURATION, STOP_TICKS);
        boolean stored = SpellUpgrades.perk(p, Spell.TIME_STOP, "Stored Blows");
        boolean bosses = SpellUpgrades.perk(p, Spell.TIME_STOP, "Frozen Bosses");
        boolean shatter = SpellUpgrades.perk(p, Spell.TIME_STOP, "Shattered Hour");
        if (SpellUpgrades.perk(p, Spell.TIME_STOP, "Time Runner"))
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1, false, false, true));
        if (SpellUpgrades.perk(p, Spell.TIME_STOP, "Timeless Rest"))
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, 0, false, false, true));
        int held = 0;
        for (var e : level.getEntities((Entity) null, p.getBoundingBox().inflate(r), e -> e.distanceTo(p) <= r)) {
            if (HELD.containsKey(e)) continue;
            if (e instanceof net.minecraft.world.entity.projectile.Projectile shot) {
                if (shot.getOwner() instanceof Player || e.getDeltaMovement().lengthSqr() < 1e-4) continue;
                hold(e, new Held(p, e, ticks, false));
                held++;
                continue;
            }
            if (!(e instanceof LivingEntity living) || !monster(p, living)) continue;
            if (mighty(living)) {
                if (!bosses) {
                    living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 3), p);
                    continue;
                }
                hold(e, new Held(p, e, ticks / 2, stored));
            } else hold(e, new Held(p, e, ticks, stored));
            held++;
        }
        if (shatter) SHATTER.put(p, Perks.flat(p, 10));
        // The hour stops: a grey wave runs out to the edge, a bell tolls low.
        for (double ring = 1; ring <= r; ring += 2.5)
            Spells.ring(level, p.position().add(0, .3, 0), ring, new DustParticleOptions(0xC9D6F0, 1.4f));
        forOthers(p, ParticleTypes.REVERSE_PORTAL, p.position().add(0, 1, 0), 40, .5, .2);
        atFeet(p, new DustParticleOptions(0xC9D6F0, 1.3f), ParticleTypes.END_ROD, 12);
        sound(level, p.position(), SoundEvents.BELL_RESONATE, 1.6f, .5f);
        sound(level, p.position(), SoundEvents.BEACON_DEACTIVATE, 1.2f, .6f);
        if (held == 0) say(p, "Time stops, but nothing here was moving against you");
        return true;
    }

    /** What Shattered Hour strikes each monster for when time runs on, by caster. */
    private static final Map<ServerPlayer, Double> SHATTER = new IdentityHashMap<>();

    private static void hold(Entity e, Held h) {
        HELD.put(e, h);
        e.addTag(HELD_TAG);
        e.setNoGravity(true);
        e.setDeltaMovement(Vec3.ZERO);
        e.needsSync = true;
        if (e instanceof Mob m) {
            m.setNoAi(true);
            m.getNavigation().stop();
            if (m instanceof Creeper c) c.setSwellDir(-1);
        }
    }

    static boolean held(Entity e) {
        return HELD.containsKey(e);
    }

    static void tickHeld() {
        if (HELD.isEmpty()) {
            SHATTER.clear();
            return;
        }
        for (var e : new ArrayList<>(HELD.keySet())) {
            var h = HELD.get(e);
            if (e.isRemoved() || --h.ticks < 0) {
                release(e);
                continue;
            }
            // Pinned where time caught it, knocked or not.
            if (e.position().distanceToSqr(h.pos) > 1e-4) e.setPos(h.pos.x, h.pos.y, h.pos.z);
            e.setDeltaMovement(Vec3.ZERO);
            // A pale ring stands still round each held thing, the mark that time has it.
            if ((h.ticks + e.getId()) % 4 == 0 && e.level() instanceof ServerLevel level) {
                Spells.ring(
                        level,
                        e.position().add(0, e.getBbHeight() * .55, 0),
                        Math.max(.35, e.getBbWidth() * .85),
                        new DustParticleOptions(0xC9D6F0, .9f));
                level.sendParticles(
                        ParticleTypes.END_ROD, e.getX(), e.getY() + e.getBbHeight() + .25, e.getZ(), 1, .05, 0, .05, 0);
            }
        }
        if (HELD.isEmpty()) SHATTER.clear();
    }

    private static void release(Entity e) {
        var h = HELD.remove(e);
        e.removeTag(HELD_TAG);
        if (h == null) return;
        e.setNoGravity(h.noGravity);
        if (e instanceof Mob m) m.setNoAi(h.noAi);
        if (e instanceof net.minecraft.world.entity.projectile.Projectile) push(e, h.motion);
        if (!(e instanceof LivingEntity living) || !living.isAlive() || h.owner.isRemoved()) return;
        double blow = (h.stored ? h.harm * .5 : 0) + SHATTER.getOrDefault(h.owner, 0.0);
        if (blow > 0) {
            releasing = true;
            try {
                Spells.hit(h.owner, living, blow, Spell.TIME_STOP);
            } finally {
                releasing = false;
            }
            if (e.level() instanceof ServerLevel level)
                level.sendParticles(ParticleTypes.CRIT, e.getX(), chest(e).y, e.getZ(), 14, .3, .4, .3, .3);
        }
    }
}
