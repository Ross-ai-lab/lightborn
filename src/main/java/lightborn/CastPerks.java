package lightborn;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The tier II branches of the first shelves' spells that act once, as the spell is cast (owner, 2026-11-23: "one
 * aspect per tree"; each spell's tier II holds its own small new things in place of a second stat boost). The ones
 * that act on each blow live in {@link Perks#afterHit}.
 */
final class CastPerks {
    private CastPerks() {}

    private static boolean has(ServerPlayer p, Spell spell, String name) {
        return SpellUpgrades.perk(p, spell, name);
    }

    private static void give(ServerPlayer p, net.minecraft.core.Holder<MobEffect> effect, int ticks, int amp) {
        p.addEffect(new MobEffectInstance(effect, ticks, amp, false, false, true));
    }

    /** Throws every monster within {@code r} of {@code p} away from them. Bosses stand. */
    private static void throwBack(ServerPlayer p, double r) {
        for (var e : Perks.nearestMonsters(p, null, p.position(), r, 32)) {
            if (e instanceof Mob m && Cage.mighty(m)) continue;
            Vec3 away = e.position().subtract(p.position()).multiply(1, 0, 1);
            away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
            double keep = 1 - SpellKit.knockResist(e);
            e.setDeltaMovement(away.scale(1.1 * keep).add(0, .4 * keep, 0));
            e.needsSync = true;
        }
    }

    /** After a cast of {@code spell} that went off. */
    static void after(ServerPlayer p, Spell spell) {
        ServerLevel level = p.level();
        switch (spell) {
            case FROST_NOVA -> {
                if (has(p, spell, "Nova Dash")) give(p, MobEffects.SPEED, 60, 1);
            }
            case BLINK -> {
                if (has(p, spell, "Blink Ward")) give(p, MobEffects.RESISTANCE, 40, 1);
                if (has(p, spell, "Blink Mend")) p.heal(2);
                if (has(p, spell, "Safe Landing")) {
                    p.resetFallDistance();
                    give(p, MobEffects.SLOW_FALLING, 40, 0);
                }
            }
            case STONESKIN -> {
                int ticks = EarthSpells.stoneskinTicks();
                if (has(p, spell, "Mossy Skin")) give(p, MobEffects.REGENERATION, ticks, 0);
                if (has(p, spell, "Stone Fists")) give(p, MobEffects.STRENGTH, ticks, 0);
            }
            case MENDING_LIGHT -> {
                if (has(p, spell, "Light Ward")) give(p, MobEffects.ABSORPTION, 200, 0);
            }
            case VERDANT_SURGE -> {
                if (has(p, spell, "Fresh Picking")) p.getFoodData().eat(4, .6f);
                if (has(p, spell, "Nature's Gift")) give(p, MobEffects.REGENERATION, 200, 0);
                if (has(p, spell, "Green Stride")) give(p, MobEffects.SPEED, 600, 0);
            }
            case REAP -> {
                if (has(p, spell, "Reaper's Edge"))
                    for (var e : Perks.nearestMonsters(p, null, p.position(), 4, 16)) {
                        Spells.hit(p, e, Perks.flat(p, 4), Spell.REAP);
                        level.sendParticles(
                                ParticleTypes.SWEEP_ATTACK, e.getX(), e.getY() + 1, e.getZ(), 1, 0, 0, 0, 0);
                    }
                if (has(p, spell, "Farmer's Feast")) p.getFoodData().eat(4, .6f);
                if (has(p, spell, "Swift Harvest")) give(p, MobEffects.SPEED, 400, 0);
            }
            case EXCAVATE -> {
                if (has(p, spell, "Digging Rush")) give(p, MobEffects.HASTE, 600, 1);
                if (has(p, spell, "Tidy Digger"))
                    for (var item : level.getEntitiesOfClass(
                            ItemEntity.class, p.getBoundingBox().inflate(8), i -> i.getAge() < 5))
                        item.teleportTo(p.getX(), p.getY() + .2, p.getZ());
                if (has(p, spell, "Gravel Guard")) give(p, MobEffects.RESISTANCE, 200, 0);
            }
            case ORE_SIGHT -> {
                if (has(p, spell, "Feather Step")) give(p, MobEffects.SLOW_FALLING, 600, 0);
                if (has(p, spell, "Danger Sense"))
                    for (var e : Perks.nearestMonsters(p, null, p.position(), 24, 64))
                        e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false, false));
            }
            case FELLING -> {
                if (has(p, spell, "Lumber Haste")) give(p, MobEffects.HASTE, 600, 0);
                if (has(p, spell, "Apple Picker"))
                    p.spawnAtLocation(
                            level,
                            new ItemStack(Items.APPLE, 1 + level.getRandom().nextInt(2)));
            }
            case HERD_CALL -> {
                boolean heal = has(p, spell, "Herd Heal"), speed = has(p, spell, "Herd Speed");
                if (heal || speed)
                    for (var a : level.getEntitiesOfClass(
                            Animal.class, p.getBoundingBox().inflate(24))) {
                        if (heal) a.setHealth(a.getMaxHealth());
                        if (speed) a.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 1, false, false, false));
                    }
            }
            case FORGE_FIRE -> {
                if (has(p, spell, "Fire Hands")) give(p, MobEffects.FIRE_RESISTANCE, 1200, 0);
                if (has(p, spell, "Forge Strength")) give(p, MobEffects.STRENGTH, 400, 0);
                if (has(p, spell, "Fire Burst"))
                    for (var e : Perks.nearestMonsters(p, null, p.position(), 4, 16)) e.igniteForSeconds(3);
            }
            case GATHERING -> {
                double r = WorkSpells.gatherRadius();
                if (has(p, spell, "Shake Off"))
                    for (var fx : java.util.List.copyOf(p.getActiveEffects()))
                        if (fx.getEffect().value().getCategory()
                                == net.minecraft.world.effect.MobEffectCategory.HARMFUL) {
                            p.removeEffect(fx.getEffect());
                            break;
                        }
                if (has(p, spell, "Arrow Pull"))
                    for (var arrow : level.getEntitiesOfClass(
                            AbstractArrow.class,
                            p.getBoundingBox().inflate(r),
                            a -> a.pickup == AbstractArrow.Pickup.ALLOWED))
                        arrow.teleportTo(p.getX(), p.getY() + .2, p.getZ());
                if (has(p, spell, "Gatherer's Haste")) give(p, MobEffects.SPEED, 200, 0);
            }
            case HOMEWARD -> {
                int ticks = TravelSpells.homewardTicks();
                if (has(p, spell, "Steady Channel")) give(p, MobEffects.RESISTANCE, ticks, 1);
                if (has(p, spell, "Calm Channel"))
                    for (var m : level.getEntitiesOfClass(
                            Mob.class, p.getBoundingBox().inflate(32))) if (m.getTarget() == p) m.setTarget(null);
                if (has(p, spell, "Warm Welcome")) give(p, MobEffects.REGENERATION, ticks + 200, 0);
            }
            case SKIES -> {
                if (has(p, spell, "Rain Dance")) give(p, MobEffects.SPEED, 600, 0);
                if (has(p, spell, "Rainbow Mend")) give(p, MobEffects.REGENERATION, 200, 0);
            }
            case ECLIPSE -> {
                if (has(p, spell, "Shadow Strength")) give(p, MobEffects.STRENGTH, 400, 0);
                if (has(p, spell, "Shadow Cloak")) give(p, MobEffects.RESISTANCE, 400, 0);
                if (has(p, spell, "Shadow Mana")) Perks.mana(p, 15);
            }
            case SOULS -> {
                if (has(p, spell, "Grave Speed")) give(p, MobEffects.SPEED, 400, 0);
                if (has(p, spell, "Soul Mend")) p.heal(4);
            }
            case RADIANT_PULSE -> {
                if (has(p, spell, "Radiant Ward")) give(p, MobEffects.RESISTANCE, 200, 0);
                if (has(p, spell, "Pulse Burst")) throwBack(p, 4);
                if (has(p, spell, "Holy Glow"))
                    for (var e : Perks.nearestMonsters(p, null, p.position(), 16, 64))
                        e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false, false));
            }
            case TREASURE_THREAD -> {
                if (has(p, spell, "Treasure Stride")) give(p, MobEffects.SPEED, 400, 0);
                if (has(p, spell, "Lucky Find")) give(p, MobEffects.LUCK, 2400, 0);
            }
            case FLIGHT -> {
                if (has(p, spell, "Sky Ward")) give(p, MobEffects.RESISTANCE, 200, 0);
                if (has(p, spell, "Lift Off")) {
                    p.setDeltaMovement(p.getDeltaMovement().add(0, .8, 0));
                    p.connection.send(new ClientboundSetEntityMotionPacket(p));
                }
            }
            case CAGE -> {
                if (has(p, spell, "Jailer's Haste")) give(p, MobEffects.SPEED, 100, 0);
            }
            case TAUNT -> {
                if (has(p, spell, "Safe Distance")) give(p, MobEffects.SPEED, 200, 0);
            }
            default -> {}
        }
    }
}
