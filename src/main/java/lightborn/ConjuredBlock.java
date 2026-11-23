package lightborn;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * A block a spell raises for a while: Stone Wall's stone and Light Bridge's light. Its own scheduled tick takes it
 * away, so a wall or bridge never outlives its spell, even across a save and reload (the tick is saved with the
 * chunk). What it replaced (a plant, a pool of water, lava or goo) comes back when it goes, while the server runs; after
 * a restart it leaves air. It drops nothing and no item makes it.
 */
public class ConjuredBlock extends Block {
    public static Block STONE, LIGHT;

    /** What each conjured block replaced, by dimension and position. */
    static final Map<ResourceKey<Level>, Map<BlockPos, BlockState>> ORIGINAL = new HashMap<>();

    private final boolean light;

    ConjuredBlock(Properties props, boolean light) {
        super(props);
        this.light = light;
    }

    static void remember(ServerLevel level, BlockPos pos, BlockState old) {
        ORIGINAL.computeIfAbsent(level.dimension(), k -> new HashMap<>()).put(pos.immutable(), old);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        var map = ORIGINAL.get(level.dimension());
        BlockState back = map == null ? null : map.remove(pos);
        level.setBlock(pos, back == null ? Blocks.AIR.defaultBlockState() : back, Block.UPDATE_ALL);
        if (light)
            level.sendParticles(
                    ParticleTypes.END_ROD, pos.getX() + .5, pos.getY() + .7, pos.getZ() + .5, 2, .25, .1, .25, .01);
        else
            level.sendParticles(
                    new net.minecraft.core.particles.BlockParticleOption(
                            ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                    pos.getX() + .5,
                    pos.getY() + .5,
                    pos.getZ() + .5,
                    8,
                    .3,
                    .3,
                    .3,
                    .05);
        if (random.nextInt(4) == 0)
            level.playSound(
                    null,
                    pos,
                    light ? SoundEvents.AMETHYST_BLOCK_BREAK : SoundEvents.STONE_BREAK,
                    SoundSource.BLOCKS,
                    .5f,
                    1.2f);
    }

    private static Block register(String id, boolean light, BlockBehaviour.Properties props) {
        var key = ResourceKey.create(Registries.BLOCK, ModItems.id(id));
        return Registry.register(BuiltInRegistries.BLOCK, key, new ConjuredBlock(props.setId(key), light));
    }

    static void init() {
        STONE = register(
                "conjured_stone",
                false,
                BlockBehaviour.Properties.of()
                        .mapColor(MapColor.STONE)
                        .strength(1.5f, 6f)
                        .sound(SoundType.STONE)
                        .noLootTable()
                        .pushReaction(PushReaction.IMMOVEABLE));
        LIGHT = register(
                "light_bridge",
                true,
                BlockBehaviour.Properties.of()
                        .mapColor(MapColor.GOLD)
                        .strength(.3f, 6f)
                        .lightLevel(s -> 10)
                        .sound(SoundType.AMETHYST)
                        .noLootTable()
                        .pushReaction(PushReaction.IMMOVEABLE));
    }
}
