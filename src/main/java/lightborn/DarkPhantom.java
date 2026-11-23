package lightborn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The Dark Phantom (owner, 2026-11-17): a phantom of the dark, black and blind, with milky eyes and a voice of its own
 * (clicks, low cries; {@link NightSounds}). At night a few circle every ward from outside its edge, and others drift
 * over the wilds near players. It cannot see: it never picks a target by sight. It hears instead. Every sound a player
 * makes (steps, sprinting, mining, building, shooting, fighting, splashing, explosions) adds to that player's noise,
 * which fades over a few seconds; crouching quiets everything to a quarter, and inside a ward nothing carries. When a
 * phantom within earshot hears noise loud enough for the distance it clicks, cries and dives at that player, and keeps
 * at it while the noise goes on; fifteen quiet seconds and it loses them and goes back to circling. A blow from a
 * player always gives that player away. At dawn the wild ones vanish in smoke, a night slept through included
 * (owner, 2026-11-20: they lingered over the ward after a night in bed); a Book of Souls spirit stays.
 */
public class DarkPhantom extends Phantom {
    public static EntityType<DarkPhantom> TYPE;

    /** How far a phantom hears, the noise that sets it on a player at point-blank range, and how long it remembers. */
    static final double EARSHOT = 48; // a quarter less than 64 (owner, 2026-11-23)

    static final float LOUD = 6f;
    static final int FORGET_TICKS = 100; // five quiet seconds (owner, 2026-11-18)

    /** Ward rings: circle this far past a ward's edge, this high over the ground. */
    static final int RING_OUT = 14, RING_HEIGHT = 22;

    private long heardAt;
    /** The ward this phantom circles (null: it roams over the wilds), and its place on the ring. */
    private BlockPos ward;

    private double ringAngle = Double.NaN, ringSpin = 1;

    public DarkPhantom(EntityType<? extends Phantom> type, Level level) {
        super(type, level);
        // A quarter less damage than a phantom's own bite (owner, 2026-11-23). On the total, so the phantom's size
        // rule, which resets the base, keeps it.
        var damage = getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null)
            damage.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    ModItems.id("dark_phantom_bite"),
                    -.25,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    static void init() {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, ModItems.id("dark_phantom"));
        TYPE = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                key,
                FabricEntityType.Builder.<DarkPhantom>createMob(
                                DarkPhantom::new,
                                MobCategory.MONSTER,
                                b -> b.defaultAttributes(() -> Monster.createMonsterAttributes()
                                        .add(Attributes.MAX_HEALTH, 30)
                                        .add(Attributes.FOLLOW_RANGE, EARSHOT)))
                        .sized(.9f, .5f)
                        .clientTrackingRange(8)
                        .build(key));
        ModEntities.egg("dark_phantom", TYPE);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // Blind: no goal ever picks a target by sight; DarkPhantom.hear sets it.
        targetSelector.removeAllGoals(g -> true);
    }

    // ---------------------------------------------------------------- its voice

    @Override
    protected SoundEvent getAmbientSound() {
        return NightSounds.PHANTOM_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return NightSounds.PHANTOM_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return NightSounds.PHANTOM_DEATH;
    }

    @Override
    public void playSound(SoundEvent sound, float volume, float pitch) {
        if (sound == net.minecraft.sounds.SoundEvents.PHANTOM_SWOOP) sound = NightSounds.PHANTOM_SWOOP;
        else if (sound == net.minecraft.sounds.SoundEvents.PHANTOM_BITE) sound = NightSounds.PHANTOM_SWOOP;
        super.playSound(sound, volume, pitch);
    }

    // ---------------------------------------------------------------- blind hunting

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (source.getEntity() instanceof ServerPlayer p && !p.isCreative() && !p.isSpectator() && !thrall()) hunt(p);
        return super.hurtServer(level, source, amount);
    }

    private void hunt(ServerPlayer p) {
        if (getTarget() != p) {
            playSound(NightSounds.PHANTOM_HEAR, 3f, .9f + random.nextFloat() * .2f);
            setTarget(p);
        }
        heardAt = level().getGameTime();
    }

    /** A Book of Souls spirit: it serves its master, so none of the wild phantom's hearing or circling applies. */
    boolean thrall() {
        return getAttached(ModData.THRALL_MASTER) != null;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!(level() instanceof ServerLevel level) || thrall()) return;
        // The Dark has no dawn: only the Overworld's sun ends them.
        if (tickCount % 20 == 0 && level.dimension() == Level.OVERWORLD && !LightDragons.night(level)) {
            level.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                    getX(),
                    getY(),
                    getZ(),
                    16,
                    .5,
                    .2,
                    .5,
                    .02);
            discard();
            return;
        }
        var target = getTarget();
        if (target != null) {
            boolean lost = level.getGameTime() - heardAt > FORGET_TICKS
                    || !target.isAlive()
                    || target instanceof ServerPlayer p && (p.isCreative() || p.isSpectator())
                    || LightWards.covers(level, target.blockPosition());
            if (lost) setTarget(null);
            else return;
        }
        // No prey: the anchor slides round its ward's ring (or drifts over the wilds), and the vanilla circling
        // follows.
        if (tickCount % 5 != 0) return;
        if (ward == null && tickCount % 100 == 0) ward = nearestWard(level); // after a reload, or drifted near one
        var anchor = ((lightborn.mixin.PhantomAccessor) this).lightborn$anchor();
        if (ward != null) {
            int radius = LightWards.radiusAt(level, ward) + RING_OUT;
            if (Double.isNaN(ringAngle)) {
                ringAngle = Math.atan2(getZ() - ward.getZ(), getX() - ward.getX());
                ringSpin = random.nextBoolean() ? 1 : -1;
            }
            ringAngle += ringSpin * 1.6 / radius; // about 1.6 blocks of ring every five ticks
            int x = ward.getX() + (int) Math.round(Math.cos(ringAngle) * radius),
                    z = ward.getZ() + (int) Math.round(Math.sin(ringAngle) * radius);
            int ground = level.hasChunkAt(new BlockPos(x, 0, z))
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
                    : ward.getY();
            ((lightborn.mixin.PhantomAccessor) this)
                    .lightborn$setAnchor(new BlockPos(x, Math.max(ground, ward.getY()) + RING_HEIGHT, z));
        } else if (anchor != null && random.nextInt(8) == 0) {
            ((lightborn.mixin.PhantomAccessor) this)
                    .lightborn$setAnchor(anchor.offset(random.nextInt(9) - 4, 0, random.nextInt(9) - 4));
        }
    }

    private BlockPos nearestWard(ServerLevel level) {
        BlockPos best = null;
        double bestD = 200 * 200;
        var owners = level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of());
        for (var home : level.getAttachedOrElse(ModData.LIGHT_WARDS, java.util.List.<BlockPos>of())) {
            double d = home.distToCenterSqr(position());
            if (d < bestD && owners.containsKey(Long.toString(home.asLong()))) {
                best = home;
                bestD = d;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- the world's noise

    /** Each player's noise, fading every tick. */
    private static final Map<UUID, Float> NOISE = new HashMap<>();

    static float noise(ServerPlayer p) {
        return NOISE.getOrDefault(p.getUUID(), 0f);
    }

    /** A sound a player made: the game event's weight, quieter crouched, nothing inside a ward. */
    public static void heard(ServerPlayer p, float weight) {
        if (p.isSpectator() || p.isCreative()) return;
        if (p.isCrouching()) weight *= .25f;
        NOISE.merge(p.getUUID(), weight, Float::sum);
    }

    /** From every game event in a level (ServerLevel mixin): the noise it makes, charged to the player who made it. */
    public static void gameEvent(ServerLevel level, Holder<GameEvent> event, Vec3 at, GameEvent.Context context) {
        if (level.dimension() != Level.OVERWORLD || TYPE == null) return;
        if (event.is(GameEvent.EXPLODE)) {
            for (var p : level.players()) if (p.position().distanceToSqr(at) < 32 * 32) heard(p, 20);
            return;
        }
        if (!(context.sourceEntity() instanceof ServerPlayer p)) return;
        heard(p, weight(event, p));
    }

    static float weight(Holder<GameEvent> e, ServerPlayer p) {
        if (e.is(GameEvent.STEP)) return p.isSprinting() ? 1.2f : .4f;
        if (e.is(GameEvent.BLOCK_DESTROY) || e.is(GameEvent.PROJECTILE_SHOOT)) return 3f;
        if (e.is(GameEvent.BLOCK_PLACE) || e.is(GameEvent.SPLASH) || e.is(GameEvent.HIT_GROUND)) return 1.5f;
        if (e.is(GameEvent.ENTITY_DAMAGE) || e.is(GameEvent.PRIME_FUSE)) return 2f;
        return .5f;
    }

    /**
     * Each tick: noise fades (half-life about two seconds), and twice a second every Dark Phantom without prey listens.
     * Loud enough means louder than {@link #LOUD}, scaled up with the distance, to nothing at {@link #EARSHOT}.
     */
    static void tick(MinecraftServer server) {
        NOISE.replaceAll((id, n) -> n * .985f);
        NOISE.values().removeIf(n -> n < .05f);
        tick(server, server.overworld());
        var dark = server.getLevel(Level.NETHER);
        if (dark != null && !dark.players().isEmpty()) tick(server, dark);
    }

    /** One level's phantoms listen, and new ones come: the Overworld's by night, the Dark's at any hour. */
    private static void tick(MinecraftServer server, ServerLevel level) {
        if (server.getTickCount() % 10 == 0 && !NOISE.isEmpty())
            for (var phantom : level.getEntities(TYPE, e -> e.isAlive() && !e.thrall())) {
                ServerPlayer best = null;
                double loudest = 0;
                for (var p : level.players()) {
                    float n = noise(p);
                    if (n <= 0 || LightWards.covers(level, p.blockPosition())) continue;
                    double d = phantom.distanceTo(p);
                    if (d >= EARSHOT) continue;
                    double heard = n * (1 - d / EARSHOT);
                    if (heard > LOUD && heard > loudest) {
                        loudest = heard;
                        best = p;
                    }
                }
                if (best != null && (phantom.getTarget() == null || phantom.getTarget() == best)) phantom.hunt(best);
            }
        if (server.getTickCount() % 100 == 0) spawn(level);
    }

    // ---------------------------------------------------------------- where they fly

    /** Phantoms round each ward with a player near it: two in the first week, one more each week, up to six. */
    static int perWard(ServerLevel level) {
        return Math.min(6, 1 + LightShop.week(LightShop.day(level)));
    }

    static void spawn(ServerLevel level) {
        if (level.dimension() == Level.NETHER) {
            spawnInTheDark(level);
            return;
        }
        if (!LightDragons.night(level) || !Hordes.enabled(level)) return;
        var random = level.getRandom();
        var owners = level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of());
        for (var home : level.getAttachedOrElse(ModData.LIGHT_WARDS, java.util.List.<BlockPos>of())) {
            if (!owners.containsKey(Long.toString(home.asLong()))) continue;
            var center = Vec3.atCenterOf(home);
            boolean watched = level.players().stream().anyMatch(p -> p.distanceToSqr(center) < 160 * 160);
            if (!watched) continue;
            long circling = level.getEntities(TYPE, e -> home.equals(e.ward)).size();
            if (circling >= perWard(level)) continue;
            int radius = LightWards.radiusAt(level, home) + RING_OUT;
            double a = random.nextDouble() * Math.PI * 2;
            int x = home.getX() + (int) Math.round(Math.cos(a) * radius),
                    z = home.getZ() + (int) Math.round(Math.sin(a) * radius);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            int y = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), home.getY()) + RING_HEIGHT;
            var phantom = make(level, new BlockPos(x, y, z));
            if (phantom != null) phantom.ward = home;
        }
        // Over the wilds: now and then one near a player far from any ward, if none is already close.
        for (var p : level.players()) {
            if (!Hordes.huntable(p) || LightWards.covers(level, p.blockPosition()) || random.nextInt(6) != 0) continue;
            if (!level.getEntities(TYPE, p.getBoundingBox().inflate(96), e -> true)
                    .isEmpty()) continue;
            boolean nearWard = level.getAttachedOrElse(ModData.LIGHT_WARDS, java.util.List.<BlockPos>of()).stream()
                    .anyMatch(h -> h.distSqr(p.blockPosition()) < 160 * 160);
            if (nearWard) continue;
            double a = random.nextDouble() * Math.PI * 2;
            int x = p.getBlockX() + (int) Math.round(Math.cos(a) * 40),
                    z = p.getBlockZ() + (int) Math.round(Math.sin(a) * 40);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            make(level, new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 24, z));
        }
    }

    /**
     * The Dark is always night (owner, 2026-11-20: its own monsters roam there): now and then one comes near a player
     * with none close, in the open air above them under the Dark's roof.
     */
    static void spawnInTheDark(ServerLevel level) {
        if (!Hordes.enabled(level)) return;
        var random = level.getRandom();
        for (var p : level.players()) {
            if (!Hordes.huntable(p) || random.nextInt(4) != 0) continue;
            if (!level.getEntities(TYPE, p.getBoundingBox().inflate(64), e -> true)
                    .isEmpty()) continue;
            double a = random.nextDouble() * Math.PI * 2;
            int x = p.getBlockX() + (int) Math.round(Math.cos(a) * 28),
                    z = p.getBlockZ() + (int) Math.round(Math.sin(a) * 28);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            for (int y = Math.min(p.getBlockY() + 14, 118); y > p.getBlockY(); y--) {
                var at = new BlockPos(x, y, z);
                if (level.getBlockState(at).isAir()
                        && level.getBlockState(at.below()).isAir()) {
                    make(level, at);
                    break;
                }
            }
        }
    }

    private static DarkPhantom make(ServerLevel level, BlockPos at) {
        var phantom = TYPE.create(level, EntitySpawnReason.EVENT);
        if (phantom == null) return null;
        phantom.snapTo(Vec3.atCenterOf(at), level.getRandom().nextFloat() * 360, 0);
        phantom.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.EVENT, null);
        phantom.setPhantomSize(1);
        Monsters.setRank(phantom, MonsterInfo.NORMAL);
        return level.addFreshEntity(phantom) ? phantom : null;
    }

    static boolean is(Object e) {
        return e instanceof DarkPhantom;
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.HOSTILE;
    }
}
