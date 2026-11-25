package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBiomeTags;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Dark Fountains, called nests in the code and the save (owner, 2026-11-18: renamed for the player; the block id stays
 * {@code dark_nest} so old worlds keep theirs). Dark Nests (opened to everyone from the first night, owner 2026-11-16). Places to farm for loot (owner, 2026-11-17),
 * with no hold on the Cleanse. At the end of every night each home has a 1 in 2 chance to grow a new one, up to eight,
 * rooted 100 to 300 blocks out and 150 apart (owner, 2026-11-18). Every Dark Moon a nest grows a stage (to four) and creeping
 * growth spreads round it. By day its guards wake once when a player comes close, any kind of monster up to the huge
 * mutants, and the sun does not burn them; at night the nest is unguarded and any guards still standing crumble.
 * Breaking the core clears it and pays by stage; after that the home is left alone until the next Dark Moon. Each nest
 * sends up a pillar of black smoke the player can steer by ({@link #SMOKE}; drawn by {@code NestSmokeView}).
 *
 * <p>The Dark (the old Nether) grows its own (owner, 2026-11-20), round wherever a player roams rather than round a
 * home, guarded by its own monsters at any hour, since it has no night ({@link #roam}). Each level keeps its own
 * fountains. The Hollow (the old End) has none (owner, 2026-11-21; {@link #unroot}).
 */
public final class DarkNests {
    private DarkNests() {}

    static final int MAX_STAGE = 4, MOON_DAYS = 8;

    // ServerLevel attachments are saved per dimension. Older saves have no dimension split to migrate.
    static final AttachmentType<Integer> VICTORIES =
            AttachmentRegistry.create(ModData.id("fountain_victories"), builder -> builder.persistent(Codec.INT));

    static int victories(ServerLevel level) {
        return level.getAttachedOrElse(VICTORIES, 0);
    }

    private static boolean dark(ServerLevel level) {
        return level.dimension().equals(Level.NETHER);
    }
    /**
     * Where a nest roots: at least this far past the ward's edge, at most this far from the chest, this far from any
     * other nest; and how close a player comes before its day guards wake.
     */
    static final int PLANT_MIN = 100, PLANT_MAX = 300, SPACING = 150, GUARD_WAKE = 16, MAX_PER_HOME = 3;

    static final float DAWN_CHANCE = .5f;
    /** Marks a nest's guard: the sun spares it ({@link MireZombie}) and nightfall crumbles it. */
    public static final String GUARD_TAG = "lightborn_nest_guard";

    public static Block NEST, GROWTH, GOO;
    public static final IntegerProperty STAGE = IntegerProperty.create("stage", 1, MAX_STAGE);

    /** {@code guardNight} is the day its guards last woke (the save keeps the old field name). */
    record Nest(BlockPos core, BlockPos home, UUID owner, long bornMoon, long guardNight) {
        static final Codec<Nest> CODEC = RecordCodecBuilder.create(i -> i.group(
                        BlockPos.CODEC.fieldOf("core").forGetter(Nest::core),
                        BlockPos.CODEC.fieldOf("home").forGetter(Nest::home),
                        UUIDUtil.CODEC.fieldOf("owner").forGetter(Nest::owner),
                        Codec.LONG.fieldOf("born_moon").forGetter(Nest::bornMoon),
                        Codec.LONG.fieldOf("guard_night").forGetter(Nest::guardNight))
                .apply(i, Nest::new));

        int stage(ServerLevel level) {
            long moons = (Hordes.MoonNight.read(level.getServer().overworld()).latestHunt() - bornMoon) / MOON_DAYS;
            // The Dark's own grow from stage 2, two stages a Dark Moon (owner, 2026-11-20: stronger fountains there).
            if (level.dimension() == Level.NETHER) return (int) Math.clamp(2 + 2 * moons, 2, MAX_STAGE);
            return (int) Math.clamp(1 + moons, 1, MAX_STAGE);
        }
    }

    /** The nests near a player (the nearest few within {@link #SMOKE_RANGE}), for the smoke pillars over them. */
    public static final AttachmentType<List<BlockPos>> SMOKE = AttachmentRegistry.<List<BlockPos>>create(
            ModItems.id("nest_smoke"),
            b -> b.syncWith(BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), AttachmentSyncPredicate.targetOnly()));

    static final int SMOKE_RANGE = 768, SMOKE_MAX = 10;

    /** Once a second, from the story tick: which nests' smoke this player sees. */
    static void smoke(ServerPlayer p) {
        var pos = p.position();
        List<BlockPos> near = nests(p.level()).stream()
                .map(Nest::core)
                .filter(c -> horizontal(c, pos) < (double) SMOKE_RANGE * SMOKE_RANGE)
                .sorted(java.util.Comparator.comparingDouble(c -> horizontal(c, pos)))
                .limit(SMOKE_MAX)
                .toList();
        if (near.equals(p.getAttachedOrElse(SMOKE, List.of()))) return;
        if (near.isEmpty()) p.removeAttached(SMOKE);
        else p.setAttached(SMOKE, near);
    }

    private static double horizontal(BlockPos c, Vec3 pos) {
        double dx = c.getX() + .5 - pos.x, dz = c.getZ() + .5 - pos.z;
        return dx * dx + dz * dz;
    }

    static final AttachmentType<List<Nest>> NESTS =
            AttachmentRegistry.create(ModItems.id("dark_nests"), b -> b.persistent(Nest.CODEC.listOf()));
    /** Retired with the one-nest rule (a cleared home no longer rests); kept registered so old saves load clean. */
    static final AttachmentType<Map<String, Long>> RESTING = AttachmentRegistry.create(
            ModItems.id("dark_resting"), b -> b.persistent(Codec.unboundedMap(Codec.STRING, Codec.LONG)));
    /** Home position (as a long string) to the last day its dawn roll for a new nest was made. */
    static final AttachmentType<Map<String, Long>> DAWN_ROLL = AttachmentRegistry.create(
            ModItems.id("nest_dawn_roll"), b -> b.persistent(Codec.unboundedMap(Codec.STRING, Codec.LONG)));

    static void init() {
        NEST = register(
                "dark_nest",
                new Core(BlockBehaviour.Properties.of()
                        .setId(key("dark_nest"))
                        // A pickaxe cuts it in one to five seconds; anything else takes half a minute, obsidian slow
                        // (owner, 2026-11-17).
                        .strength(6f, 1200f)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.SLIME_BLOCK)
                        .noOcclusion()
                        .noLootTable()
                        .lightLevel(s -> 3)));
        GROWTH = register(
                "dark_growth",
                new CarpetBlock(BlockBehaviour.Properties.of()
                        .setId(key("dark_growth"))
                        .strength(.1f)
                        .sound(SoundType.MOSS_CARPET)
                        .noLootTable()));
        // Water the dark takes (owner, 2026-11-18: "it turns to goo"): thick and sticky like honey, slow to wade.
        GOO = register(
                "dark_goo",
                new net.minecraft.world.level.block.HoneyBlock(BlockBehaviour.Properties.of()
                        .setId(key("dark_goo"))
                        .strength(.6f)
                        .speedFactor(.4f)
                        .jumpFactor(.5f)
                        .sound(SoundType.HONEY_BLOCK)
                        .noLootTable()));
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) ->
                !(state.is(NEST) && player instanceof ServerPlayer p && level instanceof ServerLevel l)
                        || mayBreak(l, p, pos));
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (state.is(NEST) && player instanceof ServerPlayer p && level instanceof ServerLevel l)
                cleared(l, p, pos, state.getValue(STAGE));
        });
    }

    /** The tag naming the one nest a guard belongs to, beside {@link #GUARD_TAG}. */
    static String guardOf(BlockPos core) {
        return GUARD_TAG + "@" + core.asLong();
    }

    /**
     * The core holds while its guards stand (owner, 2026-11-17). By day a player who reaches it before they woke wakes
     * them now; any still alive light up so they can be found. At night there are none and the core can be broken.
     */
    static boolean mayBreak(ServerLevel level, ServerPlayer p, BlockPos core) {
        if (p.isCreative()) return true;
        var nests = new ArrayList<>(nests(level));
        if (level.dimension() != Level.OVERWORLD || !LightDragons.night(level)) {
            long day = LightShop.day(level);
            for (int i = 0; i < nests.size(); i++) {
                var nest = nests.get(i);
                if (!nest.core.equals(core) || nest.guardNight == day && !vanished(level, nest)) continue;
                guards(level, p, nest, nest.stage(level));
                nests.set(i, new Nest(nest.core, nest.home, nest.owner, nest.bornMoon, day));
                save(level, nests);
                p.sendOverlayMessage(Component.literal("The fountain's guards wake. Slay them, then break it.")
                        .withStyle(ChatFormatting.DARK_PURPLE));
                return false;
            }
        }
        String tag = guardOf(core);
        var standing = level.getEntities(
                net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),
                m -> m.isAlive() && m.entityTags().contains(tag));
        if (standing.isEmpty()) return true;
        for (var m : standing)
            m.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.GLOWING, 200, 0, false, false));
        p.sendOverlayMessage(Component.literal("Its guards still stand: " + standing.size() + " left.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        level.playSound(null, core, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, .6f, 1.4f);
        return false;
    }

    private static ResourceKey<Block> key(String id) {
        return ResourceKey.create(Registries.BLOCK, ModItems.id(id));
    }

    private static Block register(String id, Block block) {
        return Registry.register(BuiltInRegistries.BLOCK, key(id), block);
    }

    /** This level's fountains (the Overworld's or the Dark's). */
    static List<Nest> nests(ServerLevel level) {
        return level.getAttachedOrElse(NESTS, List.of());
    }

    private static void save(ServerLevel level, List<Nest> nests) {
        level.setAttached(NESTS, List.copyOf(nests));
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 100 != 0 || !Hordes.enabled(server.overworld())) return;
        for (var level : server.getAllLevels()) {
            var dim = level.dimension();
            if (dim == Level.OVERWORLD) tick(level, true);
            else if (dim == Level.NETHER && !level.players().isEmpty()) tick(level, false);
            else if (dim == Level.END) unroot(level);
        }
    }

    private static void tick(ServerLevel level, boolean overworld) {
        var nests = new ArrayList<>(nests(level));
        boolean changed = false;
        // Every loaded nest takes its Dark Moon stage; a core gone by other means drops its record.
        for (var it = nests.listIterator(); it.hasNext(); ) {
            var nest = it.next();
            if (!level.hasChunkAt(nest.core)) continue;
            var state = level.getBlockState(nest.core);
            if (!state.is(NEST)) {
                uncrystal(level, nest.core);
                it.remove();
                changed = true;
                continue;
            }
            int stage = nest.stage(level);
            if (state.getValue(STAGE) != stage) {
                level.setBlockAndUpdate(nest.core, state.setValue(STAGE, stage));
                DarkLand.spread(level, nest.core, stage);
                level.playSound(null, nest.core, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.HOSTILE, 1.5f, .6f);
            }
            crystal(level, nest.core, stage);
        }
        cease(level);
        darkenNear(level, nests);
        boolean night = overworld && LightDragons.night(level);
        if (overworld) {
            changed |= seed(level, nests);
            if (!night) changed |= dawn(level, nests);
        } else changed |= roam(level, nests);
        // The guards: by day, once a day, for whoever comes close (not only the nest's owner); none at night. The
        // Nether and the End have no night, so theirs wake once a day at any hour.
        if (night) crumbleGuards(level);
        else {
            long day = LightShop.day(level);
            for (int i = 0; i < nests.size(); i++) {
                var nest = nests.get(i);
                if (!level.hasChunkAt(nest.core)
                        || !level.getBlockState(nest.core).is(NEST)) continue;
                if (nest.guardNight == day && !vanished(level, nest)) continue;
                var core = Vec3.atCenterOf(nest.core);
                ServerPlayer near = null;
                for (var p : level.players())
                    if (Hordes.huntable(p) && p.distanceToSqr(core) < GUARD_WAKE * GUARD_WAKE) near = p;
                if (near == null) continue;
                guards(level, near, nest, nest.stage(level));
                nests.set(i, new Nest(nest.core, nest.home, nest.owner, nest.bornMoon, day));
                changed = true;
            }
        }
        if (changed) save(level, nests);
    }

    /**
     * The end of each night (owner, 2026-11-17): every owned home in the Overworld rolls once, a 1 in 2 chance of a new
     * nest while it has fewer than {@link #MAX_PER_HOME}. A home first seen today waits for the next dawn. True when a
     * nest was planted.
     */
    private static boolean dawn(ServerLevel level, List<Nest> nests) {
        long day = LightShop.day(level);
        var rolled = new java.util.HashMap<>(level.getAttachedOrElse(DAWN_ROLL, Map.of()));
        var owners = level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of());
        boolean planted = false, dirty = false;
        for (var home : level.getAttachedOrElse(ModData.LIGHT_WARDS, List.of())) {
            String key = Long.toString(home.asLong());
            var owner = owners.get(key);
            if (owner == null) continue;
            Long last = rolled.get(key);
            if (last != null && last >= day) continue;
            rolled.put(key, day);
            dirty = true;
            if (last == null || level.getRandom().nextFloat() >= DAWN_CHANCE) continue;
            if (nests.stream().filter(n -> n.home.equals(home)).count() >= MAX_PER_HOME) continue;
            var nest = plant(level, owner.player(), home, nests);
            if (nest != null) {
                nests.add(nest);
                planted = true;
            }
        }
        if (dirty) level.setAttached(DAWN_ROLL, Map.copyOf(rolled));
        return planted;
    }

    /** Homes that have had their first nests (home position as a long string). */
    static final AttachmentType<List<String>> SEEDED =
            AttachmentRegistry.create(ModItems.id("nest_seeded"), b -> b.persistent(Codec.STRING.listOf()));

    private static final Map<String, Integer> SEED_TRIES = new java.util.HashMap<>();

    /**
     * A home's first nests come at once (owner, 2026-11-17: all from the first day), one every five seconds so
     * the far chunks they load never stall the server. Homes from before this rule are filled the same way once.
     */
    private static boolean seed(ServerLevel level, List<Nest> nests) {
        var seeded = level.getAttachedOrElse(SEEDED, List.of());
        var owners = level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of());
        for (var home : level.getAttachedOrElse(ModData.LIGHT_WARDS, List.of())) {
            String key = Long.toString(home.asLong());
            var owner = owners.get(key);
            if (owner == null || seeded.contains(key)) continue;
            int tries = SEED_TRIES.merge(key, 1, Integer::sum);
            if (nests.stream().filter(n -> n.home.equals(home)).count() >= MAX_PER_HOME || tries > MAX_PER_HOME * 3) {
                var done = new ArrayList<>(seeded);
                done.add(key);
                level.setAttached(SEEDED, List.copyOf(done));
                SEED_TRIES.remove(key);
                continue;
            }
            var nest = plant(level, owner.player(), home, nests);
            if (nest == null) return false;
            nests.add(nest);
            return true;
        }
        return false;
    }

    /**
     * Fountain core (as a long string) to {@code [guards that last woke, of them slain by players]}, saved with the world
     * (owner, 2026-11-23: a restart had forgotten the count and woke a slain set again).
     */
    static final AttachmentType<Map<String, List<Integer>>> GUARDS = AttachmentRegistry.create(
            ModItems.id("nest_guards"), b -> b.persistent(Codec.unboundedMap(Codec.STRING, Codec.INT.listOf())));

    private static List<Integer> tally(ServerLevel level, BlockPos core) {
        return level.getAttachedOrElse(GUARDS, Map.of()).get(Long.toString(core.asLong()));
    }

    private static void setTally(ServerLevel level, BlockPos core, int woke, int slain) {
        var all = new java.util.HashMap<>(level.getAttachedOrElse(GUARDS, Map.of()));
        all.put(Long.toString(core.asLong()), List.of(woke, slain));
        level.setAttached(GUARDS, all);
    }

    /** A guard of {@code core}'s fountain died: counted when a player had a hand in it. */
    static void guardDied(Mob mob, net.minecraft.world.damagesource.DamageSource source) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        for (var tag : mob.entityTags())
            if (tag.startsWith(GUARD_TAG + "@")
                    && (source.getEntity() instanceof net.minecraft.world.entity.player.Player
                            || mob.getLastHurtByPlayer() != null)) {
                var core = BlockPos.of(Long.parseLong(tag.substring(GUARD_TAG.length() + 1)));
                var t = tally(level, core);
                if (t != null && t.size() == 2) setTally(level, core, t.get(0), t.get(1) + 1);
            }
    }

    /**
     * Today's guards are gone though not all were slain (despawned, swept up by another mod, or woken before this count
     * was saved): the fountain wakes a fresh set on the next approach (owner, 2026-11-23).
     */
    static boolean vanished(ServerLevel level, Nest nest) {
        String tag = guardOf(nest.core);
        if (!level.getEntities(
                        net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),
                        m -> m.isAlive() && m.entityTags().contains(tag))
                .isEmpty()) return false;
        var t = tally(level, nest.core);
        return t == null || t.size() != 2 || t.get(1) < t.get(0);
    }

    /** Nightfall: every nest guard still standing crumbles to spores. */
    static void crumbleGuards(ServerLevel level) {
        for (var mob : level.getEntities(
                net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),
                m -> m.isAlive() && m.entityTags().contains(GUARD_TAG))) {
            level.sendParticles(ParticleTypes.SCULK_SOUL, mob.getX(), mob.getY() + 1, mob.getZ(), 8, .3, .5, .3, .02);
            mob.discard();
        }
    }

    /**
     * Roots a stage-1 nest on dry ground between {@link #PLANT_MIN} blocks past the home's ward and {@link #PLANT_MAX}
     * from its chest, at least {@link #SPACING} from any other nest. Loaded spots are tried first, then a few far chunks
     * are loaded on the spot. Nothing is said in chat (owner, 2026-11-17): the smoke is how a nest is found.
     */
    static Nest plant(ServerLevel level, UUID owner, BlockPos home, List<Nest> others) {
        var random = level.getRandom();
        double start = random.nextDouble() * Math.PI * 2;
        int radius = LightWards.radiusAt(level, home);
        int min = radius + PLANT_MIN;
        for (int i = 0; i < 24; i++) {
            boolean load = i >= 16;
            double angle = start + i * Math.PI / 8 + (load ? Math.PI / 16 : 0);
            double distance = min + random.nextInt(Math.max(1, PLANT_MAX - min + 1));
            int x = home.getX() + (int) Math.round(Math.cos(angle) * distance),
                    z = home.getZ() + (int) Math.round(Math.sin(angle) * distance);
            if (load) level.getChunk(x >> 4, z >> 4);
            else if (!level.hasChunkAt(new BlockPos(x, home.getY(), z))) continue;
            var at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!dryGround(level, at) || LightWards.covers(level, at)) continue;
            if (others.stream().anyMatch(n -> n.core.distSqr(at) < (double) SPACING * SPACING)) continue;
            level.setBlockAndUpdate(at, NEST.defaultBlockState());
            DarkLand.spread(level, at, 1);
            crystal(level, at, 1);
            level.playSound(null, at, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.HOSTILE, 1.5f, .5f);
            return new Nest(at, home, owner, bornMoon(level), Long.MIN_VALUE);
        }
        return null;
    }

    private static long bornMoon(ServerLevel level) {
        return Hordes.MoonNight.read(level.getServer().overworld()).latestHunt();
    }

    /** How many fountains a roaming player keeps round them in the Dark, and how far that reaches. */
    static final int ROAM_NEAR = 3, ROAM_RANGE = 300, ROAM_EVERY = 1200;

    private static final Map<ResourceKey<Level>, Long> LAST_ROAM = new java.util.HashMap<>();

    /**
     * The Dark's fountains (owner, 2026-11-20): with no home to grow round, they root round a player who roams there.
     * While a player has fewer than {@link #ROAM_NEAR} within {@link #ROAM_RANGE} blocks, one roots {@link #PLANT_MIN}
     * to {@link #PLANT_MAX} blocks from them and {@link #SPACING} from any other, at most one a minute. True when one
     * was planted.
     */
    static boolean roam(ServerLevel level, List<Nest> nests) {
        long now = level.getGameTime();
        if (now - LAST_ROAM.getOrDefault(level.dimension(), Long.MIN_VALUE / 2) < ROAM_EVERY) return false;
        for (var p : level.players()) {
            if (!Hordes.huntable(p)) continue;
            var pos = p.position();
            long close = nests.stream()
                    .filter(n -> horizontal(n.core, pos) < (double) ROAM_RANGE * ROAM_RANGE)
                    .count();
            if (close >= ROAM_NEAR) continue;
            LAST_ROAM.put(level.dimension(), now);
            var nest = plantRoaming(level, p, nests);
            if (nest == null) return false;
            nests.add(nest);
            return true;
        }
        return false;
    }

    /** One roaming fountain round a player: loaded ground first, then two far chunks loaded on the spot. */
    static Nest plantRoaming(ServerLevel level, ServerPlayer p, List<Nest> others) {
        var random = level.getRandom();
        double start = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 18; i++) {
            boolean load = i >= 16;
            double angle = start + i * Math.PI / 8 + (load ? Math.PI / 16 : 0);
            double distance = PLANT_MIN + random.nextInt(PLANT_MAX - PLANT_MIN + 1);
            int x = p.getBlockX() + (int) Math.round(Math.cos(angle) * distance),
                    z = p.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            if (load) level.getChunk(x >> 4, z >> 4);
            else if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;
            var at = floor(level, x, z, p.getBlockY());
            if (at == null || LightWards.covers(level, at)) continue;
            if (others.stream().anyMatch(n -> n.core.distSqr(at) < (double) SPACING * SPACING)) continue;
            level.setBlockAndUpdate(at, NEST.defaultBlockState());
            DarkLand.spread(level, at, 1);
            crystal(level, at, 1);
            level.playSound(null, at, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.HOSTILE, 1.5f, .5f);
            return new Nest(at, at, p.getUUID(), bornMoon(level), Long.MIN_VALUE);
        }
        return null;
    }

    /**
     * Open dry floor in a column, with room above for the crystal. The Dark's caverns are searched down from a little
     * above {@code nearY} (its roof hides the sky), so a fountain roots on a floor the player can walk to; never on the
     * roof.
     */
    static BlockPos floor(ServerLevel level, int x, int z, int nearY) {
        int top = Math.min(nearY + 24, 120), bottom = Math.max(nearY - 24, 32);
        for (int y = top; y >= bottom; y--) {
            var at = new BlockPos(x, y, z);
            if (roomy(level, at)) return at;
        }
        return null;
    }

    private static boolean roomy(ServerLevel level, BlockPos at) {
        return dryGround(level, at)
                && !level.getBlockState(at.below()).is(Blocks.BEDROCK)
                && level.getBlockState(at.above()).isAir()
                && level.getBlockState(at.above(2)).isAir();
    }

    static boolean dryGround(ServerLevel level, BlockPos at) {
        var below = at.below();
        return level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
                && level.getFluidState(below).isEmpty()
                && level.getBlockState(at).canBeReplaced()
                && level.getFluidState(at).isEmpty();
    }

    /**
     * The kinds that may guard a fountain here, from each monster's {@code guards} in {@code lightborn/monsters.json}
     * (owner, 2026-11-20: "each fountain fits its place"). The base pool: in the Overworld the night's own kinds and the
     * Overworld's (the Zombie Villager, the Executioner); in the Dark only the Dark's.
     * The themed pool (Overworld only): the kinds of the fountain's biome, by its conventional tags (snowy: the Stray,
     * the frozen kinds, the Iceologer; desert: the Husk, the Parched, the Sand Spider; jungle; swamp; coast; forest and
     * plains: the raiders). A kind whose companion mod is missing is left out. World bosses, mounts, water-only kinds,
     * kinds with no mind and summons have no group and never guard.
     */
    static List<Souls.Page> pool(ServerLevel level, BlockPos core, boolean themed) {
        var groups = groups(level, core, themed);
        var out = new ArrayList<Souls.Page>();
        if (groups.isEmpty()) return out;
        for (var page : Souls.PAGES) {
            var m = Souls.monster(page.id());
            if (m != null
                    && m.guards().stream().anyMatch(groups::contains)
                    && page.type().get() != null) out.add(page);
        }
        return out;
    }

    static List<String> groups(ServerLevel level, BlockPos core, boolean themed) {
        var dim = level.dimension();
        if (dim == Level.NETHER) return themed ? List.of() : List.of("nether");
        if (dim == Level.END) return List.of(); // the Hollow has no fountains to guard
        if (!themed) return List.of("night", "overworld");
        var biome = level.getBiome(core);
        var out = new ArrayList<String>();
        if (biome.is(ConventionalBiomeTags.IS_SNOWY) || biome.is(ConventionalBiomeTags.IS_ICY)) out.add("snowy");
        if (biome.is(ConventionalBiomeTags.IS_DESERT) || biome.is(ConventionalBiomeTags.IS_BADLANDS)) out.add("desert");
        if (biome.is(ConventionalBiomeTags.IS_JUNGLE)) out.add("jungle");
        if (biome.is(ConventionalBiomeTags.IS_SWAMP)) out.add("swamp");
        if (biome.is(ConventionalBiomeTags.IS_BEACH)
                || biome.is(ConventionalBiomeTags.IS_OCEAN)
                || biome.is(ConventionalBiomeTags.IS_RIVER)) out.add("coast");
        if (biome.is(ConventionalBiomeTags.IS_FOREST)
                || biome.is(ConventionalBiomeTags.IS_PLAINS)
                || biome.is(ConventionalBiomeTags.IS_TAIGA)
                || biome.is(ConventionalBiomeTags.IS_SAVANNA)) out.add("forest");
        return out;
    }

    /** Draw exactly the requested roster tier and retain the local biome's share when it has a match. */
    static EntityType<? extends Mob> guardKind(
            RandomSource random, int tier, List<Souls.Page> base, List<Souls.Page> themed) {
        var regional = themed.stream().filter(page -> page.weight() == tier).toList();
        var choices = !regional.isEmpty() && random.nextBoolean()
                ? regional
                : base.stream().filter(page -> page.weight() == tier).toList();
        if (choices.isEmpty()) {
            // Keep working when a companion providing every local example of a tier is absent.
            int difference = base.stream()
                    .mapToInt(page -> Math.abs(page.weight() - tier))
                    .min()
                    .orElse(5);
            choices = base.stream()
                    .filter(page -> Math.abs(page.weight() - tier) == difference)
                    .toList();
        }
        return choices.isEmpty()
                ? ModEntities.MIRE_ZOMBIE
                : choices.get(random.nextInt(choices.size())).type().get();
    }

    // ---------------------------------------------------------------- dark that never took, and nests that end

    /** Each nest's core (as a long) and the reach of the dark last laid round it. */
    static final AttachmentType<Map<String, Integer>> DARKENED = AttachmentRegistry.create(
            ModItems.id("nest_darkened"), b -> b.persistent(Codec.unboundedMap(Codec.STRING, Codec.INT)));

    /**
     * When a player comes within 64 blocks of a nest whose dark land is missing or smaller than its stage's reach, the
     * land is laid now (owner, 2026-11-17: a nest stood on clean ground). The area is loaded then, so it always takes,
     * and nests from before a wider reach catch up.
     */
    static void darkenNear(ServerLevel level, List<Nest> nests) {
        if (level.players().isEmpty()) return;
        var done = level.getAttachedOrElse(DARKENED, Map.of());
        Map<String, Integer> next = null;
        for (var nest : nests) {
            if (!level.hasChunkAt(nest.core)) continue;
            String key = Long.toString(nest.core.asLong());
            int stage = nest.stage(level), reach = DarkLand.darkRadius(stage);
            if (done.getOrDefault(key, 0) >= reach) continue;
            var center = Vec3.atCenterOf(nest.core);
            if (level.players().stream().noneMatch(p -> p.distanceToSqr(center) < 64 * 64)) continue;
            DarkLand.spread(level, nest.core, stage);
            if (next == null) next = new java.util.HashMap<>(done);
            next.put(key, reach);
        }
        if (next != null) level.setAttached(DARKENED, Map.copyOf(next));
    }

    /** Nests of a home that moved away (owner, 2026-11-17): each core crumbles and its land heals once it is loaded. */
    static final AttachmentType<List<Long>> CEASED =
            AttachmentRegistry.create(ModItems.id("nest_ceased"), b -> b.persistent(Codec.LONG.listOf()));

    /** A home moved: its nests end and it plants ten new ones round the new place. */
    static void homeMoved(ServerLevel level, BlockPos from) {
        var nests = new ArrayList<>(nests(level));
        var ceased = new ArrayList<>(level.getAttachedOrElse(CEASED, List.of()));
        for (var it = nests.iterator(); it.hasNext(); ) {
            var nest = it.next();
            if (!nest.home.equals(from)) continue;
            it.remove();
            ceased.add(nest.core.asLong());
            for (var mob : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),
                    m -> m.entityTags().contains(guardOf(nest.core)))) mob.discard();
        }
        save(level, nests);
        level.setAttached(CEASED, List.copyOf(ceased));
        String key = Long.toString(from.asLong());
        var seeded = new ArrayList<>(level.getAttachedOrElse(SEEDED, List.of()));
        if (seeded.remove(key)) level.setAttached(SEEDED, List.copyOf(seeded));
        var rolled = new java.util.HashMap<>(level.getAttachedOrElse(DAWN_ROLL, Map.of()));
        if (rolled.remove(key) != null) level.setAttached(DAWN_ROLL, Map.copyOf(rolled));
    }

    /**
     * The Hollow has no Dark Fountains (owner, 2026-11-21: "remove fountains from the hollow"): any that rooted there
     * before crumble with their guards, and their land heals once it is loaded.
     */
    static void unroot(ServerLevel level) {
        var nests = nests(level);
        if (!nests.isEmpty()) {
            var ceased = new ArrayList<>(level.getAttachedOrElse(CEASED, List.of()));
            for (var nest : nests) {
                ceased.add(nest.core.asLong());
                for (var mob : level.getEntities(
                        net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),
                        m -> m.entityTags().contains(guardOf(nest.core)))) mob.discard();
            }
            save(level, List.of());
            level.setAttached(CEASED, List.copyOf(ceased));
        }
        cease(level);
    }

    static void cease(ServerLevel level) {
        var ceased = level.getAttachedOrElse(CEASED, List.of());
        if (ceased.isEmpty()) return;
        var left = new ArrayList<Long>();
        for (long packed : ceased) {
            var core = BlockPos.of(packed);
            // Its crystal is an entity, and a chunk's entities can load after its blocks: wait for both, or the crystal
            // is missed and floats on over healed ground.
            if (!level.hasChunkAt(core) || !level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(core))) {
                left.add(packed);
                continue;
            }
            uncrystal(level, core);
            if (level.getBlockState(core).is(NEST)) {
                level.setBlockAndUpdate(core, Blocks.AIR.defaultBlockState());
                DarkLand.healNow(level, core);
            }
        }
        if (left.size() != ceased.size()) level.setAttached(CEASED, List.copyOf(left));
    }

    /**
     * A player died near a nest whose guards were up (owner, 2026-11-17): the fight resets. The guards crumble and the
     * nest's day guard turn is given back, so the full guard wakes again on the next approach.
     */
    static void playerDied(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) return;
        var nests = new ArrayList<>(nests(level));
        boolean changed = false;
        for (int i = 0; i < nests.size(); i++) {
            var nest = nests.get(i);
            if (nest.core.distSqr(p.blockPosition()) > 64 * 64) continue;
            String tag = guardOf(nest.core);
            var guards = level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),
                    m -> m.isAlive() && m.entityTags().contains(tag));
            if (guards.isEmpty() && nest.guardNight != LightShop.day(level)) continue;
            for (var mob : guards) {
                level.sendParticles(
                        ParticleTypes.SCULK_SOUL, mob.getX(), mob.getY() + 1, mob.getZ(), 8, .3, .5, .3, .02);
                mob.discard();
            }
            nests.set(i, new Nest(nest.core, nest.home, nest.owner, nest.bornMoon, Long.MIN_VALUE));
            changed = true;
        }
        if (changed) save(level, nests);
    }

    static final int MAX_GUARDS = 8;
    private static final int[] MAX_BY_TIER = {0, MAX_GUARDS, MAX_GUARDS, 2, 4, 2};
    private static final String ROSTER_TAG = "lightborn_fountain_roster";

    /** Old active groups are replaced on the next approach. Their saved clear counters stay in use. */
    static boolean obsoleteGuard(net.minecraft.world.entity.Entity entity) {
        return entity.entityTags().contains(GUARD_TAG) && !entity.entityTags().contains(ROSTER_TAG);
    }

    static List<Integer> guardRoster(boolean nether, int wins) {
        int[] counts = nether ? new int[] {0, 1, 1, 1, 1, 0} : new int[] {0, 2, 1, 1, 0, 0};
        int size = 4;
        for (int step = 1; step <= wins; step++) {
            if (step % 2 == 1 && size < MAX_GUARDS) {
                counts[1]++;
                size++;
            } else if (!promoteGuard(counts)) {
                if (size == MAX_GUARDS) break;
                counts[1]++;
                size++;
            }
        }
        var roster = new ArrayList<Integer>(size);
        for (int tier = 5; tier >= 1; tier--) for (int i = 0; i < counts[tier]; i++) roster.add(tier);
        return List.copyOf(roster);
    }

    private static boolean promoteGuard(int[] counts) {
        for (int tier = 1; tier < 5; tier++) {
            if (counts[tier] == 0 || counts[tier + 1] >= MAX_BY_TIER[tier + 1]) continue;
            counts[tier]--;
            counts[tier + 1]++;
            return true;
        }
        return false;
    }

    static int fightRank(ServerLevel level, int stage) {
        return Math.min(stage, 1 + Math.min(3, victories(level) / 2));
    }

    /**
     * Day guards wake once a day when a player comes close: its current roster from the fountain's pools round
     * the core. Any of yesterday's still standing (the Dark's never crumble) go first.
     */
    static void guards(ServerLevel level, ServerPlayer p, Nest nest, int stage) {
        var random = level.getRandom();
        var roster = guardRoster(dark(level), victories(level));
        int woke = 0;
        String tag = guardOf(nest.core);
        for (var old : level.getEntities(
                net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),
                m -> m.entityTags().contains(tag))) old.discard();
        var base = pool(level, nest.core, false);
        var themed = pool(level, nest.core, true);
        boolean overworld = level.dimension() == Level.OVERWORLD;
        for (int tier : roster) {
            for (int retry = 0; retry < 6; retry++) {
                var type = guardKind(random, tier, base, themed);
                Mob mob = VariantCompat.keepKind(type.create(level, EntitySpawnReason.MOB_SUMMONED));
                if (mob == null) continue;
                double angle = random.nextDouble() * Math.PI * 2, radius = 3 + random.nextDouble() * 4;
                int x = nest.core.getX() + (int) Math.round(Math.cos(angle) * radius),
                        z = nest.core.getZ() + (int) Math.round(Math.sin(angle) * radius);
                var at = overworld
                        ? new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z)
                        : guardFloor(level, x, z, nest.core.getY());
                if (at == null) {
                    mob.discard();
                    continue;
                }
                mob.setPos(Vec3.atBottomCenterOf(at));
                if (!level.noCollision(mob) || LightWards.covers(level, at)) {
                    mob.discard();
                    continue;
                }
                Monsters.setRank(mob, MonsterInfo.NORMAL);
                mob.addTag(GUARD_TAG);
                mob.addTag(ROSTER_TAG);
                mob.addTag(tag);
                mob.setPersistenceRequired();
                mob.addTag("ts.sa.exclude");
                if (level.addFreshEntity(mob)) {
                    Hordes.aim(mob, p);
                    woke++;
                    break;
                }
            }
        }
        setTally(level, nest.core, woke, 0);
        level.playSound(null, nest.core, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 1.2f, .7f);
    }

    /** Standing room by a Nether or End fountain: the nearest floor within a few blocks of the core's height. */
    private static BlockPos guardFloor(ServerLevel level, int x, int z, int y) {
        for (int dy = 0; dy <= 6; dy++)
            for (int sign : new int[] {1, -1}) {
                var at = new BlockPos(x, y + dy * sign, z);
                var below = at.below();
                if (level.getBlockState(at).isAir()
                        && level.getBlockState(at.above()).isAir()
                        && level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP))
                    return at;
            }
        return null;
    }

    /**
     * What breaking a fountain's crystal pays in Sparks (owner, 2026-11-23: "7-25", rising with its stage): 7 to 11 at
     * stage 1, 12 to 16, 17 to 21, and 21 to 25 at stage 4.
     */
    static int nestSparks(RandomSource random, int stage) {
        int lo = Balance.NEST_SPARKS[Math.clamp(stage, 1, 4) - 1];
        return lo + random.nextInt(Balance.NEST_SPARKS_SPREAD + 1);
    }

    static void cleared(ServerLevel level, ServerPlayer p, BlockPos core, int stage) {
        var nests = new ArrayList<>(nests(level));
        Nest gone = null;
        for (var n : nests) if (n.core.equals(core)) gone = n;
        uncrystal(level, core);
        if (gone == null) return;
        nests.remove(gone);
        save(level, nests);
        int wins = victories(level);
        int rank = fightRank(level, stage);
        level.setAttached(VICTORIES, wins == Integer.MAX_VALUE ? wins : wins + 1);
        ChestTabs.fountainBeaten(p);
        // The land heals in a wave rolling out from the core over 15 seconds (owner, 2026-11-17).
        for (int step = 0; step < DarkLand.HEAL_STEPS; step++) {
            int s = step;
            Scheduler.later(1 + s * DarkLand.HEAL_TICKS / DarkLand.HEAL_STEPS, () -> DarkLand.heal(level, core, s));
        }
        var random = level.getRandom();
        var at = Vec3.atCenterOf(core);
        // The Dark's own pay half again in Sparks and shards and one more item (owner, 2026-11-20).
        boolean dark = dark(level);
        int bonus = (int) Math.floor(6 * Math.log1p(wins / 2.0));
        int sparks = nestSparks(random, rank) + bonus;
        int shards = 2 * rank + bonus / 4, items = rank + (dark && wins > 0 ? 1 : 0);
        if (dark) {
            sparks = sparks * 3 / 2;
            shards = shards * 3 / 2;
        }
        drop(level, at, new ItemStack(ModItems.SPARK, sparks));
        // Round odd payouts in either direction so their long-run average is also halved.
        int shardDrop = (shards + (shards % 2 == 1 && random.nextBoolean() ? 1 : 0)) / 2;
        if (shardDrop > 0) drop(level, at, new ItemStack(ModItems.DARK_SHARD, shardDrop));
        for (int i = 0; i < items; i++) {
            var rarity = i == 0 && wins >= 12
                    ? LootRoller.Rarity.MYTHIC
                    : i == 0 && wins >= 4
                            ? LootRoller.Rarity.LEGENDARY
                            : wins >= 2 ? LootRoller.Rarity.RARE : LootRoller.Rarity.UNCOMMON;
            var tier = OreTiers.pick(random, p, rank >= 3 ? OreTiers.BOSS : rank + 1);
            if (tier != null) LootBeams.onDrop(drop(level, at, LootRoller.roll(random, tier, rarity)), rarity);
        }
        level.sendParticles(ParticleTypes.SCULK_SOUL, at.x, at.y, at.z, 30, 1, .6, 1, .05);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + .5, at.z, 20, .6, .6, .6, .05);
        // No chat line (owner, 2026-11-17): the healing wave says it.
        level.playSound(null, core, SoundEvents.SCULK_CATALYST_BREAK, SoundSource.BLOCKS, 1.5f, .6f);
    }

    // ---------------------------------------------------------------- the crystal

    /** Marks the display that draws a nest's crystal; a second tag names its stage. */
    static final String CRYSTAL_TAG = "lightborn_nest_crystal";

    /**
     * How large each stage's crystal stands (owner, 2026-11-18: "a huge shard, the length of a human"): about 1.9 blocks
     * tall at stage 1 and 2.8 at stage 4, its girth and side shards growing with it. A block model stops at two blocks,
     * so the crystal is an item display scaled up over the core, which keeps only the pool at its foot.
     */
    static final float[] CRYSTAL_SCALE = {1.9f, 1.6f, 1.45f, 1.4f};

    public static List<net.minecraft.world.entity.Display.ItemDisplay> crystals(ServerLevel level, BlockPos core) {
        return level.getEntitiesOfClass(
                net.minecraft.world.entity.Display.ItemDisplay.class,
                new net.minecraft.world.phys.AABB(core).inflate(1),
                d -> d.entityTags().contains(CRYSTAL_TAG));
    }

    /** Keeps exactly one crystal of the nest's stage standing on a loaded core. */
    public static void crystal(ServerLevel level, BlockPos core, int stage) {
        String mine = CRYSTAL_TAG + "_" + stage;
        boolean kept = false;
        for (var d : crystals(level, core)) {
            if (!kept && d.entityTags().contains(mine)) kept = true;
            else d.discard();
        }
        if (kept) return;
        var d = net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (d == null) return;
        var stack = new ItemStack(net.minecraft.world.item.Items.ECHO_SHARD);
        stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, ModItems.id("dark_crystal_" + stage));
        d.setItemStack(stack);
        d.setItemTransform(net.minecraft.world.item.ItemDisplayContext.NONE);
        float s = CRYSTAL_SCALE[stage - 1];
        // Scaled about the core's centre, then lifted so the crystal's root stays on the core's floor.
        d.setTransformation(new com.mojang.math.Transformation(
                new org.joml.Vector3f(0, (s - 1) / 2, 0),
                new org.joml.Quaternionf(),
                new org.joml.Vector3f(s),
                new org.joml.Quaternionf()));
        // Lit like open air beside a dim lamp, by day and by night, so the dark crystal still reads in its fog.
        d.setBrightnessOverride(new net.minecraft.util.Brightness(9, 15));
        d.setViewRange(4f);
        d.setPos(core.getX() + .5, core.getY() + .5, core.getZ() + .5);
        d.addTag(CRYSTAL_TAG);
        d.addTag(mine);
        level.addFreshEntity(d);
    }

    static void uncrystal(ServerLevel level, BlockPos core) {
        crystals(level, core).forEach(net.minecraft.world.entity.Entity::discard);
    }

    private static net.minecraft.world.entity.item.ItemEntity drop(ServerLevel level, Vec3 at, ItemStack stack) {
        var item = new net.minecraft.world.entity.item.ItemEntity(level, at.x, at.y, at.z, stack);
        item.setDefaultPickUpDelay();
        level.addFreshEntity(item);
        return item;
    }

    /** The nest core: four growth stages, each a larger mound, breathing spores. */
    static final class Core extends Block {
        /**
         * What can be hit and stood on: the crystal's root, a block and a half tall (owner, 2026-11-18: the crystal
         * stands as tall as a person and more, drawn by a display over the core).
         */
        private static final VoxelShape[] SHAPES = {
            Block.box(2, 0, 2, 14, 24, 14),
            Block.box(1, 0, 1, 15, 24, 15),
            Block.box(0, 0, 0, 16, 24, 16),
            Block.box(0, 0, 0, 16, 24, 16)
        };

        Core(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(STAGE, 1));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(STAGE);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter l, BlockPos p, CollisionContext c) {
            return SHAPES[state.getValue(STAGE) - 1];
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            if (random.nextInt(3) != 0) return;
            level.addParticle(
                    ParticleTypes.MYCELIUM,
                    pos.getX() + random.nextDouble(),
                    pos.getY() + .3 + random.nextDouble() * state.getValue(STAGE) * .25,
                    pos.getZ() + random.nextDouble(),
                    0,
                    .02,
                    0);
        }
    }
}
