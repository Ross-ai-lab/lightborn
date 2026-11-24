package lightborn;

import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.*;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;

/** A quick outward wave and a sparse, temporary grid of real light sources. Never damages entities. */
public final class RadiantPulse {
    private RadiantPulse() {}

    public static final int RANGE = 24, LIGHT_TICKS = 600, WAVE_TICKS = 24;
    private static final int SPACING = 6, MAX_LIGHTS_PER_CAST = 320;
    private static final AttachmentType<List<CollectionLight.Placed>> SAVED = AttachmentRegistry.create(
            ModItems.id("radiant_pulse_lights"), b -> b.persistent(CollectionLight.Placed.CODEC.listOf()));

    private record Site(ServerLevel level, BlockPos pos) {}

    private static final Map<Site, Long> LIGHTS = new HashMap<>();

    public static int duration() {
        return SpellUpgrades.scaleInt(Spell.RADIANT_PULSE, SpellUpgrades.Stat.DURATION, LIGHT_TICKS);
    }

    static void init() {
        ServerChunkEvents.CHUNK_LOAD.register((l, c, generated) -> loaded(l, c));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 4 == 0)
                for (var s : List.copyOf(LIGHTS.keySet())) if (s.level.getGameTime() >= LIGHTS.get(s)) remove(s);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            for (var site : List.copyOf(LIGHTS.keySet())) remove(site);
            LIGHTS.clear();
        });
    }

    private static Vec3 origin(ServerPlayer p) {
        var look = p.getLookAngle();
        var right = look.cross(new Vec3(0, 1, 0)).normalize();
        double hand = p.getMainArm() == HumanoidArm.RIGHT ? -.28 : .28;
        return p.getEyePosition().add(look.scale(.35)).add(right.scale(hand)).add(0, -.3, 0);
    }

    private static boolean open(ServerLevel level, BlockPos at) {
        var state = level.getBlockState(at);
        return state.isAir() || state.is(Blocks.WATER) && state.getFluidState().isSource();
    }

    /** Open, or one of the pulse's own lights: the spread passes through both. */
    private static boolean passable(ServerLevel level, BlockPos at) {
        return open(level, at) || LIGHTS.containsKey(new Site(level, at));
    }

    /** Blocks the spread may visit in one cast: a little over the open half of the sphere. */
    private static final int MAX_SPREAD = 60_000;

    static List<BlockPos> candidates(ServerLevel l, Vec3 from) {
        return candidates(l, from, SPACING);
    }

    /** Dense Light lays the grid every 4 blocks instead of 6. */
    static final int DENSE_SPACING = 4;

    /**
     * Where the lights go (owner, 2026-11-19: caves were lit only in patches). The pulse spreads from the book through
     * open air and water like a flood, block by block within {@link #RANGE}, so it follows a cave round its bends and up
     * and down its slopes; nothing walled off is reached. The space is cut into {@code spacing}-block cells and each
     * cell the spread reached gets one light, on the reached block nearest the cell's middle, unless the cell is
     * already lit (torchlight 12 or more, or daylight on open ground by day) or already holds one of the pulse's lights.
     * The old way kept only exact grid points a straight line from the book could see, and in a cave most of those sit
     * inside the rock.
     */
    static List<BlockPos> candidates(ServerLevel l, Vec3 from, int spacing) {
        BlockPos start = BlockPos.containing(from);
        if (!passable(l, start)) {
            BlockPos near = null;
            for (var d : net.minecraft.core.Direction.values())
                if (passable(l, start.relative(d))) near = start.relative(d);
            if (near == null) return List.of();
            start = near;
        }
        var seen = new HashSet<Long>();
        var queue = new ArrayDeque<BlockPos>();
        seen.add(start.asLong());
        queue.add(start);
        var best = new HashMap<Long, BlockPos>();
        var bestDist = new HashMap<Long, Double>();
        boolean day = !LightDragons.night(l);
        while (!queue.isEmpty()) {
            var at = queue.poll();
            long cell = BlockPos.asLong(
                    Math.floorDiv(at.getX(), spacing),
                    Math.floorDiv(at.getY(), spacing),
                    Math.floorDiv(at.getZ(), spacing));
            if (LIGHTS.containsKey(new Site(l, at))) {
                best.put(cell, at);
                bestDist.put(cell, -1.0); // the cell's light stands: it is renewed, never doubled
            } else if (bestDist.getOrDefault(cell, Double.MAX_VALUE) >= 0
                    && open(l, at)
                    && l.getBrightness(LightLayer.BLOCK, at) < 12
                    && !(day && l.getBrightness(LightLayer.SKY, at) >= 8)) {
                double cx = (Math.floorDiv(at.getX(), spacing) + .5) * spacing,
                        cy = (Math.floorDiv(at.getY(), spacing) + .5) * spacing,
                        cz = (Math.floorDiv(at.getZ(), spacing) + .5) * spacing;
                double d = at.distToCenterSqr(cx, cy, cz);
                if (d < bestDist.getOrDefault(cell, Double.MAX_VALUE)) {
                    best.put(cell, at);
                    bestDist.put(cell, d);
                }
            }
            if (seen.size() >= MAX_SPREAD) continue;
            for (var dir : net.minecraft.core.Direction.values()) {
                var next = at.relative(dir);
                if (next.getY() < l.getMinY()
                        || next.getY() > l.getMaxY()
                        || next.distToCenterSqr(from) > RANGE * RANGE
                        || !l.hasChunkAt(next)
                        || !seen.add(next.asLong())
                        || !passable(l, next)) continue;
                queue.add(next);
            }
        }
        var out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingDouble(at -> at.distToCenterSqr(from)));
        return out.size() > MAX_LIGHTS_PER_CAST ? List.copyOf(out.subList(0, MAX_LIGHTS_PER_CAST)) : List.copyOf(out);
    }

    public static boolean cast(ServerPlayer p) {
        var from = origin(p);
        var cells = candidates(
                p.level(), from, SpellUpgrades.perk(p, Spell.RADIANT_PULSE, "Dense Light") ? DENSE_SPACING : SPACING);
        if (cells.isEmpty()) {
            p.sendOverlayMessage(Component.literal("Nothing nearby needs light."));
            return false;
        }
        var wave = new Wave(ModEntities.RADIANT_WAVE, p.level());
        wave.setPos(from);
        wave.cells = cells;
        wave.duration = duration();
        if (!p.level().addFreshEntity(wave)) return false;
        p.level()
                .playSound(
                        null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, .8f, 1.45f);
        p.level().playSound(null, p.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, .35f, 1.8f);
        if (SpellUpgrades.perk(p, Spell.RADIANT_PULSE, "Dazzle"))
            for (var e : Perks.nearestMonsters(p, null, p.position(), 12, 32)) {
                if (e instanceof Mob m && m.getTarget() == p) m.setTarget(null);
                e.addEffect(
                        new net.minecraft.world.effect.MobEffectInstance(
                                net.minecraft.world.effect.MobEffects.SLOWNESS, 100, 1),
                        p);
            }
        if (SpellUpgrades.perk(p, Spell.RADIANT_PULSE, "Searing Light"))
            for (var m : p.level()
                    .getEntitiesOfClass(
                            Mob.class,
                            p.getBoundingBox().inflate(RANGE),
                            m -> m.isAlive()
                                    && m.getType().builtInRegistryHolder().is(net.minecraft.tags.EntityTypeTags.UNDEAD)
                                    && m.distanceTo(p) <= RANGE
                                    && p.hasLineOfSight(m))) {
                m.igniteForSeconds(5);
                p.level()
                        .sendParticles(
                                net.minecraft.core.particles.ParticleTypes.END_ROD,
                                m.getX(),
                                m.getY() + m.getBbHeight(),
                                m.getZ(),
                                8,
                                .2,
                                .3,
                                .2,
                                .05);
            }
        return true;
    }

    private static void place(ServerLevel l, BlockPos at, int duration) {
        var site = new Site(l, at);
        long until = l.getGameTime() + duration;
        if (LIGHTS.containsKey(site)) {
            LIGHTS.merge(site, until, Math::max);
            return;
        }
        if (!l.hasChunkAt(at) || !open(l, at)) return;
        var old = l.getBlockState(at);
        var chunk = l.getChunkAt(at);
        var records = new ArrayList<>(chunk.getAttachedOrElse(SAVED, List.of()));
        records.add(new CollectionLight.Placed(at, old));
        chunk.setAttached(SAVED, List.copyOf(records));
        LIGHTS.put(site, until);
        l.setBlockAndUpdate(
                at,
                Blocks.LIGHT
                        .defaultBlockState()
                        .setValue(LightBlock.LEVEL, 15)
                        .setValue(BlockStateProperties.WATERLOGGED, old.is(Blocks.WATER)));
    }

    private static void remove(Site site) {
        LIGHTS.remove(site);
        if (!site.level.hasChunkAt(site.pos))
            return; // The saved record cleans an unloaded chunk without forcing it back in.
        var chunk = site.level.getChunkAt(site.pos);
        var old = chunk.getAttachedOrElse(SAVED, List.of());
        for (var entry : old)
            if (entry.pos().equals(site.pos)
                    && site.level.getBlockState(site.pos).is(Blocks.LIGHT)
                    && site.level.getBlockState(site.pos).getValue(LightBlock.LEVEL) == 15)
                site.level.setBlockAndUpdate(site.pos, entry.original());
        chunk.setAttached(
                SAVED, old.stream().filter(r -> !r.pos().equals(site.pos)).toList());
    }

    private static void loaded(ServerLevel l, LevelChunk chunk) {
        var keep = new ArrayList<CollectionLight.Placed>();
        for (var entry : chunk.getAttachedOrElse(SAVED, List.of())) {
            var site = new Site(l, entry.pos());
            Long until = LIGHTS.get(site);
            if (until != null && until > l.getGameTime()) {
                keep.add(entry);
                continue;
            }
            LIGHTS.remove(site);
            var block = chunk.getBlockState(entry.pos());
            if (block.is(Blocks.LIGHT) && block.getValue(LightBlock.LEVEL) == 15) {
                chunk.setBlockState(entry.pos(), entry.original(), 0);
                l.getLightEngine().checkBlock(entry.pos());
            }
        }
        chunk.setAttached(SAVED, List.copyOf(keep));
    }

    static int activeLights(ServerLevel l) {
        return (int) LIGHTS.keySet().stream().filter(s -> s.level == l).count();
    }

    public static final class Wave extends Entity {
        private List<BlockPos> cells = List.of();
        private int next, duration = LIGHT_TICKS;

        public Wave(EntityType<?> type, Level l) {
            super(type, l);
            noPhysics = true;
            setNoGravity(true);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder b) {}

        @Override
        protected void readAdditionalSaveData(ValueInput in) {}

        @Override
        protected void addAdditionalSaveData(ValueOutput out) {}

        @Override
        public boolean shouldBeSaved() {
            return false;
        }

        @Override
        public boolean isPickable() {
            return false;
        }

        @Override
        public boolean hurtServer(ServerLevel l, DamageSource s, float n) {
            return false;
        }

        public float radius(float partial) {
            return Math.min(RANGE, (tickCount + partial) * RANGE / WAVE_TICKS);
        }

        @Override
        public boolean shouldRenderAtSqrDistance(double d) {
            return d < 96 * 96;
        }

        @Override
        public void tick() {
            super.tick();
            if (!(level() instanceof ServerLevel l)) return;
            double r = radius(0);
            for (int budget = 0;
                    next < cells.size() && budget < 32 && cells.get(next).distToCenterSqr(position()) <= r * r;
                    next++, budget++) {
                var at = cells.get(next);
                if (l.hasChunkAt(at)) place(l, at, duration);
            }
            if (tickCount >= WAVE_TICKS + 8 && next >= cells.size()) discard();
        }
    }
}
