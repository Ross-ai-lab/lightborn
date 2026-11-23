package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The travel school's spells ({@link TravelSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestTravelSpells {
    private SelfTestTravelSpells() {}

    static void run() {
        try {
            tether();
            transpose();
            moongate();
            comet();
        } finally {
            clean();
            faceFresh();
        }
    }

    /**
     * A wall in your aim pulls you to it, past a creature in the way (owner, 2026-11-22: pulling creatures is the
     * Lasso upgrade now); with Lasso, the creature in your aim is pulled to you.
     */
    private static void tether() {
        face();
        BlockPos wall = caster.blockPosition().offset(12, 1, 0);
        BlockState was = level.getBlockState(wall);
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        var zombie = mob(EntityTypes.ZOMBIE, 8, 0);
        boolean self = cast(Spell.TETHER);
        TravelSpells.tickPulls();
        double flung = caster.getDeltaMovement().x, spared = zombie.getDeltaMovement().x;
        clean();
        face();
        zombie = mob(EntityTypes.ZOMBIE, 8, 0);
        boolean lasso = cast(Spell.TETHER, new int[] {-1, -1, 0});
        int pulls = TravelSpells.live("pulls");
        TravelSpells.tickPulls();
        double toward = zombie.getDeltaMovement().x;
        level.setBlockAndUpdate(wall, was);
        clean();
        check(
                "spell_tether_pulls_you_and_lassos_creatures",
                self && flung > .3 && Math.abs(spared) < .05 && lasso && pulls == 1 && toward < -.3,
                "caster vx=" + String.format("%.2f", flung) + " bare zombie vx=" + String.format("%.2f", spared)
                        + " lasso pulls=" + pulls + " zombie vx=" + String.format("%.2f", toward));
        face();
    }

    /** The caster and the zombie they look at trade places. */
    private static void transpose() {
        face();
        var zombie = mob(EntityTypes.ZOMBIE, 8, 0);
        Vec3 mine = caster.position(), theirs = zombie.position();
        boolean cast = cast(Spell.TRANSPOSE);
        double a = caster.position().distanceTo(theirs), b = zombie.position().distanceTo(mine);
        check(
                "spell_transpose_trades_places",
                cast && a < .1 && b < .1,
                "caster off " + String.format("%.2f", a) + " zombie off " + String.format("%.2f", b));
        clean();
        face();
    }

    /** A pig stepping into the near gate comes out of the far one; a zombie may not pass. */
    private static void moongate() {
        face();
        BlockPos wall = caster.blockPosition().offset(10, 1, 0);
        BlockState was = level.getBlockState(wall);
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        boolean cast = cast(Spell.MOONGATE);
        var pig = mob(EntityTypes.PIG, TravelSpells.GATE_AHEAD, 0);
        var zombie = mob(EntityTypes.ZOMBIE, TravelSpells.GATE_AHEAD, 0.2);
        TravelSpells.tickGates();
        double pigX = pig.getX() - caster.getX(), zombieX = zombie.getX() - caster.getX();
        level.setBlockAndUpdate(wall, was);
        check(
                "spell_moongate_carries_friends_not_monsters",
                cast && TravelSpells.live("gates") == 1 && pigX > 6 && zombieX < 3,
                "pig at +" + String.format("%.1f", pigX) + " zombie at +" + String.format("%.1f", zombieX));
        clean();
        // In open ground the far portal is a doorway: walking east into the near one, you walk on east out of the far
        // one, beyond it (owner, 2026-11-22: you came out of the wrong side).
        face();
        lookDown(10);
        boolean open = cast(Spell.MOONGATE);
        var walker = mob(EntityTypes.PIG, TravelSpells.GATE_AHEAD, 0);
        walker.xo = walker.getX() - .6; // it came from the caster's side
        TravelSpells.tickGates();
        double out = walker.getX() - caster.getX();
        check(
                "spell_moongate_walks_you_on_through",
                open && out > 10.5,
                "walker came out at +" + String.format("%.1f", out) + " (the far portal near +10)");
        clean();
    }

    /** The dash strikes a monster on its path once, and one off to the side not at all. */
    private static void comet() {
        faceFresh();
        double dmg = Spells.damage(caster, Spell.COMET_DASH);
        var onPath = tough(5, 0);
        var aside = tough(5, 6);
        float a0 = onPath.getHealth(), b0 = aside.getHealth();
        boolean cast = cast(Spell.COMET_DASH);
        boolean dashing = TravelSpells.dashing(caster);
        Vec3 start = caster.position();
        // The test player has no client to move it: walk it along the dash as the client would.
        for (int i = 1; i <= TravelSpells.COMET_TICKS + 1 && TravelSpells.dashing(caster); i++) {
            caster.setPos(start.add(1.4 * i, 0, 0));
            TravelSpells.tickDashes();
        }
        float lost = a0 - onPath.getHealth();
        check(
                "spell_comet_dash_strikes_on_path",
                cast
                        && dashing
                        && !TravelSpells.dashing(caster)
                        && lost >= dmg - 1e-3
                        && lost < dmg * 2.6
                        && aside.getHealth() == b0,
                "dashing=" + dashing + " lost " + lost + " of " + dmg + " aside lost " + (b0 - aside.getHealth()));
        clean();
    }
}
