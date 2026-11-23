package lightborn.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.entity.Entity;

/**
 * Owl Sight on this client (owner, 2026-11-21): the monsters the server lists for this player glow through walls on
 * this screen only ({@code OwlSightMixin}). The list comes every half second and lapses on its own if it stops coming.
 */
public final class OwlSightView {
    private OwlSightView() {}

    private static final Set<Integer> SHOWN = new HashSet<>();
    private static long until;

    public static void show(List<Integer> ids) {
        SHOWN.clear();
        SHOWN.addAll(ids);
        until = System.currentTimeMillis() + 1500;
    }

    public static boolean glows(Entity entity) {
        return !SHOWN.isEmpty() && System.currentTimeMillis() < until && SHOWN.contains(entity.getId());
    }
}
