package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.chest;
import static lightborn.SpellKit.line;
import static lightborn.SpellKit.sound;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.DURATION;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lightborn.SummonSpells.Thrall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Summons: allies that fight for you. Spirit Wolves, the Giant Golem, and the thralls of the Bone Staff and the
 * Book of Souls ({@link Souls}).
 */
public final class SummonSpells {
    private SummonSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case SPIRIT_WOLVES -> wolves(level, p, dmg, SpellUpgrades.special(spell)) && wolvesBranches(p, spell);
            case COLOSSUS -> colossus(level, p, dmg);
            case SOULS -> Souls.cast(p);
            default -> throw new IllegalArgumentException(spell + " is not a summons");
        };
    }

    static void tick(MinecraftServer server) {
        tickWolves();
        tickThralls();
        tickColossi();
    }

    /** Server stopping: wolves and thralls are saved with the world and pick up again on load. */
    static void clear() {
        SPIRITS.clear();
        THRALLS.clear();
        COLOSSI.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "wolves" -> SPIRITS.size();
            case "thralls" -> THRALLS.size();
            case "colossi" -> COLOSSI.size();
            default -> -1;
        };
    }

    /** Spirit Wolves' tier II branches, acting once as they are called: Pack Speed and Howl. */
    private static boolean wolvesBranches(ServerPlayer p, Spell spell) {
        if (SpellUpgrades.perk(p, spell, "Pack Speed"))
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, 600, 0, false, false, true));
        if (SpellUpgrades.perk(p, spell, "Howl"))
            for (var e : Perks.nearestMonsters(p, null, p.position(), 8, 32))
                HexSpells.frighten(p, e, 40, false, false);
        return true;
    }

    /**
     * Chunk load: a spirit wolf or thrall rejoins its master's pack, or, if the master is online and no longer counts
     * it (replaced while it was out of reach), lets go. True when the entity needs nothing more from the load hook.
     */
    static boolean onLoad(Entity e) {
        if (e instanceof SpiritWolf w) {
            if (w.getOwner() instanceof ServerPlayer owner
                    && !owner.getAttachedOrElse(ModData.BOUND_WOLVES, List.of()).contains(w.getUUID())) {
                vanish(w);
            } else if (!SPIRITS.contains(w)) {
                SPIRITS.add(w);
            }
            return true;
        }
        if (e instanceof Mob m) {
            UUID master = m.getAttached(ModData.THRALL_MASTER);
            if (master != null) {
                if (!THRALLS.containsKey(m)) THRALLS.put(m, new Thrall(master, null));
                m.addTag(THRALL_TAG);
            } else if (m.entityTags().contains(THRALL_TAG)) {
                m.removeTag(THRALL_TAG); // a thrall of the first version, which never outlived a reload
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- Spirit Wolves

    /** A player's pack never holds more than this (owner, 2026-11-10). */
    static final int WOLF_MAX = 2;

    static final double WOLF_SEEK = 16;
    /** Upgraded (Packmaster): the bite is this much stronger, and each wolf mends 1 health a second. */
    static final double PACK_BITE = 1.5;
    /** Set on a wolf or thrall raised by a tome with its mending upgrade (Packmaster, Tyrant). */
    static final String MEND_TAG = "lightborn_mends";

    /** Spirit wolves in loaded chunks; the master's BOUND_WOLVES list is the pack itself. */
    static final List<SpiritWolf> SPIRITS = new ArrayList<>();

    /**
     * Two glowing spirit wolves join you and stay until they fall: real tame wolves (a tame wolf's 40 health, they
     * sit, follow and teleport to you), that hunt the nearest monster within 16 blocks of you. Their bite is the
     * spell's damage, so they grow with you. Casting again calls the pack to your side, healed, with the bite brought
     * up to date, and raises a new wolf for each one that fell or is too far away (that one fades when next seen).
     */
    static boolean wolves(ServerLevel level, ServerPlayer p, double dmg, boolean upgraded) {
        double bite = Math.max(1, dmg * (upgraded ? PACK_BITE : 1));
        List<UUID> pack = new ArrayList<>(p.getAttachedOrElse(ModData.BOUND_WOLVES, List.of()));
        List<SpiritWolf> here = new ArrayList<>();
        for (Iterator<UUID> it = pack.iterator(); it.hasNext(); ) {
            Entity e = level.getEntity(it.next());
            if (e instanceof SpiritWolf w && w.isAlive() && w.distanceTo(p) < 64) here.add(w);
            else it.remove();
        }
        int i = 0;
        for (SpiritWolf w : here) {
            Vec3 at = besidePlayer(level, p, i++);
            w.snapTo(at.x, at.y, at.z, p.getYRot(), 0);
            w.getNavigation().stop();
            w.setOrderedToSit(false);
            w.setInSittingPose(false);
            w.setHealth(w.getMaxHealth());
            setBite(w, bite);
            level.sendParticles(ParticleTypes.SOUL, at.x, at.y + 0.5, at.z, 10, 0.3, 0.4, 0.3, 0.05);
        }
        int max = WOLF_MAX + (SpellUpgrades.perk(p, Spell.SPIRIT_WOLVES, "Third Wolf") ? 1 : 0);
        while (pack.size() < max) {
            SpiritWolf w = ModEntities.SPIRIT_WOLF.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (w == null) break;
            Command.reserve(p, w);
            Vec3 at = besidePlayer(level, p, i++);
            w.snapTo(at.x, at.y, at.z, p.getYRot(), 0);
            w.setTame(true, true);
            w.setOwner(p);
            setBite(w, bite);
            AttributeInstance speed = w.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0.4);
            pack.add(w.getUUID());
            p.setAttached(
                    ModData.BOUND_WOLVES,
                    List.copyOf(pack)); // before it joins the world: the load hook checks the pack
            level.addFreshEntity(w);
            level.sendParticles(ParticleTypes.SOUL, at.x, at.y + 0.5, at.z, 12, 0.3, 0.4, 0.3, 0.05);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.3, at.z, 16, 0.3, 0.2, 0.3, 0.05);
        }
        p.setAttached(ModData.BOUND_WOLVES, List.copyOf(pack));
        sound(level, p.position(), SoundEvents.SOUL_ESCAPE.value(), 2.0f, 0.8f);
        sound(level, p.position(), SoundEvents.EVOKER_PREPARE_SUMMON, 0.8f, 1.2f);
        return true;
    }

    private static void setBite(SpiritWolf w, double bite) {
        AttributeInstance a = w.getAttribute(Attributes.ATTACK_DAMAGE);
        if (a != null) a.setBaseValue(bite);
        if (SpellUpgrades.special(Spell.SPIRIT_WOLVES)) w.addTag(MEND_TAG);
        else w.removeTag(MEND_TAG);
    }

    /** A standing spot on a small circle around the player, {@code i}-th of a few. */
    private static Vec3 besidePlayer(ServerLevel level, ServerPlayer p, int i) {
        double a = Math.toRadians(p.getYRot()) + Math.PI / 2 + i * Math.PI * 2 / 3;
        Vec3 at = p.position().add(Math.cos(a) * 1.6, 0, Math.sin(a) * 1.6);
        BlockPos spot = EarthSpells.standing(level, at.add(0, 0.5, 0));
        return spot != null ? new Vec3(at.x, spot.getY(), at.z) : p.position();
    }

    /** A spirit wolf died: its place in the pack is free. */
    static void unbindWolf(ServerPlayer owner, UUID wolf) {
        List<UUID> pack = owner.getAttachedOrElse(ModData.BOUND_WOLVES, List.of());
        if (!pack.contains(wolf)) return;
        List<UUID> rest = new ArrayList<>(pack);
        rest.remove(wolf);
        owner.setAttached(ModData.BOUND_WOLVES, List.copyOf(rest));
    }

    static void tickWolves() {
        for (Iterator<SpiritWolf> it = SPIRITS.iterator(); it.hasNext(); ) {
            SpiritWolf w = it.next();
            if (w.isRemoved() || !w.isAlive()) {
                it.remove();
                continue;
            }
            if (!(w.getOwner() instanceof ServerPlayer owner))
                continue; // master away: an ordinary tame wolf till they return
            if (!owner.getAttachedOrElse(ModData.BOUND_WOLVES, List.of()).contains(w.getUUID())) {
                vanish(w); // replaced while out of reach
                it.remove();
                continue;
            }
            if ((w.tickCount + w.getId()) % 20 != 0) continue;
            if (w.entityTags().contains(MEND_TAG) && w.getHealth() < w.getMaxHealth()) w.heal(1);
            if (w.isOrderedToSit() || owner.level() != w.level()) continue;
            LivingEntity t = w.getTarget();
            if (t == null || !t.isAlive()) {
                Mob prey = nearestMonster(owner, owner.position(), WOLF_SEEK, null);
                if (prey != null && prey.distanceTo(w) < WOLF_SEEK * 1.5) w.setTarget(prey);
            }
        }
    }

    static void vanish(Wolf w) {
        if (!w.isRemoved() && w.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SOUL, w.getX(), w.getY() + 0.4, w.getZ(), 10, 0.3, 0.3, 0.3, 0.04);
            level.playSound(
                    null,
                    w.getX(),
                    w.getY(),
                    w.getZ(),
                    SoundEvents.SOUL_ESCAPE.value(),
                    SoundSource.PLAYERS,
                    0.8f,
                    1.2f);
        }
        w.discard();
    }

    /** The nearest living monster (not a thrall, not {@code not}) the owner's spells may hit, within r of c. */
    private static Mob nearestMonster(ServerPlayer owner, Vec3 c, double r, Entity not) {
        Mob best = null;
        double bestD = r * r;
        for (Mob m : owner.level()
                .getEntitiesOfClass(
                        Mob.class,
                        new AABB(c, c).inflate(r),
                        m -> m != not
                                && m instanceof Enemy
                                && m.isAlive()
                                && !THRALLS.containsKey(m)
                                && Spells.canHitDirect(owner, m))) {
            double d = m.distanceToSqr(c);
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    static int liveWolves() {
        return SPIRITS.size();
    }

    // ---------------------------------------------------------------- Thralls

    // ---- Thralls: bound monsters (the Book of Souls' spirits, the Bonecaller's skeletons) ----

    static final double THRALL_LURE = 8, THRALL_SEEK = 16;
    /** A player holds at most this many thralls (owner, 2026-11-10); binding one more frees the oldest. */
    static final int THRALL_MAX = 3;
    /** A thrall further than this from its master is brought to their side, the way a tame pet is. */
    static final double THRALL_LEASH = 24;
    /** A thrall hits other monsters this much harder, on top of its own level's scaling. */
    static final double THRALL_POWER = 2.0;

    /** A loaded thrall: its master's id, and the master's player object while it is known to be the live one. */
    static final class Thrall {
        final UUID master;
        ServerPlayer owner;

        Thrall(UUID master, ServerPlayer owner) {
            this.master = master;
            this.owner = owner;
        }

        /** The master if online (a relog makes a new player object, found again by id); null when away. */
        ServerPlayer owner(MinecraftServer server) {
            if (owner != null && !owner.isRemoved()) return owner;
            owner = server == null ? null : server.getPlayerList().getPlayer(master);
            return owner;
        }
    }

    static final Map<Mob, Thrall> THRALLS = new IdentityHashMap<>();

    static void bindThrall(ServerLevel level, ServerPlayer p, Mob m, boolean upgraded) {
        Command.reserve(p, m);
        List<UUID> bound = new ArrayList<>(p.getAttachedOrElse(ModData.BOUND_THRALLS, List.of()));
        bound.add(m.getUUID());
        p.setAttached(ModData.BOUND_THRALLS, List.copyOf(bound));
        if (upgraded) m.setHealth(m.getMaxHealth());
        m.setAttached(ModData.THRALL_MASTER, p.getUUID());
        m.addTag(THRALL_TAG);
        mark(m);
        m.setPersistenceRequired();
        m.setTarget(null);
        THRALLS.put(m, new Thrall(p.getUUID(), p));
        Vec3 at = chest(m);
        line(level, chest(p).add(p.getLookAngle().scale(1.5)), at, new DustParticleOptions(0xFFE7A8, 0.8f), 0.5);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 30, 0.4, 0.6, 0.4, 0.08);
        sound(level, at, SoundEvents.EVOKER_PREPARE_WOLOLO, 1.5f, 1.0f);
    }

    static boolean isThrall(Entity e) {
        return e instanceof Mob m && THRALLS.containsKey(m);
    }

    /** The player a thrall serves, if online, or null. */
    static ServerPlayer thrallOwner(Entity e) {
        Thrall t = e instanceof Mob m ? THRALLS.get(m) : null;
        return t == null ? null : t.owner(e.level().getServer());
    }

    static void tickThralls() {
        for (Iterator<Map.Entry<Mob, Thrall>> it = THRALLS.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Mob m = en.getKey();
            Thrall t = en.getValue();
            Long expires = m.getAttached(ModData.RAISED_UNTIL);
            if (expires != null && m.level().getGameTime() >= expires && !m.isRemoved()) m.discard();
            if (m.isRemoved() || !m.isAlive()) {
                // Died (not just unloaded with its chunk): its place among the master's thralls is free.
                var why = m.getRemovalReason();
                if (!m.isAlive() || (why != null && why.shouldDestroy())) {
                    ServerPlayer owner = t.owner(m.level().getServer());
                    if (owner != null) {
                        List<UUID> rest = new ArrayList<>(owner.getAttachedOrElse(ModData.BOUND_THRALLS, List.of()));
                        if (rest.remove(m.getUUID())) owner.setAttached(ModData.BOUND_THRALLS, List.copyOf(rest));
                    }
                }
                it.remove();
                continue;
            }
            ServerLevel level = (ServerLevel) m.level();
            ServerPlayer owner = t.owner(level.getServer());
            if (owner != null) Command.relink(owner, m);
            if (owner != null
                    && !owner.getAttachedOrElse(ModData.BOUND_THRALLS, List.of())
                            .contains(m.getUUID())) {
                release(m); // freed while out of reach
                it.remove();
                continue;
            }
            if (m.isOnFire() && !m.isInLava()) m.clearFire(); // the sun and flames no longer take it; lava still does
            if ((level.getGameTime() + m.getId()) % 6 == 0) {
                level.sendParticles(MOTE, m.getX(), m.getY() + m.getBbHeight() * .5, m.getZ(), 3, .3, .45, .3, 0);
                level.sendParticles(
                        ParticleTypes.END_ROD, m.getX(), m.getY() + m.getBbHeight() * .5, m.getZ(), 1, .3, .4, .3, .01);
            }
            LivingEntity target = m.getTarget();
            if (target != null
                    && (target instanceof Player
                            || !(target instanceof Enemy)
                            || THRALLS.containsKey(target)
                            || !target.isAlive())) {
                m.setTarget(null);
                target = null;
            }
            long now = level.getGameTime() + m.getId();
            boolean present = owner != null && owner.isAlive() && owner.level() == level;
            if (present && now % 10 == 0) {
                double far = m.distanceTo(owner);
                if (far > THRALL_LEASH) {
                    Vec3 at = besidePlayer(level, owner, 2);
                    m.snapTo(at.x, at.y, at.z, m.getYRot(), 0);
                    m.getNavigation().stop();
                    m.setTarget(null);
                    target = null;
                }
                if (target == null) {
                    Mob prey = nearestMonster(owner, m.position(), THRALL_SEEK, m);
                    if (prey != null && prey.distanceTo(owner) < THRALL_LEASH) {
                        m.setTarget(prey);
                        prey.setTarget(m);
                    } else if (far > 6) {
                        m.getNavigation().moveTo(owner, 1.1);
                    }
                }
                for (Mob o : level.getEntitiesOfClass(
                        Mob.class,
                        m.getBoundingBox().inflate(THRALL_LURE),
                        o -> o != m
                                && o instanceof Enemy
                                && !THRALLS.containsKey(o)
                                && (o.getTarget() == null || o.getTarget() instanceof Player))) {
                    o.setTarget(m);
                }
            }
            if (present
                    && now % 40 == 0
                    && m.getHealth() < m.getMaxHealth()
                    && m.entityTags().contains(MEND_TAG)) m.heal(1);
            if (now % 4 == 0) {
                double a = now * 0.3;
                for (int k = 0; k < 3; k++) {
                    double b = a + k * Math.PI * 2 / 3;
                    level.sendParticles(
                            ParticleTypes.ELECTRIC_SPARK,
                            m.getX() + Math.cos(b) * 0.4,
                            m.getY() + m.getBbHeight() + 0.35,
                            m.getZ() + Math.sin(b) * 0.4,
                            1,
                            0,
                            0,
                            0,
                            0);
                }
            }
        }
    }

    static void releaseCompanion(Mob m) {
        THRALLS.remove(m);
        release(m);
    }

    public static void converted(Mob original, Mob fresh) {
        UUID master = original.getAttached(ModData.THRALL_MASTER);
        if (master == null) return;
        Thrall tracking = THRALLS.remove(original);
        if (tracking == null) tracking = new Thrall(master, null);
        fresh.setAttached(ModData.THRALL_MASTER, master);
        fresh.setAttached(
                ModData.PREVIOUS_BOUND_ID, original.getAttachedOrElse(ModData.PREVIOUS_BOUND_ID, original.getUUID()));
        fresh.setPersistenceRequired();
        fresh.addTag(THRALL_TAG);
        mark(fresh);
        fresh.setTarget(null);
        MonsterInfo info = Monsters.info(original);
        double health = original.getHealth() / Math.max(1, original.getMaxHealth());
        if (info != null) Monsters.setRank(fresh, info.rank());
        String soul = original.getAttached(ModData.SOUL_KIND);
        if (soul != null) fresh.setAttached(ModData.SOUL_KIND, soul);
        Integer rank = original.getAttached(ModData.SOUL_RANK);
        if (rank != null) Souls.setRank(fresh, rank);
        fresh.setHealth((float) (fresh.getMaxHealth() * health));
        THRALLS.put(fresh, tracking);
        var owner = tracking.owner(fresh.level().getServer());
        if (owner != null) Command.relink(owner, fresh);
    }

    /**
     * Your minions read as yours at a glance (owner, 2026-11-16: too easy to mistake for foes; no names over heads, and
     * Light, not violet): clients draw them lit from within with a warm light tint, and a few motes of golden light
     * drift around them (in {@link #tickThralls}).
     */
    static void mark(Mob m) {
        m.setAttached(ModData.MINION, true);
    }

    private static void unmark(Mob m) {
        m.removeAttached(ModData.MINION);
    }

    /**
     * A raised spirit's light (owner, 2026-11-16: the violet crown is gone and the light must read at night). Dust
     * takes the world's light, so the motes pair it with self-lit sparks.
     */
    private static final DustParticleOptions MOTE = new DustParticleOptions(0xFFE7A8, 1.1f);

    /** Neither the master nor anything of theirs (another minion, an arrow, a spell) can hurt their own minion. */
    static boolean friendlyFire(Entity victim, net.minecraft.world.damagesource.DamageSource source) {
        UUID master = victim.getAttached(ModData.THRALL_MASTER);
        if (master == null || source.getEntity() == null) return false;
        var by = WardCombat.owner(source.getEntity());
        return by != null && by.getUUID().equals(master);
    }

    private static void release(Mob m) {
        if (m.getAttached(ModData.SOUL_KIND) != null) {
            m.discard();
            return;
        }
        if (m.getAttached(ModData.RAISED_UNTIL) != null) {
            m.discard();
            return;
        }
        m.removeTag(THRALL_TAG);
        m.removeAttached(ModData.THRALL_MASTER);
        unmark(m);
        m.setTarget(null);
        if (!m.isRemoved() && m.isAlive() && m.level() instanceof ServerLevel level) {
            level.sendParticles(
                    ParticleTypes.END_ROD,
                    m.getX(),
                    m.getY() + m.getBbHeight() * 0.6,
                    m.getZ(),
                    16,
                    0.3,
                    0.4,
                    0.3,
                    0.05);
            level.playSound(
                    null,
                    m.getX(),
                    m.getY(),
                    m.getZ(),
                    SoundEvents.ILLUSIONER_MIRROR_MOVE,
                    SoundSource.PLAYERS,
                    1.0f,
                    0.7f);
        }
    }
    /** A thrall (the master lives in {@link ModData#THRALL_MASTER}); dropped when it goes free. */
    static final String THRALL_TAG = "lightborn_thrall";

    // ---------------------------------------------------------------- Colossus

    static final int COLOSSUS_TICKS = 500, SLAM_PERIOD = 80;
    static final double COLOSSUS_SCALE = 2, SLAM_RADIUS = 4;

    static final class Giant {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final IronGolem golem;
        final double dmg, slam;
        final boolean guardian;
        final long until;
        int age;

        Giant(ServerPlayer owner, IronGolem golem, double dmg, double slam, boolean guardian, long until) {
            this.owner = owner;
            this.golem = golem;
            this.dmg = dmg;
            this.slam = slam;
            this.guardian = guardian;
            this.until = until;
        }
    }

    static final List<Giant> COLOSSI = new ArrayList<>();

    /** A giant golem of light rises beside you and fights for you, then sinks back into the ground. */
    static boolean colossus(ServerLevel level, ServerPlayer p, double dmg) {
        if (!Command.hasRoom(p, Balance.COMMAND_THRALL)) {
            say(p, "No room left in your command");
            return false;
        }
        var golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (golem == null) return false;
        golem.setPlayerCreated(true);
        golem.getAttribute(Attributes.SCALE).setBaseValue(COLOSSUS_SCALE);
        golem.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        golem.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(dmg);
        golem.setHealth(golem.getMaxHealth());
        golem.refreshDimensions();
        Vec3 look = p.getLookAngle().multiply(1, 0, 1);
        look = look.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : look.normalize();
        Vec3 spot = null;
        for (Vec3 at : List.of(
                p.position().add(look.scale(3.5)),
                p.position().add(look.scale(-3.5)),
                p.position().add(look.yRot(1.57f).scale(3.5)),
                p.position().add(look.yRot(-1.57f).scale(3.5)),
                p.position())) {
            Vec3 ground = SpellKit.floor(level, at.add(0, 1, 0), 4);
            golem.setPos(ground);
            if (!level.getBlockCollisions(golem, golem.getBoundingBox())
                    .iterator()
                    .hasNext()) {
                spot = ground;
                break;
            }
        }
        if (spot == null) {
            golem.discard();
            say(p, "No room for the Colossus to rise here");
            return false;
        }
        golem.setYRot(p.getYRot());
        golem.setYHeadRot(p.getYRot());
        int ticks = SpellUpgrades.scaleInt(Spell.COLOSSUS, DURATION, COLOSSUS_TICKS);
        long until = level.getGameTime() + ticks;
        golem.setAttached(ModData.RAISED_UNTIL, until);
        if (SpellUpgrades.perk(p, Spell.COLOSSUS, "Stoneheart"))
            golem.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 1, false, false, false));
        if (SpellUpgrades.perk(p, Spell.COLOSSUS, "Swift Golem"))
            golem.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1, false, false, false));
        if (SpellUpgrades.perk(p, Spell.COLOSSUS, "Iron Will"))
            p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 0, false, false, true));
        if (!level.addFreshEntity(golem)) return false;
        SummonSpells.bindThrall(level, p, golem, true);
        COLOSSI.add(new Giant(
                p,
                golem,
                dmg,
                SLAM_RADIUS * (SpellUpgrades.perk(p, Spell.COLOSSUS, "Earthshaker") ? 2 : 1),
                SpellUpgrades.perk(p, Spell.COLOSSUS, "Guardian"),
                until));
        Vec3 top = spot.add(0, golem.getBbHeight() / 2, 0);
        level.sendParticles(
                new net.minecraft.core.particles.BlockParticleOption(
                        ParticleTypes.BLOCK,
                        level.getBlockState(BlockPos.containing(spot).below())),
                spot.x,
                spot.y + .2,
                spot.z,
                60,
                1.2,
                .2,
                1.2,
                .2);
        level.sendParticles(ParticleTypes.END_ROD, top.x, top.y, top.z, 50, 1, 2, 1, .08);
        sound(level, spot, SoundEvents.IRON_GOLEM_REPAIR, 1.6f, .5f);
        sound(level, spot, SoundEvents.ANVIL_LAND, .8f, .5f);
        return true;
    }

    static void tickColossi() {
        for (Iterator<Giant> it = COLOSSI.iterator(); it.hasNext(); ) {
            var g = it.next();
            var golem = g.golem;
            if (golem.isRemoved() || !golem.isAlive()) {
                it.remove();
                continue;
            }
            var level = (ServerLevel) golem.level();
            g.age++;
            if (level.getGameTime() >= g.until) {
                level.sendParticles(
                        ParticleTypes.END_ROD, golem.getX(), chest(golem).y, golem.getZ(), 40, 1, 2, 1, .05);
                sound(level, golem.position(), SoundEvents.IRON_GOLEM_DEATH, 1f, .6f);
                golem.discard();
                it.remove();
                continue;
            }
            if (g.age % 5 == 0)
                level.sendParticles(
                        new DustParticleOptions(0xFFE7A8, 1.6f),
                        golem.getX(),
                        golem.getY() + golem.getBbHeight() * .6,
                        golem.getZ(),
                        3,
                        .9,
                        1.4,
                        .9,
                        0);
            if (g.guardian && g.age % 20 == 0 && !g.owner.isRemoved())
                for (var m : level.getEntitiesOfClass(
                        Mob.class,
                        g.owner.getBoundingBox().inflate(12),
                        m -> m instanceof Enemy && m.getTarget() == g.owner && !mighty(m))) m.setTarget(golem);
            if (g.age % SLAM_PERIOD != 0 || g.owner.isRemoved()) continue;
            var near = Perks.nearestMonsters(g.owner, null, golem.position(), g.slam, 16);
            if (near.isEmpty()) continue;
            // The slam: both fists into the ground, a ring of broken earth, everything near thrown back.
            for (var e : near) {
                Spells.hit(g.owner, e, g.dmg * .5, Spell.COLOSSUS, g.cast);
                throwFrom(e, golem.position(), 1.2, .55);
            }
            for (double r = 1; r <= g.slam; r += 1)
                Spells.ring(
                        level,
                        golem.position().add(0, .15, 0),
                        r,
                        new net.minecraft.core.particles.BlockParticleOption(
                                ParticleTypes.BLOCK,
                                level.getBlockState(golem.blockPosition().below())));
            level.sendParticles(ParticleTypes.EXPLOSION, golem.getX(), golem.getY() + .3, golem.getZ(), 2, 1, 0, 1, 0);
            sound(level, golem.position(), SoundEvents.IRON_GOLEM_ATTACK, 1.6f, .5f);
            sound(level, golem.position(), SoundEvents.GENERIC_EXPLODE.value(), .6f, .7f);
        }
    }
}
