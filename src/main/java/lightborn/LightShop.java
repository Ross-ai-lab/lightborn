package lightborn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Daily wares grow with the world's age, priced in Sparks, the Light's own currency (vanilla emeralds buy nothing here).
 * Every displayed bundle can be bought repeatedly for its stated price.
 */
public final class LightShop {
    private LightShop() {}

    /** One ware and its price. Nothing is level-gated (owner, 2026-11-16: there are no levels). */
    public record Offer(ItemStack stack, int price) {
        static final StreamCodec<RegistryFriendlyByteBuf, Offer> CODEC = StreamCodec.composite(
                ItemStack.STREAM_CODEC, Offer::stack, ByteBufCodecs.VAR_INT, Offer::price, Offer::new);
    }

    public record Ask(BlockPos chest) implements CustomPacketPayload {
        public static final Type<Ask> TYPE = new Type<>(ModItems.id("shop_ask"));
        static final StreamCodec<RegistryFriendlyByteBuf, Ask> CODEC =
                BlockPos.STREAM_CODEC.map(Ask::new, Ask::chest).cast();

        @Override
        public Type<Ask> type() {
            return TYPE;
        }
    }

    public record Buy(BlockPos chest, long day, int index) implements CustomPacketPayload {
        public static final Type<Buy> TYPE = new Type<>(ModItems.id("shop_buy"));
        static final StreamCodec<RegistryFriendlyByteBuf, Buy> CODEC = StreamCodec.composite(
                        BlockPos.STREAM_CODEC,
                        Buy::chest,
                        ByteBufCodecs.VAR_LONG,
                        Buy::day,
                        ByteBufCodecs.VAR_INT,
                        Buy::index,
                        Buy::new)
                .cast();

        @Override
        public Type<Buy> type() {
            return TYPE;
        }
    }

    /** {@code wardTier}: how many ward-size steps the player has bought (the radii and prices are in Balance). */
    public record Show(
            BlockPos chest, long day, int sparks, int untilDawn, List<Offer> offers, String message, int wardTier)
            implements CustomPacketPayload {
        public static final Type<Show> TYPE = new Type<>(ModItems.id("shop_show"));
        static final StreamCodec<RegistryFriendlyByteBuf, Show> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                Show::chest,
                ByteBufCodecs.VAR_LONG,
                Show::day,
                ByteBufCodecs.VAR_INT,
                Show::sparks,
                ByteBufCodecs.VAR_INT,
                Show::untilDawn,
                Offer.CODEC.apply(ByteBufCodecs.list()),
                Show::offers,
                ByteBufCodecs.STRING_UTF8,
                Show::message,
                ByteBufCodecs.VAR_INT,
                Show::wardTier,
                Show::new);

        @Override
        public Type<Show> type() {
            return TYPE;
        }
    }

    /** The Buy index that widens the ward instead of buying a ware. */
    public static final int GROW_WARD = -1;

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Ask.TYPE, Ask.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Buy.TYPE, Buy.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Show.TYPE, Show.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Ask.TYPE, (p, ctx) -> {
            if (near(ctx.player(), p.chest())) ctx.responseSender().sendPacket(snapshot(ctx.player(), p.chest(), ""));
        });
        ServerPlayNetworking.registerGlobalReceiver(Buy.TYPE, (p, ctx) -> {
            if (!near(ctx.player(), p.chest()) || !ChestTabs.shop(ctx.player())) return;
            String message = p.index() == GROW_WARD
                    ? LightWards.grow(ctx.player(), p.chest())
                    : purchase(ctx.player(), p.chest(), p.day(), p.index());
            ctx.responseSender().sendPacket(snapshot(ctx.player(), p.chest(), message));
        });
    }

    public static long day(ServerLevel level) {
        return Math.floorDiv(level.getServer().overworld().getOverworldClockTime(), 24000);
    }

    public static int week(long day) {
        return (int) Math.clamp(Math.floorDiv(Math.max(0, day), 7) + 1, 1, Integer.MAX_VALUE);
    }

    public static int levelCap(long day) {
        return switch (Math.min(week(day), 7)) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 12;
            case 4 -> 25;
            case 5 -> 40;
            case 6 -> 55;
            default -> 70;
        };
    }

    static boolean near(ServerPlayer p, BlockPos chest) {
        return p.isAlive()
                && !p.isSpectator()
                && p.level().isLoaded(chest)
                && p.level().getBlockState(chest).is(ModItems.LIGHT_CHEST)
                && p.distanceToSqr(chest.getX() + .5, chest.getY() + .5, chest.getZ() + .5) <= 64;
    }

    public static List<Offer> wares(ServerLevel level, long day) {
        var random = RandomSource.create(level.getServer().overworld().getSeed() ^ (day * 0x9E3779B97F4A7C15L));
        var all = new ArrayList<Offer>();
        int week = week(day), cap = levelCap(day);
        int floor = week == 1 ? 1 : week == 2 ? 5 : week == 3 ? 10 : week == 4 ? 15 : 10;
        List<Spell> spells = Arrays.stream(Spell.values())
                .filter(s -> s.dropWeight > 0 && s.shopTier <= cap && s.shopTier >= floor)
                .toList();
        Spell tome = spells.get(random.nextInt(spells.size()));
        all.add(new Offer(new ItemStack(ModItems.TOMES.get(tome)), (tome.shopTier <= 5 ? 12 : 24) + tome.shopTier * 2));
        int staffCap = Math.min(cap, levelCap(5 * 7 - 7));
        all.add(new Offer(staff(level, day, weekTier(week)), week == 1 ? 12 : 24 + staffCap * 2));
        // Six wares (owner, 2026-11-16: the armour piece and the second curio are gone; their row holds the ward).
        if (week == 1) all.add(new Offer(new ItemStack(Items.AMETHYST_SHARD, 8), 4));
        else {
            int amount = Math.min(4, Math.max(1, week - 2));
            all.add(new Offer(new ItemStack(ModItems.DARK_SHARD, amount), 24 * amount));
        }
        String[] curios = {
            "minecraft:echo_shard",
            "minecraft:nautilus_shell",
            "minecraft:phantom_membrane",
            "minecraft:dragon_breath",
            "minecraft:glow_ink_sac",
            "minecraft:prismarine_crystals",
            "minecraft:heart_of_the_sea"
        };
        int choice = random.nextInt(curios.length);
        var curio = Collection.item(curios[choice]);
        if (curio == Items.AIR) curio = Items.ECHO_SHARD;
        all.add(new Offer(new ItemStack(curio, choice >= 6 ? 1 : 3), choice == 6 ? 32 : 12));
        MapTarget map = MAPS.get(random.nextInt(MAPS.size()));
        all.add(new Offer(map.offer(), map.price()));
        // Raw, never cooked: the player cooks it (owner, 2026-11-15). Golden foods have no raw form.
        String[] foods = {
            "minecraft:golden_carrot",
            "minecraft:golden_apple",
            "minecraft:chorus_fruit",
            "naturalist:anglerfish",
            "naturalist:crab_meat",
            "naturalist:lizard_tail",
            "naturalist:mammoth_meat",
            "naturalist:venison",
            "naturalist:bushmeat",
            "naturalist:clam_meat"
        };
        String food = foods[random.nextInt(foods.length)];
        var item = Collection.item(food);
        if (item == Items.AIR) {
            item = Items.RABBIT;
            food = "minecraft:rabbit";
        }
        boolean golden = food.endsWith("golden_apple");
        all.add(new Offer(new ItemStack(item, golden ? 1 : 4), golden ? 12 : 6));
        return List.copyOf(all);
    }

    /** A map the shop sells: which notable structure, how its marker looks, and what it costs. */
    record MapTarget(String name, TagKey<Structure> tag, Holder<MapDecorationType> marker, int price, int level) {
        ItemStack offer() {
            var stack = new ItemStack(Items.MAP);
            stack.set(
                    DataComponents.CUSTOM_NAME,
                    Component.literal("Map to " + name).withStyle(ChatFormatting.GOLD));
            stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, d -> {
                var tag = d.copyTag();
                tag.putString(MAP_KEY, name);
                return CustomData.of(tag);
            });
            return stack;
        }
    }

    static final String MAP_KEY = "lightborn_shop_map";

    static final List<MapTarget> MAPS = List.of(
            new MapTarget(
                    "a Woodland Mansion",
                    StructureTags.ON_WOODLAND_MANSION_MAPS,
                    MapDecorationTypes.WOODLAND_MANSION,
                    30,
                    10),
            new MapTarget(
                    "an Ocean Monument",
                    StructureTags.ON_OCEAN_MONUMENT_MAPS,
                    MapDecorationTypes.OCEAN_MONUMENT,
                    24,
                    10),
            new MapTarget(
                    "the Trial Chambers",
                    StructureTags.ON_BURIED_TRIAL_CHAMBERS_MAPS,
                    MapDecorationTypes.TRIAL_CHAMBERS,
                    20,
                    5),
            new MapTarget(
                    "an Ancient City", StructureTags.ON_ANCIENT_CITY_MAPS, MapDecorationTypes.ANCIENT_CITY, 30, 20),
            new MapTarget(
                    "a Jungle Temple", StructureTags.ON_JUNGLE_PYRAMID_MAPS, MapDecorationTypes.JUNGLE_TEMPLE, 16, 1),
            new MapTarget("a Witch Hut", StructureTags.ON_SWAMP_HUT_MAPS, MapDecorationTypes.SWAMP_HUT, 12, 1),
            new MapTarget(
                    "a Desert Pyramid", StructureTags.ON_DESERT_PYRAMID_MAPS, MapDecorationTypes.DESERT_PYRAMID, 16, 1),
            new MapTarget("Buried Treasure", StructureTags.ON_TREASURE_MAPS, MapDecorationTypes.RED_X, 16, 1));

    static MapTarget mapOf(ItemStack offer) {
        String name = offer.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getStringOr(MAP_KEY, "");
        for (MapTarget m : MAPS) if (m.name().equals(name)) return m;
        return null;
    }

    /**
     * Draws the bought map: the nearest such place not yet visited by anyone, found the way a cartographer's
     * explorer map finds it, from the chest. Null when none lies within reach.
     */
    static ItemStack drawMap(ServerPlayer p, BlockPos from, MapTarget m) {
        return drawMap(p, from, m, 100);
    }

    /** A map to the nearest unfound {@code m} within {@code chunks} chunks; a smaller search costs less time. */
    static ItemStack drawMap(ServerPlayer p, BlockPos from, MapTarget m, int chunks) {
        ServerLevel level = p.level().getServer().overworld();
        BlockPos at = level.findNearestMapStructure(m.tag(), from, chunks, true);
        if (at == null) return null;
        ItemStack map = MapItem.create(level, at.getX(), at.getZ(), (byte) 2, true, true);
        MapItem.renderBiomePreviewMap(level, map);
        MapItemSavedData.addTargetDecoration(map, at, "+", m.marker());
        map.set(
                DataComponents.CUSTOM_NAME,
                Component.literal("Map to " + m.name()).withStyle(ChatFormatting.GOLD));
        return map;
    }

    /** Sparks carried plus Sparks kept in the Chest of Light's storage: the shop sees both. */
    public static int sparks(ServerPlayer p) {
        return carriedSparks(p) + storedSparks(p);
    }

    static int carriedSparks(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++)
            if (p.getInventory().getItem(i).is(ModItems.SPARK))
                n += p.getInventory().getItem(i).getCount();
        return n;
    }

    static int storedSparks(ServerPlayer p) {
        return Collection.stored(p, SPARK_ID).getCount();
    }

    static final String SPARK_ID = "lightborn:spark";

    /** Pays {@code cost} Sparks, from the chest's store first, then the inventory. False (and nothing taken) if short. */
    static boolean pay(ServerPlayer p, int cost) {
        if (cost <= 0) return true;
        if (sparks(p) < cost) return false;
        cost -= Collection.spendStored(p, SPARK_ID, cost);
        for (int i = 0; i < Inventory.INVENTORY_SIZE && cost > 0; i++) {
            var s = p.getInventory().getItem(i);
            if (!s.is(ModItems.SPARK)) continue;
            int used = Math.min(cost, s.getCount());
            s.shrink(used);
            cost -= used;
        }
        p.getInventory().setChanged();
        p.containerMenu.broadcastChanges();
        return true;
    }

    /** Netherite is the top tier (owner, 2026-11-16): week 4 sells a Rare one, week 5 onward a Legendary one. */
    static ModItems.Tier weekTier(int week) {
        return ModItems.Tier.values()[Math.min(Math.max(0, week - 2), 2)];
    }

    /**
     * The day's staff in a given material. It rolls from its own seed, so the same day gives the same staff whatever
     * material a player's ore caps it at, and the other wares never shift with it.
     */
    static ItemStack staff(ServerLevel level, long day, ModItems.Tier tier) {
        int week = week(day);
        if (week == 1)
            return new ItemStack(ModItems.GEAR.get(ModItems.Tier.IRON).get(ModItems.Kind.STAFF));
        var random = RandomSource.create(level.getServer().overworld().getSeed() ^ (day * 0x5DEECE66DL) ^ 0x57AFFL);
        return LootRoller.roll(
                random,
                week < 4 ? LootRoller.Rarity.UNCOMMON : week < 5 ? LootRoller.Rarity.RARE : LootRoller.Rarity.LEGENDARY,
                tier,
                ModItems.Kind.STAFF);
    }

    static List<Offer> waresFor(ServerPlayer p, long day) {
        var list = new ArrayList<>(wares(p.level(), day));
        // The staff's material is capped by the ore this player has mined (owner, 2026-11-17), the same rule as
        // dropped gear; iron is the floor, since the shop sells an iron staff from the first day.
        var mined = OreTiers.best(p);
        var cap = mined == null ? ModItems.Tier.IRON : mined;
        var weekly = weekTier(week(day));
        if (weekly.ordinal() > cap.ordinal())
            list.set(1, new Offer(staff(p.level(), day, cap), list.get(1).price()));
        if (DragonStory.ending(p.level()) == 2
                && DragonStory.hasFlag(p, 16)
                && !LightGear.has(p, DragonStory.LIGHT_KEY))
            list.set(3, new Offer(new ItemStack(DragonStory.LIGHT_KEY), 0));
        else if (DragonStory.ending(p.level()) == 3 && LightGear.lost(p) != 0) {
            int i = Integer.numberOfTrailingZeros(LightGear.lost(p));
            list.set(3, new Offer(LightGear.gift(i, LightGear.lostStep(p, i)), 0));
        }
        return List.copyOf(list);
    }

    static Show snapshot(ServerPlayer p, BlockPos chest, String message) {
        long day = day(p.level());
        return new Show(
                chest,
                day,
                sparks(p),
                (int) (24000 - Math.floorMod(p.level().getServer().overworld().getOverworldClockTime(), 24000)),
                waresFor(p, day),
                message,
                LightWards.tier(p));
    }
    /** Simulate payment first (the chest's store pays before the inventory), so a Spark stack consumed by the price can free a slot for the purchase. */
    static boolean fitsAfterPayment(ServerPlayer p, ItemStack item, int price) {
        int cost = Math.max(0, price - storedSparks(p)), left = item.getCount();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = p.getInventory().getItem(i).copy();
            if (s.is(ModItems.SPARK)) {
                int used = Math.min(cost, s.getCount());
                s.shrink(used);
                cost -= used;
            }
            if (s.isEmpty()) left -= item.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(s, item))
                left -= Math.max(0, s.getMaxStackSize() - s.getCount());
        }
        return left <= 0;
    }

    public static String purchase(ServerPlayer p, BlockPos chest, long requestedDay, int index) {
        if (!near(p, chest)) return "Return to the Chest of Light.";
        long day = day(p.level());
        if (requestedDay != day) return "A new day has brought new wares.";
        var offers = waresFor(p, day);
        if (index < 0 || index >= offers.size()) return "Choose a current offer.";
        Offer o = offers.get(index);
        if (sparks(p) < o.price) return "You need " + o.price + " sparks.";
        if (!fitsAfterPayment(p, o.stack, o.price)) return "Make room in your inventory.";
        MapTarget map = mapOf(o.stack);
        if (map != null) {
            if (p.level() != p.level().getServer().overworld()) return "Maps are drawn in the Overworld.";
            ItemStack drawn = drawMap(p, chest, map);
            if (drawn == null)
                return "No unvisited " + map.name().replaceFirst("^(a|an|the) ", "") + " lies near enough.";
            pay(p, o.price);
            p.getInventory().add(drawn);
            p.getInventory().setChanged();
            p.containerMenu.broadcastChanges();
            return "Bought " + drawn.getHoverName().getString() + ".";
        }
        pay(p, o.price);
        if (DragonStory.ending(p.level()) == 3 && LightGear.ITEMS.contains(o.stack.getItem())) {
            int i = LightGear.ITEMS.indexOf(o.stack.getItem());
            LightGear.clearLost(p, i);
            LightGear.give(p, o.stack.copy());
        } else p.getInventory().add(o.stack.copy());
        p.getInventory().setChanged();
        p.containerMenu.broadcastChanges();
        return "Bought " + o.stack.getCount() + " × " + o.stack.getHoverName().getString() + ".";
    }
}
