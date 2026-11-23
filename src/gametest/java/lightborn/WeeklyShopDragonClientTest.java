package lightborn;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lightborn.client.CollectionScreen;
import lightborn.test.Soft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

/** Native bundle purchases, week boundaries, spectral hit feedback and all three conversation/quest routes. */
public final class WeeklyShopDragonClientTest implements FabricClientGameTest {
    private static void require(boolean ok, String why) {
        Soft.require(ok, why);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1920, 1080);
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(100);
            BlockPos inside = sp.getServer().computeOnServer(server -> {
                var l = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                l.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                l.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                l.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                for (int i = 1; i <= 6; i++) p.getInventory().setItem(i, new ItemStack(ModItems.SPARK, 64));
                var loader = FabricLoader.getInstance();
                require(
                        !loader.isModLoaded("graveyard") && !loader.isModLoaded("enderzoology"),
                        "Removed mods still loaded");
                return l.getRespawnData().pos();
            });
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(4);
                mc.resizeGui();
            });
            sp.getServer().runCommand("title @a clear");
            BlockPos chest = SpawnHut.lightChestAt(inside);
            long foodDay = sp.getServer().computeOnServer(server -> {
                for (long day = 0; day < 7; day++) {
                    var offers = LightShop.wares(server.overworld(), day);
                    if (offers.get(4).stack().getCount() == 4
                            || offers.get(5).stack().getCount() == 4) return day;
                }
                throw new AssertionError("No four-item food bundle this week");
            });
            sp.getServer().runCommand("time set " + (foodDay * 24000 + 6000));
            sp.getServer().runOnServer(server -> {
                var offers = LightShop.wares(server.overworld(), foodDay);
                require(
                        !offers.get(1).stack().has(DataComponents.CUSTOM_NAME)
                                && !ModItems.SPECIALIST_TIER.containsKey(
                                        offers.get(1).stack().getItem()),
                        "Week one is not basic");
            });
            ctx.runOnClient(mc -> {
                var s = new CollectionScreen(chest);
                mc.gui.setScreen(s);
                s.select(-2);
            });
            ctx.waitTicks(12);
            ctx.getInput().setCursorPos(15, 15);
            ctx.waitTicks(3);
            ctx.takeScreenshot("weekly_shop_basic_quantities");
            int foodIndex = sp.getServer()
                    .computeOnServer(server -> LightShop.wares(server.overworld(), foodDay)
                                            .get(4)
                                            .stack()
                                            .getCount()
                                    == 4
                            ? 4
                            : 5);
            int foodPrice = sp.getServer()
                    .computeOnServer(server -> LightShop.wares(server.overworld(), foodDay)
                            .get(foodIndex)
                            .price());
            for (int i = 0; i < 5; i++) clickShop(ctx, foodIndex);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                var offer = LightShop.wares(server.overworld(), foodDay).get(foodIndex);
                require(
                        Enchanting.count(p, offer.stack().getItem()) == 20
                                && LightShop.sparks(p) == 384 - foodPrice * 5,
                        "Five native bundle purchases failed or charged incorrectly");
            });
            ctx.getInput().setCursorPos(15, 15);
            ctx.waitTicks(3);
            ctx.takeScreenshot("weekly_shop_five_purchases_still_available");
            clickShop(ctx, 1);
            ctx.setScreen(() -> null);
            for (long day : new long[] {7, 21, 42}) {
                sp.getServer().runCommand("time set " + (day * 24000 + 6000));
                ctx.runOnClient(mc -> {
                    var s = new CollectionScreen(chest);
                    mc.gui.setScreen(s);
                    s.select(-2);
                });
                ctx.waitTicks(12);
                ctx.getInput().setCursorPos(15, 15);
                ctx.waitTicks(3);
                ctx.takeScreenshot("weekly_shop_week_" + LightShop.week(day));
                ctx.setScreen(() -> null);
            }
            BlockPos display = inside.offset(18, 0, 18);
            sp.getServer().runCommand("time set 6000");
            sp.getServer().runOnServer(server -> {
                var l = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                for (int x = -6; x <= 6; x++)
                    for (int z = -6; z <= 6; z++) {
                        for (int y = 0; y <= 6; y++)
                            l.setBlockAndUpdate(display.offset(x, y, z), Blocks.AIR.defaultBlockState());
                        l.setBlockAndUpdate(display.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState());
                    }
                l.setBlockAndUpdate(
                        display,
                        ModItems.LIGHT_CHEST.defaultBlockState().setValue(LightChestBlock.FACING, Direction.SOUTH));
            });
            teleport(sp, display, 1.75, 0, 3.2);
            ctx.waitTicks(35);
            sp.getServer().runCommand("time set 13000");
            ctx.waitTicks(120);
            ctx.takeScreenshot("weekly_dragon_smaller_hornless_head");
            sp.getServer().runOnServer(server -> {
                var d = LightDragons.get(server.overworld(), display);
                require(d != null && Math.abs(d.getY() - display.getY() - 1.5) < .001, "1.5-block hover lift missing");
            });
            lookDragon(ctx, sp, display);
            ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            ctx.waitTicks(4);
            ctx.runOnClient(mc -> {
                var d = mc
                        .level
                        .getEntitiesOfClass(
                                LightDragon.class, mc.player.getBoundingBox().inflate(8))
                        .stream()
                        .filter(e -> e.distanceToSqr(mc.player) < 25)
                        .findFirst()
                        .orElseThrow();
                require(d.opacity(0) < .5, "Client did not enter translucent phase");
            });
            sp.getServer().runOnServer(server -> {
                var d = LightDragons.get(server.overworld(), display);
                require(
                        d.opacity(0) < .5 && d.getHealth() == d.getMaxHealth(),
                        "Native attack did not make Dragon translucent and unharmed");
            });
            ctx.takeScreenshot("weekly_dragon_translucent_on_hit");
            ctx.waitTicks(35);
            sp.getServer()
                    .runOnServer(server -> require(
                            LightDragons.get(server.overworld(), display).opacity(0) == 1,
                            "Dragon stayed translucent"));
            // The rewritten dialogue (2026-11-16): one intro choice sets the tone, then the help offer and its answer.
            String[] first = {"Stay as long as you like.", "An echo of what, exactly?", "Why my chest?"};
            String[] help = {"Can I help you somehow?", "Got any work for me?", "Give me something to fight."};
            String[] accept = {"I'll bring it home.", "Deal.", "Consider it broken."};
            for (int route = 0; route < 3; route++) {
                final int tone = route;
                sp.getServer().runOnServer(server -> {
                    var p = server.getPlayerList().getPlayers().getFirst();
                    p.setAttached(DragonStory.CHAPTERS, 9);
                    p.setAttached(DragonTalk.JOURNEY, DragonTalk.Journey.NEW);
                    p.removeAttached(DragonTalk.DISPOSITION);
                    p.removeAttached(DragonTalk.SURVEY);
                    p.setGameMode(GameType.SURVIVAL);
                });
                teleport(sp, display, 1.75, 0, 3.2);
                ctx.waitTicks(35);
                lookDragon(ctx, sp, display);
                ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
                ctx.waitTicks(8);
                ctx.takeScreenshot("weekly_dragon_intro_choices_" + tone);
                click(ctx, first[route]);
                ctx.takeScreenshot("weekly_dragon_reaction_" + tone);
                sp.getServer().runOnServer(server -> {
                    var p = server.getPlayerList().getPlayers().getFirst();
                    require(
                            p.getAttached(DragonTalk.DISPOSITION) == tone + 1
                                    && p.getAttached(DragonTalk.JOURNEY).stage() == 2,
                            "Dialogue did not remember tone or auto-assigned a quest");
                });
                click(ctx, help[route]);
                ctx.takeScreenshot("weekly_dragon_offer_" + tone);
                click(ctx, accept[route]);
                ctx.setScreen(() -> null);
                sp.getServer()
                        .runOnServer(server ->
                                server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.CREATIVE));
                if (route == 1) {
                    for (int mark = 0; mark < 3; mark++) {
                        BlockPos site = sp.getServer()
                                .computeOnServer(server -> server.getPlayerList()
                                        .getPlayers()
                                        .getFirst()
                                        .getAttached(DragonTalk.JOURNEY)
                                        .site());
                        teleport(sp, site, 0, 0, 0);
                        ctx.waitTicks(40);
                    }
                    sp.getServer().runOnServer(server -> {
                        var p = server.getPlayerList().getPlayers().getFirst();
                        require(
                                p.getAttached(DragonTalk.JOURNEY).stage() == 6
                                        && p.getAttached(DragonTalk.SURVEY).isEmpty(),
                                "Three native survey visits did not finish neutral route");
                    });
                } else {
                    BlockPos site = sp.getServer()
                            .computeOnServer(server -> server.getPlayerList()
                                    .getPlayers()
                                    .getFirst()
                                    .getAttached(DragonTalk.JOURNEY)
                                    .site());
                    teleport(sp, site, 0, 0, 3);
                    ctx.waitTicks(60);
                    sp.getServer().runOnServer(server -> {
                        var p = server.getPlayerList().getPlayers().getFirst();
                        var q = p.getAttached(DragonTalk.JOURNEY);
                        require(q.stage() == 5 && q.enemies().size() == 3, "Combat route did not create three foes");
                        // The site is at Y -60, where Mob Variants would swap most zombies and skeletons for new
                        // entities: every foe still answering to its UUID proves VariantCompat keeps them.
                        for (UUID id : List.copyOf(q.enemies())) {
                            require(p.level().getEntity(id) instanceof Mob, "A quest foe was swapped for a variant");
                            var m = (Mob) p.level().getEntity(id);
                            m.setNoAi(true);
                            m.hurtServer(p.level(), p.damageSources().playerAttack(p), 10000);
                        }
                        require(p.getAttached(DragonTalk.JOURNEY).stage() == 6, "Combat route did not finish");
                    });
                }
                int money = sp.getServer()
                        .computeOnServer(server -> LightShop.sparks(
                                server.getPlayerList().getPlayers().getFirst()));
                int items = sp.getServer().computeOnServer(server -> {
                    var p = server.getPlayerList().getPlayers().getFirst();
                    return Enchanting.count(p, DragonTalk.reward(p).getItem());
                });
                sp.getServer()
                        .runOnServer(server ->
                                server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SURVIVAL));
                teleport(sp, display, 1.75, 0, 3.2);
                ctx.waitTicks(40);
                lookDragon(ctx, sp, display);
                ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
                ctx.waitTicks(8);
                click(ctx, "It's done.");
                ctx.takeScreenshot("weekly_dragon_reward_" + tone);
                sp.getServer().runOnServer(server -> {
                    var p = server.getPlayerList().getPlayers().getFirst();
                    // Friendly pays Blink plus 8 Sparks; the other routes pay 12 + 8 = the twenty Sparks they promise.
                    var reward = DragonTalk.reward(p);
                    int paid = 8 + (reward.is(ModItems.SPARK) ? reward.getCount() : 0);
                    require(
                            LightShop.sparks(p) == money + paid
                                    && (reward.is(ModItems.SPARK)
                                            || Enchanting.count(p, reward.getItem()) == items + 1),
                            "Route paid wrong reward: " + (LightShop.sparks(p) - money) + " Sparks");
                    DragonTalk.respond(p, LightDragons.get(p.level(), display), 6);
                    require(LightShop.sparks(p) == money + paid, "Route reward repeated");
                });
                ctx.setScreen(() -> null);
            }
            System.out.println(
                    "LIGHTBORN WEEKLY CLIENT PASS: explicit quantities, five repeat purchases, seven-day progression, hornless head, quarter-block hover, native hit transparency, all three introduction and quest routes, removed mods absent");
        }
    }

    private static void teleport(TestSingleplayerContext sp, BlockPos pos, double x, double y, double z) {
        sp.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.teleportTo(
                    server.overworld(),
                    pos.getX() + .5 + x,
                    pos.getY() + y,
                    pos.getZ() + .5 + z,
                    Set.of(),
                    180,
                    0,
                    false);
        });
    }

    private static void lookDragon(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos chest) {
        var aim = sp.getServer()
                .computeOnServer(server -> LightDragons.get(server.overworld(), chest)
                        .blockPosition()
                        .above());
        ctx.getInput().lookAt(aim);
        ctx.waitTicks(4);
    }

    private static void clickShop(ClientGameTestContext ctx, int index) {
        var xy = ctx.computeOnClient(mc -> {
            var b = mc.gui.screen().children().stream()
                    .filter(c -> c instanceof Button btn
                            && btn.visible
                            // Shop buttons carry their price: "Buy · 12 Sparks", "Take · Free" (2026-11-16).
                            && (btn.getMessage().getString().startsWith("Buy")
                                    || btn.getMessage().getString().startsWith("Take")))
                    .map(c -> (Button) c)
                    .toList()
                    .get(index);
            return new double[] {
                (b.getX() + b.getWidth() / 2d) * mc.getWindow().getGuiScale(),
                (b.getY() + 8) * mc.getWindow().getGuiScale()
            };
        });
        press(ctx, xy);
    }

    private static void click(ClientGameTestContext ctx, String text) {
        var xy = ctx.computeOnClient(mc -> {
            var b = mc.gui.screen().children().stream()
                    .filter(c -> c instanceof Button btn
                            && btn.visible
                            && btn.getMessage().getString().equals(text))
                    .map(c -> (Button) c)
                    .findFirst()
                    .orElseThrow();
            return new double[] {
                (b.getX() + b.getWidth() / 2d) * mc.getWindow().getGuiScale(),
                (b.getY() + 8) * mc.getWindow().getGuiScale()
            };
        });
        press(ctx, xy);
    }

    private static void press(ClientGameTestContext ctx, double[] xy) {
        ctx.getInput().setCursorPos(xy[0], xy[1]);
        ctx.waitTicks(3);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ctx.waitTicks(8);
    }
}
