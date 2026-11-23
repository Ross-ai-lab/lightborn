package lightborn;

import java.lang.reflect.Method;
import net.minecraft.world.entity.Mob;

/**
 * Mob Variants (frycmobvariants) replaces a zombie, skeleton, spider or creeper on its first tick with a variant
 * (Forgotten, Undead Warrior and others): a new entity with a new UUID and none of Lightborn's attachments. A monster
 * Lightborn tracks by identity (a quest foe, a hunt or Cleanse hunter, a boss body, a nest guard, a raised spirit, a
 * summoned minion) would vanish
 * from its owner's list, so those are marked never to convert. Reflection keeps Lightborn free of a compile dependency;
 * without the mod this does nothing.
 */
public final class VariantCompat {
    private VariantCompat() {}

    private static final Class<?> CAN_CONVERT;
    private static final Method SET_FALSE;

    static {
        Class<?> type = null;
        Method off = null;
        try {
            type = Class.forName("net.fryc.frycmobvariants.util.mixin_interfaces.CanConvert");
            off = type.getMethod("setCanConvertToFalse");
        } catch (ReflectiveOperationException | LinkageError absent) {
            type = null;
            off = null;
        }
        CAN_CONVERT = type;
        SET_FALSE = off;
    }

    /** Keeps this mob its own kind: Mob Variants will not swap it for a variant. */
    public static <T extends Mob> T keepKind(T mob) {
        if (mob != null && CAN_CONVERT != null && CAN_CONVERT.isInstance(mob)) {
            try {
                SET_FALSE.invoke(mob);
            } catch (ReflectiveOperationException ignored) {
                // The mod changed its internals: the mob may convert, as it would have without this guard.
            }
        }
        return mob;
    }
}
