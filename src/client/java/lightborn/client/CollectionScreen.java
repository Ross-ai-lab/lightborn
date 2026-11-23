package lightborn.client;

import static lightborn.client.ChestStyle.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import lightborn.ChestTabs;
import lightborn.ChestTravel;
import lightborn.Cleanse;
import lightborn.Collection;
import lightborn.LightShop;
import lightborn.ModItems;
import lightborn.Net;
import lightborn.Souls;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * The Chest of Light: a column of tabs at the left and the open tab beside it. This class keeps the frame, the tab
 * column, the shared scrollbar and the Unlocked window; each tab is its own class ({@link ChestTab}): Upgrade Spells
 * and Upgrade Gear ({@link UpgradeTab}), {@link CleanseTab}, {@link ShopTab}, {@link TravelTab}, {@link SpiritsTab} and
 * the {@link CollectionTab}.
 * <p>
 * Some tabs appear only as the game reaches them (owner, 2026-11-18; {@link ChestTabs}): the Shop after a night
 * survived, the Cleanse after a Dark Fountain is beaten, Travel after eight, Spirits once the Book of Souls is found.
 */
public class CollectionScreen extends Screen {
    /** One fixed tab: its code, name, colour and whether this player has it yet. */
    private record Fixed(int code, String name, int color, Predicate<Player> open) {}

    /**
     * The fixed tabs above the Collection. The Dragon's story is told in his talks (owner, 2026-11-15: the Dragon Quest
     * tab is gone).
     */
    private static final List<Fixed> FIXED = List.of(
            new Fixed(UPGRADE_SPELLS, "Upgrade Spells", QUEST_RED, p -> true),
            new Fixed(UPGRADE_GEAR, "Upgrade Gear", LIGHT_GOLD, p -> true),
            new Fixed(CLEANSE, "Cleanse", CLEANSE_VIOLET, ChestTabs::cleanse),
            new Fixed(SHOP, "Shop", SHOP_BLUE, ChestTabs::shop),
            new Fixed(TRAVEL, "Travel", TRAVEL_GREEN, ChestTabs::travel),
            new Fixed(SPIRITS, "Spirits", SPIRIT_PURPLE, ChestTabs::spirits));

    final BlockPos chest;
    /** The shop's last snapshot: its wares, and the Sparks the upgrade trees price against. */
    LightShop.Show shop;

    private final UpgradeTab spells = new UpgradeTab(this, false), gear = new UpgradeTab(this, true);
    private final CleanseTab cleanse = new CleanseTab(this);
    private final ShopTab shopTab = new ShopTab(this);
    private final TravelTab travel = new TravelTab(this);
    private final SpiritsTab spirits = new SpiritsTab(this);
    private final CollectionTab collection = new CollectionTab(this);
    private final Map<Integer, ChestTab> fixedTabs = new LinkedHashMap<>();
    private final UnlockedWindow unlocked = new UnlockedWindow(this);

    /** The open tab: a fixed tab's code (negative), or a set's index in the Collection. */
    private int selected = Integer.MIN_VALUE;

    private int ticks;
    private boolean dragBar;
    /** Whether init has built the tabs' widgets (a test may select a tab before the screen is shown). */
    private boolean built;

    public CollectionScreen(BlockPos chest) {
        super(Component.literal("Chest of Light"));
        this.chest = chest;
        fixedTabs.put(UPGRADE_SPELLS, spells);
        fixedTabs.put(UPGRADE_GEAR, gear);
        fixedTabs.put(CLEANSE, cleanse);
        fixedTabs.put(SHOP, shopTab);
        fixedTabs.put(TRAVEL, travel);
        fixedTabs.put(SPIRITS, spirits);
    }

    // ---------------------------------------------------------------- what the server sends

    /** Called from the RecipeShow receiver. */
    public static void receive(Net.RecipeShow show) {
        CollectionTab.receive(show);
    }

    public void receiveTravel(ChestTravel.Show show) {
        travel.receive(show);
    }

    public void receiveCleanse(Cleanse.Show show) {
        cleanse.receive(show);
    }

    public void receiveShop(LightShop.Show show) {
        if (!chest.equals(show.chest())) return;
        shop = show;
        shopTab.receive(show);
    }

    public void receiveSpirit(Souls.Note note) {
        spirits.receive(note);
    }

    public void receiveUnlocked(Net.Unlocked u) {
        if (chest.equals(u.chest())) unlocked.show(u);
    }

    // ---------------------------------------------------------------- for the tabs

    Minecraft mc() {
        return minecraft;
    }

    Font font() {
        return font;
    }

    <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
        return addRenderableWidget(widget);
    }

    int w() {
        return Math.min(width - 24, 480);
    }

    int h() {
        return Math.min(height - 20, 310);
    }

    int left() {
        return (width - w()) / 2;
    }

    int top() {
        return (height - h()) / 2;
    }

    int gridX() {
        return left() + TAB_W + 18;
    }

    int gridW() {
        return w() - TAB_W - 30;
    }

    /** The short panel under the Collection's grid, on the footer. */
    int detailY() {
        return top() + h() - HELP - DETAIL;
    }

    int screenWidth() {
        return width;
    }

    int screenHeight() {
        return height;
    }

    int ticks() {
        return ticks;
    }

    /** Carried plus stored Sparks: what the server lets the player spend. */
    static int sparksOf(Player player) {
        int n = Collection.stored(player, "lightborn:spark").getCount();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++)
            if (inv.getItem(i).is(ModItems.SPARK)) n += inv.getItem(i).getCount();
        return n;
    }

    // ---------------------------------------------------------------- tabs

    /** The fixed tabs this player has, in order. */
    private List<Fixed> shownTabs() {
        if (minecraft == null || minecraft.player == null) return FIXED;
        return FIXED.stream().filter(f -> f.open().test(minecraft.player)).toList();
    }

    private ChestTab open() {
        return selected >= 0 ? collection : fixedTabs.get(selected);
    }

    private int tabHeight() {
        return Math.max(12, Math.min(16, (h() - HEAD - HELP - 26) / (FIXED.size() + 1)));
    }

    /**
     * Shows a tab by its code, or a set by its index (also used by the client tests, which may open a tab the player
     * has not reached yet).
     */
    public void select(int index) {
        selected = index;
        if (index >= 0) collection.show(index);
        var tab = open();
        if (tab != null) tab.opened();
        layout();
    }

    @Override
    protected void init() {
        if (minecraft.level != null)
            Collection.build(minecraft.level.enabledFeatures(), minecraft.level.registryAccess());
        for (var tab : fixedTabs.values()) tab.init();
        collection.init();
        built = true;
        ClientPlayNetworking.send(new LightShop.Ask(chest));
        // A fresh chest opens on the Shop once it is there, else on the Collection (owner, 2026-11-18: the Shop waits
        // for the first night).
        if (selected == Integer.MIN_VALUE)
            selected = minecraft.player != null && ChestTabs.shop(minecraft.player) ? SHOP : collection.set;
        layout();
    }

    /** Every tab places its widgets: the open one shows them, the rest hide theirs. */
    private void layout() {
        if (minecraft == null || !built) return;
        var open = open();
        for (var tab : fixedTabs.values()) tab.layout(tab == open);
        collection.layout(open == collection);
    }

    @Override
    public void tick() {
        if (minecraft.level == null
                || minecraft.player == null
                || !minecraft.level.getBlockState(chest).is(ModItems.LIGHT_CHEST)
                || minecraft.player.distanceToSqr(chest.getX() + .5, chest.getY() + .5, chest.getZ() + .5) > 64) {
            onClose();
            return;
        }
        ticks++;
        var tab = open();
        if (tab != null) tab.tick(ticks);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fillGradient(0, 0, width, height, 0xC0100C04, 0xD0181206);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        boolean blocked = unlocked.isOpen();
        page(g, blocked ? -1 : mouseX, blocked ? -1 : mouseY, partial);
        if (blocked) unlocked.draw(g, mouseX, mouseY);
    }

    private void page(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        var got = Collection.collected(minecraft.player);
        int x0 = left(), y0 = top(), W = w(), H = h();
        g.fill(x0 - 3, y0 - 3, x0 + W + 3, y0 + H + 3, 0x60FFD86B);
        g.fill(x0 - 1, y0 - 1, x0 + W + 1, y0 + H + 1, GOLD);
        g.fill(x0, y0, x0 + W, y0 + H, 0xF0141008);

        int total = Collection.all().size();
        var title = Component.literal("Chest of Light").withStyle(ChatFormatting.BOLD);
        g.text(font, title, x0 + 8, y0 + 7, GOLD, true);
        // One Sparks count for the whole chest (owner, 2026-11-16): the shop, spell upgrades, spirits and the ward all
        // spend the same Sparks, carried and stored.
        g.text(font, "· " + sparks(sparksOf(minecraft.player)), x0 + 8 + font.width(title) + 6, y0 + 7, GOLD, false);
        int given = Collection.totalCount(got);
        String count = given + " / " + total + " given to the Light";
        g.text(font, count, x0 + W - 8 - font.width(count), y0 + 7, IVORY, false);
        int barY = y0 + 19;
        g.fill(x0 + 8, barY, x0 + W - 8, barY + 2, 0xFF3A3020);
        if (total > 0) g.fill(x0 + 8, barY, x0 + 8 + (W - 16) * given / total, barY + 2, GOLD);
        layout();
        if (Collection.categories().isEmpty()) {
            super.extractRenderState(g, mouseX, mouseY, partial);
            return;
        }

        // A compact tab list leaves the bulk-give button reachable at the default GUI scale.
        int ty = y0 + HEAD;
        var shown = shownTabs();
        for (int f = 0; f < shown.size(); f++) {
            var tab = shown.get(f);
            int unclaimed = tab.code() == SPIRITS ? spirits.unclaimed() : 0;
            drawTab(
                    g,
                    tab.name(),
                    unclaimed > 0 ? "+" + unclaimed : "",
                    tab.code(),
                    ty + f * tabHeight(),
                    mouseX,
                    mouseY,
                    tab.color());
        }
        drawTab(
                g,
                "Collection",
                given == total && total > 0 ? "✔" : given + "/" + total,
                selected >= 0 ? selected : Integer.MAX_VALUE,
                ty + shown.size() * tabHeight(),
                mouseX,
                mouseY,
                GOLD);
        g.verticalLine(x0 + TAB_W + 10, y0 + HEAD, y0 + H - HELP, 0xFF3A3020);
        var tab = open();
        if (tab != null) tab.draw(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partial);
        if (tab != null) tab.drawOver(g, mouseX, mouseY);
    }

    private void drawTab(
            GuiGraphicsExtractor g, String label, String count, int index, int y, int mouseX, int mouseY, int color) {
        var name = Component.literal(label);
        int x = left() + 6, height = tabHeight();
        boolean hover = mouseX >= x && mouseX < x + TAB_W && mouseY >= y && mouseY < y + height;
        boolean on = index == selected || index == Integer.MAX_VALUE && selected >= 0;
        if (on || hover)
            g.fill(
                    x,
                    y,
                    x + TAB_W,
                    y + height,
                    (on ? 0x50000000 : 0x25000000) | ((index < 0 ? color : GOLD) & 0xFFFFFF));
        int labelWidth = TAB_W - font.width(count) - 12;
        float fit = Math.min(1f, labelWidth / (float) Math.max(1, font.width(name)));
        g.pose().pushMatrix();
        g.pose().translate(x + 4, y + (height - 8 * fit) / 2);
        g.pose().scale(fit, fit);
        g.text(font, name, 0, 0, color, false);
        g.pose().popMatrix();
        g.text(font, count, x + TAB_W - 4 - font.width(count), y + (height - 8) / 2, DIM, false);
    }

    // ---------------------------------------------------------------- the scrollbar

    private int barX() {
        return left() + w() - 8;
    }

    private static int thumb(int len, int rows, int max) {
        return Math.max(10, len * rows / (max + rows));
    }

    void drawBar(GuiGraphicsExtractor g, int y, int len, int rows, int at, int max) {
        int sx = barX(), th = thumb(len, rows, max), tpos = y + (len - th) * at / max;
        g.fill(sx, y, sx + 3, y + len, SLOT);
        g.fill(sx, tpos, sx + 3, tpos + th, GOLD);
    }

    private int[] bar() {
        var tab = open();
        return tab == null ? null : tab.bar();
    }

    private boolean onBar(double mx, double my) {
        int[] b = bar();
        return b != null && mx >= barX() - 3 && mx < barX() + 6 && my >= b[0] && my < b[0] + b[1];
    }

    /** Scrolls so the thumb's middle follows the mouse (owner, 2026-11-17: either scrollbar can be dragged). */
    private void dragTo(double my) {
        int[] b = bar();
        if (b == null) return;
        int th = thumb(b[1], b[2], b[4]);
        int at = (int) Math.round((my - b[0] - th / 2.0) * b[4] / Math.max(1, b[1] - th));
        open().scrollTo(Math.clamp(at, 0, b[4]));
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (unlocked.isOpen()) return unlocked.click(event.x(), event.y());
        if (super.mouseClicked(event, doubleClick)) return true;
        if (onBar(event.x(), event.y())) {
            dragBar = true;
            dragTo(event.y());
            return true;
        }
        int x0 = left(), ty = top() + HEAD;
        var cats = Collection.categories();
        var shown = shownTabs();
        boolean inTabs = event.x() >= x0 + 6 && event.x() < x0 + 6 + TAB_W && event.y() >= ty;
        int row = inTabs ? (int) ((event.y() - ty) / tabHeight()) : -1;
        if (row >= 0 && row < shown.size()) {
            select(shown.get(row).code());
            return true;
        }
        if (row == shown.size() && !cats.isEmpty()) {
            select(Math.min(collection.set, cats.size() - 1));
            return true;
        }
        var tab = open();
        return tab != null && tab.click(event.x(), event.y(), doubleClick);
    }

    /** Escape or Enter closes the Unlocked window first, leaving the chest open. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (unlocked.isOpen() && (event.isEscape() || event.isConfirmation() || event.isSelection())) {
            unlocked.close();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (unlocked.isOpen()) {
            unlocked.scroll(dy);
            return true;
        }
        var tab = open();
        return tab != null && tab.scroll(dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        dragBar = false;
        if (unlocked.isOpen()) return true;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (unlocked.isOpen()) return true;
        if (dragBar) {
            dragTo(e.y());
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- for the client tests

    /** Opens one item in the Collection's panel and asks the server how it is made. */
    public void pick(String id) {
        if (selected < 0) select(collection.set);
        collection.pick(id);
        layout();
    }

    /** Opens one spirit in the Spirits tab. */
    public void pickSpirit(String kind) {
        spirits.pick(kind);
        layout();
    }

    /** The centre of one upgrade branch's node in GUI pixels, in the open upgrade tree. */
    public int[] upgradeCard(int tier, int choice) {
        return (selected == UPGRADE_GEAR ? gear : spells).card(tier, choice);
    }

    /** The tab names shown in the column, top to bottom. */
    public List<String> tabNames() {
        var out = new ArrayList<String>();
        for (var f : shownTabs()) out.add(f.name());
        out.add("Collection");
        return out;
    }

    /**
     * Every visible button that crosses the chest's frame (owner, 2026-11-17: the Claim button hung over the frame at
     * his GUI scale and nothing caught it).
     */
    public List<String> outOfFrame() {
        List<String> out = new ArrayList<>();
        for (var child : children())
            if (child instanceof AbstractWidget b
                    && b.visible
                    && (b.getX() < left()
                            || b.getY() < top()
                            || b.getX() + b.getWidth() > left() + w()
                            || b.getY() + b.getHeight() > top() + h()))
                out.add("'" + b.getMessage().getString() + "' at " + b.getX() + "," + b.getY() + " size "
                        + b.getWidth() + "x" + b.getHeight() + " (frame " + left() + "," + top() + " " + w() + "x"
                        + h() + ")");
        return out;
    }

    /** Every visible button whose label runs past its edges (owner, 2026-11-18: "Upgrade ward · 20 Sparks"). */
    public List<String> cutLabels() {
        List<String> out = new ArrayList<>();
        for (var child : children())
            if (child instanceof AbstractWidget b && b.visible && font.width(b.getMessage()) > b.getWidth() - 6)
                out.add("'" + b.getMessage().getString() + "' (" + font.width(b.getMessage()) + " px of text in "
                        + b.getWidth() + ")");
        return out;
    }
}
