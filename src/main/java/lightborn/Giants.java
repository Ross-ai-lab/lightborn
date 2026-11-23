package lightborn;

import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementType;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.Bogged;
import net.minecraft.world.entity.monster.skeleton.Parched;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.skeleton.Stray;
import net.minecraft.world.entity.monster.spider.CaveSpider;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A giant of every vanilla night monster that had none (owner, 2026-11-17), beside the Giant Zombie: skeleton, stray,
 * bogged, parched, husk, drowned, spider, cave spider, creeper, enderman and witch. Each is the vanilla monster with
 * its own look, twice the size (the cave spider three times, so it outgrows a plain spider), its size the entity's
 * real size; three times its kind's health and half again its hit (owner, 2026-11-17), slower, hard to knock back, and
 * it steps up whole blocks. Skeletons' arrows hit 50% harder; the giant creeper's blast is twice as wide. Voices drop low
 * (LivingEntityMixin). They spawn beside their kind at about a tenth of its weight and pay as giants (Lightborn's
 * loot, Command, OreTiers). Never a baby, never a reinforcement call.
 */
public final class Giants {
    private Giants() {}

    /** Marks every giant kind, the Giant Zombie included. */
    public interface Giant {}

    public static boolean is(Object e) {
        return e instanceof Giant;
    }

    public static final float SIZE = 2f, CAVE_SIZE = 3f, ENDER_SIZE = 2f;

    public static EntityType<GiantSkeleton> SKELETON;
    public static EntityType<GiantStray> STRAY;
    public static EntityType<GiantBogged> BOGGED;
    public static EntityType<GiantParched> PARCHED;
    public static EntityType<GiantHusk> HUSK;
    public static EntityType<GiantDrowned> DROWNED;
    public static EntityType<GiantSpider> SPIDER;
    public static EntityType<GiantCaveSpider> CAVE_SPIDER;
    public static EntityType<GiantCreeper> CREEPER;
    public static EntityType<GiantEnderman> ENDERMAN;
    public static EntityType<GiantWitch> WITCH;

    /** Entity id path, display name, type: in registry order (the Book of Souls lists them so). */
    public record Kind(String id, String name, Supplier<EntityType<? extends net.minecraft.world.entity.Mob>> type) {}

    public static final List<Kind> KINDS = List.of(
            new Kind("giant_skeleton", "Giant Skeleton", () -> SKELETON),
            new Kind("giant_stray", "Giant Stray", () -> STRAY),
            new Kind("giant_bogged", "Giant Bogged", () -> BOGGED),
            new Kind("giant_parched", "Giant Parched", () -> PARCHED),
            new Kind("giant_husk", "Giant Husk", () -> HUSK),
            new Kind("giant_drowned", "Giant Drowned", () -> DROWNED),
            new Kind("giant_spider", "Giant Spider", () -> SPIDER),
            new Kind("giant_cave_spider", "Giant Cave Spider", () -> CAVE_SPIDER),
            new Kind("giant_creeper", "Giant Creeper", () -> CREEPER),
            new Kind("giant_enderman", "Giant Enderman", () -> ENDERMAN),
            new Kind("giant_witch", "Giant Witch", () -> WITCH));

    /** The giant's stats on top of its kind's: health, attack, speed; then the shared heft. */
    private static Supplier<AttributeSupplier.Builder> heft(
            Supplier<AttributeSupplier.Builder> base, double health, double attack, double speed) {
        return () -> base.get()
                .add(Attributes.MAX_HEALTH, health)
                .add(Attributes.ATTACK_DAMAGE, attack)
                .add(Attributes.MOVEMENT_SPEED, speed)
                .add(Attributes.KNOCKBACK_RESISTANCE, .6)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    static void init() {
        SKELETON = giant(
                "giant_skeleton",
                GiantSkeleton::new,
                heft(AbstractSkeleton::createAttributes, 60, 3, .2),
                EntityTypes.SKELETON,
                SIZE,
                SpawnPlacementTypes.ON_GROUND,
                Monster::checkMonsterSpawnRules);
        STRAY = giant(
                "giant_stray",
                GiantStray::new,
                heft(AbstractSkeleton::createAttributes, 60, 3, .2),
                EntityTypes.STRAY,
                SIZE,
                SpawnPlacementTypes.ON_GROUND,
                (t, l, r, p, rnd) -> Stray.checkStraySpawnRules(EntityTypes.STRAY, l, r, p, rnd));
        BOGGED = giant(
                "giant_bogged",
                GiantBogged::new,
                heft(Bogged::createAttributes, 48, 3, .2),
                EntityTypes.BOGGED,
                SIZE,
                SpawnPlacementTypes.ON_GROUND,
                Monster::checkMonsterSpawnRules);
        PARCHED = giant(
                "giant_parched",
                GiantParched::new,
                heft(Parched::createAttributes, 48, 3, .2),
                EntityTypes.PARCHED,
                SIZE,
                SpawnPlacementTypes.ON_GROUND,
                Monster::checkMonsterSpawnRules);
        HUSK = giant(
                "giant_husk",
                GiantHusk::new,
                heft(Zombie::createAttributes, 60, 4.5, .19),
                EntityTypes.HUSK,
                SIZE,
                SpawnPlacementTypes.ON_GROUND,
                Monster::checkMonsterSpawnRules);
        DROWNED = giant(
                "giant_drowned",
                GiantDrowned::new,
                heft(Drowned::createAttributes, 60, 4.5, .19),
                EntityTypes.DROWNED,
                SIZE,
                SpawnPlacementTypes.IN_WATER,
                (t, l, r, p, rnd) -> Drowned.checkDrownedSpawnRules(EntityTypes.DROWNED, l, r, p, rnd));
        SPIDER = giant(
                "giant_spider",
                GiantSpider::new,
                heft(Spider::createAttributes, 48, 3, .26),
                EntityTypes.SPIDER,
                SIZE,
                SpawnPlacementTypes.ON_GROUND,
                Monster::checkMonsterSpawnRules);
        CAVE_SPIDER = giant(
                "giant_cave_spider",
                GiantCaveSpider::new,
                heft(CaveSpider::createCaveSpider, 36, 3, .28),
                EntityTypes.CAVE_SPIDER,
                CAVE_SIZE,
                SpawnPlacementTypes.ON_GROUND,
                // Deep and dark only, like the caves it comes from.
                (t, l, r, p, rnd) -> p.getY() < 40 && Monster.checkMonsterSpawnRules(t, l, r, p, rnd));
        CREEPER = giant(
                "giant_creeper",
                GiantCreeper::new,
                heft(Creeper::createAttributes, 60, 0, .2),
                EntityTypes.CREEPER,
                SIZE,
                SpawnPlacementTypes.ON_GROUND,
                Monster::checkMonsterSpawnRules);
        ENDERMAN = giant(
                "giant_enderman",
                GiantEnderman::new,
                heft(Enderman::createAttributes, 120, 10.5, .28),
                EntityTypes.ENDERMAN,
                ENDER_SIZE,
                SpawnPlacementTypes.ON_GROUND,
                Monster::checkMonsterSpawnRules);
        WITCH = giant(
                "giant_witch",
                GiantWitch::new,
                heft(Witch::createAttributes, 78, 3, .2),
                EntityTypes.WITCH,
                SIZE,
                SpawnPlacementTypes.ON_GROUND,
                Monster::checkMonsterSpawnRules);
        for (var k : KINDS) ModEntities.egg(k.id(), k.type().get());

        // Beside their kind at about a tenth of its weight, one at a time.
        BiomeModifications.addSpawn(
                BiomeSelectors.spawnsOneOf(EntityTypes.SKELETON), MobCategory.MONSTER, SKELETON, 8, 1, 1);
        BiomeModifications.addSpawn(BiomeSelectors.spawnsOneOf(EntityTypes.STRAY), MobCategory.MONSTER, STRAY, 8, 1, 1);
        BiomeModifications.addSpawn(
                BiomeSelectors.spawnsOneOf(EntityTypes.BOGGED), MobCategory.MONSTER, BOGGED, 4, 1, 1);
        BiomeModifications.addSpawn(
                BiomeSelectors.spawnsOneOf(EntityTypes.PARCHED), MobCategory.MONSTER, PARCHED, 4, 1, 1);
        BiomeModifications.addSpawn(BiomeSelectors.spawnsOneOf(EntityTypes.HUSK), MobCategory.MONSTER, HUSK, 8, 1, 1);
        BiomeModifications.addSpawn(
                BiomeSelectors.spawnsOneOf(EntityTypes.DROWNED), MobCategory.MONSTER, DROWNED, 4, 1, 1);
        BiomeModifications.addSpawn(
                BiomeSelectors.spawnsOneOf(EntityTypes.SPIDER), MobCategory.MONSTER, SPIDER, 8, 1, 1);
        BiomeModifications.addSpawn(BiomeSelectors.foundInOverworld(), MobCategory.MONSTER, CAVE_SPIDER, 6, 1, 1);
        BiomeModifications.addSpawn(
                BiomeSelectors.spawnsOneOf(EntityTypes.CREEPER), MobCategory.MONSTER, CREEPER, 6, 1, 1);
        BiomeModifications.addSpawn(
                BiomeSelectors.foundInOverworld().and(BiomeSelectors.spawnsOneOf(EntityTypes.ENDERMAN)),
                MobCategory.MONSTER,
                ENDERMAN,
                2,
                1,
                1);
        BiomeModifications.addSpawn(BiomeSelectors.spawnsOneOf(EntityTypes.WITCH), MobCategory.MONSTER, WITCH, 2, 1, 1);
    }

    /** A vanilla kind's shape times {@code size}, with that spawn rule. */
    private static <T extends Monster> EntityType<T> giant(
            String name,
            EntityType.EntityFactory<T> factory,
            Supplier<AttributeSupplier.Builder> attributes,
            EntityType<?> base,
            float size,
            SpawnPlacementType placement,
            SpawnPlacements.SpawnPredicate<T> rule) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id(name));
        var dims = base.getDimensions();
        return Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                key,
                FabricEntityType.Builder.<T>createMob(
                                factory,
                                MobCategory.MONSTER,
                                b -> b.spawnPlacement(placement, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rule)
                                        .defaultAttributes(attributes))
                        .sized(dims.width() * size, dims.height() * size)
                        .eyeHeight(dims.eyeHeight() * size)
                        .clientTrackingRange(10)
                        .build(key));
    }

    /** Heavier arrows for the giant skeletons: half again the damage. */
    static AbstractArrow heavy(AbstractArrow arrow) {
        arrow.setBaseDamage(((lightborn.mixin.ArrowDamageAccessor) arrow).lightborn$baseDamage() * 1.5);
        return arrow;
    }

    public static class GiantSkeleton extends Skeleton implements Giant {
        public GiantSkeleton(EntityType<? extends Skeleton> type, Level level) {
            super(type, level);
        }

        @Override
        protected AbstractArrow getArrow(ItemStack arrow, float power, ItemStack weapon) {
            return heavy(super.getArrow(arrow, power, weapon));
        }
    }

    public static class GiantStray extends Stray implements Giant {
        public GiantStray(EntityType<? extends Stray> type, Level level) {
            super(type, level);
        }

        @Override
        protected AbstractArrow getArrow(ItemStack arrow, float power, ItemStack weapon) {
            return heavy(super.getArrow(arrow, power, weapon));
        }
    }

    public static class GiantBogged extends Bogged implements Giant {
        public GiantBogged(EntityType<? extends Bogged> type, Level level) {
            super(type, level);
        }

        @Override
        protected AbstractArrow getArrow(ItemStack arrow, float power, ItemStack weapon) {
            return heavy(super.getArrow(arrow, power, weapon));
        }
    }

    public static class GiantParched extends Parched implements Giant {
        public GiantParched(EntityType<? extends AbstractSkeleton> type, Level level) {
            super(type, level);
        }

        @Override
        protected AbstractArrow getArrow(ItemStack arrow, float power, ItemStack weapon) {
            return heavy(super.getArrow(arrow, power, weapon));
        }
    }

    public static class GiantHusk extends Husk implements Giant {
        public GiantHusk(EntityType<? extends Husk> type, Level level) {
            super(type, level);
        }

        @Override
        public void setBaby(boolean baby) {
            super.setBaby(false);
        }

        @Override
        protected void randomizeReinforcementsChance() {
            var chance = getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
            if (chance != null) chance.setBaseValue(0);
        }

        @Override
        protected boolean convertsInWater() {
            return false;
        }
    }

    public static class GiantDrowned extends Drowned implements Giant {
        public GiantDrowned(EntityType<? extends Drowned> type, Level level) {
            super(type, level);
        }

        @Override
        public void setBaby(boolean baby) {
            super.setBaby(false);
        }

        @Override
        protected void randomizeReinforcementsChance() {
            var chance = getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
            if (chance != null) chance.setBaseValue(0);
        }
    }

    public static class GiantSpider extends Spider implements Giant {
        public GiantSpider(EntityType<? extends Spider> type, Level level) {
            super(type, level);
        }
    }

    public static class GiantCaveSpider extends CaveSpider implements Giant {
        public GiantCaveSpider(EntityType<? extends CaveSpider> type, Level level) {
            super(type, level);
        }
    }

    public static class GiantCreeper extends Creeper implements Giant {
        public GiantCreeper(EntityType<? extends Creeper> type, Level level) {
            super(type, level);
            ((lightborn.mixin.CreeperAccessor) this).lightborn$setExplosionRadius(6); // twice vanilla's 3
        }
    }

    public static class GiantEnderman extends Enderman implements Giant {
        public GiantEnderman(EntityType<? extends Enderman> type, Level level) {
            super(type, level);
        }
    }

    public static class GiantWitch extends Witch implements Giant {
        public GiantWitch(EntityType<? extends Witch> type, Level level) {
            super(type, level);
        }
    }
}
