package lightborn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Pillars of light over dropped loot, coloured by rarity: a short glow for Uncommon, a tall beam for Rare,
 * a towering one plus a glowing outline for Legendary, and a still taller red one for Mythic. The beam rides on an entity tag, so it comes
 * back when the chunk reloads.
 */
public final class LootBeams {
    private LootBeams() {}

    static final String TAG = "lightborn_beam_";
    private static final int[] COLOR = {0xFFFFFF, 0x4F7DFF, 0xFFD84A, 0xFF7A1A, 0xFF3B3B};
    private static final double[] HEIGHT = {.5, 1.4, 5.0, 9.0, 12.0};
    /** Self-lit (full-bright) particles per rarity: cyan glow, white-yellow spark, orange wax. */
    private static final SimpleParticleType[] GLOW = {
        ParticleTypes.END_ROD,
        ParticleTypes.GLOW,
        ParticleTypes.ELECTRIC_SPARK,
        ParticleTypes.WAX_ON,
        ParticleTypes.FLAME
    };

    private static final List<ItemEntity> LIVE = new ArrayList<>();
    private static int tick;

    public static void onDrop(ItemEntity item, LootRoller.Rarity rarity) {
        if (item == null) return;
        item.addTag(TAG + rarity.ordinal());
        track(item);
        ServerLevel level = (ServerLevel) item.level();
        switch (rarity) {
            case RARE ->
                level.playSound(
                        null, item.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 2.0f, 1.2f);
            case LEGENDARY, MYTHIC -> {
                level.playSound(
                        null, item.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.5f, 1.6f);
                level.playSound(
                        null,
                        item.blockPosition(),
                        SoundEvents.AMETHYST_BLOCK_RESONATE,
                        SoundSource.PLAYERS,
                        2.0f,
                        0.8f);
            }
            default -> {}
        }
    }

    /** Re-attach a beam to an item that comes back with a chunk. */
    public static void onLoad(Entity entity) {
        if (entity instanceof ItemEntity item && rarityOf(item) >= 0) track(item);
    }

    private static void track(ItemEntity item) {
        if (rarityOf(item) >= LootRoller.Rarity.LEGENDARY.ordinal()) item.setGlowingTag(true);
        if (!LIVE.contains(item)) LIVE.add(item);
        while (LIVE.size() > 96) LIVE.remove(0);
    }

    static int rarityOf(ItemEntity item) {
        for (int i = 0; i < COLOR.length; i++) if (item.entityTags().contains(TAG + i)) return i;
        return -1;
    }

    public static int liveCount() {
        return LIVE.size();
    }

    public static void tick() {
        if (++tick % 4 != 0) return;
        for (Iterator<ItemEntity> it = LIVE.iterator(); it.hasNext(); ) {
            ItemEntity item = it.next();
            if (item.isRemoved()) {
                it.remove();
                continue;
            }
            int r = rarityOf(item);
            if (r < 0) {
                it.remove();
                continue;
            }
            ServerLevel level = (ServerLevel) item.level();
            // Dust carries the rarity colour in daylight, but it takes the world's light and vanishes
            // at night; the self-lit glow particles between them keep the pillar visible in the dark.
            DustParticleOptions dust = new DustParticleOptions(COLOR[r], r == 0 ? 0.8f : 1.4f);
            SimpleParticleType glow = GLOW[r];
            double x = item.getX(), y = item.getY() + 0.25, z = item.getZ();
            int step = 0;
            for (double h = 0; h < HEIGHT[r]; h += 0.4, step++) {
                if (step % 2 == 0) level.sendParticles(glow, true, true, x, y + h, z, 1, 0.01, 0.03, 0.01, 0);
                else level.sendParticles(dust, true, true, x, y + h, z, 1, 0.02, 0.05, 0.02, 0);
            }
            if (r == 2) {
                level.sendParticles(
                        ParticleTypes.END_ROD,
                        true,
                        true,
                        x,
                        y + level.getRandom().nextDouble() * HEIGHT[r],
                        z,
                        1,
                        0.1,
                        0.1,
                        0.1,
                        0.01);
            }
            if (r >= 1 && tick % 12 == 0) {
                level.sendParticles(dust, true, true, x, item.getY() + 0.05, z, 6, 0.35, 0.0, 0.35, 0);
            }
        }
    }

    public static void clear() {
        LIVE.clear();
    }
}
