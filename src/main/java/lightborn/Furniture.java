package lightborn;

import com.mojang.math.Transformation;
import java.util.*;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Eight furnishings, one item each. */
public final class Furniture {
    private Furniture() {}

    public enum Kind {
        CHAIR,
        TABLE,
        COUCH,
        FRIDGE,
        WARDROBE,
        LAMP,
        RUG,
        SHELF;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        boolean storage() {
            return this == FRIDGE || this == WARDROBE || this == SHELF;
        }
    }

    public static final Map<Kind, Block> BLOCKS = new EnumMap<>(Kind.class);
    static BlockEntityType<Storage> STORAGE;
    public static EntityType<Seat> SEAT;

    static void init() {
        for (var kind : Kind.values()) {
            var key = ResourceKey.create(Registries.BLOCK, ModItems.id(kind.id()));
            var props = BlockBehaviour.Properties.of()
                    .setId(key)
                    .strength(1.8f)
                    .sound(SoundType.WOOD)
                    .noOcclusion();
            if (kind == Kind.LAMP) props.lightLevel(s -> 14);
            var block = Registry.register(BuiltInRegistries.BLOCK, key, new Furnishing(kind, props));
            BLOCKS.put(kind, block);
            ModItems.register(
                    kind.id(), p -> new BlockItem(block, p), new Item.Properties().useBlockDescriptionPrefix());
        }
        STORAGE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                ModItems.id("furniture_storage"),
                FabricBlockEntityTypeBuilder.create(
                                Storage::new, BLOCKS.values().toArray(Block[]::new))
                        .build());
        var seatKey = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("furniture_seat"));
        SEAT = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                seatKey,
                EntityType.Builder.<Seat>of(Seat::new, MobCategory.MISC)
                        .sized(.1f, .1f)
                        .clientTrackingRange(8)
                        .build(seatKey));
        UseBlockCallback.EVENT.register(Furniture::use);
    }

    public static Kind kind(BlockState state) {
        return state.getBlock() instanceof Furnishing f ? f.kind : null;
    }

    static InteractionResult use(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        var state = level.getBlockState(hit.getBlockPos());
        var kind = kind(state);
        if (kind == null || player.isShiftKeyDown()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
        var pos = hit.getBlockPos();
        if (kind == Kind.WARDROBE && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
            pos = pos.below();
        if (kind == Kind.CHAIR || kind == Kind.COUCH) {
            if (p.isPassenger()) return InteractionResult.SUCCESS_SERVER;
            var occupied = level.getEntitiesOfClass(Seat.class, new AABB(pos).inflate(.5)).stream()
                    .anyMatch(s -> !s.getPassengers().isEmpty());
            if (occupied) return InteractionResult.SUCCESS_SERVER;
            var seat = new Seat(SEAT, level);
            seat.anchor = pos;
            seat.setPos(pos.getX() + .5, pos.getY() + .1, pos.getZ() + .5);
            seat.setYRot(state.getValue(HorizontalDirectionalBlock.FACING).toYRot());
            level.addFreshEntity(seat);
            p.startRiding(seat, true, true);
            return InteractionResult.SUCCESS_SERVER;
        }
        if (level.getBlockEntity(pos) instanceof Storage storage) {
            if (kind != Kind.SHELF) {
                p.openMenu(storage);
                return InteractionResult.SUCCESS_SERVER;
            }
            var held = p.getItemInHand(hand);
            if (!held.isEmpty()) {
                for (int i = 0; i < 3; i++)
                    if (storage.getItem(i).isEmpty()) {
                        storage.setItem(i, held.copyWithCount(1));
                        if (!p.isCreative()) held.shrink(1);
                        break;
                    }
            } else
                for (int i = 2; i >= 0; i--)
                    if (!storage.getItem(i).isEmpty()) {
                        var item = storage.removeItemNoUpdate(i);
                        if (!p.getInventory().add(item)) p.spawnAtLocation(p.level(), item);
                        storage.setChanged();
                        break;
                    }
            return InteractionResult.SUCCESS_SERVER;
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    public static final class Furnishing extends HorizontalDirectionalBlock implements EntityBlock {
        final Kind kind;

        Furnishing(Kind kind, Properties props) {
            super(props);
            this.kind = kind;
            registerDefaultState(stateDefinition
                    .any()
                    .setValue(FACING, Direction.NORTH)
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING, BlockStateProperties.DOUBLE_BLOCK_HALF);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            if (kind == Kind.WARDROBE
                    && (ctx.getClickedPos().getY() >= ctx.getLevel().getMaxY() - 1
                            || !ctx.getLevel()
                                    .getBlockState(ctx.getClickedPos().above())
                                    .canBeReplaced(ctx))) return null;
            return defaultBlockState()
                    .setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        }

        @Override
        public void setPlacedBy(Level l, BlockPos pos, BlockState state, LivingEntity p, ItemStack s) {
            if (kind == Kind.WARDROBE)
                l.setBlock(
                        pos.above(),
                        state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                        Block.UPDATE_ALL);
        }

        @Override
        protected void affectNeighborsAfterRemoval(BlockState s, ServerLevel l, BlockPos pos, boolean moved) {
            if (kind == Kind.WARDROBE) {
                var other = s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                        ? pos.above()
                        : pos.below();
                if (l.getBlockState(other).is(this)) l.destroyBlock(other, false);
            }
            super.affectNeighborsAfterRemoval(s, l, pos, moved);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter l, BlockPos p, CollisionContext c) {
            var shape = switch (kind) {
                case RUG -> Block.box(0, 0, 0, 16, 1, 16);
                case CHAIR -> Shapes.or(Block.box(1, 0, 1, 15, 9, 15), Block.box(1, 9, 12, 15, 20, 16));
                case COUCH ->
                    Shapes.or(
                            Block.box(0, 0, 1, 16, 9, 16),
                            Block.box(0, 9, 12, 16, 15, 16),
                            Block.box(0, 9, 1, 3, 12, 13),
                            Block.box(13, 9, 1, 16, 12, 13));
                case TABLE -> Block.box(0, 0, 0, 16, 13, 16);
                case LAMP -> Block.box(3, 0, 3, 13, 16, 13);
                default -> Shapes.block();
            };
            int turns = switch (state.getValue(FACING)) {
                case EAST -> 1;
                case SOUTH -> 2;
                case WEST -> 3;
                default -> 0;
            };
            for (int i = 0; i < turns; i++) {
                var rotated = new VoxelShape[] {Shapes.empty()};
                shape.forAllBoxes((x0, y0, z0, x1, y1, z1) ->
                        rotated[0] = Shapes.or(rotated[0], Shapes.box(1 - z1, y0, x0, 1 - z0, y1, x1)));
                shape = rotated[0];
            }
            return shape;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos p, BlockState s) {
            return kind.storage()
                            && !(kind == Kind.WARDROBE
                                    && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                    ? new Storage(p, s)
                    : null;
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> type) {
            return l.isClientSide()
                    ? null
                    : (level, pos, state, be) -> {
                        if (be instanceof Storage storage && kind == Kind.SHELF && level.getGameTime() % 20 == 0)
                            storage.displays();
                    };
        }
    }

    public static final class Seat extends Entity {
        BlockPos anchor = BlockPos.ZERO;

        public Seat(EntityType<?> type, Level level) {
            super(type, level);
            setNoGravity(true);
            noPhysics = true;
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder b) {}

        @Override
        protected void readAdditionalSaveData(ValueInput in) {}

        @Override
        protected void addAdditionalSaveData(ValueOutput out) {}

        @Override
        public boolean hurtServer(ServerLevel l, DamageSource s, float n) {
            return false;
        }

        @Override
        public boolean shouldBeSaved() {
            return false;
        }

        @Override
        protected boolean canAddPassenger(Entity e) {
            return getPassengers().isEmpty();
        }

        @Override
        protected Vec3 getPassengerAttachmentPoint(Entity e, EntityDimensions dims, float scale) {
            return new Vec3(0, .4, 0);
        }

        @Override
        public Vec3 getDismountLocationForPassenger(LivingEntity p) {
            var front = level().getBlockState(anchor).hasProperty(HorizontalDirectionalBlock.FACING)
                    ? level().getBlockState(anchor).getValue(HorizontalDirectionalBlock.FACING)
                    : Direction.SOUTH;
            return position().add(front.getStepX(), .1, front.getStepZ());
        }

        @Override
        public void tick() {
            super.tick();
            if (!level().isClientSide()
                    && (getPassengers().isEmpty()
                            || !(kind(level().getBlockState(anchor)) == Kind.CHAIR
                                    || kind(level().getBlockState(anchor)) == Kind.COUCH))) discard();
        }
    }

    public static final class Storage extends BlockEntity implements Container, MenuProvider {
        private NonNullList<ItemStack> items;
        private List<UUID> displayIds = new ArrayList<>();

        Storage(BlockPos pos, BlockState state) {
            super(STORAGE, pos, state);
            items = NonNullList.withSize(kind(state) == Kind.SHELF ? 3 : 27, ItemStack.EMPTY);
        }

        @Override
        public int getContainerSize() {
            return items.size();
        }

        @Override
        public boolean isEmpty() {
            return items.stream().allMatch(ItemStack::isEmpty);
        }

        @Override
        public ItemStack getItem(int i) {
            return items.get(i);
        }

        @Override
        public ItemStack removeItem(int i, int n) {
            var result = ContainerHelper.removeItem(items, i, n);
            if (!result.isEmpty()) setChanged();
            return result;
        }

        @Override
        public ItemStack removeItemNoUpdate(int i) {
            return ContainerHelper.takeItem(items, i);
        }

        @Override
        public void setItem(int i, ItemStack s) {
            items.set(i, s);
            s.limitSize(getMaxStackSize(s));
            setChanged();
        }

        @Override
        public void clearContent() {
            items.clear();
            setChanged();
        }

        @Override
        public boolean stillValid(Player p) {
            return level != null
                    && level.getBlockEntity(worldPosition) == this
                    && p.distanceToSqr(Vec3.atCenterOf(worldPosition)) <= 64;
        }

        @Override
        public boolean canPlaceItem(int i, ItemStack s) {
            return kind(getBlockState()) != Kind.FRIDGE || s.has(DataComponents.FOOD);
        }

        @Override
        public Component getDisplayName() {
            return Component.literal(kind(getBlockState()) == Kind.FRIDGE ? "Fridge · Food only" : "Wardrobe");
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
            return new StorageMenu(id, inv, this);
        }

        @Override
        protected void loadAdditional(ValueInput in) {
            super.loadAdditional(in);
            ContainerHelper.loadAllItems(in, items);
            displayIds = new ArrayList<>(
                    in.read("shelf_displays", UUIDUtil.CODEC.listOf()).orElse(List.of()));
        }

        @Override
        protected void saveAdditional(ValueOutput out) {
            super.saveAdditional(out);
            ContainerHelper.saveAllItems(out, items);
            out.store("shelf_displays", UUIDUtil.CODEC.listOf(), displayIds);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (level instanceof ServerLevel && kind(getBlockState()) == Kind.SHELF) displays();
        }

        void displays() {
            if (!(level instanceof ServerLevel l)
                    || !l.areEntitiesActuallyLoadedAndTicking(
                            new ChunkPos(worldPosition.getX() >> 4, worldPosition.getZ() >> 4))) return;
            while (displayIds.size() < 3) displayIds.add(UUID.randomUUID());
            var facing = getBlockState().getValue(HorizontalDirectionalBlock.FACING);
            var right = facing.getClockWise();
            for (int i = 0; i < 3; i++) {
                var old = l.getEntity(displayIds.get(i));
                if (items.get(i).isEmpty()) {
                    if (old != null) old.discard();
                    continue;
                }
                Display.ItemDisplay entity = old instanceof Display.ItemDisplay d ? d : null;
                if (entity == null) {
                    entity = EntityTypes.ITEM_DISPLAY.create(l, EntitySpawnReason.COMMAND);
                    displayIds.set(i, entity.getUUID());
                    entity.addTag("lightborn_shelf_display");
                    l.addFreshEntity(entity);
                    super.setChanged();
                }
                entity.setPos(
                        worldPosition.getX() + .5 + right.getStepX() * (i - 1) * .29 + facing.getStepX() * .2,
                        worldPosition.getY() + .62,
                        worldPosition.getZ() + .5 + right.getStepZ() * (i - 1) * .29 + facing.getStepZ() * .2);
                entity.setItemStack(items.get(i).copy());
                entity.setItemTransform(ItemDisplayContext.FIXED);
                entity.setTransformation(new Transformation(
                        new Vector3f(),
                        new Quaternionf().rotationY((float) Math.toRadians(-facing.toYRot())),
                        new Vector3f(.34f),
                        new Quaternionf()));
            }
        }

        @Override
        public void preRemoveSideEffects(BlockPos pos, BlockState state) {
            if (level instanceof ServerLevel l) {
                Containers.dropContents(l, pos, this);
                for (var id : displayIds) {
                    var e = l.getEntity(id);
                    if (e != null) e.discard();
                }
            }
            super.preRemoveSideEffects(pos, state);
        }
    }

    static final class StorageMenu extends ChestMenu {
        StorageMenu(int id, Inventory inv, Storage storage) {
            super(MenuType.GENERIC_9x3, id, inv, storage, 3);
            for (int i = 0; i < 27; i++) {
                var previous = slots.get(i);
                var slot = new Slot(storage, i, previous.x, previous.y) {
                    @Override
                    public boolean mayPlace(ItemStack s) {
                        return storage.canPlaceItem(getContainerSlot(), s);
                    }
                };
                slot.index = i;
                slots.set(i, slot);
            }
        }
    }
}
