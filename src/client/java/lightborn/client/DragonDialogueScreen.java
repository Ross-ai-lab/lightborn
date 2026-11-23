package lightborn.client;

import lightborn.DragonFavours;
import lightborn.DragonTalk;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** The Dragon speaks first; the player chooses when to ask for an adventure and when to accept it. */
public final class DragonDialogueScreen extends Screen {
    private final DragonTalk.Reply reply;
    private static final int GOLD = 0xFFFFD86B, IVORY = 0xFFFFF4D6;

    public DragonDialogueScreen(DragonTalk.Reply reply) {
        super(Component.literal(reply.speaker().isEmpty() ? "The Dragon" : reply.speaker()));
        this.reply = reply;
    }

    private int panelW() {
        return Math.min(380, width - 30);
    }

    private int panelH() {
        return Math.min(240, height - 24);
    }

    private int left() {
        return (width - panelW()) / 2;
    }

    private int top() {
        return (height - panelH()) / 2;
    }

    @Override
    protected void init() {
        int y = top() + panelH() - 12 - reply.choices().size() * 22;
        for (var choice : reply.choices()) {
            addRenderableWidget(Button.builder(Component.literal(choice.label()), b -> {
                        if (choice.action() < 0) onClose();
                        else ClientPlayNetworking.send(new DragonTalk.Answer(reply.dragon(), choice.action()));
                    })
                    .bounds(left() + 12, y, panelW() - 24, 20)
                    .build());
            y += 22;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
        g.fill(0, 0, width, height, 0xA0081014);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        int x = left(), y = top(), w = panelW(), h = panelH();
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x5076D5D9);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, GOLD);
        g.fill(x, y, x + w, y + h, 0xF015201F);
        g.text(font, getTitle().copy().withStyle(ChatFormatting.BOLD), x + 12, y + 12, GOLD, true);
        if (reply.speaker().isEmpty()) hearts(g, x + w - 12, y + 9, mx, my);
        g.horizontalLine(x + 12, x + w - 12, y + 26, 0xFF547E79);
        int lineY = y + 36;
        for (var line : font.split(Component.literal(reply.text()), w - 28)) {
            g.text(font, line, x + 14, lineY, IVORY, false);
            lineY += 10;
        }
        super.extractRenderState(g, mx, my, partial);
    }

    private static final Identifier CONTAINER = Identifier.withDefaultNamespace("hud/heart/container"),
            FULL = Identifier.withDefaultNamespace("hud/heart/full"),
            HALF = Identifier.withDefaultNamespace("hud/heart/half"),
            WITHERED = Identifier.withDefaultNamespace("hud/heart/withered_full"),
            WITHERED_HALF = Identifier.withDefaultNamespace("hud/heart/withered_half");

    /**
     * His warmth toward you, right-aligned at (right, y): disposition name, then five hearts. Gifts and errands fill
     * them in half-hearts; five full hearts warm him one step, three withered hearts cool him one step.
     */
    private void hearts(GuiGraphicsExtractor g, int right, int y, int mx, int my) {
        var player = minecraft == null ? null : minecraft.player;
        if (player == null || !player.hasAttached(DragonTalk.DISPOSITION)) return;
        var disposition = DragonTalk.Disposition.values()[player.getAttachedOrElse(DragonTalk.DISPOSITION, 1) - 1];
        int warmth = player.getAttachedOrElse(DragonFavours.WARMTH, 0);
        int left = right - 5 * 9;
        for (int i = 0; i < 5; i++) {
            int hx = left + i * 9;
            g.blitSprite(RenderPipelines.GUI_TEXTURED, CONTAINER, hx, y, 9, 9);
            int fill = Math.abs(warmth) - i * 2;
            if (fill <= 0) continue;
            boolean cold = warmth < 0;
            g.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    fill >= 2 ? (cold ? WITHERED : FULL) : (cold ? WITHERED_HALF : HALF),
                    hx,
                    y,
                    9,
                    9);
        }
        String name = switch (disposition) {
            case FRIENDLY -> "Friendly";
            case NEUTRAL -> "Neutral";
            case GUARDED -> "Guarded";
        };
        int nameX = left - 6 - font.width(name);
        g.text(font, name, nameX, y + 1, warmth < 0 ? 0xFFB9A7C9 : IVORY, true);
        if (mx >= nameX && mx < right && my >= y - 1 && my < y + 10) {
            String tip = disposition == DragonTalk.Disposition.FRIENDLY && warmth >= DragonFavours.WARM_STEP
                    ? "He could not be fonder of you."
                    : warmth < 0
                            ? "He is cooling toward you. Three withered hearts and he grows more guarded."
                            : disposition == DragonTalk.Disposition.FRIENDLY
                                    ? "Gifts and errands fill these hearts."
                                    : "Gifts and errands fill these hearts. Five full hearts and he grows friendlier.";
            g.setTooltipForNextFrame(font, font.split(Component.literal(tip), 200), mx, my);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
