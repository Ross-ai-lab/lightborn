package lightborn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Frozen solid (owner, 2026-11-18): what Frost Nova hits turns into a block of ice for a few seconds. Inside, its mind
 * stops: it cannot walk, turn, target or strike, a bow is never drawn and a creeper's fuse winds back. This is the path
 * vanilla takes for an immobile mob ({@code LivingEntity.isImmobile}, hooked in LivingEntityMixin), so the nova's
 * knockback and gravity still carry it: it slides back as an ice block and stays where it lands. A clear ice block its
 * own size rides on it (a passenger, so the two are drawn together), and it can still be hurt. When the time is up, or it dies, the ice shatters.
 * <p>
 * Bosses and the vanilla giants ({@link Cage#mighty}) are never frozen solid, only rooted for the same time; a caged
 * creature is already held. Frozen creatures live in memory only: one saved mid-freeze wakes when it loads.
 */
public final class Freeze {
    private Freeze() {}

    private record Ice(Mob mob, long until, Display block) {}

    private static final Map<Mob, Ice> FROZEN = new LinkedHashMap<>();

    public static boolean frozen(Entity e) {
        return e instanceof Mob m && FROZEN.containsKey(m);
    }

    /** Freezes {@code e} solid for {@code ticks} (a longer freeze already on it stands); a giant is only rooted. */
    static void freeze(ServerLevel level, ServerPlayer by, LivingEntity e, int ticks) {
        if (!(e instanceof Mob m) || Cage.mighty(m) || Cage.caged(m)) {
            Perks.root(e, by, ticks);
            return;
        }
        long until = level.getGameTime() + ticks;
        var old = FROZEN.get(m);
        if (old != null) {
            if (until > old.until()) FROZEN.put(m, new Ice(m, until, old.block()));
            return;
        }
        if (m instanceof Creeper c) c.setSwellDir(-1); // its swell goal will not run to keep the fuse burning
        m.getNavigation().stop();
        float w = m.getBbWidth() + .7f, h = m.getBbHeight() + .25f; // wide enough for arms held out
        var d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        Display block = null;
        if (d != null) {
            d.setBlockState(Blocks.ICE.defaultBlockState());
            block = SpellKit.track(level, d, m.position());
            // The ice rides its creature: the client then draws both from one position every frame. A block set to
            // follow it instead runs ahead while it flies, since a mob's own drawn position trails the server's.
            var seat = Vec3.ZERO;
            if (d.startRiding(m, true, false)) {
                m.positionRider(d);
                seat = d.position().subtract(m.position());
            }
            d.setTransformation(new com.mojang.math.Transformation(
                    new Vector3f((float) (-w / 2 - seat.x), (float) (-.1 - seat.y), (float) (-w / 2 - seat.z)),
                    new Quaternionf(),
                    new Vector3f(w, h, w),
                    new Quaternionf()));
        }
        FROZEN.put(m, new Ice(m, until, block));
        level.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.GLASS_PLACE, SoundSource.PLAYERS, .9f, 1.5f);
    }

    /** Each server tick: the ice follows its creature, and shatters when the time is up or the creature dies. */
    static void tick(MinecraftServer server) {
        if (FROZEN.isEmpty()) return;
        for (var ice : List.copyOf(FROZEN.values())) {
            Mob m = ice.mob();
            if (m.isRemoved() || !(m.level() instanceof ServerLevel level)) {
                thaw(ice, false);
                continue;
            }
            if (!m.isAlive() || level.getGameTime() >= ice.until()) {
                thaw(ice, true);
                continue;
            }
            var block = ice.block(); // rides along; only a creature that refused the rider is followed by hand
            if (block != null && !block.isPassenger() && block.level() == level)
                block.setPos(m.getX(), m.getY(), m.getZ());
        }
    }

    /** Ends a freeze now, if {@code m} is frozen (tests). */
    static void thaw(Mob m) {
        var ice = FROZEN.get(m);
        if (ice != null) thaw(ice, false);
    }

    private static void thaw(Ice ice, boolean shatter) {
        FROZEN.remove(ice.mob());
        SpellKit.drop(ice.block());
        Mob m = ice.mob();
        if (!shatter || !(m.level() instanceof ServerLevel level)) return;
        level.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1f, 1.3f);
        level.sendParticles(
                new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()),
                m.getX(),
                m.getY() + m.getBbHeight() / 2,
                m.getZ(),
                30,
                m.getBbWidth() / 2,
                m.getBbHeight() / 3,
                m.getBbWidth() / 2,
                .15);
    }
}
