package lightborn.client;

import lightborn.LightGear;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The Light Sword, and any weapon Lightbrand burns on, overflows with light while held (owner, 2026-11-17): motes of light spill off the blade and drift
 * down, with now and then a golden glint, in first person beside the view and on anyone seen holding it. The blade
 * itself shines full-bright through its glow card (tools/make_light_arms.py).
 */
public final class LightArmsView {
    private LightArmsView() {}

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.isPaused() || LightGear.ITEMS.size() < 2) return;
        var sword = LightGear.ITEMS.get(1);
        var rnd = mc.level.getRandom();
        for (Player p : mc.level.players()) {
            // Lightbrand (owner, 2026-11-22): any weapon in hand spills light as the Light Sword does.
            boolean branded = Boolean.TRUE.equals(p.getAttached(lightborn.ModData.BRANDED))
                    && lightborn.LightSpells.weapon(p.getMainHandItem());
            boolean main = p.getMainHandItem().is(sword) || branded;
            if (!main && !p.getOffhandItem().is(sword)) continue;
            if (p.distanceToSqr(mc.player) > 48 * 48 || p.isInvisible()) continue;
            boolean firstPerson = p == mc.player && mc.options.getCameraType() == CameraType.FIRST_PERSON;
            // Which side the blade is on: the main hand's arm, or the other one for an off-hand sword.
            boolean right = (p.getMainArm() == HumanoidArm.RIGHT) == main;
            Vec3 at = firstPerson ? firstPersonBlade(p, right, rnd.nextFloat()) : worldBlade(p, right, rnd.nextFloat());
            double s = firstPerson ? .05 : .2;
            // Fine sparks of ivory, gold and teal, drifting down off the blade: small enough to read as light
            // spilling over even this close to the eye; now and then a bright mote away from the eye.
            int colour = switch (rnd.nextInt(3)) {
                case 0 -> 0xFFF6D8;
                case 1 -> 0xF4C860;
                default -> 0x9AF4EC;
            };
            mc.level.addParticle(
                    new net.minecraft.core.particles.DustParticleOptions(
                            colour, (firstPerson ? .2f : .3f) + rnd.nextFloat() * .15f),
                    at.x + (rnd.nextDouble() - .5) * s,
                    at.y + (rnd.nextDouble() - .5) * s,
                    at.z + (rnd.nextDouble() - .5) * s,
                    0,
                    -.03 - rnd.nextDouble() * .03,
                    0);
            if (!firstPerson && rnd.nextInt(5) == 0)
                mc.level.addParticle(ParticleTypes.END_ROD, at.x, at.y, at.z, 0, -.01, 0);
        }
    }

    /** A point on the blade as the holder's own view shows it: low on the hand's side, angled up and away. */
    private static Vec3 firstPersonBlade(Player p, boolean right, float t) {
        var look = p.getViewVector(1);
        var up = new Vec3(0, 1, 0);
        var side = look.cross(up).normalize().scale(right ? 1 : -1);
        var trueUp = side.cross(look).normalize().scale(right ? 1 : -1);
        // Where the held sprite's blade sits on screen: low at the hand's edge, rising toward the middle.
        var grip = p.getEyePosition(1).add(look.scale(.8)).add(side.scale(.62)).add(trueUp.scale(-.5));
        return grip.add(look.scale(.1 * t)).add(trueUp.scale(.45 * t)).add(side.scale(.08 * t));
    }

    /** A point on the blade held at the holder's side, as others (or the holder in third person) see it. */
    private static Vec3 worldBlade(Player p, boolean right, float t) {
        double yaw = Math.toRadians(p.yBodyRot);
        var forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        var side = new Vec3(-forward.z, 0, forward.x).scale(right ? 1 : -1);
        var grip = p.position().add(side.scale(.38)).add(forward.scale(.25)).add(0, p.isCrouching() ? .6 : .75, 0);
        return grip.add(forward.scale(.55 * t)).add(0, .35 * t, 0);
    }
}
