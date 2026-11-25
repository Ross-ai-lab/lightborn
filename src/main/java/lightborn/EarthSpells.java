package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.chest;
import static lightborn.SpellKit.drop;
import static lightborn.SpellKit.knockResist;
import static lightborn.SpellKit.sound;
import static lightborn.SpellKit.track;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.AREA;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.SpellUpgrades.Stat.REACH;
import static lightborn.Spells.sound;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Earth spells: stone and crystal. Stoneskin, Stone Wall, Earthshatter, Boulder and Crystal Shell.
 */
public final class EarthSpells {
    private EarthSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case STONESKIN -> {
                stoneskin(level, p, dmg, stoneskinTicks());
                yield true;
            }
            case STONE_WALL -> stoneWall(level, p);
            case EARTHSHATTER -> earthshatter(level, p, dmg, SpellUpgrades.special(spell));
            case BOULDER -> boulder(level, p, dmg);
            case CRYSTAL_SHELL -> crystalShell(level, p);
            default -> throw new IllegalArgumentException(spell + " is not an earth spell");
        };
    }

    static void tick(MinecraftServer server) {
        tickFissures();
        tickShards();
        tickWalls();
        tickShells();
        tickBoulders();
    }

    static void clear() {
        FISSURES.clear();
        SHARDS.clear();
        WALLS.clear();
        SHELLS.values().forEach(s -> s.walls.forEach(SpellKit::drop));
        SHELLS.keySet().forEach(EarthSpells::free);
        SHELLS.clear();
        BOULDERS.forEach(b -> SpellKit.drop(b.stone));
        BOULDERS.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "fissures" -> FISSURES.size();
            case "shards" -> SHARDS.size();
            case "walls" -> WALLS.size();
            case "shells" -> SHELLS.size();
            case "boulders" -> BOULDERS.size();
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- Stoneskin

    static int stoneskinTicks() {
        return SpellUpgrades.scaleInt(Spell.STONESKIN, SpellUpgrades.Stat.DURATION, 240);
    }

    /** A shield of {@code power} health (absorption, golden hearts) plus Resistance I, both for {@code ticks}. */
    static void stoneskin(ServerLevel level, ServerPlayer p, double power, int ticks) {
        float before = p.getAbsorptionAmount();
        // The Absorption effect sets the cap (4 per amplifier step); the shield itself is set to the exact power.
        int amp = Math.max(0, (int) Math.ceil(power / 4.0) - 1);
        p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ticks, amp, false, false, true));
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 0, false, false, true));
        p.setAbsorptionAmount(Math.max(before, (float) power));
        sound(level, p, SoundEvents.STONE_PLACE, 1.2f, 0.6f);
        level.playSound(
                null, p.getX(), p.getY(), p.getZ(), SoundEvents.ARMOR_EQUIP_IRON, SoundSource.PLAYERS, 1.0f, 0.8f);
        var stone = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
        for (int i = 0; i < 28; i++) {
            double a = i * Math.PI * 2 / 28, y = (i % 7) * 0.28;
            level.sendParticles(
                    stone, p.getX() + Math.cos(a) * 0.7, p.getY() + y, p.getZ() + Math.sin(a) * 0.7, 1, 0, 0, 0, 0);
        }
        if (SpellUpgrades.perk(p, Spell.STONESKIN, "Living Rock"))
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, 0, false, false, true));
        if (SpellUpgrades.perk(p, Spell.STONESKIN, "Rooted")) {
            var kb = p.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (kb != null) {
                kb.removeModifier(ROOTED);
                kb.addTransientModifier(new AttributeModifier(ROOTED, 1, AttributeModifier.Operation.ADD_VALUE));
                long until = level.getGameTime() + ticks;
                p.setAttached(ModData.STONE_ROOTED_UNTIL, until);
                Scheduler.later(ticks, () -> {
                    if (p.getAttachedOrElse(ModData.STONE_ROOTED_UNTIL, 0L)
                            <= p.level().getGameTime()) kb.removeModifier(ROOTED);
                });
            }
        }
        if (SpellUpgrades.perk(p, Spell.STONESKIN, "Shatterburst")) {
            boolean[] burst = {false};
            for (int t = 5; t <= ticks; t += 5)
                Scheduler.later(t, () -> {
                    if (burst[0] || !p.isAlive() || p.getAbsorptionAmount() > 0) return;
                    burst[0] = true;
                    Spells.withSpell(
                            Spell.STONESKIN, () -> Perks.burst(p, p.position().add(0, 1, 0), 4, power, stone));
                });
        }
    }

    /** Rooted's knockback immunity; a newer cast pushes its end back. */
    static final net.minecraft.resources.Identifier ROOTED = ModItems.id("stoneskin_rooted");

    // ---------------------------------------------------------------- Earthshatter

    private static final class Fissure {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        Vec3 pos;
        final Vec3 dir;
        int steps;
        final double dmg, radius;
        final boolean wide;
        final Set<UUID> struck = new HashSet<>();

        Fissure(
                ServerPlayer owner,
                ServerLevel level,
                Vec3 pos,
                Vec3 dir,
                int steps,
                double dmg,
                double radius,
                boolean wide) {
            this.owner = owner;
            this.level = level;
            this.pos = pos;
            this.dir = dir;
            this.steps = steps;
            this.dmg = dmg;
            this.radius = radius;
            this.wide = wide;
        }
    }

    /** One spike: a stack of pointed dripstone pieces (base, frustum, tip) leaning together. */
    private static final class Shard {
        final List<Display.BlockDisplay> parts;
        final Quaternionf rot;
        final float w, seg;
        int age;

        Shard(List<Display.BlockDisplay> parts, Quaternionf rot, float w, float seg) {
            this.parts = parts;
            this.rot = rot;
            this.w = w;
            this.seg = seg;
        }
    }

    static final List<Fissure> FISSURES = new ArrayList<>();
    static final List<Shard> SHARDS = new ArrayList<>();
    static final int FISSURE_STEPS = 14, FISSURE_LONG = 20;
    static final double FISSURE_RADIUS = 1.6, FISSURE_WIDE = 2.4;
    /** A spike stands this many ticks (owner, 2026-11-10: they vanished too soon), then sinks over SPIKE_SINK. */
    static final int SPIKE_HOLD = 60, SPIKE_SINK = 8;

    /**
     * The ground splits from your feet along your gaze, 14 blocks (upgraded 20, and half as wide again), a block a
     * tick. Stone spears burst up along it; every creature it passes is struck once and thrown into the air. It
     * climbs small steps and stops at a cliff or a wall.
     */
    static boolean earthshatter(ServerLevel level, ServerPlayer p, double dmg, boolean upgraded) {
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1e-3 || !groundBelow(level, p.blockPosition())) {
            p.sendSystemMessage(
                    Component.literal("Stand on solid ground and face where the earth should split")
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return false;
        }
        // The first spear bursts two blocks out: one right under the eyes would fill the screen.
        FISSURES.add(new Fissure(
                p,
                level,
                p.position().add(flat.normalize()),
                flat.normalize(),
                upgraded ? FISSURE_LONG : FISSURE_STEPS,
                dmg,
                upgraded ? FISSURE_WIDE : FISSURE_RADIUS,
                upgraded));
        sound(level, p.position(), SoundEvents.POINTED_DRIPSTONE_LAND, 1.5f, 0.5f);
        sound(level, p.position(), SoundEvents.ANVIL_LAND, 0.6f, 0.4f);
        if (SpellUpgrades.perk(p, Spell.EARTHSHATTER, "Boulder Toss")) {
            Vec3 end = Perks.ground(
                    level,
                    p.position()
                            .add(flat.normalize().scale(upgraded ? FISSURE_LONG : FISSURE_STEPS))
                            .add(0, 2, 0));
            FireSpells.thrownRock(level, p.getEyePosition(), end, 24, () -> {
                if (p.isAlive() && p.level() == level)
                    Perks.burst(
                            p,
                            end.add(0, .5, 0),
                            3,
                            dmg,
                            new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()));
            });
        }
        return true;
    }

    private static boolean groundBelow(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /** The standing spot near {@code at}: up to 1 block up or 3 down, open at feet and head, solid below. */
    static BlockPos standing(ServerLevel level, Vec3 at) {
        BlockPos base = BlockPos.containing(at);
        for (int dy = 1; dy >= -3; dy--) {
            BlockPos q = base.offset(0, dy, 0);
            if (level.getBlockState(q).getCollisionShape(level, q).isEmpty()
                    && level.getBlockState(q.above())
                            .getCollisionShape(level, q.above())
                            .isEmpty()
                    && groundBelow(level, q)) return q;
        }
        return null;
    }

    static void tickFissures() {
        for (Iterator<Fissure> it = FISSURES.iterator(); it.hasNext(); ) {
            Fissure f = it.next();
            ServerLevel level = f.level;
            if (f.owner.isRemoved() || f.owner.level() != level || f.steps-- <= 0) {
                it.remove();
                continue;
            }
            Vec3 ahead = f.pos.add(f.dir);
            BlockPos spot = standing(level, ahead.add(0, 0.5, 0));
            if (spot == null) {
                it.remove();
                continue;
            }
            f.pos = new Vec3(ahead.x, spot.getY(), ahead.z);
            BlockState ground = level.getBlockState(spot.below());
            if (!ground.isCollisionShapeFullBlock(level, spot.below())) ground = Blocks.STONE.defaultBlockState();
            spear(level, f.pos, 1.0f, true);
            if (f.wide) {
                Vec3 side = new Vec3(-f.dir.z, 0, f.dir.x).scale(f.steps % 2 == 0 ? 0.9 : -0.9);
                spear(level, f.pos.add(side), 0.75f, false);
            }
            BlockParticleOption debris = new BlockParticleOption(ParticleTypes.BLOCK, ground);
            level.sendParticles(debris, f.pos.x, f.pos.y + 0.2, f.pos.z, 24, 0.4, 0.1, 0.4, 0.25);
            level.sendParticles(ParticleTypes.CRIT, f.pos.x, f.pos.y + 0.3, f.pos.z, 4, 0.3, 0.1, 0.3, 0.1);
            if (f.steps % 2 == 0)
                sound(
                        level,
                        f.pos,
                        SoundEvents.DRIPSTONE_BLOCK_BREAK,
                        1.2f,
                        0.6f + level.getRandom().nextFloat() * 0.3f);
            if (f.steps % 3 == 0) sound(level, f.pos, SoundEvents.POINTED_DRIPSTONE_LAND, 1.0f, 0.7f);
            for (LivingEntity e : level.getEntitiesOfClass(
                    LivingEntity.class,
                    new AABB(f.pos, f.pos).inflate(f.radius, 2.5, f.radius),
                    e -> Spells.canHitArea(f.owner, e))) {
                if (f.struck.contains(e.getUUID())) continue;
                double dx = e.getX() - f.pos.x, dz = e.getZ() - f.pos.z;
                if (dx * dx + dz * dz > f.radius * f.radius) continue;
                f.struck.add(e.getUUID());
                Spells.hit(f.owner, e, f.dmg, Spell.EARTHSHATTER, f.cast);
                e.setDeltaMovement(e.getDeltaMovement().multiply(0.3, 0, 0.3).add(0, 0.8 * (1 - knockResist(e)), 0));
            }
        }
    }

    private static final SpeleothemThickness[] TALL = {
        SpeleothemThickness.BASE, SpeleothemThickness.FRUSTUM, SpeleothemThickness.TIP
    };
    private static final SpeleothemThickness[] SHORT = {SpeleothemThickness.FRUSTUM, SpeleothemThickness.TIP};

    /**
     * One stone spike bursting up from below at a random lean: pointed dripstone stacked base, frustum, tip (a short
     * one skips the base), so it reads as a spike at a glance (owner, 2026-11-10: blocks of the ground looked like
     * stacked grass blocks).
     */
    static void spear(ServerLevel level, Vec3 at, float size, boolean tall) {
        var r = level.getRandom();
        Quaternionf rot = new Quaternionf().rotateY(r.nextFloat() * 6.28f).rotateX((r.nextFloat() - 0.5f) * 0.5f);
        float w = (1.3f + r.nextFloat() * 0.3f) * size, seg = (0.75f + r.nextFloat() * 0.2f) * size;
        List<Display.BlockDisplay> parts = new ArrayList<>();
        for (var thickness : tall ? TALL : SHORT) {
            Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            if (d == null) continue;
            d.setBlockState(Blocks.POINTED_DRIPSTONE
                    .defaultBlockState()
                    .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.UP)
                    .setValue(BlockStateProperties.SPELEOTHEM_THICKNESS, thickness));
            d.setViewRange(3f);
            parts.add(d);
        }
        if (parts.isEmpty()) return;
        Shard s = new Shard(parts, rot, w, seg);
        shardPose(s, -parts.size() * seg - 0.1f, 0);
        for (Display.BlockDisplay d : parts) track(level, d, at);
        SHARDS.add(s);
    }

    /** Places a spike with its base {@code rise} blocks above the ground point (negative = still buried). */
    private static void shardPose(Shard s, float rise, int interpolate) {
        for (int i = 0; i < s.parts.size(); i++) {
            Display.BlockDisplay d = s.parts.get(i);
            Vector3f offset =
                    s.rot.transform(new Vector3f(-s.w / 2, i * s.seg, -s.w / 2)).add(0, rise, 0);
            d.setTransformation(new Transformation(offset, s.rot, new Vector3f(s.w, s.seg, s.w), new Quaternionf()));
            d.setTransformationInterpolationDelay(0);
            d.setTransformationInterpolationDuration(interpolate);
        }
    }

    static void tickShards() {
        for (Iterator<Shard> it = SHARDS.iterator(); it.hasNext(); ) {
            Shard s = it.next();
            s.age++;
            if (s.parts.stream()
                    .anyMatch(Entity::isRemoved)) { // its chunk unloaded: saved copies are cleaned up when it loads
                for (Display.BlockDisplay d : s.parts) drop(d);
                it.remove();
                continue;
            }
            if (s.age == 1) shardPose(s, -0.2f, 3);
            else if (s.age == SPIKE_HOLD) shardPose(s, -s.parts.size() * s.seg - 0.1f, SPIKE_SINK);
            else if (s.age >= SPIKE_HOLD + SPIKE_SINK) {
                for (Display.BlockDisplay d : s.parts) drop(d);
                it.remove();
            }
        }
    }

    // ---------------------------------------------------------------- Stone Wall

    static final int WALL_TICKS = 200, WALL_WIDTH = 8, WALL_HEIGHT = 3;
    // 8 wide: owner, 2026-11-22
    static final double WALL_REACH = 12;

    static final class Wall {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        final List<BlockPos> cells;
        int ticks;

        Wall(ServerPlayer owner, List<BlockPos> cells, int ticks) {
            this.owner = owner;
            this.level = owner.level();
            this.cells = cells;
            this.ticks = ticks;
        }
    }

    static final List<Wall> WALLS = new ArrayList<>();

    /** A wall of stone 8 wide and 3 high rises across your gaze where you look, and crumbles after 10 seconds. */
    static boolean stoneWall(ServerLevel level, ServerPlayer p) {
        int ticks = SpellUpgrades.scaleInt(Spell.STONE_WALL, DURATION, WALL_TICKS);
        if (SpellUpgrades.perk(p, Spell.STONE_WALL, "Wall Ward"))
            p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 0, false, false, true));
        var columns = new ArrayList<BlockPos>();
        if (SpellUpgrades.perk(p, Spell.STONE_WALL, "Ring Fort")) {
            BlockPos c = p.blockPosition();
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d >= 2.5 && d < 3.5) columns.add(c.offset(dx, 0, dz));
                }
        } else {
            double reach = SpellUpgrades.scale(Spell.STONE_WALL, REACH, WALL_REACH);
            Vec3 eye = p.getEyePosition();
            var hr = level.clip(new ClipContext(
                    eye,
                    eye.add(p.getLookAngle().scale(reach)),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    p));
            Vec3 at = hr.getType() == HitResult.Type.MISS
                    ? p.position()
                            .add(p.getLookAngle().multiply(1, 0, 1).normalize().scale(4))
                    : hr.getLocation().add(p.getLookAngle().scale(-.3));
            Direction facing = p.getDirection();
            Direction along = facing.getClockWise();
            int width = (int) Math.round(WALL_WIDTH * (1 + SpellUpgrades.sum(Spell.STONE_WALL, AREA)));
            BlockPos mid = BlockPos.containing(at);
            for (int i = -(width / 2); i < width - width / 2; i++) columns.add(mid.relative(along, i));
        }
        var cells = new ArrayList<BlockPos>();
        for (var col : columns) {
            BlockPos floor = floorOf(level, col);
            if (floor == null) continue;
            for (int y = 0; y < WALL_HEIGHT; y++) cells.add(floor.above(y));
        }
        if (cells.isEmpty()) return false;
        boolean rising = SpellUpgrades.perk(p, Spell.STONE_WALL, "Rising Wall");
        var placed = new ArrayList<BlockPos>();
        for (int y = 0; y < WALL_HEIGHT; y++) {
            int row = y;
            Runnable raise = () -> {
                for (var cell : cells) {
                    if (cell.getY() - floorY(cells, cell) != row) continue;
                    shove(level, p, cell, rising);
                    if (conjure(level, cell, ConjuredBlock.STONE, ticks - row * 2)) {
                        placed.add(cell);
                        level.sendParticles(
                                new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                                cell.getX() + .5,
                                cell.getY() + .5,
                                cell.getZ() + .5,
                                6,
                                .3,
                                .3,
                                .3,
                                .05);
                    }
                }
                sound(
                        level,
                        Vec3.atCenterOf(cells.get(cells.size() / 2)),
                        SoundEvents.STONE_PLACE,
                        1.3f,
                        .6f + row * .15f);
            };
            if (y == 0 || LightSpells.instant) raise.run();
            else Scheduler.later(y * 2, raise);
        }
        if (placed.isEmpty()) return false;
        WALLS.add(new Wall(p, placed, ticks));
        sound(level, Vec3.atCenterOf(placed.getFirst()), SoundEvents.RAVAGER_STEP, 1.2f, .5f);
        return true;
    }

    private static int floorY(List<BlockPos> cells, BlockPos cell) {
        int min = cell.getY();
        for (var c : cells) if (c.getX() == cell.getX() && c.getZ() == cell.getZ()) min = Math.min(min, c.getY());
        return min;
    }

    /** The first open block on solid ground in this column, searched from 3 above to 4 below; null if none. */
    static BlockPos floorOf(ServerLevel level, BlockPos col) {
        for (int dy = 3; dy >= -4; dy--) {
            BlockPos at = col.above(dy);
            var below = level.getBlockState(at.below());
            var here = level.getBlockState(at);
            if (!below.isAir()
                    && below.isFaceSturdy(level, at.below(), Direction.UP)
                    && (here.isAir() || here.canBeReplaced())) return at;
        }
        return null;
    }

    /** A creature standing where the wall rises is pushed out of it; with Rising Wall, thrown up and back and struck. */
    private static void shove(ServerLevel level, ServerPlayer p, BlockPos cell, boolean rising) {
        for (var e : level.getEntitiesOfClass(LivingEntity.class, new AABB(cell))) {
            if (e == p) continue;
            Vec3 away = e.position().subtract(p.position()).multiply(1, 0, 1);
            away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
            e.setDeltaMovement(away.scale(rising ? 1.1 : .5).add(0, rising ? .7 : .3, 0));
            e.needsSync = true;
            if (rising && Spells.canHitArea(p, e)) Spells.hit(p, e, Perks.flat(p, 4), Spell.STONE_WALL);
        }
    }

    static void tickWalls() {
        for (Iterator<Wall> it = WALLS.iterator(); it.hasNext(); ) {
            var w = it.next();
            if (--w.ticks < 0 || w.owner.isRemoved()) {
                it.remove();
                continue;
            }
            if (w.ticks % 10 != 0 || !SpellUpgrades.perk(w.owner, Spell.STONE_WALL, "Thorn Wall")) continue;
            var struck = new HashSet<LivingEntity>();
            for (var cell : w.cells) {
                if (!(w.level.getBlockState(cell).getBlock() instanceof ConjuredBlock)) continue;
                for (var e : w.level.getEntitiesOfClass(
                        LivingEntity.class, new AABB(cell).inflate(.3), e -> monster(w.owner, e)))
                    if (struck.add(e)) Spells.hit(w.owner, e, Perks.flat(w.owner, 1), Spell.STONE_WALL, w.cast);
            }
        }
    }

    // ---------------------------------------------------------------- Crystal Shell

    static final double SHELL_SHATTER = 4;
    static final int SHELL_TICKS = 200;
    // 10 s (owner, 2026-11-22)
    static final net.minecraft.resources.Identifier STILL = ModItems.id("crystal_shell_still");

    static final class Shell {
        final CastContext.Cast cast = CastContext.elemental();
        int ticks;
        final List<Display.BlockDisplay> walls = new ArrayList<>();

        Shell(int ticks) {
            this.ticks = ticks;
        }
    }

    static final Map<ServerPlayer, Shell> SHELLS = new IdentityHashMap<>();

    /** Seals you in crystal: nothing can hurt you and you mend fast, then it shatters and throws monsters back. */
    static boolean crystalShell(ServerLevel level, ServerPlayer p) {
        var old = SHELLS.remove(p);
        if (old != null) old.walls.forEach(SpellKit::drop);
        var shell = new Shell(SpellUpgrades.scaleInt(Spell.CRYSTAL_SHELL, DURATION, SHELL_TICKS));
        SHELLS.put(p, shell);
        purify(p);
        for (var attr : List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH)) {
            var inst = p.getAttribute(attr);
            if (inst != null)
                inst.addOrUpdateTransientModifier(
                        new AttributeModifier(STILL, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        push(p, new Vec3(0, Math.min(0, p.getDeltaMovement().y), 0));
        // Four walls and a roof, each a thin slab, so their inner faces show from inside too.
        float w = .7f, h = 2.2f, t = .06f;
        float[][] slabs = {
            {-w, 0, -w, 2 * w, h, t},
            {-w, 0, w - t, 2 * w, h, t},
            {-w, 0, -w, t, h, 2 * w},
            {w - t, 0, -w, t, h, 2 * w},
            {-w, h - t, -w, 2 * w, t, 2 * w}
        };
        for (float[] s : slabs) {
            var wall = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (wall == null) continue;
            wall.setBlockState(Blocks.STAINED_GLASS
                    .pick(net.minecraft.world.item.DyeColor.LIGHT_BLUE)
                    .defaultBlockState());
            wall.setTransformation(new Transformation(
                    new Vector3f(s[0], s[1], s[2]),
                    new Quaternionf(),
                    new Vector3f(s[3], s[4], s[5]),
                    new Quaternionf()));
            shell.walls.add(SpellKit.track(level, wall, p.position()));
        }
        forOthers(
                p,
                new BlockParticleOption(ParticleTypes.BLOCK, Blocks.AMETHYST_BLOCK.defaultBlockState()),
                p.position().add(0, 1, 0),
                20,
                .5,
                .1);
        atFeet(p, new DustParticleOptions(0xBFE6FF, 1f), ParticleTypes.END_ROD, 10);
        sound(level, p.position(), SoundEvents.AMETHYST_BLOCK_PLACE, 1.2f, .8f);
        sound(level, p.position(), SoundEvents.GLASS_PLACE, 1f, 1.2f);
        return true;
    }

    /** Harmful effects and fire leave the shelled player (owner, 2026-11-22: what Purifying Shell did, now always). */
    private static void purify(ServerPlayer p) {
        for (var effect : new ArrayList<>(p.getActiveEffects()))
            if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL)
                p.removeEffect(effect.getEffect());
        p.clearFire();
    }

    static boolean shelled(ServerPlayer p) {
        return SHELLS.containsKey(p);
    }

    /** Nothing hurts a shelled player but what passes every guard (the void, /kill). */
    static boolean allowDamage(LivingEntity hurt, DamageSource source, float amount) {
        if (!(hurt instanceof ServerPlayer p) || !SHELLS.containsKey(p)) return true;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
        sound(p.level(), p.position(), SoundEvents.AMETHYST_BLOCK_HIT, 1f, 1.4f);
        p.level().sendParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1, p.getZ(), 6, .5, .6, .5, .1);
        return false;
    }

    private static void free(ServerPlayer p) {
        for (var attr : List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH)) {
            var inst = p.getAttribute(attr);
            if (inst != null) inst.removeModifier(STILL);
        }
    }

    static void tickShells() {
        for (var it = SHELLS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var p = entry.getKey();
            var s = entry.getValue();
            var level = p.level();
            if (p.isRemoved() || !p.isAlive() || s.walls.stream().anyMatch(w -> w.level() != level)) {
                s.walls.forEach(SpellKit::drop);
                free(p);
                it.remove();
                continue;
            }
            for (var w : s.walls) w.setPos(p.getX(), p.getY(), p.getZ());
            if (--s.ticks >= 0) {
                p.heal(.1f); // two health a second
                if (SpellUpgrades.perk(p, Spell.CRYSTAL_SHELL, "Mana Crystal")) Perks.mana(p, .25);
                if (SpellUpgrades.perk(p, Spell.CRYSTAL_SHELL, "Healing Crystal")) p.heal(.1f);
                if (s.ticks % 10 == 0 && SpellUpgrades.perk(p, Spell.CRYSTAL_SHELL, "Hidden Shell"))
                    for (var m : level.getEntitiesOfClass(
                            Mob.class, p.getBoundingBox().inflate(32))) if (m.getTarget() == p) m.setTarget(null);
                if (s.ticks % 5 == 0)
                    level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + .1, p.getZ(), 1, .5, 0, .5, .01);
                continue;
            }
            s.walls.forEach(SpellKit::drop);
            free(p);
            it.remove();
            shatter(p, s.cast);
        }
    }

    private static void shatter(ServerPlayer p, CastContext.Cast cast) {
        var level = p.level();
        double radius = SpellUpgrades.scale(Spell.CRYSTAL_SHELL, AREA, SHELL_SHATTER);
        boolean shards = SpellUpgrades.perk(p, Spell.CRYSTAL_SHELL, "Shard Burst");
        boolean blind = SpellUpgrades.perk(p, Spell.CRYSTAL_SHELL, "Blinding Shatter");
        purify(p);
        for (var e : Perks.nearestMonsters(p, null, p.position(), radius, 64)) {
            if (shards) Spells.hit(p, e, Perks.flat(p, 8), Spell.CRYSTAL_SHELL, cast);
            throwFrom(e, p.position(), 2.2, .55); // twice as far (owner, 2026-11-22)
            if (blind) daze(e, p, DAZE_TICKS);
        }
        level.sendParticles(
                new BlockParticleOption(
                        ParticleTypes.BLOCK,
                        Blocks.STAINED_GLASS
                                .pick(net.minecraft.world.item.DyeColor.LIGHT_BLUE)
                                .defaultBlockState()),
                p.getX(),
                p.getY() + 1,
                p.getZ(),
                60,
                .6,
                .9,
                .6,
                .2);
        sound(level, p.position(), SoundEvents.GLASS_BREAK, 1.2f, .9f);
        sound(level, p.position(), SoundEvents.AMETHYST_CLUSTER_BREAK, 1f, 1.2f);
    }

    // ---------------------------------------------------------------- Boulder

    /**
     * Owner, 2026-11-23: "40% as big and heavy ... the arc more pronounced", read as 40% bigger and heavier: a larger
     * stone thrown slower and falling harder, so it lands nearer in a steeper arc.
     */
    static final double QUAKE = 1.5, BOULDER_RADIUS = 2, BOULDER_SPEED = .8, BOULDER_UP = .18, BOULDER_GRAVITY = .09;

    /** Owner, 2026-11-23 (later): 30% smaller (was 1.26), "but still as heavy": the same throw and fall. */
    static final float BOULDER_SIZE = .75f;
    // owner, 2026-11-23 (was .88)

    static final class Boulder {
        final CastContext.Cast cast = CastContext.elemental();
        final ServerPlayer owner;
        final ServerLevel level;
        final Display.BlockDisplay stone;
        /** Quake: the landing slows every monster round it. */
        boolean quake;

        final double dmg, radius;
        final boolean rolls, stuns, shatters;
        Vec3 pos, vel;
        int life = 80, bounces;
        float spin;

        Boulder(
                ServerPlayer owner,
                Display.BlockDisplay stone,
                Vec3 pos,
                Vec3 vel,
                double dmg,
                double radius,
                boolean rolls,
                boolean stuns,
                boolean shatters) {
            this.owner = owner;
            this.level = owner.level();
            this.stone = stone;
            this.pos = pos;
            this.vel = vel;
            this.dmg = dmg;
            this.radius = radius;
            this.rolls = rolls;
            this.stuns = stuns;
            this.shatters = shatters;
        }
    }

    static final List<Boulder> BOULDERS = new ArrayList<>();

    /** A boulder thrown along your aim that falls in an arc and smashes what is round where it lands. */
    static boolean boulder(ServerLevel level, ServerPlayer p, double dmg) {
        var d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (d == null) return false;
        d.setBlockState(Blocks.COBBLESTONE.defaultBlockState());
        SpellKit.centred(d, BOULDER_SIZE, new Quaternionf(), 0);
        Vec3 look = p.getLookAngle();
        Vec3 from = p.getEyePosition().add(look.scale(1.6)).add(0, -.3, 0);
        var made = new Boulder(
                p,
                SpellKit.track(level, d, from),
                from,
                look.scale(BOULDER_SPEED * (perk(p, Spell.BOULDER, "Long Throw") ? 1.5 : 1))
                        .add(0, BOULDER_UP, 0),
                dmg,
                SpellUpgrades.scale(Spell.BOULDER, AREA, BOULDER_RADIUS),
                perk(p, Spell.BOULDER, "Rolling Boulder"),
                perk(p, Spell.BOULDER, "Stunning Boulder"),
                perk(p, Spell.BOULDER, "Shatterstone"));
        made.quake = perk(p, Spell.BOULDER, "Quake");
        BOULDERS.add(made);
        sound(level, p.position(), SoundEvents.STONE_BREAK, 1.2f, .6f);
        sound(level, p.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1f, .6f);
        return true;
    }

    static void tickBoulders() {
        for (Iterator<Boulder> it = BOULDERS.iterator(); it.hasNext(); ) {
            var b = it.next();
            if (b.owner.isRemoved() || b.stone.isRemoved() || --b.life < 0) {
                SpellKit.drop(b.stone);
                it.remove();
                continue;
            }
            Vec3 next = b.pos.add(b.vel);
            BlockHitResult hit = b.level.clip(
                    new ClipContext(b.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, b.owner));
            LivingEntity struck = null;
            for (var e : b.level.getEntitiesOfClass(
                    LivingEntity.class, new AABB(b.pos, next).inflate(.5), e -> Spells.canHitDirect(b.owner, e)))
                if (e.getBoundingBox()
                        .inflate(BOULDER_SIZE / 2)
                        .clip(b.pos, next)
                        .isPresent()) {
                    struck = e;
                    break;
                }
            if (struck != null || hit.getType() != HitResult.Type.MISS) {
                Vec3 at = struck != null ? struck.position() : hit.getLocation();
                smash(b, at, b.dmg, b.radius);
                if (b.quake)
                    for (var e : Perks.nearestMonsters(b.owner, null, at, b.radius + QUAKE, 32))
                        e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1, false, true, true));
                if (b.rolls && b.bounces == 0 && struck == null) {
                    // Rolling Boulder: on it goes along the ground, to smash once more.
                    b.bounces++;
                    Vec3 flat = new Vec3(b.vel.x, 0, b.vel.z);
                    b.vel = (flat.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : flat.normalize())
                            .scale(.5)
                            .add(0, .35, 0);
                    b.pos = at.add(0, .6, 0);
                    b.life = 30;
                    continue;
                }
                SpellKit.drop(b.stone);
                it.remove();
                continue;
            }
            b.pos = next;
            b.vel = b.vel.add(0, -BOULDER_GRAVITY, 0).scale(.99);
            b.spin += .35f;
            SpellKit.centred(b.stone, BOULDER_SIZE, new Quaternionf().rotateXYZ(b.spin, b.spin * .6f, 0), 1);
            b.stone.setPos(b.pos.x, b.pos.y, b.pos.z);
            if (b.life % 2 == 0)
                b.level.sendParticles(
                        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState()),
                        b.pos.x,
                        b.pos.y,
                        b.pos.z,
                        1,
                        .1,
                        .1,
                        .1,
                        0);
        }
    }

    private static void smash(Boulder b, Vec3 at, double dmg, double radius) {
        b.level.sendParticles(
                new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState()),
                at.x,
                at.y + .3,
                at.z,
                50,
                radius / 2,
                .3,
                radius / 2,
                .2);
        b.level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + .4, at.z, 1, 0, 0, 0, 0);
        sound(b.level, at, SoundEvents.STONE_BREAK, 1.6f, .5f);
        sound(b.level, at, SoundEvents.GENERIC_EXPLODE.value(), .6f, 1.4f);
        for (var e : Perks.nearestMonsters(b.owner, null, at, radius, 32)) {
            Spells.hit(b.owner, e, dmg, Spell.BOULDER, b.cast);
            if (!(e instanceof Mob m && Cage.mighty(m))) {
                Vec3 away = e.position().subtract(at).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                double keep = 1 - SpellKit.knockResist(e);
                e.setDeltaMovement(away.scale(.9 * keep).add(0, .35 * keep, 0));
                e.needsSync = true;
            }
            if (b.stuns) e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 3, false, true, true));
        }
        if (b.shatters)
            for (var e : Perks.nearestMonsters(b.owner, null, at, 5, 3)) {
                SpellKit.line(
                        b.level,
                        at.add(0, .5, 0),
                        chest(e),
                        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                        .5);
                Spells.hit(b.owner, e, dmg / 2, Spell.BOULDER, b.cast);
            }
    }
}
