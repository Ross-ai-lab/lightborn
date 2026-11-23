package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.phys.AABB;

/**
 * The summon school's spells ({@link SummonSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestSummonSpells {
    private SelfTestSummonSpells() {}

    static void run() {
        try {
            colossus();
        } finally {
            clean();
            faceFresh();
        }
    }

    /** A giant golem of light rises at your side, bound to you. */
    private static void colossus() {
        faceHealed();
        boolean cast = cast(Spell.COLOSSUS);
        var golems = level.getEntitiesOfClass(IronGolem.class, new AABB(caster.blockPosition()).inflate(8));
        boolean giant = golems.size() == 1
                && Math.abs(golems.get(0).getAttributeValue(Attributes.SCALE) - SummonSpells.COLOSSUS_SCALE) < 1e-6
                && SummonSpells.isThrall(golems.get(0));
        golems.forEach(IronGolem::discard);
        check("spell_giant_golem_rises", cast && giant, "golems=" + golems.size());
        clean();
    }
}
