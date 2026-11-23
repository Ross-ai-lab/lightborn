package lightborn;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lightborn.client.CollectionScreen;
import lightborn.test.Soft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

/** Real-client presentation plus normal input and native menu-packet coverage for Phase 6. */
public class PhaseSixClientTest implements FabricClientGameTest {
    private static void require(boolean value, String message) {
        Soft.require(value, message);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1600, 900);
        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .adjustSettings(s -> s.setDifficulty(Difficulty.NORMAL))
                .create()) {
            ctx.waitTicks(100);
            BlockPos at = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                var center = level.getRespawnData().pos().offset(64, 0, 64).atY(200);
                for (int x = -18; x <= 18; x++)
                    for (int z = -18; z <= 18; z++) {
                        level.setBlockAndUpdate(center.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState());
                        if (x % 4 == 0 && z % 4 == 0)
                            level.setBlockAndUpdate(center.offset(x, 3, z), Blocks.LIGHT.defaultBlockState());
                    }
                p.teleportTo(level, center.getX() + .5, center.getY(), center.getZ() + 7.5, Set.of(), 180f, 5f, false);
                p.setGameMode(GameType.SURVIVAL);
                p.getInventory().clearContent();
                PlayerBonuses.apply(p);
                var fire = new ItemStack(ModItems.TOMES.get(Spell.FIREBALL));
                SpellUpgrades.write(fire, new int[] {0, 1, -1});
                p.getInventory().setItem(40, fire);
                p.getInventory().setItem(1, new ItemStack(ModItems.TOMES.get(Spell.METEOR)));
                p.getInventory().setItem(2, new ItemStack(ModItems.TOMES.get(Spell.SPIRIT_WOLVES)));
                return center;
            });
            ctx.waitTicks(40);
            sp.getServer().runCommand("time set 18000");
            sp.getServer().runCommand("title @a clear");
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                var pages = SpawnHut.guideBook()
                        .get(DataComponents.WRITTEN_BOOK_CONTENT)
                        .pages();
                for (int i = 0; i < pages.size(); i++)
                    require(mc.font.split(pages.get(i).raw(), 114).size() <= 14, "Guide page " + i + " overflows");
                var s = new CollectionScreen(SpawnHut.lightChestAt(at));
                mc.gui.setScreen(s);
                s.select(-5);
            });
            ctx.waitTicks(8);
            ctx.getInput().setCursorPos(2, 2);
            ctx.waitTicks(2);
            ctx.takeScreenshot("phase6_upgrade_spells_tab");
            ctx.setScreen(() -> null);
            ctx.waitTicks(4);
            // Tomes never wear (owner, 2026-11-16), so there is no anvil mend to test.
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                p.getInventory().clearContent();
                p.setItemInHand(
                        InteractionHand.MAIN_HAND,
                        new ItemStack(ModItems.GEAR.get(ModItems.Tier.IRON).get(ModItems.Kind.STAFF)));
                p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.TOMES.get(lightborn.Spell.SOULS)));
                p.setAttached(ModData.SOUL_COUNTS, Map.of("minecraft:zombie", 40, "minecraft:husk", 40));
                p.setAttached(
                        ModData.SPIRITS,
                        new Souls.Spirits(
                                Map.of("minecraft:zombie", 1, "minecraft:husk", 3),
                                List.of("minecraft:zombie", "minecraft:husk"),
                                3));
                Progression.setMana(p, 100);
            });
            ctx.waitTicks(10);
            ctx.getInput().lookAt(at.offset(0, 8, -6));
            ctx.waitTicks(3);
            ctx.takeScreenshot("phase6_book_of_souls_held");
            ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
            ctx.waitTicks(8);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                var ids = p.getAttachedOrElse(ModData.BOUND_THRALLS, List.<UUID>of());
                // Both placed spirits rise (zombie and husk), for the book's 100 mana and one command each.
                require(
                        ids.size() == 2 && Progression.mana(p) < 70 && Command.used(p) == 2,
                        "Native soul summoning did not pay and bind: thralls " + ids.size() + ", mana "
                                + Progression.mana(p) + ", command " + Command.used(p));
                boolean ranked = false;
                for (var id : ids)
                    if (server.overworld().getEntity(id) instanceof Mob mob) {
                        mob.setNoAi(true);
                        ranked |= mob.getAttachedOrElse(ModData.SOUL_RANK, 0) == 3
                                && Monsters.info(mob).rank() == MonsterInfo.NORMAL;
                    }
                require(ranked, "Spirit rank missing: no rank-3 husk thrall");
            });
            // Sneak + scroll with the staff turns the off-hand tome; the hotbar stays put and a strip shows.
            int hotbar = sp.getServer().computeOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                int free = p.getInventory().getSelectedSlot() == 3 ? 5 : 3;
                p.getInventory().setItem(free, new ItemStack(ModItems.TOMES.get(Spell.FIREBALL)));
                p.getInventory().setItem(20, new ItemStack(ModItems.TOMES.get(Spell.FROST_NOVA)));
                TomeCycle.forget(p);
                return p.getInventory().getSelectedSlot();
            });
            ctx.waitTicks(10);
            ctx.getInput().holdKey(o -> o.keyShift);
            ctx.waitTicks(4);
            ctx.getInput().scroll(-1);
            ctx.waitTicks(6);
            ctx.takeScreenshot("phase6_tome_strip");
            ctx.getInput().scroll(-1);
            ctx.waitTicks(6);
            ctx.getInput().releaseKey(o -> o.keyShift);
            ctx.waitTicks(4);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                require(
                        TomeItem.spellOf(p.getOffhandItem()) == Spell.FROST_NOVA
                                && p.getInventory().getSelectedSlot() == hotbar,
                        "Sneak + scroll did not turn the off-hand tome: "
                                + p.getOffhandItem().getHoverName().getString() + ", hotbar slot "
                                + p.getInventory().getSelectedSlot());
            });
            System.out.println("LIGHTBORN PHASE6 REAL SOULS PASS");
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                p.getInventory().setItem(5, new ItemStack(ModItems.SPECIALIST_OF.get(ModItems.Tier.DIAMOND)));
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
            });
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> require(
                    Command.capacity(mc.player) == 10 && Command.used(mc.player) == 2,
                    "Carried Bone Staff capacity did not sync while holding food"));
            sp.getServer()
                    .runOnServer(server -> server.getPlayerList()
                            .getPlayers()
                            .getFirst()
                            .getInventory()
                            .setItem(5, ItemStack.EMPTY));
            System.out.println("LIGHTBORN PHASE6 REAL CARRIED COMMAND PASS");

            UUID stalker = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                var p = server.getPlayerList().getPlayers().getFirst();
                for (UUID id : p.getAttachedOrElse(ModData.BOUND_THRALLS, List.<UUID>of()))
                    if (level.getEntity(id) instanceof Mob mob) SummonSpells.releaseCompanion(mob);
                p.removeAttached(ModData.BOUND_THRALLS);
                p.removeAttached(ModData.COMMAND_ROSTER);
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                p.sendOverlayMessage(Component.empty());
                var types = List.of(
                        ModEntities.FROSTBITTEN_ZOMBIE,
                        ModEntities.MIRE_ZOMBIE,
                        ModEntities.DUNE_SKELETON,
                        ModEntities.CANOPY_SPIDER,
                        ModEntities.DEEP_STALKER_SPIDER,
                        ModEntities.DROWNED_CAPTAIN);
                UUID hidden = null;
                for (int i = 0; i < types.size(); i++) {
                    var mob = types.get(i).create(level, EntitySpawnReason.COMMAND);
                    mob.setPos(at.getX() - 4.5 + (i % 3) * 4.5, at.getY(), at.getZ() + (i < 3 ? -2.5 : 1.5));
                    mob.setYRot(0);
                    mob.setYHeadRot(0);
                    mob.setNoAi(true);
                    mob.setPersistenceRequired();
                    Monsters.setRank(mob, MonsterInfo.NORMAL);
                    if (i == 2) mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
                    level.addFreshEntity(mob);
                    if (i == 4) hidden = mob.getUUID();
                }
                return hidden;
            });
            ctx.waitTicks(15);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                var mob = server.overworld().getEntity(stalker);
                p.teleportTo(server.overworld(), mob.getX(), mob.getY(), mob.getZ() + 6.1, Set.of(), 180f, 0f, false);
            });
            ctx.waitTicks(8);
            require(
                    sp.getServer()
                            .computeOnServer(server ->
                                    server.overworld().getEntity(stalker).isInvisible()),
                    "Stalker is visible beyond six blocks");
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                var mob = server.overworld().getEntity(stalker);
                p.teleportTo(server.overworld(), mob.getX(), mob.getY(), mob.getZ() + 5.9, Set.of(), 180f, 2f, false);
            });
            ctx.waitTicks(8);
            require(
                    sp.getServer()
                            .computeOnServer(server ->
                                    !server.overworld().getEntity(stalker).isInvisible()),
                    "Stalker did not reveal within six blocks");
            ctx.getInput().lookAt(at.offset(0, 1, 0));
            ctx.waitTicks(4);
            ctx.takeScreenshot("phase6_regional_monsters");
            System.out.println("LIGHTBORN PHASE6 REAL STALKER BOUNDARY PASS");

            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                p.getFoodData().setFoodLevel(20);
                p.getFoodData().setSaturation(5);
                p.setHealth(10);
                p.setAttached(ModData.FOOD_TIMER, 0);
                p.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200, 3));
            });
            ctx.getInput().holdKey(o -> o.keyUp);
            ctx.getInput().holdKey(o -> o.keySprint);
            ctx.getInput().holdKey(o -> o.keyJump);
            ctx.waitTicks(20);
            ctx.getInput().releaseKey(o -> o.keyUp);
            ctx.getInput().releaseKey(o -> o.keySprint);
            ctx.getInput().releaseKey(o -> o.keyJump);
            ctx.waitTicks(110);
            sp.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                require(
                        p.getHealth() > 10
                                && p.getFoodData().getFoodLevel() == 20
                                && p.getFoodData().getSaturationLevel() == 5,
                        "Running, jumping, Hunger or healing consumed extra food");
                p.setAttached(ModData.FOOD_TIMER, Balance.FOOD_DRAIN_TICKS - 2);
            });
            ctx.waitTicks(6);
            require(
                    sp.getServer()
                            .computeOnServer(server -> server.getPlayerList()
                                            .getPlayers()
                                            .getFirst()
                                            .getFoodData()
                                            .getFoodLevel()
                                    == 19),
                    "Fixed hunger did not drain exactly one point");
            System.out.println("LIGHTBORN PHASE6 REAL STEADY FOOD PASS");
        }
    }
}
