package lightborn;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Which Chest of Light tabs a player has opened up (owner, 2026-11-18). Upgrade Spells, Upgrade Gear and the
 * Collection are there from the start; the rest appear as the game gets to them:
 * <ul>
 *   <li>Shop: after surviving one night (outdoors or in, from nightfall to dawn without dying);
 *   <li>Cleanse: after beating one Dark Fountain;
 *   <li>Travel: after seven Minecraft days of player playtime;
 *   <li>Spirits: once the Book of Souls has been found (carried, or in a Spellstone).
 * </ul>
 * The counts live on the player, kept through death and synced to them so the chest can read them.
 */
public final class ChestTabs {
    private ChestTabs() {}

    public static final int CLEANSE_FOUNTAINS = 1, SHOP_NIGHTS = 1;
    static final int TRAVEL_PLAY_TICKS = 7 * 24_000;
    public static final String TRAVEL_LOCKED = "Play for seven Minecraft days first.";

    /** Dark Fountains whose core this player broke. */
    public static final AttachmentType<Integer> FOUNTAINS_BEATEN = AttachmentRegistry.<Integer>create(
            ModData.id("fountains_beaten"),
            b -> b.persistent(Codec.INT)
                    .copyOnDeath()
                    .syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly()));
    /** Nights this player lived through from nightfall to dawn. */
    public static final AttachmentType<Integer> NIGHTS_SURVIVED = AttachmentRegistry.<Integer>create(
            ModData.id("nights_survived"),
            b -> b.persistent(Codec.INT)
                    .copyOnDeath()
                    .syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly()));

    private static final AttachmentType<Boolean> TRAVEL_READY = AttachmentRegistry.<Boolean>create(
            ModData.id("travel_ready"), b -> b.syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.targetOnly()));

    /** Whether this player has held the Book of Souls. */
    public static final AttachmentType<Boolean> SOULS_FOUND = AttachmentRegistry.<Boolean>create(
            ModData.id("souls_found"),
            b -> b.persistent(Codec.BOOL)
                    .copyOnDeath()
                    .syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.targetOnly()));
    /**
     * The night this player has been keeping watch through since nightfall (its day number). Not kept through death,
     * so dying ends the watch; joining mid-night starts none.
     */
    static final AttachmentType<Long> WATCH =
            AttachmentRegistry.create(ModData.id("night_watch"), b -> b.persistent(Codec.LONG));

    public static boolean shop(Player p) {
        return p.isCreative() || p.getAttachedOrElse(NIGHTS_SURVIVED, 0) >= SHOP_NIGHTS;
    }

    public static boolean cleanse(Player p) {
        return p.isCreative() || p.getAttachedOrElse(FOUNTAINS_BEATEN, 0) >= CLEANSE_FOUNTAINS;
    }

    public static boolean travel(Player p) {
        return p.isCreative()
                || (p instanceof ServerPlayer server
                        ? playedWeek(server)
                        : Boolean.TRUE.equals(p.getAttached(TRAVEL_READY)));
    }

    private static boolean playedWeek(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) >= TRAVEL_PLAY_TICKS;
    }

    public static boolean spirits(Player p) {
        return p.isCreative() || Boolean.TRUE.equals(p.getAttached(SOULS_FOUND));
    }

    static void fountainBeaten(ServerPlayer p) {
        p.setAttached(FOUNTAINS_BEATEN, p.getAttachedOrElse(FOUNTAINS_BEATEN, 0) + 1);
    }

    private static Boolean wasNight;

    /** Loads the class at start-up, so its saved attachments are registered before any player loads. */
    static void init() {}

    /** Once a second: the night watch, and whether the Book of Souls has turned up in a player's bag. */
    static void tick(MinecraftServer server) {
        if (Scheduler.now() % 20 != 0) return;
        var overworld = server.overworld();
        boolean night = LightDragons.night(overworld);
        boolean nightfall = night && Boolean.FALSE.equals(wasNight), dawn = !night && Boolean.TRUE.equals(wasNight);
        wasNight = night;
        long day = LightShop.day(overworld);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean ready = playedWeek(p);
            if (ready != Boolean.TRUE.equals(p.getAttached(TRAVEL_READY))) p.setAttached(TRAVEL_READY, ready);
            if (!Boolean.TRUE.equals(p.getAttached(SOULS_FOUND)) && carriesSouls(p)) p.setAttached(SOULS_FOUND, true);
            if (!p.isAlive()) continue;
            if (nightfall && p.level().dimension() == Level.OVERWORLD) p.setAttached(WATCH, day);
            else if (dawn && p.getAttached(WATCH) != null) {
                p.removeAttached(WATCH);
                p.setAttached(NIGHTS_SURVIVED, p.getAttachedOrElse(NIGHTS_SURVIVED, 0) + 1);
            }
        }
    }

    /** Server stop: the next start reads the clock afresh. */
    static void clear() {
        wasNight = null;
    }

    static boolean carriesSouls(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (TomeItem.spellOf(s) == Spell.SOULS) return true;
            if (Spellstone.is(s)) for (var t : Spellstone.tomes(s)) if (TomeItem.spellOf(t) == Spell.SOULS) return true;
        }
        return false;
    }
}
