package lightborn.client;

import static lightborn.client.ChestStyle.*;

import lightborn.Balance;
import lightborn.Cleanse;
import lightborn.ModItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * The Cleanse: the story in two lines, ten shard slots to choose how many to feed (lit up to the choice, red where the
 * bag runs short), what that many will call, and the server's word on whether it can begin now. Shown once a Dark
 * Fountain is beaten.
 */
final class CleanseTab extends ChestTab {
    /** The server's last word on it, and how many shards the player means to feed. */
    private Cleanse.Show cleanse;

    private int feed = 1;
    private Button feedLess, feedMore, begin;

    CleanseTab(CollectionScreen s) {
        super(s);
    }

    void receive(Cleanse.Show show) {
        if (s.chest.equals(show.chest())) cleanse = show;
    }

    @Override
    void init() {
        feedLess = s.add(Button.builder(Component.literal("-"), b -> feed = Math.max(1, feed - 1))
                .bounds(0, 0, 16, 16)
                .build());
        feedMore =
                s.add(Button.builder(Component.literal("+"), b -> feed = Math.min(Balance.CLEANSE_MAX_SHARDS, feed + 1))
                        .bounds(0, 0, 16, 16)
                        .build());
        begin = s.add(Button.builder(
                        Component.literal("Begin the Cleanse"),
                        b -> ClientPlayNetworking.send(new Cleanse.Start(s.chest, feed)))
                .bounds(0, 0, 120, 16)
                .build());
        ClientPlayNetworking.send(new Cleanse.Ask(s.chest));
    }

    @Override
    void opened() {
        ClientPlayNetworking.send(new Cleanse.Ask(s.chest));
    }

    @Override
    void tick(int ticks) {
        if (ticks % 10 == 0) ClientPlayNetworking.send(new Cleanse.Ask(s.chest));
    }

    /** Where the ten shard slots start. */
    private int rowX() {
        return s.gridX() + 4;
    }

    private int rowY() {
        return s.top() + HEAD + 64;
    }

    private int carried() {
        return mc().player == null ? 0 : mc().player.getInventory().countItem(ModItems.DARK_SHARD);
    }

    @Override
    void layout(boolean on) {
        int by = s.top() + s.h() - HELP - 20, bx = s.gridX() + 4;
        feedLess.visible = feedMore.visible = begin.visible = on;
        feedLess.setPosition(bx, by);
        feedMore.setPosition(bx + 20, by);
        begin.setPosition(bx + 44, by);
        feedLess.active = feed > 1;
        feedMore.active = feed < Balance.CLEANSE_MAX_SHARDS;
        boolean running = cleanse != null && cleanse.left() >= 0;
        begin.active = on
                && cleanse != null
                && cleanse.problem().isEmpty()
                && !running
                && (carried() >= feed || mc().player != null && mc().player.isCreative());
        begin.setMessage(Component.literal(running ? "Under way" : "Begin the Cleanse")
                .withStyle(st -> st.withColor(CLEANSE_VIOLET & 0xFFFFFF)));
        fit(begin, 120, false);
    }

    @Override
    void draw(GuiGraphicsExtractor g, int mx, int my) {
        int x = s.gridX() + 4, y = s.top() + HEAD + 3, w = s.gridW() - 12;
        g.text(
                font(),
                Component.literal("Cleanse the Dark").withStyle(ChatFormatting.BOLD),
                x,
                y,
                CLEANSE_VIOLET,
                true);
        int ly = y + 14;
        for (var line : font().split(
                        Component.literal(
                                "Dissolve dark shards in the Light, and the dark comes for you: one great wave from "
                                        + "beyond the ward. While it runs the ward still shines, but it no longer shields. Fell nine "
                                        + "in ten of the wave and it is beaten."),
                        w)) {
            g.text(font(), line, x, ly, DIM, false);
            ly += 10;
        }
        int carried = carried(), sx = rowX(), sy = rowY();
        var shard = new ItemStack(ModItems.DARK_SHARD);
        for (int i = 0; i < Balance.CLEANSE_MAX_SHARDS; i++) {
            int cx = sx + i * 20;
            boolean on = i < feed, missing = on && i >= carried;
            boolean hover = mx >= cx && mx < cx + 18 && my >= sy && my < sy + 18;
            g.fill(cx, sy, cx + 18, sy + 18, missing ? 0xFF7A2A2A : on ? CLEANSE_VIOLET : hover ? 0xFF5A4A70 : SLOT);
            g.fill(cx + 1, sy + 1, cx + 17, sy + 17, on ? 0xFF24183A : SLOT_IN);
            g.item(shard, cx + 1, sy + 1);
            if (!on) g.fill(cx + 1, sy + 1, cx + 17, sy + 17, 0xB0100C06);
        }
        int monsters = Cleanse.size(1, feed), items = Balance.cleanseItems(feed);
        int py = sy + 26;
        g.text(
                font(),
                "Feed " + feed + (feed == 1 ? " shard" : " shards") + " · you carry " + carried,
                x,
                py,
                carried >= feed ? IVORY : 0xFFFF7070,
                false);
        g.text(font(), "One wave of about " + monsters + " monsters", x, py + 12, IVORY, false);
        g.text(
                font(),
                feed >= Balance.CLEANSE_BOSS_SHARDS
                        ? "Then one boss answers for the dark"
                        : "No boss (" + Balance.CLEANSE_BOSS_SHARDS + " shards call one)",
                x,
                py + 24,
                feed >= Balance.CLEANSE_BOSS_SHARDS ? QUEST_RED : DIM,
                false);
        // Wrapped: at a large GUI scale the full cache line runs past the panel's edge.
        int cy = py + 36;
        for (var cacheLine : font().split(
                        Component.literal("Cache: " + items + " rolled items, a tome, "
                                + Balance.CLEANSE_SPARKS_PER_ITEM * items + " Sparks"
                                + (feed >= Balance.CLEANSE_LEGENDARY_SHARDS ? ", one Legendary" : "")),
                        s.gridW() - 12)) {
            g.text(font(), cacheLine, x, cy, GOLD, false);
            cy += 10;
        }
        int status = s.top() + s.h() - HELP - 34;
        String line = "";
        int color = DIM;
        if (cleanse != null && cleanse.left() >= 0) {
            line = "The Cleanse is under way: " + cleanse.left() + " left to fell";
            color = CLEANSE_VIOLET;
        } else if (cleanse != null && !cleanse.message().isEmpty()) {
            line = cleanse.message();
            color = 0xFFFF7070;
        } else if (cleanse != null && !cleanse.problem().isEmpty()) {
            line = cleanse.problem();
        }
        if (!line.isEmpty()) g.text(font(), font().plainSubstrByWidth(line, w), x, status, color, false);
    }

    @Override
    boolean click(double mx, double my, boolean doubleClick) {
        if (my < rowY() || my >= rowY() + 18 || mx < rowX() || mx >= rowX() + 20 * Balance.CLEANSE_MAX_SHARDS)
            return false;
        feed = (int) ((mx - rowX()) / 20) + 1;
        if (cleanse != null)
            cleanse = new Cleanse.Show(s.chest, cleanse.problem(), cleanse.left(), cleanse.total(), "");
        sound(SoundEvents.AMETHYST_BLOCK_CHIME, .6f + feed * .1f);
        return true;
    }

    @Override
    boolean scroll(double dy) {
        feed = Math.clamp(feed + (int) Math.signum(dy), 1, Balance.CLEANSE_MAX_SHARDS);
        return true;
    }
}
