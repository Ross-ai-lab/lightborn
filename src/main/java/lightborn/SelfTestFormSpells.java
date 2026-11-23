package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import java.util.List;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The form school's spells ({@link FormSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestFormSpells {
    private SelfTestFormSpells() {}

    static void run() {
        try {
            ascension();
            wolf();
            bear();
            spider();
            blaze();
            spirit();
            shrinking();
            gills();
            warden();
        } finally {
            clean();
            faceFresh();
        }
    }

    /** You grow as tall as the space allows (three times in the open) and reach farther, then shrink back. */
    private static void ascension() {
        faceHealed();
        boolean room = FormSpells.roomFor(caster, FormSpells.GIANT_SCALE);
        boolean cast = cast(Spell.ASCENSION);
        double scale = caster.getAttributeValue(Attributes.SCALE);
        double reach = caster.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        double step = caster.getAttributeValue(Attributes.STEP_HEIGHT);
        FormSpells.ASCENTS.put(caster, new FormSpells.Ascent(level.getGameTime(), false));
        FormSpells.tickAscents();
        double back = caster.getAttributeValue(Attributes.SCALE),
                reachBack = caster.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        check(
                "spell_gigantify_grows_then_shrinks",
                cast
                        && (room ? Math.abs(scale - FormSpells.GIANT_SCALE) < 1e-6 : scale >= 2)
                        && reach >= 3 + (scale - 1) * 2
                        && step > 1
                        && Math.abs(back - 1) < 1e-6
                        && reachBack < 3.5,
                "room=" + room + " scale=" + scale + " reach=" + reach + " step=" + step + " after=" + back);
        caster.removeEffect(MobEffects.RESISTANCE);
        clean();
    }

    /** A great wolf: fast, and right-click pounces on the monster looked at and bites it. */
    private static void wolf() {
        faceFresh();
        double dmg = Spells.damage(caster, Spell.WOLF_FORM);
        var zombie = mob(EntityTypes.ZOMBIE, 6, 0);
        float z0 = zombie.getHealth();
        boolean cast = cast(Spell.WOLF_FORM);
        boolean wolf = FormSpells.in(caster, FormSpells.Form.WOLF) && caster.hasEffect(MobEffects.SPEED);
        boolean leapt = FormSpells.power(caster) && FormSpells.shape(caster).prey == zombie;
        caster.setPos(zombie.getX() - 1.5, zombie.getY(), zombie.getZ()); // the leap has landed
        FormSpells.tickForTest(caster);
        float lost = z0 - zombie.getHealth();
        FormSpells.end(caster);
        boolean back = !FormSpells.in(caster, FormSpells.Form.WOLF) && !caster.hasEffect(MobEffects.SPEED);
        check(
                "spell_wolf_form_pounces",
                cast && wolf && leapt && lost >= dmg - 1e-3 && back,
                "wolf=" + wolf + " leapt=" + leapt + " lost " + lost + " of " + dmg + " back=" + back);
        clean();
    }

    /** A great bear: ten more hearts, and right-click roars, striking the monsters near. */
    private static void bear() {
        faceFresh();
        double dmg = Spells.damage(caster, Spell.BEAR_FORM);
        var zombie = mob(EntityTypes.ZOMBIE, 3, 0);
        float z0 = zombie.getHealth();
        boolean cast = cast(Spell.BEAR_FORM);
        double health = caster.getMaxHealth();
        FormSpells.power(caster);
        float lost = z0 - zombie.getHealth();
        FormSpells.end(caster);
        double after = caster.getMaxHealth();
        check(
                "spell_bear_form_roars",
                cast && health >= after + 19.9 && lost >= dmg - 1e-3,
                "max health " + health + " -> " + after + " roar took " + lost + " of " + dmg);
        clean();
    }

    /**
     * A spider climbs walls, and right-click webs the monster looked at; the web holds as long as the spider is a
     * spider (long past the old 4 seconds) and falls, freeing the monster, when the form ends.
     */
    private static void spider() {
        faceFresh();
        var zombie = mob(EntityTypes.ZOMBIE, 6, 0);
        boolean cast = cast(Spell.SPIDER_FORM);
        caster.horizontalCollision = true;
        boolean climbs = FormSpells.climbs(caster);
        caster.horizontalCollision = false;
        FormSpells.power(caster);
        var slow = zombie.getEffect(MobEffects.SLOWNESS);
        boolean webbed = slow != null && slow.getAmplifier() >= 4 && FormSpells.webs() == 1;
        boolean holds = slow != null && slow.getDuration() > 200;
        for (int i = 0; i < 100; i++) FormSpells.tickForms(level.getServer());
        holds &= FormSpells.webs() == 1 && zombie.hasEffect(MobEffects.SLOWNESS);
        boolean night = caster.hasEffect(MobEffects.NIGHT_VISION);
        FormSpells.end(caster);
        FormSpells.tickForms(level.getServer());
        boolean freed = FormSpells.webs() == 0
                && !zombie.hasEffect(MobEffects.SLOWNESS)
                && !zombie.hasEffect(MobEffects.WEAKNESS);
        check(
                "spell_spider_form_climbs_and_webs",
                cast && climbs && webbed && holds && freed && night,
                "climbs=" + climbs + " webbed=" + webbed + " holds=" + holds + " freed=" + freed);
        clean();
    }

    /** A blaze cannot burn, and right-click throws three fireballs. */
    private static void blaze() {
        faceFresh();
        boolean cast = cast(Spell.BLAZE_FORM);
        int before = Spells.liveProjectiles();
        FormSpells.power(caster);
        int thrown = Spells.liveProjectiles() - before;
        float fire = FormSpells.incoming(caster, caster.damageSources().onFire(), 4);
        check(
                "spell_blaze_form_throws_fire",
                cast && thrown == 3 && fire == 0,
                "thrown=" + thrown + " fire harm=" + fire);
        clean();
    }

    /** The spirit walks free (spectator) while the body waits, then returns to the body. */
    private static void spirit() {
        faceFresh();
        Vec3 start = caster.position();
        boolean cast = cast(Spell.SPIRIT_WALK);
        boolean out = caster.isSpectator() && caster.hasAttached(FormSpells.SPIRIT_HOME);
        var bodies = level.getEntitiesOfClass(
                Mannequin.class,
                new AABB(caster.blockPosition()).inflate(3),
                m -> m.entityTags().contains(FormSpells.BODY_TAG));
        caster.setPos(start.x + 6, start.y + 3, start.z);
        FormSpells.end(caster);
        boolean home = !caster.isSpectator()
                && caster.position().distanceTo(start) < .1
                && !caster.hasAttached(FormSpells.SPIRIT_HOME);
        boolean bodyGone = bodies.size() == 1 && bodies.get(0).isRemoved();
        check(
                "spell_spirit_walk_leaves_and_returns",
                cast && out && home && bodyGone,
                "out=" + out + " bodies=" + bodies.size() + " home=" + home + " body gone=" + bodyGone);
        clean();
    }

    /** Six times smaller, then full size again once there is room. */
    private static void shrinking() {
        faceFresh();
        boolean cast = cast(Spell.SHRINKING);
        double small = caster.getAttributeValue(Attributes.SCALE);
        FormSpells.SMALL.put(caster, new FormSpells.Small(level.getGameTime(), false, false, List.of()));
        FormSpells.tickSmall();
        double back = caster.getAttributeValue(Attributes.SCALE);
        check(
                "spell_shrinking_shrinks_then_grows",
                cast && Math.abs(small - 1 / 6.0) < 1e-6 && Math.abs(back - 1) < 1e-6,
                "small=" + small + " back=" + back);
        clean();
    }

    private static void gills() {
        faceFresh();
        boolean cast = cast(Spell.GILLS);
        check(
                "spell_gills_breathe_water",
                cast && caster.hasEffect(MobEffects.CONDUIT_POWER) && caster.hasEffect(MobEffects.DOLPHINS_GRACE),
                null);
        clean();
    }

    /** A warden: twenty more hearts, and right-click's sonic boom strikes the monster ahead, not the one behind. */
    private static void warden() {
        faceFresh();
        double dmg = Spells.damage(caster, Spell.WARDEN_FORM);
        var ahead = mob(EntityTypes.ZOMBIE, 10, 0);
        var behind = mob(EntityTypes.ZOMBIE, -4, 0);
        float a0 = ahead.getHealth(), b0 = behind.getHealth();
        double before = caster.getMaxHealth();
        boolean cast = cast(Spell.WARDEN_FORM);
        boolean warden = FormSpells.in(caster, FormSpells.Form.WARDEN) && caster.hasEffect(MobEffects.RESISTANCE);
        double health = caster.getMaxHealth();
        boolean boomed = FormSpells.power(caster);
        float lost = a0 - ahead.getHealth();
        FormSpells.end(caster);
        double after = caster.getMaxHealth();
        check(
                "spell_warden_form_sonic_boom",
                cast
                        && warden
                        && boomed
                        && health >= before + 39.9
                        && Math.abs(after - before) < 1e-6
                        && lost >= Math.min(dmg, a0) - 1e-3
                        && behind.getHealth() == b0,
                "warden=" + warden + " max health " + before + " -> " + health + " -> " + after + " lost " + lost
                        + " of " + dmg + " behind lost " + (b0 - behind.getHealth()));
        clean();
    }
}
