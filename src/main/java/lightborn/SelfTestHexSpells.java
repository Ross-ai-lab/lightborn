package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import java.util.EnumSet;
import net.minecraft.world.entity.EntityTypes;

/**
 * The hex school's spells ({@link HexSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestHexSpells {
    private SelfTestHexSpells() {}

    static void run() {
        try {
            soulLink();
            enfeeble();
            fright();
            doom();
        } finally {
            clean();
            faceFresh();
        }
    }

    /** A blow to one of a bound pack is shared with the rest. */
    private static void soulLink() {
        faceHealed();
        var first = mob(EntityTypes.ZOMBIE, 6, 0);
        var second = mob(EntityTypes.ZOMBIE, 7.5, 2);
        var third = mob(EntityTypes.ZOMBIE, 7.5, -2);
        boolean cast = cast(Spell.SOUL_LINK);
        boolean bound = HexSpells.linked(first) && HexSpells.linked(second) && HexSpells.linked(third);
        float f0 = first.getHealth(), s0 = second.getHealth(), t0 = third.getHealth();
        int links = HexSpells.live("links");
        Spells.hit(caster, first, 10, Spell.ARCANE_BOLT);
        float shared = Math.min(s0 - second.getHealth(), t0 - third.getHealth());
        check(
                "spell_soul_link_shares_harm",
                cast && bound && shared >= 10 * HexSpells.LINK_SHARE - .5,
                "bound=" + bound + " links=" + links + " first lost " + (f0 - first.getHealth()) + " shared " + shared);
        clean();
    }

    /** The weakened monster deals half its harm. */
    private static void enfeeble() {
        faceFresh();
        var zombie = mob(EntityTypes.ZOMBIE, 6, 0);
        boolean cast = cast(Spell.ENFEEBLE);
        float harm = SpellSchools.incoming(caster, caster.damageSources().mobAttack(zombie), 10);
        check(
                "spell_enfeeble_halves_harm",
                cast && HexSpells.live("enfeebled") == 1 && Math.abs(harm - 5) < 1e-3,
                "10 became " + harm);
        clean();
    }

    /** The monster near runs from you and forgets you. */
    private static void fright() {
        faceFresh();
        var zombie = mob(EntityTypes.ZOMBIE, 4, 0);
        zombie.setNoAi(false);
        zombie.setTarget(caster);
        boolean cast = cast(Spell.FRIGHT);
        boolean afraid = HexSpells.afraid(zombie) && zombie.getTarget() == null;
        check("spell_fright_scatters", cast && afraid, "afraid=" + afraid);
        clean();
    }

    /**
     * Doom on an animal: a skull over it, one random curse, 35% more harm from everything. With Weakness, Burning and
     * Terror in use a zombie suffers those three and one more, deals 30% less, and the skull goes when it ends.
     */
    private static void doom() {
        faceFresh();
        var cow = mob(EntityTypes.COW, 6, 0);
        boolean cast = cast(Spell.DOOM);
        var d = HexSpells.doomed(cow);
        boolean one = d != null && d.curses.size() == 1 && d.skull != null && !d.skull.isRemoved();
        float harm = SpellSchools.incoming(cow, level.damageSources().generic(), 10);
        clean();
        faceFresh();
        var zombie = mob(EntityTypes.ZOMBIE, 6, 0);
        boolean cast2 = castWith(Spell.DOOM, new int[] {1, 1, 2});
        var z = HexSpells.doomed(zombie);
        boolean chosen = z != null
                && z.curses.size() == 4
                && z.curses.containsAll(
                        EnumSet.of(HexSpells.Curse.WEAKNESS, HexSpells.Curse.BURNING, HexSpells.Curse.TERROR))
                && zombie.getRemainingFireTicks() > 0
                && HexSpells.afraid(zombie);
        float dealt = SpellSchools.incoming(caster, caster.damageSources().mobAttack(zombie), 10);
        for (int i = 0; i < 400 && HexSpells.doomed(zombie) != null; i++) HexSpells.tickDoom();
        boolean ends = HexSpells.doomed(zombie) == null && (z == null || z.skull == null || z.skull.isRemoved());
        check(
                "spell_doom_curses",
                cast && one && Math.abs(harm - 13.5) < 1e-3 && cast2 && chosen && Math.abs(dealt - 7) < 1e-3 && ends,
                "cow " + (d == null ? "not doomed" : d.curses + " skull=" + (d.skull != null)) + " took " + harm
                        + "; zombie " + (z == null ? "not doomed" : z.curses) + " fire "
                        + zombie.getRemainingFireTicks()
                        + " afraid " + HexSpells.afraid(zombie) + " dealt " + dealt + " ends=" + ends);
        clean();
    }
}
