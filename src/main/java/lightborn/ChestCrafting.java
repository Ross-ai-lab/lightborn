package lightborn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

/** A server-side crafting table backed by the player's inventory and the Chest of Light store. */
public final class ChestCrafting {
    private ChestCrafting() {}

    public static final int PAGE_SIZE = 16, BROWSE = 0, CRAFT = 1, ASSEMBLE = 2;
    private static final int GRID = 9;

    public record Entry(String key, ItemStack stack) {
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(192), Entry::key, ModData.CHEST_STACK, Entry::stack, Entry::new);
    }

    public record Ask(
            BlockPos chest, boolean manual, String search, String recipe, List<String> cells, int page, int action)
            implements CustomPacketPayload {
        public static final Type<Ask> TYPE = new Type<>(ModItems.id("craft_ask"));
        static final StreamCodec<RegistryFriendlyByteBuf, Ask> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                Ask::chest,
                ByteBufCodecs.BOOL,
                Ask::manual,
                ByteBufCodecs.stringUtf8(80),
                Ask::search,
                ByteBufCodecs.stringUtf8(192),
                Ask::recipe,
                ByteBufCodecs.stringUtf8(192).apply(ByteBufCodecs.list(GRID)),
                Ask::cells,
                ByteBufCodecs.VAR_INT,
                Ask::page,
                ByteBufCodecs.VAR_INT,
                Ask::action,
                Ask::new);

        @Override
        public Type<Ask> type() {
            return TYPE;
        }
    }

    public record Show(
            BlockPos chest,
            List<Entry> entries,
            List<ItemStack> grid,
            ItemStack output,
            String matched,
            int page,
            int pages,
            int crafted,
            String message)
            implements CustomPacketPayload {
        public static final Type<Show> TYPE = new Type<>(ModItems.id("craft_show"));
        static final StreamCodec<RegistryFriendlyByteBuf, Show> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                Show::chest,
                Entry.CODEC.apply(ByteBufCodecs.list(PAGE_SIZE)),
                Show::entries,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(GRID)),
                Show::grid,
                ItemStack.OPTIONAL_STREAM_CODEC,
                Show::output,
                ByteBufCodecs.stringUtf8(192),
                Show::matched,
                ByteBufCodecs.VAR_INT,
                Show::page,
                ByteBufCodecs.VAR_INT,
                Show::pages,
                ByteBufCodecs.VAR_INT,
                Show::crafted,
                ByteBufCodecs.stringUtf8(220),
                Show::message,
                Show::new);

        @Override
        public Type<Show> type() {
            return TYPE;
        }
    }

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Ask.TYPE, Ask.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Show.TYPE, Show.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Ask.TYPE, (ask, ctx) -> {
            if (LightShop.near(ctx.player(), ask.chest())) ctx.responseSender().sendPacket(handle(ctx.player(), ask));
        });
    }

    private record Stock(
            Map<String, ItemStack> byKey, Map<Holder<Item>, List<String>> simple, StackedItemContents contents) {}

    private static Stock stock(ServerPlayer player) {
        var available = new LinkedHashMap<String, ItemStack>();
        for (var stack : Collection.storage(player)) available.put(Collection.idOf(stack.getItem()), stack);
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) available.put("@" + slot, stack);
        }
        var simple = new HashMap<Holder<Item>, List<String>>();
        var contents = new StackedItemContents();
        available.forEach((key, stack) -> {
            // Automatic selection leaves named, enchanted, upgraded and component-bearing items to the manual grid.
            if (!ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()))) return;
            contents.accountStack(stack, stack.getCount());
            simple.computeIfAbsent(stack.getItem().builtInRegistryHolder(), item -> new ArrayList<>())
                    .add(key);
        });
        return new Stock(available, simple, contents);
    }

    private record Plan(
            RecipeHolder<CraftingRecipe> recipe,
            Map<String, Integer> used,
            CraftingInput input,
            List<ItemStack> grid,
            ItemStack result) {}

    private static Plan automatic(ServerPlayer player, Stock stock, RecipeHolder<?> holder) {
        if (holder == null
                || !(holder.value() instanceof CraftingRecipe recipe)
                || recipe.placementInfo().isImpossibleToPlace()) return null;
        var needed = new ArrayList<Holder<Item>>();
        if (!stock.contents().canCraft(recipe, needed::add)) return null;
        int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
        int height = recipe instanceof ShapedRecipe shaped ? shaped.getHeight() : 3;
        var keys = new ArrayList<>(java.util.Collections.nCopies(GRID, ""));
        var taken = new HashMap<String, Integer>();
        var positions = recipe.placementInfo().slotsToIngredientIndex();
        if (width > 3 || height > 3 || positions.size() > GRID) return null;
        for (int slot = 0; slot < positions.size(); slot++) {
            int index = positions.getInt(slot);
            if (index < 0) continue;
            var sources = stock.simple().getOrDefault(needed.get(index), List.of());
            for (String source : sources) {
                int count = taken.getOrDefault(source, 0);
                if (count == stock.byKey().get(source).getCount()) continue;
                taken.put(source, count + 1);
                keys.set(slot / width * 3 + slot % width, source);
                break;
            }
        }
        return plan(player, stock, keys, asCraftRecipe(holder));
    }

    @SuppressWarnings("unchecked")
    private static RecipeHolder<CraftingRecipe> asCraftRecipe(RecipeHolder<?> holder) {
        return (RecipeHolder<CraftingRecipe>) holder;
    }

    private static Plan plan(ServerPlayer player, Stock stock, List<String> keys, RecipeHolder<CraftingRecipe> recipe) {
        if (keys.size() != GRID) return null;
        var grid = new ArrayList<ItemStack>(GRID);
        var used = new LinkedHashMap<String, Integer>();
        for (String key : keys) {
            var source = stock.byKey().getOrDefault(key, ItemStack.EMPTY);
            if (key.isEmpty()) grid.add(ItemStack.EMPTY);
            else if (source.isEmpty() || used.merge(key, 1, Integer::sum) > source.getCount()) return null;
            else grid.add(source.copyWithCount(1));
        }
        var input = CraftingInput.ofPositioned(3, 3, grid).input();
        if (input.ingredientCount() == 0) return null;
        if (recipe == null)
            recipe = player.level()
                    .getServer()
                    .getRecipeManager()
                    .getRecipeFor(RecipeType.CRAFTING, input, player.level())
                    .orElse(null);
        if (recipe == null || !recipe.value().matches(input, player.level())) return null;
        var result = recipe.value().assemble(input);
        return result.isEmpty() || !result.getItem().isEnabled(player.level().enabledFeatures())
                ? null
                : new Plan(recipe, used, input, grid, result);
    }

    private static void execute(ServerPlayer player, Plan plan) {
        var stored = new HashMap<String, Integer>();
        plan.used().forEach((key, count) -> {
            if (key.startsWith("@")) player.getInventory().removeItem(Integer.parseInt(key.substring(1)), count);
            else stored.put(key, count);
        });
        if (!stored.isEmpty()) {
            var remaining = new ArrayList<ItemStack>();
            for (var stack : Collection.storage(player)) {
                int left = stack.getCount() - stored.getOrDefault(Collection.idOf(stack.getItem()), 0);
                if (left > 0) remaining.add(stack.copyWithCount(left));
            }
            player.setAttached(ModData.STORAGE, List.copyOf(remaining));
        }
        var result = plan.result().copy();
        result.onCraftedBy(player, result.getCount());
        if (!plan.recipe().value().isSpecial()) player.awardRecipes(List.of(plan.recipe()));
        player.getInventory().placeItemBackInInventory(result, Prediction.SERVER_ONLY);
        for (var stack : plan.recipe().value().getRemainingItems(plan.input()))
            if (!stack.isEmpty()) player.getInventory().placeItemBackInInventory(stack.copy(), Prediction.SERVER_ONLY);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    static Show handle(ServerPlayer player, Ask ask) {
        var manager = player.level().getServer().getRecipeManager();
        var key = Identifier.tryParse(ask.recipe());
        var recipe = ask.recipe().isEmpty() || key == null
                ? null
                : manager.byKey(ResourceKey.create(Registries.RECIPE, key)).orElse(null);
        var stock = stock(player);
        var preview = ask.manual() ? plan(player, stock, ask.cells(), null) : automatic(player, stock, recipe);
        int created = 0;
        ItemStack last = ItemStack.EMPTY;
        if (ask.action() == CRAFT || ask.action() == ASSEMBLE) {
            int attempts = ask.action() == CRAFT || ask.manual() ? 1 : 64;
            while (attempts-- > 0 && preview != null && id(preview.recipe()).equals(ask.recipe())) {
                // Freeze the batch's output components when an ingredient uses a different variant.
                if (!last.isEmpty() && !ItemStack.isSameItemSameComponents(last, preview.result())) break;
                last = preview.result();
                execute(player, preview);
                created += last.getCount();
                stock = stock(player);
                preview = ask.manual() ? plan(player, stock, ask.cells(), null) : automatic(player, stock, recipe);
            }
        }
        String message = created != 0
                ? "Crafted " + created + " × " + last.getHoverName().getString()
                : ask.action() == BROWSE ? "" : "The ingredients or recipe changed.";
        String search = ask.search().toLowerCase(Locale.ROOT).strip();
        var entries = new ArrayList<Entry>();
        if (ask.manual()) {
            stock.byKey().forEach((source, stack) -> {
                if (matches(stack, search)) entries.add(new Entry(source, stack));
            });
        } else {
            for (var candidate : manager.getRecipes()) {
                var possible = automatic(player, stock, candidate);
                if (possible != null && matches(possible.result(), search))
                    entries.add(new Entry(id(candidate), possible.result()));
            }
        }
        entries.sort(
                Comparator.comparing(e -> e.stack().getHoverName().getString().toLowerCase(Locale.ROOT)));
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.clamp(ask.page(), 0, pages - 1);
        List<ItemStack> grid = preview != null
                ? preview.grid()
                : ask.manual()
                        ? ask.cells().stream()
                                .map(cell -> stockForCell(player, cell))
                                .toList()
                        : List.of();
        return new Show(
                ask.chest(),
                entries.subList(page * PAGE_SIZE, Math.min(entries.size(), (page + 1) * PAGE_SIZE)),
                grid,
                preview == null ? ItemStack.EMPTY : preview.result(),
                preview == null ? "" : id(preview.recipe()),
                page,
                pages,
                created,
                message);
    }

    private static ItemStack stockForCell(ServerPlayer player, String key) {
        if (key.isEmpty()) return ItemStack.EMPTY;
        if (!key.startsWith("@")) return Collection.stored(player, key).copyWithCount(1);
        try {
            int slot = Integer.parseInt(key.substring(1));
            if (slot >= 0 && slot < Inventory.INVENTORY_SIZE)
                return player.getInventory().getItem(slot).copyWithCount(1);
        } catch (NumberFormatException ignored) {
        }
        return ItemStack.EMPTY;
    }

    private static boolean matches(ItemStack stack, String query) {
        return query.isEmpty()
                || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)
                || Collection.idOf(stack.getItem()).contains(query);
    }

    private static String id(RecipeHolder<?> holder) {
        return holder.id().identifier().toString();
    }
}
