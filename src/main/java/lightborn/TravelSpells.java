package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.chest;
import static lightborn.SpellKit.knockResist;
import static lightborn.SpellKit.sound;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.SpellUpgrades.Stat.REACH;
import static lightborn.Spells.sound;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Travel spells: moving yourself and others. Blink, Tether, Transpose, Moongate, Homeward, Updraft and Comet
 * Dash (Flight lives in {@link StaffFlight}).
 */
public final class TravelSpells {
    private TravelSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case BLINK -> {
                boolean moved = blink(level, p, SpellUpgrades.scale(spell, REACH, 12));
                if (moved) StoryBosses.blinked(p);
                yield moved;
            }
            case TETHER -> tether(level, p);
            case TRANSPOSE -> transpose(level, p);
            case MOONGATE -> moongate(level, p);
            case HOMEWARD -> {
                homeward(level, p, homewardTicks());
                yield true;
            }
            case UPDRAFT -> {
                // The gust goes first, while the monsters are still round you on the ground.
                updraftBranches(p, spell);
                yield updraft(level, p, SpellUpgrades.special(spell));
            }
            case COMET_DASH -> comet(level, p, dmg);
            case FLIGHT -> StaffFlight.cast(p);
            default -> throw new IllegalArgumentException(spell + " is not a travel spell");
        };
    }

    static void tick(MinecraftServer server) {
        tickPulls();
        tickGates();
        tickDashes();
    }

    static void clear() {
        PULLS.clear();
        SAFE.clear();
        GATES.forEach(pair -> {
            SpellKit.drop(pair.a.portal);
            SpellKit.drop(pair.b.portal);
        });
        GATES.clear();
        DASHES.clear();
        DASH_SAFE.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "pulls" -> PULLS.size();
            case "gates" -> GATES.size();
            case "dashes" -> DASHES.size();
            default -> -1;
        };
    }

    /** Updraft's tier II branches, acting once as it is cast: Cloud Step, and Gust blowing monsters away. */
    private static void updraftBranches(ServerPlayer p, Spell spell) {
        if (SpellUpgrades.perk(p, spell, "Cloud Step"))
            p.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 200, 1, false, false, true));
        if (SpellUpgrades.perk(p, spell, "Gust"))
            for (var e : Perks.nearestMonsters(p, null, p.position(), 3, 32)) {
                if (e instanceof net.minecraft.world.entity.Mob m && Cage.mighty(m)) continue;
                Vec3 away = e.position().subtract(p.position()).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                double keep = 1 - knockResist(e);
                e.setDeltaMovement(away.scale(1.2 * keep).add(0, .4 * keep, 0));
                e.needsSync = true;
            }
    }

    // ---------------------------------------------------------------- Homeward

    static int homewardTicks() {
        return SpellUpgrades.scaleInt(Spell.HOMEWARD, SpellUpgrades.Stat.DURATION, 200);
    }

    /**
     * After {@code delay} ticks of swirling light, carries the caster to where they would respawn: their bed or anchor
     * (an anchor's charge is not spent), or the world spawn (the hut) when there is none or it is gone.
     */
    static void homeward(ServerLevel level, ServerPlayer p, int delay) {
        sound(level, p, SoundEvents.PORTAL_TRIGGER, 0.4f, 1.6f);
        // A small countdown above the hotbar, one number a second: 10, 9, 8... (owner, 2026-11-16).
        for (int left = delay; left > 0; left -= 20) {
            int seconds = (left + 19) / 20;
            Scheduler.later(delay - left, () -> {
                if (!p.isRemoved() && p.isAlive())
                    p.sendSystemMessage(
                            Component.literal("Homeward · " + seconds).withStyle(ChatFormatting.LIGHT_PURPLE), true);
            });
        }
        for (int t = 0; t < delay; t += 4) {
            int tt = t;
            Scheduler.later(t, () -> {
                if (p.isRemoved()) return;
                double a = tt * 0.5;
                p.level()
                        .sendParticles(
                                ParticleTypes.END_ROD,
                                p.getX() + Math.cos(a) * 0.8,
                                p.getY() + tt / (double) delay * 2,
                                p.getZ() + Math.sin(a) * 0.8,
                                2,
                                0,
                                0,
                                0,
                                0);
                p.level().sendParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 6, 0.4, 0.6, 0.4, 0.4);
            });
        }
        // Return Trip: the free cast inside the window goes back to where the last trip home began.
        Away back = Perks.free(p, Spell.HOMEWARD) ? AWAY.get(p.getUUID()) : null;
        Scheduler.later(delay, () -> {
            if (p.isRemoved() || !p.isAlive()) return;
            var transition = back != null
                    ? new TeleportTransition(
                            back.level(),
                            back.pos(),
                            Vec3.ZERO,
                            p.getYRot(),
                            p.getXRot(),
                            TeleportTransition.DO_NOTHING)
                    : p.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
            ServerLevel from = p.level();
            Vec3 left = p.position();
            // Fellowship: players beside you and your spirit wolves come along.
            List<Entity> company = new ArrayList<>();
            if (SpellUpgrades.perk(p, Spell.HOMEWARD, "Fellowship")) {
                company.addAll(from.getEntitiesOfClass(
                        ServerPlayer.class, p.getBoundingBox().inflate(4), o -> o != p && o.isAlive()));
                company.addAll(from.getEntitiesOfClass(
                        SpiritWolf.class, p.getBoundingBox().inflate(24), w -> w.isAlive() && w.getOwner() == p));
            }
            from.sendParticles(
                    ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF4C0),
                    p.getX(),
                    p.getY() + 1,
                    p.getZ(),
                    1,
                    0,
                    0,
                    0,
                    0);
            from.playSound(
                    null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 0.8f);
            p.teleport(transition);
            ServerLevel to = p.level();
            for (Entity e : company)
                e.teleport(new TeleportTransition(
                        to, p.position(), Vec3.ZERO, e.getYRot(), e.getXRot(), TeleportTransition.DO_NOTHING));
            if (back == null && SpellUpgrades.perk(p, Spell.HOMEWARD, "Return Trip")) {
                AWAY.put(p.getUUID(), new Away(from, left));
                Perks.grantFree(p, Spell.HOMEWARD, Perks.RETURN_TICKS);
            }
            if (SpellUpgrades.perk(p, Spell.HOMEWARD, "Safe Harbour")) {
                p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1, false, false, true));
                p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 1, false, false, true));
            }
            to.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 40, 0.4, 0.8, 0.4, 0.08);
            to.playSound(
                    null,
                    p.getX(),
                    p.getY(),
                    p.getZ(),
                    SoundEvents.AMETHYST_BLOCK_RESONATE,
                    SoundSource.PLAYERS,
                    1.2f,
                    1.2f);
            if (transition.missingRespawnBlock()) {
                p.sendSystemMessage(
                        Component.literal("The Light returned you to your first home")
                                .withStyle(ChatFormatting.GRAY),
                        true);
            }
        });
    }

    /** Return Trip: where each player's last trip home began. */
    private record Away(ServerLevel level, Vec3 pos) {}

    private static final java.util.Map<java.util.UUID, Away> AWAY = new java.util.concurrent.ConcurrentHashMap<>();

    // ---------------------------------------------------------------- Blink

    static boolean blink(ServerLevel level, ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        BlockHitResult hr = level.clip(new ClipContext(
                eye, eye.add(look.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 dest = hr.getType() == HitResult.Type.MISS
                ? eye.add(look.scale(range))
                : hr.getLocation().subtract(look.scale(0.8));
        // Feet position: step back along the path until the player fits.
        Vec3 feet = dest.subtract(0, p.getEyeHeight(), 0);
        for (int i = 0; i < 12; i++) {
            AABB box = p.getBoundingBox().move(feet.subtract(p.position()));
            if (level.noCollision(p, box)) break;
            feet = feet.subtract(look.scale(0.6)).add(0, 0.25, 0);
        }
        AABB box = p.getBoundingBox().move(feet.subtract(p.position()));
        if (!level.noCollision(p, box) || feet.distanceToSqr(p.position()) < 4) {
            p.sendSystemMessage(Component.literal("No room to blink there").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        Vec3 from = p.position();
        level.sendParticles(ParticleTypes.PORTAL, from.x, from.y + 1, from.z, 40, 0.3, 0.6, 0.3, 0.6);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1, from.z, 20, 0.3, 0.6, 0.3, 0.05);
        p.teleportTo(feet.x, feet.y, feet.z);
        p.resetFallDistance();
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 1.3f);
        level.playSound(null, feet.x, feet.y, feet.z, SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS, 0.8f, 1.3f);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, feet.x, feet.y + 1, feet.z, 40, 0.3, 0.6, 0.3, 0.1);
        if (SpellUpgrades.perk(p, Spell.BLINK, "Phase Burst")) {
            for (LivingEntity e : level.getEntitiesOfClass(
                    LivingEntity.class,
                    p.getBoundingBox().inflate(4),
                    e -> e instanceof Enemy && Spells.canHitArea(p, e))) {
                Vec3 away = e.position().subtract(feet).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : away.normalize();
                e.setDeltaMovement(
                        away.scale(1.1 * (1 - SpellKit.knockResist(e))).add(0, .4, 0));
                e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1), p);
            }
            Bosses.ring(level, feet.add(0, .2, 0), 4, ParticleTypes.REVERSE_PORTAL, 40);
            level.playSound(
                    null,
                    feet.x,
                    feet.y,
                    feet.z,
                    SoundEvents.WIND_CHARGE_BURST.value(),
                    SoundSource.PLAYERS,
                    .8f,
                    1.2f);
        }
        if (SpellUpgrades.perk(p, Spell.BLINK, "Shadow Step")) {
            p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, false, false, true));
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 1, false, false, true));
        }
        return true;
    }

    // ---------------------------------------------------------------- Updraft

    /** Launch speeds: 3.12 climbs about 42 blocks, four times the first version's 1.4 (owner, 2026-11-10); 3.95 about 62. */
    static final double UPDRAFT_LIFT = 3.12, UPDRAFT_HIGH = 3.95, UPDRAFT_SHOVE = 4;

    static final int UPDRAFT_FALL = 400, UPDRAFT_FALL_LONG = 600;

    /**
     * How much of a full charge the cast was released at (0.15 to 1; StaffItem sets it around the cast). A climb's
     * height goes with the square of the launch speed, so the speed takes the square root: half a charge, half the
     * height.
     */
    static float updraftCharge = 1f;

    /**
     * A pillar of wind throws you about 40 blocks up (upgraded about 60) and a little forward, scatters monsters
     * within 4 blocks, and lets you drift down on Slow Falling for 20 s (upgraded 30). For cliffs, walls, escapes
     * and a look around.
     */
    static boolean updraft(ServerLevel level, ServerPlayer p, boolean upgraded) {
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        boolean tailwind = SpellUpgrades.perk(p, Spell.UPDRAFT, "Tailwind");
        flat = flat.lengthSqr() < 1e-4 ? Vec3.ZERO : flat.normalize().scale(tailwind ? 2.4 : 0.6);
        double lift = (upgraded ? UPDRAFT_HIGH : UPDRAFT_LIFT) * Math.sqrt(updraftCharge);
        p.setDeltaMovement(flat.x * updraftCharge, lift, flat.z * updraftCharge);
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
        p.resetFallDistance();
        int fall = SpellUpgrades.scaleInt(
                Spell.UPDRAFT, SpellUpgrades.Stat.DURATION, upgraded ? UPDRAFT_FALL_LONG : UPDRAFT_FALL);
        p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, fall, 0), p);
        // Lift Friends: other players and your pets beside you go up too.
        if (SpellUpgrades.perk(p, Spell.UPDRAFT, "Lift Friends"))
            for (LivingEntity f : level.getEntitiesOfClass(
                    LivingEntity.class,
                    p.getBoundingBox().inflate(4),
                    f -> f != p
                            && f.isAlive()
                            && (f instanceof ServerPlayer
                                    || f instanceof net.minecraft.world.entity.OwnableEntity own
                                            && own.getOwner() == p))) {
                f.setDeltaMovement(flat.x * updraftCharge, lift * .9, flat.z * updraftCharge);
                f.resetFallDistance();
                f.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, fall, 0), p);
                if (f instanceof ServerPlayer friend)
                    friend.connection.send(new ClientboundSetEntityMotionPacket(friend));
                else f.needsSync = true;
            }
        // Thunder Landing: once you are back on the ground, it slams out round you.
        if (SpellUpgrades.perk(p, Spell.UPDRAFT, "Thunder Landing")) {
            boolean[] state = {false, false}; // left the ground, landed
            for (int t = 4; t <= fall + 200; t += 2)
                Scheduler.later(t, () -> {
                    if (state[1] || !p.isAlive() || p.level() != level) return;
                    if (!p.onGround()) state[0] = true;
                    else if (state[0]) {
                        state[1] = true;
                        Spells.withSpell(
                                Spell.UPDRAFT,
                                () -> Perks.burst(
                                        p, p.position().add(0, .5, 0), 4, Perks.flat(p, 14), ParticleTypes.CLOUD));
                        for (LivingEntity e : level.getEntitiesOfClass(
                                LivingEntity.class,
                                p.getBoundingBox().inflate(4),
                                e -> e instanceof Enemy && Spells.canHitArea(p, e)))
                            e.setDeltaMovement(e.getDeltaMovement().add(0, .7 * (1 - knockResist(e)), 0));
                        sound(level, p.position(), SoundEvents.MACE_SMASH_GROUND_HEAVY, 1.2f, .8f);
                    }
                });
        }
        Vec3 c = p.position();
        for (LivingEntity e : level.getEntitiesOfClass(
                LivingEntity.class,
                p.getBoundingBox().inflate(UPDRAFT_SHOVE),
                e -> e instanceof Enemy && Spells.canHitArea(p, e))) {
            Vec3 away = e.position().subtract(c).multiply(1, 0, 1);
            away = away.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : away.normalize();
            double k = 1 - knockResist(e);
            e.setDeltaMovement(away.scale(1.2 * k).add(0, 0.5 * k, 0));
        }
        level.sendParticles(ParticleTypes.GUST, c.x, c.y + 0.5, c.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.CLOUD, c.x, c.y + 0.2, c.z, 40, 1.2, 0.1, 1.2, 0.08);
        Bosses.ring(level, c.add(0, 0.2, 0), 2.0, ParticleTypes.SMALL_GUST, 12);
        sound(level, c, SoundEvents.WIND_CHARGE_BURST.value(), 1.2f, 0.8f);
        sound(level, c, SoundEvents.BREEZE_JUMP, 1.0f, 1.0f);
        for (int i = 2; i <= 24; i += 2) {
            Scheduler.later(i, () -> {
                if (p.isAlive())
                    p.level()
                            .sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY(), p.getZ(), 4, 0.25, 0.1, 0.25, 0.02);
            });
        }
        return true;
    }

    // ---------------------------------------------------------------- Tether

    static final double TETHER_REACH = 24, TETHER_SPEED = 1.1, TETHER_SWIFT = 2.0;
    static final int TETHER_TICKS = 60, TETHER_AGAIN = 60;

    static final class Pull {
        final ServerPlayer owner;
        final ServerLevel level;
        final LivingEntity who;
        final Vec3 anchor; // null: a creature pulled to the owner
        final double speed;
        int life = TETHER_TICKS, stuck;
        Vec3 last;

        Pull(ServerPlayer owner, LivingEntity who, Vec3 anchor, double speed) {
            this.owner = owner;
            this.level = owner.level();
            this.who = who;
            this.anchor = anchor;
            this.speed = speed;
            this.last = who.position();
        }
    }

    static final List<Pull> PULLS = new ArrayList<>();
    /** Players a tether has just carried: no fall hurts them for a moment. */
    static final Map<ServerPlayer, Integer> SAFE = new IdentityHashMap<>();

    /** A line of light to what you look at: a creature is pulled to you, else you are pulled to the block. */
    static boolean tether(ServerLevel level, ServerPlayer p) {
        double reach = SpellUpgrades.scale(Spell.TETHER, REACH, TETHER_REACH);
        double speed = SpellUpgrades.special(Spell.TETHER) ? TETHER_SWIFT : TETHER_SPEED;
        var caught = new ArrayList<LivingEntity>();
        // Lasso (owner, 2026-11-22: pulling creatures is an upgrade, not the base spell).
        var first = SpellUpgrades.perk(p, Spell.TETHER, "Lasso") ? Spells.lookTarget(p, reach) : null;
        if (first != null && !(first instanceof Mob m && Cage.mighty(m))) caught.add(first);
        if (SpellUpgrades.perk(p, Spell.TETHER, "Double Line") && !Perks.free(p, Spell.TETHER))
            Perks.grantFree(p, Spell.TETHER, TETHER_AGAIN);
        Vec3 from = hand(p);
        if (!caught.isEmpty()) {
            PULLS.removeIf(u -> caught.contains(u.who));
            for (var e : caught) PULLS.add(new Pull(p, e, null, speed));
        } else {
            Vec3 eye = p.getEyePosition();
            BlockHitResult hit = level.clip(new ClipContext(
                    eye,
                    eye.add(p.getLookAngle().scale(reach)),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    p));
            if (hit.getType() == HitResult.Type.MISS) {
                p.sendSystemMessage(
                        Component.literal("Nothing in reach to tether to").withStyle(ChatFormatting.GRAY), true);
                return false;
            }
            PULLS.removeIf(u -> u.who == p);
            PULLS.add(new Pull(p, p, hit.getLocation(), speed));
            level.sendParticles(
                    ParticleTypes.END_ROD,
                    hit.getLocation().x,
                    hit.getLocation().y,
                    hit.getLocation().z,
                    8,
                    .15,
                    .15,
                    .15,
                    .05);
        }
        sound(level, from, SoundEvents.FISHING_BOBBER_THROW, 1f, 1.3f);
        sound(level, from, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.6f);
        return true;
    }

    static void tickPulls() {
        for (var it = SAFE.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            e.getKey().resetFallDistance();
            if (e.setValue(e.getValue() - 1) <= 1 || e.getKey().isRemoved()) it.remove();
        }
        for (Iterator<Pull> it = PULLS.iterator(); it.hasNext(); ) {
            var u = it.next();
            var p = u.owner;
            if (p.isRemoved()
                    || !p.isAlive()
                    || p.level() != u.level
                    || u.who.isRemoved()
                    || !u.who.isAlive()
                    || --u.life < 0) {
                it.remove();
                continue;
            }
            u.stuck = u.who.position().distanceToSqr(u.last) < .0025 ? u.stuck + 1 : 0;
            u.last = u.who.position();
            if (u.anchor != null) {
                Vec3 to = u.anchor.subtract(p.position().add(0, p.getBbHeight() * .5, 0));
                double d = to.length();
                boolean soft = SpellUpgrades.perk(p, Spell.TETHER, "Soft Landing");
                if (soft) SAFE.put(p, 40);
                if (d < 1.5 || u.stuck > 5 && u.life < TETHER_TICKS - 4) {
                    Vec3 dir = d < 1e-4 ? Vec3.ZERO : to.normalize();
                    pushPlayer(
                            p,
                            SpellUpgrades.perk(p, Spell.TETHER, "Slingshot")
                                    ? dir.scale(1.3).add(0, .65, 0)
                                    : dir.scale(.15).add(0, .42, 0));
                    if (soft) SAFE.put(p, 60);
                    if (SpellUpgrades.perk(p, Spell.TETHER, "Running Start"))
                        p.addEffect(new MobEffectInstance(MobEffects.SPEED, 80, 0, false, false, true));
                    sound(u.level, p.position(), SoundEvents.FISHING_BOBBER_RETRIEVE, 1f, 1.2f);
                    it.remove();
                    continue;
                }
                pushPlayer(p, to.normalize().scale(Math.min(u.speed, d * .5 + .3)));
                SpellKit.line(u.level, ahead(p), u.anchor, new DustParticleOptions(0xFFE7A0, .7f), .4);
            } else {
                var e = u.who;
                Vec3 look = p.getLookAngle().multiply(1, 0, 1);
                look = look.lengthSqr() < 1e-4 ? Vec3.ZERO : look.normalize();
                Vec3 to = p.position().add(look.scale(1.6)).subtract(e.position());
                double d = to.length();
                if (d < 1.8 || u.stuck > 5 && u.life < TETHER_TICKS - 4) {
                    e.setDeltaMovement(Vec3.ZERO);
                    e.needsSync = true;
                    it.remove();
                    continue;
                }
                e.setDeltaMovement(to.normalize()
                        .scale(Math.min(u.speed * .9, d * .5 + .3))
                        .add(0, .04, 0));
                e.needsSync = true;
                e.resetFallDistance();
                SpellKit.line(u.level, ahead(p), chest(e), new DustParticleOptions(0xFFE7A0, .7f), .4);
            }
        }
    }

    // ---------------------------------------------------------------- Transpose

    static final double SWAP_REACH = 32;
    static final int SWAP_AGAIN = 80;

    /** You and the creature you look at trade places. */
    static boolean transpose(ServerLevel level, ServerPlayer p) {
        var e = Spells.lookTarget(p, SpellUpgrades.scale(Spell.TRANSPOSE, REACH, SWAP_REACH));
        if (e == null || mighty(e) || e.isPassenger() || e.isVehicle()) {
            say(
                    p,
                    e != null && mighty(e)
                            ? "Transpose does nothing to bosses"
                            : "Look at a creature to trade places with");
            return false;
        }
        Vec3 mine = p.position(), theirs = e.position();
        float yRot = p.getYRot(), xRot = p.getXRot();
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, mine.x, mine.y + 1, mine.z, 30, .3, .6, .3, .05);
        forOthers(p, ParticleTypes.REVERSE_PORTAL, theirs.add(0, 1, 0), 30, .4, .05);
        SpellKit.line(level, mine.add(0, 1, 0), theirs.add(0, 1, 0), new DustParticleOptions(0xBFD8FF, .7f), .5);
        // Facing what it was looking at, now from the other side: turned round to face the creature again.
        p.teleportTo(level, theirs.x, theirs.y, theirs.z, Set.of(), yRot + 180, xRot, false);
        e.teleportTo(mine.x, mine.y, mine.z);
        atFeet(p, new DustParticleOptions(0xBFD8FF, 1f), ParticleTypes.REVERSE_PORTAL, 16);
        push(p, Vec3.ZERO);
        push(e, Vec3.ZERO);
        p.resetFallDistance();
        e.resetFallDistance();
        if (monster(p, e)) {
            if (SpellUpgrades.perk(p, Spell.TRANSPOSE, "Dazing Swap")) {
                e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 3), p);
                e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 1), p);
                if (e instanceof Mob m) m.setTarget(null);
            }
            if (SpellUpgrades.perk(p, Spell.TRANSPOSE, "Shocking Swap")) {
                Spells.hit(p, e, Perks.flat(p, 6), Spell.TRANSPOSE);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), chest(e).y, e.getZ(), 12, .3, .4, .3, .2);
            }
        }
        if (SpellUpgrades.perk(p, Spell.TRANSPOSE, "Swap Ward"))
            p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 40, 1, false, false, true));
        if (SpellUpgrades.perk(p, Spell.TRANSPOSE, "Swift Swap"))
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 1, false, false, true));
        if (SpellUpgrades.perk(p, Spell.TRANSPOSE, "Mending Swap")) p.heal(2);
        if (SpellUpgrades.perk(p, Spell.TRANSPOSE, "Return Swap") && !Perks.free(p, Spell.TRANSPOSE))
            Perks.grantFree(p, Spell.TRANSPOSE, SWAP_AGAIN);
        sound(level, mine, SoundEvents.CHORUS_FRUIT_TELEPORT, 1f, 1.4f);
        sound(level, theirs, SoundEvents.CHORUS_FRUIT_TELEPORT, 1f, 1.1f);
        return true;
    }

    // ---------------------------------------------------------------- Moongate

    static final double GATE_REACH = 50, GATE_AHEAD = 2.5, GATE_STEP = 1.5;
    // 50 blocks (owner, 2026-11-22)
    static final int GATE_TICKS = 1200;
    /** The portal's size: an upright oval of swirling green light you cannot see through (owner, 2026-11-22). */
    public static final float PORTAL_WIDE = 1.6f, PORTAL_TALL = 2.3f;
    /** The name that marks a gate's empty display for the client, which draws the portal there (PortalView). */
    public static final String PORTAL_NAME = "lightborn:moongate", PORTAL_CLOSING = "lightborn:moongate_closing";

    static final class Gate {
        final ServerLevel level;
        final Vec3 pos;
        /** The way the gate's face looks: a traveller crossing it keeps going the same way out of the other gate. */
        float facing;

        Gate other;
        Display.ItemDisplay portal;
        /** What came out of this gate and has not stepped clear of it yet: it may not go straight back. */
        final Set<UUID> arrived = new HashSet<>();

        Gate(ServerLevel level, Vec3 pos, float facing) {
            this.level = level;
            this.pos = pos;
            this.facing = facing;
        }

        /** The gate's face direction, flat. */
        Vec3 normal() {
            double yaw = Math.toRadians(facing);
            return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        }

        /**
         * Where a traveller comes out: a step out on {@code side} (1 the gate's face side, -1 its back), as through a
         * doorway; the other side when that one is walled, and the gate itself when both are.
         */
        Vec3 exit(double side) {
            for (double s : new double[] {side, -side}) {
                Vec3 step = pos.add(normal().scale(s * GATE_STEP));
                if (roomFor(level, step)) return step;
            }
            return pos;
        }

        AABB room() {
            return new AABB(pos.x - .6, pos.y, pos.z - .6, pos.x + .6, pos.y + 1.9, pos.z + .6);
        }
    }

    static final class Pair {
        final ServerPlayer owner;
        final Gate a, b;
        int ticks, age;

        Pair(ServerPlayer owner, Gate a, Gate b, int ticks) {
            this.owner = owner;
            this.a = a;
            this.b = b;
            this.ticks = ticks;
            a.other = b;
            b.other = a;
        }
    }

    static final List<Pair> GATES = new ArrayList<>();

    /** Opens a gate just ahead of you and another where you look; what steps into one comes out of the other. */
    static boolean moongate(ServerLevel level, ServerPlayer p) {
        Vec3 ahead = flat(p.getLookAngle());
        Vec3 near =
                SpellKit.floor(level, p.position().add(ahead.scale(GATE_AHEAD)).add(0, .5, 0), 3);
        if (!roomFor(level, near)) near = SpellKit.floor(level, p.position().add(0, .5, 0), 3);
        Gate a = new Gate(level, near, p.getYRot()), b;
        if (SpellUpgrades.perk(p, Spell.MOONGATE, "Homegate")) {
            TeleportTransition home = LightHome.destination(p, TeleportTransition.DO_NOTHING);
            b = new Gate(home.newLevel(), home.position(), home.yRot());
        } else {
            Vec3 eye = p.getEyePosition();
            double reach = SpellUpgrades.scale(Spell.MOONGATE, REACH, GATE_REACH);
            BlockHitResult hit = level.clip(new ClipContext(
                    eye,
                    eye.add(p.getLookAngle().scale(reach)),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    p));
            if (hit.getType() == HitResult.Type.MISS) {
                say(p, "Look at a place within " + (int) reach + " blocks to open the far gate");
                return false;
            }
            BlockPos cell = hit.getBlockPos().relative(hit.getDirection());
            Vec3 far = SpellKit.floor(level, Vec3.atBottomCenterOf(cell).add(0, .1, 0), 6);
            if (!roomFor(level, far) && roomFor(level, far.add(0, 1, 0))) far = far.add(0, 1, 0);
            if (!roomFor(level, far) || far.distanceTo(near) < 3) {
                say(p, "No room for a gate there");
                return false;
            }
            // Both gates face the way from the near one to the far one, so walking on through the near gate carries
            // you on out of the far one (owner, 2026-11-22: you came out of the wrong side). A far gate set against a
            // wall turns round, opening out of the wall.
            float on = yawTo(near, far);
            a.facing = on;
            b = new Gate(level, far, on);
            if (!roomFor(level, far.add(b.normal().scale(GATE_STEP)))
                    && roomFor(level, far.add(b.normal().scale(-GATE_STEP)))) b.facing = on + 180;
        }
        a.arrived.add(p.getUUID()); // the caster steps in when ready, not the moment it opens
        GATES.removeIf(g -> g.owner == p);
        GATES.add(new Pair(p, a, b, SpellUpgrades.scaleInt(Spell.MOONGATE, DURATION, GATE_TICKS)));
        for (var g : List.of(a, b)) {
            openPortal(g);
            sound(g.level, g.pos, SoundEvents.RESPAWN_ANCHOR_CHARGE, 1f, 1.4f);
            sound(g.level, g.pos, SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, .9f);
        }
        return true;
    }

    /**
     * The portal's marker: an empty item display, named and turned to the gate's facing, where every client draws the
     * swirl (PortalView: it grows open as the display first ticks).
     */
    private static void openPortal(Gate g) {
        var d = EntityTypes.ITEM_DISPLAY.create(g.level, EntitySpawnReason.MOB_SUMMONED);
        if (d == null) return;
        d.setCustomName(Component.literal(PORTAL_NAME));
        d.setYRot(g.facing);
        g.portal = SpellKit.track(g.level, d, g.pos);
    }

    /** The portal shrinks shut on every client, then its marker goes. */
    private static void closePortal(Gate g) {
        var d = g.portal;
        if (d == null || d.isRemoved()) return;
        d.setCustomName(Component.literal(PORTAL_CLOSING));
        Scheduler.later(7, () -> SpellKit.drop(d));
    }

    /** The yaw that looks from {@code from} toward {@code to}. */
    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    private static boolean roomFor(ServerLevel level, Vec3 at) {
        return level.noCollision(new AABB(at.x - .3, at.y, at.z - .3, at.x + .3, at.y + 1.8, at.z + .3))
                && level.getFluidState(BlockPos.containing(at)).isEmpty();
    }

    /** Who may walk through: any player, animals and other peaceful creatures, dropped items; no monsters. */
    private static boolean passes(Entity e) {
        if (e.isPassenger() || e.isVehicle() || !e.isAlive()) return false;
        if (e instanceof ItemEntity) return true;
        return e instanceof LivingEntity && !(e instanceof Enemy) && !(e instanceof Display);
    }

    static void tickGates() {
        long now = Scheduler.now();
        for (Iterator<Pair> it = GATES.iterator(); it.hasNext(); ) {
            var pair = it.next();
            var p = pair.owner;
            if (p.isRemoved() || --pair.ticks < 0) {
                for (var g : List.of(pair.a, pair.b)) {
                    closePortal(g);
                    g.level.sendParticles(
                            ParticleTypes.REVERSE_PORTAL, g.pos.x, g.pos.y + 1, g.pos.z, 30, .3, .6, .3, .05);
                    sound(g.level, g.pos, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), .6f, 1.4f);
                }
                it.remove();
                continue;
            }
            pair.age++;
            for (var g : List.of(pair.a, pair.b)) {
                if (pair.age % 2 == 0) drawGate(g, pair.age);
                if (pair.age % 40 == 0) sound(g.level, g.pos, SoundEvents.PORTAL_AMBIENT, .12f, 1.8f);
                var inside = new HashSet<UUID>();
                for (var e : g.level.getEntitiesOfClass(Entity.class, g.room(), e -> true)) {
                    inside.add(e.getUUID());
                    if (g.arrived.contains(e.getUUID())) continue;
                    if (e instanceof LivingEntity le && monster(p, le)) {
                        if (SpellUpgrades.perk(p, Spell.MOONGATE, "Banishing Gate") && now % 10 == 0) {
                            Spells.hit(p, le, Perks.flat(p, 8), Spell.MOONGATE);
                            throwFrom(le, g.pos, 1.2, .5);
                        }
                        continue;
                    }
                    if (!passes(e)) continue;
                    pass(p, e, g, g.other);
                }
                g.arrived.retainAll(inside);
            }
        }
    }

    /**
     * Carries {@code e} from gate {@code through} to gate {@code to} as through a doorway: it comes out on the far side
     * from where it went in, still facing and going the same way relative to the gates.
     */
    private static void pass(ServerPlayer owner, Entity e, Gate through, Gate to) {
        to.arrived.add(e.getUUID());
        Vec3 from = e.position();
        if (e.level() instanceof ServerLevel here)
            here.sendParticles(ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1, from.z, 20, .3, .6, .3, .05);
        // Which side it came in from: where it stood a tick ago, else where it stands, else the way it looks.
        Vec3 n = through.normal();
        double came = new Vec3(e.xo, e.yo, e.zo).subtract(through.pos).dot(n);
        if (Math.abs(came) < .02) came = from.subtract(through.pos).dot(n);
        if (Math.abs(came) < .02) came = -e.getLookAngle().dot(n);
        Vec3 out = to.exit(came < 0 ? 1 : -1);
        float yaw = e.getYRot() + (to.facing - through.facing);
        if (e instanceof ServerPlayer sp) {
            sp.teleportTo(to.level, out.x, out.y, out.z, Set.of(), yaw, sp.getXRot(), false);
            push(sp, Vec3.ZERO);
            sp.resetFallDistance();
            if (SpellUpgrades.perk(owner, Spell.MOONGATE, "Swift Passage"))
                sp.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 1));
            if (SpellUpgrades.perk(owner, Spell.MOONGATE, "Warm Gate")) sp.heal(4);
            if (SpellUpgrades.perk(owner, Spell.MOONGATE, "Safe Passage"))
                sp.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 0, false, false, true));
        } else {
            e.teleportTo(to.level, out.x, out.y, out.z, Set.of(), yaw, e.getXRot(), false);
            e.setDeltaMovement(Vec3.ZERO);
        }
        if (e instanceof ServerPlayer sp) forOthers(sp, ParticleTypes.END_ROD, out.add(0, 1, 0), 10, .4, .03);
        else to.level.sendParticles(ParticleTypes.END_ROD, out.x, out.y + 1, out.z, 10, .3, .5, .3, .03);
        sound(to.level, to.pos, SoundEvents.CHORUS_FRUIT_TELEPORT, .8f, 1.5f);
    }

    /** Green sparks thrown off the portal's rim now and then; the portal itself is the display. */
    private static void drawGate(Gate g, int age) {
        if (age % 4 != 0) return;
        double yaw = Math.toRadians(g.facing);
        Vec3 side = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        var spark = new DustParticleOptions(0xC8FF7A, .8f);
        var random = g.level.getRandom();
        for (int k = 0; k < 3; k++) {
            double a = random.nextDouble() * Math.PI * 2;
            Vec3 at = g.pos.add(side.scale(Math.cos(a) * PORTAL_WIDE / 2))
                    .add(0, PORTAL_TALL / 2 + .05 + Math.sin(a) * PORTAL_TALL / 2, 0);
            g.level.sendParticles(spark, at.x, at.y, at.z, 1, .05, .05, .05, 0);
        }
        g.level.sendParticles(ParticleTypes.HAPPY_VILLAGER, g.pos.x, g.pos.y + 1.15, g.pos.z, 1, .5, .8, .5, 0);
    }

    // ---------------------------------------------------------------- Comet Dash

    static final double COMET_REACH = 14, COMET_HIT = 1.8, COMET_MAX_UP = .6;
    static final int COMET_TICKS = 10, COMET_SAFE = 60, SECOND_WIND = 60;

    static final class Dash {
        final ServerPlayer p;
        final Vec3 dir;
        final double speed, dmg;
        final boolean blazing, shockwave, dazing, momentum;
        final Set<LivingEntity> struck = new HashSet<>();
        Vec3 last;
        int left, stuck;

        Dash(
                ServerPlayer p,
                Vec3 dir,
                double speed,
                double dmg,
                boolean blazing,
                boolean shockwave,
                boolean dazing,
                boolean momentum) {
            this.dazing = dazing;
            this.momentum = momentum;
            this.p = p;
            this.dir = dir;
            this.speed = speed;
            this.dmg = dmg;
            this.blazing = blazing;
            this.shockwave = shockwave;
            this.last = p.position();
            this.left = COMET_TICKS;
        }
    }

    static final Map<ServerPlayer, Dash> DASHES = new IdentityHashMap<>();
    /** Players spared fall damage for a moment after a dash: ticks left. */
    static final Map<ServerPlayer, Integer> DASH_SAFE = new IdentityHashMap<>();

    /** You streak along your gaze as a comet, striking and throwing aside every monster you pass. */
    static boolean comet(ServerLevel level, ServerPlayer p, double dmg) {
        Vec3 look = p.getLookAngle();
        double y = Math.max(-COMET_MAX_UP, Math.min(COMET_MAX_UP, look.y));
        Vec3 flat = look.multiply(1, 0, 1);
        flat = flat.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : flat.normalize();
        Vec3 dir = flat.scale(Math.sqrt(1 - y * y)).add(0, y, 0);
        double speed = SpellUpgrades.scale(Spell.COMET_DASH, REACH, COMET_REACH) / COMET_TICKS;
        var d = new Dash(
                p,
                dir,
                speed,
                dmg,
                perk(p, Spell.COMET_DASH, "Blazing Trail"),
                perk(p, Spell.COMET_DASH, "Shockwave"),
                perk(p, Spell.COMET_DASH, "Dazing Strike"),
                perk(p, Spell.COMET_DASH, "Momentum"));
        DASHES.put(p, d);
        DASH_SAFE.put(p, COMET_TICKS + COMET_SAFE);
        pushPlayer(p, dir.scale(speed));
        if (perk(p, Spell.COMET_DASH, "Second Wind") && !Perks.free(p, Spell.COMET_DASH))
            Perks.grantFree(p, Spell.COMET_DASH, SECOND_WIND);
        level.sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getY() + 1, p.getZ(), 30, .3, .5, .3, .2);
        sound(level, p.position(), SoundEvents.FIRECHARGE_USE, 1f, 1.4f);
        sound(level, p.position(), SoundEvents.BLAZE_SHOOT, .7f, 1.6f);
        return true;
    }

    static void tickDashes() {
        for (Iterator<Map.Entry<ServerPlayer, Integer>> it =
                        DASH_SAFE.entrySet().iterator();
                it.hasNext(); ) {
            var en = it.next();
            en.getKey().fallDistance = 0;
            if (en.getKey().isRemoved() || en.setValue(en.getValue() - 1) <= 1) it.remove();
        }
        for (Iterator<Dash> it = DASHES.values().iterator(); it.hasNext(); ) {
            Dash d = it.next();
            ServerPlayer p = d.p;
            if (p.isRemoved() || !p.isAlive()) {
                it.remove();
                continue;
            }
            ServerLevel level = p.level();
            Vec3 now = p.position();
            strike(level, d, d.last, now);
            // Two ticks on and still barely moving: a wall stopped the comet.
            d.stuck = now.distanceTo(d.last) < .25 && d.left < COMET_TICKS - 1 ? d.stuck + 1 : 0;
            d.last = now;
            if (--d.left <= 0 || d.stuck >= 2) {
                it.remove();
                pushPlayer(p, d.dir.scale(.25));
                if (d.shockwave) shockwave(level, d);
                if (d.momentum) p.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 1, false, false, true));
                continue;
            }
            pushPlayer(p, d.dir.scale(d.speed));
            Vec3 c = chest(p);
            level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 6, .25, .25, .25, .02);
            level.sendParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 4, .2, .2, .2, .01);
            level.sendParticles(ParticleTypes.FIREWORK, c.x, c.y, c.z, 2, .1, .1, .1, .05);
        }
    }

    /** Every monster within reach of the stretch the comet crossed this tick is struck once and thrown aside. */
    private static void strike(ServerLevel level, Dash d, Vec3 a, Vec3 b) {
        Vec3 up = new Vec3(0, 1, 0);
        AABB box = new AABB(a, b).inflate(COMET_HIT + 1);
        for (LivingEntity e : level.getEntitiesOfClass(
                LivingEntity.class, box, e -> !d.struck.contains(e) && SpellKit.monster(d.p, e))) {
            Vec3 at = chest(e);
            if (SpellKit.distToSegment(at, a.add(0, 1, 0), b.add(0, 1, 0)) > COMET_HIT) continue;
            d.struck.add(e);
            Spells.hit(d.p, e, d.dmg, Spell.COMET_DASH);
            Vec3 side = e.position().subtract(b);
            side = side.subtract(d.dir.scale(side.dot(d.dir)));
            if (side.lengthSqr() < 1e-3) side = d.dir.cross(up);
            fling(e, side, .9, .4);
            if (d.blazing) e.igniteForSeconds(4);
            if (d.dazing) e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1, false, true, true));
            level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0, 0, 0, 0);
            sound(level, at, SoundEvents.PLAYER_ATTACK_CRIT, 1f, 1.2f);
        }
    }

    private static void shockwave(ServerLevel level, Dash d) {
        Vec3 at = d.p.position();
        for (var e : Perks.nearestMonsters(d.p, null, at, 3, 32)) {
            Spells.hit(d.p, e, d.dmg * .5, Spell.COMET_DASH);
            fling(e, e.position().subtract(at), 1.1, .45);
        }
        Spells.ring(level, at.add(0, .2, 0), 3, ParticleTypes.FLAME);
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + .5, at.z, 2, .5, .2, .5, 0);
        sound(level, at, SoundEvents.MACE_SMASH_GROUND, 1f, .9f);
    }

    static boolean dashing(ServerPlayer p) {
        return DASHES.containsKey(p);
    }
}
