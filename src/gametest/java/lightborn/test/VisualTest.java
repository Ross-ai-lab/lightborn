package lightborn.test;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lightborn.Bosses;
import lightborn.Collection;
import lightborn.DragonStory;
import lightborn.LootBeams;
import lightborn.LootRoller;
import lightborn.ModData;
import lightborn.ModEntities;
import lightborn.ModItems;
import lightborn.Progression;
import lightborn.SpawnHut;
import lightborn.Spell;
import lightborn.SpellUpgrades;
import lightborn.Spells;
import lightborn.client.CollectionScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The screenshot tour, in named scenes (README.md, Testing rules). {@code tools/build.ps1 -Shots 'cage,flight'} runs only
 * those scenes (through {@code LIGHTBORN_SHOTS}), and a world none of the chosen scenes needs is never made. Every field
 * scene starts from the same place, loadout, time and light ({@link #fresh}), so any scene runs alone exactly as it
 * runs in the whole tour. Screenshots land in the run folder's screenshots directory.
 */
public class VisualTest implements FabricClientGameTest {
    /** Scenes in the generated world with the spawn hut, in order. */
    static final List<String> HUT =
            List.of("hut", "chest", "chest_gui4", "chest_hands", "hut_outside", "loot_beams", "showcase", "title");
    /** Scenes in the open field beside the hut, in order. */
    static final List<String> FIELD = List.of(
            "zombies",
            "monster_kinds",
            "giants",
            "cage",
            "taunt",
            "flight",
            "refugees",
            "hud",
            "upgrades",
            "light_gear",
            "light_arms",
            "tooltips",
            "meteor",
            "ore_sight",
            "water_splash",
            "earthshatter",
            "gravity_well",
            "frost_nova",
            "moon",
            "spirit_wolves",
            "boss",
            "encounter",
            "spellstone",
            "nest",
            "nether_nest",
            "dark",
            "hollow",
            "third_shelf",
            "fourth_shelf",
            "fifth_shelf",
            "sixth_shelf",
            "seventh_shelf",
            "eighth_shelf",
            "frost_butterflies",
            "steed",
            "journal",
            "guide");

    private static final Set<String> WANTED = Arrays.stream(
                    System.getenv().getOrDefault("LIGHTBORN_SHOTS", "").split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());

    /** Where every field scene starts, and the loadout it starts with (set once, on the first field scene). */
    private static Vec3 field;

    private static ItemStack staff = ItemStack.EMPTY, fireball = ItemStack.EMPTY;

    private static boolean wanted(String scene) {
        return WANTED.isEmpty() || WANTED.contains(scene);
    }

    private static boolean anyOf(List<String> scenes) {
        return scenes.stream().anyMatch(VisualTest::wanted);
    }

    /** True when {@code name} should run now; names it for the heartbeat and the log. */
    private static boolean scene(String name) {
        if (!wanted(name)) return false;
        Soft.step(name);
        return true;
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        var known = Stream.concat(HUT.stream(), FIELD.stream()).toList();
        for (String name : WANTED)
            Soft.require(known.contains(name), "no VisualTest scene named " + name + "; the scenes are " + known);
        ctx.getInput().resizeWindow(1600, 900);
        if (anyOf(HUT)) hutWorld(ctx);
        if (scene("title")) {
            ctx.waitTicks(20);
            ctx.takeScreenshot("lightborn_title");
        }
        if (anyOf(FIELD)) fieldWorld(ctx);
    }

    /** A real generated world: the spawn hut, its chest, loot beams, and the title-screen panorama capture. */
    private void hutWorld(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(s -> {
                    s.setWorldType(s.getNormalPresetList().get(0));
                    s.setSeed("lightborn");
                    s.setDifficulty(Difficulty.PEACEFUL);
                })
                .create()) {
            sp.getServer().runCommand("time set 1000");
            sp.getServer().runOnServer(server -> {
                var rules = server.overworld().getGameRules();
                rules.set(GameRules.ADVANCE_TIME, false, server);
                rules.set(GameRules.ADVANCE_WEATHER, false, server);
            });
            sp.getServer().runCommand("weather clear");
            ctx.waitTicks(80);
            sp.getServer().runCommand("title @a clear"); // the new-world welcome title would cover the first scene
            BlockPos chestAt = sp.getServer()
                    .computeOnServer(server -> SpawnHut.lightChestAt(
                            server.overworld().getRespawnData().pos()));

            if (scene("hut")) {
                sp.getServer().runCommand("time set 1000");
                ctx.takeScreenshot("lightborn_hut_inside");
                // The chest's light ends at its 9-block ward (README: nothing outside the ward is lit; the ward glow
                // lights the inside on the client): 12 blocks out the chest gives no block light on any side.
                int outside = sp.getServer().computeOnServer(server -> {
                    ServerLevel level = server.overworld();
                    int best = 0;
                    for (int[] d :
                            new int[][] {{12, 0}, {-12, 0}, {0, 12}, {0, -12}, {9, 9}, {9, -9}, {-9, 9}, {-9, -9}}) {
                        for (int h = 0; h <= 2; h++)
                            best = Math.max(best, level.getBrightness(LightLayer.BLOCK, chestAt.offset(d[0], h, d[1])));
                    }
                    return best;
                });
                Soft.require(
                        outside == 0,
                        "Chest of Light light leaks past its ward: block light 12 blocks out is " + outside);
            }

            if (scene("chest")) {
                // The Chest of Light by the door, then its book with a few gifts given, some kept, and one opened.
                sp.getServer().runCommand("time set 1000");
                sp.getServer().runOnServer(server -> {
                    look(server, -1, 1, -0.5, 2, 1.2, 1);
                });
                ctx.waitTicks(10);
                ctx.getInput().lookAt(chestAt); // aim at the chest itself, wherever the hut put it
                ctx.waitTicks(20);
                ctx.takeScreenshot("lightborn_light_chest");
                sp.getServer().runOnServer(server -> {
                    Collection.ensure(player(server));
                    var sets = Collection.categories();
                    List<String> given =
                            new ArrayList<>(sets.get(sets.size() - 1).items());
                    given.addAll(sets.get(2).items().subList(0, 40));
                    player(server).setAttached(ModData.COLLECTION, given);
                    List<ItemStack> kept = new ArrayList<>();
                    for (int i = 0; i < 12; i += 3)
                        kept.add(new ItemStack(Collection.item(given.get(given.size() - 40 + i)), 1 + i * 5));
                    player(server).setAttached(ModData.STORAGE, kept);
                });
                ctx.waitTicks(5);
                ctx.setScreen(() -> {
                    var screen = new CollectionScreen(chestAt);
                    screen.select(2);
                    return screen;
                });
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_collection");
                // One opened: the panel and how it is made (the recipe comes from the server).
                ctx.runOnClient(mc -> {
                    if (mc.gui.screen() instanceof CollectionScreen screen) screen.pick("minecraft:chest");
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("lightborn_collection_recipe");
                ctx.runOnClient(mc -> {
                    if (mc.gui.screen() instanceof CollectionScreen screen)
                        screen.pick(Collection.categories().get(2).items().get(0));
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("lightborn_collection_kept");
                ctx.setScreen(() -> null);
            }

            if (scene("chest_gui4")) {
                // The owner's screen (1920 x 1080 at GUI scale 4): no button on any tab may cross the chest's frame.
                sp.getServer().runOnServer(server -> {
                    look(server, -1, 1, -0.5, 2, 1.2, 1);
                    player(server).setGameMode(GameType.SURVIVAL); // a spectator gets no shop snapshot
                });
                ctx.getInput().resizeWindow(1920, 1080);
                ctx.runOnClient(mc -> {
                    mc.options.guiScale().set(4);
                    mc.resizeGui();
                });
                List<String> overflow = new ArrayList<>(), cut = new ArrayList<>();
                for (int tab : new int[] {-5, -7, -3, -2, -4, -6, 2, 3}) {
                    ctx.setScreen(() -> {
                        var screen = new CollectionScreen(chestAt);
                        screen.select(tab);
                        if (tab == -6)
                            screen.pickSpirit(lightborn.Souls.PAGES.getFirst().id());
                        if (tab == 2) screen.pick("minecraft:chest");
                        // An item no hand makes: the panel says where it is found (owner, 2026-11-18).
                        if (tab == 3) screen.pick("minecraft:snowball"); // the owner's long example
                        return screen;
                    });
                    ctx.waitTicks(tab == -2 ? 30 : 5); // the shop waits on the server's snapshot
                    if (tab == -6) ctx.takeScreenshot("lightborn_spirits_gui4");
                    if (tab == -2) ctx.takeScreenshot("lightborn_shop_gui4");
                    if (tab == 2) ctx.takeScreenshot("lightborn_collection_item_gui4");
                    if (tab == 3) ctx.takeScreenshot("lightborn_collection_found_gui4");
                    overflow.addAll(
                            ctx.computeOnClient(mc -> ((CollectionScreen) mc.gui.screen()).outOfFrame()).stream()
                                    .map(s -> "tab " + tab + ": " + s)
                                    .toList());
                    cut.addAll(ctx.computeOnClient(mc -> ((CollectionScreen) mc.gui.screen()).cutLabels()).stream()
                            .map(s -> "tab " + tab + ": " + s)
                            .toList());
                }
                ctx.setScreen(() -> null);
                ctx.runOnClient(mc -> {
                    mc.options.guiScale().set(0);
                    mc.resizeGui();
                });
                ctx.getInput().resizeWindow(1600, 900);
                ctx.waitTicks(3);
                Soft.require(overflow.isEmpty(), "Chest of Light buttons cross the frame: " + overflow);
                Soft.require(cut.isEmpty(), "Chest of Light button labels run past their edges: " + cut);
            }

            if (scene("chest_hands")) {
                // The real hands on the real chest: Ctrl + right-click with redstone gives one, a plain right-click
                // opens the book.
                sp.getServer().runOnServer(server -> {
                    look(server, -1, 1, 0, 2, 1.4, 1);
                    player(server).setGameMode(GameType.SURVIVAL);
                    player(server).setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.REDSTONE, 5));
                    player(server).setAttached(ModData.STORAGE, List.of());
                });
                ctx.waitTicks(10);
                ctx.getInput().lookAt(chestAt);
                ctx.getInput().holdControl();
                ctx.waitTicks(2);
                ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
                ctx.waitTicks(2);
                ctx.getInput().releaseControl();
                ctx.waitTicks(10);
                String gave = sp.getServer()
                        .computeOnServer(server -> Collection.stored(player(server), "minecraft:redstone")
                                        .getCount()
                                + " stored, " + player(server).getMainHandItem().getCount() + " in hand");
                boolean opened = ctx.computeOnClient(mc -> mc.gui.screen() instanceof CollectionScreen);
                // The owner's own keys: sneak is on Left Ctrl, and vanilla skips a block's click while sneaking
                // with an item.
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(4);
                ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
                ctx.waitTicks(4);
                ctx.getInput().releaseKey(o -> o.keyShift);
                ctx.waitTicks(10);
                String sneakGave = sp.getServer()
                        .computeOnServer(server -> Collection.stored(player(server), "minecraft:redstone")
                                        .getCount()
                                + " stored, " + player(server).getMainHandItem().getCount() + " in hand");
                ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
                ctx.waitTicks(10);
                boolean book = ctx.computeOnClient(mc -> mc.gui.screen() instanceof CollectionScreen);
                ctx.setScreen(() -> null);
                Soft.require(
                        gave.equals("1 stored, 4 in hand")
                                && sneakGave.equals("2 stored, 3 in hand")
                                && !opened
                                && book,
                        "Chest of Light by hand: ctrl-click gave " + gave + ", sneak-click gave " + sneakGave
                                + ", ctrl-click opened book=" + opened + ", plain click opened book=" + book);
            }

            if (scene("hut_outside")) {
                // Outside, looking back at the door from across the yard.
                sp.getServer().runCommand("time set 1000");
                sp.getServer().runOnServer(server -> look(server, 3, 2.4, 10, 0, 2.5, 0));
                ctx.waitTicks(60);
                ctx.takeScreenshot("lightborn_hut_outside");
            }

            if (scene("loot_beams")) {
                // Loot beams at dusk, one of each rarity, in the yard.
                sp.getServer().runCommand("time set 13200");
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.overworld();
                    BlockPos o = level.getRespawnData().pos().below();
                    LootRoller.Rarity[] rs = LootRoller.Rarity.values();
                    for (int i = 0; i < rs.length; i++) {
                        ItemEntity item = new ItemEntity(
                                level,
                                o.getX() - 2 + i * 2 + 0.5,
                                o.getY() + 1.2,
                                o.getZ() + 6.5,
                                LootRoller.roll(level.getRandom(), ModItems.Tier.values()[i % 3], rs[i]));
                        item.setPickUpDelay(32767);
                        item.setDeltaMovement(0, 0, 0);
                        level.addFreshEntity(item);
                        LootBeams.onDrop(item, rs[i]);
                    }
                    look(server, 0, 3.5, 15, 0, 3, 6);
                });
                ctx.waitTicks(40);
                ctx.takeScreenshot("lightborn_loot_beams");
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.overworld();
                    for (ItemEntity e : level.getEntitiesOfClass(
                            ItemEntity.class, new AABB(level.getRespawnData().pos()).inflate(30))) e.discard();
                    LootBeams.clear();
                });
            }

            if (scene("showcase")) {
                // Title-screen panorama: a showcase hut in the nearest open, pretty biome at sunset.
                // (The real spawn here is deep forest: every face would be leaves.)
                BlockPos[] showcase = new BlockPos[1];
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.overworld();
                    BlockPos from = level.getRespawnData().pos();
                    var found = level.findClosestBiome3d(b -> b.is(Biomes.CHERRY_GROVE), from, 4000, 32, 64);
                    if (found == null) found = level.findClosestBiome3d(b -> b.is(Biomes.MEADOW), from, 4000, 32, 64);
                    if (found == null) found = level.findClosestBiome3d(b -> b.is(Biomes.PLAINS), from, 4000, 32, 64);
                    BlockPos target = found == null ? from.offset(300, 0, 0) : found.getFirst();
                    player(server).setGameMode(GameType.SPECTATOR);
                    player(server).teleportTo(level, target.getX(), 200, target.getZ(), Set.of(), 0f, 0f, false);
                    showcase[0] = target;
                });
                ctx.waitTicks(200); // let the far chunks generate around the player
                sp.getServer().runOnServer(server -> {
                    ServerLevel level = server.overworld();
                    BlockPos site = SpawnHut.findSite(level, showcase[0]);
                    SpawnHut.build(level, site, false);
                    showcase[0] = site;
                    look(server, site, 4, 2.6, 10, 0, 3.0, 0);
                });
                sp.getServer().runCommand("time set 12300");
                ctx.waitTicks(120);
                ctx.takeScreenshot("lightborn_showcase");
                ctx.runOnClient(mc -> {
                    mc.player.setXRot(0f);
                    mc.grabPanoramixScreenshot(mc.gameDirectory);
                });
                ctx.waitTicks(5);
            }
        }
    }

    /** Teleports the player to hut-relative (x,y,z) looking at hut-relative (tx,ty,tz), around the spawn hut. */
    private static void look(MinecraftServer server, double x, double y, double z, double tx, double ty, double tz) {
        look(server, server.overworld().getRespawnData().pos().below(), x, y, z, tx, ty, tz);
    }

    private static void look(
            MinecraftServer server, BlockPos o, double x, double y, double z, double tx, double ty, double tz) {
        ServerPlayer p = player(server);
        ServerLevel level = server.overworld();
        double px = o.getX() + 0.5 + x, py = o.getY() + y, pz = o.getZ() + 0.5 + z;
        double dx = (o.getX() + 0.5 + tx) - px, dy = (o.getY() + ty) - (py + 1.62), dz = (o.getZ() + 0.5 + tz) - pz;
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        p.setGameMode(GameType.SPECTATOR);
        p.teleportTo(level, px, py, pz, Set.of(), yaw, pitch, false);
    }

    /**
     * Puts the field back as every scene expects it: no line-up left, the player on the field spot facing +X in
     * survival with the staff and the Fireball tome, full-ish mana, on foot, first person, at {@code time}, with or
     * without night vision, and no title over the HUD.
     */
    private static void fresh(ClientGameTestContext ctx, TestSingleplayerContext sp, int time, boolean nightVision) {
        sp.getServer().runCommand("kill @e[tag=lightborn_lineup]");
        sp.getServer().runCommand("time set " + time);
        sp.getServer().runCommand("weather clear");
        sp.getServer()
                .runCommand(
                        nightVision ? "effect give @a night_vision infinite 0 true" : "effect clear @a night_vision");
        ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
        ctx.setScreen(() -> null);
        sp.getServer().runOnServer(server -> {
            ServerPlayer p = player(server);
            p.stopRiding();
            p.removeEffect(MobEffects.SLOW_FALLING);
            p.setGameMode(GameType.SURVIVAL);
            p.teleportTo(server.overworld(), field.x, field.y, field.z, Set.of(), -90f, 0f, false);
            p.getInventory().setSelectedSlot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND, staff.copy());
            p.setItemInHand(InteractionHand.OFF_HAND, fireball.copy());
            Progression.setMana(p, (float) (Progression.maxMana(p) * 0.8));
        });
        ctx.waitTicks(5);
        sp.getServer().runCommand("title @a clear");
        ctx.getInput().lookAt(-90f, -6f);
        ctx.waitTicks(2);
    }

    /** The open field beside the spawn hut: monsters, spells, the HUD, the chest's tabs, tooltips and a boss. */
    private void fieldWorld(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .adjustSettings(s -> s.setDifficulty(Difficulty.NORMAL))
                .create()) {
            sp.getServer().runCommand("effect give @a resistance infinite 255 true");
            sp.getServer().runOnServer(server -> {
                var rules = server.overworld().getGameRules();
                rules.set(GameRules.ADVANCE_TIME, false, server);
                rules.set(GameRules.ADVANCE_WEATHER, false, server);
                setUpPlayer(server);
            });
            ctx.waitTicks(40);

            if (scene("zombies")) {
                // The four kinds of zombie side by side at night (night vision on, so their skins read; the
                // darkness and vampire eyes glow): plain, darkness, vampire, giant. No name banner over any of them.
                fresh(ctx, sp, 18000, true);
                lineUp(
                        sp,
                        List.<EntityType<? extends Mob>>of(
                                EntityTypes.ZOMBIE,
                                ModEntities.DARKNESS_ZOMBIE,
                                ModEntities.VAMPIRE_ZOMBIE,
                                ModEntities.GIANT_ZOMBIE),
                        (i, p) -> new Vec3(p.getX() + 6, p.getY(), p.getZ() - 3.6 + i * 2.4));
                ctx.waitTicks(20);
                ctx.getInput().lookAt(-90f, -6f);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_zombies");
            }

            if (scene("monster_kinds")) {
                // The skeleton and spider kinds the same way: skeleton, ember, deadeye; spider, weaver, hunter.
                fresh(ctx, sp, 18000, true);
                lineUp(
                        sp,
                        List.<EntityType<? extends Mob>>of(
                                EntityTypes.SKELETON,
                                ModEntities.EMBER_SKELETON,
                                ModEntities.DEADEYE_SKELETON,
                                EntityTypes.SPIDER,
                                ModEntities.WEAVER_SPIDER,
                                ModEntities.HUNTER_SPIDER),
                        (i, p) -> new Vec3(p.getX() + (i < 3 ? 6 : 8.5), p.getY(), p.getZ() - 2.6 + (i % 3) * 2.6));
                ctx.waitTicks(20);
                sp.getServer().runCommand("title @a clear"); // nightfall's horde warning would cover the line-up
                ctx.waitTicks(2);
                ctx.takeScreenshot("lightborn_monster_kinds");
            }

            if (scene("giants")) {
                // The giants (owner, 2026-11-17): every vanilla night monster's giant, two rows.
                fresh(ctx, sp, 18000, true);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    ServerLevel level = p.level();
                    var kinds = lightborn.Giants.KINDS;
                    for (int i = 0; i < kinds.size(); i++) {
                        Mob m = kinds.get(i).type().get().create(level, EntitySpawnReason.COMMAND);
                        boolean back = i >= 6;
                        m.snapTo(
                                p.getX() + (back ? 17 : 10),
                                p.getY(),
                                p.getZ() - (back ? 8 : 9) + (back ? i - 6 : i) * 3.6,
                                90f,
                                0f);
                        m.setYHeadRot(90f);
                        m.setYBodyRot(90f);
                        m.setNoAi(true);
                        m.addTag("lightborn_lineup");
                        level.addFreshEntity(m);
                    }
                    // A Dark Phantom hanging over the front row (owner, 2026-11-17).
                    var phantom = lightborn.DarkPhantom.TYPE.create(level, EntitySpawnReason.COMMAND);
                    phantom.snapTo(p.getX() + 6, p.getY() + 2.5, p.getZ() + 1, 90f, 0f);
                    phantom.setPhantomSize(1);
                    phantom.setNoAi(true);
                    phantom.addTag("lightborn_lineup");
                    level.addFreshEntity(phantom);
                });
                ctx.waitTicks(20);
                sp.getServer().runCommand("title @a clear");
                ctx.getInput().lookAt(-90f, -12f);
                ctx.waitTicks(3);
                ctx.takeScreenshot("lightborn_giants");
            }

            if (scene("cage")) {
                // Cage (owner, 2026-11-18): a zombie five blocks ahead, shut in its bars.
                fresh(ctx, sp, 18000, true);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    // A block to stand on at the player's own height: the ground ahead may lie lower, behind a ledge.
                    BlockPos stand = BlockPos.containing(p.getX() + 5, p.getY() - 1, p.getZ());
                    p.level().setBlockAndUpdate(stand, Blocks.STONE.defaultBlockState());
                    var z = EntityTypes.ZOMBIE.create(p.level(), EntitySpawnReason.COMMAND);
                    z.snapTo(stand.getX() + .5, stand.getY() + 1, stand.getZ() + .5, 90f, 0f);
                    z.setYHeadRot(90f);
                    z.addTag("lightborn_lineup");
                    p.level().addFreshEntity(z);
                });
                // A fresh zombie may turn into another kind on its first tick, and it settles on the ground.
                ctx.waitTicks(10);
                // Aim at it wherever it stands.
                float[] aim = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    var to =
                            p
                                    .level()
                                    .getEntitiesOfClass(
                                            Mob.class, p.getBoundingBox().inflate(8), Mob::isAlive)
                                    .stream()
                                    .findFirst()
                                    .map(m -> m.position()
                                            .add(0, m.getBbHeight() * .5, 0)
                                            .subtract(p.getEyePosition()))
                                    .orElse(new Vec3(5, -1, 0));
                    return new float[] {
                        (float) Math.toDegrees(Math.atan2(-to.x, to.z)),
                        (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)))
                    };
                });
                ctx.getInput().lookAt(aim[0], aim[1]);
                ctx.waitTicks(4);
                String castNote = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    var near = new ArrayList<String>();
                    for (Mob m : p.level()
                            .getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(8), Mob::isAlive)) {
                        lightborn.Monsters.setRank(
                                m, lightborn.MonsterInfo.NORMAL); // never a boss, which no cage holds
                        m.addTag("lightborn_lineup");
                        near.add(BuiltInRegistries.ENTITY_TYPE
                                        .getKey(m.getType())
                                        .getPath() + "@"
                                + Math.round(m.getX() - p.getX()) + "," + Math.round(m.getY() - p.getY()) + ","
                                + Math.round(m.getZ() - p.getZ()) + (m.isNoAi() ? " noAI" : ""));
                    }
                    p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.TOMES.get(Spell.CAGE)));
                    Progression.setMana(p, (float) Progression.maxMana(p));
                    boolean cast = Spells.cast(p, p.getOffhandItem());
                    return "cast=" + cast + " near=" + near + " mode=" + p.gameMode.getGameModeForPlayer();
                });
                ctx.waitTicks(6);
                String cage = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    var caged = p.level()
                            .getEntitiesOfClass(
                                    Mob.class,
                                    p.getBoundingBox().inflate(12),
                                    m -> m.entityTags().contains("lightborn_caged"));
                    return caged.isEmpty()
                            ? "nothing caged; looking " + p.getYRot() + "/" + p.getXRot() + ", mana "
                                    + Progression.mana(p) + "; " + castNote
                            : "";
                });
                Soft.require(cage.isEmpty(), "Cage: " + cage);
                ctx.takeScreenshot("lightborn_cage");
            }

            if (scene("taunt")) {
                // Taunt (owner, 2026-11-18): the totem of light ahead, three zombies around it turned on it.
                fresh(ctx, sp, 18000, true);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    for (int i = 0; i < 3; i++) {
                        var z = EntityTypes.ZOMBIE.create(p.level(), EntitySpawnReason.COMMAND);
                        z.snapTo(p.getX() + 7 + (i % 2) * 2, p.getY(), p.getZ() - 3 + i * 3, 90f, 0f);
                        z.addTag("lightborn_lineup");
                        p.level().addFreshEntity(z);
                    }
                    p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.TOMES.get(Spell.TAUNT)));
                    Progression.setMana(p, (float) Progression.maxMana(p));
                    Spells.cast(p, p.getOffhandItem());
                });
                ctx.getInput().lookAt(-90f, 10f);
                ctx.waitTicks(30);
                String taunt = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    var totems = p.level()
                            .getEntitiesOfClass(
                                    lightborn.Taunt.Totem.class,
                                    p.getBoundingBox().inflate(10));
                    if (totems.isEmpty()) return "no totem";
                    long on = p.level()
                            .getEntitiesOfClass(
                                    Mob.class,
                                    p.getBoundingBox().inflate(14),
                                    m -> m.entityTags().contains("lightborn_lineup")
                                            && m.getTarget() == totems.getFirst())
                            .size();
                    return on == 0 ? "no monster turned on the totem" : "";
                });
                Soft.require(taunt.isEmpty(), "Taunt: " + taunt);
                ctx.takeScreenshot("lightborn_taunt");
                sp.getServer().runCommand("kill @e[type=lightborn:light_totem]");
            }

            if (scene("flight")) {
                // Flight (owner, 2026-11-18): the player astride the staff, seen from behind (a little above) and
                // from the front.
                fresh(ctx, sp, 18000, true);
                ctx.getInput().lookAt(-90f, 0f);
                ctx.waitTicks(2);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.TOMES.get(Spell.FLIGHT)));
                    Progression.setMana(p, (float) Progression.maxMana(p));
                    Spells.cast(p, p.getOffhandItem());
                });
                ctx.waitTicks(30);
                // A real player rides (the self-test's fake one cannot), and a second aloft costs mana.
                String ride = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    return p.getVehicle() instanceof lightborn.StaffFlight.Mount
                            ? (Progression.mana(p) < Progression.maxMana(p) ? "" : "no mana spent")
                            : "not riding: " + p.getVehicle();
                });
                Soft.require(ride.isEmpty(), "Flight: " + ride);
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                ctx.getInput().lookAt(-90f, 25f);
                ctx.waitTicks(4);
                ctx.takeScreenshot("lightborn_flight_back");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
                ctx.getInput().lookAt(-90f, 0f);
                ctx.waitTicks(4);
                ctx.takeScreenshot("lightborn_flight_front");
            }

            if (scene("refugees")) {
                // Three dawn refugees in different default skins, named (the name shows when looked at).
                fresh(ctx, sp, 18000, true);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    ServerLevel level = p.level();
                    for (int i = 0; i < 3; i++) {
                        var r = ModEntities.REFUGEE.create(level, EntitySpawnReason.COMMAND);
                        r.snapTo(p.getX() + 5, p.getY(), p.getZ() - 2.4 + i * 2.4, 90f, 0f);
                        r.setYHeadRot(90f);
                        r.setYBodyRot(90f);
                        r.setNoAi(true);
                        r.setSkin(i * 3 + 1);
                        r.setCustomName(net.minecraft.network.chat.Component.literal(lightborn.Refugee.NAMES[i * 5]));
                        r.addTag("lightborn_lineup");
                        level.addFreshEntity(r);
                    }
                });
                ctx.waitTicks(20);
                ctx.takeScreenshot("lightborn_refugees");
            }

            if (scene("hud")) {
                // Right-click the staff: casts the off-hand Fireball tome, and the cooldown sweeps its slot.
                fresh(ctx, sp, 6000, false);
                ctx.getInput().lookAt(-90f, 8f); // face +X, slightly down
                ctx.waitTicks(5);
                ctx.getInput().pressKey(o -> o.keyUse);
                ctx.waitTicks(6);
                ctx.takeScreenshot("lightborn_hud");
            }

            if (scene("upgrades")) {
                fresh(ctx, sp, 6000, true);
                BlockPos treeChest = toChest(ctx, sp);
                ctx.getInput().setCursorPos(4, 4); // over the panel: no node tooltip in the plain shot
                ctx.setScreen(() -> upgradeTab(treeChest));
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_upgrades");
                // The owner's large GUI scale: three tiers of three cards must still fit inside the chest's panel.
                ctx.runOnClient(mc -> {
                    mc.options.guiScale().set(4);
                    mc.resizeGui();
                });
                ctx.setScreen(() -> upgradeTab(treeChest));
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_upgrades_gui4");
                ctx.runOnClient(mc -> {
                    mc.options.guiScale().set(0);
                    mc.resizeGui();
                });
                ctx.setScreen(() -> upgradeTab(treeChest));
                ctx.waitTicks(3);
                double guiScale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
                int[] card = ctx.computeOnClient(mc -> ((CollectionScreen) mc.gui.screen()).upgradeCard(2, 0));
                ctx.getInput().setCursorPos(card[0] * guiScale, card[1] * guiScale);
                ctx.waitTicks(3);
                ctx.takeScreenshot("lightborn_upgrades_tooltip");
            }

            if (scene("light_gear")) {
                // The Upgrade Gear tab: three Light pieces (the staff with tiers I and II bought, old step 3) and
                // plain gear beside them, a bow, a pickaxe and a shield, each with its own tree.
                fresh(ctx, sp, 6000, true);
                BlockPos treeChest = toChest(ctx, sp);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.getInventory().setItem(27, lightborn.LightGear.gift(0, 1));
                    p.getInventory().setItem(28, lightborn.LightGear.gift(1, 2));
                    p.getInventory().setItem(29, lightborn.LightGear.gift(5, 3));
                    p.getInventory().setItem(30, new ItemStack(Items.BOW));
                    p.getInventory().setItem(31, new ItemStack(Items.IRON_PICKAXE));
                    p.getInventory().setItem(32, lightborn.LightGear.gift(lightborn.LightGear.SHIELD, 1));
                });
                ctx.setScreen(() -> {
                    var s = new CollectionScreen(treeChest);
                    s.select(-7);
                    return s;
                });
                ctx.waitTicks(25);
                ctx.takeScreenshot("lightborn_light_gear_tab");
                ctx.setScreen(() -> null);
                sp.getServer().runOnServer(server -> {
                    for (int slot = 27; slot <= 32; slot++)
                        player(server).getInventory().setItem(slot, ItemStack.EMPTY);
                });
            }

            if (scene("light_arms")) {
                // The Light Sword and Shield in hand at night, without night vision so the glow reads (owner,
                // 2026-11-17): first person, then from the front.
                fresh(ctx, sp, 18000, false);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.setItemInHand(InteractionHand.MAIN_HAND, lightborn.LightGear.gift(1, 1));
                    p.setItemInHand(InteractionHand.OFF_HAND, lightborn.LightGear.gift(lightborn.LightGear.SHIELD, 1));
                });
                ctx.waitTicks(40);
                ctx.takeScreenshot("lightborn_light_arms");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
                ctx.waitTicks(30);
                ctx.takeScreenshot("lightborn_light_arms_front");
            }

            if (scene("tooltips")) {
                fresh(ctx, sp, 6000, true);
                ctx.setScreen(() -> new InventoryScreen(ctx.computeOnClient(mc -> mc.player)));
                ctx.waitTicks(5);
                // Hover the first hotbar slot (rolled staff) for its tooltip. Inventory GUI is 176x166, centred.
                double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
                int gw = ctx.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
                int gh = ctx.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
                double sx = (gw - 176) / 2.0 + 8 + 8, sy = (gh - 166) / 2.0 + 142 + 8;
                ctx.getInput().setCursorPos(sx * scale, sy * scale);
                ctx.waitTicks(3);
                ctx.takeScreenshot("lightborn_tooltip");
                // The Water Splash tome (hotbar slot 7): its long lines wrap. The guide book (first inventory slot):
                // author line.
                ctx.getInput().setCursorPos((sx + 7 * 18) * scale, sy * scale);
                ctx.waitTicks(3);
                ctx.takeScreenshot("lightborn_tome_tooltip");
                ctx.getInput().setCursorPos(sx * scale, ((gh - 166) / 2.0 + 84 + 8) * scale);
                ctx.waitTicks(3);
                ctx.takeScreenshot("lightborn_book_tooltip");
                // The Light Sword names the ore it is as strong as (diamond mined); broken Light boots say so (owner,
                // 2026-11-20). Hotbar slots 2 and 3.
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.setAttached(lightborn.OreTiers.MINED, 1 | 2);
                    p.getInventory().setItem(1, lightborn.LightGear.gift(1, 1));
                    var boots = lightborn.LightGear.gift(4, 1);
                    boots.setDamageValue(boots.getMaxDamage());
                    p.getInventory().setItem(2, boots);
                });
                ctx.waitTicks(30);
                ctx.getInput().setCursorPos((sx + 18) * scale, sy * scale);
                ctx.waitTicks(3);
                ctx.takeScreenshot("lightborn_light_tooltip");
                ctx.getInput().setCursorPos((sx + 36) * scale, sy * scale);
                ctx.waitTicks(3);
                ctx.takeScreenshot("lightborn_light_broken_tooltip");
                sp.getServer().runOnServer(server -> player(server).removeAttached(lightborn.OreTiers.MINED));
                ctx.setScreen(() -> null);
            }

            if (scene("meteor")) {
                // Meteor at the ground ahead: one shot mid-fall (rock, thick warning ring), one as it lands.
                fresh(ctx, sp, 6000, true);
                ctx.getInput().lookAt(-90f, 12f);
                ctx.waitTicks(4);
                castGreater(sp, Spell.METEOR);
                ctx.waitTicks(12);
                ctx.takeScreenshot("lightborn_meteor_fall");
                ctx.waitTicks(10);
                ctx.takeScreenshot("lightborn_meteor_impact");
                ctx.waitTicks(30);
            }

            if (scene("ore_sight")) {
                // Ore Sight: a few ores buried in the ground ahead glow through it in their own colours.
                fresh(ctx, sp, 6000, true);
                ctx.getInput().lookAt(-90f, 35f);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    var lvl = p.level();
                    BlockPos at = p.blockPosition();
                    var ores = List.of(
                            Blocks.DIAMOND_ORE,
                            Blocks.GOLD_ORE,
                            Blocks.IRON_ORE,
                            Blocks.REDSTONE_ORE,
                            Blocks.EMERALD_ORE,
                            Blocks.COAL_ORE);
                    for (int i = 0; i < ores.size(); i++)
                        lvl.setBlockAndUpdate(
                                at.offset(3 + i, -2 - i % 2, i % 3 - 1),
                                ores.get(i).defaultBlockState());
                });
                castGreater(sp, Spell.ORE_SIGHT);
                ctx.waitTicks(10);
                ctx.takeScreenshot("lightborn_ore_sight");
            }

            // The second shelf: a wave mid-run, the fissure's spikes, and a gravity well dragging zombies in.
            if (scene("water_splash")) {
                fresh(ctx, sp, 6000, true);
                ctx.getInput().lookAt(-90f, 15f);
                ctx.waitTicks(4);
                castGreater(sp, Spell.WATER_SPLASH);
                ctx.waitTicks(4);
                ctx.takeScreenshot("lightborn_water_splash");
            }

            if (scene("earthshatter")) {
                fresh(ctx, sp, 6000, true);
                ctx.getInput().lookAt(-90f, 20f);
                ctx.waitTicks(4);
                castGreater(sp, Spell.EARTHSHATTER);
                ctx.getInput().lookAt(-45f, 20f); // half-turned, so the line of spears runs across the shot
                ctx.waitTicks(16);
                ctx.takeScreenshot("lightborn_earthshatter");
                ctx.waitTicks(30);
            }

            if (scene("moon")) {
                // The chained moon, then the Dragon-friend ending (owner, 2026-11-19): the chains go and the night is
                // lit as in an ordinary game. Midnight, looking straight up at the moon.
                fresh(ctx, sp, 18000, false);
                ctx.getInput().lookAt(-90f, -89.9f);
                ctx.waitTicks(20);
                ctx.takeScreenshot("lightborn_moon_chained");
                int old = sp.getServer().computeOnServer(server -> {
                    var root = server.overworld();
                    int was = root.getAttachedOrElse(lightborn.DragonStory.ENDING, 0);
                    root.setAttached(lightborn.DragonStory.ENDING, 1);
                    return was;
                });
                ctx.waitTicks(20);
                boolean lifted = ctx.computeOnClient(mc -> lightborn.DragonStory.nightLifted(mc.level));
                ctx.takeScreenshot("lightborn_moon_freed");
                ctx.getInput().lookAt(-90f, 10f);
                ctx.waitTicks(10);
                ctx.takeScreenshot("lightborn_moon_freed_night");
                sp.getServer().runOnServer(server -> server.overworld().setAttached(lightborn.DragonStory.ENDING, old));
                ctx.waitTicks(5);
                Soft.require(lifted, "the client sees the Dragon-friend ending");
            }

            if (scene("frost_nova")) {
                // Frost Nova (owner, 2026-11-18): what it hits flies back as a block of ice and, frozen, cannot strike
                // for 3 seconds; then the ice shatters. Three husks start right beside the player.
                fresh(ctx, sp, 6000, true);
                // Every scene's player wears Resistance 255; this one must be hurtable, or "none can strike" proves
                // nothing.
                sp.getServer().runCommand("effect clear @a resistance");
                ctx.getInput().lookAt(-90f, 15f);
                ctx.waitTicks(4);
                float hp = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.setHealth(p.getMaxHealth());
                    for (int i = 0; i < 3; i++) {
                        Mob h = EntityTypes.HUSK.create(p.level(), EntitySpawnReason.COMMAND);
                        h.snapTo(p.getX() + 2, p.getY(), p.getZ() - 1.5 + i * 1.5, 90f, 0f);
                        h.addTag("lightborn_lineup");
                        h.setTarget(p);
                        p.level().addFreshEntity(h);
                    }
                    ItemStack before = p.getOffhandItem().copy();
                    p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.TOMES.get(Spell.FROST_NOVA)));
                    Progression.setMana(p, (float) Progression.maxMana(p));
                    lightborn.Spells.cast(p, p.getOffhandItem());
                    p.setItemInHand(InteractionHand.OFF_HAND, before);
                    return p.getHealth();
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("lightborn_frost_nova_ice");
                // Where the game draws them, each ice block sits on its husk: it rides it, so they move as one even
                // while the shove carries the husk (a block that only followed it ran a block and more ahead).
                boolean together = ctx.computeOnClient(mc -> {
                    var husks = new java.util.ArrayList<net.minecraft.world.entity.Entity>();
                    var ice = new java.util.ArrayList<net.minecraft.world.entity.Entity>();
                    for (var e : mc.level.entitiesForRendering())
                        if (e.getType() == EntityTypes.HUSK) husks.add(e);
                        else if (e instanceof net.minecraft.world.entity.Display.BlockDisplay d && d.isPassenger())
                            ice.add(e);
                    return ice.size() == 3
                            && ice.stream()
                                    .allMatch(d -> husks.stream()
                                            .anyMatch(h -> Math.hypot(h.getX() - d.getX(), h.getZ() - d.getZ()) < .3));
                });
                Soft.require(together, "each block of ice is drawn on its husk");
                ctx.waitTicks(35);
                boolean held = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    var husks = p.level()
                            .getEntitiesOfClass(
                                    Mob.class,
                                    p.getBoundingBox().inflate(12),
                                    m -> m.entityTags().contains("lightborn_lineup"));
                    return husks.size() == 3
                            && husks.stream().allMatch(lightborn.Freeze::frozen)
                            && p.getHealth() >= hp;
                });
                Soft.require(held, "Ice Nova freezes the husks solid for 3 s and none of them can strike");
                ctx.waitTicks(25);
                boolean thawed = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    return p
                            .level()
                            .getEntitiesOfClass(
                                    Mob.class,
                                    p.getBoundingBox().inflate(16),
                                    m -> m.entityTags().contains("lightborn_lineup"))
                            .stream()
                            .noneMatch(lightborn.Freeze::frozen);
                });
                Soft.require(thawed, "the ice shatters after 3 seconds");
                sp.getServer().runCommand("kill @e[tag=lightborn_lineup]");
                sp.getServer().runCommand("effect give @a resistance infinite 255 true");
            }

            if (scene("gravity_well")) {
                fresh(ctx, sp, 6000, true);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    for (int i = 0; i < 4; i++) {
                        Mob z = EntityTypes.ZOMBIE.create(p.level(), EntitySpawnReason.COMMAND);
                        z.snapTo(p.getX() + 6 + (i % 2) * 5, p.getY(), p.getZ() - 4 + (i / 2) * 8, 90f, 0f);
                        z.addTag("lightborn_lineup");
                        p.level().addFreshEntity(z);
                    }
                });
                ctx.getInput().lookAt(-90f, 12f);
                ctx.waitTicks(4);
                castGreater(sp, Spell.GRAVITY_WELL);
                ctx.waitTicks(30);
                ctx.takeScreenshot("lightborn_gravity_well");
                ctx.waitTicks(40);
            }

            if (scene("spirit_wolves")) {
                // Spirit wolves at night: they glow.
                fresh(ctx, sp, 18000, true);
                castGreater(sp, Spell.SPIRIT_WOLVES);
                ctx.getInput().lookAt(-60f, 35f);
                ctx.waitTicks(12);
                sp.getServer().runCommand("title @a clear");
                ctx.waitTicks(2);
                ctx.takeScreenshot("lightborn_spirit_wolves");
                sp.getServer().runCommand("kill @e[type=lightborn:spirit_wolf]");
            }

            if (scene("boss")) {
                fresh(ctx, sp, 6000, true);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    Mob boss = Bosses.spawn(
                            p.level(),
                            BlockPos.containing(p.getX() + 9, p.getY(), p.getZ() + 2),
                            p.level().getRandom());
                    boss.setNoAi(true);
                    boss.addTag("lightborn_lineup");
                });
                ctx.waitTicks(30);
                ctx.getInput().lookAt(-78f, 0f);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_boss");
            }

            if (scene("encounter")) {
                // Out in the wild, a mutant: its first-sight introduction. The mutant is a companion mod's.
                if (!FabricLoader.getInstance().isModLoaded("mutantmonsters")) {
                    Soft.LOG.info("LIGHTBORN CLIENT SHOT encounter skipped: no Mutant Monsters in this run (-Lean)");
                } else {
                    fresh(ctx, sp, 6000, true);
                    sp.getServer().runCommand("kill @e[type=!player]");
                    sp.getServer().runOnServer(server -> {
                        ServerPlayer p = player(server);
                        p.teleportTo(p.level(), p.getX() + 900, p.getY(), p.getZ(), Set.of(), -90f, 0f, false);
                    });
                    ctx.waitTicks(45);
                    sp.getServer().runOnServer(server -> {
                        ServerPlayer p = player(server);
                        Mob mutant = (Mob) BuiltInRegistries.ENTITY_TYPE
                                .getValue(Identifier.parse("mutantmonsters:mutant_zombie"))
                                .create(p.level(), EntitySpawnReason.COMMAND);
                        mutant.snapTo(p.getX() + 11, p.getY(), p.getZ() + 1, 90f, 0f);
                        mutant.setNoAi(true);
                        mutant.addTag("lightborn_lineup");
                        p.level().addFreshEntity(mutant);
                    });
                    ctx.getInput().lookAt(-90f, -8f);
                    ctx.waitTicks(30);
                    ctx.takeScreenshot("lightborn_encounter");
                }
            }

            if (scene("spellstone")) {
                // The Spellstone (owner, 2026-11-18): four tomes in the off hand, cast by right-click, R, Q and V.
                fresh(ctx, sp, 6000, false);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    var stone = new ItemStack(lightborn.Spellstone.ITEM);
                    stone.set(
                            DataComponents.CONTAINER,
                            net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(
                                    new ItemStack(ModItems.TOMES.get(Spell.FIREBALL)),
                                    new ItemStack(ModItems.TOMES.get(Spell.FROST_NOVA)),
                                    new ItemStack(ModItems.TOMES.get(Spell.ARCANE_BOLT)),
                                    new ItemStack(ModItems.TOMES.get(Spell.TAUNT)))));
                    p.setItemInHand(InteractionHand.OFF_HAND, stone);
                    p.getCooldowns()
                            .removeCooldown(p.getCooldowns()
                                    .getCooldownGroup(new ItemStack(ModItems.TOMES.get(Spell.ARCANE_BOLT))));
                    Progression.setMana(p, 50);
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("lightborn_spellstone_hud");
                // Q casts slot three (Arcane Bolt) and the staff stays in hand.
                ctx.getInput().pressKey(options -> options.keyDrop);
                ctx.waitTicks(3);
                boolean cast = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    return p.getMainHandItem().getItem() instanceof lightborn.StaffItem
                            && p.getCooldowns().isOnCooldown(new ItemStack(ModItems.TOMES.get(Spell.ARCANE_BOLT)))
                            && Progression.mana(p) < 49;
                });
                Soft.require(cast, "Q with a staff and Spellstone casts slot three and keeps the staff");
                // Used from the main hand, it opens on its slots.
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    var stone = p.getOffhandItem().copy();
                    p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    p.getInventory().setItem(1, stone);
                    p.getInventory().setSelectedSlot(1);
                    stone.getItem().use(p.level(), p, InteractionHand.MAIN_HAND);
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("lightborn_spellstone_slots");
                // Closed the way Escape closes it, so the server leaves the stone's menu too.
                ctx.runOnClient(mc -> mc.player.closeContainer());
                ctx.waitTicks(5);

                // In the inventory it is a bag: carry a tome over it, scroll to the V slot, click it in, right-click
                // it back out. Every step goes through the real mouse, as a player's would.
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    var stone = new ItemStack(lightborn.Spellstone.ITEM);
                    lightborn.Spellstone.store(
                            stone,
                            List.of(
                                    new ItemStack(ModItems.TOMES.get(Spell.FIREBALL)),
                                    new ItemStack(ModItems.TOMES.get(Spell.FROST_NOVA)),
                                    ItemStack.EMPTY,
                                    ItemStack.EMPTY));
                    p.getInventory().setItem(10, stone);
                    p.getInventory().setItem(11, new ItemStack(ModItems.TOMES.get(Spell.ARCANE_BOLT)));
                });
                ctx.getInput().pressKey(InputConstants.KEY_E);
                ctx.waitTicks(10);
                java.util.function.IntFunction<double[]> at = menuSlot -> ctx.computeOnClient(mc -> {
                    var slot = mc.player.inventoryMenu.getSlot(menuSlot);
                    int left = (mc.getWindow().getGuiScaledWidth() - 176) / 2,
                            top = (mc.getWindow().getGuiScaledHeight() - 166) / 2;
                    return new double[] {
                        (left + slot.x + 8) * mc.getWindow().getGuiScale(),
                        (top + slot.y + 8) * mc.getWindow().getGuiScale()
                    };
                });
                double[] tomeAt = at.apply(11), stoneAt = at.apply(10);
                ctx.getInput().setCursorPos(tomeAt[0], tomeAt[1]);
                ctx.waitTicks(3);
                ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
                ctx.waitTicks(3);
                ctx.getInput().setCursorPos(stoneAt[0], stoneAt[1]);
                ctx.waitTicks(3);
                for (int i = 0; i < 6; i++) {
                    if (ctx.computeOnClient(
                                    mc -> lightborn.Spellstone.picked(mc.player, mc.player.inventoryMenu.getSlot(10)))
                            == 3) break;
                    ctx.getInput().scroll(-1);
                    ctx.waitTicks(2);
                }
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spellstone_bag");
                ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
                ctx.waitTicks(5);
                boolean in = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    var slots = lightborn.Spellstone.tomes(p.getInventory().getItem(10));
                    return p.containerMenu.getCarried().isEmpty()
                            && lightborn.TomeItem.spellOf(slots.get(3)) == Spell.ARCANE_BOLT
                            && slots.get(2).isEmpty();
                });
                Soft.require(in, "a tome clicked onto the Spellstone lands in the slot picked by scrolling (V)");
                ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
                ctx.waitTicks(5);
                boolean out = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    return lightborn.TomeItem.spellOf(p.containerMenu.getCarried()) == Spell.ARCANE_BOLT
                            && lightborn.Spellstone.tomes(p.getInventory().getItem(10))
                                    .get(3)
                                    .isEmpty();
                });
                Soft.require(out, "right-clicking the Spellstone takes the picked slot's tome out");
                ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spellstone_full");
                ctx.getInput().setCursorPos(1, 1);
                ctx.getInput().pressKey(InputConstants.KEY_E);
                ctx.waitTicks(5);
            }

            if (scene("nest")) {
                // A full-grown Dark Nest by day (owner, 2026-11-18): its crystal taller than a person, a young one
                // beside it, a pond turned to goo, bare stone blackened, grass withered round them.
                fresh(ctx, sp, 6000, false);
                BlockPos[] cores = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    ServerLevel level = p.level();
                    var ground = p.blockPosition();
                    var big = ground.offset(9, 0, -2);
                    var small = ground.offset(9, 0, 5);
                    for (var pos : BlockPos.betweenClosed(big.offset(-3, -1, 3), big.offset(-1, -1, 5)))
                        level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
                    for (var pos : BlockPos.betweenClosed(big.offset(2, -1, -5), big.offset(5, -1, -2)))
                        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                    for (var pos : BlockPos.betweenClosed(ground.offset(3, 0, -8), ground.offset(16, 0, 9)))
                        if (level.getBlockState(pos).isAir()
                                && level.getFluidState(pos.below()).isEmpty()
                                && (pos.getX() + pos.getZ()) % 3 == 0)
                            level.setBlockAndUpdate(
                                    pos, (pos.getX() % 5 == 0 ? Blocks.POPPY : Blocks.SHORT_GRASS).defaultBlockState());
                    level.setBlockAndUpdate(
                            big, lightborn.DarkNests.NEST.defaultBlockState().setValue(lightborn.DarkNests.STAGE, 4));
                    level.setBlockAndUpdate(small, lightborn.DarkNests.NEST.defaultBlockState());
                    lightborn.DarkLand.spread(level, big, 4);
                    lightborn.DarkNests.crystal(level, big, 4);
                    lightborn.DarkNests.crystal(level, small, 1);
                    return new BlockPos[] {big, small};
                });
                ctx.waitTicks(40);
                sp.getServer().runCommand("title @a clear");
                ctx.getInput().lookAt(cores[0].above());
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_nest");
                Soft.require(
                        sp.getServer()
                                        .computeOnServer(
                                                server -> lightborn.DarkNests.crystals(server.overworld(), cores[0])
                                                        .size())
                                == 1,
                        "a nest core stands under exactly one crystal");
            }

            if (scene("nether_nest")) {
                // A grown Dark Fountain in the Nether (owner, 2026-11-20): sculk at its heart and deepslate round it
                // over the Nether's own floor, in a hall cut open so the camera sees it.
                fresh(ctx, sp, 6000, false);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.setGameMode(GameType.SPECTATOR);
                    p.teleportTo(
                            server.getLevel(net.minecraft.world.level.Level.NETHER),
                            .5,
                            72,
                            .5,
                            Set.of(),
                            -90f,
                            0f,
                            false);
                });
                ctx.waitTicks(100);
                BlockPos core = sp.getServer().computeOnServer(server -> {
                    ServerLevel level = server.getLevel(net.minecraft.world.level.Level.NETHER);
                    var o = new BlockPos(0, 70, 0);
                    // Flag 2: no neighbour updates, so the lava in the walls stays put.
                    for (var pos : BlockPos.betweenClosed(o.offset(-4, 0, -14), o.offset(30, 12, 14)))
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    for (var pos : BlockPos.betweenClosed(o.offset(-4, -1, -14), o.offset(30, -1, 14)))
                        level.setBlock(
                                pos, lightborn.DarkLand.awayFloor(level, pos).defaultBlockState(), 2);
                    var big = o.offset(14, 0, -2);
                    var small = o.offset(12, 0, 7);
                    level.setBlockAndUpdate(
                            big, lightborn.DarkNests.NEST.defaultBlockState().setValue(lightborn.DarkNests.STAGE, 4));
                    level.setBlockAndUpdate(small, lightborn.DarkNests.NEST.defaultBlockState());
                    lightborn.DarkLand.spread(level, big, 4);
                    lightborn.DarkNests.crystal(level, big, 4);
                    lightborn.DarkNests.crystal(level, small, 1);
                    player(server)
                            .teleportTo(level, o.getX() + .5, o.getY() + 1, o.getZ() + .5, Set.of(), -90f, 10f, false);
                    return big;
                });
                ctx.waitTicks(60);
                sp.getServer().runCommand("title @a clear");
                ctx.getInput().lookAt(core.above());
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_nether_nest");
            }

            if (scene("dark")) {
                // The Dark (owner, 2026-11-20): a goo sea under the Dark's sky, then the view from inside the goo.
                fresh(ctx, sp, 6000, false);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.setGameMode(GameType.SPECTATOR);
                    p.teleportTo(
                            server.getLevel(net.minecraft.world.level.Level.NETHER),
                            .5,
                            60,
                            .5,
                            Set.of(),
                            -90f,
                            0f,
                            false);
                });
                ctx.waitTicks(200);
                int[] sea = sp.getServer().computeOnServer(server -> {
                    ServerLevel level = server.getLevel(net.minecraft.world.level.Level.NETHER);
                    for (int r = 0; r < 120; r += 4)
                        for (int a = 0; a < 16; a++) {
                            int x = (int) Math.round(Math.cos(a * Math.PI / 8) * r),
                                    z = (int) Math.round(Math.sin(a * Math.PI / 8) * r);
                            if (!level.hasChunkAt(new BlockPos(x, 31, z))
                                    || !level.getFluidState(new BlockPos(x, 31, z))
                                            .is(lightborn.DarkGoo.TAG)) continue;
                            boolean open = true;
                            for (int y = 32; y <= 46 && open; y++)
                                for (int dx = -14; dx <= 0 && open; dx += 2)
                                    open = level.getBlockState(new BlockPos(x + dx, y, z))
                                            .isAir();
                            if (open) return new int[] {x, z};
                        }
                    return new int[] {0, 0};
                });
                sp.getServer()
                        .runOnServer(server -> player(server)
                                .teleportTo(
                                        server.getLevel(net.minecraft.world.level.Level.NETHER),
                                        sea[0] - 12.5,
                                        40,
                                        sea[1] + .5,
                                        Set.of(),
                                        -90f,
                                        18f,
                                        false));
                ctx.waitTicks(80);
                sp.getServer().runCommand("title @a clear");
                ctx.takeScreenshot("lightborn_dark_sea");
                sp.getServer()
                        .runOnServer(server -> player(server)
                                .teleportTo(
                                        server.getLevel(net.minecraft.world.level.Level.NETHER),
                                        sea[0] + .5,
                                        28,
                                        sea[1] + .5,
                                        Set.of(),
                                        -90f,
                                        0f,
                                        false));
                ctx.waitTicks(20);
                ctx.takeScreenshot("lightborn_dark_goo_inside");
            }

            if (scene("hollow")) {
                // The Hollow (owner, 2026-11-21): the bleached main island under its starless sky, then its dark kinds.
                fresh(ctx, sp, 6000, false);
                int[] ground = sp.getServer().computeOnServer(server -> {
                    ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
                    ServerPlayer p = player(server);
                    p.setGameMode(GameType.SPECTATOR);
                    p.teleportTo(end, 60.5, 90, 0.5, Set.of(), 90f, 20f, false);
                    end.getChunk(1, 1); // between the obsidian pillars, which stand 42 blocks out
                    int y = end.getHeight(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 20, 22);
                    return new int[] {y};
                });
                ctx.waitTicks(200);
                sp.getServer().runCommand("title @a clear");
                ctx.takeScreenshot("lightborn_hollow_island");
                sp.getServer().runOnServer(server -> {
                    ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
                    for (var e : List.of(
                            EntityTypes.ENDERMAN.create(end, EntitySpawnReason.COMMAND),
                            EntityTypes.SHULKER.create(end, EntitySpawnReason.COMMAND),
                            EntityTypes.ENDERMITE.create(end, EntitySpawnReason.COMMAND))) {
                        int i = e.getType() == EntityTypes.ENDERMAN ? 0 : e.getType() == EntityTypes.SHULKER ? 1 : 2;
                        e.setPos(18.5, ground[0], 20.5 + i * 1.6);
                        e.setNoAi(true);
                        e.setYRot(-90f);
                        e.setYHeadRot(-90f);
                        end.addFreshEntity(e);
                    }
                    var dragon = EntityTypes.ENDER_DRAGON.create(end, EntitySpawnReason.COMMAND);
                    dragon.setPos(2.5, ground[0] + 10, 30.5);
                    dragon.setNoAi(true);
                    end.addFreshEntity(dragon);
                    player(server).teleportTo(end, 25.5, ground[0] + 2.2, 22.5, Set.of(), 90f, 8f, false);
                });
                ctx.waitTicks(60);
                ctx.takeScreenshot("lightborn_hollow_kinds");
            }

            if (scene("third_shelf")) {
                // The third shelf (owner, 2026-11-21): the ten new tomes on the hotbar, the beam, wisps and halo in
                // flight, falling stars, the wall and dome with Owl Sight's glow through the wall, the bridge over a
                // drop, sown ground, and the steed under its rider.
                fresh(ctx, sp, 6000, true);
                ctx.getInput().lookAt(-90f, 4f);
                ctx.waitTicks(4);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    var shelf = List.of(
                            Spell.SUNBEAM,
                            Spell.WISPS,
                            Spell.HALO_DISC,
                            Spell.STARFALL,
                            Spell.STONE_WALL,
                            Spell.SANCTUARY,
                            Spell.LIGHT_BRIDGE,
                            Spell.OWL_SIGHT);
                    for (int i = 0; i < shelf.size(); i++)
                        p.getInventory().setItem(i, new ItemStack(ModItems.TOMES.get(shelf.get(i))));
                    p.getInventory().setSelectedSlot(0);
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.TOMES.get(Spell.SUNBEAM)));
                    for (int i = 0; i < 3; i++) {
                        Mob h = EntityTypes.HUSK.create(p.level(), EntitySpawnReason.COMMAND);
                        h.snapTo(p.getX() + 7 + i * 2, p.getY(), p.getZ() - 1.5 + i * 1.5, 90f, 0f);
                        h.setNoAi(true);
                        h.addTag("lightborn_lineup");
                        p.level().addFreshEntity(h);
                    }
                    p.setItemInHand(InteractionHand.MAIN_HAND, lightborn.LootRoller.starterStaff(p.getRandom()));
                });
                castGreater(sp, Spell.HALO_DISC);
                castGreater(sp, Spell.WISPS);
                ctx.waitTicks(5);
                castGreater(sp, Spell.SUNBEAM);
                ctx.waitTicks(1);
                ctx.takeScreenshot("lightborn_spell_beam_wisps_halo");
                ctx.waitTicks(60);

                // The Sunbeam alone, poured for a second: a solid beam of light from the book, not a smoke trail.
                for (int pulse = 0; pulse < 6; pulse++) {
                    ctx.getInput().lookAt(-90f + pulse * .6f, 3f);
                    castGreater(sp, Spell.SUNBEAM);
                    ctx.waitTicks(4);
                }
                ctx.takeScreenshot("lightborn_spell_sunbeam");
                ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
                for (int pulse = 0; pulse < 3; pulse++) {
                    castGreater(sp, Spell.SUNBEAM);
                    ctx.waitTicks(4);
                }
                ctx.takeScreenshot("lightborn_spell_sunbeam_outside");
                ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
                ctx.waitTicks(20);

                ctx.getInput().lookAt(-90f, 24f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.STARFALL);
                ctx.waitTicks(26);
                ctx.takeScreenshot("lightborn_spell_starfall");
                ctx.waitTicks(60);

                ctx.getInput().lookAt(-90f, 12f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.STONE_WALL);
                ctx.waitTicks(8);
                castGreater(sp, Spell.SANCTUARY);
                castGreater(sp, Spell.OWL_SIGHT);
                ctx.getInput().lookAt(-90f, 4f);
                ctx.waitTicks(16);
                ctx.takeScreenshot("lightborn_spell_wall_dome_owl");
                // The Ward seen from outside: walk 10 blocks back west and look at it.
                var wardAt =
                        sp.getServer().computeOnServer(server -> player(server).position());
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), wardAt.x - 11, wardAt.y, wardAt.z, Set.of(), -90f, 8f, false);
                });
                ctx.getInput().lookAt(-90f, 8f);
                ctx.waitTicks(10);
                ctx.takeScreenshot("lightborn_spell_ward");
                boolean warded = sp.getServer()
                        .computeOnServer(server -> !player(server)
                                .level()
                                .getEntitiesOfClass(
                                        lightborn.WardAura.class,
                                        new AABB(wardAt, wardAt).inflate(2),
                                        lightborn.WardAura::spell)
                                .isEmpty());
                Soft.require(warded, "the Ward spell raised no ward dome");
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), wardAt.x, wardAt.y, wardAt.z, Set.of(), -90f, 4f, false);
                });
                ctx.waitTicks(5);

                // On a pillar 6 blocks up, the bridge runs out over the drop.
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    BlockPos top = p.blockPosition().above(6).relative(net.minecraft.core.Direction.WEST, 12);
                    p.level()
                            .setBlockAndUpdate(
                                    top.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                    p.teleportTo(top.getX() + .5, top.getY(), top.getZ() + .5);
                });
                ctx.getInput().lookAt(-90f, 0f);
                ctx.waitTicks(10);
                castGreater(sp, Spell.LIGHT_BRIDGE);
                ctx.getInput().lookAt(-90f, 32f);
                ctx.waitTicks(16);
                ctx.takeScreenshot("lightborn_spell_light_bridge");

                ctx.getInput().lookAt(-90f, 10f);
                castGreater(sp, Spell.SPIRIT_STEED);
                ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
                ctx.waitTicks(20);
                boolean riding = sp.getServer()
                        .computeOnServer(server -> player(server).getVehicle() instanceof lightborn.SpiritSteed);
                Soft.require(riding, "the caster rides the Light Steed");
                ctx.takeScreenshot("lightborn_spell_spirit_steed");
                ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
                // Get off (the steed fades) and let the Ward's 20 seconds run out, so neither is in the next scene.
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(10);
                ctx.getInput().releaseKey(o -> o.keyShift);
                ctx.waitTicks(300);
            }

            if (scene("fourth_shelf")) {
                // The fourth shelf (owner, 2026-11-21): thorns under a pack with the orb drifting at them and the
                // blades
                // round you, the tether's line, Wayglow's motes lighting the night, a catch flying out of a pond,
                // Rewind's
                // mark, and Twincast's second disc.
                fresh(ctx, sp, 6000, true);
                ctx.getInput().lookAt(-90f, 4f);
                ctx.waitTicks(4);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    var shelf = List.of(
                            Spell.TETHER,
                            Spell.WAYGLOW,
                            Spell.THORNFIELD,
                            Spell.LIGHTBRAND,
                            Spell.BALL_LIGHTNING,
                            Spell.BLADE_RING,
                            Spell.REWIND);
                    for (int i = 0; i < shelf.size(); i++)
                        p.getInventory().setItem(i, new ItemStack(ModItems.TOMES.get(shelf.get(i))));
                    p.getInventory().setSelectedSlot(0);
                    p.setItemInHand(InteractionHand.MAIN_HAND, staff.copy());
                    for (int i = 0; i < 4; i++) {
                        Mob h = EntityTypes.HUSK.create(p.level(), EntitySpawnReason.COMMAND);
                        h.snapTo(p.getX() + 6 + (i % 2) * 1.5, p.getY(), p.getZ() - 2 + i * 1.3, 90f, 0f);
                        h.setNoAi(true);
                        h.addTag("lightborn_lineup");
                        p.level().addFreshEntity(h);
                    }
                    Mob near = EntityTypes.HUSK.create(p.level(), EntitySpawnReason.COMMAND);
                    near.snapTo(p.getX() + 2, p.getY(), p.getZ() + 1.5, 90f, 0f);
                    near.setNoAi(true);
                    near.addTag("lightborn_lineup");
                    p.level().addFreshEntity(near);
                });
                ctx.getInput().lookAt(-90f, 14f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.THORNFIELD);
                ctx.getInput().lookAt(-90f, 8f);
                ctx.waitTicks(12);
                ctx.takeScreenshot("lightborn_spell_thornfield");
                ctx.getInput().lookAt(-90f, 4f);
                ctx.waitTicks(2);
                castGreater(sp, Spell.BALL_LIGHTNING);
                ctx.waitTicks(16);
                ctx.takeScreenshot("lightborn_spell_ball_lightning");
                castGreater(sp, Spell.LIGHTBRAND);
                castGreater(sp, Spell.BLADE_RING);
                ctx.waitTicks(6);
                ctx.takeScreenshot("lightborn_spell_blade_ring_first_person");
                ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
                ctx.getInput().lookAt(-90f, 30f);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spell_blade_ring");
                ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
                ctx.waitTicks(200);

                // The tether's line to the ground ahead, pulling the caster to it (owner, 2026-11-22: creatures are
                // pulled only with the Lasso perk).
                fresh(ctx, sp, 6000, true);
                var tetherFrom =
                        sp.getServer().computeOnServer(server -> player(server).position());
                ctx.getInput().lookAt(-90f, 7f);
                ctx.waitTicks(4);
                castGreater(sp, Spell.TETHER);
                ctx.waitTicks(4);
                ctx.takeScreenshot("lightborn_spell_tether");
                ctx.waitTicks(30);
                double reeled = sp.getServer()
                        .computeOnServer(server -> player(server).position().distanceTo(tetherFrom));
                Soft.require(reeled > 6, "the tether pulled its caster " + reeled + " blocks");

                // Wayglow at night: motes thrown round the field stay and light it.
                fresh(ctx, sp, 18000, false);
                for (int k = 0; k < 5; k++) {
                    ctx.getInput().lookAt(-90f + (k - 2) * 18f, 16f + (k % 2) * 6f);
                    ctx.waitTicks(2);
                    castGreater(sp, Spell.WAYGLOW);
                    ctx.waitTicks(14);
                }
                ctx.getInput().lookAt(-90f, 12f);
                ctx.waitTicks(20);
                int motes = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    int n = 0;
                    for (var c : BlockPos.betweenClosed(
                            p.blockPosition().offset(-30, -8, -30),
                            p.blockPosition().offset(30, 8, 30)))
                        if (p.level().getBlockState(c).is(lightborn.GlowMote.MOTE)) n++;
                    return n;
                });
                Soft.require(motes >= 4, "Wayglow left " + motes + " motes of light");
                ctx.takeScreenshot("lightborn_spell_wayglow");
                ctx.getInput().lookAt(-90f, 20f);
                ctx.waitTicks(2);
                castUpgraded(sp, Spell.WAYGLOW, new int[] {-1, -1, 0}); // Bright Motes
                ctx.waitTicks(16);
                boolean big = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    for (var c : BlockPos.betweenClosed(
                            p.blockPosition().offset(-30, -8, -30),
                            p.blockPosition().offset(30, 8, 30))) {
                        var st = p.level().getBlockState(c);
                        if (st.is(lightborn.GlowMote.MOTE) && st.getValue(lightborn.GlowMote.LARGE)) return true;
                    }
                    return false;
                });
                Soft.require(big, "a Bright Mote landed twice the size");
                ctx.takeScreenshot("lightborn_spell_wayglow_bright");

                // Lightbrand at night, away from the motes: cast with the staff, then a plain iron sword in hand spills
                // light like the Light Sword and lights the ground (owner, 2026-11-22).
                castGreater(sp, Spell.LIGHTBRAND);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), p.getX(), p.getY(), p.getZ() + 40, Set.of(), -90f, 20f, false);
                    p.setItemInHand(
                            InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
                });
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                ctx.getInput().lookAt(-90f, 20f);
                ctx.waitTicks(30);
                boolean brandLit = sp.getServer()
                        .computeOnServer(
                                server -> Boolean.TRUE.equals(player(server).getAttached(lightborn.ModData.BRANDED)));
                Soft.require(brandLit, "Lightbrand marks its caster's weapons to shine");
                ctx.takeScreenshot("lightborn_spell_lightbrand_night");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                sp.getServer()
                        .runOnServer(server -> player(server).setItemInHand(InteractionHand.MAIN_HAND, staff.copy()));

                // Rewind: the mark where you stood, seen from where you ran to; then you are back on it.
                fresh(ctx, sp, 6000, true);
                var mark =
                        sp.getServer().computeOnServer(server -> player(server).position());
                castGreater(sp, Spell.REWIND);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), mark.x - 6, mark.y, mark.z, Set.of(), -90f, 10f, false);
                });
                ctx.getInput().lookAt(-90f, 10f);
                ctx.waitTicks(20);
                ctx.takeScreenshot("lightborn_spell_rewind");
                ctx.waitTicks(190);
                double off = sp.getServer()
                        .computeOnServer(server -> player(server).position().distanceTo(mark));
                Soft.require(off < 1, "Rewind took the player back to the mark (off by " + off + ")");

                // Twincast: the Halo Disc thrown twice, the echo a moment behind.
                fresh(ctx, sp, 6000, true);
                ctx.getInput().lookAt(-90f, 4f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.TWINCAST);
                castGreater(sp, Spell.HALO_DISC);
                ctx.waitTicks(15);
                ctx.takeScreenshot("lightborn_spell_twincast");
                ctx.waitTicks(30);
            }

            if (scene("fifth_shelf")) {
                // The fifth and last shelf (owner, 2026-11-22): the cyclone in a pack, a rune waiting and bursting,
                // Dawnbreak gathering and breaking, Emberwalk's crust over a lava pool, the Crystal Shell from inside
                // and out, Transpose, Moongate's pair of gates, a wolf befriended, calves grown, and a bored tunnel.
                fresh(ctx, sp, 6000, true);
                lineUp(
                        sp,
                        List.of(EntityTypes.HUSK, EntityTypes.HUSK, EntityTypes.HUSK),
                        (i, p) -> p.position().add(6 + i, 0, -1.2 + i * 1.2));
                ctx.getInput().lookAt(-90f, 6f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.CYCLONE);
                ctx.waitTicks(16);
                ctx.takeScreenshot("lightborn_spell_cyclone");
                ctx.waitTicks(70);

                // Rune Snare: the rune waiting on the grass, then a husk stepping on it.
                fresh(ctx, sp, 6000, true);
                ctx.getInput().lookAt(-90f, 22f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.RUNE_SNARE);
                ctx.waitTicks(14);
                ctx.takeScreenshot("lightborn_spell_rune_snare");
                var rune = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    return p.position().add(4, 0, 0);
                });
                lineUp(sp, List.of(EntityTypes.HUSK), (i, p) -> new Vec3(rune.x, p.getY(), rune.z));
                ctx.waitTicks(2);
                ctx.takeScreenshot("lightborn_spell_rune_snare_burst");
                ctx.waitTicks(20);

                // Dawnbreak: the light gathering at your feet, then the sun breaking over a ring of husks.
                fresh(ctx, sp, 13000, true);
                lineUp(
                        sp,
                        List.of(
                                EntityTypes.HUSK,
                                EntityTypes.HUSK,
                                EntityTypes.ZOMBIE,
                                EntityTypes.HUSK,
                                EntityTypes.ZOMBIE),
                        (i, p) -> p.position().add(5 + (i % 2) * 2, 0, -4 + i * 2));
                ctx.getInput().lookAt(-90f, 10f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.DAWNBREAK);
                ctx.waitTicks(18);
                ctx.takeScreenshot("lightborn_spell_dawnbreak_gather");
                ctx.waitTicks(15);
                ctx.takeScreenshot("lightborn_spell_dawnbreak");
                ctx.waitTicks(40);

                // Emberwalk: standing out on a lava pool, the crust under your feet.
                fresh(ctx, sp, 6000, true);
                var pool = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    var cells = new ArrayList<BlockPos>();
                    var was = new ArrayList<net.minecraft.world.level.block.state.BlockState>();
                    BlockPos feet = p.blockPosition();
                    for (var c : BlockPos.betweenClosed(feet.offset(2, -1, -4), feet.offset(12, -1, 4))) {
                        cells.add(c.immutable());
                        was.add(p.level().getBlockState(c));
                        p.level().setBlockAndUpdate(c, Blocks.LAVA.defaultBlockState());
                    }
                    return new Object[] {cells, was};
                });
                castGreater(sp, Spell.EMBERWALK);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), p.getX() + 6, p.getY(), p.getZ(), Set.of(), -90f, 30f, false);
                });
                ctx.getInput().lookAt(-90f, 38f);
                ctx.waitTicks(8);
                boolean crusted = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    return p.level().getBlockState(p.blockPosition().below()).is(lightborn.EmberCrust.CRUST);
                });
                Soft.require(crusted, "Emberwalk left no crust under the player on the lava");
                ctx.takeScreenshot("lightborn_spell_emberwalk");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                ctx.getInput().lookAt(-90f, 30f);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spell_emberwalk_outside");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    @SuppressWarnings("unchecked")
                    var cells = (List<BlockPos>) pool[0];
                    @SuppressWarnings("unchecked")
                    var was = (List<net.minecraft.world.level.block.state.BlockState>) pool[1];
                    for (int i = 0; i < cells.size(); i++) p.level().setBlockAndUpdate(cells.get(i), was.get(i));
                    p.clearFire();
                });

                // Crystal Shell: the walls seen from inside, then from behind, a husk beside it.
                fresh(ctx, sp, 6000, true);
                lineUp(sp, List.of(EntityTypes.HUSK), (i, p) -> p.position().add(2.2, 0, 1));
                ctx.getInput().lookAt(-90f, 4f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.CRYSTAL_SHELL);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spell_crystal_shell_inside");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                ctx.getInput().lookAt(-90f, 25f);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spell_crystal_shell");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                ctx.waitTicks(70);

                // Transpose: you stand where the husk stood, looking back at it where you were.
                fresh(ctx, sp, 6000, true);
                lineUp(sp, List.of(EntityTypes.HUSK), (i, p) -> p.position().add(10, 0, 0));
                ctx.getInput().lookAt(-90f, 4f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.TRANSPOSE);
                ctx.waitTicks(4);
                ctx.takeScreenshot("lightborn_spell_transpose");

                // Moongate: the near gate ahead and the far one by a wall; then you walk through and look back. By day:
                // at dusk the first frame was lost in the night's own haze.
                fresh(ctx, sp, 6000, true);
                var wall = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    var cells = new ArrayList<BlockPos>();
                    for (var c : BlockPos.betweenClosed(
                            p.blockPosition().offset(16, 0, -2),
                            p.blockPosition().offset(16, 3, 2))) {
                        cells.add(c.immutable());
                        p.level().setBlockAndUpdate(c, Blocks.STONE_BRICKS.defaultBlockState());
                    }
                    return cells;
                });
                ctx.getInput().lookAt(-90f, 3f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.MOONGATE);
                ctx.waitTicks(12);
                ctx.takeScreenshot("lightborn_spell_moongate");
                var before =
                        sp.getServer().computeOnServer(server -> player(server).position());
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), p.getX() + 2.5, p.getY(), p.getZ(), Set.of(), -90f, 3f, false);
                });
                ctx.waitTicks(6);
                double went =
                        sp.getServer().computeOnServer(server -> player(server).getX() - before.x);
                Soft.require(went > 10, "Moongate carried the player " + went + " blocks");
                ctx.getInput().lookAt(90f, 3f);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spell_moongate_through");
                sp.getServer().runOnServer(server -> {
                    lightborn.SpellSchools.clear();
                    for (var c : wall) player(server).level().setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());
                });

                // In open ground the portals are a doorway: walk on into the near one and you walk on out beyond the
                // far one, still going the same way (owner, 2026-11-22); then turn and see the portal behind you.
                fresh(ctx, sp, 6000, true);
                var walkFrom =
                        sp.getServer().computeOnServer(server -> player(server).position());
                ctx.getInput().lookAt(-90f, 6.5f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.MOONGATE);
                ctx.getInput().lookAt(-90f, 2f);
                ctx.waitTicks(12);
                ctx.takeScreenshot("lightborn_spell_moongate_open_ground");
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.waitTicks(16);
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.waitTicks(4);
                double walked =
                        sp.getServer().computeOnServer(server -> player(server).getX() - walkFrom.x);
                Soft.require(
                        walked > 15, "walking through the Moongate carried the player on " + walked + " blocks east");
                ctx.getInput().lookAt(90f, 2f);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spell_moongate_doorway");
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());

                // Quickening: calves and lambs, then grown.
                fresh(ctx, sp, 6000, true);
                lineUp(
                        sp,
                        List.of(EntityTypes.COW, EntityTypes.SHEEP, EntityTypes.COW, EntityTypes.SHEEP),
                        (i, p) -> p.position().add(4 + (i % 2) * 1.5, 0, -2.4 + i * 1.6));
                sp.getServer().runOnServer(server -> {
                    for (var m : player(server)
                            .level()
                            .getEntitiesOfClass(
                                    net.minecraft.world.entity.AgeableMob.class,
                                    player(server).getBoundingBox().inflate(10),
                                    m -> m.entityTags().contains("lightborn_lineup"))) m.setBaby(true);
                });
                ctx.getInput().lookAt(-90f, 16f);
                ctx.waitTicks(4);
                ctx.takeScreenshot("lightborn_spell_quickening_before");
                castGreater(sp, Spell.QUICKENING);
                ctx.waitTicks(6);
                ctx.takeScreenshot("lightborn_spell_quickening");

                // Light Bore: a hill of stone ahead, a tunnel bored into it.
                fresh(ctx, sp, 6000, true);
                var hill = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    var cells = new ArrayList<BlockPos>();
                    BlockPos feet = p.blockPosition();
                    for (var c : BlockPos.betweenClosed(feet.offset(3, 0, -3), feet.offset(24, 4, 3))) {
                        cells.add(c.immutable());
                        p.level()
                                .setBlockAndUpdate(
                                        c,
                                        (c.getX() * 7 + c.getY() * 3 + c.getZ() * 5) % 23 == 0
                                                ? Blocks.IRON_ORE.defaultBlockState()
                                                : Blocks.STONE.defaultBlockState());
                    }
                    return cells;
                });
                ctx.getInput().lookAt(-90f, 0f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.LIGHT_BORE);
                ctx.waitTicks(4);
                ctx.takeScreenshot("lightborn_spell_light_bore");
                sp.getServer().runOnServer(server -> {
                    for (var c : hill) player(server).level().setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());
                    for (var item : player(server)
                            .level()
                            .getEntitiesOfClass(
                                    ItemEntity.class,
                                    player(server).getBoundingBox().inflate(6),
                                    e -> true)) item.discard();
                });
            }

            if (scene("sixth_shelf")) {
                // The sixth shelf (owner, 2026-11-22: six Legendary, seven Mythic): one look at each spell at work.
                var pack = List.<EntityType<? extends Mob>>of(
                        EntityTypes.HUSK, EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.ZOMBIE);

                // Sentinel: the crystal standing on the grass, its beam on a husk beyond.
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[type=iron_golem]");
                fresh(ctx, sp, 6000, true);
                lineUp(sp, pack, (i, p) -> p.position().add(10 + (i % 2), 0, -3 + i * 2));
                ctx.getInput().lookAt(-90f, 20f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.SENTINEL);
                ctx.getInput().lookAt(-90f, 4f);
                ctx.waitTicks(21);
                ctx.takeScreenshot("lightborn_spell_sentinel");

                // Blizzard: the storm over a pack.
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[type=iron_golem]");
                fresh(ctx, sp, 6000, true);
                lineUp(sp, pack, (i, p) -> p.position().add(8 + (i % 2) * 2, 0, -3 + i * 2));
                ctx.getInput().lookAt(-90f, 11f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.BLIZZARD);
                ctx.getInput().lookAt(-90f, 2f);
                ctx.waitTicks(30);
                ctx.takeScreenshot("lightborn_spell_blizzard");

                // Wellspring: the spring rising round you, seen from behind.
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[type=iron_golem]");
                fresh(ctx, sp, 6000, true);
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                ctx.getInput().lookAt(-90f, 25f);
                castGreater(sp, Spell.WELLSPRING);
                ctx.waitTicks(6);
                ctx.takeScreenshot("lightborn_spell_wellspring");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));

                // Banish: the pillar of light falling on the husk.
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[type=iron_golem]");
                fresh(ctx, sp, 6000, true);
                lineUp(sp, List.of(EntityTypes.HUSK), (i, p) -> p.position().add(9, 0, 0));
                ctx.getInput().lookAt(-90f, 0f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.BANISH);
                ctx.getInput().lookAt(-90f, -12f);
                ctx.waitTicks(3);
                ctx.takeScreenshot("lightborn_spell_banish");

                // Soul Link: chains of light across a pack.
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[type=iron_golem]");
                fresh(ctx, sp, 6000, true);
                lineUp(sp, pack, (i, p) -> p.position().add(7 + (i % 2) * 2, 0, -3 + i * 2));
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), p.getX(), p.getY(), p.getZ() + 0.6, Set.of(), -90f, 4f, false);
                });
                ctx.getInput().lookAt(-90f, 4f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.SOUL_LINK);
                ctx.waitTicks(9);
                ctx.takeScreenshot("lightborn_spell_soul_link");

                // Time Stop: the grey wave and the held pack.
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[type=iron_golem]");
                fresh(ctx, sp, 6000, true);
                lineUp(sp, pack, (i, p) -> p.position().add(6 + (i % 2) * 2, 0, -3 + i * 2));
                ctx.getInput().lookAt(-90f, 8f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.TIME_STOP);
                ctx.waitTicks(3);
                ctx.takeScreenshot("lightborn_spell_time_stop");
                ctx.waitTicks(30);

                // Tempest: lightning on the pack under the storm cloud, at dusk so the bolts show.
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[type=iron_golem]");
                fresh(ctx, sp, 13000, true);
                lineUp(sp, pack, (i, p) -> p.position().add(9 + (i % 2) * 2, 0, -4 + i * 2.5));
                ctx.getInput().lookAt(-90f, -8f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.TEMPEST);
                ctx.waitTicks(2);
                ctx.takeScreenshot("lightborn_spell_tempest");
                ctx.waitTicks(40);
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());

                // Colossus: the giant golem of light beside you, seen from behind.
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[type=iron_golem]");
                fresh(ctx, sp, 6000, true);
                lineUp(sp, List.of(EntityTypes.HUSK), (i, p) -> p.position().add(9, 0, 1));
                ctx.getInput().lookAt(-90f, 0f);
                castGreater(sp, Spell.COLOSSUS);
                ctx.waitTicks(3);
                // Step back and to the side to see it whole beside the husk.
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), p.getX() - 4, p.getY(), p.getZ() - 6, Set.of(), -60f, -8f, false);
                });
                ctx.getInput().lookAt(-60f, -8f);
                ctx.waitTicks(20);
                ctx.takeScreenshot("lightborn_spell_colossus");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                sp.getServer().runCommand("kill @e[type=iron_golem]");

                // Ascension: you standing half again as tall beside a husk, from behind.
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[type=iron_golem]");
                fresh(ctx, sp, 6000, true);
                lineUp(sp, List.of(EntityTypes.HUSK), (i, p) -> p.position().add(5, 0, 3));
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                ctx.getInput().lookAt(-75f, 15f);
                castGreater(sp, Spell.ASCENSION);
                ctx.waitTicks(10);
                ctx.takeScreenshot("lightborn_spell_ascension");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());

                // Renewal: the golden flourish round you, from behind.
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[type=iron_golem]");
                fresh(ctx, sp, 6000, true);
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                ctx.getInput().lookAt(-90f, 25f);
                castGreater(sp, Spell.RENEWAL);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spell_renewal");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));

                // Transmute: the ground round you, 10 blocks every way, turned to ore; the field is put back after.
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[type=iron_golem]");
                fresh(ctx, sp, 6000, true);
                var bed = sp.getServer().computeOnServer(server -> {
                    ServerPlayer p = player(server);
                    var cells = new ArrayList<BlockPos>();
                    var was = new ArrayList<net.minecraft.world.level.block.state.BlockState>();
                    BlockPos feet = p.blockPosition();
                    for (var c : BlockPos.betweenClosed(feet.offset(-11, -11, -11), feet.offset(11, 11, 11))) {
                        var s = p.level().getBlockState(c);
                        if (s.isAir()) continue;
                        cells.add(c.immutable());
                        was.add(s);
                    }
                    return new Object[] {cells, was};
                });
                ctx.getInput().lookAt(-90f, 30f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.TRANSMUTE);
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                ctx.getInput().lookAt(-90f, 40f);
                ctx.waitTicks(8);
                ctx.takeScreenshot("lightborn_spell_transmute");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    @SuppressWarnings("unchecked")
                    var cells = (List<BlockPos>) bed[0];
                    @SuppressWarnings("unchecked")
                    var was = (List<net.minecraft.world.level.block.state.BlockState>) bed[1];
                    for (int i = 0; i < cells.size(); i++) p.level().setBlockAndUpdate(cells.get(i), was.get(i));
                });
            }

            if (scene("seventh_shelf")) {
                // The seventh shelf (owner, 2026-11-22: beast forms and Basic spells): each form from behind, so the
                // beast drawn in the player's place shows, then the Basic spells you can see.
                var pack = List.<EntityType<? extends Mob>>of(
                        EntityTypes.HUSK, EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.ZOMBIE);
                Spell[] forms = {
                    Spell.WOLF_FORM, Spell.BEAR_FORM, Spell.SPIDER_FORM, Spell.BLAZE_FORM, Spell.WARDEN_FORM
                };
                String[] shots = {"wolf_form", "bear_form", "spider_form", "blaze_form", "warden_form"};
                for (int f = 0; f < forms.length; f++) {
                    fresh(ctx, sp, 6000, true);
                    lineUp(sp, List.of(EntityTypes.HUSK), (i, p) -> p.position().add(7, 0, 2));
                    ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                    ctx.getInput().lookAt(-70f, 18f);
                    castGreater(sp, forms[f]);
                    ctx.waitTicks(4);
                    if (forms[f] == Spell.BLAZE_FORM) {
                        sp.getServer().runOnServer(server -> {
                            ServerPlayer p = player(server);
                            p.teleportTo(p.level(), p.getX(), p.getY() + 1.5, p.getZ(), Set.of(), -70f, 8f, false);
                            lightborn.FormSpells.power(p);
                        });
                        ctx.getInput().lookAt(-70f, 8f);
                        ctx.waitTicks(4);
                    } else if (forms[f] == Spell.WARDEN_FORM) {
                        // Right-click is the form's power (owner, 2026-11-23): a real click, through FormUseMixin.
                        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
                        ctx.waitTicks(2);
                    } else ctx.waitTicks(6);
                    ctx.takeScreenshot("lightborn_spell_" + shots[f]);
                    ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                    sp.getServer().runOnServer(server -> lightborn.FormSpells.end(player(server)));
                }

                // Spirit Walk: the body left standing, seen by the spirit that has stepped back from it.
                fresh(ctx, sp, 6000, true);
                castGreater(sp, Spell.SPIRIT_WALK);
                ctx.waitTicks(3);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), p.getX() - 4, p.getY() + 1, p.getZ() + 1.5, Set.of(), -110f, 12f, false);
                });
                ctx.getInput().lookAt(-110f, 12f);
                ctx.waitTicks(8);
                ctx.takeScreenshot("lightborn_spell_spirit_walk");
                sp.getServer().runOnServer(server -> lightborn.FormSpells.end(player(server)));
                ctx.waitTicks(5);

                // Shrinking: you, six times smaller, beside a husk, from behind.
                fresh(ctx, sp, 6000, true);
                lineUp(sp, List.of(EntityTypes.HUSK), (i, p) -> p.position().add(2.5, 0, 1));
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                ctx.getInput().lookAt(-80f, 20f);
                castGreater(sp, Spell.SHRINKING);
                ctx.waitTicks(10);
                ctx.takeScreenshot("lightborn_spell_shrinking");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                sp.getServer().runOnServer(server -> lightborn.FormSpells.unshrink(player(server)));

                // Splinter: the fan of light in flight.
                fresh(ctx, sp, 13000, true);
                lineUp(sp, pack, (i, p) -> p.position().add(11, 0, -3 + i * 2));
                ctx.getInput().lookAt(-90f, 2f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.SPLINTER);
                ctx.waitTicks(3);
                ctx.takeScreenshot("lightborn_spell_splinter");

                // Boulder: the stone in the air on its way to a pack.
                fresh(ctx, sp, 6000, true);
                lineUp(sp, pack, (i, p) -> p.position().add(12, 0, -3 + i * 2));
                ctx.getInput().lookAt(-90f, -6f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.BOULDER);
                ctx.waitTicks(6);
                ctx.takeScreenshot("lightborn_spell_boulder");
                ctx.waitTicks(30);
                sp.getServer().runCommand("kill @e[tag=lightborn_lineup]");

                // Workbench with Stonecutter, Loom and Anvil in use: the crafting table with its arrows, a press of
                // the right key steps to the stonecutter, and two more (past the loom) to the anvil.
                fresh(ctx, sp, 6000, true);
                castGreater(sp, Spell.WORKBENCH, new int[] {0, 0, 0});
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spell_workbench");
                ctx.getInput().pressKey(262);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spell_workbench_next");
                for (int i = 0; i < 2; i++) {
                    ctx.getInput().pressKey(262);
                    ctx.waitTicks(3);
                }
                ctx.waitTicks(3);
                ctx.takeScreenshot("lightborn_spell_workbench_anvil");
                String station = sp.getServer()
                        .computeOnServer(server -> player(server).containerMenu instanceof lightborn.Workbench.Bench b
                                ? b.station().name()
                                : "none");
                Soft.require(
                        "ANVIL".equals(station),
                        "with Stonecutter, Loom and Anvil in use, three right presses reached the anvil: " + station);
                ctx.runOnClient(mc -> mc.player.closeContainer());
                ctx.waitTicks(3);
            }

            if (scene("eighth_shelf")) {
                // The eighth shelf (owner, 2026-11-23). Comet Dash mid-streak from behind, and it must really carry
                // the player; Doom's skull riding a husk; the Lifebloom tree inside its glowing ring.
                var pack = List.<EntityType<? extends Mob>>of(
                        EntityTypes.HUSK, EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.ZOMBIE);
                fresh(ctx, sp, 6000, true);
                lineUp(sp, pack, (i, p) -> p.position().add(8, 0, -1.5 + i));
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                ctx.getInput().lookAt(-90f, 4f);
                ctx.waitTicks(3);
                var dashFrom =
                        sp.getServer().computeOnServer(server -> player(server).position());
                castGreater(sp, Spell.COMET_DASH);
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_spell_comet_dash");
                ctx.waitTicks(10);
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                double dashed = sp.getServer()
                        .computeOnServer(server -> player(server).position().distanceTo(dashFrom));
                Soft.require(dashed > 9, "Comet Dash carried the player " + dashed + " blocks");
                sp.getServer().runCommand("kill @e[tag=lightborn_lineup]");

                fresh(ctx, sp, 6000, true);
                lineUp(sp, List.of(EntityTypes.HUSK), (i, p) -> p.position().add(5, 0, 0));
                ctx.getInput().lookAt(-90f, 10f);
                ctx.waitTicks(3);
                // Glowing, Burning and Bleeding in use, and one more at random.
                castGreater(sp, Spell.DOOM, new int[] {2, 1, 0});
                ctx.waitTicks(12);
                ctx.takeScreenshot("lightborn_spell_doom");
                boolean skull = sp.getServer().computeOnServer(server -> lightborn.HexSpells.skullRiding());
                Soft.require(skull, "Doom's skull rides the doomed husk");
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[tag=lightborn_lineup]");

                // Lifebloom: the tree grown, seen from across its ring.
                fresh(ctx, sp, 6000, true);
                lineUp(sp, List.of(EntityTypes.VILLAGER), (i, p) -> p.position().add(4, 0, 2.5));
                ctx.getInput().lookAt(-90f, 35f);
                ctx.waitTicks(3);
                castGreater(sp, Spell.LIFEBLOOM);
                ctx.waitTicks(25);
                var tree = sp.getServer()
                        .computeOnServer(server -> lightborn.NatureSpells.treeRoot() == null
                                ? player(server).position()
                                : lightborn.NatureSpells.treeRoot());
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), tree.x - 12, tree.y, tree.z, Set.of(), -90f, 8f, false);
                });
                ctx.getInput().lookAt(-90f, 8f);
                ctx.waitTicks(6);
                ctx.takeScreenshot("lightborn_spell_lifebloom");
                sp.getServer().runOnServer(server -> lightborn.SpellSchools.clear());
                sp.getServer().runCommand("kill @e[tag=lightborn_lineup]");
            }

            if (scene("frost_butterflies")) {
                // Frostbite (owner, 2026-11-23): the zombie and skeleton frostbitten, shaking and frost-pale, the husk
                // between them untouched to compare. Then three Light Butterflies, by day and in black night.
                fresh(ctx, sp, 6000, true);
                lineUp(
                        sp,
                        List.<EntityType<? extends Mob>>of(EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.SKELETON),
                        (i, p) -> p.position().add(4, 0, -1.6 + i * 1.6));
                ctx.getInput().lookAt(-90f, 8f);
                ctx.waitTicks(3);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    for (var m : p.level()
                            .getEntitiesOfClass(
                                    Mob.class,
                                    p.getBoundingBox().inflate(8),
                                    m -> m.entityTags().contains("lightborn_lineup")
                                            && m.getType() != EntityTypes.HUSK)) lightborn.Frostbite.bite(m, p, 400);
                });
                ctx.waitTicks(8);
                ctx.takeScreenshot("lightborn_frostbite");
                boolean frosted = sp.getServer()
                        .computeOnServer(server -> server
                                .overworld()
                                .getEntitiesOfClass(
                                        Mob.class,
                                        player(server).getBoundingBox().inflate(8),
                                        m -> m.getType() == EntityTypes.ZOMBIE)
                                .stream()
                                .allMatch(m -> m.isFullyFrozen()));
                Soft.require(frosted, "the frostbitten zombie is frozen through");
                sp.getServer().runCommand("kill @e[tag=lightborn_lineup]");

                lineUp(
                        sp,
                        List.<EntityType<? extends Mob>>of(
                                lightborn.ModEntities.LIGHT_BUTTERFLY,
                                lightborn.ModEntities.LIGHT_BUTTERFLY,
                                lightborn.ModEntities.LIGHT_BUTTERFLY),
                        (i, p) -> p.position().add(2.6 + i * .9, 1.2 + (i % 2) * .5, -1.1 + i * 1.1));
                // Turned every which way, as they fly: side on, head on and three quarters.
                sp.getServer().runOnServer(server -> {
                    float[] yaws = {0f, 90f, 135f};
                    int k = 0;
                    for (var b : server.overworld()
                            .getEntitiesOfClass(
                                    lightborn.LightButterfly.class,
                                    player(server).getBoundingBox().inflate(8))) {
                        b.setYRot(yaws[k % 3]);
                        b.yRotO = yaws[k++ % 3];
                    }
                });
                ctx.getInput().lookAt(-90f, -4f);
                ctx.waitTicks(7);
                ctx.takeScreenshot("lightborn_butterflies");
                sp.getServer().runCommand("time set 18000");
                sp.getServer().runCommand("effect clear @a night_vision");
                ctx.waitTicks(9);
                ctx.takeScreenshot("lightborn_butterflies_night");
                sp.getServer().runCommand("kill @e[tag=lightborn_lineup]");
            }

            if (scene("steed")) {
                // The Light Steed carries its rider where they steer it (owner, 2026-11-22: mounted, it would not
                // move), and fades once they get off.
                fresh(ctx, sp, 18000, false); // at night, so the light it gives its rider shows
                ctx.getInput().lookAt(-90f, 10f);
                castGreater(sp, Spell.SPIRIT_STEED);
                ctx.waitTicks(10);
                var start =
                        sp.getServer().computeOnServer(server -> player(server).position());
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.waitTicks(40);
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                ctx.waitTicks(4);
                ctx.takeScreenshot("lightborn_spell_light_steed");
                ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
                double moved = sp.getServer()
                        .computeOnServer(server -> player(server).position().distanceTo(start));
                boolean riding = sp.getServer()
                        .computeOnServer(server -> player(server).getVehicle() instanceof lightborn.SpiritSteed);
                Soft.require(
                        riding && moved > 6,
                        "the Light Steed carried its rider " + moved + " blocks (riding=" + riding + ")");
                // A one-tick tap of Shift never reaches the server as a dismount; hold it as a player would.
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(10);
                ctx.getInput().releaseKey(o -> o.keyShift);
                ctx.waitTicks(20);
                boolean off =
                        sp.getServer().computeOnServer(server -> player(server).getVehicle() == null);
                Soft.require(off, "Shift took the rider off the Light Steed");
                boolean gone = sp.getServer()
                        .computeOnServer(server -> player(server)
                                .level()
                                .getEntitiesOfClass(
                                        lightborn.SpiritSteed.class,
                                        player(server).getBoundingBox().inflate(16),
                                        e -> true)
                                .isEmpty());
                Soft.require(gone, "the Light Steed fades once its rider gets off");

                // The Ward at night lights the ground inside it, as the chest's ward does (owner, 2026-11-22). Last,
                // so its dome is in no other scene's shot.
                var nightWard =
                        sp.getServer().computeOnServer(server -> player(server).position());
                castGreater(sp, Spell.SANCTUARY);
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), nightWard.x - 10, nightWard.y, nightWard.z, Set.of(), -90f, 14f, false);
                });
                ctx.getInput().lookAt(-90f, 14f);
                ctx.waitTicks(30);
                ctx.takeScreenshot("lightborn_spell_ward_night");
                // Inside it the night has no hold (owner, 2026-11-23): no dark drops on the screen.
                sp.getServer().runOnServer(server -> {
                    ServerPlayer p = player(server);
                    p.teleportTo(p.level(), nightWard.x, nightWard.y, nightWard.z, Set.of(), -90f, 10f, false);
                });
                ctx.getInput().lookAt(-90f, 10f);
                ctx.waitTicks(40);
                ctx.takeScreenshot("lightborn_spell_ward_inside_night");
            }

            if (scene("journal")) {
                // Orvane's fixed diary, rather than a random journal page.
                ctx.setScreen(
                        () -> new BookViewScreen(BookViewScreen.BookAccess.fromItem(DragonStory.keeperNote(true))));
                ctx.waitTicks(5);
                ctx.takeScreenshot("lightborn_journal");
                ctx.setScreen(() -> null);
            }

            if (scene("guide")) {
                // The lectern guide, every page (README: three at most, story only), at the owner's GUI scale 4.
                ctx.runOnClient(mc -> {
                    mc.options.guiScale().set(4);
                    mc.resizeGui();
                });
                int pages = SpawnHut.guideBook()
                        .get(DataComponents.WRITTEN_BOOK_CONTENT)
                        .pages()
                        .size();
                Soft.require(pages <= 3, "the guide book has " + pages + " pages; README allows three");
                ctx.setScreen(() -> new BookViewScreen(BookViewScreen.BookAccess.fromItem(SpawnHut.guideBook())));
                ctx.waitTicks(5);
                for (int i = 0; i < pages; i++) {
                    int page = i; // turned once the screen is built: setPage before init has no buttons yet
                    ctx.runOnClient(mc -> ((BookViewScreen) mc.gui.screen()).setPage(page));
                    ctx.waitTicks(5);
                    ctx.takeScreenshot("lightborn_guide_" + (page + 1));
                }
                ctx.setScreen(() -> null);
                ctx.runOnClient(mc -> {
                    mc.options.guiScale().set(0);
                    mc.resizeGui();
                });
            }
        }
    }

    /** Spawns still, tagged monsters facing the player, the i-th at {@code at(i, player)}. */
    private static void lineUp(
            TestSingleplayerContext sp,
            List<EntityType<? extends Mob>> kinds,
            java.util.function.BiFunction<Integer, ServerPlayer, Vec3> at) {
        sp.getServer().runOnServer(server -> {
            ServerPlayer p = player(server);
            ServerLevel level = p.level();
            for (int i = 0; i < kinds.size(); i++) {
                Mob m = kinds.get(i).create(level, EntitySpawnReason.COMMAND);
                Vec3 pos = at.apply(i, p);
                m.snapTo(pos.x, pos.y, pos.z, 90f, 0f);
                m.setYHeadRot(90f);
                m.setYBodyRot(90f);
                m.setNoAi(true);
                m.addTag("lightborn_lineup");
                level.addFreshEntity(m);
            }
        });
    }

    /** Stands the player beside the Chest of Light (its screen closes itself beyond 8 blocks); returns the chest. */
    private static BlockPos toChest(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        BlockPos chest = sp.getServer().computeOnServer(server -> {
            BlockPos at =
                    SpawnHut.lightChestAt(server.overworld().getRespawnData().pos());
            player(server)
                    .teleportTo(
                            server.overworld(), at.getX() + .5, at.getY(), at.getZ() + 2.5, Set.of(), 180, 20, false);
            return at;
        });
        ctx.waitTicks(5);
        return chest;
    }

    /** As {@link #castGreater}, from a tome with the given upgrade picks (one per tier, -1 for none). */
    private static void castUpgraded(TestSingleplayerContext sp, Spell spell, int[] picks) {
        sp.getServer().runOnServer(server -> {
            ServerPlayer p = player(server);
            ItemStack before = p.getOffhandItem().copy();
            var tome = new ItemStack(ModItems.TOMES.get(spell));
            SpellUpgrades.write(tome, picks);
            p.setItemInHand(InteractionHand.OFF_HAND, tome);
            Progression.setMana(p, (float) Progression.maxMana(p));
            p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(tome));
            Spells.cast(p, p.getOffhandItem());
            p.setItemInHand(InteractionHand.OFF_HAND, before);
        });
    }

    /** Casts a tome of {@code spell} from the off hand, with full mana and no cooldown, as a right-click would. */
    private static void castGreater(TestSingleplayerContext sp, Spell spell) {
        castGreater(sp, spell, null);
    }

    /** Casts {@code spell} from a tome with these upgrades in use (one choice per tier, -1 for none). */
    private static void castGreater(TestSingleplayerContext sp, Spell spell, int[] upgrades) {
        sp.getServer().runOnServer(server -> {
            ServerPlayer p = player(server);
            ItemStack before = p.getOffhandItem().copy();
            p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.TOMES.get(spell)));
            if (upgrades != null) lightborn.SpellUpgrades.write(p.getOffhandItem(), upgrades);
            Progression.setMana(p, (float) Progression.maxMana(p));
            // No cooldown either: an earlier scene's cast of the same spell may still be cooling (the Light Steed's
            // is 4 minutes).
            p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(p.getOffhandItem()));
            Spells.cast(p, p.getOffhandItem());
            p.setItemInHand(InteractionHand.OFF_HAND, before);
        });
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static CollectionScreen upgradeTab(BlockPos chest) {
        var s = new CollectionScreen(chest);
        s.select(-5);
        return s;
    }

    /** The field spot beside the spawn hut and the loadout every field scene starts with. */
    private static void setUpPlayer(MinecraftServer server) {
        ServerPlayer p = player(server);
        BlockPos home = server.overworld().getRespawnData().pos();
        field = new Vec3(home.getX() + 20.5, home.getY(), home.getZ() + 0.5);
        p.teleportTo(server.overworld(), field.x, field.y, field.z, Set.of(), -90f, 0f, false);
        p.setGameMode(GameType.SURVIVAL);
        staff = LootRoller.roll(
                p.level().getRandom(), LootRoller.Rarity.LEGENDARY, ModItems.Tier.NETHERITE, ModItems.Kind.STAFF);
        fireball = new ItemStack(ModItems.TOMES.get(Spell.FIREBALL));
        SpellUpgrades.write(fireball, new int[] {2, 0, -1}); // Quick Hands, Wider Blast
        p.getInventory().setSelectedSlot(0);
        p.setItemInHand(InteractionHand.MAIN_HAND, staff.copy());
        p.setItemInHand(InteractionHand.OFF_HAND, fireball.copy());
        p.getInventory().setItem(7, new ItemStack(ModItems.TOMES.get(Spell.WATER_SPLASH)));
        p.getInventory().setItem(8, new ItemStack(ModItems.TOMES.get(Spell.FROST_NOVA)));
        for (int i = 1; i < 6; i++)
            p.getInventory().setItem(i, LootRoller.roll(p.level().getRandom(), ModItems.Tier.values()[i % 3], 1.0));
        p.getInventory().setItem(9, SpawnHut.guideBook());
        var chest = ModItems.GEAR.get(ModItems.Tier.NETHERITE).get(ModItems.Kind.CHESTPLATE);
        p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(chest));
        Progression.setMana(p, (float) (Progression.maxMana(p) * 0.8));
    }
}
