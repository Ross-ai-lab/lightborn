package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public final class Bosses {
    private Bosses() {}

    record Grudge(BossKind kind, UUID token) {
        static final Codec<Grudge> CODEC = RecordCodecBuilder.create(i -> i.group(
                        BossKind.CODEC.fieldOf("kind").forGetter(Grudge::kind),
                        UUIDUtil.CODEC.fieldOf("token").forGetter(Grudge::token))
                .apply(i, Grudge::new));
    }

    record Revenge(UUID player, UUID token) {
        static final Codec<Revenge> CODEC = RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("player").forGetter(Revenge::player),
                        UUIDUtil.CODEC.fieldOf("token").forGetter(Revenge::token))
                .apply(i, Revenge::new));
    }

    record Encounter(BossKind kind, Optional<Revenge> revenge) {
        static final Codec<Encounter> CODEC = RecordCodecBuilder.create(i -> i.group(
                        BossKind.CODEC.fieldOf("kind").forGetter(Encounter::kind),
                        Revenge.CODEC.optionalFieldOf("revenge").forGetter(Encounter::revenge))
                .apply(i, Encounter::new));

        static Encounter normal(BossKind kind) {
            return new Encounter(kind, Optional.empty());
        }
    }

    static final String ADD_TAG = "lightborn_boss_add";
    static final String SHADOW_TAG = "lightborn_boss_shadow";
    private static final Map<UUID, State> BOSSES = new HashMap<>();
    private static final Map<UUID, Mob> ADDS = new HashMap<>();

    private static final class State {
        final Mob mob;
        final ServerBossEvent bar;
        final Encounter encounter;
        final List<Mob> adds = new ArrayList<>();
        long nextAbility = Scheduler.now() + Balance.BOSS_WARMUP;
        int move;

        State(Mob mob, Encounter encounter) {
            this.mob = mob;
            this.encounter = encounter;
            String title =
                    encounter.revenge.isPresent() ? encounter.kind.title + " remembers you" : encounter.kind.title;
            this.bar = new ServerBossEvent(
                    UUID.randomUUID(),
                    Component.literal(title),
                    enraged(mob) ? BossEvent.BossBarColor.PURPLE : BossEvent.BossBarColor.RED,
                    BossEvent.BossBarOverlay.NOTCHED_10);
        }
    }

    public static int liveCount() {
        return BOSSES.size();
    }

    static Encounter identity(Entity entity) {
        if (entity instanceof LastScribe || entity instanceof LightDragon) return null;
        return entity == null ? null : entity.getAttached(ModData.BOSS_IDENTITY);
    }

    static Encounter revengeFor(ServerPlayer player) {
        var grudge = player.getAttached(ModData.BOSS_GRUDGE);
        return grudge == null
                ? null
                : new Encounter(grudge.kind, Optional.of(new Revenge(player.getUUID(), grudge.token)));
    }

    static void rememberDeath(ServerPlayer player, DamageSource source) {
        var boss = identity(source.getEntity());
        if (boss != null) player.setAttached(ModData.BOSS_GRUDGE, new Grudge(boss.kind, UUID.randomUUID()));
    }

    static void settleGrudge(ServerPlayer player, Encounter encounter) {
        if (encounter == null) return;
        var grudge = player.getAttached(ModData.BOSS_GRUDGE);
        if (grudge != null
                && encounter
                        .revenge
                        .filter(r -> r.player.equals(player.getUUID()) && r.token.equals(grudge.token))
                        .isPresent()) {
            player.removeAttached(ModData.BOSS_GRUDGE);
        }
    }

    static boolean canSpawnWild(ServerLevel level, BlockPos pos) {
        if (LightWards.covers(level, pos)) return false;
        for (State s : BOSSES.values()) {
            if (s.mob.level() == level
                    && s.mob.isAlive()
                    && s.mob.blockPosition().closerThan(pos, Balance.WILD_BOSS_SPACING)) return false;
        }
        return true;
    }

    static boolean replaceWild(ServerLevel level, Mob original, RandomSource random) {
        Mob boss = create(level, original.position(), Encounter.normal(BossKind.random(random)));
        if (boss == null) return false;
        if (!level.addFreshEntity(boss)) {
            boss.discard();
            return false;
        }
        original.discard();
        return true;
    }

    static void promote(Mob mob, Encounter encounter) {
        mob.setAttached(ModData.BOSS_IDENTITY, encounter);
        if (encounter.kind == BossKind.GORATH) {
            var health = mob.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) health.setBaseValue(Balance.GORATH_BASE_HEALTH);
        }
        Monsters.setRank(mob, MonsterInfo.BOSS);
        Monsters.modifier(mob, Attributes.SCALE, "boss_scale", 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        Monsters.modifier(mob, Attributes.KNOCKBACK_RESISTANCE, "boss_kb", 0.8, AttributeModifier.Operation.ADD_VALUE);
        Monsters.modifier(mob, Attributes.ARMOR, "boss_armor", 8, AttributeModifier.Operation.ADD_VALUE);
        Monsters.modifier(
                mob, Attributes.MOVEMENT_SPEED, "boss_speed", 0.15, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        Monsters.modifier(mob, Attributes.FOLLOW_RANGE, "boss_follow", 32, AttributeModifier.Operation.ADD_VALUE);
        name(mob, encounter.kind);
        mob.setPersistenceRequired();
        mob.setItemSlot(
                EquipmentSlot.MAINHAND,
                switch (encounter.kind) {
                    case GORATH -> new ItemStack(Items.NETHERITE_AXE);
                    case VEXMORA, OZRIK -> new ItemStack(Items.BOW);
                    case SYRITH -> new ItemStack(Items.NETHERITE_SWORD);
                    default -> ItemStack.EMPTY;
                });
        for (EquipmentSlot slot : EquipmentSlot.values()) mob.setDropChance(slot, 0f);
        mob.refreshDimensions();
        mob.setHealth(mob.getMaxHealth());
    }

    private static void name(Mob mob, BossKind kind) {
        mob.setCustomName(Component.literal(kind.title).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        mob.setCustomNameVisible(false);
    }

    /** Place a random named boss in front of the caller (the /lightborn boss test command). */
    public static boolean summonNear(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle().multiply(1, 0, 1).normalize();
        BlockPos pos = BlockPos.containing(player.position().add(look.scale(6)));
        var encounter = Encounter.normal(BossKind.random(level.getRandom()));
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos p = pos.above(dy);
            if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
            Mob boss = spawn(level, p, encounter);
            if (boss != null) {
                Hordes.aim(boss, player);
                return true;
            }
        }
        return false;
    }

    public static Mob spawn(ServerLevel level, BlockPos pos, RandomSource random) {
        return spawn(level, pos, Encounter.normal(BossKind.random(random)));
    }

    static Mob spawn(ServerLevel level, BlockPos pos, Encounter encounter) {
        Mob mob = create(level, Vec3.atBottomCenterOf(pos), encounter);
        if (mob == null) return null;
        if (!level.addFreshEntity(mob)) {
            mob.discard();
            return null;
        }
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, mob.getX(), mob.getY() + 1, mob.getZ(), 1, 0, 0, 0, 0);
        level.sendParticles(
                ParticleTypes.SOUL_FIRE_FLAME, mob.getX(), mob.getY() + 1, mob.getZ(), 55, 0.9, 1.5, 0.9, 0.04);
        level.playSound(
                null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.0f, 1.0f);
        return mob;
    }

    private static Mob create(ServerLevel level, Vec3 at, Encounter encounter) {
        Mob mob = VariantCompat.keepKind(encounter.kind.body().create(level, EntitySpawnReason.TRIGGERED));
        if (mob == null) return null;
        mob.setPos(at);
        promote(mob, encounter);
        if (level.getBlockCollisions(mob, mob.getBoundingBox()).iterator().hasNext()
                || level.containsAnyLiquid(mob.getBoundingBox())
                || !level.getWorldBorder().isWithinBounds(mob.blockPosition())) {
            mob.discard();
            return null;
        }
        return mob;
    }

    static void track(Mob mob) {
        if (StoryBosses.hostile(mob)) return;
        var encounter = identity(mob);
        if (encounter == null) {
            // Old named bosses keep their saved body and attributes, then take the matching or a stable kit.
            BossKind kind = BossKind.ROSTER.stream()
                    .filter(k -> k.body() == mob.getType())
                    .findFirst()
                    .orElseGet(() ->
                            BossKind.ROSTER.get(Math.floorMod(mob.getUUID().hashCode(), BossKind.ROSTER.size())));
            encounter = Encounter.normal(kind);
            mob.setAttached(ModData.BOSS_IDENTITY, encounter);
        }
        name(mob, encounter.kind);
        State current = BOSSES.get(mob.getUUID());
        if (current != null && current.mob != mob) untrack(current.mob);
        if (!BOSSES.containsKey(mob.getUUID())) BOSSES.put(mob.getUUID(), new State(mob, encounter));
    }

    static void untrack(Mob mob) {
        State s = BOSSES.remove(mob.getUUID());
        if (s == null) return;
        s.bar.removeAllPlayers();
        for (Mob add : s.adds) {
            ADDS.remove(add.getUUID());
            if (!add.isRemoved()) add.discard();
        }
    }

    static boolean onAddLoad(Entity entity) {
        if (!entity.entityTags().contains(ADD_TAG)) return false;
        if (ADDS.get(entity.getUUID()) != entity) {
            entity.discard();
            return true;
        }
        return false;
    }

    static void clear() {
        for (State s : List.copyOf(BOSSES.values())) untrack(s.mob);
        ADDS.clear();
    }

    static boolean enraged(Entity entity) {
        return Boolean.TRUE.equals(entity.getAttached(ModData.BOSS_ENRAGED));
    }

    static float modifyDamage(LivingEntity target, float amount) {
        if (target.entityTags().contains(SHADOW_TAG))
            return Math.max(amount, 6 * (target.getHealth() + target.getAbsorptionAmount()));
        var encounter = identity(target);
        return encounter != null && encounter.kind == BossKind.GORATH && enraged(target)
                ? amount * Balance.GORATH_ENRAGED_DAMAGE
                : amount;
    }

    static boolean allowDeath(LivingEntity mob, DamageSource source, float amount) {
        var encounter = identity(mob);
        if (encounter == null
                || encounter.kind != BossKind.SYRITH
                || Boolean.TRUE.equals(mob.getAttached(ModData.BOSS_REVIVED))
                || !(mob.level() instanceof ServerLevel level)
                || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
        mob.setAttached(ModData.BOSS_REVIVED, true);
        mob.setHealth(mob.getMaxHealth() * Balance.SYRITH_REVIVE_HEALTH);
        mob.clearFire();
        level.playSound(null, mob.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.HOSTILE, 1.1f, 0.55f);
        ring(level, mob.position(), 3, ParticleTypes.SOUL, 32);
        return false;
    }

    static void tick(MinecraftServer server) {
        for (State s : List.copyOf(BOSSES.values())) {
            Mob mob = s.mob;
            if (mob.isRemoved() || !mob.isAlive()) {
                untrack(mob);
                continue;
            }
            if (!(mob.level() instanceof ServerLevel level)) continue;
            s.bar.setProgress(mob.getHealth() / mob.getMaxHealth());
            if (Scheduler.now() % 10 == 0) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (p.level() == level && p.distanceTo(mob) < Balance.BOSS_BAR_RANGE) s.bar.addPlayer(p);
                    else s.bar.removePlayer(p);
                }
            }
            if (!enraged(mob) && mob.getHealth() <= mob.getMaxHealth() * Balance.BOSS_ENRAGE_HEALTH) enrage(level, s);
            if (mob.getTarget() instanceof ServerPlayer target
                    && target.isAlive()
                    && !BossTrophies.concealed(target)
                    && target.distanceTo(mob) < Balance.BOSS_MOVE_RANGE
                    && Scheduler.now() >= s.nextAbility) {
                BossAbilities.cast(s.encounter.kind, s.move++, level, mob, target);
                s.nextAbility =
                        Scheduler.now() + (enraged(mob) ? Balance.BOSS_ENRAGED_COOLDOWN : Balance.BOSS_MOVE_COOLDOWN);
            }
        }
    }

    private static void enrage(ServerLevel level, State s) {
        Mob mob = s.mob;
        mob.setAttached(ModData.BOSS_ENRAGED, true);
        Monsters.modifier(
                mob, Attributes.MOVEMENT_SPEED, "boss_enrage", 0.3, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        s.bar.setColor(BossEvent.BossBarColor.PURPLE);
        level.playSound(null, mob.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.5f, 0.7f);
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, mob.getX(), mob.getY() + 2.5, mob.getZ(), 20, 1, 1, 1, 0);
        if (s.encounter.kind == BossKind.GORATH) BossAbilities.gorathEnrage(level, mob);
    }

    static void add(ServerLevel level, Mob master, EntityType<? extends Mob> type, Vec3 around, boolean shadow) {
        var state = BOSSES.get(master.getUUID());
        if (state == null) return;
        state.adds.removeIf(add -> {
            if (!add.isRemoved() && add.isAlive()) return false;
            ADDS.remove(add.getUUID());
            return true;
        });
        if (state.adds.size() >= Balance.BOSS_ADD_CAP) return;
        Mob mob = VariantCompat.keepKind(type.create(level, EntitySpawnReason.MOB_SUMMONED));
        if (mob == null) return;
        for (int tryIndex = 0; tryIndex < 8; tryIndex++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2;
            var spot = EarthSpells.standing(level, around.add(Math.cos(angle) * 3, 0, Math.sin(angle) * 3));
            if (spot == null) continue;
            mob.setPos(Vec3.atBottomCenterOf(spot));
            if (!level.noCollision(mob) || !mob.checkSpawnObstruction(level)) continue;
            Monsters.setRank(mob, MonsterInfo.NORMAL);
            if (mob instanceof AbstractSkeleton) mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            for (EquipmentSlot slot : EquipmentSlot.values()) mob.setDropChance(slot, 0);
            mob.addTag(ADD_TAG);
            if (shadow) {
                mob.addTag(SHADOW_TAG);
                mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1);
                mob.getAttribute(Attributes.MAX_HEALTH).removeModifier(ModItems.id("level_health"));
                mob.setHealth(1);
                Monsters.modifier(
                        mob, Attributes.SCALE, "boss_shadow_scale", 1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
                mob.refreshDimensions();
                if (!level.noCollision(mob)) continue;
            }
            ADDS.put(mob.getUUID(), mob);
            if (level.addFreshEntity(mob)) {
                state.adds.add(mob);
                mob.setTarget(master.getTarget());
                level.sendParticles(
                        ParticleTypes.LARGE_SMOKE, mob.getX(), mob.getY() + 0.5, mob.getZ(), 18, 0.3, 0.5, 0.3, 0.02);
                return;
            }
            ADDS.remove(mob.getUUID());
        }
        mob.discard();
    }

    static void ring(ServerLevel level, Vec3 c, double radius, ParticleOptions particle, int points) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            level.sendParticles(
                    particle, c.x + Math.cos(a) * radius, c.y + 0.15, c.z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
        }
    }
}
