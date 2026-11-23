package lightborn;

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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;

/**
 * Emberwalk's crust: lava hardened under a walker's feet. Its own scheduled tick melts it back (saved with the chunk,
 * so it melts after a reload too): to a lava source where it was one, else to air, so walking never makes new lava. A
 * player standing on it keeps it hard. It drops nothing and no item makes it.
 */
public class EmberCrust extends Block {
    public static Block CRUST;
    public static final BooleanProperty SOURCE = BooleanProperty.create("source");
    /** How long a crust holds once nobody stands on it. */
    static final int HOLD_TICKS = 60, RECHECK_TICKS = 20;

    EmberCrust(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(SOURCE, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SOURCE);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.getEntitiesOfClass(Player.class, new AABB(pos.above()).inflate(.3, 0, .3), Player::isAlive)
                .isEmpty()) {
            level.scheduleTick(pos, this, RECHECK_TICKS);
            return;
        }
        level.setBlock(
                pos,
                state.getValue(SOURCE) ? Blocks.LAVA.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        level.sendParticles(ParticleTypes.LAVA, pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, 2, .3, 0, .3, 0);
        if (random.nextInt(3) == 0) level.playSound(null, pos, SoundEvents.LAVA_POP, SoundSource.BLOCKS, .5f, 1f);
    }

    static void init() {
        var key = ResourceKey.create(Registries.BLOCK, ModItems.id("ember_crust"));
        CRUST = Registry.register(
                BuiltInRegistries.BLOCK,
                key,
                new EmberCrust(BlockBehaviour.Properties.of()
                        .setId(key)
                        .mapColor(MapColor.NETHER)
                        .strength(2f, 6f)
                        .lightLevel(s -> 7)
                        .sound(SoundType.BASALT)
                        .noLootTable()
                        .pushReaction(PushReaction.IMMOVEABLE)));
    }
}
