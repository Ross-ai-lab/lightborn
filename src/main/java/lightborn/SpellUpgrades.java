package lightborn;

import static lightborn.SpellUpgrades.Stat.AREA;
import static lightborn.SpellUpgrades.Stat.COOLDOWN;
import static lightborn.SpellUpgrades.Stat.COST;
import static lightborn.SpellUpgrades.Stat.COUNT;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.SpellUpgrades.Stat.PERK;
import static lightborn.SpellUpgrades.Stat.POWER;
import static lightborn.SpellUpgrades.Stat.REACH;
import static lightborn.SpellUpgrades.Stat.SPECIAL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Spell upgrades (owner, 2026-11-15; they replaced the passive skill tree). A tome set in the Chest of Light's red
 * Upgrade Spells tab is offered three tiers of upgrades, three nodes each; a node costs its tier's Sparks (10, 20, 40)
 * and a tier opens once a node of the tier above is bought. Any number of a tier's nodes can be bought, but one per
 * tier is in use at a time, and clicking a bought node switches to it for free (owner, 2026-11-17: this replaced the
 * Clear tome button). Upgrades belong to the spell, never to the character, and are written on that tome, so they
 * travel with it.
 * <p>
 * Every upgrade raises one {@link Stat} of its spell. The cast reads them through {@link CastContext}: the generic
 * stats (power, mana, cooldown, wear) in {@link Spells#cast}, the spell's own numbers (its radius, reach, count or
 * duration) where each spell computes them, and {@link Stat#SPECIAL} where the spell has a signature trick (the old
 * attunement perks: Felling drops the leaves, Skies calls a storm, and so on).
 */
public final class SpellUpgrades {
    private SpellUpgrades() {}

    /**
     * What an upgrade changes. Fractions scale the spell's base number (+0.25 = a quarter more, -0.3 = 30% less);
     * COUNT adds whole things (targets, projectiles, pierces); SPECIAL turns on the spell's signature trick; PERK is a
     * named new thing the spell does, read by {@link #perk}.
     */
    public enum Stat {
        POWER,
        COST,
        COOLDOWN,
        AREA,
        REACH,
        COUNT,
        DURATION,
        SPECIAL,
        PERK
    }

    public record Upgrade(String name, String text, Stat stat, double amount) {}

    public static final int TIERS = 3, CHOICES = 3;
    /** Sparks each tier costs. */
    public static final int[] TIER_COST = {10, 20, 40}; // owner, 2026-11-20

    /** On the tome: the node in use per tier, and a bit mask per tier of the nodes bought. */
    static final String KEY = "lightborn_upgrades", OWNED = "lightborn_upgrades_owned";
    /** Left on tomes by the retired Clear button's refund rule; removed when a tome is next written. */
    private static final String OLD_BY = "lightborn_upgraded_by";

    private static final Map<Spell, List<List<Upgrade>>> TABLE = new EnumMap<>(Spell.class);

    /** The three tiers of three choices for a spell. */
    public static List<List<Upgrade>> tiers(Spell spell) {
        return TABLE.get(spell);
    }

    private static Upgrade u(String name, String text, Stat stat, double amount) {
        return new Upgrade(name, text, stat, amount);
    }

    private static Upgrade perk(String name, String text) {
        return new Upgrade(name, text, PERK, 1);
    }

    private static void spell(Spell spell, Upgrade... nine) {
        if (nine.length != TIERS * CHOICES) throw new IllegalStateException(spell + " needs nine upgrades");
        var tiers = new ArrayList<List<Upgrade>>();
        for (int t = 0; t < TIERS; t++)
            tiers.add(List.of(Arrays.copyOfRange(nine, t * CHOICES, t * CHOICES + CHOICES)));
        TABLE.put(spell, List.copyOf(tiers));
    }

    static {
        eighthShelf();
        // Owner, 2026-11-17: never two of one kind in a tier; every tier III node is a perk, a new thing the spell does
        // (the model is Twin Stars' second meteor), never a bigger number. Owner, 2026-11-23: one aspect per tree, so
        // no stat is raised in both tier I and tier II; where a spell had no other number left, its tier II holds small
        // new things of its own (perks) instead.
        spell(
                Spell.ARCANE_BOLT,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("More Pierces", "Passes through two more creatures.", COUNT, 2),
                u("Longer Reach", "Flies 60% farther.", REACH, .60),
                perk("Hexing Bolt", "Struck creatures are weakened for 3 seconds."),
                perk(
                        "Splitting Bolt",
                        "A bolt that strikes splits into two shards that seek the nearest monsters within 8 blocks, each for half the damage."),
                perk("Arcane Echo", "A second bolt follows the first along your aim a moment later."),
                perk("Mana Leech", "Each creature a bolt strikes gives you 3 mana back, 10 if it dies."));
        spell(
                Spell.FIREBALL,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Area", "The blast is 40% wider.", AREA, .40),
                u("Lasts Longer", "Targets burn twice as long.", DURATION, 1.0),
                perk("Blinding Smoke", "Monsters in the blast lose track of you."),
                perk("Twin Fireball", "Throws a second fireball beside the first."),
                perk(
                        "Lingering Flames",
                        "The blast leaves the ground burning for 4 seconds, scorching monsters that stand in it."),
                perk(
                        "Living Bomb",
                        "A monster the blast kills bursts in turn, hitting everything within 3 blocks for half the damage."));
        spell(
                Spell.FROST_NOVA,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Area", "The ring reaches 35% farther.", AREA, .35),
                perk("Glacial Mana", "1 mana back for each creature it strikes."),
                perk("Nova Dash", "Speed II for 3 seconds after."),
                perk("Ice Prison", "What it hits stays frozen in its block of ice for 5 seconds."),
                perk("Frost Armour", "Each monster it hits gives you 2 health of ice shield, up to 10."),
                perk("Second Ring", "A second ring bursts from you a second later, for 60% of the damage."));
        spell(
                Spell.CHAIN_LIGHTNING,
                u("More Damage", "+15% damage.", POWER, .15),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("More Jumps", "Jumps to two more creatures.", COUNT, 2),
                perk("Sparking", "Struck creatures catch fire for 2 seconds."),
                perk("Charged Mana", "1 mana back for each creature it strikes."),
                perk(
                        "Overload",
                        "The last creature struck bursts in a thunderclap, hitting all within 3 blocks for 60% of the damage."),
                perk("Static Shock", "Every creature struck is stunned for 2 seconds: slowed hard and weakened."),
                perk(
                        "Ball Lightning",
                        "A crackling orb stays at the first target for 3 seconds, zapping the nearest monster for a quarter of the damage each half second."));
        spell(
                Spell.METEOR,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 25% less mana.", COST, -.25),
                u("Shorter Cooldown", "20% shorter cooldown.", COOLDOWN, -.20),
                u("Wider Area", "The impact is 40% wider.", AREA, .40),
                u("Lands Sooner", "Lands after 2 seconds instead of 3.", DURATION, -1 / 3.0),
                perk("Impact Daze", "Struck creatures are rooted for a second."),
                perk("Twin Stars", "A second meteor falls a few blocks away."),
                perk("Molten Crater", "The crater burns for 5 seconds, scorching monsters that stand in it."),
                perk("Gravity Stone", "As the stone comes down, it drags monsters near the circle into it."));
        spell(
                Spell.BLINK,
                u("Longer Reach", "Reaches 25% farther.", REACH, .25),
                u("Less Mana", "Costs 40% less mana.", COST, -.40),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Blink Ward", "Resistance II for 2 seconds after you blink."),
                perk("Safe Landing", "No fall harm from where you blink to."),
                perk("Blink Mend", "Each blink heals you a heart."),
                perk("Phase Burst", "You arrive in a burst that throws back and slows monsters within 4 blocks."),
                perk("Echo Step", "After a blink you can blink once more within 4 seconds, free and without waiting."),
                perk("Shadow Step", "You arrive unseen and quick: Invisibility and Speed II for 3 seconds."));
        spell(
                Spell.STONESKIN,
                u("Stronger Shield", "The shield is 25% stronger.", POWER, .25),
                u("Lasts Longer", "Lasts 40% longer.", DURATION, .40),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                perk("Mossy Skin", "Regeneration I while it holds."),
                perk("Stone Fists", "Strength I while it holds."),
                u("Shorter Cooldown", "50% shorter cooldown.", COOLDOWN, -.50),
                perk("Rooted", "Nothing can knock you back while the stone holds."),
                perk("Living Rock", "You mend while the stone holds: Regeneration I."),
                perk(
                        "Shatterburst",
                        "When the shield breaks, its stone bursts outward, hitting monsters within 4 blocks for the shield's strength."));
        spell(
                Spell.MENDING_LIGHT,
                u("More Healing", "Heals 25% more.", POWER, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Cures Effects", "Also cures every harmful effect.", SPECIAL, 1),
                perk("Light Ward", "You get Absorption I for 10 seconds."),
                u("Wider Area", "Reaches 12 blocks instead of 8.", AREA, .50),
                perk(
                        "Beacon of Hope",
                        "A pillar of light stays for 8 seconds, mending you and your friends within 8 blocks 1 health a second."),
                perk("Smite", "Monsters in the light take damage equal to half the heal."),
                perk("Second Wind", "You also regain 25 mana and 3 food."));
        spell(
                Spell.VERDANT_SURGE,
                u("Wider Area", "Reaches 30% farther.", AREA, .30),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Fresh Picking", "It fills 4 hunger."),
                perk("Nature's Gift", "Regeneration I for 10 seconds."),
                perk("Green Stride", "Speed I for 30 seconds."),
                perk("Full Ripeness", "Crops in reach grow all the way to ripe in one cast."),
                perk("Sower", "Bare farmland in reach is sown with seeds from your pack."),
                perk("Wildflowers", "Grass in reach bursts into flowers and tall grass."));
        spell(
                Spell.REAP,
                u("Wider Area", "Reaches 25% farther.", AREA, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Reaper's Edge", "The sweep strikes monsters within 4 blocks for 4."),
                perk("Farmer's Feast", "It fills 4 hunger."),
                perk("Swift Harvest", "Speed I for 20 seconds."),
                perk("Rich Harvest", "One crop in three gives double."),
                perk("Straight to the Pack", "The harvest goes into your pack instead of onto the ground."),
                perk("Second Crop", "Replanted crops start half grown."));
        spell(
                Spell.EXCAVATE,
                u("Digs Deeper", "Digs one block deeper.", REACH, 1 / 3.0),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Digging Rush", "Haste II for 30 seconds."),
                perk("Tidy Digger", "What it digs lands at your feet."),
                perk("Gravel Guard", "Resistance I for 10 seconds."),
                perk("Vein Follower", "An ore it digs pulls out the rest of its vein too, up to 32 blocks."),
                perk("Smelting Bore", "What it digs comes out smelted: ingots, stone, glass."),
                perk("Wide Bore", "Bores a 5 by 5 face instead of 3 by 3."));
        spell(
                Spell.ORE_SIGHT,
                u("Wider Area", "Reaches 25% farther.", AREA, .25),
                u("Lasts Longer", "The glow lasts 50% longer.", DURATION, .50),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Feather Step", "Slow Falling for 30 seconds."),
                perk("Danger Sense", "Monsters within 24 blocks glow for 10 seconds."),
                u("Less Mana", "Costs 60% less mana.", COST, -.60),
                perk("Treasure Sense", "Unopened loot chests glow gold, spawners red and budding amethyst purple."),
                perk("Ore Ledger", "Tells you how many of each ore it found."),
                perk("Miner's Haste", "You dig faster for a minute: Haste II."));
        spell(
                Spell.FELLING,
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Longer Reach", "Reaches trunks 9 blocks away instead of 6.", REACH, .50),
                u("Drops Leaves", "The tree's leaves fall with it: saplings, sticks, apples.", SPECIAL, 1),
                perk("Lumber Haste", "Haste I for 30 seconds."),
                perk("Apple Picker", "The tree drops an apple or two."),
                perk("Replant", "A sapling of the same tree is planted where the trunk stood."),
                perk("Forest Sweep", "Fells up to four more trees standing within 5 blocks of the first."),
                perk("Charcoal Kiln", "A quarter of the logs come out as charcoal."));
        spell(
                Spell.HERD_CALL,
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Area", "Reaches 50% farther.", AREA, .50),
                u("Grows Babies", "Babies in reach grow up on the spot.", SPECIAL, 1),
                perk("Herd Speed", "Animals within 24 blocks run to you with Speed II."),
                perk("Herd Heal", "Animals within 24 blocks heal fully."),
                perk("Shearer", "Sheep in reach are shorn, their wool at your feet."),
                perk("Henhouse", "Every chicken in reach lays an egg."),
                perk("Herd Lure", "Animals within 32 blocks come walking to you."));
        spell(
                Spell.FORGE_FIRE,
                u("Smelts More", "Smelts 16 more things.", COUNT, 16),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Fire Hands", "Fire Resistance for a minute."),
                perk("Forge Strength", "Strength I for 20 seconds."),
                perk("Fire Burst", "Monsters within 4 blocks catch fire for 3 seconds."),
                perk("Rich Ore", "One raw ore in three smelts into two."),
                perk("Kiln", "Also fires sand, cobblestone, clay and logs: glass, stone, bricks, charcoal."),
                perk("Ember Heart", "The forge's heat stays in you: Fire Resistance for a minute."));
        spell(
                Spell.GATHERING,
                u("Wider Area", "Reaches 25% farther.", AREA, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Shake Off", "Each cast lifts one harmful effect from you."),
                perk("Arrow Pull", "Your arrows lying in reach come back to you."),
                perk("Gatherer's Haste", "Speed I for 10 seconds."),
                perk("Pack Mule", "What it pulls goes into your pack instead of onto the ground."),
                perk("Lingering Pull", "For 30 seconds, anything dropped within 6 blocks flies to you."),
                perk("Smelter's Pull", "Raw ore and raw food it pulls arrive smelted and cooked."));
        spell(
                Spell.HOMEWARD,
                u("Quicker Channel", "The channel is 25% shorter.", DURATION, -.25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Steady Channel", "Resistance II while you channel."),
                perk("Calm Channel", "Monsters hunting you lose you as you begin."),
                perk("Warm Welcome", "Regeneration I through the channel and 10 seconds after."),
                perk(
                        "Return Trip",
                        "Within 5 minutes of arriving, cast again, free and without waiting, to go back where you left."),
                perk("Safe Harbour", "You arrive with Regeneration II and Resistance II for 10 seconds."),
                perk("Fellowship", "Players within 4 blocks and your spirit wolves travel with you."));
        spell(
                Spell.SKIES,
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Lasts Longer", "The weather you call lasts 50% longer.", DURATION, .50),
                u("Thunderstorm", "The rain it calls comes as a thunderstorm.", SPECIAL, 1),
                perk("Rain Dance", "Speed I for 30 seconds."),
                perk("Rainbow Mend", "Regeneration I for 10 seconds."),
                perk(
                        "Storm Lord",
                        "For 30 seconds, lightning strikes a monster within 16 blocks of you every 5 seconds."),
                perk("Harvest Rain", "For a minute, the crops within 12 blocks of you grow a stage every 10 seconds."),
                perk("Sunblessed", "Whatever the sky does, you gain Speed and Haste for 2 minutes."));
        spell(
                Spell.GRAVITY_WELL,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Lasts Longer", "Holds 5 seconds instead of 3.", DURATION, 2 / 3.0),
                perk("Crushing Weight", "Struck monsters are slowed hard for a second."),
                perk("Mana Star", "Its blows give you back a tenth of their harm as mana."),
                perk("Supernova", "The collapse is twice as wide and sets everything it throws on fire."),
                perk("Leech Star", "Each bite of the well heals you for a fifth of what it took."),
                perk("Drifting Well", "The well drifts toward the nearest monster while it lives."));
        spell(
                Spell.GLACIAL_SPIKE,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Splinters Out", "A shatter splinters into every monster within 3 blocks.", SPECIAL, 1),
                perk("Ice Lance", "The struck monster is rooted for a second."),
                perk("Frost Guard", "A spike that strikes gives you Resistance I for 3 seconds."),
                perk("Icicle Volley", "Throws three spikes in a fan."),
                perk("Frozen Tomb", "A shatter chills every monster within 4 blocks, ready to shatter in turn."),
                perk(
                        "Permafrost",
                        "Where a spike strikes, the ground freezes for 5 seconds, chilling monsters that cross it."));
        spell(
                Spell.WATER_SPLASH,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Wave", "The wave runs 12 blocks in a 100-degree arc.", SPECIAL, 1),
                perk("Soaking Wave", "Struck monsters are slowed for 3 seconds."),
                perk("Ride the Wave", "You surge forward with the wave."),
                perk("Undertow", "The wave drags monsters toward you instead of throwing them back."),
                perk("Healing Spring", "You and your friends within 8 blocks are healed 4 and put out."),
                perk("Freezing Wave", "Monsters the wave strikes are chilled for 3 seconds."));
        spell(
                Spell.EARTHSHATTER,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Longer Fissure", "The fissure runs 20 blocks instead of 14, half as wide again.", SPECIAL, 1),
                perk("Shrapnel", "Struck monsters bleed for 3 seconds."),
                perk("Stone Skin", "Striking a monster gives you Resistance I for 5 seconds."),
                perk("Aftershock", "Every monster on the line is struck again a second later for half the damage."),
                perk("Quicksand", "Monsters on the line are held fast for 3 seconds."),
                perk(
                        "Boulder Toss",
                        "A boulder flies to the far end of the line and bursts, hitting all within 3 blocks."));
        spell(
                Spell.SIPHON,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("More Threads", "Five threads latch on instead of three.", COUNT, 2),
                perk("Wither Threads", "Struck monsters wither for 3 seconds."),
                perk("Sapping Threads", "Struck monsters are weakened for 3 seconds."),
                perk("Blood Shield", "Life drained past your full health becomes a shield, up to 8."),
                perk("Mana Drain", "Each pulse also gives you 2 mana."),
                perk("Death Grip", "A creature that dies on a thread makes the tome ready again at once."));
        spell(
                Spell.CONTAGION,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Longer Reach", "The plague leaps 8 blocks instead of 5.", REACH, .60),
                perk("Feverish", "Infected monsters are slowed for 3 seconds."),
                perk("Plague Glow", "Infected monsters glow for 5 seconds."),
                perk("Plague Bearer", "Sick creatures take 30% more from all your spells."),
                perk(
                        "Pestilence Cloud",
                        "A sick monster that dies leaves a cloud for 5 seconds that sickens monsters inside it."),
                perk("Rot", "Sick creatures are weakened and hit far softer."));
        spell(
                Spell.SPIRIT_WOLVES,
                u("Harder Bites", "Wolves bite 20% harder.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Stronger Wolves", "Wolves bite half as hard again and mend 1 health a second.", SPECIAL, 1),
                perk("Pack Speed", "Speed I for 30 seconds as they come."),
                perk("Howl", "Their howl sends monsters within 8 blocks running for 2 seconds."),
                perk("Third Wolf", "A third wolf joins the pack."),
                perk("Frost Fangs", "A wolf's bite slows its prey for 2 seconds."),
                perk("Spirit Return", "A wolf that falls returns to you as light, healing you 6."));
        spell(
                Spell.MIRROR_WARD,
                u("Stronger Throw", "Throws back 20% more.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Lasts Longer", "Holds 12 seconds instead of 8.", DURATION, .50),
                perk("Glass Skin", "Resistance I while the ward holds."),
                perk("Mirror Mend", "Raising the ward heals you two hearts."),
                perk("Arrow Mirror", "Arrows and other shots glance off the glass and do nothing."),
                perk(
                        "Shard Burst",
                        "When the ward ends, its glass bursts, hitting monsters within 5 blocks for twice its power."),
                perk("Glass Haste", "You move faster while the ward holds: Speed I."));
        spell(
                Spell.UPDRAFT,
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Lasts Longer", "Slow Falling lasts 50% longer.", DURATION, .50),
                u("Higher Throw", "Throws you half as high again, with 30 seconds of Slow Falling.", SPECIAL, 1),
                perk("Cloud Step", "Jump Boost II for 10 seconds."),
                perk("Gust", "Monsters within 3 blocks are blown away as you rise."),
                perk(
                        "Thunder Landing",
                        "When you land, the ground slams out, hitting and tossing monsters within 4 blocks."),
                perk("Tailwind", "The wind throws you far forward as well as up."),
                perk(
                        "Lift Friends",
                        "Players and your pets within 4 blocks are thrown up with you, with Slow Falling."));
        spell(
                Spell.ECLIPSE,
                u("Lasts Longer", "Concealment lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "15% shorter cooldown.", COOLDOWN, -.15),
                perk("Shadow Strength", "Strength I for 20 seconds."),
                perk("Shadow Cloak", "Resistance I for 20 seconds."),
                perk("Shadow Mana", "15 mana back."),
                perk("Shadow Strike", "The spell that strikes from concealment, and so ends it, does double damage."),
                perk("Night Runner", "You run fast while concealed: Speed II."),
                perk("Dark Mend", "You mend while concealed: Regeneration I."));
        spell(
                Spell.SOULS,
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Healed Summons", "Summons rise healed whole, with 4 absorption health for 20 seconds.", SPECIAL, 1),
                perk("Grave Speed", "Speed I for 20 seconds."),
                perk("Soul Mend", "Calling them heals you two hearts."),
                perk("Swift Dead", "Your spirits move 30% faster."),
                perk("Soul Harvest", "Each monster your spirits slay gives you 5 mana."),
                perk("Vengeful Dead", "A spirit that falls bursts, hitting monsters within 3 blocks."),
                perk("Spirit Ward", "With two or more of your spirits within 16 blocks, you take 15% less damage."));
        spell(
                Spell.RADIANT_PULSE,
                u("Lasts Longer", "The light lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Radiant Ward", "Resistance I for 10 seconds."),
                perk("Pulse Burst", "Monsters within 4 blocks are thrown back."),
                perk("Holy Glow", "Monsters within 16 blocks glow for 10 seconds."),
                perk("Searing Light", "Undead within reach that the light can see catch fire for 5 seconds."),
                perk("Dazzle", "Monsters within 12 blocks lose sight of you and are slowed for 5 seconds."),
                perk("Dense Light", "Lights sit every 4 blocks instead of 6."));
        spell(
                Spell.TREASURE_THREAD,
                u("Wider Area", "Reaches 25% farther.", AREA, .25),
                u("Lasts Longer", "The glow lasts 50% longer.", DURATION, .50),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Treasure Stride", "Speed I for 20 seconds."),
                u("Less Mana", "Costs 50% less mana.", COST, -.50),
                perk("Lucky Find", "Luck for 2 minutes."),
                perk("Second Thread", "A second thread runs to the next nearest chest."),
                perk("Pathfinder", "You walk the thread quickly: Speed I while the chest glows."),
                perk("Spawner Sense", "Monster spawners within reach glow red too."));
        spell(
                Spell.FLIGHT,
                u("Less Mana", "Costs 30% less mana each second.", COST, -.30),
                u("Faster Flight", "Flies 25% faster.", REACH, .25),
                u("Shorter Cooldown", "50% shorter cooldown.", COOLDOWN, -.50),
                perk("Sky Ward", "Resistance I for 10 seconds as you take off."),
                perk("Lift Off", "You spring up as you take off."),
                u("Free Hovering", "Hanging still in the air costs no mana.", SPECIAL, 1),
                perk("Ram", "Flying fast into a monster strikes it for 6 and throws it aside."),
                perk("Wind Shield", "While you ride you take 30% less damage."),
                perk("Soft Landing", "Getting off (or running dry) gives Slow Falling for 20 seconds, not 6."));
        spell(
                Spell.CAGE,
                u("Lasts Longer", "The cage holds 25% longer.", DURATION, .25),
                u("Longer Reach", "Reaches 50% farther.", REACH, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                perk("Jailer's Haste", "Speed I for 5 seconds."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                u("Second Cage", "Also cages the nearest other monster within 6 blocks of the first.", COUNT, 1),
                perk("Long Shadow", "When the cage opens, the freed monster deals 30% less damage for 10 seconds."),
                perk(
                        "Shatter",
                        "When the cage opens it bursts: 8 damage to the monster, and monsters near it thrown"
                                + " back."),
                perk("Holding Cell", "Monsters within 4 blocks of the cage are slowed: Slowness II."));
        spell(
                Spell.TAUNT,
                u("Lasts Longer", "The totem stands 30% longer.", DURATION, .30),
                u("Wider Area", "Taunts monsters 50% farther away.", AREA, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Tougher Totem", "The totem has half as much health again as you.", SPECIAL, .50),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Safe Distance", "Speed I for 10 seconds, to get clear of the totem."),
                perk("Thorned Light", "A monster that strikes the totem takes 2 damage back."),
                perk("Parting Gift", "When the totem ends or falls, it heals you 2 hearts."),
                perk("Rallying Chime", "While you stand near your totem you regenerate."));
        // The third shelf (owner, 2026-11-21).
        spell(
                Spell.SUNBEAM,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Longer Reach", "Reaches 36 blocks instead of 24.", REACH, .50),
                perk("Blinding Beam", "Struck monsters lose track of you."),
                u("Wider Area", "The beam is 75% wider.", AREA, .75),
                perk("Solar Mana", "1 mana back for each monster struck."),
                perk(
                        "Prism",
                        "The beam splits at the first creature it strikes into two side beams, each half as strong."),
                perk("Kindling Light", "Creatures the beam strikes catch fire for 3 seconds."),
                perk("Sun's Gift", "Each pulse that strikes heals you half a health."));
        spell(
                Spell.WISPS,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("More Wisps", "Seven wisps instead of five.", COUNT, 2),
                u("Lasts Longer", "Wisps last 50% longer.", DURATION, .5),
                perk("Glowing Wisps", "Struck monsters glow for 5 seconds."),
                perk("Bursting Wisps", "A wisp bursts where it strikes, hitting everything within 2 blocks for half."),
                perk("Leeching Motes", "Each wisp that strikes gives you 2 mana."),
                perk("Splitting Light", "A wisp that kills splits into two new wisps."));
        spell(
                Spell.HALO_DISC,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Longer Reach", "Flies 40% farther.", REACH, .40),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                u("Wider Area", "The blade is half as wide again.", AREA, .50),
                perk("Dazzling Halo", "Struck monsters are slowed hard for 2 seconds."),
                perk("Twin Halos", "Throws two blades, a little apart."),
                perk(
                        "Ricochet",
                        "At the end of its flight the blade darts to the nearest monster within 8 blocks first."),
                perk("Bleeding Edge", "What it cuts bleeds a fifth of the hit each second for 4 seconds."));
        spell(
                Spell.STARFALL,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Area", "The stars fall over a place 40% wider.", AREA, .40),
                u("Lasts Longer", "Stars fall half as long again, and half as many more.", DURATION, .50),
                perk("Stardazed", "Struck monsters lose track of you."),
                perk("Shooting Star", "When the shower ends, a great star falls at its heart for triple damage."),
                perk("Stardust", "Monsters a star strikes are slowed hard for 2 seconds."),
                perk("Guiding Stars", "Each star falls on a monster in the area, if there is one."));
        spell(
                Spell.STONE_WALL,
                u("Lasts Longer", "Stands 50% longer.", DURATION, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Area", "Eleven blocks wide instead of eight.", AREA, .40),
                u("Longer Reach", "Rises up to 18 blocks away instead of 12.", REACH, .50),
                perk("Wall Ward", "Resistance I for 5 seconds as it rises."),
                perk("Thorn Wall", "Monsters touching the wall take damage twice a second."),
                perk("Rising Wall", "Creatures where it rises are thrown up and back, and monsters struck."),
                perk("Ring Fort", "The wall rises as a ring round you instead."));
        spell(
                Spell.SANCTUARY,
                u("Lasts Longer", "Holds 50% longer.", DURATION, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Area", "The ward is 40% wider.", AREA, .40),
                perk("Cleansing Ward", "Every second it lifts one harmful effect from each player inside."),
                perk("Swift Ward", "Players inside move with Speed I."),
                perk("Healing Ground", "You and your friends inside regenerate."),
                perk("Smiting Edge", "A monster the ward thrusts out is struck once."),
                perk("Mana Spring", "Inside it your mana comes back twice as fast."));
        spell(
                Spell.LIGHT_BRIDGE,
                u("Longer Reach", "Runs 50% farther.", REACH, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Lasts Longer", "Stands 50% longer.", DURATION, .50),
                u("Wider Bridge", "Three blocks wide instead of one.", SPECIAL, 1),
                perk("Warm Path", "Regeneration I for 10 seconds."),
                u("Shorter Cooldown", "50% shorter cooldown.", COOLDOWN, -.50),
                perk("Swift Bridge", "You run fast on your bridge: Speed II."),
                perk("Guard Rails", "Low rails of light run along both edges."),
                perk("Featherfall", "Fall from near your bridge and you drift down slowly."));
        spell(
                Spell.OWL_SIGHT,
                u("Lasts Longer", "Lasts 50% longer.", DURATION, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Area", "Reaches 48 blocks instead of 32.", AREA, .50),
                perk("Owl's Glide", "Slow Falling while it lasts."),
                perk("Owl's Calm", "Regeneration I while it lasts."),
                perk("Hunter's Mark", "Monsters it shows take 15% more from your spells."),
                perk("Night Stalker", "You run faster while it lasts: Speed I."),
                perk("Watchful", "A chime warns you each time a monster it shows turns on you."));
        spell(
                Spell.SPIRIT_STEED,
                u("Lasts Longer", "Stays 50% longer.", DURATION, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Swift Steed", "The steed runs with Speed II."),
                u("Higher Leap", "It leaps far higher.", SPECIAL, 1),
                perk("Healing Ride", "While you ride, you heal a heart every 3 seconds."),
                perk("Trample", "Galloping into monsters strikes them and throws them aside."),
                perk("Ice Hooves", "Water freezes under its hooves, so it runs across lakes."),
                perk("Steadfast", "You take 30% less damage while riding it."));
        // The fourth shelf (owner, 2026-11-21).
        spell(
                Spell.TETHER,
                u("Longer Reach", "Reaches 50% farther.", REACH, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Soft Landing", "You take no fall damage after a pull."),
                u("Faster Pull", "Pulls nearly twice as fast.", SPECIAL, 1),
                perk("Running Start", "Speed I for 4 seconds after the line lands you."),
                perk("Lasso", "Aim at a creature and it is pulled to you instead. It does nothing to bosses."),
                perk("Slingshot", "Pulled to a block, you are flung on past it and up, instead of stopping."),
                perk("Double Line", "After each tether you may cast one more within 3 seconds, free."));
        spell(
                Spell.WAYGLOW,
                u("Longer Reach", "Flies 50% farther.", REACH, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("More Motes", "Throws three motes in a spread instead of one.", COUNT, 2),
                perk("Gentle Light", "Each throw heals you a heart."),
                perk("Feather Fall", "Slow Falling for 5 seconds when you throw, to mark a drop and take it."),
                perk("Bright Motes", "Motes are twice the size, easy to spot from far off."),
                perk("Flare", "Where a mote lands, monsters within 16 blocks glow for 10 seconds."),
                perk("Kindled Stride", "Taking down a mote gives a second of Speed, adding up with each one."));
        spell(
                Spell.THORNFIELD,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Wider Area", "The brambles spread 40% wider.", AREA, .40),
                u("Lasts Longer", "Grows 12 seconds instead of 8.", DURATION, .50),
                perk("Healing Roots", "Standing in your thicket, you heal 1 each second."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Strangling Roots", "Monsters in it cannot move at all."),
                perk("Venom Thorns", "Monsters it cuts are poisoned."),
                perk("Last Bloom", "When it fades it bursts, striking every monster still in it for triple."));
        spell(
                Spell.LIGHTBRAND,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Lasts Longer", "Burns 50% longer.", DURATION, .50),
                perk("Searing Brand", "Branded blows set monsters alight for 3 seconds."),
                perk("Brand Guard", "Resistance I while the brand burns."),
                u("Shorter Cooldown", "40% shorter cooldown.", COOLDOWN, -.40),
                perk("Cleave", "Each blow also strikes the monsters within 2.5 blocks of what you hit."),
                perk("Radiant Thirst", "Each blow heals you half a heart."),
                perk("Mana Edge", "Each blow gives you 2 mana."));
        spell(
                Spell.BALL_LIGHTNING,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Lasts Longer", "Lasts 50% longer.", DURATION, .50),
                u("Wider Area", "Zaps 40% farther.", AREA, .40),
                u("More Targets", "Zaps five monsters at a time instead of three.", COUNT, 2),
                perk("Mana Spark", "1 mana back for each zap."),
                perk("Homing Orb", "The orb drifts toward the nearest monster."),
                perk("Thunderclap", "When it fades it bursts, striking monsters within 4 blocks for triple."),
                perk("Stunning Arc", "Monsters it zaps are held fast for a second."));
        spell(
                Spell.BLADE_RING,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Lasts Longer", "Circles 50% longer.", DURATION, .50),
                u("Wider Area", "The ring is 40% wider.", AREA, .40),
                u("More Blades", "Five blades instead of three, so they cut more often.", COUNT, 2),
                perk("Bleeding Blades", "The blades make monsters bleed for 3 seconds."),
                perk("Parry", "The blades knock monsters' shots out of the air."),
                perk("Bladestorm", "When it ends, the blades fly at the nearest monsters, each striking for double."),
                perk("Whirling Guard", "What the blades cut is thrown back."));
        spell(
                Spell.REWIND,
                u("Waits Longer", "Waits 50% longer before pulling you back.", DURATION, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Safe Return", "Resistance II for 3 seconds after it pulls you back."),
                perk("Swift Return", "Speed II for 4 seconds after it pulls you back."),
                perk(
                        "Shadow Return",
                        "Invisible for 3 seconds after it pulls you back, and the monsters hunting you lose you."),
                perk("Cleansing Return", "Harmful effects and fire are gone when you return."),
                perk("Mana Rewind", "Your mana goes back too, if it was more."),
                perk("Parting Shock", "Where you leave, monsters within 4 blocks are struck and thrown back."));
        spell(
                Spell.TWINCAST,
                u("Waits Longer", "Waits 50% longer for your next spell.", DURATION, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Sooner Echo", "The echo comes twice as soon.", SPECIAL, 1),
                perk("Echo Ward", "Each echo gives you Absorption I for 10 seconds."),
                perk("Echo Sparks", "Each echo strikes the 2 nearest monsters within 8 for 3."),
                u("Two Charges", "Twins your next two spells, not one.", COUNT, 1),
                perk("Triple Cast", "Your spell is cast a third time too."),
                perk("Mana Echo", "Each echo gives you 5 mana."),
                perk("Empowered Echo", "The echoed casts are a quarter stronger."));
        // The fifth and last shelf (owner, 2026-11-22).
        spell(
                Spell.CYCLONE,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Lasts Longer", "Blows 50% longer.", DURATION, .50),
                u("Wider Area", "The funnel is 40% wider.", AREA, .40),
                perk("Dust Devil", "Monsters caught in the funnel are weakened, their blows softer."),
                perk("Twin Cyclones", "Two whirlwinds leave you, a little apart."),
                perk("Firestorm", "The whirlwind burns: monsters it catches are set alight."),
                perk("Steering Wind", "The whirlwind follows where you look."));
        spell(
                Spell.RUNE_SNARE,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Area", "Bursts 40% wider.", AREA, .40),
                u("More Traps", "Up to five traps wait at once instead of three.", COUNT, 2),
                perk("Frost Rune", "The burst freezes monsters for 3 seconds: slowed hard and shaking with cold."),
                perk("Binding Rune", "Monsters it bursts on are held fast for 3 seconds."),
                perk("Searing Rune", "Monsters it bursts on are set alight."),
                perk("Echo Rune", "A trap bursts twice before it is spent."));
        spell(
                Spell.DAWNBREAK,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Area", "Reaches 40% farther.", AREA, .40),
                perk("Sun Ward", "You get Absorption II for 10 seconds."),
                perk("Purging Light", "The dawn lifts every harmful effect and fire from you."),
                perk(
                        "Blinding Dawn",
                        "Monsters that live through it are blinded and slowed for 5 seconds, and lose sight of you."),
                perk("Morning Warmth", "You and the players in reach are healed 3 hearts."),
                perk(
                        "Lingering Sun",
                        "A small sun hangs over the place for 6 seconds, striking and burning monsters within 8 blocks each second."));
        spell(
                Spell.EMBERWALK,
                u("Lasts Longer", "Lasts 50% longer.", DURATION, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Area", "The crust spreads 60% wider.", AREA, .60),
                perk("Ember Speed", "Speed I while you walk on your embers."),
                perk("Cinder Shield", "Resistance I while it lasts."),
                // The owner's own exception to "tier III is all perks" (2026-11-22: a cooldown cut in Haste's place).
                u("Cool Embers", "50% shorter cooldown.", COOLDOWN, -.50),
                perk("Smouldering Aura", "Monsters within 3 blocks of you catch fire."),
                perk("Lava Heart", "Standing on the crust or in lava mends you 1 health every 2 seconds."));
        spell(
                Spell.CRYSTAL_SHELL,
                u("Lasts Longer", "Holds 50% longer.", DURATION, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Wider Area", "The shatter throws back monsters within 6 blocks instead of 4.", AREA, .50),
                perk("Healing Crystal", "Inside, you heal twice as fast."),
                perk("Hidden Shell", "Monsters lose you while you are inside."),
                perk(
                        "Blinding Shatter",
                        "Monsters the shatter throws are blinded and slowed for 5 seconds, and lose sight of you."),
                perk("Shard Burst", "The shatter strikes each monster it throws for 8."),
                perk("Mana Crystal", "You regain 5 mana a second inside."));
        spell(
                Spell.TRANSPOSE,
                u("Longer Reach", "Reaches 50% farther.", REACH, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Swap Ward", "Resistance II for 2 seconds after the swap."),
                perk("Swift Swap", "Speed II for 3 seconds after the swap."),
                perk("Mending Swap", "The swap heals you a heart."),
                perk("Dazing Swap", "A monster you trade places with is dazed for 3 seconds and loses sight of you."),
                perk("Shocking Swap", "A monster you trade places with is struck for 6."),
                perk("Return Swap", "You can trade places once more within 4 seconds, free."));
        spell(
                Spell.MOONGATE,
                u("Longer Reach", "The far gate opens 50% farther away.", REACH, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                u("Lasts Longer", "The gates stay 2 minutes instead of 1.", DURATION, 1.0),
                perk("Warm Gate", "Passing through heals you 2 hearts."),
                perk("Safe Passage", "Resistance I for 5 seconds after you pass through."),
                perk("Homegate", "The far gate opens beside your Chest of Light instead, wherever you are."),
                perk("Swift Passage", "Passing through gives Speed II for 10 seconds."),
                perk("Banishing Gate", "A monster that steps into a gate is struck for 8 and thrown out."));
        spell(
                Spell.QUICKENING,
                u("Wider Area", "Reaches 40% farther.", AREA, .40),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Breeding Mood", "Grown animals in reach are ready to breed."),
                perk("Quick Saplings", "Saplings within 8 blocks grow into trees."),
                perk("Sprouting", "Crops within 8 blocks grow a stage."),
                perk("Ready Again", "Grown animals resting after breeding are ready to breed again."),
                perk("Shepherd's Bounty", "Shorn sheep in reach grow their wool back."),
                perk("Hearty Herd", "Hurt animals in reach are healed to full and mend for a minute."));
        spell(
                Spell.LIGHT_BORE,
                u("Bores Deeper", "Bores 50% deeper.", REACH, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Wide Bore", "The tunnel is three blocks wide."),
                perk("Smelting Bore", "What it digs comes out smelted, as from a furnace."),
                perk("Swift Digger", "Haste II for 30 seconds after."),
                perk("Ore Snatcher", "Ore within 2 blocks of the tunnel's walls comes out too."),
                perk("Torchlit Bore", "A mote of light hangs in the tunnel every 8 blocks."),
                perk("Sealed Walls", "Water and lava touching the tunnel turn to stone."));
        // The sixth shelf (owner, 2026-11-22): six Legendary, seven Mythic.
        spell(
                Spell.SENTINEL,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Lasts Longer", "Stands 50% longer.", DURATION, .50),
                u("Wider Area", "Sees 40% farther.", AREA, .40),
                perk("Chilling Rise", "As it rises, monsters within 4 blocks freeze for 3 seconds."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Twin Crystals", "Two crystals rise, one each side of where you look."),
                perk("Splitting Shards", "Each shard splits on to a second monster near the first, for half."),
                perk(
                        "Shatter",
                        "A chilled monster that dies bursts into shards that strike and chill the monsters within 3"
                                + " blocks."));
        spell(
                Spell.BLIZZARD,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Wider Area", "40% wider.", AREA, .40),
                u("Lasts Longer", "Rages 50% longer.", DURATION, .50),
                perk("Snowblind", "Monsters in the storm as it gathers lose track of you."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Hailstones", "Each second a hailstone strikes a monster in the storm for triple."),
                perk("Whiteout", "Monsters in the storm lose sight of the players they were after."),
                perk("Eye of the Storm", "The storm rages round you instead, and goes where you go."));
        spell(
                Spell.WELLSPRING,
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Shorter Cooldown", "15% shorter cooldown.", COOLDOWN, -.15),
                perk("Light Feet", "While it lasts you run faster: Speed I."),
                perk("Spell Ward", "Absorption I while it lasts."),
                perk("Mana Surge", "When it ends, your mana is full."),
                perk("Free Fall", "While it lasts you take no fall damage."),
                perk("Swift Hands", "While it lasts, your spells' cooldowns are halved."),
                perk("Fierce Tempest", "While it lasts, your spells strike 25% harder."),
                perk("Second Wind", "While it lasts you mend: Regeneration I."));
        spell(
                Spell.BANISH,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Longer Reach", "Reaches 50% farther.", REACH, .50),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Stunning Light", "The smitten monster is rooted for 2 seconds."),
                perk("Holy Ground", "Monsters within 3 blocks of the pillar are thrown back."),
                perk("Execution", "Strikes twice as hard at a monster below half its health."),
                perk("Soul Price", "If the blow kills, you get 20 mana back."),
                perk("Searing Light", "A monster that lives through it burns for 6 seconds and glows for 10."));
        spell(
                Spell.SOUL_LINK,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Lasts Longer", "Binds 50% longer.", DURATION, .50),
                u("Wider Area", "Gathers from 40% farther.", AREA, .40),
                u("More Chains", "Binds 3 more monsters.", COUNT, 3),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Full Share", "They share the whole of the harm, not 60%."),
                perk("Chained Fall", "When a bound monster dies, the rest are struck for 6."),
                perk("Dragging Chains", "The bound are slowed while they are chained."));
        spell(
                Spell.TIME_STOP,
                u("Lasts Longer", "Holds 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Wider Area", "Reaches 40% farther.", AREA, .40),
                perk("Time Runner", "Speed II while time stands still."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Timeless Rest", "Regeneration I while time stands still."),
                perk("Stored Blows", "Harm done to a held monster lands again, half as hard, when time runs on."),
                perk("Shattered Hour", "When time runs on, every held monster is struck for 10."),
                perk("Frozen Bosses", "Bosses are held too, for half as long."));
        spell(
                Spell.TEMPEST,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Wider Area", "Strikes 40% farther out.", AREA, .40),
                u("Lasts Longer", "Rages 50% longer.", DURATION, .50),
                u("More Bolts", "Strikes 2 more monsters each second.", COUNT, 2),
                perk("Storm Ward", "Resistance I while the storm rages."),
                perk("Chain Bolts", "Each bolt leaps on to a monster within 5 blocks of the first, for half."),
                perk("Charged Air", "Each bolt gives you 2 mana back."),
                perk("Thunderclap", "Struck monsters are slowed hard for 2 seconds."));
        spell(
                Spell.COLOSSUS,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Lasts Longer", "Stays 50% longer.", DURATION, .50),
                perk("Swift Golem", "The golem moves with Speed II."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Iron Will", "Resistance I while the golem stands."),
                perk("Guardian", "Monsters within 12 blocks that are after you turn on the golem instead."),
                perk("Stoneheart", "It takes far less harm: Resistance II."),
                perk("Earthshaker", "Its slam reaches twice as far."));
        spell(
                Spell.ASCENSION,
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "15% shorter cooldown.", COOLDOWN, -.15),
                perk("Thick Skin", "Resistance II instead of I."),
                perk("Fire Hide", "Fire Resistance while you are a giant."),
                perk("Giant Heart", "Absorption II while you are a giant."),
                perk("Titan's Stride", "You run faster and leap higher: Speed II and Jump Boost II."),
                perk("Radiant Form", "You mend while you are a giant: Regeneration I."),
                perk("Trample", "Monsters at your feet are struck for 4 and thrown aside, twice a second."));
        spell(
                Spell.RENEWAL,
                u("Lasts Longer", "Its effects last 50% longer.", DURATION, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "15% shorter cooldown.", COOLDOWN, -.15),
                perk("Hearty Meal", "It fills 6 hunger as well."),
                perk("Cleansing Apple", "It lifts every harmful effect too."),
                perk("Golden Stride", "Speed I for 30 seconds."),
                perk(
                        "Enchanted Golden Apple",
                        "You gain an enchanted golden apple's effects instead: Regeneration II 20 s, Absorption IV 2"
                                + " min, Resistance and Fire Resistance 5 min."),
                perk("Shared Apple", "Players within 8 blocks gain the same."),
                perk("Golden Feast", "It fills your hunger and saturation too."));
        spell(
                Spell.TRANSMUTE,
                perk("Iron Vein", "Iron ore joins the mix."),
                perk("Copper Vein", "Copper ore joins the mix."),
                perk("Redstone Vein", "Redstone ore joins the mix."),
                perk("Gold Vein", "Gold ore joins the mix."),
                perk("Lapis Vein", "Lapis ore joins the mix."),
                perk("Emerald Vein", "Emerald ore joins the mix."),
                perk("Diamond Vein", "Diamond ore joins the mix."),
                perk("Ancient Vein", "Ancient debris joins the mix, now and then."),
                perk("Mother Lode", "A little of every ore of tiers I and II joins the mix."));
        spell(
                Spell.WOLF_FORM,
                u("More Damage", "+20% pounce damage.", POWER, .20),
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Longer Reach", "Pounces 50% farther.", REACH, .50),
                perk("Healing Bite", "Each pounce that lands heals you a heart."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Feral Bite", "Your bites and pounces make monsters bleed, 1 a second for 5 seconds."),
                perk("Rending Pounce", "A pounce strikes every monster within 2.5 blocks of where it lands."),
                perk("Moon Wolf", "You see in the dark and bite harder still: Night Vision and Strength I."));
        spell(
                Spell.BEAR_FORM,
                u("More Damage", "+20% roar damage.", POWER, .20),
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Wider Area", "The roar reaches 40% farther.", AREA, .40),
                perk("Bear's Heart", "Each roar heals you a heart."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Thick Hide", "Resistance II, not I."),
                perk("Mother Bear", "Players within 8 blocks take less harm too: Resistance I."),
                perk("Thunderous Roar", "Monsters the roar strikes are slowed hard for 3 seconds."));
        spell(
                Spell.SPIDER_FORM,
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Longer Reach", "Webs reach 50% farther.", REACH, .50),
                perk("Sticky Bite", "Your bites slow monsters for 2 seconds."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Spider Leap", "Jump Boost II while you are a spider."),
                perk("Venom", "Your bites and webs poison harder and longer: Poison II for 6 seconds."),
                perk("Web Nest", "A web catches every monster within 3 blocks of the one you aim at."),
                perk("Skitter", "You run faster: Speed II."));
        spell(
                Spell.BLAZE_FORM,
                u("More Damage", "+20% fireball damage.", POWER, .20),
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                perk("Searing Touch", "Your blows set monsters alight for 4 seconds."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Heat Haze", "Monsters that strike you burn for 3 seconds."),
                perk("Blazing Volley", "Right-click throws five fireballs, not three."),
                perk("Bursting Flame", "Fireballs burst, burning every creature within 2.5 blocks of where they land."),
                perk("Fire Ring", "Right-click throws a ring of eight fireballs out round you instead."));
        spell(
                Spell.SPIRIT_WALK,
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "20% shorter cooldown.", COOLDOWN, -.20),
                perk("Spirit Sense", "Monsters within 16 blocks glow while you walk."),
                perk("Swift Spirit", "You fly twice as fast."),
                perk("Mana Well", "You regain 2 mana a second while you walk."),
                perk("Restful Spirit", "Your body mends while you walk, a heart every 2 seconds."),
                perk("Haunting", "When you return, monsters within 6 blocks are struck for 8 and flee."),
                perk("Unseen Return", "When you return, monsters lose you and you stay unseen for 5 seconds."));
        spell(
                Spell.SHRINKING,
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Hard to Hit", "Resistance I while you are small."),
                perk("Small Leaps", "Jump Boost II while you are small."),
                perk("Small Mend", "You mend while small: Regeneration I."),
                perk("Quick Feet", "You run faster while small: Speed II."),
                perk("Light as a Mote", "You take no fall damage while small."),
                perk("Unnoticed", "Monsters more than 4 blocks away lose sight of you."));
        spell(
                Spell.GILLS,
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Tide Healing", "In water, you heal a heart every 4 seconds."),
                perk("Pressure Ward", "Resistance I while you are in water."),
                perk("Sonar", "Monsters in the water within 24 blocks glow."),
                perk("Undertow", "Monsters in the water within 8 blocks are dragged down: Slowness II."),
                perk("Shared Gills", "Players within 8 blocks gain the same."),
                perk("Tidal Strength", "You strike harder while you are in water: Strength I."));
        spell(
                Spell.PURIFY,
                u("Lasts Longer", "New harmful effects are kept off 50% longer.", DURATION, .50),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "25% shorter cooldown.", COOLDOWN, -.25),
                perk("Fireproof Ward", "Fire Resistance for as long as the ward holds."),
                perk("Ward Burst", "Monsters within 3 blocks are thrown back."),
                perk("Mana Back", "3 mana back for each harmful effect lifted."),
                perk("Shared Purify", "Players within 8 blocks are purified too."),
                perk("Mending Purity", "You heal a heart for each effect it lifts."),
                perk("Spark of Life", "You mend for 5 seconds after: Regeneration I."));
        spell(
                Spell.ENDER_POCKET,
                // No cooldown to shorten since 2026-11-23 (owner: 100 mana, no cooldown).
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                perk("Gathering Pocket", "Dropped items within 8 blocks go into your ender chest first."),
                perk("Stow Plants", "Seeds, saplings, flowers and crops in your pack (not your hotbar) go in first."),
                perk("Stow Potions", "Every potion in your pack (not your hotbar) goes in first."),
                perk("Stow Blocks", "Every stack of blocks in your pack (not your hotbar) goes in first."),
                perk("Stow Food", "Everything you can eat in your pack goes in first."),
                perk("Stow Ores", "Every ore, raw metal, ingot and gem in your pack goes in first."),
                perk(
                        "Stow Monster Loot",
                        "Bones, string, flesh, gunpowder and other monster drops in your pack go in first."),
                perk("Stow All", "Everything in your pack (not your hotbar) goes in first."));
        spell(
                Spell.WORKBENCH,
                // Owner, 2026-11-23: the crafting table at first, the other seven stations as upgrades; one in use
                // per tier, so four stations at most. The names are Workbench.Station's perk names.
                perk("Stonecutter", "A stonecutter joins the crafting table."),
                perk("Smithing Table", "A smithing table joins the crafting table."),
                perk("Grindstone", "A grindstone joins the crafting table."),
                perk("Loom", "A loom joins the stations."),
                perk("Cartography Table", "A cartography table joins the stations."),
                u("Less Mana", "Costs 40% less mana.", COST, -.40),
                perk("Anvil", "An anvil joins the stations."),
                perk(
                        "Enchanting Table",
                        "An enchanting table joins the stations (it counts real bookshelves round you)."),
                perk("Safe Crafting", "No creature can harm you while a station is open."));
        spell(
                Spell.ENFEEBLE,
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Longer Reach", "Reaches 50% farther.", REACH, .50),
                perk("Brittle", "The weakened monster takes 15% more harm."),
                u("Shorter Cooldown", "40% shorter cooldown.", COOLDOWN, -.40),
                perk("Heavy Limbs", "Slowness II instead of Slowness I."),
                perk("Spreading Weakness", "Monsters within 4 blocks of it are weakened too."),
                perk("Sapping", "Each time it strikes you, you get 5 mana back."),
                perk("Disarm", "For its first 3 seconds it deals no harm at all."));
        spell(
                Spell.SPLINTER,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("More Splinters", "Two more splinters.", COUNT, 2),
                u("Longer Reach", "Fly 50% farther.", REACH, .50),
                perk("Chilling Splinters", "Struck monsters are slowed for 2 seconds."),
                u("Shorter Cooldown", "40% shorter cooldown.", COOLDOWN, -.40),
                perk("Piercing Splinters", "Each splinter passes through one creature to strike the next."),
                perk("Bleeding Splinters", "What they strike bleeds, 1 a second for 3 seconds."),
                perk("Mana Splinters", "Each splinter that strikes gives you 1 mana back."));
        spell(
                Spell.BOULDER,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Wider Area", "Smashes 40% wider.", AREA, .40),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Quake", "Monsters round where it lands are slowed hard for 3 seconds."),
                perk("Long Throw", "It flies half again as far."),
                perk("Rolling Boulder", "After it lands it rolls on and smashes once more."),
                perk("Stunning Boulder", "What it strikes is slowed hard for 2 seconds."),
                perk(
                        "Shatterstone",
                        "It breaks into stones that strike the 3 monsters nearest, within 5 blocks, for half."));
        spell(
                Spell.FROSTWALK,
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Wider Area", "Freezes 35% wider.", AREA, .35),
                perk("Warm Heart", "Cold never freezes you while it lasts."),
                u("Shorter Cooldown", "40% shorter cooldown.", COOLDOWN, -.40),
                perk("Ice Armour", "Resistance I while you stand on ice."),
                perk("Ice Skates", "You run faster on ice: Speed II."),
                perk("Lasting Ice", "The ice it makes takes three times as long to melt."),
                perk("Chill Steps", "Monsters within 3 blocks of you are slowed."));
        spell(
                Spell.FRIGHT,
                u("Wider Area", "Reaches 35% farther.", AREA, .35),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Lasts Longer", "Lasts 50% longer.", DURATION, .50),
                perk("Marked Prey", "Frightened monsters glow while they run."),
                u("Shorter Cooldown", "40% shorter cooldown.", COOLDOWN, -.40),
                perk("Fleet Foot", "Speed I for 5 seconds."),
                perk("Cowering", "While afraid, they deal half their harm."),
                perk("Scattering Blow", "Each is struck for 3 and thrown back as it flees."),
                perk("Dread", "When the fear ends they stay weak and slow for 5 seconds."));
        spell(
                Spell.WARDEN_FORM,
                u("More Damage", "+20% sonic boom damage.", POWER, .20),
                u("Lasts Longer", "Lasts 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Longer Reach", "The sonic boom reaches 50% farther.", REACH, .50),
                perk("Sculk Sense", "Monsters within 16 blocks glow while you are a warden."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Ancient Hide", "Resistance II, not I."),
                perk("Echoing Boom", "The 2 monsters nearest the boom's end, within 6 blocks, are struck for half."),
                perk("Deep Dark", "Monsters the boom strikes are slowed hard for 3 seconds."));
        for (Spell s : Spell.values())
            if (!TABLE.containsKey(s)) throw new IllegalStateException("No upgrades for " + s);
    }

    // ---------------------------------------------------------------- on the tome

    /** The choice kept in each tier of this tome ({@code -1} where none is). */
    public static int[] chosen(ItemStack tome) {
        int[] saved = tome.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getIntArray(KEY)
                .orElse(new int[0]);
        int[] out = {-1, -1, -1};
        for (int t = 0; t < TIERS && t < saved.length; t++)
            out[t] = saved[t] >= 0 && saved[t] < CHOICES ? saved[t] : -1;
        return out;
    }

    /**
     * The nodes bought on this tome, one bit mask per tier. The node in use always counts as bought, so a tome
     * upgraded before nodes could be switched reads right.
     */
    public static int[] owned(ItemStack tome) {
        int[] saved = tome.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getIntArray(OWNED)
                .orElse(new int[0]);
        int[] chosen = chosen(tome), out = new int[TIERS];
        for (int t = 0; t < TIERS; t++) {
            out[t] = (t < saved.length ? saved[t] : 0) & ((1 << CHOICES) - 1);
            if (chosen[t] >= 0) out[t] |= 1 << chosen[t];
        }
        return out;
    }

    public static boolean owns(int[] owned, int tier, int choice) {
        return (owned[tier] & 1 << choice) != 0;
    }

    /** Whether any node is in use on this tome. */
    public static boolean upgraded(ItemStack tome) {
        for (int c : chosen(tome)) if (c >= 0) return true;
        return false;
    }

    /** The upgrades kept on this tome, tier by tier. */
    public static List<Upgrade> kept(ItemStack tome) {
        Spell spell = TomeItem.spellOf(tome);
        if (spell == null) return List.of();
        int[] chosen = chosen(tome);
        var out = new ArrayList<Upgrade>();
        for (int t = 0; t < TIERS; t++)
            if (chosen[t] >= 0) out.add(tiers(spell).get(t).get(chosen[t]));
        return out;
    }

    /** The summed amount of one stat over the given choices. */
    static double total(Spell spell, int[] chosen, Stat stat) {
        if (spell == null || chosen == null) return 0;
        double sum = 0;
        for (int t = 0; t < TIERS; t++) {
            if (chosen[t] < 0) continue;
            var up = tiers(spell).get(t).get(chosen[t]);
            if (up.stat() == stat) sum += up.amount();
        }
        return sum;
    }

    /** Writes the nodes in use and the nodes bought on the tome. */
    public static void write(ItemStack tome, int[] chosen, int[] owned) {
        CustomData.update(DataComponents.CUSTOM_DATA, tome, (CompoundTag tag) -> {
            tag.putIntArray(KEY, chosen.clone());
            tag.putIntArray(OWNED, owned.clone());
            tag.remove(OLD_BY);
        });
    }

    /** Writes these nodes in use, each counted as bought (the tests' shortcut). */
    public static void write(ItemStack tome, int[] chosen) {
        int[] owned = new int[TIERS];
        for (int t = 0; t < TIERS; t++) if (chosen[t] >= 0) owned[t] = 1 << chosen[t];
        write(tome, chosen, owned);
    }

    // ---------------------------------------------------------------- during a cast

    /** The current cast's total for one stat of {@code spell}; 0 outside a cast of it. */
    static double sum(Spell spell, Stat stat) {
        var cast = CastContext.current();
        return cast == null || cast.spell() != spell ? 0 : total(spell, cast.upgrades(), stat);
    }

    /** {@code base} raised by the cast's upgrades of that stat (fractions), never below a tenth of it. */
    static double scale(Spell spell, Stat stat, double base) {
        return base * Math.max(.1, 1 + sum(spell, stat));
    }

    static int scaleInt(Spell spell, Stat stat, int base) {
        return (int) Math.round(scale(spell, stat, base));
    }

    /** {@code base} plus the cast's whole-number upgrades of that stat. */
    static int add(Spell spell, Stat stat, int base) {
        return base + (int) Math.round(sum(spell, stat));
    }

    /** Whether the cast's tome has the spell's signature upgrade. */
    static boolean special(Spell spell) {
        return sum(spell, SPECIAL) > 0;
    }

    /**
     * The nodes in use on each player's tome of each spell at their latest cast of it. Delayed effects (a meteor
     * landing, a thread pulsing, a wolf biting) read their perks here, long after the cast itself returned.
     */
    private static final Map<java.util.UUID, EnumMap<Spell, int[]>> ARMED =
            new java.util.concurrent.ConcurrentHashMap<>();

    static void armed(ServerPlayer p, Spell spell, int[] chosen) {
        ARMED.computeIfAbsent(p.getUUID(), k -> new EnumMap<>(Spell.class)).put(spell, chosen.clone());
    }

    /**
     * Whether {@code p}'s latest cast of {@code spell} had the perk called {@code name} in use. A name the spell does
     * not have is a coding slip, so it throws, and the self-test's casts catch it.
     */
    /** The eighth shelf's Legendary spells (owner, 2026-11-23). */
    private static void eighthShelf() {
        spell(
                Spell.COMET_DASH,
                u("More Damage", "+20% damage.", POWER, .20),
                u("Longer Reach", "Dashes 30% farther.", REACH, .30),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Dazing Strike", "Struck monsters are slowed hard for 2 seconds."),
                perk("Momentum", "Speed II for 3 seconds after the dash."),
                perk("Blazing Trail", "Monsters you strike burn for 4 seconds."),
                perk("Second Wind", "Within 3 seconds of a dash, a second one is free."),
                perk(
                        "Shockwave",
                        "Where you stop, monsters within 3 blocks are struck for half the damage and thrown."));
        // Doom (owner, 2026-11-23): every node is a curse the doomed creature always suffers, on top of one drawn at
        // random from the rest (HexSpells.Curse holds the same names).
        spell(
                Spell.DOOM,
                perk("Slowness", "It always suffers Slowness II."),
                perk("Weakness", "It always deals 30% less harm."),
                perk("Glowing", "It always glows, seen through walls."),
                perk("Poison", "It always suffers Poison (Wither for the undead)."),
                perk("Burning", "It always burns."),
                perk("Frostbite", "It always freezes: slowed hard and shaking with cold."),
                perk("Bleeding", "It always bleeds, 1 harm a second."),
                perk("Rooted", "It always stands rooted, unable to walk."),
                perk("Terror", "It always runs from you in terror."));
        spell(
                Spell.LIFEBLOOM,
                u("Wider Area", "Heals 30% farther out.", AREA, .30),
                u("Lasts Longer", "Blooms 25% longer.", DURATION, .25),
                u("Less Mana", "Costs 30% less mana.", COST, -.30),
                perk("Regrowth", "Each pulse also gives Regeneration I for 3 seconds."),
                perk("Mana Bloom", "Each pulse gives you 3 mana while you stand in the ring."),
                u("Shorter Cooldown", "35% shorter cooldown.", COOLDOWN, -.35),
                perk("Thornbloom", "Monsters within its reach are struck for 2 every 2 seconds."),
                perk("Deep Roots", "It heals 4 at a time."),
                perk("Seedfall", "When it fades, everyone near it gets Absorption I for 30 seconds."));
    }

    static boolean perk(ServerPlayer p, Spell spell, String name) {
        if (p == null) return false;
        var tiers = tiers(spell);
        for (int t = 0; t < TIERS; t++)
            for (int c = 0; c < CHOICES; c++)
                if (tiers.get(t).get(c).name().equals(name)) {
                    var cast = CastContext.current();
                    int[] chosen = cast != null && cast.player() == p && cast.spell() == spell
                            ? cast.upgrades()
                            : ARMED.getOrDefault(p.getUUID(), new EnumMap<>(Spell.class))
                                    .get(spell);
                    return chosen != null && chosen[t] == c;
                }
        throw new IllegalArgumentException(spell + " has no upgrade called " + name);
    }

    // ---------------------------------------------------------------- the chest tab

    /**
     * Why clicking this node does nothing, or null. A bought node switches in for free; a new one needs a node of the
     * tier above bought and its tier's Sparks.
     */
    public static String blockReason(int[] chosen, int[] owned, int tier, int choice, int sparks) {
        if (tier < 0 || tier >= TIERS || choice < 0 || choice >= CHOICES) return "No such upgrade";
        if (chosen[tier] == choice) return "Already in use";
        if (owns(owned, tier, choice)) return null;
        if (tier > 0 && owned[tier - 1] == 0) return "Buy a tier " + roman(tier - 1) + " upgrade first";
        if (sparks < TIER_COST[tier]) return "Needs " + TIER_COST[tier] + " Sparks";
        return null;
    }

    public static String roman(int tier) {
        return switch (tier) {
            case 0 -> "I";
            case 1 -> "II";
            default -> "III";
        };
    }

    public record Choose(BlockPos chest, int slot, int tier, int choice) implements CustomPacketPayload {
        public static final Type<Choose> TYPE = new Type<>(ModItems.id("upgrade_choose"));
        static final StreamCodec<RegistryFriendlyByteBuf, Choose> CODEC = StreamCodec.composite(
                        BlockPos.STREAM_CODEC,
                        Choose::chest,
                        ByteBufCodecs.VAR_INT,
                        Choose::slot,
                        ByteBufCodecs.VAR_INT,
                        Choose::tier,
                        ByteBufCodecs.VAR_INT,
                        Choose::choice,
                        Choose::new)
                .cast();

        @Override
        public Type<Choose> type() {
            return TYPE;
        }
    }

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Choose.TYPE, Choose.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Choose.TYPE, (p, ctx) -> {
            if (LightShop.near(ctx.player(), p.chest())) choose(ctx.player(), p.slot(), p.tier(), p.choice());
        });
    }

    /** The tome at a {@link Spellstone#tomeAt} address: an inventory slot, or a slot in a carried Spellstone. */
    private static ItemStack tomeIn(ServerPlayer p, int slot) {
        var stack = Spellstone.tomeAt(p, slot);
        return TomeItem.spellOf(stack) == null ? ItemStack.EMPTY : stack;
    }

    /**
     * Clicks one node of the tome in that inventory slot: a bought node goes into use for free, a new one is bought for
     * its tier's Sparks and goes into use. Null when done.
     */
    static String choose(ServerPlayer p, int slot, int tier, int choice) {
        var tome = tomeIn(p, slot);
        if (tome.isEmpty()) return refuse(p, "Set a tome in the slot first.");
        int[] chosen = chosen(tome), owned = owned(tome);
        String reason = blockReason(chosen, owned, tier, choice, LightShop.sparks(p));
        if (reason != null) return refuse(p, reason);
        boolean switching = owns(owned, tier, choice);
        if (!switching && !LightShop.pay(p, TIER_COST[tier])) return refuse(p, "Needs " + TIER_COST[tier] + " Sparks");
        chosen[tier] = choice;
        owned[tier] |= 1 << choice;
        write(tome, chosen, owned);
        Spellstone.putTomeAt(p, slot, tome);
        p.level()
                .playSound(
                        null,
                        p.getX(),
                        p.getY(),
                        p.getZ(),
                        SoundEvents.ENCHANTMENT_TABLE_USE,
                        SoundSource.PLAYERS,
                        1f,
                        .8f + .2f * tier);
        var up = tiers(TomeItem.spellOf(tome)).get(tier).get(choice);
        p.sendOverlayMessage(
                Component.literal(up.name() + (switching ? " is in use again." : " is written on the tome."))
                        .withStyle(ChatFormatting.RED));
        return null;
    }

    private static String refuse(ServerPlayer p, String why) {
        p.sendOverlayMessage(Component.literal(why).withStyle(ChatFormatting.RED));
        return why;
    }

    /** Tooltip lines for a tome's kept upgrades. */
    static void tooltip(ItemStack tome, Consumer<Component> out) {
        var kept = kept(tome);
        if (kept.isEmpty()) return;
        int[] chosen = chosen(tome);
        int line = 0;
        for (int t = 0; t < TIERS; t++) {
            if (chosen[t] < 0) continue;
            var up = kept.get(line++);
            out.accept(Component.literal("✦ " + up.name() + ": ")
                    .withStyle(ChatFormatting.RED)
                    .append(Component.literal(up.text()).withStyle(ChatFormatting.GRAY)));
        }
    }
}
