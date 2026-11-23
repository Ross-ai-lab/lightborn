package lightborn;

import java.util.*;
import lightborn.test.Soft;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Focused transport regression: real rider, initial entity sync, cloud handoff, dimensions and cooldown. */
public final class FlightClientTest implements FabricClientGameTest {
    static void require(boolean ok, String text) {
        Soft.require(ok, text);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1920, 1080);
        // Flat, but with structures on: the Nether flight needs a real fortress for the Scale to point at.
        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .adjustSettings(s -> {
                    s.setWorldType(s.getNormalPresetList().stream()
                            .filter(t -> t.preset().is(WorldPresets.FLAT))
                            .findFirst()
                            .orElseThrow());
                    s.setGenerateStructures(true);
                })
                .create()) {
            ctx.waitTicks(70);
            var a = sp.getServer().computeOnServer(s -> {
                var l = s.overworld();
                l.getGameRules().set(GameRules.ADVANCE_TIME, false, s);
                l.getGameRules().set(GameRules.SPAWN_MOBS, false, s);
                l.getGameRules().set(GameRules.SPAWN_MONSTERS, false, s);
                var p = s.getPlayerList().getPlayers().getFirst();
                return p.blockPosition().east(30);
            });
            var b = a.east(96);
            var far = b.east(6000);
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                p.setGameMode(GameType.SURVIVAL);
                p.setHealth(p.getMaxHealth());
                for (var at : List.of(a, b, far)) {
                    var l = s.overworld();
                    l.getChunkAt(at);
                    for (var pos : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, 3, 2)))
                        l.setBlockAndUpdate(
                                pos,
                                pos.getY() == at.getY() - 1
                                        ? Blocks.STONE.defaultBlockState()
                                        : Blocks.AIR.defaultBlockState());
                    l.setBlockAndUpdate(at, ModItems.LIGHT_CHEST.defaultBlockState());
                    LightWards.claim(l, at, p);
                }
                p.teleportTo(s.overworld(), a.getX() + .5, a.getY(), a.getZ() + 1.5, Set.of(), -90, 0, false);
            });
            ctx.waitTicks(50);
            go(ctx, sp, a, b);
            ctx.waitTicks(100);
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                require(ChestTravel.flying(p) && p.isPassenger(), "No real rider");
                var d = p.getVehicle();
                System.out.println("FLIGHT SERVER CARRY player=" + p.position() + " dragon=" + d.position());
                float hp = p.getHealth();
                p.damageCooldownTime = 0;
                p.hurtServer(p.level(), p.damageSources().generic(), 5);
                require(hp == p.getHealth(), "Survival rider took damage");
                p.stopRiding();
                require(p.isPassenger(), "Server allowed a mid-air dismount");
            });
            ctx.runOnClient(mc -> {
                require(mc.player.isPassenger(), "Client is not riding");
                var d = (LightDragon) mc.player.getVehicle();
                System.out.println("FLIGHT CLIENT CARRY role=" + d.role() + " player=" + mc.player.position()
                        + " dragon=" + d.position());
                require(d.role() == 6, "Dragon role did not sync at spawn");
                require(mc.player.getY() < d.getY(), "Rider is above rather than carried below the Dragon");
            });
            ctx.getInput().holdKey(o -> o.keyShift);
            ctx.waitTicks(10);
            ctx.getInput().releaseKey(o -> o.keyShift);
            ctx.runOnClient(mc -> require(mc.player.isPassenger(), "Sneak detached the client rider"));
            ctx.takeScreenshot("flight_carried_in_claws");
            arrive(ctx, sp, b);
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                s.overworld().setAttached(DragonStory.ENDING, 3);
                p.setAttached(DragonForm.COOLDOWN, 1234567L);
            });
            long begin = go(ctx, sp, b, far);
            ctx.waitTicks(110);
            ctx.takeScreenshot("flight_dragon_heart_body");
            ctx.waitTicks(140);
            ctx.takeScreenshot("flight_cloud_handoff");
            arrive(ctx, sp, far);
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                require(s.overworld().getGameTime() - begin < 600, "Route exceeded thirty seconds of game time");
                require(
                        p.getAttachedOrElse(DragonForm.COOLDOWN, 0L) == 1234567L,
                        "Travel spent the normal Form cooldown");
                require(!p.isNoGravity() && !p.noPhysics && !p.isInvisible(), "Travel did not restore the player");
                var middle = b.east(3000);
                require(
                        s.overworld().getChunkSource().getChunkNow(middle.getX() >> 4, middle.getZ() >> 4) == null,
                        "Middle corridor was generated");
            });
            ctx.takeScreenshot("flight_six_thousand_block_arrival");
            nether(ctx, sp, far);
            System.out.println(
                    "LIGHTBORN FLIGHT CLIENT PASS: real survival carry, initial sync, damage and sneak protection, 6000-block cloud transfer, Scale's Fortress Nether crossing, restoration and cooldown");
        }
    }

    /**
     * The Scale's Fortress flight (owner, 2026-11-16): from chapter 6 the Dragon flies from an Overworld chest, dives
     * through the cloud veil and sets the rider down on the fortress's nether-brick, under the Nether's roof.
     */
    static void nether(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos chest) {
        String began = sp.getServer().computeOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            p.setAttached(DragonStory.CHAPTERS, 6);
            p.setAttached(ModData.HUNTS_CLEARED, 10L); // every flight is open
            int index = 0;
            while (!ChestTravel.SITES.get(index).nether()) index++;
            p.getInventory()
                    .add(new net.minecraft.world.item.ItemStack(
                            ModItems.SPARK, ChestTravel.SITES.get(index).price()));
            String said = ChestTravel.start(p, chest, index);
            // start() answers with the call line on success, so the proof is the channel itself.
            return ChestTravel.channeling(p) ? "" : said;
        });
        require(began.isEmpty(), "Scale's Fortress flight did not start: " + began);
        ctx.waitTicks(150);
        ctx.takeScreenshot("flight_nether_veil");
        for (int i = 0; i < 400; i++) {
            boolean active = sp.getServer()
                    .computeOnServer(s -> ChestTravel.channeling(
                            s.getPlayerList().getPlayers().getFirst()));
            if (!active) break;
            ctx.waitTicks(4);
        }
        ctx.waitTicks(40);
        sp.getServer().runOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            var l = p.level();
            var fort = DragonStory.scaleFortress(p);
            var feet = p.blockPosition();
            require(
                    l.dimension() == net.minecraft.world.level.Level.NETHER
                            && !ChestTravel.flying(p)
                            && !p.isPassenger()
                            && p.isAlive()
                            && p.getY() < 127
                            && l.getBlockState(feet.below()).isSolid()
                            && !p.isInLava()
                            && fort != null
                            && Math.hypot(p.getX() - fort.getX(), p.getZ() - fort.getZ()) < 48,
                    "Scale's Fortress flight did not land on the fortress: dimension=" + l.dimension()
                            + " position=" + p.position() + " fort=" + fort + " below="
                            + l.getBlockState(feet.below()) + " flying=" + ChestTravel.flying(p) + " alive="
                            + p.isAlive());
        });
        // The client needs a moment to load the Nether after the server sets the rider down.
        for (int i = 0; i < 100; i++) {
            boolean ready = ctx.computeOnClient(mc -> mc.level != null
                    && mc.level.dimension() == net.minecraft.world.level.Level.NETHER
                    && mc.gui.screen() == null);
            if (ready) break;
            ctx.waitTicks(4);
        }
        ctx.waitTicks(40);
        ctx.takeScreenshot("flight_scales_fortress_arrival");
    }

    /**
     * Starts a Dragon flight from a chest to the ground beside another, the way a structure trip starts once its site
     * is found (owner, 2026-11-16: flights go to structures now), with the fare put in the player's hand.
     */
    static void call(TestSingleplayerContext sp, BlockPos source, BlockPos target) {
        sp.getServer().runOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            var site = ChestTravel.SITES.getFirst();
            p.getInventory().add(new net.minecraft.world.item.ItemStack(ModItems.SPARK, site.price()));
            var land = ChestTravel.ground(p.level(), target.getX(), target.getZ() + 1);
            ChestTravel.begin(p, source, land == null ? target.south() : land, site);
        });
    }

    static long go(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos source, BlockPos target) {
        long now = sp.getServer().computeOnServer(s -> s.overworld().getGameTime());
        FlightClientTest.call(sp, source, target);
        ctx.waitTicks(5);
        sp.getServer().runOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            require(ChestTravel.channeling(p), "Flight request did not start: " + p.position());
        });
        return now;
    }

    static void arrive(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos target) {
        for (int i = 0; i < 400; i++) {
            boolean active = sp.getServer()
                    .computeOnServer(s -> ChestTravel.channeling(
                            s.getPlayerList().getPlayers().getFirst()));
            if (!active) break;
            ctx.waitTicks(4);
        }
        sp.getServer().runOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            require(
                    !ChestTravel.channeling(p)
                            && !ChestTravel.flying(p)
                            && !p.isPassenger()
                            // beside it on the surface: a buried target lands on the ground above
                            && Math.hypot(p.getX() - target.getX() - .5, p.getZ() - target.getZ() - .5) < 4,
                    "Flight did not land: position=" + p.position() + " target=" + target + " active="
                            + ChestTravel.channeling(p) + " flying=" + ChestTravel.flying(p) + " vehicle="
                            + p.getVehicle());
        });
    }
}
