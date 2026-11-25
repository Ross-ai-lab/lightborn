package lightborn;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Food drains steadily, with an extra cost for health actually recovered. */
public final class SteadyFood {
    private SteadyFood() {}

    private static final AttachmentType<Float> HEALING_EXHAUSTION = AttachmentRegistry.create(
            ModData.id("healing_exhaustion"), b -> b.persistent(Codec.FLOAT).copyOnDeath());

    static void init() {}

    public static void healed(ServerPlayer player, float recovered) {
        if (recovered <= 0 || player.isSpectator() || player.hasInfiniteMaterials()) return;
        float cost = player.getAttachedOrElse(HEALING_EXHAUSTION, 0f) + recovered * .6f;
        int points = (int) (cost / 4);
        player.setAttached(HEALING_EXHAUSTION, cost - points * 4);
        if (points > 0) drain(player, points);
    }

    private static void drain(ServerPlayer player, int points) {
        var food = player.getFoodData();
        food.setFoodLevel(Math.max(0, food.getFoodLevel() - points));
        food.setSaturation(Math.min(food.getSaturationLevel(), food.getFoodLevel()));
    }

    public static void tick(MinecraftServer server) {
        for (var p : server.getPlayerList().getPlayers()) tick(p);
    }

    static void tick(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || p.hasInfiniteMaterials()) return;
        int elapsed = p.getAttachedOrElse(ModData.FOOD_TIMER, 0) + 1;
        if (elapsed >= Balance.FOOD_DRAIN_TICKS) {
            drain(p, 1);
            elapsed = 0;
        }
        p.setAttached(ModData.FOOD_TIMER, elapsed);
    }
}
