package lightborn.client;

import lightborn.Enchanting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The two Light station skins reuse Minecraft's inventory slots, text input and recipe controls. */
public final class LightStationScreens {
    private LightStationScreens() {}

    private static final int GOLD = 0xFFFFD885, IVORY = 0xFFF9E8C8, DIM = 0xFFC0AA88;
    private static final int BRASS = 0xFF927044, INK = 0xFF1B1510, SLOT = 0xFF100D0B;
    private static final int EMBER = 0xFFFF9C51, QUENCHED = 0xFF9ADCED;

    public static final class Furnace extends FurnaceScreen {
        public Furnace(FurnaceMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            boolean burning = menu.isLit();
            boolean ready = menu.getSlot(2).hasItem();
            drawBench(g, menu, leftPos, topPos, imageWidth, imageHeight, ready, EMBER);
            meter(g, leftPos + 79, topPos + 43, 24, menu.getBurnProgress(), EMBER);
            arrow(g, leftPos + 79, topPos + 37, EMBER, menu.getBurnProgress() > 0);
            meter(g, leftPos + 58, topPos + 40, 12, menu.getLitProgress(), GOLD);
            var idle = menu.getSlot(0).hasItem() ? "Waiting for heat" : "Add an ingredient";
            stage(
                    g,
                    minecraft,
                    leftPos + imageWidth,
                    topPos,
                    imageHeight,
                    Items.FURNACE.getDefaultInstance(),
                    menu.getSlot(2).getItem(),
                    EMBER,
                    burning,
                    ready ? "Output ready" : burning ? "Smelting" : idle);
        }

        @Override
        protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            labels(g, font, title, playerInventoryTitle, titleLabelX, titleLabelY, inventoryLabelX, inventoryLabelY);
            g.text(font, "Heat", 24, 58, DIM, false);
        }
    }

    public static final class Anvil extends AnvilScreen {
        public Anvil(AnvilMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            boolean ready = menu.getSlot(2).hasItem();
            drawBench(g, menu, leftPos, topPos, imageWidth, imageHeight, ready, QUENCHED);
            inset(g, leftPos + 59, topPos + 20, 110, 16, menu.getSlot(0).hasItem() ? BRASS : 0xFF544233);
            g.text(font, "Name", leftPos + 12, topPos + 24, DIM, false);
            int plusX = leftPos + 56, plusY = topPos + 54;
            g.fill(plusX - 3, plusY, plusX + 4, plusY + 1, BRASS);
            g.fill(plusX, plusY - 3, plusX + 1, plusY + 4, BRASS);
            arrow(g, leftPos + 98, topPos + 53, QUENCHED, ready);
            var status = ready
                    ? "Result ready"
                    : menu.getSlot(0).hasItem() ? "Add material or rename" : "Set gear on the anvil";
            stage(
                    g,
                    minecraft,
                    leftPos + imageWidth,
                    topPos,
                    imageHeight,
                    Items.ANVIL.getDefaultInstance(),
                    menu.getSlot(2).getItem(),
                    QUENCHED,
                    ready,
                    status);
        }

        @Override
        protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            labels(g, font, title, playerInventoryTitle, titleLabelX, titleLabelY, inventoryLabelX, inventoryLabelY);
            if (!menu.getSlot(2).hasItem()) return;
            int lapis = ((Enchanting.AnvilPrice) menu).lightborn$lapis();
            var price = lapis == 0
                    ? Component.translatable("lightborn.anvil.free")
                    : Component.translatable("container.repair.cost", lapis);
            g.text(
                    font,
                    price,
                    imageWidth - font.width(price) - 8,
                    67,
                    menu.getSlot(2).mayPickup(minecraft.player) ? GOLD : 0xFFF07B69,
                    false);
        }
    }

    private static void labels(
            GuiGraphicsExtractor g,
            Font font,
            Component title,
            Component inventory,
            int titleX,
            int titleY,
            int invX,
            int invY) {
        g.text(font, title, titleX, titleY, GOLD, true);
        g.text(font, inventory, invX, invY, DIM, false);
    }

    private static void drawBench(
            GuiGraphicsExtractor g, AbstractContainerMenu menu, int x, int y, int w, int h, boolean ready, int accent) {
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0x750B0806);
        frame(g, x, y, w, h, BRASS);
        g.fillGradient(x + 3, y + 17, x + w - 3, y + 71, 0xFF3A2B1B, 0xFF231A13);
        g.fill(x + 3, y + 17, x + w - 3, y + 18, 0xFF6D512E);
        g.fill(x + 3, y + 71, x + w - 3, y + 72, 0xFF6D512E);
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            if (slot.isActive())
                inset(g, x + slot.x - 1, y + slot.y - 1, 18, 18, i == 2 && ready ? accent : i < 3 ? BRASS : 0xFF524131);
        }
    }

    private static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int edge) {
        g.fill(x + 2, y + 3, x + w + 2, y + h + 3, 0x97090008);
        g.fill(x + 1, y, x + w - 1, y + h, edge);
        g.fill(x, y + 1, x + w, y + h - 1, edge);
        g.fillGradient(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF37291E, INK);
        g.fill(x + 3, y + 2, x + w - 3, y + 3, 0xFFC09A63);
        for (int dx : new int[] {4, w - 6})
            for (int dy : new int[] {4, h - 6}) g.fill(x + dx, y + dy, x + dx + 2, y + dy + 2, edge);
    }

    private static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h, int edge) {
        g.fill(x, y, x + w, y + h, edge);
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, SLOT, INK);
    }

    private static void arrow(GuiGraphicsExtractor g, int x, int y, int accent, boolean active) {
        int color = active ? accent : BRASS;
        g.fill(x, y, x + 23, y + 1, color);
        for (int i = 1; i <= 5; i++) {
            g.fill(x + 23 - i, y - i, x + 24 - i, y - i + 1, color);
            g.fill(x + 23 - i, y + i, x + 24 - i, y + i + 1, color);
        }
    }

    private static void meter(GuiGraphicsExtractor g, int x, int y, int w, float progress, int accent) {
        g.fill(x, y, x + w, y + 4, 0xFF110F0D);
        int filled = (int) Math.ceil(w * Math.clamp(progress, 0, 1));
        if (filled > 0) g.fillGradient(x, y, x + filled, y + 4, accent, 0xFF8C4B2B);
    }

    private static void stage(
            GuiGraphicsExtractor g,
            Minecraft game,
            int edge,
            int y,
            int h,
            ItemStack station,
            ItemStack output,
            int accent,
            boolean active,
            String status) {
        int x = edge + 7, w = Math.min(116, g.guiWidth() - x - 7);
        if (w < 62) return;
        int cx = x + w / 2, floor = y + h - 37, ry = y + h / 2 - 10;
        float time = (System.nanoTime() % 1_000_000_000_000L) / 1_000_000_000f;
        double shimmer = .85 + .15 * Math.sin(time * 3.7);
        frame(g, x, y, w, h, BRASS);
        g.fillGradient(x + 5, y + 5, x + w - 5, floor + 8, 0xFF221913, 0xFF302315);

        int rgb = accent & 0xFFFFFF;
        int radius = Math.min(44, w / 2 - 6);
        for (int dy = -radius; dy <= radius; dy++) {
            double k = 1.0 - (double) (dy * dy) / (radius * radius);
            int half = (int) (radius * Math.sqrt(k));
            if (half <= 0) continue;
            int alpha = (int) ((active ? 54 : 33) * k * shimmer);
            g.fill(cx - half, ry + dy, cx + half, ry + dy + 1, alpha << 24 | rgb);
            g.fill(cx - half / 2, ry + dy, cx + half / 2, ry + dy + 1, (alpha / 2) << 24 | rgb);
        }

        int base = Math.min(35, w / 2 - 9);
        for (int row = 0; row < 12; row++) {
            int half = base - (11 - row);
            int tone = 58 + row * 9;
            int stone = 0xFF000000 | tone << 16 | (tone * 4 / 5) << 8 | (tone / 2);
            g.fill(cx - half, floor - 12 + row, cx + half, floor - 11 + row, stone);
        }
        g.fill(cx - base, floor, cx + base, floor + 4, 0xFF5C3E28);
        g.fill(cx - base + 4, floor, cx + base - 4, floor + 1, accent);

        g.enableScissor(x + 3, y + 3, x + w - 3, floor - 2);
        model(g, game, station, cx, ry - 7, Math.min(152, w * 1.35f), time * 13);
        if (!output.isEmpty()) model(g, game, output, cx, y + 28, 63, time * 18 + 12);
        sparks(g, cx, floor - 13, radius, time, rgb, active);
        g.disableScissor();

        g.fill(x + 7, floor + 13, x + w - 7, floor + 14, 0xFF584022);
        String line = game.font.plainSubstrByWidth(status, w - 12);
        g.text(game.font, line, cx - game.font.width(line) / 2, floor + 20, active ? IVORY : DIM, false);
    }

    private static void model(
            GuiGraphicsExtractor g, Minecraft game, ItemStack stack, int x, int y, float scale, float age) {
        var state = new ItemEntityRenderState();
        state.entityType = EntityTypes.ITEM;
        state.count = 1;
        state.ageInTicks = age;
        state.lightCoords = 0xF000F0;
        game.getItemModelResolver().updateForTopItem(state.item, stack, ItemDisplayContext.GROUND, game.level, null, 0);
        g.entity(
                state,
                scale,
                new Vector3f(0, .30f, 0),
                new Quaternionf().rotationZ((float) Math.PI).rotateX(-.28f),
                null,
                x - 45,
                y - 51,
                x + 45,
                y + 49);
    }

    private static void sparks(
            GuiGraphicsExtractor g, int cx, int bottom, int radius, float time, int rgb, boolean active) {
        for (int i = 0; i < (active ? 21 : 9); i++) {
            float age = (time * (active ? .93f : .46f) + i * .173f) % 1;
            double angle = i * 2.399963 + age * 1.1;
            int x = cx + (int) (Math.sin(angle) * radius * (.25 + age * .55));
            int y = bottom - (int) (age * (30 + i % 5 * 8));
            int alpha = (int) ((active ? 225 : 110) * Math.sin(Math.PI * age));
            if (alpha <= 0) continue;
            int color = alpha << 24 | (i % 4 == 0 ? 0xFFF1BE : rgb);
            g.fill(x, y, x + 1, y + 1, color);
            if (active && i % 3 == 0) {
                g.fill(x - 1, y, x + 2, y + 1, color);
                g.fill(x, y - 1, x + 1, y + 2, color);
            }
        }
    }
}
