package lightborn;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Set;
import lightborn.test.Soft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;

/** Native menus and packets, real block-spawner provenance and real death-orb recovery. */
public class EconomyTest implements FabricClientGameTest {
    private static void require(boolean ok, String why) {
        Soft.require(ok, why);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1600, 900);
        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .adjustSettings(s -> s.setDifficulty(Difficulty.NORMAL))
                .create()) {
            ctx.waitTicks(100);
            BlockPos table = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                var at = level.getRespawnData().pos().offset(64, 0, 64).atY(200);
                for (int x = -12; x <= 12; x++)
                    for (int z = -12; z <= 12; z++)
                        level.setBlockAndUpdate(at.offset(x, -1, z), Blocks.DEEPSLATE.defaultBlockState());
                level.setBlockAndUpdate(at, Blocks.ENCHANTING_TABLE.defaultBlockState());
                for (var offset : EnchantingTableBlock.BOOKSHELF_OFFSETS)
                    level.setBlockAndUpdate(at.offset(offset), Blocks.BOOKSHELF.defaultBlockState());
                level.setBlockAndUpdate(at.offset(5, 0, 0), Blocks.ANVIL.defaultBlockState());
                var p = server.getPlayerList().getPlayers().getFirst();
                p.teleportTo(level, at.getX() + 4.5, at.getY(), at.getZ() + 3.5, Set.of(), 90f, 10f, false);
                p.setGameMode(GameType.SURVIVAL);
                p.getInventory().clearContent();
                return at;
            });
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(3);
                mc.resizeGui();
                var pages = SpawnHut.guideBook()
                        .get(DataComponents.WRITTEN_BOOK_CONTENT)
                        .pages();
                for (int i : new int[] {1, 2})
                    require(
                            mc.font.split(pages.get(i).raw(), 114).size() <= 14,
                            "Phase 5 guide page overflows the book");
            });
            sp.getServer().runCommand("time set 18000");
            sp.getServer().runCommand("title @a clear");
            sp.getServer().runOnServer(server -> {
                var level = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                p.openMenu(level.getBlockState(table).getMenuProvider(level, table));
                var menu = (EnchantmentMenu) p.containerMenu;
                menu.getSlot(0).set(new ItemStack(Items.DIAMOND_PICKAXE));
                menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 18));
            });
            ctx.waitTicks(20);
            hover(ctx, 122, 60);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                // A Dark Shard in the pack is left alone: shards are only for the Cleanse (owner, 2026-11-16).
                p.getInventory().setItem(0, new ItemStack(ModItems.DARK_SHARD, 2));
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("phase5_table_paid_top_tier");
            ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            ctx.waitTicks(10);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                var menu = (EnchantmentMenu) p.containerMenu;
                require(
                        menu.getGoldCount() == 9
                                && menu.getSlot(0).getItem().isEnchanted()
                                && Enchanting.count(p, ModItems.DARK_SHARD) == 2,
                        "Native table payment failed");
                require(p.experienceLevel == 0 && p.totalExperience == 0, "Enchanting needed or touched experience");
                p.closeContainer();
            });
            ctx.waitTicks(20);
            BlockPos anvil = table.offset(5, 0, 0);
            openAnvil(ctx, sp, anvil, 0, 2, 0);
            ctx.takeScreenshot("phase5_anvil_missing_lapis");
            quickTake(ctx);
            ctx.waitTicks(8);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                var menu = (AnvilMenu) p.containerMenu;
                require(
                        menu.getSlot(2).hasItem() && Enchanting.count(p, Items.LAPIS_LAZULI) == 2,
                        "Unpaid anvil shift-click changed resources");
                p.getInventory().setItem(0, new ItemStack(Items.LAPIS_LAZULI, 3));
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("phase5_anvil_three_lapis");
            quickTake(ctx);
            ctx.waitTicks(10);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                require(
                        !p.containerMenu.getSlot(2).hasItem()
                                && Enchanting.count(p, Items.LAPIS_LAZULI) == 0
                                && p.totalExperience == 0,
                        "Paid native anvil shift-click failed");
                p.closeContainer();
            });
            ctx.waitTicks(20);
            openAnvil(ctx, sp, anvil, Integer.MAX_VALUE, 39, 1);
            ctx.runOnClient(mc -> {
                var price = (Enchanting.AnvilPrice) mc.player.containerMenu;
                require(
                        price.lightborn$lapis() == 39
                                && mc.player.containerMenu.getSlot(2).mayPickup(mc.player),
                        "Capped price failed across the menu network sync");
            });
            ctx.takeScreenshot("phase5_anvil_lapis_cap");
            quickTake(ctx);
            ctx.waitTicks(10);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                require(
                        Enchanting.count(p, Items.LAPIS_LAZULI) == 0
                                && Enchanting.count(p, ModItems.DARK_SHARD) == 1
                                && p.totalExperience == 0,
                        "Capped anvil payment failed");
                p.closeContainer();
            });
            ctx.waitTicks(20);
            System.out.println("LIGHTBORN PHASE5 REAL MENUS PASS");

            BlockPos spawner = table.offset(8, 0, 6);
            sp.getServer().runOnServer(server -> {
                var level = server.overworld();
                level.setBlockAndUpdate(spawner, Blocks.SPAWNER.defaultBlockState());
                var baseSpawner = ((SpawnerBlockEntity) level.getBlockEntity(spawner)).getSpawner();
                baseSpawner.setEntityId(EntityTypes.HUSK, level, level.getRandom(), spawner);
                CompoundTag data = new CompoundTag();
                data.putShort("Delay", (short) 0);
                data.putInt("MinSpawnDelay", 10000);
                data.putInt("MaxSpawnDelay", 10001);
                data.putInt("SpawnCount", 1);
                data.putInt("SpawnRange", 2);
                data.putInt("RequiredPlayerRange", 16);
                baseSpawner.load(
                        level, spawner, TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
            });
            ctx.waitTicks(100);
            sp.getServer().runOnServer(server -> {
                var level = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                var spawned = level.getEntitiesOfClass(Husk.class, new AABB(spawner).inflate(9));
                require(
                        !spawned.isEmpty() && spawned.stream().allMatch(Monsters::fromSpawner),
                        "A real block spawner lost provenance");
                var mob = spawned.getFirst();
                mob.setNoAi(true);
                mob.teleportTo(spawner.getX() + .5, spawner.getY(), spawner.getZ() + .5);
                Monsters.setRank(mob, MonsterInfo.NORMAL);
                mob.hurtServer(level, p.damageSources().playerAttack(p), 10000);
                require(p.totalExperience == 0, "Spawner kill paid XP");
                var orbs = level.getEntitiesOfClass(ExperienceOrb.class, new AABB(spawner).inflate(3));
                require(orbs.isEmpty(), "Spawner kill dropped XP orbs: " + orbs.size());
                for (var orb : orbs) orb.discard();
                level.setBlockAndUpdate(spawner, Blocks.AIR.defaultBlockState());
            });
            System.out.println("LIGHTBORN PHASE5 REAL SPAWNER PASS");
            sp.getServer().runOnServer(server -> {
                var level = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                // A real death keeps the gear, burns half the carried Sparks (17 -> 9 rounded up) and drops the other
                // 8.
                boolean keep = level.getGameRules().get(GameRules.KEEP_INVENTORY);
                level.getGameRules().set(GameRules.KEEP_INVENTORY, true, server);
                p.getInventory().clearContent();
                p.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
                p.getInventory().setItem(4, new ItemStack(ModItems.SPARK, 12));
                p.getInventory().setItem(30, new ItemStack(ModItems.SPARK, 5));
                p.kill(level);
                require(p.isDeadOrDying(), "Player did not die");
                var sparks = level.getEntitiesOfClass(
                        ItemEntity.class,
                        p.getBoundingBox().inflate(3),
                        e -> e.getItem().is(ModItems.SPARK));
                int dropped =
                        sparks.stream().mapToInt(e -> e.getItem().getCount()).sum();
                require(
                        dropped == 8
                                && sparks.stream().allMatch(e -> e.getAge() < 0)
                                && Enchanting.count(p, ModItems.SPARK) == 0
                                && p.getInventory().getItem(0).is(Items.DIAMOND_SWORD),
                        "Death did not drop exactly half the carried Sparks (8): " + dropped);
                require(
                        level.getEntitiesOfClass(
                                        ExperienceOrb.class, p.getBoundingBox().inflate(4))
                                .isEmpty(),
                        "Death dropped experience orbs");
                level.getGameRules().set(GameRules.KEEP_INVENTORY, keep, server);
            });
            System.out.println("LIGHTBORN PHASE5 REAL DEATH SPARKS PASS");
        }
    }

    private static void hover(ClientGameTestContext ctx, int x, int y) {
        var point = ctx.computeOnClient(mc -> {
            var screen = mc.gui.screen();
            double scale = mc.getWindow().getGuiScale();
            return new double[] {((screen.width - 176) / 2 + x) * scale, ((screen.height - 166) / 2 + y) * scale};
        });
        ctx.getInput().setCursorPos(point[0], point[1]);
        ctx.waitTicks(4);
    }

    private static void quickTake(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(
                mc.player.containerMenu.containerId, 2, 0, ContainerInput.QUICK_MOVE, mc.player));
    }

    private static void openAnvil(
            ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos at, int prior, int lapis, int shards) {
        sp.getServer().runOnServer(server -> {
            var level = server.overworld();
            var p = server.getPlayerList().getPlayers().getFirst();
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(Items.LAPIS_LAZULI, lapis));
            if (shards > 0) p.getInventory().setItem(1, new ItemStack(ModItems.DARK_SHARD, shards));
            // Each operation gets a fresh anvil, so vanilla chipping cannot make the fixture disappear.
            level.setBlockAndUpdate(at, Blocks.ANVIL.defaultBlockState());
            p.openMenu(level.getBlockState(at).getMenuProvider(level, at));
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.set(DataComponents.REPAIR_COST, prior);
            ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
            book.enchant(
                    level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3);
            p.containerMenu.getSlot(0).set(sword);
            p.containerMenu.getSlot(1).set(book);
        });
        ctx.waitTicks(20);
    }
}
