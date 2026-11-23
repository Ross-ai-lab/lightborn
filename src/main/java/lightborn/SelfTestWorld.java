package lightborn;

import static lightborn.SelfTest.base;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;

import com.mojang.authlib.GameProfile;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/** Self-test checks for the spawn hut, regions, lore and loot beams. */
final class SelfTestWorld {
    private SelfTestWorld() {}

    /**
     * The starting Spellstone: the Bolt on right-click and one random Basic tome beside it, nothing else (owner,
     * 2026-11-23).
     */
    private static boolean starterStone(ItemStack s) {
        if (!Spellstone.is(s)) return false;
        var tomes = Spellstone.tomes(s);
        var second = TomeItem.spellOf(tomes.get(1));
        return TomeItem.spellOf(tomes.get(0)) == Spell.ARCANE_BOLT
                && second != null
                && second.tier == 1
                && second != Spell.ARCANE_BOLT
                && second != Spell.ENDER_POCKET
                && TomeItem.spellOf(tomes.get(2)) == Spell.HOMEWARD
                && TomeItem.spellOf(tomes.get(3)) == Spell.ENDER_POCKET;
    }

    /** A great monster is tracked for its first-sight horn. */
    static void testWilds() {
        Entity mutant = BuiltInRegistries.ENTITY_TYPE
                .getValue(Identifier.parse("mutantmonsters:mutant_zombie"))
                .create(level, EntitySpawnReason.NATURAL);
        if (mutant instanceof Mob m) {
            int before = Wilds.greatCount();
            Wilds.trackGreat(m);
            check("wilds_great_tracked", Wilds.greatCount() == before + 1, Wilds.greatCount());
            Wilds.clear();
        } else {
            check("wilds_great_tracked", false, "mutant missing");
        }
    }

    /** The journal: twelve entries as books, in structure chests at about CHEST_CHANCE, and the Bestiary tab loads. */
    static void testLore() {
        check("story_nine_spoken_chapters", DragonStory.TITLES.size() == 9 && DragonStory.TEXT.size() == 9, null);
        check(
                "keeper_notes_have_two_authors",
                !DragonStory.keeperNote(true)
                        .get(DataComponents.WRITTEN_BOOK_CONTENT)
                        .author()
                        .equals(DragonStory.keeperNote(false)
                                .get(DataComponents.WRITTEN_BOOK_CONTENT)
                                .author()),
                null);
        BlockPos at = base.offset(-4, 0, -8);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        StringBuilder rates = new StringBuilder(), tomeRates = new StringBuilder();
        boolean ok = true, tomesOk = true;
        for (String table : new String[] {"minecraft:chests/simple_dungeon", "minecraft:chests/desert_pyramid"}) {
            int found = 0, tomes = 0, rolls = 300;
            for (int i = 0; i < rolls; i++) {
                if (!(level.getBlockEntity(at) instanceof ChestBlockEntity chest)) break;
                chest.clearContent();
                chest.setLootTable(
                        ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(table)),
                        level.getRandom().nextLong());
                chest.unpackLootTable(null);
                for (int s = 0; s < chest.getContainerSize(); s++) {
                    WrittenBookContent c = chest.getItem(s).get(DataComponents.WRITTEN_BOOK_CONTENT);
                    if (c != null && c.title().raw().startsWith("The Dragon's Call")) found++;
                    if (TomeItem.spellOf(chest.getItem(s)) != null) tomes++;
                }
            }
            double rate = found / (double) rolls, tomeRate = tomes / (double) rolls;
            ok &= found == 0;
            tomesOk &= tomeRate > 0.07 && tomeRate < 0.25;
            rates.append(String.format("%s=%.0f%% ", table.substring(table.indexOf(':') + 1), rate * 100));
            tomeRates.append(String.format("%s=%.0f%% ", table.substring(table.indexOf(':') + 1), tomeRate * 100));
        }
        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
        check("no_random_journal_in_chests", ok, rates.toString().trim());
        check("tomes_in_chests", tomesOk, tomeRates.toString().trim());

        // Any tome from any monster (owner, 2026-11-16: no levels); drop weights keep the strong ones rarer.
        Set<Spell> seenHigh = EnumSet.noneOf(Spell.class);
        // Enough draws that the rarest of 90 tomes never goes unseen by chance (2000 missed one, 2026-11-23).
        for (int i = 0; i < 6000; i++) seenHigh.add(TomeItem.spellOf(TomeItem.roll(level.getRandom())));
        check(
                "tome_drops_from_any_monster",
                // Every tome drops: the Book of Souls since 2026-11-18, Eclipse since 2026-11-22.
                seenHigh.contains(Spell.METEOR)
                        && seenHigh.size() == Spell.values().length
                        && seenHigh.contains(Spell.ECLIPSE)
                        && seenHigh.contains(Spell.SOULS),
                "high=" + seenHigh.size());
        // Spell tiers (owner, 2026-11-18): a tier-1 monster (a zombie) never drops a spell above tier 1, a tier-5 one
        // can drop Flight, and every tier is rarer than the one below.
        Set<Spell> fromZombie = EnumSet.noneOf(Spell.class);
        for (int i = 0; i < 2000; i++) fromZombie.add(TomeItem.spellOf(TomeItem.roll(level.getRandom(), 1)));
        boolean flightFromStrong = false;
        for (int i = 0; i < 4000 && !flightFromStrong; i++)
            flightFromStrong = TomeItem.spellOf(TomeItem.roll(level.getRandom(), 5)) == Spell.FLIGHT;
        var zombieKind = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        int zombieTier = Souls.tierOf(zombieKind);
        zombieKind.discard();
        boolean rarer = true;
        for (Spell s : Spell.values())
            for (Spell t : Spell.values())
                if (s.drops && t.drops && s.tier < t.tier) rarer &= s.dropWeight > t.dropWeight;
        check(
                "tome_drops_follow_spell_tiers",
                zombieTier == 1
                        && fromZombie.stream().allMatch(s -> s.tier == 1)
                        && fromZombie.size()
                                == (int) java.util.Arrays.stream(Spell.values())
                                        .filter(s -> s.tier == 1 && s.drops)
                                        .count() // every tier-1 tome
                        && flightFromStrong
                        && Spell.FLIGHT.tier == 5
                        && rarer,
                "zombieTier=" + zombieTier + " fromZombie=" + fromZombie + " flight=" + flightFromStrong + " rarer="
                        + rarer);
        int recipes = 0;
        var badRecipes = new java.util.ArrayList<String>();
        for (var r : level.getServer().getRecipeManager().getRecipes()) {
            var id = r.id().identifier();
            if (!id.getNamespace().equals(Lightborn.MOD_ID) || !id.getPath().startsWith("tome_")) continue;
            recipes++;
            // Every tome recipe fills all nine slots, and pays its tier's price besides the book (owner, 2026-11-18).
            Spell spell = null;
            for (Spell s : Spell.values()) if (s.tomeId().equals(id.getPath())) spell = s;
            if (!(r.value() instanceof net.minecraft.world.item.crafting.ShapelessRecipe shapeless) || spell == null) {
                badRecipes.add(id.getPath() + " not shapeless");
                continue;
            }
            var names = new java.util.ArrayList<String>();
            for (var ing : shapeless.placementInfo().ingredients())
                names.add(ing.items()
                        .findFirst()
                        .map(h -> h.value()
                                .builtInRegistryHolder()
                                .key()
                                .identifier()
                                .getPath())
                        .orElse("?"));
            var rest = new java.util.ArrayList<>(names);
            boolean pays = rest.remove("book");
            for (String price : Spell.recipePrice(spell.tier)) pays &= rest.remove(price);
            if (names.size() != 9 || !pays) badRecipes.add(id.getPath() + " " + names);
        }
        check("tome_recipes", recipes == Spell.values().length, recipes);
        check("tome_recipes_fill_nine_by_tier", badRecipes.isEmpty(), badRecipes);
        // The unique starter chest no longer has a crafting recipe.
        var torch = new ItemStack(Items.TORCH);
        var fourTorches = CraftingInput.of(2, 2, List.of(torch, torch, torch, torch));
        var chestRecipe = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, fourTorches, level);
        check(
                "light_chest_not_craftable",
                chestRecipe.isEmpty()
                        || !chestRecipe.get().id().identifier().toString().equals("lightborn:light_chest"),
                chestRecipe.map(r -> r.id().identifier().toString()).orElse("none"));
        check("lightborn_recipes_clash_with_none", recipeClashes(level).isEmpty(), recipeClashes(level));

        int bestiary = 0;
        for (var adv : level.getServer().getAdvancements().getAllAdvancements()) {
            if (adv.id().getNamespace().equals(Lightborn.MOD_ID)
                    && adv.id().getPath().startsWith("bestiary/")) bestiary++;
        }
        check("bestiary_removed", bestiary == 0, bestiary);
    }

    /** Loot beams: tagging, tracking, legendary glow, re-tracking on chunk load, dropping removed items. */
    static void testBeams() {
        for (int i = 0; i < 4; i++) LootBeams.tick(); // purge expired items from earlier encounter fixtures
        int n0 = LootBeams.liveCount();
        ItemEntity leg = loose(base.offset(-6, 1, 6));
        LootBeams.onDrop(leg, LootRoller.Rarity.LEGENDARY);
        ItemEntity magic = loose(base.offset(-6, 1, -6));
        LootBeams.onDrop(magic, LootRoller.Rarity.UNCOMMON);
        check("beam_tracks", LootBeams.liveCount() == n0 + 2, n0 + " -> " + LootBeams.liveCount());
        check(
                "beam_rarity_tag",
                LootBeams.rarityOf(leg) == LootRoller.Rarity.LEGENDARY.ordinal()
                        && LootBeams.rarityOf(magic) == LootRoller.Rarity.UNCOMMON.ordinal(),
                LootBeams.rarityOf(leg) + "/" + LootBeams.rarityOf(magic));
        check("beam_legendary_glows", leg.hasGlowingTag() && !magic.hasGlowingTag(), null);
        // An item that comes back with its chunk carries only the tag; onLoad must pick it up again.
        ItemEntity reloaded = loose(base.offset(-8, 1, 0));
        reloaded.addTag(LootBeams.TAG + LootRoller.Rarity.RARE.ordinal());
        int n1 = LootBeams.liveCount();
        LootBeams.onLoad(reloaded);
        LootBeams.onLoad(loose(base.offset(-9, 1, 0))); // untagged: ignored
        check("beam_reload", LootBeams.liveCount() == n1 + 1, n1 + " -> " + LootBeams.liveCount());
        leg.discard();
        magic.discard();
        reloaded.discard();
        for (int i = 0; i < 4; i++) LootBeams.tick();
        check("beam_untracks_removed", LootBeams.liveCount() == n0, n0 + " vs " + LootBeams.liveCount());
    }

    static ItemEntity loose(BlockPos p) {
        ItemEntity e = new ItemEntity(level, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, new ItemStack(Items.STICK));
        e.setPickUpDelay(32767);
        level.addFreshEntity(e);
        return e;
    }

    /**
     * The spawn hut end to end, as a brand-new player joining a brand-new world would get it: one hut,
     * respawn moved inside it, the player brought in once, the lectern book, the stocked chest, the bed.
     */
    /** Open for a body: air, or the ward's invisible light fill. */
    private static boolean open(net.minecraft.world.level.block.state.BlockState s) {
        return s.isAir() || s.is(net.minecraft.world.level.block.Blocks.LIGHT);
    }

    static void testHut() {
        check("hut_not_built_yet", level.getAttached(ModData.HUT) == null, level.getAttached(ModData.HUT));
        BlockPos oldSpawn = level.getRespawnData().pos();
        FakePlayer joiner = FakePlayer.get(
                level, new GameProfile(UUID.fromString("6e0b8d1c-6f6a-4c1e-9d1e-2b7a5c3f9a10"), "HutTester"));
        joiner.setPos(oldSpawn.getX() + 0.5, 250, oldSpawn.getZ() + 0.5);
        SpawnHut.onJoin(joiner);
        check(
                "start_mana",
                Progression.maxMana(joiner) == 100 && Progression.mana(joiner) == 100,
                Progression.mana(joiner) + "/" + Progression.maxMana(joiner));
        // The starter staff never adds mana: no implicit mana and never a Max Mana roll.
        double starterMana = 0;
        for (int i = 0; i < 200; i++) {
            for (ItemAttributeModifiers.Entry e : LootRoller.starterStaff(level.getRandom())
                    .getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                    .modifiers()) {
                if (e.attribute().equals(ModAttributes.MAX_MANA))
                    starterMana += Math.abs(e.modifier().amount());
            }
        }
        check("starter_staff_no_mana", starterMana == 0, starterMana);

        BlockPos inside = level.getRespawnData().pos();
        check("hut_built_once", Boolean.TRUE.equals(level.getAttached(ModData.HUT)), level.getAttached(ModData.HUT));
        check(
                "hut_near_spawn",
                Math.abs(inside.getX() - oldSpawn.getX()) <= 34 && Math.abs(inside.getZ() - oldSpawn.getZ()) <= 34,
                oldSpawn.toShortString() + " -> " + inside.toShortString());
        // Open sky over the yard: nothing (a cliff, an overhang, a tree the clearing missed) hangs above it.
        StringBuilder roofed = new StringBuilder();
        for (int x = -3; x <= 3; x += 2)
            for (int z = 5; z <= 9; z += 2) {
                BlockPos p = inside.offset(x, 0, z);
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ());
                if (top > inside.getY() + 1)
                    roofed.append(' ')
                            .append(x)
                            .append(',')
                            .append(z)
                            .append('@')
                            .append(top)
                            .append(':')
                            .append(BuiltInRegistries.BLOCK
                                    .getKey(level.getBlockState(new BlockPos(p.getX(), top - 1, p.getZ()))
                                            .getBlock())
                                    .getPath());
            }
        check(
                "hut_on_surface",
                inside.getY() >= level.getSeaLevel() - 4 && roofed.isEmpty(),
                inside.getY() + roofed.toString());
        check(
                "hut_respawn_radius",
                level.getGameRules().get(GameRules.RESPAWN_RADIUS) == 0,
                level.getGameRules().get(GameRules.RESPAWN_RADIUS));
        check(
                "hut_player_inside",
                joiner.blockPosition().distManhattan(inside) <= 1
                        && Boolean.TRUE.equals(joiner.getAttached(ModData.ARRIVED)),
                joiner.blockPosition().toShortString() + " vs " + inside.toShortString());
        // Arrival spot: open air underfoot and at head height (the ward's invisible light fill counts), a plank floor.
        check(
                "hut_headroom",
                open(level.getBlockState(inside))
                        && open(level.getBlockState(inside.above()))
                        && level.getBlockState(inside.below()).isSolidRender(),
                level.getBlockState(inside) + " / " + level.getBlockState(inside.above()));

        boolean bed = level.getBlockState(inside.offset(-2, 0, -2)).getBlock() instanceof BedBlock
                && level.getBlockState(inside.offset(-2, 0, -1)).getBlock() instanceof BedBlock;
        check("hut_bed", bed, null);
        ItemStack book = SpawnHut.guideBook();
        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        check(
                "hut_book_pages", // never more than three pages, story only (owner, 2026-11-18)
                content != null && content.pages().size() <= 3,
                content == null ? "none" : content.pages().size());
        boolean lectern = level.getBlockEntity(inside.offset(2, 0, -2)) instanceof LecternBlockEntity l
                && l.hasBook()
                && l.getBook().has(DataComponents.WRITTEN_BOOK_CONTENT);
        check("hut_lectern_book", lectern, null);
        check(
                "hut_light_chest",
                level.getBlockState(SpawnHut.lightChestAt(inside)).is(ModItems.LIGHT_CHEST),
                level.getBlockState(SpawnHut.lightChestAt(inside)));
        // Smaller and plainer (owner, 2026-11-09): no glass, carpet, lanterns, barrel or flower pot anywhere in or on
        // it.
        StringBuilder frills = new StringBuilder();
        for (BlockPos p : BlockPos.betweenClosed(inside.offset(-6, -1, -5), inside.offset(6, 9, 5))) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.GLASS_PANE)
                    || s.is(Blocks.GLASS)
                    || s.is(BlockTags.WOOL_CARPETS)
                    || s.is(Blocks.LANTERN)
                    || s.is(Blocks.BARREL)
                    || s.getBlock() instanceof FlowerPotBlock)
                frills.append(' ').append(s.getBlock());
        }
        int shelves = 0;
        for (BlockPos p : BlockPos.betweenClosed(inside.offset(-4, 0, -3), inside.offset(4, 4, 3)))
            if (level.getBlockState(p).is(Blocks.BOOKSHELF)) shelves++;
        // The chest's invisible room-fill light blocks may occupy the open floor; anything else is a frill.
        BlockState besideLectern = level.getBlockState(inside.offset(1, 0, 0)),
                frontOfChest = level.getBlockState(inside.offset(0, 0, -1));
        check(
                "hut_plain",
                frills.isEmpty()
                        && shelves == 0
                        && (besideLectern.isAir() || besideLectern.is(Blocks.LIGHT))
                        && (frontOfChest.isAir() || frontOfChest.is(Blocks.LIGHT)),
                frills + " shelves=" + shelves + " floor=" + besideLectern.getBlock() + "/" + frontOfChest.getBlock());
        check(
                "hut_direct_starter_kit",
                joiner.getMainHandItem().is(ModItems.STARTER_STAFF)
                        && starterStone(joiner.getOffhandItem())
                        && Enchanting.count(joiner, Items.APPLE) == 5
                        && Enchanting.count(joiner, Items.TORCH) == 0
                        && Enchanting.count(joiner, Items.WRITTEN_BOOK) == 0
                        && Enchanting.count(joiner, Items.WOODEN_SWORD) == 0,
                joiner.getMainHandItem());
        check(
                "hut_yard_clear",
                level.getBlockState(inside.offset(3, 0, 6)).isAir()
                        && level.getBlockState(inside.offset(3, 0, 8)).isAir()
                        && level.getBlockState(inside.offset(5, 0, 6)).isAir(),
                null);
        check(
                "hut_no_loose_items",
                level.getEntitiesOfClass(ItemEntity.class, new AABB(inside).inflate(10))
                        .isEmpty(),
                null);
        // Inside-height check against the real terrain: the floor sits within a few blocks of the natural ground on at
        // least one side (one side alone can be a hill; the world seed changes every run). Catches a sunk or floating
        // hut.
        int bestGap = Integer.MAX_VALUE;
        StringBuilder grounds = new StringBuilder();
        for (int[] d : new int[][] {{12, 0}, {-12, 0}, {0, 12}, {0, -12}}) {
            int natural = SpawnHut.groundAt(level, inside.getX() + d[0], inside.getZ() + d[1]);
            bestGap = Math.min(bestGap, Math.abs(inside.getY() - 1 - natural));
            grounds.append(' ').append(natural);
        }
        check("hut_on_ground", bestGap <= 8, inside.getY() + " vs ground" + grounds);

        // A second join: no second hut, no second teleport.
        joiner.setPos(inside.getX() + 40.5, 250, inside.getZ() + 0.5);
        SpawnHut.onJoin(joiner);
        check(
                "hut_join_once",
                joiner.getX() > inside.getX() + 30
                        && level.getRespawnData().pos().equals(inside),
                joiner.blockPosition().toShortString());

        // A world from before the mod (no hut): a first-time player gets the starter staff in hand-reach instead.
        level.setAttached(ModData.HUT, false);
        FakePlayer old = FakePlayer.get(
                level, new GameProfile(UUID.fromString("0d7f3a52-1c44-4b8e-a0f1-6b2c9e4d7a31"), "OldWorldTester"));
        SpawnHut.onJoin(old);
        boolean gotStaff = false, gotTome = false;
        for (int i = 0; i < old.getInventory().getContainerSize(); i++) {
            gotStaff |= ModItems.isStaff(old.getInventory().getItem(i));
            gotTome |= starterStone(old.getInventory().getItem(i));
        }
        check(
                "no_hut_gets_staff",
                (gotStaff || ModItems.isStaff(old.getMainHandItem()))
                        && (gotTome || starterStone(old.getOffhandItem())),
                "staff=" + gotStaff + " tome=" + gotTome);
        level.setAttached(ModData.HUT, true);

        // An away (non-home) build leaves the world spawn alone.
        SpawnHut.build(level, new BlockPos(inside.getX() + 200, 200, inside.getZ()), false);
        check(
                "hut_away_keeps_spawn",
                level.getRespawnData().pos().equals(inside),
                level.getRespawnData().pos().toShortString());
    }

    /**
     * Every Lightborn crafting recipe laid out in a grid, once with each ingredient's first accepted item and once with
     * its last, then offered to every other crafting recipe loaded (vanilla's and the companion mods'). A match means
     * two recipes share one grid and the game shows only one of them (owner, 2026-11-20: the table took the wooden
     * pickaxe's grid).
     */
    static java.util.List<String> recipeClashes(net.minecraft.server.level.ServerLevel level) {
        var all = new java.util.ArrayList<net.minecraft.world.item.crafting.RecipeHolder<?>>(
                level.getServer().getRecipeManager().getRecipes());
        var clashes = new java.util.TreeSet<String>();
        for (var mine : all) {
            if (!mine.id().identifier().getNamespace().equals(Lightborn.MOD_ID)) continue;
            for (boolean last : new boolean[] {false, true}) {
                CraftingInput input;
                if (mine.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
                    var stacks = new java.util.ArrayList<ItemStack>();
                    for (var slot : shaped.getIngredients())
                        stacks.add(slot.map(i -> pick(i, last)).orElse(ItemStack.EMPTY));
                    input = CraftingInput.of(shaped.getWidth(), shaped.getHeight(), stacks);
                } else if (mine.value() instanceof net.minecraft.world.item.crafting.ShapelessRecipe shapeless) {
                    var stacks = new java.util.ArrayList<ItemStack>();
                    for (var i : shapeless.placementInfo().ingredients()) stacks.add(pick(i, last));
                    while (stacks.size() < 9) stacks.add(ItemStack.EMPTY);
                    input = CraftingInput.of(3, 3, stacks);
                } else continue;
                for (var other : all) {
                    if (other == mine
                            || !(other.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe recipe))
                        continue;
                    if (recipe.matches(input, level))
                        clashes.add(mine.id().identifier().getPath() + " = "
                                + other.id().identifier());
                }
            }
        }
        return new java.util.ArrayList<>(clashes);
    }

    private static ItemStack pick(net.minecraft.world.item.crafting.Ingredient ingredient, boolean last) {
        var items = ingredient.items().toList();
        return items.isEmpty() ? ItemStack.EMPTY : new ItemStack(items.get(last ? items.size() - 1 : 0));
    }
}
