package lightborn;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Floating numbers for hits a player lands. Vanilla text displays, so every client sees them
 * with no client code; each one rises for under a second and is removed.
 */
public final class DamageNumbers {
    private DamageNumbers() {}

    private static final int LIFE = 16;
    /** Marks our displays, so one left in a saved chunk (crash, chunk unload) is removed when it loads again. */
    static final String TAG = "lightborn_damage_number";

    private record Num(Display.TextDisplay display, int born) {}

    private static final List<Num> LIVE = new ArrayList<>();

    static void spawn(ServerLevel level, LivingEntity target, float amount, boolean crit) {
        if (LIVE.size() > 60) return;
        Display.TextDisplay d = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (d == null) return;
        String text = amount >= 100 ? String.valueOf(Math.round(amount)) : LootRoller.trim(amount);
        Component c = crit
                ? Component.literal("✦" + text).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                : Component.literal(text).withStyle(ChatFormatting.WHITE);
        d.setText(c);
        d.setBackgroundColor(0);
        d.setFlags(Display.TextDisplay.FLAG_SHADOW);
        d.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        d.setBrightnessOverride(new Brightness(15, 15));
        d.setPosRotInterpolationDuration(2);
        d.setViewRange(0.6f);
        float s = crit ? 1.6f : 1.0f;
        d.setTransformation(
                new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        double ox = (level.getRandom().nextDouble() - 0.5) * 0.8;
        double oz = (level.getRandom().nextDouble() - 0.5) * 0.8;
        d.setPos(target.getX() + ox, target.getY() + target.getBbHeight() + 0.2, target.getZ() + oz);
        d.addTag(TAG);
        LIVE.add(new Num(d, (int) Scheduler.now()));
        level.addFreshEntity(d);
    }

    static boolean isLive(Entity e) {
        for (Num n : LIVE) if (n.display() == e) return true;
        return false;
    }

    static void tick() {
        for (Iterator<Num> it = LIVE.iterator(); it.hasNext(); ) {
            Num n = it.next();
            Display.TextDisplay d = n.display();
            int age = (int) Scheduler.now() - n.born();
            if (d.isRemoved() || age > LIFE) {
                d.discard();
                it.remove();
                continue;
            }
            d.setPos(d.getX(), d.getY() + 0.06 * (1.0 - age / (double) LIFE) + 0.01, d.getZ());
        }
    }

    /** Removes all numbers (server stopping), so none are saved into the world. */
    static void clear() {
        for (Num n : LIVE) n.display().discard();
        LIVE.clear();
    }
}
