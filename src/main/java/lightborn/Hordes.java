package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** The Overworld moon schedules scout packs; the Cleanse runs on its clock. Ambient spawning is separate. */
final class Hordes {
    private Hordes() {}

    static final int DAY_TICKS = 24_000, POLL_TICKS = 20;
    private static final int SCOUT_DIRECTION_TRIES = 16, HUNTER_DIRECTION_TRIES = 24, SURFACE_HEIGHT_RANGE = 12;
    static final String SCOUT_TAG = "lightborn_scout";
    static final String HUNTER_TAG = "lightborn_hunter";
    private static List<EntityType<? extends Mob>> scouts = List.of();

    record MoonNight(long day, int time, MoonPhase phase) {
        static MoonNight read(ServerLevel level) {
            long clock = level.getOverworldClockTime();
            var phase = level.environmentAttributes()
                    .getValue(
                            EnvironmentAttributes.MOON_PHASE,
                            level.getRespawnData().pos());
            return new MoonNight(Math.floorDiv(clock, DAY_TICKS), Math.floorMod(clock, DAY_TICKS), phase);
        }

        boolean darkHours() {
            return time >= Balance.DUSK_TICK && time < Balance.DAWN_TICK;
        }

        long latestHunt() {
            int sinceDark = Math.floorMod(phase.index() - MoonPhase.NEW_MOON.index(), MoonPhase.values().length);
            return day - (sinceDark == 0 && time < Balance.DUSK_TICK ? MoonPhase.values().length : sinceDark);
        }
    }

    record ScoutNight(long day, int sent) {
        static final Codec<ScoutNight> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Codec.LONG.fieldOf("day").forGetter(ScoutNight::day),
                        Codec.INT.fieldOf("sent").forGetter(ScoutNight::sent))
                .apply(i, ScoutNight::new));
    }

    static void init() {
        scouts = List.of(
                EntityTypes.ZOMBIE,
                EntityTypes.ZOMBIE,
                EntityTypes.HUSK,
                ModEntities.DARKNESS_ZOMBIE,
                ModEntities.VAMPIRE_ZOMBIE);
    }

    static boolean enabled(ServerLevel level) {
        return level.getDifficulty() != Difficulty.PEACEFUL
                && level.getGameRules().get(GameRules.SPAWN_MONSTERS);
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % POLL_TICKS != 0) return;
        ServerLevel level = server.overworld();
        MoonNight night = MoonNight.read(level);
        Cleanse.tick(level, night);
        if (!enabled(level)) return;
        for (ServerPlayer p : level.players()) {
            if (huntable(p) && night.darkHours() && !Cleanse.active(p)) scoutTick(level, p, night);
        }
        if (night.darkHours()) retargetScouts(level);
    }

    static boolean huntable(ServerPlayer p) {
        return p.isAlive() && !p.isSpectator() && !p.isCreative();
    }

    static int scoutPacks(MoonPhase phase) {
        return switch (phase) {
            case WANING_GIBBOUS, THIRD_QUARTER -> Balance.STIRRING_PACKS;
            case WANING_CRESCENT -> Balance.EVE_PACKS;
            default -> 0;
        };
    }

    static void scoutTick(ServerLevel level, ServerPlayer p, MoonNight night) {
        int packs = scoutPacks(night.phase);
        if (packs == 0 || night.time < Balance.SCOUT_START_TICK) return;
        int window = (Balance.DAWN_TICK - Balance.SCOUT_START_TICK) / packs;
        int slot = (night.time - Balance.SCOUT_START_TICK) / window;
        if (slot >= packs) return;
        var previous = p.getAttached(ModData.SCOUT_NIGHT);
        int sent = previous != null && previous.day == night.day ? previous.sent : 0;
        if ((sent & (1 << slot)) != 0) return;
        // The time is stable across reconnects, and the saved mask prevents a repeat after a restart.
        long seed = level.getSeed() ^ p.getUUID().getMostSignificantBits() ^ Long.rotateLeft(night.day, 19);
        int due = Balance.SCOUT_START_TICK
                + window * slot
                + RandomSource.create(seed + slot).nextInt(window - POLL_TICKS);
        if (night.time < due) return;
        p.setAttached(ModData.SCOUT_NIGHT, new ScoutNight(night.day, sent | (1 << slot)));
        if (LightWards.covers(level, p.blockPosition())
                || scoutPack(level, p, packs == Balance.EVE_PACKS).isEmpty()) return;
        double angle = level.getRandom().nextDouble() * Math.PI * 2;
        double distance = Balance.SCOUT_GROAN_DISTANCE;
        level.playSound(
                null,
                p.getX() + distance * Math.cos(angle),
                p.getY(),
                p.getZ() + distance * Math.sin(angle),
                SoundEvents.ZOMBIE_AMBIENT,
                SoundSource.HOSTILE,
                1.0f,
                0.6f);
    }

    static List<Mob> scoutPack(ServerLevel level, ServerPlayer p, boolean eve) {
        if (LightWards.covers(level, p.blockPosition())) return List.of();
        var random = level.getRandom();
        int size =
                between(random, eve ? Balance.EVE_MIN : Balance.SCOUT_MIN, eve ? Balance.EVE_MAX : Balance.SCOUT_MAX);
        double direction = random.nextDouble() * Math.PI * 2;
        var made = new ArrayList<Mob>();
        for (int i = 0; i < size; i++) {
            Mob mob = prepare(level, p.position(), pick(scouts, random), direction, false, null);
            if (mob == null) continue;
            mob.addTag(SCOUT_TAG);
            if (level.addFreshEntity(mob)) {
                aim(mob, p);
                made.add(mob);
            } else mob.discard();
        }
        return made;
    }

    private static void retargetScouts(ServerLevel level) {
        for (ServerPlayer p : level.players()) {
            if (!huntable(p) || LightWards.covers(level, p.blockPosition())) continue;
            for (Mob m : level.getEntitiesOfClass(
                    Mob.class,
                    p.getBoundingBox().inflate(Balance.HUNTER_FOLLOW_RANGE),
                    m -> m.isAlive() && m.entityTags().contains(SCOUT_TAG) && m.getTarget() == null)) {
                if (m.distanceToSqr(p) <= Balance.HUNTER_FOLLOW_RANGE * Balance.HUNTER_FOLLOW_RANGE) aim(m, p);
            }
        }
    }

    static void horn(ServerPlayer p) {
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.RAID_HORN, SoundSource.HOSTILE, 0.6f, 0.7f);
    }

    static void aim(Mob mob, ServerPlayer p) {
        if (BossTrophies.concealed(p)) return;
        if (mob.getTarget() == p) return;
        mob.setTarget(p);
        mob.getNavigation().moveTo(p, 1.0);
    }

    static <T> T pick(List<T> values, RandomSource random) {
        return values.get(random.nextInt(values.size()));
    }

    static int between(RandomSource random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    /** Prepare one mob in a sector before publishing it to the level's entity-load listeners. */
    static Mob prepare(
            ServerLevel level,
            Vec3 origin,
            EntityType<? extends Mob> type,
            double direction,
            boolean hunter,
            Bosses.Encounter boss) {
        var random = level.getRandom();
        Mob mob = VariantCompat.keepKind(type.create(level, EntitySpawnReason.NATURAL));
        if (mob == null) return null;
        for (int attempt = 0; attempt < (hunter ? HUNTER_DIRECTION_TRIES : SCOUT_DIRECTION_TRIES); attempt++) {
            // Try other sides if the chosen approach is blocked, lit or underwater.
            double centre = attempt < 12 ? direction : random.nextDouble() * Math.PI * 2;
            double angle = centre + (random.nextDouble() - 0.5) * Math.PI / 3;
            BlockPos at = spot(level, origin, angle, random, false, hunter);
            if (at != null && position(level, mob, at, boss != null, random)) {
                return finalizeMob(level, mob, at, origin, hunter, boss, random);
            }
        }
        if (hunter) {
            for (int attempt = 0; attempt < HUNTER_DIRECTION_TRIES; attempt++) {
                double angle = direction + (random.nextDouble() - 0.5) * Math.PI * 2;
                BlockPos at = spot(level, origin, angle, random, true, true);
                if (at != null && position(level, mob, at, boss != null, random)) {
                    return finalizeMob(level, mob, at, origin, hunter, boss, random);
                }
            }
        }
        mob.discard();
        return null;
    }

    private static Mob finalizeMob(
            ServerLevel level,
            Mob mob,
            BlockPos at,
            Vec3 origin,
            boolean hunter,
            Bosses.Encounter boss,
            RandomSource random) {
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.NATURAL, null);
        if (mob instanceof Zombie zombie) zombie.setCanBreakDoors(mob instanceof GiantZombie);
        if (boss != null) {
            Bosses.promote(mob, boss);
        } else if (hunter) {
            Monsters.setRank(mob, MonsterInfo.NORMAL);
        }
        if (!level.noCollision(mob) || !mob.checkSpawnObstruction(level)) {
            mob.discard();
            return null;
        }
        var follow = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow != null) {
            double range = Balance.HUNTER_FOLLOW_RANGE;
            if (hunter)
                range = Math.max(range, mob.position().distanceTo(origin) + Balance.HUNT_APPROACH_FOLLOW_MARGIN);
            follow.setBaseValue(Math.max(follow.getBaseValue(), range));
            // Zombies roll a follow-range modifier in finalizeSpawn.
            if (follow.getValue() < range)
                follow.setBaseValue(follow.getBaseValue() * range * 1.001 / follow.getValue());
        }
        return mob;
    }

    private static BlockPos spot(
            ServerLevel level, Vec3 origin, double angle, RandomSource random, boolean cave, boolean hunt) {
        int min = cave ? Balance.CAVE_SPAWN_MIN : Balance.HUNTER_SPAWN_MIN;
        int max = cave ? Balance.CAVE_SPAWN_MAX : Balance.HUNTER_SPAWN_MAX;
        double distance = min + 1 + random.nextDouble() * (max - min - 2);
        if (hunt) {
            distance = LightWards.approachDistance(level, origin, angle, distance);
            if (distance < 0) return null;
        }
        int x = (int) Math.floor(origin.x + Math.cos(angle) * distance);
        int z = (int) Math.floor(origin.z + Math.sin(angle) * distance);
        if (cave) {
            if (!level.hasChunk(x >> 4, z >> 4)) return null;
            for (int dy : new int[] {0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6}) {
                BlockPos at = new BlockPos(x, (int) Math.floor(origin.y) + dy, z);
                double dist = origin.distanceToSqr(x + 0.5, at.getY(), z + 0.5);
                double allowed = Math.max(max, distance + 2);
                if (dist >= min * min && dist <= allowed * allowed && usable(level, at, hunt)) return at;
            }
            return null;
        }
        BlockPos at = surface(level, x, z);
        return at != null && Math.abs(at.getY() - origin.y) <= SURFACE_HEIGHT_RANGE && usable(level, at, hunt)
                ? at
                : null;
    }

    private static boolean position(ServerLevel level, Mob mob, BlockPos at, boolean boss, RandomSource random) {
        if (!level.isPositionEntityTicking(at)) return false;
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360f, 0f);
        if (!level.noCollision(mob) || !mob.checkSpawnObstruction(level)) return false;
        if (!boss) return true;
        // Bosses grow to twice their normal size on promotion.
        var box = mob.getBoundingBox();
        return level.noCollision(
                mob,
                box.inflate((box.maxX - box.minX) / 2, 0, (box.maxZ - box.minZ) / 2)
                        .expandTowards(0, box.maxY - box.minY, 0));
    }

    static BlockPos surface(ServerLevel level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /**
     * A spawn spot: dry, solid ground, in the border, outside every ward. Scouts also need darkness; a Cleanse's dark
     * comes straight out of the dome's own torchlight at its edge ({@code lit}).
     */
    static boolean usable(ServerLevel level, BlockPos at, boolean lit) {
        BlockPos below = at.below();
        return at.getY() > level.getMinY()
                && at.getY() < level.getMaxY()
                && level.getWorldBorder().isWithinBounds(at)
                && level.getFluidState(at).isEmpty()
                && level.getFluidState(below).isEmpty()
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && (lit || level.getBrightness(LightLayer.BLOCK, at) < Balance.HUNTER_LIGHT_LIMIT)
                && !LightWards.covers(level, at);
    }
}
