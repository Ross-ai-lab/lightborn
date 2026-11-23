package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The earth school's spells ({@link EarthSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestEarthSpells {
    private SelfTestEarthSpells() {}

    static void run() {
        try {
            stoneWall();
            crystalShell();
            boulder();
        } finally {
            clean();
            faceFresh();
        }
    }

    /** The wall rises 8 by 3 and is taken away by its own tick. */
    private static void stoneWall() {
        face();
        var ground = caster.blockPosition();
        LightSpells.instant = true; // the wall's rows at once, not over a few ticks
        boolean raised = cast(Spell.STONE_WALL);
        var wall = conjured(ground, 8);
        boolean ticking = !wall.isEmpty()
                && wall.stream().allMatch(c -> level.getBlockTicks().hasScheduledTick(c, ConjuredBlock.STONE));
        int count = wall.size();
        for (var c : wall) level.getBlockState(c).tick(level, c, level.getRandom());
        check(
                "spell_stone_wall_rises_and_crumbles",
                raised
                        && count == EarthSpells.WALL_WIDTH * EarthSpells.WALL_HEIGHT
                        && ticking
                        && conjured(ground, 8).isEmpty(),
                "placed=" + count + " ticking=" + ticking + " left="
                        + conjured(ground, 8).size());
        clean();
    }

    /** Sealed in crystal the caster cannot be hurt or move; when it shatters a monster beside them is thrown. */
    private static void crystalShell() {
        face();
        var zombie = mob(EntityTypes.ZOMBIE, 2, 0);
        caster.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.POISON, 400, 0));
        boolean cast = cast(Spell.CRYSTAL_SHELL);
        boolean pure = !caster.hasEffect(MobEffects.POISON);
        boolean safe = !EarthSpells.allowDamage(caster, level.damageSources().generic(), 5f);
        boolean still = caster.getAttributeValue(Attributes.MOVEMENT_SPEED) < 1e-6;
        for (int i = 0; i <= EarthSpells.SHELL_TICKS; i++) EarthSpells.tickShells();
        boolean free = caster.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0 && !EarthSpells.shelled(caster);
        boolean hurtable = EarthSpells.allowDamage(caster, level.damageSources().generic(), 5f);
        double thrown = zombie.getDeltaMovement().x;
        check(
                "spell_crystal_shell_shields_then_shatters",
                cast && pure && safe && still && free && hurtable && thrown > .3,
                "pure=" + pure + " safe=" + safe + " still=" + still + " free=" + free + " zombie vx="
                        + String.format("%.2f", thrown));
        clean();
    }

    private static void boulder() {
        faceFresh();
        double dmg = Spells.damage(caster, Spell.BOULDER);
        var zombie = mob(EntityTypes.ZOMBIE, 6, 0);
        float z0 = zombie.getHealth();
        caster.setXRot(0f);
        boolean cast = cast(Spell.BOULDER);
        for (int i = 0; i < 60 && EarthSpells.live("boulders") > 0; i++) EarthSpells.tickBoulders();
        float lost = z0 - zombie.getHealth();
        check(
                "spell_boulder_smashes",
                cast && lost >= dmg - 1e-3 && EarthSpells.live("boulders") == 0,
                "lost " + lost + " of " + dmg);
        clean();
    }
}
