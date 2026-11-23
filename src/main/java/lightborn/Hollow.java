package lightborn;

import java.util.Map;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

/**
 * The Hollow (owner, 2026-11-21: the End "becomes the Hollow"). Its land is bleached bone-white and silent under a
 * starless sky ({@code tools/make_hollow.py}), its kinds are the dark ones (Darkmen, Darkmites, Dark Shulkers, the Dark
 * Dragon: named here, skinned by the client's {@code HollowLook} only while the player is there), it has no Dark
 * Fountains, its stone holds rare Spark Ore, and near every edge the void pulls at the player.
 */
public final class Hollow {
    private Hollow() {}

    public static Block SPARK_ORE;

    /**
     * Each Ender kind's name in the Hollow (owner, 2026-11-21: "the endermen become darkmen there, etc"). The dragon
     * lives only there, so its name is changed everywhere instead (the boss bar reads the vanilla key).
     */
    public static final Map<String, String> DARK_NAMES = Map.of(
            "minecraft:enderman", "darkman",
            "minecraft:endermite", "darkmite",
            "minecraft:shulker", "dark_shulker",
            "lightborn:giant_enderman", "giant_darkman",
            "mutantmonsters:mutant_enderman", "mutant_darkman");

    /** The pull reaches this many blocks from an edge, strongest at one block. */
    public static final int REACH = 3;

    /** Added to a player's speed each tick at full strength: about 1.4 blocks a second of drift when standing still. */
    public static final double PULL = .03;

    /** An edge is a column with nothing to stand on for this many blocks below the player's feet. */
    static final int DROP = 24;

    static void init() {
        var key = ResourceKey.create(Registries.BLOCK, ModItems.id("spark_ore"));
        SPARK_ORE = Registry.register(
                BuiltInRegistries.BLOCK,
                key,
                new Block(BlockBehaviour.Properties.of()
                        .setId(key)
                        .mapColor(MapColor.SAND)
                        .instrument(NoteBlockInstrument.BASEDRUM)
                        .requiresCorrectToolForDrops()
                        .strength(3f, 9f)
                        .lightLevel(s -> 3)
                        .sound(SoundType.STONE)));
        ModItems.register(
                "spark_ore", p -> new BlockItem(SPARK_ORE, p), new Item.Properties().useBlockDescriptionPrefix());
    }

    /** Spark Ore in every Hollow biome. */
    static void initWorld() {
        BiomeModifications.addFeature(
                BiomeSelectors.foundInTheEnd(),
                GenerationStep.Decoration.UNDERGROUND_ORES,
                ResourceKey.create(Registries.PLACED_FEATURE, ModItems.id("ore_spark")));
    }

    public static boolean in(Level level) {
        return level.dimension() == Level.END;
    }

    /** An Ender kind's dark name while it stands in the Hollow, else null. */
    public static Component darkName(Entity e) {
        if (!in(e.level())) return null;
        String dark =
                DARK_NAMES.get(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        return dark == null ? null : Component.translatable("entity.lightborn." + dark);
    }

    /** A player on their feet in the Hollow, walking (not flying, gliding, riding or watching). */
    public static boolean pulled(LivingEntity e) {
        return e instanceof Player p
                && in(p.level())
                && p.onGround()
                && !p.getAbilities().flying
                && !p.isSpectator()
                && !p.isFallFlying()
                && !p.isPassenger();
    }

    /**
     * The void pulls (owner, 2026-11-21): within {@link #REACH} blocks of an island's edge the player is drawn toward
     * the drop, harder the closer they stand, summed over the eight directions so a narrow ridge pulls both ways.
     * Crouching holds them, since a crouching player never steps off an edge. Returns the pull's strength, 0 to 1.
     */
    public static double pull(Player p) {
        var level = p.level();
        double vx = 0, vz = 0, strongest = 0;
        int feet = p.getBlockY();
        for (int a = 0; a < 8; a++) {
            double dx = Math.cos(a * Math.PI / 4), dz = Math.sin(a * Math.PI / 4);
            for (int d = 1; d <= REACH; d++) {
                int x = (int) Math.floor(p.getX() + dx * d), z = (int) Math.floor(p.getZ() + dz * d);
                if (!open(level, x, feet, z)) break; // a wall: nothing to fall from that way
                if (!drop(level, x, feet, z)) continue;
                double w = (REACH + 1 - d) / (double) REACH;
                vx += dx * w;
                vz += dz * w;
                strongest = Math.max(strongest, w);
                break;
            }
        }
        double len = Math.sqrt(vx * vx + vz * vz);
        if (len < 1e-6) return 0;
        p.addDeltaMovement(new Vec3(vx / len * PULL * strongest, 0, vz / len * PULL * strongest));
        if (level.isClientSide()) wind(p, vx / len, vz / len, strongest);
        return strongest;
    }

    private static boolean open(Level level, int x, int y, int z) {
        var at = new BlockPos(x, y, z);
        return level.getBlockState(at).getCollisionShape(level, at).isEmpty();
    }

    private static boolean drop(Level level, int x, int feet, int z) {
        for (int y = feet - 1; y >= feet - DROP; y--) if (!open(level, x, y, z)) return false;
        return true;
    }

    /** Ash streaming toward the edge, and now and then the wind itself. */
    private static void wind(Player p, double dx, double dz, double strength) {
        var level = p.level();
        var random = level.getRandom();
        if (random.nextDouble() < .35 * strength)
            level.addParticle(
                    ParticleTypes.WHITE_ASH,
                    p.getX() - dx * 2 + random.nextGaussian(),
                    p.getY() + random.nextDouble() * 1.6,
                    p.getZ() - dz * 2 + random.nextGaussian(),
                    dx * .2,
                    0,
                    dz * .2);
        if (random.nextInt(60) == 0)
            level.playLocalSound(
                    p.getX() + dx * 3,
                    p.getY() + 1,
                    p.getZ() + dz * 3,
                    SoundEvents.BREEZE_IDLE_AIR,
                    SoundSource.AMBIENT,
                    .35f * (float) strength,
                    .55f,
                    false);
    }
}
