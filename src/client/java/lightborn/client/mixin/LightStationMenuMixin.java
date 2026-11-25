package lightborn.client.mixin;

import lightborn.ChestStations;
import lightborn.client.LightStationScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MenuScreens.class)
abstract class LightStationMenuMixin {
    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static <T extends AbstractContainerMenu> void lightborn$chestStation(
            MenuType<T> type, Minecraft mc, int id, Component title, CallbackInfo callback) {
        if (mc.player == null || !(title.getContents() instanceof TranslatableContents contents)) return;
        String key = contents.getKey();
        var inventory = mc.player.getInventory();
        if (type == MenuType.FURNACE && key.equals(ChestStations.FURNACE_TITLE)) {
            FurnaceMenu furnace = MenuType.FURNACE.create(id, inventory);
            show(mc, furnace, new LightStationScreens.Furnace(furnace, inventory, title), callback);
        } else if (type == MenuType.ANVIL && key.equals(ChestStations.ANVIL_TITLE)) {
            AnvilMenu anvil = MenuType.ANVIL.create(id, inventory);
            show(mc, anvil, new LightStationScreens.Anvil(anvil, inventory, title), callback);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static void show(Minecraft mc, AbstractContainerMenu menu, Screen screen, CallbackInfo callback) {
        mc.player.containerMenu = menu;
        mc.gui.setScreen(screen);
        callback.cancel();
    }
}
