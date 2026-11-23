package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The arcane school's spells ({@link ArcaneSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestArcaneSpells {
    private SelfTestArcaneSpells() {}

    static void run() {
        try {
            rewind();
            twincast();
            rune();
            wellspring();
            timeStop();
        } finally {
            clean();
            faceFresh();
        }
    }

    /** Rewind takes the caster back to the marked spot, with the health they had then. */
    private static void rewind() {
        face();
        Vec3 marked = caster.position();
        float hp = caster.getHealth();
        boolean cast = cast(Spell.REWIND);
        caster.setPos(marked.add(5, 0, 0));
        caster.setHealth(hp / 2);
        for (int i = 0; i <= ArcaneSpells.REWIND_TICKS; i++) ArcaneSpells.tickMarks();
        double off = caster.position().distanceTo(marked);
        float now = caster.getHealth();
        caster.setHealth(hp);
        check(
                "spell_rewind_takes_you_back",
                cast && off < .1 && now == hp && ArcaneSpells.live("marks") == 0,
                "off by " + String.format("%.2f", off) + " health " + now + "/" + hp);

        // A killing blow inside the 10 seconds pulls you back at once instead (owner, 2026-11-22).
        face();
        marked = caster.position();
        boolean again = cast(Spell.REWIND);
        caster.setPos(marked.add(5, 0, 0));
        boolean lives = !ArcaneSpells.allowDeath(caster, level.damageSources().generic(), 100);
        double saved = caster.position().distanceTo(marked);
        boolean spent = ArcaneSpells.live("marks") == 0;
        boolean second = ArcaneSpells.allowDeath(caster, level.damageSources().generic(), 100);
        caster.setHealth(hp);
        check(
                "spell_rewind_saves_you_from_death",
                again && lives && saved < .1 && spent && second,
                "lives=" + lives + " off by " + String.format("%.2f", saved) + " spent=" + spent
                        + " a second death allowed=" + second);
        face();
    }

    /** Twincast casts the next spell a second time a moment later; a spell after that is cast once. */
    private static void twincast() {
        face();
        boolean armed = cast(Spell.TWINCAST) && ArcaneSpells.twinned(caster);
        int before = Spells.liveProjectiles();
        cast(Spell.ARCANE_BOLT);
        int once = Spells.liveProjectiles() - before;
        boolean spent = !ArcaneSpells.twinned(caster);
        for (int i = 0; i < ArcaneSpells.TWIN_DELAY; i++) ArcaneSpells.tickTwins();
        int twice = Spells.liveProjectiles() - before;
        cast(Spell.ARCANE_BOLT);
        for (int i = 0; i < ArcaneSpells.TWIN_DELAY; i++) ArcaneSpells.tickTwins();
        int third = Spells.liveProjectiles() - before;
        check(
                "spell_twincast_twins_the_next_spell",
                armed && once == 1 && spent && twice == 2 && third == 3,
                "bolts " + once + " then " + twice + " then " + third);
    }

    /** A rune drawn on the ground waits; a monster on it sets it off once it is armed, and it is spent. */
    private static void rune() {
        face();
        double dmg = Spells.damage(caster, Spell.RUNE_SNARE);
        lookDown(4);
        boolean drawn = cast(Spell.RUNE_SNARE);
        int runes = ArcaneSpells.live("runes");
        var zombie = mob(EntityTypes.ZOMBIE, 4, 0);
        float z0 = zombie.getHealth();
        ArcaneSpells.tickRunes();
        boolean waited = zombie.getHealth() == z0; // not armed yet
        for (int i = 0; i < ArcaneSpells.RUNE_ARM; i++) ArcaneSpells.tickRunes();
        float lost = z0 - zombie.getHealth();
        boolean dazed = SpellKit.dazed(zombie) && zombie.hasEffect(MobEffects.BLINDNESS);
        boolean spent = ArcaneSpells.live("runes") == 0;
        clean();
        // A trap with nothing to set it off waits on, past the old two minutes (owner, 2026-11-22: no time limit).
        face();
        lookDown(4);
        cast(Spell.RUNE_SNARE);
        for (int i = 0; i < 2600; i++) ArcaneSpells.tickRunes();
        boolean waits = ArcaneSpells.live("runes") == 1;
        check(
                "spell_trap_bursts_underfoot_and_dazes",
                drawn && runes == 1 && waited && lost >= dmg - 1e-3 && spent && dazed && waits,
                "runes=" + runes + " waited=" + waited + " lost " + lost + " of " + dmg + " dazed=" + dazed
                        + " still waiting after 130 s=" + waits);
        clean();
    }

    /** While it lasts, a spell costs no mana. */
    private static void wellspring() {
        faceHealed();
        boolean cast = cast(Spell.WELLSPRING);
        float before = Progression.mana(caster);
        caster.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.TOMES.get(Spell.FIREBALL)));
        boolean fire = Spells.cast(caster, caster.getOffhandItem());
        float after = Progression.mana(caster);
        check(
                "spell_freedom_casts_free",
                cast && fire && Math.abs(before - after) < 1e-3 && ArcaneSpells.flowing(caster),
                "mana " + before + " -> " + after);
        clean();
    }

    /** Monsters round you are held still for the time, then move again. */
    private static void timeStop() {
        faceHealed();
        var zombie = mob(EntityTypes.ZOMBIE, 6, 0);
        zombie.setNoAi(false);
        var arrow = EntityTypes.ARROW.create(level, EntitySpawnReason.COMMAND);
        arrow.setPos(caster.getX() + 4, caster.getY() + 3, caster.getZ());
        arrow.setDeltaMovement(new Vec3(-1, 0, 0));
        level.addFreshEntity(arrow);
        boolean cast = cast(Spell.TIME_STOP);
        boolean held = zombie.isNoAi() && ArcaneSpells.held(zombie) && ArcaneSpells.held(arrow);
        for (int i = 0; i <= ArcaneSpells.STOP_TICKS; i++) ArcaneSpells.tickHeld();
        boolean free = !zombie.isNoAi() && ArcaneSpells.live("held") == 0 && arrow.getDeltaMovement().x < -.5;
        arrow.discard();
        check("spell_time_stop_holds_then_frees", cast && held && free, "held=" + held + " free=" + free);
        clean();
    }
}
