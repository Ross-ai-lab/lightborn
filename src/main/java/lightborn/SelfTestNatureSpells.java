package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SpellTestKit.*;

import java.util.List;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;

/**
 * The nature school's spells ({@link NatureSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestNatureSpells {
    private SelfTestNatureSpells() {}

    static void run() {
        try {
            thornfield();
            renewal();
            purify();
            lifebloom();
        } finally {
            clean();
            faceFresh();
        }
    }

    /** Thornfield slows and cuts the monster in it. */
    private static void thornfield() {
        face();
        double thorns = Spells.damage(caster, Spell.THORNFIELD);
        var zombie = mob(EntityTypes.ZOMBIE, 6, 0);
        lookDown(6);
        float z0 = zombie.getHealth();
        boolean grown = cast(Spell.THORNFIELD);
        NatureSpells.tickThickets();
        float lost = z0 - zombie.getHealth();
        check(
                "spell_thornfield_slows_and_cuts",
                grown && lost >= thorns - 1e-3 && zombie.hasEffect(MobEffects.SLOWNESS),
                "lost " + lost + " of " + thorns + " slowed=" + zombie.hasEffect(MobEffects.SLOWNESS));
        clean();
    }

    /** A golden apple's effects; the enchanted one's with that upgrade (checked by its own numbers). */
    private static void renewal() {
        faceHealed();
        caster.removeAllEffects();
        boolean cast = cast(Spell.RENEWAL);
        var regen = caster.getEffect(MobEffects.REGENERATION);
        var absorb = caster.getEffect(MobEffects.ABSORPTION);
        boolean apple = regen != null
                && regen.getAmplifier() == 1
                && regen.getDuration() <= NatureSpells.APPLE_REGEN
                && absorb != null
                && absorb.getAmplifier() == 0
                && absorb.getDuration() > 2000;
        caster.removeAllEffects();
        check("spell_golden_apple_gives_its_effects", cast && apple, "regen=" + regen + " absorption=" + absorb);
        clean();
    }

    /** Poison is lifted, and new poison cannot take hold for a moment. */
    private static void purify() {
        faceFresh();
        caster.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
        boolean cast = cast(Spell.PURIFY);
        boolean lifted = !caster.hasEffect(MobEffects.POISON);
        caster.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
        boolean kept = !caster.hasEffect(MobEffects.POISON);
        check("spell_purify_lifts_and_wards", cast && lifted && kept, "lifted=" + lifted + " kept off=" + kept);
        clean();
    }

    /**
     * The tree grows at your feet with its ring laid round it, heals a villager beside you as it opens and lifts its
     * harmful effect, then fades. (The test player is never added to the world, so no search can find it: a villager
     * stands in.)
     */
    private static void lifebloom() {
        faceFresh();
        var villager = mob(EntityTypes.VILLAGER, 1, 0);
        villager.setHealth(villager.getMaxHealth() - 6);
        villager.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
        float h0 = villager.getHealth();
        caster.setXRot(90f); // at your feet
        boolean cast = cast(Spell.LIFEBLOOM);
        float healed = villager.getHealth() - h0;
        boolean cured = !villager.hasEffect(MobEffects.POISON);
        var b = NatureSpells.BLOOMS.isEmpty() ? null : NatureSpells.BLOOMS.get(0);
        int tree = b == null ? 0 : b.tree.size(), ring = b == null ? 0 : b.ring.size();
        var strips = b == null ? List.<net.minecraft.world.entity.Display.BlockDisplay>of() : List.copyOf(b.ring);
        for (int i = 0; i < 2000 && NatureSpells.live("blooms") > 0; i++) NatureSpells.tickBlooms();
        boolean gone = strips.stream().allMatch(net.minecraft.world.entity.Entity::isRemoved);
        check(
                "spell_lifebloom_tree_heals_and_fades",
                cast
                        && tree == NatureSpells.TREE.size()
                        && ring >= 40
                        && healed >= 1.99
                        && cured
                        && NatureSpells.live("blooms") == 0
                        && gone,
                "tree " + tree + " ring " + ring + " healed " + healed + " cured=" + cured + " left "
                        + NatureSpells.live("blooms") + " ring gone=" + gone);
        clean();
    }
}
