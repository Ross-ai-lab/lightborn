package lightborn.client;

import lightborn.ChestStations;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class StationTab extends ChestTab {
    private final ChestStations.Station station;
    private Button open;

    StationTab(CollectionScreen screen, ChestStations.Station station) {
        super(screen);
        this.station = station;
    }

    @Override
    void init() {
        open = s.add(Button.builder(
                        Component.literal("Open " + station.title),
                        button -> ClientPlayNetworking.send(new ChestStations.Open(s.chest, station.ordinal())))
                .bounds(0, 0, 110, 18)
                .build());
    }

    @Override
    void layout(boolean on) {
        open.visible = on;
        open.setPosition(s.gridX() + (s.gridW() - open.getWidth()) / 2, s.top() + s.h() / 2 + 34);
    }

    @Override
    void draw(GuiGraphicsExtractor g, int mx, int my) {
        int x = s.gridX() + s.gridW() / 2, y = s.top() + s.h() / 2 - 10;
        g.fill(x - 17, y - 17, x + 17, y + 17, 0xFF796037);
        g.fill(x - 16, y - 16, x + 16, y + 16, 0xFF2E2515);
        g.item(new ItemStack(station.icon), x - 8, y - 8);
        g.centeredText(font(), station.description, x, y + 23, ChestStyle.IVORY);
    }
}
