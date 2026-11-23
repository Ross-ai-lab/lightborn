package lightborn;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The dawn refugee (owner, 2026-11-15): on one night in five, shortly before dawn, a frightened stranger comes running
 * out of the dark into a home's ward, begging to be let stay. The owner may throw them out, fight them, or let them
 * stay; one who stays repays the night at sunrise by offering to lead the way to one of up to three nearby places no
 * player has found yet (a village, a temple, a ruined portal...). They walk there ahead of you, stop and call when you
 * fall behind, stay at the place once shown, and slip away the moment nobody is watching.
 * <p>
 * The talk reuses the Dragon's dialogue box ({@link DragonTalk.Reply} with a speaker name); answers come back through
 * {@link DragonTalk.Answer} keyed by this entity's id.
 */
public final class Refugee extends PathfinderMob {
    static final int RUNNING = 0, SHELTERED = 1, GUIDING = 2, ARRIVED = 3, LEAVING = 4, HOSTILE = 5;
    /** Answer actions: 0 opens, 1 lets them stay, 2 sends them away, 10..12 picks an offered place. */
    static final int STAY = 1, SEND_AWAY = 2, PICK = 10;

    static final int SKINS = 9;
    private static final EntityDataAccessor<Integer> SKIN =
            SynchedEntityData.defineId(Refugee.class, EntityDataSerializers.INT);

    /** Places already shown by some refugee in this world (packed positions): never offered twice. */
    static final AttachmentType<List<Long>> SHOWN =
            AttachmentRegistry.create(ModItems.id("refugee_shown"), b -> b.persistent(Codec.LONG.listOf()));

    /** Homes already rolled tonight ("chest@day"), so each home rolls once per night. */
    private static final Set<String> ROLLED = ConcurrentHashMap.newKeySet();

    public static final String[] NAMES = {
        "Ash", "Bryn", "Cass", "Dell", "Ember", "Fen", "Gale", "Hollis", "Ivo", "Jory", "Kit", "Lark", "Morrow", "Nell",
        "Oren", "Pell", "Quill", "Rowan", "Sage", "Tamsin", "Vale", "Wren", "Yael", "Zeph"
    };

    static final String[] PLEAS = {
        "Please! Let me in! I don't want to die out here tonight!",
        "Protect me, please! They're right behind me!",
        "Light! Oh, thank the Light. Let me in, please!",
        "Don't shut me out. I beg you, not tonight!",
        "They took everyone else. Please, just until morning!",
        "Please, I can hear them in the dark. Let me stay by your light!"
    };

    /** A kind of place a refugee may know the way to: walkable, above ground, worth the walk. */
    record Kind(String name, TagKey<Structure> tag, ResourceKey<Structure> key) {
        HolderSet<Structure> holders(ServerLevel level) {
            var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            if (tag != null)
                return registry.get(tag).map(set -> (HolderSet<Structure>) set).orElse(null);
            return registry.get(key)
                    .map(h -> (HolderSet<Structure>) HolderSet.direct(h))
                    .orElse(null);
        }
    }

    static final List<Kind> KINDS = List.of(
            new Kind("a village", StructureTags.VILLAGE, null),
            new Kind("a pillager outpost", null, BuiltinStructures.PILLAGER_OUTPOST),
            new Kind("a ruined portal", StructureTags.RUINED_PORTAL, null),
            new Kind("a desert pyramid", StructureTags.ON_DESERT_PYRAMID_MAPS, null),
            new Kind("a jungle temple", StructureTags.ON_JUNGLE_PYRAMID_MAPS, null),
            new Kind("a witch hut", StructureTags.ON_SWAMP_HUT_MAPS, null),
            new Kind("an igloo", null, BuiltinStructures.IGLOO),
            new Kind("an abandoned camp", StructureTags.ABANDONED_CAMP, null),
            new Kind("trail ruins", null, BuiltinStructures.TRAIL_RUINS),
            new Kind("a beached shipwreck", null, BuiltinStructures.SHIPWRECK_BEACHED),
            new Kind("buried treasure", StructureTags.ON_TREASURE_MAPS, null));

    record Site(String name, BlockPos pos) {}

    int state = RUNNING;
    BlockPos chest = BlockPos.ZERO;
    long day = -1;
    UUID guided;
    /** The player being led, when known in memory (also how the self-test hands in a fake player). */
    Player follower;

    BlockPos target;
    String targetName = "";
    boolean announced, waiting;
    int dayTicks, unseen, callCooldown, stuck, detour, pleaded, lost, beaconCooldown;
    private Vec3 lastPos;
    private double detourX, detourZ;

    /** Places found for the dawn offer; worked out one kind at a time while they shelter (not saved, redone). */
    final List<Site> offers = new ArrayList<>();

    private List<Integer> order;
    private int searched;
    boolean searchDone;

    public Refugee(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 3)
                .add(Attributes.FOLLOW_RANGE, 32);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(SKIN, 0);
    }

    public int skin() {
        return entityData.get(SKIN);
    }

    public void setSkin(int skin) {
        entityData.set(SKIN, Math.floorMod(skin, SKINS));
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.25, false) {
            @Override
            public boolean canUse() {
                return state == HOSTILE && super.canUse();
            }
        });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    String name() {
        return getCustomName() == null ? "Stranger" : getCustomName().getString();
    }

    // ------------------------------------------------------------------ arrival

    /** Whether a refugee comes to this home on the night of {@code day}: one night in five, fixed per home and night. */
    static boolean arrives(ServerLevel level, BlockPos chest, long day) {
        long mix = level.getSeed() ^ chest.asLong() * 0x9E3779B97F4A7C15L ^ day * 0xC2B2AE3D27D4EB4FL;
        return RandomSource.create(mix).nextFloat() < Balance.REFUGEE_CHANCE;
    }

    /** Once a second: shortly before dawn, each home whose owner is at home rolls once for a refugee. */
    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        ServerLevel level = server.overworld();
        long clock = level.getOverworldClockTime();
        long time = Math.floorMod(clock, 24000), day = Math.floorDiv(clock, 24000);
        if (time < Balance.DAWN_TICK - Balance.REFUGEE_LEAD_TICKS || time >= Balance.DAWN_TICK - 400) return;
        for (var entry : level.getAttachedOrElse(
                        ModData.LIGHT_WARD_OWNERS, java.util.Map.<String, LightWards.Owner>of())
                .entrySet()) {
            BlockPos chest = BlockPos.of(Long.parseLong(entry.getKey()));
            ServerPlayer owner =
                    server.getPlayerList().getPlayer(entry.getValue().player());
            if (owner == null
                    || owner.level() != level
                    || owner.distanceToSqr(Vec3.atCenterOf(chest)) > 64 * 64
                    || !level.isLoaded(chest)
                    || !level.getBlockState(chest).is(ModItems.LIGHT_CHEST)) continue;
            if (!ROLLED.add(entry.getKey() + "@" + day) || !arrives(level, chest, day)) continue;
            if (Cleanse.active(owner) || LightWards.suspended(level, chest)) continue;
            if (!level.getEntitiesOfClass(Refugee.class, new AABB(chest).inflate(96))
                    .isEmpty()) continue;
            spawn(level, chest, entry.getValue().radius(), day);
        }
    }

    static void clear() {
        ROLLED.clear();
    }

    /** Registers the world's list of shown places (the attachment must exist before any world loads). */
    static void init() {
        java.util.Objects.requireNonNull(SHOWN);
    }

    /** Sets a refugee down on dry ground just outside the ward, running for the chest. Null if no spot was found. */
    static Refugee spawn(ServerLevel level, BlockPos chest, int radius, long day) {
        var random = level.getRandom();
        double start = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 12; i++) {
            double a = start + i * Math.PI / 6;
            int x = chest.getX() + (int) Math.round(Math.cos(a) * (radius + Balance.REFUGEE_START_OUTSIDE));
            int z = chest.getZ() + (int) Math.round(Math.sin(a) * (radius + Balance.REFUGEE_START_OUTSIDE));
            if (!level.hasChunkAt(new BlockPos(x, chest.getY(), z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.getFluidState(feet.below()).isEmpty()
                    || !level.getBlockState(feet.below()).isSolid()) continue;
            var r = ModEntities.REFUGEE.create(level, EntitySpawnReason.EVENT);
            if (r == null) return null;
            float yaw = (float) (Mth.atan2(chest.getZ() - z, chest.getX() - x) * Mth.RAD_TO_DEG) - 90;
            r.snapTo(x + .5, y, z + .5, yaw, 0);
            r.chest = chest.immutable();
            r.day = day;
            r.setSkin(random.nextInt(SKINS));
            r.setCustomName(Component.literal(NAMES[random.nextInt(NAMES.length)]));
            level.addFreshEntity(r);
            r.shout(PLEAS[random.nextInt(PLEAS.length)]);
            return r;
        }
        return null;
    }

    // ------------------------------------------------------------------ behaviour

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (callCooldown > 0) callCooldown--;
        if (beaconCooldown > 0) beaconCooldown--;
        switch (state) {
            case RUNNING -> run(level);
            case SHELTERED -> shelter(level);
            case GUIDING -> guideStep(level, follower(level));
            case ARRIVED, LEAVING -> lingerStep(level, level.players());
            case HOSTILE -> {
                if (getTarget() == null || !getTarget().isAlive()) {
                    setTarget(null);
                    state = LEAVING;
                }
            }
            default -> {}
        }
        if (state == GUIDING) beacon(level, follower(level));
        else if (state == SHELTERED && announced) beacon(level, level.getNearestPlayer(this, 64));
        else lost = 0;
        swimStep();
    }

    /**
     * Calls out when the one he is waiting for has lost him (owner, 2026-11-16: silent, he was easy to lose): after
     * {@link Balance#REFUGEE_LOST_TICKS} out of their sight, every {@link Balance#REFUGEE_BEACON_TICKS} he shouts which
     * way he is from where they are looking, whistles loud enough to hear through trees, glows through walls for three
     * seconds and sends a short column of light up.
     */
    void beacon(ServerLevel level, Player p) {
        if (p == null || p.level() != level() || isRemoved()) {
            lost = 0;
            return;
        }
        boolean seen = distanceToSqr(p) < 6 * 6 || sees(p);
        lost = seen ? 0 : lost + 1;
        if (lost < Balance.REFUGEE_LOST_TICKS || beaconCooldown > 0) return;
        beaconCooldown = Balance.REFUGEE_BEACON_TICKS;
        callCooldown = Math.max(callCooldown, 120);
        say(p, whereLine(p));
        for (float pitch : new float[] {1.2f, 1.6f})
            level.playSound(
                    null,
                    getX(),
                    getEyeY(),
                    getZ(),
                    SoundEvents.NOTE_BLOCK_FLUTE.value(),
                    SoundSource.NEUTRAL,
                    3f,
                    pitch);
        addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
        for (int i = 0; i < 12; i++)
            level.sendParticles(
                    ParticleTypes.END_ROD, true, true, getX(), getY() + 2.2 + i * .45, getZ(), 2, .05, .1, .05, 0);
    }

    /** Whether he is in front of this player and nothing blocks the view. */
    private boolean sees(Player p) {
        Vec3 to = position().add(0, getBbHeight() * .6, 0).subtract(p.getEyePosition());
        double len = to.length();
        return len < 96 && p.getViewVector(1).dot(to.scale(1 / len)) > .5 && p.hasLineOfSight(this);
    }

    /** "Over here, to your left!": which way he is from where the player is looking, and roughly how far. */
    String whereLine(Player p) {
        Vec3 to = position().subtract(p.position());
        float yaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90;
        float turn = Mth.wrapDegrees(yaw - p.getYRot());
        int blocks = Math.max(1, (int) Math.round(Math.hypot(to.x, to.z) / 5) * 5);
        String height = to.y > 6 ? ", up here" : to.y < -6 ? ", down here" : "";
        String way = Math.abs(turn) < 35
                ? "Straight ahead of you"
                : Math.abs(turn) > 125
                        ? "Behind you"
                        : turn > 0 ? "Over here, to your right" : "Over here, to your left";
        return way + height + "! About " + blocks + " blocks. " + (state == GUIDING ? "Follow my voice!" : "I'm here!");
    }

    /**
     * Swims like a person rather than wading like a zombie (owner, 2026-11-16): in water he lies flat in a crawl and
     * strokes toward the next point of his path at about a swimming player's speed.
     */
    private void swimStep() {
        var path = getNavigation().getPath();
        boolean stroke = isInWater() && state != HOSTILE && path != null && !path.isDone() && !isPassenger();
        if (!stroke) {
            if (getPose() == Pose.SWIMMING && !isInWater()) setPose(Pose.STANDING);
            return;
        }
        Vec3 next = path.getNextEntityPos(this);
        double dx = next.x - getX(), dz = next.z - getZ(), d = Math.hypot(dx, dz);
        if (d < .3) return;
        Vec3 v = getDeltaMovement();
        if (Math.hypot(v.x, v.z) < Balance.REFUGEE_SWIM_SPEED)
            setDeltaMovement(v.x + dx / d * Balance.REFUGEE_SWIM_PUSH, v.y, v.z + dz / d * Balance.REFUGEE_SWIM_PUSH);
        setYRot((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90);
        yBodyRot = getYRot();
        if (getPose() != Pose.SWIMMING) setPose(Pose.SWIMMING);
    }

    private void run(ServerLevel level) {
        if (tickCount % 10 == 0) getNavigation().moveTo(chest.getX() + .5, chest.getY(), chest.getZ() + 1.5, 1.6);
        if (pleaded == 0 && tickCount > 120) {
            pleaded = 1;
            shout(PLEAS[getRandom().nextInt(PLEAS.length)]);
        }
        if (distanceToSqr(Vec3.atBottomCenterOf(chest)) < 16) {
            state = SHELTERED;
            getNavigation().stop();
            shout("Thank you... thank you. I'll stay out of your way, I swear.");
        } else if (tickCount > Balance.REFUGEE_GIVE_UP_TICKS) state = LEAVING;
    }

    private void shelter(ServerLevel level) {
        if (!searchDone && tickCount % 10 == 0) searchStep(level);
        double d = distanceToSqr(Vec3.atBottomCenterOf(chest));
        if (d > 36 && tickCount % 20 == 0)
            getNavigation().moveTo(chest.getX() + .5, chest.getY(), chest.getZ() + 1.5, 1);
        if (LightDragons.night(level)) return;
        if (!announced) {
            announced = true;
            shout("The sun's up. I'm alive because of you. Come talk to me, I want to repay you.");
        }
        if (++dayTicks > Balance.REFUGEE_DAY_WAIT_TICKS) {
            shout("I can't wait any longer. Thank you for the night.");
            state = LEAVING;
        }
    }

    /** Works out one more kind of place for the dawn offer, until three are found or every kind has been tried. */
    void searchStep(ServerLevel level) {
        if (order == null) {
            order = new ArrayList<>();
            for (int i = 0; i < KINDS.size(); i++) order.add(i);
            java.util.Collections.shuffle(order, new java.util.Random(getUUID().getLeastSignificantBits()));
        }
        if (offers.size() >= 3 || searched >= order.size()) {
            searchDone = true;
            return;
        }
        Kind kind = KINDS.get(order.get(searched++));
        var set = kind.holders(level);
        if (set == null) return;
        var found = level.getChunkSource()
                .getGenerator()
                .findNearestMapStructure(level, set, chest, Balance.REFUGEE_SEARCH_CHUNKS, false);
        if (found != null && fresh(level, chest, found.getFirst())) offers.add(new Site(kind.name(), found.getFirst()));
    }

    /**
     * Not found yet: well clear of the home, never shown by another refugee, and no player has spent a minute near it
     * (a chunk's inhabited time only grows while a player is within about 128 blocks).
     */
    static boolean fresh(ServerLevel level, BlockPos chest, BlockPos at) {
        if (Math.hypot(at.getX() - chest.getX(), at.getZ() - chest.getZ()) < Balance.REFUGEE_MIN_SITE_DISTANCE)
            return false;
        for (long shown : level.getAttachedOrElse(SHOWN, List.of()))
            if (BlockPos.of(shown).distManhattan(at.atY(BlockPos.of(shown).getY())) < 64) return false;
        var chunk = level.getChunk(at.getX() >> 4, at.getZ() >> 4, ChunkStatus.STRUCTURE_STARTS, true);
        return chunk.getInhabitedTime() < Balance.REFUGEE_KNOWN_TICKS;
    }

    private Player follower(ServerLevel level) {
        if (follower != null && !follower.isRemoved() && follower.getUUID().equals(guided)) return follower;
        follower = guided == null ? null : level.getServer().getPlayerList().getPlayer(guided);
        return follower;
    }

    /**
     * One step of leading the way: walk a stretch toward the place, stop and call out when the player falls more than
     * 24 blocks behind, go on once they are within 10, and stay put on arrival.
     */
    void guideStep(ServerLevel level, Player p) {
        if (target == null) {
            state = LEAVING;
            return;
        }
        double tx = target.getX() + .5 - getX(), tz = target.getZ() + .5 - getZ();
        double dist = Math.hypot(tx, tz);
        if (dist < Balance.REFUGEE_ARRIVE_DISTANCE) {
            arrive(level);
            return;
        }
        if (p == null || p.level() != level()) {
            getNavigation().stop();
            return;
        }
        double behind = Math.hypot(p.getX() - getX(), p.getZ() - getZ());
        if (waiting && behind <= Balance.REFUGEE_RESUME_DISTANCE) waiting = false;
        else if (!waiting && behind > Balance.REFUGEE_WAIT_DISTANCE) waiting = true;
        if (waiting) {
            getNavigation().stop();
            getLookControl().setLookAt(p);
            if (callCooldown == 0) {
                callCooldown = 160;
                say(p, getRandom().nextBoolean() ? "This way! Keep up!" : "Over here! Don't lose me!");
            }
            return;
        }
        if (tickCount % 40 == 0) {
            stuck = lastPos != null && position().distanceToSqr(lastPos) < 1 ? stuck + 40 : 0;
            lastPos = position();
        }
        if (stuck >= 120 && detour == 0) {
            // Blocked: step around at an angle for a few seconds; if that fails too, slip past unseen.
            double side = (getRandom().nextBoolean() ? 1 : -1) * Math.PI * .4, a = Math.atan2(tz, tx) + side;
            detourX = getX() + Math.cos(a) * 12;
            detourZ = getZ() + Math.sin(a) * 12;
            detour = 60;
        }
        if (stuck >= 320 && !watched(level.players())) {
            hopAhead(level, tx / dist, tz / dist);
            stuck = 0;
        }
        if (detour > 0) {
            detour--;
            if (detour % 20 == 19) walkTo(level, detourX, detourZ, 1.1);
            return;
        }
        if (tickCount % 20 == 0 || getNavigation().isDone()) {
            double step = Math.min(dist, 20);
            walkTo(level, getX() + tx / dist * step, getZ() + tz / dist * step, 1.1);
        }
    }

    private void walkTo(ServerLevel level, double x, double z, double speed) {
        BlockPos at = BlockPos.containing(x, getY(), z);
        if (!level.hasChunkAt(at)) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        getNavigation().moveTo(at.getX() + .5, y, at.getZ() + .5, speed);
    }

    private void hopAhead(ServerLevel level, double dx, double dz) {
        for (int step = 10; step >= 4; step -= 2) {
            int x = Mth.floor(getX() + dx * step), z = Mth.floor(getZ() + dz * step);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (level.getFluidState(new BlockPos(x, y - 1, z)).is(FluidTags.LAVA)) continue;
            teleportTo(x + .5, y, z + .5);
            return;
        }
    }

    private void arrive(ServerLevel level) {
        state = ARRIVED;
        getNavigation().stop();
        unseen = 0;
        Player p = follower(level);
        String line = "There it is: " + targetName + ". I think I'll stay here a while. Thank you, for the night.";
        if (p != null) say(p, line);
        else shout(line);
    }

    /** Whether any of these players is looking roughly toward this refugee with nothing in between. */
    boolean watched(List<? extends Player> players) {
        for (Player p : players) {
            if (p.isSpectator() || p.level() != level()) continue;
            Vec3 to = getEyePosition().subtract(p.getEyePosition());
            double len = to.length();
            if (len > 96) continue;
            if (len < 1e-3 || p.getViewVector(1).dot(to.scale(1 / len)) > .5 && p.hasLineOfSight(this)) return true;
        }
        return false;
    }

    /** Shown the way, or sent off: walks away (if sent off) and is gone once nobody has watched for three seconds. */
    void lingerStep(ServerLevel level, List<? extends Player> players) {
        if (state == LEAVING && tickCount % 40 == 0) {
            Vec3 away = position().subtract(Vec3.atBottomCenterOf(chest)).multiply(1, 0, 1);
            if (away.lengthSqr() < 1) away = new Vec3(1, 0, 0);
            away = away.normalize().scale(24);
            walkTo(level, getX() + away.x, getZ() + away.z, 1.3);
        }
        double nearest = Double.MAX_VALUE;
        for (Player p : players) if (p.level() == level()) nearest = Math.min(nearest, distanceTo(p));
        boolean seen = watched(players);
        unseen = seen ? 0 : unseen + 1;
        if (unseen >= Balance.REFUGEE_UNSEEN_TICKS && nearest > 8 || nearest > 128) discard();
    }

    // ------------------------------------------------------------------ talk

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (state == HOSTILE || state == LEAVING || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (player instanceof ServerPlayer sp) DragonTalk.send(sp, respond(sp, 0));
        return InteractionResult.SUCCESS;
    }

    boolean talkable(ServerPlayer p) {
        return isAlive() && state != HOSTILE && state != LEAVING && distanceToSqr(p) <= 100;
    }

    private DragonTalk.Reply page(String text, DragonTalk.Choice... choices) {
        return new DragonTalk.Reply(getId(), text, List.of(choices), name());
    }

    private static DragonTalk.Choice c(String label, int action) {
        return new DragonTalk.Choice(label, action);
    }

    DragonTalk.Reply respond(ServerPlayer p, int action) {
        ServerLevel level = (ServerLevel) level();
        boolean night = LightDragons.night(level);
        if (action == SEND_AWAY) {
            state = LEAVING;
            getNavigation().stop();
            return page(
                    night
                            ? "No... please. Fine. I'll take my chances out there."
                            : "Then I'll find my own way. Goodbye.");
        }
        return switch (state) {
            case RUNNING, SHELTERED ->
                night
                        ? action == STAY
                                ? page("Thank you. I won't be any trouble. Just until the sun comes up.")
                                : page(
                                        "I'll be quiet, I swear. Just let me stay by your light until the sun comes up."
                                                + " The things out there tonight... I can't go back out.",
                                        c("Stay. You're safe here.", STAY),
                                        c("Get out of my home.", SEND_AWAY))
                        : dawn(p, action);
            case GUIDING -> page("It's not far now: " + targetName + ". Stay close.", c("Go your own way.", SEND_AWAY));
            case ARRIVED -> page("This is it. I'll be fine here. Go on, it's yours to explore.");
            default -> page("...");
        };
    }

    /** At sunrise: up to three undiscovered places to choose from, or a few Sparks when none is left nearby. */
    private DragonTalk.Reply dawn(ServerPlayer p, int action) {
        if (action >= PICK && action < PICK + offers.size()) {
            Site site = offers.get(action - PICK);
            target = site.pos();
            targetName = site.name();
            guided = p.getUUID();
            follower = p;
            state = GUIDING;
            waiting = false;
            var shown = new ArrayList<>(((ServerLevel) level()).getAttachedOrElse(SHOWN, List.of()));
            shown.add(target.asLong());
            level().setAttached(SHOWN, List.copyOf(shown));
            return page("Then follow me, and keep close. If you fall behind, I'll wait.");
        }
        if (!searchDone) return page("Give me a moment... I'm trying to remember the way. Ask me again soon.");
        if (offers.isEmpty()) {
            state = LEAVING;
            DragonTalk.give(p, new ItemStack(ModItems.SPARK, Balance.REFUGEE_FALLBACK_SPARKS));
            return page("I meant to show you something, but everything near here is already known. Take these. It's all"
                    + " I have.");
        }
        var choices = new ArrayList<DragonTalk.Choice>();
        for (int i = 0; i < offers.size(); i++) {
            Site s = offers.get(i);
            int dx = s.pos().getX() - getBlockX(), dz = s.pos().getZ() - getBlockZ();
            choices.add(c(
                    "Show me " + s.name() + " (" + (int) Math.hypot(dx, dz) + " blocks " + compass(dx, dz) + ")",
                    PICK + i));
        }
        // Three places at most and no goodbye (owner, 2026-11-16): Escape closes, and he leaves on his own later.
        return new DragonTalk.Reply(
                getId(),
                "The sun's up, and I'm still breathing because of you. I've wandered a long way, and I've seen places"
                        + " out there nobody has found yet. Pick one, and I'll lead you to it.",
                List.copyOf(choices),
                name());
    }

    static String compass(int dx, int dz) {
        String[] names = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        int i = Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8);
        return names[i];
    }

    /** A line to everyone close enough to hear. */
    void shout(String line) {
        for (Player p : level().players()) if (p.distanceToSqr(this) < 64 * 64) say(p, line);
    }

    void say(Player p, String line) {
        if (p instanceof ServerPlayer sp)
            sp.sendSystemMessage(Component.literal("<" + name() + "> ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(line).withStyle(ChatFormatting.WHITE)));
    }

    // ------------------------------------------------------------------ fighting

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt && isAlive() && source.getEntity() instanceof Player attacker && state != HOSTILE) {
            state = HOSTILE;
            getNavigation().stop();
            setTarget(attacker);
            say(attacker, "You'd do this?! After everything out there? Then fight me!");
        }
        return hurt;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putInt("refugee_state", state);
        out.putLong("refugee_chest", chest.asLong());
        out.putLong("refugee_day", day);
        out.putInt("refugee_skin", skin());
        out.putInt("refugee_day_ticks", dayTicks);
        out.putInt("refugee_announced", announced ? 1 : 0);
        if (guided != null) out.store("refugee_guided", UUIDUtil.CODEC, guided);
        if (target != null) out.putLong("refugee_target", target.asLong());
        out.putString("refugee_target_name", targetName);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        state = in.getIntOr("refugee_state", SHELTERED);
        chest = BlockPos.of(in.getLongOr("refugee_chest", blockPosition().asLong()));
        day = in.getLongOr("refugee_day", -1);
        setSkin(in.getIntOr("refugee_skin", 0));
        dayTicks = in.getIntOr("refugee_day_ticks", 0);
        announced = in.getIntOr("refugee_announced", 0) == 1;
        guided = in.read("refugee_guided", UUIDUtil.CODEC).orElse(null);
        long t = in.getLongOr("refugee_target", Long.MIN_VALUE);
        target = t == Long.MIN_VALUE ? null : BlockPos.of(t);
        targetName = in.getStringOr("refugee_target_name", "");
        if (state == HOSTILE) state = LEAVING;
    }
}
