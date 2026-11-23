package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.AABB;

/**
 * The storm school's spells ({@link StormSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestStormSpells {
    private SelfTestStormSpells() {}

    static void run() {
        try {
            ballLightning();
            cyclone();
            tempest();
        } finally {
            clean();
            faceFresh();
        }
    }

    /** The orb zaps a monster near it and spares the cow. */
    private static void ballLightning() {
        face();
        double zap = Spells.damage(caster, Spell.BALL_LIGHTNING);
        var zombie = mob(EntityTypes.ZOMBIE, 3, 0);
        var cow = mob(EntityTypes.COW, 3, 2.5);
        float z0 = zombie.getHealth(), c0 = cow.getHealth();
        boolean orb = cast(Spell.BALL_LIGHTNING);
        for (int i = 0; i < 10; i++) StormSpells.tickOrbs();
        float lost = z0 - zombie.getHealth();
        check(
                "spell_ball_lightning_zaps_monsters",
                orb && lost >= zap - 1e-3 && cow.getHealth() == c0,
                "zombie -" + lost + " (zap " + zap + ") cow -" + (c0 - cow.getHealth()));
        clean();
    }

    /** The whirlwind catches the monster ahead, cutting it and lifting it, and leaves the cow beside you alone. */
    private static void cyclone() {
        face();
        double dmg = Spells.damage(caster, Spell.CYCLONE);
        var zombie = mob(EntityTypes.ZOMBIE, 3.5, 0);
        var cow = mob(EntityTypes.COW, -3, 0);
        float z0 = zombie.getHealth(), c0 = cow.getHealth();
        boolean cast = cast(Spell.CYCLONE);
        StormSpells.tickGales();
        float lost = z0 - zombie.getHealth();
        double lift = zombie.getDeltaMovement().y;
        check(
                "spell_cyclone_catches_and_lifts",
                cast && StormSpells.live("gales") == 1 && lost >= dmg - 1e-3 && lift > .05 && cow.getHealth() == c0,
                "lost " + lost + " of " + dmg + " lift=" + String.format("%.2f", lift));
        clean();
    }

    /** Each second lightning strikes the monsters nearest you. */
    private static void tempest() {
        faceHealed();
        double dmg = Spells.damage(caster, Spell.TEMPEST);
        var zombie = mob(EntityTypes.ZOMBIE, 6, 0);
        float z0 = zombie.getHealth();
        boolean cast = cast(Spell.TEMPEST);
        StormSpells.tickTempests();
        float lost = z0 - zombie.getHealth();
        check(
                "spell_tempest_strikes_and_burns",
                cast && lost >= dmg - 1e-3 && zombie.isOnFire() && StormSpells.live("tempests") == 1,
                "lost " + lost + " of " + dmg + " burning=" + zombie.isOnFire());
        level.getEntitiesOfClass(
                        net.minecraft.world.entity.LightningBolt.class, new AABB(caster.blockPosition()).inflate(32))
                .forEach(net.minecraft.world.entity.Entity::discard);
        clean();
    }
}
