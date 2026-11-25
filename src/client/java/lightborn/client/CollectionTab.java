package lightborn.client;

import static lightborn.client.ChestStyle.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lightborn.Balance;
import lightborn.Collection;
import lightborn.Collection.Category;
import lightborn.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

/**
 * The Collection: the ten sets in one tab (owner, 2026-11-17: a mega tab), a strip of set chips along the top, the
 * open set's items lit once given and dimmed until then, with the count the chest keeps. Click an item to see it in
 * the panel below (take it out, put more in, how it is made or where it is found); double-click it to take it all.
 */
final class CollectionTab extends ChestTab {
    /** Set chips: 40% bigger, then 10% more (owner, 2026-11-17). */
    private static final float CHIP_SCALE = .924f;

    private static final int CHIP_H = 14, CHIP_GAP = 3, SEARCH_W = 52;

    /** Recipes the server sent, by item id (empty list = none), and where a recipe-less item comes from. */
    private static final Map<String, List<RecipeDisplay>> RECIPES = new HashMap<>();

    private static final Map<String, Component> FOUND = new HashMap<>();

    /** The open set (an index into the sets). */
    int set;

    private int scroll;
    private String picked;
    private int recipeIndex;
    private Button takeOne, takeAll, putIn, giveNew, giveNonEssential, depositSparks;

    private Button searchButton;
    private EditBox searchBox;
    private List<String> searchResults = List.of();
    private boolean searching, focusSearch;

    CollectionTab(CollectionScreen s) {
        super(s);
    }

    static void receive(Net.RecipeShow show) {
        RECIPES.put(show.item(), show.recipes());
        FOUND.put(show.item(), show.found());
    }

    @Override
    void init() {
        String query = searchBox == null ? "" : searchBox.getValue();
        int searchY = s.top() + HEAD;
        int right = s.gridX() + s.gridW() - 4;
        searchButton = s.add(Button.builder(Component.literal("Search"), b -> toggleSearch())
                .bounds(right - SEARCH_W, searchY, SEARCH_W, CHIP_H)
                .build());
        searchBox = s.add(new EditBox(
                font(),
                s.gridX(),
                searchY,
                right - SEARCH_W - 5 - s.gridX(),
                CHIP_H,
                Component.literal("Search collection")));
        searchBox.setHint(Component.literal("Search all items"));
        searchBox.setMaxLength(80);
        searchBox.setValue(query);
        searchBox.setResponder(this::search);
        // The three buttons share the room left of the recipe's divider, so they never run into it on narrow screens.
        int bx = s.gridX() + 42, by = s.detailY() + 26;
        int room = s.gridW() - RECIPE_W - 52;
        int bw = Math.max(30, Math.min(BTN_W, (room - 6) / 3));
        takeOne = s.add(Button.builder(Component.literal("Take 1"), b -> move(Net.ChestMove.TAKE_ONE))
                .bounds(bx, by, bw, ITEM_BTN_H)
                .build());
        takeAll = s.add(Button.builder(Component.literal("Take all"), b -> move(Net.ChestMove.TAKE_ALL))
                .bounds(bx + bw + 3, by, bw, ITEM_BTN_H)
                .build());
        putIn = s.add(Button.builder(Component.literal("Put in"), b -> move(Net.ChestMove.PUT_IN))
                .bounds(bx + 2 * (bw + 3), by, bw, ITEM_BTN_H)
                .build());
        int footerRight = s.gridX() + s.gridW() - 4, footerY = s.top() + s.h() - HELP + 3;
        giveNew = s.add(new DonationButton(
                font(),
                "Give all new",
                footerRight,
                footerY,
                b -> ClientPlayNetworking.send(new Net.ChestMove(s.chest, "", Net.ChestMove.GIVE_NEW))));
        giveNonEssential = s.add(new DonationButton(
                font(),
                "Give all non-essential items",
                giveNew.getX() - 4,
                footerY,
                b -> ClientPlayNetworking.send(new Net.ChestMove(s.chest, "", Net.ChestMove.GIVE_NON_ESSENTIAL))));
        depositSparks = s.add(new DonationButton(
                font(),
                "Deposit Sparks",
                giveNonEssential.getX() - 4,
                footerY,
                b -> ClientPlayNetworking.send(new Net.ChestMove(s.chest, "", Net.ChestMove.DEPOSIT_SPARKS))));
        giveNonEssential.setTooltip(Tooltip.create(
                Component.literal("Store every non-essential collectible that fits, including past gifts.")));
    }

    /** A compact vanilla button for the footer, with a scaled label. */
    static final class DonationButton extends Button {
        private static final float LABEL_SCALE = .75f;
        private final Font font;

        DonationButton(Font font, String label, int right, int y, OnPress onPress) {
            super(
                    0,
                    y,
                    (int) Math.ceil(font.width(label) * LABEL_SCALE) + 8,
                    9,
                    Component.literal(label),
                    onPress,
                    DEFAULT_NARRATION);
            this.font = font;
            setX(right - getWidth());
        }

        int labelWidth() {
            return (int) Math.ceil(font.width(getMessage()) * LABEL_SCALE);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            extractDefaultSprite(g);
            g.pose().pushMatrix();
            g.pose()
                    .translate(
                            getX() + (getWidth() - font.width(getMessage()) * LABEL_SCALE) / 2,
                            getY() + (getHeight() - font.lineHeight * LABEL_SCALE) / 2);
            g.pose().scale(LABEL_SCALE, LABEL_SCALE);
            g.text(font, getMessage(), 0, 0, active ? 0xFFFFFFFF : 0xFFA0A0A0, true);
            g.pose().popMatrix();
        }
    }

    private void move(int action) {
        if (picked != null) ClientPlayNetworking.send(new Net.ChestMove(s.chest, picked, action));
    }

    /** Opens one item in the panel and asks the server how it is made. */
    void pick(String id) {
        picked = id;
        recipeIndex = 0;
        if (!RECIPES.containsKey(id)) ClientPlayNetworking.send(new Net.RecipeAsk(id));
    }

    /** Shows a set from the top. */
    void show(int index) {
        set = index;
        scroll = 0;
        picked = null;
    }

    boolean closeSearch() {
        if (!searching) return false;
        toggleSearch();
        return true;
    }

    private void toggleSearch() {
        searching = !searching;
        if (searching) {
            search(searchBox.getValue());
            focusSearch = true;
        } else {
            focusSearch = false;
            searchBox.setFocused(false);
            searchBox.setValue("");
            scroll = 0;
            picked = null;
        }
    }

    private void search(String text) {
        String query = text.strip().toLowerCase(java.util.Locale.ROOT);
        searchResults = query.isEmpty()
                ? Collection.all()
                : Collection.all().stream()
                        .filter(id -> id.contains(query)
                                || new ItemStack(Collection.item(id))
                                        .getHoverName()
                                        .getString()
                                        .toLowerCase(java.util.Locale.ROOT)
                                        .contains(query))
                        .toList();
        scroll = 0;
        picked = null;
    }

    @Override
    void tick(int ticks) {
        if (focusSearch) {
            focusSearch = false;
            s.setFocused(searchBox);
        }
    }

    private List<String> shownItems() {
        return searching ? searchResults : category().items();
    }

    private Category category() {
        var cats = Collection.categories();
        return cats.get(Math.clamp(set, 0, cats.size() - 1));
    }

    // ---------------------------------------------------------------- layout

    private int cols() {
        return Math.max(1, (s.gridW() - 6) / CELL);
    }

    private int visibleRows() {
        return Math.max(1, (s.h() - HEAD - stripH() - DETAIL - HELP - 6) / CELL);
    }

    /** Top of a set's item grid, under the strip of set chips. */
    private int gridTop() {
        return s.top() + HEAD + stripH();
    }

    /** The set chips laid out left to right, wrapping: x, y and width of each, in set order. */
    private List<int[]> chips() {
        List<int[]> out = new ArrayList<>();
        int x = s.gridX(), y = s.top() + HEAD, right = s.gridX() + s.gridW() - 4;
        for (Category c : Collection.categories()) {
            int w = Math.round((4 + 10 + 3 + font().width(c.name()) + 4 + font().width(chipCount(c)) + 5) * CHIP_SCALE);
            int rowRight = y == s.top() + HEAD ? right - SEARCH_W - CHIP_GAP : right;
            if (x + w > rowRight && x > s.gridX()) {
                x = s.gridX();
                y += CHIP_H + CHIP_GAP;
            }
            out.add(new int[] {x, y, w});
            x += w + CHIP_GAP;
        }
        return out;
    }

    private int stripH() {
        if (searching) return CHIP_H + 5;
        var chips = chips();
        return chips.isEmpty() ? 0 : chips.getLast()[1] - (s.top() + HEAD) + CHIP_H + 5;
    }

    private String chipCount(Category c) {
        int n = Collection.count(Collection.collected(mc().player), c),
                all = c.items().size();
        return n == all && all > 0 ? "✔" : n + "/" + all;
    }

    private int chipAt(double mx, double my) {
        var chips = chips();
        for (int i = 0; i < chips.size(); i++) {
            int[] r = chips.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + CHIP_H) return i;
        }
        return -1;
    }

    private int maxScroll() {
        if (Collection.categories().isEmpty()) return 0;
        int rows = (shownItems().size() + cols() - 1) / cols();
        return Math.max(0, rows - visibleRows());
    }

    /** The recipe pager's line in the item panel. */
    private int pagerY() {
        return s.detailY() + DETAIL - 12;
    }

    @Override
    int[] bar() {
        return maxScroll() > 0 ? new int[] {gridTop(), visibleRows() * CELL, visibleRows(), scroll, maxScroll()} : null;
    }

    @Override
    void scrollTo(int at) {
        scroll = at;
    }

    // ---------------------------------------------------------------- drawing

    /** Take buttons need something stored; Put in needs some of it carried. */
    @Override
    void layout(boolean on) {
        searchButton.visible = on;
        searchButton.setMessage(Component.literal(searching ? "Close" : "Search"));
        searchBox.setVisible(on && searching);
        searchBox.active = on && searching;
        if (!on && searchBox.isFocused()) searchBox.setFocused(false);
        boolean show = on && picked != null && mc().player != null;
        int kept = show ? Collection.stored(mc().player, picked).getCount() : 0;
        for (Button b : List.of(takeOne, takeAll, putIn)) {
            b.visible = show;
            b.setY(s.detailY() + 26); // under the name and count, inside the short panel
        }
        giveNew.visible = giveNonEssential.visible = depositSparks.visible = on;
        depositSparks.active = on
                && mc().player != null
                && Collection.canDeposit(mc().player, Collection.idOf(lightborn.ModItems.SPARK));
        takeOne.active = takeAll.active = kept > 0;
        putIn.active = show && Collection.canDeposit(mc().player, picked);
        giveNew.active = giveNonEssential.active = false;
        if (on && mc().player != null) {
            var inventory = mc().player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                giveNew.active |= Collection.newForLight(mc().player, stack);
                giveNonEssential.active |= Collection.canGiveNonEssential(mc().player, stack);
                if (giveNew.active && giveNonEssential.active) break;
            }
        }
    }

    /** The chips: the open set lit gold, a finished one's name in gold, the rest ivory. */
    private void drawChips(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var cats = Collection.categories();
        var chips = chips();
        for (int i = 0; i < chips.size(); i++) {
            int[] r = chips.get(i);
            Category c = cats.get(i);
            boolean on = i == set;
            boolean hover = mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + CHIP_H;
            String count = chipCount(c);
            boolean done = count.equals("✔");
            g.fill(r[0], r[1], r[0] + r[2], r[1] + CHIP_H, on ? GOLD : hover ? 0xFF6A5420 : 0xFF3A3020);
            g.fill(r[0] + 1, r[1] + 1, r[0] + r[2] - 1, r[1] + CHIP_H - 1, on ? 0xFF3A2C10 : 0xFF17130C);
            // Drawn at full size inside a scaled frame, so text and icon shrink together.
            g.pose().pushMatrix();
            g.pose().translate(r[0], r[1]);
            g.pose().scale(CHIP_SCALE, CHIP_SCALE);
            g.pose().pushMatrix();
            g.pose().translate(4, 2.5f);
            g.pose().scale(.625f, .625f);
            g.item(c.icon(), 0, 0);
            g.pose().popMatrix();
            int tx = 4 + 10 + 3;
            g.text(font(), c.name(), tx, 4, on ? 0xFFFFF0C0 : done ? GOLD : 0xFFC8BEA4, false);
            g.text(font(), count, tx + font().width(c.name()) + 4, 4, on ? GOLD : DIM, false);
            g.pose().popMatrix();
        }
        int below = gridTop() - 3;
        g.fill(s.gridX(), below, s.gridX() + s.gridW() - 4, below + 1, 0xFF3A3020);
    }

    private String hovered;
    private ItemStack recipeHover = ItemStack.EMPTY;

    @Override
    void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Set<String> got = Collection.collected(mc().player);
        if (!searching) drawChips(g, mouseX, mouseY);
        String hint = "Ctrl + right-click to give one";
        int hintX = Math.clamp(depositSparks.getX() - 110, s.left() + 8, s.gridX() + 1);
        float hintScale = Math.min(.75f, (depositSparks.getX() - hintX - 5) / (float) font().width(hint));
        if (hintScale > 0) {
            g.pose().pushMatrix();
            g.pose().translate(hintX, s.top() + s.h() - HELP + 4);
            g.pose().scale(hintScale, hintScale);
            g.text(font(), hint, 0, 0, DIM, false);
            g.pose().popMatrix();
        }

        // The open set's items, scrolled, each with the count the chest keeps.
        int gx = s.gridX(), gy = gridTop(), cols = cols(), rows = visibleRows();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        hovered = null;
        var items = shownItems();
        if (items.isEmpty()) g.text(font(), "No matching items", gx + 4, gy + 4, DIM, false);
        for (int i = scroll * cols; i < items.size() && i < (scroll + rows) * cols; i++) {
            String id = items.get(i);
            int x = gx + (i % cols) * CELL, y = gy + (i / cols - scroll) * CELL;
            boolean has = got.contains(id);
            boolean pick = id.equals(picked);
            g.fill(x, y, x + 17, y + 17, pick ? GOLD : has ? 0xFF6A5420 : SLOT);
            g.fill(x + 1, y + 1, x + 16, y + 16, has ? 0xFF2C2210 : SLOT_IN);
            g.item(new ItemStack(Collection.item(id)), x, y);
            if (!has) g.fill(x + 1, y + 1, x + 16, y + 16, 0xC0100C06);
            ItemStack kept = Collection.stored(mc().player, id);
            if (!kept.isEmpty()) g.itemDecorations(font(), kept, x, y);
            if (mouseX >= x && mouseX < x + 17 && mouseY >= y && mouseY < y + 17) hovered = id;
        }
        if (maxScroll() > 0) s.drawBar(g, gy, rows * CELL, rows, scroll, maxScroll());
        recipeHover = detail(g, got, mouseX, mouseY);
    }

    @Override
    void drawOver(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (hovered != null) {
            Set<String> got = Collection.collected(mc().player);
            List<Component> lines = new ArrayList<>();
            lines.add(new ItemStack(Collection.item(hovered)).getHoverName());
            lines.add(Component.literal(got.contains(hovered) ? "Given" : "New for the Light")
                    .withStyle(ChatFormatting.GOLD));
            int kept = Collection.stored(mc().player, hovered).getCount();
            if (kept > 0) lines.add(Component.literal("In the chest: " + kept).withStyle(ChatFormatting.YELLOW));
            lines.add(Component.literal(kept > 0 ? "Click to see it · double-click to take it" : "Click to see it")
                    .withStyle(ChatFormatting.DARK_GRAY));
            g.setComponentTooltipForNextFrame(font(), lines, mouseX, mouseY);
        } else if (!recipeHover.isEmpty()) {
            g.setTooltipForNextFrame(font(), recipeHover, mouseX, mouseY);
        }
    }

    private ItemStack detail(GuiGraphicsExtractor g, Set<String> got, int mouseX, int mouseY) {
        int px = s.gridX(), py = s.detailY(), pw = s.gridW(), ph = DETAIL;
        g.fill(px, py, px + pw, py + ph, 0xFF0E0B06);
        g.fill(px, py, px + pw, py + 1, 0xFF3A3020);
        if (picked == null) {
            if (searching) {
                int matches = shownItems().size();
                if (matches > 0)
                    g.text(font(), matches == 1 ? "1 match" : matches + " matches", px + 4, py + 5, DIM, false);
            } else milestones(g, got, px, py, pw, ph);
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(Collection.item(picked));
        boolean has = got.contains(picked);
        // The same short panel as the milestones (owner, 2026-11-17: they must match): big icon; name; given or not
        // with the chest's count; the three buttons under them; the recipe in small slots at the right.
        g.pose().pushMatrix();
        g.pose().translate(px + 5, py + 5);
        g.pose().scale(2f, 2f);
        g.item(stack, 0, 0);
        g.pose().popMatrix();
        int tx = px + 42;
        int nameRoom = pw - RECIPE_W - 50;
        g.text(font(), font().plainSubstrByWidth(stack.getHoverName().getString(), nameRoom), tx, py + 4, IVORY, false);
        String status = has ? "Given" : "Not yet given";
        int kept = Collection.stored(mc().player, picked).getCount();
        String count = "  ·  " + kept + " / " + Collection.STORAGE_LIMIT + " kept";
        g.text(font(), status, tx, py + 14, has ? GOLD : DIM, false);
        g.text(
                font(),
                font().plainSubstrByWidth(count, Math.max(0, nameRoom - font().width(status))),
                tx + font().width(status),
                py + 14,
                kept > 0 ? 0xFFFFE9A0 : DIM,
                false);

        // How it is made.
        int rx = px + pw - RECIPE_W - 4;
        g.fill(rx - 5, py + 4, rx - 4, py + ph - 4, 0xFF3A3020);
        List<RecipeDisplay> list = RECIPES.get(picked);
        if (list == null) {
            g.text(font(), "Asking the Light…", rx, py + 16, DIM, false);
            return ItemStack.EMPTY;
        }
        if (list.isEmpty()) {
            found(g, rx, py, ph);
            return ItemStack.EMPTY;
        }
        return recipe(g, list, rx, py, mouseX, mouseY);
    }

    /**
     * Where a recipe-less item comes from (owner, 2026-11-18: dropped by monsters, by bosses, and so on), in the
     * recipe's place: full size when it fits in three lines, else three quarters.
     */
    private void found(GuiGraphicsExtractor g, int rx, int py, int ph) {
        Component text = FOUND.getOrDefault(picked, Component.empty());
        if (text.getString().isEmpty()) text = Component.literal("Not made by hand: found in the world");
        var lines = font().split(text, RECIPE_W);
        if (lines.size() <= 3) {
            int ly = py + (ph - lines.size() * 10) / 2 + 1;
            for (var line : lines) {
                g.text(font(), line, rx, ly, DIM, false);
                ly += 10;
            }
            return;
        }
        // Shrinks until the whole hint fits the panel (owner, 2026-11-19: a long one was cut at three quarters size),
        // never below half size.
        float k = .75f;
        lines = font().split(text, (int) (RECIPE_W / k));
        while (k > .5f && lines.size() * 9 * k > ph - 4) {
            k -= .05f;
            lines = font().split(text, (int) (RECIPE_W / k));
        }
        int shown = Math.min(lines.size(), (int) ((ph - 4) / (9 * k)));
        g.pose().pushMatrix();
        g.pose().translate(rx, py + (ph - shown * 9 * k) / 2 + 1);
        g.pose().scale(k, k);
        for (int i = 0; i < shown; i++) g.text(font(), lines.get(i), 0, i * 9, DIM, false);
        g.pose().popMatrix();
    }

    private void milestones(GuiGraphicsExtractor g, Set<String> got, int px, int py, int pw, int ph) {
        var category = category();
        int earned = Collection.quarters(got, category);
        int next = Math.min(Balance.COLLECTION_QUARTERS, earned + 1);
        int needed = Math.max(
                0,
                (category.items().size() * next + Balance.COLLECTION_QUARTERS - 1) / Balance.COLLECTION_QUARTERS
                        - Collection.count(got, category));
        g.text(
                font(),
                earned == 4 ? "Set complete" : "Next milestone: " + next * 25 + "%  ·  " + needed + " more",
                px + 5,
                py + 5,
                GOLD,
                false);
        // The four quarter marks on the same line, at the right, lit as they are earned.
        for (int i = 4; i >= 1; i--) {
            String mark = i * 25 + "%";
            int mx = px + pw - 5 - (4 - i + 1) * 30 + (30 - font().width(mark));
            g.text(font(), mark, mx, py + 5, i <= earned ? GOLD : DIM, false);
        }
        int lineY = py + 17;
        Component gain = category.reward().effects().isEmpty()
                ? Component.literal("Each new gift pays a Spark. No stat bonus.")
                : earned == 4
                        ? Component.literal("Earned: ").append(Collection.rewardAmount(category, 4))
                        : Component.literal("Gain: ").append(Collection.rewardAmount(category, 1));
        if (!category.reward().effects().isEmpty() && earned > 0 && earned < 4)
            gain = gain.copy()
                    .append("  ·  afterward ")
                    .append(Collection.rewardAmount(category, next))
                    .append(" total");
        for (var line : font().split(gain, pw - 10)) {
            if (lineY + 9 > py + ph) break;
            g.text(font(), line, px + 5, lineY, IVORY, false);
            lineY += 10;
        }
    }

    @FunctionalInterface
    private interface SlotDrawer {
        void draw(SlotDisplay sd, int x, int y);
    }

    /** The recipe in small slots, its kind beside it and its pager under that; returns the slot item under the mouse. */
    private ItemStack recipe(GuiGraphicsExtractor g, List<RecipeDisplay> list, int rx, int py, int mouseX, int mouseY) {
        recipeIndex = Math.floorMod(recipeIndex, list.size());
        RecipeDisplay d = list.get(recipeIndex);
        ContextMap ctx = SlotDisplayContext.fromLevel(mc().level);
        ItemStack[] hover = {ItemStack.EMPTY};
        int c = RECIPE_CELL;
        SlotDrawer slot = (sd, x, y) -> {
            g.fill(x, y, x + c, y + c, SLOT);
            g.fill(x + 1, y + 1, x + c - 1, y + c - 1, SLOT_IN);
            ItemStack is = cycle(sd, ctx);
            if (is.isEmpty()) return;
            g.pose().pushMatrix();
            g.pose().translate(x + .5f, y + .5f);
            g.pose().scale(.75f, .75f);
            g.item(is, 0, 0);
            g.itemDecorations(font(), is, 0, 0);
            g.pose().popMatrix();
            if (mouseX >= x && mouseX < x + c && mouseY >= y && mouseY < y + c) hover[0] = is;
        };
        String kind;
        int gy = py + 1;
        int arrowX = rx + 3 * c + 3, outX = rx + 3 * c + 13;
        switch (d) {
            case ShapedCraftingRecipeDisplay sh -> {
                kind = "Crafting";
                for (int y = 0; y < 3; y++)
                    for (int x = 0; x < 3; x++) {
                        SlotDisplay sd = x < sh.width() && y < sh.height()
                                ? sh.ingredients().get(y * sh.width() + x)
                                : null;
                        slot.draw(sd, rx + x * c, gy + y * c);
                    }
            }
            case ShapelessCraftingRecipeDisplay sl -> {
                kind = sl.craftingStation().resolveForFirstStack(ctx).is(Items.CRAFTING_TABLE)
                        ? "Crafting, any order"
                        : stationName(sl.craftingStation(), ctx, "Crafting");
                for (int i = 0; i < 9; i++)
                    slot.draw(
                            i < sl.ingredients().size() ? sl.ingredients().get(i) : null,
                            rx + (i % 3) * c,
                            gy + (i / 3) * c);
            }
            case FurnaceRecipeDisplay f -> {
                kind = stationName(f.craftingStation(), ctx, "Smelting");
                slot.draw(f.ingredient(), rx + c, gy);
                g.pose().pushMatrix();
                g.pose().translate(rx + c + 3.5f, gy + c + 1.5f);
                g.pose().scale(.7f, .7f);
                flame(g, 0, 0);
                g.pose().popMatrix();
                slot.draw(f.fuel(), rx + c, gy + 2 * c);
            }
            case StonecutterRecipeDisplay st -> {
                kind = "Stonecutter";
                slot.draw(st.input(), rx + c, gy + c);
            }
            case SmithingRecipeDisplay sm -> {
                kind = "Smithing";
                slot.draw(sm.template(), rx, gy + c);
                slot.draw(sm.base(), rx + c, gy + c);
                slot.draw(sm.addition(), rx + 2 * c, gy + c);
            }
            default -> kind = "";
        }
        g.text(font(), "→", arrowX, gy + c + 3, GOLD, false);
        slot.draw(d.result(), outX, gy + c);
        // The recipe's kind beside it, and its pager under that when it has more than one.
        int kx = outX + c + 5, kw = rx + RECIPE_W - kx, ky = py + 4;
        for (var line : font().split(Component.literal(kind), kw)) {
            if (ky > py + 14) break;
            g.text(font(), line, kx, ky, IVORY, false);
            ky += 10;
        }
        if (list.size() > 1) {
            String pg = "◀ " + (recipeIndex + 1) + "/" + list.size() + " ▶";
            g.text(font(), pg, rx + RECIPE_W - font().width(pg), pagerY(), GOLD, false);
        }
        return hover[0];
    }

    /** A small furnace flame between the ingredient and the fuel. */
    private static void flame(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x + 3, y, x + 5, y + 2, 0xFFFFE070);
        g.fill(x + 2, y + 2, x + 6, y + 5, 0xFFFFB030);
        g.fill(x + 1, y + 5, x + 7, y + 9, 0xFFFF7020);
        g.fill(x + 3, y + 5, x + 5, y + 8, 0xFFFFE070);
    }

    /** One of a slot's possible items, changing every second (any plank, any coal...). */
    private static ItemStack cycle(SlotDisplay sd, ContextMap ctx) {
        if (sd == null) return ItemStack.EMPTY;
        List<ItemStack> all = sd.resolveForStacks(ctx);
        if (all.isEmpty()) return ItemStack.EMPTY;
        return all.get((int) ((ClientState.tick / 20) % all.size()));
    }

    private static String stationName(SlotDisplay station, ContextMap ctx, String fallback) {
        ItemStack st = station.resolveForFirstStack(ctx);
        if (st.isEmpty()) return fallback;
        if (st.is(Items.CAMPFIRE) || st.is(Items.SOUL_CAMPFIRE)) return "Campfire";
        return st.getHoverName().getString();
    }

    // ---------------------------------------------------------------- input

    @Override
    boolean click(double mx, double my, boolean doubleClick) {
        var cats = Collection.categories();
        if (cats.isEmpty()) return false;
        int chip = searching ? -1 : chipAt(mx, my);
        if (chip >= 0) {
            if (chip != set) {
                s.select(chip);
                sound(SoundEvents.BOOK_PAGE_TURN, 1f);
            }
            return true;
        }
        var items = shownItems();
        int gx = s.gridX(), gy = gridTop(), cols = cols();
        if (mx >= gx && mx < gx + cols * CELL && my >= gy && my < gy + visibleRows() * CELL) {
            int i = (scroll + (int) (my - gy) / CELL) * cols + (int) (mx - gx) / CELL;
            if (i < items.size()) {
                String id = items.get(i);
                // A double-click takes what the chest keeps of it into the bag (owner, 2026-11-18).
                if (doubleClick && id.equals(picked)) {
                    move(Net.ChestMove.TAKE_ALL);
                    sound(SoundEvents.ITEM_PICKUP, 1f);
                } else pick(id);
                return true;
            }
        }
        // The recipe page arrows: the right half of the recipe header flips forward, the left half back.
        List<RecipeDisplay> list = picked == null ? null : RECIPES.get(picked);
        int rx = s.gridX() + s.gridW() - RECIPE_W - 4, ry = pagerY();
        if (list != null
                && list.size() > 1
                && my >= ry - 2
                && my < ry + 10
                && mx >= rx + RECIPE_W / 2
                && mx < rx + RECIPE_W) {
            String pg = "◀ " + (recipeIndex + 1) + "/" + list.size() + " ▶";
            int start = rx + RECIPE_W - font().width(pg);
            if (mx >= start) {
                recipeIndex += mx < start + font().width(pg) / 2 ? -1 : 1;
                return true;
            }
        }
        return false;
    }

    @Override
    boolean scroll(double dy) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(dy) * 2));
        return true;
    }
}
