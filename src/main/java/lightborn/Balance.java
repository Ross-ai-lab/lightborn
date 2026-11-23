package lightborn;

/** Core progression, combat and timing constants. */
public final class Balance {
    private Balance() {}

    // ---- No levels, no experience (owner, 2026-11-16): power is gear plus what Sparks buy ----
    /** Enchanting costs lapis only; the table never asks for experience levels. */
    public static final int TABLE_LAPIS_PER_TIER = 3;

    public static final int ANVIL_LAPIS_CAP = 39;
    public static final int FOOD_DRAIN_TICKS = 5 * 60 * 20;
    /** Book of Souls: one kill earns a kind's spirit; ranks II to V and slots 3 to 5 are bought with Sparks. */
    public static final int SPIRIT_KILL_COUNT_CAP = 9999, SPIRIT_MAX_RANK = 5;
    /**
     * Kills that earn a spirit, by its tier (its slot weight; owner, 2026-11-18): tier 1 80, tier 2 40, tier 3 20,
     * tier 4 10, tier 5 5. Index 0 is unused.
     */
    public static final int[] SPIRIT_KILLS_BY_WEIGHT = {0, 40, 30, 20, 10, 3}; // owner, 2026-11-20

    public static final int[] SPIRIT_RANK_COSTS = {10, 20, 40, 80};
    /** The Book of Souls starts with two slots; three more can be bought (owner, 2026-11-16). */
    public static final int SPIRIT_START_SLOTS = 2;

    public static final int[] SPIRIT_SLOT_COSTS = {20, 40, 100};
    /** Chapter 4, The Ash Lantern, is told once this many Cleanses are won (chapter 2 follows the first). */
    public static final int STORY_ASH_LANTERN_CLEANSES = 3;
    /** Each rank above I adds this much health and damage. */
    public static final double SPIRIT_RANK_BONUS = .2;

    public static final int COMMAND_BASE = 8, COMMAND_BONE_STAFF = 2;
    public static final int COMMAND_WOLF = 1, COMMAND_THRALL = 2, COMMAND_GIANT = 4;
    public static final double REGIONAL_SPAWN_SHARE = .2, STALKER_REVEAL = 6, CAPTAIN_PULL = .9;
    public static final int FROSTBITE_TICKS = 100,
            MIRE_POISON_TICKS = 100,
            DUNE_BLIND_TICKS = 40,
            CANOPY_DIVE_COOLDOWN = 80;

    // ---- Mana ----
    public static final double BASE_MANA = 100;
    /**
     * Extra Max Mana a staff carries by rarity (Basic, Magic, Rare, Legendary), a line of its own outside the affix
     * count (owner, 2026-11-16).
     */
    public static final int[] STAFF_RARITY_MANA = {0, 25, 50, 100, 150}; // by rarity, Basic to Mythic
    /** Owner, 2026-11-18: doubled to 5.6 per second, a full starting bar in about 18 seconds. */
    public static final double MANA_REGEN_PER_SECOND = 5.6;

    public static final float NIGHT_AMBIENT_MULTIPLIER = 0f;

    /** Global multiplier on every spell's damage (0.5 = the 2026-11-09 nerf by half). */
    public static final double SPELL_DAMAGE = 0.5;

    // ---- Monsters: no levels, each kind keeps its own natural strength; bosses get these fixed multiples ----
    public static final double WILD_BOSS_CHANCE = 0.002;
    public static final double BOSS_HEALTH = 14;
    public static final double BOSS_DAMAGE = 1.8;
    static final double WILD_BOSS_SPACING = 256, BOSS_BAR_RANGE = 48, BOSS_MOVE_RANGE = 24;
    static final int BOSS_WARMUP = 80, BOSS_MOVE_COOLDOWN = 120, BOSS_ENRAGED_COOLDOWN = 80;
    static final float BOSS_ENRAGE_HEALTH = 0.5f, GORATH_ENRAGED_DAMAGE = 0.85f, SYRITH_REVIVE_HEALTH = 0.4f;
    /**
     * Gorath walks in a giant zombie's body (50 health against a zombie's 20), so he was by far the tankiest boss
     * (owner, 2026-11-15: nerfed). His body counts this much base health as a boss, and his enrage raises one giant.
     */
    static final double GORATH_BASE_HEALTH = 28;

    static final int GORATH_ENRAGE_GIANTS = 1;
    static final int BOSS_ADD_CAP = 8;
    static final double BOSS_TROPHY_REPEAT = 0.25, BONECALLER_CHANCE = 0.15, ASHBRINGER_FIRE_MULTIPLIER = 1.25;
    static final int BONECALLER_TICKS = 600, ECLIPSE_TICKS = 400, ECLIPSE_COOLDOWN = 2400;
    static final int PLATE_COOLDOWN = 400, PLATE_SHIELD_TICKS = 240;
    static final double PLATE_SHIELD = 8;

    static final double BOULDER_RADIUS = 3, CAGE_RADIUS = 3, WEB_BURST_RADIUS = 4, NYX_WELL_RADIUS = 6;
    static final int BOULDER_FLIGHT = 24, CAGE_TICKS = 60, BOSS_WEB_TICKS = 80, NYX_DARKNESS_TICKS = 120;
    static final int NYX_WELL_TICKS = 60, OZRIK_TRAIL_TICKS = 80;
    static final float GORATH_SLAM_HIT = 7, BOULDER_HIT = 7, BOSS_POUNCE_HIT = 5, SYRITH_DRAIN_HIT = 5;
    static final float OZRIK_NOVA_HIT = 5, OZRIK_METEOR_HIT = 8, OZRIK_TRAIL_HIT = 3, NYX_WELL_HIT = 5;

    // ---- Giant zombies: the big kill of an ordinary night (in place of champions, owner 2026-11-09) ----
    /** Rolled items from a giant: always one, a second at this chance times Item Find. */
    public static final double GIANT_SECOND_DROP = 0.25;

    public static final double TOME_FROM_GIANT = 0.125; // halved with the rest (owner, 2026-11-18)

    // ---- Moon nights ----
    static final int DUSK_TICK = 12_000, DAWN_TICK = 23_000, SCOUT_START_TICK = 13_000;
    /**
     * The dawn refugee (Refugee): one night in five per home whose owner is within 64 blocks, starting a minute before
     * dawn, 14 blocks outside the ward. At sunrise they offer up to three places within 40 chunks that no player has
     * spent a minute near; they wait when the player falls 24 blocks behind, go on within 10, count as arrived within
     * 10, and leave once unwatched for three seconds. Unclaimed by the next dusk, they go.
     */
    static final float REFUGEE_CHANCE = .20f;

    static final int REFUGEE_LEAD_TICKS = 1200,
            REFUGEE_START_OUTSIDE = 14,
            REFUGEE_GIVE_UP_TICKS = 2400,
            REFUGEE_DAY_WAIT_TICKS = 12_000,
            REFUGEE_SEARCH_CHUNKS = 40,
            REFUGEE_MIN_SITE_DISTANCE = 48,
            REFUGEE_KNOWN_TICKS = 1200,
            REFUGEE_WAIT_DISTANCE = 24,
            REFUGEE_RESUME_DISTANCE = 10,
            REFUGEE_ARRIVE_DISTANCE = 10,
            REFUGEE_UNSEEN_TICKS = 60,
            REFUGEE_FALLBACK_SPARKS = 8,
            // Lost: four seconds out of the player's sight, then a call every six.
            REFUGEE_LOST_TICKS = 80,
            REFUGEE_BEACON_TICKS = 120;
    /** Swimming: a stroke's push each tick and the speed it holds (water friction 0.8 settles it near 0.2). */
    static final double REFUGEE_SWIM_PUSH = .045, REFUGEE_SWIM_SPEED = .22;

    static final int STIRRING_PACKS = 1, EVE_PACKS = 3;
    static final int SCOUT_MIN = 2, SCOUT_MAX = 3, EVE_MIN = 4, EVE_MAX = 5;
    static final int HUNTER_SPAWN_MIN = 24, HUNTER_SPAWN_MAX = 48;
    static final int CAVE_SPAWN_MIN = 16, CAVE_SPAWN_MAX = 32;
    static final int HUNTER_LIGHT_LIMIT = 8;
    static final double HUNTER_FOLLOW_RANGE = 64;
    static final double SCOUT_GROAN_DISTANCE = 10;
    static final double SCOUT_SHARD_CHANCE = 0.15;
    /**
     * The ward (owner, 2026-11-16): it starts at radius 9, half the old 18, and grows only when bought with Sparks in
     * the chest's Shop tab, five steps to 48. Gifts to the collection no longer grow it.
     */
    public static final int LIGHT_WARD_RADIUS = 9;

    public static final int[] WARD_RADII = {9, 13, 18, 26, 36, 48};
    /** Sparks for each step: WARD_PRICES[i] grows WARD_RADII[i] to WARD_RADII[i + 1]. */
    public static final int[] WARD_PRICES = {20, 40, 80, 150, 250};
    /**
     * Moving home (the chest's Travel tab): a flat price, any distance. The Light picks completely random ground up to
     * a million blocks out (far beyond that the game's own rendering starts to shake) and at least two thousand away,
     * whose height varies by at most four blocks across the ward. The plug reaches four blocks under the chest.
     */
    public static final int HOME_MOVE_SPARKS = 25;
    /** How far from a structure's centre a Dragon flight looks for open ground to land on. */
    static final int TRAVEL_LANDING_REACH = 24;

    static final int HOME_MOVE_RANGE = 1_000_000,
            HOME_MOVE_MIN_DISTANCE = 2000,
            HOME_MOVE_TRIES = 256,
            HOME_MOVE_TRIES_PER_TICK = 8,
            HOME_MOVE_DEPTH = 4,
            HOME_MOVE_WAIT_TICKS = 1200;
    public static final int COLLECTION_QUARTERS = 4;
    static final int HUNT_WARD_SEARCH_STEP = 4, HUNT_APPROACH_LIMIT = 120;
    static final double HUNT_APPROACH_FOLLOW_MARGIN = 8;

    /** One Hunt wave for a single player, before the Cleanse's shard weight (was 6 to 20 by power level). */
    static final int HUNT_SIZE_BASE = 8;

    static final double HUNT_EXTRA_PLAYER_SIZE = 0.5;
    static final int HUNT_PACK_MIN = 4, HUNT_PACK_MAX = 6, HUNT_ALIVE_CAP = 24;
    static final int HUNT_APPROACH_MIN = 2, HUNT_APPROACH_MAX = 3;
    static final int HUNT_PACK_TICKS = 4 * 20;
    static final int HUNT_DEATH_PAUSE_TICKS = 15 * 20, HUNT_DEATH_LIMIT = 2;
    static final int HUNT_RANGED_DIVISOR = 4, HUNT_GIANT_DIVISOR = 6;
    static final int BOSS_SHARDS = 1;

    // ---- The Cleanse (owner, 2026-11-15): one continuous wave, sized by the dark shards fed to the chest ----
    public static final int CLEANSE_MAX_SHARDS = 10;
    /** From this many shards the beaten wave is followed by one boss (never more than one). */
    public static final int CLEANSE_BOSS_SHARDS = 6;
    /** From this many shards the cache's first item is always Legendary. */
    public static final int CLEANSE_LEGENDARY_SHARDS = 8;
    /** Players this far beyond the ward's edge still count as in the fight. */
    static final int CLEANSE_ARENA_MARGIN = 32;

    public static final int CLEANSE_SPARKS_PER_ITEM = 2;

    /** The wave's size against one old Hunt wave: two at one shard, half a wave more per extra shard. */
    public static double cleanseWeight(int shards) {
        return 2 + 0.5 * (shards - 1);
    }

    /** Rolled items in the cache. */
    public static int cleanseItems(int shards) {
        return 3 + shards;
    }

    public static double cleanseRarity(int shards) {
        return 1 + 0.08 * (shards - 1);
    }

    // ---- Loot ----
    /** Chance per ordinary kill, times Item Find (was 0.10 while champions added their own drops). */
    public static final double DROP_CHANCE = 0.14;

    /** Mythic, the rung above Legendary (owner, 2026-11-22): one roll in a hundred; Legendary gave it a point. */
    public static final double MYTHIC_CHANCE = 0.01;

    public static final double LEGENDARY_CHANCE = 0.04;
    public static final double RARE_CHANCE = 0.25;
    public static final int BOSS_DROPS = 3;

    // ---- Spell tomes (casts per tome and drop weights live on Spell) ----
    /** Chance per ordinary kill, times Item Find. */
    public static final double TOME_DROP_CHANCE = 0.015; // halved (owner, 2026-11-18)
    /** Chance that any structure chest holds one tome. */
    public static final double TOME_CHEST_CHANCE = 0.15;
    /** A Cleanse's wave counts as beaten when this share of it is down (owner, 2026-11-18). */
    public static final double CLEANSE_BEATEN_SHARE = 0.9;
    /** Chance that any structure chest holds dark shards (one to SHARD_CHEST_MAX), the Cleanse's fuel. */
    public static final double SHARD_CHEST_CHANCE = 0.25;

    public static final int SHARD_CHEST_MAX = 2;
    /** Monsters a tome must slay itself (its own spell, from the off hand) before it awakens. */
    public static final int TOME_AWAKEN_KILLS = 100;

    // Sparks, the Light's own currency: the Chest of Light's shop takes nothing else, and villagers never pay them.
    public static final int QUEST_SPARKS = 8, ERRAND_SPARKS = 3, RETURN_GIFT_SPARKS = 2;
    /** A fountain crystal's least pay by stage 1 to 4, and how far above it a roll may go (owner, 2026-11-23). */
    public static final int[] NEST_SPARKS = {7, 12, 17, 21};

    public static final int NEST_SPARKS_SPREAD = 4;
    /** Every boss pays the first; a named boss (one with a trophy) adds the second on top. */
    public static final int BOSS_SPARKS = 3, NAMED_BOSS_SPARKS = 5;
    /** A Cleanse still running at dawn pays this to each fighter in it. */
    public static final int HUNT_SURVIVE_SPARKS = 3;
    /** Each kind given to the Light for the first time. */
    public static final int COLLECTION_GIFT_SPARKS = 1;
    /**
     * Sparks every monster kill pays by its power tier ({@link Souls#tierOf}, 1 to 5), lowest and highest (owner,
     * 2026-11-20): tier 1 one, tier 2 two or three, tier 3 five, tier 4 ten, tier 5 twenty-five. Index 0 is unused.
     */
    public static final int[] KILL_SPARKS_MIN = {0, 1, 2, 5, 10, 25}, KILL_SPARKS_MAX = {0, 1, 3, 5, 10, 25};

    /**
     * Chance a tier 5 monster that is not a boss drops a Relic, times Item Find (owner, 2026-11-20: one in five): a
     * Legendary piece of the best ore mined that arrives with all three upgrade tiers bought. A boss always drops one.
     */
    public static final double RELIC_CHANCE = 0.2;
    /** After dawn a Cleanse-cracked ward heals one crack this often (ten seconds; must be a multiple of 10). */
    public static final int SCAR_HEAL_TICKS = 200;
    /** Share of the carried Sparks that burn away for good on death; the rest drop where you fell. */
    public static final double DEATH_SPARK_LOSS = 0.5;
}
