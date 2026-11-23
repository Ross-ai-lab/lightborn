package lightborn;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import lightborn.client.CollectionScreen;
import lightborn.test.Soft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Real normal-world progression, both irreversible endings, native map/key input and visual review frames. */
public final class StoryClientTest implements FabricClientGameTest {
    /** Blocks east of home, past any ward's edge, where the player may fight. */
    private static final int WARD_CLEAR = 100;

    static void require(boolean ok, String why) {
        Soft.require(ok, why);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1920, 1080);
        for (boolean fight : List.of(false, true))
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
                BlockPos home = sp.getServer().computeOnServer(s -> {
                    var p = s.getPlayerList().getPlayers().getFirst();
                    s.overworld().getGameRules().set(GameRules.ADVANCE_TIME, false, s);
                    s.overworld().getGameRules().set(GameRules.SPAWN_MOBS, false, s);
                    s.overworld().getGameRules().set(GameRules.ADVANCE_WEATHER, false, s);
                    p.setGameMode(GameType.CREATIVE);
                    return s.overworld().getRespawnData().pos();
                });
                BlockPos chest = SpawnHut.lightChestAt(home);
                if (!fight) {
                    night(sp, 1);
                    ctx.waitTicks(90);
                    sp.getServer()
                            .runOnServer(s -> require(
                                    LightDragons.get(s.overworld(), chest) != null,
                                    "The first-night Dragon cannot appear beside the compact hut"));
                    open(ctx, sp, chest);
                    click(ctx, "Stay as long as you like.");
                    click(ctx, "Tell me more about yourself."); // a ready chapter is told on the spot
                    ctx.takeScreenshot("story_chapter_one_and_choices");
                    ctx.setScreen(() -> null);
                    for (int n = 2; n <= 6; n++) {
                        final int chapter = n;
                        // Chapter 2 opens after the first won Cleanse, chapter 4 after the third (README.md, the
                        // chapters).
                        if (n == 2 || n == 4) {
                            sp.getServer().runOnServer(s -> {
                                var p = s.getPlayerList().getPlayers().getFirst();
                                p.setAttached(
                                        ModData.HUNTS_CLEARED,
                                        chapter == 2 ? 1L : (long) Balance.STORY_ASH_LANTERN_CLEANSES);
                            });
                            ctx.waitTicks(40);
                        }
                        if (n == 3)
                            sp.getServer().runOnServer(s -> {
                                var p = s.getPlayerList().getPlayers().getFirst();
                                // A player inside their ward cannot hurt monsters (WardCombat), so the kill
                                // happens outside it; the loop teleports home afterwards.
                                var spot = p.position();
                                p.setPos(spot.add(WARD_CLEAR, 0, 0));
                                var giant = new GiantZombie(ModEntities.GIANT_ZOMBIE, p.level());
                                giant.setPos(p.position().add(3, 0, 0));
                                Monsters.setRank(giant, MonsterInfo.NORMAL);
                                p.level().addFreshEntity(giant);
                                giant.hurtServer(p.level(), p.damageSources().playerAttack(p), 100000);
                                p.setPos(spot);
                                require(
                                        !giant.isAlive() && DragonStory.hasFlag(p, 1),
                                        "Giant kill did not unlock chapter 3: alive=" + giant.isAlive() + " health="
                                                + giant.getHealth() + " flag=" + DragonStory.hasFlag(p, 1));
                            });
                        if (n == 5)
                            sp.getServer().runOnServer(s -> {
                                var p = s.getPlayerList().getPlayers().getFirst();
                                var spot = p.position();
                                var boss = Bosses.spawn(
                                        p.level(), p.blockPosition().east(4), Bosses.Encounter.normal(BossKind.GORATH));
                                require(boss != null, "Story boss did not spawn");
                                p.setPos(spot.add(WARD_CLEAR, 0, 0));
                                boss.hurtServer(p.level(), p.damageSources().playerAttack(p), 100000);
                                p.setPos(spot);
                                require(
                                        !boss.isAlive() && DragonStory.hasFlag(p, 2),
                                        "Boss kill did not unlock chapter 5: alive=" + boss.isAlive());
                            });
                        if (n == 6) {
                            teleport(sp, Level.NETHER, new BlockPos(0, 120, 0));
                            ctx.waitTicks(50);
                        }
                        teleport(sp, Level.OVERWORLD, home);
                        night(sp, n);
                        ctx.waitTicks(70);
                        open(ctx, sp, chest);
                        ctx.takeScreenshot("story_chapter_" + n);
                        ctx.setScreen(() -> null);
                        sp.getServer()
                                .runOnServer(s -> require(
                                        DragonStory.chapters(s.getPlayerList()
                                                        .getPlayers()
                                                        .getFirst())
                                                == chapter,
                                        "Chapter " + chapter + " failed to unlock through normal progress"));
                    }
                    ctx.getInput().pressKey(InputConstants.KEY_M);
                    ctx.waitTicks(40);
                    ctx.runOnClient(mc -> {
                        require(
                                mc.gui.screen() != null
                                        && mc.gui.screen().getClass().getName().contains("xaero"),
                                "M did not open Xaero's world map");
                        try {
                            var session = Class.forName("xaero.map.WorldMapSession")
                                    .getMethod("getCurrentSession")
                                    .invoke(null);
                            var processor = session.getClass()
                                    .getMethod("getMapProcessor")
                                    .invoke(session);
                            var world = processor
                                    .getClass()
                                    .getMethod("getMapWorld")
                                    .invoke(processor);
                            var dim = world.getClass()
                                    .getMethod("getCurrentDimension")
                                    .invoke(world);
                            require(
                                    !(boolean) dim.getClass()
                                            .getMethod("isUsingWorldSave")
                                            .invoke(dim),
                                    "Map reads unexplored world-save terrain");
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    ctx.takeScreenshot("story_map_explored_terrain_only");
                    ctx.setScreen(() -> null);
                    // The real fortress starts Orvane; the Scale supplies the destination rather than a test
                    // coordinate.
                    teleport(sp, Level.NETHER, new BlockPos(0, 120, 0));
                    ctx.waitTicks(40);
                    sp.getServer()
                            .runOnServer(s -> DragonStory.locate(
                                    s.getPlayerList().getPlayers().getFirst()));
                    BlockPos fortress = site(sp, "fortresses", Level.NETHER);
                    teleport(sp, Level.NETHER, fortress);
                    ctx.waitTicks(60);
                    sp.getServer().runOnServer(s -> {
                        var p = s.getPlayerList().getPlayers().getFirst();
                        require(
                                !p.level()
                                        .getEntitiesOfClass(
                                                LastScribe.class,
                                                p.getBoundingBox().inflate(15))
                                        .isEmpty(),
                                "Entering the fortress did not summon Orvane");
                        var boss = p.level()
                                .getEntitiesOfClass(
                                        LastScribe.class, p.getBoundingBox().inflate(15))
                                .getFirst();
                        Spells.withSpell(Spell.FROST_NOVA, () -> Spells.hit(p, boss, 5));
                    });
                    var scribe = sp.getServer().computeOnServer(s -> {
                        var p = s.getPlayerList().getPlayers().getFirst();
                        return p.level()
                                .getEntitiesOfClass(
                                        LastScribe.class, p.getBoundingBox().inflate(16))
                                .getFirst()
                                .blockPosition()
                                .above();
                    });
                    ctx.getInput().lookAt(scribe);
                    ctx.waitTicks(15);
                    ctx.takeScreenshot("story_orvane_ink_robes");
                    ctx.waitTicks(150);
                    ctx.takeScreenshot("story_orvane_copy_windup");
                    ctx.waitTicks(90);
                    sp.getServer().runOnServer(s -> {
                        var p = s.getPlayerList().getPlayers().getFirst();
                        var boss = p.level()
                                .getEntitiesOfClass(
                                        LastScribe.class, p.getBoundingBox().inflate(20))
                                .getFirst();
                        boss.damageCooldownTime = 0;
                        boss.hurtServer(p.level(), p.damageSources().playerAttack(p), 100000);
                        require(
                                DragonStory.hasFlag(p, 16)
                                        && LightGear.has(p, DragonStory.LIGHT_KEY)
                                        && LightGear.has(p, DragonStory.SCRIBE_SEAL),
                                "Orvane did not leave the key, trophy and progress");
                    });
                    BlockPos stronghold = site(sp, "strongholds", Level.OVERWORLD);
                    teleport(sp, Level.OVERWORLD, stronghold);
                    ctx.waitTicks(40);
                    teleport(sp, Level.OVERWORLD, home);
                    night(sp, 7);
                    ctx.waitTicks(60);
                    open(ctx, sp, chest);
                    ctx.setScreen(() -> null);
                    night(sp, 8);
                    ctx.waitTicks(40);
                    open(ctx, sp, chest);
                    ctx.takeScreenshot("story_chapter_eight_awakened_set");
                    ctx.setScreen(() -> null);
                    sp.getServer().runOnServer(s -> {
                        var p = s.getPlayerList().getPlayers().getFirst();
                        p.setItemSlot(EquipmentSlot.HEAD, LightGear.gift(0, 3));
                        p.setItemSlot(EquipmentSlot.CHEST, LightGear.gift(2, 3));
                        p.setItemSlot(EquipmentSlot.LEGS, LightGear.gift(3, 3));
                        p.setItemSlot(EquipmentSlot.FEET, LightGear.gift(4, 3));
                        p.setItemInHand(InteractionHand.MAIN_HAND, LightGear.gift(1, 3));
                        p.setItemInHand(InteractionHand.OFF_HAND, LightGear.gift(5, 3));
                        p.setGameMode(GameType.SURVIVAL);
                        p.setHealth(p.getMaxHealth());
                    });
                    ctx.waitTicks(20);
                    ctx.runOnClient(mc -> {
                        mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                    });
                    ctx.waitTicks(12);
                    ctx.takeScreenshot("story_light_armour_and_claw_staff");
                    ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                    ctx.runOnClient(mc -> {
                        var s = new CollectionScreen(chest);
                        mc.gui.setScreen(s);
                        s.select(-3);
                    });
                    ctx.waitTicks(10);
                    ctx.takeScreenshot("chest_cleanse_tab");
                    ctx.setScreen(() -> null);
                    sp.getServer()
                            .runOnServer(s ->
                                    s.getPlayerList().getPlayers().getFirst().setGameMode(GameType.CREATIVE));
                } else
                    sp.getServer().runOnServer(s -> {
                        var p = s.getPlayerList().getPlayers().getFirst();
                        p.setAttached(DragonStory.CHAPTERS, 8);
                        p.setAttached(DragonStory.FLAGS, 127);
                        LightGear.give(p, new ItemStack(DragonStory.LIGHT_KEY));
                        LightGear.give(p, new ItemStack(DragonStory.SCALE));
                    });
                night(sp, 9);
                sp.getServer()
                        .runOnServer(s -> DragonStory.locate(
                                s.getPlayerList().getPlayers().getFirst()));
                BlockPos city = site(sp, "cities", Level.OVERWORLD);
                teleport(sp, Level.OVERWORLD, city);
                ctx.waitTicks(120);
                BlockPos gate = sp.getServer().computeOnServer(s -> {
                    var l = s.overworld();
                    var p = s.getPlayerList().getPlayers().getFirst();
                    for (var at : BlockPos.betweenClosed(city.offset(-90, -24, -90), city.offset(90, 30, 90)))
                        if (l.hasChunkAt(at) && l.getBlockState(at).is(Blocks.REINFORCED_DEEPSLATE)) {
                            p.teleportTo(l, at.getX() + .5, at.getY(), at.getZ() + 2.5, Set.of(), 180, 0, false);
                            DragonStory.rememberGate(p, at.immutable());
                            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(DragonStory.LIGHT_KEY));
                            return at.immutable();
                        }
                    throw new AssertionError("No reinforced-deepslate prison gate in the ancient city");
                });
                ctx.waitTicks(40);
                sp.getServer().runOnServer(s -> {
                    var p = s.getPlayerList().getPlayers().getFirst();
                    require(DragonStory.sleepingWarden(p.level(), gate), "Prison city Warden protection missing");
                    var w = EntityTypes.WARDEN.create(p.level(), EntitySpawnReason.TRIGGERED);
                    w.setPos(p.position());
                    p.level().addFreshEntity(w);
                    require(w.isRemoved(), "A Warden emerged inside the protected city");
                });
                ctx.getInput().lookAt(gate);
                ctx.waitTicks(4);
                ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
                ctx.waitTicks(25);
                ctx.takeScreenshot("story_prison_ninth_chapter_" + fight);
                ctx.takeScreenshot("story_prison_final_choice_" + fight);
                if (!fight) {
                    click(ctx, "Free him. Keep the night.");
                    ctx.takeScreenshot("story_dragon_friend_ending");
                    ctx.setScreen(() -> null);
                    teleport(sp, Level.OVERWORLD, home);
                    sp.getServer().runCommand("time set 246000");
                    ctx.waitTicks(70);
                    sp.getServer()
                            .runOnServer(s -> require(
                                    DragonStory.ending(s.overworld()) == 1
                                            && LightDragons.get(s.overworld(), chest) != null,
                                    "Freed Dragon did not remain by day"));
                    night(sp, 11);
                    ctx.waitTicks(40);
                    ctx.getInput().pressKey(InputConstants.KEY_R);
                    ctx.waitTicks(12);
                    ctx.takeScreenshot("story_friend_answers_night_call");
                    sp.getServer().runOnServer(s -> {
                        var p = s.getPlayerList().getPlayers().getFirst();
                        require(
                                !p.level()
                                        .getEntitiesOfClass(
                                                LightDragon.class,
                                                p.getBoundingBox().inflate(10),
                                                d -> d.role() == 2)
                                        .isEmpty(),
                                "R did not call the Dragon friend");
                    });
                } else {
                    click(ctx, "Fight him for his power.");
                    ctx.setScreen(() -> null);
                    ctx.waitTicks(50);
                    var dragon = sp.getServer().computeOnServer(s -> {
                        var p = s.getPlayerList().getPlayers().getFirst();
                        return p.level()
                                .getEntitiesOfClass(
                                        LightDragon.class, p.getBoundingBox().inflate(30), d -> d.role() == 1)
                                .getFirst()
                                .blockPosition()
                                .above(4);
                    });
                    ctx.getInput().lookAt(dragon);
                    ctx.waitTicks(10);
                    ctx.takeScreenshot("story_true_dragon_prison_arena");
                    ctx.waitTicks(100);
                    ctx.takeScreenshot("story_true_dragon_light_breath");
                    sp.getServer().runOnServer(s -> {
                        var p = s.getPlayerList().getPlayers().getFirst();
                        var d = p.level()
                                .getEntitiesOfClass(
                                        LightDragon.class, p.getBoundingBox().inflate(35), e -> e.role() == 1)
                                .getFirst();
                        d.hurtServer(p.level(), p.damageSources().playerAttack(p), 100000);
                        require(DragonStory.ending(p.level()) == 3, "Dragon fight did not complete its ending");
                        p.setGameMode(GameType.SURVIVAL);
                        p.setHealth(p.getMaxHealth());
                    });
                    teleport(sp, Level.OVERWORLD, home.above(7));
                    ctx.waitTicks(40);
                    ctx.getInput().pressKey(InputConstants.KEY_R);
                    ctx.waitTicks(20);
                    ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                    ctx.waitTicks(10);
                    ctx.takeScreenshot("story_dragon_form_flight");
                    ctx.getInput().pressKey(InputConstants.KEY_V);
                    ctx.waitTicks(8);
                    ctx.takeScreenshot("story_dragon_form_breath");
                    sp.getServer().runOnServer(s -> {
                        var p = s.getPlayerList().getPlayers().getFirst();
                        require(DragonForm.active(p) && p.getAbilities().flying, "Dragon Form did not grant flight");
                    });
                    ctx.waitTicks(610);
                    sp.getServer().runOnServer(s -> {
                        var p = s.getPlayerList().getPlayers().getFirst();
                        require(
                                !DragonForm.active(p) && !p.getAbilities().mayfly && !p.isInvisible(),
                                "Dragon Form did not restore the survival player after expiry");
                    });
                    ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                }
            }
        System.out.println(
                "LIGHTBORN STORY CLIENT PASS: normal worlds, M explored map, nine chapters, chapter gear, real fortress Scribe, stronghold, prison Warden protection, both endings, friend call and Dragon Form expiry");
    }

    static void night(TestSingleplayerContext sp, int day) {
        sp.getServer().runCommand("time set " + (day * 24000L + 13000));
    }

    static void teleport(TestSingleplayerContext sp, ResourceKey<Level> dim, BlockPos at) {
        sp.getServer().runOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            p.teleportTo(s.getLevel(dim), at.getX() + .5, at.getY(), at.getZ() + .5, Set.of(), 180, 0, false);
        });
    }

    static void open(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos chest) {
        sp.getServer().runOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            var d = LightDragons.get(s.overworld(), chest);
            require(d != null, "Missing chest Dragon");
            Vec3 found = null;
            search:
            for (double r : new double[] {1.5, 2, 1, 2.5, 3})
                for (int y : new int[] {0, -1, 1, -2, 2})
                    for (int i = 0; i < 16; i++) {
                        double a = i * Math.PI / 8;
                        var at = d.position().add(Math.cos(a) * r, y, Math.sin(a) * r);
                        var box = new AABB(at.x - .3, at.y, at.z - .3, at.x + .3, at.y + 1.8, at.z + .3);
                        var ray = s.overworld()
                                .clip(new ClipContext(
                                        at.add(0, 1.62, 0),
                                        d.position().add(0, 1.2, 0),
                                        ClipContext.Block.COLLIDER,
                                        ClipContext.Fluid.NONE,
                                        p));
                        if (s.overworld().noCollision(p, box) && ray.getType() == HitResult.Type.MISS) {
                            found = at;
                            break search;
                        }
                    }
            require(found != null, "No clear approach to the hut Dragon at " + d.position() + " chest=" + chest);
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            p.teleportTo(s.overworld(), found.x, found.y, found.z, Set.of(), 180, 0, false);
        });
        ctx.waitTicks(35);
        var aim = sp.getServer()
                .computeOnServer(s ->
                        LightDragons.get(s.overworld(), chest).blockPosition().above());
        ctx.getInput().lookAt(aim);
        ctx.waitTicks(4);
        ctx.takeScreenshot("story_dialogue_approach");
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
        ctx.waitTicks(10);
    }

    static void click(ClientGameTestContext ctx, String label) {
        var xy = ctx.computeOnClient(mc -> {
            require(mc.gui.screen() != null, "No dialogue for: " + label);
            var b = mc.gui.screen().children().stream()
                    .filter(e -> e instanceof Button btn
                            && btn.visible
                            && btn.getMessage().getString().equals(label))
                    .map(e -> (Button) e)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing choice: " + label));
            return new double[] {
                (b.getX() + b.getWidth() / 2d) * mc.getWindow().getGuiScale(),
                (b.getY() + 8) * mc.getWindow().getGuiScale()
            };
        });
        ctx.getInput().setCursorPos(xy[0], xy[1]);
        ctx.waitTicks(3);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ctx.waitTicks(8);
    }

    static BlockPos site(TestSingleplayerContext sp, String tag, ResourceKey<Level> dimension) {
        return sp.getServer().computeOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            var l = s.getLevel(dimension);
            var near = l.findNearestMapStructure(DragonStory.tag(tag), p.blockPosition(), 256, false);
            require(near != null, "No " + tag + " found");
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                    var chunk = l.getChunk((near.getX() >> 4) + x, (near.getZ() >> 4) + z);
                    for (var entry : chunk.getAllStarts().entrySet())
                        if (entry.getValue().isValid()
                                && l.registryAccess()
                                        .lookupOrThrow(Registries.STRUCTURE)
                                        .getOrThrow(DragonStory.tag(tag))
                                        .contains(l.registryAccess()
                                                .lookupOrThrow(Registries.STRUCTURE)
                                                .wrapAsHolder(entry.getKey()))) {
                            var box = entry.getValue().getPieces().getFirst().getBoundingBox();
                            var middle = box.getCenter();
                            var at = new BlockPos(middle.getX(), middle.getY(), middle.getZ());
                            if (!tag.equals("cities"))
                                for (var b : BlockPos.betweenClosed(at.offset(-3, 0, -3), at.offset(3, 3, 3)))
                                    l.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState());
                            return at;
                        }
                }
            throw new AssertionError("No generated " + tag + " structure start at target " + near);
        });
    }
}
