package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;

/**
 * The frost school's spells ({@link FrostSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestFrostSpells {
    private SelfTestFrostSpells() {}

    static void run() {
        try {
            sentinel();
            blizzard();
            frostwalk();
            frostbite();
        } finally {
            clean();
            faceFresh();
        }
    }

    /** An ice crystal rises where you look; each second it shoots the monster it sees, which is then chilled. */
    private static void sentinel() {
        faceHealed();
        double dmg = Spells.damage(caster, Spell.SENTINEL);
        var zombie = mob(EntityTypes.ZOMBIE, 7, 0);
        float z0 = zombie.getHealth();
        lookDown(4);
        boolean cast = cast(Spell.SENTINEL);
        for (int i = 0; i < FrostSpells.SENTRY_PERIOD; i++) FrostSpells.tickSentries();
        float lost = z0 - zombie.getHealth();
        boolean chilled = FrostSpells.sentryChilled(zombie) && zombie.hasEffect(MobEffects.SLOWNESS);
        // A chilled monster takes 30% more from everything else.
        float more = SpellSchools.incoming(zombie, caster.damageSources().playerAttack(caster), 10);
        for (int i = 0; i < FrostSpells.SENTRY_TICKS; i++) FrostSpells.tickSentries();
        boolean ends = FrostSpells.live("sentries") == 0;
        check(
                "spell_sentinel_shoots_and_chills",
                cast && lost >= dmg - 1e-3 && chilled && Math.abs(more - 13) < 1e-3 && ends,
                "lost " + lost + " of " + dmg + " chilled=" + chilled + " 10 became " + more + " gone=" + ends);
        clean();
    }

    /** The storm slows, freezes and bites what stands in it. */
    private static void blizzard() {
        faceHealed();
        double dmg = Spells.damage(caster, Spell.BLIZZARD);
        var zombie = mob(EntityTypes.ZOMBIE, 5, 0);
        var cow = mob(EntityTypes.COW, 5, 2);
        float z0 = zombie.getHealth(), c0 = cow.getHealth();
        lookDown(5);
        boolean cast = cast(Spell.BLIZZARD);
        for (int i = 0; i < 10; i++) FrostSpells.tickBlizzards();
        float lost = z0 - zombie.getHealth();
        boolean frozen =
                zombie.getTicksFrozen() >= zombie.getTicksRequiredToFreeze() && zombie.hasEffect(MobEffects.SLOWNESS);
        check(
                "spell_blizzard_freezes_and_bites",
                cast && lost >= dmg - 1e-3 && frozen && cow.getHealth() == c0,
                "lost " + lost + " of " + dmg + " frozen=" + frozen);
        clean();
    }

    /** Water under your feet freezes, 5 blocks round. */
    private static void frostwalk() {
        faceFresh();
        BlockPos below = caster.blockPosition().below();
        var was = level.getBlockState(below);
        level.setBlockAndUpdate(below, Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(below.above(), Blocks.AIR.defaultBlockState());
        boolean cast = cast(Spell.FROSTWALK);
        FrostSpells.tickFrost();
        boolean ice = level.getBlockState(below).is(Blocks.FROSTED_ICE);
        level.setBlockAndUpdate(below, was);
        ice &= FrostSpells.FROST_RADIUS == 5;
        check("spell_frostwalk_freezes_water", cast && ice, "ice=" + ice);
        clean();
    }

    /**
     * Frostbite (owner, 2026-11-23): three Sentinel-strength blows bite where two do not, one Frost Nova blow bites at
     * once, the bitten shake (frozen through) and are slowed, a shatter spends it, and a stray, which the cold cannot
     * touch, is spared.
     */
    private static void frostbite() {
        faceFresh();
        var zombie = tough(4, 0);
        Frostbite.add(zombie, caster, 34);
        Frostbite.add(zombie, caster, 34);
        boolean notYet = !Frostbite.bitten(zombie);
        Frostbite.add(zombie, caster, 34);
        boolean bitten = Frostbite.bitten(zombie) && zombie.isFullyFrozen() && zombie.hasEffect(MobEffects.SLOWNESS);
        Frostbite.clear(zombie);
        boolean spent = !Frostbite.bitten(zombie);
        var nova = tough(4, 2);
        Frostbite.add(nova, caster, Frostbite.FULL);
        boolean oneBlow = Frostbite.bitten(nova);
        var stray = mob(EntityTypes.STRAY, 4, -2);
        Frostbite.add(stray, caster, Frostbite.FULL);
        boolean spared = !Frostbite.bitten(stray);
        check(
                "frostbite_builds_and_bites",
                notYet && bitten && spent && oneBlow && spared,
                "two=" + !notYet + " three=" + bitten + " spent=" + spent + " nova=" + oneBlow + " stray=" + !spared);
        Frostbite.clearAll();
        clean();
    }
}
