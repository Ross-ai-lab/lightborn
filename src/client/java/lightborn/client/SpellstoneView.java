package lightborn.client;

import java.util.Arrays;
import java.util.List;
import lightborn.Spell;
import lightborn.Spellstone;
import lightborn.TomeItem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ScrollWheelHandler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.ItemSlotMouseAction;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Spellstone on the client (owner, 2026-11-18): its key casts, its five slots on the HUD beside the hotbar, the
 * slot panel its tooltip shows, scrolling over it to pick a slot, and the screen it opens from the main hand.
 */
public final class SpellstoneView {
    private SpellstoneView() {}

    /** A held key recasts this often, like a held right-click. */
    private static final int REPEAT = 4;
    /** Ticks each slot's key has been held; -1 waits for a release after Updraft threw at full charge. */
    private static final int[] HELD = new int[Spellstone.SLOTS];

    /** The slot picked by scrolling over a Spellstone, and the menu slot that stone sits in; -1 for none. */
    private static int pickedMenuSlot = -1, picked = -1;

    private static KeyMapping key(Minecraft mc, int slot) {
        return switch (slot) {
            case 0 -> mc.options.keyUse;
            case 1 -> LightbornClient.storyPower;
            case 2 -> mc.options.keyDrop;
            case 3 -> LightbornClient.dragonBreath;
            default -> LightbornClient.spellstoneFive;
        };
    }

    /**
     * At the start of each tick, before the game reads its own keys: with a staff and a Spellstone in hand, R, Q, V and C
     * cast slots two to five and are used up here, so Q never drops the staff and R and V call no Dragon power. A held
     * key recasts like a held right-click; Updraft gathers while its key is held and throws on release, or at full
     * charge.
     */
    static void tick(Minecraft mc) {
        var p = mc.player;
        if (p != null && mc.gui.screen() == null && lightborn.FormSpells.spiritSight(p)) {
            boolean returning = false;
            while (mc.options.keyDrop.consumeClick()) returning = true;
            if (returning)
                ClientPlayNetworking.send(new lightborn.DragonStory.Power(lightborn.DragonStory.RETURN_SPIRIT));
            Arrays.fill(HELD, 0);
            return;
        }
        if (p == null || mc.gui.screen() != null || !Spellstone.casting(p)) {
            Arrays.fill(HELD, 0);
            while (LightbornClient.spellstoneFive.consumeClick()) {}
            return;
        }
        var slots = Spellstone.tomes(p.getOffhandItem());
        for (int i = 1; i < Spellstone.SLOTS; i++) {
            var key = key(mc, i);
            boolean clicked = false;
            while (key.consumeClick()) clicked = true;
            boolean down = key.isDown();
            if (TomeItem.spellOf(slots.get(i)) == Spell.UPDRAFT) {
                if (HELD[i] < 0) {
                    if (!down) HELD[i] = 0;
                } else if (down) {
                    gather(mc, ++HELD[i]);
                    if (HELD[i] >= lightborn.StaffItem.UPDRAFT_CHARGE_TICKS) {
                        send(i, HELD[i]);
                        HELD[i] = -1;
                    }
                } else if (HELD[i] > 0 || clicked) {
                    send(i, HELD[i]);
                    HELD[i] = 0;
                }
                continue;
            }
            if (clicked || down && HELD[i] > 0 && HELD[i] % REPEAT == 0) send(i, 0);
            HELD[i] = down ? HELD[i] + 1 : 0;
        }
    }

    /**
     * Updraft gathering on a key: the same tightening ring of wind and rising breath as on the staff's right-click
     * (StaffItem.onUseTick), drawn here, since the server hears of a key cast only on its release.
     */
    private static void gather(Minecraft mc, int held) {
        if (held % 3 != 0 || held > lightborn.StaffItem.UPDRAFT_CHARGE_TICKS) return;
        var p = mc.player;
        float charge = Math.min(1f, held / (float) lightborn.StaffItem.UPDRAFT_CHARGE_TICKS);
        double r = 2.2 - 1.4 * charge;
        int n = 6 + (int) (8 * charge);
        for (int k = 0; k < n; k++) {
            double a = k * Math.PI * 2 / n;
            mc.level.addParticle(
                    net.minecraft.core.particles.ParticleTypes.SMALL_GUST,
                    p.getX() + Math.cos(a) * r,
                    p.getY() + .15,
                    p.getZ() + Math.sin(a) * r,
                    0,
                    0,
                    0);
        }
        mc.level.playLocalSound(
                p.getX(),
                p.getY(),
                p.getZ(),
                net.minecraft.sounds.SoundEvents.BREEZE_INHALE,
                net.minecraft.sounds.SoundSource.PLAYERS,
                .5f + .4f * charge,
                .7f + .8f * charge,
                false);
    }

    private static void send(int slot, int charge) {
        ClientPlayNetworking.send(new Spellstone.Cast(slot, charge));
    }

    /** A key's short name for a 16 px label: "RMB" for the right mouse button, else the key itself. */
    static String label(Minecraft mc, int slot) {
        String s = key(mc, slot).getTranslatedKeyMessage().getString();
        return s.equals("Right Button") ? "RMB" : s.length() > 3 ? s.substring(0, 3) : s;
    }

    /** The five slots left of the hotbar's off-hand slot, each with its key and its cooldown, while casting. */
    static void extractHud(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null || mc.gui.hud.isHidden() || !Spellstone.casting(p)) return;
        var slots = Spellstone.tomes(p.getOffhandItem());
        int y = g.guiHeight() - 20;
        int x0 = g.guiWidth() / 2 - 91 - 29 - Spellstone.SLOTS * 21;
        for (int display = 0; display < Spellstone.SLOTS; display++) {
            int i = display < 3 ? display : 7 - display;
            int x = x0 + display * 21;
            g.fill(x - 1, y - 1, x + 17, y + 17, 0x90000000);
            ItemStack tome = slots.get(i);
            if (!tome.isEmpty()) {
                g.item(tome, x, y);
                g.itemDecorations(mc.font, tome, x, y);
            }
            String k = label(mc, i);
            ModHud.small(g, mc.font, k, x + 8 - mc.font.width(k) * 3 / 8, y - 8, ModHud.GOLD);
        }
    }

    // ---- The bag: picking a slot by scrolling, and the panel its tooltip shows ----

    /** Picks (or with -1 forgets) a slot here and tells the server, which reads the pick when the click arrives. */
    private static void pick(int menuSlot, int slot) {
        pickedMenuSlot = slot < 0 ? -1 : menuSlot;
        picked = slot;
        var mc = Minecraft.getInstance();
        if (mc.player != null) Spellstone.pick(mc.player, menuSlot, slot);
        if (mc.getConnection() != null) ClientPlayNetworking.send(new Spellstone.Pick(menuSlot, slot));
    }

    /**
     * Scrolling over a Spellstone in any inventory picks the slot a click puts a tome into or takes one out of, the way
     * scrolling over a bundle picks an item. Added to every container screen (SpellstoneSlotMixin).
     */
    public static final class MouseActions implements ItemSlotMouseAction {
        private final ScrollWheelHandler wheel = new ScrollWheelHandler();

        @Override
        public boolean matches(Slot slot) {
            return Spellstone.is(slot.getItem());
        }

        @Override
        public boolean onMouseScrolled(double scrollX, double scrollY, int menuSlot, ItemStack stone) {
            var v = wheel.onMouseScroll(scrollX, scrollY);
            int step = v.y == 0 ? -v.x : v.y;
            if (step == 0) return true;
            int now = pickedMenuSlot == menuSlot ? picked : -1;
            int next = ScrollWheelHandler.getNextScrollWheelSelection(step, now, Spellstone.SLOTS);
            if (next != now) pick(menuSlot, next);
            return true;
        }

        @Override
        public void onStopHovering(Slot slot) {
            if (pickedMenuSlot == slot.index) pick(slot.index, -1);
        }

        @Override
        public void onSlotClicked(Slot slot, ContainerInput input) {
            if (input == ContainerInput.QUICK_MOVE || input == ContainerInput.SWAP) pick(slot.index, -1);
        }
    }

    /** The tooltip's slot panel: five slots with their tomes and keys, and the picked slot's tome by name. */
    public static final class Tooltip implements ClientTooltipComponent {
        private static final Identifier SLOT = Identifier.withDefaultNamespace("container/bundle/slot_background");
        private static final Identifier PICK_BACK =
                Identifier.withDefaultNamespace("container/bundle/slot_highlight_back");
        private static final Identifier PICK_FRONT =
                Identifier.withDefaultNamespace("container/bundle/slot_highlight_front");
        private static final int SIZE = 24, GAP = 3, ROW = Spellstone.SLOTS * SIZE + (Spellstone.SLOTS - 1) * GAP;

        private final List<ItemStack> tomes;

        public Tooltip(List<ItemStack> tomes) {
            this.tomes = tomes;
        }

        private static Component name(ItemStack tome) {
            return tome.isEmpty() ? Component.literal("Empty slot") : tome.getHoverName();
        }

        @Override
        public int getHeight(Font font) {
            return SIZE + 2 * (font.lineHeight + 2) + 2;
        }

        @Override
        public int getWidth(Font font) {
            int w = ROW;
            for (var t : tomes) w = Math.max(w, font.width(name(t)));
            return w;
        }

        @Override
        public boolean showTooltipWithItemInHand() {
            return true;
        }

        @Override
        public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor g) {
            Minecraft mc = Minecraft.getInstance();
            int x0 = x + (width - ROW) / 2;
            for (int i = 0; i < Spellstone.SLOTS; i++) {
                int sx = x0 + i * (SIZE + GAP);
                boolean on = i == picked;
                g.blitSprite(RenderPipelines.GUI_TEXTURED, on ? PICK_BACK : SLOT, sx, y, SIZE, SIZE);
                ItemStack tome = i < tomes.size() ? tomes.get(i) : ItemStack.EMPTY;
                if (!tome.isEmpty()) {
                    g.item(tome, sx + 4, y + 4);
                    g.itemDecorations(font, tome, sx + 4, y + 4);
                }
                if (on) g.blitSprite(RenderPipelines.GUI_TEXTURED, PICK_FRONT, sx, y, SIZE, SIZE);
                String k = label(mc, i);
                g.text(font, k, sx + SIZE / 2 - font.width(k) / 2, y + SIZE + 2, on ? 0xFFFFFFFF : ModHud.GOLD, true);
            }
            if (picked >= 0 && picked < tomes.size()) {
                var tome = tomes.get(picked);
                var line = name(tome);
                int color = tome.isEmpty() ? 0xFF808080 : 0xFFFFFFFF;
                g.text(font, line, x + (width - font.width(line)) / 2, y + SIZE + font.lineHeight + 5, color, true);
            }
        }
    }

    /** The Spellstone's slots as a screen: five tome slots, each labelled with the key that casts it. */
    public static final class Screen extends AbstractContainerScreen<Spellstone.Menu> {
        private static final Identifier HOPPER = Identifier.withDefaultNamespace("textures/gui/container/hopper.png");

        public Screen(Spellstone.Menu menu, Inventory inv, Component title) {
            super(menu, inv, title, 176, 133);
            inventoryLabelY = 10_000; // the key labels sit where its "Inventory" would
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            super.extractBackground(g, mouseX, mouseY, partial);
            int x = leftPos, y = topPos;
            g.blit(RenderPipelines.GUI_TEXTURED, HOPPER, x, y, 0, 0, 176, 133, 256, 256);
            g.fill(x + 27, y + 19, x + 149, y + 37, 0xFFC6C6C6); // the hopper's five slots, painted over
            Minecraft mc = Minecraft.getInstance();
            for (int i = 0; i < Spellstone.SLOTS; i++) {
                int sx = x + Spellstone.Menu.SLOT_X + i * Spellstone.Menu.SLOT_STEP - 1,
                        sy = y + Spellstone.Menu.SLOT_Y - 1;
                g.fill(sx, sy, sx + 18, sy + 18, 0xFF8B8B8B);
                g.fill(sx, sy, sx + 17, sy + 1, 0xFF373737);
                g.fill(sx, sy, sx + 1, sy + 17, 0xFF373737);
                g.fill(sx + 1, sy + 17, sx + 18, sy + 18, 0xFFFFFFFF);
                g.fill(sx + 17, sy + 1, sx + 18, sy + 18, 0xFFFFFFFF);
                String k = label(mc, i);
                g.text(font, k, sx + 9 - font.width(k) / 2, sy + 20, 0xFF404040, false);
            }
        }
    }
}
