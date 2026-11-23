package lightborn;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Dark Shards are pieces of the Dark's soul, split off so it could never wholly die (owner, 2026-11-16: "like
 * horcruxes"). The first one found makes the player take it to the Dragon, who explains it; carried in numbers they
 * whisper, more often the more there are, and the edges of the screen darken ({@code ShardWhisperView}). The Cleanse
 * burns them.
 */
public final class DarkShards {
    private DarkShards() {}

    /** Story flags: a shard has been carried; the Dragon has explained them. */
    static final int SEEN = 512, TOLD = 1024;

    /** Carried shards from which they start to whisper. */
    public static final int WHISPER_FROM = 3;

    /** The Dragon-talk action that asks about the first shard. */
    static final int ASK = 50;

    static final List<String> WHISPERS = List.of(
            "Keep us. Keep us close.",
            "The Light is lying to you.",
            "Why burn what makes you strong?",
            "One more. Bring us one more.",
            "He chained the night. Who will chain him?",
            "You are more than his errand-runner.",
            "We were whole once. We will be again.",
            "Not the fire. Anything but the fire.",
            "Listen. Only listen.",
            "Your ward is so small, and the dark so wide.");

    /** Once a second, from the story tick. */
    static void tick(ServerPlayer p) {
        int n = p.getInventory().countItem(ModItems.DARK_SHARD);
        if (n <= 0) return;
        if (!DragonStory.hasFlag(p, SEEN)) {
            DragonStory.flag(p, SEEN);
            p.sendSystemMessage(Component.literal("The shard is cold in your hand, and something in it is listening. "
                            + "Ask the Dragon what it is.")
                    .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            hiss(p, .8f);
        }
        // Owner, 2026-11-17: not during a Cleanse, where the shards are being burned and the fight's own lines matter.
        if (n >= WHISPER_FROM && !Cleanse.active(p) && p.getRandom().nextDouble() < chance(n)) {
            p.sendOverlayMessage(Component.literal(WHISPERS.get(p.getRandom().nextInt(WHISPERS.size())))
                    .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            hiss(p, .5f + .05f * Math.min(n, 10));
        }
    }

    /** The chance a second that the shards whisper: about once a minute at three, every ten seconds at ten. */
    static double chance(int shards) {
        return shards < WHISPER_FROM ? 0 : Math.min(.1, .015 + (shards - WHISPER_FROM) * .012);
    }

    /** A soul's sigh only this player hears. */
    private static void hiss(ServerPlayer p, float volume) {
        p.connection.send(new ClientboundSoundPacket(
                SoundEvents.SOUL_ESCAPE,
                SoundSource.AMBIENT,
                p.getX(),
                p.getY(),
                p.getZ(),
                volume,
                .5f + p.getRandom().nextFloat() * .2f,
                p.getRandom().nextLong()));
    }

    static DragonTalk.Choice menuChoice(ServerPlayer p) {
        return DragonStory.hasFlag(p, SEEN) && !DragonStory.hasFlag(p, TOLD)
                ? new DragonTalk.Choice("I found a shard of something dark.", ASK)
                : null;
    }

    static String explain(ServerPlayer p) {
        DragonStory.flag(p, TOLD);
        return "Hold it away from you. That isn't a stone. It's a piece of the Dark itself, a sliver of its soul. Long"
                + " ago, when it was nearly beaten, it tore itself apart and hid the pieces in its creatures, so it"
                + " could never wholly die. Every shard you carry is a little of it still alive. It listens, and the"
                + " more of them you carry, the more it talks. Don't believe a word. Bring them to the chest and feed"
                + " them to the Cleanse: my light burns them out, and each one burned leaves the Dark a little smaller.";
    }
}
