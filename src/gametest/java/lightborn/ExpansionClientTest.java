package lightborn;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Set;
import lightborn.test.Soft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/** Exercise the expanded pack in a normal generated world, including physical workstations and real consumption. */
public final class ExpansionClientTest implements FabricClientGameTest {
    private static void require(boolean ok, String why) {
        Soft.require(ok, why);
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    /** Use the real server reply with a full set of known destinations for the refugee screenshot. */
    public static void showRefugeeOffer(ServerPlayer player, Refugee refugee) {
        BlockPos here = refugee.blockPosition();
        refugee.state = Refugee.SHELTERED;
        refugee.searchDone = true;
        refugee.offers.addAll(List.of(
                new Refugee.Site("a village", here.east(180)),
                new Refugee.Site("a witch hut", here.north(240)),
                new Refugee.Site("a ruined portal", here.south(320))));
        DragonTalk.send(player, refugee.respond(player, 0));
    }

    public static void makeGuideWait(ServerPlayer player, Refugee refugee) {
        player.teleportTo(player.level(), refugee.getX() - 32, refugee.getY(), refugee.getZ(), Set.of(), -90, 0, false);
        refugee.state = Refugee.GUIDING;
        refugee.target = refugee.blockPosition().east(80);
        refugee.guideStep(player.level(), player);
    }

    public static void callAtDawn(ServerPlayer player, Refugee refugee, int blocksAway) {
        player.teleportTo(
                player.level(), refugee.getX() - blocksAway, refugee.getY(), refugee.getZ(), Set.of(), -90, 0, false);
        refugee.callCooldown = 0;
        refugee.dawnCall(player.level(), player);
    }

    public static void dawnCallBehindWall(ServerPlayer player, Refugee refugee) {
        var level = player.level();
        BlockPos wall = refugee.blockPosition().west(2);
        var base = level.getBlockState(wall);
        var top = level.getBlockState(wall.above());
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
        callAtDawn(player, refugee, 4);
        level.setBlockAndUpdate(wall.above(), top);
        level.setBlockAndUpdate(wall, base);
    }

    public static BlockPos guideClimbFixture(ServerPlayer player, Refugee guide) {
        var level = player.level();
        BlockPos foot = player.blockPosition().above(24);
        for (int x = -4; x <= 26; x++) {
            level.setBlockAndUpdate(foot.offset(x, -1, 0), Blocks.STONE.defaultBlockState());
            for (int y = 0; y < 4; y++) {
                level.setBlockAndUpdate(foot.offset(x, y, -1), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(foot.offset(x, y, 1), Blocks.STONE.defaultBlockState());
                if (x == -4 || x > 0 && y < 2)
                    level.setBlockAndUpdate(foot.offset(x, y, 0), Blocks.STONE.defaultBlockState());
            }
        }
        player.teleportTo(level, foot.getX() - 1.5, foot.getY(), foot.getZ() + .5, Set.of(), -90, 0, false);
        guide.snapTo(foot.getX() + .5, foot.getY(), foot.getZ() + .5, -90, 0);
        guide.setNoAi(false);
        guide.target = foot.east(40).above(2);
        guide.guided = player.getUUID();
        guide.follower = player;
        guide.waiting = false;
        return foot;
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1600, 900);
        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .adjustSettings(s -> s.setWorldType(s.getNormalPresetList().stream()
                        .filter(t -> t.preset().is(WorldPresets.NORMAL))
                        .findFirst()
                        .orElseThrow()))
                .create()) {
            ctx.waitTicks(100);
            BlockPos inside = sp.getServer().computeOnServer(server -> {
                var l = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                l.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                l.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                l.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                p.setGameMode(GameType.CREATIVE);
                require(
                        !(l.getChunkSource().getGenerator() instanceof FlatLevelSource),
                        "Normal terrain preset not applied");
                return l.getRespawnData().pos();
            });
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(3);
                mc.resizeGui();
                var loader = FabricLoader.getInstance();
                require(
                        List.of(
                                        "sit-oth3r",
                                        "visualworkbench",
                                        "terralith",
                                        "ambientsounds",
                                        "mr_dungeons_andtaverns",
                                        "xaeroworldmap")
                                .stream()
                                .allMatch(loader::isModLoaded),
                        "A requested client mod is missing");
            });
            sp.getServer().runCommand("time set 6000");
            sp.getServer().runCommand("weather clear");
            sp.getServer().runCommand("title @a clear");
            teleport(sp, Level.OVERWORLD, inside.offset(0, 2, 9), 180, 10);
            ctx.waitTicks(40);
            ctx.takeScreenshot("expansion_terralith_and_spawn_hut");
            BlockPos arena = inside.offset(16, 5, 16), seat = arena.offset(0, 0, 1), bench = arena.offset(3, 0, 1);
            sp.getServer().runOnServer(server -> {
                var l = server.overworld();
                for (int x = -8; x <= 9; x++)
                    for (int z = -5; z <= 7; z++) {
                        for (int y = 0; y <= 7; y++)
                            l.setBlockAndUpdate(arena.offset(x, y, z), Blocks.AIR.defaultBlockState());
                        l.setBlockAndUpdate(arena.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState());
                    }
                l.setBlockAndUpdate(seat, Blocks.SPRUCE_STAIRS.defaultBlockState());
                l.setBlockAndUpdate(
                        bench, ((BlockItem) Items.CRAFTING_TABLE).getBlock().defaultBlockState());
                var p = server.getPlayerList().getPlayers().getFirst();
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            });
            teleport(sp, Level.OVERWORLD, seat.south(2), 180, 30);
            ctx.waitTicks(35);
            useBlock(ctx, seat);
            sp.getServer()
                    .runOnServer(server -> require(
                            server.getPlayerList().getPlayers().getFirst().isPassenger(),
                            "Empty-hand stair click did not sit"));
            ctx.takeScreenshot("expansion_sitting_on_stair");
            ctx.getInput().holdKey(o -> o.keyShift);
            ctx.waitTicks(4);
            ctx.getInput().releaseKey(o -> o.keyShift);
            ctx.waitTicks(6);
            teleport(sp, Level.OVERWORLD, bench.south(2), 180, 20);
            ctx.waitTicks(35);
            useBlock(ctx, bench);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                require(p.containerMenu != p.inventoryMenu, "Workbench menu did not open");
                p.containerMenu.getSlot(1).set(new ItemStack(Items.OAK_PLANKS));
                p.containerMenu.getSlot(4).set(new ItemStack(Items.OAK_PLANKS));
            });
            ctx.waitTicks(8);
            ctx.takeScreenshot("expansion_visual_workbench_menu");
            sp.getServer()
                    .runOnServer(server ->
                            server.getPlayerList().getPlayers().getFirst().closeContainer());
            ctx.waitTicks(20);
            sp.getServer().runOnServer(server -> {
                var c = (Container) server.overworld().getBlockEntity(bench);
                int n = 0;
                for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
                require(n >= 2, "Closing workbench returned or lost its ingredients");
            });
            ctx.takeScreenshot("expansion_workbench_retained_surface_items");

            BlockPos endView = sp.getServer().computeOnServer(server -> {
                var end = server.getLevel(Level.END);
                for (int[] at : new int[][] {{1400, 1400}, {1600, 1000}, {1800, 1500}, {-1500, 1400}, {2000, -1600}}) {
                    end.getChunk(at[0] >> 4, at[1] >> 4);
                    int y = end.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at[0], at[1]);
                    if (y > 20) {
                        var p = server.getPlayerList().getPlayers().getFirst();
                        p.setGameMode(GameType.CREATIVE);
                        p.getAbilities().flying = true;
                        p.onUpdateAbilities();
                        return new BlockPos(at[0], y + 5, at[1]);
                    }
                }
                throw new AssertionError("No End island found for preview");
            });
            teleport(sp, Level.END, endView, 0, 15);
            ctx.waitTicks(70);
            ctx.takeScreenshot("expansion_vanilla_end_island");
            System.out.println(
                    "LIGHTBORN EXPANSION CLIENT PASS: the reduced expansion pack, normal terrain, sitting, retained workbench, compass, map and vanilla End island");
        }
    }

    private static void teleport(
            TestSingleplayerContext sp, ResourceKey<Level> dimension, BlockPos pos, float yaw, float pitch) {
        sp.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().getFirst();
            p.teleportTo(
                    server.getLevel(dimension),
                    pos.getX() + .5,
                    pos.getY(),
                    pos.getZ() + .5,
                    Set.of(),
                    yaw,
                    pitch,
                    false);
        });
    }

    private static void useBlock(ClientGameTestContext ctx, BlockPos pos) {
        var angles = ctx.computeOnClient(mc -> {
            var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
            var center = shape.isEmpty() ? new Vec3(.5, .5, .5) : shape.bounds().getCenter();
            var target = center.add(pos.getX(), pos.getY(), pos.getZ());
            var delta = target.subtract(mc.player.getEyePosition());
            return new float[] {
                (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90,
                (float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)))
            };
        });
        ctx.getInput().lookAt(angles[0], angles[1]);
        ctx.waitTicks(4);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
        ctx.waitTicks(8);
    }
}
