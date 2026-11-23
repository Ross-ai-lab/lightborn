package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.chest;
import static lightborn.SpellKit.sound;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.AREA;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.SpellUpgrades.Stat.REACH;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Nature spells: living things that hold, heal and cleanse. Thornfield, Lifebloom, Golden Apple and Purify.
 */
public final class NatureSpells {
    private NatureSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case THORNFIELD -> thornfield(level, p, dmg);
            case LIFEBLOOM -> lifebloom(level, p);
            case RENEWAL -> goldenApple(level, p);
            case PURIFY -> purify(level, p);
            default -> throw new IllegalArgumentException(spell + " is not a nature spell");
        };
    }

    static void tick(MinecraftServer server) {
        tickThickets();
        tickBlooms();
        tickWards();
    }

    static void clear() {
        THICKETS.forEach(t -> t.roots.forEach(r -> SpellKit.drop(r.d())));
        THICKETS.clear();
        BLOOMS.forEach(NatureSpells::fell);
        BLOOMS.clear();
        WARDED.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "thickets" -> THICKETS.size();
            case "blooms" -> BLOOMS.size();
            case "warded" -> WARDED.size();
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- Thornfield

    static final double THORN_AREA = 4, THORN_REACH = 24;
    /** The field runs half again as far away from the caster as across (owner, 2026-11-22). */
    static final double THORN_LONG = 1.5;

    static final int THORN_TICKS = 160, ROOT_GROW = 8, ROOTS_MAX = 48;

    static final class Thicket {
        final ServerPlayer owner;
        final ServerLevel level;
        final Vec3 centre, along;
        final double radius, dmg;
        int ticks, age;
        final List<Root> roots = new ArrayList<>();

        Thicket(ServerPlayer owner, Vec3 centre, Vec3 along, double radius, int ticks, double dmg) {
            this.owner = owner;
            this.level = owner.level();
            this.centre = centre;
            this.along = along;
            this.radius = radius;
            this.ticks = ticks;
            this.dmg = dmg;
        }

        /** Inside the field: an oval, {@code radius} across and {@code THORN_LONG} times that along the caster's aim. */
        boolean holds(LivingEntity e) {
            double dx = e.getX() - centre.x, dz = e.getZ() - centre.z;
            double a = (dx * along.x + dz * along.z) / (radius * THORN_LONG),
                    b = (dx * -along.z + dz * along.x) / radius;
            return a * a + b * b <= 1 && e.getY() > centre.y - 1.5 && e.getY() < centre.y + 2.5;
        }
    }

    static final List<Thicket> THICKETS = new ArrayList<>();

    /**
     * Roots of light break from the ground you aim at: monsters among them are slowed hard and cut each second. The
     * field reaches half again as far away from you as across, its near edge where you aimed.
     */
    static boolean thornfield(ServerLevel level, ServerPlayer p, double dmg) {
        Vec3 at = floor(level, aim(level, p, SpellUpgrades.scale(Spell.THORNFIELD, REACH, THORN_REACH)), 24);
        double radius = SpellUpgrades.scale(Spell.THORNFIELD, AREA, THORN_AREA);
        Vec3 along = p.getLookAngle().multiply(1, 0, 1);
        along = along.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : along.normalize();
        Vec3 centre = at.add(along.scale(radius * (THORN_LONG - 1)));
        var t = new Thicket(
                p, centre, along, radius, SpellUpgrades.scaleInt(Spell.THORNFIELD, DURATION, THORN_TICKS), dmg);
        THICKETS.add(t);
        sprout(t);
        sound(level, centre, SoundEvents.ROOTED_DIRT_BREAK, 1.4f, .7f);
        sound(level, centre, SoundEvents.SWEET_BERRY_BUSH_PLACE, 1.2f, .8f);
        sound(level, centre, SoundEvents.HANGING_ROOTS_PLACE, 1.2f, .7f);
        return true;
    }

    /**
     * The roots: hanging roots turned to grow upward, with a few clumps of mangrove root at their feet, each rising out
     * of the ground over {@link #ROOT_GROW} ticks to its own height and turn. Lit by the world, not self-lit, so they
     * read as roots.
     */
    private static void sprout(Thicket t) {
        var random = t.level.getRandom();
        double area = Math.PI * t.radius * t.radius * THORN_LONG;
        int n = (int) Math.min(ROOTS_MAX, Math.max(10, area * .6));
        for (int k = 0; k < n; k++) {
            double a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble());
            double u = Math.cos(a) * r * t.radius * THORN_LONG, v = Math.sin(a) * r * t.radius;
            Vec3 spot = t.centre.add(t.along.scale(u)).add(new Vec3(-t.along.z, 0, t.along.x).scale(v));
            Vec3 ground = floor(t.level, spot.add(0, 1.5, 0), 4);
            boolean clump = random.nextInt(4) == 0;
            var d = EntityTypes.BLOCK_DISPLAY.create(t.level, EntitySpawnReason.MOB_SUMMONED);
            if (d == null) continue;
            d.setBlockState((clump ? Blocks.MANGROVE_ROOTS : Blocks.HANGING_ROOTS).defaultBlockState());
            float w = clump ? .45f + random.nextFloat() * .2f : .8f + random.nextFloat() * .35f;
            float h = clump ? .25f + random.nextFloat() * .2f : .7f + random.nextFloat() * .7f;
            float turn = random.nextFloat() * (float) Math.PI * 2;
            root(d, w, .02f, turn, !clump, 0);
            SpellKit.track(t.level, d, ground);
            d.setBrightnessOverride(null);
            t.roots.add(new Root(d, w, !clump));
            int delay = random.nextInt(5);
            Scheduler.later(1 + delay, () -> {
                if (!d.isRemoved()) root(d, w, h, turn, !clump, ROOT_GROW);
            });
        }
        t.level.sendParticles(
                new net.minecraft.core.particles.BlockParticleOption(
                        ParticleTypes.BLOCK, Blocks.ROOTED_DIRT.defaultBlockState()),
                t.centre.x,
                t.centre.y + .2,
                t.centre.z,
                40,
                t.radius * .6,
                .1,
                t.radius * .6,
                .1);
    }

    /** One root {@code w} wide and {@code h} tall standing on its display's spot, flipped upright if it hangs. */
    private static void root(Display.BlockDisplay d, float w, float h, float turn, boolean flip, int ticks) {
        var rot = new Quaternionf().rotateY(turn);
        if (flip) rot.rotateX((float) Math.PI);
        var size = new Vector3f(w, h, w);
        Vector3f mid = rot.transform(new Vector3f(w / 2, h / 2, w / 2));
        var offset = new Vector3f(0, h / 2, 0).sub(mid);
        d.setTransformation(new Transformation(offset, rot, size, new Quaternionf()));
        d.setTransformationInterpolationDelay(0);
        d.setTransformationInterpolationDuration(ticks);
    }

    /** One root of a thicket: its display, how wide it grew, and whether it is a hanging root turned upright. */
    record Root(Display.BlockDisplay d, float w, boolean flip) {}

    /** The roots sink back into the ground, then go. */
    private static void wither(Thicket t) {
        var random = t.level.getRandom();
        for (var r : t.roots) {
            var d = r.d();
            if (d.isRemoved()) continue;
            float turn = random.nextFloat() * (float) Math.PI * 2;
            root(d, r.w(), .02f, turn, r.flip(), ROOT_GROW);
            Scheduler.later(ROOT_GROW + 1, () -> SpellKit.drop(d));
        }
        t.roots.clear();
    }

    static void tickThickets() {
        for (Iterator<Thicket> it = THICKETS.iterator(); it.hasNext(); ) {
            var t = it.next();
            var p = t.owner;
            if (p.isRemoved() || --t.ticks < 0) {
                if (!p.isRemoved() && SpellUpgrades.perk(p, Spell.THORNFIELD, "Last Bloom")) {
                    for (var e : inThicket(t)) Spells.hit(p, e, t.dmg * 3, Spell.THORNFIELD);
                    t.level.sendParticles(
                            new DustParticleOptions(0xFFE08A, 1.4f),
                            t.centre.x,
                            t.centre.y + .5,
                            t.centre.z,
                            60,
                            t.radius * .5,
                            .4,
                            t.radius * .5,
                            0);
                    sound(t.level, t.centre, SoundEvents.AMETHYST_CLUSTER_BREAK, 1.2f, .9f);
                }
                wither(t);
                sound(t.level, t.centre, SoundEvents.ROOTED_DIRT_PLACE, 1f, .6f);
                it.remove();
                continue;
            }
            boolean cut = t.age++ % 20 == 0;
            boolean strangle = SpellUpgrades.perk(p, Spell.THORNFIELD, "Strangling Roots");
            boolean venom = SpellUpgrades.perk(p, Spell.THORNFIELD, "Venom Thorns");
            if (cut && SpellUpgrades.perk(p, Spell.THORNFIELD, "Healing Roots") && t.holds(p)) p.heal(1);
            for (var e : inThicket(t)) {
                if (strangle) Perks.root(e, p, 10);
                else e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 10, 2), p);
                if (!cut) continue;
                Spells.hit(p, e, t.dmg, Spell.THORNFIELD);
                if (venom) e.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0), p);
                t.level.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY() + .4, e.getZ(), 5, .3, .2, .3, .1);
                t.level.sendParticles(
                        new net.minecraft.core.particles.BlockParticleOption(
                                ParticleTypes.BLOCK, Blocks.HANGING_ROOTS.defaultBlockState()),
                        e.getX(),
                        e.getY() + .3,
                        e.getZ(),
                        6,
                        .3,
                        .2,
                        .3,
                        .05);
            }
        }
    }

    private static List<LivingEntity> inThicket(Thicket t) {
        double reach = t.radius * THORN_LONG + 1;
        return t.level.getEntitiesOfClass(
                LivingEntity.class,
                new AABB(t.centre, t.centre).inflate(reach, 3, reach),
                e -> monster(t.owner, e) && t.holds(e));
    }

    // ---------------------------------------------------------------- Golden Apple

    /** Regeneration II for 5 s and Absorption I for 2 min, as eating a golden apple gives. */
    static final int APPLE_REGEN = 100, APPLE_ABSORB = 2400;
    /** An enchanted golden apple: Regeneration II 20 s, Absorption IV 2 min, Resistance and Fire Resistance 5 min. */
    static final int GOD_REGEN = 400, GOD_ABSORB = 2400, GOD_GUARD = 6000;

    /** You gain the effects of a golden apple (an enchanted one with the upgrade). */
    static boolean goldenApple(ServerLevel level, ServerPlayer p) {
        boolean god = SpellUpgrades.perk(p, Spell.RENEWAL, "Enchanted Golden Apple");
        boolean feast = SpellUpgrades.perk(p, Spell.RENEWAL, "Golden Feast");
        int regen = SpellUpgrades.scaleInt(Spell.RENEWAL, DURATION, god ? GOD_REGEN : APPLE_REGEN),
                absorb = SpellUpgrades.scaleInt(Spell.RENEWAL, DURATION, god ? GOD_ABSORB : APPLE_ABSORB),
                guard = SpellUpgrades.scaleInt(Spell.RENEWAL, DURATION, GOD_GUARD);
        List<ServerPlayer> fed = new ArrayList<>(List.of(p));
        if (SpellUpgrades.perk(p, Spell.RENEWAL, "Shared Apple"))
            for (var other : level.getEntitiesOfClass(
                    ServerPlayer.class, p.getBoundingBox().inflate(8)))
                if (other != p && other.isAlive() && !other.isSpectator()) fed.add(other);
        boolean meal = SpellUpgrades.perk(p, Spell.RENEWAL, "Hearty Meal"),
                cleanse = SpellUpgrades.perk(p, Spell.RENEWAL, "Cleansing Apple"),
                stride = SpellUpgrades.perk(p, Spell.RENEWAL, "Golden Stride");
        for (var pl : fed) {
            if (meal) pl.getFoodData().eat(6, 1.2f);
            if (cleanse) cleanse(pl);
            if (stride) pl.addEffect(new MobEffectInstance(MobEffects.SPEED, 600, 0, false, true, true));
            pl.addEffect(new MobEffectInstance(MobEffects.REGENERATION, regen, 1));
            pl.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, absorb, god ? 3 : 0));
            if (god) {
                pl.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, guard, 0));
                pl.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, guard, 0));
            }
            if (feast) {
                pl.getFoodData().setFoodLevel(20);
                pl.getFoodData().setSaturation(20);
            }
            pl.level()
                    .sendParticles(
                            new net.minecraft.core.particles.ItemParticleOption(
                                    ParticleTypes.ITEM,
                                    god
                                            ? net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE
                                            : net.minecraft.world.item.Items.GOLDEN_APPLE),
                            pl.getX(),
                            pl.getY() + .3,
                            pl.getZ(),
                            16,
                            .3,
                            .1,
                            .3,
                            .08);
        }
        atFeet(p, new DustParticleOptions(0xFFD54A, 1.5f), ParticleTypes.WAX_ON, 24);
        forOthers(p, ParticleTypes.END_ROD, p.position().add(0, 1, 0), 20, .6, .1);
        sound(level, p.position(), SoundEvents.GENERIC_EAT.value(), 1f, 1f);
        sound(level, p.position(), SoundEvents.PLAYER_BURP, .8f, 1.1f);
        if (god) sound(level, p.position(), SoundEvents.TOTEM_USE, .5f, 1.6f);
        return true;
    }

    // ---------------------------------------------------------------- Purify

    static final int PURE_TICKS = 160;
    // owner, 2026-11-23: 8 s, was 5
    static final double WARD_BURST = 3;
    /** Players no harmful effect can touch for a moment after Purify. */
    static final Map<LivingEntity, Long> WARDED = new IdentityHashMap<>();

    /** Lifts every harmful effect, puts out fire and frost, and keeps new ones off for a moment. */
    static boolean purify(ServerLevel level, ServerPlayer p) {
        int ward = SpellUpgrades.scaleInt(Spell.PURIFY, DURATION, PURE_TICKS);
        for (var pl : withFriends(p, perk(p, Spell.PURIFY, "Shared Purify"))) {
            int lifted = 0;
            for (var effect : new ArrayList<>(pl.getActiveEffects()))
                if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                    pl.removeEffect(effect.getEffect());
                    lifted++;
                }
            if (pl.isOnFire()) lifted++;
            pl.clearFire();
            pl.setTicksFrozen(0);
            if (perk(p, Spell.PURIFY, "Mending Purity") && lifted > 0) pl.heal(2 * lifted);
            if (perk(p, Spell.PURIFY, "Spark of Life"))
                pl.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, false, false, true));
            if (perk(p, Spell.PURIFY, "Fireproof Ward"))
                pl.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, ward, 0, false, false, true));
            if (perk(p, Spell.PURIFY, "Mana Back")) Perks.mana(p, 3 * lifted);
            WARDED.put(pl, level.getGameTime() + ward);
            pl.level().sendParticles(ParticleTypes.WAX_OFF, pl.getX(), pl.getY() + 1, pl.getZ(), 14, .3, .5, .3, .5);
        }
        if (perk(p, Spell.PURIFY, "Ward Burst"))
            for (var e : Perks.nearestMonsters(p, null, p.position(), WARD_BURST, 32)) {
                if (e instanceof Mob m && Cage.mighty(m)) continue;
                Vec3 away = e.position().subtract(p.position()).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? p.getLookAngle() : away.normalize();
                double keep = 1 - SpellKit.knockResist(e);
                e.setDeltaMovement(away.scale(1.1 * keep).add(0, .35 * keep, 0));
                e.needsSync = true;
            }
        flourish(p, 0xF4F7FF);
        sound(level, p.position(), SoundEvents.BREWING_STAND_BREW, 1f, 1.6f);
        sound(level, p.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.4f);
        return true;
    }

    /** Whether a harmful effect may not take hold on {@code e} just now (Purify's moment). */
    public static boolean warded(LivingEntity e) {
        Long until = WARDED.get(e);
        return until != null && e.level().getGameTime() < until;
    }

    private static void tickWards() {
        WARDED.entrySet()
                .removeIf(en -> en.getKey().isRemoved() || en.getKey().level().getGameTime() >= en.getValue());
        for (var it = FormSpells.TIDAL.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            var p = en.getKey();
            var g = en.getValue();
            long now = p.level().getGameTime();
            if (p.isRemoved() || now >= g.until()) {
                it.remove();
                continue;
            }
            if (!p.isInWater() || now % 20 != 0) continue;
            if (g.tidal()) p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 30, 0, false, false, true));
            if (g.pressure()) p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 30, 0, false, false, true));
            if (g.healing() && now % 80 == 0) p.heal(2);
            if (g.sonar())
                for (var e : Perks.nearestMonsters(p, null, p.position(), FormSpells.SONAR, 64))
                    if (e.isInWater())
                        e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false, false));
            if (g.undertow())
                for (var e : Perks.nearestMonsters(p, null, p.position(), 8, 32))
                    if (e.isInWater()) e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 1), p);
        }
    }

    // ---------------------------------------------------------------- Lifebloom

    static final double BLOOM_REACH = 16, BLOOM_RADIUS = 6, RING_STEP = .9;
    static final int BLOOM_TICKS = 400, BLOOM_PULSE = 40, SEEDFALL_TICKS = 600, GROW_TICKS = 16;

    /** One piece of the tree: a block, its centre above the root, its size and its turn. */
    record Piece(BlockState block, float x, float y, float z, float w, float h, float yaw, float tilt) {}

    /** A small flowering tree of light (owner, 2026-11-23: "an actual magical beautiful tree"). */
    static final List<Piece> TREE = List.of(
            new Piece(Blocks.STRIPPED_CHERRY_LOG.defaultBlockState(), 0, 1.086f, 0, .34f, 2.171f, 0, 0),
            new Piece(Blocks.STRIPPED_CHERRY_LOG.defaultBlockState(), .32f, 1.921f, .05f, .2f, .8f, .3f, -.6f),
            new Piece(Blocks.STRIPPED_CHERRY_LOG.defaultBlockState(), -.3f, 2.021f, -.1f, .2f, .75f, -.4f, .55f),
            new Piece(Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState(), 0, 2.621f, 0, 1.35f, 1.1f, .4f, 0),
            new Piece(Blocks.CHERRY_LEAVES.defaultBlockState(), .72f, 2.371f, .15f, 1f, .85f, .9f, 0),
            new Piece(Blocks.CHERRY_LEAVES.defaultBlockState(), -.68f, 2.421f, -.2f, 1f, .85f, .2f, 0),
            new Piece(Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState(), .1f, 2.371f, .72f, .9f, .8f, 1.2f, 0),
            new Piece(Blocks.CHERRY_LEAVES.defaultBlockState(), -.12f, 2.321f, -.72f, .9f, .8f, .7f, 0),
            new Piece(Blocks.CHERRY_LEAVES.defaultBlockState(), .05f, 3.321f, .05f, .85f, .7f, .1f, 0),
            // Lanterns of light hanging under the crown.
            new Piece(Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState(), .62f, 1.851f, .38f, .2f, .2f, .5f, 0),
            new Piece(Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState(), -.55f, 1.891f, -.42f, .2f, .2f, .2f, 0),
            new Piece(Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState(), .18f, 1.821f, -.66f, .18f, .18f, .9f, 0),
            new Piece(Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState(), -.35f, 1.871f, .6f, .18f, .18f, .3f, 0));

    /** The tree is drawn this much bigger than its pieces above: a crown a little over six blocks up. */
    static final float TREE_SCALE = 1.75f;

    static final class Bloom {
        final ServerPlayer owner;
        final ServerLevel level;
        final List<Display.BlockDisplay> tree = new ArrayList<>(), ring = new ArrayList<>();
        final Vec3 at;
        final double radius, heal;
        final boolean thorns, seedfall;
        boolean regrowth, manaBloom;
        int left, pulse, age;
        /** The light it sheds while it stands, as much as two pieces of Light gear (owner, 2026-11-23). */
        BlockPos lamp;

        Bloom(
                ServerPlayer owner,
                ServerLevel level,
                Vec3 at,
                double radius,
                int left,
                double heal,
                boolean thorns,
                boolean seedfall) {
            this.owner = owner;
            this.level = level;
            this.at = at;
            this.radius = radius;
            this.left = left;
            this.heal = heal;
            this.thorns = thorns;
            this.seedfall = seedfall;
        }
    }

    static final List<Bloom> BLOOMS = new ArrayList<>();

    /** For the client test: where the first standing tree roots, or null. */
    public static Vec3 treeRoot() {
        return BLOOMS.isEmpty() ? null : BLOOMS.get(0).at;
    }

    /** Where a blossom roots: the ground under where you look (16), else under your feet. */
    static Vec3 bloomRoot(ServerLevel level, ServerPlayer p) {
        Vec3 aim = SpellKit.aimPoint(p, BLOOM_REACH);
        // Stepped back off a wall it was aimed at, then dropped to the ground.
        Vec3 from = aim == null ? p.position() : aim.subtract(p.getLookAngle().scale(.3));
        return ground(level, from.add(0, .6, 0), p);
    }

    private static Vec3 ground(ServerLevel level, Vec3 from, ServerPlayer p) {
        var hit = level.clip(
                new ClipContext(from, from.add(0, -6, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS ? from.add(0, -.6, 0) : hit.getLocation();
    }

    /** A flowering tree of light where you look that heals your side within its ring every two seconds. */
    static boolean lifebloom(ServerLevel level, ServerPlayer p) {
        Vec3 at = bloomRoot(level, p);
        // One each: a new cast moves it.
        BLOOMS.removeIf(b -> {
            if (b.owner != p) return false;
            fell(b);
            return true;
        });
        var b = new Bloom(
                p,
                level,
                at,
                SpellUpgrades.scale(Spell.LIFEBLOOM, AREA, BLOOM_RADIUS),
                SpellUpgrades.scaleInt(Spell.LIFEBLOOM, DURATION, BLOOM_TICKS),
                perk(p, Spell.LIFEBLOOM, "Deep Roots") ? 4 : 2,
                perk(p, Spell.LIFEBLOOM, "Thornbloom"),
                perk(p, Spell.LIFEBLOOM, "Seedfall"));
        for (Piece piece : TREE) {
            var d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (d == null) continue;
            d.setBlockState(piece.block());
            shape(d, piece, .02f, 0);
            b.tree.add(SpellKit.track(level, d, at));
        }
        b.regrowth = perk(p, Spell.LIFEBLOOM, "Regrowth");
        b.manaBloom = perk(p, Spell.LIFEBLOOM, "Mana Bloom");
        ring(b);
        BLOOMS.add(b);
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y + 1.5, at.z, 40, .8, .8, .8, .02);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + .3, at.z, 20, .6, .2, .6, .05);
        sound(level, at, SoundEvents.BONE_MEAL_USE, 1f, .8f);
        sound(level, at, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.2f);
        pulse(level, b);
        return true;
    }

    /** Draws a tree piece at {@code grow} of its size (it grows from its own base). */
    private static void shape(Display d, Piece piece, float grow, int interpolate) {
        grow *= TREE_SCALE;
        float w = piece.w() * grow, h = piece.h() * grow;
        var rot = new Quaternionf().rotateY(piece.yaw()).rotateZ(piece.tilt());
        // The piece's centre sits at (x, y, z) at full size; growing, it rises out of the root.
        Vector3f centre = new Vector3f(piece.x(), piece.y(), piece.z()).mul(grow);
        Vector3f offset =
                rot.transform(new Vector3f(w / 2, h / 2, w / 2)).negate().add(centre);
        d.setTransformation(new Transformation(offset, rot, new Vector3f(w, h, w), new Quaternionf()));
        d.setTransformationInterpolationDelay(0);
        d.setTransformationInterpolationDuration(interpolate);
    }

    /**
     * The healing ring, drawn for as long as the tree stands (owner, 2026-11-23: "always visible, not flashing in and
     * out"): thin glowing strips laid on the ground all round it.
     */
    private static void ring(Bloom b) {
        int n = Math.max(12, (int) Math.ceil(2 * Math.PI * b.radius / RING_STEP));
        float len = (float) (2 * Math.PI * b.radius / n) * .82f, w = .16f, h = .05f;
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            Vec3 on = new Vec3(b.at.x + Math.cos(a) * b.radius, b.at.y, b.at.z + Math.sin(a) * b.radius);
            Vec3 g = ground(b.level, on.add(0, 1.5, 0), b.owner);
            var d = EntityTypes.BLOCK_DISPLAY.create(b.level, EntitySpawnReason.MOB_SUMMONED);
            if (d == null) continue;
            d.setBlockState(Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
            var rot = new Quaternionf().rotateY((float) (-a - Math.PI / 2));
            Vector3f offset = rot.transform(new Vector3f(len / 2, 0, w / 2)).negate();
            d.setTransformation(new Transformation(offset, rot, new Vector3f(len, h, w), new Quaternionf()));
            b.ring.add(SpellKit.track(b.level, d, g.add(0, .03, 0)));
        }
    }

    /** Light level 6: what two pieces of Light gear shed at night ({@link LightGear#radiance}; owner, 2026-11-23). */
    static final int BLOOM_LIGHT = 6;

    /** Lights the tree's foot, in the first open block above its root. */
    private static void lamp(Bloom b) {
        var bloomRoot = BlockPos.containing(b.at);
        for (int dy = 1; dy <= 2 && b.lamp == null; dy++) {
            var at = bloomRoot.above(dy);
            if (!b.level.getBlockState(at).isAir()) continue;
            b.level.setBlockAndUpdate(
                    at,
                    Blocks.LIGHT
                            .defaultBlockState()
                            .setValue(net.minecraft.world.level.block.LightBlock.LEVEL, BLOOM_LIGHT));
            b.lamp = at;
        }
    }

    /** Takes a tree down: it shrinks back into the ground, then goes; the ring and its light go at once. */
    private static void fell(Bloom b) {
        if (b.lamp != null && b.level.getBlockState(b.lamp).is(Blocks.LIGHT))
            b.level.setBlockAndUpdate(b.lamp, Blocks.AIR.defaultBlockState());
        b.lamp = null;
        b.ring.forEach(SpellKit::drop);
        b.ring.clear();
        for (int i = 0; i < b.tree.size() && i < TREE.size(); i++) shape(b.tree.get(i), TREE.get(i), .02f, 10);
        var gone = List.copyOf(b.tree);
        b.tree.clear();
        Scheduler.later(10, () -> gone.forEach(SpellKit::drop));
    }

    /** Players, tamed pets and villagers within the blossom's reach. */
    static List<LivingEntity> friends(ServerLevel level, Vec3 c, double r) {
        return level.getEntitiesOfClass(
                LivingEntity.class,
                new AABB(c, c).inflate(r),
                e -> e.isAlive()
                        && (e instanceof Player
                                || e instanceof AbstractVillager
                                || e instanceof OwnableEntity own && own.getOwner() != null)
                        && e.position().distanceToSqr(c) <= r * r);
    }

    private static void pulse(ServerLevel level, Bloom b) {
        Vec3 crown = b.at.add(0, (2.1 + 1 / TREE_SCALE) * TREE_SCALE, 0);
        for (var e : friends(level, b.at, b.radius)) {
            boolean hurt = e.getHealth() < e.getMaxHealth();
            e.heal((float) b.heal);
            if (b.regrowth) e.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 0, false, true, true));
            for (var fx : List.copyOf(e.getActiveEffects()))
                if (fx.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                    e.removeEffect(fx.getEffect());
                    break;
                }
            if (hurt)
                level.sendParticles(
                        ParticleTypes.HEART, e.getX(), e.getY() + e.getBbHeight() + .3, e.getZ(), 2, .3, .1, .3, 0);
            SpellKit.line(level, crown, chest(e), ParticleTypes.HAPPY_VILLAGER, .8);
        }
        if (b.thorns && !b.owner.isRemoved())
            for (var e : Perks.nearestMonsters(b.owner, null, b.at, b.radius, 32)) {
                Spells.hit(b.owner, e, Perks.flat(b.owner, 2), Spell.LIFEBLOOM);
                level.sendParticles(ParticleTypes.CRIT, e.getX(), chest(e).y, e.getZ(), 6, .3, .3, .3, .1);
            }
        if (b.manaBloom && !b.owner.isRemoved() && b.owner.position().distanceToSqr(b.at) <= b.radius * b.radius)
            Perks.mana(b.owner, 3);
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, crown.x, crown.y, crown.z, 12, .7, .4, .7, .01);
        sound(level, b.at, SoundEvents.AMETHYST_BLOCK_CHIME, .8f, 1.5f);
    }

    static void tickBlooms() {
        for (Iterator<Bloom> it = BLOOMS.iterator(); it.hasNext(); ) {
            Bloom b = it.next();
            ServerLevel level = b.level;
            if (b.tree.isEmpty() || b.tree.get(0).isRemoved()) {
                fell(b);
                it.remove();
                continue;
            }
            if (b.age++ == 1) {
                for (int i = 0; i < b.tree.size() && i < TREE.size(); i++)
                    shape(b.tree.get(i), TREE.get(i), 1f, GROW_TICKS);
                lamp(b);
            }
            if (--b.left <= 0) {
                it.remove();
                if (b.seedfall)
                    for (var e : friends(level, b.at, b.radius))
                        e.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, SEEDFALL_TICKS, 0, false, true, true));
                level.sendParticles(ParticleTypes.CHERRY_LEAVES, b.at.x, b.at.y + 1.6, b.at.z, 50, .9, .8, .9, .02);
                fell(b);
                continue;
            }
            if (++b.pulse >= BLOOM_PULSE) {
                b.pulse = 0;
                pulse(level, b);
            }
            if (b.left % 4 == 0) {
                level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, b.at.x, b.at.y + 3.4, b.at.z, 3, 1.5, 1, 1.5, 0);
                level.sendParticles(ParticleTypes.CHERRY_LEAVES, b.at.x, b.at.y + 3.6, b.at.z, 2, 1.4, .5, 1.4, 0);
            }
            if (b.left % 7 == 0)
                level.sendParticles(ParticleTypes.END_ROD, b.at.x, b.at.y + 3.6, b.at.z, 1, 1.4, .8, 1.4, .01);
        }
    }
}
