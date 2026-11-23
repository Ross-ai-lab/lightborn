package lightborn;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/**
 * Frostbite (owner, 2026-11-23: "ice nova gives frostbite; other snow and ice spells also give it, not instantly,
 * depending on if it makes sense, from many hits to only 1 hit"). Every ice blow adds chill to a creature; at
 * {@link #FULL} chill it is frostbitten for {@link #TICKS}: slowed hard (Slowness II), frozen through so it shakes and
 * the cold bites it as powder snow does (1 harm every 2 seconds), shedding snowflakes, and its skin
 * drawn frost-pale on every client ({@code FrostTintMixin}, keyed to vanilla's synced frozen ticks). Chill fades a
 * fifth of the way a second when no blow renews it. A creature the cold cannot touch (strays, snow golems, the cold
 * kinds, anyone in leather) is spared ({@link #coldProof}).
 *
 * <p>Chill per blow: Frost Nova and Frost Rune {@code 100} (one hit), Glacial Spike, Freezing Wave, Chilling Rise and
 * Frozen Tomb {@code 50} (two), Sentinel's shards and Chilling Splinters {@code 34} (three), Frost Fangs {@code 25}
 * (four), and each half second in a Blizzard or by a Frostwalker's chill {@code 20} (five).
 */
public final class Frostbite {
    private Frostbite() {}

    /** Chill that bites, and how long a bite lasts (5 seconds). */
    public static final int FULL = 100, TICKS = 100;
    /** Chill lost each second with no blow. */
    static final float FADE = 20;

    private record Chill(float points, long at) {}

    private static final Map<LivingEntity, Chill> CHILL = new IdentityHashMap<>();
    private static final Map<LivingEntity, Long> BITTEN = new IdentityHashMap<>();

    /** An ice blow: adds {@code points} of chill, and frostbites at {@link #FULL}. */
    public static void add(LivingEntity e, ServerPlayer by, int points) {
        if (!e.isAlive() || coldProof(e) || !(e.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        var old = CHILL.get(e);
        float have = old == null ? 0 : Math.max(0, old.points - FADE * (now - old.at) / 20f);
        have += points;
        if (have >= FULL) {
            CHILL.remove(e);
            bite(e, by, TICKS);
        } else CHILL.put(e, new Chill(have, now));
    }

    /** Frostbites a creature for {@code ticks}, at once (Doom's Frostbite curse, a full chill). */
    public static void bite(LivingEntity e, ServerPlayer by, int ticks) {
        if (!e.isAlive() || coldProof(e) || !(e.level() instanceof ServerLevel level)) return;
        long until = level.getGameTime() + ticks;
        BITTEN.merge(e, until, Math::max);
        e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 1), by);
        hold(e);
        level.sendParticles(
                ParticleTypes.SNOWFLAKE, e.getX(), e.getY() + e.getBbHeight() * .6, e.getZ(), 16, .35, .5, .35, .02);
    }

    /**
     * Whether the cold cannot touch it, as vanilla decides ({@code canFreeze}): the freeze-immune kinds and anyone in
     * leather. Except the plain zombie: Mob Variants tags it freeze-immune only so powder snow turns it into its own
     * Frozen Zombie (by {@code isInPowderSnow}, never by frozen ticks), which would otherwise spare the commonest
     * monster of all (found 2026-11-23).
     */
    static boolean coldProof(LivingEntity e) {
        if (e.getType() != net.minecraft.world.entity.EntityTypes.ZOMBIE) return !e.canFreeze();
        for (var slot : net.minecraft.world.entity.EquipmentSlotGroup.ARMOR)
            if (e.getItemBySlot(slot).is(net.minecraft.tags.ItemTags.FREEZE_IMMUNE_WEARABLES)) return true;
        return false;
    }

    public static boolean bitten(LivingEntity e) {
        Long until = BITTEN.get(e);
        return until != null && e.level().getGameTime() < until;
    }

    /** Ends the frostbite (Glacial Spike's shatter spends it). */
    public static void clear(LivingEntity e) {
        BITTEN.remove(e);
        CHILL.remove(e);
    }

    /** Frozen through, a little past what vanilla needs, so it shakes and the cold bites. */
    private static void hold(LivingEntity e) {
        e.setTicksFrozen(Math.max(e.getTicksFrozen(), e.getTicksRequiredToFreeze() + 10));
    }

    static void tick() {
        for (var it = BITTEN.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            var e = en.getKey();
            if (e.isRemoved() || !e.isAlive() || e.level().getGameTime() >= en.getValue()) {
                it.remove();
                continue;
            }
            hold(e);
            // Vanilla's own freeze harm skips a creature it thinks cannot freeze (the zombie above): bite it here.
            if (!e.canFreeze() && (e.level().getGameTime() + e.getId()) % 40 == 0 && e.level() instanceof ServerLevel l)
                e.hurtServer(l, e.damageSources().freeze(), 1);
            if ((e.level().getGameTime() + e.getId()) % 4 == 0 && e.level() instanceof ServerLevel l)
                l.sendParticles(
                        ParticleTypes.SNOWFLAKE,
                        e.getX(),
                        e.getY() + e.getBbHeight() * .55,
                        e.getZ(),
                        2,
                        e.getBbWidth() * .45,
                        e.getBbHeight() * .3,
                        e.getBbWidth() * .45,
                        .005);
        }
        if (CHILL.size() > 0 && Scheduler.now() % 100 == 0)
            CHILL.entrySet()
                    .removeIf(en -> en.getKey().isRemoved()
                            || en.getKey().level().getGameTime() - en.getValue().at > 20L * FULL / (long) FADE);
    }

    static void clearAll() {
        CHILL.clear();
        BITTEN.clear();
    }

    /** For the self-test: chill held now (after fading), 0 when none. */
    static float chill(LivingEntity e) {
        var c = CHILL.get(e);
        return c == null ? 0 : Math.max(0, c.points - FADE * (e.level().getGameTime() - c.at) / 20f);
    }
}
