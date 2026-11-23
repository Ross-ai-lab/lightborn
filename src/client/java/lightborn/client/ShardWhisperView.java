package lightborn.client;

import lightborn.DarkShards;
import lightborn.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Carried Dark Shards press in from the screen's edges: a slow, breathing violet-black dark that creeps in as the
 * shards are picked up and grows with how many there are (owner, 2026-11-16).
 */
public final class ShardWhisperView {
    private ShardWhisperView() {}

    private static float shown;

    static void draw(GuiGraphicsExtractor g, float partial) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int n = mc.player.getInventory().countItem(ModItems.DARK_SHARD);
        float target = n < DarkShards.WHISPER_FROM ? 0 : Math.min(1f, .35f + (n - DarkShards.WHISPER_FROM) * .09f);
        // Creeps in and ebbs out over a few seconds rather than snapping.
        shown += (target - shown) * .01f;
        if (shown < .01f) return;
        double time = (mc.player.tickCount + partial) / 20.0;
        float breath = (float) (.8 + .2 * Math.sin(time * 1.3));
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        int depth = (int) (Math.min(w, h) * (.16f + .14f * shown));
        int peak = (int) (200 * shown * breath);
        int rgb = 0x14001F;
        g.fillGradient(0, 0, w, depth, peak << 24 | rgb, rgb);
        g.fillGradient(0, h - depth, w, h, rgb, peak << 24 | rgb);
        // The sides fade in strips, since a gradient fill runs top to bottom only.
        int strips = 24;
        for (int i = 0; i < strips; i++) {
            float f = 1f - i / (float) strips;
            int alpha = (int) (peak * f * f);
            int x0 = depth * i / strips, x1 = depth * (i + 1) / strips;
            g.fill(x0, 0, x1, h, alpha << 24 | rgb);
            g.fill(w - x1, 0, w - x0, h, alpha << 24 | rgb);
        }
    }
}
