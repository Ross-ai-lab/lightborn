package lightborn;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Wayglow's mote of light: a small floating orb that lights its place like a torch, for good. Nothing walks into it,
 * a touch takes it down, and it drops nothing; no item makes it.
 */
public class GlowMote extends Block {
    public static Block MOTE;

    /** Bright Motes (Wayglow's upgrade): a mote twice the size, easier to find again. */
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty LARGE =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("large");

    private static final VoxelShape SHAPE = Block.box(6, 6, 6, 10, 10, 10),
            LARGE_SHAPE = Block.box(4, 4, 4, 12, 12, 12);

    GlowMote(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(LARGE, false));
    }

    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LARGE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(LARGE) ? LARGE_SHAPE : SHAPE;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(3) != 0) return;
        level.addParticle(
                ParticleTypes.END_ROD,
                pos.getX() + .5 + (random.nextDouble() - .5) * .3,
                pos.getY() + .5 + (random.nextDouble() - .5) * .3,
                pos.getZ() + .5 + (random.nextDouble() - .5) * .3,
                0,
                .01,
                0);
    }

    static void init() {
        var key = ResourceKey.create(Registries.BLOCK, ModItems.id("glow_mote"));
        MOTE = Registry.register(
                BuiltInRegistries.BLOCK,
                key,
                new GlowMote(BlockBehaviour.Properties.of()
                        .setId(key)
                        .mapColor(MapColor.GOLD)
                        .noCollision()
                        .noOcclusion()
                        .instabreak()
                        .lightLevel(s -> 15)
                        .sound(SoundType.AMETHYST_CLUSTER)
                        .noLootTable()
                        .pushReaction(PushReaction.POPPED)));
    }
}
