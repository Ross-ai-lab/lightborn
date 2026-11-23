package lightborn;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.material.MapColor;

/**
 * The Dark (owner, 2026-11-20: "turn the nether dimension into dark dimension"). The Nether keeps its structures,
 * resources and monsters, but its seas are {@link DarkGoo}, its sky, fog, light and music are the Dark's (the five
 * biome files and the dimension type in {@code data/minecraft}), its rock is retextured near black and violet
 * ({@code tools/make_dark_realm.py}), and it is called the Dark everywhere the game names it. Lightborn's own dark
 * kinds roam it beside the Nether's, the mutants in its open biomes, and its rock holds Dark Shard ore.
 */
public final class DarkRealm {
    private DarkRealm() {}

    public static Block SHARD_ORE;

    /** The ore and its item, beside the other blocks. */
    static void init() {
        var key = ResourceKey.create(Registries.BLOCK, ModItems.id("dark_shard_ore"));
        SHARD_ORE = Registry.register(
                BuiltInRegistries.BLOCK,
                key,
                new Block(BlockBehaviour.Properties.of()
                        .setId(key)
                        .mapColor(MapColor.NETHER)
                        .instrument(NoteBlockInstrument.BASEDRUM)
                        .requiresCorrectToolForDrops()
                        .strength(3f, 3f)
                        .lightLevel(s -> 2)
                        .sound(SoundType.NETHER_ORE)));
        ModItems.register(
                "dark_shard_ore", p -> new BlockItem(SHARD_ORE, p), new Item.Properties().useBlockDescriptionPrefix());
    }

    /**
     * The Dark's own kinds and how common each is, against the rest of its kinds: together about a third of a biome's
     * monster spawns. Mutants only in the open biomes (wastes, deltas, soul sand valley), never the forests.
     */
    static final Map<String, Integer> KINDS = new LinkedHashMap<>();

    static final Map<String, Integer> MUTANTS = new LinkedHashMap<>();

    /** About a third of every Dark biome's monsters are the Dark's own (owner, 2026-11-20: "add dark ones"). */
    static final double SHARE = .35;

    /** A forest's spawns are thin (the Warped Forest's one entry has weight 1), so shares are taken of at least this. */
    static final int MIN_TOTAL = 60;

    static {
        KINDS.put("lightborn:darkness_zombie", 10);
        KINDS.put("lightborn:vampire_zombie", 6);
        KINDS.put("lightborn:ember_skeleton", 8);
        KINDS.put("lightborn:deadeye_skeleton", 4);
        KINDS.put("lightborn:hunter_spider", 4);
        KINDS.put("lightborn:weaver_spider", 3);
        KINDS.put("lightborn:giant_zombie", 2);
        KINDS.put("lightborn:giant_skeleton", 2);
        MUTANTS.put("mutantmonsters:mutant_zombie", 1);
        MUTANTS.put("mutantmonsters:mutant_skeleton", 1);
        MUTANTS.put("mutantmonsters:mutant_creeper", 1);
    }

    /** Spawns and ore, once every entity type is registered (the mutant mods' included). */
    static void initWorld() {
        BiomeModifications.addFeature(
                BiomeSelectors.foundInTheNether(),
                GenerationStep.Decoration.UNDERGROUND_ORES,
                ResourceKey.create(Registries.PLACED_FEATURE, ModItems.id("ore_dark_shard")));
        BiomeModifications.create(ModItems.id("dark_kinds"))
                .add(ModificationPhase.POST_PROCESSING, BiomeSelectors.foundInTheNether(), (biome, ctx) -> {
                    var spawns = ctx.getMobSpawnSettings();
                    int total = 0;
                    for (var w : spawns.getMobs(MobCategory.MONSTER)) total += w.weight();
                    String path = biome.getBiomeKey().identifier().getPath();
                    boolean open = !path.contains("forest");
                    double budget = Math.max(total, MIN_TOTAL) * SHARE;
                    int rel =
                            KINDS.values().stream().mapToInt(Integer::intValue).sum();
                    for (var e : KINDS.entrySet()) add(spawns, e.getKey(), budget * e.getValue() / rel);
                    if (open) for (var e : MUTANTS.entrySet()) add(spawns, e.getKey(), e.getValue());
                });
    }

    private static void add(
            net.fabricmc.fabric.api.biome.v1.BiomeModificationContext.MobSpawnSettingsContext spawns,
            String id,
            double weight) {
        EntityType<?> type =
                BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(id)).orElse(null);
        if (type == null) return; // that companion mod is not installed
        spawns.addSpawn(
                type, Math.max(1, (int) Math.round(weight)), 1, id.contains("giant") || id.contains("mutant") ? 1 : 3);
    }
}
