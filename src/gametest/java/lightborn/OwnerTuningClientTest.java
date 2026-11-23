package lightborn;

import java.util.List;
import java.util.Set;
import lightborn.client.CollectionScreen;
import lightborn.test.Soft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Fresh-world starter, live quarter rewards, broken-item rendering and the two companion mods. */
public class OwnerTuningClientTest implements FabricClientGameTest {
    private static void require(boolean ok, String why) {
        Soft.require(ok, why);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1600, 900);
        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .adjustSettings(s -> s.setDifficulty(Difficulty.HARD))
                .create()) {
            ctx.waitTicks(100);
            BlockPos inside = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                require(
                        level.getDifficulty() == Difficulty.NORMAL
                                && server.getWorldData().isDifficultyLocked(),
                        "Fresh world difficulty is not locked Normal");
                require(
                        p.getMainHandItem().is(ModItems.STARTER_STAFF)
                                && TomeItem.spellOf(p.getOffhandItem()) == Spell.ARCANE_BOLT
                                && Enchanting.count(p, Items.APPLE) == 10
                                && Enchanting.count(p, Items.TORCH) == 10
                                && Enchanting.count(p, Items.WRITTEN_BOOK) == 0,
                        "Wrong starter kit");
                require(
                        Math.abs(p.getAttributeValue(ModAttributes.SPELL_POWER) - 1) < .001
                                && p.getAttributeValue(ModAttributes.LIFE_LEECH) == 0,
                        "Starting staff adds a buff");
                level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                return level.getRespawnData().pos();
            });
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(3);
                mc.resizeGui();
            });
            sp.getServer().runCommand("time set 6000");
            sp.getServer().runCommand("title @a clear");
            teleport(sp, inside.getX() + .5, inside.getY(), inside.getZ() + 1.25, 180, 12);
            ctx.waitTicks(40);
            ctx.takeScreenshot("owner_hut_inside");
            ctx.runOnClient(mc -> mc.gui.setScreen(new InventoryScreen(mc.player)));
            ctx.waitTicks(5);
            hoverInventory(ctx, true);
            ctx.takeScreenshot("owner_plain_starter_staff");
            ctx.setScreen(() -> null);
            ctx.waitTicks(5);
            teleport(sp, inside.getX() + .5, inside.getY() + 1, inside.getZ() + 9.5, 180, 8);
            ctx.waitTicks(35);
            ctx.takeScreenshot("owner_hut_outside_clear_yard");
            teleport(sp, inside.getX() + .5, inside.getY(), inside.getZ() + .5, 180, 5);
            ctx.waitTicks(35);
            BlockPos chest = SpawnHut.lightChestAt(inside);
            for (int earned = 0; earned <= 2; earned++) {
                final int quarter = earned;
                sp.getServer().runOnServer(server -> {
                    var p = server.getPlayerList().getPlayers().getFirst();
                    Collection.ensure(p);
                    var group = Collection.categories().getFirst();
                    int count = (group.items().size() * quarter + 3) / 4;
                    p.setAttached(ModData.COLLECTION, List.copyOf(group.items().subList(0, count)));
                    PlayerBonuses.apply(p);
                });
                ctx.waitTicks(8);
                ctx.runOnClient(mc -> {
                    var screen = new CollectionScreen(chest);
                    screen.select(0);
                    mc.gui.setScreen(screen);
                });
                ctx.waitTicks(5);
                ctx.takeScreenshot("owner_next_milestone_" + (earned + 1) * 25);
                ctx.setScreen(() -> null);
                ctx.waitTicks(3);
            }
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                p.setAttached(ModData.COLLECTION, List.of());
                PlayerBonuses.apply(p);
                Progression.setMana(p, 80);
            });
            ctx.waitTicks(8);
            System.out.println("LIGHTBORN OWNER STARTER PASS");

            BlockPos arena = inside.offset(64, 0, 64).atY(200);
            sp.getServer().runOnServer(server -> {
                var level = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                p.getInventory().clearContent();
                for (int x = -16; x <= 16; x++)
                    for (int z = -16; z <= 16; z++)
                        level.setBlockAndUpdate(arena.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState());
            });
            teleport(sp, arena.getX() + .5, arena.getY(), arena.getZ() + 9.5, 180, 7);
            ctx.waitTicks(40);
            sp.getServer().runOnServer(server -> {
                var level = server.overworld();
                String[] kinds = {"elephant", "deer", "bear", "capybara", "bird"};
                for (int i = 0; i < kinds.length; i++) {
                    var type = BuiltInRegistries.ENTITY_TYPE.getValue(
                            Identifier.fromNamespaceAndPath("naturalist", kinds[i]));
                    var animal = (Mob) type.create(level, EntitySpawnReason.COMMAND);
                    animal.setPos(arena.getX() - 5 + i * 2.5, arena.getY(), arena.getZ());
                    animal.setNoAi(true);
                    animal.setYRot(0);
                    animal.setYHeadRot(0);
                    level.addFreshEntity(animal);
                }
            });
            ctx.waitTicks(12);
            ctx.takeScreenshot("owner_naturalist_animals");
            sp.getServer().runOnServer(server -> {
                var level = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                p.getFoodData().setFoodLevel(16);
                p.setHealth(p.getMaxHealth());
                p.damageCooldownTime = 0;
                var source = server.createCommandSourceStack()
                        .withEntity(p)
                        .withPosition(new Vec3(arena.getX() + .5, arena.getY(), arena.getZ() - 5.5))
                        .withLevel(level);
                server.getCommands()
                        .performPrefixedCommand(
                                source,
                                "function lunareclipse.watching:sightings/custom/summon {position:'still',max_distance:9999,vanishing_delay:0.5,spot_fov:0,jumpscare:'false',despawn_timer:30,face_player:'true',light_reactant:'false',sneaky_strike:'false'}");
            });
            ctx.waitTicks(100);
            ctx.takeScreenshot("owner_passive_herobrine");
            sp.getServer().runOnServer(server -> {
                var level = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                require(p.getHealth() == p.getMaxHealth(), "Watcher initiated damage");
                var props = level.getAllEntities();
                boolean seen = false;
                for (var e : props) if (e.entityTags().contains("watching.model")) seen = true;
                require(seen, "Watcher model did not spawn");
                float before = p.getHealth();
                server.getCommands()
                        .performPrefixedCommand(
                                server.createCommandSourceStack()
                                        .withEntity(p)
                                        .withPosition(p.position())
                                        .withLevel(level),
                                "function lunareclipse.watching:sightings/hit_herobrine");
                require(Math.abs(p.getHealth() - before + 2) < .01, "Approved retaliation was unexpectedly removed");
            });
            System.out.println("LIGHTBORN OWNER ANIMALS AND PASSIVE STALKER PASS");
        }
    }

    private static void teleport(TestSingleplayerContext sp, double x, double y, double z, float yaw, float pitch) {
        sp.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.teleportTo(server.overworld(), x, y, z, Set.of(), yaw, pitch, false);
        });
    }

    private static void hoverInventory(ClientGameTestContext ctx, boolean staff) {
        var point = ctx.computeOnClient(mc -> {
            var screen = mc.gui.screen();
            var slots = mc.player.inventoryMenu.slots;
            var slot = slots.stream()
                    .filter(s -> staff
                            ? s.getItem().is(ModItems.STARTER_STAFF)
                            : TomeItem.spellOf(s.getItem()) == Spell.ARCANE_BOLT)
                    .findFirst()
                    .orElseThrow();
            double scale = mc.getWindow().getGuiScale();
            return new double[] {
                ((screen.width - 176) / 2 + slot.x + 8) * scale, ((screen.height - 166) / 2 + slot.y + 8) * scale
            };
        });
        ctx.getInput().setCursorPos(point[0], point[1]);
        ctx.waitTicks(4);
    }
}
