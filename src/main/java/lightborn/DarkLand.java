package lightborn;

import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The land round a Dark Nest (split from DarkNests, 2026-11-18): how far the dark reaches, what it turns soil, sand,
 * stone, water, grass, flowers and trees into, the creeping growth, and the healing wave that gives it all back when the
 * nest is cleared. DarkNests decides when; this class decides what the land becomes.
 */
public final class DarkLand {
    private DarkLand() {}

    /** Creeping growth over open ground round the core, 4.5 blocks a stage; never inside a ward. */
    public static void spread(ServerLevel level, BlockPos core, int stage) {
        darken(level, core, stage);
        var random = RandomSource.create(core.asLong() * 31 + stage);
        int r = (int) Math.round(stage * 4.5); // half again, with the dark land round it (owner, 2026-11-18)
        for (var pos : BlockPos.betweenClosed(core.offset(-r, -3, -r), core.offset(r, 3, r))) {
            int dx = pos.getX() - core.getX(), dz = pos.getZ() - core.getZ();
            if (dx * dx + dz * dz > r * r || random.nextFloat() > .3f || pos.equals(core)) continue;
            var at = pos.immutable();
            if (!level.getBlockState(at).isAir()
                    || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP)
                    || LightWards.covers(level, at)) continue;
            level.setBlockAndUpdate(at, DarkNests.GROWTH.defaultBlockState());
        }
    }

    /** How far the dark reaches: 24, 34, 44 and 54 blocks by stage (owner, 2026-11-18: half again as wide). */
    public static int darkRadius(int stage) {
        return (int) Math.round((stage * 3 + 4) * 3.375);
    }

    /**
     * The dark round a nest (owner, 2026-11-17; 2026-11-18: more kinds), out to {@link #darkRadius} and thickest near
     * the core: soil turns to sculk, mud, mycelium and coarse dirt, sand and gravel to soul sand and soul soil, bare
     * stone to blackstone and deepslate, and still water to goo. Grass withers, dry and brown at the edge, dead bushes
     * toward the heart; a few flowers turn to wither roses and the rest die; snow melts away. The trees die back, near
     * the heart most leaves fallen and the trunks charred, further out the crowns ashen in patches, with sculk veins
     * creeping over them. Never inside a ward. The healing wave gives the land back when the nest is cleared; the ashen
     * trees stay, a scar on the land.
     */
    static void darken(ServerLevel level, BlockPos core, int stage) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            darkenAway(level, core, stage);
            return;
        }
        var random = RandomSource.create(core.asLong() * 17 + stage);
        int r = darkRadius(stage);
        var tops = new ArrayList<BlockPos>();
        for (var pos : BlockPos.betweenClosed(core.offset(-r, -8, -r), core.offset(r, 24, r))) {
            int dx = pos.getX() - core.getX(), dz = pos.getZ() - core.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            // Rings, not confetti: sculk at the heart, mud and mycelium round it, patchy coarse dirt at the edge, the
            // borders blurred a little so they wander.
            double band = d / r + (random.nextFloat() - .5) * .22;
            if (d > r || band > .78 && random.nextFloat() < .55) continue;
            var at = pos.immutable();
            if (at.equals(core) || LightWards.covers(level, at)) continue;
            var state = level.getBlockState(at);
            if (state.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock
                    && state.getValue(net.minecraft.world.level.block.DoublePlantBlock.HALF)
                            == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                // Tall grass and tall flowers wither to a dry tuft at the edge.
                if (band >= .5 && random.nextFloat() < .6)
                    level.setBlock(at, Blocks.SHORT_DRY_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else if (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.FERN) || state.is(Blocks.BUSH)) {
                // Grass withers: dry and brown toward the edge, dead bushes toward the heart.
                level.setBlockAndUpdate(
                        at,
                        (band >= .5
                                        ? random.nextFloat() < .7 ? Blocks.SHORT_DRY_GRASS : Blocks.AIR
                                        : random.nextFloat() < .3 ? Blocks.DEAD_BUSH : Blocks.AIR)
                                .defaultBlockState());
            } else if (state.getBlock() instanceof net.minecraft.world.level.block.FlowerBlock
                    && !state.is(Blocks.WITHER_ROSE)) {
                // A few flowers turn to wither roses; the rest die.
                level.setBlockAndUpdate(
                        at,
                        (band < .6 && random.nextFloat() < .15 ? Blocks.WITHER_ROSE : Blocks.AIR).defaultBlockState());
            } else if (state.is(net.minecraft.tags.BlockTags.FLOWERS) && !state.is(net.minecraft.tags.BlockTags.LEAVES)
                    || state.is(Blocks.SNOW)) {
                if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock))
                    level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            } else if (state.is(Blocks.WATER)
                    && state.getFluidState().isSource()
                    && level.getFluidState(at.above()).isEmpty()) {
                // Still water's surface thickens to goo (lily pads sink); the water beneath stays water.
                if (band < .85) {
                    if (level.getBlockState(at.above()).is(Blocks.LILY_PAD))
                        level.setBlockAndUpdate(at.above(), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(at, DarkNests.GOO.defaultBlockState());
                }
            } else if (rock(state) && level.getBlockState(at.above()).canBeReplaced()) {
                // Bare stone blackens: blackstone at the heart, deepslate round it.
                if (band < .4) level.setBlockAndUpdate(at, Blocks.BLACKSTONE.defaultBlockState());
                else if (band < .75 && random.nextFloat() < .7)
                    level.setBlockAndUpdate(at, Blocks.DEEPSLATE.defaultBlockState());
            } else if (state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                // The trees die back (owner, 2026-11-17: the trees round a nest stood untouched): near the heart
                // most leaves fall and the rest turn ashen; further out the crowns go ashen in patches.
                if (band < .5 && random.nextFloat() < .55) level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                else if (band < .5 || random.nextFloat() < .55) level.setBlockAndUpdate(at, ashen(state));
            } else if (state.is(net.minecraft.tags.BlockTags.LOGS) && !state.is(Blocks.PALE_OAK_LOG)) {
                // The trunks blacken toward the heart, sculk veins on the rest (below).
                if (band < .35 && random.nextFloat() < .7) level.setBlockAndUpdate(at, charred(state));
            } else if (ground(state)) {
                var above = level.getBlockState(at.above());
                if (!above.isAir() && !above.canBeReplaced() && !above.is(net.minecraft.tags.BlockTags.LEAVES))
                    continue; // buried ground stays as it is
                level.setBlockAndUpdate(at, darkGround(state, band).defaultBlockState());
                if (level.getBlockState(at).is(Blocks.COARSE_DIRT)) tops.add(at.above());
            } else if (state.isAir()) {
                for (var dir : net.minecraft.core.Direction.values()) {
                    var side = level.getBlockState(at.relative(dir));
                    if ((side.is(net.minecraft.tags.BlockTags.LEAVES) || side.is(net.minecraft.tags.BlockTags.LOGS))
                            && random.nextFloat() < .4) {
                        level.setBlockAndUpdate(
                                at,
                                Blocks.SCULK_VEIN
                                        .defaultBlockState()
                                        .setValue(
                                                net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(dir),
                                                true));
                        break;
                    }
                }
            }
        }
        // A few dead bushes on the bare coarse dirt.
        for (var at : tops)
            if (random.nextFloat() < .2 && level.getBlockState(at).isAir())
                level.setBlockAndUpdate(at, Blocks.DEAD_BUSH.defaultBlockState());
    }

    /**
     * The dark round a Nether or End fountain (owner, 2026-11-20): its open floor turns to sculk at the heart and
     * deepslate round it, out to the same reach, and the plants on it wither away. Neither block grows there on its own,
     * so the healing wave can give the floor back without touching the dimension's own soul sand or blackstone.
     */
    private static void darkenAway(ServerLevel level, BlockPos core, int stage) {
        var random = RandomSource.create(core.asLong() * 17 + stage);
        int r = darkRadius(stage);
        for (var pos : BlockPos.betweenClosed(core.offset(-r, -8, -r), core.offset(r, 12, r))) {
            int dx = pos.getX() - core.getX(), dz = pos.getZ() - core.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            double band = d / r + (random.nextFloat() - .5) * .22;
            if (d > r || band > .78 && random.nextFloat() < .55) continue;
            var at = pos.immutable();
            if (at.equals(core) || !awayGround(level.getBlockState(at)) || LightWards.covers(level, at)) continue;
            var up = at.above();
            var above = level.getBlockState(up);
            if (!above.isAir() && !above.canBeReplaced()) continue; // buried floor stays as it is
            if (!above.isAir() && above.getFluidState().isEmpty())
                level.setBlock(up, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            if (band < .3) level.setBlockAndUpdate(at, Blocks.SCULK.defaultBlockState());
            else if (random.nextFloat() < .7) level.setBlockAndUpdate(at, Blocks.DEEPSLATE.defaultBlockState());
        }
    }

    /** The Dark's own floor, which the dark takes (and the Hollow's, for land darkened before its fountains went). */
    static boolean awayGround(BlockState state) {
        return state.is(Blocks.NETHERRACK)
                || state.is(net.minecraft.tags.BlockTags.NYLIUM)
                || state.is(Blocks.SOUL_SAND)
                || state.is(Blocks.SOUL_SOIL)
                || state.is(Blocks.BASALT)
                || state.is(Blocks.BLACKSTONE)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.END_STONE);
    }

    /** The floor a Nether or End spot heals back to: its biome's own (crimson or warped nylium, soul soil, basalt). */
    public static Block awayFloor(ServerLevel level, BlockPos at) {
        if (level.dimension() == net.minecraft.world.level.Level.END) return Blocks.END_STONE;
        var biome = level.getBiome(at);
        if (biome.is(net.minecraft.world.level.biome.Biomes.CRIMSON_FOREST)) return Blocks.CRIMSON_NYLIUM;
        if (biome.is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST)) return Blocks.WARPED_NYLIUM;
        if (biome.is(net.minecraft.world.level.biome.Biomes.SOUL_SAND_VALLEY)) return Blocks.SOUL_SOIL;
        if (biome.is(net.minecraft.world.level.biome.Biomes.BASALT_DELTAS)) return Blocks.BASALT;
        return Blocks.NETHERRACK;
    }

    /** Bare stone the dark blackens (the heal wave gives it back as stone). */
    static boolean rock(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.TUFF);
    }

    /** Open ground the dark takes: soil of every kind, sand, gravel and snow (the heal wave gives soil back as grass). */
    static boolean ground(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.PALE_MOSS_BLOCK)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.FARMLAND)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY);
    }

    /** By ring: sculk at the very heart; soil turns to mud, mycelium, coarse dirt; sand and gravel to soul sand and soil. */
    static Block darkGround(BlockState state, double band) {
        if (band < .22) return Blocks.SCULK;
        boolean sandy = state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL);
        if (sandy) return band < .55 ? Blocks.SOUL_SAND : Blocks.SOUL_SOIL;
        return band < .48 ? Blocks.MUD : band < .76 ? Blocks.MYCELIUM : Blocks.COARSE_DIRT;
    }

    /** A leaf turned ashen: pale oak leaves, keeping the tree's decay distance so it still falls if cut off. */
    static BlockState ashen(BlockState leaf) {
        var out = Blocks.PALE_OAK_LEAVES.defaultBlockState();
        for (var prop : leaf.getProperties()) if (out.hasProperty(prop)) out = copy(leaf, out, prop);
        return out;
    }

    /** A trunk charred black: a dark oak log of the same axis (stripped where it was stripped). */
    static BlockState charred(BlockState log) {
        boolean stripped =
                BuiltInRegistries.BLOCK.getKey(log.getBlock()).getPath().startsWith("stripped_");
        var out = (stripped ? Blocks.STRIPPED_DARK_OAK_LOG : Blocks.DARK_OAK_LOG).defaultBlockState();
        for (var prop : log.getProperties()) if (out.hasProperty(prop)) out = copy(log, out, prop);
        return out;
    }

    private static <T extends Comparable<T>> BlockState copy(
            BlockState from, BlockState to, net.minecraft.world.level.block.state.properties.Property<T> prop) {
        return to.setValue(prop, from.getValue(prop));
    }

    /** The wave reaches past the widest dark (54 blocks at stage 4) and rolls out over 15 seconds. */
    static final int HEAL_RADIUS = 56, HEAL_STEPS = 30, HEAL_TICKS = 300;

    /** Every heal ring at once (the self-test's shortcut through the 15-second wave). */
    static void healNow(ServerLevel level, BlockPos core) {
        for (int s = 0; s < HEAL_STEPS; s++) heal(level, core, s);
    }

    /**
     * One ring of the healing wave: dark growth, sculk veins, dead bushes and wither roses go, sculk, mud, mycelium and
     * coarse dirt turn back to grass, soul sand and soil to sand, blackstone and deepslate to stone, goo to water and
     * withered grass to green, and a little grass and a few flowers spring up on what was healed, with a glint of light.
     * Only the ring's own columns are walked, so the wider dark costs no more than the old.
     */
    static void heal(ServerLevel level, BlockPos core, int step) {
        if (!level.hasChunkAt(core)) return;
        double inner = HEAL_RADIUS * step / (double) HEAL_STEPS, outer = HEAL_RADIUS * (step + 1) / (double) HEAL_STEPS;
        boolean last = step == HEAL_STEPS - 1;
        var random = level.getRandom();
        int glints = 0;
        int r = (int) Math.ceil(outer);
        var at = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < inner || d > outer || d == outer && !last) continue;
                for (int dy = -8; dy <= 24; dy++) {
                    at.set(core.getX() + dx, core.getY() + dy, core.getZ() + dz);
                    if (healOne(level, at.immutable(), random) && glints++ < 10 && random.nextInt(3) == 0)
                        level.sendParticles(
                                ParticleTypes.HAPPY_VILLAGER,
                                at.getX() + .5,
                                at.getY() + 1.1,
                                at.getZ() + .5,
                                2,
                                .3,
                                .2,
                                .3,
                                0);
                }
            }
        if (step % 5 == 0)
            level.playSound(null, core, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1f, .8f + step * .02f);
    }

    /** What the dark left on top of the ground: cleared before the ground under it heals. */
    private static boolean litter(BlockState s) {
        return s.is(DarkNests.GROWTH) || s.is(Blocks.SCULK_VEIN) || s.is(Blocks.DEAD_BUSH) || s.is(Blocks.WITHER_ROSE);
    }

    /** Heals one block; true when something changed. */
    private static boolean healOne(ServerLevel level, BlockPos at, RandomSource random) {
        var state = level.getBlockState(at);
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            // The Nether and the End: the dark's litter goes and its sculk and deepslate turn back to the floor.
            if (litter(state)) {
                level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                return true;
            }
            if (!state.is(Blocks.SCULK) && !state.is(Blocks.DEEPSLATE)) return false;
            var above = level.getBlockState(at.above());
            if (litter(above)) level.setBlockAndUpdate(at.above(), Blocks.AIR.defaultBlockState());
            else if (!above.isAir() && !above.canBeReplaced()) return false;
            level.setBlockAndUpdate(at, awayFloor(level, at).defaultBlockState());
            return true;
        }
        if (state.is(DarkNests.GOO)) {
            level.setBlockAndUpdate(at, Blocks.WATER.defaultBlockState());
            return true;
        }
        if (litter(state)) {
            boolean rose = state.is(Blocks.WITHER_ROSE);
            level.setBlockAndUpdate(at, (rose ? Blocks.POPPY : Blocks.AIR).defaultBlockState());
            return true;
        }
        if (state.is(Blocks.SHORT_DRY_GRASS) && level.getBlockState(at.below()).is(Blocks.GRASS_BLOCK)) {
            level.setBlockAndUpdate(at, Blocks.SHORT_GRASS.defaultBlockState());
            return true;
        }
        Block healed = state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL)
                ? Blocks.SAND
                : state.is(Blocks.BLACKSTONE) || state.is(Blocks.DEEPSLATE)
                        ? Blocks.STONE
                        : state.is(Blocks.SCULK)
                                        || state.is(Blocks.MUD)
                                        || state.is(Blocks.MYCELIUM)
                                        || state.is(Blocks.COARSE_DIRT)
                                ? Blocks.GRASS_BLOCK
                                : null;
        if (healed == null) return false;
        var up = at.above();
        var above = level.getBlockState(up);
        if (litter(above)) {
            level.setBlockAndUpdate(up, Blocks.AIR.defaultBlockState());
            above = Blocks.AIR.defaultBlockState();
        }
        // Only open ground was darkened: ground under something solid was always this way.
        if (!above.isAir() && !above.canBeReplaced()) return false;
        level.setBlockAndUpdate(at, healed.defaultBlockState());
        if (healed == Blocks.GRASS_BLOCK && above.isAir() && random.nextFloat() < .25)
            level.setBlockAndUpdate(
                    up, (random.nextFloat() < .2 ? Blocks.POPPY : Blocks.SHORT_GRASS).defaultBlockState());
        return true;
    }
}
