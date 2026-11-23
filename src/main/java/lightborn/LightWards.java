package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Chest positions stay registered when their chunks unload. */
public final class LightWards {
    private LightWards() {}

    private static final Map<ServerLevel, Map<BlockPos, WardAura>> AURAS = new HashMap<>();
    /** Wards whose shield is lifted while a Cleanse runs at them: they still light, monsters pass, players fight. */
    private static final Map<ServerLevel, Set<BlockPos>> SUSPENDED = new HashMap<>();
    /** Cracks in each ward's dome from a Cleanse (cosmetic, so not saved). */
    private static final Map<ServerLevel, Map<BlockPos, Integer>> SCARS = new HashMap<>();

    public static int scars(ServerLevel level, BlockPos chest) {
        return SCARS.getOrDefault(level, Map.of()).getOrDefault(chest, 0);
    }

    /** Cracks the dome to at least {@code n} (never fewer than it has). */
    static void scar(ServerLevel level, BlockPos chest, int n) {
        n = Math.min(WardAura.MAX_SCARS, n);
        if (n <= scars(level, chest)) return;
        SCARS.computeIfAbsent(level, k -> new HashMap<>()).put(chest.immutable(), n);
        level.playSound(null, chest, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, .6f, .6f);
        var aura = AURAS.getOrDefault(level, Map.of()).get(chest);
        if (aura != null) aura.scars(n);
    }

    /** After dawn, with no Cleanse under way, one crack heals every few seconds with a soft chime. */
    private static void heal(ServerLevel level, BlockPos chest, WardAura aura) {
        int n = scars(level, chest);
        if (n > 0
                && !suspended(level, chest)
                && !LightDragons.night(level)
                && level.getServer().getTickCount() % Balance.SCAR_HEAL_TICKS == 0) {
            n--;
            if (n == 0) SCARS.get(level).remove(chest);
            else SCARS.get(level).put(chest, n);
            level.playSound(null, chest, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1f, 1.4f);
        }
        aura.scars(n);
    }

    static void suspend(ServerLevel level, BlockPos chest, boolean on) {
        var set = SUSPENDED.computeIfAbsent(level, k -> new HashSet<>());
        if (on) set.add(chest.immutable());
        else set.remove(chest);
        var aura = AURAS.getOrDefault(level, Map.of()).get(chest);
        if (aura != null) aura.bind(chest, radiusAt(level, chest), on);
    }

    public static boolean suspended(ServerLevel level, BlockPos chest) {
        var set = SUSPENDED.get(level);
        return set != null && set.contains(chest);
    }

    static boolean registered(ServerLevel level, BlockPos chest) {
        return level.getAttachedOrElse(ModData.LIGHT_WARDS, List.of()).contains(chest);
    }

    /** The wards that shield right now: every ward but those a Cleanse has suspended. */
    private static List<BlockPos> shielding(ServerLevel level) {
        var all = level.getAttachedOrElse(ModData.LIGHT_WARDS, List.of());
        var off = SUSPENDED.get(level);
        return off == null || off.isEmpty()
                ? all
                : all.stream().filter(c -> !off.contains(c)).toList();
    }

    /** Inside a ward's shield (a suspended ward shields no one). */
    public static boolean contains(ServerLevel level, Vec3 position) {
        for (var chest : shielding(level))
            if (Vec3.atCenterOf(chest).distanceToSqr(position) <= Math.pow(radiusAt(level, chest), 2)) return true;
        return false;
    }

    public static int radiusAt(ServerLevel level, BlockPos pos) {
        var owner = level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of()).get(key(pos));
        return owner == null ? Balance.LIGHT_WARD_RADIUS : owner.radius;
    }
    /** Friendly summons, thralls and From The Fog scenery are allowed through. */
    public static boolean blocked(Entity e) {
        if (e == null) return false;
        if (Monsters.isAtmosphericProp(e)) return false;
        if (e instanceof LightDragon dragon && dragon.role() != 1) return false;
        if (e instanceof TamableAnimal pet && pet.isTame() && !(pet.getTarget() instanceof Player)) return false;
        return (hostile(e)
                        || e instanceof LightDragon d && d.role() == 1
                        || e instanceof Animal animal && (animal.isAggressive() || animal.getTarget() != null)
                        || e instanceof NeutralMob neutral && neutral.isAngry()
                        || e instanceof Mob
                                && e.getType().getCategory() == MobCategory.MONSTER
                                && !Monsters.isAtmosphericProp(e))
                && e.getAttached(ModData.THRALL_MASTER) == null
                && !Souls.summoned(e);
    }

    public static Vec3 limitMovement(Entity e, Vec3 move) {
        if (!(e.level() instanceof ServerLevel l)
                || !(blocked(e) || e instanceof Projectile p && hostileProjectile(p))
                || move.lengthSqr() < 1e-12) return move;
        var from = e.getBoundingBox().getCenter();
        var shields = shielding(l);
        var first = stop(l, shields, e, from, move);
        if (first == null) return move;
        var before = move.scale(first.t);
        // A projectile stops at the wall (intercept() takes it). A creature slides along it (owner, 2026-11-23: they
        // stuck fast where they touched it, and hung in the air on its upper half): the rest of its move keeps all
        // but the part pushing in, so it walks round the dome or slides down it, and gravity always has its way.
        if (e instanceof Projectile) return before;
        var rest = move.scale(1 - first.t);
        var normal = from.add(before).subtract(first.centre);
        if (normal.lengthSqr() < 1e-8) return before;
        normal = normal.normalize();
        double in = rest.dot(normal);
        if (in < 0) rest = rest.subtract(normal.scale(in));
        var then = stop(l, shields, e, from.add(before), rest);
        return before.add(then == null ? rest : rest.scale(then.t));
    }

    private record Stop(double t, Vec3 centre) {}

    /** Where along {@code move} from {@code from} the creature first meets a ward's wall from outside, or null. */
    private static Stop stop(ServerLevel l, List<BlockPos> shields, Entity e, Vec3 from, Vec3 move) {
        if (move.lengthSqr() < 1e-12) return null;
        Stop best = null;
        for (BlockPos chest : shields) {
            double r = radiusAt(l, chest)
                    + Math.sqrt(e.getBbWidth() * e.getBbWidth() * .5 + e.getBbHeight() * e.getBbHeight() * .25);
            var centre = Vec3.atCenterOf(chest);
            var delta = from.subtract(centre);
            double c = delta.lengthSqr() - r * r;
            if (c <= 0) continue; // Creatures already inside may leave; tick() expels them safely.
            double a = move.lengthSqr(), b = delta.dot(move), disc = b * b - a * c;
            if (b >= 0 || disc < 0) continue;
            double hit = (-b - Math.sqrt(disc)) / a;
            if (hit >= 0 && hit < 1 && (best == null || hit < best.t))
                best = new Stop(Math.max(0, hit - .002 / Math.sqrt(a)), centre);
        }
        return best;
    }

    /** Checks in a row (half a second each) a creature has pressed at the wall for someone inside. */
    private static final Map<Mob, Integer> PRESSING = new java.util.WeakHashMap<>();
    /** Creatures turned away from the wall, and until when they prowl. */
    private static final Map<Mob, Long> PROWL = new java.util.WeakHashMap<>();

    static final int PRESS_CHECKS = 6, PROWL_TICKS = 160;

    /** Whether a creature is prowling round a ward, turned from the one inside (it cannot take them as a target). */
    public static boolean prowling(Mob m) {
        Long until = PROWL.get(m);
        return until != null && m.level().getGameTime() < until;
    }

    /**
     * The dark gathers at the wall but never stands frozen against it (owner, 2026-11-23: monsters "just get stuck
     * there"). A monster that has pressed at the wall for three seconds after someone inside gives them up and prowls
     * a stretch of the wall, a quarter turn or so round it a few blocks out, for 8 to 12 seconds; then the night's
     * hearing turns it back on the player and it comes again.
     */
    private static void prowl(ServerLevel l, BlockPos chest, int radius) {
        var centre = Vec3.atCenterOf(chest);
        long now = l.getGameTime();
        var random = l.getRandom();
        for (var mob : l.getEntitiesOfClass(Mob.class, new AABB(chest).inflate(radius + 5), LightWards::blocked)) {
            double d = mob.getBoundingBox().getCenter().distanceTo(centre);
            var target = mob.getTarget();
            if (d <= radius
                    || d > radius + 2.5 + mob.getBbWidth()
                    || prowling(mob)
                    || target == null
                    || !contains(l, target.getBoundingBox().getCenter())) {
                if (!prowling(mob)) PRESSING.remove(mob);
                continue;
            }
            if (PRESSING.merge(mob, 1, Integer::sum) < PRESS_CHECKS) continue;
            PRESSING.remove(mob);
            PROWL.put(mob, now + PROWL_TICKS + random.nextInt(80));
            mob.setTarget(null);
            var from = mob.position().subtract(centre);
            double a = Math.atan2(from.z, from.x) + (random.nextBoolean() ? 1 : -1) * (.6 + random.nextDouble() * .8);
            double r = radius + 3 + random.nextDouble() * 4;
            mob.getNavigation().moveTo(centre.x + Math.cos(a) * r, mob.getY(), centre.z + Math.sin(a) * r, 1);
        }
        PROWL.entrySet().removeIf(en -> en.getKey().isRemoved() || now >= en.getValue());
    }

    public static boolean hostileProjectile(Projectile p) {
        var owner = p.getOwner();
        return owner != null ? blocked(owner) : p instanceof LargeFireball || p instanceof WitherSkull;
    }

    public static boolean intercept(Projectile p, Vec3 to) {
        if (!(p.level() instanceof ServerLevel l) || !hostileProjectile(p)) return false;
        var move = to.subtract(p.position());
        if (contains(l, p.position()) || limitMovement(p, move).distanceToSqr(move) > 1e-10) {
            p.discard();
            return true;
        }
        return false;
    }

    public static Vec3 limitDestination(Entity e, Vec3 destination) {
        return e.tickCount <= 0 || e.level() instanceof ServerLevel l && covers(l, e.blockPosition())
                ? destination
                : e.position().add(limitMovement(e, destination.subtract(e.position())));
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) return;
        for (var l : server.getAllLevels()) {
            var live = AURAS.computeIfAbsent(l, k -> new HashMap<>());
            var chests = l.getAttachedOrElse(ModData.LIGHT_WARDS, List.of());
            for (var at : List.copyOf(live.keySet()))
                if (!chests.contains(at)
                        || !l.hasChunkAt(at)
                        || !l.getBlockState(at).is(ModItems.LIGHT_CHEST)) {
                    live.remove(at).discard();
                }
            for (var at : chests)
                if (l.hasChunkAt(at) && l.getBlockState(at).is(ModItems.LIGHT_CHEST)) {
                    ChestGlow.place(l, at);
                    int radius = radiusAt(l, at);
                    ChestGlow.field(l, at, radius);
                    var aura = live.get(at);
                    if (aura == null || aura.isRemoved()) {
                        aura = new WardAura(ModEntities.WARD_AURA, l);
                        aura.bind(at, radius, suspended(l, at));
                        live.put(at, aura);
                        l.addFreshEntity(aura);
                    } else aura.bind(at, radius, suspended(l, at));
                    heal(l, at, aura);
                    if (suspended(l, at)) continue;
                    prowl(l, at, radius);
                    for (var mob :
                            l.getEntitiesOfClass(Mob.class, new AABB(at).inflate(radius + 5), LightWards::blocked)) {
                        var center = Vec3.atCenterOf(at);
                        var body = mob.getBoundingBox().getCenter();
                        if (body.distanceToSqr(center) > radius * radius) continue;
                        var direction = body.subtract(center).multiply(1, 0, 1).normalize();
                        if (direction.lengthSqr() < .01) direction = new Vec3(1, 0, 0);
                        for (int step = 2; step <= 18; step += 2) {
                            var target = center.add(direction.scale(radius + mob.getBbWidth() + step));
                            var pos = BlockPos.containing(target);
                            if (!l.hasChunkAt(pos) || covers(l, pos)) continue;
                            int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
                            var exit = new Vec3(target.x, y, target.z);
                            if (covers(l, BlockPos.containing(exit))
                                    || !l.noCollision(mob, mob.getBoundingBox().move(exit.subtract(mob.position()))))
                                continue;
                            mob.setPos(exit);
                            mob.setDeltaMovement(0, 0, 0);
                            mob.needsSync = true;
                            break;
                        }
                    }
                }
        }
    }

    static void clear() {
        AURAS.values().forEach(m -> m.values().forEach(Entity::discard));
        AURAS.clear();
        SUSPENDED.clear();
    }

    record Owner(UUID player, int radius) {
        static final Codec<Owner> CODEC = RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("player").forGetter(Owner::player),
                        Codec.INT.fieldOf("radius").forGetter(Owner::radius))
                .apply(i, Owner::new));
    }

    public static int tier(net.minecraft.world.entity.player.Player player) {
        return Math.clamp(player.getAttachedOrElse(ModData.WARD_TIER, 0), 0, Balance.WARD_RADII.length - 1);
    }

    public static int radiusFor(ServerPlayer player) {
        return Balance.WARD_RADII[tier(player)];
    }

    /** Buys the next ward step at a chest: the price in Sparks, then every ward the player owns grows at once. */
    static String grow(ServerPlayer p, BlockPos chest) {
        int tier = tier(p);
        if (tier >= Balance.WARD_PRICES.length) return "The ward is as wide as the Light can hold it.";
        int price = Balance.WARD_PRICES[tier];
        if (LightShop.sparks(p) < price) return "You need " + price + " Sparks to widen the ward.";
        if (!LightShop.pay(p, price)) return "You need " + price + " Sparks to widen the ward.";
        p.setAttached(ModData.WARD_TIER, tier + 1);
        updateOwned(p);
        if (p.level() instanceof ServerLevel l)
            l.playSound(
                    null,
                    chest,
                    net.minecraft.sounds.SoundEvents.BEACON_POWER_SELECT,
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    1f,
                    1.2f);
        return "The ward widens to " + Balance.WARD_RADII[tier + 1] + " blocks.";
    }

    static boolean covers(ServerLevel level, BlockPos pos) {
        var chests = level.getAttached(ModData.LIGHT_WARDS);
        return chests != null && covers(chests, level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of()), pos);
    }

    private static boolean covers(List<BlockPos> chests, Map<String, Owner> owners, BlockPos pos) {
        for (BlockPos chest : chests) {
            var owner = owners.get(key(chest));
            int radius = owner == null ? Balance.LIGHT_WARD_RADIUS : owner.radius;
            if (chest.distSqr(pos) <= radius * radius) return true;
        }
        return false;
    }

    public static boolean preventsNaturalSpawn(ServerLevel level, Mob mob) {
        return blocked(mob) && shields(level, mob.blockPosition());
    }

    /** {@link #covers} for the wards that shield right now (a Cleanse lifts its ward's shield). */
    static boolean shields(ServerLevel level, BlockPos pos) {
        return covers(shielding(level), level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of()), pos);
    }

    static double approachDistance(ServerLevel level, Vec3 origin, double angle, double distance) {
        while (distance <= Balance.HUNT_APPROACH_LIMIT) {
            var at = BlockPos.containing(
                    origin.x + Math.cos(angle) * distance, origin.y, origin.z + Math.sin(angle) * distance);
            if (!covers(level, at)) return distance;
            distance += Balance.HUNT_WARD_SEARCH_STEP;
        }
        return -1;
    }

    /** Filter positions, including air that a blast could ignite. Entity damage has its own path. */
    public static void protectBlast(ServerExplosion blast, List<BlockPos> positions) {
        var chests = shielding(blast.level());
        if (chests.isEmpty() || !monsterBlast(blast)) return;
        var owners = blast.level().getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of());
        positions.removeIf(pos -> covers(chests, owners, pos));
    }

    private static boolean monsterBlast(ServerExplosion blast) {
        Entity direct = blast.getDirectSourceEntity();
        Entity owner = blast.getIndirectSourceEntity();
        Entity attacker = blast.getDamageSource().getEntity();
        return hostile(direct)
                || hostile(owner)
                || hostile(attacker)
                || (owner == null
                        && attacker == null
                        && (direct instanceof LargeFireball || direct instanceof WitherSkull));
    }

    private static boolean hostile(Entity entity) {
        return Monsters.isMonster(entity) || Monsters.isVanillaBoss(entity);
    }

    static void add(ServerLevel level, BlockPos pos) {
        var old = level.getAttachedOrCreate(ModData.LIGHT_WARDS);
        if (old.contains(pos)) return;
        var next = new ArrayList<>(old);
        next.add(pos.immutable());
        level.setAttached(ModData.LIGHT_WARDS, List.copyOf(next));
    }

    static void remove(ServerLevel level, BlockPos pos) {
        var live = AURAS.get(level);
        if (live != null) {
            var aura = live.remove(pos);
            if (aura != null) aura.discard();
        }
        var old = level.getAttached(ModData.LIGHT_WARDS);
        if (old == null || !old.contains(pos)) return;
        level.setAttached(
                ModData.LIGHT_WARDS, old.stream().filter(p -> !p.equals(pos)).toList());
        var owners = new HashMap<>(level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of()));
        if (owners.remove(key(pos)) != null) level.setAttached(ModData.LIGHT_WARD_OWNERS, Map.copyOf(owners));
    }

    private static String key(BlockPos pos) {
        return Long.toString(pos.asLong());
    }

    static void claim(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                || !level.getBlockState(pos).is(ModItems.LIGHT_CHEST)) return;
        add(level, pos);
        var owners = level.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of());
        if (owners.containsKey(key(pos))) return;
        var next = new HashMap<>(owners);
        next.put(key(pos), new Owner(player.getUUID(), radiusFor(player)));
        level.setAttached(ModData.LIGHT_WARD_OWNERS, Map.copyOf(next));
    }

    static void updateOwned(ServerPlayer player) {
        int radius = radiusFor(player);
        for (ServerLevel level : player.level().getServer().getAllLevels()) {
            var old = level.getAttached(ModData.LIGHT_WARD_OWNERS);
            if (old == null
                    || old.values().stream()
                            .noneMatch(owner -> owner.player.equals(player.getUUID()) && owner.radius != radius))
                continue;
            var next = new HashMap<>(old);
            next.replaceAll(
                    (pos, owner) -> owner.player.equals(player.getUUID()) ? new Owner(owner.player, radius) : owner);
            level.setAttached(ModData.LIGHT_WARD_OWNERS, Map.copyOf(next));
        }
    }

    /** Backfill chests in worlds saved before the ward existed, without scanning empty palettes. */
    static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
        if (generated) return;
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        var sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (!section.maybeHas(state -> state.is(ModItems.LIGHT_CHEST))) continue;
            int y0 = level.getMinY() + i * 16;
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        if (section.getBlockState(x, y, z).is(ModItems.LIGHT_CHEST))
                            add(level, new BlockPos(x0 + x, y0 + y, z0 + z));
                    }
        }
    }
}
