package lightborn.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drifting embers and arcane motes between the panorama and the logo/buttons. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    private static final int EMBERS = 90;
    private static final int[] WARM = {0xFFB347, 0xFF8A2A, 0xFFD27A};
    private static final int[] ARCANE = {0xC9A0FF, 0x9B6BFF};

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(
            method = "extractRenderState",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/screens/TitleScreen;extractPanorama(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V",
                            shift = At.Shift.AFTER))
    private void lightborn$embers(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        double t = (System.nanoTime() / 1_000_000_000.0) % 100_000.0;
        int w = this.width, h = this.height;
        // Warm glow rising from the bottom edge.
        g.fillGradient(0, h - h / 3, w, h, 0x00000000, 0x40FF6A1A);
        for (int i = 0; i < EMBERS; i++) {
            double r1 = hash(i * 3 + 1), r2 = hash(i * 3 + 2), r3 = hash(i * 3 + 3);
            double speed = 7 + r2 * 16;
            double span = h + 30;
            double y = h + 10 - ((t * speed + r3 * span) % span);
            double x = r1 * w + Math.sin(t * (0.4 + r2 * 0.5) + r3 * 6.283) * (6 + r1 * 10);
            boolean arcane = r2 > 0.72;
            int rgb = arcane ? ARCANE[i % ARCANE.length] : WARM[i % WARM.length];
            double life = Math.max(0, Math.min(1, y / (h * 0.85)));
            double flicker = 0.55 + 0.45 * Math.sin(t * (4 + r1 * 5) + r3 * 20);
            int a = (int) (255 * life * flicker * (arcane ? 0.8 : 1.0));
            if (a <= 4) continue;
            float size = r3 > 0.9 ? 4.0f : r3 > 0.5 ? 2.6f : 1.8f;
            g.pose().pushMatrix();
            g.pose().translate((float) x, (float) y);
            g.pose().scale(size, size);
            g.fill(-1, -1, 2, 2, (a / 5) << 24 | rgb); // soft halo
            g.fill(0, 0, 1, 1, a << 24 | rgb);
            g.pose().popMatrix();
        }
    }

    private static double hash(int n) {
        long x = n * 0x9E3779B97F4A7C15L;
        x ^= x >>> 31;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 29;
        return (x >>> 11) / (double) (1L << 53);
    }
}
