package lightborn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.biome.v1.BiomeModificationContext;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The mod's own creatures: kinds of zombie, skeleton and spider that roam beside the plain ones (owner, 2026-11-09,
 * in place of champions), each with its trait built in.
 * <p>
 * Zombies, against the vanilla zombie's 95 plus the mod's extra 95 (Lightborn): darkness 95, vampire 60, giant 35.
 * That is the same number of zombies as before the split (the extra plain-zombie weight went from 190 to 95).
 * <p>
 * Skeletons and spiders: in every biome, each vanilla skeleton or spider spawn entry is split, keeping its total
 * weight and pack size: 55% stays plain, 25% becomes ember skeletons or weaver spiders, 20% deadeye skeletons or
 * hunter spiders. So there are exactly as many skeletons and spiders as in vanilla, only of more kinds.
 */
public final class ModEntities {
    private ModEntities() {}

    public static EntityType<DarknessZombie> DARKNESS_ZOMBIE;
    public static EntityType<VampireZombie> VAMPIRE_ZOMBIE;
    public static EntityType<GiantZombie> GIANT_ZOMBIE;
    public static EntityType<EmberSkeleton> EMBER_SKELETON;
    public static EntityType<DeadeyeSkeleton> DEADEYE_SKELETON;
    public static EntityType<WeaverSpider> WEAVER_SPIDER;
    public static EntityType<HunterSpider> HUNTER_SPIDER;
    public static EntityType<FrostbittenZombie> FROSTBITTEN_ZOMBIE;
    public static EntityType<MireZombie> MIRE_ZOMBIE;
    public static EntityType<DuneSkeleton> DUNE_SKELETON;
    public static EntityType<CanopySpider> CANOPY_SPIDER;
    public static EntityType<DeepStalkerSpider> DEEP_STALKER_SPIDER;
    public static EntityType<DrownedCaptain> DROWNED_CAPTAIN;
    /** A zombie and a spider grown into one; spawns wherever zombies do. */
    /** A zombie and a bat grown into one; caves only. */
    public static EntityType<BatZombie> BAT_ZOMBIE;
    /** Never spawns on its own: raised by the Spirit Wolves tome. */
    public static EntityType<SpiritWolf> SPIRIT_WOLF;

    public static EntityType<SpiritSteed> SPIRIT_STEED;
    public static EntityType<LightButterfly> LIGHT_BUTTERFLY;

    public static EntityType<LightDragon> LIGHT_DRAGON;
    public static EntityType<WardAura> WARD_AURA;
    public static EntityType<RadiantPulse.Wave> RADIANT_WAVE;
    public static EntityType<LastScribe> LAST_SCRIBE;
    /** The frightened stranger who runs into a home's ward before dawn on some nights (Refugee). */
    public static EntityType<Refugee> REFUGEE;
    /** Each kind's spawn egg, kind name to item. */
    public static final Map<String, Item> EGGS = new LinkedHashMap<>();

    static void init() {
        WardAura.init();
        StaffFlight.init();
        Taunt.init();
        var pulseKey = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("radiant_wave"));
        RADIANT_WAVE = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                pulseKey,
                EntityType.Builder.<RadiantPulse.Wave>of(RadiantPulse.Wave::new, MobCategory.MISC)
                        .sized(.1f, .1f)
                        .clientTrackingRange(12)
                        .build(pulseKey));
        var wardKey = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("ward_aura"));
        WARD_AURA = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                wardKey,
                EntityType.Builder.<WardAura>of(WardAura::new, MobCategory.MISC)
                        .sized(1, 1)
                        .clientTrackingRange(12)
                        .build(wardKey));
        var scribeKey = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("last_scribe"));
        LAST_SCRIBE = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                scribeKey,
                FabricEntityType.Builder.<LastScribe>createMob(
                                LastScribe::new,
                                MobCategory.MONSTER,
                                b -> b.defaultAttributes(Zombie::createAttributes))
                        .sized(.7f, 2.2f)
                        .eyeHeight(1.9f)
                        .clientTrackingRange(12)
                        .build(scribeKey));
        var refugeeKey = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("refugee"));
        REFUGEE = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                refugeeKey,
                FabricEntityType.Builder.<Refugee>createMob(
                                Refugee::new, MobCategory.MISC, b -> b.defaultAttributes(Refugee::createAttributes))
                        .sized(.6f, 1.8f)
                        .eyeHeight(1.62f)
                        .clientTrackingRange(10)
                        .build(refugeeKey));
        Refugee.init();
        var dragonKey = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("light_dragon"));
        LIGHT_DRAGON = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                dragonKey,
                FabricEntityType.Builder.<LightDragon>createMob(
                                LightDragon::new, MobCategory.MISC, b -> b.defaultAttributes(Mob::createMobAttributes))
                        .sized(1.6f, 3.4f)
                        .eyeHeight(3.0f)
                        .clientTrackingRange(12)
                        .build(dragonKey));
        DARKNESS_ZOMBIE = zombie("darkness_zombie", DarknessZombie::new, Zombie::createAttributes, 1f);
        VAMPIRE_ZOMBIE = zombie("vampire_zombie", VampireZombie::new, Zombie::createAttributes, 1f);
        GIANT_ZOMBIE = zombie("giant_zombie", GiantZombie::new, GiantZombie::createAttributes, GiantZombie.SIZE);
        // Vanilla skeleton and spider shapes.
        EMBER_SKELETON = mob("ember_skeleton", EmberSkeleton::new, Skeleton::createAttributes, 0.6f, 1.99f, 1.74f);
        DEADEYE_SKELETON =
                mob("deadeye_skeleton", DeadeyeSkeleton::new, DeadeyeSkeleton::createAttributes, 0.6f, 1.99f, 1.74f);
        WEAVER_SPIDER = mob("weaver_spider", WeaverSpider::new, Spider::createAttributes, 1.4f, 0.9f, 0.65f);
        HUNTER_SPIDER = mob("hunter_spider", HunterSpider::new, HunterSpider::createAttributes, 1.4f, 0.9f, 0.65f);
        FROSTBITTEN_ZOMBIE = zombie("frostbitten_zombie", FrostbittenZombie::new, Zombie::createAttributes, 1f);
        MIRE_ZOMBIE = zombie("mire_zombie", MireZombie::new, Zombie::createAttributes, 1f);
        DUNE_SKELETON = mob("dune_skeleton", DuneSkeleton::new, Skeleton::createAttributes, .6f, 1.99f, 1.74f);
        CANOPY_SPIDER = mob("canopy_spider", CanopySpider::new, Spider::createAttributes, 1.4f, .9f, .65f);
        DEEP_STALKER_SPIDER =
                mob("deep_stalker_spider", DeepStalkerSpider::new, Spider::createAttributes, 1.4f, .9f, .65f);
        var batKey = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("bat_zombie"));
        BAT_ZOMBIE = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                batKey,
                FabricEntityType.Builder.<BatZombie>createMob(
                                BatZombie::new,
                                MobCategory.MONSTER,
                                b -> b.defaultAttributes(BatZombie::createAttributes)
                                        .spawnPlacement(
                                                SpawnPlacementTypes.ON_GROUND,
                                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                                BatZombie::checkSpawnRules))
                        .sized(.75f, .8f)
                        .eyeHeight(.45f)
                        .notInPeaceful()
                        .clientTrackingRange(8)
                        .build(batKey));
        var captainKey = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("drowned_captain"));
        DROWNED_CAPTAIN = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                captainKey,
                FabricEntityType.Builder.<DrownedCaptain>createMob(
                                DrownedCaptain::new,
                                MobCategory.MONSTER,
                                b -> b.defaultAttributes(Drowned::createAttributes)
                                        .spawnPlacement(
                                                SpawnPlacementTypes.IN_WATER,
                                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                                (type, level, reason, pos, random) -> SpawnPlacements.checkSpawnRules(
                                                        EntityTypes.DROWNED, level, reason, pos, random)))
                        .sized(.6f, 1.95f)
                        .eyeHeight(1.74f)
                        .clientTrackingRange(8)
                        .build(captainKey));
        ResourceKey<EntityType<?>> wolfKey = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("spirit_wolf"));
        SPIRIT_WOLF = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                wolfKey,
                FabricEntityType.Builder.<SpiritWolf>createMob(
                                SpiritWolf::new, MobCategory.CREATURE, b -> b.defaultAttributes(Wolf::createAttributes))
                        .sized(0.6f, 0.85f)
                        .eyeHeight(0.68f)
                        .clientTrackingRange(10)
                        .build(wolfKey));
        ResourceKey<EntityType<?>> butterflyKey =
                ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("light_butterfly"));
        LIGHT_BUTTERFLY = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                butterflyKey,
                FabricEntityType.Builder.<LightButterfly>createMob(
                                LightButterfly::new,
                                MobCategory.AMBIENT,
                                b -> b.defaultAttributes(LightButterfly::attributes))
                        .sized(0.6f, 0.5f)
                        .eyeHeight(0.25f)
                        .clientTrackingRange(8)
                        .build(butterflyKey));
        ResourceKey<EntityType<?>> steedKey = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("spirit_steed"));
        SPIRIT_STEED = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                steedKey,
                FabricEntityType.Builder.<SpiritSteed>createMob(
                                SpiritSteed::new,
                                MobCategory.CREATURE,
                                b -> b.defaultAttributes(SpiritSteed::attributes))
                        .sized(1.3964844f, 1.6f)
                        .eyeHeight(1.52f)
                        .clientTrackingRange(10)
                        .build(steedKey));
        egg("darkness_zombie", DARKNESS_ZOMBIE);
        egg("vampire_zombie", VAMPIRE_ZOMBIE);
        egg("giant_zombie", GIANT_ZOMBIE);
        egg("ember_skeleton", EMBER_SKELETON);
        egg("deadeye_skeleton", DEADEYE_SKELETON);
        egg("weaver_spider", WEAVER_SPIDER);
        egg("hunter_spider", HUNTER_SPIDER);
        egg("frostbitten_zombie", FROSTBITTEN_ZOMBIE);
        egg("mire_zombie", MIRE_ZOMBIE);
        egg("dune_skeleton", DUNE_SKELETON);
        egg("canopy_spider", CANOPY_SPIDER);
        egg("deep_stalker_spider", DEEP_STALKER_SPIDER);
        egg("drowned_captain", DROWNED_CAPTAIN);
        egg("bat_zombie", BAT_ZOMBIE);

        var zombieBiomes = BiomeSelectors.spawnsOneOf(EntityTypes.ZOMBIE);
        BiomeModifications.addSpawn(zombieBiomes, MobCategory.MONSTER, DARKNESS_ZOMBIE, 95, 2, 4);
        BiomeModifications.addSpawn(zombieBiomes, MobCategory.MONSTER, VAMPIRE_ZOMBIE, 60, 1, 3);
        BiomeModifications.addSpawn(zombieBiomes, MobCategory.MONSTER, GIANT_ZOMBIE, 35, 1, 1);
        // Bat zombies ask for the Overworld everywhere, but their own spawn rule keeps them to dark caves below y 40.
        BiomeModifications.addSpawn(BiomeSelectors.foundInOverworld(), MobCategory.MONSTER, BAT_ZOMBIE, 30, 1, 3);
        BiomeModifications.create(ModItems.id("monster_kinds"))
                .add(
                        ModificationPhase.REPLACEMENTS,
                        BiomeSelectors.spawnsOneOf(EntityTypes.SKELETON, EntityTypes.SPIDER),
                        (biome, ctx) -> {
                            split(ctx.getMobSpawnSettings(), EntityTypes.SKELETON, EMBER_SKELETON, DEADEYE_SKELETON);
                            split(ctx.getMobSpawnSettings(), EntityTypes.SPIDER, WEAVER_SPIDER, HUNTER_SPIDER);
                        });
        RegionalSpawns.init();
        Giants.init();
    }

    static void egg(String kind, EntityType<?> type) {
        EGGS.put(kind, ModItems.register(kind + "_spawn_egg", SpawnEggItem::new, new Item.Properties().spawnEgg(type)));
    }

    /** Splits each spawn entry of {@code plain} into plain 55%, {@code a} 25%, {@code b} 20%, same total and pack size. */
    static void split(
            BiomeModificationContext.MobSpawnSettingsContext spawns,
            EntityType<?> plain,
            EntityType<?> a,
            EntityType<?> b) {
        List<Weighted<MobSpawnSettings.SpawnerData>> found = spawns.getMobs(MobCategory.MONSTER).stream()
                .filter(w -> w.value().type() == plain)
                .toList();
        if (found.isEmpty()) return;
        spawns.removeSpawns((category, data) -> category == MobCategory.MONSTER && data.type() == plain);
        for (var w : found) {
            int[] parts = splitWeight(w.weight());
            spawns.addSpawn(MobCategory.MONSTER, w.value(), parts[0]);
            spawns.addSpawn(
                    MobCategory.MONSTER,
                    new MobSpawnSettings.SpawnerData(a, w.value().count()),
                    parts[1]);
            spawns.addSpawn(
                    MobCategory.MONSTER,
                    new MobSpawnSettings.SpawnerData(b, w.value().count()),
                    parts[2]);
        }
    }

    /** {plain, first kind, second kind} weights adding up to {@code total}. */
    static int[] splitWeight(int total) {
        int a = Math.max(1, Math.round(total * 0.25f)), b = Math.max(1, Math.round(total * 0.20f));
        return new int[] {Math.max(1, total - a - b), a, b};
    }

    /** A vanilla zombie's spawn rules and shape, times {@code size}. */
    private static <T extends Zombie> EntityType<T> zombie(
            String name,
            EntityType.EntityFactory<T> factory,
            Supplier<AttributeSupplier.Builder> attributes,
            float size) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id(name));
        return Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                key,
                FabricEntityType.Builder.<T>createMob(
                                factory,
                                MobCategory.MONSTER,
                                b -> b.spawnPlacement(
                                                SpawnPlacementTypes.ON_GROUND,
                                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                                Monster::checkMonsterSpawnRules)
                                        .defaultAttributes(attributes))
                        .sized(0.6f * size, 1.95f * size)
                        .eyeHeight(1.74f * size)
                        .ridingOffset(-0.7f * size)
                        .clientTrackingRange(8)
                        .build(key));
    }

    /** A ground monster with a vanilla monster's spawn rules. */
    private static <T extends Monster> EntityType<T> mob(
            String name,
            EntityType.EntityFactory<T> factory,
            Supplier<AttributeSupplier.Builder> attributes,
            float width,
            float height,
            float eye) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id(name));
        return Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                key,
                FabricEntityType.Builder.<T>createMob(
                                factory,
                                MobCategory.MONSTER,
                                b -> b.spawnPlacement(
                                                SpawnPlacementTypes.ON_GROUND,
                                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                                Monster::checkMonsterSpawnRules)
                                        .defaultAttributes(attributes))
                        .sized(width, height)
                        .eyeHeight(eye)
                        .clientTrackingRange(8)
                        .build(key));
    }
}
