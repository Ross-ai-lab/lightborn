package lightborn;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Mana and life regeneration. There are no levels and no experience (owner, 2026-11-16): power comes from gear and
 * from Sparks spent on {@link SpellUpgrades}, spirits and the ward.
 */
public final class Progression {
    private Progression() {}

    public static double maxMana(ServerPlayer player) {
        return player.getAttributeValue(ModAttributes.MAX_MANA);
    }

    public static float mana(ServerPlayer player) {
        Float m = player.getAttached(ModData.MANA);
        return m == null ? (float) maxMana(player) : m;
    }

    public static void setMana(ServerPlayer player, float value) {
        float clamped = (float) Math.max(0, Math.min(maxMana(player), value));
        Float old = player.getAttached(ModData.MANA);
        boolean endpoint = clamped == 0 || clamped == (float) maxMana(player);
        if (old == null || endpoint && old.floatValue() != clamped || Math.abs(old - clamped) > 0.001f)
            player.setAttached(ModData.MANA, clamped);
    }

    /** Every 5 ticks: mana and life regeneration. Mana sync is batched to 4 updates per second. */
    static void tick(MinecraftServer server) {
        // Creative: the bar is always full (owner, 2026-11-18), topped up every tick so a cast never shows it drop.
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.isCreative() && player.isAlive()) setMana(player, (float) maxMana(player));
        if (Scheduler.now() % 5 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive()) continue;
            double regen = Balance.MANA_REGEN_PER_SECOND * player.getAttributeValue(ModAttributes.MANA_REGEN) / 4.0;
            float mana = mana(player);
            if (mana < maxMana(player)) setMana(player, (float) (mana + regen));
            else if (mana > maxMana(player)) setMana(player, (float) maxMana(player));
            double life = player.getAttributeValue(ModAttributes.LIFE_REGEN);
            if (life > 0 && player.getHealth() < player.getMaxHealth()) player.heal((float) (life / 4.0));
        }
    }
}
