package lightborn;

import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;
import static lightborn.SpellTestKit.*;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The work school's spells ({@link WorkSpells}), each cast for real by the test player on the
 * spell field, facing east.
 */
final class SelfTestWorkSpells {
    private SelfTestWorkSpells() {}

    static void run() {
        try {
            owlSight();
            wayglow();
            quickening();
            lightBore();
            transmute();
            enderPocket();
            workbench();
        } finally {
            clean();
            faceFresh();
        }
    }

    /** Owl Sight shows a monster through walls. */
    private static void owlSight() {
        face();
        var skeleton = mob(EntityTypes.SKELETON, 12, 0);
        boolean sight = cast(Spell.OWL_SIGHT);
        check(
                "spell_owl_sight_shows_monsters",
                sight && WorkSpells.sees(caster, skeleton) && caster.hasEffect(MobEffects.NIGHT_VISION),
                "sees=" + WorkSpells.sees(caster, skeleton));
        caster.removeEffect(MobEffects.NIGHT_VISION);
        clean();
    }

    /** A mote flies to the wall in your aim and stays there as a block of light. */
    private static void wayglow() {
        face();
        BlockPos wall = caster.blockPosition().offset(6, 1, 0), cell = wall.west();
        BlockState was = level.getBlockState(wall), cellWas = level.getBlockState(cell);
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        boolean thrown = cast(Spell.WAYGLOW);
        for (int i = 0; i < 20 && WorkSpells.live("motes") > 0; i++) WorkSpells.tickMotes();
        boolean lit = level.getBlockState(cell).is(GlowMote.MOTE);
        level.setBlockAndUpdate(cell, cellWas);
        level.setBlockAndUpdate(wall, was);
        check(
                "spell_wayglow_leaves_a_light",
                thrown && lit,
                "at " + cell.toShortString() + ": " + level.getBlockState(cell));
    }

    /** A calf grows up; with no young near, it refuses. */
    private static void quickening() {
        face();
        var calf = mob(EntityTypes.COW, 3, 1);
        calf.setBaby(true);
        boolean baby = calf.isBaby();
        boolean cast = cast(Spell.QUICKENING);
        boolean grown = !calf.isBaby();
        boolean refuses = !cast(Spell.QUICKENING);
        check(
                "spell_quickening_grows_the_young",
                baby && cast && grown && refuses,
                "was baby=" + baby + " grown=" + grown + " refuses=" + refuses);
        clean();
    }

    /** The beam bores 16 blocks of a stone run, two high, and the iron ore in it lands at your feet. */
    private static void lightBore() {
        face();
        BlockPos feet = caster.blockPosition();
        var cells = new ArrayList<BlockPos>();
        for (int d = 1; d <= WorkSpells.BORE_LENGTH + 2; d++)
            for (int y = 0; y <= 1; y++) cells.add(feet.east(d).above(y));
        var was = cells.stream().map(level::getBlockState).toList();
        for (var c : cells) level.setBlockAndUpdate(c, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(feet.east(5), Blocks.IRON_ORE.defaultBlockState());
        var lying = level.getEntitiesOfClass(ItemEntity.class, new AABB(feet).inflate(4), e -> true);
        boolean cast = cast(Spell.LIGHT_BORE);
        boolean bored = true;
        for (int d = 1; d <= WorkSpells.BORE_LENGTH; d++)
            bored &= level.getBlockState(feet.east(d)).isAir()
                    && level.getBlockState(feet.east(d).above()).isAir();
        boolean stops =
                level.getBlockState(feet.east(WorkSpells.BORE_LENGTH + 1)).is(Blocks.STONE);
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(feet).inflate(4), e -> !lying.contains(e));
        boolean iron = drops.stream().anyMatch(e -> e.getItem().is(Items.RAW_IRON));
        drops.forEach(ItemEntity::discard);
        for (int i = 0; i < cells.size(); i++) level.setBlockAndUpdate(cells.get(i), was.get(i));
        check(
                "spell_light_bore_bores_a_tunnel",
                cast && bored && stops && iron,
                "bored=" + bored + " stops=" + stops + " iron=" + iron + " drops=" + drops.size());
    }

    /**
     * Every solid block within 10 blocks of you turns to coal ore, and only coal with no upgrade; a chest, an ore
     * already there and anything farther than 10 blocks stay. The field is put back block for block.
     */
    private static void transmute() {
        faceHealed();
        BlockPos at = caster.blockPosition();
        int r = (int) WorkSpells.TRANSMUTE_RADIUS + 2;
        java.util.Map<BlockPos, BlockState> before = new java.util.HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-r, -r, -r), at.offset(r, r, r)))
            before.put(pos.immutable(), level.getBlockState(pos));
        BlockPos dirt = at.offset(4, -1, 0), chest = at.offset(-3, 0, 2), ore = at.offset(0, -1, 3);
        BlockPos far = at.offset(11, -1, 0);
        level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.STONE.defaultBlockState());
        boolean cast = cast(Spell.TRANSMUTE);
        int coal = 0, other = 0;
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-r, -r, -r), at.offset(r, r, r))) {
            BlockState s = level.getBlockState(pos);
            if (s.is(Blocks.COAL_ORE) || s.is(Blocks.DEEPSLATE_COAL_ORE)) coal++;
            if (!pos.equals(ore)) for (var v : WorkSpells.VEINS) if (s.is(v.ore()) || s.is(v.deep())) other++;
        }
        boolean dirtTurned = level.getBlockState(dirt).is(Blocks.COAL_ORE),
                chestKept = level.getBlockState(chest).is(Blocks.CHEST),
                oreKept = level.getBlockState(ore).is(Blocks.IRON_ORE),
                farKept = level.getBlockState(far).is(Blocks.STONE);
        // Put the test field back as it was.
        level.removeBlockEntity(chest);
        before.forEach((pos, s) -> {
            if (level.getBlockState(pos) != s) level.setBlock(pos, s, 2);
        });
        check(
                "spell_transmute_turns_blocks_round_you",
                cast && coal > 20 && other == 0 && dirtTurned && chestKept && oreKept && farKept,
                "coal=" + coal + " other ores=" + other + " dirt=" + dirtTurned + " chest kept=" + chestKept
                        + " ore kept=" + oreKept + " far kept=" + farKept);
        clean();
    }

    /** Ender Pocket opens your ender chest wherever you are. */
    private static void enderPocket() {
        faceFresh();
        boolean pocket = cast(Spell.ENDER_POCKET);
        caster.closeContainer();
        check("spell_ender_pocket_opens", pocket, "pocket=" + pocket);
    }

    /** Workbench opens a crafting table and steps through its stations. */
    private static void workbench() {
        faceFresh();
        boolean bench = cast(Spell.WORKBENCH);
        caster.closeContainer();
        check("spell_workbench_opens", bench, "bench=" + bench);
        // The arrows step round all eight stations, each a menu that knows it is the spell's and stays open with no
        // block there; what is left in one comes back to you when you step on (as a real block gives it back).
        var all = Workbench.Station.values();
        boolean steps = all.length == 8
                && Workbench.Station.CRAFTING.step(1, 255) == Workbench.Station.STONECUTTER
                && Workbench.Station.CRAFTING.step(-1, 255) == Workbench.Station.ENCHANTING
                && Workbench.Station.ENCHANTING.step(1, 255) == Workbench.Station.CRAFTING
                // Only what the upgrades unlocked: crafting, loom and anvil here, so it skips the rest.
                && Workbench.Station.CRAFTING.step(1, 1 | 1 << 4 | 1 << 6) == Workbench.Station.LOOM
                && Workbench.Station.CRAFTING.step(-1, 1 | 1 << 4 | 1 << 6) == Workbench.Station.ANVIL
                && Workbench.Station.CRAFTING.step(1, 1) == Workbench.Station.CRAFTING
                // With no upgrade in use, the tome holds the crafting table alone.
                && Workbench.unlocked(caster) == 1;
        var access = net.minecraft.world.inventory.ContainerLevelAccess.create(level, caster.blockPosition());
        List<String> wrong = new ArrayList<>();
        for (var s : all) {
            var m = Workbench.menu(s, 1, caster.getInventory(), access);
            if (!(m instanceof Workbench.Bench b) || b.station() != s || !m.stillValid(caster)) wrong.add(s.name());
        }
        int given = 0;
        var inv = caster.getInventory();
        List<ItemStack> kept = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) kept.add(inv.getItem(i).copy());
        for (var s : List.of(Workbench.Station.CRAFTING, Workbench.Station.ANVIL, Workbench.Station.ENCHANTING)) {
            var m = Workbench.menu(s, 1, caster.getInventory(), access);
            caster.getInventory().clearContent();
            m.getSlot(s == Workbench.Station.CRAFTING ? 1 : 0)
                    .set(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));
            m.removed(caster);
            if (caster.getInventory().countItem(net.minecraft.world.item.Items.STICK) == 1) given++;
            caster.getInventory().clearContent();
        }
        // Give the caster back its staff and the rest, or every later spell has nothing to cast with.
        for (int i = 0; i < kept.size(); i++) inv.setItem(i, kept.get(i));
        check(
                "spell_workbench_steps_through_stations",
                steps && wrong.isEmpty() && given == 3,
                "steps=" + steps + " wrong=" + wrong + " items given back " + given + " of 3");
        clean();
    }
}
