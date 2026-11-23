package lightborn.client;

import java.util.Map;
import java.util.WeakHashMap;
import lightborn.Workbench;
import lightborn.client.mixin.ContainerScreenAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/**
 * The Tome of the Workbench's arrows (owner, 2026-11-23): beside a station the spell opened, a button on each side
 * steps to the previous or next station the tome's upgrades hold (none drawn while it holds only the crafting table),
 * and so do the left and right keys (unless a text box, such as the anvil's
 * name, is being typed in). The server says which screen is a station just before it opens ({@link
 * Workbench.Opening}); stepping asks the server to open the next one ({@link Workbench.Step}).
 */
public final class BenchView {
    private BenchView() {}

    private static final int KEY_RIGHT = 262, KEY_LEFT = 263, SIZE = 20, GAP = 4;

    /** The station the next container screen will be, or -1, and the stations the tome holds (a bit each). */
    private static int pending = -1, unlocked = 1;

    private static final Map<Screen, Workbench.Station> BENCHES = new WeakHashMap<>();
    private static final Map<Screen, Button[]> ARROWS = new WeakHashMap<>();

    static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Workbench.Opening.TYPE, (packet, ctx) -> {
            pending = packet.station();
            unlocked = packet.unlocked();
        });
        ScreenEvents.AFTER_INIT.register(BenchView::afterInit);
    }

    /**
     * While stepping from one station to the next, the server's "close" for the old one is not acted on: the next
     * screen replaces it at once, so the mouse is never grabbed and recentred between them.
     */
    public static boolean swallowClose() {
        var screen = Minecraft.getInstance().gui.screen();
        return pending >= 0 && screen != null && BENCHES.containsKey(screen);
    }

    private static void afterInit(Minecraft mc, Screen screen, int w, int h) {
        if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
        if (pending >= 0) {
            var all = Workbench.Station.values();
            if (pending < all.length) BENCHES.put(screen, all[pending]);
            pending = -1;
        }
        var station = BENCHES.get(screen);
        if (station == null || Integer.bitCount(unlocked) < 2) return;
        int held = unlocked;
        Button prev = Button.builder(Component.literal("<"), b -> step(-1))
                .size(SIZE, SIZE)
                .tooltip(Tooltip.create(name(station.step(-1, held))))
                .build();
        Button next = Button.builder(Component.literal(">"), b -> step(1))
                .size(SIZE, SIZE)
                .tooltip(Tooltip.create(name(station.step(1, held))))
                .build();
        Screens.getWidgets(screen).add(prev);
        Screens.getWidgets(screen).add(next);
        ARROWS.put(screen, new Button[] {prev, next});
        place(acs);
        // The recipe book slides the crafting panel aside: the arrows follow it.
        ScreenEvents.afterTick(screen).register(s -> place(acs));
        ScreenKeyboardEvents.afterKeyPress(screen).register((s, key) -> {
            // The anvil's name box takes the arrows once there is a name in it to move through; empty (as it opens,
            // focused), they still step, or the keys could never leave the anvil.
            if (s.getFocused() instanceof EditBox box
                    && box.isFocused()
                    && !box.getValue().isEmpty()) return;
            if (key.key() == KEY_RIGHT) step(1);
            else if (key.key() == KEY_LEFT) step(-1);
        });
    }

    private static Component name(Workbench.Station s) {
        return Component.translatable(s.title);
    }

    private static void place(AbstractContainerScreen<?> acs) {
        var arrows = ARROWS.get(acs);
        if (arrows == null) return;
        var a = (ContainerScreenAccessor) acs;
        int y = a.lightborn$top() + GAP;
        arrows[0].setPosition(a.lightborn$left() - SIZE - GAP, y);
        arrows[1].setPosition(a.lightborn$left() + a.lightborn$width() + GAP, y);
    }

    private static void step(int dir) {
        ClientPlayNetworking.send(new Workbench.Step(dir));
    }
}
