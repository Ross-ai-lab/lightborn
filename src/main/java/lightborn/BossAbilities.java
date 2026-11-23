package lightborn;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Each boss cycles through its active moves. Half-health and resurrection moves have their own triggers. */
final class BossAbilities {
    private BossAbilities() {}

    static void cast(BossKind kind, int step, ServerLevel level, Mob boss, ServerPlayer target) {
        int move = Math.floorMod(step, kind == BossKind.GORATH || kind == BossKind.SYRITH ? 2 : 3);
        switch (kind) {
            case GORATH -> {
                if (move == 0) slam(level, boss, 5.5, Balance.GORATH_SLAM_HIT, 20);
                else boulder(level, boss, target.position());
            }
            case VEXMORA -> {
                if (move == 0) volley(level, boss, target);
                else if (move == 1) {
                    sound(level, boss.position(), SoundEvents.EVOKER_CAST_SPELL);
                    Bosses.add(level, boss, EntityTypes.SKELETON, boss.position(), false);
                    Bosses.add(level, boss, ModEntities.DEADEYE_SKELETON, boss.position(), false);
                    Bosses.add(level, boss, ModEntities.EMBER_SKELETON, boss.position(), false);
                } else cage(level, boss, target.position());
            }
            case MAELIS -> {
                if (move == 0) webs(level, boss, target.position());
                else if (move == 1) pounce(level, boss, target.position());
                else {
                    sound(level, boss.position(), SoundEvents.SPIDER_AMBIENT);
                    for (int i = 0; i < 4; i++)
                        Bosses.add(level, boss, EntityTypes.CAVE_SPIDER, boss.position(), false);
                }
            }
            case SYRITH -> {
                if (move == 0) drain(level, boss, target);
                else mistStep(level, boss, target);
            }
            case OZRIK -> {
                if (move == 0) nova(level, boss);
                else if (move == 1) meteor(level, boss, target.position());
                else trail(level, boss);
            }
            case NYX -> {
                if (move == 0) darkness(level, boss);
                else if (move == 1) {
                    sound(level, boss.position(), SoundEvents.ILLUSIONER_MIRROR_MOVE);
                    for (int i = 0; i < 3; i++)
                        Bosses.add(level, boss, ModEntities.DARKNESS_ZOMBIE, boss.position(), true);
                } else gravity(level, boss, target.position());
            }
        }
    }

    static void gorathEnrage(ServerLevel level, Mob boss) {
        for (int i = 0; i < Balance.GORATH_ENRAGE_GIANTS; i++)
            Bosses.add(level, boss, ModEntities.GIANT_ZOMBIE, boss.position(), false);
    }

    private static void slam(ServerLevel level, Mob boss, double radius, float damage, int delay) {
        sound(level, boss.position(), SoundEvents.RAVAGER_ROAR);
        var dust = new DustParticleOptions(0xEF433F, 1.5f);
        Bosses.ring(level, boss.position(), radius, dust, 48);
        later(level, boss, delay / 2, () -> Bosses.ring(level, boss.position(), radius, dust, 48));
        later(level, boss, delay, () -> {
            Vec3 at = boss.position();
            sound(level, at, SoundEvents.GENERIC_EXPLODE.value());
            level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, 14, 1.2, 0.2, 1.2, 0);
            area(level, at, radius, p -> {
                hit(level, boss, p, damage);
                double lift = 0.9 * (1 - p.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
                p.setDeltaMovement(p.getDeltaMovement().add(0, lift, 0));
                p.needsSync = true;
            });
        });
    }

    private static void boulder(ServerLevel level, Mob boss, Vec3 at) {
        sound(level, boss.position(), SoundEvents.STONE_BREAK);
        var dust = new DustParticleOptions(0xE6A261, 1.4f);
        Bosses.ring(level, at, Balance.BOULDER_RADIUS, dust, 36);
        later(level, boss, 8, () -> {
            Bosses.ring(level, at, Balance.BOULDER_RADIUS, dust, 36);
            FireSpells.thrownRock(level, boss.getEyePosition(), at, Balance.BOULDER_FLIGHT, () -> {
                if (!boss.isAlive() || boss.level() != level) return;
                sound(level, at, SoundEvents.STONE_BREAK);
                area(level, at, Balance.BOULDER_RADIUS, p -> hit(level, boss, p, Balance.BOULDER_HIT));
            });
        });
    }

    private static void volley(ServerLevel level, Mob boss, ServerPlayer player) {
        sound(level, boss.position(), SoundEvents.CROSSBOW_LOADING_START.value());
        later(level, boss, 12, () -> {
            if (!player.isAlive() || player.level() != level || BossTrophies.concealed(player)) return;
            Vec3 aim = player.getEyePosition().subtract(boss.getEyePosition()).normalize();
            for (int i = -2; i <= 2; i++) {
                Vec3 dir = aim.yRot(i * 0.13f);
                var arrow = new Arrow(level, boss, new ItemStack(Items.ARROW), boss.getMainHandItem());
                arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                arrow.shoot(dir.x, dir.y, dir.z, 1.7f, 0);
                level.addFreshEntity(arrow);
            }
            sound(level, boss.position(), SoundEvents.SKELETON_SHOOT);
        });
    }

    private static void cage(ServerLevel level, Mob boss, Vec3 at) {
        double radius = Balance.CAGE_RADIUS;
        Bosses.ring(level, at, radius, new DustParticleOptions(0xEEE0BD, 1.4f), 36);
        sound(level, at, SoundEvents.BONE_BLOCK_HIT);
        later(level, boss, 14, () -> {
            for (int i = 0; i < 10; i++) {
                double angle = i * Math.PI / 5;
                var spot = EarthSpells.standing(level, at.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
                if (spot != null) EarthSpells.spear(level, Vec3.atBottomCenterOf(spot), 0.95f, true);
            }
            for (int ticks = 1; ticks <= Balance.CAGE_TICKS; ticks += 10) {
                later(
                        level,
                        boss,
                        ticks,
                        () -> area(level, at, radius + 0.8, p -> {
                            if (p.distanceToSqr(at) < (radius - 0.8) * (radius - 0.8)) return;
                            p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 16, 2), boss);
                            hit(level, boss, p, 2);
                        }));
            }
        });
    }

    private static void webs(ServerLevel level, Mob boss, Vec3 at) {
        Bosses.ring(level, at, Balance.WEB_BURST_RADIUS, new DustParticleOptions(0xEEE3F6, 1.5f), 32);
        sound(level, at, SoundEvents.SPIDER_AMBIENT);
        later(level, boss, 12, () -> {
            BlockPos center = BlockPos.containing(at);
            int radius = (int) Balance.WEB_BURST_RADIUS;
            for (int x = -radius; x <= radius; x++)
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z > radius * radius || ((x + z) & 1) != 0) continue;
                    var spot = EarthSpells.standing(level, Vec3.atBottomCenterOf(center.offset(x, 0, z)));
                    if (spot != null) WeaverSpider.spin(level, spot, Balance.BOSS_WEB_TICKS);
                }
        });
    }

    private static void pounce(ServerLevel level, Mob boss, Vec3 at) {
        sound(level, boss.position(), SoundEvents.SPIDER_AMBIENT);
        Bosses.ring(level, at, 3.5, new DustParticleOptions(0xA977BC, 1.5f), 32);
        later(level, boss, 12, () -> {
            Vec3 horizontal = at.subtract(boss.position()).multiply(1, 0, 1);
            boss.setDeltaMovement(horizontal
                    .normalize()
                    .scale(Math.min(1.5, horizontal.length() * 0.11))
                    .add(0, 0.8, 0));
            boss.needsSync = true;
            slam(level, boss, 3.5, Balance.BOSS_POUNCE_HIT, 18);
        });
    }

    private static void drain(ServerLevel level, Mob boss, ServerPlayer target) {
        sound(level, boss.position(), SoundEvents.ILLUSIONER_MIRROR_MOVE);
        var blood = new DustParticleOptions(0xB22146, 1.1f);
        for (int ticks = 6; ticks <= 30; ticks += 6) {
            int step = ticks;
            later(level, boss, ticks, () -> {
                if (!target.isAlive()
                        || target.level() != level
                        || BossTrophies.concealed(target)
                        || boss.distanceTo(target) > 14
                        || !boss.hasLineOfSight(target)) return;
                SpellKit.line(level, target.getEyePosition(), boss.getEyePosition(), blood, 0.45);
                if (step >= 18 && hit(level, boss, target, Balance.SYRITH_DRAIN_HIT))
                    boss.heal(Balance.SYRITH_DRAIN_HIT);
            });
        }
    }

    private static void mistStep(ServerLevel level, Mob boss, ServerPlayer target) {
        Vec3 backward = target.getLookAngle().multiply(-1, 0, -1).normalize();
        var spot = EarthSpells.standing(level, target.position().add(backward.scale(3)));
        if (spot == null) return;
        Vec3 at = Vec3.atBottomCenterOf(spot);
        Bosses.ring(level, at, 1.4, ParticleTypes.PORTAL, 24);
        sound(level, at, SoundEvents.ENDERMAN_TELEPORT);
        later(level, boss, 12, () -> {
            if (!level.hasChunkAt(spot)
                    || !level.noCollision(boss, boss.getBoundingBox().move(at.subtract(boss.position())))) return;
            boss.setPos(at);
            boss.needsSync = true;
            level.sendParticles(ParticleTypes.PORTAL, at.x, at.y + 1, at.z, 50, 0.35, 0.6, 0.35, 0.1);
        });
    }

    private static void nova(ServerLevel level, Mob boss) {
        sound(level, boss.position(), SoundEvents.BLAZE_SHOOT);
        for (int t = 0; t < 4; t++) {
            int step = t;
            later(
                    level,
                    boss,
                    1 + step * 4,
                    () -> Bosses.ring(level, boss.position(), 1.5 + step * 1.5, ParticleTypes.FLAME, 40));
        }
        later(
                level,
                boss,
                16,
                () -> area(level, boss.position(), 7, p -> {
                    hit(level, boss, p, Balance.OZRIK_NOVA_HIT);
                    p.igniteForSeconds(3);
                }));
    }

    private static void meteor(ServerLevel level, Mob boss, Vec3 at) {
        sound(level, at, SoundEvents.FIRECHARGE_USE);
        // A boss's meteor keeps its one-second drop, so the fight's rhythm is unchanged.
        FireSpells.meteorAt(level, at, 20, 3, () -> {
            if (boss.isAlive() && boss.level() == level)
                area(level, at, 3, p -> {
                    hit(level, boss, p, Balance.OZRIK_METEOR_HIT);
                    p.igniteForSeconds(4);
                });
        });
    }

    private static void trail(ServerLevel level, Mob boss) {
        sound(level, boss.position(), SoundEvents.BLAZE_AMBIENT);
        var footprints = new ArrayDeque<Vec3>();
        for (int ticks = 5; ticks <= Balance.OZRIK_TRAIL_TICKS + 20; ticks += 5) {
            int step = ticks;
            later(level, boss, ticks, () -> {
                if (step <= Balance.OZRIK_TRAIL_TICKS) footprints.addLast(boss.position());
                if (footprints.size() > 5 || step > Balance.OZRIK_TRAIL_TICKS) footprints.pollFirst();
                for (Vec3 at : footprints) {
                    level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.1, at.z, 6, 0.55, 0.12, 0.55, 0.006);
                }
                if (step % 10 != 0) return;
                for (ServerPlayer p : level.getEntitiesOfClass(
                        ServerPlayer.class, boss.getBoundingBox().inflate(12))) {
                    if (footprints.stream().noneMatch(at -> p.distanceToSqr(at) <= 2.25)) continue;
                    if (hit(level, boss, p, Balance.OZRIK_TRAIL_HIT)) p.igniteForSeconds(2);
                }
            });
        }
    }

    private static void darkness(ServerLevel level, Mob boss) {
        sound(level, boss.position(), SoundEvents.WARDEN_HEARTBEAT);
        Bosses.ring(level, boss.position(), 10, ParticleTypes.SOUL, 48);
        later(
                level,
                boss,
                12,
                () -> area(
                        level,
                        boss.position(),
                        12,
                        p -> p.addEffect(
                                new MobEffectInstance(MobEffects.DARKNESS, Balance.NYX_DARKNESS_TICKS), boss)));
    }

    private static void gravity(ServerLevel level, Mob boss, Vec3 at) {
        double radius = Balance.NYX_WELL_RADIUS;
        var dust = new DustParticleOptions(0xA15BEE, 1.35f);
        Bosses.ring(level, at, radius, dust, 48);
        sound(level, at, SoundEvents.PORTAL_AMBIENT);
        for (int t = 1; t <= Balance.NYX_WELL_TICKS; t += 4) {
            int step = t;
            later(level, boss, 16 + t, () -> {
                Bosses.ring(level, at, 0.7 + (radius - 0.7) * (1 - step / (double) Balance.NYX_WELL_TICKS), dust, 28);
                area(level, at, radius, p -> {
                    Vec3 pull = at.subtract(p.position());
                    p.setDeltaMovement(p.getDeltaMovement()
                            .scale(0.65)
                            .add(pull.normalize().scale(Math.min(0.45, pull.length() * 0.15))));
                    p.needsSync = true;
                });
            });
        }
        later(level, boss, 16 + Balance.NYX_WELL_TICKS, () -> {
            Bosses.ring(level, at, 3, ParticleTypes.SOUL, 32);
            area(level, at, 3, p -> hit(level, boss, p, Balance.NYX_WELL_HIT));
        });
    }

    private static void area(ServerLevel level, Vec3 at, double radius, Consumer<ServerPlayer> effect) {
        List<ServerPlayer> players = level.getEntitiesOfClass(
                ServerPlayer.class,
                new AABB(at, at).inflate(radius),
                p -> Hordes.huntable(p) && p.distanceToSqr(at) <= radius * radius);
        players.forEach(effect);
    }

    private static boolean hit(ServerLevel level, Mob boss, ServerPlayer player, float amount) {
        return Hordes.huntable(player)
                && player.hurtServer(level, level.damageSources().mobAttack(boss), amount);
    }

    private static void later(ServerLevel level, Mob boss, int delay, Runnable action) {
        Scheduler.later(delay, () -> {
            if (boss.isAlive() && boss.level() == level && !boss.isRemoved()) action.run();
        });
    }

    private static void sound(ServerLevel level, Vec3 at, SoundEvent event) {
        level.playSound(null, at.x, at.y, at.z, event, SoundSource.HOSTILE, 1.25f, 0.8f);
    }
}
