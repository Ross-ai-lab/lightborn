package lightborn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Taunt (owner, 2026-11-18): a totem of light rises a few blocks ahead of the caster for ten seconds, chiming, and every
 * monster within {@link #RADIUS} blocks turns on it. The totem has the caster's own maximum health and armour, and it
 * falls when they are spent. Bosses ignore it. A monster struck by a player stops being taunted and goes for that
 * player as usual. Players and their spells never hurt a totem.
 */
public final class Taunt {
    private Taunt() {}

    static final int TICKS = 200, RADIUS = 12;
    public static EntityType<Totem> TYPE;

    /** Live totems, and per totem the monsters it holds and the ones a player has since pulled off it. */
    private static final Map<Totem, Held> LIVE = new LinkedHashMap<>();

    private record Held(UUID caster, long until, int radius, List<Mob> taunted, Set<Mob> broken) {}

    static void init() {
        var key = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("light_totem"));
        TYPE = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                key,
                FabricEntityType.Builder.<Totem>createMob(
                                Totem::new, MobCategory.MISC, b -> b.defaultAttributes(Totem::createAttributes))
                        .sized(.9f, 2.2f)
                        .eyeHeight(1.5f)
                        .clientTrackingRange(10)
                        .build(key));
    }

    /** The light totem: still, unsaved, struck by monsters only. */
    public static final class Totem extends PathfinderMob {
        public Totem(EntityType<? extends Totem> type, Level level) {
            super(type, level);
            setNoAi(true);
            setPersistenceRequired();
        }

        static AttributeSupplier.Builder createAttributes() {
            return PathfinderMob.createMobAttributes()
                    .add(Attributes.MAX_HEALTH, 20)
                    .add(Attributes.ARMOR, 0)
                    .add(Attributes.ARMOR_TOUGHNESS, 0)
                    .add(Attributes.KNOCKBACK_RESISTANCE, 1);
        }

        @Override
        public boolean shouldBeSaved() {
            return false;
        }

        @Override
        public boolean isPushable() {
            return false;
        }

        @Override
        public boolean removeWhenFarAway(double distance) {
            return false;
        }
    }

    /** The cast: a totem on the ground a few blocks ahead, taking the caster's health and armour. */
    static boolean cast(ServerPlayer p) {
        ServerLevel level = p.level();
        Vec3 look = p.getLookAngle().multiply(1, 0, 1);
        Vec3 ahead = look.lengthSqr() < 1e-4
                ? p.position()
                : p.position().add(look.normalize().scale(3));
        BlockPos column = BlockPos.containing(ahead);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        double y = Math.abs(ground - p.getY()) <= 3 ? ground : p.getY();
        Totem totem = TYPE.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (totem == null) return false;
        totem.snapTo(column.getX() + .5, y, column.getZ() + .5, p.getYRot() + 180, 0);
        // The caster's own health and armour (Stout Totem: half as much health again).
        double health = p.getMaxHealth() * SpellUpgrades.scale(Spell.TAUNT, SpellUpgrades.Stat.SPECIAL, 1);
        totem.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        totem.getAttribute(Attributes.ARMOR).setBaseValue(p.getArmorValue());
        totem.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(p.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        totem.setHealth((float) health);
        totem.setCustomName(Component.literal("Totem of Light").withStyle(ChatFormatting.GOLD));
        int ticks = SpellUpgrades.scaleInt(Spell.TAUNT, SpellUpgrades.Stat.DURATION, TICKS);
        int radius = SpellUpgrades.scaleInt(Spell.TAUNT, SpellUpgrades.Stat.AREA, RADIUS);
        LIVE.put(
                totem,
                new Held(
                        p.getUUID(),
                        level.getGameTime() + ticks,
                        radius,
                        new ArrayList<>(),
                        new java.util.HashSet<>()));
        level.addFreshEntity(totem);
        level.playSound(
                null, totem.getX(), totem.getY(), totem.getZ(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.6f, .8f);
        level.sendParticles(ParticleTypes.END_ROD, totem.getX(), totem.getY() + 1, totem.getZ(), 40, .4, .9, .4, .05);
        return true;
    }

    /** Server tick: hold the taunted monsters on their totem, chime, and end a totem that fell or ran out. */
    static void tick(MinecraftServer server) {
        for (var entry : List.copyOf(LIVE.entrySet())) {
            Totem totem = entry.getKey();
            Held held = entry.getValue();
            if (!(totem.level() instanceof ServerLevel level)) continue;
            if (totem.isRemoved() || !totem.isAlive() || level.getGameTime() >= held.until()) {
                end(totem, held, level, server);
                continue;
            }
            long t = level.getGameTime();
            ServerPlayer caster = server.getPlayerList().getPlayer(held.caster());
            int radius = held.radius();
            // Every tick: a monster that walks into range turns at once (a handful of totems at most, so it is cheap).
            for (Mob m : level.getEntitiesOfClass(
                    Mob.class, totem.getBoundingBox().inflate(radius), m -> tauntable(m, held))) {
                if (!held.taunted().contains(m)) held.taunted().add(m);
            }
            held.taunted().removeIf(m -> !m.isAlive() || held.broken().contains(m));
            for (Mob m : held.taunted()) aim(m, totem);
            if (t % 20 == 0) {
                // It makes noise: a bell's chime and a ring of rising light.
                level.playSound(
                        null,
                        totem.getX(),
                        totem.getY() + 1,
                        totem.getZ(),
                        SoundEvents.BELL_BLOCK,
                        SoundSource.PLAYERS,
                        1.2f,
                        1.2f);
                Bosses.ring(level, totem.position().add(0, .2, 0), 1.2, ParticleTypes.END_ROD, 16);
                if (caster != null
                        && SpellUpgrades.perk(caster, Spell.TAUNT, "Rallying Chime")
                        && caster.distanceToSqr(totem) < radius * radius)
                    caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 40, 0, true, true));
            }
            if (t % 4 == 0)
                level.sendParticles(
                        ParticleTypes.END_ROD, totem.getX(), totem.getY() + 1.2, totem.getZ(), 1, .25, .6, .25, .01);
        }
    }

    private static boolean tauntable(Mob m, Held held) {
        if (!(m instanceof Enemy)
                || !m.isAlive()
                || m instanceof Totem
                || held.broken().contains(m)) return false;
        if (m instanceof WitherBoss
                || m instanceof EnderDragon
                || m instanceof net.minecraft.world.entity.monster.warden.Warden) return false;
        if (Monsters.info(m).rank() == MonsterInfo.BOSS || SummonSpells.thrallOwner(m) != null) return false;
        return !Cage.caged(m);
    }

    /** Points a monster at the totem, goal-driven and brain-driven monsters alike. */
    private static void aim(Mob m, LivingEntity totem) {
        if (m.getTarget() != totem) m.setTarget(totem);
        if (m.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED))
            m.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, totem);
    }

    private static void end(Totem totem, Held held, ServerLevel level, MinecraftServer server) {
        LIVE.remove(totem);
        for (Mob m : held.taunted()) {
            if (m.getTarget() == totem) m.setTarget(null);
            if (m.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED))
                m.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        }
        ServerPlayer caster = server.getPlayerList().getPlayer(held.caster());
        if (caster != null && SpellUpgrades.perk(caster, Spell.TAUNT, "Parting Gift") && caster.isAlive())
            caster.heal(4);
        level.sendParticles(ParticleTypes.END_ROD, totem.getX(), totem.getY() + 1, totem.getZ(), 30, .3, .8, .3, .08);
        level.playSound(
                null,
                totem.getX(),
                totem.getY(),
                totem.getZ(),
                SoundEvents.AMETHYST_BLOCK_BREAK,
                SoundSource.PLAYERS,
                1f,
                .8f);
        if (!totem.isRemoved()) totem.discard();
    }

    /**
     * Damage hook: players and anything of theirs never hurt a totem (false = blocked). Otherwise true; a taunted
     * monster that a player strikes is let go and turns on that player (owner, 2026-11-18).
     */
    static boolean allow(LivingEntity victim, DamageSource source) {
        Entity by = source.getEntity();
        if (victim instanceof Totem) return !(by instanceof Player) && WardCombat.owner(by) == null;
        if (victim instanceof Mob m && by instanceof Player p)
            for (var entry : LIVE.entrySet()) {
                Held held = entry.getValue();
                if (held.taunted().remove(m)) {
                    held.broken().add(m);
                    if (m.getTarget() == entry.getKey()) m.setTarget(p);
                    if (m.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED))
                        m.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, p);
                }
            }
        return true;
    }

    /** Thorned Light: a monster striking a totem takes 2 damage back. */
    static void struck(LivingEntity victim, DamageSource source) {
        if (!(victim instanceof Totem totem) || !(source.getEntity() instanceof LivingEntity attacker)) return;
        Held held = LIVE.get(totem);
        if (held == null || !(totem.level() instanceof ServerLevel level)) return;
        ServerPlayer caster = level.getServer().getPlayerList().getPlayer(held.caster());
        if (caster != null && SpellUpgrades.perk(caster, Spell.TAUNT, "Thorned Light"))
            attacker.hurtServer(level, level.damageSources().thorns(totem), 2);
    }

    /** True while {@code m} is held by a totem (tests). */
    static boolean taunted(Mob m) {
        for (Held held : LIVE.values()) if (held.taunted().contains(m)) return true;
        return false;
    }

    /** Every live totem (tests). */
    static List<Totem> totems() {
        return List.copyOf(LIVE.keySet());
    }

    /** Ends every totem now (tests). */
    static void clear(MinecraftServer server) {
        for (var entry : List.copyOf(LIVE.entrySet()))
            if (entry.getKey().level() instanceof ServerLevel level)
                end(entry.getKey(), entry.getValue(), level, server);
    }
}
