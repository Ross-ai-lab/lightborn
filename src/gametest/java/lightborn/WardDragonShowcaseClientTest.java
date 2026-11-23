package lightborn;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * The mod's two showpieces, photographed: the Light ward (inside the hut, overhead, a motion sequence, outside, by
 * day) and the Dragon over his chest. Asserts only that the Dragon is there; the rest is for the eye.
 */
public class WardDragonShowcaseClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1600, 900);
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(100);
            BlockPos inside = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                return level.getRespawnData().pos();
            });
            BlockPos chest = SpawnHut.lightChestAt(inside);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(3);
                mc.resizeGui();
            });
            sp.getServer().runCommand("weather clear");
            sp.getServer().runCommand("title @a clear");
            ctx.getInput().pressKey(InputConstants.KEY_F1);

            sp.getServer().runCommand("time set 18000");
            ctx.waitTicks(60);
            // The ward through the hut's open door, looking out at the night.
            sp.getServer().runOnServer(server -> {
                var level = server.overworld();
                for (BlockPos door :
                        new BlockPos[] {inside.south(3), inside.south(3).above()})
                    level.setBlockAndUpdate(door, level.getBlockState(door).setValue(BlockStateProperties.OPEN, true));
            });
            view(sp, inside, 0, 0, 1.6, 0, -8);
            ctx.waitTicks(30);
            ctx.takeScreenshot("showcase_ward_from_doorway");
            // In the yard, looking up into the dome: three frames to see the light flow.
            view(sp, inside, 0, 0, 7, 20, -42);
            ctx.waitTicks(30);
            ctx.takeScreenshot("showcase_ward_overhead_1");
            ctx.waitTicks(8);
            ctx.takeScreenshot("showcase_ward_overhead_2");
            ctx.waitTicks(8);
            ctx.takeScreenshot("showcase_ward_overhead_3");
            // Along the wall, where the eye grazes it (the starting ward is 9 blocks).
            view(sp, inside, 3, 0, 4, 300, -8);
            ctx.waitTicks(25);
            ctx.takeScreenshot("showcase_ward_wall_close");
            // From outside: the dome over the hut.
            view(sp, inside, 6, 7, 36, 170, 8);
            ctx.waitTicks(40);
            ctx.takeScreenshot("showcase_ward_outside_night");

            // Zombies just outside the ward at night: nothing out there is lit, so only their glowing eyes show, seen
            // from inside (a row facing the chest, south of the door) and from outside (a row facing out, east).
            int radius = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                int r = LightWards.radiusAt(level, chest);
                for (int i = 0; i < 6; i++) {
                    boolean south = i < 3;
                    double a = (south ? Math.PI / 2 : 0) + (i % 3 - 1) * .3;
                    double x = chest.getX() + .5 + Math.cos(a) * (r + 3), z = chest.getZ() + .5 + Math.sin(a) * (r + 3);
                    int y = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
                    var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                    zombie.setNoAi(true);
                    zombie.setPersistenceRequired();
                    float yaw = (float) Math.toDegrees(a) + (south ? 90 : -90);
                    zombie.snapTo(x, y, z, yaw, 0);
                    zombie.setYHeadRot(yaw);
                    zombie.setYBodyRot(yaw);
                    level.addFreshEntity(zombie);
                }
                return r;
            });
            // A still mob's body turn never reaches the client (it rebuilds it from movement), so the zombies stood
            // side-on with their eyes edge-on (owner, 2026-11-16). Turn the client's copies to the same yaw.
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> {
                double cx = chest.getX() + .5, cz = chest.getZ() + .5;
                for (var z :
                        mc.level.getEntitiesOfClass(Zombie.class, new AABB(chest).inflate(radius + 6, 8, radius + 6))) {
                    double a = Math.atan2(z.getZ() - cz, z.getX() - cx);
                    float yaw = (float) Math.toDegrees(a) + (a > Math.PI / 4 ? 90 : -90);
                    z.setYRot(yaw);
                    z.yRotO = yaw;
                    z.setYHeadRot(yaw);
                    z.yHeadRotO = yaw;
                    z.setYBodyRot(yaw);
                    z.yBodyRotO = yaw;
                }
            });
            view(sp, inside, 0, 0, 1.6, 0, -6);
            ctx.waitTicks(30);
            ctx.takeScreenshot("showcase_eyes_from_inside");
            view(sp, chest, radius + 13, 1, 0, 90, 2);
            ctx.waitTicks(30);
            ctx.takeScreenshot("showcase_eyes_from_outside");

            // The Dragon over his chest, from just inside the door.
            view(sp, inside, 0, 0, 2.3, 180, -17);
            ctx.waitTicks(40);
            sp.getServer().runOnServer(server -> {
                if (LightDragons.get(server.overworld(), chest) == null)
                    throw new AssertionError("No Dragon above the starter chest at night");
            });
            ctx.takeScreenshot("showcase_dragon_portrait");
            ctx.waitTicks(15);
            ctx.takeScreenshot("showcase_dragon_portrait_later");
            // Where a new player arrives: right under his gaze.
            view(sp, inside, 0, 0, 0, 180, -38);
            ctx.waitTicks(20);
            ctx.takeScreenshot("showcase_dragon_arrival_spot");
            view(sp, inside, -1.6, 0, 1.2, 215, -30);
            ctx.waitTicks(20);
            ctx.takeScreenshot("showcase_dragon_from_below");
            view(sp, inside, 2.0, 0, 1.9, 140, -16);
            ctx.waitTicks(20);
            ctx.takeScreenshot("showcase_dragon_side");

            // Night scenes after the Dragon's (a far teleport sends him away). A Cleanse's scars: eight cracks
            // run open over the dome; they heal at dawn, in the day shot below.
            sp.getServer().runOnServer(server -> LightWards.scar(server.overworld(), chest, 8));
            view(sp, inside, 6, 7, 36, 170, 8);
            ctx.waitTicks(200);
            ctx.takeScreenshot("showcase_ward_scars_outside");
            view(sp, inside, 0, 0, 7, 20, -42);
            ctx.waitTicks(60);
            ctx.takeScreenshot("showcase_ward_scars_inside");

            // Nine Dark Shards carried: the edges of the screen darken and breathe (a HUD layer, so the HUD is on).
            ctx.getInput().pressKey(InputConstants.KEY_F1);
            sp.getServer()
                    .runOnServer(server -> server.getPlayerList()
                            .getPlayers()
                            .getFirst()
                            .getInventory()
                            .setItem(8, new ItemStack(ModItems.DARK_SHARD, 9)));
            view(sp, inside, 0, 0, 1.6, 0, -8);
            ctx.waitTicks(160);
            ctx.takeScreenshot("showcase_shard_whispers");
            sp.getServer()
                    .runOnServer(server -> server.getPlayerList()
                            .getPlayers()
                            .getFirst()
                            .getInventory()
                            .setItem(8, ItemStack.EMPTY));
            ctx.getInput().pressKey(InputConstants.KEY_F1);

            // Treasure Thread: a loot chest 18 blocks off, and the line of light that runs to it.
            BlockPos loot = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                var at = inside.offset(14, 0, 11);
                at = new BlockPos(
                        at.getX(),
                        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()),
                        at.getZ());
                level.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                if (level.getBlockEntity(at)
                        instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity box)
                    box.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON);
                return at;
            });
            // Just outside the ward (so its lattice does not cross the shot), facing the chest at +14, +11 from
            // +0, +14: yaw -atan2(14, -3).
            view(sp, inside, 0, 0, 14, -102, 14);
            ctx.waitTicks(10);
            sp.getServer()
                    .runOnServer(server -> TreasureThread.cast(
                            server.getPlayerList().getPlayers().getFirst()));
            ctx.waitTicks(22);
            ctx.takeScreenshot("showcase_treasure_thread_running");
            ctx.waitTicks(40);
            ctx.takeScreenshot("showcase_treasure_thread_glow");
            sp.getServer()
                    .runOnServer(server -> server.overworld()
                            .setBlockAndUpdate(loot, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));

            // The lighthouse: 300 blocks north of home at night, looking back south at a faint gold pillar.
            int groundY = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                level.getChunk((inside.getX()) >> 4, (inside.getZ() - 300) >> 4);
                return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, inside.getX(), inside.getZ() - 300);
            });
            view(sp, inside, 0, groundY - inside.getY() + 1, -300, 0, -6);
            ctx.waitTicks(120);
            ctx.takeScreenshot("showcase_lighthouse");
            view(sp, inside, 0, 0, 1.6, 0, -8);
            ctx.waitTicks(40);

            sp.getServer().runCommand("time set 6000");
            ctx.waitTicks(40);
            view(sp, inside, 6, 7, 36, 170, 8);
            ctx.waitTicks(30);
            ctx.takeScreenshot("showcase_ward_outside_day");
            // A flicker check close to the dome: the camera creeps 3 hundredths of a block a tick, too little to move
            // the picture, enough to stir any depth tie between the dome's layers into flashing lines.
            view(sp, inside, 3, 3, 15, 170, -12);
            ctx.waitTicks(20);
            for (int i = 0; i < 12; i++) {
                view(sp, inside, 3 + i * .03, 3, 15 - i * .03, 170, -12);
                ctx.waitTicks(1);
                ctx.takeScreenshot("showcase_ward_burst_" + i);
            }

            // A Dark Nest's smoke by day: the nest 30 east and 20 north of home, seen from 50 blocks off, then from
            // 300 blocks south, where it stands in for itself at the edge of the render distance.
            BlockPos nest = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                var at = inside.offset(30, 0, -20);
                at = new BlockPos(
                        at.getX(),
                        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()),
                        at.getZ());
                level.setBlockAndUpdate(at, DarkNests.NEST.defaultBlockState().setValue(DarkNests.STAGE, 4));
                DarkLand.spread(level, at, 4); // the dark land round a full-grown nest
                DarkNests.crystal(level, at, 4); // its crystal, taller than a person
                var player = server.getPlayerList().getPlayers().getFirst();
                level.setAttached(
                        DarkNests.NESTS,
                        java.util.List.of(new DarkNests.Nest(
                                at,
                                inside,
                                player.getUUID(),
                                Hordes.MoonNight.read(level).latestHunt() - 80, // full grown: stage 4
                                LightShop.day(level)))); // its guards already woke today: none in the shots
                return at;
            });
            view(sp, nest, -36, 4, 36, -135, -14);
            ctx.waitTicks(60);
            ctx.takeScreenshot("showcase_nest_smoke");
            // The core up close: the big dark shard, its pool and the darkness spilling off it.
            view(sp, nest, -4, 1.6, 4, -135, -8);
            ctx.waitTicks(40);
            ctx.takeScreenshot("showcase_nest_core");
            int southY = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                level.getChunk(nest.getX() >> 4, (nest.getZ() + 300) >> 4);
                return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nest.getX(), nest.getZ() + 300);
            });
            view(sp, nest, 0, southY - nest.getY() + 1, 300, 180, -6);
            ctx.waitTicks(120);
            ctx.takeScreenshot("showcase_nest_smoke_far");
            sp.getServer().runOnServer(server -> {
                server.overworld().removeAttached(DarkNests.NESTS);
                server.overworld()
                        .setBlockAndUpdate(nest, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            });
            System.out.println("LIGHTBORN WARD AND DRAGON SHOWCASE PASS");
        }
    }

    private static void view(
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
}
