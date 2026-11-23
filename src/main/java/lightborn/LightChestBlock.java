package lightborn;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Chest of Light: a glowing chest that wants one of every item in the world, for the Light, and keeps a stack of
 * each. Ctrl + right-click holding an item gives one (the owner's rule: a plain click must never give by accident);
 * a plain right-click opens its book, where things come back out. What it holds lives on the player (Collection).
 * It cannot be broken, pushed or blown up (owner, 2026-11-15): a home moves whole, through {@link HomeMove}.
 */
public class LightChestBlock extends HorizontalDirectionalBlock {
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);
    /** Opens the chest's book; set by the client. */
    public static Consumer<BlockPos> openScreen;
    /** Client: is Ctrl held? And: tell the server to take one of the held item. Set by the client. */
    public static BooleanSupplier ctrlDown = () -> false;

    public static Consumer<BlockPos> giveOne;

    public LightChestBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    /**
     * Giving runs here, ahead of vanilla, because vanilla skips a block's own click while the player sneaks with an
     * item in hand, and the owner's sneak key IS Left Ctrl (sneak and sprint swapped). So the client gives one when
     * Ctrl is held or the player sneaks, whatever key either is on; the server swallows a sneaking click on the chest
     * so the held block is never placed against it instead.
     */
    static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND
                || player.isSpectator()
                || !level.getBlockState(hit.getBlockPos()).is(ModItems.LIGHT_CHEST)) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            Collection.ensure(serverPlayer);
            LightWards.claim(serverPlayer.level(), hit.getBlockPos(), serverPlayer);
        }
        if (player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (level.isClientSide()) {
            if (!(ctrlDown.getAsBoolean() || player.isSecondaryUseActive()) || giveOne == null)
                return InteractionResult.PASS;
            giveOne.accept(hit.getBlockPos());
            return InteractionResult.SUCCESS;
        }
        return player.isSecondaryUseActive() ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    @Override
    protected InteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        // Never placing the held block against the chest: a plain click always opens the book.
        return hand == InteractionHand.MAIN_HAND ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide() && openScreen != null && !ctrlDown.getAsBoolean()) openScreen.accept(pos);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (level instanceof ServerLevel server && !old.is(this)) {
            LightWards.add(server, pos);
            placeLights(level, pos);
            Scheduler.later(10, () -> ChestGlow.refresh(server, pos));
            LightDragons.reconcile(server, pos);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity entity, ItemStack stack) {
        super.setPlacedBy(level, pos, state, entity, stack);
        if (entity instanceof ServerPlayer player) {
            Collection.ensure(player);
            LightWards.claim(player.level(), pos, player);
        }
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        placeLights(level, pos);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state, level, pos, moved);
        LightWards.remove(level, pos);
        LightDragons.remove(level, pos);
        ChestGlow.remove(level, pos);
        Scheduler.later(10, () -> ChestGlow.refresh(level, pos));
    }

    /** Restore static room and outer-ring fill after a source has been replaced. */
    static void placeLights(Level level, BlockPos pos) {
        if (level instanceof ServerLevel server) ChestGlow.place(server, pos);
    }

    /** Motes of light drift up from the lid. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(3) == 0) {
            level.addParticle(
                    ParticleTypes.END_ROD,
                    pos.getX() + 0.2 + random.nextDouble() * 0.6,
                    pos.getY() + 0.9,
                    pos.getZ() + 0.2 + random.nextDouble() * 0.6,
                    0,
                    0.02 + random.nextDouble() * 0.02,
                    0);
        }
        if (random.nextInt(4) == 0) {
            level.addParticle(
                    ParticleTypes.WAX_OFF,
                    pos.getX() + random.nextDouble(),
                    pos.getY() + random.nextDouble() * 0.9,
                    pos.getZ() + random.nextDouble(),
                    0,
                    0,
                    0);
        }
    }
}
