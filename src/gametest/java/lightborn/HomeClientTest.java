package lightborn;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.datafixers.util.Pair;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import lightborn.client.CollectionScreen;
import lightborn.client.MusicDirector;
import lightborn.test.Soft;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;

/** Native furniture/menu input, resource packs, music fallback, channelled travel and form expiry. */
public final class HomeClientTest implements FabricClientGameTest {
    static void require(boolean ok, String why) {
        Soft.require(ok, why);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1920, 1080);
        preparePacks(ctx);
        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .adjustSettings(s -> {
                    s.setWorldType(s.getNormalPresetList().stream()
                            .filter(t -> t.preset().is(WorldPresets.NORMAL))
                            .findFirst()
                            .orElseThrow());
                    s.setGenerateStructures(true);
                })
                .create()) {
            ctx.waitTicks(100);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(3);
                mc.resizeGui();
            });
            BlockPos origin = sp.getServer().computeOnServer(s -> {
                var l = s.overworld();
                var p = s.getPlayerList().getPlayers().getFirst();
                l.getGameRules().set(GameRules.ADVANCE_TIME, false, s);
                l.getGameRules().set(GameRules.SPAWN_MOBS, false, s);
                l.getGameRules().set(GameRules.SPAWN_MONSTERS, false, s);
                l.getGameRules().set(GameRules.ADVANCE_WEATHER, false, s);
                p.setGameMode(GameType.CREATIVE);
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
                var o = l.getRespawnData().pos().east(28).above(5);
                for (var b : BlockPos.betweenClosed(o.offset(-4, -1, -9), o.offset(17, 6, 12)))
                    l.setBlockAndUpdate(
                            b,
                            b.getY() == o.getY() - 1
                                    ? Blocks.SMOOTH_STONE.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState());
                return o;
            });
            sp.getServer().runCommand("time set 6000");
            sp.getServer().runCommand("weather clear");
            sp.getServer().runCommand("title @a clear");
            var kinds = List.of(
                    Furniture.Kind.CHAIR,
                    Furniture.Kind.TABLE,
                    Furniture.Kind.COUCH,
                    Furniture.Kind.RUG,
                    Furniture.Kind.FRIDGE,
                    Furniture.Kind.WARDROBE,
                    Furniture.Kind.LAMP,
                    Furniture.Kind.SHELF);
            var sites = new ArrayList<BlockPos>();
            sp.getServer().runOnServer(s -> {
                var l = s.overworld();
                var p = s.getPlayerList().getPlayers().getFirst();
                int i = 0;
                for (var kind : kinds) {
                    var at = origin.offset((i % 4) * 4, 0, -(i / 4) * 4);
                    sites.add(at);
                    var state = Furniture.BLOCKS
                            .get(kind)
                            .defaultBlockState()
                            .setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH);
                    l.setBlockAndUpdate(at, state);
                    state.getBlock().setPlacedBy(l, at, state, p, new ItemStack(state.getBlock()));
                    i++;
                }
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            });
            teleport(sp, origin.offset(6, 4, 11), 180, 12);
            ctx.waitTicks(50);
            ctx.getInput().lookAt(origin.offset(6, 1, -2));
            ctx.takeScreenshot("home_eight_furnishings_day");
            for (int row : new int[] {0, 2}) {
                var at = sites.get(row);
                teleport(sp, at.south(2), 180, 15);
                ctx.waitTicks(30);
                use(ctx, at);
                sp.getServer()
                        .runOnServer(s -> require(
                                s.getPlayerList().getPlayers().getFirst().isPassenger(),
                                "Furniture did not seat the real player"));
                ctx.takeScreenshot("home_seated_" + row);
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(4);
                ctx.getInput().releaseKey(o -> o.keyShift);
                ctx.waitTicks(10);
            }
            var fridge = sites.get(4);
            teleport(sp, fridge.south(2), 180, 20);
            ctx.waitTicks(30);
            use(ctx, fridge);
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                require(p.containerMenu.getType() == MenuType.GENERIC_9x3, "Fridge did not open a 27-slot menu");
                p.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 8));
                p.getInventory().setItem(10, new ItemStack(Items.APPLE, 4));
            });
            ctx.waitTicks(8);
            ctx.takeScreenshot("home_fridge_before_transfer");
            shiftSlot(ctx, 27);
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                require(
                        ((Furniture.Storage) p.level().getBlockEntity(fridge)).isEmpty()
                                && p.getInventory().getItem(9).getCount() == 8,
                        "Fridge transfer mismatch: slot0="
                                + ((Furniture.Storage) p.level().getBlockEntity(fridge)).getItem(0) + " inventory9="
                                + p.getInventory().getItem(9) + " inventory10="
                                + p.getInventory().getItem(10) + " carried=" + p.containerMenu.getCarried()
                                + " storage="
                                + IntStream.range(0, 27)
                                        .mapToObj(i -> ((Furniture.Storage)
                                                        p.level().getBlockEntity(fridge))
                                                .getItem(i)
                                                .toString())
                                        .toList());
            });
            shiftSlot(ctx, 28);
            ctx.takeScreenshot("home_fridge_food_filter");
            sp.getServer()
                    .runOnServer(s -> require(
                            ((Furniture.Storage) s.overworld().getBlockEntity(fridge))
                                    .getItem(0)
                                    .is(Items.APPLE),
                            "Food did not shift into the fridge"));
            sp.getServer()
                    .runOnServer(s -> s.getPlayerList().getPlayers().getFirst().closeContainer());
            ctx.waitTicks(20);
            var shelf = sites.get(7);
            teleport(sp, shelf.south(2), 180, 15);
            ctx.waitTicks(30);
            for (Item item : List.of(Items.APPLE, Items.BOOK, Items.AMETHYST_SHARD)) {
                sp.getServer()
                        .runOnServer(s -> s.getPlayerList()
                                .getPlayers()
                                .getFirst()
                                .setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item)));
                ctx.waitTicks(5);
                use(ctx, shelf);
            }
            ctx.takeScreenshot("home_shelf_three_displayed_items");
            sp.getServer()
                    .runOnServer(s -> require(
                            s.overworld()
                                            .getEntitiesOfClass(
                                                    Display.ItemDisplay.class,
                                                    new AABB(shelf).inflate(2),
                                                    e -> e.entityTags().contains("lightborn_shelf_display"))
                                            .size()
                                    == 3,
                            "Shelf did not show three real item displays"));
            sp.getServer().runCommand("time set 18000");
            teleport(sp, origin.offset(6, 4, 10), 180, 14);
            ctx.waitTicks(30);
            ctx.getInput().lookAt(origin.offset(6, 1, -2));
            ctx.takeScreenshot("home_furnishings_and_lamp_at_night");
            sp.getServer().runCommand("time set 6000");
            lightProbe(ctx, sp, origin);
            rarityFrames(ctx, sp);
            BlockPos source = origin.south(7), target = origin.east(96);
            sp.getServer().runOnServer(s -> {
                var l = s.overworld();
                var p = s.getPlayerList().getPlayers().getFirst();
                for (var center : List.of(source, target))
                    for (var at : BlockPos.betweenClosed(center.offset(-3, -1, -3), center.offset(3, 3, 3)))
                        l.setBlockAndUpdate(
                                at,
                                at.getY() == center.getY() - 1
                                        ? Blocks.STONE.defaultBlockState()
                                        : Blocks.AIR.defaultBlockState());
                for (var at : List.of(source, target)) {
                    l.setBlockAndUpdate(at, ModItems.LIGHT_CHEST.defaultBlockState());
                    LightWards.claim(l, at, p);
                }
                p.setGameMode(GameType.SURVIVAL);
                p.setHealth(p.getMaxHealth());
                p.setAttached(DragonStory.CHAPTERS, 0);
                p.setAttached(ModData.HUNTS_CLEARED, 3L); // three flights open, the rest locked
                Progression.setMana(p, 100);
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            });
            teleport(sp, source.south(1), 180, 20);
            ctx.waitTicks(40);
            use(ctx, source);
            ctx.runOnClient(mc -> {
                require(mc.gui.screen() instanceof CollectionScreen, "Chest did not open");
                ((CollectionScreen) mc.gui.screen()).select(-4);
            });
            ctx.waitTicks(15);
            ctx.waitTicks(10);
            ctx.takeScreenshot("home_travel_tab_structure_flights");
            ctx.setScreen(() -> null);
            FlightClientTest.call(sp, source, target);
            ctx.waitTicks(85);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                require(ChestTravel.flying(p) && p.isPassenger(), "Dragon did not pick up the real player");
                float hp = p.getHealth();
                p.damageCooldownTime = 0;
                p.hurtServer(p.level(), p.damageSources().generic(), 10);
                require(p.getHealth() == hp, "Flight did not prevent damage");
                p.stopRiding();
                require(p.isPassenger(), "Rider could drop off in mid-air");
                require(!p.canDropItems(), "Rider could drop items");
            });
            ctx.takeScreenshot("home_dragon_carrying_over_terrain");
            ctx.waitTicks(160);
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                require(
                        p.blockPosition().closerThan(target, 4) && LightShop.carriedSparks(p) == 0,
                        "Travel did not arrive at the destination with the fare paid");
            });
            ctx.takeScreenshot("home_travel_arrival_fare_paid");
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                p.setGameMode(GameType.SURVIVAL);
                p.setHealth(p.getMaxHealth());
                Progression.setMana(p, 40);
            });
            ctx.waitTicks(30);
            FlightClientTest.call(sp, target, source);
            ctx.waitTicks(12);
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                p.damageCooldownTime = 0;
                p.hurtServer(p.level(), p.damageSources().generic(), 1);
                require(!ChestTravel.channeling(p), "Taking real damage did not cancel travel");
            });
            ctx.waitTicks(65);
            sp.getServer()
                    .runOnServer(s -> require(
                            s.getPlayerList()
                                    .getPlayers()
                                    .getFirst()
                                    .blockPosition()
                                    .closerThan(target, 4),
                            "Cancelled travel still moved the player"));
            ctx.waitTicks(210);
            BlockPos far = target.east(6000);
            sp.getServer().runOnServer(s -> {
                var l = s.overworld();
                l.getChunkAt(far);
                for (var b : BlockPos.betweenClosed(far.offset(-2, -1, -2), far.offset(2, 3, 2)))
                    l.setBlockAndUpdate(
                            b,
                            b.getY() == far.getY() - 1
                                    ? Blocks.STONE.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState());
                l.setBlockAndUpdate(far, ModItems.LIGHT_CHEST.defaultBlockState());
                var p = s.getPlayerList().getPlayers().getFirst();
                LightWards.claim(l, far, p);
                s.overworld().setAttached(DragonStory.ENDING, 3);
                p.setAttached(DragonForm.COOLDOWN, 1234567L);
            });
            FlightClientTest.call(sp, target, far);
            ctx.waitTicks(105);
            ctx.takeScreenshot("home_dragon_heart_travel_form");
            ctx.waitTicks(140);
            ctx.takeScreenshot("home_long_flight_cloud_sea");
            ctx.waitTicks(235);
            FlightClientTest.arrive(ctx, sp, far);
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                require(
                        Math.hypot(p.getX() - far.getX() - .5, p.getZ() - far.getZ() - .5)
                                        < 4 // surface above a buried pad
                                && !ChestTravel.flying(p)
                                && !p.isPassenger(),
                        "Long trip did not land: " + p.position() + " target=" + far + " flying="
                                + ChestTravel.flying(p) + " vehicle=" + p.getVehicle());
                require(
                        p.getAttachedOrElse(DragonForm.COOLDOWN, 0L) == 1234567L,
                        "Travel spent the normal Dragon Form cooldown");
                var middle = target.east(3000);
                require(
                        s.overworld().getChunkSource().getChunkNow(middle.getX() >> 4, middle.getZ() >> 4) == null,
                        "Long flight generated the middle corridor");
                p.setAttached(DragonForm.COOLDOWN, 0L);
            });
            ctx.takeScreenshot("home_six_thousand_block_arrival");
            // Empty folders really fall back; this test-only pool references an existing vanilla sound, adding no
            // tracks.
            ctx.runOnClient(mc ->
                    require(!MusicDirector.overridesVanilla(), "Empty music folders did not fall back to vanilla"));
            testMusicPool(ctx);
            ctx.waitTicks(30);
            ctx.runOnClient(mc ->
                    require(MusicDirector.overridesVanilla(), "Situational soundtrack did not use a populated pool"));
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                s.overworld().setAttached(DragonStory.ENDING, 3);
                p.setAttached(DragonStory.CHAPTERS, 9);
                p.setHealth(p.getMaxHealth());
            });
            ctx.getInput().pressKey(InputConstants.KEY_R);
            ctx.waitTicks(25);
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                require(
                        DragonForm.active(p) && p.getAbilities().mayfly && p.getAbilities().flying,
                        "Form must remain in flight even after touching down: active=" + DragonForm.active(p)
                                + " flying=" + p.getAbilities().flying);
            });
            ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            ctx.waitTicks(10);
            ctx.takeScreenshot("home_dragon_form_hover");
            ctx.getInput().pressKey(InputConstants.KEY_V);
            ctx.waitTicks(8);
            ctx.takeScreenshot("home_dragon_form_breath");
            ctx.waitTicks(610);
            sp.getServer().runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                require(
                        p.isAlive() && !DragonForm.active(p) && !p.getAbilities().mayfly && !p.isInvisible(),
                        "Form expiry failed to restore the living survival player");
            });
            ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
            sp.getServer().runOnServer(s -> {
                var l = s.overworld();
                var registry = l.registryAccess().lookupOrThrow(Registries.STRUCTURE);
                var list = new ArrayList<Holder<Structure>>();
                for (var st : registry)
                    if (registry.getKey(st).getNamespace().equals("mvs")) list.add(registry.wrapAsHolder(st));
                var at = l.findNearestMapStructure(HolderSet.direct(list), source, 128, false);
                require(at != null, "Moog's structures have no generating placement");
                var c = l.getChunk(at.getX() >> 4, at.getZ() >> 4);
                require(
                        c.getAllStarts().entrySet().stream()
                                .anyMatch(e -> registry.getKey(e.getKey())
                                                .getNamespace()
                                                .equals("mvs")
                                        && e.getValue().isValid()
                                        && !e.getValue().getPieces().isEmpty()),
                        "Moog's structure did not generate pieces");
            });
            System.out.println(
                    "LIGHTBORN HOME CLIENT PASS: all companion client mods, Fresh Animations, furniture and food filtering, shelf displays, travel and damage interruption, music fallback/pool, Dragon Form flight and expiry, Moog generation");
        }
    }

    static void preparePacks(ClientGameTestContext ctx) {
        try {
            Path game = ctx.computeOnClient(mc -> mc.gameDirectory.toPath());
            Path root = game.getParent().getParent().getParent();
            var command = new ProcessBuilder(
                            "py",
                            "-3.14",
                            "-I",
                            root.resolve("tools/prepare_game.py").toString(),
                            "--game-dir",
                            game.toString(),
                            "--music-dir",
                            root.resolve(".preview/test-music").toString())
                    .redirectErrorStream(true)
                    .start();
            var text = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            require(command.waitFor() == 0, text);
            reload(ctx);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void rarityFrames(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.SURVIVAL);
            int i = 9;
            for (var rarity : LootRoller.Rarity.values())
                p.getInventory()
                        .setItem(i++, LootRoller.roll(p.getRandom(), rarity, ModItems.Tier.IRON, ModItems.Kind.SWORD));
        });
        ctx.waitTicks(12);
        ctx.runOnClient(mc -> {
            try {
                var lt = Class.forName("com.anthonyhilyard.legendarytooltips.config.LegendaryTooltipsConfig");
                var ib = Class.forName("com.anthonyhilyard.itemborders.config.ItemBordersConfig");
                var frames = lt.getMethod("getInstance").invoke(null);
                var borders = ib.getMethod("getInstance").invoke(null);
                int i = 9;
                for (var r : LootRoller.Rarity.values()) {
                    var stack = mc.player.getInventory().getItem(i++);
                    var frame = lt.getMethod("getFrameDefinition", ItemStack.class, HolderLookup.Provider.class)
                            .invoke(frames, stack, mc.level.registryAccess());
                    int color = ((Supplier<Integer>)
                                    frame.getClass().getMethod("startBorder").invoke(frame))
                            .get();
                    require(
                            (color & 0xFFFFFF)
                                    == TextColor.fromLegacyFormat(r.color).getValue(),
                            "Tooltip frame disagrees with " + r + ": " + Integer.toHexString(color));
                    var pair = (Pair<?, ?>)
                            ib.getMethod("getBorderColorForItem", ItemStack.class, HolderLookup.Provider.class)
                                    .invoke(borders, stack, mc.level.registryAccess());
                    require(
                            pair != null
                                    && (((Integer) ((Supplier<?>) pair.getFirst()).get()) & 0xFFFFFF)
                                            == TextColor.fromLegacyFormat(r.color)
                                                    .getValue(),
                            "Slot border disagrees with " + r);
                }
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.getInput().pressKey(InputConstants.KEY_E);
        ctx.waitTicks(10);
        var xy = ctx.computeOnClient(mc -> {
            var slot = mc.player.containerMenu.getSlot(10);
            int left = (mc.getWindow().getGuiScaledWidth() - 176) / 2,
                    top = (mc.getWindow().getGuiScaledHeight() - 166) / 2;
            return new double[] {
                (left + slot.x + 8) * mc.getWindow().getGuiScale(),
                (top + slot.y + 8) * mc.getWindow().getGuiScale()
            };
        });
        ctx.getInput().setCursorPos(xy[0], xy[1]);
        ctx.waitTicks(5);
        ctx.takeScreenshot("home_lightborn_rarity_frames");
        ctx.getInput().pressKey(InputConstants.KEY_E);
        ctx.waitTicks(6);
        sp.getServer().runOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
        });
    }

    static void lightProbe(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos origin) {
        BlockPos torch = new BlockPos(origin.getX() - 40, 260, origin.getZ()),
                chest = new BlockPos(origin.getX() + 40, 260, origin.getZ());
        sp.getServer().runOnServer(s -> {
            var l = s.overworld();
            for (var at : List.of(torch, chest)) {
                l.getChunkAt(at);
                l.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
            }
            l.setBlockAndUpdate(torch, Blocks.TORCH.defaultBlockState());
            l.setBlockAndUpdate(chest, ModItems.LIGHT_CHEST.defaultBlockState());
        });
        ctx.waitTicks(70);
        var radii = ctx.computeOnClient(mc -> {
            int a = 0, b = 0;
            for (int d = 0; d < 32; d++) {
                if (mc.level.getBrightness(LightLayer.BLOCK, torch.offset(0, 1, d)) > 0) a = d;
                if (mc.level.getBrightness(LightLayer.BLOCK, chest.offset(0, 1, d)) > 0) b = d;
            }
            return new int[] {a, b};
        });
        // The chest lights its whole ward (client glow) and nothing past the wall (owner, 2026-11-16).
        require(
                radii[0] > 0 && radii[1] >= Balance.LIGHT_WARD_RADIUS - 1 && radii[1] <= Balance.LIGHT_WARD_RADIUS,
                "Chest light does not end at its 9-block wall (torch, chest): " + Arrays.toString(radii));
        System.out.println("LIGHTBORN LIGHT RADII: torch=" + radii[0] + " chest=" + radii[1]);
        sp.getServer().runOnServer(s -> {
            s.overworld().setBlockAndUpdate(torch, Blocks.AIR.defaultBlockState());
            s.overworld().setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
        });
    }

    static void testMusicPool(ClientGameTestContext ctx) {
        try {
            Path game = ctx.computeOnClient(mc -> mc.gameDirectory.toPath());
            Path p = game.resolve("resourcepacks/Lightborn-Music/assets/lightborn/sounds.json");
            var json = new JsonObject();
            for (String mood : GameMusic.MOODS) {
                var event = new JsonObject();
                var array = new JsonArray();
                var ref = new JsonObject();
                ref.addProperty("name", "minecraft:music.game");
                ref.addProperty("type", "event");
                array.add(ref);
                event.add("sounds", array);
                json.add("music." + mood, event);
            }
            Files.writeString(p, json.toString());
            reload(ctx);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void reload(ClientGameTestContext ctx) {
        var future = ctx.computeOnClient(mc -> {
            try {
                var options = Files.readAllLines(mc.gameDirectory.toPath().resolve("options.txt"));
                var line = options.stream()
                        .filter(s -> s.startsWith("resourcePacks:"))
                        .findFirst()
                        .orElseThrow();
                var names = JsonParser.parseString(line.substring(line.indexOf(':') + 1))
                        .getAsJsonArray();
                var packs = new ArrayList<String>();
                names.forEach(n -> packs.add(n.getAsString()));
                mc.getResourcePackRepository().reload();
                mc.getResourcePackRepository().setSelected(packs);
                mc.options.resourcePacks.clear();
                mc.options.resourcePacks.addAll(packs);
                return mc.reloadResourcePacks();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        for (int i = 0; i < 400 && !future.isDone(); i++) ctx.waitTicks(2);
        require(future.isDone(), "Resource reload timed out");
        future.join();
    }

    static void teleport(TestSingleplayerContext sp, BlockPos at, float yaw, float pitch) {
        sp.getServer().runOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            p.teleportTo(s.overworld(), at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), yaw, pitch, false);
        });
    }

    static void use(ClientGameTestContext ctx, BlockPos at) {
        ctx.getInput().lookAt(at);
        ctx.waitTicks(4);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
        ctx.waitTicks(10);
    }

    static void press(ClientGameTestContext ctx, double[] xy) {
        ctx.getInput().setCursorPos(xy[0], xy[1]);
        ctx.waitTicks(3);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ctx.waitTicks(8);
    }

    static void shiftSlot(ClientGameTestContext ctx, int index) {
        ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(
                mc.player.containerMenu.containerId, index, 0, ContainerInput.QUICK_MOVE, mc.player));
        ctx.waitTicks(10);
    }
}
