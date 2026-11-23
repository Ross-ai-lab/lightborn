package lightborn;

import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The cast in progress: who casts which spell from which tome, at what power. Captured so delayed effects (a meteor
 * landing, a pulse ticking) still read the right tome's numbers even if the player swaps tomes meanwhile.
 */
public final class CastContext {
    private CastContext() {}

    /** {@code upgrades}: the choice kept in each tier of the tome ({@link SpellUpgrades#chosen}). */
    record Cast(ServerPlayer player, Spell spell, ItemStack tome, double power, int[] upgrades) {}

    private static final ThreadLocal<Cast> CAST = new ThreadLocal<>();

    static boolean perform(ServerPlayer player, Spell spell, ItemStack tome, double power, BooleanSupplier work) {
        Cast before = CAST.get();
        int[] chosen = SpellUpgrades.chosen(tome);
        CAST.set(new Cast(player, spell, tome, power, chosen));
        SpellUpgrades.armed(player, spell, chosen);
        try {
            return work.getAsBoolean();
        } finally {
            if (before == null) CAST.remove();
            else CAST.set(before);
        }
    }

    /** The cast now running, or null outside one. */
    static Cast current() {
        return CAST.get();
    }

    static double power(ServerPlayer player, Spell spell, double fallback) {
        Cast cast = CAST.get();
        return cast == null || cast.player() != player || cast.spell() != spell ? fallback : cast.power();
    }
}
