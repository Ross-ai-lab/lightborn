package lightborn;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Food is a clock, independent of movement, mining, combat, effects and natural healing. */
public final class SteadyFood {
    private SteadyFood() {}

    public static void tick(MinecraftServer server) {
        for (var p : server.getPlayerList().getPlayers()) tick(p);
    }

    static void tick(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || p.hasInfiniteMaterials()) return;
        int elapsed = p.getAttachedOrElse(ModData.FOOD_TIMER, 0) + 1;
        if (elapsed >= Balance.FOOD_DRAIN_TICKS) {
            var food = p.getFoodData();
            food.setFoodLevel(Math.max(0, food.getFoodLevel() - 1));
            food.setSaturation(Math.min(food.getSaturationLevel(), food.getFoodLevel()));
            elapsed = 0;
        }
        p.setAttached(ModData.FOOD_TIMER, elapsed);
    }
}
