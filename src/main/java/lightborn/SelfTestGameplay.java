package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

/**
 * The new-feature checks that are not spells: fishing's catch, Light Butterflies, fountain guards, Night
 * Vision brewing and the gear trees' one-aspect rule.
 */
final class SelfTestGameplay {
    private SelfTestGameplay() {}

    static void run() {
        try {
            fishing();
            butterfly();
            guards();
            brewing();
            gearTrees();
        } finally {
            clean();
            faceFresh();
        }
    }

    /**
     * Fishing pays more (owner, 2026-11-22): over many catches, Sparks come up about one cast in five, and treasure,
     * gear and tomes each now and then, every stack real.
     */
    private static void fishing() {
        final int n = 3000;
        int sparks = 0, treasure = 0, gear = 0, tomes = 0, empty = 0;
        var random = net.minecraft.util.RandomSource.create(20261122L);
        for (int i = 0; i < n; i++)
            for (var r : Fishing.rewards(caster, caster.position(), 0, random, false)) {
                var s = r.stack();
                if (s.isEmpty()) empty++;
                else if (s.is(ModItems.SPARK)) sparks++;
                else if (s.getItem() instanceof TomeItem) tomes++;
                else if (r.rarity() != null) gear++;
                else treasure++;
            }
        double share = sparks / (double) n;
        // The hook into vanilla fishing is woven in: loading the bobber applies the mixin (a missing target would
        // crash here), and its handler is on the class.
        var bobber = EntityTypes.FISHING_BOBBER.create(level, EntitySpawnReason.COMMAND);
        boolean hooked = bobber != null
                && java.util.Arrays.stream(bobber.getClass().getDeclaredMethods())
                        .anyMatch(m -> m.getName().contains("lightborn$extras"));
        if (bobber != null) bobber.discard();
        check(
                "fishing_brings_up_more",
                share > .15 && share < .25 && treasure > 0 && gear > 0 && tomes > 0 && empty == 0 && hooked,
                String.format(
                        "sparks %.1f%%, treasure stacks %d, gear %d, tomes %d, empty %d of %d casts, hooked=%s",
                        share * 100, treasure, gear, tomes, empty, n, hooked));
    }

    /** A Light Butterfly caught by a player's blow bursts and leaves 1 to 3 Sparks, and nothing else. */
    private static void butterfly() {
        faceFresh();
        var b = ModEntities.LIGHT_BUTTERFLY.create(level, EntitySpawnReason.COMMAND);
        b.snapTo(caster.getX() + 2, caster.getY() + 1, caster.getZ(), 0, 0);
        level.addFreshEntity(b);
        var around = new net.minecraft.world.phys.AABB(b.blockPosition()).inflate(3);
        for (var i : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, around)) i.discard();
        b.hurtServer(level, caster.damageSources().playerAttack(caster), 1);
        var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, around);
        int sparks = drops.stream()
                .filter(i -> i.getItem().is(ModItems.SPARK))
                .mapToInt(i -> i.getItem().getCount())
                .sum();
        check(
                "light_butterfly_pays_sparks",
                b.isRemoved() && sparks >= 1 && sparks <= 3 && drops.size() == 1,
                "caught=" + b.isRemoved() + " sparks=" + sparks + " drops=" + drops.size());
        drops.forEach(net.minecraft.world.entity.Entity::discard);
    }

    /**
     * A fountain's guards stay until slain (never despawned, never hidden by Spawn Animations), and guards that vanish
     * unslain wake again on the next approach (owner, 2026-11-23: "near dark fountain but no enemies appeared").
     */
    private static void guards() {
        faceFresh();
        var core = caster.blockPosition().offset(12, 0, 0);
        var nest = new DarkNests.Nest(core, core, caster.getUUID(), 0, LightShop.day(level));
        DarkNests.guards(level, caster, nest, 1);
        String tag = DarkNests.guardOf(core);
        var woke = level.getEntities(
                net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),
                m -> m.isAlive() && m.entityTags().contains(tag));
        boolean kept = !woke.isEmpty()
                && woke.stream()
                        .allMatch(
                                m -> m.isPersistenceRequired() && m.entityTags().contains("ts.sa.exclude"));
        boolean standing = !DarkNests.vanished(level, nest);
        woke.forEach(Mob::discard);
        boolean again = DarkNests.vanished(level, nest);
        // Slain by a player, the set is done and does not wake again, and the count is saved with the world.
        DarkNests.guards(level, caster, nest, 1);
        var second = level.getEntities(
                net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),
                m -> m.isAlive() && m.entityTags().contains(tag));
        for (var m : second) m.hurtServer(level, caster.damageSources().playerAttack(caster), 10_000);
        var saved =
                level.getAttachedOrElse(DarkNests.GUARDS, java.util.Map.of()).get(Long.toString(core.asLong()));
        boolean done = !second.isEmpty() && !DarkNests.vanished(level, nest);
        for (var i : level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(core).inflate(8)))
            i.discard();
        check(
                "fountain_guards_stay_and_wake_again_if_lost",
                kept && standing && again && done && saved != null && saved.get(1) >= second.size(),
                "woke=" + woke.size() + " kept=" + kept + " standing=" + standing + " again=" + again + " done=" + done
                        + " saved=" + saved);
    }

    /**
     * Night Vision costs a golden apple a bottle, the golden carrot no longer brews it but extends it, one a bottle
     * (owner, 2026-11-23).
     */
    private static void brewing() {
        var pos = caster.blockPosition().offset(-3, 0, 3);
        level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.BREWING_STAND.defaultBlockState());
        var stand = (net.minecraft.world.level.block.entity.BrewingStandBlockEntity) level.getBlockEntity(pos);
        java.util.function.Function<net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion>, ItemStack>
                bottle = p -> net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                net.minecraft.world.item.Items.POTION, p);
        var awkward = net.minecraft.world.item.alchemy.Potions.AWKWARD;
        var nv = net.minecraft.world.item.alchemy.Potions.NIGHT_VISION;
        for (int i = 0; i < 3; i++) stand.setItem(i, bottle.apply(awkward));
        stand.setItem(3, new ItemStack(net.minecraft.world.item.Items.GOLDEN_APPLE, 2));
        lightborn.mixin.BrewingStandInvoker.lightborn$doBrew(level, pos, stand);
        int turned = 0, left = 0;
        for (int i = 0; i < 3; i++) {
            var c = stand.getItem(i).get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (c != null && c.is(nv)) turned++;
            if (c != null && c.is(awkward)) left++;
        }
        boolean spent = stand.getItem(3).isEmpty();
        var carrot = level.recipeAccess()
                .getRecipeFor(
                        net.minecraft.world.item.crafting.RecipeType.BREWING,
                        new net.minecraft.world.item.crafting.BrewingInput(
                                bottle.apply(awkward), new ItemStack(net.minecraft.world.item.Items.GOLDEN_CARROT)),
                        level)
                .isPresent();
        var extend = level.recipeAccess()
                .getRecipeFor(
                        net.minecraft.world.item.crafting.RecipeType.BREWING,
                        new net.minecraft.world.item.crafting.BrewingInput(
                                bottle.apply(nv), new ItemStack(net.minecraft.world.item.Items.GOLDEN_CARROT)),
                        level)
                .isPresent();
        level.removeBlock(pos, false);
        check(
                "night_vision_brews_with_a_golden_apple_a_bottle",
                turned == 2 && left == 1 && spent && !carrot && extend,
                "turned=" + turned + " left=" + left + " spent=" + spent + " carrot=" + carrot + " extend=" + extend);
    }

    /**
     * No gear tree raises a stat in both tier I and tier II, and the staff's tree is all attack: no health, armour,
     * toughness or knockback (owner, 2026-11-23).
     */
    private static void gearTrees() {
        var bad = new ArrayList<String>();
        for (int k = 0; k <= LightGearTree.TRIDENT; k++) {
            var t = LightGearTree.tiers(k);
            for (var a : t.get(0))
                for (var b : t.get(1))
                    if (!a.perk() && !b.perk() && a.attr().equals(b.attr()))
                        bad.add(k + ":" + a.name() + "/" + b.name());
        }
        var defence = List.of(
                Attributes.MAX_HEALTH, Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS, Attributes.KNOCKBACK_RESISTANCE);
        for (var tier : LightGearTree.tiers(LightGearTree.STAFF))
            for (var n : tier) if (!n.perk() && defence.contains(n.attr())) bad.add("staff:" + n.name());
        check("gear_trees_raise_each_stat_once", bad.isEmpty(), "repeats=" + bad);
    }
}
