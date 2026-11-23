package lightborn.client.mixin;

import java.util.ArrayList;
import java.util.List;
import lightborn.Enchanting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The same resource and level requirements used by the server, in the native table screen. */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantmentScreenMixin extends AbstractContainerScreen<EnchantmentMenu> {
    protected EnchantmentScreenMixin(EnchantmentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    /** Each offered row shows its lapis price where vanilla showed a level (there are no levels). */
    @Redirect(
            method = {"extractBackground", "extractRenderState"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/inventory/EnchantmentMenu;costs:[I"))
    private int[] lightborn$shownPrices(EnchantmentMenu menu) {
        int[] shown = new int[menu.costs.length];
        for (int slot = 0; slot < shown.length; slot++) shown[slot] = menu.costs[slot] > 0 ? Enchanting.lapis(slot) : 0;
        return shown;
    }

    /** Vanilla greys out rows the player's level cannot reach; no level gates anything here. */
    @Redirect(
            method = {"extractBackground", "extractRenderState"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;experienceLevel:I"))
    private int lightborn$noLevelGate(net.minecraft.client.player.LocalPlayer player) {
        return Integer.MAX_VALUE;
    }

    @Redirect(
            method = "extractBackground",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/EnchantmentMenu;getGoldCount()I"))
    private int lightborn$availableTiers(EnchantmentMenu menu) {
        return menu.getGoldCount() / Enchanting.lapis(0);
    }

    @Redirect(
            method = "extractRenderState",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V"))
    private void lightborn$priceTooltip(
            GuiGraphicsExtractor graphics, Font font, List<Component> vanilla, int mouseX, int mouseY) {
        for (int slot = 0; slot < 3; slot++) {
            if (!isHovering(60, 14 + 19 * slot, 108, 17, mouseX, mouseY) || menu.costs[slot] <= 0) continue;
            var clue = minecraft
                    .level
                    .registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .get(menu.enchantClue[slot]);
            if (clue.isEmpty() || menu.levelClue[slot] < 0) continue;
            var lines = new ArrayList<Component>();
            lines.add(Component.translatable(
                            "container.enchant.clue", Enchantment.getFullname(clue.get(), menu.levelClue[slot]))
                    .withStyle(ChatFormatting.WHITE));
            if (!minecraft.player.hasInfiniteMaterials()) {
                int price = Enchanting.lapis(slot);
                lines.add(Component.empty());
                lines.add(Component.translatable("container.enchant.lapis.many", price)
                        .withStyle(menu.getGoldCount() >= price ? ChatFormatting.GRAY : ChatFormatting.RED));
            }
            graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
            return;
        }
        graphics.setComponentTooltipForNextFrame(font, vanilla, mouseX, mouseY);
    }
}
