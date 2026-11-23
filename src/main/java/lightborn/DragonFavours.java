package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The small things between chapters: one gift a night (sneak-use the Dragon with an item in hand) and one nightly
 * errand, both paid partly in warmth. Enough warmth moves his disposition one step; the Big Quest's disposition is
 * never changed while its task is underway (stages 4 to 6).
 */
public final class DragonFavours {
    private DragonFavours() {}

    /** Half-hearts: +10 (five hearts) warms his disposition one step, -6 (three withered hearts) cools it. */
    public static final int WARM_STEP = 10, COOL_STEP = -6;

    public static final AttachmentType<Integer> WARMTH = AttachmentRegistry.create(
            ModItems.id("dragon_warmth"),
            b -> b.persistent(Codec.intRange(COOL_STEP, WARM_STEP))
                    .copyOnDeath()
                    .syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly()));

    static void init() {} // Synced attachments register before any client joins.

    static final AttachmentType<Long> GIFT_NIGHT = DragonStory.saved("dragon_gift_night", Codec.LONG);

    // kind: 0 slay any monsters, 1 zombies, 2 skeletons, 3 spiders, 4 bring items.
    public record Errand(long night, int kind, String item, int need, int progress, boolean paid) {
        static final Codec<Errand> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Codec.LONG.fieldOf("night").forGetter(Errand::night),
                        Codec.INT.fieldOf("kind").forGetter(Errand::kind),
                        Codec.STRING.fieldOf("item").forGetter(Errand::item),
                        Codec.INT.fieldOf("need").forGetter(Errand::need),
                        Codec.INT.fieldOf("progress").forGetter(Errand::progress),
                        Codec.BOOL.fieldOf("paid").forGetter(Errand::paid))
                .apply(i, Errand::new));

        Errand progress(int n) {
            return new Errand(night, kind, item, need, Math.min(need, n), paid);
        }

        Errand settle() {
            return new Errand(night, kind, item, need, need, true);
        }

        boolean done() {
            return progress >= need;
        }

        Item wanted() {
            return BuiltInRegistries.ITEM.getValue(Identifier.parse(item));
        }
    }

    static final AttachmentType<Errand> ERRAND = DragonStory.saved("dragon_errand", Errand.CODEC);

    static final Set<Item> LOVED = Set.of(Items.DIAMOND, Items.ECHO_SHARD, Items.GOLDEN_APPLE, Items.NETHER_STAR);
    static final Set<Item> LIKED = Set.of(
            Items.GOLD_INGOT,
            Items.GOLD_NUGGET,
            Items.GLOWSTONE_DUST,
            Items.GLOWSTONE,
            Items.AMETHYST_SHARD,
            Items.GLOW_BERRIES,
            Items.GLOW_INK_SAC,
            Items.HONEYCOMB,
            Items.SEA_LANTERN,
            Items.LANTERN,
            Items.QUARTZ,
            Items.PRISMARINE_CRYSTALS,
            Items.SUNFLOWER,
            Items.TORCHFLOWER);
    static final Set<Item> DISLIKED = Set.of(
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.FERMENTED_SPIDER_EYE,
            Items.POISONOUS_POTATO,
            Items.INK_SAC,
            Items.COAL,
            Items.CHARCOAL,
            Items.BONE,
            Items.GUNPOWDER,
            Items.WITHER_ROSE);

    // What a gift is worth in warmth, and how likely he is to send something back.
    enum Taste {
        LOVED(3, .6),
        LIKED(2, .3),
        PLAIN(1, .1),
        DISLIKED(-2, 0);

        final int warmth;
        final double returns;

        Taste(int warmth, double returns) {
            this.warmth = warmth;
            this.returns = returns;
        }
    }

    static Taste taste(ItemStack s) {
        return LOVED.contains(s.getItem())
                ? Taste.LOVED
                : LIKED.contains(s.getItem())
                        ? Taste.LIKED
                        : DISLIKED.contains(s.getItem()) ? Taste.DISLIKED : Taste.PLAIN;
    }

    static final List<List<String>> WORDS = List.of(
            List.of(
                    "%s. You found a piece of light and gave it away. I won't forget it.",
                    "%s? For me? Nobody's given me anything this bright since before the chains.",
                    "%s. I can feel it from here. Thank you."),
            List.of(
                    "%s. It catches the hearth-light nicely. Thank you.",
                    "%s. Small and warm. I like it.", "%s. You know what I'm fond of, then."),
            List.of(
                    "%s. I can't do much with it, but I see the thought.",
                    "%s? Odd gift. I'll keep it by the chest.", "%s. It's the giving that warms me, not the thing."),
            List.of(
                    "%s. That smells of the dark. Keep it away from the hearth, please.",
                    "%s? I'd rather you'd kept it.", "%s. I know you meant well. I don't like it."));

    /** Sneak-use with an item in hand: one gift a night. Returns true when the click was a gift attempt. */
    static boolean offer(ServerPlayer p, LightDragon d) {
        var held = p.getMainHandItem();
        if (held.isEmpty() || !DragonTalk.valid(p, d)) return false;
        long night = DragonStory.night(p);
        if (held.getMaxStackSize() == 1) {
            DragonTalk.send(p, DragonTalk.say(p, d, "Keep that close. It's worth more in your hands than in mine."));
            return true;
        }
        if (p.getAttachedOrElse(GIFT_NIGHT, Long.MIN_VALUE) == night) {
            DragonTalk.send(p, DragonTalk.say(p, d, "You've already given me something tonight. Keep the rest."));
            return true;
        }
        var gift = held.split(1);
        p.setAttached(GIFT_NIGHT, night);
        var taste = taste(gift);
        var random = p.getRandom();
        var lines = WORDS.get(taste.ordinal());
        String text = lines.get(random.nextInt(lines.size()))
                .formatted(gift.getHoverName().getString());
        if (random.nextDouble() < taste.returns) {
            var back = returnGift(random);
            DragonTalk.give(p, back);
            text += " Here, take this back: " + back.getCount() + " × "
                    + back.getHoverName().getString() + ".";
        }
        text += warm(p, taste.warmth);
        var l = p.level();
        if (taste == Taste.DISLIKED) d.refract();
        else
            l.sendParticles(
                    taste == Taste.LOVED ? ParticleTypes.END_ROD : ParticleTypes.GLOW,
                    d.getX(),
                    d.getY() + 1.2,
                    d.getZ(),
                    taste == Taste.LOVED ? 24 : 10,
                    .5,
                    .6,
                    .5,
                    .03);
        l.playSound(
                null,
                d.blockPosition(),
                taste == Taste.DISLIKED ? SoundEvents.AMETHYST_BLOCK_HIT : SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.NEUTRAL,
                1,
                taste == Taste.DISLIKED ? .6f : 1.2f);
        DragonTalk.send(p, DragonTalk.say(p, d, text));
        return true;
    }

    static ItemStack returnGift(RandomSource random) {
        double roll = random.nextDouble();
        if (roll < .05) return new ItemStack(Items.GOLDEN_APPLE);
        if (roll < .30) return new ItemStack(ModItems.SPARK, Balance.RETURN_GIFT_SPARKS);
        if (roll < .55) return new ItemStack(Items.GLOWSTONE_DUST, 4);
        if (roll < .75) return new ItemStack(Items.AMETHYST_SHARD, 3);
        return new ItemStack(Items.LAPIS_LAZULI, 6); // was experience bottles, which no longer exist (2026-11-16)
    }

    /**
     * Adds warmth and, at a threshold, moves disposition one step. Returns a sentence to append when it moved, else
     * an empty string.
     */
    static String warm(ServerPlayer p, int delta) {
        int w = Math.clamp(p.getAttachedOrElse(WARMTH, 0) + delta, COOL_STEP, WARM_STEP);
        int stage = p.getAttachedOrCreate(DragonTalk.JOURNEY).stage();
        boolean questUnderway = stage >= 4 && stage <= 6;
        int now = p.getAttachedOrElse(DragonTalk.DISPOSITION, 1);
        if (!questUnderway && w >= WARM_STEP && now > 1) {
            p.setAttached(DragonTalk.DISPOSITION, now - 1);
            p.setAttached(WARMTH, 0);
            return " Something old in me warms to you.";
        }
        if (!questUnderway && w <= COOL_STEP && now < 3) {
            p.setAttached(DragonTalk.DISPOSITION, now + 1);
            p.setAttached(WARMTH, 0);
            return " I think I'll keep a little more distance.";
        }
        p.setAttached(WARMTH, w);
        return "";
    }

    // ---- Nightly errands ----

    static final List<Item> FETCH = List.of(
            Items.GLOWSTONE_DUST,
            Items.AMETHYST_SHARD,
            Items.GOLD_INGOT,
            Items.HONEYCOMB,
            Items.GLOW_BERRIES,
            Items.LAPIS_LAZULI,
            Items.REDSTONE,
            Items.STRING);
    static final int[] FETCH_COUNT = {8, 6, 3, 4, 8, 10, 12, 8};
    static final String[] PREY = {"monsters", "zombies", "skeletons", "spiders"};

    /** Tonight's errand for this player, rolled once from the night and the player so re-asking never rerolls. */
    static Errand tonight(ServerPlayer p) {
        long night = DragonStory.night(p);
        var held = p.getAttached(ERRAND);
        if (held != null && held.night == night) return held;
        // Fetch errands only: no "slay so many" errands (owner, 2026-11-23). Kinds 0 to 3 stay readable for a save
        // that holds one from before.
        var random = RandomSource.create(night * 31 + p.getUUID().getLeastSignificantBits());
        int i = random.nextInt(FETCH.size());
        return new Errand(night, 4, BuiltInRegistries.ITEM.getKey(FETCH.get(i)).toString(), FETCH_COUNT[i], 0, false);
    }

    static boolean accepted(ServerPlayer p) {
        var e = p.getAttached(ERRAND);
        return e != null && e.night == DragonStory.night(p);
    }

    static String describe(Errand e) {
        return e.kind == 4
                ? "Bring me " + e.need + " "
                        + new ItemStack(e.wanted()).getHoverName().getString() + " before dawn."
                : "Slay " + e.need + " " + PREY[e.kind] + " before dawn.";
    }

    static boolean counts(Errand e, LivingEntity dead) {
        return switch (e.kind) {
            case 0 -> true;
            case 1 -> dead instanceof Zombie;
            case 2 -> dead instanceof AbstractSkeleton;
            case 3 -> dead instanceof Spider;
            default -> false;
        };
    }

    /** Called for every monster kill credited to a player. */
    static void onMonsterKill(ServerPlayer p, LivingEntity dead) {
        if (!accepted(p)) return;
        var e = p.getAttached(ERRAND);
        if (e.paid || e.kind == 4 || e.done() || !counts(e, dead)) return;
        e = e.progress(e.progress + 1);
        p.setAttached(ERRAND, e);
        if (e.done())
            p.sendOverlayMessage(
                    net.minecraft.network.chat.Component.literal("The Dragon's errand is done. Tell him before dawn."));
    }

    static int carried(ServerPlayer p, Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            var s = p.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    static boolean ready(ServerPlayer p, Errand e) {
        return e.kind == 4 ? carried(p, e.wanted()) >= e.need : e.done();
    }

    static final int ASK = 40, ACCEPT = 41, REPORT = 42;

    /** The errand branch of the Dragon's menu; null when the action is not an errand action. */
    static DragonTalk.Reply respond(ServerPlayer p, LightDragon d, int action) {
        if (action != ASK && action != ACCEPT && action != REPORT) return null;
        if (action == ACCEPT && !accepted(p)) p.setAttached(ERRAND, tonight(p));
        var e = tonight(p);
        if (!accepted(p))
            return DragonTalk.page(
                    d,
                    "Something small, if you've time tonight. " + describe(e) + " I'll pay in Sparks.",
                    DragonTalk.c("I'll do it.", ACCEPT),
                    DragonTalk.c("Not tonight.", 0));
        if (e.paid) return DragonTalk.say(p, d, "That's tonight's errand done. There'll be another tomorrow.");
        if (action == REPORT && ready(p, e)) {
            if (e.kind == 4) {
                int left = e.need;
                for (int i = 0; i < p.getInventory().getContainerSize() && left > 0; i++) {
                    var s = p.getInventory().getItem(i);
                    if (!s.is(e.wanted())) continue;
                    int used = Math.min(left, s.getCount());
                    s.shrink(used);
                    left -= used;
                }
            }
            p.setAttached(ERRAND, e.settle());
            int pay = Balance.ERRAND_SPARKS;
            DragonTalk.give(p, new ItemStack(ModItems.SPARK, pay));
            p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, .5f, 1.3f);
            return DragonTalk.say(
                    p,
                    d,
                    "Done, and done well. " + pay + " Sparks, as promised." + warm(p, 2)
                            + " Come back tomorrow night; there's always something.");
        }
        String status = e.kind == 4
                ? "You carry " + Math.min(e.need, carried(p, e.wanted())) + " of " + e.need + "."
                : "You have slain " + e.progress + " of " + e.need + ".";
        return DragonTalk.say(p, d, describe(e) + " " + status);
    }

    /** The menu line for tonight's errand, or null when he has none to give yet. */
    static DragonTalk.Choice menuChoice(ServerPlayer p) {
        if (p.getAttachedOrCreate(DragonTalk.JOURNEY).stage() < 2) return null;
        if (!accepted(p)) return DragonTalk.c("Any errands tonight?", ASK);
        var e = p.getAttached(ERRAND);
        if (e.paid) return null;
        return ready(p, e)
                ? DragonTalk.c("Your errand's done.", REPORT)
                : DragonTalk.c("How's tonight's errand going?", ASK);
    }
}
