package lightborn.client;

import lightborn.ChestTravel;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.Level;

/** A following camera and a short cloud veil; the terrain at each end remains the real world. */
public final class DragonTransitView {
    public static boolean phasing() {
        var mc = Minecraft.getInstance();
        if (mc.player == null
                || mc.level == null
                || !ChestTravel.flying(mc.player)
                || mc.level.dimension() != Level.NETHER) return false;
        return mc.level.getBlockState(mc.player.blockPosition()).isSolidRender()
                || mc.level
                        .getBlockState(mc.gameRenderer.mainCamera().blockPosition())
                        .isSolidRender();
    }

    private static CameraType previous;
    private static int ticks;

    static void tick() {
        var mc = Minecraft.getInstance();
        boolean riding = mc.player != null && ChestTravel.flying(mc.player);
        if (riding) {
            if (previous == null) {
                previous = mc.options.getCameraType();
                ticks = 0;
            }
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            if (++ticks == 8 && mc.player.getVehicle() != null) {
                mc.player.setYRot(mc.player.getVehicle().getYRot());
                mc.player.setXRot(15);
            }
            mc.options.keyShift.setDown(false);
        } else if (previous != null) {
            mc.options.setCameraType(previous);
            previous = null;
        }
    }

    static void draw(GuiGraphicsExtractor g) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int opacity = mc.player.getAttachedOrElse(ChestTravel.CLOUD, 0);
        if (opacity <= 0) return;
        int alpha = Math.min(255, (int) Math.ceil(opacity * 2.55));
        g.fill(0, 0, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), alpha << 24 | 0xF6F5E6);
    }
}
