package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The light school's spells ({@link LightSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestLightSpells {
    private SelfTestLightSpells() {}

    static void run() {
        try {
            sunbeam();
            wisps();
            haloDisc();
            starfall();
            lightBridge();
            ward();
            steed();
            lightbrand();
            bladeRing();
            dawnbreak();
            banish();
            splinter();
        } finally {
            clean();
            faceFresh();
        }
    }

    /** The beam pierces both, the undead one for double. */
    private static void sunbeam() {
        face();
        double dmg = Spells.damage(caster, Spell.SUNBEAM);
        var zombie = mob(EntityTypes.ZOMBIE, 5, 0);
        var cow = mob(EntityTypes.COW, 8, 0);
        float z0 = zombie.getHealth(), c0 = cow.getHealth();
        boolean beamed = cast(Spell.SUNBEAM);
        double zLost = z0 - zombie.getHealth(), cLost = c0 - cow.getHealth();
        check(
                "spell_sunbeam_pierces_and_burns_the_undead",
                beamed && zLost >= 2 * dmg - 1e-3 && cLost >= dmg - 1e-3,
                "zombie -" + zLost + " cow -" + cLost + " (pulse " + dmg + ")");
        clean();
    }

    /** Wisps find their prey and leave the cow be. */
    private static void wisps() {
        face();
        var zombie = mob(EntityTypes.ZOMBIE, 5, 0);
        var cow = mob(EntityTypes.COW, 8, 0);
        float z0 = zombie.getHealth(), cowBefore = cow.getHealth();
        double wispDmg = Spells.damage(caster, Spell.WISPS);
        boolean released = cast(Spell.WISPS);
        int out = LightSpells.live("wisps");
        for (int i = 0; i < 100; i++) LightSpells.tickWisps();
        int still = LightSpells.live("wisps");
        float lost = z0 - zombie.getHealth();
        // Five wisps on one zombie (the cow is no monster, so they leave it be); they keep striking, more blows than
        // there are wisps, and are all still out after five seconds.
        check(
                "spell_wisps_seek_monsters",
                released
                        && out == LightSpells.WISP_COUNT
                        && still == LightSpells.WISP_COUNT
                        && lost > LightSpells.WISP_COUNT * wispDmg + 1e-3
                        && cow.getHealth() == cowBefore,
                "out=" + out + " still out=" + still + " zombie lost " + lost + " (" + wispDmg + " a strike)");
        clean();
    }

    /** The disc cuts going and coming. */
    private static void haloDisc() {
        face();
        var zombie = mob(EntityTypes.ZOMBIE, 5, 0);
        mob(EntityTypes.COW, 8, 0);
        float z0 = zombie.getHealth();
        double discDmg = Spells.damage(caster, Spell.HALO_DISC);
        boolean thrown = cast(Spell.HALO_DISC);
        for (int i = 0; i < 120 && LightSpells.live("discs") > 0; i++) LightSpells.tickDiscs();
        double cut = z0 - zombie.getHealth();
        check(
                "spell_halo_disc_cuts_both_ways_and_returns",
                thrown && LightSpells.live("discs") == 0 && cut >= 2 * discDmg - 1e-3,
                "cut " + cut + " of " + discDmg + " each way");
        clean();
    }

    /** Stars fall over the aimed ground and burst where they land. */
    private static void starfall() {
        face();
        var zombie = mob(EntityTypes.ZOMBIE, 6, 0);
        caster.setXRot(30f); // at the ground ahead
        boolean cast = cast(Spell.STARFALL);
        caster.setXRot(0f);
        float before = zombie.getHealth();
        LightSpells.launch(caster, zombie.position(), Spells.damage(caster, Spell.STARFALL), LightSpells.STAR_BURST);
        for (int i = 0; i < LightSpells.STAR_FALL; i++) LightSpells.tickStars();
        check("spell_starfall_rains_stars", cast && zombie.getHealth() < before, zombie.getHealth() + "/" + before);
    }

    /** The bridge runs over a drop and fades by its own tick. */
    private static void lightBridge() {
        face();
        var ground = caster.blockPosition();
        LightSpells.instant = true; // the bridge's steps at once, not over a few ticks
        // On a pillar three blocks up, so the path runs out over open air.
        var pillar = ground.above(2);
        BlockState was = level.getBlockState(pillar);
        level.setBlockAndUpdate(pillar, Blocks.STONE.defaultBlockState());
        caster.setPos(pillar.getX() + .5, pillar.getY() + 1, pillar.getZ() + .5);
        boolean bridged = cast(Spell.LIGHT_BRIDGE);
        var path = conjured(pillar, LightSpells.BRIDGE_LENGTH + 2);
        boolean light = path.stream().allMatch(c -> level.getBlockState(c).is(ConjuredBlock.LIGHT));
        for (var c : path) level.getBlockState(c).tick(level, c, level.getRandom());
        level.setBlockAndUpdate(pillar, was);
        check(
                "spell_light_bridge_spans_the_air",
                bridged
                        && path.size() >= 10
                        && light
                        && conjured(pillar, LightSpells.BRIDGE_LENGTH + 2).isEmpty(),
                "path=" + path.size());
        clean();
        face();
    }

    /** The ward thrusts a monster out and stops a monster's shot. */
    private static void ward() {
        face();
        var zombie = mob(EntityTypes.ZOMBIE, 2, 0);
        var skeleton = mob(EntityTypes.SKELETON, 12, 0);
        boolean domed = cast(Spell.SANCTUARY);
        var arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(
                level, caster.getX() + 2, caster.getY() + 1, caster.getZ(), new ItemStack(Items.ARROW), null);
        arrow.setOwner(skeleton);
        level.addFreshEntity(arrow);
        LightSpells.tickDomes();
        boolean thrust = zombie.position().distanceTo(caster.position()) >= LightSpells.DOME_RADIUS;
        check(
                "spell_sanctuary_keeps_monsters_and_shots_out",
                domed && thrust && arrow.isRemoved(),
                "zombie at " + String.format("%.1f", zombie.position().distanceTo(caster.position())) + " arrow gone="
                        + arrow.isRemoved());
        clean();
    }

    /**
     * The steed comes tame and saddled, as swift as the swiftest horse; a player's blow does nothing to it; left alone,
     * it fades at once and drops nothing (the test's fake player can never ride, so riding is checked in the visual
     * test).
     */
    private static void steed() {
        face();
        boolean called = cast(Spell.SPIRIT_STEED);
        var horse = LightSpells.steedOf(caster);
        boolean ready = horse != null
                && !horse.isRemoved()
                && horse.level().getEntity(horse.getId()) == horse // still in the world after it was added
                && horse.isTamed()
                && horse.isSaddled()
                && horse.getOwner() == caster
                && horse.getItemBySlot(EquipmentSlot.SADDLE).is(Items.SADDLE)
                && Math.abs(horse.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)
                                - .3375)
                        < 1e-6;
        float hp = horse == null ? 0 : horse.getHealth();
        if (horse != null) horse.hurtServer(level, caster.damageSources().playerAttack(caster), 6);
        boolean unhurt = horse != null && horse.getHealth() == hp;
        for (int i = 0; i <= LightSpells.STEED_IDLE + 1; i++) LightSpells.tickSteeds();
        boolean faded = horse != null && horse.isRemoved() && LightSpells.live("steeds") == 0;
        int saddles = level.getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        new AABB(caster.blockPosition()).inflate(6),
                        e -> e.getItem().is(Items.SADDLE))
                .size();
        check(
                "spell_light_steed_comes_and_fades",
                called && ready && unhurt && faded && saddles == 0,
                "ready=" + ready + " unhurt=" + unhurt + " faded=" + faded + " saddles=" + saddles);
    }

    /** Lightbrand adds its light to the caster's blow and burns. */
    private static void lightbrand() {
        face();
        double brand = Spells.damage(caster, Spell.LIGHTBRAND);
        var zombie = mob(EntityTypes.ZOMBIE, 2, 0);
        float z0 = zombie.getHealth();
        boolean lit = cast(Spell.LIGHTBRAND);
        zombie.hurtServer(level, level.damageSources().playerAttack(caster), 1f);
        float lost = z0 - zombie.getHealth();
        boolean burning = zombie.getRemainingFireTicks() > 0;
        boolean glows = Boolean.TRUE.equals(caster.getAttached(ModData.BRANDED));
        check(
                "spell_lightbrand_burns_your_blows",
                lit && LightSpells.branded(caster) && glows && lost >= brand + .5 && burning,
                "lost " + lost + " (brand " + brand + ") burning=" + burning + " glow flag=" + glows);
        clean();
    }

    /** The ring cuts a monster beside you and spares the cow. */
    private static void bladeRing() {
        face();
        double cut = Spells.damage(caster, Spell.BLADE_RING);
        var zombie = mob(EntityTypes.ZOMBIE, 2, 0);
        var cow = mob(EntityTypes.COW, -2, 0);
        float z0 = zombie.getHealth(), c0 = cow.getHealth();
        boolean ring = cast(Spell.BLADE_RING);
        LightSpells.tickRings();
        float lost = z0 - zombie.getHealth();
        check(
                "spell_blade_ring_guards_you",
                ring && lost >= cut - 1e-3 && cow.getHealth() == c0,
                "zombie -" + lost + " (cut " + cut + ") cow -" + (c0 - cow.getHealth()));
        clean();
    }

    /** After the gathering, the sun bursts: the zombie is struck and burns. */
    private static void dawnbreak() {
        face();
        double dmg = Spells.damage(caster, Spell.DAWNBREAK);
        var zombie = mob(EntityTypes.ZOMBIE, 6, 0);
        float z0 = zombie.getHealth();
        boolean cast = cast(Spell.DAWNBREAK);
        LightSpells.tickDawns();
        boolean gathering = zombie.getHealth() == z0;
        for (int i = 0; i < LightSpells.DAWN_GATHER; i++) LightSpells.tickDawns();
        float lost = z0 - zombie.getHealth();
        boolean burning = zombie.getRemainingFireTicks() > 0;
        check(
                "spell_dawnbreak_bursts_after_gathering",
                cast && gathering && lost >= Math.min(dmg, z0) - 1e-3 && burning,
                "lost " + lost + " of " + dmg + " burning=" + burning);
        clean();
    }

    /** One huge blow to the one monster looked at: it is not killed outright, and its neighbour is untouched. */
    private static void banish() {
        faceHealed();
        double dmg = Spells.damage(caster, Spell.BANISH);
        var ravager = mob(EntityTypes.RAVAGER, 7, 0);
        var near = mob(EntityTypes.ZOMBIE, 7, 2);
        float r0 = ravager.getHealth(), n0 = near.getHealth();
        boolean cast = cast(Spell.BANISH);
        float lost = r0 - ravager.getHealth();
        check(
                "spell_smite_strikes_one_hard",
                cast && ravager.isAlive() && lost >= dmg * .5 && near.getHealth() == n0,
                "lost " + lost + " of " + dmg + " alive=" + ravager.isAlive() + " neighbour lost "
                        + (n0 - near.getHealth()));
        clean();
    }

    private static void splinter() {
        faceFresh();
        double dmg = Spells.damage(caster, Spell.SPLINTER);
        var zombie = mob(EntityTypes.ZOMBIE, 5, 0);
        float z0 = zombie.getHealth();
        int before = Spells.liveProjectiles();
        boolean cast = cast(Spell.SPLINTER);
        int flying = Spells.liveProjectiles() - before;
        for (int i = 0; i < 10; i++) Spells.tickProjectiles();
        float lost = z0 - zombie.getHealth();
        check(
                "spell_splinter_fans_out",
                cast && flying == LightSpells.SPLINTERS && lost >= dmg - 1e-3,
                "splinters=" + flying + " lost " + lost + " of " + dmg);
        clean();
    }
}
