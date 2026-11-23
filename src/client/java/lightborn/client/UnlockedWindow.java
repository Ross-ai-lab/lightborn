package lightborn.client;

import static lightborn.client.ChestStyle.*;

import lightborn.Collection;
import lightborn.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * The "Unlocked" window after Give all new: a gilt frame over the whole chest with every newly given item (icon and
 * name, two per row, scrolling past eight rows) and the Sparks earned, shown until closed.
 */
final class UnlockedWindow {
    private static final int W = 260, ROWS = 8, ROW_H = 18;

    private final CollectionScreen s;
    private Net.Unlocked unlocked;
    private int scroll;

    UnlockedWindow(CollectionScreen s) {
        this.s = s;
    }

    boolean isOpen() {
        return unlocked != null;
    }

    void show(Net.Unlocked u) {
        unlocked = u;
        scroll = 0;
        if (s.mc() != null)
            s.mc().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.4f, .5f));
    }

    void close() {
        unlocked = null;
    }

    private int x() {
        return (s.screenWidth() - W) / 2;
    }

    private int height() {
        return 50 + Math.min(ROWS, rows()) * ROW_H + 30;
    }

    private int y() {
        return (s.screenHeight() - height()) / 2;
    }

    /** Two names per row. */
    private int rows() {
        return unlocked == null ? 0 : (unlocked.items().size() + 1) / 2;
    }

    private int okX() {
        return x() + W / 2 - 30;
    }

    private int okY() {
        return y() + height() - 24;
    }

    void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var font = s.font();
        g.fill(0, 0, s.screenWidth(), s.screenHeight(), 0xA0000000);
        int x = x(), y = y(), hh = height();
        float pulse = (float) (.5 + .5 * Math.sin(s.ticks() * .15));
        int glow = ((int) (0x50 + 0x40 * pulse) << 24) | 0xFFD86B;
        g.fill(x - 4, y - 4, x + W + 4, y + hh + 4, glow);
        g.fill(x - 1, y - 1, x + W + 1, y + hh + 1, GOLD);
        g.fill(x, y, x + W, y + hh, 0xF8141008);
        int n = unlocked.items().size();
        String title = n == 1 ? "Unlocked!" : "Unlocked " + n + " new items!";
        g.text(
                font,
                Component.literal(title).withStyle(ChatFormatting.BOLD),
                x + (W - font.width(title) - 2) / 2,
                y + 9,
                GOLD,
                true);
        String sub = "Given to the Light";
        g.text(font, sub, x + (W - font.width(sub)) / 2, y + 22, IVORY, false);
        g.fill(x + 10, y + 34, x + W - 10, y + 35, 0xFF3A3020);
        int rows = Math.min(ROWS, rows());
        int maxScroll = Math.max(0, rows() - ROWS);
        scroll = Math.clamp(scroll, 0, maxScroll);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < 2; c++) {
                int i = (r + scroll) * 2 + c;
                if (i >= n) continue;
                var item = Collection.item(unlocked.items().get(i));
                int ix = x + 10 + c * (W - 20) / 2, iy = y + 40 + r * ROW_H;
                g.fill(ix, iy, ix + 18, iy + 18, SLOT);
                g.item(new ItemStack(item), ix + 1, iy + 1);
                String name = font.plainSubstrByWidth(
                        Component.translatable(item.getDescriptionId()).getString(), (W - 20) / 2 - 24);
                g.text(font, name, ix + 22, iy + 5, IVORY, false);
            }
        if (maxScroll > 0) {
            String more = "Scroll for more (" + (scroll + 1) + " / " + (maxScroll + 1) + ")";
            g.text(font, more, x + (W - font.width(more)) / 2, y + 40 + rows * ROW_H + 1, DIM, false);
        }
        g.text(font, "+" + unlocked.sparks() + " Sparks", x + 10, okY() + 4, GOLD, false);
        boolean hover = mouseX >= okX() && mouseX < okX() + 60 && mouseY >= okY() && mouseY < okY() + 16;
        g.fill(okX(), okY(), okX() + 60, okY() + 16, hover ? 0xFF6A5420 : 0xFF3A2E14);
        g.fill(okX(), okY(), okX() + 60, okY() + 1, GOLD);
        g.fill(okX(), okY() + 15, okX() + 60, okY() + 16, 0xFF8A6A20);
        g.text(font, "Nice!", okX() + 30 - font.width("Nice!") / 2, okY() + 4, hover ? 0xFFFFFFFF : IVORY, true);
    }

    /** Only its Nice! button answers; every other click is swallowed while it is open. */
    boolean click(double mx, double my) {
        if (mx >= okX() && mx < okX() + 60 && my >= okY() && my < okY() + 16) {
            unlocked = null;
            s.mc().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
        }
        return true;
    }

    void scroll(double dy) {
        scroll -= (int) Math.signum(dy);
    }
}
