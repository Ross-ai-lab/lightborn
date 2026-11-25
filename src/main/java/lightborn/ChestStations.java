package lightborn;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;

/** Each Chest of Light station is bought once per player. The furnace keeps its own vanilla contents and burn time. */
public final class ChestStations {
    private ChestStations() {}

    public static final int PRICE = 20, OFFER_START = 10;

    public enum Station {
        CRAFTING("Crafting", Items.CRAFTING_TABLE, "Craft using your chest storage and inventory."),
        FURNACE("Furnace", Items.FURNACE, "Smelt or cook with ordinary fuel."),
        TRAVEL("Travel", Items.COMPASS, "Fly by Dragon or move your home."),
        ANVIL("Anvil", Items.ANVIL, "Combine, rename and repair gear.");
        public final String title, description;
        public final Item icon;

        Station(String title, Item icon, String description) {
            this.title = title;
            this.icon = icon;
            this.description = description;
        }
    }

    private static final AttachmentType<Integer> UNLOCKS = AttachmentRegistry.create(
            ModItems.id("chest_stations"),
            b -> b.persistent(Codec.INT)
                    .copyOnDeath()
                    .syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly()));
    private static final AttachmentType<CompoundTag> FURNACE = AttachmentRegistry.create(
            ModItems.id("chest_furnace"), b -> b.persistent(CompoundTag.CODEC).copyOnDeath());

    private static final Map<ServerPlayer, Furnace> FURNACES = new HashMap<>();

    public static boolean owned(Player p, Station station) {
        return p.isCreative() || (p.getAttachedOrElse(UNLOCKS, 0) & (1 << station.ordinal())) != 0;
    }

    public static String purchase(ServerPlayer p, BlockPos chest, int index) {
        if (!LightShop.near(p, chest) || !ChestTabs.shop(p)) return "Return to the Chest of Light shop.";
        if (index < 0 || index >= Station.values().length) return "Choose a station.";
        Station station = Station.values()[index];
        if (owned(p, station)) return station.title + " is already available.";
        if (station == Station.TRAVEL && !ChestTabs.travelReady(p))
            return "Travel opens after seven Minecraft days of play.";
        if (!LightShop.pay(p, PRICE)) return "You need " + PRICE + " Sparks.";
        p.setAttached(UNLOCKS, p.getAttachedOrElse(UNLOCKS, 0) | (1 << index));
        return "Opened " + station.title + ".";
    }

    public record Open(BlockPos chest, int station) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ModItems.id("chest_station_open"));
        static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Open::chest, ByteBufCodecs.VAR_INT, Open::station, Open::new);

        @Override
        public Type<Open> type() {
            return TYPE;
        }
    }

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Open.TYPE, Open.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Open.TYPE, (open, ctx) -> {
            int id = open.station;
            if (id >= 0 && id < Station.values().length) open(ctx.player(), open.chest, Station.values()[id]);
        });
        ChestSalvage.init();
    }

    private static void open(ServerPlayer p, BlockPos chest, Station station) {
        if (!LightShop.near(p, chest) || !owned(p, station)) return;
        if (station == Station.ANVIL) {
            var level = p.level();
            var access = ContainerLevelAccess.create(level, chest);
            p.openMenu(new SimpleMenuProvider(
                    (id, inv, player) -> new AnvilMenu(id, inv, access) {
                        @Override
                        public boolean stillValid(Player viewer) {
                            return viewer == p && p.level() == level && LightShop.near(p, chest);
                        }
                    },
                    Component.literal("Anvil of Light")));
        } else if (station == Station.FURNACE) {
            var furnace = FURNACES.computeIfAbsent(p, Furnace::new);
            p.openMenu(new SimpleMenuProvider(
                    (id, inv, player) -> furnace.open(id, inv, chest), Component.literal("Furnace of Light")));
        }
    }

    static void tick(MinecraftServer server) {
        FURNACES.keySet().retainAll(server.getPlayerList().getPlayers());
        for (var player : server.getPlayerList().getPlayers()) {
            if (!player.hasAttached(FURNACE) && !FURNACES.containsKey(player)) continue;
            FURNACES.computeIfAbsent(player, Furnace::new).tick();
        }
    }

    /** The vanilla recipe, fuel and slot logic runs without placing a furnace block in the world. */
    public static final class Furnace extends FurnaceBlockEntity {
        private final ServerPlayer owner;

        Furnace(ServerPlayer owner) {
            super(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
            this.owner = owner;
            setLevel(owner.level());
            CompoundTag saved = owner.getAttached(FURNACE);
            if (saved != null)
                loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, owner.registryAccess(), saved));
        }

        AbstractContainerMenu open(int id, Inventory inv, BlockPos chest) {
            var level = owner.level();
            return new net.minecraft.world.inventory.FurnaceMenu(id, inv, this, dataAccess) {
                @Override
                public boolean stillValid(Player p) {
                    return p == owner && owner.level() == level && LightShop.near(owner, chest);
                }
            };
        }

        void tick() {
            if (getLevel() != owner.level()) setLevel(owner.level());
            int fuel = dataAccess.get(0), cooking = dataAccess.get(2);
            AbstractFurnaceBlockEntity.serverTick(owner.level(), getBlockPos(), getBlockState(), this);
            if (fuel != dataAccess.get(0) || cooking != dataAccess.get(2)) setChanged();
        }

        @Override
        public void setChanged() {
            owner.setAttached(FURNACE, saveWithoutMetadata(owner.registryAccess()));
        }
    }
}
