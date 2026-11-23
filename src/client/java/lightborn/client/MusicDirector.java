package lightborn.client;

import lightborn.GameMusic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** Situational streams. Empty folders preserve vanilla music; discs use their normal record channel. */
public final class MusicDirector {
    private MusicDirector() {}

    public static String scene = "wilds";
    private static Track current;
    private static String playing = "";
    private static long ticks, started;

    public static String wanted() {
        var mc = Minecraft.getInstance();
        return scene.equals("finale") ? "finale" : mc.gui.screen() instanceof DragonDialogueScreen ? "dragon" : scene;
    }

    public static boolean overridesVanilla() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        var event = GameMusic.EVENTS.get(wanted());
        if (event == null) return false;
        var sounds = mc.getSoundManager().getSoundEvent(event.location());
        return sounds != null && sounds.getWeight() > 0;
    }

    public static void tick() {
        ticks++;
        var mc = Minecraft.getInstance();
        if (!overridesVanilla()) {
            if (current != null) current.fade = true;
            current = null;
            playing = "";
            return;
        }
        String mood = wanted();
        if (!mood.equals(playing)) {
            if (current != null) current.fade = true;
            current = null;
            playing = mood;
        }
        if (current == null || ticks - started > 25 && !mc.getSoundManager().isActive(current)) {
            current = new Track(mood);
            mc.getSoundManager().play(current);
            started = ticks;
        }
    }

    private static final class Track extends AbstractTickableSoundInstance {
        boolean fade;
        int age;

        Track(String mood) {
            super(GameMusic.EVENTS.get(mood), SoundSource.MUSIC, RandomSource.create());
            volume = .05f;
            pitch = 1;
            relative = true;
            attenuation = SoundInstance.Attenuation.NONE;
            looping = false;
        }

        @Override
        public void tick() {
            age++;
            if (fade) {
                volume -= .05f;
                if (volume <= 0) stop();
            } else volume = Math.min(1, age / 20f);
        }
    }
}
