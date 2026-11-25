package lightborn;

import com.mojang.authlib.GameProfile;
import java.util.*;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Verify the state machine, real damage/drop hooks, gift scaling and irreversible endings. */
final class SelfTestStory {
    static void check(String name, boolean ok, Object detail) {
        SelfTest.check("story_" + name, ok, detail);
    }

    static void run(ServerLevel l, BlockPos at) {
        var p = FakePlayer.get(l, new GameProfile(UUID.randomUUID(), "StoryTest"));
        p.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        var root = l.getServer().overworld();
        var clock = l.registryAccess().getOrThrow(WorldClocks.OVERWORLD);
        long oldTime = l.getOverworldClockTime();
        int oldEnd = DragonStory.ending(l);
        var oldGate = root.getAttached(DragonStory.PRISON);
        var oldArena = root.getAttached(DragonStory.ARENA);
        var oldCity = root.getAttached(DragonStory.CITY);
        var oldBounds = root.getAttached(DragonStory.CITY_BOUNDS);
        try {
            root.setAttached(DragonStory.ENDING, 0);
            root.setAttached(DragonStory.PRISON, at);
            root.setAttached(DragonStory.ARENA, at);
            p.setAttached(DragonStory.FLAGS, 0);
            check("no_skipped_chapter", !DragonStory.unlocked(p, 2) && !DragonStory.unlocked(p, 3), null);
            p.setAttached(DragonStory.FLAGS, 127);
            boolean ordered = true, oneNight = true, gifts = true;
            for (int n = 1; n <= 9; n++) {
                l.clockManager().setTotalTicks(clock, (100L + n) * 24000 + 13000);
                var page = DragonStory.tell(p, -2);
                ordered &= page != null
                        && DragonStory.chapters(p) == n
                        && page.text().equals(DragonStory.TEXT.get(n - 1)); // his words only, no heading
                oneNight &= DragonStory.tell(p, -2) == null;
                gifts &= n < 2 || n > 8 || LightGear.has(p, LightGear.ITEMS.get(n - 2)); // chapters 2 to 8 give
            }
            check("ordered_nine_chapters", ordered, DragonStory.chapters(p));
            check("one_chapter_per_night", oneNight, null);
            check("chapter_gifts_and_scale", gifts && LightGear.has(p, DragonStory.SCALE), null);
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, l.registryAccess());
            p.saveWithoutId(output);
            var restored = FakePlayer.get(l, new GameProfile(UUID.randomUUID(), "StoryReload"));
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, l.registryAccess(), output.buildResult()));
            check(
                    "chapters_flags_and_night_save_reload",
                    DragonStory.chapters(restored) == 9
                            && restored.getAttached(DragonStory.FLAGS) == 127
                            && restored.getAttached(DragonStory.LAST_NIGHT)
                                    .equals(p.getAttached(DragonStory.LAST_NIGHT)),
                    null);
            restored.discard();
            for (int tier = 1; tier <= 3; tier++) {
                var stats = LightGear.stats(1, tier);
                double attack = stats.modifiers().stream()
                        .filter(e -> e.attribute().equals(Attributes.ATTACK_DAMAGE))
                        .mapToDouble(e -> e.modifier().amount())
                        .sum();
                check("sword_tier_" + tier, attack == 4 + tier, attack);
            }
            // Upgrade Light Gear (owner, 2026-11-17): a tree like the spells'; each tier opened raises the level.
            p.getInventory().clearContent();
            p.getInventory().setItem(0, LightGear.gift(1, 1));
            // Tier I takes 3 copper ingots, tier II 3 lapis blocks and tier III 3 redstone blocks.
            p.getInventory().setItem(1, new ItemStack(ModItems.SPARK, 5));
            p.getInventory().setItem(2, new ItemStack(Items.COPPER_INGOT, 3));
            boolean first = LightGearTree.choose(p, 0, 0, 0) == null
                    && LightGearTree.level(p.getInventory().getItem(0)) == 2
                    && Enchanting.count(p, Items.COPPER_INGOT) == 0;
            boolean refused = LightGearTree.choose(p, 0, 1, 1) != null
                    && LightGearTree.level(p.getInventory().getItem(0)) == 2;
            p.getInventory().setItem(2, new ItemStack(Items.LAPIS_BLOCK, 3));
            var sword = p.getInventory().getItem(0);
            boolean bought = LightGearTree.choose(p, 0, 1, 1) == null
                    && LightGearTree.level(sword) == 3
                    && LightGear.step(sword) == 3
                    && Enchanting.count(p, Items.LAPIS_BLOCK) == 0
                    && sword.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS).modifiers().stream()
                            .anyMatch(e -> e.attribute().equals(ModAttributes.WEAPON_CRIT));
            p.getInventory().setItem(2, new ItemStack(Items.REDSTONE, 2));
            boolean noIron = LightGearTree.choose(p, 0, 2, 0) != null;
            p.getInventory().setItem(2, new ItemStack(Items.REDSTONE_BLOCK, 3));
            boolean third = LightGearTree.choose(p, 0, 2, 0) == null
                    && Enchanting.count(p, Items.REDSTONE_BLOCK) == 0
                    && Enchanting.count(p, ModItems.SPARK) == 5;
            Item[][] ores = {
                {LightGearTree.ore(0), Items.COPPER_INGOT},
                {LightGearTree.ore(1), Items.LAPIS_BLOCK},
                {LightGearTree.ore(2), Items.REDSTONE_BLOCK}
            };
            boolean oresOk = java.util.Arrays.stream(ores).allMatch(o -> o[0] == o[1]);
            var helm = LightGear.gift(LightGearTree.HELM, 1);
            LightGearTree.write(helm, new int[] {1, 1, -1}, new int[] {2, 2, 0});
            LootRoller.refresh(helm);
            double healthGain =
                    helm.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS).modifiers().stream()
                            .filter(entry -> entry.attribute().equals(Attributes.MAX_HEALTH))
                            .mapToDouble(entry -> entry.modifier().amount())
                            .sum();
            check("light_helm_upgrade_adds_one_heart", healthGain == 2, healthGain);
            // Light gear grows with the best ore mined and only that (owner, 2026-11-20): iron 5, diamond 6, netherite
            // 7
            // damage on the sword, whatever its upgrades; a node's own bonus rides on top.
            int oldMined = p.getAttachedOrElse(OreTiers.MINED, 0);
            p.getInventory().clearContent();
            p.getInventory().setItem(0, LightGear.gift(1, 1));
            double[] grown = new double[3];
            int[] mined = {0, 1 | 2, 1 | 2 | 4};
            for (int k = 0; k < 3; k++) {
                p.setAttached(OreTiers.MINED, mined[k]);
                LightGear.refresh(p);
                grown[k] = p
                        .getInventory()
                        .getItem(0)
                        .get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS)
                        .modifiers()
                        .stream()
                        .filter(e -> e.attribute().equals(Attributes.ATTACK_DAMAGE))
                        .mapToDouble(e -> e.modifier().amount())
                        .sum();
            }
            boolean named = LightGear.oreLevel(p.getInventory().getItem(0)) == 3;
            p.setAttached(OreTiers.MINED, oldMined);
            check(
                    "light_gear_grows_with_best_ore",
                    grown[0] == 5 && grown[1] == 6 && grown[2] == 7 && named,
                    java.util.Arrays.toString(grown) + " " + named);
            // A boss still drops a tier-five spell.
            var bossTome = TomeItem.spellOf(TomeItem.rollTier(p.getRandom(), 5));
            check("boss_tome_is_tier_5", bossTome != null && bossTome.tier == 5, bossTome);
            var weapon = LootRoller.roll(
                    p.getRandom(), LootRoller.Rarity.LEGENDARY, ModItems.Tier.IRON, ModItems.Kind.SWORD);
            var original = weapon.getOrDefault(
                    net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                    net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
            var healthAffix = new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    ModItems.id("affix_max_health_mainhand"),
                    6,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
            weapon.set(
                    net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                    original.withModifierAdded(
                            Attributes.MAX_HEALTH,
                            healthAffix,
                            net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND));
            LootRoller.refresh(weapon);
            check(
                    "legacy_weapon_loses_only_the_max_health_roll",
                    weapon.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS)
                            .equals(original),
                    null);
            // Light gear breaks but never vanishes (owner, 2026-11-20); broken it does nothing until mended.
            var worn = LightGear.gift(4, 1);
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, worn);
            var boots = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET);
            boots.hurtAndBreak(boots.getMaxDamage() + 50, p, net.minecraft.world.entity.EquipmentSlot.FEET);
            boolean stays = !boots.isEmpty()
                    && boots.isBroken()
                    && LightGear.broken(boots)
                    && !boots.getOrDefault(
                                    net.minecraft.core.component.DataComponents.TOOLTIP_DISPLAY,
                                    net.minecraft.world.item.component.TooltipDisplay.DEFAULT)
                            .shows(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
            boolean powerless =
                    !LightGear.immune(p, net.minecraft.world.effect.MobEffects.WITHER) && !LightGearPerks.webWalker(p);
            boots.setDamageValue(0);
            LightGear.hideStatsWhileBroken(boots);
            boolean mended = LightGear.immune(p, net.minecraft.world.effect.MobEffects.WITHER)
                    && boots.getOrDefault(
                                    net.minecraft.core.component.DataComponents.TOOLTIP_DISPLAY,
                                    net.minecraft.world.item.component.TooltipDisplay.DEFAULT)
                            .shows(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
            var vanilla = new ItemStack(Items.IRON_BOOTS);
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, vanilla);
            p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)
                    .hurtAndBreak(1000, p, net.minecraft.world.entity.EquipmentSlot.FEET);
            boolean vanillaGone = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)
                    .isEmpty();
            check(
                    "light_gear_breaks_never_vanishes",
                    stays && powerless && mended && vanillaGone,
                    stays + " " + powerless + " " + mended + " " + vanillaGone);
            check(
                    "upgrade_gear_prices_in_ore",
                    noIron && third && oresOk,
                    noIron + " " + third + " " + java.util.Arrays.deepToString(ores));
            // A step-days sword at step 3 reads as its first two nodes bought.
            var old = LightGear.gift(1, 3);
            boolean migrated = LightGearTree.chosen(old)[0] == 0
                    && LightGearTree.chosen(old)[1] == 0
                    && LightGearTree.chosen(old)[2] == -1
                    && LightGearTree.level(old) == 3;
            check(
                    "light_gear_tree_bought_with_ore",
                    first && refused && bought && migrated,
                    first + " " + refused + " " + bought + " " + migrated);
            // Upgrade Gear takes any gear (owner, 2026-11-17): each kind its own tree.
            int[][] kinds = {
                {LightGearTree.kind(new ItemStack(Items.IRON_HELMET)), LightGearTree.HELM},
                {LightGearTree.kind(new ItemStack(Items.DIAMOND_SWORD)), LightGearTree.WEAPON},
                {LightGearTree.kind(new ItemStack(Items.NETHERITE_AXE)), LightGearTree.AXE},
                {LightGearTree.kind(new ItemStack(Items.MACE)), LightGearTree.MACE},
                {LightGearTree.kind(new ItemStack(Items.IRON_SPEAR)), LightGearTree.SPEAR},
                {LightGearTree.kind(LightGear.gift(LightGear.SHIELD, 1)), LightGearTree.SHIELD},
                {LightGearTree.kind(new ItemStack(Items.TRIDENT)), LightGearTree.TRIDENT},
                {LightGearTree.kind(new ItemStack(Items.GOLDEN_CHESTPLATE)), LightGearTree.CHEST},
                {LightGearTree.kind(new ItemStack(Items.LEATHER_LEGGINGS)), LightGearTree.LEGS},
                {LightGearTree.kind(new ItemStack(Items.CHAINMAIL_BOOTS)), LightGearTree.BOOTS},
                {LightGearTree.kind(new ItemStack(Items.ELYTRA)), LightGearTree.WINGS},
                {LightGearTree.kind(new ItemStack(Items.BOW)), LightGearTree.RANGED},
                {LightGearTree.kind(new ItemStack(Items.CROSSBOW)), LightGearTree.RANGED},
                {LightGearTree.kind(new ItemStack(Items.IRON_PICKAXE)), LightGearTree.TOOL},
                {LightGearTree.kind(new ItemStack(Items.WOODEN_HOE)), LightGearTree.TOOL},
                {LightGearTree.kind(new ItemStack(Items.SHIELD)), LightGearTree.SHIELD},
                {LightGearTree.kind(LootRoller.starterStaff(p.getRandom())), LightGearTree.STAFF},
                {LightGearTree.kind(new ItemStack(Items.CARVED_PUMPKIN)), -1},
                {LightGearTree.kind(new ItemStack(Items.STICK)), -1}
            };
            boolean kindsOk = java.util.Arrays.stream(kinds).allMatch(k -> k[0] == k[1]);
            check("upgrade_gear_kinds", kindsOk, java.util.Arrays.deepToString(kinds));
            // A plain diamond sword keeps its own damage and gains the node's.
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            p.getInventory().setItem(1, new ItemStack(Items.COPPER_INGOT, 3));
            var plain = p.getInventory().getItem(0);
            boolean plainBought = LightGearTree.choose(p, 0, 0, 0) == null;
            double plainAttack =
                    plain.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS).modifiers().stream()
                            .filter(e -> e.attribute().equals(Attributes.ATTACK_DAMAGE))
                            .mapToDouble(e -> e.modifier().amount())
                            .sum();
            check(
                    "upgrade_gear_plain_sword",
                    plainBought && plainAttack == 6 + 1 && LightGear.step(plain) == 0,
                    plainBought + " " + plainAttack);
            // Every worn Light piece's armour counts: one modifier id per slot, never one shared.
            var ids = new java.util.HashSet<String>();
            int armourMods = 0;
            for (int i : new int[] {0, 2, 3, 4})
                for (var e : LightGear.stats(i, 1).modifiers())
                    if (e.attribute().equals(Attributes.ARMOR)) {
                        armourMods++;
                        ids.add(e.modifier().id().toString());
                    }
            check("light_armour_ids_per_slot", armourMods == 4 && ids.size() == 4, ids);
            // The first Dark Shard sends the player to the Dragon; carried in numbers they whisper more often.
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(ModItems.DARK_SHARD));
            p.setAttached(
                    DragonStory.FLAGS,
                    p.getAttachedOrElse(DragonStory.FLAGS, 0) & ~(DarkShards.SEEN | DarkShards.TOLD));
            DarkShards.tick(p);
            boolean seen = DragonStory.hasFlag(p, DarkShards.SEEN) && DarkShards.menuChoice(p) != null;
            DarkShards.explain(p);
            check(
                    "dark_shard_first_find_and_whispers",
                    seen
                            && DarkShards.menuChoice(p) == null
                            && DarkShards.chance(2) == 0
                            && DarkShards.chance(3) == .0075
                            && DarkShards.chance(10) > DarkShards.chance(3)
                            && DarkShards.chance(99) == .05
                            && Balance.SHARD_CHEST_CHANCE == .125
                            && Balance.SCOUT_SHARD_CHANCE == .075
                            && Balance.BOSS_SHARD_CHANCE == .5,
                    seen);
            p.getInventory().clearContent();
            // Treasure Thread finds a chest that still holds its generated loot, and not one already opened.
            var chestAt = p.blockPosition().above(30);
            l.setBlockAndUpdate(chestAt, Blocks.CHEST.defaultBlockState());
            boolean threadFound = false, threadIgnoresOpened = false;
            if (l.getBlockEntity(chestAt)
                    instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity box) {
                box.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON);
                threadFound = chestAt.equals(TreasureThread.find(l, chestAt, 2));
                box.setLootTable(null);
                threadIgnoresOpened = TreasureThread.find(l, chestAt, 2) == null;
            }
            l.setBlockAndUpdate(chestAt, Blocks.AIR.defaultBlockState());
            check("treasure_thread_finds_unopened_loot", threadFound && threadIgnoresOpened, threadFound);
            check(
                    "glowstone_repair",
                    LightGear.ITEMS.stream()
                            .allMatch(i -> new ItemStack(i)
                                    .get(DataComponents.REPAIRABLE)
                                    .isValidRepairItem(new ItemStack(Items.GLOWSTONE_DUST))),
                    null);
            p.setItemSlot(EquipmentSlot.HEAD, LightGear.gift(0, 3));
            p.setItemSlot(EquipmentSlot.CHEST, LightGear.gift(2, 3));
            p.setItemSlot(EquipmentSlot.LEGS, LightGear.gift(3, 3));
            p.setItemSlot(EquipmentSlot.FEET, LightGear.gift(4, 3));
            boolean immune = true;
            for (var effect : List.of(
                    MobEffects.BLINDNESS,
                    MobEffects.POISON,
                    MobEffects.SLOWNESS,
                    MobEffects.WITHER,
                    MobEffects.DARKNESS)) immune &= !p.addEffect(new MobEffectInstance(effect, 100));
            check("five_effect_immunities", immune, null);
            for (int n = 0; n <= 6; n++) {
                for (var slot : List.of(
                        EquipmentSlot.HEAD,
                        EquipmentSlot.CHEST,
                        EquipmentSlot.LEGS,
                        EquipmentSlot.FEET,
                        EquipmentSlot.MAINHAND,
                        EquipmentSlot.OFFHAND)) p.setItemSlot(slot, ItemStack.EMPTY);
                int[] indices = {0, 2, 3, 4, 1, 5};
                EquipmentSlot[] slots = {
                    EquipmentSlot.HEAD,
                    EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS,
                    EquipmentSlot.FEET,
                    EquipmentSlot.MAINHAND,
                    EquipmentSlot.OFFHAND
                };
                for (int i = 0; i < n; i++) p.setItemSlot(slots[i], LightGear.gift(indices[i], 3));
                check(
                        "radiance_" + n,
                        LightGear.radiance(p) == (n == 0 ? 0 : Math.min(13, 2 + n * 2)),
                        LightGear.radiance(p));
            }
            l.clockManager().setTotalTicks(clock, 110L * 24000 + 6000);
            check("radiance_only_at_night", LightGear.radiance(p) == 0, null);
            l.clockManager().setTotalTicks(clock, 110L * 24000 + 13000);
            p.getInventory().clearContent();
            p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            LightGear.give(p, LightGear.gift(0, 3));
            ItemStack dropped = p.getInventory().getItem(0).copy();
            p.getInventory().setItem(0, ItemStack.EMPTY);
            var item = new ItemEntity(l, p.getX(), p.getY(), p.getZ(), dropped);
            l.addFreshEntity(item);
            item.hurtServer(l, l.damageSources().lava(), 1000);
            check("destroyed_gift_recorded", (LightGear.lost(p) & 1) != 0, LightGear.lost(p));
            LightGear.replaceMissing(p);
            check("gift_replaced_once", LightGear.has(p, LightGear.ITEMS.get(0)) && LightGear.lost(p) == 0, null);
            int before = Enchanting.count(p, LightGear.ITEMS.get(0));
            LightGear.replaceMissing(p);
            check("no_free_duplicate_for_stored_gift", Enchanting.count(p, LightGear.ITEMS.get(0)) == before, null);
            p.setAttached(DragonStory.FLAGS, 15);
            StoryBosses.scribe(p);
            var scribe = l.getEntitiesOfClass(
                            LastScribe.class, p.getBoundingBox().inflate(12))
                    .getFirst();
            check("scribe_is_separate_from_hunt_roster", Bosses.identity(scribe) == null, null);
            double health = scribe.getHealth();
            Spells.withSpell(Spell.FROST_NOVA, () -> Spells.hit(p, scribe, 4));
            check(
                    "scribe_takes_real_spell_damage",
                    scribe.getHealth() < health && StoryBosses.copied(p) == Spell.FROST_NOVA,
                    scribe.getHealth());
            scribe.damageCooldownTime = 0;
            scribe.hurtServer(l, p.damageSources().playerAttack(p), 100000);
            check(
                    "scribe_key_diary_trophy_and_progress",
                    DragonStory.hasFlag(p, 16)
                            && LightGear.has(p, DragonStory.LIGHT_KEY)
                            && LightGear.has(p, DragonStory.SCRIBE_SEAL)
                            && p.getInventory().countItem(Items.WRITTEN_BOOK) == 1,
                    null);
            int keys = p.getInventory().countItem(DragonStory.LIGHT_KEY);
            StoryBosses.died(scribe, p.damageSources().playerAttack(p));
            check("scribe_reward_once", p.getInventory().countItem(DragonStory.LIGHT_KEY) == keys, null);
            root.setAttached(
                    DragonStory.CITY_BOUNDS,
                    new BoundingBox(
                            at.offset(-30, -10, -30).getX(),
                            at.getY() - 10,
                            at.getZ() - 30,
                            at.getX() + 30,
                            at.getY() + 10,
                            at.getZ() + 30));
            check(
                    "prison_warden_scope",
                    DragonStory.sleepingWarden(l, at) && !DragonStory.sleepingWarden(l, at.east(100)),
                    null);
            DragonStory.prisonReply(p, 911);
            check("friend_ending", DragonStory.ending(l) == 1, null);
            DragonStory.prisonReply(p, 912);
            check("choice_locked_per_world", DragonStory.ending(l) == 1, null);
            check("warden_danger_returns_after_choice", !DragonStory.sleepingWarden(l, at), null);
            DragonStory.call(p);
            int allies = l.getEntitiesOfClass(
                            LightDragon.class, p.getBoundingBox().inflate(10), d -> d.role() == 2)
                    .size();
            DragonStory.call(p);
            check(
                    "friend_once_per_night",
                    allies == 1
                            && l.getEntitiesOfClass(
                                                    LightDragon.class,
                                                    p.getBoundingBox().inflate(10),
                                                    d -> d.role() == 2)
                                            .size()
                                    == 1,
                    allies);
            // A fresh isolated ending fixture exercises the alternative, without changing the rule above.
            StoryBosses.clear();
            root.setAttached(DragonStory.ENDING, 0);
            DragonStory.prisonReply(p, 912);
            var dragon = l.getEntitiesOfClass(
                            LightDragon.class, p.getBoundingBox().inflate(20), d -> d.role() == 1)
                    .getFirst();
            check(
                    "true_size_dragon_is_vulnerable",
                    dragon.getAttributeValue(Attributes.SCALE) == StoryBosses.STORY_SIZE
                            && Spells.canHitDirect(p, dragon),
                    dragon.getScale());
            check(
                    "dragon_fight_does_not_consume_spell_use",
                    dragon.mobInteract(p, InteractionHand.MAIN_HAND) == InteractionResult.PASS,
                    null);
            dragon.hurtServer(l, p.damageSources().playerAttack(p), 100000);
            check("fight_ending", DragonStory.ending(l) == 3, null);
            p.setGameMode(GameType.SURVIVAL);
            p.setHealth(p.getMaxHealth());
            check(
                    "form_grants_flight",
                    DragonForm.activate(p) && p.getAbilities().mayfly && p.getAbilities().flying && p.isInvisible(),
                    "alive=" + p.isAlive() + " mode=" + p.gameMode.getGameModeForPlayer() + " end="
                            + DragonStory.ending(l) + " fly=" + p.getAbilities().flying + " invisible="
                            + p.isInvisible());
            DragonForm.end(p);
            check(
                    "form_restores_survival_abilities",
                    !p.getAbilities().mayfly && !p.getAbilities().flying && !p.isInvisible(),
                    null);
            check("form_has_long_cooldown", !DragonForm.activate(p), null);
            LightGear.destroyed(new ItemEntity(l, p.getX(), p.getY(), p.getZ(), dropped));
            var offer = LightShop.waresFor(p, LightShop.day(l));
            check(
                    "shop_replaces_lost_gift_in_six_slots",
                    offer.size() == 6
                            && offer.get(3).stack().is(LightGear.ITEMS.get(0))
                            && offer.get(3).price() == 0,
                    null);
            var chest = p.blockPosition().east(2);
            l.setBlockAndUpdate(chest, ModItems.LIGHT_CHEST.defaultBlockState());
            check(
                    "shop_replacement_claim",
                    LightShop.purchase(p, chest, LightShop.day(l), 3).startsWith("Bought") && LightGear.lost(p) == 0,
                    null);
            l.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
            var load = FabricLoader.getInstance();
            check(
                    "removed_dimensions_and_libraries_absent",
                    List.of("betterend", "betternether", "fishofthieves", "bclib", "wover").stream()
                            .noneMatch(load::isModLoaded),
                    null);
            Object census = Collection.all().stream()
                    .collect(Collectors.groupingBy(
                            id -> id.substring(0, id.indexOf(':')), TreeMap::new, Collectors.counting()));
            Lightborn.LOG.info("CATALOGUE NAMESPACES {}", census);
        } finally {
            DragonForm.end(p);
            StoryBosses.clear();
            CollectionLight.release(p);
            p.discard();
            root.setAttached(DragonStory.ENDING, oldEnd);
            if (oldGate == null) root.removeAttached(DragonStory.PRISON);
            else root.setAttached(DragonStory.PRISON, oldGate);
            if (oldArena == null) root.removeAttached(DragonStory.ARENA);
            else root.setAttached(DragonStory.ARENA, oldArena);
            if (oldCity == null) root.removeAttached(DragonStory.CITY);
            else root.setAttached(DragonStory.CITY, oldCity);
            if (oldBounds == null) root.removeAttached(DragonStory.CITY_BOUNDS);
            else root.setAttached(DragonStory.CITY_BOUNDS, oldBounds);
            l.clockManager().setTotalTicks(clock, oldTime);
        }
    }
}
