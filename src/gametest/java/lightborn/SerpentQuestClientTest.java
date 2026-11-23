package lightborn;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lightborn.client.CollectionScreen;
import lightborn.client.DragonDialogueScreen;
import lightborn.test.Soft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

/** Real interaction packets, the full voluntary quest, safe door pounding, sleep and arrival/departure poses. */
public final class SerpentQuestClientTest implements FabricClientGameTest {
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
                p.setAttached(
                        DragonStory.CHAPTERS,
                        9); // This fixture isolates the optional quests; StoryClientTest covers chapter priority.
                require(
                        Enchanting.count(p, Items.APPLE) == 10 && Enchanting.count(p, Items.TORCH) == 10,
                        "Starter must have ten apples and ten torches");
                l.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                l.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                l.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                return l.getRespawnData().pos();
            });
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(4);
                mc.resizeGui();
            });
            sp.getServer().runCommand("time set 6000");
            sp.getServer().runCommand("title @a clear");
            BlockPos chest = SpawnHut.lightChestAt(inside);
            ctx.runOnClient(mc -> {
                var screen = new CollectionScreen(chest);
                mc.gui.setScreen(screen);
                screen.select(-2);
            });
            ctx.waitTicks(15);
            ctx.getInput().setCursorPos(20, 20);
            ctx.waitTicks(3);
            ctx.takeScreenshot("serpent_shop_order_and_button_clearance");
            ctx.setScreen(() -> null);
            BlockPos display = inside.offset(18, 0, 18);
            sp.getServer().runOnServer(server -> {
                var l = server.overworld();
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
            teleport(sp, display, 1.8, 0, 5, 180, -3);
            ctx.waitTicks(40);
            sp.getServer().runCommand("time set 13000");
            ctx.waitTicks(12);
            ctx.takeScreenshot("serpent_epic_arrival_early");
            ctx.waitTicks(15);
            ctx.takeScreenshot("serpent_epic_arrival_forming");
            ctx.waitTicks(110);
            ctx.takeScreenshot("serpent_wise_dragon_full");
            sp.getServer().runOnServer(server -> {
                var l = server.overworld();
                var d = LightDragons.get(l, display);
                require(d != null && d.manifestation(0) == 1 && !d.departing(), "Dragon did not finish arrival");
            });
            teleport(sp, display, 1.75, 0, 3.2, 180, -4);
            ctx.waitTicks(35);
            openDragon(ctx, sp, display);
            ctx.takeScreenshot("serpent_first_introduction");
            // The rewritten dialogue (2026-11-16): one welcome choice ends the introduction.
            click(ctx, "Stay as long as you like.");
            sp.getServer()
                    .runOnServer(server -> require(
                            server.getPlayerList()
                                            .getPlayers()
                                            .getFirst()
                                            .getAttachedOrCreate(DragonTalk.JOURNEY)
                                            .stage()
                                    == 2,
                            "Introduction auto-started a quest"));
            click(ctx, "Can I help you somehow?");
            ctx.takeScreenshot("serpent_voluntary_quest_offer");
            click(ctx, "I'll bring it home.");
            BlockPos site = sp.getServer().computeOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                var q = p.getAttachedOrCreate(DragonTalk.JOURNEY);
                require(q.stage() == 4 && !LightWards.covers(p.level(), q.site()), "Quest site not outside ward");
                p.setGameMode(GameType.CREATIVE);
                return q.site();
            });
            ctx.setScreen(() -> null);
            teleport(sp, site, 0, 0, 3, 180, 0);
            ctx.waitTicks(65);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                var q = p.getAttachedOrCreate(DragonTalk.JOURNEY);
                require(q.stage() == 5 && q.enemies().size() == 3, "Ambush did not create three quest foes");
                for (UUID id : q.enemies()) if (p.level().getEntity(id) instanceof Mob mob) mob.setNoAi(true);
            });
            ctx.takeScreenshot("serpent_fallen_spark_ambush");
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                for (UUID id :
                        List.copyOf(p.getAttachedOrCreate(DragonTalk.JOURNEY).enemies())) {
                    var mob = (Mob) p.level().getEntity(id);
                    require(mob != null, "Quest foe disappeared");
                    mob.hurtServer(p.level(), p.damageSources().playerAttack(p), 10000);
                }
                require(p.getAttachedOrCreate(DragonTalk.JOURNEY).stage() == 6, "Clearing foes did not secure spark");
                p.setGameMode(GameType.SURVIVAL);
            });
            teleport(sp, display, 1.75, 0, 3.2, 180, -4);
            ctx.waitTicks(40);
            openDragon(ctx, sp, display);
            ctx.takeScreenshot("serpent_quest_return");
            click(ctx, "It's done.");
            ctx.takeScreenshot("serpent_quest_reward_dialogue");
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                require(
                        p.getAttachedOrCreate(DragonTalk.JOURNEY).stage() == 7
                                && Enchanting.count(p, ModItems.TOMES.get(Spell.BLINK)) == 1
                                && Enchanting.count(p, ModItems.SPARK) == 8,
                        "Wrong quest reward");
                var d = LightDragons.get(p.level(), display);
                DragonTalk.respond(p, d, 6);
                require(Enchanting.count(p, ModItems.SPARK) == 8, "Reward can be claimed twice");
            });
            ctx.setScreen(() -> null);
            sp.getServer().runCommand("time set 24000");
            ctx.waitTicks(12);
            ctx.takeScreenshot("serpent_calm_departure");
            ctx.waitTicks(45);
            sp.getServer()
                    .runOnServer(server -> require(
                            LightDragons.get(server.overworld(), display) == null, "Dragon did not dissolve at dawn"));
            // Door pounding and sleeping near monsters are server rules: the self-test checks them
            // (zombies_pound_but_never_break_doors, sleep_allowed_near_monsters), per README.md Testing rules.
            System.out.println(
                    "LIGHTBORN SERPENT CLIENT PASS: eight-slot shop, arrival and departure, introduction, voluntary quest ambush and reward, calm departure at dawn");
        }
    }

    private static void teleport(
            TestSingleplayerContext sp, BlockPos pos, double x, double y, double z, float yaw, float pitch) {
        sp.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.teleportTo(
                    server.overworld(),
                    pos.getX() + .5 + x,
                    pos.getY() + y,
                    pos.getZ() + .5 + z,
                    Set.of(),
                    yaw,
                    pitch,
                    false);
        });
    }

    private static void openDragon(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos chest) {
        BlockPos aim = sp.getServer()
                .computeOnServer(server -> LightDragons.get(server.overworld(), chest)
                        .blockPosition()
                        .above());
        ctx.getInput().lookAt(aim);
        ctx.waitTicks(4);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
        ctx.waitTicks(8);
        ctx.runOnClient(mc ->
                require(mc.gui.screen() instanceof DragonDialogueScreen, "Right-click did not open Dragon dialogue"));
    }

    private static void click(ClientGameTestContext ctx, String label) {
        var xy = ctx.computeOnClient(mc -> {
            var b = mc.gui.screen().children().stream()
                    .filter(c -> c instanceof Button btn
                            && btn.visible
                            && btn.getMessage().getString().equals(label))
                    .map(c -> (Button) c)
                    .findFirst()
                    .orElseThrow();
            return new double[] {
                (b.getX() + b.getWidth() / 2d) * mc.getWindow().getGuiScale(),
                (b.getY() + 10) * mc.getWindow().getGuiScale()
            };
        });
        ctx.getInput().setCursorPos(xy[0], xy[1]);
        ctx.waitTicks(3);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ctx.waitTicks(8);
    }
}
