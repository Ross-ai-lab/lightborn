package lightborn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Casting, cooldowns, and what each spell does. Server side only; a cast starts from StaffItem.use (right-click). */
public final class Spells {
    private Spells() {}

    /** Arcane Bolts and Fireballs in flight ({@link ArcaneSpells}, {@link FireSpells}). */
    static final List<Projectile> PROJECTILES = new ArrayList<>();
    /** Set while a spell hit is being applied, so damage numbers can show crits. */
    static boolean critHit;

    // ---- Casting ----

    /**
     * Casts the spell of {@code tome} (the off-hand stack). Needs a staff in the main hand, the tome off cooldown
     * and enough mana. The cooldown lives on the tome item (vanilla item cooldowns, drawn over the slot); tomes never
     * wear. The tome's {@link SpellUpgrades} set
     * its mana cost, cooldown and power here; each spell reads its own upgraded numbers in {@link #performNow}.
     */
    public static boolean cast(ServerPlayer player, ItemStack tome) {
        Spell spell = TomeItem.spellOf(tome);
        if (spell == null || !player.isAlive() || player.isSpectator()) return false;
        if (!ModItems.isStaff(player.getMainHandItem()) || LightGear.broken(player.getMainHandItem())) {
            player.sendSystemMessage(
                    Component.literal(
                                    LightGear.broken(player.getMainHandItem())
                                            ? "Your staff is broken: mend it with glowstone dust at an anvil"
                                            : "Hold a staff to cast spells")
                            .withStyle(ChatFormatting.LIGHT_PURPLE),
                    true);
            player.level()
                    .playSound(
                            null,
                            player.getX(),
                            player.getY(),
                            player.getZ(),
                            SoundEvents.UI_BUTTON_CLICK,
                            SoundSource.PLAYERS,
                            0.4f,
                            0.6f);
            return false;
        }
        // Echo Step and Return Trip: one free cast inside their window, past the cooldown and without mana.
        boolean echo = Perks.free(player, spell);
        if (player.getCooldowns().isOnCooldown(tome) && !echo) return false;
        if (spell.kind == Spell.Kind.ATTACK && spell.baseDamage > 0 && WardCombat.inside(player)) {
            WardCombat.warn(player);
            return false;
        }
        float mana = Progression.mana(player);
        int[] chosen = SpellUpgrades.chosen(tome);
        double cost = echo || ArcaneSpells.flowing(player) // Wellspring
                ? 0
                : spell.mana * Math.max(.1, 1 + SpellUpgrades.total(spell, chosen, SpellUpgrades.Stat.COST));
        if (mana < cost) {
            player.sendSystemMessage(
                    Component.literal(
                                    "Not enough mana (" + spell.label + " needs " + Math.round(cost * 10) / 10.0 + ")")
                            .withStyle(ChatFormatting.AQUA),
                    true);
            player.level()
                    .playSound(
                            null,
                            player.getX(),
                            player.getY(),
                            player.getZ(),
                            SoundEvents.UI_BUTTON_CLICK,
                            SoundSource.PLAYERS,
                            0.4f,
                            0.6f);
            return false;
        }
        double basePower =
                damage(player, spell) * Math.max(.1, 1 + SpellUpgrades.total(spell, chosen, SpellUpgrades.Stat.POWER));
        var staff = player.getMainHandItem();
        var charge = LightGearPerks.spellCharge(staff);
        if (!CastContext.perform(
                player,
                spell,
                tome,
                basePower,
                false,
                charge == null ? null : charge.element(),
                () -> perform(player, spell))) return false;
        if (charge != null) charge.commit(staff);
        // From the mana left after the cast, so mana a spell gave back as it landed (Mana Leech) is kept.
        Progression.setMana(player, (float) Math.max(0, Progression.mana(player) - cost));
        ArcaneSpells.afterCast(player, spell, tome, basePower); // Twincast
        if (echo) {
            Perks.spendFree(player, spell);
            return true;
        }
        if (spell == Spell.BLINK && SpellUpgrades.perk(player, spell, "Echo Step"))
            Perks.grantFree(player, spell, Perks.ECHO_TICKS);
        if (LightGearPerks.afterCast(player)) return true; // Echoing Staff: no cooldown this time
        int cooldown = ArcaneSpells.cooldown(player, spell, cooldownTicks(player, spell, tome)); // Rushing Waters
        if (cooldown > 0) player.getCooldowns().addCooldown(tome, cooldown);
        return true;
    }

    /** Twincast's echo: the spell again, from a copy of the tome, at the given power, with no mana or cooldown. */
    static void recast(ServerPlayer player, Spell spell, ItemStack tome, double power) {
        CastContext.perform(player, spell, tome, power, true, () -> perform(player, spell));
    }

    /** The tome's cooldown: the spell's own, shortened by its cooldown upgrades and the player's Cooldown Speed. */
    public static int cooldownTicks(ServerPlayer player, Spell spell, ItemStack tome) {
        double base = spell.cooldownTicks
                * Math.max(.1, 1 + SpellUpgrades.total(spell, SpellUpgrades.chosen(tome), SpellUpgrades.Stat.COOLDOWN));
        if (base <= 0) return 0;
        return (int) Math.max(4, Math.round(base / player.getAttributeValue(ModAttributes.COOLDOWN_SPEED)));
    }

    /** Damage before crit and upgrades: base x global scale x spell power x trophy. */
    public static double damage(Player player, Spell spell) {
        double mastery = 1.0;
        if ((spell == Spell.FIREBALL || spell == Spell.METEOR) && BossTrophies.holds(player, BossKind.OZRIK)) {
            mastery *= Balance.ASHBRINGER_FIRE_MULTIPLIER;
        }
        return spell.baseDamage * Balance.SPELL_DAMAGE * player.getAttributeValue(ModAttributes.SPELL_POWER) * mastery;
    }

    static void withSpell(Spell spell, Runnable action) {
        var old = CONTEXT.get();
        CONTEXT.set(spell);
        try {
            action.run();
        } finally {
            CONTEXT.set(old);
        }
    }

    static final ThreadLocal<Spell> CONTEXT = new ThreadLocal<>();

    private static boolean perform(ServerPlayer p, Spell spell) {
        var old = CONTEXT.get();
        CONTEXT.set(spell);
        try {
            boolean done = performNow(p, spell);
            if (done) CastPerks.after(p, spell);
            return done;
        } finally {
            CONTEXT.set(old);
        }
    }

    /** Casts through the spell's school ({@link SpellSchools}); true when it took effect. */
    private static boolean performNow(ServerPlayer p, Spell spell) {
        double dmg = CastContext.power(p, spell, damage(p, spell));
        return SpellSchools.perform(p.level(), p, spell, dmg);
    }

    static void sound(ServerLevel level, Entity at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.getX(), at.getY(), at.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
    }

    // ---- Targets and hits ----

    /** Direct hits may hit any creature except players, pets, villagers, armour stands and the caster's thralls. */
    static boolean canHitDirect(ServerPlayer caster, Entity e) {
        if (!(e instanceof LivingEntity living) || !living.isAlive() || e == caster) return false;
        if (e instanceof Player || e instanceof ArmorStand || e instanceof AbstractVillager) return false;
        if (e instanceof LightDragon d && d.role() != 1 || Monsters.isAtmosphericProp(e)) return false;
        if (e instanceof Taunt.Totem) return false; // a Taunt totem is on the players' side
        if (SummonSpells.thrallOwner(e) == caster) return false; // your own thrall (a spirit or a Bonecaller skeleton)
        return !(e instanceof OwnableEntity own && own.getOwner() != null);
    }

    /** Area hits (nova, blast, meteor, chain jumps) hit the same creatures as direct ones, animals included. */
    static boolean canHitArea(ServerPlayer caster, Entity e) {
        return canHitDirect(caster, e);
    }

    /** Applies spell damage with crit roll. Spell damage ignores invulnerability frames and armour. */
    static void hit(ServerPlayer caster, LivingEntity target, double amount) {
        ServerLevel level = caster.level();
        if (WardCombat.prevents(target, level.damageSources().indirectMagic(caster, caster))) return;
        amount = Perks.beforeHit(caster, target, CONTEXT.get(), amount);
        StoryBosses.hit(caster, target, CONTEXT.get(), amount);
        if (LightDragons.night(level) && caster.getMainHandItem().is(LightGear.ITEMS.get(5)))
            StoryGlow.place(level, target.blockPosition());
        boolean crit = level.getRandom().nextDouble() < caster.getAttributeValue(ModAttributes.SPELL_CRIT);
        if (crit) amount *= caster.getAttributeValue(ModAttributes.CRIT_DAMAGE);
        // 26.3 keeps the hurt cooldown in LivingEntity.damageCooldownTime (Entity.invulnerableTime no longer gates
        // hits):
        // inside it a new hit only deals what it exceeds the last one by.
        target.damageCooldownTime = 0;
        critHit = crit;
        boolean alive = target.isAlive();
        boolean landed;
        try {
            landed = target.hurtServer(level, level.damageSources().indirectMagic(caster, caster), (float) amount);
        } finally {
            critHit = false;
        }
        boolean killed = alive && target.isDeadOrDying();
        if (killed) TomeAwakening.killed(caster, target, CONTEXT.get());
        Perks.afterHit(caster, target, CONTEXT.get(), amount, killed);
        if (landed && !killed) LightGearPerks.spellHit(caster, target);
        if (crit)
            level.sendParticles(
                    ParticleTypes.ENCHANTED_HIT,
                    target.getX(),
                    target.getY() + target.getBbHeight() * 0.6,
                    target.getZ(),
                    12,
                    0.3,
                    0.3,
                    0.3,
                    0.3);
    }

    static void hit(ServerPlayer caster, LivingEntity target, double amount, Spell school) {
        withSpell(school, () -> hit(caster, target, amount));
    }

    static void hit(ServerPlayer caster, LivingEntity target, double amount, Spell school, CastContext.Cast cast) {
        if (cast == null) hit(caster, target, amount, school);
        else CastContext.resume(cast, () -> hit(caster, target, amount, school));
    }

    static void areaHit(ServerPlayer caster, Vec3 center, double radius, double amount, Consumer<LivingEntity> extra) {
        ServerLevel level = caster.level();
        for (LivingEntity e : level.getEntitiesOfClass(
                LivingEntity.class, new AABB(center, center).inflate(radius), e -> canHitArea(caster, e))) {
            if (e.distanceToSqr(center) > radius * radius) continue;
            hit(caster, e, amount);
            if (extra != null) extra.accept(e);
        }
    }

    /** A flat ring of particles, one roughly every block of its edge, so a spell's reach shows on the ground. */
    static void ring(ServerLevel level, Vec3 c, double radius, ParticleOptions particle) {
        int n = Math.max(16, (int) (radius * 2 * Math.PI));
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            level.sendParticles(
                    particle, c.x + Math.cos(a) * radius, c.y, c.z + Math.sin(a) * radius, 1, 0, 0.05, 0, 0.01);
        }
    }

    // ---- Farming ----

    /** Positions in a flat cylinder round the caster's feet: radius {@code r}, two blocks down and up. */
    static Iterable<BlockPos> around(ServerPlayer p, int r) {
        BlockPos c = p.blockPosition();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-r, -2, -r), c.offset(r, 2, r))) {
            int dx = pos.getX() - c.getX(), dz = pos.getZ() - c.getZ();
            if (dx * dx + dz * dz <= r * r + r) out.add(pos.immutable());
        }
        return out;
    }

    /** The creature the player is looking at (generous aim cone), up to {@code range} blocks, not behind walls. */
    public static LivingEntity lookTarget(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        ServerLevel level = p.level();
        BlockHitResult wall = level.clip(new ClipContext(
                eye, eye.add(look.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double max = wall.getType() == HitResult.Type.MISS
                ? range
                : wall.getLocation().distanceTo(eye) + 0.5;
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        AABB area = p.getBoundingBox().expandTowards(look.scale(max)).inflate(2);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, e -> canHitDirect(p, e))) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double along = to.dot(look);
            if (along <= 0 || along > max) continue;
            double off = to.subtract(look.scale(along)).length();
            double allowed = 1.2 + e.getBbWidth() / 2 + along * 0.03;
            if (off > allowed) continue;
            double score = off + along * 0.05;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    // ---- Projectiles (simulated server side, drawn with particles) ----

    static final class Projectile {
        final ServerPlayer owner;
        final Spell spell;
        Vec3 pos;
        final Vec3 vel;
        final CastContext.Cast cast = CastContext.elemental();
        final double damage;
        final double radius;
        int life;
        int pierce, burnSeconds = 4;
        final java.util.Set<LivingEntity> pierced = new java.util.HashSet<>();

        Projectile(ServerPlayer owner, Spell spell, Vec3 pos, Vec3 vel, double damage, double radius, int life) {
            this.owner = owner;
            this.spell = spell;
            this.pos = pos;
            this.vel = vel;
            this.damage = damage;
            this.radius = radius;
            this.life = life;
        }
    }

    static void tickProjectiles() {
        for (Iterator<Projectile> it = PROJECTILES.iterator(); it.hasNext(); ) {
            Projectile pr = it.next();
            if (pr.owner.isRemoved() || --pr.life < 0) {
                it.remove();
                continue;
            }
            ServerLevel level = pr.owner.level();
            Vec3 next = pr.pos.add(pr.vel);
            BlockHitResult block = level.clip(
                    new ClipContext(pr.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pr.owner));
            Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
            LivingEntity struck = null;
            double bestDist = Double.MAX_VALUE;
            AABB sweep = new AABB(pr.pos, end).inflate(0.6);
            for (LivingEntity e : level.getEntitiesOfClass(
                    LivingEntity.class, sweep, e -> canHitDirect(pr.owner, e) && !pr.pierced.contains(e))) {
                var clip = e.getBoundingBox().inflate(0.35).clip(pr.pos, end);
                if (clip.isPresent()) {
                    double d = clip.get().distanceToSqr(pr.pos);
                    if (d < bestDist) {
                        bestDist = d;
                        struck = e;
                    }
                }
            }
            trail(level, pr, pr.pos, end);
            if (struck != null) {
                impact(level, pr, struck.position().add(0, struck.getBbHeight() * 0.5, 0), struck);
                if (pr.pierce > 0 && block.getType() == HitResult.Type.MISS) {
                    // Arcane Lance: flies on through, never striking the same creature twice.
                    pr.pierce--;
                    pr.pierced.add(struck);
                    pr.pos = next;
                } else it.remove();
            } else if (block.getType() != HitResult.Type.MISS) {
                impact(level, pr, end, null);
                it.remove();
            } else {
                pr.pos = next;
            }
        }
    }

    private static void trail(ServerLevel level, Projectile pr, Vec3 a, Vec3 b) {
        int steps = 4;
        Vec3 eye = pr.owner.getEyePosition();
        for (int i = 0; i < steps; i++) {
            Vec3 p = a.lerp(b, (double) i / steps);
            // Nothing right at the caster's eyes: up close, trail particles fill the screen.
            if (p.distanceToSqr(eye) < 2.5 * 2.5) continue;
            if (pr.spell == Spell.SPLINTER) {
                level.sendParticles(new DustParticleOptions(0xFFF0B0, .9f), p.x, p.y, p.z, 1, 0.01, 0.01, 0.01, 0);
                if (i == 0) level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            } else if (pr.spell == Spell.ARCANE_BOLT) {
                level.sendParticles(new DustParticleOptions(0xB98CFF, 1.3f), p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0);
                if (i == 0) level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0.005);
            } else {
                level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 2, 0.08, 0.08, 0.08, 0.01);
                if (i % 2 == 0) level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.005);
            }
        }
    }

    static void impact(ServerLevel level, Projectile pr, Vec3 at, LivingEntity struck) {
        var old = CONTEXT.get();
        CONTEXT.set(pr.spell);
        try {
            if (pr.cast == null) impactNow(level, pr, at, struck);
            else CastContext.resume(pr.cast, () -> impactNow(level, pr, at, struck));
        } finally {
            CONTEXT.set(old);
        }
    }

    private static void impactNow(ServerLevel level, Projectile pr, Vec3 at, LivingEntity struck) {
        if (LightDragons.night(level) && pr.owner.getMainHandItem().is(LightGear.ITEMS.get(5)))
            StoryGlow.place(level, BlockPos.containing(at));
        if (pr.spell == Spell.SPLINTER) {
            level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 4, 0.1, 0.1, 0.1, 0.04);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.PLAYERS, 0.6f, 1.8f);
            if (struck != null) {
                hit(pr.owner, struck, pr.damage);
                LightSpells.splinterHit(pr.owner, struck);
            }
        } else if (pr.spell == Spell.BLAZE_FORM) {
            level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 16, 0.25, 0.25, 0.25, 0.04);
            level.sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 2, 0.2, 0.2, 0.2, 0);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.8f, 1.3f);
            if (pr.radius > 0) {
                // Bursting Flame: the ball bursts and burns everything round where it lands.
                level.sendParticles(
                        ParticleTypes.FLAME, at.x, at.y, at.z, 30, pr.radius / 2.5, 0.3, pr.radius / 2.5, 0.03);
                areaHit(pr.owner, at, pr.radius, pr.damage, e -> e.igniteForSeconds(pr.burnSeconds));
            }
            if (struck != null && (pr.radius <= 0 || !canHitArea(pr.owner, struck))) {
                hit(pr.owner, struck, pr.damage);
                struck.igniteForSeconds(pr.burnSeconds);
            }
        } else if (pr.spell == Spell.ARCANE_BOLT) {
            ParticleOptions burst = new DustParticleOptions(0xD4B8FF, 1.5f);
            level.sendParticles(burst, at.x, at.y, at.z, 14, 0.25, 0.25, 0.25, 0.05);
            level.sendParticles(ParticleTypes.WITCH, at.x, at.y, at.z, 6, 0.2, 0.2, 0.2, 0.05);
            level.playSound(
                    null, at.x, at.y, at.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 0.8f, 1.5f);
            if (struck != null) hit(pr.owner, struck, pr.damage);
        } else {
            level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 3, 0.6, 0.6, 0.6, 0);
            // Flames stay inside the blast radius so the visual matches the area that takes damage.
            level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 60, pr.radius / 2.5, 0.4, pr.radius / 2.5, 0.03);
            Bosses.ring(
                    level, new Vec3(at.x, at.y - 0.4, at.z), pr.radius, new DustParticleOptions(0xFF7A1A, 1.0f), 28);
            level.sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 8, 0.5, 0.3, 0.5, 0);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.2f, 1.3f);
            areaHit(pr.owner, at, pr.radius, pr.damage, e -> e.igniteForSeconds(pr.burnSeconds));
            if (pr.spell == Spell.FIREBALL && SpellUpgrades.perk(pr.owner, Spell.FIREBALL, "Lingering Flames"))
                Perks.firePatch(pr.owner, Spell.FIREBALL, at, pr.radius, 80, pr.damage * .2);
            // The direct target is hit even if it is a non-hostile creature the player aimed at.
            if (struck != null && !canHitArea(pr.owner, struck)) hit(pr.owner, struck, pr.damage);
        }
    }

    /**
     * A spell projectile of {@code spell}'s look (Splinter's light, Blaze Form's fire) from {@code from}, flying
     * {@code vel} a tick for {@code life} ticks; returned so the caller can set its pierce.
     */
    static Projectile shoot(ServerPlayer p, Spell spell, Vec3 from, Vec3 vel, double dmg, double radius, int life) {
        var pr = new Projectile(p, spell, from, vel, dmg, radius, life);
        PROJECTILES.add(pr);
        return pr;
    }

    /** Clears a player's spell cooldowns (between self-test casts). */
    static void forget(ServerPlayer player) {
        for (var tome : ModItems.TOMES.values())
            player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(new ItemStack(tome)));
    }

    static int liveProjectiles() {
        return PROJECTILES.size();
    }
}
