package lightborn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;

/**
 * One page of the Chest of Light ({@link CollectionScreen}): its widgets, drawing and input. The screen owns the frame,
 * the tab column and the shared scrollbar; a tab owns everything to the right of the column.
 */
abstract class ChestTab {
    final CollectionScreen s;

    ChestTab(CollectionScreen s) {
        this.s = s;
    }

    Minecraft mc() {
        return s.mc();
    }

    Font font() {
        return s.font();
    }

    /** Adds this tab's widgets (the screen's init, on open and on resize). */
    void init() {}

    /** Shows, places and enables this tab's widgets; {@code on} is whether the tab is open. Runs every frame. */
    void layout(boolean on) {}

    /** Draws the open tab under its widgets. */
    abstract void draw(GuiGraphicsExtractor g, int mx, int my);

    /** Draws the open tab over its widgets (padlocks, tooltips). */
    void drawOver(GuiGraphicsExtractor g, int mx, int my) {}

    boolean click(double mx, double my, boolean doubleClick) {
        return false;
    }

    boolean scroll(double dy) {
        return false;
    }

    /** Each client tick while the tab is open. */
    void tick(int ticks) {}

    /** The tab was just selected. */
    void opened() {}

    /** The tab's scrollbar: top, length, rows shown, scroll and its maximum; null when nothing scrolls. */
    int[] bar() {
        return null;
    }

    void scrollTo(int at) {}

    void sound(SoundEvent sound, float pitch) {
        mc().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
    }

    void sound(Holder<SoundEvent> sound, float pitch) {
        mc().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
    }

    /**
     * Widens a button to its label (owner, 2026-11-18: "Upgrade ward · 20 Sparks" was cut at the edges), keeping its
     * right edge where it was when {@code fromRight}.
     */
    void fit(AbstractWidget b, int min, boolean fromRight) {
        int want = Math.max(min, font().width(b.getMessage()) + 12);
        if (want == b.getWidth()) return;
        int right = b.getX() + b.getWidth();
        b.setWidth(want);
        if (fromRight) b.setX(right - want);
    }
}
