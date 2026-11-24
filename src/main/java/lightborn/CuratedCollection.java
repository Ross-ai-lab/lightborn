package lightborn;

import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;

/**
 * The owner's curation: ten one-word sets. Materials, Treasures and Home are hand-picked; the Natural Blocks tab is
 * split into Earth and Plants; four creative tabs are retired except for a few named survivors.
 */
final class CuratedCollection {
    private CuratedCollection() {}

    static final String MATERIALS = "materials",
            TREASURES = "treasures",
            HOME = "home",
            EARTH = "earth",
            PLANTS = "plants";
    static final Set<String> RETIRED =
            Set.of("building_blocks", "colored_blocks", "redstone_blocks", "functional_blocks");
    private static final Set<String> MINERALS = Set.of(
            "coal_block",
            "iron_block",
            "raw_iron_block",
            "copper_block",
            "raw_copper_block",
            "gold_block",
            "raw_gold_block",
            "redstone_block",
            "lapis_block",
            "emerald_block",
            "diamond_block",
            "netherite_block",
            "amethyst_block",
            "quartz_block");
    private static final Set<String> STONE = Set.of(
            "end_stone",
            "prismarine",
            "prismarine_bricks",
            "dark_prismarine",
            "sandstone",
            "chiseled_sandstone",
            "cut_sandstone",
            "smooth_sandstone",
            "red_sandstone",
            "chiseled_red_sandstone",
            "cut_red_sandstone",
            "smooth_red_sandstone",
            "bricks",
            "glass",
            "quartz_pillar",
            "end_stone_bricks",
            "nether_bricks",
            "red_nether_bricks",
            "mud_bricks",
            "resin_bricks");
    private static final Set<String> WORK = Set.of(
            "crafting_table",
            "stonecutter",
            "furnace",
            "blast_furnace",
            "smoker",
            "smithing_table",
            "cartography_table",
            "fletching_table",
            "loom",
            "grindstone",
            "enchanting_table",
            "brewing_stand",
            "crafter");
    private static final Set<String> FURNISHINGS = Set.of(
            "redstone_lamp",
            "bell",
            "ender_eye",
            "ender_chest",
            "red_bed",
            "white_banner",
            "shulker_box",
            "chest",
            "lectern",
            "bookshelf",
            "painting",
            "item_frame",
            "glow_item_frame",
            "flower_pot",
            "decorated_pot",
            "beehive",
            "scaffolding",
            "ladder",
            "beacon",
            "cauldron",
            "jukebox",
            "note_block",
            "composter",
            "anvil",
            "campfire",
            "soul_campfire",
            "lantern",
            "barrel",
            "iron_chain",
            "iron_bars",
            "oak_shelf",
            "armor_stand",
            "end_crystal");
    /** Owner-picked survivors of the retired Redstone tab: they belong with the tools. */
    private static final Set<String> MECHANISMS =
            Set.of("rail", "lever", "piston", "sticky_piston", "slime_block", "honey_block");
    // Dedicated mob loot goes to Treasures; ordinary food and equipment keep their useful subject groups.
    private static final Set<String> LOOT = Set.of(
            "bone",
            "rotten_flesh",
            "spider_eye",
            "string",
            "gunpowder",
            "arrow",
            "ender_pearl",
            "blaze_rod",
            "breeze_rod",
            "magma_cream",
            "slime_ball",
            "ghast_tear",
            "prismarine_shard",
            "prismarine_crystals",
            "sponge",
            "wet_sponge",
            "shulker_shell",
            "phantom_membrane",
            "nautilus_shell",
            "nether_star",
            "totem_of_undying",
            "dragon_breath",
            // Rare finds beyond monster drops.
            "echo_shard",
            "heart_of_the_sea",
            "trial_key",
            "ominous_trial_key",
            "ominous_bottle",
            "heavy_core",
            "disc_fragment_5",
            "goat_horn",
            "dragon_egg",
            "rabbit_foot",
            "armadillo_scute",
            "turtle_scute",
            "ink_sac",
            "glow_ink_sac");
    /** Natural Blocks items that grow: everything else in that tab is Earth. */
    private static final List<String> GROWING = List.of(
            "mushroom",
            "fungus",
            "vine",
            "coral",
            "kelp",
            "seagrass",
            "cactus",
            "sugar_cane",
            "bamboo",
            "lily_pad",
            "moss",
            "azalea",
            "dripleaf",
            "spore_blossom",
            "roots",
            "lichen",
            "pumpkin",
            "melon",
            "hay_block",
            "wart_block",
            "shroomlight",
            "chorus",
            "pitcher",
            "torchflower",
            "petals",
            "leaf_litter",
            "wildflowers",
            "bush",
            "eyeblossom",
            "sea_pickle",
            "fern",
            "grass",
            "dead_bush",
            "seeds",
            "cocoa",
            "frogspawn",
            "nether_sprouts",
            "hanging_moss",
            "creaking_heart",
            "propagule",
            "honeycomb_block",
            "bee_nest");

    static String group(String id, ItemStack stack, String original) {
        if (id.equals("lightborn:light_chest")) return null; // The unique hub must never have to be donated to itself.
        if (id.startsWith("thetruepaxels:")) return null; // Playable tools, outside the full 999-item catalogue.
        if (id.startsWith("lightborn:")) return "lightborn:items";
        String namespace = id.substring(0, id.indexOf(':')), path = id.substring(id.indexOf(':') + 1);
        boolean vanilla = namespace.equals("minecraft");
        var block = stack.getItem() instanceof BlockItem item ? item.getBlock() : null;
        if (block instanceof AbstractSkullBlock
                || path.endsWith("_skull")
                || path.endsWith("_head")
                || vanilla && LOOT.contains(path)
                || namespace.equals("mutantmonsters") && !stack.has(DataComponents.EQUIPPABLE)) return TREASURES;
        if (vanilla && path.equals("tnt")) return "combat";
        if (vanilla && MECHANISMS.contains(path)) return "tools_and_utilities";
        if (block instanceof CandleBlock
                || block instanceof BaseTorchBlock
                || id.equals("naturalist:plush_bear")
                || vanilla && (WORK.contains(path) || FURNISHINGS.contains(path))) return HOME;
        if (vanilla
                        && (MINERALS.contains(path)
                                || STONE.contains(path)
                                || path.equals("redstone")
                                || path.endsWith("_glazed_terracotta"))
                || block != null && block.defaultBlockState().is(BlockTags.PLANKS)) return MATERIALS;
        if (original.equals("natural_blocks")) return growing(block, path) ? PLANTS : EARTH;
        return RETIRED.contains(original) ? null : original;
    }

    /** Logs, leaves, saplings, flowers, crops and other growing things (seeds included) are Plants. */
    static boolean growing(Block block, String path) {
        if (block == null) return true;
        var state = block.defaultBlockState();
        if (state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)) return true;
        if (path.equals("grass_block") || path.contains("nylium")) return false;
        for (String word : GROWING) if (path.contains(word)) return true;
        return false;
    }
}
