package lightborn;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;

/**
 * A plain spruce hut at the world spawn of every NEW world (owner: simple, 2026-11-09; roomy enough for the full-size
 * Dragon, 2026-11-15): one room 5 by 5 inside and five blocks high, solid walls, a bed, the lectern with the guide book
 * and the Chest of Light against the back wall. New players
 * arrive inside it, and players without a bed respawn in it. Worlds that already ran before this feature never get one.
 */
public final class SpawnHut {
    private SpawnHut() {}

    /** A world older than this (ticks) when first seen is treated as existing: no hut is dropped on it. */
    static final long NEW_WORLD_TICKS = 6000;

    /** Called when a player joins. Builds the hut once per world, then brings first-time players inside. */
    public static void onJoin(ServerPlayer player) {
        ServerLevel overworld = player.level().getServer().overworld();
        Boolean state = overworld.getAttached(ModData.HUT);
        if (state == null) {
            if (overworld.getGameTime() > NEW_WORLD_TICKS) {
                overworld.setAttached(ModData.HUT, false); // existing world: never build
            } else {
                build(overworld, findSite(overworld, overworld.getRespawnData().pos()), true);
                overworld.setAttached(ModData.HUT, true);
            }
            state = overworld.getAttached(ModData.HUT);
        }
        boolean arrived = Boolean.TRUE.equals(player.getAttached(ModData.ARRIVED));
        if (Boolean.TRUE.equals(state)) {
            LightWards.claim(overworld, lightChestAt(overworld.getRespawnData().pos()), player);
        }
        player.setAttached(ModData.ARRIVED, true);
        if (arrived) return;
        giveKit(player);
        if (!Boolean.TRUE.equals(state)) {
            return;
        }
        BlockPos inside = overworld.getRespawnData().pos();
        player.teleportTo(
                overworld, inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5, Set.of(), 180f, 10f, false);
        welcome(player);
    }

    /** The tome every new character starts with. */
    public static ItemStack starterTome() {
        return new ItemStack(ModItems.TOMES.get(Spell.ARCANE_BOLT));
    }

    /**
     * The Spellstone every new character starts with in the off hand (owner, 2026-11-18): the Bolt tome on right-click,
     * with Homeward and Ender Pocket (owner, 2026-11-23). The Book of Souls is found later, like any tome.
     */
    public static ItemStack starterStone() {
        return starterStone(null);
    }

    /**
     * The Spellstone with the Bolt and, when {@code random} is given, a second tome: one Basic (tier 1) spell drawn at
     * random (owner, 2026-11-23: "tome of arcane bolt and 1 random basic spell").
     */
    public static ItemStack starterStone(net.minecraft.util.RandomSource random) {
        var stone = new ItemStack(Spellstone.ITEM);
        var second = random == null ? ItemStack.EMPTY : new ItemStack(ModItems.TOMES.get(basicSpell(random)));
        Spellstone.store(
                stone,
                List.of(
                        starterTome(),
                        second,
                        new ItemStack(ModItems.TOMES.get(Spell.HOMEWARD)),
                        new ItemStack(ModItems.TOMES.get(Spell.ENDER_POCKET))));
        return stone;
    }

    /** A random Basic spell that drops (so never one given only), never one the stone already holds. */
    static Spell basicSpell(net.minecraft.util.RandomSource random) {
        var basic = java.util.Arrays.stream(Spell.values())
                .filter(s -> s.tier == 1 && s.drops && s != Spell.ARCANE_BOLT && s != Spell.ENDER_POCKET)
                .toList();
        return basic.get(random.nextInt(basic.size()));
    }

    public static BlockPos lightChestAt(BlockPos inside) {
        return inside.offset(0, 0, -2);
    }

    private static void giveKit(ServerPlayer player) {
        ItemStack staff = LootRoller.starterStaff(player.getRandom()), stone = starterStone(player.getRandom());
        if (player.getMainHandItem().isEmpty()) player.setItemInHand(InteractionHand.MAIN_HAND, staff);
        else give(player, staff);
        // The Bolt from the very start, inside the Spellstone (owner, 2026-11-18).
        if (player.getOffhandItem().isEmpty()) player.setItemInHand(InteractionHand.OFF_HAND, stone);
        else give(player, stone);
        give(player, new ItemStack(Items.APPLE, 5));
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.spawnAtLocation(player.level(), stack);
    }

    private static void welcome(ServerPlayer player) {
        // Fade in 2 s, hold 8 s, fade out 3 s (doubled 2026-11-09 so it reads as an opening card).
        player.connection.send(new ClientboundSetTitlesAnimationPacket(40, 160, 60));
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal("Lightborn").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        player.level()
                .playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.AMBIENT, 0.8f, 0.7f);
    }

    // ---------------------------------------------------------------- site

    /**
     * Floor of the hut: the most open, flattest dry ground within 32 blocks of world spawn. Ground
     * height ignores leaves; tree cover (leaves above the ground) and slope both count against a spot,
     * and so does distance. The floor goes at the HIGHEST ground point, so the builder fills a low
     * corner with a foundation instead of digging the hut into a pit.
     */
    public static BlockPos findSite(ServerLevel level, BlockPos spawn) {
        // Heights read from a chunk that is not loaded come back as the world floor, which would sink the
        // hut into the void; load (generating if needed) every chunk the scan touches first. Once per world.
        for (int cx = (spawn.getX() - 40) >> 4; cx <= (spawn.getX() + 40) >> 4; cx++) {
            for (int cz = (spawn.getZ() - 40) >> 4; cz <= (spawn.getZ() + 46) >> 4; cz++) level.getChunk(cx, cz);
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -32; dx <= 32; dx += 2) {
            for (int dz = -32; dz <= 32; dz += 2) {
                int x = spawn.getX() + dx, z = spawn.getZ() + dz;
                int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, canopy = 0;
                boolean wet = false;
                for (int sx = -6; sx <= 6 && !wet; sx += 2) {
                    for (int sz = -6; sz <= 10; sz += 2) {
                        int ground = groundAt(level, x + sx, z + sz);
                        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + sx, z + sz) - 1;
                        BlockPos g = new BlockPos(x + sx, ground, z + sz);
                        if (!level.getFluidState(g).isEmpty()
                                || !level.getFluidState(g.above()).isEmpty()) {
                            wet = true;
                            break;
                        }
                        // A trunk shows up as a spike in ground height; leaves as cover above it.
                        if (top > ground) canopy++;
                        min = Math.min(min, ground);
                        max = Math.max(max, ground);
                    }
                }
                if (wet) continue;
                double score = (max - min) * 6.0 + canopy * 1.5 + Math.sqrt(dx * dx + dz * dz) * 0.15;
                if (score < bestScore) {
                    bestScore = score;
                    best = new BlockPos(x, max, z);
                }
            }
        }
        if (best != null) return best;
        level.getChunk(spawn.getX() >> 4, spawn.getZ() >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()) - 1;
        return new BlockPos(spawn.getX(), y, spawn.getZ());
    }

    /** Highest natural ground block: steps down past logs, leaves and plants. */
    static int groundAt(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(
                x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        for (int i = 0; i < 40 && p.getY() > level.getMinY(); i++) {
            BlockState s = level.getBlockState(p);
            if (!(s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.canBeReplaced() || s.isAir())
                    || !s.getFluidState().isEmpty()) break;
            p.move(0, -1, 0);
        }
        return p.getY();
    }

    // ---------------------------------------------------------------- build

    /** Builds the hut with its floor at {@code o}; door faces south (+z). */
    /** {@code home}: also make it the world spawn (false for a showcase copy). */
    public static void build(ServerLevel level, BlockPos o, boolean home) {
        BlockState air = Blocks.AIR.defaultBlockState();
        // Quiet placement while shaping the land: no neighbour or shape updates, so plants and leaf
        // litter at the edges don't pop off as item drops.
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        // Clear trees and brush above the floor.
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 10; z++) {
                for (int y = 1; y <= 18; y++) {
                    BlockPos p = o.offset(x, y, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, air, quiet);
                }
            }
        }
        // Level a platform (hut on cobblestone, yard on grass) and taper its edges two blocks down
        // into the surrounding ground, so a low corner reads as a gentle mound, not a cliff.
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 10; z++) {
                int out = Math.max(Math.max(-3 - x, x - 3), Math.max(-3 - z, z - 8));
                out = Math.max(0, out);
                if (out > 2) continue;
                boolean hut = Math.abs(x) <= 3 && Math.abs(z) <= 3;
                int topY = -out;
                level.setBlock(
                        o.offset(x, topY, z),
                        hut ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(),
                        quiet);
                for (int d = topY - 1; d >= topY - 12; d--) {
                    BlockPos p = o.offset(x, d, z);
                    BlockState s = level.getBlockState(p);
                    if (!s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced()) break;
                    level.setBlock(
                            p, hut ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState(), quiet);
                }
            }
        }

        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState logY = Blocks.SPRUCE_LOG.defaultBlockState();
        // 7 by 7 shell, a 5 by 5 room with walls five blocks high: the Dragon's full shape fits over the chest.
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) set(level, o.offset(x, 0, z), planks);
        for (int y = 1; y <= 5; y++) {
            for (int x = -3; x <= 3; x++) {
                boolean corner = Math.abs(x) == 3;
                set(level, o.offset(x, y, -3), corner ? logY : planks);
                set(level, o.offset(x, y, 3), corner ? logY : planks);
            }
            for (int z = -2; z <= 2; z++) {
                set(level, o.offset(-3, y, z), planks);
                set(level, o.offset(3, y, z), planks);
            }
        }
        // Door (south wall), with a plank threshold under it
        set(level, o.offset(0, 0, 3), planks);
        BlockState door = Blocks.SPRUCE_DOOR
                .defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT);
        set(level, o.offset(0, 1, 3), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        set(level, o.offset(0, 2, 3), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));

        // Gable roof running east-west, dark oak, slab ridge, overhanging the front and back walls only: the gable
        // ends stop flush with the walls, so the roof stays inside the starting ward's dome (owner, 2026-11-17: the
        // overhang poked through it). The eaves start at the wall top, so the room keeps five blocks of headroom.
        BlockState stairS = Blocks.DARK_OAK_STAIRS
                .defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        BlockState stairN = Blocks.DARK_OAK_STAIRS
                .defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        for (int k = 0; k <= 3; k++) {
            int y = 5 + k;
            for (int x = -3; x <= 3; x++) {
                set(level, o.offset(x, y, -4 + k), stairS);
                set(level, o.offset(x, y, 4 - k), stairN);
            }
            if (k == 0) continue;
            for (int z = -3 + k; z <= 3 - k; z++) {
                set(level, o.offset(-3, y, z), planks);
                set(level, o.offset(3, y, z), planks);
            }
        }
        for (int x = -3; x <= 3; x++) set(level, o.offset(x, 9, 0), Blocks.DARK_OAK_SLAB.defaultBlockState());

        // Furniture along the back wall; the middle stays clear for the arrival spot (0, 1, 0), facing the chest.
        BlockState bed =
                Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        set(level, o.offset(-2, 1, -2), bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
        set(level, o.offset(-2, 1, -1), bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT));
        set(
                level,
                o.offset(2, 1, -2),
                Blocks.LECTERN
                        .defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                        .setValue(BlockStateProperties.HAS_BOOK, true));
        set(
                level,
                o.offset(0, 1, -2),
                ModItems.LIGHT_CHEST.defaultBlockState().setValue(LightChestBlock.FACING, Direction.SOUTH));

        // A plain front path.
        for (int z = 4; z <= 8; z++) set(level, o.offset(0, 0, z), Blocks.COARSE_DIRT.defaultBlockState());

        // Sweep up anything that still fell loose while building.
        for (var e : level.getEntitiesOfClass(ItemEntity.class, new AABB(o).inflate(12))) e.discard();
        if (level.getBlockEntity(o.offset(2, 1, -2)) instanceof LecternBlockEntity lectern)
            lectern.setBook(guideBook());

        // Home: spawn and bed-less respawn land inside, facing the lectern.
        BlockPos inside = o.offset(0, 1, 0);
        if (!home) return;
        level.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, inside, 180f, 0f));
        level.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, level.getServer());
    }

    private static void set(ServerLevel level, BlockPos p, BlockState s) {
        level.setBlock(p, s, Block.UPDATE_ALL);
    }

    // ---------------------------------------------------------------- book

    /**
     * The lectern guide (owner, 2026-11-18: story only, three pages at most): the Dragon's own account, told without
     * what his chapters reveal later, and no page of hints. Every page fits the book's 14 lines at 114 px
     * (PhaseSixClientTest checks each one).
     */
    public static ItemStack guideBook() {
        List<Filterable<Component>> pages = new ArrayList<>();
        pages.add(
                page(
                        h("The Dragon's Notes"),
                        t(
                                "\n\nI'm the Dragon under this world. Long ago I was chained deep beneath the stone.\n\nWhere my light can't reach, the dark gathers, and every night it gathers a little more.")));
        pages.add(
                page(
                        t(
                                "The dark stirs the dead and sends them walking. Some grow far bigger than they should. They hunt whatever still carries light.\n\nA little of mine slipped through my chain, and it runs in you now.")));
        pages.add(
                page(
                        t(
                                "The chest by the wall is my hearth. Its ward keeps the night off your home, so build inside it.\n\nI can't leave the stone. You can. Stay near the chest tonight, and I'll visit you.\n\n      the Dragon")));
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(
                DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough("The Dragon's Notes"), Lore.AUTHOR, 0, pages, true));
        Lore.signByDragon(book); // the tooltip reads only "by the Dragon", without vanilla's "Original"
        return book;
    }

    private static Filterable<Component> page(Component... parts) {
        MutableComponent all = Component.empty();
        for (Component p : parts) all.append(p);
        return Filterable.passThrough(all);
    }

    private static Component h(String s) {
        return Component.literal(s).withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD);
    }

    private static Component t(String s) {
        return Component.literal(s).withStyle(ChatFormatting.BLACK);
    }

    private static Component c(String s, ChatFormatting color) {
        return Component.literal(s).withStyle(color, ChatFormatting.BOLD);
    }
}
