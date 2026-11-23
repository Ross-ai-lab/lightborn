package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The fire school's spells ({@link FireSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestFireSpells {
    private SelfTestFireSpells() {}

    static void run() {
        try {
            emberwalk();
        } finally {
            clean();
            faceFresh();
        }
    }

    /** Lava under the walker hardens; once they leave, it melts back to lava. */
    private static void emberwalk() {
        face();
        BlockPos cell = caster.blockPosition().below();
        BlockState was = level.getBlockState(cell);
        level.setBlockAndUpdate(cell, Blocks.LAVA.defaultBlockState());
        boolean cast = cast(Spell.EMBERWALK);
        BlockState crust = level.getBlockState(cell);
        boolean hard = crust.is(EmberCrust.CRUST) && crust.getValue(EmberCrust.SOURCE);
        boolean fireproof = caster.hasEffect(MobEffects.FIRE_RESISTANCE);
        caster.setPos(caster.position().add(6, 0, 0));
        level.getBlockState(cell).tick(level, cell, level.getRandom());
        boolean melted = level.getBlockState(cell).is(Blocks.LAVA);
        level.setBlockAndUpdate(cell, was);
        // A monster that strikes the walker catches fire (owner, 2026-11-22).
        var zombie = mob(EntityTypes.ZOMBIE, 1.5, 0);
        FireSpells.afterDamage(caster, level.damageSources().mobAttack(zombie), 2f, 2f, false);
        boolean kindled = zombie.getRemainingFireTicks() > 0;
        caster.removeEffect(MobEffects.FIRE_RESISTANCE);
        check(
                "spell_emberwalk_hardens_lava",
                cast && hard && fireproof && melted && FireSpells.walking(caster) && kindled,
                "crust=" + crust + " fireproof=" + fireproof + " melted=" + melted + " striker burns=" + kindled);
        clean();
        face();
    }
}
