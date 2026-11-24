package lightborn;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import lightborn.client.CollectionScreen;
import lightborn.test.Soft;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/** Focused regression for this batch: native mouse packet, source lighting and actual ward movement. */
public final class ChestWardClientTest implements FabricClientGameTest {
    static void check(boolean ok, String message) {
        Soft.require(ok, message);
    }

    static int number(Object o, String name) {
        try {
            var m = o.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return (int) m.invoke(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Object field(Object o, String name) {
        try {
            var f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void click(ClientGameTestContext ctx, double[] xy) {
        ctx.getInput().setCursorPos(xy[0], xy[1]);
        ctx.waitTicks(3);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ctx.waitTicks(8);
    }

    static void teleport(TestSingleplayerContext sp, Vec3 at) {
        sp.getServer().runOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            p.teleportTo(s.overworld(), at.x, at.y, at.z, Set.of(), 0, 0, false);
        });
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1920, 1080);
        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .adjustSettings(s -> s.setWorldType(s.getNormalPresetList().stream()
                        .filter(p -> p.preset().is(WorldPresets.FLAT))
                        .findFirst()
                        .orElseThrow()))
                .create()) {
            ctx.waitTicks(65);
            BlockPos chest = sp.getServer()
                    .computeOnServer(s ->
                            SpawnHut.lightChestAt(s.overworld().getRespawnData().pos()));
            sp.getServer().runOnServer(s -> {
                var l = s.overworld();
                l.getGameRules().set(GameRules.SPAWN_MOBS, false, s);
                l.getGameRules().set(GameRules.ADVANCE_TIME, false, s);
                var p = s.getPlayerList().getPlayers().getFirst();
                PlayerBonuses.apply(p);
                p.getInventory().clearContent(); // the starting kit's off-hand tome would take the altar first
                p.getInventory().setItem(3, new ItemStack(ModItems.TOMES.get(Spell.ARCANE_BOLT)));
                p.getInventory().setItem(4, new ItemStack(ModItems.SPARK, 20)); // tier I costs 10 Sparks
                check(
                        s.getAdvancements().getAllAdvancements().stream()
                                .noneMatch(a -> a.id().getNamespace().equals("lightborn")
                                        && a.id().getPath().startsWith("bestiary/")),
                        "Bestiary still installed");
                check(
                        BuiltInRegistries.ITEM.keySet().stream()
                                .noneMatch(id -> id.getNamespace().equals("farmersdelight")),
                        "Farmer's Delight still loaded");
                Collection.build(l.enabledFeatures(), l.registryAccess());
                var counts = new TreeMap<String, Integer>();
                for (String id : Collection.all()) counts.merge(id.substring(0, id.indexOf(':')), 1, Integer::sum);
                System.out.println("CHEST CATALOGUE " + Collection.all().size() + " " + counts);
                if (FabricLoader.getInstance().isModLoaded("thetruepaxels")) checkPaxelDurability();
            });
            teleport(sp, Vec3.atBottomCenterOf(chest).add(0, 0, 3));
            ctx.waitTicks(15);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(3);
                mc.resizeGui();
                mc.gui.setScreen(new CollectionScreen(chest));
            });
            ctx.waitTicks(8);
            ctx.runOnClient(mc -> check(
                    (int) field(mc.gui.screen(), "selected") >= 0,
                    "A fresh player with the shop locked did not open on the Collection"));
            click(ctx, ctx.computeOnClient(mc -> {
                var screen = mc.gui.screen();
                double scale = mc.getWindow().getGuiScale();
                return new double[] {
                    (number(screen, "left") + 35) * scale,
                    (number(screen, "top") + 28 + .5 * number(screen, "tabHeight")) * scale
                };
            }));
            ctx.runOnClient(mc ->
                    check((int) field(mc.gui.screen(), "selected") == -5, "Upgrade Spells is not the first tab"));
            click(ctx, ctx.computeOnClient(mc -> {
                var xy = ((CollectionScreen) mc.gui.screen()).upgradeCard(0, 0);
                double scale = mc.getWindow().getGuiScale();
                return new double[] {xy[0] * scale, xy[1] * scale};
            }));
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                check(
                        SpellUpgrades.chosen(p.getInventory().getItem(3))[0] == 0,
                        "Left click did not write Honed Focus on the Arcane Bolt tome");
                PlayerBonuses.apply(p);
            });
            ctx.getInput().setCursorPos(1, 1);
            ctx.waitTicks(5);
            ctx.takeScreenshot("chest_upgrade_spells_tab");
            ctx.runOnClient(mc -> mc.gui.setScreen(null));
            teleport(sp, Vec3.atBottomCenterOf(chest).add(60, 0, 0));
            ctx.waitTicks(12);
            ctx.runOnClient(mc -> ClientPlayNetworking.send(new SpellUpgrades.Choose(chest, 3, 1, 0)));
            ctx.waitTicks(8);
            sp.getServer()
                    .runOnServer(s -> check(
                            SpellUpgrades.chosen(s.getPlayerList()
                                            .getPlayers()
                                            .getFirst()
                                            .getInventory()
                                            .getItem(3))[1]
                                    == -1,
                            "Remote upgrade accepted"));

            checkWardReentry(ctx, sp, chest);

            BlockPos surface = chest.offset(60, 0, 0),
                    cave =
                            sp.getServer()
                                    .computeOnServer(s -> new BlockPos(
                                            surface.getX() + 24, s.overworld().getMinY() + 1, surface.getZ())),
                    house = surface.offset(0, 0, 24),
                    magic = surface.offset(24, 0, 24);
            sp.getServer().runOnServer(s -> {
                var l = s.overworld();
                for (int x = (surface.getX() >> 4) - 1; x <= (magic.getX() >> 4) + 1; x++)
                    for (int z = (surface.getZ() >> 4) - 1; z <= (magic.getZ() >> 4) + 1; z++) {
                        l.setChunkForced(x, z, true);
                        l.getChunk(x, z);
                    }
                for (var at : List.of(surface, house, magic)) {
                    l.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
                    l.setBlockAndUpdate(at, Blocks.TORCH.defaultBlockState());
                }
                for (var at : BlockPos.betweenClosed(cave.offset(-1, 0, -1), cave.offset(1, 1, 1)))
                    l.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                // Fabric's flat preset is a test platform; construct a generated-rock cave roof and floor explicitly.
                for (var at : BlockPos.betweenClosed(cave.offset(-1, 2, -1), cave.offset(1, 2, 1)))
                    l.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
                l.setBlockAndUpdate(cave.below(), Blocks.STONE.defaultBlockState());
                l.setBlockAndUpdate(cave, Blocks.TORCH.defaultBlockState());
                // A player-built stone roof must count as a surface house, not a cave.
                for (var at : BlockPos.betweenClosed(house.offset(-2, 2, -2), house.offset(2, 2, 2))) {
                    l.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
                    SurfaceLights.placed(l, at);
                }
                l.setBlockAndUpdate(magic, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15));
                check(SurfaceLights.surface(l.getChunkAt(surface), surface), "Open surface classified as cave");
                check(
                        !SurfaceLights.surface(l.getChunkAt(cave), cave),
                        "Natural underground ceiling missed: " + l.getBlockState(cave.above(2)));
                check(SurfaceLights.surface(l.getChunkAt(house), house), "Built stone roof classified as cave");
            });
            sp.getServer().runCommand("time set 6000");
            ctx.waitTicks(65);
            sp.getServer().runOnServer(s -> {
                var l = s.overworld();
                check(l.getBrightness(LightLayer.BLOCK, surface) == 14, "Day torch not lit");
            });
            sp.getServer().runCommand("time set 18000");
            ctx.waitTicks(70);
            sp.getServer().runOnServer(s -> {
                var l = s.overworld();
                int a = l.getBrightness(LightLayer.BLOCK, surface),
                        b = l.getBrightness(LightLayer.BLOCK, cave),
                        c = l.getBrightness(LightLayer.BLOCK, house),
                        d = l.getBrightness(LightLayer.BLOCK, magic);
                System.out.println("NIGHT LIGHTS surface=" + a + " cave=" + b + " house=" + c + " magic=" + d);
                check(a == 0 && b == 14 && c == 0 && d == 15, "Surface/cave/magic lighting rule failed");
            });
            ctx.runOnClient(mc -> check(
                    mc.level.getBrightness(LightLayer.BLOCK, surface) == 0
                            && mc.level.getBrightness(LightLayer.BLOCK, house) == 0
                            && mc.level.getBrightness(LightLayer.BLOCK, cave) == 14
                            && mc.level.getBrightness(LightLayer.BLOCK, magic) == 15,
                    "Client lighting disagrees with server"));
            sp.getServer().runCommand("time set 6000");
            ctx.waitTicks(60);
            sp.getServer().runOnServer(s -> {
                var l = s.overworld();
                check(
                        l.getBrightness(LightLayer.BLOCK, surface) == 14
                                && l.getBrightness(LightLayer.BLOCK, house) == 14,
                        "Dawn did not restore lights");
            });

            teleport(sp, Vec3.atBottomCenterOf(chest).add(25, 0, 25));
            ctx.waitTicks(20);
            sp.getServer().runOnServer(s -> {
                var l = s.overworld();
                var p = s.getPlayerList().getPlayers().getFirst();
                p.setGameMode(GameType.CREATIVE);
                var center = Vec3.atCenterOf(chest);
                var z = EntityTypes.ZOMBIE.create(l, EntitySpawnReason.COMMAND);
                z.setNoAi(true);
                z.setPersistenceRequired();
                z.setPos(center.x + 25, chest.getY(), center.z);
                z.tickCount = 25;
                l.addFreshEntity(z);
                z.move(MoverType.SELF, new Vec3(-50, 0, 0));
                check(z.getX() > center.x + Balance.LIGHT_WARD_RADIUS - 1, "Walking zombie crossed ward");
                z.setPos(center.x, chest.getY(), center.z);
                check(z.getX() > center.x + Balance.LIGHT_WARD_RADIUS - 1, "Teleport crossed ward");
                var skeleton = EntityTypes.SKELETON.create(l, EntitySpawnReason.COMMAND);
                skeleton.setNoAi(true);
                skeleton.setPos(center.x, center.y + 25, center.z);
                skeleton.tickCount = 20;
                l.addFreshEntity(skeleton);
                skeleton.move(MoverType.SELF, new Vec3(0, -50, 0));
                check(skeleton.getY() > center.y + Balance.LIGHT_WARD_RADIUS - 1, "Flying mob crossed top of sphere");
                skeleton.discard();
                var wolf = EntityTypes.WOLF.create(l, EntitySpawnReason.COMMAND);
                check(LightWards.limitMovement(wolf, new Vec3(30, 0, 0)).x == 30, "Animal blocked");
                var dragon = new LightDragon(ModEntities.LIGHT_DRAGON, l);
                dragon.bind(chest);
                var intro = DragonTalk.respond(p, dragon, 0);
                check(
                        intro.text().contains("can see me") && !intro.text().contains("The Dragon studies"),
                        "Narrator remains");
                for (int route : new int[] {11, 12, 13}) {
                    p.setAttached(DragonTalk.JOURNEY, DragonTalk.Journey.NEW);
                    var speech = DragonTalk.respond(p, dragon, route).text();
                    check(
                            !speech.contains("His eyes") && !speech.contains("He ") && !speech.contains("he says"),
                            "Narrator in route " + route);
                }
                for (int i = 0; i < 5; i++) {
                    var mob = EntityTypes.ZOMBIE.create(l, EntitySpawnReason.COMMAND);
                    mob.setNoAi(true);
                    mob.setPersistenceRequired();
                    double angle = .2 + i * .23;
                    mob.setPos(center.x + Math.cos(angle) * 21, chest.getY(), center.z + Math.sin(angle) * 21);
                    l.addFreshEntity(mob);
                }
            });
            sp.getServer().runCommand("time set 18000");
            ctx.waitTicks(45);
            teleport(sp, Vec3.atCenterOf(chest).add(31, 10, 30));
            ctx.waitTicks(15);
            ctx.getInput().lookAt(chest.offset(0, 5, 0));
            ctx.getInput().pressKey(InputConstants.KEY_F1);
            ctx.waitTicks(10);
            ctx.takeScreenshot("golden_ward_sphere_night");
            teleport(sp, Vec3.atCenterOf(chest).add(3, 1, 2)); // inside the 9-block ward, looking out at the wall
            ctx.waitTicks(10);
            ctx.getInput().lookAt(chest.offset(12, -1, 6));
            ctx.waitTicks(5);
            ctx.takeScreenshot("golden_ward_from_inside");
            System.out.println(
                    "LIGHTBORN CHEST WARD CLIENT PASS: native skill allocation, remote rejection, bestiary/Farmer removal, surface+cave+magical night lighting, dawn recovery, walk/teleport/top barrier, first-person dialogue");
        }
    }

    private static void checkPaxelDurability() {
        Map.of(
                        "wooden", ToolMaterial.WOOD,
                        "stone", ToolMaterial.STONE,
                        "copper", ToolMaterial.COPPER,
                        "golden", ToolMaterial.GOLD,
                        "iron", ToolMaterial.IRON,
                        "diamond", ToolMaterial.DIAMOND,
                        "netherite", ToolMaterial.NETHERITE)
                .forEach((tier, material) -> {
                    var id = Identifier.fromNamespaceAndPath("thetruepaxels", tier + "_paxel");
                    int actual = new ItemStack(BuiltInRegistries.ITEM.getValue(id)).getMaxDamage();
                    check(actual == 4 * material.durability(), tier + " paxel has durability " + actual);
                });
    }

    private static void checkWardReentry(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos chest) {
        Soft.step("ward reentry");
        Vec3 center = Vec3.atBottomCenterOf(chest);
        double radius = Balance.LIGHT_WARD_RADIUS;
        teleport(sp, center.add(radius - 2, 0, 0));
        ctx.waitTicks(10);
        ctx.getInput().lookAt(chest.offset(18, 0, 0));
        ctx.getInput().holdKey(o -> o.keyUp);
        ctx.getInput().holdKey(o -> o.keySprint);
        boolean escaped = false;
        for (int tick = 0; tick < 100 && !escaped; tick++) {
            ctx.waitTicks(1);
            escaped = ctx.computeOnClient(mc -> mc.player.getX() > center.x + radius + .2);
        }
        ctx.getInput().releaseKey(o -> o.keyUp);
        ctx.getInput().releaseKey(o -> o.keySprint);
        check(escaped, "Sprinting outward never broke through the ward");
        if (!escaped) return;

        // Touch the outside edge without movement input, first during grace and then after it runs out.
        teleport(sp, center.add(radius + .4, 0, 0));
        ctx.waitTicks(12);
        check(
                ctx.computeOnClient(mc -> mc.player.getX() > center.x + radius + .25),
                "The ward pulled inward immediately after breaking out");
        ctx.waitTicks(60);
        check(
                ctx.computeOnClient(mc -> mc.player.getX() < center.x + radius - .3),
                "The inward pull did not return after the exit grace period");
    }
}
