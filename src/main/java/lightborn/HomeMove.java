package lightborn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Clearable;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Moving home (owner, 2026-11-15): the Chest of Light can no longer be broken; instead its owner pays a flat price in
 * Sparks and the Light lifts everything inside the ward (house, farm, animals, the people standing in it) and sets it
 * down at completely random coordinates in the Overworld: any land at all, mountain, swamp or odd biome (owner,
 * 2026-11-17: lax). The plug brings its own ground, so where the land falls away it simply hangs over it, and anything
 * standing in the way (trees, a hill, a structure) is erased. The old home's dark nests end and ten new ones rise round
 * the new place.
 * <p>
 * The move is a plug: every block in a cylinder of the ward's radius, from {@link Balance#HOME_MOVE_DEPTH} below the
 * chest up to the dome's top, chest contents and all. The chunks at the far end are force-loaded first and the move
 * waits for them over several ticks, so a far, ungenerated site never freezes the server. The ground left behind is
 * filled with earth and grass where the ground was, so the old site reads as a clean scar, not a hole. Ward ownership,
 * its glow, its dragons, the world spawn (when the home is the spawn hut) and respawn points inside
 * the ward all move with it.
 */
public final class HomeMove {
    private HomeMove() {}

    public record Move(BlockPos chest) implements CustomPacketPayload {
        public static final Type<Move> TYPE = new Type<>(ModItems.id("home_move"));
        static final StreamCodec<RegistryFriendlyByteBuf, Move> CODEC =
                BlockPos.STREAM_CODEC.map(Move::new, Move::chest).cast();

        @Override
        public Type<Move> type() {
            return TYPE;
        }
    }

    /**
     * A paid move: first it looks for ground a few candidates a tick (a search all at once froze the server for
     * seconds, owner 2026-11-16), then it waits for the far chunks to load.
     */
    private static final class Job {
        final ServerLevel level;
        final UUID player;
        final BlockPos from;
        final int radius;
        final long started;
        int x, z, tries;
        boolean found;

        Job(ServerLevel level, UUID player, BlockPos from, int radius, long started) {
            this.level = level;
            this.player = player;
            this.from = from;
            this.radius = radius;
            this.started = started;
        }

        ServerLevel level() {
            return level;
        }

        UUID player() {
            return player;
        }

        BlockPos from() {
            return from;
        }

        int radius() {
            return radius;
        }

        int x() {
            return x;
        }

        int z() {
            return z;
        }

        long started() {
            return started;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Move.TYPE, Move.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Move.TYPE, (m, ctx) -> {
            var p = ctx.player();
            if (LightShop.near(p, m.chest()))
                ChestTravel.reply(
                        p,
                        m.chest(),
                        ChestTabs.travel(p)
                                ? start(p, m.chest())
                                : "Beat " + ChestTabs.TRAVEL_FOUNTAINS + " Dark Fountains first.");
        });
    }

    static void clear() {
        for (var job : JOBS) if (job.found) force(job.level(), job.x(), job.z(), job.radius(), false);
        JOBS.clear();
    }

    /** Why this player cannot move this chest's home now, or null. */
    static String problem(ServerPlayer p, BlockPos chest) {
        var level = p.level();
        if (level.dimension() != Level.OVERWORLD) return "Only a home in the Overworld can be moved.";
        if (!level.getBlockState(chest).is(ModItems.LIGHT_CHEST)) return "Stand beside your Chest of Light.";
        var owner = level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of()).get(Long.toString(chest.asLong()));
        if (owner == null || !owner.player().equals(p.getUUID())) return "Only the chest's owner can move this home.";
        if (Cleanse.active(p) || LightWards.suspended(level, chest)) return "Not while the Cleanse is under way.";
        if (ChestTravel.channeling(p)) return "Not while the Dragon is carrying you.";
        if (JOBS.stream().anyMatch(j -> j.from().equals(chest))) return "The Light is already lifting this home.";
        return null;
    }

    static String start(ServerPlayer p, BlockPos chest) {
        String why = problem(p, chest);
        if (why != null) return why;
        if (LightShop.sparks(p) < Balance.HOME_MOVE_SPARKS)
            return "Moving home costs " + Balance.HOME_MOVE_SPARKS + " Sparks.";
        var level = p.level();
        int radius = LightWards.radiusAt(level, chest);
        if (!LightShop.pay(p, Balance.HOME_MOVE_SPARKS))
            return "Moving home costs " + Balance.HOME_MOVE_SPARKS + " Sparks.";
        JOBS.add(new Job(level, p.getUUID(), chest.immutable(), radius, level.getGameTime()));
        // The purchase must read at once (owner, 2026-11-16): the screen closes, a chime, a line in chat, a
        // burst of light, and a held overlay until the home lands.
        p.closeContainer();
        level.playSound(null, chest, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 2f, .6f);
        level.playSound(null, chest, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 2f, .5f);
        p.sendSystemMessage(Component.literal("-" + Balance.HOME_MOVE_SPARKS
                        + " Sparks. The Light is lifting your home: stay inside the ward.")
                .withStyle(ChatFormatting.GOLD));
        var c = Vec3.atCenterOf(chest);
        level.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 1, c.z, 80, radius * .4, 2, radius * .4, .1);
        lifting(level, chest, radius);
        return "The Light is lifting your home.";
    }

    // ---------------------------------------------------------------- where to

    /**
     * Completely random coordinates (within {@link Balance#HOME_MOVE_RANGE} and the world border) on land: any height
     * and any slope, read from the generator's own heights (no chunk is built to ask). Only the sea is refused, a spot
     * whose middle or most of whose footprint lies under water, or an ocean.
     */
    static BlockPos findSite(ServerLevel level, BlockPos from, int radius) {
        for (int tries = 0; tries < Balance.HOME_MOVE_TRIES; tries++) {
            BlockPos site = trySite(level, from, radius);
            if (site != null) return site;
        }
        return null;
    }

    /** One random candidate: its x and z when the ground there passes, else null. */
    static BlockPos trySite(ServerLevel level, BlockPos from, int radius) {
        var border = level.getWorldBorder();
        double reachX = Math.min(Balance.HOME_MOVE_RANGE, (border.getMaxX() - border.getMinX()) / 2 - radius - 48);
        double reachZ = Math.min(Balance.HOME_MOVE_RANGE, (border.getMaxZ() - border.getMinZ()) / 2 - radius - 48);
        if (reachX < radius * 4 || reachZ < radius * 4) return null;
        double away = Math.min(Balance.HOME_MOVE_MIN_DISTANCE, Math.min(reachX, reachZ) / 2);
        var random = level.getRandom();
        int x = (int) (border.getCenterX() + (random.nextDouble() * 2 - 1) * reachX);
        int z = (int) (border.getCenterZ() + (random.nextDouble() * 2 - 1) * reachZ);
        if (Math.hypot(x - from.getX(), z - from.getZ()) < away) return null;
        return level(level, x, z, radius) ? new BlockPos(x, 0, z) : null;
    }

    /** The held overlay for everyone inside a ward that is being lifted. */
    private static void lifting(ServerLevel level, BlockPos chest, int radius) {
        for (var other : level.players())
            if (inside(chest, radius, other.position()))
                other.sendOverlayMessage(Component.literal("The Light is lifting your home... stay inside the ward")
                        .withStyle(ChatFormatting.GOLD));
    }

    /** Whether the generator lays land (not sea) under a ward of this radius at x, z; its shape does not matter. */
    static boolean level(ServerLevel level, int x, int z, int radius) {
        var gen = level.getChunkSource().getGenerator();
        var noise = level.getChunkSource().randomState();
        int sea = level.getSeaLevel(), wet = 0, ground0 = 0;
        for (int ring = 0; ring <= 2; ring++) {
            int points = ring == 0 ? 1 : 8;
            double d = radius * ring / 2.0;
            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2 * i / points;
                int sx = x + (int) Math.round(Math.cos(a) * d), sz = z + (int) Math.round(Math.sin(a) * d);
                int ground = gen.getBaseHeight(sx, sz, Heightmap.Types.OCEAN_FLOOR_WG, level, noise);
                int surface = gen.getBaseHeight(sx, sz, Heightmap.Types.WORLD_SURFACE_WG, level, noise);
                boolean water = surface > ground || ground < sea;
                if (ring == 0) {
                    if (water) return false; // the chest never lands in water
                    ground0 = ground;
                }
                if (water) wet++;
            }
        }
        if (wet > 8) return false; // most of the footprint is under water
        return !level.getBiome(new BlockPos(x, ground0, z)).is(BiomeTags.IS_OCEAN);
    }

    private static void force(ServerLevel level, int x, int z, int radius, boolean on) {
        int r = radius + 16;
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++)
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) level.setChunkForced(cx, cz, on);
    }

    private static boolean loaded(ServerLevel level, int x, int z, int radius) {
        int r = radius + 16;
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++)
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++)
                if (!level.getChunkSource().hasChunk(cx, cz)) return false;
        return true;
    }

    static void tick(MinecraftServer server) {
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            var job = it.next();
            var level = job.level();
            long age = level.getGameTime() - job.started();
            if (age % 4 == 0) {
                var c = Vec3.atCenterOf(job.from());
                level.sendParticles(
                        ParticleTypes.END_ROD, c.x, c.y + .5, c.z, 6, job.radius() * .3, 2, job.radius() * .3, .05);
            }
            if (age % 20 == 0) {
                lifting(level, job.from(), job.radius());
                level.playSound(null, job.from(), SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS, 2f, .7f);
            }
            boolean chestGone = !level.getBlockState(job.from()).is(ModItems.LIGHT_CHEST);
            boolean lost = !job.found && job.tries >= Balance.HOME_MOVE_TRIES;
            if (chestGone || lost || age > Balance.HOME_MOVE_WAIT_TICKS) {
                it.remove();
                if (job.found) force(level, job.x(), job.z(), job.radius(), false);
                var p = server.getPlayerList().getPlayer(job.player());
                if (p != null) {
                    var refund = new ItemStack(ModItems.SPARK, Balance.HOME_MOVE_SPARKS);
                    DragonTalk.give(p, refund);
                    p.sendOverlayMessage(Component.literal(
                                    lost
                                            ? "The Light found no open ground this time. Your Sparks are returned."
                                            : "The Light could not reach the new ground. Your Sparks are returned.")
                            .withStyle(ChatFormatting.RED));
                }
                continue;
            }
            if (!job.found) {
                for (int i = 0; i < Balance.HOME_MOVE_TRIES_PER_TICK && !job.found; i++, job.tries++) {
                    BlockPos site = trySite(level, job.from(), job.radius());
                    if (site == null) continue;
                    job.found = true;
                    job.x = site.getX();
                    job.z = site.getZ();
                    force(level, job.x, job.z, job.radius(), true);
                }
                continue;
            }
            if (!loaded(level, job.x(), job.z(), job.radius())) continue;
            it.remove();
            BlockPos to = relocate(level, job.from(), job.x(), job.z(), job.radius());
            int fx = job.x(), fz = job.z(), r = job.radius();
            Scheduler.later(100, () -> force(level, fx, fz, r, false));
            // The landing thud, "da-DOOM", once the riders are down at the new place (owner, 2026-11-16).
            Scheduler.later(4, () -> thud(level, to, false));
            Scheduler.later(9, () -> thud(level, to, true));
        }
    }

    // ---------------------------------------------------------------- the move itself

    private static boolean inside(BlockPos chest, int radius, Vec3 at) {
        double dx = at.x - (chest.getX() + .5), dz = at.z - (chest.getZ() + .5);
        return dx * dx + dz * dz <= (radius + .5) * (radius + .5)
                && at.y >= chest.getY() - Balance.HOME_MOVE_DEPTH - 1
                && at.y <= chest.getY() + radius + 4;
    }

    /** The ground height to set the home on: the median surface across the ward's footprint at x, z. */
    private static int groundAt(ServerLevel level, int x, int z, int radius) {
        var heights = new ArrayList<Integer>();
        for (int ring = 0; ring <= 2; ring++) {
            int points = ring == 0 ? 1 : 8;
            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2 * i / points, d = radius * ring / 2.0;
                heights.add(level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        x + (int) Math.round(Math.cos(a) * d),
                        z + (int) Math.round(Math.sin(a) * d)));
            }
        }
        heights.sort(Integer::compare);
        return heights.get(heights.size() / 2);
    }

    /**
     * Moves the home now; the destination chunks must be loaded. Returns the chest's new position. Used by the tick
     * once a job's chunks are in, and by the self-test directly.
     */
    static BlockPos relocate(ServerLevel level, BlockPos from, int x, int z, int radius) {
        int floor = Math.max(level.getMinY(), from.getY() - Balance.HOME_MOVE_DEPTH);
        int roof = Math.min(level.getMaxY(), from.getY() + radius + 2);
        int ground = groundAt(level, x, z, radius);
        BlockPos to = new BlockPos(x, ground, z);
        int dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        floor = Math.max(floor, level.getMinY() - dy);
        roof = Math.min(roof, level.getMaxY() - dy);

        // What travels with the chest's identity.
        String key = Long.toString(from.asLong());
        var owner = level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of()).get(key);
        ChestGlow.remove(
                level, from); // its light blocks go back to what they replaced, so the plug carries the real build

        // The plug: every block in the cylinder, bottom to top, and the block entities' saved contents.
        var cells = new ArrayList<BlockPos>();
        var states = new ArrayList<BlockState>();
        var saved = new HashMap<BlockPos, CompoundTag>();
        double r2 = (radius + .5) * (radius + .5);
        for (int y = floor; y <= roof; y++)
            for (int ox = -radius; ox <= radius; ox++)
                for (int oz = -radius; oz <= radius; oz++) {
                    if (ox * ox + oz * oz > r2) continue;
                    var pos = new BlockPos(from.getX() + ox, y, from.getZ() + oz);
                    var state = level.getBlockState(pos);
                    cells.add(pos);
                    states.add(state);
                    var be = level.getBlockEntity(pos);
                    if (be != null) saved.put(pos, be.saveWithFullMetadata(level.registryAccess()));
                }

        // Who travels: everything but the monsters (and the ward's own aura and dragons, which follow the chest).
        var box = new AABB(
                from.getX() - radius - 1,
                floor - 1,
                from.getZ() - radius - 1,
                from.getX() + radius + 2,
                roof + 3,
                from.getZ() + radius + 2);
        var riders = new ArrayList<Entity>();
        for (Entity e : level.getEntities((Entity) null, box, e -> inside(from, radius, e.position()))) {
            if (e instanceof WardAura || e instanceof LightDragon || LightWards.blocked(e)) continue;
            if (e.isPassenger() && !(e instanceof ServerPlayer)) continue; // rides along with its mount
            if (e.isPassenger()) e.stopRiding();
            riders.add(e);
        }

        // Lift it out, top down, without drops or neighbour updates (torches and doors must not pop off).
        int lift = Block.UPDATE_CLIENTS
                | Block.UPDATE_KNOWN_SHAPE
                | Block.UPDATE_SUPPRESS_DROPS
                | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
        for (int i = cells.size() - 1; i >= 0; i--) {
            if (states.get(i).isAir()) continue;
            var pos = cells.get(i);
            if (level.getBlockEntity(pos) instanceof Clearable box2) box2.clearContent();
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), lift);
        }
        LightWards.remove(level, from);
        LightDragons.remove(level, from);

        // Set it down, bottom up, then pour the saved contents back into the block entities.
        int place = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int i = 0; i < cells.size(); i++) {
            var state = states.get(i);
            var dst = cells.get(i).offset(dx, dy, dz);
            if (state.isAir() && level.getBlockState(dst).isAir()) continue;
            level.setBlock(dst, state, place);
        }
        for (var entry : saved.entrySet()) {
            var dst = entry.getKey().offset(dx, dy, dz);
            var be = level.getBlockEntity(dst);
            if (be == null) continue;
            be.loadWithComponents(
                    TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), entry.getValue()));
            be.setChanged();
            level.sendBlockUpdated(dst, level.getBlockState(dst), level.getBlockState(dst), Block.UPDATE_CLIENTS);
        }

        // The old site: earth where the ground was, grass where that ground met open air, open sky above. Every layer
        // of the plug holds the same columns in the same order, so the cell above is one layer further on.
        int perLayer = cells.size() / (roof - floor + 1);
        for (int i = 0; i < cells.size(); i++) {
            var pos = cells.get(i);
            if (pos.getY() >= from.getY() || !states.get(i).isSolid()) continue;
            boolean top = pos.getY() == from.getY() - 1
                    || i + perLayer < cells.size() && !states.get(i + perLayer).isSolid();
            level.setBlock(pos, (top ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        // Anything left standing over the plug at the new place (a tree's crown, a hill's top) is erased.
        int clear = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;
        for (int ox = -radius; ox <= radius; ox++)
            for (int oz = -radius; oz <= radius; oz++) {
                if (ox * ox + oz * oz > r2) continue;
                int cx = to.getX() + ox, cz = to.getZ() + oz;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz);
                for (int y = roof + dy + 1; y < top; y++) {
                    var pos = new BlockPos(cx, y, cz);
                    if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), clear);
                }
            }

        // The chest's identity at its new place.
        LightWards.add(level, to);
        if (owner != null) {
            var owners = new HashMap<>(level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of()));
            owners.remove(key);
            owners.put(Long.toString(to.asLong()), owner);
            level.setAttached(ModData.LIGHT_WARD_OWNERS, Map.copyOf(owners));
        }
        ChestGlow.place(level, to);
        Scheduler.later(10, () -> ChestGlow.refresh(level, to));
        LightDragons.reconcile(level, to);
        DarkNests.homeMoved(level, from);

        // The people and creatures, the world spawn and the respawn points inside the ward.
        for (Entity e : riders) {
            e.teleportTo(level, e.getX() + dx, e.getY() + dy, e.getZ() + dz, Set.of(), e.getYRot(), e.getXRot(), true);
            e.resetFallDistance();
            if (e instanceof ServerPlayer p) {
                CollectionLight.release(p);
                p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 60, 0, false, false, true));
            }
        }
        var spawn = level.getRespawnData();
        if (spawn.dimension() == level.dimension() && inside(from, radius, Vec3.atBottomCenterOf(spawn.pos())))
            level.setRespawnData(LevelData.RespawnData.of(
                    level.dimension(), spawn.pos().offset(dx, dy, dz), spawn.yaw(), spawn.pitch()));
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            var config = p.getRespawnConfig();
            if (config != null
                    && config.respawnData().dimension() == level.dimension()
                    && inside(
                            from,
                            radius,
                            Vec3.atBottomCenterOf(config.respawnData().pos()))) {
                var data = config.respawnData();
                p.setRespawnPosition(
                        new net.minecraft.server.level.ServerPlayer.RespawnConfig(
                                LevelData.RespawnData.of(
                                        data.dimension(), data.pos().offset(dx, dy, dz), data.yaw(), data.pitch()),
                                config.forced()),
                        false);
            }
            var journey = p.getAttached(DragonTalk.JOURNEY);
            if (journey != null && from.equals(journey.home()))
                p.setAttached(
                        DragonTalk.JOURNEY,
                        new DragonTalk.Journey(journey.stage(), journey.site(), to, journey.enemies()));
        }
        var c = Vec3.atCenterOf(to);
        level.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 1, c.z, 160, radius * .5, 3, radius * .5, .08);
        Lightborn.LOG.info("Home moved from {} to {} (radius {})", from.toShortString(), to.toShortString(), radius);
        return to;
    }

    /** One beat of the landing: a lighter hit, then the heavy one with dust thrown off the ward's rim. */
    private static void thud(ServerLevel level, BlockPos to, boolean heavy) {
        if (heavy) {
            level.playSound(null, to, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.BLOCKS, 4f, .5f);
            level.playSound(null, to, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 2.5f, .45f);
            level.playSound(null, to, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1.5f, .5f);
            var c = Vec3.atBottomCenterOf(to);
            level.sendParticles(ParticleTypes.CLOUD, c.x, c.y + .2, c.z, 120, 6, .2, 6, .15);
        } else {
            level.playSound(null, to, SoundEvents.MACE_SMASH_GROUND, SoundSource.BLOCKS, 3f, .7f);
        }
    }
}
