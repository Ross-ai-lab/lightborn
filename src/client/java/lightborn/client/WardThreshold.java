package lightborn.client;

import java.util.Comparator;
import lightborn.SurfaceLights;
import lightborn.WardAura;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Crossing the ward's edge is never seamless (owner, 2026-11-15; 2026-11-16: five times as long, the same both ways,
 * day and night). Pressing through, the dome's light holds you like warm syrup: for about five seconds you barely
 * advance while gold creeps in from the edges of the screen, then it tears and you break through. Coming home is the
 * other way round: the ward pulls you in (owner, 2026-11-23: climbing into it from a mine below left you stuck). Leaving
 * at night, you hear a slow heartbeat and dark rain runs down the screen (owner, 2026-11-17: dark raindrops, not small
 * dots; 2026-11-23: half as many, and no darkened screen). Coming home at night ends in a soft chime.
 *
 * <p>All of it runs on the client: the local player's movement is the client's to shape, and the ward itself (the
 * synced {@link WardAura}) is already there. Other dimensions, spectators, creative flight and riding are left alone.
 */
final class WardThreshold {
    private WardThreshold() {}

    private static final Identifier MEMBRANE =
            Identifier.fromNamespaceAndPath("lightborn", "textures/gui/ward_membrane.png");
    private static final Identifier NIGHT =
            Identifier.fromNamespaceAndPath("lightborn", "textures/gui/night_vignette.png");
    /** Ticks of pressing through before the ward lets go: five seconds, either way. */
    private static final float HOLD_TICKS = 100;
    /**
     * The membrane is a band SKIN either side of the ward's radius: you are held from the side you came from, and sink
     * at most SINK into it (just past the radius) before it tears.
     */
    private static final double SKIN = .6, SINK = .7;

    /** 0..1: how far into the membrane's grip you are. */
    private static float pressure, lastPressure;
    /** 0..1: how unprotected the screen feels (eases in outside at night, out at home). */
    private static float danger, lastDanger;
    /** Counts down the white-gold flash when the membrane tears. */
    private static int flash;
    /** Which side of the membrane you were last clear of: 1 inside, -1 outside, 0 not yet known. */
    private static int side;

    private static int heartbeat, sinceBeat = 1000, sinceFree = 1000;
    private static boolean wasOutside;

    /** A drop of the dark's goo on the screen: where (gui pixels), how big, how old, how long it lasts, how fast. */
    private static final class Drop {
        float x, y, size, speed;
        int age, life;
    }

    private static final java.util.List<Drop> DROPS = new java.util.ArrayList<>();
    private static final java.util.Random RANDOM = new java.util.Random();
    /** Dark rain: a new drop about every two thirds of a second, never more than seven on the screen (owner, 2026-11-23: half). */
    private static final int DROP_EVERY = 14, DROP_MAX = 7;

    private static void ease() {
        pressure = Math.max(0, pressure - .1f);
        danger = Math.max(0, danger - .05f);
    }

    static void tick(Minecraft mc) {
        lastPressure = pressure;
        lastDanger = danger;
        if (flash > 0) flash--;
        sinceBeat++;
        DROPS.removeIf(d -> ++d.age >= d.life);
        var p = mc.player;
        if (p == null
                || mc.level == null
                || !p.isAlive()
                || p.isSpectator()
                || p.getAbilities().flying
                || p.isPassenger()
                || p.isSleeping()
                || mc.level.dimension() != Level.OVERWORLD) {
            side = 0;
            ease();
            return;
        }
        boolean night = SurfaceLights.night(mc.level);
        Vec3 body = p.position().add(0, p.getBbHeight() / 2, 0);
        WardAura ward =
                mc.level.getEntitiesOfClass(WardAura.class, p.getBoundingBox().inflate(64), w -> !w.spell()).stream()
                        .min(Comparator.comparingDouble(a -> a.position().distanceTo(body) - a.radius()))
                        .orElse(null);
        if (ward != null && ward.suspended()) {
            // A Cleanse lifts this ward's shield: no membrane to hold you, and the night is already at the door.
            side = 0;
            ease();
            wasOutside = false;
            return;
        }
        double r = ward == null ? 0 : ward.radius(),
                d = ward == null ? 1e9 : ward.position().distanceTo(body);
        boolean inside = d <= r - SKIN, beyond = d >= r + SKIN;
        if (inside) side = 1;
        else if (beyond) side = -1;

        // Coming home, the ward pulls you in (owner, 2026-11-23): from a mine shaft below, its wall held you off the
        // stairs. Each tick in the membrane draws you toward its centre, with the tear's flash on first touch.
        if (ward != null && side < 0 && !inside && !beyond) {
            Vec3 in = ward.position().subtract(body).normalize();
            if (pressure == 0) {
                flash = 8;
                play(mc, SoundEvents.HONEY_BLOCK_BREAK, .5f, 1f);
                play(mc, SoundEvents.AMETHYST_BLOCK_BREAK, .55f, .8f);
            }
            pressure = .01f; // marks the pull as begun; too faint to draw
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x * .5 + in.x * .3, Math.max(v.y, in.y * .3), v.z * .5 + in.z * .3);
        } else if (ward != null && side > 0 && !inside && !beyond) {
            // Leaving, the membrane holds you: the body cannot pass deeper than the grip lets it; pressing on deepens
            // it until it tears.
            Vec3 out = body.subtract(ward.position()).normalize(), through = out.scale(side);
            double depth = side > 0 ? d - (r - SKIN) : (r + SKIN) - d;
            boolean pressing = p.getDeltaMovement().dot(through) > .01 || depth > SINK * pressure + .05;
            // Sprinting tears through twice as fast (owner, 2026-11-16); the sprint key counts, since the grip can
            // drop the sprint state itself.
            boolean sprint = p.isSprinting() || mc.options.keySprint.isDown();
            pressure = Mth.clamp(pressure + (pressing ? (sprint ? 2 : 1) / HOLD_TICKS : -1 / 10f), 0, 1);
            if (pressure >= 1) {
                breakThrough(mc, through, night);
            } else {
                double limit = side > 0 ? r - SKIN + SINK * pressure : r + SKIN - SINK * pressure;
                if (side > 0 ? d > limit : d < limit) {
                    Vec3 to = ward.position().add(out.scale(limit)).subtract(0, p.getBbHeight() / 2, 0);
                    p.setPos(to.x, Math.max(to.y, p.getY()), to.z);
                }
                Vec3 v = p.getDeltaMovement();
                double along = v.dot(through);
                if (along > 0)
                    p.setDeltaMovement(v.subtract(through.scale(along)).scale(.5));
                if (pressing && mc.level.getGameTime() % 5 == 0)
                    play(mc, SoundEvents.HONEY_BLOCK_SLIDE, .45f + .35f * pressure, .9f);
            }
        } else pressure = Math.max(0, pressure - .1f);

        boolean outside = ward == null || d > r + .3;
        // A Ward spell's dome shelters from the night as a home ward does: no dark drops, no vignette, no heartbeat
        // (owner, 2026-11-23). Its membrane does not grip, so only the inside counts.
        boolean sheltered = !mc.level
                .getEntitiesOfClass(
                        WardAura.class,
                        p.getBoundingBox().inflate(16),
                        w -> w.spell() && w.position().distanceTo(body) < w.radius())
                .isEmpty();
        boolean exposed = night && outside && side <= 0 && !sheltered;
        if (exposed) {
            danger = Math.min(1, danger + 1 / 30f);
            sinceFree++;
            // A heart pounding at first, slowing and fading as you settle into the dark.
            if (--heartbeat <= 0) {
                float fade = Mth.clamp(1 - sinceFree / 240f, .25f, 1);
                play(mc, SoundEvents.WARDEN_HEARTBEAT, .7f, .8f * fade);
                sinceBeat = 0;
                heartbeat = sinceFree < 120 ? 22 : 36;
            }
            if (DROPS.size() < DROP_MAX && RANDOM.nextInt(DROP_EVERY) == 0) {
                var drop = new Drop();
                var window = mc.getWindow();
                drop.x = RANDOM.nextFloat() * window.getGuiScaledWidth();
                drop.y = RANDOM.nextFloat() * window.getGuiScaledHeight() * .8f;
                drop.size = 2 + RANDOM.nextInt(3);
                drop.speed = .35f + RANDOM.nextFloat() * .55f;
                drop.life = 45 + RANDOM.nextInt(50);
                DROPS.add(drop);
            }
        } else {
            if (wasOutside && inside && night) {
                play(mc, SoundEvents.AMETHYST_BLOCK_CHIME, 1.3f, 1f);
                play(mc, SoundEvents.BEACON_POWER_SELECT, 1.6f, .35f);
            }
            danger = Math.max(0, danger - 1 / 15f);
            heartbeat = 0;
        }
        wasOutside = exposed;
    }

    private static void breakThrough(Minecraft mc, Vec3 through, boolean night) {
        var p = mc.player;
        boolean leaving = side > 0;
        side = -side;
        pressure = 0;
        lastPressure = 0;
        flash = 8;
        p.setDeltaMovement(p.getDeltaMovement().add(through.x * .45, .12, through.z * .45));
        play(mc, SoundEvents.HONEY_BLOCK_BREAK, .5f, 1f);
        play(mc, SoundEvents.AMETHYST_BLOCK_BREAK, .55f, .8f);
        if (leaving && night) {
            sinceFree = 0;
            heartbeat = 6;
            play(mc, SoundEvents.SCULK_CLICKING_STOP, .5f, .7f);
        }
    }

    private static void play(Minecraft mc, SoundEvent sound, float pitch, float volume) {
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    /** Drawn under the hotbar: the gold grip, the tear's flash, and the night's edges with their pulse. */
    static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        float t = delta.getGameTimeDeltaPartialTick(false);
        float press = Mth.lerp(t, lastPressure, pressure), dark = Mth.lerp(t, lastDanger, danger);
        int w = g.guiWidth(), h = g.guiHeight();
        long now = ClientState.tick;
        if (dark > 0) {
            // No darkened screen at night (owner, 2026-11-23); only the heartbeat: a red bloom at the edges with each
            // beat, strongest just after breaking free.
            float pulse = Math.max(0, 1 - (sinceBeat + t) / 9f) * Mth.clamp(1 - sinceFree / 240f, .3f, 1);
            if (pulse > 0) vignette(g, NIGHT, w, h, 1.15f, dark * pulse * .45f, 0x6A0010);
        }
        if (press > 0) {
            g.fill(0, 0, w, h, ((int) (press * 50) << 24) | 0x3A2400);
            float wobble = (float) Math.sin(now * .45) * .015f;
            vignette(g, MEMBRANE, w, h, 1.55f - .55f * press + wobble, Math.min(1, press * 1.1f), 0xFFFFFF);
        }
        if (flash > 0) {
            float f = (flash - t) / 8f;
            g.fill(0, 0, w, h, ((int) (Math.max(0, f) * 170) << 24) | 0xFFF0C8);
        }
        for (Drop drop : DROPS) goo(g, drop, t);
    }

    /**
     * One dark raindrop on the lens: a round near-black bead (wider in the middle rows, so it reads as a drop, not a
     * square), a dim grey glint at its upper left, and a streak of thinner dark water left above it as it runs down.
     * It fades in on landing and out at the end of its life.
     */
    private static void goo(GuiGraphicsExtractor g, Drop d, float partial) {
        float age = d.age + partial;
        float fade = Mth.clamp(Math.min(age / 4f, (d.life - age) / 18f), 0, 1);
        int r = Math.round(d.size);
        int x = Math.round(d.x), y = Math.round(d.y + age * d.speed);
        int trail = Math.min(18 + r * 4, Math.round(age * d.speed));
        for (int i = 1; i <= trail; i++) {
            float k = 1 - i / (float) (trail + 1);
            int a = Math.round(fade * 120 * k);
            int half = k > .55f && r > 2 ? 1 : 0;
            g.fill(x - half, y - r - i, x + 1 + half, y - r - i + 1, a << 24 | 0x0A0A10);
        }
        int body = Math.round(fade * 215) << 24;
        for (int row = -r; row <= r; row++) {
            int half = (int) Math.round(Math.sqrt(Math.max(0, r * r - row * row)));
            if (row < 0) half = Math.max(0, half - 1); // a slight point on top: a drop, not a ball
            g.fill(x - half, y + row, x + half + 1, y + row + 1, body | 0x07070B);
        }
        g.fill(x - r / 2, y - r / 2, x - r / 2 + 1, y - r / 2 + 1, Math.round(fade * 150) << 24 | 0x50505C);
    }

    /** One overlay texture stretched over the screen, scaled about its centre (above 1 pushes it off the edges). */
    private static void vignette(
            GuiGraphicsExtractor g, Identifier tex, int w, int h, float scale, float alpha, int rgb) {
        if (alpha <= 0) return;
        scale = Math.max(1, scale); // never smaller than the screen, so no edge is ever left uncovered
        int dw = Math.round(w * scale), dh = Math.round(h * scale);
        int x = (w - dw) / 2, y = (h - dh) / 2;
        int color = ((int) (Mth.clamp(alpha, 0, 1) * 255) << 24) | rgb;
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, dw, dh, 512, 512, 512, 512, color);
    }
}
