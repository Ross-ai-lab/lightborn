package lightborn;

import java.util.function.Predicate;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;

/** Replace a share of an existing local species; keep total spawn weight and pack size. */
public final class RegionalSpawns {
    private RegionalSpawns() {}

    static void init() {
        var overworld = BiomeSelectors.foundInOverworld();
        replace(
                "frostbitten",
                overworld.and(
                        c -> c.getBiome().hasPrecipitation() && c.getBiome().getBaseTemperature() < .15f),
                EntityTypes.ZOMBIE,
                ModEntities.FROSTBITTEN_ZOMBIE);
        replace(
                "mire",
                BiomeSelectors.includeByKey(Biomes.SWAMP, Biomes.MANGROVE_SWAMP)
                        .or(BiomeSelectors.tag(BiomeTags.HAS_SWAMP_HUT))
                        .or(BiomeSelectors.tag(tag("is_swamp"))),
                EntityTypes.ZOMBIE,
                ModEntities.MIRE_ZOMBIE);
        replace(
                "dune",
                BiomeSelectors.includeByKey(Biomes.DESERT)
                        .or(BiomeSelectors.tag(BiomeTags.IS_BADLANDS))
                        .or(BiomeSelectors.tag(BiomeTags.HAS_DESERT_PYRAMID))
                        .or(BiomeSelectors.tag(tag("is_desert"))),
                EntityTypes.SKELETON,
                ModEntities.DUNE_SKELETON);
        replace("canopy", BiomeSelectors.tag(BiomeTags.IS_JUNGLE), EntityTypes.SPIDER, ModEntities.CANOPY_SPIDER);
        replace("captain", BiomeSelectors.tag(BiomeTags.IS_OCEAN), EntityTypes.DROWNED, ModEntities.DROWNED_CAPTAIN);
    }

    private static TagKey<Biome> tag(String path) {
        return TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("c", path));
    }

    private static void replace(
            String id, Predicate<BiomeSelectionContext> where, EntityType<?> plain, EntityType<?> regional) {
        BiomeModifications.create(ModItems.id("regional_" + id))
                .add(ModificationPhase.POST_PROCESSING, where, (biome, ctx) -> {
                    var spawns = ctx.getMobSpawnSettings();
                    var entries = spawns.getMobs(MobCategory.MONSTER).stream()
                            .filter(w -> w.value().type() == plain)
                            .toList();
                    EntityType<?> chosen = plain;
                    if (entries.isEmpty() && regional == ModEntities.DUNE_SKELETON) {
                        chosen = EntityTypes.PARCHED;
                        entries = spawns.getMobs(MobCategory.MONSTER).stream()
                                .filter(w -> w.value().type() == EntityTypes.PARCHED)
                                .toList();
                    }
                    if (entries.isEmpty()) return;
                    EntityType<?> source = chosen;
                    spawns.removeSpawns((category, data) -> category == MobCategory.MONSTER && data.type() == source);
                    for (var entry : entries) {
                        int part = share(entry.weight());
                        spawns.addSpawn(MobCategory.MONSTER, entry.value(), entry.weight() - part);
                        if (part > 0)
                            spawns.addSpawn(
                                    MobCategory.MONSTER,
                                    new MobSpawnSettings.SpawnerData(
                                            regional, entry.value().count()),
                                    part);
                    }
                });
    }

    static int share(int weight) {
        return Math.max(0, Math.min(weight - 1, Math.round(weight * (float) Balance.REGIONAL_SPAWN_SHARE)));
    }

    public static WeightedList<MobSpawnSettings.SpawnerData> belowZero(
            WeightedList<MobSpawnSettings.SpawnerData> original) {
        if (original.unwrap().stream().noneMatch(w -> w.value().type() == EntityTypes.SPIDER && share(w.weight()) > 0))
            return original;
        var pool = WeightedList.<MobSpawnSettings.SpawnerData>builder();
        for (var entry : original.unwrap()) {
            int part = entry.value().type() == EntityTypes.SPIDER ? share(entry.weight()) : 0;
            pool.add(entry.value(), entry.weight() - part);
            if (part > 0)
                pool.add(
                        new MobSpawnSettings.SpawnerData(
                                ModEntities.DEEP_STALKER_SPIDER, entry.value().count()),
                        part);
        }
        return pool.build();
    }
}
