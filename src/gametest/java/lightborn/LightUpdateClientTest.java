package lightborn;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lightborn.client.CollectionScreen;
import lightborn.test.Soft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import org.joml.Vector3f;

/** Native screenshots and network transactions for the Light chest, attunements and night-time Dragon. */
public class LightUpdateClientTest implements FabricClientGameTest {
    private static void require(boolean b, String why) {
        Soft.require(b, why);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1600, 900);
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(100);
            BlockPos inside = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                p.getInventory().setItem(1, new ItemStack(ModItems.SPARK, 64));
                p.getInventory().setItem(2, new ItemStack(ModItems.SPARK, 64));
                return level.getRespawnData().pos();
            });
            BlockPos chest = SpawnHut.lightChestAt(inside);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(3);
                mc.resizeGui();
            });
            sp.getServer().runCommand("time set 6000");
            sp.getServer().runCommand("title @a clear");
            teleport(sp, inside, 0, 0, 1.2, 180, 9);
            ctx.waitTicks(40);
            ctx.takeScreenshot("light_compact_hut_inside");
            ctx.runOnClient(mc -> mc.gui.setScreen(new CollectionScreen(chest)));
            ctx.waitTicks(12);
            ctx.takeScreenshot("light_overview");
            ctx.runOnClient(mc -> ((CollectionScreen) mc.gui.screen()).select(-2));
            ctx.waitTicks(12);
            ctx.getInput().setCursorPos(20, 20);
            ctx.waitTicks(3);
            ctx.takeScreenshot("light_shop_daily_stock");
            int price = sp.getServer()
                    .computeOnServer(server -> LightShop.wares(server.overworld(), LightShop.day(server.overworld()))
                            .getFirst()
                            .price());
            double[] button = ctx.computeOnClient(mc -> {
                var b = mc.gui.screen().children().stream()
                        .filter(c -> c instanceof Button bt
                                && bt.visible
                                && bt.getMessage().getString().startsWith("Buy"))
                        .map(c -> (Button) c)
                        .findFirst()
                        .orElseThrow();
                return new double[] {
                    (b.getX() + b.getWidth() / 2d) * mc.getWindow().getGuiScale(),
                    (b.getY() + 8) * mc.getWindow().getGuiScale()
                };
            });
            ctx.getInput().setCursorPos(button[0], button[1]);
            ctx.waitTicks(3);
            ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            ctx.waitTicks(12);
            ctx.takeScreenshot("light_shop_after_native_click");
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                require(
                        LightShop.sparks(p) == 128 - price,
                        "Native shop purchase failed: sparks=" + LightShop.sparks(p));
            });
            ctx.takeScreenshot("light_shop_purchase_sold_out");
            // The Spirits tab: one claimed and placed (rank II), two waiting to be claimed, one part way (kills shown),
            // the rest unknown.
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                p.setAttached(
                        ModData.SOUL_COUNTS,
                        Map.of(
                                "minecraft:zombie",
                                44,
                                "minecraft:husk",
                                40,
                                "lightborn:ember_skeleton",
                                20,
                                "mutantmonsters:mutant_zombie",
                                2));
                p.setAttached(
                        ModData.SPIRITS,
                        new Souls.Spirits(Map.of("minecraft:zombie", 2), List.of("minecraft:zombie"), 3));
            });
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> {
                var s = (CollectionScreen) mc.gui.screen();
                s.select(-6);
                s.pickSpirit("minecraft:zombie");
            });
            ctx.waitTicks(8);
            ctx.takeScreenshot("light_spirits_tab");
            ctx.setScreen(() -> null);
            ctx.waitTicks(5);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                PlayerBonuses.apply(p);
                var glacial = new ItemStack(ModItems.TOMES.get(Spell.GLACIAL_SPIKE));
                SpellUpgrades.write(glacial, new int[] {0, -1, -1});
                p.getInventory().setItem(40, glacial);
            });
            ctx.waitTicks(8);
            ctx.runOnClient(mc -> {
                var s = new CollectionScreen(chest);
                mc.gui.setScreen(s);
                s.select(-5);
            });
            ctx.waitTicks(8);
            hoverCard(ctx, 1, 0);
            ctx.takeScreenshot("light_upgrade_special_wrapped_tooltip");
            hoverCard(ctx, 0, 1);
            ctx.takeScreenshot("light_upgrade_passed_choice");
            ctx.setScreen(() -> null);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                var c = Collection.categories().getFirst();
                p.setAttached(
                        ModData.COLLECTION,
                        List.copyOf(c.items().subList(0, (c.items().size() + 3) / 4)));
                PlayerBonuses.apply(p);
            });
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> {
                var s = new CollectionScreen(chest);
                mc.gui.setScreen(s);
                s.select(0);
            });
            ctx.waitTicks(8);
            ctx.takeScreenshot("light_rounded_next_milestone");
            ctx.setScreen(() -> null);
            teleport(sp, inside, 0, 1, 8.5, 180, 8);
            ctx.waitTicks(35);
            ctx.takeScreenshot("light_compact_hut_exterior");
            sp.getServer().runCommand("time set 18000");
            ctx.waitTicks(30);
            sp.getServer().runOnServer(server -> {
                // He hovers only above his chest, and the spot is low enough to fit under the starter hut's roof.
                var dragon = LightDragons.get(server.overworld(), chest);
                require(
                        dragon != null
                                && dragon.getY() >= chest.getY() + 1
                                && Math.abs(dragon.getX() - (chest.getX() + .5)) < .5,
                        "Starter hut must show its Dragon above the chest: "
                                + (dragon == null ? "none" : dragon.position() + " chest " + chest));
            });
            teleport(sp, inside, 0, 0, 1.2, 180, -10);
            ctx.waitTicks(35);
            ctx.takeScreenshot("light_dragon_hovering_above_chest");
            ctx.runOnClient(mc -> {
                var extractor = new LightmapRenderStateExtractor(mc.gameRenderer, mc);
                var s = new LightmapRenderState();
                extractor.tick();
                extractor.extract(s, 0);
                float sky = s.skyFactor;
                var ambient = new Vector3f(s.ambientColor);
                extractor.extract(s, 0);
                require(
                        Math.abs(s.skyFactor - sky) < .0001 && ambient.distance(s.ambientColor) < .0001,
                        "Night darkening compounded on cached frame");
            });
            BlockPos display = inside.offset(20, 0, 15);
            sp.getServer().runOnServer(server -> {
                var l = server.overworld();
                for (int x = -5; x <= 5; x++)
                    for (int z = -5; z <= 5; z++) {
                        for (int y = 0; y <= 6; y++)
                            l.setBlockAndUpdate(display.offset(x, y, z), Blocks.AIR.defaultBlockState());
                        l.setBlockAndUpdate(display.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState());
                    }
                l.setBlockAndUpdate(
                        display,
                        ModItems.LIGHT_CHEST.defaultBlockState().setValue(LightChestBlock.FACING, Direction.SOUTH));
            });
            teleport(sp, display, 2.5, 0, 5, 180, -3);
            ctx.waitTicks(40);
            ctx.takeScreenshot("light_dragon_night_portrait");
            ctx.waitTicks(18);
            ctx.takeScreenshot("light_dragon_wingbeat");
            sp.getServer().runOnServer(server -> {
                var l = server.overworld();
                var d = LightDragons.get(l, display);
                require(
                        d != null && Math.abs(d.getX() - (display.getX() + .5)) < .5 && d.getY() >= display.getY() + 1,
                        "Open chest should show its Dragon above it: "
                                + (d == null ? "none" : d.position() + " chest " + display));
                l.setBlockAndUpdate(display, Blocks.AIR.defaultBlockState());
                require(d.isRemoved(), "Dragon survived chest break");
                l.setBlockAndUpdate(display, ModItems.LIGHT_CHEST.defaultBlockState());
                require(LightDragons.get(l, display) != null, "Dragon did not return on replacement");
            });
            sp.getServer().runCommand("time set 24000");
            ctx.waitTicks(55);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                require(LightDragons.get(server.overworld(), display) == null, "Dragon survived dawn");
                require(
                        LightShop.snapshot(p, chest, "").day() == LightShop.day(server.overworld()),
                        "Shop did not refresh at dawn");
                Progression.setMana(p, 1);
                p.kill(server.overworld());
                require(p.isDeadOrDying(), "Death fixture did not die");
            });
            ctx.waitTicks(25);
            ctx.runOnClient(mc -> mc.player.respawn());
            ctx.waitTicks(60);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                require(
                        p.isAlive() && Math.abs(Progression.mana(p) - Progression.maxMana(p)) < .01,
                        "Respawn did not refill mana");
            });
            System.out.println(
                    "LIGHTBORN LIGHT UPDATE CLIENT PASS: shop, all attunements, compact hut, night Dragon, cached lightmap, dawn restock, mana respawn");
        }
    }

    private static void teleport(
            TestSingleplayerContext sp, BlockPos p, double dx, double dy, double dz, float yaw, float pitch) {
        sp.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(
                    server.overworld(),
                    p.getX() + .5 + dx,
                    p.getY() + dy,
                    p.getZ() + .5 + dz,
                    Set.of(),
                    yaw,
                    pitch,
                    false);
        });
    }

    private static void hoverCard(ClientGameTestContext ctx, int tier, int choice) {
        var xy = ctx.computeOnClient(mc -> {
            var p = ((CollectionScreen) mc.gui.screen()).upgradeCard(tier, choice);
            return new double[] {
                p[0] * mc.getWindow().getGuiScale(), p[1] * mc.getWindow().getGuiScale()
            };
        });
        ctx.getInput().setCursorPos(xy[0], xy[1]);
        ctx.waitTicks(4);
    }
}
