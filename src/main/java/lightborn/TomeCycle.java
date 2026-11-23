package lightborn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Sneak + scroll with a staff in hand swaps the off-hand tome for the next (or previous) tome in the inventory. The
 * order is the inventory's slot order, with the held tome standing where it last left; the client draws a short-lived
 * strip of every tome, the held one lit.
 */
public final class TomeCycle {
    private TomeCycle() {}

    /** Client → server: one scroll notch, +1 next or -1 previous. */
    public record Cycle(int direction) implements CustomPacketPayload {
        public static final Type<Cycle> TYPE = new Type<>(ModItems.id("tome_cycle"));
        static final StreamCodec<RegistryFriendlyByteBuf, Cycle> CODEC =
                ByteBufCodecs.VAR_INT.map(Cycle::new, Cycle::direction).cast();

        @Override
        public Type<Cycle> type() {
            return TYPE;
        }
    }

    /** Server → client: every tome in order and which one is now in the off hand. */
    public record Strip(List<ItemStack> tomes, int held) implements CustomPacketPayload {
        public static final Type<Strip> TYPE = new Type<>(ModItems.id("tome_strip"));
        static final StreamCodec<RegistryFriendlyByteBuf, Strip> CODEC = StreamCodec.composite(
                ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                Strip::tomes,
                ByteBufCodecs.VAR_INT,
                Strip::held,
                Strip::new);

        @Override
        public Type<Strip> type() {
            return TYPE;
        }
    }

    /** Where the held tome stands in the order: beside this slot, after it (true) or before it (false). */
    private record Place(int slot, boolean after) {}

    private static final Map<UUID, Place> PLACES = new HashMap<>();

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Cycle.TYPE, Cycle.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Strip.TYPE, Strip.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Cycle.TYPE, (p, ctx) -> {
            var strip = cycle(ctx.player(), Integer.signum(p.direction()));
            if (strip != null) ctx.responseSender().sendPacket(strip);
        });
    }

    static void forget(ServerPlayer p) {
        PLACES.remove(p.getUUID());
    }

    public static boolean isTome(ItemStack s) {
        return s.getItem() instanceof TomeItem;
    }

    /** Swaps the off hand with the neighbouring tome. Null when there is nothing to turn to. */
    static Strip cycle(ServerPlayer p, int direction) {
        if (direction == 0 || !(p.getMainHandItem().getItem() instanceof StaffItem)) return null;
        Inventory inv = p.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (isTome(inv.getItem(i))) slots.add(i);
        if (slots.isEmpty()) return null;
        ItemStack held = p.getOffhandItem();
        if (!held.isEmpty() && !isTome(held)) return null; // never trade away a shield or totem for a tome
        // The ring: the tome slots in order, with the off hand standing at its remembered place.
        Place place = PLACES.get(p.getUUID());
        int at = 0;
        if (place != null && slots.contains(place.slot())) at = slots.indexOf(place.slot()) + (place.after() ? 1 : 0);
        List<Integer> ring = new ArrayList<>(slots);
        ring.add(at, -1);
        int target = ring.get(Math.floorMod(at + direction, ring.size()));
        if (target == -1) return null; // only the off hand itself: nothing else to take up
        ItemStack next = inv.getItem(target);
        inv.setItem(target, held.copy());
        p.setItemInHand(InteractionHand.OFF_HAND, next.copy());
        // The old tome now sits in the target slot: going forward it is behind us, going back it is ahead.
        PLACES.put(p.getUUID(), new Place(target, direction > 0));
        inv.setChanged();
        p.containerMenu.broadcastChanges();
        return strip(p);
    }

    /** The tomes as the strip shows them: the ring with the off hand in its place. */
    static Strip strip(ServerPlayer p) {
        Inventory inv = p.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (isTome(inv.getItem(i))) slots.add(i);
        Place place = PLACES.get(p.getUUID());
        int at = 0;
        if (place != null && slots.contains(place.slot())) at = slots.indexOf(place.slot()) + (place.after() ? 1 : 0);
        List<ItemStack> tomes = new ArrayList<>();
        for (int s : slots) tomes.add(inv.getItem(s).copyWithCount(1));
        ItemStack held = p.getOffhandItem();
        if (isTome(held)) tomes.add(at, held.copyWithCount(1));
        else at = -1;
        return new Strip(List.copyOf(tomes), at);
    }
}
