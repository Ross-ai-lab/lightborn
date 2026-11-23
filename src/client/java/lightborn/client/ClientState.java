package lightborn.client;

import lightborn.ModAttributes;
import lightborn.ModData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** What the client knows: mana. Spell cooldowns are vanilla item cooldowns on the tomes. */
public final class ClientState {
    private ClientState() {}

    static long tick;

    static float mana() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return 0;
        Float m = p.getAttached(ModData.MANA);
        return m == null ? (float) maxMana() : m;
    }

    static double maxMana() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 100 : p.getAttributeValue(ModAttributes.MAX_MANA);
    }
}
