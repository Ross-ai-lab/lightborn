package lightborn.client;

import static lightborn.client.ChestStyle.*;

import java.util.ArrayList;
import java.util.List;
import lightborn.Balance;
import lightborn.ChestTravel;
import lightborn.HomeMove;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Travel: moving the whole home comes first (the chest can no longer be picked up), then the Dragon's flights to
 * notable places, each kind at its own price (owner, 2026-11-16). Shown once eight Dark Fountains are beaten.
 */
final class TravelTab extends ChestTab {
    /** Where the Dragon's flight buttons start under the Move Home section. */
    private static final int TRAVEL_LIST = 92;

    private ChestTravel.Show travel;
    private final List<Button> flights = new ArrayList<>();
    /** The Move Home button, and whether it has been pressed once (a second press moves the home). */
    private Button moveHome;

    private boolean moveArmed;

    TravelTab(CollectionScreen s) {
        super(s);
    }

    void receive(ChestTravel.Show show) {
        if (!s.chest.equals(show.source())) return;
        travel = show;
    }

    @Override
    void init() {
        flights.clear();
        for (int i = 0; i < ChestTravel.SITES.size(); i++) {
            final int index = i;
            var site = ChestTravel.SITES.get(i);
            flights.add(s.add(Button.builder(
                            Component.literal(site.name()),
                            b -> ClientPlayNetworking.send(new ChestTravel.Go(s.chest, index)))
                    .bounds(0, 0, 100, 18)
                    .build()));
        }
        ClientPlayNetworking.send(new ChestTravel.Ask(s.chest));
        moveHome = s.add(Button.builder(Component.literal("Move Home"), b -> {
                    if (!moveArmed) moveArmed = true;
                    else {
                        ClientPlayNetworking.send(new HomeMove.Move(s.chest));
                        moveArmed = false;
                    }
                })
                .bounds(s.gridX() + 4, s.top() + HEAD + 58, 170, 18)
                .build());
    }

    @Override
    void opened() {
        moveArmed = false;
    }

    @Override
    void tick(int ticks) {
        if (ticks % 20 == 0) ClientPlayNetworking.send(new ChestTravel.Ask(s.chest));
    }

    /** Whether the player owns the chest this screen belongs to (only the owner may move its home). */
    private boolean ownsChest() {
        return travel != null && travel.own();
    }

    /** The flight buttons sit in two columns; a row is as tall as the room under the Move Home section allows. */
    private int pitch() {
        int rows = (ChestTravel.SITES.size() + 1) / 2;
        return Math.clamp((s.h() - HEAD - TRAVEL_LIST - 16) / rows, 14, 21);
    }

    /** Whether a flight still waits on Cleanses (owner, 2026-11-17: one opens per Cleanse, easiest first). */
    private boolean locked(int index) {
        return travel == null || travel.cleanses() < ChestTravel.cleansesFor(index);
    }

    @Override
    void layout(boolean on) {
        moveHome.visible = on;
        moveHome.active = on && ownsChest();
        if (!moveHome.active) moveArmed = false;
        moveHome.setMessage(Component.literal(
                        moveArmed
                                ? "Click again: the home moves"
                                : "Move Home · " + Balance.HOME_MOVE_SPARKS + " Sparks")
                .withStyle(st -> st.withColor((moveArmed ? 0xFFFFC040 : TRAVEL_GREEN) & 0xFFFFFF)));
        fit(moveHome, 170, false);
        int pitch = pitch(), colW = (s.gridW() - 12) / 2;
        for (int i = 0; i < flights.size(); i++) {
            var b = flights.get(i);
            var site = ChestTravel.SITES.get(i);
            boolean locked = locked(i);
            b.visible = on && travel != null;
            b.active = b.visible && !locked && travel.sparks() >= site.price();
            b.setWidth(colW);
            b.setHeight(pitch - 2);
            b.setPosition(s.gridX() + 4 + (i % 2) * (colW + 4), s.top() + HEAD + TRAVEL_LIST + (i / 2) * pitch);
            // The label fits its button (owner, 2026-11-17: names were cut off); a locked one leaves room for the lock.
            int room = colW - (locked ? 26 : 10);
            int need = ChestTravel.cleansesFor(i);
            String tail = locked ? need + (need == 1 ? " Cleanse" : " Cleanses") : sparks(site.price());
            String label = site.name() + " · " + tail;
            if (font().width(label) > room) label = site.name() + " · " + (locked ? need + "" : site.price() + "✦");
            if (font().width(label) > room) label = site.name();
            b.setMessage(Component.literal(label));
            String why = locked
                    ? "Opens after " + need + (need == 1 ? " Cleanse" : " Cleanses") + " (you have won "
                            + (travel == null ? 0 : travel.cleanses()) + "). One more destination opens with each. "
                    : "";
            String about = site.nether()
                    ? "From chapter 6: the Dragon carries you into the Dark, to the fortress his Scale points at."
                    : site.id().equals("ancient_city")
                            ? "It lies underground: the Dragon sets you down on the ground above it. From chapter 8 he"
                                    + " flies to the city the Scale points at."
                            : site.underground()
                                    ? "It lies underground: the Dragon sets you down on the ground above it."
                                    : "";
            String text = (why + about).trim();
            b.setTooltip(text.isEmpty() ? null : Tooltip.create(Component.literal(text + "\n" + sparks(site.price()))));
        }
    }

    @Override
    void draw(GuiGraphicsExtractor g, int mx, int my) {
        int x = s.gridX() + 4, y = s.top() + HEAD + 3, w = s.gridW() - 12;
        g.text(font(), Component.literal("Move Your Home").withStyle(ChatFormatting.BOLD), x, y, TRAVEL_GREEN, true);
        int ly = y + 13;
        for (var line : font().split(
                        Component.literal(
                                "Lifts everything inside the ward (house, farm, animals, you) to a completely "
                                        + "random spot: any land, any height, anything in the way erased. Your Dark Fountains fade; ten "
                                        + "new ones rise round the new home."),
                        w)) {
            g.text(font(), line, x, ly, DIM, false);
            ly += 10;
        }
        if (travel != null && !ownsChest())
            g.text(font(), "Only the chest's owner can move this home.", x + 178, s.top() + HEAD + 63, DIM, false);
        int listY = s.top() + HEAD + TRAVEL_LIST - 14;
        g.fill(x, listY - 5, x + w, listY - 4, 0xFF3A3020);
        var title = Component.literal("Fly by Dragon").withStyle(ChatFormatting.BOLD);
        g.text(font(), title, x, listY, GOLD, true);
        // The subtitle sits after the title in the room left: whole phrases only, never cut mid-word.
        int subRoom = w - font().width(title) - 8;
        String sub = "to the nearest one not yet visited · home by Homeward";
        if (font().width(sub) > subRoom) sub = "to the nearest one not yet visited";
        if (font().width(sub) > subRoom) sub = "";
        g.text(font(), sub, x + w - font().width(sub), listY, DIM, false);
        if (travel != null && !travel.message().isEmpty())
            g.text(font(), font().plainSubstrByWidth(travel.message(), w), x, s.top() + s.h() - 13, GOLD, false);
    }

    /** A small padlock on the right of every locked flight's button, drawn over it. */
    @Override
    void drawOver(GuiGraphicsExtractor g, int mx, int my) {
        for (int i = 0; i < flights.size(); i++) {
            var b = flights.get(i);
            if (!b.visible || !locked(i)) continue;
            int x = b.getX() + b.getWidth() - 14, y = b.getY() + (b.getHeight() - 10) / 2;
            int metal = 0xFFC9B27A, dark = 0xFF2A2116;
            // the shackle: an arch over the body
            g.fill(x + 2, y, x + 7, y + 1, metal);
            g.fill(x + 1, y + 1, x + 2, y + 4, metal);
            g.fill(x + 7, y + 1, x + 8, y + 4, metal);
            // the body, with its keyhole
            g.fill(x, y + 4, x + 9, y + 10, metal);
            g.fill(x + 4, y + 6, x + 5, y + 9, dark);
            g.fill(x + 3, y + 6, x + 6, y + 7, dark);
        }
    }
}
