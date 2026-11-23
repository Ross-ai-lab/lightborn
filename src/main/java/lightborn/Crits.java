package lightborn;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Weapon crits (owner, 2026-11-17): every melee hit, arrow and thrown trident of a player has a base 5% chance
 * ({@link ModAttributes#WEAPON_CRIT}) to deal {@link ModAttributes#CRIT_DAMAGE} times its damage, triple at base, the
 * same multiplier a spell crit uses. Arrows also carry their bow's {@link ModAttributes#RANGED_POWER}, read from the
 * bow that fired them, so switching to a sword in flight changes nothing.
 */
final class Crits {
    private Crits() {}

    private static LivingEntity lastTarget;
    private static long lastTick;

    /** Called for every hit before armour, from {@link Monsters#modifyIncomingDamage}. */
    static float roll(LivingEntity target, ServerLevel level, DamageSource source, float amount) {
        if (!(source.getEntity() instanceof ServerPlayer p) || target == p || amount <= 0) return amount;
        double chance;
        if (source.getDirectEntity() == p && !source.is(DamageTypeTags.IS_PROJECTILE) && isMelee(source)) {
            chance = p.getAttributeValue(ModAttributes.WEAPON_CRIT);
        } else if (source.getDirectEntity() instanceof AbstractArrow arrow) {
            ItemStack bow = arrow.getWeaponItem();
            amount *= (float) (1 + sum(bow, ModAttributes.RANGED_POWER));
            chance = p.getAttributeBaseValue(ModAttributes.WEAPON_CRIT) + sum(bow, ModAttributes.WEAPON_CRIT);
        } else return amount;
        if (level.getRandom().nextDouble() >= chance) return amount;
        lastTarget = target;
        lastTick = level.getGameTime();
        level.sendParticles(
                ParticleTypes.CRIT,
                target.getX(),
                target.getY() + target.getBbHeight() * .6,
                target.getZ(),
                14,
                .3,
                .3,
                .3,
                .3);
        level.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1f, 1.1f);
        return amount * (float) p.getAttributeValue(ModAttributes.CRIT_DAMAGE);
    }

    private static boolean isMelee(DamageSource source) {
        return source.is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)
                || source.is(net.minecraft.world.damagesource.DamageTypes.MACE_SMASH)
                || source.is(net.minecraft.world.damagesource.DamageTypes.SPEAR);
    }

    /** The sum of a held item's own modifiers to one attribute (added values and base multipliers alike). */
    private static double sum(
            ItemStack s, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> a) {
        if (s == null || s.isEmpty()) return 0;
        double out = 0;
        for (var e : s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .modifiers())
            if (e.attribute().equals(a) && e.modifier().operation() != AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
                out += e.modifier().amount();
        return out;
    }

    /** Whether the hit just taken by {@code target} was a weapon crit, for its damage number. */
    static boolean landed(LivingEntity target) {
        boolean hit = target == lastTarget && target.level().getGameTime() == lastTick;
        if (hit) lastTarget = null;
        return hit;
    }
}
