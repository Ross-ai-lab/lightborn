package lightborn;

import static lightborn.SelfTest.base;
import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SelfTest.litChest;
import static lightborn.SelfTest.litCount;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.Vec3;

/** Self-test checks for the Chest of Light collection, its milestones and becoming the Light. */
final class SelfTestCollection {
    private SelfTestCollection() {}

    /** Chest of Light: every obtainable item, one of each taken, repeats and the unobtainable refused, sneak gives all, set rewards. */
    static void testCollection() {
        Collection.ensure(caster);
        var all = Collection.all();
        StringBuilder sets = new StringBuilder();
        for (var c : Collection.categories())
            sets.append(c.key()).append('=').append(c.items().size()).append(' ');
        Lightborn.LOG.info("SELFTEST collection sets: {}", sets);
        boolean everyday = all.containsAll(List.of(
                "minecraft:charcoal",
                "minecraft:spruce_planks",
                "minecraft:book",
                "minecraft:torch",
                "minecraft:flower_pot",
                "minecraft:diamond_sword",
                "minecraft:redstone"));
        check(
                "collection_catalogue",
                all.size() > 400
                        && Collection.categories().size() == 10
                        && all.size() > 999
                        && all.contains("minecraft:oak_stairs")
                        && all.contains("minecraft:red_banner")
                        && everyday
                        && !all.contains("lightborn:light_chest")
                        && Collection.categories().stream().noneMatch(c -> CuratedCollection.RETIRED.contains(c.key()))
                        && !all.contains("minecraft:bedrock")
                        && new HashSet<>(all).size() == all.size(),
                all.size() + " ids; " + sets);
        check(
                "light_chest_glows_to_its_wall",
                ModItems.LIGHT_CHEST.defaultBlockState().getLightEmission() == Balance.LIGHT_WARD_RADIUS + 1,
                null);
        // Twice the reach: hidden light blocks ring a placed chest, and go when it is broken (in afterSpells).
        litChest = base.offset(20, 0, 20);
        level.setBlockAndUpdate(litChest, ModItems.LIGHT_CHEST.defaultBlockState());
        litCount = 0;
        for (BlockPos q : BlockPos.betweenClosed(litChest.offset(-8, -2, -8), litChest.offset(8, 4, 8)))
            if (level.getBlockState(q).is(Blocks.LIGHT)) litCount++;

        caster.setAttached(ModData.COLLECTION, List.of());
        caster.getInventory().clearContent();
        BlockPos chest = base.offset(4, 0, 6);
        ItemStack logs = new ItemStack(Items.REDSTONE, 3);
        var first = Collection.offer(caster, logs, chest);
        var again = Collection.offer(caster, logs, chest);
        var sword = Collection.offer(caster, new ItemStack(Items.BEDROCK), chest);
        check(
                "collection_offer",
                first == Collection.Offer.TAKEN
                        && again == Collection.Offer.STORED
                        && sword == Collection.Offer.NOT_WANTED
                        && logs.getCount() == 1
                        && Collection.stored(caster, "minecraft:redstone").getCount() == 2
                        && Collection.collected(caster).contains("minecraft:redstone"),
                first + " " + again + " " + sword + " " + logs.getCount());
        // Put in: every carried dirt goes in up to one stack; taking out leaves it collected.
        caster.setAttached(ModData.STORAGE, List.of());
        caster.getInventory().setItem(0, new ItemStack(Items.DIRT, 50));
        caster.getInventory().setItem(1, new ItemStack(Items.SAND, 1));
        caster.getInventory().setItem(2, new ItemStack(Items.DIRT, 30));
        int put = Collection.deposit(caster, "minecraft:dirt", chest);
        int keptDirt = Collection.stored(caster, "minecraft:dirt").getCount();
        // The first gift pays a Spark, which may land in the emptied slot.
        boolean leftOver = !caster.getInventory().getItem(0).is(Items.DIRT)
                && caster.getInventory().getItem(2).getCount() == 16
                && caster.getInventory().getItem(1).getCount() == 1;
        check(
                "collection_deposit",
                put == 64
                        && keptDirt == 64
                        && leftOver
                        && Collection.collected(caster).contains("minecraft:dirt"),
                "put=" + put + " kept=" + keptDirt);
        caster.getInventory().clearContent();
        int one = Collection.withdraw(caster, "minecraft:dirt", false, chest);
        int rest = Collection.withdraw(caster, "minecraft:dirt", true, chest);
        int carried = 0;
        for (int i = 0; i < caster.getInventory().getContainerSize(); i++)
            if (caster.getInventory().getItem(i).is(Items.DIRT))
                carried += caster.getInventory().getItem(i).getCount();
        check(
                "collection_withdraw",
                one == 1
                        && rest == 63
                        && carried == 64
                        && Collection.stored(caster, "minecraft:dirt").isEmpty()
                        && Collection.collected(caster).contains("minecraft:dirt"),
                one + "+" + rest + " carried=" + carried);
        caster.getInventory().clearContent();
        caster.setAttached(ModData.STORAGE, List.of());

        // Finishing the Lightborn set grants +80 max mana for good; losing it (a reset) takes it back.
        var lightborn = Collection.categories().get(Collection.categories().size() - 1);
        double manaBefore = caster.getAttributeValue(ModAttributes.MAX_MANA);
        List<String> almost =
                new ArrayList<>(lightborn.items().subList(1, lightborn.items().size()));
        caster.setAttached(ModData.COLLECTION, almost);
        Collection.offer(caster, new ItemStack(Collection.item(lightborn.items().get(0))), chest);
        double manaAfter = caster.getAttributeValue(ModAttributes.MAX_MANA);
        caster.setAttached(ModData.COLLECTION, List.of());
        PlayerBonuses.apply(caster);
        double manaReset = caster.getAttributeValue(ModAttributes.MAX_MANA);
        check(
                "collection_set_reward",
                lightborn.key().equals("lightborn:items")
                        && Math.abs(manaAfter - manaBefore - 80) < 0.01
                        && Math.abs(manaReset - manaBefore) < 0.01,
                lightborn.key() + " " + manaBefore + " -> " + manaAfter + " -> " + manaReset);
        caster.getInventory().clearContent();
        caster.setAttached(ModData.STORAGE, List.of());
        testCrafting(chest);
        testCollectionPhase3();
    }

    private static void testCrafting(BlockPos chest) {
        caster.getInventory().clearContent();
        caster.setAttached(ModData.STORAGE, List.of(new ItemStack(Items.OAK_PLANKS, 3)));
        caster.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS));
        var ask = new ChestCrafting.Ask(
                chest, false, "crafting_table", "minecraft:crafting_table", List.of(), 0, ChestCrafting.BROWSE);
        var offered = ChestCrafting.handle(caster, ask);
        var result = ChestCrafting.handle(
                caster, new ChestCrafting.Ask(chest, false, "", offered.matched(), List.of(), 0, ChestCrafting.CRAFT));
        check(
                "crafting_from_store_and_inventory",
                offered.output().is(Items.CRAFTING_TABLE)
                        && result.output().isEmpty()
                        && Enchanting.count(caster, Items.CRAFTING_TABLE) == 1
                        && Enchanting.count(caster, Items.OAK_PLANKS) == 0
                        && Collection.stored(caster, "minecraft:oak_planks").isEmpty(),
                result.message());

        caster.getInventory().clearContent();
        caster.setAttached(
                ModData.STORAGE,
                List.of(
                        new ItemStack(Items.WHEAT, 3),
                        new ItemStack(Items.SUGAR, 2),
                        new ItemStack(Items.EGG),
                        new ItemStack(Items.MILK_BUCKET)));
        caster.getInventory().setItem(0, new ItemStack(Items.MILK_BUCKET));
        caster.getInventory().setItem(1, new ItemStack(Items.MILK_BUCKET));
        var cells = List.of(
                "minecraft:milk_bucket",
                "@0",
                "@1",
                "minecraft:sugar",
                "minecraft:egg",
                "minecraft:sugar",
                "minecraft:wheat",
                "minecraft:wheat",
                "minecraft:wheat");
        offered = ChestCrafting.handle(
                caster, new ChestCrafting.Ask(chest, true, "", "", cells, 0, ChestCrafting.BROWSE));
        result = ChestCrafting.handle(
                caster, new ChestCrafting.Ask(chest, true, "", offered.matched(), cells, 0, ChestCrafting.CRAFT));
        check(
                "crafting_manual_remainders",
                offered.output().is(Items.CAKE)
                        && Enchanting.count(caster, Items.BUCKET) == 3
                        && Enchanting.count(caster, Items.CAKE) == 1
                        && Collection.storage(caster).isEmpty(),
                result.message());
        caster.getInventory().clearContent();
        caster.setAttached(ModData.STORAGE, List.of());
    }

    private static void testCollectionPhase3() {
        var p = FakePlayer.get(
                level, new GameProfile(UUID.fromString("2f8ec9ab-8136-4398-9579-094297971874"), "CollectionTester"));
        p.setPos(Vec3.atBottomCenterOf(base.offset(26, 0, -20)));
        var natural = Collection.categories().stream()
                .filter(c -> c.key().equals(CuratedCollection.EARTH))
                .findFirst()
                .orElseThrow();
        double initialHealth = p.getAttributeValue(Attributes.MAX_HEALTH);
        boolean milestones = true;
        for (int quarter = 0; quarter <= 4; quarter++) {
            int count = (natural.items().size() * quarter + 3) / 4;
            p.setAttached(ModData.COLLECTION, natural.items().subList(0, count));
            PlayerBonuses.apply(p);
            milestones &= Collection.quarters(Collection.collected(p), natural) == quarter
                    && Math.abs(p.getAttributeValue(Attributes.MAX_HEALTH) - initialHealth - quarter) < 0.001;
        }
        check("collection_quarter_rewards_migrate", milestones, null);
        p.setAttached(ModData.COLLECTION, List.of());
        p.setAttached(ModData.STORAGE, List.of());
        p.getInventory().clearContent();
        PlayerBonuses.apply(p);
        var chest = base.offset(26, 0, -20);
        level.setBlockAndUpdate(chest, ModItems.LIGHT_CHEST.defaultBlockState());
        p.getInventory().setItem(0, new ItemStack(Items.DIRT, 5));
        p.getInventory().setItem(1, new ItemStack(Items.DIRT, 3));
        p.getInventory().setItem(2, new ItemStack(Items.REDSTONE, 2));
        int bulk = Collection.giveAllNew(p, chest);
        boolean newTooltip = !Collection.newForLight(p, new ItemStack(Items.DIRT))
                && Collection.newForLight(p, new ItemStack(Items.SAND))
                && !Collection.newForLight(p, new ItemStack(Items.BEDROCK));
        int sparks = p.getInventory().countItem(ModItems.SPARK);
        check("collection_first_gifts_pay_sparks", sparks == 2 * Balance.COLLECTION_GIFT_SPARKS, sparks);
        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
            if (p.getInventory().getItem(i).is(ModItems.SPARK)) p.getInventory().setItem(i, ItemStack.EMPTY);
        check(
                "collection_give_all_new_once",
                bulk == 2
                        && p.getInventory().getItem(0).getCount() == 4
                        && p.getInventory().getItem(1).getCount() == 3
                        && p.getInventory().getItem(2).getCount() == 1
                        && Collection.giveAllNew(p, chest) == 0
                        && p.totalExperience == 0
                        && newTooltip,
                bulk + " gifts, experience " + p.totalExperience);
        p.getInventory().clearContent();
        p.getInventory().setItem(0, new ItemStack(Items.SEA_PICKLE, 3));
        var protectedItems = List.of(
                new ItemStack(Items.IRON_PICKAXE),
                new ItemStack(Items.IRON_SWORD),
                new ItemStack(Items.APPLE),
                new ItemStack(Items.ROTTEN_FLESH),
                new ItemStack(Items.TORCH),
                new ItemStack(ModItems.SPARK),
                new ItemStack(ModItems.STARTER_STAFF),
                new ItemStack(ModItems.TOMES.get(Spell.HOMEWARD)),
                SpawnHut.starterStone(),
                new ItemStack(DragonStory.LIGHT_KEY));
        for (int i = 0; i < protectedItems.size(); i++)
            p.getInventory().setItem(i + 1, protectedItems.get(i).copy());
        p.getInventory().setItem(11, new ItemStack(Items.DIRT, 64)); // One dirt is already in the chest.
        ItemStack namedPickle = new ItemStack(Items.SEA_PICKLE);
        namedPickle.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
        p.getInventory().setItem(12, namedPickle);
        p.getInventory().setItem(13, new ItemStack(Items.SPIDER_EYE, 2));
        int sparksBefore = p.getInventory().countItem(ModItems.SPARK);
        int selective = Collection.giveNonEssential(p, chest);
        boolean protectedInPlace = true;
        for (int i = 0; i < protectedItems.size(); i++)
            protectedInPlace &=
                    p.getInventory().getItem(i + 1).is(protectedItems.get(i).getItem());
        check(
                "collection_give_non_essential_only",
                selective == 68
                        && protectedInPlace
                        && p.getInventory().getItem(0).isEmpty()
                        && p.getInventory().getItem(11).getCount() == 1
                        && p.getInventory().getItem(12).has(DataComponents.CUSTOM_NAME)
                        && Collection.stored(p, "minecraft:dirt").getCount() == 64
                        && Collection.stored(p, "minecraft:sea_pickle").getCount() == 3
                        && Collection.stored(p, "minecraft:spider_eye").getCount() == 2
                        && p.getInventory().countItem(ModItems.SPARK)
                                == sparksBefore + 2 * Balance.COLLECTION_GIFT_SPARKS,
                selective + " items moved");
        p.getInventory().setItem(0, new ItemStack(Items.SEA_PICKLE, 2));
        int repeats = Collection.giveNonEssential(p, chest);
        check(
                "collection_repeat_bulk_gifts_only_fill_storage",
                repeats == 2
                        && Collection.stored(p, "minecraft:sea_pickle").getCount() == 5
                        && p.getInventory().countItem(ModItems.SPARK)
                                == sparksBefore + 2 * Balance.COLLECTION_GIFT_SPARKS
                        && Collection.giveNonEssential(p, chest) == 0
                        && Collection.giveAllNew(p, chest) > 0,
                repeats + " repeat items moved");
        p.setAttached(ModData.COLLECTION, List.of());
        p.setAttached(ModData.STORAGE, List.of());
        // The ward grows only when bought with Sparks (owner, 2026-11-16), never from gifts.
        boolean growth = true;
        p.setAttached(ModData.COLLECTION, Collection.all().subList(0, 500));
        LightWards.updateOwned(p);
        growth &= LightWards.radiusFor(p) == Balance.WARD_RADII[0];
        for (int i = 0; i < Balance.WARD_PRICES.length; i++) {
            p.getInventory().clearContent();
            p.getInventory().add(new ItemStack(ModItems.SPARK, Balance.WARD_PRICES[i] - 1));
            String poor = LightWards.grow(p, chest);
            growth &= LightWards.tier(p) == i && poor.contains("need");
            p.getInventory().add(new ItemStack(ModItems.SPARK, 1));
            LightWards.grow(p, chest);
            int r = Balance.WARD_RADII[i + 1];
            growth &= LightWards.tier(p) == i + 1
                    && Enchanting.count(p, ModItems.SPARK) == 0
                    && LightWards.covers(level, chest.east(r))
                    && !LightWards.covers(level, chest.east(r + 1));
        }
        growth &= LightWards.grow(p, chest).contains("as wide");
        check("collection_ward_growth_saved", growth, null);
        var small = FakePlayer.get(
                level, new GameProfile(UUID.fromString("ba1c8a3e-c7b4-448d-9dba-d6628b914fa2"), "WardPartner"));
        var smallChest = chest.east(15);
        level.setBlockAndUpdate(smallChest, ModItems.LIGHT_CHEST.defaultBlockState());
        LightWards.claim(level, smallChest, small);
        check("collection_overlapping_ward", LightWards.covers(level, smallChest.west(40)), null);
        level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
        check("collection_owner_ward_removed", !LightWards.covers(level, smallChest.west(40)), null);
        level.setBlockAndUpdate(smallChest, Blocks.AIR.defaultBlockState());

        p.setAttached(DragonStory.CHAPTERS, 1);
        p.setAttached(DragonStory.CHAPTERS, 1);
        CollectionLedger.read(p, new ItemStack(Items.WRITTEN_BOOK));
        var snapshot = CollectionLedger.snapshot(p);
        check(
                "collection_ledger_history",
                snapshot.journal() == 1
                        && snapshot.journalTotal() == 9
                        && snapshot.regions() == 0
                        && snapshot.regionTotal() == 0
                        && snapshot.bestiaryTotal() == 0
                        && snapshot.wardRadius() == 48,
                snapshot);
        var buffer = Unpooled.buffer();
        try {
            var packet = new Net.LedgerShow(chest, snapshot);
            Net.LedgerShow.CODEC.encode(buffer, packet);
            check(
                    "collection_ledger_packet",
                    packet.equals(Net.LedgerShow.CODEC.decode(buffer)) && buffer.readableBytes() == 0,
                    null);
        } finally {
            buffer.release();
        }
        testCompletedLight(p, small);
    }

    private static void testCompletedLight(FakePlayer p, FakePlayer partner) {
        p.getInventory().clearContent();
        var missing = Collection.all().getFirst();
        p.setAttached(
                ModData.COLLECTION, Collection.all().subList(1, Collection.all().size()));
        p.setAttached(ModData.STORAGE, List.of());
        PlayerBonuses.apply(p);
        Collection.offer(p, new ItemStack(Collection.item(missing)), p.blockPosition());
        PlayerBonuses.apply(p);
        int stacks = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
            if (!p.getInventory().getItem(i).isEmpty()) stacks++;
        check(
                "collection_finale_once_without_random_book",
                // The only thing left in hand is the first gift's Spark: no random book.
                Boolean.TRUE.equals(p.getAttached(ModData.LIGHT_COMPLETE))
                        && p.getInventory().countItem(ModItems.SPARK) == 1
                        && stacks == 1,
                null);
        p.setPos(Vec3.atBottomCenterOf(base.above(20)));
        var head = BlockPos.containing(p.getEyePosition());
        CollectionLight.tickPlayer(p);
        boolean moving = level.getBlockState(head).is(Blocks.LIGHT)
                && level.getBlockState(head).getValue(LightBlock.LEVEL) == 15;
        p.setPos(p.position().add(2, 0, 0));
        CollectionLight.tickPlayer(p);
        var moved = BlockPos.containing(p.getEyePosition());
        moving &=
                level.getBlockState(head).isAir() && level.getBlockState(moved).is(Blocks.LIGHT);
        partner.setAttached(ModData.LIGHT_COMPLETE, true);
        partner.setPos(p.position());
        CollectionLight.tickPlayer(partner);
        CollectionLight.release(p);
        moving &= level.getBlockState(moved).is(Blocks.LIGHT);
        CollectionLight.release(partner);
        moving &= level.getBlockState(moved).isAir();
        check("collection_light_moves_and_shares", moving, null);
        level.setBlockAndUpdate(moved, Blocks.WATER.defaultBlockState());
        CollectionLight.tickPlayer(p);
        boolean water = level.getBlockState(moved).is(Blocks.LIGHT)
                && level.getBlockState(moved).getValue(LightBlock.WATERLOGGED);
        CollectionLight.release(p);
        water &= level.getBlockState(moved).is(Blocks.WATER);
        level.setBlockAndUpdate(moved, Blocks.AIR.defaultBlockState());
        CollectionLight.tickPlayer(p);
        level.setBlockAndUpdate(moved, Blocks.STONE.defaultBlockState());
        CollectionLight.release(p);
        check(
                "collection_light_preserves_edits",
                water && level.getBlockState(moved).is(Blocks.STONE),
                null);
        level.setBlockAndUpdate(moved, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15));
        var chunk = level.getChunkAt(moved);
        chunk.setAttached(
                ModData.COLLECTION_LIGHTS, List.of(new CollectionLight.Placed(moved, Blocks.AIR.defaultBlockState())));
        CollectionLight.onChunkLoad(level, chunk, false);
        check(
                "collection_light_recovers_orphans",
                level.getBlockState(moved).isAir()
                        && chunk.getAttachedOrElse(ModData.COLLECTION_LIGHTS, List.of())
                                .isEmpty(),
                null);
    }
}
