package lightborn;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;

/**
 * Dark Goo, the sea of the Dark (owner, 2026-11-20: the Nether is the Dark, and its lava seas are goo). A real liquid
 * that flows like lava. A player who falls in sinks: they cannot swim up, only move sideways, slowly, and they drown
 * when their air runs out; pressed against a wall or a shore they can slowly haul themselves up and out. Monsters
 * are not troubled by it: they move in it as in water and never drown in it. Striders walk on it as on lava. It
 * gives a dim violet light (7, so its seas can be seen from the shore), cannot be scooped into a bucket, and burns nothing. The fountains' solid goo
 * ({@link DarkNests#GOO}) is another block.
 */
public final class DarkGoo {
    private DarkGoo() {}

    public static FlowingFluid SOURCE, FLOWING;
    public static Block BLOCK;
    /** Both goo fluids, so entities' fluid trackers and checks can name them in one go. */
    public static final TagKey<Fluid> TAG = TagKey.create(Registries.FLUID, ModItems.id("dark_goo"));

    /** How a sinking player moves: how much sideways speed is kept, the sink speeds, and the haul up a wall. */
    static final double SIDEWAYS = .45, SINK_SLOW = -.035, SINK_FAST = -.1, CLIMB = .07;

    static void init() {
        SOURCE = Registry.register(BuiltInRegistries.FLUID, ModItems.id("dark_goo"), new Goo.Source());
        FLOWING = Registry.register(BuiltInRegistries.FLUID, ModItems.id("flowing_dark_goo"), new Goo.Flowing());
        var key = ResourceKey.create(Registries.BLOCK, ModItems.id("liquid_dark_goo"));
        BLOCK = Registry.register(
                BuiltInRegistries.BLOCK,
                key,
                new Liquid(
                        SOURCE,
                        BlockBehaviour.Properties.of()
                                .setId(key)
                                .mapColor(MapColor.COLOR_BLACK)
                                .replaceable()
                                .noCollision()
                                .strength(100f)
                                .lightLevel(s -> 7)
                                .pushReaction(PushReaction.POPPED)
                                .noLootTable()
                                .liquid()
                                .sound(SoundType.EMPTY)));
    }

    /** How deep in goo an entity stands (0 when out of it). */
    public static double depth(net.minecraft.world.entity.Entity e) {
        return e.getFluidHeight(TAG);
    }

    /** Whether goo is a player's trap: anyone walking, not flying, a spectator or riding. */
    public static boolean sinks(LivingEntity e) {
        return e instanceof Player p
                && !p.getAbilities().flying
                && !p.isSpectator()
                && !p.isPassenger()
                && depth(p) > 0;
    }

    /**
     * A player in goo, before they move each tick: sideways speed bleeds away, and they sink whatever they press,
     * slowly near the top and faster below it; against a wall (they bumped one last tick) they haul themselves up.
     */
    public static void sink(LivingEntity p) {
        var v = p.getDeltaMovement();
        double vy = p.horizontalCollision ? CLIMB : Math.clamp(v.y, SINK_FAST, SINK_SLOW);
        p.setDeltaMovement(new Vec3(v.x * SIDEWAYS, vy, v.z * SIDEWAYS));
        p.resetFallDistance();
        p.setSprinting(false);
    }

    /** Goo where a monster stands counts as water for its movement only (owner, 2026-11-20: "they treat it like water"). */
    public static boolean swimsIn(net.minecraft.world.entity.Entity e) {
        return !(e instanceof Player) && depth(e) > 0;
    }

    /** The liquid's block: like lava's, except a bucket brings none of it up. */
    static final class Liquid extends LiquidBlock {
        Liquid(FlowingFluid fluid, Properties props) {
            super(fluid, props);
        }

        @Override
        public ItemStack pickupBlock(LivingEntity user, LevelAccessor level, BlockPos pos, BlockState state) {
            return ItemStack.EMPTY;
        }
    }

    abstract static class Goo extends FlowingFluid {
        @Override
        public Fluid getFlowing() {
            return FLOWING;
        }

        @Override
        public Fluid getSource() {
            return SOURCE;
        }

        @Override
        public Item getBucket() {
            return Items.AIR;
        }

        @Override
        protected boolean canConvertToSource(ServerLevel level) {
            return false;
        }

        @Override
        protected void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state) {
            Block.dropResources(state, level, pos, state.hasBlockEntity() ? level.getBlockEntity(pos) : null);
        }

        @Override
        protected int getSlopeFindDistance(LevelReader level) {
            return 2;
        }

        @Override
        protected int getDropOff(LevelReader level) {
            return 2;
        }

        @Override
        public int getTickDelay(LevelReader level) {
            return 20;
        }

        /** Nothing flows into goo: water and lava stop at its edge. */
        @Override
        protected boolean canBeReplacedWith(
                FluidState state, BlockGetter level, BlockPos pos, Fluid other, Direction direction) {
            return false;
        }

        @Override
        protected float getExplosionResistance() {
            return 100f;
        }

        @Override
        public BlockState createLegacyBlock(FluidState state) {
            return BLOCK.defaultBlockState().setValue(LiquidBlock.LEVEL, getLegacyLevel(state));
        }

        @Override
        public boolean isSame(Fluid other) {
            return other == SOURCE || other == FLOWING;
        }

        /** Now and then a slow bubble of dark breaks the surface. */
        @Override
        public void animateTick(Level level, BlockPos pos, FluidState state, RandomSource random) {
            if (!state.isSource()
                    || random.nextInt(80) != 0
                    || !level.getBlockState(pos.above()).isAir()) return;
            level.addParticle(
                    net.minecraft.core.particles.ParticleTypes.SQUID_INK,
                    pos.getX() + random.nextDouble(),
                    pos.getY() + 1,
                    pos.getZ() + random.nextDouble(),
                    0,
                    .02,
                    0);
        }

        static final class Flowing extends Goo {
            @Override
            protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
                super.createFluidStateDefinition(builder);
                builder.add(LEVEL);
            }

            @Override
            public int getAmount(FluidState state) {
                return state.getValue(LEVEL);
            }

            @Override
            public boolean isSource(FluidState state) {
                return false;
            }
        }

        static final class Source extends Goo {
            @Override
            public int getAmount(FluidState state) {
                return 8;
            }

            @Override
            public boolean isSource(FluidState state) {
                return true;
            }
        }
    }
}
