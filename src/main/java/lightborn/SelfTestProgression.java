package lightborn;

import static lightborn.SelfTest.base;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Self-test checks for the no-experience rule, the death Spark drop, lapis enchanting and the anvil economy. */
final class SelfTestProgression {
    private SelfTestProgression() {}

    /**
     * There is no experience (owner, 2026-11-16): nothing raises vanilla's level or points, no orb ever appears, and a
     * death drops every carried Spark while gear is kept.
     */
    static void testExperience() {
        FakePlayer p = FakePlayer.get(
                level, new GameProfile(UUID.fromString("3c5e7a10-2b4d-4f6e-8a1c-9d0e1f2a3b4c"), "XpTester"));
        p.setPos(base.getX() + 0.5, base.getY(), base.getZ() + 6.5);
        p.getInventory().clearContent();
        p.giveExperiencePoints(30); // an orb, a bottle, a trade
        p.giveExperienceLevels(5);
        p.onEnchantmentPerformed(new ItemStack(Items.DIAMOND_SWORD), 3);
        check(
                "no_experience_ever",
                p.experienceLevel == 0 && p.totalExperience == 0 && p.experienceProgress == 0,
                p.experienceLevel + "/" + p.totalExperience + "/" + p.experienceProgress);
        Vec3 orbAt = Vec3.atCenterOf(base.offset(29, 1, 14));
        ExperienceOrb.award(level, orbAt, 7);
        ExperienceOrb.awardWithDirection(level, orbAt, new Vec3(0, 1, 0), 7);
        int awarded = level.getEntitiesOfClass(ExperienceOrb.class, new AABB(orbAt, orbAt).inflate(2))
                .size();
        ExperienceOrb stray = new ExperienceOrb(level, orbAt.x, orbAt.y, orbAt.z, 7);
        level.addFreshEntity(stray);
        stray.tick();
        check(
                "no_orbs_and_old_orbs_fade",
                awarded == 0 && stray.isRemoved(),
                awarded + " orbs, stray removed " + stray.isRemoved());

        // Anvil: a repair priced far past vanilla's 40 cap still works, at level 12, and costs nothing.
        AnvilMenu anvil = new AnvilMenu(0, p.getInventory(), ContainerLevelAccess.NULL);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamageValue(900);
        sword.set(DataComponents.REPAIR_COST, 60);
        anvil.getSlot(0).set(sword);
        anvil.getSlot(1).set(new ItemStack(Items.DIAMOND, 2));
        anvil.createResult();
        ItemStack out = anvil.getSlot(2).getItem();
        boolean takeable = anvil.getSlot(2).mayPickup(p);
        if (takeable) anvil.getSlot(2).onTake(p, out);
        check(
                "anvil_free",
                !out.isEmpty()
                        && out.getDamageValue() < 900
                        && anvil.getCost() == 0
                        && takeable
                        && p.experienceLevel == 0,
                out.getDamageValue() + " cost " + anvil.getCost() + " take " + takeable);

        // Grindstone: stripping an enchantment gives no XP (free enchanting would make it a lapis-to-XP mill).
        BlockPos grind = base.offset(24, 0, 8);
        GrindstoneMenu stone = new GrindstoneMenu(0, p.getInventory(), ContainerLevelAccess.create(level, grind));
        ItemStack enchanted = new ItemStack(Items.DIAMOND_SWORD);
        enchanted.enchant(
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 5);
        stone.getSlot(0).set(enchanted);
        ItemStack ground = stone.getSlot(2).getItem();
        if (!ground.isEmpty()) stone.getSlot(2).onTake(p, ground);
        int orbs = level.getEntitiesOfClass(ExperienceOrb.class, new AABB(grind).inflate(3))
                .size();
        check("grindstone_no_xp", !ground.isEmpty() && orbs == 0, ground.isEmpty() ? "no result" : orbs + " orbs");

        // Death burns half the carried Sparks (rounded up) and drops the rest where the player fell; gear stays.
        var rules = level.getGameRules();
        boolean keptByDefault = rules.get(GameRules.KEEP_INVENTORY);
        rules.set(GameRules.KEEP_INVENTORY, true, level.getServer());
        var lostAt = base.offset(20, 1, 11);
        p.setPos(Vec3.atBottomCenterOf(lostAt));
        p.getInventory().clearContent();
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        p.getInventory().setItem(5, new ItemStack(ModItems.SPARK, 5));
        p.getInventory().setItem(20, new ItemStack(ModItems.SPARK, 3));
        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.SPARK, 2));
        int dropped = Lightborn.dropSparks(p);
        var piles = level.getEntitiesOfClass(
                ItemEntity.class, new AABB(lostAt).inflate(3), e -> e.getItem().is(ModItems.SPARK));
        int onGround = piles.stream().mapToInt(e -> e.getItem().getCount()).sum();
        boolean kept = piles.stream().allMatch(e -> e.getAge() < 0);
        check(
                "death_burns_half_the_sparks_keeps_gear",
                keptByDefault
                        && dropped == 5
                        && onGround == 5
                        && kept
                        && Enchanting.count(p, ModItems.SPARK) == 0
                        && p.getInventory().getItem(0).is(Items.DIAMOND_SWORD),
                "keep gear=" + keptByDefault + ", dropped " + dropped + ", on ground " + onGround + ", kept " + kept
                        + ", left " + Enchanting.count(p, ModItems.SPARK));
        piles.forEach(Entity::discard);
        rules.set(GameRules.KEEP_INVENTORY, keptByDefault, level.getServer());
    }

    static void testEconomy() {
        FakePlayer p = FakePlayer.get(
                level, new GameProfile(UUID.fromString("d520ea20-ea51-4150-b001-000000000005"), "EconomyTester"));
        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().clearContent();
        BlockPos table = base.offset(35, 0, 10);
        level.setBlockAndUpdate(table, Blocks.ENCHANTING_TABLE.defaultBlockState());
        for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS)
            level.setBlockAndUpdate(table.offset(offset), Blocks.BOOKSHELF.defaultBlockState());
        var menu = new EnchantmentMenu(1, p.getInventory(), ContainerLevelAccess.create(level, table));
        menu.getSlot(0).set(new ItemStack(Items.DIAMOND_PICKAXE));
        menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 18));
        check("table_raw_strength", menu.costs[2] == 30, Arrays.toString(menu.costs));
        // Dark Shards are only for the Cleanse (owner, 2026-11-16): a shard in the pack is never touched.
        p.getInventory().setItem(0, new ItemStack(ModItems.DARK_SHARD, 2));
        menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 8));
        check(
                "table_top_needs_nine_lapis",
                !menu.clickMenuButton(p, 2)
                        && menu.getGoldCount() == 8
                        && !menu.getSlot(0).getItem().isEnchanted(),
                menu.getGoldCount());
        menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 18));
        int seed = p.getEnchantmentSeed();
        var allowed = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(EnchantmentTags.IN_ENCHANTING_TABLE);
        var expected = EnchantmentHelper.selectEnchantment(
                RandomSource.create(seed + 2), new ItemStack(Items.DIAMOND_PICKAXE), 30, allowed.stream());
        boolean paid = menu.clickMenuButton(p, 2);
        var actual =
                EnchantmentHelper.getEnchantmentsForCrafting(menu.getSlot(0).getItem());
        check(
                "table_native_strength_preserved_without_levels",
                paid
                        && !expected.isEmpty()
                        && actual.size() == expected.size()
                        && expected.stream().allMatch(e -> actual.getLevel(e.enchantment()) == e.level()),
                actual);
        check(
                "table_top_exact_payment_and_seed",
                menu.getGoldCount() == 9
                        && Enchanting.count(p, ModItems.DARK_SHARD) == 2
                        && seed != p.getEnchantmentSeed()
                        && p.experienceLevel == 0,
                menu.getGoldCount() + "/" + p.getEnchantmentSeed());
        check(
                "table_repeat_click_no_double_charge",
                !menu.clickMenuButton(p, 2)
                        && menu.getGoldCount() == 9
                        && Enchanting.count(p, ModItems.DARK_SHARD) == 2,
                menu.getGoldCount());
        check("table_invalid_buttons", !menu.clickMenuButton(p, -1) && !menu.clickMenuButton(p, 3), null);
        for (int slot = 0; slot < 2; slot++) {
            menu.getSlot(0).set(new ItemStack(Items.BOOK));
            menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, Enchanting.lapis(slot)));
            boolean ok = menu.clickMenuButton(p, slot);
            check(
                    "table_lower_price_" + slot,
                    ok
                            && menu.getGoldCount() == 0
                            && menu.getSlot(0).getItem().is(Items.ENCHANTED_BOOK)
                            && Enchanting.count(p, ModItems.DARK_SHARD) == 2,
                    menu.getSlot(0).getItem());
        }

        var sharpness =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        book.enchant(sharpness, 3);
        p.getInventory().clearContent();
        var anvil = new AnvilMenu(2, p.getInventory(), ContainerLevelAccess.NULL);
        anvil.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD));
        anvil.getSlot(1).set(book.copy());
        var price = (Enchanting.AnvilPrice) anvil;
        int fee = price.lightborn$lapis();
        check(
                "anvil_old_price_in_lapis",
                fee == anvil.getCost() && fee > 0 && !anvil.getSlot(2).mayPickup(p),
                fee);
        p.getInventory().setItem(0, new ItemStack(Items.LAPIS_LAZULI, fee - 1));
        check(
                "anvil_insufficient_lapis_blocks_shift_take",
                anvil.quickMoveStack(p, 2).isEmpty()
                        && anvil.getSlot(0).hasItem()
                        && anvil.getSlot(1).hasItem(),
                fee);
        p.getInventory().setItem(1, new ItemStack(Items.LAPIS_LAZULI, 1));
        check("anvil_count_multiple_stacks", anvil.getSlot(2).mayPickup(p), Enchanting.count(p, Items.LAPIS_LAZULI));
        ItemStack combined = anvil.quickMoveStack(p, 2);
        check(
                "anvil_shift_take_pays_once",
                !combined.isEmpty()
                        && combined.getEnchantments().getLevel(sharpness) == 3
                        && Enchanting.count(p, Items.LAPIS_LAZULI) == 0
                        && anvil.quickMoveStack(p, 2).isEmpty(),
                combined);
        int prior = combined.getOrDefault(DataComponents.REPAIR_COST, 0);
        anvil.getSlot(0).set(combined.copy());
        anvil.getSlot(1).set(book.copy());
        check(
                "anvil_prior_work_increases_lapis",
                price.lightborn$lapis() > fee
                        && anvil.getSlot(2).getItem().getOrDefault(DataComponents.REPAIR_COST, 0) == prior * 2 + 1,
                price.lightborn$lapis());
        for (int expensive : new int[] {40, Integer.MAX_VALUE}) {
            ItemStack veteran = new ItemStack(Items.DIAMOND_SWORD);
            veteran.set(DataComponents.REPAIR_COST, expensive);
            anvil.getSlot(0).set(veteran);
            anvil.getSlot(1).set(book.copy());
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(Items.LAPIS_LAZULI, 39));
            p.getInventory().setItem(1, new ItemStack(ModItems.DARK_SHARD));
            boolean take = price.lightborn$lapis() == 39 && anvil.getSlot(2).mayPickup(p);
            ItemStack result = anvil.quickMoveStack(p, 2);
            check(
                    "anvil_over_cap_lapis_only_" + expensive,
                    take
                            && !result.isEmpty()
                            && Enchanting.count(p, Items.LAPIS_LAZULI) == 0
                            && Enchanting.count(p, ModItems.DARK_SHARD) == 1,
                    result);
        }
        p.getInventory().clearContent();
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(DataComponents.REPAIR_COST, Integer.MAX_VALUE);
        anvil.getSlot(0).set(named);
        anvil.getSlot(1).set(ItemStack.EMPTY);
        anvil.setItemName("A free name");
        check(
                "anvil_rename_stays_free",
                price.lightborn$lapis() == 0 && anvil.getSlot(2).mayPickup(p),
                anvil.getCost());
        anvil.quickMoveStack(p, 2);
        ItemStack repair = combined.copy();
        repair.setDamageValue(1000);
        repair.set(DataComponents.REPAIR_COST, Integer.MAX_VALUE);
        anvil.getSlot(0).set(repair);
        anvil.getSlot(1).set(new ItemStack(Items.DIAMOND, 2));
        check(
                "anvil_enchanted_material_repair_free",
                price.lightborn$lapis() == 0
                        && anvil.getSlot(2).mayPickup(p)
                        && anvil.getSlot(2).getItem().getDamageValue() < 1000,
                anvil.getCost());

        // Probe both sides of the native packet boundary; raw prior-work penalties cannot be sent as prices.
        boolean shorts = true;
        for (int value : new int[] {32767, 32768, -32768, -32769, Integer.MAX_VALUE}) {
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                var codec = ClientboundContainerSetDataPacket.STREAM_CODEC;
                codec.encode(buf, new ClientboundContainerSetDataPacket(2, 1, value));
                shorts &= codec.decode(buf).getValue() == (short) value;
            } finally {
                buf.release();
            }
        }
        check("menu_price_packet_signed_short_boundary", shorts, null);
        Husk spawner = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
        spawner.setAttached(ModData.SPAWNER_MONSTER, true);
        Monsters.setRank(spawner, MonsterInfo.NORMAL);
        check(
                "spawner_no_rolled_drops",
                Lightborn.dropLoot(level, spawner, Monsters.info(spawner), p).isEmpty(),
                null);
        var mobOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        spawner.saveWithoutId(mobOutput);
        Husk reloaded = EntityTypes.HUSK.create(level, EntitySpawnReason.LOAD);
        reloaded.load(
                TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), mobOutput.buildResult()));
        check("spawner_origin_survives_save_reload", Monsters.fromSpawner(reloaded), null);
        Mob converted = reloaded.convertTo(
                EntityTypes.ZOMBIE,
                ConversionParams.single(reloaded, false, false),
                EntitySpawnReason.CONVERSION,
                mob -> {});
        check("spawner_conversion_keeps_penalty", converted != null && Monsters.fromSpawner(converted), null);
        if (converted != null) converted.discard();
        Mob trial = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.TRIAL_SPAWNER);
        check("trial_spawner_full_rewards", !Monsters.fromSpawner(trial), null);
        for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS)
            level.setBlockAndUpdate(table.offset(offset), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(table, Blocks.AIR.defaultBlockState());
    }
}
