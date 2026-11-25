package lightborn;

import static lightborn.SelfTest.ANIMALS;
import static lightborn.SelfTest.DUMMIES;
import static lightborn.SelfTest.at;
import static lightborn.SelfTest.base;
import static lightborn.SelfTest.castOk;
import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.dummy;
import static lightborn.SelfTest.face;
import static lightborn.SelfTest.finish;
import static lightborn.SelfTest.hurt;
import static lightborn.SelfTest.level;
import static lightborn.SelfTestCollection.testCollection;
import static lightborn.SelfTestMonsters.husk;
import static lightborn.SelfTestMonsters.testMonsterKinds;
import static lightborn.SelfTestMonsters.testZombieKinds;
import static lightborn.SelfTestWorld.loose;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Self-test checks for every spell tome: attack, utility, the second shelf, darkness and rain growth. */
final class SelfTestSpells {
    private SelfTestSpells() {}

    /** Puts a fresh tome of {@code spell} in the caster's off hand and casts it, the way a right-click with a staff does. */
    private static boolean castTome(Spell spell) {
        caster.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.TOMES.get(spell)));
        return Spells.cast(caster, caster.getOffhandItem());
    }

    private static Spell stoneSlot(net.minecraft.world.inventory.Slot slot, int i) {
        return TomeItem.spellOf(Spellstone.tomes(slot.getItem()).get(i));
    }

    /**
     * The Spellstone fills like a bundle (owner, 2026-11-18), through the inventory's own click handling: a tome
     * clicked onto it fills the first free slot, a picked slot takes the next one and swaps out what it held, a
     * right-click takes the picked tome out, and anything that is not a tome swaps with the stone as usual.
     */
    private static void testSpellstoneBag() {
        var menu = caster.inventoryMenu;
        int at = 9; // the first main-inventory slot of the player's own menu
        var slot = menu.getSlot(at);
        var before = slot.getItem().copy();
        var tome = (java.util.function.Function<Spell, ItemStack>) s -> new ItemStack(ModItems.TOMES.get(s));
        java.util.function.IntConsumer click =
                button -> menu.clicked(at, button, net.minecraft.world.inventory.ContainerInput.PICKUP, caster);
        slot.set(new ItemStack(Spellstone.ITEM));
        Spellstone.pick(caster, at, -1);
        menu.setCarried(tome.apply(Spell.FIREBALL));
        click.accept(0);
        boolean first = menu.getCarried().isEmpty() && stoneSlot(slot, 0) == Spell.FIREBALL;
        Spellstone.pick(caster, at, 4);
        menu.setCarried(tome.apply(Spell.ARCANE_BOLT));
        click.accept(0);
        boolean picked =
                menu.getCarried().isEmpty() && stoneSlot(slot, 4) == Spell.ARCANE_BOLT && stoneSlot(slot, 1) == null;
        Spellstone.pick(caster, at, 0);
        menu.setCarried(tome.apply(Spell.TAUNT));
        click.accept(0);
        boolean swapped = TomeItem.spellOf(menu.getCarried()) == Spell.FIREBALL && stoneSlot(slot, 0) == Spell.TAUNT;
        menu.setCarried(ItemStack.EMPTY);
        Spellstone.pick(caster, at, 4);
        click.accept(1);
        boolean out = TomeItem.spellOf(menu.getCarried()) == Spell.ARCANE_BOLT && stoneSlot(slot, 4) == null;
        Spellstone.pick(caster, at, -1);
        menu.setCarried(new ItemStack(Items.TORCH));
        click.accept(0);
        boolean torch = Spellstone.is(menu.getCarried()) && slot.getItem().is(Items.TORCH);
        menu.setCarried(ItemStack.EMPTY);
        slot.set(before);
        check(
                "spellstone_fills_like_a_bag",
                first && picked && swapped && out && torch,
                "first=" + first + " picked=" + picked + " swapped=" + swapped + " out=" + out + " torch=" + torch);
    }

    private static void testArcaneEcho() {
        var tome = new ItemStack(ModItems.TOMES.get(Spell.ARCANE_BOLT));
        SpellUpgrades.write(tome, new int[] {0, 0, 1});
        int first = Spells.PROJECTILES.size();
        var fired = new java.util.ArrayList<Integer>();
        for (int cast = 1; cast <= 7; cast++) {
            int before = Spells.PROJECTILES.size();
            var source = tome;
            CastContext.perform(
                    caster,
                    Spell.ARCANE_BOLT,
                    source,
                    5,
                    () -> ArcaneSpells.arcaneBolt(level, caster, Spell.ARCANE_BOLT, 5));
            fired.add(Spells.PROJECTILES.size() - before);
            if (cast == 5) {
                int echo = Spells.PROJECTILES.size();
                CastContext.perform(
                        caster,
                        Spell.ARCANE_BOLT,
                        tome.copy(),
                        5,
                        true,
                        () -> ArcaneSpells.arcaneBolt(level, caster, Spell.ARCANE_BOLT, 5));
                check("arcane_echo_ignores_twincast_replay", Spells.PROJECTILES.size() - echo == 1, null);
                tome = tome.copy();
            }
        }
        check("arcane_echo_fires_three_on_every_sixth_cast", fired.equals(List.of(1, 1, 1, 1, 1, 3, 1)), fired);
        Spells.PROJECTILES.subList(first, Spells.PROJECTILES.size()).clear();
    }

    private static void testStaffElements() {
        var playerStaff = caster.getMainHandItem();
        int initialProjectiles = Spells.PROJECTILES.size();
        boolean cadence = true, frost = false, fire = false;
        for (int branch = 0; branch < 2; branch++) {
            var staff = new ItemStack(ModItems.STARTER_STAFF);
            LightGearTree.write(staff, new int[] {0, 0, branch}, new int[] {1, 1, 1 << branch});
            caster.setItemInHand(InteractionHand.MAIN_HAND, staff);
            var tome = new ItemStack(ModItems.TOMES.get(Spell.ARCANE_BOLT));
            for (int cast = 1; cast <= 4; cast++) {
                caster.getCooldowns().removeCooldown(caster.getCooldowns().getCooldownGroup(tome));
                Progression.setMana(caster, (float) Progression.maxMana(caster));
                int before = Spells.PROJECTILES.size();
                boolean success = Spells.cast(caster, tome);
                cadence &= success
                        && Spells.PROJECTILES.size() == before + 1
                        && (Spells.PROJECTILES.getLast().cast != null) == (cast == 4);
            }
            if (Spells.PROJECTILES.size() > initialProjectiles) {
                var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                zombie.setPos(caster.position().add(5, 0, 0));
                Spells.impact(level, Spells.PROJECTILES.getLast(), zombie.position(), zombie);
                if (branch == 0) frost = Freeze.frozen(zombie);
                else fire = zombie.getRemainingFireTicks() >= 39;
                zombie.discard();
            }
            Spells.PROJECTILES
                    .subList(initialProjectiles, Spells.PROJECTILES.size())
                    .clear();
        }
        caster.setItemInHand(InteractionHand.MAIN_HAND, playerStaff);
        Spells.forget(caster);
        check("fourth_staff_cast_charges_delayed_projectiles", cadence && frost && fire, List.of(cadence, frost, fire));
    }

    /** The owner's round of 2026-11-18: tiers, tome drops, the Spellstone in the upgrade tree, hints, night, tabs. */
    private static void testRound18() {
        check(
                "round18_tiers_and_drops",
                Spell.FROST_NOVA.tier == 3
                        && Spell.HOMEWARD.tier == 2
                        && Spell.SOULS.tier == 3
                        && Spell.SOULS.drops
                        && Balance.TOME_DROP_CHANCE == 0.015
                        && Balance.TOME_FROM_GIANT == 0.125
                        && DarkNests.SPACING == 150
                        && !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(
                                ModItems.id("spider_zombie")),
                null);

        // A tome inside a carried Spellstone takes an upgrade where it sits.
        int at = 9;
        var before = caster.getInventory().getItem(at).copy();
        var stone = new ItemStack(Spellstone.ITEM);
        Spellstone.store(
                stone,
                java.util.List.of(
                        ItemStack.EMPTY,
                        new ItemStack(ModItems.TOMES.get(Spell.GLACIAL_SPIKE)),
                        ItemStack.EMPTY,
                        ItemStack.EMPTY));
        caster.getInventory().setItem(at, stone);
        caster.getInventory().add(new ItemStack(ModItems.SPARK, SpellUpgrades.TIER_COST[0]));
        String refused = SpellUpgrades.choose(caster, Spellstone.inStone(at, 1), 0, 2);
        var after = Spellstone.tomes(caster.getInventory().getItem(at)).get(1);
        boolean upgraded = refused == null && SpellUpgrades.chosen(after)[0] == 2;
        caster.getInventory().setItem(at, before);
        check("upgrade_tome_inside_spellstone", upgraded, refused);

        // Where an item no hand can make comes from.
        String flesh = Sources.hint(level, "minecraft:rotten_flesh").getString(),
                diamond = Sources.hint(level, "minecraft:diamond").getString(),
                storm = Sources.hint(level, "lightborn:storm_staff").getString(),
                trophy = Sources.hint(level, "lightborn:ashbringer").getString(),
                saddle = Sources.hint(level, "minecraft:saddle").getString();
        check(
                "found_hints_say_where",
                flesh.startsWith("Dropped by")
                        && diamond.contains("Mined from")
                        && storm.startsWith("Dropped by monsters")
                        && trophy.contains("Ozrik")
                        && saddle.contains("chests"),
                flesh + " | " + diamond + " | " + storm + " | " + trophy + " | " + saddle);

        // The chest's tabs open as the game reaches them.
        caster.removeAttached(ChestTabs.NIGHTS_SURVIVED);
        caster.removeAttached(ChestTabs.FOUNTAINS_BEATEN);
        boolean closed = !ChestTabs.shop(caster) && !ChestTabs.cleanse(caster) && !ChestTabs.travel(caster);
        caster.setAttached(ChestTabs.NIGHTS_SURVIVED, 1);
        caster.setAttached(ChestTabs.FOUNTAINS_BEATEN, 7);
        boolean some = ChestTabs.shop(caster) && ChestTabs.cleanse(caster) && !ChestTabs.travel(caster);
        ChestTabs.fountainBeaten(caster);
        var playTime = net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME);
        int originalTime = caster.getStats().getValue(playTime);
        caster.getStats().setValue(caster, playTime, ChestTabs.TRAVEL_PLAY_TICKS - 1);
        boolean waits = !ChestTabs.travel(caster);
        caster.getStats().setValue(caster, playTime, ChestTabs.TRAVEL_PLAY_TICKS);
        check(
                "chest_tabs_open_in_turn",
                closed && some && waits && ChestTabs.travelReady(caster) && !ChestTabs.travel(caster),
                "closed=" + closed + " some=" + some);
        caster.getStats().setValue(caster, playTime, originalTime);
        caster.removeAttached(ChestTabs.NIGHTS_SURVIVED);
        caster.removeAttached(ChestTabs.FOUNTAINS_BEATEN);

        // Radiant Pulse floods a cave (owner, 2026-11-19: it lit caves in patches): a tunnel cut deep in stone that
        // runs 10 blocks east, turns north and climbs 6 blocks over 10 more is lit along its hidden far leg too.
        var base = new net.minecraft.core.BlockPos(
                caster.blockPosition().getX() + 200,
                level.getMinY() + 20,
                caster.blockPosition().getZ() + 200);
        for (int x = -2; x <= 14; x++)
            for (int y = -2; y <= 12; y++)
                for (int z = -14; z <= 2; z++)
                    level.setBlock(
                            base.offset(x, y, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int x = 0; x <= 10; x++) for (int y = 0; y < 2; y++) level.setBlock(base.offset(x, y, 0), air, 2);
        for (int z = 0; z >= -10; z--)
            for (int y = 0; y < 2; y++) level.setBlock(base.offset(10, y - z * 6 / 10, z), air, 2);
        var cells = RadiantPulse.candidates(level, net.minecraft.world.phys.Vec3.atCenterOf(base.above()), 6);
        boolean farLeg = cells.stream().anyMatch(c -> c.getX() == base.getX() + 10 && c.getZ() <= base.getZ() - 6);
        boolean inRock = cells.stream().anyMatch(c -> !level.getBlockState(c).isAir());
        check(
                "radiant_pulse_floods_caves",
                farLeg && !inRock && cells.size() >= 3,
                "cells=" + cells.size() + " farLeg=" + farLeg + " inRock=" + inRock);
        check("radiant_pulse_base_lights_last_thirty_seconds", RadiantPulse.duration() == 600, RadiantPulse.duration());
    }

    static void castAll() {
        // The owner's per-spell numbers (mana / cooldown), and regen a third of the first version's then +40%.
        int[][] want = {
            {8, 10},
            {10, 32},
            {20, 140}, // Frost Nova: 20 mana, 7 s (owner, 2026-11-18)
            {15, 40},
            {25, 120},
            {40, 400},
            {25, 400},
            {30, 240},
            {100, 600},
            {10, 60},
            {10, 40},
            {25, 1200},
            {100, 900}, // Felling (owner, 2026-11-23: 100 mana, 45 s)
            {20, 200},
            {25, 400},
            {10, 100},
            {100, 3600},
            {40, 1200},
            {35, 180},
            {10, 20},
            {20, 60},
            {25, 120},
            {40, 160},
            {20, 120},
            {40, 900},
            {30, 400},
            {10, 100}, // Updraft: 5 s (owner, 2026-11-18)
            {50, 2400}, // Eclipse (owner, 2026-11-22: two minutes)
            {100, 400}, // Book of Souls: 100 mana (owner, 2026-11-16)
            {30, 600}, // Radiant Pulse
            {100, 2400}, // Treasure Thread (owner, 2026-11-17: 100 mana, two minutes)
            {15, 60}, // Flight: 15 mana a second (owner, 2026-11-18)
            {20, 400}, // Cage: 20 mana, 20 seconds (owner, 2026-11-18)
            {30, 300}, // Taunt: 30 mana, a 15-second cooldown (owner, 2026-11-18)
            // The third shelf (2026-11-21): Sunbeam, Wisps, Halo Disc, Starfall, Stone Wall, Sanctuary, Light Bridge,
            // Owl Sight, Light Steed (Sow deleted 2026-11-22).
            {4, 4},
            {30, 320}, // Wisps (owner, 2026-11-23: they keep attacking; 16 s)
            {12, 30},
            {40, 200}, // Starfall (owner, 2026-11-22)
            {10, 200}, // Stone Wall (owner, 2026-11-22)
            {35, 2400}, // Ward, was Sanctuary (owner, 2026-11-22)
            {20, 100},
            {30, 3200}, // Owl Sight
            {100, 4800}, // Light Steed (owner, 2026-11-22)
            // The fourth shelf (2026-11-21): Tether, Wayglow, Thornfield, Lightbrand, Ball Lightning, Blade Ring,
            // Rewind, Twincast (Mend and Angler's Call deleted 2026-11-22).
            {15, 60},
            {4, 10},
            {30, 200},
            {25, 3600}, // Lightbrand (owner, 2026-11-22)
            {30, 200},
            {30, 300},
            {30, 1200}, // Rewind (owner, 2026-11-22)
            {30, 0}, // Twincast (owner, 2026-11-22)
            // The fifth and last shelf (2026-11-22): Cyclone, Trap, Dawnbreak, Emberwalk, Crystal Shell, Transpose,
            // Moongate, Quickening, Light Bore (Beastfriend deleted 2026-11-22).
            {30, 240},
            {15, 60},
            {80, 1200},
            {30, 3600}, // Emberwalk (owner, 2026-11-22)
            {35, 1800}, // Crystal Shell (owner, 2026-11-22)
            {15, 100}, // Transpose (owner, 2026-11-22)
            {100, 900}, // Moongate (owner, 2026-11-22)
            {100, 1200}, // Quickening (owner, 2026-11-22)
            {100, 200}, // Light Bore (owner, 2026-11-22)
            // The sixth shelf (owner, 2026-11-22): first-pass numbers of my own, then the owner's rework the same day.
            {50, 1200}, // Sentinel (owner: 50 mana, 60 s)
            {50, 500}, // Blizzard
            {0, 4800}, // Freedom (owner: no mana, 4 min)
            {60, 1400}, // Smite (owner, 2026-11-23: 70 s)
            {40, 600}, // Soul Link
            {100, 3600}, // Time Stop (100 is all the mana a player starts with)
            {100, 2400}, // Tempest
            {100, 4800}, // Giant Golem (owner: 4 min)
            {100, 4800}, // Gigantify
            {100, 12000}, // Golden Apple (owner: 100 mana, 10 min)
            {100, 3600}, // Transmute (owner: 3 min)
            // The seventh shelf (owner, 2026-11-22): first-pass numbers of my own; on 2026-11-23 every transformation
            // costs 100 (owner).
            {100, 2400}, // Wolf Form
            {100, 3600}, // Bear Form
            {100, 2400}, // Spider Form
            {100, 3600}, // Blaze Form
            {100, 1200}, // Spirit Walk
            {100, 1200}, // Shrinking
            {10, 3000}, // Gills
            {15, 600}, // Purify
            {100, 0}, // Ender Pocket (owner, 2026-11-23: 100 mana, no cooldown)
            {100, 40}, // Workbench (owner, 2026-11-23)
            {15, 500}, // Weaken (owner, 2026-11-23: 25 s)
            {10, 30}, // Splinter
            {15, 120}, // Boulder (owner, 2026-11-23: 6 s)
            {100, 1200}, // Frostwalk (owner, 2026-11-23)
            {40, 800}, // Fright (owner, 2026-11-23: 40 mana, 40 s)
            {100, 6000}, // Warden Form, in The Dragon's place (2026-11-23)
            {40, 300}, // Comet Dash (2026-11-23)
            {40, 900}, // Doom (owner, 2026-11-23: 40 mana, 45 s)
            {50, 900} // Lifebloom
        };
        boolean balance = Math.abs(Balance.MANA_REGEN_PER_SECOND - 5.6) < 1e-9;
        List<String> off = new ArrayList<>();
        if (want.length != Spell.values().length)
            off.add(want.length + " rows for " + Spell.values().length + " spells");
        for (Spell s : Spell.values())
            if (s.ordinal() < want.length
                    && (s.mana != want[s.ordinal()][0] || s.cooldownTicks != want[s.ordinal()][1]))
                off.add(s + " " + s.mana + "/" + s.cooldownTicks + " not " + want[s.ordinal()][0] + "/"
                        + want[s.ordinal()][1]);
        balance &= off.isEmpty();
        check("spell_balance", balance, off.isEmpty() ? null : off);
        // Tome descriptions wrap into lines that fit on screen, and wrapping loses no words.
        boolean wraps = true;
        for (Spell s : Spell.values()) {
            List<String> lines = TomeItem.wrap(s.description);
            wraps &= String.join(" ", lines).equals(s.description)
                    && lines.stream().allMatch(l -> l.length() <= TomeItem.WRAP || !l.contains(" "));
        }
        check("tooltips_wrap", wraps, null);

        // No staff, no spells: bare hand and a sword both refuse, even with the tome in the off hand.
        Spells.forget(caster);
        Progression.setMana(caster, (float) Progression.maxMana(caster));
        caster.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        boolean bare = castTome(Spell.ARCANE_BOLT);
        caster.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        boolean sword = castTome(Spell.ARCANE_BOLT);
        check("spells_need_staff", !bare && !sword, "bare=" + bare + " sword=" + sword);
        caster.setItemInHand(InteractionHand.MAIN_HAND, LootRoller.starterStaff(level.getRandom()));
        // A staff with no tome (or a non-tome) in the off hand casts nothing.
        caster.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TORCH));
        check("spells_need_tome", !Spells.cast(caster, caster.getOffhandItem()), null);
        testSpellstoneBag();
        testArcaneEcho();
        testStaffElements();
        testRound18();

        // Spell damage carries the global half-damage scale.
        double expect = Spell.ARCANE_BOLT.baseDamage * 0.5 * caster.getAttributeValue(ModAttributes.SPELL_POWER);
        check(
                "spells_half_damage_no_level",
                Math.abs(Spells.damage(caster, Spell.ARCANE_BOLT) - expect) < 1e-6,
                Spells.damage(caster, Spell.ARCANE_BOLT));

        // A line of husks ahead (+X) for bolt / fireball / chain / meteor, two beside the caster for frost nova.
        for (int i = 0; i < 4; i++) husk(base.offset(8 + i * 2, 0, i % 2));
        husk(base.offset(2, 0, 2));
        husk(base.offset(-2, 0, -2));
        for (var type : List.of(EntityTypes.COW, EntityTypes.CHICKEN)) {
            Mob animal = type.create(level, EntitySpawnReason.COMMAND);
            animal.setPos(base.getX() - 2.5, base.getY(), base.getZ() + 2.5 + ANIMALS.size());
            animal.setNoAi(true); // a wandering chicken can stroll out of the nova and fail the check by chance
            level.addFreshEntity(animal);
            ANIMALS.add(animal);
        }
        // Area spells hit animals, but never the caster's own pets.
        var wolf = EntityTypes.WOLF.create(level, EntitySpawnReason.COMMAND);
        wolf.tame(caster);
        check(
                "spells_area_targets",
                Spells.canHitArea(caster, ANIMALS.get(0)) && !Spells.canHitArea(caster, wolf),
                null);
        Progression.setMana(caster, 10000);
        for (Spell s : Spell.values()) {
            if (s.kind != Spell.Kind.ATTACK || s.ordinal() > Spell.METEOR.ordinal())
                continue; // the second shelf has its own test
            // Meteor aims at the ground ahead (earlier spells may already have killed the line).
            caster.setXRot(s == Spell.METEOR ? 25f : 0f);
            Spells.forget(caster);
            Progression.setMana(caster, (float) Progression.maxMana(caster));
            if (castTome(s)) castOk++;
            else Lightborn.LOG.error("SELFTEST cast failed: {}", s);
        }
        caster.setXRot(0f);
        check("spells_cast", castOk == 5, castOk + "/5");
        check(
                "meteor_rock_falls",
                level.getEntitiesOfClass(
                                        Display.BlockDisplay.class,
                                        new AABB(base).inflate(60),
                                        e -> e.entityTags().contains(FireSpells.ROCK_TAG))
                                .size()
                        == 1,
                null);
        check("spells_projectiles", Spells.liveProjectiles() >= 1, Spells.liveProjectiles());
        // Cooldown sits on the tome item: the second cast in a row fails, and a fresh tome of the same spell shares it.
        Spells.forget(caster);
        Progression.setMana(caster, (float) Progression.maxMana(caster));
        boolean first = castTome(Spell.FROST_NOVA);
        check(
                "spells_cooldown",
                first
                        && !Spells.cast(caster, caster.getOffhandItem())
                        && !castTome(Spell.FROST_NOVA)
                        && caster.getCooldowns().isOnCooldown(caster.getOffhandItem()),
                null);
        // Mana: empty mana must refuse.
        Spells.forget(caster);
        Progression.setMana(caster, 0);
        check("spells_mana", !castTome(Spell.ARCANE_BOLT), null);
        // Tomes last forever (owner, 2026-11-16): a cast leaves no wear.
        Spells.forget(caster);
        Progression.setMana(caster, (float) Progression.maxMana(caster));
        castTome(Spell.ARCANE_BOLT);
        check(
                "tome_never_wears",
                caster.getOffhandItem().getDamageValue() == 0
                        && !caster.getOffhandItem().isDamageableItem(),
                caster.getOffhandItem());
        // No upgrade is left from the days tomes wore out.
        var stale = java.util.Arrays.stream(Spell.values())
                .flatMap(sp -> SpellUpgrades.tiers(sp).stream().flatMap(java.util.List::stream))
                .filter(u -> u.text().isEmpty() || u.text().contains("wears"))
                .map(SpellUpgrades.Upgrade::name)
                .toList();
        check("no_wear_upgrades_left", stale.isEmpty(), stale);
        // A few Frost Novas round the caster: the animals beside it must take spell damage (checked after the spells).
        caster.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.TOMES.get(Spell.FROST_NOVA)));
        for (int i = 0; i < 4; i++) {
            Spells.forget(caster);
            Progression.setMana(caster, (float) Progression.maxMana(caster));
            Spells.cast(caster, caster.getOffhandItem());
        }
        Spells.forget(caster);
        Progression.setMana(caster, (float) Progression.maxMana(caster));
        double x0 = caster.getX();
        boolean blinked = castTome(Spell.BLINK);
        check("spells_blink", blinked && caster.getX() > x0 + 4, String.format("%.1f -> %.1f", x0, caster.getX()));
        testEchoStep();
        testFlightAndCage();
        testTaunt();
        testUtilitySpells();
        testCollection();
    }

    private static void testEchoStep() {
        var blink = new ItemStack(ModItems.TOMES.get(Spell.BLINK));
        SpellUpgrades.write(blink, new int[] {0, 0, 1});
        var staff = caster.getMainHandItem();
        var old = caster.getOffhandItem();
        boolean ok = true;
        for (boolean stone : new boolean[] {false, true}) {
            Spells.forget(caster);
            Perks.spendFree(caster, Spell.BLINK);
            Progression.setMana(caster, (float) Progression.maxMana(caster));
            var castItem = blink.copy();
            if (stone) {
                var spellstone = new ItemStack(Spellstone.ITEM);
                Spellstone.store(spellstone, List.of(ItemStack.EMPTY, castItem));
                caster.setItemInHand(InteractionHand.OFF_HAND, spellstone);
                Spellstone.cast(caster, 1, 0);
            } else {
                caster.setItemInHand(InteractionHand.OFF_HAND, castItem);
                staff.getItem().use(level, caster, InteractionHand.MAIN_HAND);
            }
            boolean ready =
                    Perks.free(caster, Spell.BLINK) && caster.getCooldowns().isOnCooldown(castItem);
            double mana = Progression.mana(caster);
            var first = caster.position();
            if (stone) Spellstone.cast(caster, 1, 0);
            else staff.getItem().use(level, caster, InteractionHand.MAIN_HAND);
            ok &= ready
                    && !Perks.free(caster, Spell.BLINK)
                    && caster.position().distanceToSqr(first) > 1
                    && Progression.mana(caster) == mana - 40;
        }
        caster.setItemInHand(InteractionHand.OFF_HAND, old);
        check("blink_echo_step_passes_both_cast_routes", ok, null);
    }

    /**
     * Taunt (owner, 2026-11-18): the totem takes the caster's health and armour, a monster near it turns on it, a
     * player's blow pulls that monster off it for good, and no player can hurt the totem.
     */
    private static void testTaunt() {
        Spells.forget(caster);
        Progression.setMana(caster, (float) Progression.maxMana(caster));
        Vec3 ahead = caster.position()
                .add(caster.getLookAngle().multiply(1, 0, 1).normalize().scale(6));
        var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        zombie.snapTo(ahead.x, caster.getY(), ahead.z, 0f, 0f);
        level.addFreshEntity(zombie);
        boolean cast = castTome(Spell.TAUNT);
        var totems = Taunt.totems();
        var totem = totems.isEmpty() ? null : totems.getFirst();
        boolean likeYou = totem != null
                && Math.abs(totem.getMaxHealth() - caster.getMaxHealth()) < .01
                && totem.getArmorValue() == caster.getArmorValue();
        Taunt.tick(level.getServer());
        boolean taunted = Taunt.taunted(zombie) && zombie.getTarget() == totem;
        float totemHealth = totem == null ? 0 : totem.getHealth();
        if (totem != null) {
            totem.damageCooldownTime = 0;
            totem.hurtServer(level, caster.damageSources().playerAttack(caster), 6);
        }
        boolean safeFromPlayers = totem != null && totem.getHealth() == totemHealth;
        zombie.damageCooldownTime = 0;
        zombie.hurtServer(level, caster.damageSources().playerAttack(caster), 1);
        Taunt.tick(level.getServer());
        boolean broken = !Taunt.taunted(zombie) && zombie.getTarget() == caster;
        Taunt.clear(level.getServer());
        boolean gone = Taunt.totems().isEmpty() && (totem == null || totem.isRemoved());
        check(
                "spell_taunt",
                cast && likeYou && taunted && safeFromPlayers && broken && gone,
                "cast=" + cast + " likeYou=" + likeYou + " taunted=" + taunted + " safe=" + safeFromPlayers + " broken="
                        + broken + " gone=" + gone);
        zombie.discard();
    }

    /**
     * Flight: the test's fake player can never ride (Fabric refuses it), so here a refused ride costs nothing and
     * leaves no staff behind, and a second aloft costs 15 mana and is refused when the mana is short; the real ride is
     * checked by the visual test. Cage shuts a monster in six panes of bars, stills it, and lets it go as it was.
     */
    private static void testFlightAndCage() {
        Spells.forget(caster);
        Progression.setMana(caster, (float) Progression.maxMana(caster));
        float start = Progression.mana(caster);
        boolean refused = !castTome(Spell.FLIGHT) && Progression.mana(caster) == start;
        boolean noStaffLeft = level.getEntities(StaffFlight.TYPE, e -> true).isEmpty();
        var mount = new StaffFlight.Mount(StaffFlight.TYPE, level);
        mount.second(caster);
        float afterSecond = Progression.mana(caster);
        Progression.setMana(caster, 5);
        mount.second(caster);
        boolean dry = Progression.mana(caster) == 5;
        mount.discard();
        check(
                "spell_flight_drains_mana",
                refused && noStaffLeft && Math.abs(start - afterSecond - 15) < .01 && dry,
                "refused=" + refused + " noStaffLeft=" + noStaffLeft + " mana " + start + " -> " + afterSecond + " dry="
                        + dry);

        Spells.forget(caster);
        Progression.setMana(caster, (float) Progression.maxMana(caster));
        Vec3 ahead = caster.position()
                .add(caster.getLookAngle().multiply(1, 0, 1).normalize().scale(3));
        var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        zombie.snapTo(ahead.x, caster.getY(), ahead.z, 0f, 0f);
        level.addFreshEntity(zombie);
        face(zombie);
        boolean caged = castTome(Spell.CAGE);
        boolean held = Cage.caged(zombie) && zombie.isNoAi() && Cage.bars(zombie) == 6;
        // Nothing hurts a caged monster (owner, 2026-11-18): a player's blow lands on nothing.
        float before = zombie.getHealth();
        zombie.damageCooldownTime = 0;
        zombie.hurtServer(level, caster.damageSources().playerAttack(caster), 6);
        boolean unhurt = zombie.getHealth() == before;
        Cage.release(zombie);
        boolean freed = !Cage.caged(zombie) && !zombie.isNoAi();
        zombie.damageCooldownTime = 0;
        zombie.hurtServer(level, caster.damageSources().playerAttack(caster), 6);
        boolean hurtAfter = zombie.getHealth() < before;
        check(
                "spell_cage_holds_and_frees",
                caged && held && unhurt && freed && hurtAfter,
                "cast=" + caged + " held=" + held + " unhurt=" + unhurt + " freed=" + freed + " hurtAfter="
                        + hurtAfter);
        zombie.discard();

        // Animals can be caged too (owner, 2026-11-18); villagers and golems, whose category is MISC, cannot.
        Spells.forget(caster);
        var cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
        cow.snapTo(ahead.x, caster.getY(), ahead.z, 0f, 0f);
        level.addFreshEntity(cow);
        face(cow);
        boolean cowCaged = castTome(Spell.CAGE) && Cage.caged(cow);
        Cage.release(cow);
        cow.discard();
        var villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
        boolean villagerSpared = !Cage.cageable(caster, villager);
        check(
                "spell_cage_holds_animals",
                cowCaged && villagerSpared,
                "cow=" + cowCaged + " villager=" + !villagerSpared);
    }

    /** The defence, farming and mining spells, each on a small set piece beside the arena's west edge. */
    private static void testUtilitySpells() {
        caster.setPos(base.getX() - 6.5, base.getY(), base.getZ() + 0.5);
        caster.setYRot(-90f); // facing +X
        caster.setXRot(0f);
        caster.setYHeadRot(-90f);
        Supplier<Boolean> fresh = () -> {
            Spells.forget(caster);
            Progression.setMana(caster, (float) Progression.maxMana(caster));
            return true;
        };

        fresh.get();
        caster.setAbsorptionAmount(0);
        boolean skin = castTome(Spell.STONESKIN);
        check(
                "spell_stoneskin",
                skin && caster.getAbsorptionAmount() > 1 && caster.hasEffect(MobEffects.RESISTANCE),
                caster.getAbsorptionAmount());
        caster.removeEffect(MobEffects.ABSORPTION);
        caster.removeEffect(MobEffects.RESISTANCE);

        fresh.get();
        caster.setHealth(caster.getMaxHealth());
        boolean idleHeal = castTome(Spell.MENDING_LIGHT);
        fresh.get();
        caster.setHealth(caster.getMaxHealth() - 8);
        float hurt = caster.getHealth();
        var friend = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
        friend.snapTo(caster.getX() + 4, caster.getY(), caster.getZ() + 3, 0f, 0f);
        level.addFreshEntity(friend);
        friend.setHealth(3);
        boolean healed = castTome(Spell.MENDING_LIGHT);
        check(
                "spell_light",
                idleHeal && healed && caster.getHealth() > hurt && friend.getHealth() > 3,
                hurt + " -> " + caster.getHealth() + ", cow 3 -> " + friend.getHealth());
        friend.discard();
        caster.setHealth(caster.getMaxHealth());

        // A small field two blocks south-west: farmland with young carrots (age 0) and ripe wheat.
        BlockPos young = base.offset(-8, 0, 2), ripe = base.offset(-7, 0, 2);
        var cropAge = CropBlock.AGE;
        for (BlockPos f : List.of(young, ripe)) level.setBlockAndUpdate(f.below(), Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(young, Blocks.CARROTS.defaultBlockState());
        level.setBlockAndUpdate(ripe, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        fresh.get();
        boolean surged = castTome(Spell.VERDANT_SURGE);
        int age = level.getBlockState(young).getValue(cropAge);
        check("spell_verdant_surge", surged && age > 0, "age=" + age);

        level.setBlockAndUpdate(young, Blocks.CARROTS.defaultBlockState()); // unripe: Reap must leave it be
        fresh.get();
        boolean reaped = castTome(Spell.REAP);
        var ripeState = level.getBlockState(ripe);
        int wheatItems = 0;
        for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(caster.blockPosition()).inflate(3))) {
            if (item.getItem().is(Items.WHEAT)) wheatItems += item.getItem().getCount();
            item.discard();
        }
        check(
                "spell_reap",
                reaped
                        && ripeState.is(Blocks.WHEAT)
                        && ripeState.getValue(cropAge) == 0
                        && level.getBlockState(young).is(Blocks.CARROTS)
                        && level.getBlockState(young).getValue(cropAge) == 0
                        && wheatItems >= 1,
                "wheat=" + wheatItems + " " + ripeState);
        for (BlockPos f : List.of(young, ripe)) {
            level.setBlockAndUpdate(f, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(f.below(), Blocks.STONE.defaultBlockState());
        }

        // A stone wall to the west with iron ore at its heart: Excavate (facing -X) digs it and drops raw iron.
        caster.setYRot(90f);
        caster.setYHeadRot(90f);
        BlockPos face = base.offset(-9, 1, 0);
        for (int d = 0; d < 3; d++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++) {
                    level.setBlockAndUpdate(face.offset(-d, y, z), Blocks.STONE.defaultBlockState());
                }
        level.setBlockAndUpdate(face.offset(-1, 0, 0), Blocks.IRON_ORE.defaultBlockState());
        level.setBlockAndUpdate(face.offset(-2, 0, 1), Blocks.OBSIDIAN.defaultBlockState()); // too hard: stays
        fresh.get();
        boolean dug = castTome(Spell.EXCAVATE);
        int left = 0;
        for (int d = 0; d < 3; d++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++)
                    if (!level.getBlockState(face.offset(-d, y, z)).isAir()) left++;
        int rawIron = 0;
        for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(caster.blockPosition()).inflate(3))) {
            if (item.getItem().is(Items.RAW_IRON)) rawIron += item.getItem().getCount();
            item.discard();
        }
        check(
                "spell_excavate",
                dug && left == 1 && level.getBlockState(face.offset(-2, 0, 1)).is(Blocks.OBSIDIAN) && rawIron >= 1,
                "left=" + left + " raw_iron=" + rawIron);
        level.setBlockAndUpdate(face.offset(-2, 0, 1), Blocks.AIR.defaultBlockState());

        // Ore Sight: one diamond ore buried in the floor lights up as a tagged glowing display, nothing when none is
        // near.
        BlockPos ore = base.offset(-6, -1, 3);
        level.setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
        fresh.get();
        boolean sight = castTome(Spell.ORE_SIGHT);
        var glows = level.getEntitiesOfClass(
                Display.BlockDisplay.class,
                new AABB(base).inflate(30),
                e -> e.entityTags().contains(WorkSpells.ORE_TAG));
        check("spell_ore_sight", sight && glows.size() == 1 && glows.get(0).isCurrentlyGlowing(), glows.size());
        level.setBlockAndUpdate(ore, Blocks.STONE.defaultBlockState());
        FireSpells.clearDisplays();
        testNewSpells(fresh);
        SelfTestArcaneSpells.run();
        SelfTestFireSpells.run();
        SelfTestStormSpells.run();
        SelfTestFrostSpells.run();
        SelfTestEarthSpells.run();
        SelfTestNatureSpells.run();
        SelfTestLightSpells.run();
        SelfTestHexSpells.run();
        SelfTestTravelSpells.run();
        SelfTestFormSpells.run();
        SelfTestSummonSpells.run();
        SelfTestWorkSpells.run();
        SelfTestGameplay.run();
        testDarkness();
        testZombieKinds();
        testMonsterKinds();
        testRainGrowth();
        caster.setPos(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        caster.setYRot(-90f);
        caster.setYHeadRot(-90f);
    }

    static void testGreaterSpells(Runnable done) {
        // The first tests' meteor takes three seconds to land: let it, so its burning ring cannot touch these tests.
        at(FireSpells.METEOR_FALL + 20, () -> greaterSpells(done));
    }

    private static void greaterSpells(Runnable done) {
        withTestArena(
                base.offset(-16, 0, -16),
                base.offset(32, 0, 16),
                "greater_arena_ready",
                release -> {
                    for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(base).inflate(24))) m.discard();
                    runGreaterSpells(release);
                },
                done);
    }

    static void withTestArena(BlockPos min, BlockPos max, String checkName, Consumer<Runnable> test, Runnable done) {
        int cx0 = min.getX() >> 4, cx1 = max.getX() >> 4, cz0 = min.getZ() >> 4, cz1 = max.getZ() >> 4;
        var chunks = new ArrayList<ChunkPos>();
        var ownedTickets = new ArrayList<ChunkPos>();
        // Full-generation terrain packs can leave border chunks waiting on their neighbours. Warm a two-chunk apron.
        for (int cx = cx0 - 2; cx <= cx1 + 2; cx++)
            for (int cz = cz0 - 2; cz <= cz1 + 2; cz++) {
                var chunk = new ChunkPos(cx, cz);
                if (cx >= cx0 && cx <= cx1 && cz >= cz0 && cz <= cz1) chunks.add(chunk);
                if (level.setChunkForced(cx, cz, true)) ownedTickets.add(chunk);
                level.getChunk(cx, cz);
            }
        Runnable finish = () -> {
            for (ChunkPos chunk : ownedTickets) level.setChunkForced(chunk.x(), chunk.z(), false);
            done.run();
        };
        waitForArena(chunks, 1200, checkName, () -> test.accept(finish), finish);
    }

    private static void waitForArena(
            List<ChunkPos> chunks, int ticksLeft, String checkName, Runnable run, Runnable finish) {
        if (chunks.stream().allMatch(level::areEntitiesActuallyLoadedAndTicking)) {
            run.run();
        } else if (ticksLeft > 0) {
            at(1, () -> waitForArena(chunks, ticksLeft - 1, checkName, run, finish));
        } else {
            check(
                    checkName,
                    false,
                    chunks.stream()
                            .filter(c -> !level.areEntitiesActuallyLoadedAndTicking(c))
                            .toList());
            finish.run();
        }
    }

    private static void runGreaterSpells(Runnable done) {
        // Damage ratios need fixed rolls. A critical first hit would make an ordinary shatter look too weak.
        var crit = caster.getAttribute(ModAttributes.SPELL_CRIT);
        var fixedRoll = ModItems.id("selftest_no_spell_crit");
        crit.addTransientModifier(new AttributeModifier(fixedRoll, -1, AttributeModifier.Operation.ADD_VALUE));
        ItemStack staff = LootRoller.starterStaff(level.getRandom());
        Runnable ready = () -> {
            caster.setItemInHand(InteractionHand.MAIN_HAND, staff);
            Spells.forget(caster);
            Progression.setMana(caster, (float) Progression.maxMana(caster));
            caster.setPos(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
            caster.setYRot(-90f);
            caster.setYHeadRot(-90f);
            caster.setXRot(0f);
            caster.setDeltaMovement(Vec3.ZERO);
        };
        Runnable sweep = () -> {
            for (Mob m : DUMMIES) m.discard();
            DUMMIES.clear();
        };
        boolean[] ok = new boolean[4];
        double[] num = new double[4];
        Object[] keep = new Object[4];

        // Water Splash: the wave strikes a line of three ahead and a burning husk at its edge (and puts it out), spares
        // the one behind, and hardens a lava source in its path.
        BlockPos lava = base.offset(4, -1, -1);
        at(1, () -> {
            ready.run();
            Mob burning = dummy(EntityTypes.HUSK, 5, 2, false);
            burning.igniteForSeconds(10);
            keep[0] = List.of(
                    dummy(EntityTypes.HUSK, 3, 0, false),
                    dummy(EntityTypes.HUSK, 5.5, 0, false),
                    dummy(EntityTypes.HUSK, 7.5, 0, false),
                    burning);
            keep[1] = dummy(EntityTypes.HUSK, -3, 0, false);
            keep[2] = level.getBlockState(lava);
            level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
            ok[0] = castTome(Spell.WATER_SPLASH);
            num[0] = StormSpells.live("waves");
        });
        at(14, () -> {
            @SuppressWarnings("unchecked")
            List<Mob> ahead = (List<Mob>) keep[0];
            long struck = ahead.stream().filter(SelfTest::hurt).count();
            boolean out = !ahead.get(3).isOnFire(),
                    spared = !hurt((Mob) keep[1]),
                    stone = level.getBlockState(lava).is(Blocks.OBSIDIAN);
            check(
                    "spell_water_splash",
                    ok[0] && num[0] == 1 && struck == 4 && out && spared && stone && StormSpells.live("waves") == 0,
                    "cast=" + ok[0] + " struck=" + struck + "/4 doused=" + out + "(fire="
                            + ahead.get(3).getRemainingFireTicks()
                            + " at=" + level.getBlockState(ahead.get(3).blockPosition()) + "/"
                            + level.getBlockState(ahead.get(3).blockPosition().below()) + " projectiles="
                            + level.getEntitiesOfClass(
                                            net.minecraft.world.entity.projectile.Projectile.class,
                                            ahead.get(3).getBoundingBox().inflate(12))
                                    .size()
                            + ") spared=" + spared + " obsidian="
                            + stone
                            + (struck == 4
                                    ? ""
                                    : " targets="
                                            + ahead.stream()
                                                    .map(m ->
                                                            m.position() + ":" + m.getHealth() + "/" + m.getMaxHealth())
                                                    .toList()));
            level.setBlockAndUpdate(lava, (BlockState) keep[2]);
            sweep.run();
        });

        // Glacial Spike: the first chills, the second shatters for about three times as much.
        at(24, () -> {
            ready.run();
            Mob ice = dummy(EntityTypes.HUSK, 5, 0, false);
            keep[1] = ice;
            num[0] = ice.getHealth();
            ok[0] = castTome(Spell.GLACIAL_SPIKE);
        });
        at(29, () -> {
            Mob ice = (Mob) keep[1];
            num[1] = ice.getHealth();
            ok[1] = FrostSpells.chilled(ice);
            ready.run();
            ok[2] = castTome(Spell.GLACIAL_SPIKE);
        });
        at(34, () -> {
            Mob ice = (Mob) keep[1];
            double first = num[0] - num[1], shatter = num[1] - ice.getHealth();
            check(
                    "spell_glacial_spike",
                    ok[0] && ok[1] && ok[2] && first > 0 && shatter > first * 1.9 && !FrostSpells.chilled(ice),
                    String.format("chilled=%s first=%.2f shatter=%.2f", ok[1], first, shatter));
            sweep.run();
        });

        // Earthshatter: spears run along the ground and strike both husks on the line, then sink and vanish.
        at(36, () -> {
            ready.run();
            keep[2] = List.of(dummy(EntityTypes.HUSK, 3, 0, false), dummy(EntityTypes.HUSK, 8, 0, false));
            ok[0] = castTome(Spell.EARTHSHATTER);
        });
        at(42, () -> num[2] = EarthSpells.live("shards"));
        at(80, () -> {
            @SuppressWarnings("unchecked")
            List<Mob> line = (List<Mob>) keep[2];
            long struck = line.stream().filter(SelfTest::hurt).count();
            num[3] = EarthSpells.live("shards");
            check(
                    "spell_earthshatter",
                    ok[0] && num[2] > 2 && struck == 2 && num[3] > 0,
                    "cast=" + ok[0] + " spikes=" + num[2] + " struck=" + struck + "/2 standing=" + num[3]);
            sweep.run();
        });
        // The spikes stand 3 s, then sink and are gone.
        at(
                130,
                () -> check(
                        "spell_earthshatter_sinks",
                        EarthSpells.live("shards") == 0 && SpellKit.live("displays") == 0,
                        "left=" + SpellKit.live("displays")));

        // Siphon: threads on the two husks (and the pig behind), drained life heals the caster.
        at(82, () -> {
            ready.run();
            keep[0] = List.of(dummy(EntityTypes.HUSK, 4, 2, false), dummy(EntityTypes.HUSK, 4, -2, false));
            dummy(EntityTypes.PIG, -4, 0, false);
            caster.setHealth(4f);
            ok[0] = castTome(Spell.SIPHON);
            num[0] = HexSpells.live("tethers");
        });
        at(108, () -> {
            @SuppressWarnings("unchecked")
            List<Mob> pair = (List<Mob>) keep[0];
            long drained = pair.stream().filter(SelfTest::hurt).count();
            check(
                    "spell_siphon",
                    ok[0] && num[0] == 3 && drained == 2 && caster.getHealth() > 4f,
                    "cast=" + ok[0] + " threads=" + num[0] + " drained=" + drained + " health=" + caster.getHealth());
            caster.setHealth(caster.getMaxHealth());
            sweep.run();
        });

        // Contagion: the target and its neighbour sicken, the one further off does not; when the first dies, it
        // catches.
        at(110, () -> {
            ready.run();
            Mob a = dummy(EntityTypes.HUSK, 6, 0, false),
                    b = dummy(EntityTypes.HUSK, 6, 2, false),
                    c = dummy(EntityTypes.HUSK, 6, -4.2, false);
            boolean cast = castTome(Spell.CONTAGION);
            boolean first = HexSpells.infected(a) && HexSpells.infected(b) && !HexSpells.infected(c);
            a.kill(level);
            boolean spread = HexSpells.infected(c);
            check("spell_contagion", cast && first && spread, "cast=" + cast + " first=" + first + " spread=" + spread);
        });
        at(135, () -> {
            long sick = DUMMIES.stream().filter(m -> m.isAlive() && hurt(m)).count();
            check("spell_contagion_hurts", sick == 2, sick + "/2");
            sweep.run();
        });

        // Spirit Wolves: two tame spirit wolves with a tame wolf's health and the spell's bite, hunting the husk
        // nearby;
        // casting again keeps the pack at two.
        at(162, () -> {
            ready.run();
            keep[0] = dummy(EntityTypes.HUSK, 6, 4, false);
            caster.removeAttached(ModData.BOUND_WOLVES);
            ok[0] = castTome(Spell.SPIRIT_WOLVES);
            double bite = Math.max(1, Spells.damage(caster, Spell.SPIRIT_WOLVES));
            var wolves = level.getEntitiesOfClass(SpiritWolf.class, new AABB(base).inflate(6));
            ok[1] = wolves.size() == 2
                    && wolves.stream()
                            .allMatch(w -> w.getOwner() == caster
                                    && !w.isInvulnerable()
                                    && w.getMaxHealth() >= 39.9f
                                    && Math.abs(w.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) - bite) < 1e-6
                                    && !Spells.canHitArea(caster, w));
            num[0] = wolves.size();
            ready.run();
            castTome(Spell.SPIRIT_WOLVES);
            ok[2] = level.getEntitiesOfClass(SpiritWolf.class, new AABB(base).inflate(8), w -> w.isAlive())
                            .size()
                    == 2;
        });
        at(190, () -> {
            Mob prey = (Mob) keep[0];
            boolean hunting = !level.getEntitiesOfClass(
                            SpiritWolf.class, new AABB(base).inflate(20), w -> w.getTarget() == prey || !prey.isAlive())
                    .isEmpty();
            check(
                    "spell_spirit_wolves",
                    ok[0] && ok[1] && hunting && SummonSpells.live("wolves") == 2,
                    "cast=" + ok[0] + " wolves=" + num[0] + " tame=" + ok[1] + " hunting=" + hunting);
            // One falls: the next cast raises one more, never a third.
            var pack = level.getEntitiesOfClass(SpiritWolf.class, new AABB(base).inflate(30), w -> w.isAlive());
            if (!pack.isEmpty()) pack.get(0).kill(level);
            ready.run();
            boolean recast = castTome(Spell.SPIRIT_WOLVES);
            long alive = level.getEntitiesOfClass(SpiritWolf.class, new AABB(base).inflate(30), w -> w.isAlive())
                    .size();
            int listed =
                    caster.getAttachedOrElse(ModData.BOUND_WOLVES, List.of()).size();
            check(
                    "spell_spirit_wolves_pack",
                    ok[2] && recast && alive == 2 && listed == 2,
                    "recast keeps two=" + ok[2] + " after a fall=" + alive + " listed=" + listed);
            for (SpiritWolf w : level.getEntitiesOfClass(SpiritWolf.class, new AABB(base).inflate(40))) w.discard();
            caster.removeAttached(ModData.BOUND_WOLVES);
            SpellSchools.clear();
            sweep.run();
        });

        // Mirror Ward: a husk's blow lands at half, and the husk takes it back.
        at(192, () -> {
            ready.run();
            Mob brute = dummy(EntityTypes.HUSK, 2, 0, false);
            boolean cast = castTome(Spell.MIRROR_WARD);
            var blow = level.damageSources().mobAttack(brute);
            boolean soak = Math.abs(ArcaneSpells.wardSoak(caster, blow, 10f) - 5f) < 1e-4
                    && ArcaneSpells.wardSoak(caster, level.damageSources().fall(), 10f) == 10f;
            // The fake player refuses all damage, so hand the ward the landed hit the way the after-damage hook does.
            float before = brute.getHealth();
            ArcaneSpells.afterDamage(caster, blow, 2f, false);
            float reflected = before - brute.getHealth();
            boolean back = reflected > 0;
            // Spell hits ignore the hurt cooldown: two in a row both land in full (crits only add).
            Mob twice = dummy(EntityTypes.HUSK, 3, -3, false);
            float h0 = twice.getHealth();
            Spells.hit(caster, twice, 10);
            float h1 = twice.getHealth();
            Spells.hit(caster, twice, 10);
            float h2 = twice.getHealth();
            check("spell_hits_ignore_cooldown", h0 - h1 >= 9.99f && h1 - h2 >= 9.99f, (h0 - h1) + " then " + (h1 - h2));
            check(
                    "spell_mirror_ward",
                    cast && soak && back && ArcaneSpells.warded(caster),
                    "cast=" + cast + " soak=" + soak + " reflected=" + back);
            caster.setHealth(caster.getMaxHealth());
            sweep.run();
        });

        // Updraft: the caster is thrown up with Slow Falling, a husk beside is shoved away.
        at(194, () -> {
            ready.run();
            Mob near = dummy(EntityTypes.HUSK, 2, 2, false);
            boolean cast = castTome(Spell.UPDRAFT);
            double up = caster.getDeltaMovement().y;
            var push = near.getDeltaMovement();
            boolean shoved = push.x > 0.4 && push.z > 0.4;
            check(
                    "spell_updraft",
                    cast
                            && Math.abs(up - TravelSpells.UPDRAFT_LIFT) < 1e-6
                            && caster.hasEffect(MobEffects.SLOW_FALLING)
                            && shoved,
                    "cast=" + cast + " up=" + up + " shove=" + String.format("%.2f,%.2f", push.x, push.z));
            caster.removeEffect(MobEffects.SLOW_FALLING);
            sweep.run();
        });

        // Gravity Well: aimed at the floor 8 blocks ahead, it drags a pig 6 blocks off into its heart, then collapses.
        at(196, () -> {
            ready.run();
            caster.setXRot((float) Math.toDegrees(Math.atan2(caster.getEyeHeight(), 8)));
            Mob pig = dummy(EntityTypes.PIG, 8, 6, true);
            keep[1] = pig;
            ok[0] = castTome(Spell.GRAVITY_WELL);
            num[0] = ArcaneSpells.live("wells");
            num[1] = Math.hypot(pig.getX() - (base.getX() + 8.5), pig.getZ() - (base.getZ() + 0.5));
        });
        at(221, () -> {
            Mob pig = (Mob) keep[1];
            num[2] = Math.hypot(pig.getX() - (base.getX() + 8.5), pig.getZ() - (base.getZ() + 0.5));
        });
        at(262, () -> {
            check(
                    "spell_gravity_well",
                    ok[0]
                            && num[0] == 1
                            && num[2] < num[1] - 2
                            && ArcaneSpells.live("wells") == 0
                            && SpellKit.live("displays") == 0,
                    "cast=" + ok[0] + " wells=" + num[0] + String.format(" pig %.1f -> %.1f blocks", num[1], num[2])
                            + " left=" + SpellKit.live("displays"));
            sweep.run();
            SpellSchools.clear();
            ready.run();
        });
        Scheduler.later(264, () -> {
            crit.removeModifier(fixedRoll);
            done.run();
        });
    }

    /** A darkness zombie is a zombie in every way but looks: same attributes, burns by day, levelled like any monster. */
    private static void testDarkness() {
        var dark = ModEntities.DARKNESS_ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        var plain = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        boolean same = dark instanceof Zombie
                && dark.getMaxHealth() == plain.getMaxHealth()
                && dark.getAttributeBaseValue(Attributes.ATTACK_DAMAGE)
                        == plain.getAttributeBaseValue(Attributes.ATTACK_DAMAGE)
                && dark.getAttributeBaseValue(Attributes.MOVEMENT_SPEED)
                        == plain.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        dark.snapTo(base.getX() + 6.5, base.getY(), base.getZ() + 6.5, 0f, 0f);
        level.addFreshEntity(dark);
        var info = Monsters.info(dark);
        check(
                "darkness_zombie",
                same && info != null,
                dark.getMaxHealth() + " vs " + plain.getMaxHealth() + " level=" + info);
        dark.discard();
        plain.discard();
    }

    /** Rain makes crops grow: wheat out in the rain grows, the same wheat with the rain off does not. */
    private static void testRainGrowth() {
        Object seasonal = null;
        int previousSeason = 0;
        try {
            var cls = Class.forName("sereneseasons.season.SeasonHandler");
            seasonal = cls.getMethod("getSeasonSavedData", Level.class).invoke(null, level);
            var state = Class.forName("sereneseasons.api.season.SeasonHelper")
                    .getMethod("getSeasonState", Level.class)
                    .invoke(null, level);
            int duration =
                    (int) state.getClass().getMethod("getSubSeasonDuration").invoke(state);
            previousSeason = seasonal.getClass().getField("seasonCycleTicks").getInt(seasonal);
            seasonal.getClass().getField("seasonCycleTicks").setInt(seasonal, 4 * duration);
        } catch (ClassNotFoundException absent) {
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }

        BlockPos field = base.offset(-14, 0, 12);
        for (int x = 0; x < 4; x++) {
            level.setBlockAndUpdate(field.offset(x, -1, 0), Blocks.FARMLAND.defaultBlockState());
            level.setBlockAndUpdate(field.offset(x, 0, 0), Blocks.WHEAT.defaultBlockState());
            for (int y = 1; y < 6; y++) level.setBlockAndUpdate(field.offset(x, y, 0), Blocks.AIR.defaultBlockState());
        }
        var chunk = level.getChunkAt(field);
        IntSupplier grown = () -> {
            int n = 0;
            for (int x = 0; x < 4; x++)
                n += level.getBlockState(field.offset(x, 0, 0)).getValue(CropBlock.AGE);
            return n;
        };
        float rainBefore = level.getRainLevel(1f);
        level.setRainLevel(0f);
        for (int i = 0; i < 120; i++) RainGrowth.growIn(level, chunk, 4096, level.getRandom());
        int dry = grown.getAsInt();
        level.setRainLevel(1f);
        boolean rains = level.isRainingAt(field.above());
        for (int i = 0; i < 120; i++) RainGrowth.growIn(level, chunk, 4096, level.getRandom());
        int wet = grown.getAsInt();
        level.setRainLevel(rainBefore);
        if (seasonal != null)
            try {
                seasonal.getClass().getField("seasonCycleTicks").setInt(seasonal, previousSeason);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        check(
                "rain_grows_crops",
                dry == 0
                        && wet >= 8
                        && RainGrowth.isCrop(Blocks.WHEAT.defaultBlockState())
                        && !RainGrowth.isCrop(Blocks.GRASS_BLOCK.defaultBlockState()),
                "dry=" + dry + " wet=" + wet + " raining there=" + rains);
        for (int x = 0; x < 4; x++) {
            level.setBlockAndUpdate(field.offset(x, 0, 0), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(field.offset(x, -1, 0), Blocks.DIRT.defaultBlockState());
        }
    }

    /** Felling, Herd Call, Forge Fire, Gathering and Skies (Homeward waits seconds before it moves you: not tested here). */
    private static void testNewSpells(Supplier<Boolean> fresh) {
        // A small oak to the west on the row z-5: four logs and a crown of natural leaves. Felling drops it at your
        // feet.
        caster.setPos(base.getX() - 6.5, base.getY(), base.getZ() - 4.5);
        caster.setYRot(90f);
        caster.setYHeadRot(90f);
        caster.setXRot(0f);
        BlockPos trunk = base.offset(-9, 0, -5);
        var leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, false);
        for (int y = 0; y < 4; y++) level.setBlockAndUpdate(trunk.above(y), Blocks.OAK_LOG.defaultBlockState());
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                for (int y = 3; y <= 4; y++) {
                    if (x == 0 && z == 0 && y == 3) continue;
                    level.setBlockAndUpdate(trunk.offset(x, y, z), leaf);
                }
        // A log cabin wall beside it: no leaves, so Felling must refuse it.
        BlockPos wall = base.offset(-9, 0, -8);
        fresh.get();
        caster.setPos(base.getX() - 6.5, base.getY(), base.getZ() - 7.5);
        for (int y = 0; y < 3; y++) level.setBlockAndUpdate(wall.above(y), Blocks.OAK_LOG.defaultBlockState());
        boolean cabin = castTome(Spell.FELLING);
        for (int y = 0; y < 3; y++) level.setBlockAndUpdate(wall.above(y), Blocks.AIR.defaultBlockState());
        caster.setPos(base.getX() - 6.5, base.getY(), base.getZ() - 4.5);
        fresh.get();
        boolean felled = castTome(Spell.FELLING);
        int logsLeft = 0, logItems = 0;
        for (int y = 0; y < 4; y++) if (level.getBlockState(trunk.above(y)).is(Blocks.OAK_LOG)) logsLeft++;
        for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(caster.blockPosition()).inflate(2))) {
            if (item.getItem().is(Items.OAK_LOG)) logItems += item.getItem().getCount();
            item.discard();
        }
        check(
                "spell_felling",
                felled && !cabin && logsLeft == 0 && logItems == 4,
                "cabin=" + cabin + " left=" + logsLeft + " drops=" + logItems);
        for (BlockPos p : BlockPos.betweenClosed(trunk.offset(-1, 0, -1), trunk.offset(1, 4, 1)))
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());

        // Herd Call: two grown cows fall in love.
        List<Animal> herd = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
            cow.snapTo(caster.getX() + 3 + i, caster.getY(), caster.getZ() + 2, 0f, 0f);
            level.addFreshEntity(cow);
            herd.add(cow);
        }
        fresh.get();
        boolean called = castTome(Spell.HERD_CALL);
        check("spell_herd_call", called && herd.stream().allMatch(Animal::isInLove), null);
        for (var cow : herd) cow.discard();

        // Forge Fire: raw iron and a raw beef cook; the logs carried beside them are left alone.
        ItemStack held = caster.getMainHandItem().copy();
        caster.getInventory().clearContent();
        caster.setItemInHand(InteractionHand.MAIN_HAND, held.copy());
        caster.getInventory().setItem(3, new ItemStack(Items.RAW_IRON, 3));
        caster.getInventory().setItem(4, new ItemStack(Items.BEEF, 1));
        caster.getInventory().setItem(5, new ItemStack(Items.OAK_LOG, 5));
        fresh.get();
        boolean forged = castTome(Spell.FORGE_FIRE);
        int ingots = 0, steak = 0, logs = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = caster.getInventory().getItem(i);
            if (s.is(Items.IRON_INGOT)) ingots += s.getCount();
            if (s.is(Items.COOKED_BEEF)) steak += s.getCount();
            if (s.is(Items.OAK_LOG)) logs += s.getCount();
        }
        caster.getInventory().clearContent();
        caster.setItemInHand(InteractionHand.MAIN_HAND, held.copy());
        fresh.get();
        boolean nothing = castTome(Spell.FORGE_FIRE);
        check(
                "spell_forge_fire",
                forged && ingots == 3 && steak == 1 && logs == 5 && !nothing,
                ingots + " ingots, " + steak + " steak, " + logs + " logs");
        for (var orb : level.getEntitiesOfClass(ExperienceOrb.class, new AABB(caster.blockPosition()).inflate(4)))
            orb.discard();

        // Gathering: a dropped stack 9 blocks off lands at your feet.
        var loose =
                new ItemEntity(level, caster.getX() + 9, caster.getY(), caster.getZ(), new ItemStack(Items.STICK, 3));
        loose.setPickUpDelay(32767);
        level.addFreshEntity(loose);
        fresh.get();
        boolean gathered = castTome(Spell.GATHERING);
        double away = loose.distanceTo(caster);
        check("spell_gathering", gathered && away < 1.5, away);
        loose.discard();

        // Skies: a clear sky turns to rain, and the rain clears again.
        boolean wasRaining = level.getWeatherData().isRaining();
        level.getServer().setWeatherParameters(6000, 0, false, false);
        fresh.get();
        boolean rain = castTome(Spell.SKIES) && level.getWeatherData().isRaining();
        fresh.get();
        boolean clear = castTome(Spell.SKIES) && !level.getWeatherData().isRaining();
        check("spell_skies", rain && clear, "rain=" + rain + " clear=" + clear);
        if (wasRaining) level.getServer().setWeatherParameters(0, 6000, true, false);
        else level.getServer().setWeatherParameters(0, 0, false, false);
    }
}
