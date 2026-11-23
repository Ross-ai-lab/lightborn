package lightborn;

import com.mojang.serialization.Codec;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Scripted encounters with readable windups, bounded arenas and restartable fights. */
public final class StoryBosses {
    private StoryBosses() {}

    static void init() {}

    private static final Map<UUID, Fight> FIGHTS = new HashMap<>();
    private static final List<Ally> ALLIES = new ArrayList<>();
    static final AttachmentType<BlockPos> ENTRY =
            AttachmentRegistry.create(ModItems.id("prison_entry"), b -> b.persistent(BlockPos.CODEC));
    private static final AttachmentType<Boolean> RELEASED =
            AttachmentRegistry.create(ModItems.id("dragon_release_finished"), b -> b.persistent(Codec.BOOL));
    private static LightDragon bound, rising;
    private static int releaseAge;

    private record Ally(UUID owner, LightDragon dragon, long until) {}

    private static final class Fight {
        final ServerPlayer owner;
        final Mob actor;
        final Vec3 center;
        final boolean dragon;
        final ServerBossEvent bar;
        long next, wardUntil;
        int move, chain;
        Spell copied = Spell.ARCANE_BOLT;
        double copiedDamage = 5;

        Fight(ServerPlayer p, Mob m, boolean dragon) {
            owner = p;
            actor = m;
            center = m.position();
            this.dragon = dragon;
            bar = new ServerBossEvent(
                    UUID.randomUUID(),
                    Component.literal(dragon ? "The Bound Dragon" : "Orvane, the Last Scribe"),
                    dragon ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.PURPLE,
                    BossEvent.BossBarOverlay.NOTCHED_10);
            next = Scheduler.now() + 60;
        }
    }

    static boolean hostile(Entity e) {
        return e instanceof LastScribe || e instanceof LightDragon d && d.role() == 1;
    }

    /**
     * The prison Dragon's size: as tall as the old giant (3 times the old 2.4-block model, about 5.8 blocks) and just
     * inside the chains at 4 blocks either side of him.
     */
    static final double STORY_SIZE = 1.7;
    /** The Dragon Form avatar and the summoned ally: a little under his full size, about a player's reach across. */
    static final double COMPANION_SIZE = .7;

    static LightDragon apparition(ServerPlayer p, int role, Vec3 at) {
        var d = new LightDragon(ModEntities.LIGHT_DRAGON, p.level());
        d.role(role);
        if (role == 2 || role == 3) d.size(COMPANION_SIZE);
        d.setPos(at);
        d.setYRot(p.getYRot());
        p.level().addFreshEntity(d);
        return d;
    }

    static void scribe(ServerPlayer p) {
        if (FIGHTS.containsKey(p.getUUID())) return;
        var boss = new LastScribe(ModEntities.LAST_SCRIBE, p.level());
        Vec3 at = null;
        search:
        for (int r : new int[] {4, 3, 2, 6})
            for (int i = 0; i < 8; i++)
                for (int y : new int[] {0, 1, -1}) {
                    double a = Math.toRadians(p.getYRot() + i * 45);
                    var candidate = p.position().add(Math.sin(a) * r, y, Math.cos(a) * r);
                    var box = new AABB(
                            candidate.x - .35,
                            candidate.y,
                            candidate.z - .35,
                            candidate.x + .35,
                            candidate.y + 2.2,
                            candidate.z + .35);
                    boss.setPos(candidate);
                    if (p.level().noCollision(boss, box) && p.hasLineOfSight(boss)) {
                        at = candidate;
                        break search;
                    }
                }
        if (at == null) return;
        boss.setPos(at);
        start(p, boss, false);
    }

    static Fight start(ServerPlayer p, Mob boss, boolean dragon) {
        Monsters.setRank(boss, MonsterInfo.BOSS);
        boss.setNoAi(true);
        boss.setPersistenceRequired();
        boss.setCustomName(Component.literal(dragon ? "The Bound Dragon" : "Orvane, the Last Scribe"));
        boss.setCustomNameVisible(false);
        if (boss.getAttribute(Attributes.MAX_HEALTH) != null)
            boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue(dragon ? 45 : 20);
        boss.setHealth(boss.getMaxHealth());
        if (!dragon) p.level().addFreshEntity(boss);
        var f = new Fight(p, boss, dragon);
        FIGHTS.put(p.getUUID(), f);
        f.bar.addPlayer(p);
        // No title card (owner, 2026-11-16): the boss bar already names him.
        p.level().playSound(null, p.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1, .65f);
        return f;
    }

    static BlockPos prisonRoom(ServerPlayer p) {
        var l = p.level();
        var center = l.getAttached(DragonStory.ARENA);
        if (center == null) {
            var gate = l.getAttached(DragonStory.PRISON);
            if (gate == null) return null;
            center = new BlockPos(gate.getX(), Math.max(l.getMinY() + 4, gate.getY() - 18), gate.getZ());
            for (var at : BlockPos.betweenClosed(center.offset(-14, -1, -14), center.offset(14, 13, 14))) {
                double r = Math.hypot(at.getX() - center.getX(), at.getZ() - center.getZ());
                if (r > 14) continue;
                var block = at.getY() == center.getY() - 1 || r > 13 ? Blocks.DEEPSLATE_TILES : Blocks.AIR;
                l.setBlock(at, block.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (int x : new int[] {-10, 10})
                for (int z : new int[] {-10, 10})
                    l.setBlockAndUpdate(center.offset(x, 0, z), Blocks.SEA_LANTERN.defaultBlockState());
            l.setAttached(DragonStory.ARENA, center);
            for (int x : new int[] {-4, 4})
                for (int y = 5; y < 13; y++)
                    l.setBlockAndUpdate(center.offset(x, y, -3), Blocks.IRON_CHAIN.defaultBlockState());
            l.setBlockAndUpdate(center.offset(0, 1, 11), Blocks.REINFORCED_DEEPSLATE.defaultBlockState());
            var label = EntityTypes.TEXT_DISPLAY.create(l, EntitySpawnReason.COMMAND);
            label.setPos(center.getX() + .5, center.getY() + 2.8, center.getZ() + 10.5);
            label.setText(Component.literal("Return to the city").withStyle(ChatFormatting.GOLD));
            label.setBillboardConstraints(Display.BillboardConstraints.CENTER);
            l.addFreshEntity(label);
        }
        return center;
    }

    static void enterPrison(ServerPlayer p) {
        var center = prisonRoom(p);
        if (center == null) return;
        var l = p.level();
        if (l.getAttached(ENTRY) == null) l.setAttached(ENTRY, p.blockPosition());
        p.teleportTo(l, center.getX() + .5, center.getY(), center.getZ() + 8.5, Set.of(), 180, 0, false);
        if (DragonStory.ending(l) == 0 && (bound == null || bound.isRemoved())) {
            bound = apparition(p, 5, Vec3.atBottomCenterOf(center).add(0, 1, -3));
            bound.getAttribute(Attributes.SCALE).setBaseValue(STORY_SIZE);
        }
        p.sendOverlayMessage(
                Component.literal("The sealed arch behind you returns to the city. Right-click it at any time."));
    }

    static boolean exit(ServerPlayer p, BlockPos clicked) {
        var center = p.level().getAttached(DragonStory.ARENA);
        var back = p.level().getAttached(ENTRY);
        if (center == null
                || back == null
                || !clicked.equals(center.offset(0, 1, 11))
                || p.distanceToSqr(Vec3.atCenterOf(clicked)) > 64) return false;
        p.teleportTo(p.level(), back.getX() + .5, back.getY(), back.getZ() + .5, Set.of(), p.getYRot(), 0, false);
        return true;
    }

    static void release(ServerPlayer p) {
        var center = prisonRoom(p);
        if (center == null) return;
        if (bound != null) {
            bound.discard();
            bound = null;
        }
        if (rising != null) rising.discard();
        rising = apparition(p, 4, Vec3.atBottomCenterOf(center).add(0, 1, -3));
        rising.getAttribute(Attributes.SCALE).setBaseValue(STORY_SIZE);
        releaseAge = 0;
        for (int x : new int[] {-4, 4})
            for (int y = 5; y < 13; y++) {
                var at = center.offset(x, y, -3);
                if (p.level().getBlockState(at).is(Blocks.IRON_CHAIN)) {
                    p.level().levelEvent(2001, at, Block.getId(Blocks.IRON_CHAIN.defaultBlockState()));
                    p.level().setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                }
            }
        p.level().playSound(null, center, SoundEvents.CHAIN_BREAK, SoundSource.AMBIENT, 2, .6f);
        p.level().playSound(null, center, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.AMBIENT, 1, .6f);
    }

    static void prisonFight(ServerPlayer p) {
        if (FIGHTS.containsKey(p.getUUID())) return;
        var l = p.level();
        var center = prisonRoom(p);
        if (center == null) return;
        if (bound != null) {
            bound.discard();
            bound = null;
        }
        p.teleportTo(l, center.getX() + .5, center.getY(), center.getZ() + 8.5, Set.of(), 180, 0, false);
        var dragon = apparition(p, 1, Vec3.atBottomCenterOf(center).add(0, 1, -3));
        dragon.getAttribute(Attributes.SCALE).setBaseValue(STORY_SIZE);
        dragon.setPermanentlyInvulnerable(false);
        start(p, dragon, true);
    }

    static void blinked(ServerPlayer p) {
        var f = FIGHTS.get(p.getUUID());
        if (f != null) f.chain++;
    }

    static Spell copied(ServerPlayer p) {
        return FIGHTS.get(p.getUUID()).copied;
    }

    static void hit(ServerPlayer p, LivingEntity target, Spell spell, double amount) {
        for (var f : FIGHTS.values())
            if (f.actor == target && !f.dragon && spell != null) {
                f.copied = spell;
                f.copiedDamage = Math.clamp(amount * .4, 2, 7);
            }
    }

    static void tick(MinecraftServer server) {
        if (rising != null && !rising.isRemoved()) {
            releaseAge++;
            if (releaseAge > 40) rising.setPos(rising.position().add(0, .1 + (releaseAge - 40) * .005, 0));
            if (releaseAge % 3 == 0)
                ((ServerLevel) rising.level())
                        .sendParticles(
                                ParticleTypes.END_ROD,
                                rising.getX(),
                                rising.getY() + 3,
                                rising.getZ(),
                                15,
                                2,
                                3,
                                2,
                                .04);
            if (releaseAge >= 260) {
                rising.discard();
                rising = null;
                server.overworld().setAttached(RELEASED, true);
            }
        }
        if (server.getTickCount() % 20 == 0)
            for (var p : server.getPlayerList().getPlayers()) {
                var arena = server.overworld().getAttached(DragonStory.ARENA);
                if (p.level().dimension() == Level.OVERWORLD
                        && arena != null
                        && p.blockPosition().closerThan(arena, 30)) {
                    if (DragonStory.ending(p.level()) == 0 && (bound == null || bound.isRemoved())) {
                        bound = apparition(p, 5, Vec3.atBottomCenterOf(arena).add(0, 1, -3));
                        bound.getAttribute(Attributes.SCALE).setBaseValue(STORY_SIZE);
                    }
                    if (DragonStory.ending(p.level()) == 1
                            && !server.overworld().getAttachedOrElse(RELEASED, false)
                            && rising == null) release(p);
                }
            }
        for (var f : List.copyOf(FIGHTS.values())) {
            var p = server.getPlayerList().getPlayer(f.owner.getUUID());
            if (!f.actor.isAlive() || f.actor.isRemoved()) {
                remove(f);
                continue;
            }
            if (p == null
                    || !p.isAlive()
                    || p.level() != f.actor.level()
                    || p.position().distanceToSqr(f.center) > (f.dragon ? 38 * 38 : 48 * 48)
                    || f.dragon
                            && (Math.abs(p.getY() - f.center.y) > 12
                                    || p.position().subtract(f.center).horizontalDistanceSqr() > 17 * 17)
                    || !f.dragon && !DragonStory.inStructureBox(p, "fortresses")) {
                f.actor.discard();
                remove(f);
                continue;
            }
            f.bar.setProgress(f.actor.getHealth() / f.actor.getMaxHealth());
            for (var viewer : p.level().players())
                if (viewer.distanceToSqr(f.actor) < 2500) f.bar.addPlayer(viewer);
                else f.bar.removePlayer(viewer);
            var delta = p.position().subtract(f.actor.position());
            float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
            f.actor.setYRot(yaw);
            f.actor.setYHeadRot(yaw);
            if (!f.dragon && delta.horizontalDistanceSqr() > 16) {
                var step = delta.multiply(1, 0, 1).normalize().scale(.06);
                if (p.level().noCollision(f.actor, f.actor.getBoundingBox().move(step)))
                    f.actor.setPos(f.actor.position().add(step));
            }
            if (Scheduler.now() >= f.next) {
                f.next = Scheduler.now() + 90;
                attack(f, p);
            }
            if (Scheduler.now() % 5 == 0) {
                var l = p.level();
                l.sendParticles(
                        f.dragon ? ParticleTypes.END_ROD : ParticleTypes.SQUID_INK,
                        f.actor.getX(),
                        f.actor.getY() + 1.2,
                        f.actor.getZ(),
                        4,
                        .7,
                        1,
                        .7,
                        .01);
            }
        }
        for (var ally : List.copyOf(ALLIES)) {
            var p = server.getPlayerList().getPlayer(ally.owner);
            if (p == null
                    || !p.isAlive()
                    || p.level() != ally.dragon.level()
                    || p.level().getGameTime() >= ally.until) {
                ally.dragon.discard();
                ALLIES.remove(ally);
                continue;
            }
            ally.dragon.setPos(p.position().add(-3, 2, 0));
            if (Scheduler.now() % 30 == 0) {
                var target = p
                        .level()
                        .getEntitiesOfClass(
                                Mob.class, p.getBoundingBox().inflate(22), m -> Monsters.isMonster(m) && m.isAlive())
                        .stream()
                        .min(Comparator.comparingDouble(p::distanceToSqr));
                if (target.isPresent())
                    breath(
                            p,
                            ally.dragon.getEyePosition(),
                            target.get()
                                    .getEyePosition()
                                    .subtract(ally.dragon.getEyePosition())
                                    .normalize(),
                            24,
                            8 * p.getAttributeValue(ModAttributes.SPELL_POWER));
            }
        }
    }

    private static void attack(Fight f, ServerPlayer p) {
        var l = p.level();
        Vec3 target = p.position();
        int move = f.move++ % 3;
        if (f.dragon) {
            Vec3 origin = f.actor.position().add(0, 4, 0);
            Vec3 direction = p.getEyePosition().subtract(origin).normalize();
            p.sendOverlayMessage(Component.literal(
                    move == 0
                            ? "His breath gathers. Move out of its path."
                            : move == 1
                                    ? "Light gathers under your feet. Keep moving."
                                    : "The chains pull tight. Watch the ring."));
            if (move == 0) {
                line(l, origin, direction, 22, ParticleTypes.ELECTRIC_SPARK);
                later(f, 30, () -> {
                    line(l, origin, direction, 22, ParticleTypes.END_ROD);
                    for (var v : l.players()) if (nearRay(v, origin, direction, 22, 2)) hurt(f, v, 8);
                });
            } else if (move == 1) {
                for (int i = 0; i < 3; i++) {
                    var at = target.add((i - 1) * 3, 0, 0);
                    Bosses.ring(l, at, 2, ParticleTypes.ELECTRIC_SPARK, 30);
                    later(f, 30 + i * 5, () -> {
                        l.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 1, at.z, 40, 1, 3, 1, .05);
                        for (var v : l.players()) if (v.distanceToSqr(at) < 5) hurt(f, v, 7);
                    });
                }
            } else {
                Bosses.ring(l, f.center, 8, ParticleTypes.END_ROD, 64);
                later(f, 35, () -> {
                    for (var v : l.players()) if (Math.abs(v.position().distanceTo(f.center) - 8) < 2) hurt(f, v, 10);
                });
            }
        } else if (move == 0) {
            p.sendOverlayMessage(Component.literal("Ink Script · the ink will burn"));
            for (int i = -2; i <= 2; i++) {
                var at = target.add(i * 2, 0, 0);
                Bosses.ring(l, at, .7, ParticleTypes.SQUID_INK, 12);
                l.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + .1, at.z, 5, .3, 0, .3, 0);
                later(f, 30, () -> {
                    l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + .4, at.z, 20, .5, .7, .5, .03);
                    for (var v : l.players()) if (v.distanceToSqr(at) < 2.3) hurt(f, v, 6);
                });
            }
        } else if (move == 1) {
            copy(f, p);
        } else {
            int chain = ++f.chain;
            Vec3 anchor = p.position();
            p.sendOverlayMessage(Component.literal("Ink Chains · Blink breaks the tether"));
            p.level().playSound(null, p.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.HOSTILE, 1, .6f);
            for (int i = 1; i <= 40; i++) {
                int tick = i;
                later(f, i, () -> {
                    if (f.chain != chain) return;
                    if (p.position().distanceToSqr(anchor) > 16) {
                        f.chain++;
                        return;
                    }
                    if (tick < 40) {
                        p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
                        p.teleportTo(l, anchor.x, p.getY(), anchor.z, Set.of(), p.getYRot(), p.getXRot(), false);
                        l.sendParticles(ParticleTypes.SQUID_INK, anchor.x, anchor.y + .7, anchor.z, 3, .4, .5, .4, 0);
                    }
                });
            }
        }
    }

    private static boolean reflecting;

    public static float incoming(LivingEntity target, DamageSource source, float amount) {
        for (var f : FIGHTS.values())
            if (f.actor == target && f.wardUntil > Scheduler.now()) {
                if (!reflecting && source.getEntity() instanceof ServerPlayer p) {
                    reflecting = true;
                    try {
                        p.hurtServer(p.level(), p.damageSources().indirectMagic(f.actor, f.actor), amount * 1.5f);
                    } finally {
                        reflecting = false;
                    }
                }
                return amount * .5f;
            }
        return amount;
    }

    private static void copy(Fight f, ServerPlayer p) {
        var l = p.level();
        Spell school = f.copied;
        double damage = f.copiedDamage;
        Vec3 from = f.actor.getEyePosition(),
                dir = p.getEyePosition().subtract(from).normalize(),
                mark = p.position();
        p.sendOverlayMessage(Component.literal("Copy · " + school.label));
        if (school == Spell.MIRROR_WARD) {
            f.wardUntil = Scheduler.now() + 160;
            Bosses.ring(l, f.actor.position(), 2, ParticleTypes.ELECTRIC_SPARK, 36);
            return;
        }
        if (school == Spell.FROST_NOVA) {
            Bosses.ring(l, f.actor.position(), 6, ParticleTypes.SNOWFLAKE, 60);
            later(f, 24, () -> {
                Bosses.ring(l, f.actor.position(), 6, ParticleTypes.SNOWFLAKE, 100);
                for (var v : l.players())
                    if (v.distanceToSqr(f.actor) < 36) {
                        hurt(f, v, damage);
                        v.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 2));
                    }
            });
            return;
        }
        if (school == Spell.METEOR || school == Spell.FIREBALL) {
            Bosses.ring(l, mark, school == Spell.METEOR ? 4 : 2.5, ParticleTypes.FLAME, 36);
            later(f, school == Spell.METEOR ? 40 : 24, () -> {
                l.sendParticles(ParticleTypes.EXPLOSION, mark.x, mark.y + 1, mark.z, 4, 1, 1, 1, 0);
                l.playSound(
                        null,
                        BlockPos.containing(mark),
                        SoundEvents.GENERIC_EXPLODE.value(),
                        SoundSource.HOSTILE,
                        1,
                        .8f);
                for (var v : l.players())
                    if (v.distanceToSqr(mark) < (school == Spell.METEOR ? 16 : 7)) {
                        hurt(f, v, damage);
                        v.igniteForSeconds(3);
                    }
            });
            return;
        }
        if (school == Spell.GRAVITY_WELL) {
            Bosses.ring(l, mark, 5, ParticleTypes.REVERSE_PORTAL, 40);
            for (int i = 5; i <= 60; i += 5) {
                int tick = i;
                later(f, i, () -> {
                    l.sendParticles(ParticleTypes.REVERSE_PORTAL, mark.x, mark.y + 1, mark.z, 20, 2, .4, 2, .1);
                    for (var v : l.players())
                        if (v.distanceToSqr(mark) < 25) {
                            var pull = mark.subtract(v.position()).normalize().scale(.18);
                            v.setDeltaMovement(v.getDeltaMovement().add(pull));
                            v.connection.send(new ClientboundSetEntityMotionPacket(v));
                            if (tick == 60) {
                                hurt(f, v, damage);
                                v.push(0, .7, 0);
                            }
                        }
                });
            }
            return;
        }
        if (school == Spell.SIPHON) {
            for (int i = 10; i <= 60; i += 10)
                later(f, i, () -> {
                    if (p.distanceToSqr(f.actor) > 24 * 24 || !f.actor.hasLineOfSight(p)) return;
                    line(
                            l,
                            f.actor.getEyePosition(),
                            p.getEyePosition()
                                    .subtract(f.actor.getEyePosition())
                                    .normalize(),
                            (int) p.distanceTo(f.actor),
                            ParticleTypes.SOUL_FIRE_FLAME);
                    hurt(f, p, damage / 3);
                    f.actor.heal((float) damage / 6);
                });
            return;
        }
        if (school == Spell.EARTHSHATTER) {
            for (int i = 2; i < 15; i += 2) {
                var q = f.actor.position().add(dir.multiply(1, 0, 1).normalize().scale(i));
                Bosses.ring(l, q, 1, ParticleTypes.CRIT, 8);
                later(f, 24 + i, () -> {
                    l.sendParticles(ParticleTypes.CRIT, q.x, q.y + 1, q.z, 14, .3, 1.6, .3, .02);
                    for (var v : l.players())
                        if (v.distanceToSqr(q) < 3) {
                            hurt(f, v, damage);
                            v.push(0, .5, 0);
                        }
                });
            }
            return;
        }
        var particle = school == Spell.GLACIAL_SPIKE
                ? ParticleTypes.SNOWFLAKE
                : school == Spell.CONTAGION
                        ? ParticleTypes.SNEEZE
                        : school == Spell.WATER_SPLASH ? ParticleTypes.SPLASH : ParticleTypes.ELECTRIC_SPARK;
        line(l, from, dir, 20, ParticleTypes.SQUID_INK);
        later(f, 24, () -> {
            line(l, from, dir, 20, particle);
            for (var v : l.players())
                if (nearRay(v, from, dir, 20, school == Spell.WATER_SPLASH ? 3 : 1.4)) {
                    boolean chilled = v.hasEffect(MobEffects.SLOWNESS);
                    hurt(f, v, damage * (school == Spell.GLACIAL_SPIKE && chilled ? 3 : 1));
                    if (school == Spell.GLACIAL_SPIKE) v.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 50, 1));
                    if (school == Spell.CONTAGION) v.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
                    if (school == Spell.WATER_SPLASH) {
                        v.clearFire();
                        v.push(dir.x * .8, .2, dir.z * .8);
                    }
                }
        });
    }

    private static void later(Fight f, int ticks, Runnable r) {
        Scheduler.later(ticks, () -> {
            if (f.actor.isAlive() && !f.actor.isRemoved() && FIGHTS.get(f.owner.getUUID()) == f) r.run();
        });
    }

    private static void hurt(Fight f, ServerPlayer p, double amount) {
        p.hurtServer(p.level(), p.damageSources().indirectMagic(f.actor, f.actor), (float) amount);
    }

    private static boolean nearRay(LivingEntity v, Vec3 origin, Vec3 dir, double length, double radius) {
        var delta = v.getEyePosition().subtract(origin);
        double t = delta.dot(dir);
        return t > 0 && t < length && delta.subtract(dir.scale(t)).lengthSqr() < radius * radius;
    }

    private static void line(ServerLevel l, Vec3 origin, Vec3 dir, int n, SimpleParticleType particle) {
        for (int i = 1; i <= n; i++) {
            var at = origin.add(dir.scale(i));
            l.sendParticles(particle, at.x, at.y, at.z, 2, .08, .08, .08, 0);
        }
    }

    static void breath(ServerPlayer p, Vec3 at, Vec3 direction, int length, double damage) {
        line(p.level(), at, direction, length, ParticleTypes.END_ROD);
        p.level().playSound(null, p.blockPosition(), SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.PLAYERS, .7f, 1.2f);
        for (var target : p.level()
                .getEntitiesOfClass(
                        Mob.class,
                        p.getBoundingBox().inflate(length),
                        m -> (Monsters.isMonster(m) || hostile(m))
                                && !Souls.summoned(m)
                                && m.getAttached(ModData.THRALL_MASTER) == null))
            if (nearRay(target, at, direction, length, 2)) Spells.hit(p, target, damage);
    }

    static void friend(ServerPlayer p) {
        var d = apparition(p, 2, p.position().add(-3, 2, 0));
        d.arrive();
        ALLIES.add(new Ally(p.getUUID(), d, p.level().getGameTime() + 600));
    }

    static boolean died(LivingEntity dead, DamageSource source) {
        var f = FIGHTS.values().stream()
                .filter(s -> s.actor == dead)
                .findFirst()
                .orElse(null);
        if (f == null) return false;
        var p = Lightborn.killer(dead, source);
        if (p == null) p = f.owner;
        if (f.dragon) DragonStory.setEnding(p, 3);
        else {
            DragonStory.flag(p, 16);
            LightGear.give(p, new ItemStack(DragonStory.LIGHT_KEY));
            LightGear.give(p, new ItemStack(DragonStory.SCRIBE_SEAL));
            LightGear.give(p, DragonStory.keeperNote(true));
            p.removeAttached(DragonStory.TARGET);
        }
        remove(f);
        return true;
    }

    static void remove(Fight f) {
        f.bar.removeAllPlayers();
        FIGHTS.remove(f.owner.getUUID());
    }

    static void clear() {
        for (var f : List.copyOf(FIGHTS.values())) {
            f.actor.discard();
            remove(f);
        }
        ALLIES.forEach(a -> a.dragon.discard());
        ALLIES.clear();
        if (bound != null) bound.discard();
        bound = null;
        if (rising != null) rising.discard();
        rising = null;
    }
}
