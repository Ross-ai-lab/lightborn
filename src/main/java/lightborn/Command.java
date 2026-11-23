package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Persistent shared capacity. Unloaded companions still reserve their place. */
public final class Command {
    private Command() {}

    public record Bond(UUID id, int cost, boolean wolf) {
        public static final StreamCodec<ByteBuf, Bond> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString),
                Bond::id,
                ByteBufCodecs.VAR_INT,
                Bond::cost,
                ByteBufCodecs.BOOL,
                Bond::wolf,
                Bond::new);
        public static final Codec<Bond> CODEC = RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("id").forGetter(Bond::id),
                        Codec.INT.fieldOf("cost").forGetter(Bond::cost),
                        Codec.BOOL.fieldOf("wolf").forGetter(Bond::wolf))
                .apply(i, Bond::new));
    }

    public static int capacity(Player p) {
        boolean bone = isBone(p.getMainHandItem()) || isBone(p.getOffhandItem());
        for (int i = 0; i < p.getInventory().getContainerSize() && !bone; i++)
            bone = isBone(p.getInventory().getItem(i));
        return Balance.COMMAND_BASE + (bone ? Balance.COMMAND_BONE_STAFF : 0);
    }

    private static boolean isBone(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(ModItems.id("bone_staff"));
    }

    private static Entity find(ServerPlayer p, UUID id) {
        for (var level : p.level().getServer().getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    static int cost(Entity e) {
        String soul = e.getAttached(ModData.SOUL_KIND);
        Souls.Page page = soul == null ? null : Souls.page(soul);
        if (page != null) return page.weight(); // a full book (five slots) always fits the command
        return e instanceof SpiritWolf
                ? Balance.COMMAND_WOLF
                : Giants.is(e) ? Balance.COMMAND_GIANT : Balance.COMMAND_THRALL;
    }

    static List<Bond> roster(ServerPlayer p) {
        consumeDeaths(p);
        var thralls = p.getAttachedOrElse(ModData.BOUND_THRALLS, List.<UUID>of());
        var wolves = p.getAttachedOrElse(ModData.BOUND_WOLVES, List.<UUID>of());
        List<Bond> roster = new ArrayList<>(p.getAttachedOrElse(ModData.COMMAND_ROSTER, List.of()));
        roster.removeIf(b -> !thralls.contains(b.id()) && !wolves.contains(b.id()));
        for (UUID id : thralls)
            if (roster.stream().noneMatch(b -> b.id().equals(id))) roster.add(new Bond(id, cost(find(p, id)), false));
        for (UUID id : wolves)
            if (roster.stream().noneMatch(b -> b.id().equals(id))) roster.add(new Bond(id, Balance.COMMAND_WOLF, true));
        p.setAttached(ModData.COMMAND_ROSTER, List.copyOf(roster));
        return roster;
    }

    static void died(Entity entity) {
        UUID owner = entity.getAttached(ModData.THRALL_MASTER);
        if (owner == null && entity instanceof SpiritWolf wolf && wolf.getOwnerReference() != null)
            owner = wolf.getOwnerReference().getUUID();
        if (owner == null || entity.level().getServer() == null) return;
        var world = entity.level().getServer().overworld();
        var pending =
                new LinkedHashMap<>(world.getAttachedOrElse(ModData.DEAD_COMPANIONS, Map.<String, List<UUID>>of()));
        var ids = new LinkedHashSet<>(pending.getOrDefault(owner.toString(), List.of()));
        ids.add(entity.getUUID());
        UUID previous = entity.getAttached(ModData.PREVIOUS_BOUND_ID);
        if (previous != null) ids.add(previous);
        pending.put(owner.toString(), List.copyOf(ids));
        world.setAttached(ModData.DEAD_COMPANIONS, Map.copyOf(pending));
    }

    private static void consumeDeaths(ServerPlayer p) {
        var world = p.level().getServer().overworld();
        var pending = world.getAttachedOrElse(ModData.DEAD_COMPANIONS, Map.<String, List<UUID>>of());
        var ids = pending.get(p.getUUID().toString());
        if (ids == null) return;
        var thralls = new ArrayList<>(p.getAttachedOrElse(ModData.BOUND_THRALLS, List.<UUID>of()));
        thralls.removeAll(ids);
        p.setAttached(ModData.BOUND_THRALLS, List.copyOf(thralls));
        var wolves = new ArrayList<>(p.getAttachedOrElse(ModData.BOUND_WOLVES, List.<UUID>of()));
        wolves.removeAll(ids);
        p.setAttached(ModData.BOUND_WOLVES, List.copyOf(wolves));
        var remaining = new LinkedHashMap<>(pending);
        remaining.remove(p.getUUID().toString());
        world.setAttached(ModData.DEAD_COMPANIONS, Map.copyOf(remaining));
    }

    public static int used(ServerPlayer p) {
        return roster(p).stream().mapToInt(Bond::cost).sum();
    }

    public static int used(Player p) {
        return p instanceof ServerPlayer server
                ? used(server)
                : p.getAttachedOrElse(ModData.COMMAND_ROSTER, List.<Bond>of()).stream()
                        .mapToInt(Bond::cost)
                        .sum();
    }

    static boolean hasRoom(ServerPlayer p, int cost) {
        return used(p) + cost <= capacity(p);
    }

    static void relink(ServerPlayer p, Mob converted) {
        UUID previous = converted.getAttached(ModData.PREVIOUS_BOUND_ID);
        if (previous == null) return;
        var bound = new ArrayList<>(p.getAttachedOrElse(ModData.BOUND_THRALLS, List.<UUID>of()));
        int index = bound.indexOf(previous);
        if (index >= 0) {
            bound.set(index, converted.getUUID());
            p.setAttached(ModData.BOUND_THRALLS, List.copyOf(bound));
            var roster = new ArrayList<>(p.getAttachedOrElse(ModData.COMMAND_ROSTER, List.<Bond>of()));
            for (int i = 0; i < roster.size(); i++)
                if (roster.get(i).id().equals(previous))
                    roster.set(i, new Bond(converted.getUUID(), cost(converted), false));
            p.setAttached(ModData.COMMAND_ROSTER, List.copyOf(roster));
        }
        converted.removeAttached(ModData.PREVIOUS_BOUND_ID);
    }

    static void reserve(ServerPlayer p, Entity entity) {
        List<Bond> roster = roster(p);
        roster.removeIf(b -> b.id().equals(entity.getUUID()));
        trim(p, roster, cost(entity));
        roster.add(new Bond(entity.getUUID(), cost(entity), entity instanceof SpiritWolf));
        p.setAttached(ModData.COMMAND_ROSTER, List.copyOf(roster));
    }

    private static void trim(ServerPlayer p, List<Bond> roster, int extra) {
        while (!roster.isEmpty() && roster.stream().mapToInt(Bond::cost).sum() + extra > capacity(p)) {
            // Preserve the two spirit wolves where possible; replace the oldest thrall.
            Bond oldest = roster.stream().filter(b -> !b.wolf()).findFirst().orElse(roster.getFirst());
            roster.remove(oldest);
            var thralls = new ArrayList<>(p.getAttachedOrElse(ModData.BOUND_THRALLS, List.<UUID>of()));
            thralls.remove(oldest.id());
            p.setAttached(ModData.BOUND_THRALLS, List.copyOf(thralls));
            var wolves = new ArrayList<>(p.getAttachedOrElse(ModData.BOUND_WOLVES, List.<UUID>of()));
            wolves.remove(oldest.id());
            p.setAttached(ModData.BOUND_WOLVES, List.copyOf(wolves));
            Entity e = find(p, oldest.id());
            if (e instanceof SpiritWolf) e.discard();
            else if (e instanceof Mob mob) SummonSpells.releaseCompanion(mob);
            p.sendOverlayMessage(Component.literal("Command full: your oldest thrall is released")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        for (var p : server.getPlayerList().getPlayers()) {
            enforce(p);
            List<Bond> roster = roster(p);
            for (Bond b : roster) if (find(p, b.id()) instanceof Mob mob) Souls.applyRank(p, mob);
        }
    }

    static void enforce(ServerPlayer p) {
        List<Bond> roster = roster(p);
        trim(p, roster, 0);
        p.setAttached(ModData.COMMAND_ROSTER, List.copyOf(roster));
    }
}
