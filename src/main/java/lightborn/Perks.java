package lightborn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The tier III upgrade perks that ride on hits, kills and damage rather than on one spell's own code (owner,
 * 2026-11-17: tier III is new things a spell does, never bigger numbers). Each spell's other perks live in that
 * spell's code, read with {@link SpellUpgrades#perk}.
 */
final class Perks {
    private Perks() {}

    /** A perk's own hits never set off perks (a Living Bomb's burst killing another monster stops there). */
    private static boolean quiet;

    static boolean has(ServerPlayer p, Spell spell, String name) {
        return SpellUpgrades.perk(p, spell, name);
    }

    /** Runs {@code r} with perks held off: for a perk's own hits. */
    static void quietly(Runnable r) {
        boolean was = quiet;
        quiet = true;
        try {
            r.run();
        } finally {
            quiet = was;
        }
    }

    /** A flat spell number for a tome with no damage of its own (Updraft, the Book of Souls, Skies). */
    static double flat(ServerPlayer p, double base) {
        return base * Balance.SPELL_DAMAGE * p.getAttributeValue(ModAttributes.SPELL_POWER);
    }

    static void mana(ServerPlayer p, double amount) {
        Progression.setMana(p, (float) Math.min(Progression.maxMana(p), Progression.mana(p) + amount));
    }

    /** Slowed by cold for {@code ticks}, and {@code points} of chill toward {@link Frostbite} (100 bites). */
    static void chill(LivingEntity e, ServerPlayer by, int ticks, int points) {
        e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 1), by);
        Frostbite.add(e, by, points);
    }

    /** Held fast: slowed so hard it cannot walk. */
    static void root(LivingEntity e, ServerPlayer by, int ticks) {
        e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 6), by);
    }

    // ---------------------------------------------------------------- free casts

    /** Echo Step's window for the free second blink. */
    static final int ECHO_TICKS = 80;
    /** Return Trip's window after arriving home: the Homeward cooldown is three minutes, so the window is longer. */
    static final int RETURN_TICKS = 6000;

    private record Free(Spell spell, long until) {}

    private static final java.util.Map<UUID, List<Free>> FREE = new java.util.concurrent.ConcurrentHashMap<>();

    static void grantFree(ServerPlayer p, Spell spell, int ticks) {
        var list = new ArrayList<>(FREE.getOrDefault(p.getUUID(), List.of()));
        list.removeIf(f -> f.spell() == spell);
        list.add(new Free(spell, p.level().getGameTime() + ticks));
        FREE.put(p.getUUID(), List.copyOf(list));
    }

    /** Whether {@code p}'s next cast of {@code spell} is free: no mana, no waiting. */
    static boolean free(ServerPlayer p, Spell spell) {
        for (Free f : FREE.getOrDefault(p.getUUID(), List.of()))
            if (f.spell() == spell && p.level().getGameTime() <= f.until()) return true;
        return false;
    }

    static void spendFree(ServerPlayer p, Spell spell) {
        var list = new ArrayList<>(FREE.getOrDefault(p.getUUID(), List.of()));
        list.removeIf(f -> f.spell() == spell);
        FREE.put(p.getUUID(), List.copyOf(list));
    }

    // ---------------------------------------------------------------- around a spell hit

    /** Before a spell hit: perks that change how hard it lands. */
    static double beforeHit(ServerPlayer caster, LivingEntity target, Spell spell, double amount) {
        if (quiet || spell == null) return amount;
        if (HexSpells.infected(target) && has(caster, Spell.CONTAGION, "Plague Bearer")) amount *= 1.3;
        amount = WorkSpells.beforeHit(caster, target, amount); // Hunter's Mark
        if (BossTrophies.concealed(caster) && has(caster, Spell.ECLIPSE, "Shadow Strike")) {
            amount *= 2;
            BossTrophies.reveal(caster);
        }
        return amount;
    }

    /** After a spell hit: the perks that strike, slow, heal or refund on a hit or a kill. */
    static void afterHit(ServerPlayer caster, LivingEntity target, Spell spell, double amount, boolean killed) {
        if (quiet || spell == null) return;
        quietly(() -> afterHitNow(caster, target, spell, amount, killed));
    }

    private static void afterHitNow(
            ServerPlayer caster, LivingEntity target, Spell spell, double amount, boolean killed) {
        ServerLevel level = caster.level();
        switch (spell) {
            case ARCANE_BOLT -> {
                if (has(caster, spell, "Hexing Bolt") && target.isAlive())
                    target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0), caster);
                if (has(caster, spell, "Mana Leech")) mana(caster, killed ? 10 : 3);
                if (has(caster, spell, "Splitting Bolt")) {
                    Vec3 from = SpellKit.chest(target);
                    for (LivingEntity o : nearestMonsters(caster, target, from, 8, 2)) {
                        SpellKit.line(level, from, SpellKit.chest(o), new DustParticleOptions(0xC9A8FF, 1f), .35);
                        Spells.hit(caster, o, amount * .5);
                    }
                }
            }
            case FIREBALL -> {
                if (has(caster, spell, "Blinding Smoke") && target instanceof Mob m && m.getTarget() == caster)
                    m.setTarget(null);
                if (killed && has(caster, spell, "Living Bomb"))
                    burst(caster, target.position().add(0, .5, 0), 3, amount * .5, ParticleTypes.FLAME);
            }
            case FROST_NOVA -> {
                if (has(caster, spell, "Glacial Mana")) mana(caster, 1);
            }
            case METEOR -> {
                if (has(caster, spell, "Impact Daze") && target.isAlive()) root(target, caster, 20);
            }
            case CHAIN_LIGHTNING -> {
                if (has(caster, spell, "Charged Mana")) mana(caster, 1);
                if (has(caster, spell, "Sparking") && target.isAlive()) target.igniteForSeconds(2);
                if (has(caster, spell, "Static Shock") && target.isAlive()) {
                    target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 2), caster);
                    target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 1), caster);
                }
            }
            case GRAVITY_WELL -> {
                if (has(caster, spell, "Leech Star")) caster.heal((float) (amount * .2));
                if (has(caster, spell, "Mana Star")) mana(caster, amount * .1);
                if (has(caster, spell, "Crushing Weight") && target.isAlive())
                    target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20, 1), caster);
            }
            case GLACIAL_SPIKE -> {
                if (has(caster, spell, "Ice Lance") && target.isAlive()) root(target, caster, 20);
                if (has(caster, spell, "Frost Guard"))
                    caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 60, 0, false, false, true));
            }
            case WATER_SPLASH -> {
                if (has(caster, spell, "Freezing Wave") && target.isAlive()) chill(target, caster, 60, 50);
                if (has(caster, spell, "Soaking Wave") && target.isAlive())
                    target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0), caster);
            }
            case CONTAGION -> {
                if (has(caster, spell, "Feverish") && target.isAlive())
                    target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0), caster);
                if (has(caster, spell, "Plague Glow") && target.isAlive())
                    target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0, false, false, false), caster);
            }
            case EARTHSHATTER -> {
                if (target.isAlive() && has(caster, spell, "Shrapnel")) FormSpells.bleed(caster, target, 60);
                if (has(caster, spell, "Stone Skin"))
                    caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 0, false, false, true));
                if (target.isAlive() && has(caster, spell, "Quicksand")) root(target, caster, 60);
                if (target.isAlive() && has(caster, spell, "Aftershock"))
                    Scheduler.later(20, () -> {
                        if (!target.isAlive() || !caster.isAlive()) return;
                        Vec3 at = target.position();
                        level.sendParticles(
                                new net.minecraft.core.particles.BlockParticleOption(
                                        ParticleTypes.BLOCK,
                                        level.getBlockState(
                                                target.blockPosition().below())),
                                at.x,
                                at.y + .1,
                                at.z,
                                20,
                                .4,
                                .1,
                                .4,
                                .15);
                        quietly(() -> Spells.hit(caster, target, amount * .5, Spell.EARTHSHATTER));
                    });
            }
            case SIPHON -> {
                if (has(caster, spell, "Mana Drain")) mana(caster, 2);
                if (has(caster, spell, "Wither Threads") && target.isAlive())
                    target.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0), caster);
                if (has(caster, spell, "Sapping Threads") && target.isAlive())
                    target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0), caster);
                if (killed && has(caster, spell, "Death Grip")) {
                    var tome = new ItemStack(ModItems.TOMES.get(Spell.SIPHON));
                    caster.getCooldowns().removeCooldown(caster.getCooldowns().getCooldownGroup(tome));
                }
            }
            default -> {}
        }
    }

    /** Up to {@code n} monsters nearest {@code from} within {@code r}, never {@code skip}. */
    static List<LivingEntity> nearestMonsters(ServerPlayer caster, LivingEntity skip, Vec3 from, double r, int n) {
        List<LivingEntity> near = new ArrayList<>(caster.level()
                .getEntitiesOfClass(
                        LivingEntity.class,
                        new AABB(from, from).inflate(r),
                        e -> e != skip
                                && e instanceof Enemy
                                && Spells.canHitArea(caster, e)
                                && e.position().distanceTo(from) <= r));
        near.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(from)));
        return near.size() > n ? near.subList(0, n) : near;
    }

    /** A burst of the current spell's damage round a point, with a ring and a puff to show its reach. */
    static void burst(ServerPlayer caster, Vec3 at, double radius, double amount, ParticleOptions particle) {
        ServerLevel level = caster.level();
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        level.sendParticles(particle, at.x, at.y, at.z, 30, radius / 3, .3, radius / 3, .05);
        Bosses.ring(level, at.subtract(0, .4, 0), radius, particle, (int) (radius * 10));
        SpellKit.sound(level, at, SoundEvents.GENERIC_EXPLODE.value(), .8f, 1.3f);
        quietly(() -> Spells.areaHit(caster, at, radius, amount, null));
    }

    /**
     * Burning ground: for {@code ticks}, every half second monsters within {@code radius} of {@code c} take
     * {@code amount} and catch fire, and flames show the patch.
     */
    static void firePatch(ServerPlayer caster, Spell spell, Vec3 at, double radius, int ticks, double amount) {
        ServerLevel level = caster.level();
        Vec3 c = ground(level, at);
        for (int t = 0; t <= ticks; t += 5) {
            int tick = t;
            Scheduler.later(t, () -> {
                if (caster.isRemoved() || caster.level() != level) return;
                for (int i = 0; i < (int) (radius * 6); i++) {
                    double a = level.getRandom().nextDouble() * Math.PI * 2,
                            r = Math.sqrt(level.getRandom().nextDouble()) * radius;
                    level.sendParticles(
                            i % 3 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME,
                            c.x + Math.cos(a) * r,
                            c.y + .1,
                            c.z + Math.sin(a) * r,
                            1,
                            0,
                            .05,
                            0,
                            .01);
                }
                if (tick % 10 == 0)
                    for (LivingEntity e : level.getEntitiesOfClass(
                            LivingEntity.class,
                            new AABB(c, c).inflate(radius, 2, radius),
                            e -> e instanceof Enemy && Spells.canHitArea(caster, e))) {
                        if (e.position().multiply(1, 0, 1).distanceTo(c.multiply(1, 0, 1)) > radius) continue;
                        quietly(() -> Spells.hit(caster, e, amount, spell));
                        e.igniteForSeconds(2);
                    }
            });
        }
    }

    /**
     * Frozen ground: for {@code ticks}, monsters within {@code radius} of {@code c} are chilled each half second,
     * and frost shows the patch.
     */
    static void frostPatch(ServerPlayer caster, Vec3 at, double radius, int ticks) {
        ServerLevel level = caster.level();
        Vec3 c = ground(level, at);
        for (int t = 0; t <= ticks; t += 10) {
            Scheduler.later(t, () -> {
                if (caster.isRemoved() || caster.level() != level) return;
                Bosses.ring(level, c.add(0, .1, 0), radius, ParticleTypes.SNOWFLAKE, (int) (radius * 10));
                level.sendParticles(
                        new DustParticleOptions(0xCFF6FF, 1f), c.x, c.y + .1, c.z, 12, radius / 2, 0, radius / 2, 0);
                for (LivingEntity e : level.getEntitiesOfClass(
                        LivingEntity.class,
                        new AABB(c, c).inflate(radius, 2, radius),
                        e -> e instanceof Enemy && Spells.canHitArea(caster, e))) chill(e, caster, 40, 20);
            });
        }
    }

    /** The floor under a point a few blocks up (a blast at a monster's chest burns the ground under it). */
    static Vec3 ground(ServerLevel level, Vec3 at) {
        BlockPos b = BlockPos.containing(at);
        for (int i = 0; i < 4 && level.getBlockState(b.below()).isAir(); i++) b = b.below();
        return new Vec3(at.x, b.getY(), at.z);
    }

    // ---------------------------------------------------------------- any damage, any death

    /** Every hit before armour: Spirit Ward and Arrow Mirror soften what reaches the player. */
    static float incoming(LivingEntity target, DamageSource source, float amount) {
        if (!(target instanceof ServerPlayer p) || amount <= 0) return amount;
        amount = Cage.shadowed(source.getEntity(), amount); // Long Shadow
        amount = StaffFlight.incoming(p, amount); // Wind Shield
        amount = LightSpells.incoming(p, source, amount); // Steadfast
        if (ArcaneSpells.warded(p)
                && source.getDirectEntity() instanceof Projectile
                && has(p, Spell.MIRROR_WARD, "Arrow Mirror")) return 0;
        if (source.getEntity() instanceof LivingEntity && has(p, Spell.SOULS, "Spirit Ward") && spiritsNear(p) >= 2)
            return amount * .85f;
        return amount;
    }

    private static int spiritsNear(ServerPlayer p) {
        UUID me = p.getUUID();
        return p.level()
                .getEntitiesOfClass(
                        Mob.class,
                        p.getBoundingBox().inflate(16),
                        m -> m.isAlive()
                                && m.getAttached(ModData.SOUL_KIND) != null
                                && me.equals(m.getAttached(ModData.THRALL_MASTER)))
                .size();
    }

    /** After any hit landed: a spirit wolf's Frost Fangs. */
    static void afterDamage(LivingEntity hurt, DamageSource source, float taken) {
        if (taken <= 0 || !hurt.isAlive()) return;
        if (source.getEntity() instanceof SpiritWolf w
                && w.getOwner() instanceof ServerPlayer owner
                && has(owner, Spell.SPIRIT_WOLVES, "Frost Fangs")) chill(hurt, owner, 40, 25);
    }

    /** Any death: Spirit Return, Vengeful Dead and Soul Harvest. */
    static void died(LivingEntity dead, DamageSource source) {
        if (!(dead.level() instanceof ServerLevel level)) return;
        if (dead instanceof SpiritWolf w
                && w.getOwner() instanceof ServerPlayer owner
                && has(owner, Spell.SPIRIT_WOLVES, "Spirit Return")) {
            SpellKit.line(level, SpellKit.chest(w), SpellKit.chest(owner), ParticleTypes.SOUL, .5);
            owner.heal(6);
        }
        ServerPlayer master = master(level, dead);
        if (master != null && dead.getAttached(ModData.SOUL_KIND) != null && has(master, Spell.SOULS, "Vengeful Dead"))
            Spells.withSpell(
                    Spell.SOULS,
                    () -> burst(master, dead.position().add(0, .5, 0), 3, flat(master, 12), ParticleTypes.SOUL));
        if (source.getEntity() instanceof LivingEntity killer
                && killer.getAttached(ModData.SOUL_KIND) != null
                && dead instanceof Enemy) {
            ServerPlayer owner = master(level, killer);
            if (owner != null && has(owner, Spell.SOULS, "Soul Harvest")) {
                mana(owner, 5);
                SpellKit.line(level, SpellKit.chest(dead), SpellKit.chest(owner), ParticleTypes.SOUL, .6);
            }
        }
    }

    private static ServerPlayer master(ServerLevel level, LivingEntity e) {
        UUID id = e.getAttached(ModData.THRALL_MASTER);
        return id == null ? null : level.getServer().getPlayerList().getPlayer(id);
    }
}
