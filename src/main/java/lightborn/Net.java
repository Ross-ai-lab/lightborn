package lightborn;

import io.netty.buffer.ByteBuf;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;

/**
 * The few packets the client and server swap: the Chest of Light and its book. Everything else rides on
 * synced attachments; casting is a vanilla right-click (StaffItem.use) and spell cooldowns are vanilla item cooldowns.
 */
public final class Net {
    private Net() {}

    /** Client -> server: put one point into this node. */
    public record GiveOne(BlockPos chest) implements CustomPacketPayload {
        public static final Type<GiveOne> TYPE = new Type<>(ModItems.id("chest_give"));
        public static final StreamCodec<ByteBuf, GiveOne> CODEC =
                BlockPos.STREAM_CODEC.map(GiveOne::new, GiveOne::chest);

        @Override
        public Type<GiveOne> type() {
            return TYPE;
        }
    }

    /** Client -> server, from the chest's book: take one or all of an item out, or put in every one carried. */
    public record ChestMove(BlockPos chest, String item, int action) implements CustomPacketPayload {
        public static final int TAKE_ONE = 0,
                TAKE_ALL = 1,
                PUT_IN = 2,
                GIVE_NEW = 3,
                GIVE_NON_ESSENTIAL = 4,
                DEPOSIT_SPARKS = 5;
        public static final Type<ChestMove> TYPE = new Type<>(ModItems.id("chest_move"));
        public static final StreamCodec<ByteBuf, ChestMove> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                ChestMove::chest,
                ByteBufCodecs.STRING_UTF8,
                ChestMove::item,
                ByteBufCodecs.VAR_INT,
                ChestMove::action,
                ChestMove::new);

        @Override
        public Type<ChestMove> type() {
            return TYPE;
        }
    }

    public record LedgerAsk(BlockPos chest) implements CustomPacketPayload {
        public static final Type<LedgerAsk> TYPE = new Type<>(ModItems.id("ledger_ask"));
        public static final StreamCodec<ByteBuf, LedgerAsk> CODEC =
                BlockPos.STREAM_CODEC.map(LedgerAsk::new, LedgerAsk::chest);

        @Override
        public Type<LedgerAsk> type() {
            return TYPE;
        }
    }

    public record LedgerShow(BlockPos chest, CollectionLedger.Snapshot ledger) implements CustomPacketPayload {
        public static final Type<LedgerShow> TYPE = new Type<>(ModItems.id("ledger_show"));
        public static final StreamCodec<ByteBuf, LedgerShow> CODEC = StreamCodec.of(
                (buf, packet) -> {
                    BlockPos.STREAM_CODEC.encode(buf, packet.chest);
                    var v = packet.ledger;
                    ByteBufCodecs.VAR_INT.encode(buf, v.bestiary());
                    ByteBufCodecs.VAR_INT.encode(buf, v.bestiaryTotal());
                    ByteBufCodecs.VAR_INT.encode(buf, v.journal());
                    ByteBufCodecs.VAR_INT.encode(buf, v.journalTotal());
                    ByteBufCodecs.VAR_INT.encode(buf, v.bosses());
                    ByteBufCodecs.VAR_INT.encode(buf, v.bossTotal());
                    ByteBufCodecs.VAR_INT.encode(buf, v.regions());
                    ByteBufCodecs.VAR_INT.encode(buf, v.regionTotal());
                    ByteBufCodecs.VAR_LONG.encode(buf, v.hunts());
                    ByteBufCodecs.VAR_INT.encode(buf, v.wardRadius());
                    ByteBufCodecs.BOOL.encode(buf, v.luminous());
                },
                buf -> new LedgerShow(
                        BlockPos.STREAM_CODEC.decode(buf),
                        new CollectionLedger.Snapshot(
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.VAR_LONG.decode(buf),
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.BOOL.decode(buf))));

        @Override
        public Type<LedgerShow> type() {
            return TYPE;
        }
    }

    public record Gift(ItemStack stack, boolean first) {
        static final StreamCodec<RegistryFriendlyByteBuf, Gift> CODEC =
                StreamCodec.composite(ModData.CHEST_STACK, Gift::stack, ByteBufCodecs.BOOL, Gift::first, Gift::new);
    }

    /** Server -> client: everything stored by one bulk donation, with first gifts marked. */
    public record Unlocked(BlockPos chest, List<Gift> items, int sparks) implements CustomPacketPayload {
        public static final Type<Unlocked> TYPE = new Type<>(ModItems.id("unlocked"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Unlocked> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                Unlocked::chest,
                Gift.CODEC.apply(ByteBufCodecs.list()),
                Unlocked::items,
                ByteBufCodecs.VAR_INT,
                Unlocked::sparks,
                Unlocked::new);

        @Override
        public Type<Unlocked> type() {
            return TYPE;
        }
    }

    /** Client -> server: how is this item made? */
    public record RecipeAsk(String item) implements CustomPacketPayload {
        public static final Type<RecipeAsk> TYPE = new Type<>(ModItems.id("recipe_ask"));
        public static final StreamCodec<ByteBuf, RecipeAsk> CODEC =
                ByteBufCodecs.STRING_UTF8.map(RecipeAsk::new, RecipeAsk::item);

        @Override
        public Type<RecipeAsk> type() {
            return TYPE;
        }
    }

    /** Server -> client: the ways to make it (clients hold no full recipe list, so the server answers). */
    /** How an item is made; with no recipe, {@code found} says where it comes from instead ({@link Sources}). */
    public record RecipeShow(String item, List<RecipeDisplay> recipes, Component found) implements CustomPacketPayload {
        public static final Type<RecipeShow> TYPE = new Type<>(ModItems.id("recipe_show"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RecipeShow> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
                RecipeShow::item,
                RecipeDisplay.STREAM_CODEC.apply(ByteBufCodecs.list()),
                RecipeShow::recipes,
                ComponentSerialization.STREAM_CODEC,
                RecipeShow::found,
                RecipeShow::new);

        @Override
        public Type<RecipeShow> type() {
            return TYPE;
        }
    }

    /** A chest packet counts only next to a real Chest of Light (the book is a plain screen, not a container menu). */
    private static boolean atChest(ServerPlayer player, BlockPos pos) {
        return player.level().isLoaded(pos)
                && player.level().getBlockState(pos).is(ModItems.LIGHT_CHEST)
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
    }

    static void init() {
        LightShop.init();
        ChestStations.init();
        ChestCrafting.init();
        DragonTalk.init();
        TomeCycle.init();
        Souls.init();
        SpellUpgrades.init();
        HomeMove.init();
        PayloadTypeRegistry.serverboundPlay().register(GiveOne.TYPE, GiveOne.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ChestMove.TYPE, ChestMove.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LedgerAsk.TYPE, LedgerAsk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LedgerShow.TYPE, LedgerShow.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RecipeAsk.TYPE, RecipeAsk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RecipeShow.TYPE, RecipeShow.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Unlocked.TYPE, Unlocked.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(GiveOne.TYPE, (p, ctx) -> {
            if (atChest(ctx.player(), p.chest()))
                Collection.offer(ctx.player(), ctx.player().getMainHandItem(), p.chest());
        });
        ServerPlayNetworking.registerGlobalReceiver(ChestMove.TYPE, (p, ctx) -> {
            if (!atChest(ctx.player(), p.chest())) return;
            if (p.action() == ChestMove.PUT_IN) Collection.deposit(ctx.player(), p.item(), p.chest());
            else if (p.action() == ChestMove.GIVE_NEW) Collection.giveAllNew(ctx.player(), p.chest());
            else if (p.action() == ChestMove.GIVE_NON_ESSENTIAL) Collection.giveNonEssential(ctx.player(), p.chest());
            else if (p.action() == ChestMove.DEPOSIT_SPARKS) {
                int moved = Collection.deposit(ctx.player(), Collection.idOf(ModItems.SPARK), p.chest());
                if (moved > 0) ctx.player().sendOverlayMessage(Component.literal("Deposited " + moved + " Sparks"));
            } else if (p.action() == ChestMove.TAKE_ONE || p.action() == ChestMove.TAKE_ALL) {
                Collection.withdraw(ctx.player(), p.item(), p.action() == ChestMove.TAKE_ALL, p.chest());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(LedgerAsk.TYPE, (p, ctx) -> {
            if (!atChest(ctx.player(), p.chest())) return;
            Collection.ensure(ctx.player());
            LightWards.claim(ctx.player().level(), p.chest(), ctx.player());
            ctx.responseSender().sendPacket(new LedgerShow(p.chest(), CollectionLedger.snapshot(ctx.player())));
        });
        ServerPlayNetworking.registerGlobalReceiver(RecipeAsk.TYPE, (p, ctx) -> {
            var level = ctx.player().level();
            var recipes = Collection.recipesFor(level, p.item());
            ctx.responseSender()
                    .sendPacket(new RecipeShow(
                            p.item(), recipes, recipes.isEmpty() ? Sources.hint(level, p.item()) : Component.empty()));
        });
    }
}
