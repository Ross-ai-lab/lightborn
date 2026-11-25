package lightborn;

import com.mojang.authlib.GameProfile;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

final class SelfTestLightUpdate {
    static void run(ServerLevel level, BlockPos at) {
        var p = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "LightShopTest"));
        p.setPos(at.getX() + .5, at.getY(), at.getZ() + 2.5);
        PlayerBonuses.apply(p);
        var clock = level.registryAccess().getOrThrow(WorldClocks.OVERWORLD);
        long oldTime = level.getOverworldClockTime();
        try {
            for (BlockPos b : BlockPos.betweenClosed(at.offset(-5, 0, -5), at.offset(5, 5, 5)))
                level.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState());
            level.clockManager().setTotalTicks(clock, 6000);
            level.setBlockAndUpdate(
                    at,
                    ModItems.LIGHT_CHEST
                            .defaultBlockState()
                            .setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
            // Lasts Longer (tier I) and Night Sight (tier II, a perk since 2026-11-23): 20 s raised by a quarter,
            // read from the tome during the cast.
            var shade = new ItemStack(ModItems.TOMES.get(Spell.ECLIPSE));
            SpellUpgrades.write(shade, new int[] {0, 0, -1});
            CastContext.perform(p, Spell.ECLIPSE, shade, 1, () -> {
                BossTrophies.eclipse(level, p);
                return true;
            });
            SelfTest.check(
                    "eclipse_duration_upgrade",
                    p.getAttached(ModData.ECLIPSE_UNTIL) - level.getGameTime() == 500,
                    p.getAttached(ModData.ECLIPSE_UNTIL) - level.getGameTime());
            BossTrophies.eclipse(level, p);
            SelfTest.check(
                    "eclipse_base_twenty_seconds",
                    p.getAttached(ModData.ECLIPSE_UNTIL) - level.getGameTime() == 400,
                    null);
            boolean rounded = true;
            for (var c : Collection.categories()) {
                String text = Collection.rewardAmount(c, 1).getString();
                rounded &= switch (c.key()) {
                    case "food_and_drinks" -> text.startsWith("+0.2 ");
                    case "earth" -> text.equals("+0.5 hearts");
                    default -> !text.matches(".*\\d\\.\\d.*");
                };
            }
            SelfTest.check("collection_rounded_quarter_bonuses", rounded, null);
            stationsAndSalvage(level, at);
            var offers = LightShop.wares(level, 0);
            var again = LightShop.wares(level, 0);
            var tomorrow = LightShop.wares(level, 1);
            boolean deterministic = true, rotated = false;
            for (int i = 0; i < offers.size(); i++) {
                deterministic &=
                        ItemStack.matches(offers.get(i).stack(), again.get(i).stack());
                rotated |= !ItemStack.matches(
                        offers.get(i).stack(), tomorrow.get(i).stack());
            }
            SelfTest.check(
                    "shop_daily_roster_deterministic_and_rotating",
                    deterministic
                            && rotated
                            && offers.size() == 6
                            && offers.stream().noneMatch(o -> o.stack().isEmpty()),
                    null);
            boolean order = true;
            for (int day = 0; day < 64; day++) {
                var row = LightShop.wares(level, day);
                // Six wares (owner, 2026-11-16): tome, staff, shards, one curio, map, raw food.
                order &= row.size() == 6
                        && TomeItem.spellOf(row.get(0).stack()) != null
                        && ModItems.isStaff(row.get(1).stack())
                        && row.get(2).stack().is(day < 7 ? Items.AMETHYST_SHARD : ModItems.DARK_SHARD)
                        && !row.get(3).stack().isEmpty()
                        && row.stream().noneMatch(o -> o.stack().has(DataComponents.EQUIPPABLE))
                        && LightShop.mapOf(row.get(4).stack()) != null
                        && row.get(5).stack().has(DataComponents.FOOD)
                        && !row.get(5).stack().getItem().getDescriptionId().contains("cooked");
            }
            SelfTest.check("shop_exact_six_slot_order_64_days", order, null);
            boolean basic = true;
            for (int day = 0; day < 7; day++) {
                var row = LightShop.wares(level, day);
                var staff = row.get(1).stack();
                basic &= TomeItem.spellOf(row.get(0).stack()).shopTier == 1
                        && !staff.has(DataComponents.CUSTOM_NAME)
                        && !ModItems.SPECIALIST_TIER.containsKey(staff.getItem())
                        && row.get(2).stack().is(Items.AMETHYST_SHARD);
            }
            SelfTest.check("shop_first_week_basic_unlocked_wares", basic, null);
            SelfTest.check(
                    "shop_seven_day_power_progression",
                    LightShop.levelCap(6) == 1
                            && LightShop.levelCap(7) == 5
                            && LightShop.levelCap(14) == 12
                            && LightShop.levelCap(21) == 25
                            && LightShop.levelCap(42) == 70,
                    null);
            // Netherite is the top tier (owner, 2026-11-16): week 4 a Rare Netherite staff, week 5 onward Legendary.
            boolean topTier = true;
            for (long day : new long[] {21, 28, 35, 70}) {
                var staff = LightShop.wares(level, day).get(1).stack();
                boolean legendary = staff.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
                topTier &= ModItems.tierOf(staff.getItem()) == ModItems.Tier.NETHERITE && legendary == (day >= 28);
            }
            SelfTest.check("shop_staff_tops_out_at_legendary_netherite", topTier, null);
            // The staff's material is capped by the buyer's mined ore (owner, 2026-11-17); iron is the floor.
            int mined = p.getAttachedOrElse(OreTiers.MINED, 0);
            p.setAttached(OreTiers.MINED, 0);
            var none = LightShop.waresFor(p, 35).get(1);
            p.setAttached(OreTiers.MINED, 3);
            var diamond = LightShop.waresFor(p, 35).get(1);
            p.setAttached(OreTiers.MINED, 7);
            var all = LightShop.waresFor(p, 35).get(1);
            p.setAttached(OreTiers.MINED, mined);
            var world = LightShop.wares(level, 35).get(1);
            SelfTest.check(
                    "shop_staff_capped_by_mined_ore",
                    ModItems.tierOf(none.stack().getItem()) == ModItems.Tier.IRON
                            && ModItems.tierOf(diamond.stack().getItem()) == ModItems.Tier.DIAMOND
                            && ModItems.tierOf(all.stack().getItem()) == ModItems.Tier.NETHERITE
                            && none.stack().has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)
                            && none.price() == world.price(),
                    ModItems.tierOf(none.stack().getItem()) + "/"
                            + ModItems.tierOf(diamond.stack().getItem()) + "/"
                            + ModItems.tierOf(all.stack().getItem()));
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(ModItems.SPARK, 64));
            p.getInventory().setItem(1, new ItemStack(ModItems.SPARK, 64));
            int before = LightShop.sparks(p);
            String bought = LightShop.purchase(p, at, 0, 0);
            SelfTest.check(
                    "shop_purchase_charges_only_sparks",
                    bought.startsWith("Bought")
                            && LightShop.sparks(p) == before - offers.getFirst().price()
                            && Enchanting.count(p, offers.getFirst().stack().getItem())
                                    == offers.getFirst().stack().getCount(),
                    bought);
            int after = LightShop.sparks(p);
            SelfTest.check(
                    "shop_hides_owned_tome_and_lesser_staff",
                    LightShop.purchase(p, at, 0, 0).startsWith("That offer") && LightShop.sparks(p) == after,
                    null);
            var stone = new ItemStack(Spellstone.ITEM);
            Spellstone.store(
                    stone, List.of(offers.getFirst().stack(), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
            p.getInventory().clearContent();
            p.getInventory().setItem(0, stone);
            p.getInventory()
                    .setItem(
                            1,
                            LootRoller.roll(
                                    net.minecraft.util.RandomSource.create(4),
                                    LootRoller.Rarity.RARE,
                                    ModItems.Tier.IRON,
                                    ModItems.Kind.STAFF));
            var promoted = LightShop.waresFor(p, 0);
            SelfTest.check(
                    "shop_hides_owned_tomes_and_promotes_staff_past_week_limit",
                    promoted.getFirst().stack().isEmpty()
                            && LootRoller.rarity(promoted.get(1).stack()) == LootRoller.Rarity.LEGENDARY
                            && promoted.get(1).price() > offers.get(1).price()
                            && offers.getFirst().price() == 28
                            && offers.get(1).price() == 24
                            && Balance.WARD_PRICES[0] == 40,
                    null);
            SelfTest.check(
                    "shop_rejects_stale_and_forged_offers",
                    LightShop.purchase(p, at, -1, 0).startsWith("A new day")
                            && LightShop.purchase(p, at, 0, 99).startsWith("Choose"),
                    null);
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(Items.EMERALD, 64)); // the old world's money buys nothing here
            SelfTest.check(
                    "shop_rejects_unaffordable_and_emeralds",
                    LightShop.purchase(p, at, 0, 4).startsWith("You need")
                            && p.getInventory().getItem(0).getCount() == 64,
                    null);
            // Sparks kept in the chest count at the shop and are spent before the carried ones.
            p.getInventory().clearContent();
            var keptStore = p.getAttached(ModData.STORAGE);
            p.setAttached(ModData.STORAGE, List.of(new ItemStack(ModItems.SPARK, 64)));
            p.getInventory().setItem(0, new ItemStack(ModItems.SPARK, 5));
            int firstPrice = offers.getFirst().price();
            String paidFromStore = LightShop.purchase(p, at, 0, 0);
            SelfTest.check(
                    "shop_spends_stored_sparks_first",
                    paidFromStore.startsWith("Bought")
                            && LightShop.sparks(p) == 69 - firstPrice
                            && Collection.stored(p, "lightborn:spark").getCount() == 64 - firstPrice
                            && p.getInventory().getItem(0).getCount() == 5,
                    paidFromStore + " stored="
                            + Collection.stored(p, "lightborn:spark").getCount());
            if (keptStore == null) p.removeAttached(ModData.STORAGE);
            else p.setAttached(ModData.STORAGE, keptStore);
            p.getInventory().clearContent();
            // Nothing in the shop is gated (owner, 2026-11-16): a later week's staff sells to anyone with the Sparks.
            level.clockManager().setTotalTicks(clock, 7 * 24000 + 6000);
            p.getInventory().setItem(0, new ItemStack(ModItems.SPARK, 64));
            p.getInventory().setItem(1, new ItemStack(ModItems.SPARK, 64));
            String laterStaff = LightShop.purchase(p, at, 7, 1);
            SelfTest.check("shop_later_week_open_to_all", laterStaff.startsWith("Bought"), laterStaff);
            p.getInventory().clearContent();
            level.clockManager().setTotalTicks(clock, 6000);
            p.getInventory().setItem(0, new ItemStack(ModItems.SPARK, 64));
            p.getInventory().setItem(1, new ItemStack(ModItems.SPARK, 64));
            boolean repeat = true;
            // The raw-food card (the sixth): a cheap bundle, bought four times over.
            for (int i = 0; i < 4; i++)
                repeat &= LightShop.purchase(p, at, 0, 5)
                        .startsWith("Bought " + offers.get(5).stack().getCount() + " ×");
            SelfTest.check(
                    "shop_bundle_quantity_and_unlimited_repeats",
                    repeat
                            && Enchanting.count(p, offers.get(5).stack().getItem())
                                    == offers.get(5).stack().getCount() * 4,
                    offers.get(5).stack());
            for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            p.getInventory().setItem(0, new ItemStack(ModItems.SPARK, 64));
            SelfTest.check(
                    "shop_full_inventory_no_charge",
                    LightShop.purchase(p, at, 0, 4).startsWith("Make room") && LightShop.sparks(p) == 64,
                    null);
            p.getInventory()
                    .setItem(0, new ItemStack(ModItems.SPARK, offers.get(4).price()));
            SelfTest.check(
                    "shop_exact_payment_frees_inventory_slot",
                    LightShop.purchase(p, at, 0, 4).startsWith("Bought") && LightShop.sparks(p) == 0,
                    null);
            var ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
            level.clockManager().setTotalTicks(clock, 24000);
            SelfTest.check(
                    "shop_new_day_refreshes",
                    LightShop.snapshot(p, at, "").offers().size() == 6 && LightShop.day(level) == 1,
                    null);
            p.setPos(p.position().add(20, 0, 0));
            SelfTest.check(
                    "shop_rejects_distant_player",
                    LightShop.purchase(p, at, 1, 0).startsWith("Return"),
                    null);
            p.setPos(at.getX() + .5, at.getY(), at.getZ() + 2.5);
            SelfTest.check("dragon_absent_by_day", LightDragons.get(level, at) == null, null);
            level.clockManager().setTotalTicks(clock, 18000);
            LightDragons.reconcile(level, at);
            var echo = LightDragons.get(level, at);
            var introduction = DragonTalk.respond(p, echo, 0);
            SelfTest.check(
                    "dragon_introduction_before_quest",
                    p.getAttachedOrCreate(DragonTalk.JOURNEY).stage() == 0
                            && introduction.text().contains("echo")
                            && introduction.choices().stream().noneMatch(c -> c.action() == 5),
                    null);
            DragonTalk.respond(p, echo, 5);
            SelfTest.check(
                    "dragon_cannot_skip_to_accept_quest",
                    p.getAttachedOrCreate(DragonTalk.JOURNEY).stage() == 0,
                    null);
            DragonTalk.respond(p, echo, 11); // one introduction answer, then ask for work
            DragonTalk.respond(p, echo, 4);
            SelfTest.check(
                    "dragon_quest_offered_after_conversation",
                    p.getAttachedOrCreate(DragonTalk.JOURNEY).stage() == 3,
                    null);
            p.setAttached(DragonTalk.JOURNEY, new DragonTalk.Journey(6, at, at, List.of()));
            p.getInventory().clearContent();
            DragonTalk.respond(p, echo, 6);
            DragonTalk.respond(p, echo, 6);
            SelfTest.check(
                    "dragon_quest_reward_once",
                    p.getAttachedOrCreate(DragonTalk.JOURNEY).stage() == 7
                            && Enchanting.count(p, ModItems.SPARK) == 8
                            && Enchanting.count(p, ModItems.TOMES.get(Spell.BLINK)) == 1,
                    null);
            var journey = p.getAttachedOrCreate(DragonTalk.JOURNEY);
            SelfTest.check(
                    "dragon_quest_save_roundtrip",
                    DragonTalk.Journey.CODEC
                            .parse(
                                    ops,
                                    DragonTalk.Journey.CODEC
                                            .encodeStart(ops, journey)
                                            .getOrThrow())
                            .getOrThrow()
                            .equals(journey),
                    null);
            var reactions = new HashSet<String>();
            var offersByTone = new HashSet<String>();
            boolean branches = true;
            for (int route = 1; route <= 3; route++) {
                p.setAttached(DragonTalk.JOURNEY, DragonTalk.Journey.NEW);
                p.removeAttached(DragonTalk.DISPOSITION);
                reactions.add(DragonTalk.respond(p, echo, 10 + route).text());
                DragonTalk.respond(p, echo, 20 + route);
                offersByTone.add(DragonTalk.respond(p, echo, 4).text());
                branches &= p.getAttached(DragonTalk.DISPOSITION) == route
                        && p.getAttached(DragonTalk.JOURNEY).stage() == 3;
                p.setAttached(DragonTalk.JOURNEY, new DragonTalk.Journey(6, at, at, List.of()));
                p.getInventory().clearContent();
                var reward = DragonTalk.reward(p);
                DragonTalk.respond(p, echo, 6);
                DragonTalk.respond(p, echo, 6);
                // The quest pays 8 Sparks on top of the tone's reward, which may itself be Sparks.
                branches &= reward.is(ModItems.SPARK)
                        ? Enchanting.count(p, ModItems.SPARK) == 8 + reward.getCount()
                        : Enchanting.count(p, reward.getItem()) == 1 && Enchanting.count(p, ModItems.SPARK) == 8;
            }
            SelfTest.check(
                    "dragon_three_tones_reactions_offers_rewards",
                    branches && reactions.size() == 3 && offersByTone.size() == 3,
                    null);
            p.setAttached(DragonTalk.DISPOSITION, 2);
            p.setAttached(DragonTalk.SURVEY, List.of(at, at.east(20), at.north(20)));
            p.setAttached(DragonTalk.JOURNEY, new DragonTalk.Journey(4, at, at, List.of()));
            for (int i = 0; i < 3; i++) DragonTalk.survey(p, p.getAttached(DragonTalk.JOURNEY));
            SelfTest.check(
                    "dragon_neutral_survey_needs_three_marks",
                    p.getAttached(DragonTalk.SURVEY).isEmpty()
                            && p.getAttached(DragonTalk.JOURNEY).stage() == 6
                            && p.getAttached(DragonTalk.JOURNEY).enemies().isEmpty(),
                    null);
            float health = echo.getHealth();
            echo.hurtServer(level, p.damageSources().playerAttack(p), 10);
            SelfTest.check(
                    "dragon_hit_refracts_without_damage",
                    echo.getHealth() == health && echo.opacity(0) == .25f && echo.opacity(31) == 1,
                    null);
            var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            p.removeAttached(ModData.ECLIPSE_UNTIL);
            p.removeEffect(MobEffects.INVISIBILITY);
            zombie.setPos(at.getX() + .5, at.getY(), at.getZ() + 2);
            p.setPos(at.getX() + .5, at.getY(), at.getZ() - 3);
            zombie.setTarget(p);
            var door = at.south();
            level.setBlockAndUpdate(door.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(door, Blocks.SPRUCE_DOOR.defaultBlockState());
            level.setBlockAndUpdate(
                    door.above(),
                    Blocks.SPRUCE_DOOR
                            .defaultBlockState()
                            .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
            var pound = new DoorPoundGoal(zombie);
            boolean canPound = pound.canUse();
            if (canPound) {
                pound.start();
                for (int i = 0; i < 1200; i++) pound.tick();
            }
            zombie.setCanBreakDoors(true);
            SelfTest.check(
                    "zombies_pound_but_never_break_doors",
                    canPound && level.getBlockState(door).is(Blocks.SPRUCE_DOOR) && !zombie.canBreakDoors(),
                    "starts=" + canPound + " target=" + (zombie.getTarget() == p) + " canBreak="
                            + zombie.canBreakDoors());
            level.setBlockAndUpdate(door, Blocks.AIR.defaultBlockState());
            SelfTest.check("door_pounding_stops_when_open_or_removed", !pound.canContinueToUse(), null);
            // A monster close by never stops sleep (the hut is the refuge); only a running Cleanse does.
            var nearby = net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents.ALLOW_NEARBY_MONSTERS
                    .invoker()
                    .allowNearbyMonsters(p, at, false);
            SelfTest.check(
                    "sleep_allowed_near_monsters",
                    nearby == net.fabricmc.fabric.api.util.EventResult.ALLOW && Cleanse.sleepProblem(p, at) == null,
                    nearby);
            zombie.discard();
            level.setBlockAndUpdate(door.below(), Blocks.AIR.defaultBlockState());
            p.setPos(at.getX() + .5, at.getY(), at.getZ() + 2.5);
            SelfTest.check(
                    "dragon_night_above_chest_and_peaceful",
                    echo != null
                            && Math.abs(echo.getX() - (at.getX() + .5)) < .5
                            && echo.getY() >= at.getY() + 1.5
                            && echo.getY() < at.getY() + 2
                            && !echo.shouldBeSaved()
                            && !Spells.canHitDirect(p, echo)
                            && !echo.hurtServer(level, p.damageSources().generic(), 100),
                    null);
            LightDragons.reconcile(level, at);
            SelfTest.check("dragon_no_duplicates", LightDragons.get(level, at) == echo, null);
            // Above the chest only: walls two blocks to the sides change nothing, and a ceiling five blocks over the
            // chest's base (the starter hut's) still fits him. A ceiling one block lower leaves no room: he never
            // shrinks to fit, and waits for space.
            var walls = List.of(at.offset(2, 1, 0), at.offset(-2, 1, 0), at.above(5));
            for (var w : walls) level.setBlockAndUpdate(w, Blocks.STONE.defaultBlockState());
            LightDragons.reconcile(level, at);
            var boxed = LightDragons.get(level, at);
            SelfTest.check(
                    "dragon_above_only_fits_under_hut_roof",
                    boxed == echo && Math.abs(boxed.getX() - (at.getX() + .5)) < .5,
                    boxed == null ? "gone" : boxed.position());
            level.setBlockAndUpdate(at.above(4), Blocks.STONE.defaultBlockState());
            LightDragons.reconcile(level, at);
            SelfTest.check("dragon_no_room_above_waits", LightDragons.get(level, at) == null, null);
            level.setBlockAndUpdate(at.above(4), Blocks.AIR.defaultBlockState());
            for (var w : walls) level.setBlockAndUpdate(w, Blocks.AIR.defaultBlockState());
            LightDragons.reconcile(level, at);
            var old = LightDragons.get(level, at);
            level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            SelfTest.check(
                    "dragon_chest_break_immediate_removal",
                    old != null && old.isRemoved() && LightDragons.get(level, at) == null,
                    null);
            SelfTest.check(
                    "shop_rejects_missing_chest",
                    LightShop.purchase(p, at, 0, 0).startsWith("Return"),
                    null);
            level.setBlockAndUpdate(at, ModItems.LIGHT_CHEST.defaultBlockState());
            SelfTest.check("dragon_chest_replacement_returns", LightDragons.get(level, at) != null, null);
            level.clockManager().setTotalTicks(clock, 6000);
            LightDragons.reconcile(level, at);
            SelfTest.check(
                    "dragon_dawn_begins_gentle_departure",
                    LightDragons.get(level, at) != null
                            && LightDragons.get(level, at).departing(),
                    null);
        } finally {
            level.clockManager().setTotalTicks(clock, oldTime);
            LightDragons.remove(level, at);
            for (var b : List.of(at, at.offset(2, 1, 0), at.offset(-2, 1, 0), at.above(2)))
                level.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState());
            p.discard();
        }
    }

    private static void stationsAndSalvage(ServerLevel level, BlockPos chest) {
        var p = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "StationTest"));
        p.setPos(chest.getX() + .5, chest.getY(), chest.getZ() + 2.5);
        var station = ChestStations.Station.FURNACE;
        p.getInventory().setItem(0, new ItemStack(ModItems.SPARK, 40));
        boolean closed = !ChestStations.owned(p, station)
                && !ChestStations.purchase(p, chest, station.ordinal()).startsWith("Opened");
        p.setAttached(ChestTabs.NIGHTS_SURVIVED, 1);
        boolean bought = ChestStations.purchase(p, chest, station.ordinal()).startsWith("Opened");
        ChestStations.purchase(p, chest, station.ordinal());
        SelfTest.check(
                "chest_station_opens_once_for_20_sparks",
                closed && bought && ChestStations.owned(p, station) && LightShop.sparks(p) == 20,
                null);
        var playTime = net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME);
        p.getStats().setValue(p, playTime, ChestTabs.TRAVEL_PLAY_TICKS);
        boolean travelReady = !ChestTabs.travel(p);
        ChestStations.purchase(p, chest, ChestStations.Station.TRAVEL.ordinal());
        SelfTest.check(
                "chest_travel_requires_purchase_after_week",
                travelReady && ChestTabs.travel(p) && LightShop.sparks(p) == 0,
                null);

        var furnace = new ChestStations.Furnace(p);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON));
        furnace.setItem(1, new ItemStack(Items.COAL));
        var before = level.getBlockState(furnace.getBlockPos());
        for (int i = 0; i < 100; i++) furnace.tick();
        furnace = new ChestStations.Furnace(p);
        for (int i = 0; i < 100; i++) furnace.tick();
        SelfTest.check(
                "chest_furnace_smelts_through_save_without_placing_block",
                furnace.getItem(2).is(Items.IRON_INGOT)
                        && furnace.getItem(2).getCount() == 1
                        && level.getBlockState(furnace.getBlockPos()) == before,
                furnace.getItem(2));

        var loot = LootRoller.roll(p.getRandom(), LootRoller.Rarity.RARE, ModItems.Tier.IRON, ModItems.Kind.AXE);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, loot);
        var original = loot.copy();
        var mismatched = new ItemStack(Items.IRON_AXE);
        boolean refused = ChestSalvage.exchange(p, chest, mismatched) == 0;
        int payout = ChestSalvage.exchange(p, chest, original);
        SelfTest.check(
                "chest_salvage_prices_and_consumes_matching_held_gear",
                refused && payout == 2 && LightShop.sparks(p) == 2 && ChestSalvage.exchange(p, chest, original) == 0,
                payout);
    }
}
