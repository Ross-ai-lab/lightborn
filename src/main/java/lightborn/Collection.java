package lightborn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.EntityBlock;

/**
 * The collection the Chest of Light gathers for the Light: a curated range of items, vanilla and
 * Lightborn. Groups derive from creative-tab ownership and the owner's retained essentials; each item counts once.
 * Stat-bearing sets grant quarter bonuses; every first gift of an item pays Sparks.
 * The chest is storage too: one stack per item (ModData.STORAGE). Both are kept per player, so every chest shows
 * the same, like an ender chest; breaking one loses nothing.
 */
public final class Collection {
    private Collection() {}

    public record Reward(String text, List<PlayerBonuses.Effect> effects) {}

    public record Category(String key, Component name, ItemStack icon, List<String> items, Reward reward) {}

    /** Tab id -> its reward, in the order the sets are shown. Only these tabs form the collection. */
    private static final List<Object[]> SETS = List.of(
            set(CuratedCollection.MATERIALS, "Materials", Items.DIAMOND_BLOCK, "Collection progress"),
            set(
                    CuratedCollection.TREASURES,
                    "Treasures",
                    Items.NETHER_STAR,
                    "+20% Cooldown Speed",
                    fx(ModAttributes.COOLDOWN_SPEED, .20, Operation.ADD_MULTIPLIED_BASE)),
            set(CuratedCollection.HOME, "Home", Items.CRAFTING_TABLE, "Collection progress"),
            set(
                    CuratedCollection.EARTH,
                    "Earth",
                    Items.GRASS_BLOCK,
                    "+2 hearts",
                    fx(Attributes.MAX_HEALTH, 4, Operation.ADD_VALUE)),
            set(
                    CuratedCollection.PLANTS,
                    "Plants",
                    Items.OAK_SAPLING,
                    "+20% Item Rarity",
                    fx(ModAttributes.ITEM_RARITY, .20, Operation.ADD_MULTIPLIED_BASE)),
            set(
                    "tools_and_utilities",
                    "Tools",
                    Items.IRON_PICKAXE,
                    "+40% Mining Speed",
                    fx(Attributes.BLOCK_BREAK_SPEED, .40, Operation.ADD_MULTIPLIED_BASE)),
            set(
                    "combat",
                    "Combat",
                    Items.IRON_SWORD,
                    "+40% Melee Damage",
                    fx(Attributes.ATTACK_DAMAGE, .40, Operation.ADD_MULTIPLIED_BASE)),
            set(
                    "food_and_drinks",
                    "Food",
                    Items.BREAD,
                    "+0.8 Life Regeneration per second",
                    fx(ModAttributes.LIFE_REGEN, .8, Operation.ADD_VALUE)),
            set(
                    "ingredients",
                    "Ingredients",
                    Items.BLAZE_POWDER,
                    "+80% Spell Damage",
                    fx(ModAttributes.SPELL_POWER, .80, Operation.ADD_MULTIPLIED_BASE)),
            set(
                    "lightborn:items",
                    "Lightborn",
                    null,
                    "+80 Max Mana",
                    fx(ModAttributes.MAX_MANA, 80, Operation.ADD_VALUE)));

    private static final List<String> ORIGINAL_ORDER = List.of(
            "building_blocks",
            "colored_blocks",
            "natural_blocks",
            "functional_blocks",
            "redstone_blocks",
            "tools_and_utilities",
            "combat",
            "food_and_drinks",
            "ingredients",
            "lightborn:items");

    /**
     * Operator/technical items and the unique hub itself are excluded before catalogue curation.
     */
    static final Set<String> UNOBTAINABLE = Set.of(
            "lightborn:light_chest",
            "minecraft:bedrock",
            "minecraft:budding_amethyst",
            "minecraft:reinforced_deepslate",
            "minecraft:end_portal_frame",
            "minecraft:spawner",
            "minecraft:trial_spawner",
            "minecraft:vault",
            "minecraft:infested_stone",
            "minecraft:infested_cobblestone",
            "minecraft:infested_stone_bricks",
            "minecraft:infested_mossy_stone_bricks",
            "minecraft:infested_cracked_stone_bricks",
            "minecraft:infested_chiseled_stone_bricks",
            "minecraft:infested_deepslate",
            "minecraft:petrified_oak_slab",
            "minecraft:farmland",
            "minecraft:dirt_path",
            "minecraft:chorus_plant",
            "minecraft:frogspawn",
            "minecraft:player_head",
            "minecraft:suspicious_sand",
            "minecraft:suspicious_gravel",
            "minecraft:knowledge_book",
            "minecraft:debug_stick",
            "minecraft:light",
            "minecraft:barrier",
            "minecraft:structure_void",
            "minecraft:structure_block",
            "minecraft:jigsaw",
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
            "minecraft:command_block_minecart",
            "minecraft:test_block",
            "minecraft:test_instance_block");

    private static List<Category> categories = List.of();
    private static List<String> all = List.of();
    private static Map<String, Category> itemSets = Map.of();

    /** A set: its key, its one-word name, its icon (null = its creative tab's own icon) and its reward. */
    private static Object[] set(String key, String name, Item icon, String text, PlayerBonuses.Effect... effects) {
        return new Object[] {key, new Reward(text, List.of(effects)), name, icon};
    }

    private static PlayerBonuses.Effect fx(Holder<Attribute> a, double v, Operation op) {
        return new PlayerBonuses.Effect(a, v, op);
    }

    /**
     * Builds the catalogue from the creative tabs once; both sides call it and get the same lists (in single player
     * they share it outright). The tabs fill only on request, so this asks for a rebuild without operator items.
     */
    public static synchronized void build(FeatureFlagSet features, HolderLookup.Provider registries) {
        if (!categories.isEmpty()) return;
        CreativeModeTabs.tryRebuildTabContents(features, false, registries);
        var tabs = new LinkedHashMap<String, CreativeModeTab>();
        for (var tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            var id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            tabs.put(id.getNamespace().equals("minecraft") ? id.getPath() : id.toString(), tab);
        }
        var groups = new LinkedHashMap<String, List<String>>();
        for (Object[] spec : SETS) groups.put((String) spec[0], new ArrayList<>());
        // Keep each item in its first matching set even if other tabs also display it.
        Set<String> visited = new LinkedHashSet<>();
        BiConsumer<String, ItemStack> accept = (original, stack) -> {
            String id = idOf(stack.getItem());
            if (UNOBTAINABLE.contains(id) || stack.getItem() instanceof SpawnEggItem || visited.contains(id)) return;
            String chosen = CuratedCollection.group(id, stack, original);
            if (groups.containsKey(chosen)) {
                groups.get(chosen).add(id);
                visited.add(id);
            }
        };
        for (String original : ORIGINAL_ORDER) {
            var tab = tabs.get(original);
            if (tab != null) for (var stack : tab.getDisplayItems()) accept.accept(original, stack);
        }
        for (var tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            String namespace = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab).getNamespace();
            if (namespace.equals("minecraft") || namespace.equals(Lightborn.MOD_ID)) continue;
            for (var stack : tab.getDisplayItems()) accept.accept(companionItemGroup(stack), stack);
        }
        // These survival items are generated dynamically rather than through an ordinary creative tab entry.
        for (Item item : List.of(
                Items.FILLED_MAP, Items.WRITTEN_BOOK, Items.ENCHANTED_BOOK, Items.FIREWORK_STAR, Items.TIPPED_ARROW)) {
            var stack = new ItemStack(item);
            accept.accept(companionItemGroup(stack), stack);
        }
        var out = new ArrayList<Category>();
        for (Object[] spec : SETS) {
            String key = (String) spec[0];
            Component name = Component.literal((String) spec[2]);
            ItemStack icon = spec[3] != null
                    ? new ItemStack((Item) spec[3])
                    : tabs.get(key).getIconItem();
            out.add(new Category(key, name, icon, List.copyOf(groups.get(key)), (Reward) spec[1]));
        }
        categories = List.copyOf(out);
        all = out.stream().flatMap(c -> c.items().stream()).toList();
        var lookup = new HashMap<String, Category>();
        for (Category category : out) for (String id : category.items()) lookup.put(id, category);
        itemSets = Map.copyOf(lookup);
    }

    static String companionItemGroup(ItemStack stack) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id.getPath();
        if (id.getNamespace().equals("naturalist")) return animalItemGroup(stack);
        if (stack.has(DataComponents.FOOD) || stack.has(DataComponents.CONSUMABLE)) return "food_and_drinks";
        if (stack.getItem() instanceof BlockItem item) {
            var block = item.getBlock();
            var state = block.defaultBlockState();
            if (state.is(BlockTags.WOOL)
                    || state.is(BlockTags.WOOL_CARPETS)
                    || path.contains("stained_glass")
                    || path.contains("concrete")
                    || path.contains("terracotta")) return "colored_blocks";
            if (block instanceof EntityBlock
                    || path.contains("cooking_pot")
                    || path.contains("cutting_board")
                    || path.contains("stove")
                    || path.contains("cabinet")
                    || path.contains("lantern")
                    || path.contains("sign")
                    || path.contains("composter")) return "functional_blocks";
            if (state.isSignalSource()) return "redstone_blocks";
            if (state.is(BlockTags.LOGS)
                    || state.is(BlockTags.LEAVES)
                    || state.is(BlockTags.SAPLINGS)
                    || state.is(BlockTags.FLOWERS)
                    || block instanceof BonemealableBlock) return "natural_blocks";
            if (path.contains("planks")
                    || path.contains("brick")
                    || path.contains("tile")
                    || path.contains("polished")
                    || path.contains("chiseled")
                    || path.contains("pillar")
                    || path.contains("slab")
                    || path.contains("stairs")
                    || path.contains("wall")
                    || path.contains("fence")
                    || path.contains("glass")
                    || path.contains("door")) return "building_blocks";
            return "natural_blocks";
        }
        if (stack.has(DataComponents.WEAPON) || stack.has(DataComponents.EQUIPPABLE)) return "combat";
        if (stack.has(DataComponents.TOOL)
                || path.contains("compass")
                || path.endsWith("_bucket")
                || path.startsWith("music_disc_")
                || path.contains("fishing_rod")
                || path.endsWith("_map")) return "tools_and_utilities";
        return "ingredients";
    }

    private static String animalItemGroup(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (stack.has(DataComponents.FOOD) || stack.has(DataComponents.CONSUMABLE)) return "food_and_drinks";
        if (path.equals("plush_bear")) return "functional_blocks";
        if (stack.getItem() instanceof BlockItem)
            return path.contains("froglass") || path.startsWith("shellstone") ? "building_blocks" : "natural_blocks";
        if (path.endsWith("_bucket")
                || path.startsWith("music_disc_")
                || Set.of(
                                "capture_net",
                                "knapsack",
                                "whistle",
                                "caterpillar",
                                "butterfly",
                                "ant",
                                "rat",
                                "scorpion",
                                "hedgehog",
                                "dragonfly",
                                "firefly",
                                "bee",
                                "silverfish",
                                "endermite",
                                "queen_ant",
                                "snail",
                                "crab")
                        .contains(path)) return "tools_and_utilities";
        return "ingredients";
    }

    public static List<Category> categories() {
        return categories;
    }

    /** Every catalogue id, in order. */
    public static List<String> all() {
        return all;
    }

    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    public static Category categoryOf(String id) {
        return itemSets.get(id);
    }

    /** What this player has given. Works on both sides (the client reads its synced copy). */
    public static Set<String> collected(Player player) {
        List<String> list = player.getAttached(ModData.COLLECTION);
        return list == null ? Set.of() : new LinkedHashSet<>(list);
    }

    public static int count(Set<String> got, Category c) {
        int n = 0;
        for (String id : c.items()) if (got.contains(id)) n++;
        return n;
    }

    public static boolean complete(Set<String> got, Category c) {
        return !c.items().isEmpty() && count(got, c) == c.items().size();
    }

    public static boolean allComplete(Set<String> got) {
        return !all.isEmpty() && got.containsAll(all);
    }

    public static int totalCount(Set<String> got) {
        return (int) all.stream().filter(got::contains).count();
    }

    public static int quarters(Set<String> got, Category category) {
        return category.items().isEmpty()
                ? 0
                : count(got, category)
                        * Balance.COLLECTION_QUARTERS
                        / category.items().size();
    }

    /** Actual attribute amounts at a chosen milestone, not the full-set description. */
    public static Component rewardAmount(Category category, int quarters) {
        var line = Component.empty();
        int i = 0;
        for (var effect : category.reward().effects()) {
            if (i++ > 0) line.append(Component.literal(", "));
            boolean percent = effect.op() != Operation.ADD_VALUE;
            double amount = effect.perRank() * quarters / Balance.COLLECTION_QUARTERS * (percent ? 100 : 1);
            if (effect.attribute().equals(Attributes.MAX_HEALTH)) {
                double hearts = amount / 2.0;
                line.append(Component.literal("+" + LootRoller.trim(hearts) + (hearts == 1 ? " heart" : " hearts")));
                continue;
            }
            String number = BigDecimal.valueOf(amount)
                    .setScale(3, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString();
            line.append(Component.literal((amount >= 0 ? "+" : "") + number + (percent ? "% " : " ")))
                    .append(
                            effect.attribute().equals(ModAttributes.SPELL_POWER)
                                    ? Component.literal("Spell Damage")
                                    : Component.translatable(
                                            effect.attribute().value().getDescriptionId()));
            if (effect.attribute().equals(ModAttributes.LIFE_REGEN)) line.append(Component.literal(" /s"));
        }
        return line;
    }

    public static boolean newForLight(Player player, ItemStack stack) {
        String id = idOf(stack.getItem());
        return !stack.isEmpty() && categoryOf(id) != null && !collected(player).contains(id);
    }

    public static boolean canGiveNonEssential(Player player, ItemStack stack) {
        return nonEssentialGift(stack) && room(stored(player, idOf(stack.getItem())), stack) > 0;
    }

    /** Keep the player's working kit and progression items in their inventory during the selective bulk gift. */
    private static boolean nonEssentialGift(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (categoryOf(idOf(item)) == null) return false;
        // These items are bulk donations even though vanilla permits eating or using them.
        if (stack.is(Items.SPIDER_EYE)
                || stack.is(Items.POISONOUS_POTATO)
                || stack.is(Items.PUFFERFISH)
                || stack.is(Items.ROTTEN_FLESH)
                || stack.is(Items.ENDER_PEARL)) return true;
        return !stack.isDamageableItem()
                && !stack.has(DataComponents.TOOL)
                && !stack.has(DataComponents.WEAPON)
                && !stack.has(DataComponents.FOOD)
                && !stack.has(DataComponents.CONSUMABLE)
                && !(stack.has(DataComponents.EQUIPPABLE) && !(item instanceof BlockItem))
                && !(item instanceof BlockItem block && block.getBlock() instanceof BaseTorchBlock)
                && stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                        .equals(ItemContainerContents.EMPTY)
                && stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY)
                        .isEmpty()
                && !(item instanceof TomeItem)
                && !ModItems.isStaff(stack)
                && !stack.is(Spellstone.ITEM)
                && !stack.is(ModItems.SPARK)
                && !stack.is(ModItems.DARK_SHARD)
                && !stack.is(DragonStory.LIGHT_KEY)
                && !stack.is(DragonStory.SCRIBE_SEAL)
                && !stack.is(net.minecraft.tags.ItemTags.ARROWS)
                && !stack.is(Items.COMPASS)
                && !stack.is(Items.RECOVERY_COMPASS)
                && !stack.is(Items.CLOCK)
                && !stack.is(Items.MAP)
                && !stack.is(Items.FILLED_MAP)
                && !stack.is(Items.BUCKET)
                && !stack.is(Items.WATER_BUCKET)
                && !stack.is(Items.LAVA_BUCKET)
                && !stack.is(Items.MILK_BUCKET)
                && !stack.is(Items.POWDER_SNOW_BUCKET)
                && !stack.is(Items.FIREWORK_ROCKET)
                && !stack.is(Items.TNT)
                && !stack.is(Items.END_CRYSTAL)
                && !stack.is(Items.ENDER_EYE)
                && !stack.is(Items.TOTEM_OF_UNDYING)
                && !stack.is(Items.TRIAL_KEY)
                && !stack.is(Items.OMINOUS_TRIAL_KEY);
    }

    public static void ensure(ServerPlayer player) {
        var server = player.level().getServer();
        build(server.getWorldData().enabledFeatures(), server.registryAccess());
    }

    // ---------------------------------------------------------------- the chest's store

    /** What this player keeps in the Chest of Light: at most one stack per catalogue item. Both sides (synced). */
    public static List<ItemStack> storage(Player player) {
        List<ItemStack> list = player.getAttached(ModData.STORAGE);
        return list == null ? List.of() : list;
    }

    /** The stored stack of this item, or EMPTY. */
    public static ItemStack stored(Player player, String id) {
        for (ItemStack s : storage(player)) if (idOf(s.getItem()).equals(id)) return s;
        return ItemStack.EMPTY;
    }

    /** Takes up to {@code n} of a stored item (used to pay from the store); returns how many were taken. */
    static int spendStored(ServerPlayer player, String id, int n) {
        ItemStack s = stored(player, id);
        int used = Math.min(n, s.getCount());
        if (used > 0) setStored(player, id, s.copyWithCount(s.getCount() - used));
        return used;
    }

    private static void setStored(ServerPlayer player, String id, ItemStack stack) {
        List<ItemStack> list = new ArrayList<>();
        boolean placed = false;
        for (ItemStack s : storage(player)) {
            if (idOf(s.getItem()).equals(id)) {
                if (!stack.isEmpty()) list.add(stack.copy());
                placed = true;
            } else {
                list.add(s.copy());
            }
        }
        if (!placed && !stack.isEmpty()) list.add(stack.copy());
        player.setAttached(ModData.STORAGE, List.copyOf(list));
    }

    /** How many more of {@code stack} fit beside what is stored: 0 when full, or when a different variant holds the slot. */
    private static int room(ItemStack stored, ItemStack stack) {
        if (stored.isEmpty()) return stack.getMaxStackSize();
        if (!ItemStack.isSameItemSameComponents(stored, stack)) return 0;
        return stored.getMaxStackSize() - stored.getCount();
    }

    // ---------------------------------------------------------------- giving and taking

    /** Outcome of offering one item. */
    public enum Offer {
        TAKEN,
        STORED,
        FULL,
        NOT_WANTED
    }

    /**
     * Ctrl + right-click with a held item: one of it goes into the chest. The first one of its kind also ticks it in the
     * collection; later ones just add to its stack (one stack per item, the same variant only).
     */
    public static Offer offer(ServerPlayer player, ItemStack stack, BlockPos chest) {
        ensure(player);
        LightWards.claim(player.level(), chest, player);
        if (stack.isEmpty()) return Offer.NOT_WANTED;
        String id = idOf(stack.getItem());
        if (categoryOf(id) == null) {
            player.sendSystemMessage(
                    Component.literal("The Light does not take this").withStyle(ChatFormatting.GRAY), true);
            return Offer.NOT_WANTED;
        }
        ItemStack stored = stored(player, id);
        if (room(stored, stack) <= 0) {
            player.sendSystemMessage(
                    (stored.isEmpty() || ItemStack.isSameItemSameComponents(stored, stack)
                                    ? Component.literal("The chest's stack of ")
                                            .append(stack.getHoverName())
                                            .append(" is full")
                                    : Component.literal("The chest already keeps a different ")
                                            .append(stack.getHoverName()))
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return Offer.FULL;
        }
        ItemStack one = stack.copyWithCount(1);
        stack.shrink(1);
        setStored(player, id, stored.isEmpty() ? one : stored.copyWithCount(stored.getCount() + 1));
        if (collected(player).contains(id)) {
            player.sendSystemMessage(
                    Component.literal("Stored: ")
                            .append(one.getHoverName())
                            .append(Component.literal(
                                    "  (" + stored(player, id).getCount() + " / " + one.getMaxStackSize() + ")"))
                            .withStyle(ChatFormatting.YELLOW),
                    true);
            player.level().playSound(null, chest, SoundEvents.AMETHYST_BLOCK_STEP, SoundSource.BLOCKS, 0.8f, 1.4f);
            return Offer.STORED;
        }
        take(player, List.of(id), chest);
        return Offer.TAKEN;
    }

    /** From the book's Put in button: every matching item carried, until the chest's stack is full. Returns how many. */
    public static int deposit(ServerPlayer player, String id, BlockPos chest) {
        ensure(player);
        LightWards.claim(player.level(), chest, player);
        if (categoryOf(id) == null) return 0;
        var inv = player.getInventory();
        ItemStack stored = stored(player, id).copy();
        int moved = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !idOf(s.getItem()).equals(id)) continue;
            int n = Math.min(s.getCount(), room(stored, s));
            if (n <= 0) continue;
            stored = stored.isEmpty() ? s.copyWithCount(n) : stored.copyWithCount(stored.getCount() + n);
            s.shrink(n);
            moved += n;
        }
        if (moved == 0) {
            player.sendSystemMessage(
                    Component.literal("You carry none that fit").withStyle(ChatFormatting.GRAY), true);
            return 0;
        }
        setStored(player, id, stored);
        if (!collected(player).contains(id)) take(player, List.of(id), chest);
        else player.level().playSound(null, chest, SoundEvents.AMETHYST_BLOCK_STEP, SoundSource.BLOCKS, 0.8f, 1.4f);
        return moved;
    }

    /** Donate one of each new carried type that fits. */
    public static int giveAllNew(ServerPlayer player, BlockPos chest) {
        return giveBulk(player, chest, true);
    }

    /** Store every eligible copy that fits, with collection rewards only for the first gift of each type. */
    public static int giveNonEssential(ServerPlayer player, BlockPos chest) {
        return giveBulk(player, chest, false);
    }

    private static int giveBulk(ServerPlayer player, BlockPos chest, boolean newOnly) {
        ensure(player);
        LightWards.claim(player.level(), chest, player);
        var contents = new LinkedHashMap<String, ItemStack>();
        for (ItemStack kept : storage(player)) contents.putIfAbsent(idOf(kept.getItem()), kept);
        var seen = new LinkedHashSet<>(collected(player));
        var newTypes = new LinkedHashSet<String>();
        var donations = new LinkedHashMap<String, ItemStack>();
        var inventory = player.getInventory();
        int moved = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            String id = idOf(stack.getItem());
            if (newOnly ? seen.contains(id) || categoryOf(id) == null : !nonEssentialGift(stack)) continue;
            ItemStack kept = contents.getOrDefault(id, ItemStack.EMPTY);
            int count = Math.min(newOnly ? 1 : stack.getCount(), room(kept, stack));
            if (count <= 0) continue;
            contents.put(id, stack.copyWithCount(kept.getCount() + count));
            donations.merge(
                    id,
                    stack.copyWithCount(count),
                    (first, next) -> first.copyWithCount(first.getCount() + next.getCount()));
            stack.shrink(count);
            moved += count;
            if (seen.add(id)) newTypes.add(id);
        }
        if (moved == 0) {
            String message = newOnly ? "You carry no new things that fit" : "You carry no non-essential items that fit";
            player.sendOverlayMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
            return 0;
        }
        player.setAttached(ModData.STORAGE, List.copyOf(contents.values()));
        if (newTypes.isEmpty()) {
            player.level().playSound(null, chest, SoundEvents.AMETHYST_BLOCK_STEP, SoundSource.BLOCKS, 0.8f, 1.4f);
        } else {
            take(player, List.copyOf(newTypes), chest);
        }
        var gifts = donations.entrySet().stream()
                .map(entry -> new Net.Gift(entry.getValue(), newTypes.contains(entry.getKey())))
                .toList();
        ServerPlayNetworking.send(
                player, new Net.Unlocked(chest, gifts, Balance.COLLECTION_GIFT_SPARKS * newTypes.size()));
        return moved;
    }

    /**
     * From the book's Take buttons: one, or the whole stack, back into the inventory (dropped at the player's feet when
     * full). It stays ticked in the collection: what was given once counts for good (owner's choice).
     */
    public static int withdraw(ServerPlayer player, String id, boolean all, BlockPos chest) {
        ItemStack stored = stored(player, id);
        if (stored.isEmpty()) return 0;
        int n = all ? stored.getCount() : 1;
        ItemStack out = stored.copyWithCount(n);
        setStored(player, id, stored.copyWithCount(stored.getCount() - n));
        if (!player.getInventory().add(out)) Block.popResource(player.level(), player.blockPosition(), out);
        player.level().playSound(null, chest, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.6f, 0.8f);
        return n;
    }

    /** Records the ids, then the feedback: chime and light, a progress line, the reward of every finished set. */
    private static void take(ServerPlayer player, List<String> ids, BlockPos chest) {
        Set<String> before = collected(player);
        Set<String> after = new LinkedHashSet<>(before);
        after.addAll(ids);
        player.setAttached(ModData.COLLECTION, List.copyOf(after));
        int newlyGiven = after.size() - before.size();
        if (newlyGiven > 0) {
            ItemStack sparks = new ItemStack(ModItems.SPARK, Balance.COLLECTION_GIFT_SPARKS * newlyGiven);
            while (!sparks.isEmpty()) {
                ItemStack part = sparks.split(sparks.getMaxStackSize());
                if (!player.getInventory().add(part)) Block.popResource(player.level(), chest.above(), part);
            }
        }

        ServerLevel level = player.level();
        double x = chest.getX() + 0.5, y = chest.getY() + 0.9, z = chest.getZ() + 0.5;
        level.sendParticles(ParticleTypes.END_ROD, x, y, z, 10 + 4 * Math.min(ids.size(), 20), 0.25, 0.3, 0.25, 0.05);
        level.sendParticles(ParticleTypes.WAX_OFF, x, y, z, 6, 0.3, 0.2, 0.3, 0.5);
        level.playSound(
                null,
                chest,
                SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.BLOCKS,
                1.0f,
                1.2f + level.getRandom().nextFloat() * 0.4f);

        List<Category> filled = ids.stream()
                .map(Collection::categoryOf)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        for (int i = 0; i < filled.size(); i++) {
            Category group = filled.get(i);
            Component line = giftFeedback(ids, group, after);
            if (i == 0) player.sendOverlayMessage(line);
            else
                Scheduler.later(i * 35, () -> {
                    if (!player.isRemoved()) player.sendOverlayMessage(line);
                });
        }

        boolean any = false;
        int feedback = 0;
        for (Category c : categories) {
            for (int quarter = quarters(before, c) + 1; quarter <= quarters(after, c); quarter++) {
                any = true;
                int milestone = quarter * 100 / Balance.COLLECTION_QUARTERS;
                player.sendSystemMessage(Component.empty()
                        .append(c.name().copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                        .append(Component.literal(" " + milestone + "%. Reward unlocked toward ")
                                .withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(c.reward().text()).withStyle(ChatFormatting.YELLOW)));
                Scheduler.later(1 + 24 * feedback++, () -> {
                    if (player.isRemoved()) return;
                    level.playSound(null, chest, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 0.8f, 1.4f);
                    // The finale owns the title when the last gift completes the catalogue.
                    if (allComplete(after)) return;
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(8, 35, 12));
                    player.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(
                            c.name().copy().append(" " + milestone + "%").withStyle(ChatFormatting.GOLD)));
                });
            }
        }
        if (any) PlayerBonuses.apply(player);
        LightWards.updateOwned(player);
        CollectionLight.awardIfComplete(player);
    }

    static Component giftFeedback(List<String> ids, Category group, Set<String> after) {
        Component what = ids.size() == 1
                ? Component.literal("Given to the Light: ")
                        .append(Component.translatable(item(ids.getFirst()).getDescriptionId()))
                : group.name().copy();
        return what.copy()
                .append(Component.literal(
                        "  (" + count(after, group) + " / " + group.items().size() + ")"))
                .withStyle(ChatFormatting.GOLD);
    }

    // ---------------------------------------------------------------- rewards

    /** Re-applies the bonus of every finished set as permanent modifiers; called from PlayerBonuses.apply. */
    static void applyRewards(ServerPlayer player) {
        ensure(player);
        Set<String> got = collected(player);
        // Retiring a category also retires its old permanent modifier.
        var retired = Map.of(
                "building_blocks",
                Attributes.ARMOR,
                "colored_blocks",
                ModAttributes.ITEM_RARITY,
                "functional_blocks",
                ModAttributes.COOLDOWN_SPEED,
                "redstone_blocks",
                Attributes.MOVEMENT_SPEED);
        retired.forEach((key, attribute) -> {
            var stat = player.getAttribute(attribute);
            if (stat != null) stat.removeModifier(ModItems.id("collection_" + key + "_0"));
        });
        for (Category c : categories)
            apply(
                    player,
                    "collection_" + c.key().replace(':', '_'),
                    c.reward(),
                    quarters(got, c) / (double) Balance.COLLECTION_QUARTERS);
        LightWards.updateOwned(player);
        CollectionLight.awardIfComplete(player);
        // The all-sets crown was removed (owner: it was dull); clear it from players who earned it before.
        for (int i = 0; i < 2; i++) {
            Identifier old = Identifier.fromNamespaceAndPath(Lightborn.MOD_ID, "collection_crown_" + i);
            for (var a : List.of(ModAttributes.SPELL_POWER, Attributes.MAX_HEALTH)) {
                AttributeInstance inst = player.getAttribute(a);
                if (inst != null) inst.removeModifier(old);
            }
        }
    }

    private static void apply(ServerPlayer player, String name, Reward reward, double fraction) {
        for (int i = 0; i < reward.effects().size(); i++) {
            PlayerBonuses.Effect e = reward.effects().get(i);
            AttributeInstance inst = player.getAttribute(e.attribute());
            if (inst == null) continue;
            Identifier id = Identifier.fromNamespaceAndPath(Lightborn.MOD_ID, name + "_" + i);
            inst.removeModifier(id);
            if (fraction > 0) inst.addPermanentModifier(new AttributeModifier(id, e.perRank() * fraction, e.op()));
        }
    }

    // ---------------------------------------------------------------- how a thing is made

    private static Map<String, List<RecipeDisplay>> recipes;

    /**
     * Every way to make this item that the book can draw: crafting (shaped or not), furnace-type cooking, the
     * stonecutter and the smithing table, at most 8. Built once per server from the recipe manager.
     */
    public static synchronized List<RecipeDisplay> recipesFor(ServerLevel level, String id) {
        if (recipes == null) {
            recipes = new HashMap<>();
            var ctx = SlotDisplayContext.fromLevel(level);
            for (var holder : level.getServer().getRecipeManager().getRecipes()) {
                for (RecipeDisplay original : holder.value().display()) {
                    RecipeDisplay d = drawableRecipe(original, holder.value());
                    if (d == null) continue;
                    ItemStack result = d.result().resolveForFirstStack(ctx);
                    if (result.isEmpty()) continue;
                    List<RecipeDisplay> list = recipes.computeIfAbsent(idOf(result.getItem()), k -> new ArrayList<>());
                    if (list.size() < 8) list.add(d);
                }
            }
        }
        return recipes.getOrDefault(id, List.of());
    }
    /** Keep companion workstation recipes usable without making their optional Java classes a hard dependency. */
    private static RecipeDisplay drawableRecipe(RecipeDisplay d, Object recipe) {
        if (d instanceof ShapedCraftingRecipeDisplay
                || d instanceof ShapelessCraftingRecipeDisplay
                || d instanceof FurnaceRecipeDisplay
                || d instanceof StonecutterRecipeDisplay
                || d instanceof SmithingRecipeDisplay) return d;
        var inputs = new ArrayList<SlotDisplay>();
        Object list = recipeField(d, "ingredients");
        if (list instanceof List<?> values)
            for (Object value : values) if (value instanceof SlotDisplay slot) inputs.add(slot);
        if (inputs.isEmpty())
            for (String key : List.of("input", "ingredient", "base", "addition"))
                if (recipeField(d, key) instanceof SlotDisplay slot) inputs.add(slot);
        Object container = recipeField(d, "container");
        if (container instanceof Optional<?> optional && optional.orElse(null) instanceof SlotDisplay slot)
            inputs.add(slot);
        // A cooking display omits its default serving vessel; the recipe resolves it from the result's remainder.
        if (container instanceof Optional<?> optional
                && optional.isEmpty()
                && recipeField(recipe, "container") instanceof ItemStackTemplate vessel)
            inputs.add(new SlotDisplay.ItemStackSlotDisplay(vessel));
        if (inputs.isEmpty() || inputs.size() > 9) return null;
        return new ShapelessCraftingRecipeDisplay(List.copyOf(inputs), d.result(), d.craftingStation());
    }

    private static Object recipeField(Object d, String name) {
        try {
            return d.getClass().getMethod(name).invoke(d);
        } catch (NoSuchMethodException missing) {
            return null;
        } catch (ReflectiveOperationException broken) {
            throw new IllegalStateException(
                    "Cannot read companion recipe " + d.getClass().getName() + "." + name, broken);
        }
    }

    /** Server stopping: recipes may change with the next world's datapacks. */
    static void clearRecipes() {
        recipes = null;
        categories = List.of();
        all = List.of();
    }
}
