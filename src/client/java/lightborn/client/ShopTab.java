package lightborn.client;

import static lightborn.client.ChestStyle.*;

import java.util.ArrayList;
import java.util.List;
import lightborn.Balance;
import lightborn.LightShop;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The daily shop: six wares in two columns, and under them the ward's row (its size, the next and the Upgrade ward
 * button). Shown once the player has survived a night.
 */
final class ShopTab extends ChestTab {
    private static final int WARD_BTN_W = 130;

    private String message = "";
    private final List<Button> buy = new ArrayList<>();
    /** Widens the ward one step for Sparks. */
    private Button growWard;

    ShopTab(CollectionScreen s) {
        super(s);
    }

    void receive(LightShop.Show show) {
        if (!show.message().isEmpty()) message = show.message();
    }

    @Override
    void init() {
        buy.clear();
        for (int i = 0; i < 8; i++) {
            final int index = i;
            buy.add(s.add(Button.builder(Component.literal("Buy"), b -> {
                        if (s.shop != null) ClientPlayNetworking.send(new LightShop.Buy(s.chest, s.shop.day(), index));
                    })
                    .bounds(0, 0, 44, 16)
                    .build()));
        }
        growWard = s.add(Button.builder(Component.literal("Upgrade ward"), b -> {
                    if (s.shop != null)
                        ClientPlayNetworking.send(new LightShop.Buy(s.chest, s.shop.day(), LightShop.GROW_WARD));
                })
                .bounds(s.gridX() + s.gridW() - WARD_BTN_W - 4, s.top() + HEAD - 4, WARD_BTN_W, 18)
                .build());
    }

    @Override
    void opened() {
        ClientPlayNetworking.send(new LightShop.Ask(s.chest));
    }

    @Override
    void tick(int ticks) {
        if (ticks % 20 == 0) ClientPlayNetworking.send(new LightShop.Ask(s.chest));
    }

    private int cardW() {
        return (s.gridW() - 4) / 2;
    }

    private int cardH() {
        return (s.h() - HEAD - 30) / 4;
    }

    private int cardX(int i) {
        return s.gridX() + (i % 2) * (cardW() + 4);
    }

    private int cardY(int i) {
        return s.top() + HEAD + 13 + (i / 2) * cardH();
    }

    @Override
    void layout(boolean on) {
        var shop = s.shop;
        growWard.visible = on && shop != null;
        if (shop != null) {
            int tier = shop.wardTier();
            boolean max = tier >= Balance.WARD_PRICES.length;
            growWard.active =
                    !max && shop.sparks() >= Balance.WARD_PRICES[Math.min(tier, Balance.WARD_PRICES.length - 1)];
            // The price rides on the button itself (owner, 2026-11-16).
            growWard.setMessage(
                    Component.literal(max ? "Full size" : "Upgrade ward · " + sparks(Balance.WARD_PRICES[tier]))
                            .withStyle(st -> st.withColor(GOLD & 0xFFFFFF)));
            // The ward card is the fourth row, under the six wares (owner, 2026-11-16).
            growWard.setWidth(WARD_BTN_W);
            growWard.setPosition(s.gridX() + s.gridW() - WARD_BTN_W - 4, cardY(6) + cardH() - 22);
            fit(growWard, WARD_BTN_W, true);
        }
        for (int i = 0; i < buy.size(); i++) {
            Button b = buy.get(i);
            b.visible = on
                    && shop != null
                    && i < shop.offers().size()
                    && !shop.offers().get(i).stack().isEmpty();
            if (!b.visible) continue;
            var o = shop.offers().get(i);
            b.active = shop.sparks() >= o.price();
            // The price rides on the button itself (owner, 2026-11-16).
            b.setMessage(Component.literal(o.price() == 0 ? "Take · Free" : "Buy · " + sparks(o.price()))
                    .withStyle(st -> st.withColor(SHOP_BLUE & 0xFFFFFF)));
            b.setWidth(cardW() - 8);
            b.setPosition(cardX(i) + 4, cardY(i) + cardH() - 22);
        }
    }

    /** The fourth row: the ward's size, its next size and price, and the Upgrade ward button. */
    private void drawWardCard(GuiGraphicsExtractor g) {
        var shop = s.shop;
        int cx = s.gridX(), cy = cardY(6), cw = s.gridW(), ch = cardH();
        g.fill(cx, cy, cx + cw, cy + ch - 3, 0xFF5A4A22);
        g.fill(cx + 1, cy + 1, cx + cw - 1, cy + ch - 4, SLOT_IN);
        int tier = shop.wardTier(), radius = Balance.WARD_RADII[Math.min(tier, Balance.WARD_RADII.length - 1)];
        boolean max = tier >= Balance.WARD_PRICES.length;
        g.text(font(), "Your ward reaches " + radius + " blocks", cx + 6, cy + 4, GOLD, false);
        String next = max ? "At its full size." : "Next: " + Balance.WARD_RADII[tier + 1] + " blocks";
        g.text(font(), next, cx + 6, cy + 15, IVORY, false);
        // A pip per step: lit for those bought.
        for (int i = 0; i < Balance.WARD_PRICES.length; i++) {
            int px = cx + 6 + i * 12, py = cy + ch - 15;
            g.fill(px, py, px + 9, py + 6, 0xFF3D3320);
            g.fill(px + 1, py + 1, px + 8, py + 5, i < tier ? GOLD : SLOT_IN);
        }
    }

    @Override
    void draw(GuiGraphicsExtractor g, int mx, int my) {
        var shop = s.shop;
        int x = s.gridX(), y = s.top() + HEAD;
        // One quiet line, no title, Spark count or countdown (owner, 2026-11-16); Buy buttons grey out when short.
        if (shop == null) {
            g.text(font(), "Opening shop…", x, y, DIM, false);
            return;
        }
        g.text(font(), "New wares every dawn", x, y, IVORY, false);
        drawWardCard(g);
        for (int i = 0; i < shop.offers().size(); i++) {
            var o = shop.offers().get(i);
            if (o.stack().isEmpty()) continue;
            int cx = cardX(i), cy = cardY(i), cw = cardW(), ch = cardH();
            g.fill(cx, cy, cx + cw, cy + ch - 3, 0xFF3D3320);
            g.fill(cx + 1, cy + 1, cx + cw - 1, cy + ch - 4, SLOT_IN);
            g.item(o.stack(), cx + 4, cy + 4);
            var lines = font().split(
                            Component.literal(o.stack().getCount() + " × ")
                                    .append(o.stack().getHoverName()),
                            cw - 29);
            for (int line = 0; line < Math.min(2, lines.size()); line++)
                g.text(font(), lines.get(line), cx + 24, cy + 3 + 9 * line, IVORY, false);
            if (mx >= cx && mx < cx + cw && my >= cy && my < cy + ch - 20)
                g.setTooltipForNextFrame(font(), o.stack(), mx, my);
        }
        if (!message.isEmpty()) {
            var lines = font().split(Component.literal(message), s.gridW());
            if (!lines.isEmpty()) g.text(font(), lines.getFirst(), x, s.top() + s.h() - 13, IVORY, false);
        }
    }
}
