package lightborn;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;

/** Monster levels, the zombie kinds' traits and the damage maths that makes monsters hit harder. */
public final class Monsters {
    private Monsters() {}

    public static MonsterInfo info(Entity entity) {
        return entity.getAttached(ModData.MONSTER);
    }

    public static boolean isMonster(Entity entity) {
        return entity instanceof Mob && entity instanceof Enemy && !isAtmosphericProp(entity);
    }
    /** From The Fog uses tiny invisible vanilla mobs as display/bed-check props, not combat enemies. */
    public static boolean isAtmosphericProp(Entity entity) {
        return entity.entityTags().stream().anyMatch(tag -> tag.startsWith("watching."));
    }

    static boolean isVanillaBoss(Entity entity) {
        return entity instanceof EnderDragon || entity instanceof WitherBoss || entity instanceof Warden;
    }

    /** Base health from which a monster counts as already big: never promoted to a boss. */
    static final double HEAVY_HEALTH = 80;

    /**
     * Monsters that are already set-piece fights in their own right (vanilla bosses, mutants, other mods'
     * bosses): they get a level, but are never promoted to Lightborn bosses on top of that.
     */
    /** A monster from a block spawner: no rolled gear, tomes or Sparks. */
    public static boolean fromSpawner(Entity entity) {
        return entity.getAttachedOrElse(ModData.SPAWNER_MONSTER, false);
    }

    public static boolean isHeavy(Mob mob) {
        if (isVanillaBoss(mob)) return true;
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        return hp != null && hp.getBaseValue() >= HEAVY_HEALTH;
    }

    /** Called when any entity enters a world. New monsters get their rank once; it is saved with them. */
    static void onLoad(Entity entity, ServerLevel level) {
        if (StoryBosses.hostile(entity)) return;
        if (!isMonster(entity)) return;
        Mob mob = (Mob) entity;
        MonsterInfo info = info(mob);
        if (info == null) {
            RandomSource random = level.getRandom();
            if (!fromSpawner(mob)
                    && mob.getAttached(ModData.THRALL_MASTER) == null
                    && !Souls.summoned(mob)
                    && !isHeavy(mob)
                    && random.nextDouble() < Balance.WILD_BOSS_CHANCE
                    && Bosses.canSpawnWild(level, mob.blockPosition())
                    && Bosses.replaceWild(level, mob, random)) return;
            setRank(mob, MonsterInfo.NORMAL);
            info = info(mob);
        } else if (info.rank() == MonsterInfo.OLD_CHAMPION) {
            unChampion(mob, info);
        } else {
            settleHealth(mob, info);
        }
        if (info.rank() == MonsterInfo.BOSS) {
            mob.setCustomNameVisible(false); // bosses saved before the owner's no-name-banners rule
            Bosses.track(mob);
        }
        if (info.rank() == MonsterInfo.BOSS || isHeavy(mob)) Wilds.trackGreat(mob);
    }

    /**
     * A champion saved before champions were removed (owner, 2026-11-09) becomes the plain monster it was: its
     * level without the champion's +2, no champion size, speed, armour or knockback bonus, and no yellow name banner
     * (a name the player gave it with a name tag has no colour and stays).
     */
    static void unChampion(Mob mob, MonsterInfo info) {
        for (var attr : List.of(
                Attributes.SCALE, Attributes.MOVEMENT_SPEED, Attributes.ARMOR, Attributes.KNOCKBACK_RESISTANCE)) {
            AttributeInstance inst = mob.getAttribute(attr);
            if (inst == null) continue;
            for (String old : List.of("champion_scale", "champion_swift", "champion_armor", "champion_giant"))
                inst.removeModifier(ModItems.id(old));
        }
        Component name = mob.getCustomName();
        if (name != null
                && name.getStyle().getColor() != null
                && name.getStyle().getColor().equals(TextColor.fromLegacyFormat(ChatFormatting.YELLOW))) {
            mob.setCustomName(null);
        }
        mob.setCustomNameVisible(false);
        setRank(mob, MonsterInfo.NORMAL);
    }

    /**
     * A monster saved while monsters had levels still carries its level's extra health. Its modifier is set to what
     * its rank gives now, without refilling the health it has lost.
     */
    static void settleHealth(Mob mob, MonsterInfo info) {
        AttributeInstance inst = mob.getAttribute(Attributes.MAX_HEALTH);
        if (inst == null) return;
        Identifier id = ModItems.id("level_health");
        AttributeModifier old = inst.getModifier(id);
        double wanted = healthMultiplier(info) - 1;
        if (old != null && Math.abs(old.amount() - wanted) < 1e-6) return;
        if (old == null && wanted == 0) return;
        modifier(mob, Attributes.MAX_HEALTH, "level_health", wanted, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        if (mob.getHealth() > mob.getMaxHealth()) mob.setHealth(mob.getMaxHealth());
    }

    /**
     * Saves the monster's rank and its health: a normal monster keeps its own natural health, a boss gets the fixed
     * boss multiple. Health is refilled to the new maximum.
     */
    public static void setRank(Mob mob, int rank) {
        if (rank == MonsterInfo.BOSS) mob.addTag("ts.sa.verified");
        MonsterInfo info = new MonsterInfo(rank);
        mob.setAttached(ModData.MONSTER, info);
        modifier(
                mob,
                Attributes.MAX_HEALTH,
                "level_health",
                healthMultiplier(info) - 1,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        mob.setHealth(mob.getMaxHealth());
    }

    public static double healthMultiplier(MonsterInfo info) {
        return info.rank() == MonsterInfo.BOSS ? Balance.BOSS_HEALTH : 1;
    }

    /**
     * Minecraft caps max health at 1024. A monster that should have more takes proportionally less
     * damage instead, so it is exactly as tough as intended. Returns intended / actual max health (>= 1).
     */
    public static double overflow(LivingEntity e) {
        MonsterInfo info = info(e);
        AttributeInstance inst = e.getAttribute(Attributes.MAX_HEALTH);
        if (info == null || inst == null) return 1.0;
        double wanted = inst.getBaseValue() * healthMultiplier(info);
        return Math.max(1.0, wanted / Math.max(1, e.getMaxHealth()));
    }

    static void modifier(
            LivingEntity e, Holder<Attribute> attr, String name, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = e.getAttribute(attr);
        if (inst == null) return;
        Identifier id = ModItems.id(name);
        inst.removeModifier(id);
        inst.addPermanentModifier(new AttributeModifier(id, amount, op));
    }

    /** Damage multiplier for hits coming from this attacker. */
    public static double damageMultiplier(Entity attacker) {
        MonsterInfo info = attacker == null ? null : info(attacker);
        if (info == null) return 1.0;
        double m = 1 + Souls.bonus(attacker.getAttachedOrElse(ModData.SOUL_RANK, 1));
        if (info.rank() == MonsterInfo.BOSS) m *= Balance.BOSS_DAMAGE;
        return m;
    }

    /** Called from the LivingEntity mixin for every hit, before armour. */
    public static float modifyIncomingDamage(
            LivingEntity target, ServerLevel level, DamageSource source, float amount) {
        if (amount <= 0) return amount;
        amount = LightGearPerks.modify(target, source, amount);
        if (amount <= 0) return 0;
        amount = Crits.roll(target, level, source, amount);
        Entity attacker = source.getEntity();
        // Monsters do not get stronger against each other (infighting stays vanilla), except a thrall's hits.
        if (!isMonster(target)) amount *= (float) damageMultiplier(attacker);
        else if (SummonSpells.isThrall(attacker))
            amount *= (float) (damageMultiplier(attacker) * SummonSpells.THRALL_POWER);
        amount = ArcaneSpells.wardSoak(target, source, amount);
        amount = Perks.incoming(target, source, amount);
        amount = Bosses.modifyDamage(target, amount);
        MonsterInfo info = info(target);
        if (info != null && !target.entityTags().contains(Bosses.SHADOW_TAG)) amount /= (float) overflow(target);
        return amount;
    }

    /**
     * After a hit landed: the zombie kinds' traits. A vampire zombie heals by part of what its bite took (and a
     * few dark-red hearts show it feeding); a darkness zombie's touch slows (Slowness II, 3 seconds).
     */
    static void afterDamage(LivingEntity target, DamageSource source, float taken) {
        if (taken <= 0 || source.getDirectEntity() != source.getEntity())
            return; // melee only, not arrows or explosions
        Entity attacker = source.getEntity();
        if (attacker instanceof VampireZombie vampire && vampire.isAlive()) {
            vampire.heal(taken * VampireZombie.FEED);
            if (vampire.level() instanceof ServerLevel level) {
                level.sendParticles(
                        ParticleTypes.DAMAGE_INDICATOR,
                        vampire.getX(),
                        vampire.getY() + vampire.getBbHeight() * 0.8,
                        vampire.getZ(),
                        3,
                        0.25,
                        0.2,
                        0.25,
                        0.1);
            }
        }
        if (attacker instanceof DarknessZombie)
            target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1), attacker);
    }

    /** A boss's minion: a normal monster of the same type next to it, already ranked (skips random promotion). */
    public static Mob spawnMinion(ServerLevel level, Mob master) {
        @SuppressWarnings("unchecked")
        EntityType<? extends Mob> type = (EntityType<? extends Mob>) master.getType();
        Mob minion = VariantCompat.keepKind(type.create(level, EntitySpawnReason.MOB_SUMMONED));
        if (minion == null) return null;
        double angle = level.getRandom().nextDouble() * Math.PI * 2;
        minion.setPos(master.getX() + Math.cos(angle) * 2, master.getY(), master.getZ() + Math.sin(angle) * 2);
        setRank(minion, MonsterInfo.NORMAL);
        if (master.getTarget() != null) minion.setTarget(master.getTarget());
        level.addFreshEntity(minion);
        level.sendParticles(
                ParticleTypes.LARGE_SMOKE, minion.getX(), minion.getY() + 0.5, minion.getZ(), 15, 0.3, 0.5, 0.3, 0.02);
        return minion;
    }
}
