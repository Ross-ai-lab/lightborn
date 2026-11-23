package lightborn.client;

import java.util.List;
import lightborn.StaffItem;
import lightborn.TomeCycle;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Sneak + scroll with a staff turns the off-hand tome; a small strip of every carried tome shows above the hotbar,
 * the held one framed in gold and named, and fades after a moment. No screen opens.
 */
public final class TomeStripView {
    private TomeStripView() {}

    private static final long SHOW_MS = 1400, FADE_MS = 600;
    private static List<ItemStack> tomes = List.of();
    private static int held = -1;
    private static long shownAt;
    private static double notch;

    /** Called by the scroll mixin: true when the notch turned a tome (so the hotbar does not move). */
    public static boolean scroll(double amount) {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null
                || mc.gui.screen() != null
                || p.isSpectator()
                || !p.isShiftKeyDown()
                || !(p.getMainHandItem().getItem() instanceof StaffItem)) return false;
        var off = p.getOffhandItem();
        if (!off.isEmpty() && !TomeCycle.isTome(off)) return false;
        boolean any = false;
        for (int i = 0; i < Inventory.INVENTORY_SIZE && !any; i++)
            any = TomeCycle.isTome(p.getInventory().getItem(i));
        if (!any) return false;
        // A wheel gives whole notches; a touchpad gives fractions, which add up to one.
        notch += amount;
        if (Math.abs(notch) >= 1) {
            ClientPlayNetworking.send(new TomeCycle.Cycle(notch > 0 ? -1 : 1));
            notch = 0;
        }
        return true;
    }

    static void receive(TomeCycle.Strip strip) {
        tomes = strip.tomes();
        held = strip.held();
        shownAt = Util.getMillis();
    }

    static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (tomes.isEmpty() || mc.player == null || mc.gui.hud.isHidden()) return;
        long age = Util.getMillis() - shownAt;
        if (age > SHOW_MS + FADE_MS) return;
        float alpha = age < SHOW_MS ? 1 : 1 - (age - SHOW_MS) / (float) FADE_MS;
        int a = Math.max(4, Math.round(alpha * 255));
        int cell = 18, n = tomes.size();
        int x0 = g.guiWidth() / 2 - n * cell / 2, y0 = g.guiHeight() - 82;
        g.fill(x0 - 3, y0 - 3, x0 + n * cell + 2, y0 + cell + 2, (a * 160 / 255) << 24);
        for (int i = 0; i < n; i++) {
            int x = x0 + i * cell;
            boolean on = i == held;
            g.fill(x, y0, x + 17, y0 + 17, (a << 24) | (on ? 0xFFD86B : 0x3A3020));
            g.fill(x + 1, y0 + 1, x + 16, y0 + 16, (a << 24) | (on ? 0x3A2C10 : 0x17130C));
            // Items have no alpha: they leave with the last part of the fade.
            if (alpha > .25f) g.item(tomes.get(i), x, y0);
        }
        if (held >= 0 && held < n) {
            String name = tomes.get(held).getHoverName().getString();
            g.text(
                    mc.font,
                    name,
                    g.guiWidth() / 2 - mc.font.width(name) / 2,
                    y0 + cell + 4,
                    (a << 24) | 0xFFD86B,
                    true);
        }
    }
}
