package lightborn.client;

import static lightborn.client.ChestStyle.*;

import lightborn.Souls;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The Book of Souls' spirits: the book's slots along the top, every kind in three columns (an unearned one shows its
 * kill count), and the picked kind with its Claim, Rank up and Place buttons. Shown once the Book of Souls is found.
 */
final class SpiritsTab extends ChestTab {
    private static final int ROW = 10;
    /** The spirit list runs in three columns (owner, 2026-11-17: the mutants made it 28 kinds). */
    private static final int COLS = 3;
    /** The picked spirit's lines and buttons under the list: name, state, then the buttons. */
    private static final int DETAIL_H = 43;

    private String picked;
    private String message = "";
    private int scroll;
    private Button claim, rankUp, place, buySlot;

    SpiritsTab(CollectionScreen s) {
        super(s);
    }

    void receive(Souls.Note note) {
        if (s.chest.equals(note.chest())) message = note.message();
    }

    void pick(String kind) {
        picked = kind;
    }

    @Override
    void opened() {
        message = "";
    }

    @Override
    void init() {
        int sx = s.gridX() + 4, sy = detailY() + 25;
        claim = s.add(Button.builder(Component.literal("Claim"), b -> act(Souls.CLAIM))
                .bounds(sx, sy, 120, BTN_H)
                .build());
        rankUp = s.add(Button.builder(Component.literal("Rank up"), b -> act(Souls.UPGRADE))
                .bounds(sx, sy, 120, BTN_H)
                .build());
        place = s.add(Button.builder(Component.literal("Place in book"), b -> act(Souls.SLOT))
                .bounds(sx + 124, sy, 100, BTN_H)
                .build());
        buySlot = s.add(Button.builder(
                        Component.literal("Buy a slot"),
                        b -> ClientPlayNetworking.send(new Souls.Act(s.chest, Souls.BUY_SLOT, "")))
                .bounds(s.gridX() + s.gridW() - 124, s.top() + HEAD + 9, 120, 14)
                .build());
    }

    private void act(int action) {
        if (picked != null) ClientPlayNetworking.send(new Souls.Act(s.chest, action, picked));
    }

    private int listY() {
        return s.top() + HEAD + 40;
    }

    private static int rows() {
        return (Souls.PAGES.size() + COLS - 1) / COLS;
    }

    /**
     * As many rows as fit above the picked spirit's detail; the rest scroll (owner, 2026-11-17: the 39 kinds pushed the
     * Claim button over the frame).
     */
    private int shownRows() {
        int room = s.top() + s.h() - HELP - DETAIL_H - 6 - listY();
        return Math.clamp(room / ROW, 1, rows());
    }

    private int maxScroll() {
        return rows() - shownRows();
    }

    private int detailY() {
        return listY() + shownRows() * ROW + 6;
    }

    /** Spirits earned but not yet claimed: the tab's "+N". */
    int unclaimed() {
        if (mc().player == null) return 0;
        int n = 0;
        for (var page : Souls.PAGES) if (Souls.earned(mc().player, page) && Souls.rank(mc().player, page) == 0) n++;
        return n;
    }

    private String kindAt(double mx, double my) {
        int colW = (s.gridW() - 8) / COLS, x = s.gridX() + 4, y = listY(), rows = rows();
        int shown = shownRows();
        if (mx < x || mx >= x + COLS * colW || my < y || my >= y + shown * ROW) return null;
        int i = (int) ((mx - x) / colW) * rows + scroll + (int) ((my - y) / ROW);
        return i < Souls.PAGES.size() ? Souls.PAGES.get(i).id() : null;
    }

    @Override
    void layout(boolean on) {
        if (mc().player == null) return;
        int sparks = CollectionScreen.sparksOf(mc().player);
        var spirits = Souls.spirits(mc().player);
        Souls.Page page = picked == null ? null : Souls.page(picked);
        boolean shown = on && page != null;
        int rank = page == null ? 0 : spirits.rank(page.id());
        boolean earned = page != null && Souls.earned(mc().player, page);
        claim.visible = shown && rank == 0;
        claim.active = earned;
        rankUp.visible = shown && rank > 0;
        int cost = Souls.upgradeCost(rank);
        rankUp.active = cost > 0 && sparks >= cost;
        rankUp.setMessage(Component.literal(
                cost < 0 ? "Strongest rank" : "Rank " + Souls.roman(rank + 1) + " · " + sparks(cost)));
        fit(rankUp, 120, false);
        place.visible = shown && rank > 0;
        boolean slotted = page != null && spirits.slots().contains(page.id());
        place.setMessage(Component.literal(slotted ? "Take out" : "Place in book"));
        place.active = slotted || page != null && spirits.used() + page.weight() <= spirits.slotCount();
        place.setX(rankUp.getX() + rankUp.getWidth() + 4);
        int slotCost = Souls.slotCost(spirits.slotCount());
        buySlot.visible = on && slotCost > 0;
        buySlot.active = slotCost > 0 && sparks >= slotCost;
        buySlot.setMessage(Component.literal("Buy a slot · " + sparks(Math.max(0, slotCost))));
        fit(buySlot, 120, true);
    }

    @Override
    void draw(GuiGraphicsExtractor g, int mx, int my) {
        var player = mc().player;
        var spirits = Souls.spirits(player);
        int x = s.gridX() + 4, y = s.top() + HEAD, w = s.gridW() - 8;
        g.text(font(), Component.literal("Book of Souls · Spirits").withStyle(ChatFormatting.BOLD), x, y, GOLD, true);
        // The book's slots: a placed spirit spans as many slots as it takes (owner, 2026-11-16: strong ones take more).
        int unit = Math.min(52, (w - 128) / Math.max(1, spirits.slotCount()) - 2);
        int at = 0;
        for (String kind : spirits.slots()) {
            var page = Souls.page(kind);
            int span = page == null ? 1 : page.weight();
            if (at + span > spirits.slotCount()) break;
            int cx = x + at * (unit + 2), cy = y + 11, chipW = span * unit + (span - 1) * 2;
            g.fill(cx, cy, cx + chipW, cy + 12, 0xFF4A3466);
            g.fill(cx + 1, cy + 1, cx + chipW - 1, cy + 11, 0xFF241834);
            String label = page == null ? "?" : page.label() + " " + Souls.roman(spirits.rank(page.id()));
            ModHud.small(
                    g, font(), font().plainSubstrByWidth(label, (chipW - 4) * 4 / 3), cx + 2, cy + 3, SPIRIT_PURPLE);
            at += span;
        }
        for (; at < spirits.slotCount(); at++) {
            int cx = x + at * (unit + 2), cy = y + 11;
            g.fill(cx, cy, cx + unit, cy + 12, SLOT);
            g.fill(cx + 1, cy + 1, cx + unit - 1, cy + 11, SLOT_IN);
            ModHud.small(g, font(), font().plainSubstrByWidth("empty", (unit - 4) * 4 / 3), cx + 2, cy + 3, DIM);
        }
        g.text(
                font(),
                "Spirits are earned by kills · " + spirits.used() + " / " + spirits.slotCount() + " slots filled",
                x,
                y + 27,
                DIM,
                false);
        // Every kind, in three columns; an unearned kind shows its kill count toward its spirit.
        int colW = w / COLS, rows = rows(), shown = shownRows();
        scroll = Math.clamp(scroll, 0, maxScroll());
        if (maxScroll() > 0) s.drawBar(g, listY(), shown * ROW, shown, scroll, maxScroll());
        for (int i = 0; i < Souls.PAGES.size(); i++) {
            var page = Souls.PAGES.get(i);
            int row = i % rows - scroll;
            if (row < 0 || row >= shown) continue;
            int rx = x + (i / rows) * colW, ry = listY() + row * ROW;
            int rank = spirits.rank(page.id());
            boolean earned = Souls.earned(player, page),
                    slotted = spirits.slots().contains(page.id());
            if (page.id().equals(picked)) g.fill(rx - 2, ry - 1, rx + colW - 4, ry + ROW - 1, 0x40C58BFF);
            int color = slotted ? SPIRIT_PURPLE : rank > 0 ? IVORY : earned ? GOLD : DIM;
            // An unslain kind keeps its name hidden, so the book never spoils what is out there (owner, 2026-11-16).
            boolean met = earned || Souls.kills(player, page) > 0;
            g.text(font(), (slotted ? "◆ " : "  ") + (met ? page.label() : "???"), rx, ry, color, false);
            int slain = Souls.kills(player, page);
            String tag = rank > 0
                    ? Souls.roman(rank)
                    : earned ? "claim" : slain > 0 ? slain + "/" + Souls.killsNeeded(page) : "";
            g.text(
                    font(),
                    tag,
                    rx + colW - 8 - font().width(tag),
                    ry,
                    rank > 0 ? GOLD : earned ? SPIRIT_PURPLE : DIM,
                    false);
        }
        // The picked kind.
        int dy = detailY();
        g.fill(x - 2, dy - 3, x + w, dy - 2, 0xFF3A3020);
        var page = picked == null ? null : Souls.page(picked);
        if (page == null) {
            g.text(font(), "Click a spirit to see it.", x, dy + 4, DIM, false);
        } else {
            int rank = spirits.rank(page.id());
            boolean earned = Souls.earned(player, page);
            String takes = "  ·  " + lightborn.LootRoller.Rarity.ofTier(page.weight()).label + ", takes "
                    + page.weight()
                    + (page.weight() == 1 ? " slot" : " slots");
            g.text(
                    font(),
                    (earned || Souls.kills(player, page) > 0 ? page.label() : "An unknown monster")
                            + (rank > 0 ? "  ·  rank " + Souls.roman(rank) : "")
                            + takes,
                    x,
                    dy + 2,
                    GOLD,
                    false);
            String state = rank > 0
                    ? "+" + Math.round(Souls.bonus(rank) * 100) + "% health and damage"
                            + (Souls.upgradeCost(rank) > 0
                                    ? "  ·  next rank +" + Math.round(Souls.bonus(rank + 1) * 100) + "%"
                                    : "")
                    : earned
                            ? "Its spirit waits for you: claim it."
                            : Souls.kills(player, page) > 0
                                    ? "Slay " + (Souls.killsNeeded(page) - Souls.kills(player, page))
                                            + " more to earn its spirit."
                                    : "Slay one to learn its name.";
            g.text(font(), state, x, dy + 13, IVORY, false);
        }
        if (!message.isEmpty()) {
            var lines = font().split(Component.literal(message), w);
            if (!lines.isEmpty()) g.text(font(), lines.getFirst(), x, s.top() + s.h() - 13, IVORY, false);
        }
    }

    @Override
    boolean click(double mx, double my, boolean doubleClick) {
        String kind = kindAt(mx, my);
        if (kind == null) return false;
        picked = kind;
        return true;
    }

    @Override
    boolean scroll(double dy) {
        scroll = Math.clamp(scroll - (int) Math.signum(dy), 0, maxScroll());
        return true;
    }

    @Override
    int[] bar() {
        return maxScroll() > 0 ? new int[] {listY(), shownRows() * ROW, shownRows(), scroll, maxScroll()} : null;
    }

    @Override
    void scrollTo(int at) {
        scroll = at;
    }
}
