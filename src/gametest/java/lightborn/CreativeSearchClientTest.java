package lightborn;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.GameType;

/**
 * The creative inventory's search (owner, 2026-11-16: it crashed). Search builds every item's tooltip, so first every
 * Lightborn item's tooltip is built in a world, then "light" is typed into the open creative inventory and the results
 * are photographed.
 */
public final class CreativeSearchClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1600, 900);
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(60);
            sp.getServer()
                    .runOnServer(s -> s.getPlayerList().getPlayers().getFirst().setGameMode(GameType.CREATIVE));
            sp.getServer().runCommand("title @a clear");
            ctx.waitTicks(10);
            String broken = ctx.computeOnClient(mc -> {
                var context = Item.TooltipContext.of(mc.level);
                int built = 0;
                for (Item item : BuiltInRegistries.ITEM) {
                    if (!BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(Lightborn.MOD_ID)) continue;
                    try {
                        new ItemStack(item).getTooltipLines(context, mc.player, TooltipFlag.Default.NORMAL);
                        built++;
                    } catch (RuntimeException e) {
                        return BuiltInRegistries.ITEM.getKey(item) + ": " + e;
                    }
                }
                return built == 0 ? "no Lightborn items" : null;
            });
            if (broken != null) throw new AssertionError("A tooltip throws, so creative search would crash: " + broken);

            ctx.getInput().pressKey(InputConstants.KEY_E);
            ctx.waitTicks(10);
            // The search tab is the compass at the top right of the creative window (1600 x 900 window, GUI scale
            // auto): click it, then type.
            ctx.getInput().setCursorPos(1052, 212);
            ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            ctx.waitTicks(5);
            ctx.getInput().typeChars("light");
            ctx.waitTicks(40);
            // Every result must match the search, and Lightborn's Light gear must be among them.
            String result = ctx.computeOnClient(mc -> {
                if (!(mc.gui.screen() instanceof CreativeModeInventoryScreen screen)) return "no creative screen";
                // The whole result list, not just the visible page. Before the fix, "New for the Light" on every
                // tooltip made planks and stone match "light".
                var results = screen.getMenu().items;
                int lightborn = 0;
                boolean planks = false;
                for (ItemStack stack : results) {
                    var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (key.getNamespace().equals(Lightborn.MOD_ID)
                            && key.getPath().contains("light")) lightborn++;
                    if (key.getPath().equals("oak_planks")) planks = true;
                }
                return lightborn > 0 && !planks
                        ? null
                        : "results=" + results.size() + " lightborn light items=" + lightborn + " oak planks=" + planks;
            });
            ctx.takeScreenshot("creative_search_light");
            if (result != null) throw new AssertionError("Creative search for \"light\" failed: " + result);
            System.out.println("LIGHTBORN CREATIVE SEARCH PASS");
        }
    }
}
