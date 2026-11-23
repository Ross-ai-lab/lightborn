package lightborn;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;

/**
 * The Tome of the Workbench's stations (owner, 2026-11-23: "press right left to change the needed menu. and add more
 * tables if there are"). The spell opens a crafting table; the arrows beside it (or the left and right keys, drawn and
 * read by the client's `BenchView`) step through the stations its upgrades unlock. The crafting table is always there;
 * each other station is an upgrade, and as with every spell one upgrade per tier is in use, so a tome holds at most four
 * stations at once (owner, 2026-11-23: "3 tiers + crafting bench"). Each station is
 * a named menu that knows it belongs to the spell ({@link Bench}), stays open with no block there, and gives back what
 * is left in it when it closes, as the real block does (its access is the player's own spot, so nothing is lost).
 */
public final class Workbench {
    private Workbench() {}

    /** Every station, in the order the arrows step through them. */
    public enum Station {
        CRAFTING("container.crafting", null),
        STONECUTTER("container.stonecutter", "Stonecutter"),
        SMITHING("container.upgrade", "Smithing Table"),
        GRINDSTONE("container.grindstone_title", "Grindstone"),
        LOOM("container.loom", "Loom"),
        CARTOGRAPHY("container.cartography_table", "Cartography Table"),
        ANVIL("container.repair", "Anvil"),
        ENCHANTING("container.enchant", "Enchanting Table");

        public final String title;
        /** The upgrade that unlocks it; null for the crafting table, which is always there. */
        public final String perk;

        Station(String title, String perk) {
            this.title = title;
            this.perk = perk;
        }

        /** The next station in {@code dir} among those in {@code unlocked} (a bit per station), or this one. */
        public Station step(int dir, int unlocked) {
            var all = values();
            for (int i = 1; i < all.length; i++) {
                var s = all[Math.floorMod(ordinal() + dir * i, all.length)];
                if ((unlocked & 1 << s.ordinal()) != 0) return s;
            }
            return this;
        }
    }

    /** A station menu opened by the spell, not by a block. */
    public interface Bench {
        Station station();
    }

    /**
     * Server to client: the screen about to open is a Workbench station, and which stations the tome holds (a bit per
     * station), so the client draws the arrows.
     */
    public record Opening(int station, int unlocked) implements CustomPacketPayload {
        public static final Type<Opening> TYPE = new Type<>(ModItems.id("bench_opening"));
        static final StreamCodec<RegistryFriendlyByteBuf, Opening> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Opening::station, ByteBufCodecs.VAR_INT, Opening::unlocked, Opening::new);

        public Type<Opening> type() {
            return TYPE;
        }
    }

    /** Client to server: step to the next (+1) or previous (-1) station. */
    public record Step(int dir) implements CustomPacketPayload {
        public static final Type<Step> TYPE = new Type<>(ModItems.id("bench_step"));
        static final StreamCodec<RegistryFriendlyByteBuf, Step> CODEC =
                ByteBufCodecs.VAR_INT.map(Step::new, Step::dir).cast();

        public Type<Step> type() {
            return TYPE;
        }
    }

    /** The station last used, opened first next time while the tome still holds it (owner, 2026-11-23). */
    static final AttachmentType<Integer> LAST =
            AttachmentRegistry.create(ModItems.id("bench_last"), b -> b.persistent(Codec.INT));

    /** The stations each player's tome held at their latest cast, a bit per station; the arrows step among these. */
    private static final java.util.Map<java.util.UUID, Integer> UNLOCKED = new java.util.HashMap<>();

    static void init() {
        PayloadTypeRegistry.clientboundPlay().register(Opening.TYPE, Opening.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Step.TYPE, Step.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Step.TYPE, (a, c) -> step(c.player(), a.dir()));
    }

    private static boolean perk(ServerPlayer p, String name) {
        return SpellUpgrades.perk(p, Spell.WORKBENCH, name);
    }

    /** Which stations this player's tome holds now: the crafting table and the station upgrade in use in each tier. */
    static int unlocked(ServerPlayer p) {
        int mask = 1 << Station.CRAFTING.ordinal();
        for (var s : Station.values()) if (s.perk != null && perk(p, s.perk)) mask |= 1 << s.ordinal();
        return mask;
    }

    /** The spell: the station you used last, if the tome still holds it, else a crafting table. */
    static boolean cast(ServerLevel level, ServerPlayer p) {
        int mask = unlocked(p);
        UNLOCKED.put(p.getUUID(), mask);
        Station first = Station.CRAFTING;
        int last = p.getAttachedOrElse(LAST, 0);
        if (last >= 0 && last < Station.values().length && (mask & 1 << last) != 0) first = Station.values()[last];
        open(p, first);
        SpellKit.sound(level, p.position(), SoundEvents.VILLAGER_WORK_TOOLSMITH, .8f, 1.2f);
        return true;
    }

    /** The arrows: only from inside a station the spell opened. */
    static void step(ServerPlayer p, int dir) {
        if (!(p.containerMenu instanceof Bench b) || dir == 0) return;
        var next = b.station().step(Integer.signum(dir), UNLOCKED.getOrDefault(p.getUUID(), 1));
        if (next == b.station()) return;
        open(p, next);
        p.level().playSound(null, p.blockPosition(), SoundEvents.BOOK_PAGE_TURN, p.getSoundSource(), .7f, 1.3f);
    }

    static void open(ServerPlayer p, Station s) {
        var access = ContainerLevelAccess.create(p.level(), p.blockPosition());
        p.setAttached(LAST, s.ordinal());
        if (!(p instanceof net.fabricmc.fabric.api.entity.FakePlayer))
            ServerPlayNetworking.send(p, new Opening(s.ordinal(), UNLOCKED.getOrDefault(p.getUUID(), 1)));
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> menu(s, id, inv, access), Component.translatable(s.title)));
    }

    static AbstractContainerMenu menu(Station s, int id, Inventory inv, ContainerLevelAccess access) {
        return switch (s) {
            case CRAFTING -> new Crafting(id, inv, access);
            case STONECUTTER -> new Stonecutter(id, inv, access);
            case SMITHING -> new Smithing(id, inv, access);
            case GRINDSTONE -> new Grindstone(id, inv, access);
            case LOOM -> new Loom(id, inv, access);
            case CARTOGRAPHY -> new Cartography(id, inv, access);
            case ANVIL -> new Anvil(id, inv, access);
            case ENCHANTING -> new EnchantingBench(id, inv, access);
        };
    }

    /** Whether {@code p} is at a Workbench station just now. */
    public static boolean at(Player p) {
        return p.containerMenu instanceof Bench;
    }

    /** Safe Crafting: no creature can harm you while a station is open. */
    static float incoming(LivingEntity target, DamageSource source, float amount) {
        if (amount > 0
                && target instanceof ServerPlayer p
                && at(p)
                && source.getEntity() instanceof LivingEntity
                && perk(p, "Safe Crafting")) return 0;
        return amount;
    }

    // Each station is the vanilla menu with no block to stay near.

    static final class Crafting extends CraftingMenu implements Bench {
        Crafting(int id, Inventory inv, ContainerLevelAccess access) {
            super(id, inv, access);
        }

        @Override
        public boolean stillValid(Player player) {
            return player.isAlive();
        }

        public Station station() {
            return Station.CRAFTING;
        }
    }

    static final class Stonecutter extends StonecutterMenu implements Bench {
        Stonecutter(int id, Inventory inv, ContainerLevelAccess access) {
            super(id, inv, access);
        }

        @Override
        public boolean stillValid(Player player) {
            return player.isAlive();
        }

        public Station station() {
            return Station.STONECUTTER;
        }
    }

    static final class Smithing extends SmithingMenu implements Bench {
        Smithing(int id, Inventory inv, ContainerLevelAccess access) {
            super(id, inv, access);
        }

        @Override
        public boolean stillValid(Player player) {
            return player.isAlive();
        }

        public Station station() {
            return Station.SMITHING;
        }
    }

    static final class Grindstone extends GrindstoneMenu implements Bench {
        Grindstone(int id, Inventory inv, ContainerLevelAccess access) {
            super(id, inv, access);
        }

        @Override
        public boolean stillValid(Player player) {
            return player.isAlive();
        }

        public Station station() {
            return Station.GRINDSTONE;
        }
    }

    static final class Loom extends LoomMenu implements Bench {
        Loom(int id, Inventory inv, ContainerLevelAccess access) {
            super(id, inv, access);
        }

        @Override
        public boolean stillValid(Player player) {
            return player.isAlive();
        }

        public Station station() {
            return Station.LOOM;
        }
    }

    static final class Cartography extends CartographyTableMenu implements Bench {
        Cartography(int id, Inventory inv, ContainerLevelAccess access) {
            super(id, inv, access);
        }

        @Override
        public boolean stillValid(Player player) {
            return player.isAlive();
        }

        public Station station() {
            return Station.CARTOGRAPHY;
        }
    }

    static final class Anvil extends AnvilMenu implements Bench {
        Anvil(int id, Inventory inv, ContainerLevelAccess access) {
            super(id, inv, access);
        }

        @Override
        public boolean stillValid(Player player) {
            return player.isAlive();
        }

        public Station station() {
            return Station.ANVIL;
        }
    }

    static final class EnchantingBench extends EnchantmentMenu implements Bench {
        EnchantingBench(int id, Inventory inv, ContainerLevelAccess access) {
            super(id, inv, access);
        }

        @Override
        public boolean stillValid(Player player) {
            return player.isAlive();
        }

        public Station station() {
            return Station.ENCHANTING;
        }
    }
}
