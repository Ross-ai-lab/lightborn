package lightborn.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

/** Mana bar above the hearts. */
final class ModHud {
    private ModHud() {}

    static final int MANA = 0xFF3D8BFF;
    static final int MANA_DARK = 0xFF0E2547;
    static final int GOLD = 0xFFF2C14E;
    private static final Map<String, ItemStack> ICONS = new HashMap<>();

    static ItemStack icon(String id) {
        return ICONS.computeIfAbsent(id, k -> new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(k))));
    }

    /** Bar height plus its 1 px gap above the hearts; also how far the armor row moves up. */
    static final int MANA_ROW = 7;
    /** Same width as the ten-heart row. */
    static final int MANA_W = 81;

    /** True while vanilla draws hearts (survival and adventure), so the mana bar and the armor shift follow it. */
    static boolean heartsShown() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.gameMode != null && mc.gameMode.canHurtPlayer() && !mc.gui.hud.isHidden();
    }

    static int manaRowHeight() {
        return heartsShown() ? MANA_ROW : 0;
    }

    /** The experience bar's empty strip between the hotbar and the hearts (5 pixels and its 1-pixel margin). */
    static final int XP_GAP = 6;

    /**
     * How far the hearts, hunger, armor, air, mount health and mana sit lower than vanilla puts them: the game has no
     * experience (owner, 2026-11-23: close the gap over the hotbar), so they drop into the bar's empty strip. A ridden
     * mount's jump bar uses that strip, so while one shows they stay where vanilla has them.
     */
    static int drop() {
        var p = Minecraft.getInstance().player;
        return p != null && p.jumpableVehicle() != null ? 0 : XP_GAP;
    }

    /** Top of the highest heart row, following vanilla's layout (rows squeeze together as max health grows). */
    static int heartsTop(Minecraft mc, int guiHeight) {
        var p = mc.player;
        float maxHp = Math.max((float) p.getAttributeValue(Attributes.MAX_HEALTH), (float) Math.ceil(p.getHealth()));
        int absorb = (int) Math.ceil(p.getAbsorptionAmount());
        int rows = (int) Math.ceil((maxHp + absorb) / 2f / 10f);
        int rowH = Math.max(10 - (rows - 2), 3);
        return guiHeight - 39 + drop() - (rows - 1) * rowH;
    }

    /** The mana bar, directly above the hearts. */
    static void extractManaBar(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!heartsShown()) return;
        int x0 = g.guiWidth() / 2 - 91;
        int y0 = heartsTop(mc, g.guiHeight()) - MANA_ROW + 1;
        float mana = ClientState.mana();
        double maxMana = ClientState.maxMana();
        g.fill(x0, y0, x0 + MANA_W, y0 + 5, 0xC0000000);
        g.fill(x0 + 1, y0 + 1, x0 + MANA_W - 1, y0 + 4, MANA_DARK);
        int fillW = (int) Math.round((MANA_W - 2) * Math.min(1, mana / Math.max(1, maxMana)));
        g.fill(x0 + 1, y0 + 1, x0 + 1 + fillW, y0 + 4, MANA);
        g.fill(x0 + 1, y0 + 1, x0 + 1 + fillW, y0 + 2, 0xFF8CC0FF);
    }

    /** Small text at 75% size. */
    static void small(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.75f, 0.75f);
        g.text(font, text, 0, 0, color, true);
        g.pose().popMatrix();
    }
}
