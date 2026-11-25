package lightborn;

import java.util.List;

/**
 * The spells. Each lives in its own tome: hold the tome in the off hand and right-click with a staff.
 * Numbers are base values, before Spell Power and upgrades.
 * Mana and cooldowns are the owner's own per-spell numbers (bolt 8 / 1 s, fireball 16 / 2 s, frost nova 18 / 5 s,
 * chain 15 / 2 s, meteor 30 / 6 s, blink 40 / 10 s, Trees 30 s, Excavate 10 mana, Ore Sight 1 min). The other
 * non-combat spells carry first-pass numbers.
 * For defence spells baseDamage is their power (shield or heal), scaled exactly like damage.
 */
public enum Spell {
    ARCANE_BOLT(
            "Arcane Bolt",
            "minecraft:amethyst_shard",
            8,
            10,
            3.75,
            1,
            true,
            "A fast bolt of arcane force.",
            Kind.ATTACK),
    // Tier 2 and a little stronger (owner, 2026-11-18): 10 damage, was 9, and a 2-block blast, was 1.75.
    FIREBALL(
            "Fireball",
            "minecraft:fire_charge",
            10,
            32,
            10,
            2,
            true,
            "Explodes on impact and sets its targets alight. No block damage.",
            Kind.ATTACK),
    FROST_NOVA(
            "Ice Nova",
            "minecraft:blue_ice",
            20,
            140,
            7,
            3, // tier 3 since the ice-block freeze (owner, 2026-11-18)
            true,
            "A ring of ice around you: damages, knocks back hard and freezes what it hits in a block of ice for 3 seconds.",
            Kind.ATTACK),
    CHAIN_LIGHTNING(
            "Arc",
            "minecraft:lightning_rod",
            15,
            40,
            8,
            3,
            true,
            "Strikes the enemy you look at, then jumps to 4 more.",
            Kind.ATTACK),
    METEOR(
            "Comet",
            "minecraft:magma_block",
            25,
            120,
            37.5,
            5,
            true,
            "Calls a meteor straight down from the sky where you look. It lands after 3 seconds.",
            Kind.ATTACK),
    BLINK("Blink", "minecraft:ender_pearl", 40, 400, 0, 3, true, "Teleport up to 12 blocks forward.", Kind.UTILITY),
    STONESKIN(
            "Stoneskin",
            "minecraft:iron_block",
            25,
            400,
            8,
            2,
            true,
            "Your skin turns to stone: a shield that soaks damage, and Resistance for 12 s.",
            Kind.DEFENCE),
    // Item ids stay tome_mending_light and tome_verdant_surge, so tomes already in a world keep working.
    MENDING_LIGHT(
            "Light",
            "minecraft:glistering_melon_slice",
            30,
            240,
            12,
            2,
            true,
            "Heals you and every friendly creature within 8 blocks: players, pets, villagers, animals.",
            Kind.DEFENCE),
    VERDANT_SURGE(
            "Trees",
            "minecraft:bone_meal",
            100,
            600,
            0,
            2,
            true,
            "Everything within 15 blocks grows as if bone-mealed, and saplings shoot up into full trees.",
            Kind.FARMING),
    REAP(
            "Reap",
            "minecraft:wheat",
            10,
            60,
            0,
            1,
            true,
            "Harvests every ripe crop within 8 blocks to your feet, and replants it.",
            Kind.FARMING),
    EXCAVATE(
            "Excavate",
            "minecraft:iron_pickaxe",
            10,
            40,
            0,
            2,
            true,
            "Breaks a 3 by 3 tunnel, 3 deep, into the block you look at. Mines like an iron pickaxe.",
            Kind.MINING),
    ORE_SIGHT(
            "Ore Sight",
            "minecraft:spyglass",
            25,
            1200,
            0,
            3,
            true,
            "Ores within 15 blocks glow through stone for 10 minutes.",
            Kind.MINING),
    FELLING(
            "Felling",
            "minecraft:iron_axe",
            100, // owner, 2026-11-23: 100 mana and 45 seconds (was 15 and 2)
            900,
            0,
            1,
            true,
            "Fells the whole tree you look at: every log drops at your feet.",
            Kind.FARMING),
    HERD_CALL(
            "Herd Call",
            "minecraft:lead",
            20,
            200,
            0,
            1,
            true,
            "Every grown animal within 10 blocks falls in love, as if fed.",
            Kind.FARMING),
    FORGE_FIRE(
            "Forge Fire",
            "minecraft:blast_furnace",
            25,
            400,
            0,
            2,
            true,
            "Smelts up to 32 raw ores, ore blocks and raw food you carry.",
            Kind.MINING),
    GATHERING(
            "Gathering",
            "minecraft:hopper",
            10,
            100,
            0,
            1,
            true,
            "Pulls every dropped item within 16 blocks to your feet.",
            Kind.UTILITY),
    HOMEWARD(
            "Homeward",
            "minecraft:recovery_compass",
            100,
            3600,
            0,
            2, // tier 2; given at the start again in the Spellstone (owner, 2026-11-23)
            true,
            "After 10 s, carries you to the placed Chest of Light, or your first spawn if it is not placed.",
            Kind.UTILITY),
    SKIES(
            "Skies",
            "minecraft:sunflower",
            40,
            1200,
            0,
            2,
            true,
            "Turns the weather: rain clears for 10 minutes, a clear sky brings 5 minutes of rain.",
            Kind.UTILITY),
    // The second shelf (owner, 2026-11-09: ten more, "quality bangers").
    // Owner's retune, 2026-11-10: Gravity Well 9 s; Glacial Spike 10 mana, 1 s; Earthshatter 25 mana, 6 s; Siphon
    // 40 mana, 8 s, a third more damage; Updraft 20 s. Water Splash replaced Spectral Blades. Dominate is gone
    // (owner, 2026-11-17: a weaker copy of the Book of Souls).
    GRAVITY_WELL(
            "Gravity Well",
            "minecraft:crying_obsidian",
            35,
            180,
            10,
            4,
            true,
            "A collapsing star where you aim: drags every creature within 7 blocks in for 3 s, then implodes and throws them up.",
            Kind.ATTACK),
    GLACIAL_SPIKE(
            "Glacial Spike",
            "minecraft:packed_ice",
            10,
            20,
            7,
            2,
            true,
            "A lance of ice that chills. A chilled target shatters for triple damage.",
            Kind.ATTACK),
    WATER_SPLASH(
            "Water Splash",
            "minecraft:water_bucket",
            20,
            60,
            8,
            2,
            true,
            "A wave bursts from you in an arc: it strikes and throws back everything in front, puts out fire, and turns lava to stone.",
            Kind.ATTACK),
    EARTHSHATTER(
            "Earthshatter",
            "minecraft:pointed_dripstone",
            25,
            120,
            12,
            4,
            true,
            "The ground splits along your gaze for 14 blocks: stone spears strike and throw everything on the line.",
            Kind.ATTACK),
    SIPHON(
            "Essence Drain",
            "minecraft:ghast_tear",
            40,
            160,
            2.66,
            4,
            true,
            "Threads drain up to 3 creatures in sight for 4 s; half of what they lose heals you.",
            Kind.ATTACK),
    CONTAGION(
            "Contagion",
            "minecraft:fermented_spider_eye",
            20,
            120,
            2.5,
            3,
            true,
            "A plague on the creature you look at and its pack. When a sick monster dies, it leaps to every monster near.",
            Kind.ATTACK),
    SPIRIT_WOLVES(
            "Spirit Wolves",
            "minecraft:bone",
            40,
            900,
            6,
            4,
            true,
            "Two glowing spirit wolves join you and stay until they fall. Cast again to call them back, healed, or to raise a fallen one.",
            Kind.ATTACK),
    MIRROR_WARD(
            "Mirror Ward",
            "minecraft:glass",
            30,
            400,
            5,
            4,
            true,
            "For 8 s creatures' hits do half damage, and whoever struck takes one and a half times the hit back.",
            Kind.DEFENCE),
    UPDRAFT(
            "Updraft",
            "minecraft:phantom_membrane",
            10,
            100, // 5 s (owner, 2026-11-18)
            0,
            3,
            true,
            "Hold to gather the wind, release to be thrown up: a full 1.5 s charge throws you about 40 blocks, a short one less. Scatters monsters around you; you drift down slowly for 20 s.",
            Kind.UTILITY),
    ECLIPSE(
            "Eclipse",
            "minecraft:echo_shard",
            50,
            Balance.ECLIPSE_COOLDOWN,
            0,
            4,
            true,
            "Monsters lose sight of you and you fade from view for 20 seconds. Striking anything ends it; building, mining and the rest do not.",
            Kind.UTILITY),
    SOULS(
            "Book of Souls",
            "minecraft:echo_shard",
            100,
            400,
            0,
            3, // tier 3, found like any tome; no longer given at the start (owner, 2026-11-18)
            true,
            "Raises every spirit placed in the book to fight for you. A kind's spirit is earned by slaying enough of it: every kind has a tier, 1 to 5, and needs 80, 40, 20, 10 or 5 kills by tier. Claim, rank up and place spirits at the Chest of Light; a spirit takes as many of the book's slots as its tier.",
            Kind.UTILITY),
    RADIANT_PULSE(
            "Radiant Pulse",
            "minecraft:glowstone_dust",
            30,
            600,
            0,
            1,
            true,
            "A sphere of light ripples outward from the book to 24 blocks. Open paths stay lit for 30 seconds. No damage.",
            Kind.UTILITY),
    // A line of light running to treasure (owner, 2026-11-16).
    TREASURE_THREAD(
            "Treasure Thread",
            "minecraft:gold_nugget",
            100,
            2400,
            0,
            3,
            true,
            "A line of light runs from the book to the nearest unopened chest within 60 blocks, and the chest shows"
                    + " through stone, like ore under Ore Sight, for a minute.",
            Kind.UTILITY),
    // Owner, 2026-11-18: ride your staff like a broom, 15 mana a second.
    FLIGHT(
            "Flight",
            "minecraft:breeze_rod",
            15,
            60,
            0,
            5,
            true,
            "Ride your staff through the air: it flies where you look, Space rises, Shift gets off. Costs 15 mana a"
                    + " second aloft; when the mana runs dry you drift down slowly. Cast again to land.",
            Kind.UTILITY),
    // Owner, 2026-11-18: one monster in a visible cage for 20 seconds; 20 mana, 20 second cooldown.
    CAGE(
            "Cage",
            "minecraft:iron_bars",
            20,
            400,
            0,
            3,
            true,
            "Shuts the monster or animal you look at (within 16 blocks) in a cage of iron bars for 20 seconds: it cannot move,"
                    + " turn, strike or be hurt. Can't be cast on bosses.",
            Kind.DEFENCE),
    // Taunt (owner, 2026-11-18): a totem of light that draws every nearby monster onto itself.
    TAUNT(
            "Taunt",
            "minecraft:bell",
            30,
            300,
            0,
            3,
            true,
            "Raises a chiming totem of light a few blocks ahead for 10 seconds: every monster within 12 blocks turns to"
                    + " attack it. It has your health and armour. Strike a taunted monster and it comes for you"
                    + " instead. No effect on bosses.",
            Kind.DEFENCE),
    // The third shelf (owner, 2026-11-21: "add 10 new spells"). Sanctuary took the
    // place of a planned Veil, which only copied Nyx's Eclipse.
    SUNBEAM(
            "Sunbeam",
            "minecraft:glowstone",
            4,
            4,
            2.4,
            3,
            true,
            "Hold to pour a beam of sunlight from the book: it pierces every creature to the first wall, and the undead"
                    + " take double.",
            Kind.ATTACK),
    WISPS(
            "Wisps",
            "minecraft:glow_berries",
            30, // owner, 2026-11-23: wisps that keep attacking cost a bit more and come back every 16 s
            320,
            1,
            2,
            true,
            "Five motes of light leave the book for 10 seconds. Each hunts the nearest monster within 16 blocks and"
                    + " strikes it once a second until it fades.",
            Kind.ATTACK),
    HALO_DISC(
            "Halo Disc",
            "minecraft:gold_ingot",
            12,
            30,
            6,
            2,
            true,
            "Throws a spinning blade of light 14 blocks that comes back to you, cutting every creature both ways.",
            Kind.ATTACK),
    STARFALL(
            "Starfall",
            "minecraft:nether_star",
            40,
            200,
            7,
            4,
            true,
            "For 6 seconds small stars fall over the place you aim at (up to 32 blocks away), each bursting where it"
                    + " lands.",
            Kind.ATTACK),
    STONE_WALL(
            "Stone Wall",
            "minecraft:cobblestone_wall",
            10,
            200,
            0,
            2,
            true,
            "A wall of stone 8 wide and 3 high rises across your gaze where you look, and crumbles after 10 seconds.",
            Kind.DEFENCE),
    SANCTUARY(
            "Ward",
            "minecraft:beacon",
            35,
            2400,
            0,
            3,
            true,
            "A ward of light 5 blocks round you for 20 seconds: monsters are thrust out and cannot step in, and"
                    + " their shots stop at its edge.",
            Kind.DEFENCE),
    LIGHT_BRIDGE(
            "Light Bridge",
            "minecraft:end_rod",
            20,
            100,
            0,
            2,
            true,
            "A path of light runs 24 blocks from your feet along your gaze, over gaps, water, lava and goo, and fades"
                    + " after 30 seconds.",
            Kind.UTILITY),
    OWL_SIGHT(
            "Owl Sight",
            "minecraft:ender_eye",
            30,
            3200,
            0,
            1,
            true,
            "You see in the dark, and every monster within 32 blocks glows through walls for you, for 30 seconds.",
            Kind.UTILITY),
    SPIRIT_STEED(
            "Light Steed",
            "minecraft:saddle",
            100,
            4800,
            0,
            3,
            true,
            "A horse of light to ride for 2 minutes: swift, tireless, never hurt by a fall, and it lights the way like"
                    + " a piece of Light gear. It fades the moment you leave it.",
            Kind.UTILITY),
    // The fourth shelf (owner, 2026-11-21: "build another 10 spells").
    TETHER(
            "Tether",
            "minecraft:tripwire_hook",
            15,
            60,
            0,
            2,
            true,
            "A line of light to the block you look at, up to 24 blocks away, pulls you to it.",
            Kind.UTILITY),
    WAYGLOW(
            "Wayglow",
            "minecraft:glow_ink_sac",
            4,
            10,
            0,
            1,
            true,
            "Throws a mote of light up to 32 blocks: it stays where it lands, or where its flight ends, and lights the"
                    + " place like a torch, for good. A touch takes it down.",
            Kind.MINING),
    THORNFIELD(
            "Thornfield",
            "minecraft:sweet_berries",
            30,
            200,
            1,
            2,
            true,
            "Roots tear up out of the ground where you aim, in a patch 4 blocks round and half as long again away from"
                    + " you, for 8 seconds: monsters in them are slowed and cut every second.",
            Kind.ATTACK),
    LIGHTBRAND(
            "Lightbrand",
            "minecraft:golden_sword",
            25,
            3600,
            3,
            2,
            true,
            "For a minute every weapon you hold shines like the Light Sword, lighting the dark round you, and every"
                    + " blow you strike deals 3 more, as light, and sets what it hits alight.",
            Kind.ATTACK),
    BALL_LIGHTNING(
            "Ball Lightning",
            "minecraft:copper_bulb",
            30,
            200,
            3,
            3,
            true,
            "A crackling orb drifts slowly along your aim for 6 seconds, zapping the three monsters nearest it (within"
                    + " 5 blocks) every half second.",
            Kind.ATTACK),
    BLADE_RING(
            "Blade Ring",
            "minecraft:iron_sword",
            30,
            300,
            4,
            3,
            true,
            "Three blades of light circle you for 10 seconds, cutting every monster that comes within 2.5 blocks.",
            Kind.ATTACK),
    REWIND(
            "Rewind",
            "minecraft:clock",
            30,
            1200,
            0,
            3,
            true,
            "Marks the moment: 10 seconds later (it counts down) you are pulled back to where you stood, with the"
                    + " health you had then, if it was more. A blow that would kill you meanwhile pulls you back at once.",
            Kind.DEFENCE),
    TWINCAST(
            "Twincast",
            "minecraft:amethyst_cluster",
            30,
            0,
            0,
            4,
            true,
            "The next spell you cast within 5 seconds is cast a second time a moment later, for free. Spells that"
                    + " last or call one thing (a steed, a sight, a brand) are not twinned.",
            Kind.UTILITY),
    // The fifth and last shelf (owner, 2026-11-22: "add 10 more spells, this will be the final 10").
    CYCLONE(
            "Cyclone",
            "minecraft:wind_charge",
            30,
            240,
            3,
            3,
            true,
            "A whirlwind spins away along the ground where you look for 4 seconds, catching monsters, lifting and"
                    + " cutting them, then flinging them out.",
            Kind.ATTACK),
    RUNE_SNARE(
            "Trap",
            "minecraft:tripwire_hook",
            15,
            60,
            8,
            2,
            true,
            "Sets a rune trap on the ground you look at. When a monster steps on it, it bursts: every monster near it"
                    + " is struck, thrown up, blinded and dazed. Up to 3 traps wait until something sets them off.",
            Kind.ATTACK),
    DAWNBREAK(
            "Dawnbreak",
            "minecraft:sunflower",
            80,
            1200,
            14,
            5,
            true,
            "You gather the light for a moment, then a sun bursts from you: every monster within 16 blocks is struck"
                    + " hard and thrown back, and the undead burn.",
            Kind.ATTACK),
    EMBERWALK(
            "Emberwalk",
            "minecraft:magma_block",
            30,
            3600,
            0,
            3,
            true,
            "For a minute you cannot burn, monsters that strike you catch fire, and lava hardens to a glowing crust"
                    + " under your feet. It melts back once you have passed.",
            Kind.DEFENCE),
    CRYSTAL_SHELL(
            "Crystal Shell",
            "minecraft:amethyst_block",
            35,
            1800,
            0,
            3,
            true,
            "Seals you in crystal for 10 seconds: nothing can hurt you, you mend fast, and harm and fire leave you."
                    + " When it shatters, monsters within 4 blocks are thrown far back.",
            Kind.DEFENCE),
    TRANSPOSE(
            "Transpose",
            "minecraft:ender_pearl",
            15,
            100,
            0,
            2,
            true,
            "You and the creature you look at, up to 32 blocks away, trade places. It does nothing to bosses.",
            Kind.UTILITY),
    MOONGATE(
            "Moongate",
            "minecraft:crying_obsidian",
            100,
            900,
            0,
            2,
            true,
            "Opens a portal just ahead of you and another where you look, up to 50 blocks away. For a minute, players,"
                    + " animals and items that walk into one walk on out of the other. Monsters cannot pass.",
            Kind.UTILITY),
    QUICKENING(
            "Quickening",
            "minecraft:wheat",
            100,
            1200,
            0,
            1,
            true,
            "Every baby animal and villager within 12 blocks grows up at once.",
            Kind.FARMING),
    LIGHT_BORE(
            "Light Bore",
            "minecraft:iron_pickaxe",
            100,
            200,
            0,
            3, // Rare: Uncommon is capped at 20 spells (owner, 2026-11-22), and it was "a bit too op"
            true,
            "A beam bores a tunnel 2 high and 8 long straight ahead, level with your feet, as an iron pickaxe"
                    + " would. What it digs lands at your feet.",
            Kind.MINING),
    // The sixth shelf (owner, 2026-11-22: "create 6 legendary spells and 7 mythic spells"; reworked the same day).
    SENTINEL(
            "Sentinel",
            "minecraft:blue_ice",
            50,
            1200,
            5,
            4,
            true,
            "A great crystal of ice rises from the ground where you look, up to 24 blocks away, for 30 seconds. Every"
                    + " second it shoots an ice shard at the nearest monster it can see within 16 blocks, which"
                    + " chills it: slowed, and taking 30% more harm from everything else.",
            Kind.ATTACK),
    BLIZZARD(
            "Blizzard",
            "minecraft:powder_snow_bucket",
            50,
            500,
            2,
            4,
            true,
            "A blizzard rages for 8 seconds where you look, up to 32 blocks away, 6 blocks round. Monsters in it are"
                    + " slowed hard, frozen through and bitten every half second.",
            Kind.ATTACK),
    WELLSPRING(
            "Mana Tempest",
            "minecraft:feather",
            0,
            4800,
            0,
            4,
            true,
            "For 18 seconds your spells cost no mana.",
            Kind.UTILITY),
    BANISH(
            "Smite",
            "minecraft:end_rod",
            60,
            1400, // owner, 2026-11-23: 70 s, and a fifth harder
            48,
            4,
            true,
            "A pillar of light strikes the monster you look at, up to 32 blocks away, for a huge blow. Only that one"
                    + " monster is struck.",
            Kind.ATTACK),
    SOUL_LINK(
            "Soul Link",
            "minecraft:iron_chain",
            40,
            600,
            4,
            4,
            true,
            "Chains of light bind the monster you look at, up to 24 blocks away, to the 5 monsters nearest it within"
                    + " 8 blocks, for 10 seconds. Harm you do to one of them is shared with the rest, at 60%.",
            Kind.ATTACK),
    TIME_STOP(
            "Time Stop",
            "minecraft:clock",
            100,
            3600,
            0,
            5,
            true,
            "Time stands still round you for 10 seconds: every monster within 24 blocks, and every shot in the air,"
                    + " hangs where it is. Bosses are only slowed.",
            Kind.DEFENCE),
    TEMPEST(
            "Tempest",
            "minecraft:lightning_rod",
            100,
            2400,
            9,
            5,
            true,
            "A storm gathers over you for 12 seconds: every second lightning strikes the 3 monsters nearest you"
                    + " within 20 blocks and sets them alight.",
            Kind.ATTACK),
    COLOSSUS(
            "Giant Golem",
            "minecraft:iron_block",
            100,
            4800,
            12,
            5,
            true,
            "A giant golem of light rises beside you and fights for you for 25 seconds, slamming the ground among"
                    + " monsters. It takes room in your command.",
            Kind.ATTACK),
    ASCENSION(
            "Gigantify",
            "minecraft:nether_star",
            100,
            4800,
            0,
            5,
            true,
            "For 30 seconds you stand three times as tall: you reach and step far, strike twice as hard, take less"
                    + " harm, and every blow you land shakes the monsters round your target. Grows only as far as the"
                    + " space round you allows.",
            Kind.UTILITY),
    RENEWAL(
            "Golden Apple",
            "minecraft:golden_apple",
            100,
            12000,
            0,
            5,
            true,
            "You gain what eating a golden apple gives: Regeneration II for 5 seconds and Absorption for 2 minutes.",
            Kind.DEFENCE),
    TRANSMUTE(
            "Transmute",
            "minecraft:coal",
            100,
            3600,
            0,
            5,
            true,
            "Every solid block within 10 blocks of you turns to coal ore (not ores, chests and the like, or anything"
                    + " inside a ward of light). Its upgrades add other ores to the mix.",
            Kind.MINING),
    // The seventh shelf (owner, 2026-11-22: "5 more legendary spells ... transformations into beasts / monsters /
    // spirits", Shrinking at tier 1, and 11 more Basic spells).
    WOLF_FORM(
            "Wolf Form",
            "minecraft:bone",
            100, // every transformation costs 100 (owner, 2026-11-23)
            2400,
            6,
            4,
            true,
            "For 30 seconds you become a great wolf: you run fast and leap high, your bites strike half again as hard,"
                    + " and right-click pounces on the monster you look at, up to 12 blocks away.",
            Kind.ATTACK),
    BEAR_FORM(
            "Bear Form",
            "minecraft:leather",
            100,
            3600,
            6,
            4,
            true,
            "For 30 seconds you become a great bear: 10 more hearts, Resistance, no knockback, blows twice as hard, and"
                    + " right-click roars, striking and throwing back every monster within 5 blocks.",
            Kind.ATTACK),
    SPIDER_FORM(
            "Spider Form",
            "minecraft:spider_eye",
            100,
            2400,
            0,
            4,
            true,
            "For 45 seconds you become a spider: you climb any wall, see in the dark, take no fall damage, your bites"
                    + " poison, and right-click shoots a web that pins the monster you look at, up to 24 blocks away, for as"
                    + " long as you stay a spider.",
            Kind.UTILITY),
    BLAZE_FORM(
            "Blaze Form",
            "minecraft:blaze_rod",
            100,
            3600,
            5,
            4,
            true,
            "For 30 seconds you become a blaze: fire cannot hurt you, you drift down slowly and hold jump to rise, and"
                    + " right-click throws three fireballs where you look.",
            Kind.ATTACK),
    SPIRIT_WALK(
            "Spirit Walk",
            "minecraft:phantom_membrane",
            100,
            1200,
            0,
            1, // Basic (owner, 2026-11-23)
            true,
            "For 30 seconds your spirit flies through walls with Night Vision while your body waits. Nothing can touch"
                    + " your spirit. Hold right-click to zoom. Press Control to return early.",
            Kind.UTILITY),
    SHRINKING(
            "Shrinking",
            "minecraft:red_mushroom",
            100,
            1200,
            0,
            1,
            true,
            "For 30 seconds you are six times smaller: you fit through the smallest gaps and are hard to hit. You grow"
                    + " back only where there is room.",
            Kind.UTILITY),
    GILLS(
            "Gills",
            "minecraft:tropical_fish",
            10,
            3000,
            0,
            1,
            true,
            "For 2 minutes you breathe, see and dig under water as on land, and swim fast.",
            Kind.UTILITY),
    PURIFY(
            "Purify",
            "minecraft:milk_bucket",
            15,
            600,
            0,
            1,
            true,
            "Every harmful effect on you is lifted, fire and frost leave you, and no harmful effect can take hold"
                    + " for 8 seconds.",
            Kind.DEFENCE),
    ENDER_POCKET(
            "Ender Pocket",
            "minecraft:ender_chest",
            100,
            0, // no cooldown (owner, 2026-11-23)
            0,
            1,
            true,
            "Opens your ender chest, wherever you are.",
            Kind.UTILITY),
    WORKBENCH(
            "Workbench",
            "minecraft:crafting_table",
            100,
            40,
            0,
            1,
            true,
            "Opens a crafting table, wherever you are. Its upgrades add other stations (one in each tier), and the"
                    + " arrows or the left and right keys switch between them.",
            Kind.UTILITY),
    ENFEEBLE(
            "Weaken",
            "minecraft:fermented_spider_eye",
            15,
            500, // owner, 2026-11-23: 25 s
            0,
            1,
            true,
            "The monster you look at, up to 24 blocks away, deals half its harm and moves slower for 10 seconds.",
            Kind.DEFENCE),
    SPLINTER(
            "Splinter",
            "minecraft:prismarine_shard",
            10,
            30,
            3,
            1,
            true,
            "A fan of five splinters of light flies where you look, each striking the first creature in its way.",
            Kind.ATTACK),
    BOULDER(
            "Boulder",
            "minecraft:cobblestone",
            15,
            120, // owner, 2026-11-23: 6 s
            9,
            1,
            true,
            "You hurl a heavy boulder that falls in a steep arc and smashes every monster within 2 blocks of where it lands,"
                    + " throwing them back.",
            Kind.ATTACK),
    FROSTWALK(
            "Frostwalk",
            "minecraft:packed_ice",
            100,
            1200,
            0,
            1,
            true,
            "For 60 seconds water freezes under your feet as you walk, 5 blocks round you and ahead of you, even"
                    + " as you jump, and melts behind you.",
            Kind.UTILITY),
    FRIGHT(
            "Fright",
            "minecraft:goat_horn",
            40,
            800, // owner, 2026-11-23: 40 s
            0,
            1,
            true,
            "Every monster within 7 blocks runs from you for 3 seconds. No effect on bosses.",
            Kind.DEFENCE),
    // In place of The Dragon (owner, 2026-11-23: "remove dragon spell. add another form instead").
    WARDEN_FORM(
            "Warden Form",
            "minecraft:echo_shard",
            100,
            6000,
            20,
            5,
            true,
            "For 30 seconds you become a warden: 20 more hearts, Resistance, no knockback, blows twice as hard, and"
                    + " right-click lets out a sonic boom that tears through every monster in a line 16 blocks long.",
            Kind.ATTACK),

    // The eighth shelf (owner, 2026-11-23: five Legendary spells; he deleted Bounty and Exile the same day).
    COMET_DASH(
            "Comet Dash",
            "minecraft:firework_rocket",
            40,
            300,
            10,
            4,
            true,
            "You streak 14 blocks along your gaze as a comet, striking every monster you pass and throwing it aside."
                    + " No fall harm for a moment after.",
            Kind.ATTACK),
    DOOM(
            "Doom",
            "minecraft:wither_skeleton_skull",
            40,
            900,
            0,
            4,
            true,
            "Sets a skull of doom over the creature you look at for 15 seconds: it takes 35% more harm from"
                    + " everything and suffers one curse drawn at random. Each upgrade adds a curse it always suffers.",
            Kind.DEFENCE),
    LIFEBLOOM(
            "Lifebloom",
            "minecraft:spore_blossom",
            50,
            900,
            0,
            4,
            true,
            "A flowering tree of light grows where you look for 20 seconds, its ring of reach glowing on the ground."
                    + " Every 2 seconds it heals players, pets and villagers inside by 2 and lifts one harmful effect"
                    + " from each.",
            Kind.DEFENCE);

    /** What a spell is for; only ATTACK spells deal damage. */
    public enum Kind {
        ATTACK,
        DEFENCE,
        FARMING,
        MINING,
        UTILITY
    }

    public final String label;
    public final String icon;
    public final int mana;
    public final int cooldownTicks;
    public final double baseDamage;
    /**
     * Power tier, 1 to 5 (owner, 2026-11-18): how strong the spell is in play, not only its damage (Flight is 5
     * because it does what the End's wings do). The tier sets everything about getting the tome: its drop weight,
     * the weakest monster that may drop it (a tier-N monster drops tiers 1 to N only, see
     * {@link TomeItem#roll(net.minecraft.util.RandomSource, int)}), and its shop band.
     */
    public final int tier;
    /** The rarity this spell's tier is shown as: tier 1 Basic up to tier 5 Mythic (owner, 2026-11-22). */
    public LootRoller.Rarity rarity() {
        return LootRoller.Rarity.ofTier(tier);
    }

    /** False for a tome that is only given: it never drops and is never sold (none today; Eclipse dropped since 2026-11-22). */
    public final boolean drops;
    /**
     * Which shop band sells this tome, from its tier: the daily shop offers tomes whose band has opened by the world's
     * age (1, 5, 10, 15, 20).
     */
    public final int shopTier;
    /** Relative weight among tome drops, from its tier; 0 for a tome that never drops. */
    public final int dropWeight;

    /** Held in a nested class: an enum's own static fields are not set yet while its constants are built. */
    private static final class Tiers {
        /** Drop weight of one spell by tier: each tier rarer than the one below. */
        static final int[] DROP = {0, 12, 8, 5, 3, 2};
        /** Shop band by tier: week 1 sells tier 1, week 2 tier 2, week 3 tier 3, week 4 tiers 4 and 5. */
        static final int[] SHOP = {0, 1, 5, 10, 15, 20};
    }

    /**
     * What every tome recipe of a tier pays besides the book and the spell's own theme items (owner, 2026-11-18: a
     * recipe fills all nine slots and its cost follows the tier). Item ids; {@code tools/make_tome_recipes.py} writes
     * the recipes from the same table and the self-test holds them to it.
     */
    public static List<String> recipePrice(int tier) {
        return switch (tier) {
            case 1 -> List.of("lapis_lazuli", "lapis_lazuli");
            case 2 -> List.of("lapis_lazuli", "lapis_lazuli", "lapis_lazuli", "iron_ingot", "iron_ingot");
            case 3 -> List.of("lapis_lazuli", "lapis_lazuli", "gold_ingot", "gold_ingot", "diamond");
            case 4 -> List.of("lapis_block", "gold_ingot", "diamond", "diamond");
            default -> List.of("lapis_block", "netherite_scrap", "diamond", "diamond");
        };
    }

    public final String description;
    public final Kind kind;

    Spell(
            String label,
            String icon,
            int mana,
            int cooldownTicks,
            double baseDamage,
            int tier,
            boolean drops,
            String description,
            Kind kind) {
        this.label = label;
        this.icon = icon;
        this.mana = mana;
        this.cooldownTicks = cooldownTicks;
        this.baseDamage = baseDamage;
        this.tier = tier;
        this.drops = drops;
        this.shopTier = Tiers.SHOP[tier];
        this.dropWeight = drops ? Tiers.DROP[tier] : 0;
        this.description = description;
        this.kind = kind;
    }

    /** Registry path of its tome, e.g. tome_arcane_bolt. */
    public String tomeId() {
        return "tome_" + name().toLowerCase();
    }

    public static Spell byId(int id) {
        Spell[] all = values();
        return id >= 0 && id < all.length ? all[id] : null;
    }
}
