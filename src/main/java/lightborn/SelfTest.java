package lightborn;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Headless end-to-end test (run with -Dlightborn.selftest=true): the spawn hut on a fresh world, loot,
 * tree, levels, monsters, bosses, loot beams and all six spells cast by a fake player at real monsters
 * on a test platform high above spawn.
 * Prints one line "LIGHTBORN SELFTEST PASS|FAIL ..." with every check, then stops the server.
 */
final class SelfTest {
    SelfTest() {}

    private static final Map<String, String> RESULTS = new LinkedHashMap<>();
    static final List<Husk> TARGETS = new ArrayList<>();
    /** Passive animals beside the caster: area spells (frost nova) must hurt them too. */
    static final List<Mob> ANIMALS = new ArrayList<>();

    static FakePlayer caster;
    static ServerLevel level;
    static BlockPos base;
    static int castOk;

    static void check(String name, boolean ok, Object detail) {
        RESULTS.put(name, (ok ? "ok" : "FAIL") + (detail == null ? "" : "(" + detail + ")"));
        if (!ok) Lightborn.LOG.error("SELFTEST check failed: {} {}", name, detail);
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        try {
            SelfTestWorld.testHut(); // first: the world must still be untouched, before the arena goes up over spawn
            testLoot();
            testTree();
            testItems();
            setupArena();
            SelfTestSpells.withTestArena(
                    base.offset(-16, 0, -16),
                    base.offset(32, 0, 24),
                    "initial_arena_ready",
                    SelfTest::testInitialArena,
                    () -> finish(server));
        } catch (RuntimeException e) {
            Lightborn.LOG.error("SELFTEST crashed", e);
            check("no_crash", false, e.toString());
            finish(server);
        }
    }

    private static void testInitialArena(Runnable done) {
        try {
            testPlayer();
            SelfTestProgression.testExperience();
            SelfTestProgression.testEconomy();
            SelfTestMonsters.testMonsters();
            SelfTestSpells.castAll();
        } catch (RuntimeException e) {
            Lightborn.LOG.error("SELFTEST crashed", e);
            check("no_crash", false, e.toString());
            done.run();
            return;
        }
        // Projectiles and the meteor need game ticks to land.
        Scheduler.later(45, () -> {
            try {
                afterSpells(done);
            } catch (RuntimeException e) {
                Lightborn.LOG.error("SELFTEST crashed", e);
                check("no_crash", false, e.toString());
                done.run();
            }
        });
    }

    /** Adds a sword's rolled Attack Damage bonus (folded into its base line), when it has one, to {sum, count}. */
    private static void damageAffix(ItemStack stack, double[] into) {
        double plain = 0, rolled = 0;
        for (var e : new ItemStack(stack.getItem())
                .getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .modifiers())
            if (e.attribute().equals(Attributes.MAX_HEALTH))
                plain += e.modifier().amount();
        for (var e : stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .modifiers())
            if (e.attribute().equals(Attributes.MAX_HEALTH))
                rolled += e.modifier().amount();
        if (rolled > plain) {
            into[0] += rolled - plain;
            into[1]++;
        }
    }

    private static void testLoot() {
        int bad = 0, legendary = 0, rare = 0, tierBad = 0;
        final int n = 3000;
        for (int i = 0; i < n; i++) {
            ModItems.Tier tier = ModItems.Tier.values()[i % 3];
            LootRoller.Rarity r = LootRoller.rollRarity(level.getRandom(), 1.0);
            if (r.top()) legendary++; // Legendary and Mythic, 5% between them
            if (r == LootRoller.Rarity.RARE) rare++;
            ItemStack stack = LootRoller.roll(level.getRandom(), tier, r);
            var plain = new ItemStack(stack.getItem())
                    .getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                    .modifiers();
            ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            // Every affix is a line of its own (owner, 2026-11-18): it never folds into one of the item's own stats,
            // so a Rare shows exactly two new lines and a Legendary three. Only a staff's rarity mana joins its Max
            // Mana line, and it is not an affix.
            int rarityMana = ModItems.isStaff(stack) ? LootRoller.rarityMana(ModItems.Kind.STAFF, r) : 0;
            int affixes = mods == null ? -1 : 0;
            boolean folded = false;
            double rarityPart = 0; // the rarity's mana: its own line, or what it added to the staff's Max Mana line
            if (mods != null)
                for (var e : mods.modifiers()) {
                    if (e.modifier().id().getPath().startsWith("rarity_max_mana")) {
                        rarityPart += e.modifier().amount();
                        continue;
                    }
                    var own = plain.stream()
                            .filter(b -> b.attribute().equals(e.attribute())
                                    && b.modifier().id().equals(e.modifier().id()))
                            .findFirst()
                            .orElse(null);
                    if (own == null) affixes++;
                    else if (e.attribute().equals(ModAttributes.MAX_MANA))
                        rarityPart += e.modifier().amount() - own.modifier().amount();
                    else if (Math.abs(e.modifier().amount() - own.modifier().amount()) > 1e-6) folded = true;
                }
            boolean manaOk = Math.abs(rarityPart - rarityMana) < 1e-6;
            Set<String> ids = new HashSet<>();
            boolean dupes = false;
            if (mods != null)
                for (var e : mods.modifiers())
                    if (!ids.add(e.attribute().getRegisteredName() + "|"
                            + e.modifier().id())) dupes = true;
            String shown = stack.getHoverName().getString();
            boolean ok = stack.has(DataComponents.CUSTOM_NAME)
                    && shown.startsWith(r.label + " ")
                    && !shown.contains("Item Level")
                    && stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY)
                            .lines()
                            .isEmpty() // every stack carries an empty default lore
                    && !dupes
                    && !folded
                    && manaOk
                    && affixes >= r.minAffixes
                    && affixes <= r.maxAffixes;
            if (!ok && bad++ < 3)
                Lightborn.LOG.error(
                        "Bad roll {} {} affixes={} dupes={} folded={} manaOk={}",
                        stack,
                        r,
                        affixes,
                        dupes,
                        folded,
                        manaOk);
            ModItems.Tier t = ModItems.tierOf(stack.getItem());
            if (t != tier) tierBad++;
        }
        double leg = 100.0 * legendary / n, rar = 100.0 * rare / n;
        check("loot_rolls", bad == 0, bad);
        check("loot_tiers", tierBad == 0, tierBad);
        // Ore mined decides which materials can drop (owner, 2026-11-16); the monster's tier leans the odds
        // (2026-11-20).
        var miner = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "OreMiner"));
        boolean noneYet = OreTiers.pick(level.getRandom(), miner, OreTiers.BOSS) == null;
        OreTiers.mined(miner, Blocks.IRON_ORE.defaultBlockState());
        // A boss before the first diamond ore drops the best unlocked tier (iron), never nothing.
        boolean bossFallsBack = OreTiers.pick(level.getRandom(), miner, OreTiers.BOSS) == ModItems.Tier.IRON;
        OreTiers.mined(miner, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
        int weakDiamond = 0, strongDiamond = 0, mightyNetherite = 0;
        for (int i = 0; i < 400; i++) {
            if (OreTiers.pick(level.getRandom(), miner, 1) != ModItems.Tier.IRON) weakDiamond++;
            if (OreTiers.pick(level.getRandom(), miner, 2) == ModItems.Tier.DIAMOND) strongDiamond++;
            if (OreTiers.pick(level.getRandom(), miner, 3) == ModItems.Tier.NETHERITE) mightyNetherite++;
        }
        OreTiers.mined(miner, Blocks.ANCIENT_DEBRIS.defaultBlockState());
        int bossNetherite = 0;
        for (int i = 0; i < 400; i++)
            if (OreTiers.pick(level.getRandom(), miner, OreTiers.BOSS) == ModItems.Tier.NETHERITE) bossNetherite++;
        check(
                "loot_tier_by_ore_and_strength",
                noneYet
                        && bossFallsBack
                        && weakDiamond == 0
                        && strongDiamond > 50
                        && strongDiamond < 110
                        && mightyNetherite == 0
                        && bossNetherite > 240
                        && bossNetherite < 320,
                "none=" + noneYet + " bossIronOnly=" + bossFallsBack + " weak!iron=" + weakDiamond + " strongDiamond="
                        + strongDiamond + " mightyNetherite(locked)=" + mightyNetherite + " bossNetherite="
                        + bossNetherite);
        check(
                "loot_rarity",
                leg > 2.8 && leg < 7.5 && rar > 21 && rar < 29,
                String.format("leg=%.1f%% rare=%.1f%%", leg, rar));
        ItemStack high = LootRoller.roll(level.getRandom(), ModItems.Tier.NETHERITE, LootRoller.Rarity.LEGENDARY);
        check("loot_glint", high.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE), null);
        // Owner, 2026-11-16: a staff showed "Spell Power" twice. A rolled stat the item has joins its own line.
        int doubled = 0;
        for (int i = 0; i < 300; i++) {
            ItemStack st = LootRoller.roll(
                    level.getRandom(), LootRoller.Rarity.LEGENDARY, ModItems.Tier.NETHERITE, ModItems.Kind.STAFF);
            Set<String> lines = new HashSet<>();
            for (var e : st.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                    .modifiers())
                if (!lines.add(e.attribute().getRegisteredName() + "|" + e.slot() + "|"
                        + e.modifier().operation())) doubled++;
        }
        check("loot_one_line_per_stat", doubled == 0, doubled);
        // Rarity alone sets the strength. Compare the Max Health suffix on iron and netherite helmets.
        double[] low = new double[2], highSum = new double[2];
        for (int i = 0; i < 600; i++) {
            damageAffix(
                    LootRoller.roll(
                            level.getRandom(), LootRoller.Rarity.RARE, ModItems.Tier.IRON, ModItems.Kind.HELMET),
                    low);
            damageAffix(
                    LootRoller.roll(
                            level.getRandom(), LootRoller.Rarity.RARE, ModItems.Tier.NETHERITE, ModItems.Kind.HELMET),
                    highSum);
        }
        double lowMean = low[0] / Math.max(1, low[1]), highMean = highSum[0] / Math.max(1, highSum[1]);
        check(
                "loot_power_from_rarity_only",
                low[1] > 0 && Math.abs(lowMean - highMean) / lowMean < .12,
                String.format("iron=%.2f netherite=%.2f", lowMean, highMean));
        // Specialist staffs: about 3 netherite staff drops in 10 are the Storm Rod, with its signature stat.
        int storms = 0;
        for (int i = 0; i < 400; i++) {
            ItemStack st = LootRoller.roll(
                    level.getRandom(), LootRoller.Rarity.UNCOMMON, ModItems.Tier.NETHERITE, ModItems.Kind.STAFF);
            if (st.is(ModItems.SPECIALIST_OF.get(ModItems.Tier.NETHERITE))) storms++;
        }
        Item storm = ModItems.SPECIALIST_OF.get(ModItems.Tier.NETHERITE);
        boolean signature = new ItemStack(storm)
                .getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers().stream()
                        .anyMatch(e -> e.attribute().equals(ModAttributes.COOLDOWN_SPEED));
        check(
                "loot_specialist_staffs",
                storms > 80
                        && storms < 160
                        && signature
                        && ModItems.isStaff(new ItemStack(storm))
                        && ModItems.tierOf(storm) == ModItems.Tier.NETHERITE
                        && ModItems.SPECIALISTS.size() == 3,
                storms + "/400");
    }

    /**
     * The upgrade table: three tiers of three for every spell, and every upgrade changes a number that spell reads
     * (power only where it has power, SPECIAL only where it has a signature trick, its own stats where it uses them).
     */
    private static void testTree() {
        var generic = EnumSet.of(SpellUpgrades.Stat.COST, SpellUpgrades.Stat.COOLDOWN);
        var own = new EnumMap<Spell, Set<SpellUpgrades.Stat>>(Spell.class);
        own.put(Spell.ARCANE_BOLT, EnumSet.of(SpellUpgrades.Stat.COUNT, SpellUpgrades.Stat.REACH));
        own.put(Spell.FIREBALL, EnumSet.of(SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.DURATION));
        own.put(Spell.FROST_NOVA, EnumSet.of(SpellUpgrades.Stat.AREA));
        own.put(Spell.CHAIN_LIGHTNING, EnumSet.of(SpellUpgrades.Stat.COUNT));
        own.put(Spell.METEOR, EnumSet.of(SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.DURATION));
        own.put(Spell.MENDING_LIGHT, EnumSet.of(SpellUpgrades.Stat.AREA));
        own.put(Spell.FELLING, EnumSet.of(SpellUpgrades.Stat.REACH));
        own.put(Spell.HERD_CALL, EnumSet.of(SpellUpgrades.Stat.AREA));
        own.put(Spell.SKIES, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.UPDRAFT, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.BLINK, EnumSet.of(SpellUpgrades.Stat.REACH));
        own.put(Spell.STONESKIN, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.VERDANT_SURGE, EnumSet.of(SpellUpgrades.Stat.AREA));
        own.put(Spell.REAP, EnumSet.of(SpellUpgrades.Stat.AREA));
        own.put(Spell.EXCAVATE, EnumSet.of(SpellUpgrades.Stat.REACH));
        own.put(Spell.ORE_SIGHT, EnumSet.of(SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.DURATION));
        own.put(Spell.FORGE_FIRE, EnumSet.of(SpellUpgrades.Stat.COUNT));
        own.put(Spell.GATHERING, EnumSet.of(SpellUpgrades.Stat.AREA));
        own.put(Spell.HOMEWARD, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.GRAVITY_WELL, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.SIPHON, EnumSet.of(SpellUpgrades.Stat.COUNT));
        own.put(Spell.CONTAGION, EnumSet.of(SpellUpgrades.Stat.REACH));
        own.put(Spell.MIRROR_WARD, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.ECLIPSE, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.RADIANT_PULSE, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.TREASURE_THREAD, EnumSet.of(SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.DURATION));
        own.put(Spell.FLIGHT, EnumSet.of(SpellUpgrades.Stat.REACH));
        own.put(
                Spell.CAGE,
                EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.REACH, SpellUpgrades.Stat.COUNT));
        own.put(Spell.TAUNT, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA));
        own.put(Spell.SUNBEAM, EnumSet.of(SpellUpgrades.Stat.REACH, SpellUpgrades.Stat.AREA));
        own.put(Spell.WISPS, EnumSet.of(SpellUpgrades.Stat.COUNT, SpellUpgrades.Stat.DURATION));
        own.put(Spell.HALO_DISC, EnumSet.of(SpellUpgrades.Stat.REACH, SpellUpgrades.Stat.AREA));
        own.put(Spell.STARFALL, EnumSet.of(SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.DURATION));
        own.put(
                Spell.STONE_WALL,
                EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.REACH));
        own.put(Spell.SANCTUARY, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA));
        own.put(Spell.LIGHT_BRIDGE, EnumSet.of(SpellUpgrades.Stat.REACH, SpellUpgrades.Stat.DURATION));
        own.put(Spell.OWL_SIGHT, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA));
        own.put(Spell.SPIRIT_STEED, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.TETHER, EnumSet.of(SpellUpgrades.Stat.REACH));
        own.put(Spell.WAYGLOW, EnumSet.of(SpellUpgrades.Stat.REACH, SpellUpgrades.Stat.COUNT));
        own.put(Spell.THORNFIELD, EnumSet.of(SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.DURATION));
        own.put(Spell.LIGHTBRAND, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(
                Spell.BALL_LIGHTNING,
                EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.COUNT));
        own.put(
                Spell.BLADE_RING,
                EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.COUNT));
        own.put(Spell.REWIND, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.TWINCAST, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.COUNT));
        own.put(Spell.CYCLONE, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA));
        own.put(
                Spell.RUNE_SNARE,
                EnumSet.of(SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.COUNT, SpellUpgrades.Stat.DURATION));
        own.put(Spell.DAWNBREAK, EnumSet.of(SpellUpgrades.Stat.AREA));
        own.put(Spell.EMBERWALK, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA));
        own.put(Spell.CRYSTAL_SHELL, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA));
        own.put(Spell.TRANSPOSE, EnumSet.of(SpellUpgrades.Stat.REACH));
        own.put(Spell.MOONGATE, EnumSet.of(SpellUpgrades.Stat.REACH, SpellUpgrades.Stat.DURATION));
        own.put(Spell.QUICKENING, EnumSet.of(SpellUpgrades.Stat.AREA));
        own.put(Spell.LIGHT_BORE, EnumSet.of(SpellUpgrades.Stat.REACH));
        own.put(Spell.SENTINEL, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA));
        own.put(Spell.BLIZZARD, EnumSet.of(SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.DURATION));
        own.put(Spell.WELLSPRING, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.BANISH, EnumSet.of(SpellUpgrades.Stat.REACH));
        own.put(
                Spell.SOUL_LINK,
                EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.COUNT));
        own.put(Spell.TIME_STOP, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA));
        own.put(
                Spell.TEMPEST,
                EnumSet.of(SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.COUNT));
        own.put(Spell.COLOSSUS, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.ASCENSION, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.RENEWAL, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.WOLF_FORM, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.REACH));
        own.put(Spell.BEAR_FORM, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA));
        own.put(Spell.SPIDER_FORM, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.REACH));
        own.put(Spell.BLAZE_FORM, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.SPIRIT_WALK, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.SHRINKING, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.GILLS, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.PURIFY, EnumSet.of(SpellUpgrades.Stat.DURATION));
        own.put(Spell.ENFEEBLE, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.REACH));
        own.put(Spell.SPLINTER, EnumSet.of(SpellUpgrades.Stat.COUNT, SpellUpgrades.Stat.REACH));
        own.put(Spell.BOULDER, EnumSet.of(SpellUpgrades.Stat.AREA));
        own.put(Spell.FROSTWALK, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.AREA));
        own.put(Spell.FRIGHT, EnumSet.of(SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.DURATION));
        own.put(Spell.WARDEN_FORM, EnumSet.of(SpellUpgrades.Stat.DURATION, SpellUpgrades.Stat.REACH));
        own.put(Spell.COMET_DASH, EnumSet.of(SpellUpgrades.Stat.REACH));
        own.put(Spell.LIFEBLOOM, EnumSet.of(SpellUpgrades.Stat.AREA, SpellUpgrades.Stat.DURATION));
        for (Spell s : List.of(
                Spell.MENDING_LIGHT,
                Spell.FELLING,
                Spell.HERD_CALL,
                Spell.SKIES,
                Spell.GLACIAL_SPIKE,
                Spell.WATER_SPLASH,
                Spell.EARTHSHATTER,
                Spell.SPIRIT_WOLVES,
                Spell.UPDRAFT,
                Spell.SOULS,
                Spell.FLIGHT,
                Spell.TAUNT,
                Spell.LIGHT_BRIDGE,
                Spell.SPIRIT_STEED,
                Spell.TETHER,
                Spell.TWINCAST))
            own.computeIfAbsent(s, k -> EnumSet.noneOf(SpellUpgrades.Stat.class))
                    .add(SpellUpgrades.Stat.SPECIAL);
        List<String> wrong = new ArrayList<>();
        for (Spell s : Spell.values()) {
            var tiers = SpellUpgrades.tiers(s);
            if (tiers.size() != 3 || tiers.stream().anyMatch(t -> t.size() != 3)) wrong.add(s + " shape");
            Set<String> names = new HashSet<>();
            int specials = 0;
            // Owner, 2026-11-17: never two of one kind in a tier, and tier III is all perks.
            for (int t = 0; t < tiers.size(); t++) {
                var kinds = EnumSet.noneOf(SpellUpgrades.Stat.class);
                for (var up : tiers.get(t)) {
                    if (up.stat() != SpellUpgrades.Stat.PERK && !kinds.add(up.stat()))
                        wrong.add(s + " tier " + (t + 1) + " repeats " + up.stat());
                    if (t == 2
                            && up.stat() != SpellUpgrades.Stat.PERK
                            && !(s == Spell.EMBERWALK && up.stat() == SpellUpgrades.Stat.COOLDOWN))
                        wrong.add(s + " tier III " + up.name());
                }
            }
            // Owner, 2026-11-23: one aspect per tree. A stat raised in tier I is never raised again in tier II.
            if (tiers.size() == 3)
                for (var a : tiers.get(0))
                    for (var b : tiers.get(1))
                        if (a.stat() == b.stat() && a.stat() != SpellUpgrades.Stat.PERK)
                            wrong.add(s + " raises " + a.stat() + " in tier I and tier II");
            for (var tier : tiers)
                for (var up : tier) {
                    if (!names.add(up.name())) wrong.add(s + " twice " + up.name());
                    if (up.amount() == 0) wrong.add(s + " zero " + up.name());
                    boolean fits = generic.contains(up.stat())
                            || up.stat() == SpellUpgrades.Stat.PERK
                            || up.stat() == SpellUpgrades.Stat.POWER && s.baseDamage > 0
                            || own.getOrDefault(s, Set.of()).contains(up.stat());
                    if (!fits) wrong.add(s + " " + up.name() + " " + up.stat());
                    if (up.stat() == SpellUpgrades.Stat.SPECIAL) specials++;
                }
            if (own.getOrDefault(s, Set.of()).contains(SpellUpgrades.Stat.SPECIAL) && specials != 1)
                wrong.add(s + " specials " + specials);
            // Owner, 2026-11-22: a tier I and a tier II upgrade on one stat add up, so neither may read as a fixed
            // total ("48 blocks instead of 24"), which makes the tier I one look wasted beside the tier II one.
            if (tiers.size() == 3)
                for (var a : tiers.get(0))
                    for (var b : tiers.get(1))
                        if (a.stat() == b.stat()
                                && a.stat() != SpellUpgrades.Stat.PERK
                                && a.stat() != SpellUpgrades.Stat.SPECIAL
                                && (a.text().contains("instead of") || b.text().contains("instead of")))
                            wrong.add(s + " " + a.name() + "/" + b.name() + " read as fixed totals");
        }
        check("upgrades_table_fits_each_spell", wrong.isEmpty(), wrong);
        int[] per = new int[6];
        for (Spell s : Spell.values()) per[s.tier]++;
        boolean capped = true;
        for (int t = 1; t <= 5; t++) capped &= per[t] <= (t == 5 ? 10 : 20);
        check("spell_rarity_caps", capped, java.util.Arrays.toString(per));
    }

    private static void testItems() {
        int count = ModItems.MOD_ITEMS.size();
        check(
                "items_registered",
                count
                        == 41
                                + Spell.values().length
                                + ModEntities.EGGS.size() /* + the Spellstone, Dark Shard Ore, Spark Ore */,
                count);
        for (ModItems.Tier t : ModItems.Tier.values()) {
            for (ModItems.Kind k : ModItems.Kind.values()) {
                Item item = ModItems.GEAR.get(t).get(k);
                if (item == null) check("items_" + t + "_" + k, false, "missing");
            }
        }
    }

    private static void setupArena() {
        BlockPos spawn = level.getRespawnData().pos();
        base = new BlockPos(spawn.getX(), 250, spawn.getZ());
        for (int x = -12; x <= 30; x++)
            for (int z = -12; z <= 12; z++) {
                level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 6; y++)
                    level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        caster = FakePlayer.get(level);
        caster.setPos(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        caster.setYRot(-90f); // facing +X
        caster.setXRot(0f);
        caster.setYHeadRot(-90f);
    }

    private static void testPlayer() {
        check("player_attributes", caster.getAttribute(ModAttributes.SPELL_POWER) != null, null);
        double mana = Progression.maxMana(caster);
        check("player_base_mana_no_levels", Math.abs(mana - Balance.BASE_MANA) < 0.01, mana);

        caster.getAttribute(ModAttributes.SPELL_POWER)
                .addPermanentModifier(new AttributeModifier(
                        ModItems.id("tree_a_focus_0"), .30, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        caster.getAttribute(Attributes.ARMOR)
                .addPermanentModifier(
                        new AttributeModifier(ModItems.id("tree_b_skin_0"), 6, AttributeModifier.Operation.ADD_VALUE));
        PlayerBonuses.apply(caster);
        check(
                "tree_retired_clears_stats",
                Math.abs(caster.getAttributeValue(ModAttributes.SPELL_POWER) - 1) < .001
                        && caster.getAttributeValue(Attributes.ARMOR) == 0,
                caster.getAttributeValue(ModAttributes.SPELL_POWER) + " / "
                        + caster.getAttributeValue(Attributes.ARMOR));
        testUpgrades();
        Progression.setMana(caster, 1000);
        check("mana_clamped", Progression.mana(caster) <= Progression.maxMana(caster) + 0.01, Progression.mana(caster));
    }

    /** Buying, switching, spending and reading a tome's upgrades, all on the fake caster's own inventory. */
    private static void testUpgrades() {
        var tome = new ItemStack(ModItems.TOMES.get(Spell.FIREBALL));
        caster.getInventory().setItem(9, tome);
        int full = SpellUpgrades.TIER_COST[0] + SpellUpgrades.TIER_COST[1] + SpellUpgrades.TIER_COST[2];
        caster.getInventory().setItem(11, new ItemStack(ModItems.SPARK, full + SpellUpgrades.TIER_COST[0]));
        String early = SpellUpgrades.choose(caster, 9, 1, 0);
        SpellUpgrades.choose(caster, 9, 0, 2); // Quick Hands
        String twice = SpellUpgrades.choose(caster, 9, 0, 2); // already in use
        SpellUpgrades.choose(caster, 9, 0, 0); // a second tier I node, bought and put in use
        SpellUpgrades.choose(caster, 9, 1, 0); // Wider Blast
        SpellUpgrades.choose(caster, 9, 2, 1); // Lingering Flames
        tome = caster.getInventory().getItem(9);
        int[] chosen = SpellUpgrades.chosen(tome);
        check(
                "upgrade_tiers_in_order_one_in_use",
                early != null && twice != null && chosen[0] == 0 && chosen[1] == 0 && chosen[2] == 1,
                Arrays.toString(chosen) + " early=" + early + " twice=" + twice);
        // Twice in tier I bought both; going back to the first bought one costs nothing.
        String back = SpellUpgrades.choose(caster, 9, 0, 2);
        int[] owned = SpellUpgrades.owned(caster.getInventory().getItem(9));
        check(
                "upgrade_pays_sparks_switch_free",
                Enchanting.count(caster, ModItems.SPARK) == 0
                        && back == null
                        && SpellUpgrades.chosen(caster.getInventory().getItem(9))[0] == 2
                        && owned[0] == 0b101
                        && owned[1] == 1
                        && owned[2] == 2,
                Enchanting.count(caster, ModItems.SPARK) + " " + Arrays.toString(owned));
        tome = caster.getInventory().getItem(9);
        var upgraded = tome;
        boolean[] reads = new boolean[1];
        CastContext.perform(caster, Spell.FIREBALL, upgraded, 1, () -> {
            reads[0] = Math.abs(SpellUpgrades.scale(Spell.FIREBALL, SpellUpgrades.Stat.AREA, 1.75) - 2.45) < 1e-9
                    && SpellUpgrades.scale(Spell.FROST_NOVA, SpellUpgrades.Stat.AREA, 6) == 6
                    && SpellUpgrades.sum(Spell.FIREBALL, SpellUpgrades.Stat.POWER) == 0
                    && SpellUpgrades.perk(caster, Spell.FIREBALL, "Lingering Flames");
            return true;
        });
        var plain = new ItemStack(ModItems.TOMES.get(Spell.FIREBALL));
        int fast = Spells.cooldownTicks(caster, Spell.FIREBALL, upgraded),
                slow = Spells.cooldownTicks(caster, Spell.FIREBALL, plain);
        check(
                "upgrade_cast_reads_its_tome",
                reads[0]
                        && SpellUpgrades.scale(Spell.FIREBALL, SpellUpgrades.Stat.AREA, 1.75) == 1.75
                        && Math.abs(fast / (double) slow - .75) < .05,
                "reads=" + reads[0] + " cooldown " + fast + "/" + slow);
        caster.getInventory().clearContent();
        caster.getInventory().setItem(9, ItemStack.EMPTY);
        check(
                "upgrade_helpers_base_outside_cast",
                EarthSpells.stoneskinTicks() == 240
                        && WorkSpells.verdantRadius() == 15
                        && WorkSpells.reapRadius() == 8
                        && WorkSpells.excavateDepth() == 3
                        && WorkSpells.oreSightRadius() == 15
                        && WorkSpells.forgeBudget() == 32
                        && WorkSpells.gatherRadius() == 16
                        && TravelSpells.homewardTicks() == 200,
                null);
        var digger = new ItemStack(ModItems.TOMES.get(Spell.EXCAVATE));
        // Digs Deeper (+1/3), Digging Rush and Vein Follower: 3 -> 4 (owner, 2026-11-23: tier II no longer digs deeper)
        SpellUpgrades.write(digger, new int[] {0, 0, 0});
        int[] depth = new int[1];
        CastContext.perform(caster, Spell.EXCAVATE, digger, 1, () -> {
            depth[0] = WorkSpells.excavateDepth();
            return true;
        });
        check("upgrade_excavate_depth_stacks", depth[0] == 4, depth[0]);
        // A perk is read inside the cast and, for delayed effects, after it; a name the spell lacks throws.
        boolean[] inCast = new boolean[1];
        CastContext.perform(caster, Spell.EXCAVATE, digger, 1, () -> {
            inCast[0] = SpellUpgrades.perk(caster, Spell.EXCAVATE, "Vein Follower");
            return true;
        });
        boolean unknown;
        try {
            SpellUpgrades.perk(caster, Spell.EXCAVATE, "No Such Perk");
            unknown = false;
        } catch (IllegalArgumentException e) {
            unknown = true;
        }
        check(
                "upgrade_perk_read_in_and_after_cast",
                inCast[0]
                        && SpellUpgrades.perk(caster, Spell.EXCAVATE, "Vein Follower")
                        && !SpellUpgrades.perk(caster, Spell.EXCAVATE, "Wide Bore")
                        && unknown,
                inCast[0] + " " + unknown);
        // Frost Armour: a nova that hits two monsters leaves an ice shield of 4.
        var nova = new ItemStack(ModItems.TOMES.get(Spell.FROST_NOVA));
        SpellUpgrades.write(nova, new int[] {0, 0, 1});
        var z1 = dummy(net.minecraft.world.entity.EntityTypes.ZOMBIE, 2, 0, false);
        var z2 = dummy(net.minecraft.world.entity.EntityTypes.ZOMBIE, -2, 0, false);
        caster.setAbsorptionAmount(0);
        CastContext.perform(caster, Spell.FROST_NOVA, nova, 1, () -> {
            FrostSpells.frostNova(level, caster, 1, 6);
            return true;
        });
        float shield = caster.getAbsorptionAmount();
        // Owner, 2026-11-18: the nova freezes what it hits solid in ice for 3 s and shoves twice as hard (0.9 a hit;
        // the old 0.45 shove plus a hit's own knockback stayed under 0.88). A creeper's fuse winds back.
        boolean frozen = Freeze.frozen(z1) && Freeze.frozen(z2);
        double shove = z1.getDeltaMovement().horizontalDistance();
        Freeze.thaw(z1);
        Freeze.thaw(z2);
        z1.discard();
        z2.discard();
        var creeper = dummy(net.minecraft.world.entity.EntityTypes.CREEPER, 2, 0, false);
        creeper.setSwellDir(1);
        CastContext.perform(caster, Spell.FROST_NOVA, new ItemStack(ModItems.TOMES.get(Spell.FROST_NOVA)), 1, () -> {
            FrostSpells.frostNova(level, caster, 1, 6);
            return true;
        });
        boolean defused = Freeze.frozen(creeper) && creeper.getSwellDir() < 0;
        Freeze.thaw(creeper);
        creeper.discard();
        caster.removeEffect(net.minecraft.world.effect.MobEffects.ABSORPTION);
        caster.setAbsorptionAmount(0);
        check("perk_frost_armour_shields", shield >= 4 - .01 && shield <= 10, shield);
        check(
                "frost_nova_freezes_and_shoves",
                frozen && defused && shove >= .88,
                "frozen=" + frozen + " defused=" + defused + " shove=" + shove);
    }

    // ---- The second shelf of spells, cast for real by the fake player ----

    static final List<Mob> DUMMIES = new ArrayList<>();

    /** A sturdy target dx, dz from the arena centre; with ai off it stays exactly where it is put. */
    static <T extends Mob> T dummy(EntityType<T> type, double dx, double dz, boolean ai) {
        T m = type.create(level, EntitySpawnReason.COMMAND);
        m.snapTo(base.getX() + 0.5 + dx, base.getY(), base.getZ() + 0.5 + dz, 0f, 0f);
        m.setNoAi(!ai);
        m.getAttribute(Attributes.MAX_HEALTH).setBaseValue(300);
        // A delayed entity-load event must not randomize or heal a target after the spell hits it.
        if (Monsters.isMonster(m)) Monsters.setRank(m, MonsterInfo.NORMAL);
        else m.setHealth(m.getMaxHealth());
        level.addFreshEntity(m);
        DUMMIES.add(m);
        return m;
    }

    /** Runs at a test tick; a crash is recorded and the test carries on. */
    static void at(int tick, Runnable r) {
        Scheduler.later(tick, () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                Lightborn.LOG.error("SELFTEST crashed", e);
                check("no_crash", false, e.toString());
            }
        });
    }

    static void face(Entity e) {
        var to = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(caster.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
        caster.setYRot(yaw);
        caster.setYHeadRot(yaw);
        caster.setXRot(pitch);
    }

    static boolean hurt(Mob m) {
        return !m.isAlive() || m.getHealth() < m.getMaxHealth();
    }

    static BlockPos litChest;
    static int litCount;

    static void afterSpells(Runnable done) {
        // (The headless test server computes no block light at all, so how far it shines is checked in the client
        // test.)
        level.setBlockAndUpdate(litChest, Blocks.AIR.defaultBlockState());
        int left = 0;
        for (BlockPos q : BlockPos.betweenClosed(litChest.offset(-8, -2, -8), litChest.offset(8, 4, 8)))
            if (level.getBlockState(q).is(Blocks.LIGHT)) left++;
        check(
                "light_chest_reach",
                litCount >= 8 && left == 0,
                litCount + " lights placed, " + left + " left after breaking");
        int damaged = 0;
        for (Husk h : TARGETS) if (!h.isAlive() || h.getHealth() < h.getMaxHealth()) damaged++;
        check("spells_damage", damaged >= 5, damaged + "/" + TARGETS.size());
        int hurtAnimals = 0;
        for (var a : ANIMALS) if (!a.isAlive() || a.getHealth() < a.getMaxHealth()) hurtAnimals++;
        check(
                "spells_hurt_animals",
                hurtAnimals == ANIMALS.size(),
                hurtAnimals + "/" + ANIMALS.size() + " "
                        + ANIMALS.stream()
                                .map(a -> a.getType().toShortString() + "@"
                                        + a.blockPosition().toShortString() + "=" + a.getHealth() + "/" + a.isAlive())
                                .toList());
        for (var a : ANIMALS) a.discard();
        check(
                "meteor_rock_gone",
                level.getEntitiesOfClass(
                                Display.BlockDisplay.class,
                                new AABB(base).inflate(60),
                                e -> e.entityTags().contains(FireSpells.ROCK_TAG))
                        .isEmpty(),
                null);
        check("spells_projectiles_done", Spells.liveProjectiles() == 0, Spells.liveProjectiles());
        check(
                "kills_give_no_experience",
                caster.totalExperience == 0 && caster.experienceLevel == 0,
                caster.totalExperience + " points, level " + caster.experienceLevel);
        for (Husk h : TARGETS) h.discard();
        var center = base.offset(112, 0, 0);
        SelfTestSpells.withTestArena(
                center.offset(-52, 0, -52),
                center.offset(56, 0, 52),
                "moon_arena_ready",
                release -> {
                    try {
                        SelfTestCleanse.testMoonNights(center);
                        SelfTestFavours.run(center.above(40));
                    } finally {
                        release.run();
                    }
                },
                () -> {
                    SelfTestWorld.testBeams();
                    SelfTestMonsters.testModdedMonsters();
                    SelfTestWorld.testWilds();
                    SelfTestWorld.testLore();
                    SelfTestSpells.testGreaterSpells(() -> SelfTestSpells.withTestArena(
                            base.offset(380, 0, -20),
                            base.offset(440, 0, 20),
                            "phase6_arena_ready",
                            release -> SelfTestPhaseSix.run(level, base.offset(400, 0, 0), release),
                            done));
                });
    }

    static void finish(MinecraftServer server) {
        boolean pass = RESULTS.values().stream().allMatch(v -> v.startsWith("ok"));
        StringBuilder sb = new StringBuilder();
        RESULTS.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        Lightborn.LOG.info("LIGHTBORN SELFTEST {}: {} checks{}", pass ? "PASS" : "FAIL", RESULTS.size(), sb);
        server.halt(false);
    }
}
