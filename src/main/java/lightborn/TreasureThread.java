package lightborn;

import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Treasure Thread (owner, 2026-11-16): a line of light runs from the book to the nearest unopened loot chest (any
 * container that still holds its generated loot), straight through stone, then glows there as a thread and a small
 * fountain of light for a while. Only the caster sees it.
 */
public final class TreasureThread {
    private TreasureThread() {}

    /** Owner, 2026-11-17: 60 blocks, and the chest stays lit (x-ray, like Ore Sight) for a minute. */
    public static final int RANGE = 60, GLOW_TICKS = 1200, RUN_TICKS = 30;
    /** The chest's x-ray outline colour: treasure gold. */
    static final int GOLD_GLOW = 0xFFC93D;

    static int range() {
        return SpellUpgrades.scaleInt(Spell.TREASURE_THREAD, SpellUpgrades.Stat.AREA, RANGE);
    }

    static int glowTicks() {
        return SpellUpgrades.scaleInt(Spell.TREASURE_THREAD, SpellUpgrades.Stat.DURATION, GLOW_TICKS);
    }

    /** The nearest container in loaded chunks within {@code range} whose loot is not yet rolled, or null. */
    static BlockPos find(ServerLevel level, BlockPos from, int range) {
        return find(level, from, range, null);
    }

    /** Monster spawners in loaded chunks within {@code range} (Spawner Sense). */
    static java.util.List<BlockPos> spawners(ServerLevel level, BlockPos from, int range) {
        var out = new java.util.ArrayList<BlockPos>();
        for (int cx = (from.getX() - range) >> 4; cx <= (from.getX() + range) >> 4; cx++)
            for (int cz = (from.getZ() - range) >> 4; cz <= (from.getZ() + range) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (var be : level.getChunk(cx, cz).getBlockEntities().values())
                    if (be instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity
                            && be.getBlockPos().distSqr(from) <= (double) range * range) out.add(be.getBlockPos());
            }
        return out;
    }

    /** As {@link #find(ServerLevel, BlockPos, int)}, never {@code skip} (Second Thread's next nearest). */
    static BlockPos find(ServerLevel level, BlockPos from, int range, BlockPos skip) {
        BlockPos best = null;
        double nearest = (double) range * range;
        for (int cx = (from.getX() - range) >> 4; cx <= (from.getX() + range) >> 4; cx++)
            for (int cz = (from.getZ() - range) >> 4; cz <= (from.getZ() + range) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (var be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (!(be instanceof RandomizableContainerBlockEntity box)
                            || box.getLootTable() == null
                            || be.getBlockPos().equals(skip)) continue;
                    double d = be.getBlockPos().distSqr(from);
                    if (d <= nearest) {
                        nearest = d;
                        best = be.getBlockPos();
                    }
                }
            }
        return best;
    }

    static boolean cast(ServerPlayer p) {
        var level = p.level();
        int range = range();
        var target = find(level, p.blockPosition(), range);
        if (target == null) {
            level.sendParticles(ParticleTypes.SMOKE, p.getX(), p.getY() + 1.2, p.getZ(), 8, .2, .2, .2, .01);
            level.playSound(null, p.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, .4f, 1.6f);
            p.sendOverlayMessage(Component.literal("No unopened treasure lies within " + range + " blocks.")
                    .withStyle(ChatFormatting.GOLD));
            return false;
        }
        thread(level, p, target);
        int glow = glowTicks();
        if (SpellUpgrades.perk(p, Spell.TREASURE_THREAD, "Second Thread")) {
            var next = find(level, p.blockPosition(), range, target);
            if (next != null) thread(level, p, next);
        }
        if (SpellUpgrades.perk(p, Spell.TREASURE_THREAD, "Pathfinder"))
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.SPEED, RUN_TICKS + glow, 0, false, false, true));
        if (SpellUpgrades.perk(p, Spell.TREASURE_THREAD, "Spawner Sense"))
            for (BlockPos spawner : spawners(level, p.blockPosition(), range))
                WorkSpells.glow(level, spawner, level.getBlockState(spawner), 0xFF3030, RUN_TICKS + glow);
        p.sendOverlayMessage(Component.literal("A thread of light runs to treasure, "
                        + (int) Math.round(Math.sqrt(target.distSqr(p.blockPosition()))) + " blocks away.")
                .withStyle(ChatFormatting.GOLD));
        return true;
    }

    /** One thread from the book to {@code target}: the running head, the chest's x-ray glow, the lingering shimmer. */
    private static void thread(ServerLevel level, ServerPlayer p, BlockPos target) {
        level.playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1f, 1.5f);
        Vec3 from = p.getEyePosition().add(p.getLookAngle().scale(2.5)).add(0, -.5, 0);
        Vec3 to = Vec3.atCenterOf(target);
        UUID id = p.getUUID();
        // The head runs out over a second and a half, leaving a trail behind it.
        for (int t = 1; t <= RUN_TICKS; t++) {
            int tick = t;
            Scheduler.later(t, () -> {
                var caster = caster(level, id);
                if (caster == null) return;
                double a = (tick - 1) / (double) RUN_TICKS, b = tick / (double) RUN_TICKS;
                line(level, caster, ParticleTypes.END_ROD, from.lerp(to, a), from.lerp(to, b), .35);
                if (tick == RUN_TICKS)
                    caster.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                            net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(
                                    SoundEvents.AMETHYST_BLOCK_CHIME),
                            SoundSource.PLAYERS,
                            to.x,
                            to.y,
                            to.z,
                            2f,
                            1.2f,
                            caster.getRandom().nextLong()));
            });
        }
        // Then it lingers: the chest shows through stone like an ore under Ore Sight, the thread shimmers and the
        // treasure gives off a small fountain of light.
        int glow = glowTicks();
        Scheduler.later(RUN_TICKS, () -> WorkSpells.glow(level, target, level.getBlockState(target), GOLD_GLOW, glow));
        for (int t = RUN_TICKS + 10; t <= RUN_TICKS + glow; t += 10)
            Scheduler.later(t, () -> {
                var caster = caster(level, id);
                if (caster == null) return;
                Vec3 start = caster.getEyePosition().add(0, -.35, 0);
                line(level, caster, ParticleTypes.WAX_ON, start.lerp(to, .08), to, 1.6);
                for (int i = 0; i < 6; i++)
                    level.sendParticles(
                            caster,
                            ParticleTypes.END_ROD,
                            true,
                            true,
                            to.x,
                            to.y + .4 + i * .45,
                            to.z,
                            1,
                            .12,
                            .1,
                            .12,
                            .01);
                level.sendParticles(caster, ParticleTypes.GLOW, true, true, to.x, to.y + .3, to.z, 4, .35, .3, .35, 0);
            });
    }

    private static ServerPlayer caster(ServerLevel level, UUID id) {
        var p = level.getServer().getPlayerList().getPlayer(id);
        return p != null && p.level() == level && p.isAlive() ? p : null;
    }

    /** Points of light from a to b, this far apart, seen only by the caster at any distance. */
    private static void line(ServerLevel level, ServerPlayer p, ParticleOptions particle, Vec3 a, Vec3 b, double step) {
        Vec3 d = b.subtract(a);
        int n = Math.max(1, (int) Math.ceil(d.length() / step));
        for (int i = 0; i <= n; i++) {
            Vec3 at = a.add(d.scale(i / (double) n));
            level.sendParticles(p, particle, true, true, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }
}
