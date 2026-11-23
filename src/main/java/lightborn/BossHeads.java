package lightborn;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Mounted boss heads: every first kill of a named boss also drops its head on a plaque. Heads are collectible
 * (Lightborn set).
 */
public final class BossHeads {
    private BossHeads() {}

    public static final Map<BossKind, Block> BLOCKS = new EnumMap<>(BossKind.class);

    static String id(BossKind kind) {
        return kind.id + "_head";
    }

    static void init() {
        for (BossKind kind : BossKind.ROSTER) {
            var key = ResourceKey.create(Registries.BLOCK, ModItems.id(id(kind)));
            var props = BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(1.2f)
                    .sound(SoundType.WOOD)
                    .noOcclusion();
            var block = Registry.register(BuiltInRegistries.BLOCK, key, new Head(props));
            BLOCKS.put(kind, block);
            String lore = "Trophy of " + kind.title + ".";
            ModItems.register(
                    id(kind),
                    p -> new BlockItem(block, p),
                    new Item.Properties()
                            .useBlockDescriptionPrefix()
                            .rarity(Rarity.EPIC)
                            .fireResistant()
                            .component(
                                    DataComponents.LORE,
                                    new ItemLore(List.of(Component.literal(lore)
                                            .withStyle(s ->
                                                    s.withItalic(false).withColor(ChatFormatting.LIGHT_PURPLE))))));
        }
    }

    public static boolean isHead(BlockState state) {
        return state.getBlock() instanceof Head;
    }

    /** First kill only: the head drops beside the trophy, with the same fallback into the inventory. */
    static void dropFirstKillHead(LivingEntity boss, BossKind kind, ServerLevel level, ServerPlayer fallback) {
        var head = new ItemStack(BLOCKS.get(kind).asItem());
        var drop = boss.spawnAtLocation(level, head);
        LootBeams.onDrop(drop, LootRoller.Rarity.LEGENDARY);
        if (drop == null && fallback != null && !fallback.getInventory().add(head))
            fallback.spawnAtLocation(level, head);
    }

    /** A plaque mounted on the wall behind it; the head faces whoever placed it. */
    public static final class Head extends HorizontalDirectionalBlock {
        private static final Map<Direction, VoxelShape> SHAPES = Map.of(
                Direction.NORTH, Shapes.or(Block.box(2, 2, 14, 14, 14, 16), Block.box(4, 4, 6, 12, 12, 14)),
                Direction.SOUTH, Shapes.or(Block.box(2, 2, 0, 14, 14, 2), Block.box(4, 4, 2, 12, 12, 10)),
                Direction.WEST, Shapes.or(Block.box(14, 2, 2, 16, 14, 14), Block.box(6, 4, 4, 14, 12, 12)),
                Direction.EAST, Shapes.or(Block.box(0, 2, 2, 2, 14, 14), Block.box(2, 4, 4, 10, 12, 12)));

        Head(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            // Clicking a wall mounts it there; otherwise it turns to face the player.
            Direction face = ctx.getClickedFace();
            Direction facing = face.getAxis().isHorizontal()
                    ? face
                    : ctx.getHorizontalDirection().getOpposite();
            return defaultBlockState().setValue(FACING, facing);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter l, BlockPos p, CollisionContext c) {
            return SHAPES.get(state.getValue(FACING));
        }
    }
}
