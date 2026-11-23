package lightborn.client;

import java.util.ArrayList;
import java.util.Arrays;
import lightborn.WardAura;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * The ward's torchlight, drawn on this client only (owner, 2026-11-16). The server's real fill light must fall to
 * nothing at the dome so the night outside stays black, which leaves a small ward dim inside. Instead the client reads
 * every block inside a ward's sphere as lit to {@link #LEVEL}: terrain (vanilla's section compiler reads
 * {@code getLightValue}; Sodium copies whole {@link DataLayer}s) and creatures alike, with an exact edge at the dome.
 */
public final class WardGlow {
    private WardGlow() {}

    /** Torch-bright, one under a torch's own block. */
    public static final int LEVEL = 14;

    /** x, y, z, radius per ward in the client's world; swapped whole, read from render worker threads. */
    private static volatile double[] wards = new double[0];
    /** The client world's block-light layer; the light mixin answers for this one engine only. */
    private static volatile Object engine;

    public static boolean isClientBlockEngine(Object e) {
        return e != null && e == engine;
    }

    /** Once a client tick: refresh the ward list and rebuild the terrain around any ward that appeared, moved or grew. */
    public static void tick(Minecraft mc) {
        if (mc.level == null) {
            wards = new double[0];
            engine = null;
            return;
        }
        engine = mc.level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        var found = new ArrayList<double[]>();
        for (var e : mc.level.entitiesForRendering())
            if (e instanceof WardAura w && w.radius() > 0)
                found.add(new double[] {w.getX(), w.getY(), w.getZ(), w.radius()});
        found.sort((a, b) -> Double.compare(a[0] * 31 + a[2], b[0] * 31 + b[2]));
        double[] next = new double[found.size() * 4];
        for (int i = 0; i < found.size(); i++) System.arraycopy(found.get(i), 0, next, i * 4, 4);
        double[] old = wards;
        if (Arrays.equals(old, next)) return;
        wards = next;
        dirty(mc, old);
        dirty(mc, next);
    }

    private static void dirty(Minecraft mc, double[] list) {
        for (int i = 0; i < list.length; i += 4) {
            int r = (int) Math.ceil(list[i + 3]) + 1;
            int x = (int) Math.floor(list[i]), y = (int) Math.floor(list[i + 1]), z = (int) Math.floor(list[i + 2]);
            mc.level.setSectionRangeDirty(
                    SectionPos.blockToSectionCoord(x - r),
                    SectionPos.blockToSectionCoord(y - r),
                    SectionPos.blockToSectionCoord(z - r),
                    SectionPos.blockToSectionCoord(x + r),
                    SectionPos.blockToSectionCoord(y + r),
                    SectionPos.blockToSectionCoord(z + r));
        }
    }

    /** The glow at a block: {@link #LEVEL} inside any ward's sphere, else 0. */
    public static int at(int x, int y, int z) {
        double[] list = wards;
        double bx = x + .5, by = y + .5, bz = z + .5;
        for (int i = 0; i < list.length; i += 4) {
            double dx = bx - list[i], dy = by - list[i + 1], dz = bz - list[i + 2], r = list[i + 3];
            if (dx * dx + dy * dy + dz * dz <= r * r) return LEVEL;
        }
        return 0;
    }

    public static int at(BlockPos pos) {
        return at(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Whether any part of this 16-block section lies inside a ward. */
    public static boolean touches(SectionPos s) {
        double[] list = wards;
        for (int i = 0; i < list.length; i += 4) {
            double r = list[i + 3];
            double cx = Math.clamp(list[i], s.minBlockX(), s.maxBlockX() + 1);
            double cy = Math.clamp(list[i + 1], s.minBlockY(), s.maxBlockY() + 1);
            double cz = Math.clamp(list[i + 2], s.minBlockZ(), s.maxBlockZ() + 1);
            double dx = cx - list[i], dy = cy - list[i + 1], dz = cz - list[i + 2];
            if (dx * dx + dy * dy + dz * dz <= r * r) return true;
        }
        return false;
    }

    /** A copy of a section's block light with every block inside a ward raised to the glow. */
    public static DataLayer raise(SectionPos s, DataLayer layer) {
        DataLayer out = layer == null ? new DataLayer() : layer.copy();
        int bx = s.minBlockX(), by = s.minBlockY(), bz = s.minBlockZ();
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int glow = at(bx + x, by + y, bz + z);
                    if (glow > out.get(x, y, z)) out.set(x, y, z, glow);
                }
        return out;
    }
}
