package lightborn;

import com.mojang.serialization.Codec;
import lightborn.ModItems.Tier;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which gear tiers can drop, and from whom (owner, 2026-11-16). Two things decide a rolled drop's material:
 *
 * <ol>
 *   <li><b>The ore you have mined.</b> Your first iron ore lets monsters carry iron gear, your first diamond ore
 *       diamond, your first ancient debris netherite. Before your first iron ore no gear drops at all.
 *   <li><b>The monster's tier</b>, its Book of Souls tier (owner, 2026-11-20: the one tiering of monsters; the old
 *       health-based strength is gone). A tier 1 monster carries iron only; higher tiers lean to diamond and
 *       netherite, bosses most of all ({@link #WEIGHTS}).
 * </ol>
 *
 * A tier not yet unlocked falls out of the roll; if nothing the monster's weights allow is unlocked, it drops the best
 * tier the player has unlocked. Before the first iron ore it drops no gear. Unlocks belong to the player who kills (or calls the Cleanse, or clears the nest) and survive death.
 */
public final class OreTiers {
    private OreTiers() {}

    /** Bits of {@link Tier#ordinal()}: 1 iron, 2 diamond, 4 netherite. */
    public static final AttachmentType<Integer> MINED = AttachmentRegistry.<Integer>create(
            ModItems.id("ore_tiers_mined"), b -> b.persistent(Codec.INT).copyOnDeath());

    /** A boss, above the five monster tiers. */
    public static final int BOSS = 6;

    /** Chance weights of iron, diamond and netherite by monster tier, 1 to 5, then a boss (owner, 2026-11-20). */
    static final int[][] WEIGHTS = {
        {100, 0, 0}, // (unused)
        {100, 0, 0}, // tier 1: iron only
        {80, 20, 0}, // tier 2
        {40, 50, 10}, // tier 3
        {10, 50, 40}, // tier 4
        {0, 40, 60}, // tier 5
        {0, 30, 70} // a boss
    };

    static void init() {
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (player instanceof ServerPlayer p) mined(p, state);
        });
    }

    static Tier tierOf(BlockState state) {
        if (state.is(BlockTags.IRON_ORES)) return Tier.IRON;
        if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) return Tier.DIAMOND;
        if (state.is(Blocks.ANCIENT_DEBRIS)) return Tier.NETHERITE;
        return null;
    }

    /** A block the player broke (by hand or by spell); the first of each ore unlocks its tier, with a chat line. */
    public static void mined(ServerPlayer p, BlockState state) {
        Tier tier = tierOf(state);
        if (tier == null || unlocked(p, tier)) return;
        p.setAttached(MINED, p.getAttachedOrElse(MINED, 0) | 1 << tier.ordinal());
        p.sendSystemMessage(Component.literal("Your first "
                        + switch (tier) {
                            case IRON -> "iron ore. Monsters will carry iron gear now.";
                            case DIAMOND -> "diamond. The stronger monsters may carry diamond gear now.";
                            case NETHERITE -> "ancient debris. The mightiest monsters may carry netherite now.";
                        })
                .withStyle(ChatFormatting.GOLD));
        p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, .8f);
    }

    public static boolean unlocked(ServerPlayer p, Tier tier) {
        return p != null && (p.getAttachedOrElse(MINED, 0) & 1 << tier.ordinal()) != 0;
    }

    /** The best tier this player has mined the ore for, or null before any. */
    public static Tier best(ServerPlayer p) {
        Tier best = null;
        for (Tier t : Tier.values()) if (unlocked(p, t)) best = t;
        return best;
    }

    /**
     * The strength the Dragon's Light gear has for this player (owner, 2026-11-20: it grows with your ore): 1 iron
     * (also before any ore), 2 diamond, 3 netherite.
     */
    public static int lightLevel(ServerPlayer p) {
        Tier best = best(p);
        return best == null ? 1 : best.ordinal() + 1;
    }

    /**
     * The material of one rolled drop for this player and strength, or null when they have mined no ore yet. When
     * nothing the monster's weights allow is unlocked (a boss before the first diamond ore), it is the best tier the
     * player has unlocked: the ore caps the tier, strength only lifts the odds.
     */
    public static Tier pick(RandomSource random, ServerPlayer player, int strength) {
        int[] w = WEIGHTS[Math.clamp(strength, 1, BOSS)];
        int total = 0;
        Tier best = null;
        for (Tier t : Tier.values())
            if (unlocked(player, t)) {
                total += w[t.ordinal()];
                best = t;
            }
        if (total <= 0) return best;
        int r = random.nextInt(total);
        for (Tier t : Tier.values()) {
            if (!unlocked(player, t)) continue;
            r -= w[t.ordinal()];
            if (r < 0) return t;
        }
        return null;
    }
}
