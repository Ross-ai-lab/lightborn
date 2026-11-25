package lightborn.client;

import static lightborn.client.ChestStyle.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lightborn.ChestCrafting;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class CraftingTab extends ChestTab {
    private static final int BRASS = 0xFFC19648, EDGE = 0xFF574021, AMBER_INK = 0xFF130F0A;
    private static final int CELL = 19;
    private final List<String> grid = new ArrayList<>(Collections.nCopies(9, ""));
    private ChestCrafting.Show view;
    private EditBox search;
    private Button mode, previous, next, reset, craft, batch;
    private String query = "", recipe = "", held = "", status = "";
    private int page, delay;
    private boolean manual, pending;

    CraftingTab(CollectionScreen screen) {
        super(screen);
    }

    @Override
    void init() {
        search = s.add(new EditBox(font(), 0, 0, 80, 16, Component.literal("Search crafting")));
        search.setMaxLength(72);
        search.setHint(Component.literal("Search"));
        search.setValue(query);
        search.setResponder(text -> {
            query = text;
            page = 0;
            delay = 4;
        });
        mode = button("Manual grid", () -> {
            manual = !manual;
            page = 0;
            recipe = "";
            held = "";
            request(ChestCrafting.BROWSE);
        });
        previous = button("<", () -> pageBy(-1));
        next = button(">", () -> pageBy(1));
        reset = button("Clear", () -> {
            Collections.fill(grid, "");
            recipe = "";
            held = "";
            request(ChestCrafting.BROWSE);
        });
        craft = button("Craft", () -> request(ChestCrafting.CRAFT));
        batch = button("Craft all", () -> request(ChestCrafting.ASSEMBLE));
    }

    private Button button(String text, Runnable action) {
        return s.add(Button.builder(Component.literal(text), b -> action.run())
                .bounds(0, 0, 48, 16)
                .build());
    }

    @Override
    void opened() {
        request(ChestCrafting.BROWSE);
    }

    @Override
    void tick(int ticks) {
        if (delay > 0 && --delay == 0) request(ChestCrafting.BROWSE);
    }

    private void pageBy(int offset) {
        if (view == null || pending) return;
        int want = Math.clamp(page + offset, 0, view.pages() - 1);
        if (want != page) {
            page = want;
            request(ChestCrafting.BROWSE);
        }
    }

    private void request(int action) {
        if (action != ChestCrafting.BROWSE
                && (pending || view == null || view.output().isEmpty())) return;
        pending = true;
        delay = 0;
        ClientPlayNetworking.send(new ChestCrafting.Ask(
                s.chest,
                manual,
                query,
                action == ChestCrafting.BROWSE ? recipe : view.matched(),
                List.copyOf(grid),
                page,
                action));
    }

    void receive(ChestCrafting.Show response) {
        if (!s.chest.equals(response.chest())) return;
        view = response;
        page = view.page();
        if (!response.message().isEmpty()) status = response.message();
        pending = false;
    }

    private int left() {
        return s.gridX();
    }

    private int top() {
        return s.top() + HEAD;
    }

    private int right() {
        return left() + s.gridW();
    }

    private int divider() {
        return left() + Math.max(91, s.gridW() * 43 / 100);
    }

    private int foot() {
        return s.top() + s.h() - 30;
    }

    private int tilesX() {
        return left() + (divider() - left() - tileSize() * 4) / 2;
    }

    private int tilesY() {
        return top() + 32;
    }

    private int tileSize() {
        return Math.clamp(Math.min(divider() - left() - 10, foot() - tilesY() - 12) / 4, 21, 28);
    }

    private int gridX() {
        return divider() + Math.max(6, (right() - divider() - 122) / 2);
    }

    private int gridY() {
        return top() + Math.max(43, (foot() - top() - 56) / 2 + 7);
    }

    private int resultX() {
        return gridX() + 99;
    }

    @Override
    void layout(boolean on) {
        for (var button : List.of(mode, previous, next, reset, craft, batch)) button.visible = on;
        search.setVisible(on);
        search.active = on;
        if (!on) {
            if (search.isFocused()) search.setFocused(false);
            return;
        }
        search.setPosition(left() + 3, top() + 13);
        search.setWidth(divider() - left() - 7);
        mode.setPosition(divider() + 6, top() + 13);
        mode.setWidth(78);
        mode.setMessage(Component.literal(manual ? "Recipe list" : "Manual grid"));
        reset.visible = manual;
        reset.setPosition(right() - 40, top() + 13);
        reset.setWidth(36);
        previous.setPosition(left() + 5, foot() - 7);
        previous.setWidth(18);
        next.setPosition(divider() - 23, foot() - 7);
        next.setWidth(18);
        previous.active = view != null && !pending && page > 0;
        next.active = view != null && !pending && page + 1 < view.pages();
        int buttons = right() - divider() - 15;
        craft.setPosition(divider() + 7, foot() - 7);
        craft.setWidth(manual ? buttons : buttons / 2 - 2);
        batch.setPosition(divider() + 7 + buttons / 2, foot() - 7);
        batch.setWidth(buttons - buttons / 2);
        batch.visible = !manual;
        craft.active = batch.active = !pending
                && view != null
                && !view.output().isEmpty()
                && !view.matched().isEmpty();
    }

    private void centered(GuiGraphicsExtractor g, String text, int center, int y, int color) {
        g.text(font(), text, center - font().width(text) / 2, y, color, false);
    }

    private void plate(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active) {
        g.fill(x, y, x + w, y + h, active ? BRASS : EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, AMBER_INK);
        g.fill(x + 2, y + 2, x + w - 2, y + h - 3, 0xFF271F13);
        g.fill(x + 2, y + h - 3, x + w - 2, y + h - 1, 0xFF3F2D17);
    }

    private void resultPreview(GuiGraphicsExtractor g, ItemStack stack, int x, int y, float age) {
        var state = new ItemEntityRenderState();
        state.entityType = EntityTypes.ITEM;
        state.count = 1;
        state.ageInTicks = age;
        state.lightCoords = 0xF000F0;
        mc().getItemModelResolver().updateForTopItem(state.item, stack, ItemDisplayContext.GROUND, mc().level, null, 0);
        g.entity(
                state,
                96f,
                new Vector3f(0, .31f, 0),
                new Quaternionf().rotationZ((float) Math.PI).rotateX(-.25f),
                null,
                x - 26,
                y - 36,
                x + 26,
                y + 15);
    }

    private static void glow(GuiGraphicsExtractor g, int x, int y, float pulse) {
        for (int dy = -18; dy <= 18; dy++) {
            int width = (int) (28 * Math.sqrt(1 - dy * dy / 361.0));
            if (width < 2) continue;
            int alpha = (int) ((30 - Math.abs(dy)) * pulse);
            g.fill(x - width, y + dy, x + width, y + dy + 1, alpha << 24 | 0xEAB34D);
            g.fill(x - width / 2, y + dy, x + width / 2, y + dy + 1, alpha << 24 | 0xFFD982);
        }
    }

    private void craftingArrow(GuiGraphicsExtractor g, int tip, int y) {
        g.fill(gridX() + 60, y, tip + 1, y + 1, BRASS);
        for (int offset = 1; offset <= 4; offset++) {
            g.fill(tip - offset, y - offset, tip - offset + 1, y - offset + 1, GOLD);
            g.fill(tip - offset, y + offset, tip - offset + 1, y + offset + 1, GOLD);
        }
    }

    @Override
    void draw(GuiGraphicsExtractor g, int mx, int my) {
        int x = left(), y = top(), d = divider(), end = right(), bottom = foot();
        g.fill(x, y + 10, end, bottom + 13, EDGE);
        g.fill(x + 1, y + 11, end - 1, bottom + 12, AMBER_INK);
        g.fill(d, y + 11, d + 1, bottom + 12, EDGE);
        g.text(font(), manual ? "Ingredients" : "Craftable recipes", x + 3, y, GOLD, true);
        g.text(font(), "Crafting grid", d + 6, y, GOLD, true);
        if (view == null) return;

        var entries = view.entries();
        var hovered = ItemStack.EMPTY;
        int tile = tileSize();
        for (int i = 0; i < ChestCrafting.PAGE_SIZE; i++) {
            int tx = tilesX() + i % 4 * tile, ty = tilesY() + i / 4 * tile;
            boolean over = mx >= tx && mx < tx + tile - 2 && my >= ty && my < ty + tile - 2;
            var entry = i < entries.size() ? entries.get(i) : null;
            boolean selected = entry != null && entry.key().equals(manual ? held : recipe);
            plate(g, tx, ty, tile - 2, tile - 2, over || selected);
            if (entry != null) {
                g.pose().pushMatrix();
                g.pose().translate(tx + 2, ty + 2);
                float iconScale = (tile - 6) / 16f;
                g.pose().scale(iconScale, iconScale);
                g.item(entry.stack(), 0, 0);
                g.pose().popMatrix();
                if (manual && entry.stack().getCount() > 1) {
                    String count = Integer.toString(entry.stack().getCount());
                    g.text(font(), count, tx + tile - 3 - font().width(count), ty + tile - 11, IVORY, true);
                }
                if (over && !pending) hovered = entry.stack();
            }
        }
        if (entries.isEmpty()) {
            g.fill(x + 3, tilesY() + 32, d - 3, tilesY() + 50, AMBER_INK);
            centered(g, manual ? "No ingredients" : "No recipes found", (x + d) / 2, tilesY() + 37, DIM);
        }
        centered(g, (page + 1) + " / " + view.pages(), (x + d) / 2, bottom - 3, DIM);
        for (int i = 0; i < 9; i++) {
            int gx = gridX() + i % 3 * CELL, gy = gridY() + i / 3 * CELL;
            plate(g, gx, gy, CELL - 1, CELL - 1, manual && !held.isEmpty());
            if (i >= view.grid().size()) continue;
            var stack = view.grid().get(i);
            if (!stack.isEmpty()) g.item(stack, gx + 1, gy + 1);
            if (mx >= gx && mx < gx + CELL && my >= gy && my < gy + CELL && !stack.isEmpty()) hovered = stack;
        }
        int rx = resultX(), ry = gridY() + 27;
        craftingArrow(g, rx - 27, ry);
        boolean ready = !view.output().isEmpty() && !pending;
        float age = (float) ((System.nanoTime() / 1_000_000_000d % (Math.PI * 2)) * 20);
        if (ready) glow(g, rx, ry - 8, .85f + .15f * (float) Math.sin(age * .1f));
        // A staggered, lit plinth keeps block models and tools floating apart from the working grid.
        for (int i = 0; i < 7; i++) {
            int half = 12 + i * 2;
            g.fill(rx - half, ry + 10 + i, rx + half, ry + 11 + i, ready ? 0xFF624824 + i * 0x00050603 : EDGE);
        }
        g.fill(rx - 24, ry + 17, rx + 24, ry + 20, ready ? BRASS : EDGE);
        g.fill(rx - 24, ry + 20, rx + 24, ry + 23, 0xFF332518);
        if (ready) {
            resultPreview(g, view.output(), rx, ry, age);
            centered(g, "×" + view.output().getCount(), rx, ry + 25, GOLD);
            int labelWidth = 2 * Math.min(rx - divider() - 4, right() - rx - 4);
            centered(
                    g,
                    font().plainSubstrByWidth(view.output().getHoverName().getString(), labelWidth),
                    rx,
                    ry + 37,
                    IVORY);
            if (mx >= rx - 23 && mx <= rx + 23 && my >= ry - 23 && my <= ry + 25) hovered = view.output();
        }
        String hint = status.isEmpty()
                ? manual ? "Pick an ingredient, then click a cell." : "Uses the Chest of Light and your inventory."
                : status;
        g.text(font(), font().plainSubstrByWidth(hint, end - x - 8), x + 3, bottom + 16, DIM, false);
        if (!hovered.isEmpty()) g.setTooltipForNextFrame(font(), hovered, mx, my);
    }

    @Override
    boolean click(double mx, double my, boolean doubleClick) {
        if (view == null || pending) return false;
        int ix = (int) (mx - tilesX()), iy = (int) (my - tilesY());
        int tile = tileSize();
        if (mx >= tilesX() && my >= tilesY() && ix < 4 * tile && iy < 4 * tile) {
            int i = ix / tile + iy / tile * 4;
            if (i >= view.entries().size()) return false;
            var entry = view.entries().get(i);
            status = "";
            if (manual) held = held.equals(entry.key()) ? "" : entry.key();
            else {
                recipe = entry.key();
                request(ChestCrafting.BROWSE);
            }
            return true;
        }
        int gx = (int) (mx - gridX()), gy = (int) (my - gridY());
        if (manual && mx >= gridX() && my >= gridY() && gx < 3 * CELL && gy < 3 * CELL) {
            int i = gx / CELL + gy / CELL * 3;
            grid.set(i, grid.get(i).equals(held) ? "" : held);
            recipe = "";
            status = "";
            request(ChestCrafting.BROWSE);
            return true;
        }
        return false;
    }

    void preview(String key) {
        recipe = key;
        request(ChestCrafting.BROWSE);
    }

    boolean canCraft() {
        return view != null && !pending && !view.output().isEmpty();
    }

    @Override
    boolean scroll(double dy) {
        if (dy == 0) return false;
        pageBy(dy > 0 ? -1 : 1);
        return true;
    }
}
