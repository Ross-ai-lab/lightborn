package lightborn.client;

import static lightborn.client.ChestStyle.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lightborn.Collection;
import lightborn.LightGearTree;
import lightborn.LightShop;
import lightborn.Spell;
import lightborn.SpellUpgrades;
import lightborn.Spellstone;
import lightborn.TomeItem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Upgrade Spells and Upgrade Gear, one tree each. The tomes (or the gear) carried form the root along the top (click one
 * to set it in the altar); a trunk falls from it and forks into tier I's three branches, and each kept branch forks
 * again into the next tier. The kept path burns red with a spark running down it; branches open to take now glow and
 * pulse; passed-over and locked ones are dim. The panel below reads out whichever branch is under the pointer.
 */
final class UpgradeTab extends ChestTab {
    private static final int UP_RED = 0xFFE0443C, UP_RED_DEEP = 0xFF3A1210, UP_TOME = 25, UP_ICON_TILE = 22;
    private static final float UP_ICON_SCALE = 1.25f;
    private static final int UP_LABEL_W = 30, UP_NODE = 18;

    /** The Upgrade Gear tab, else Upgrade Spells. */
    private final boolean gear;
    /**
     * The altar: what is set in it. For spells a {@link Spellstone#tomeAt} address (an inventory slot, or a slot in a
     * carried Spellstone); for gear an inventory slot. -1: none yet.
     */
    private int altar = -1;

    UpgradeTab(CollectionScreen s, boolean gear) {
        super(s);
        this.gear = gear;
    }

    @Override
    void tick(int ticks) {
        // The shop's snapshot carries the Sparks the tree prices against.
        if (ticks % 20 == 0) ClientPlayNetworking.send(new LightShop.Ask(s.chest));
    }

    @Override
    void opened() {
        ClientPlayNetworking.send(new LightShop.Ask(s.chest));
    }

    private int free() {
        return s.shop == null ? 0 : s.shop.sparks();
    }

    /**
     * Where each tome carried is: the off hand first (where a tome is cast from), then the bag in order. A Spellstone's
     * tomes stand in its place, slot by slot (owner, 2026-11-18: the tab must see the tomes in the Spellstone).
     */
    private List<Integer> tomeSlots() {
        List<Integer> out = new ArrayList<>();
        if (mc().player == null) return out;
        var inv = mc().player.getInventory();
        List<Integer> order = new ArrayList<>();
        order.add(40);
        for (int i = 0; i < 36; i++) order.add(i);
        for (int i : order) {
            var stack = inv.getItem(i);
            if (TomeItem.spellOf(stack) != null) out.add(i);
            else if (Spellstone.is(stack)) {
                var tomes = Spellstone.tomes(stack);
                for (int k = 0; k < Spellstone.SLOTS; k++)
                    if (TomeItem.spellOf(tomes.get(k)) != null) out.add(Spellstone.inStone(i, k));
            }
        }
        return out;
    }

    /**
     * Inventory slots holding gear that can be upgraded, grouped by kind (owner, 2026-11-18: the staffs lay scattered
     * among the armour): helms, chests, leggings, boots, wings, weapons, staffs and so on, each in the order they are
     * carried (worn and off hand first, then the hotbar, then the bag); as many as the strip's two rows hold.
     */
    private List<Integer> gearSlots() {
        if (mc().player == null) return List.of();
        var inv = mc().player.getInventory();
        var order = new ArrayList<Integer>();
        for (int i = 36; i < inv.getContainerSize(); i++) order.add(i);
        for (int i = 0; i < 36 && i < inv.getContainerSize(); i++) order.add(i);
        var out = new ArrayList<Integer>();
        for (int i : order) if (LightGearTree.kind(inv.getItem(i)) >= 0) out.add(i);
        out.sort(Comparator.comparingInt(i -> LightGearTree.kind(inv.getItem(i)))); // stable: carried order kept
        return out.size() > tomesPerRow() * 2 ? out.subList(0, tomesPerRow() * 2) : out;
    }

    private List<Integer> treeSlots() {
        return gear ? gearSlots() : tomeSlots();
    }

    private ItemStack at(int slot) {
        if (slot < 0 || mc().player == null) return ItemStack.EMPTY;
        return gear ? mc().player.getInventory().getItem(slot) : Spellstone.tomeAt(mc().player, slot);
    }

    private int[] chosen(ItemStack st) {
        return gear ? LightGearTree.chosen(st) : SpellUpgrades.chosen(st);
    }

    private int[] owned(ItemStack st) {
        return gear ? LightGearTree.owned(st) : SpellUpgrades.owned(st);
    }

    private boolean upgraded(ItemStack st) {
        for (int c : chosen(st)) if (c >= 0) return true;
        return false;
    }

    private String block(int[] chosen, int[] owned, int t, int c, int free) {
        if (!gear) return SpellUpgrades.blockReason(chosen, owned, t, c, free);
        String reason = LightGearTree.blockReason(chosen, owned, t, c);
        if (reason != null || LightGearTree.owns(owned, t, c) || mc().player.hasInfiniteMaterials()) return reason;
        Item ore = LightGearTree.ore(t);
        int need = LightGearTree.ORE[t];
        return need > 0 && oreHeld(ore) < need
                ? "Needs " + need + " " + new ItemStack(ore).getHoverName().getString()
                : null;
    }

    /** An ore carried plus kept in the chest: a gear tier takes from both, the chest first. */
    private int oreHeld(Item ore) {
        if (mc().player == null) return 0;
        String id = BuiltInRegistries.ITEM.getKey(ore).toString();
        return Collection.stored(mc().player, id).getCount()
                + mc().player.getInventory().countItem(ore);
    }

    /** A tier's whole price in words, for the reading panel: Sparks for a spell, ore for gear. */
    private String price(int t) {
        if (!gear) return cost(t) + " Sparks";
        return LightGearTree.ORE[t] + " "
                + new ItemStack(LightGearTree.ore(t)).getHoverName().getString();
    }

    /** Whether the player has what a tier costs: Sparks for a spell, the tier's ore (bag and chest) for gear. */
    private boolean affords(int t, int free) {
        if (!gear) return free >= cost(t);
        return mc().player != null && mc().player.hasInfiniteMaterials()
                || oreHeld(LightGearTree.ore(t)) >= LightGearTree.ORE[t];
    }

    /** A node of the item in the altar, as name and text. */
    private record Node(String name, String text) {}

    private Node node(int t, int c) {
        ItemStack st = altarItem();
        if (gear) {
            var n = LightGearTree.tiers(LightGearTree.kind(st)).get(t).get(c);
            return new Node(n.name(), n.text());
        }
        var u = SpellUpgrades.tiers(TomeItem.spellOf(st)).get(t).get(c);
        return new Node(u.name(), u.text());
    }

    private int cost(int t) {
        return SpellUpgrades.TIER_COST[t];
    }

    /** A tier's price under its numeral: Sparks for a spell; for gear the ore's count and a small icon of it. */
    private void price(GuiGraphicsExtractor g, int t, int x, int y, boolean open) {
        if (!gear) {
            g.text(font(), cost(t) + "✦", x, y, open ? GOLD_TEXT : DIM, false);
            return;
        }
        String n = Integer.toString(LightGearTree.ORE[t]);
        g.text(font(), n, x, y, open ? GOLD_TEXT : DIM, false);
        g.pose().pushMatrix();
        g.pose().translate(x + font().width(n) + 1, y - 1);
        g.pose().scale(.625f, .625f);
        g.item(new ItemStack(LightGearTree.ore(t)), 0, 0);
        g.pose().popMatrix();
    }

    private void choose(int t, int c) {
        if (gear) ClientPlayNetworking.send(new LightGearTree.Choose(s.chest, altarSlot(), t, c));
        else ClientPlayNetworking.send(new SpellUpgrades.Choose(s.chest, altarSlot(), t, c));
    }

    /** What is in the altar: the chosen one while it is still carried, else the first carried. */
    private int altarSlot() {
        var slots = treeSlots();
        if (!slots.contains(altar)) altar = slots.isEmpty() ? -1 : slots.getFirst();
        return altar;
    }

    private ItemStack altarItem() {
        return at(altarSlot());
    }

    private int stripY() {
        return s.top() + HEAD + 3;
    }

    private int perRow() {
        return Math.max(1, (s.gridW() - 8) / UP_TOME);
    }

    private int tomesPerRow() {
        return perRow();
    }

    /** Rows of the strip along the top (the root of the tree). */
    private int stripRows() {
        return Math.max(1, Math.min(2, (treeSlots().size() + perRow() - 1) / perRow()));
    }

    /** Lowest pixel the tree and its reading panel may use. */
    private int bottom() {
        return s.top() + s.h() - HELP - 4;
    }

    private int treeTop() {
        return stripY() + stripRows() * UP_TOME + 16;
    }

    /** Height of one tier of the tree: roomy on a tall screen, tighter on a short one, leaving the panel ~46 px. */
    private int treeRow() {
        int space = bottom() - 52 - treeTop();
        return Math.clamp((space - 20) / 2, 26, 46);
    }

    /** Top of a tier's three branch nodes. */
    private int tierY(int tier) {
        return treeTop() + tier * treeRow();
    }

    private int branchW() {
        return (s.gridW() - 8 - UP_LABEL_W) / 3;
    }

    /** Left edge of a branch's node; its name sits to the node's right. */
    private int branchX(int choice) {
        return s.gridX() + 4 + UP_LABEL_W + choice * branchW();
    }

    private boolean onBranch(int tier, int choice, double mx, double my) {
        int bx = branchX(choice), by = tierY(tier);
        return mx >= bx && mx < bx + branchW() - 4 && my >= by - 1 && my < by + UP_NODE + 2;
    }

    /** The centre of one branch's node in GUI pixels (for the client tests). */
    int[] card(int tier, int choice) {
        return new int[] {branchX(choice) + UP_NODE / 2, tierY(tier) + UP_NODE / 2};
    }

    private static void hline(GuiGraphicsExtractor g, int x1, int x2, int y, int thick, int color) {
        g.fill(Math.min(x1, x2), y, Math.max(x1, x2) + thick, y + thick, color);
    }

    private static void vline(GuiGraphicsExtractor g, int x, int y1, int y2, int thick, int color) {
        g.fill(x, Math.min(y1, y2), x + thick, Math.max(y1, y2), color);
    }

    /**
     * One fork: from each parent's foot down to a bar, across, then down to each child node's head. With a kept child
     * the fork is drawn dark and only the route from the parent to that child burns red.
     */
    private void fork(GuiGraphicsExtractor g, int[] parents, int fromY, int toY, int kept, boolean open) {
        int barY = toY - 8;
        int color = kept >= 0 ? 0xFF3A3020 : open ? 0xFF8A4A38 : 0xFF3A3020;
        int thick = 1;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int px : parents) {
            vline(g, px - thick / 2, fromY, barY + thick, thick, color);
            lo = Math.min(lo, px);
            hi = Math.max(hi, px);
        }
        for (int c = 0; c < SpellUpgrades.CHOICES; c++) {
            int nx = branchX(c) + UP_NODE / 2;
            lo = Math.min(lo, nx);
            hi = Math.max(hi, nx);
            vline(g, nx - thick / 2, barY, toY, thick, color);
        }
        hline(g, lo - thick / 2, hi - thick / 2, barY, thick, color);
        if (kept < 0 || parents.length != 1) return;
        int px = parents[0], nx = branchX(kept) + UP_NODE / 2;
        vline(g, px - 1, fromY, barY + 2, 2, UP_RED);
        hline(g, px - 1, nx - 1, barY, 2, UP_RED);
        vline(g, nx - 1, barY, toY, 2, UP_RED);
    }

    @Override
    void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = s.gridX() + 4, w = s.gridW() - 12;
        int free = free();
        var slots = treeSlots();
        if (slots.isEmpty()) {
            int ly = stripY() + 4;
            for (var line : font().split(
                            Component.literal(
                                    gear
                                            ? "No gear on you. Armour, wings, weapons, staffs, bows, tools and shields, worn "
                                                    + "or carried, each grow a tree of three tiers like a tome's. The "
                                                    + "Dragon's Light gear also grows stronger with every tier it opens."
                                            : "Carry a tome to upgrade it, loose or in your Spellstone. Each tome grows a tree "
                                                    + "of three tiers. Buy as many branches of a tier as you like; one per tier "
                                                    + "is in use, and a click switches between the ones you own for free. The "
                                                    + "tome keeps them wherever it goes."),
                            w)) {
                g.text(font(), line, x, ly, DIM, false);
                ly += 10;
            }
            return;
        }
        ItemStack item = altarItem();
        Spell spell = TomeItem.spellOf(item);
        int[] chosen = chosen(item), owned = owned(item);
        int slot = altarSlot();

        // the branches first, so the strip and nodes sit on top of them
        long now = System.currentTimeMillis();
        int rootX = x + UP_ICON_TILE / 2, rootY = stripY() + UP_ICON_TILE;
        for (int i = 0; i < slots.size() && i < perRow() * 2; i++)
            if (slots.get(i) == slot) {
                rootX = x + (i % perRow()) * UP_TOME + UP_ICON_TILE / 2;
                rootY = stripY() + (i / perRow()) * UP_TOME + UP_ICON_TILE;
            }
        var path = new ArrayList<int[]>(); // the lit route, for the running spark
        path.add(new int[] {rootX, rootY});
        int[] parents = {rootX};
        int fromY = rootY;
        boolean alive = true;
        for (int t = 0; t < SpellUpgrades.TIERS; t++) {
            boolean lit = chosen[t] >= 0;
            boolean reached = t == 0 || chosen[t - 1] >= 0;
            boolean open = reached && !lit && affords(t, free);
            fork(g, parents, fromY, tierY(t), lit && reached ? chosen[t] : -1, open);
            if (alive && lit && reached) {
                int nx = branchX(chosen[t]) + UP_NODE / 2, barY = tierY(t) - 8;
                path.add(new int[] {path.getLast()[0], barY});
                path.add(new int[] {nx, barY});
                path.add(new int[] {nx, tierY(t)});
                path.add(new int[] {nx, tierY(t) + UP_NODE});
            } else alive = false;
            if (lit) parents = new int[] {branchX(chosen[t]) + UP_NODE / 2};
            else parents = new int[] {branchX(0) + UP_NODE / 2, branchX(1) + UP_NODE / 2, branchX(2) + UP_NODE / 2};
            fromY = tierY(t) + UP_NODE;
        }
        if (path.size() > 1) path.removeLast(); // the spark rests at the last kept node, not below it
        spark(g, path, now);

        ItemStack hoverItem = ItemStack.EMPTY;
        for (int i = 0; i < slots.size() && i < perRow() * 2; i++) {
            int cx = x + (i % perRow()) * UP_TOME, cy = stripY() + (i / perRow()) * UP_TOME;
            var stack = at(slots.get(i));
            boolean on = slots.get(i) == slot;
            boolean inStone = !gear && slots.get(i) >= Spellstone.IN_STONE;
            boolean hover = mouseX >= cx && mouseX < cx + UP_ICON_TILE && mouseY >= cy && mouseY < cy + UP_ICON_TILE;
            g.fill(
                    cx,
                    cy,
                    cx + UP_ICON_TILE,
                    cy + UP_ICON_TILE,
                    on ? UP_RED : hover ? 0xFF6A3A30 : inStone ? 0xFF4A3C5E : SLOT);
            g.fill(cx + 1, cy + 1, cx + UP_ICON_TILE - 1, cy + UP_ICON_TILE - 1, on ? UP_RED_DEEP : SLOT_IN);
            g.pose().pushMatrix();
            g.pose().translate(cx + 1, cy + 1);
            g.pose().scale(UP_ICON_SCALE, UP_ICON_SCALE);
            g.item(stack, 0, 0);
            g.pose().popMatrix();
            if (gear && stack.isDamageableItem()) {
                int remaining = Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
                int bar = Math.round((UP_ICON_TILE - 4f) * remaining / stack.getMaxDamage());
                g.fill(cx + 2, cy + UP_ICON_TILE - 3, cx + UP_ICON_TILE - 2, cy + UP_ICON_TILE - 1, 0xFF0D0A07);
                g.fill(
                        cx + 2,
                        cy + UP_ICON_TILE - 3,
                        cx + 2 + Math.max(1, bar),
                        cy + UP_ICON_TILE - 2,
                        remaining == 0 ? UP_RED : 0xFF000000 | stack.getBarColor());
            }
            if (upgraded(stack)) g.fill(cx + UP_ICON_TILE - 5, cy + 1, cx + UP_ICON_TILE - 1, cy + 5, UP_RED);
            if (hover) hoverItem = stack;
        }
        int nameX = x + Math.min(perRow(), slots.size()) * UP_TOME + 6;
        // Gear shows no name or tier here (owner, 2026-11-17): the strip's icons and their tooltips say it.
        if (!gear && nameX + 40 < s.gridX() + s.gridW() - 8)
            g.text(
                    font(),
                    font().plainSubstrByWidth(item.getHoverName().getString(), s.gridX() + s.gridW() - 8 - nameX),
                    nameX,
                    stripY() + (UP_ICON_TILE - 8) / 2,
                    IVORY,
                    false);

        int hoverT = -1, hoverC = -1;
        float pulse = .5f + .5f * (float) Math.sin(now / 260.0);
        for (int t = 0; t < SpellUpgrades.TIERS; t++) {
            int ty = tierY(t);
            boolean tierOpen = t == 0 || owned[t - 1] != 0;
            g.text(
                    font(),
                    Component.literal(SpellUpgrades.roman(t)).withStyle(ChatFormatting.BOLD),
                    x + 2,
                    ty,
                    chosen[t] >= 0 ? UP_RED : tierOpen ? IVORY : DIM,
                    false);
            price(g, t, x + 2, ty + 10, tierOpen);
            for (int c = 0; c < SpellUpgrades.CHOICES; c++) {
                var up = node(t, c);
                int nx = branchX(c);
                boolean kept = chosen[t] == c, mine = !kept && SpellUpgrades.owns(owned, t, c);
                boolean open = !kept && block(chosen, owned, t, c, free) == null;
                boolean passed = chosen[t] >= 0 && !kept && !mine;
                boolean hover = onBranch(t, c, mouseX, mouseY);
                if (hover) {
                    hoverT = t;
                    hoverC = c;
                }
                if (kept || open) { // a soft halo round a lit or takeable node
                    int halo = kept ? 0x55E0443C : (int) ((hover ? 0x80 : 0x20 + 0x40 * pulse)) << 24 | 0xFF8A50;
                    g.fill(nx - 2, ty - 2, nx + UP_NODE + 2, ty + UP_NODE + 2, halo);
                }
                // A bought node not in use keeps a dark red body and a gold rim: it switches in for free.
                int edge = kept
                        ? UP_RED
                        : mine
                                ? hover ? 0xFFFFE0A0 : 0xFFD8A040
                                : open && hover ? 0xFFFFB090 : open ? 0xFFC0603C : passed ? 0xFF3A3020 : 0xFF4A3E2C;
                g.fill(nx, ty, nx + UP_NODE, ty + UP_NODE, edge);
                g.fill(
                        nx + 1,
                        ty + 1,
                        nx + UP_NODE - 1,
                        ty + UP_NODE - 1,
                        kept ? UP_RED_DEEP : mine ? 0xFF4A1A12 : SLOT_IN);
                gem(
                        g,
                        nx + UP_NODE / 2,
                        ty + UP_NODE / 2,
                        kept ? 0xFFFFD0A0 : mine ? 0xFFC07050 : open ? 0xFFE08050 : passed ? 0xFF3E362A : 0xFF5A4E3A);
                int nameColor = kept ? 0xFFFFD0C0 : mine ? 0xFFE0B890 : open ? IVORY : passed ? 0xFF5A5246 : DIM;
                var lines = font().split(Component.literal(up.name()), branchW() - UP_NODE - 8);
                int ly = lines.size() > 1 ? ty : ty + 5;
                for (int l = 0; l < lines.size() && l < 2; l++, ly += 10)
                    g.text(font(), lines.get(l), nx + UP_NODE + 4, ly, nameColor, false);
            }
        }
        readout(g, spell, chosen, owned, free, hoverT, hoverC);
        if (hoverT < 0 && !hoverItem.isEmpty()) g.setTooltipForNextFrame(font(), hoverItem, mouseX, mouseY);
    }

    /** A small four-point star cut into a node. */
    private static void gem(GuiGraphicsExtractor g, int cx, int cy, int color) {
        g.fill(cx - 1, cy - 4, cx + 1, cy + 4, color);
        g.fill(cx - 4, cy - 1, cx + 4, cy + 1, color);
        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, color);
    }

    /** A bright mote running down the kept path, root to the last kept branch, about once a second and a half. */
    private static void spark(GuiGraphicsExtractor g, List<int[]> path, long now) {
        if (path.size() < 2) return;
        int length = 0;
        for (int i = 1; i < path.size(); i++)
            length += Math.abs(path.get(i)[0] - path.get(i - 1)[0]) + Math.abs(path.get(i)[1] - path.get(i - 1)[1]);
        if (length <= 0) return;
        int at = (int) (now / 12 % (length + 30)) - 15; // a pause off each end
        if (at < 0 || at > length) return;
        for (int i = 1; i < path.size(); i++) {
            int[] a = path.get(i - 1), b = path.get(i);
            int seg = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]);
            if (at <= seg) {
                int px = a[0] + Integer.signum(b[0] - a[0]) * at, py = a[1] + Integer.signum(b[1] - a[1]) * at;
                g.fill(px - 2, py - 2, px + 3, py + 3, 0x66FFB080);
                g.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFFF0D0);
                return;
            }
            at -= seg;
        }
    }

    /**
     * The reading panel under the tree: the branch under the pointer in full (name, what it does, what clicking does);
     * with nothing under the pointer, the branches in use and what the next tier costs.
     */
    private void readout(GuiGraphicsExtractor g, Spell spell, int[] chosen, int[] owned, int free, int t, int c) {
        int px = s.gridX() + 4, pw = s.gridW() - 12;
        // Gear tiers carry an ore line under their price: the panel starts below it, never over it.
        int py = tierY(SpellUpgrades.TIERS - 1) + (gear ? 31 : 26), bottom = bottom();
        if (bottom - py < 14) return;
        g.fill(px, py, px + pw, bottom, 0xFF3A1E14);
        g.fill(px + 1, py + 1, px + pw - 1, bottom - 1, 0xF0120C08);
        var lines = new ArrayList<FormattedCharSequence>();
        var colors = new ArrayList<Integer>();
        if (t >= 0) {
            var up = node(t, c);
            boolean kept = chosen[t] == c, mine = SpellUpgrades.owns(owned, t, c);
            String reason = block(chosen, owned, t, c, free);
            // Owner, 2026-11-17: no tier or price in the heading; the price sits beside the tier's numeral.
            lines.add(
                    Component.literal(up.name()).withStyle(ChatFormatting.BOLD).getVisualOrderText());
            colors.add(kept ? UP_RED : IVORY);
            for (var l : font().split(Component.literal(up.text()), pw - 10)) {
                lines.add(l);
                colors.add(0xFFC8BCA4);
            }
            // Owner, 2026-11-23: no "Click to buy it" line; a node you can buy says nothing more.
            String state = kept
                    ? "In use."
                    : mine
                            ? "Yours. Click to use it instead of "
                                    + node(t, chosen[t]).name() + "."
                            : reason != null ? reason + "." : null;
            if (state != null) {
                lines.add(Component.literal(state).getVisualOrderText());
                colors.add(kept ? 0xFFE07060 : reason == null ? GOLD_TEXT : DIM);
            }
        } else {
            // Each tier's line wraps rather than being cut off mid-word; if the wrapped lines would not all fit in
            // the box, each tier shows its upgrade's name alone (hovering a node still reads it in full).
            var full = new ArrayList<FormattedCharSequence>();
            var fullColors = new ArrayList<Integer>();
            var brief = new ArrayList<FormattedCharSequence>();
            var briefColors = new ArrayList<Integer>();
            for (int k = 0; k < SpellUpgrades.TIERS; k++) {
                String line, name;
                int color;
                if (chosen[k] >= 0) {
                    var up = node(k, chosen[k]);
                    line = SpellUpgrades.roman(k) + "  " + up.name() + ": " + up.text();
                    name = SpellUpgrades.roman(k) + "  " + up.name();
                    color = 0xFFE8B0A0;
                } else if (k == 0 || chosen[k - 1] >= 0) {
                    line = SpellUpgrades.roman(k) + "  Buy a branch for " + price(k) + ". Hover one to read it.";
                    name = line;
                    color = affords(k, free) ? GOLD_TEXT : DIM;
                } else break;
                for (var l : font().split(Component.literal(line), pw - 10)) {
                    full.add(l);
                    fullColors.add(color);
                }
                brief.add(Component.literal(font().plainSubstrByWidth(name, pw - 10))
                        .getVisualOrderText());
                briefColors.add(color);
            }
            boolean fits = 4 + full.size() * 10 <= bottom - py;
            lines.addAll(fits ? full : brief);
            colors.addAll(fits ? fullColors : briefColors);
        }
        float scale = Math.min(1f, (bottom - py - 7f) / Math.max(10, lines.size() * 10));
        g.pose().pushMatrix();
        g.pose().translate(px + 5, py + 4);
        g.pose().scale(scale, scale);
        for (int i = 0; i < lines.size(); i++) g.text(font(), lines.get(i), 0, i * 10, colors.get(i), false);
        g.pose().popMatrix();
    }

    @Override
    boolean click(double mx, double my, boolean doubleClick) {
        var slots = treeSlots();
        int x = s.gridX() + 4;
        for (int i = 0; i < slots.size() && i < perRow() * 2; i++) {
            int cx = x + (i % perRow()) * UP_TOME, cy = stripY() + (i / perRow()) * UP_TOME;
            if (mx >= cx && mx < cx + UP_ICON_TILE && my >= cy && my < cy + UP_ICON_TILE) {
                altar = slots.get(i);
                sound(SoundEvents.BOOK_PAGE_TURN, 1f);
                return true;
            }
        }
        ItemStack item = altarItem();
        if (item.isEmpty()) return false;
        int[] chosen = chosen(item), owned = owned(item);
        int free = free();
        for (int t = 0; t < SpellUpgrades.TIERS; t++)
            for (int c = 0; c < SpellUpgrades.CHOICES; c++) {
                if (!onBranch(t, c, mx, my)) continue;
                if (block(chosen, owned, t, c, free) == null) choose(t, c);
                else sound(SoundEvents.UI_BUTTON_CLICK.value(), .6f);
                return true;
            }
        return false;
    }
}
