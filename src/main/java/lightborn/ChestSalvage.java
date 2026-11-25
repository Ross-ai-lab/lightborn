package lightborn;

import java.util.Arrays;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** A deliberate exchange of one held piece of loot for a few Sparks. */
public final class ChestSalvage {
    private ChestSalvage() {}

    public record Give(BlockPos chest, ItemStack expected) implements CustomPacketPayload {
        public static final Type<Give> TYPE = new Type<>(ModItems.id("chest_salvage"));
        static final StreamCodec<RegistryFriendlyByteBuf, Give> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Give::chest, ItemStack.STREAM_CODEC, Give::expected, Give::new);

        @Override
        public Type<Give> type() {
            return TYPE;
        }
    }

    public static int value(ItemStack item) {
        if (item.isEmpty() || LightGear.ITEMS.contains(item.getItem()) || LightGearTree.kind(item) < 0) return 0;
        var rarity = LootRoller.rarity(item);
        int upgrades = Arrays.stream(LightGearTree.owned(item))
                .map(mask -> mask == 0 ? 0 : 1)
                .sum();
        boolean rolled = item.getOrDefault(
                        net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.EMPTY)
                .copyTag()
                .contains("lightborn_rarity");
        if (!rolled && upgrades == 0) return 0;
        int base = switch (rarity) {
            case PLAIN -> 1;
            case UNCOMMON -> 1;
            case RARE -> 2;
            case LEGENDARY -> 3;
            case MYTHIC -> 5;
        };
        var tier = ModItems.tierOf(item.getItem());
        String path = BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
        int material =
                tier == null ? path.startsWith("netherite_") ? 2 : path.startsWith("diamond_") ? 1 : 0 : tier.ordinal();
        return base + material + upgrades;
    }

    static int exchange(ServerPlayer p, BlockPos chest, ItemStack expected) {
        if (!LightShop.near(p, chest) || p.isSpectator()) return 0;
        var held = p.getMainHandItem();
        if (!ItemStack.matches(held, expected)) return 0;
        int sparks = value(held);
        if (sparks <= 0) return 0;
        held.shrink(1);
        var payment = new ItemStack(ModItems.SPARK, sparks);
        if (!p.getInventory().add(payment) && !payment.isEmpty()) p.spawnAtLocation(p.level(), payment);
        p.getInventory().setChanged();
        p.containerMenu.broadcastChanges();
        return sparks;
    }

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Give.TYPE, Give.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Give.TYPE, (give, ctx) -> {
            int sparks = exchange(ctx.player(), give.chest, give.expected);
            ctx.player()
                    .sendOverlayMessage(Component.literal(
                            sparks > 0
                                    ? "Gear given to the Light · +" + sparks + " Sparks"
                                    : "Hold the eligible gear you want to give to the Light."));
        });
    }
}
