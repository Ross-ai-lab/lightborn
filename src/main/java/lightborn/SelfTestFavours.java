package lightborn;

import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Self-test checks for the nightly layer: Book of Souls pages, mounted heads, Light Wings, the Dragon's gifts and
 * errands, themed Dark Moons, tome awakenings and Dark Nests.
 */
final class SelfTestFavours {
    private SelfTestFavours() {}

    static void run(BlockPos ground) {
        var p = FakePlayer.get(
                level, new GameProfile(UUID.fromString("5d3c1f0e-4b7a-4c55-9a51-2f0e7b9c6a11"), "FavourTester"));
        p.setPos(Vec3.atBottomCenterOf(ground));
        var placed = new ArrayList<BlockPos>();
        var spawned = new ArrayList<Entity>();
        try {
            testCollections(ground, placed);
            testWings(p);
            testWarmth(p);
            testErrands(p, spawned);
            testMoons();
            testAwakening(p, spawned);
            testNest(p, ground.offset(20, 0, 0), placed);
            testFountainPools();
            testNetherFountain();
            testTheDark(p);
            testTheHollow();
        } finally {
            for (var pos : placed) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            for (var e : spawned) e.discard();
            p.getInventory().clearContent();
            for (var slot : EquipmentSlot.values()) p.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void testCollections(BlockPos ground, List<BlockPos> placed) {
        // The 48 night kinds (owner, 2026-11-18) and every other monster in the game (2026-11-20).
        check(
                "souls_every_monster_paged",
                Souls.NIGHT_PAGES.size() == 48 && Souls.PAGES.size() == 127,
                Souls.NIGHT_PAGES.size() + "+" + (Souls.PAGES.size() - Souls.NIGHT_PAGES.size()));
        // Every monster has a page and so a tier (owner, 2026-11-20: "all must be tiered").
        var untiered = new java.util.ArrayList<String>();
        for (var type : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() != net.minecraft.world.entity.MobCategory.MONSTER) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(type)
                    .toString();
            if (Souls.page(id) != null) continue;
            untiered.add(id);
        }
        check("every_monster_has_a_page", untiered.isEmpty(), untiered);
        int[] perTier = new int[6];
        for (var page : Souls.PAGES) perTier[page.weight()]++;
        check(
                "souls_five_tiers",
                perTier[1] == 62 && perTier[2] == 38 && perTier[3] == 15 && perTier[4] == 3 && perTier[5] == 9,
                java.util.Arrays.toString(perTier));
        var full = new Souls.Spirits(
                java.util.Map.of("lightborn:giant_zombie", 1, "minecraft:zombie", 1, "minecraft:enderman", 1),
                java.util.List.of("minecraft:zombie", "lightborn:giant_zombie", "minecraft:enderman"),
                5);
        check(
                "souls_book_never_overfills",
                full.fitted().slots().equals(java.util.List.of("minecraft:zombie", "lightborn:giant_zombie"))
                        && full.fitted().ranks().size() == 3,
                full.fitted());
        check(
                "six_mounted_heads",
                BossHeads.BLOCKS.size() == 6
                        && BossHeads.BLOCKS.values().stream().allMatch(b -> BossHeads.isHead(b.defaultBlockState())),
                BossHeads.BLOCKS.size());
    }

    private static void testWings(FakePlayer p) {
        check("light_wings_registered", LightGear.ITEMS.size() == 8, LightGear.ITEMS.size()); // and the shield
        var wings = LightGear.gift(LightGear.WINGS, 1);
        check(
                "light_wings_glide_and_repair",
                wings.has(DataComponents.GLIDER)
                        && wings.get(DataComponents.REPAIRABLE).isValidRepairItem(new ItemStack(Items.GLOWSTONE_DUST)),
                null);
        p.setItemSlot(EquipmentSlot.HEAD, LightGear.gift(0, 3));
        p.setItemSlot(EquipmentSlot.CHEST, wings);
        p.setItemSlot(EquipmentSlot.LEGS, LightGear.gift(3, 3));
        p.setItemSlot(EquipmentSlot.FEET, LightGear.gift(4, 3));
        check(
                "light_wings_count_as_chest_piece",
                LightGear.pieces(p) == 4 && LightGear.fullSet(p),
                LightGear.pieces(p));
    }

    private static void testWarmth(FakePlayer p) {
        p.setAttached(DragonTalk.JOURNEY, DragonTalk.Journey.NEW.stage(2));
        p.setAttached(DragonTalk.DISPOSITION, 3);
        p.setAttached(DragonFavours.WARMTH, 0);
        DragonFavours.warm(p, 4);
        boolean filling = p.getAttached(DragonFavours.WARMTH) == 4 && p.getAttached(DragonTalk.DISPOSITION) == 3;
        String said = DragonFavours.warm(p, 6);
        boolean warmed = !said.isEmpty()
                && p.getAttached(DragonTalk.DISPOSITION) == 2
                && p.getAttached(DragonFavours.WARMTH) == 0;
        check("dragon_warmth_moves_disposition", filling && warmed, p.getAttached(DragonFavours.WARMTH));
        p.setAttached(DragonTalk.JOURNEY, DragonTalk.Journey.NEW.stage(5));
        DragonFavours.warm(p, 10);
        boolean held = p.getAttached(DragonTalk.DISPOSITION) == 2;
        p.setAttached(DragonTalk.JOURNEY, DragonTalk.Journey.NEW.stage(2));
        p.setAttached(DragonFavours.WARMTH, 0);
        DragonFavours.warm(p, -6);
        boolean cooled = p.getAttached(DragonTalk.DISPOSITION) == 3;
        check("dragon_warmth_held_mid_quest_and_cools", held && cooled, p.getAttached(DragonTalk.DISPOSITION));
        check(
                "dragon_tastes",
                DragonFavours.taste(new ItemStack(Items.DIAMOND)) == DragonFavours.Taste.LOVED
                        && DragonFavours.taste(new ItemStack(Items.ROTTEN_FLESH)) == DragonFavours.Taste.DISLIKED
                        && DragonFavours.taste(new ItemStack(Items.GLOWSTONE_DUST)) == DragonFavours.Taste.LIKED
                        && DragonFavours.taste(new ItemStack(Items.WHEAT)) == DragonFavours.Taste.PLAIN
                        && !DragonFavours.returnGift(RandomSource.create(1)).isEmpty(),
                null);
    }

    private static void testErrands(FakePlayer p, List<Entity> spawned) {
        var d = ModEntities.LIGHT_DRAGON.create(level, EntitySpawnReason.COMMAND);
        p.removeAttached(DragonFavours.ERRAND);
        var first = DragonFavours.tonight(p);
        check(
                "errand_rolled_once_a_night",
                first.equals(DragonFavours.tonight(p))
                        && first.kind() == 4
                        && !DragonFavours.describe(first).isEmpty(),
                DragonFavours.describe(first));
        var offer = DragonFavours.respond(p, d, DragonFavours.ASK);
        boolean offered = !DragonFavours.accepted(p)
                && offer.choices().stream().anyMatch(c -> c.action() == DragonFavours.ACCEPT);
        DragonFavours.respond(p, d, DragonFavours.ACCEPT);
        check("errand_offered_then_accepted", offered && DragonFavours.accepted(p), null);
        long night = DragonStory.night(p);
        p.setAttached(DragonFavours.ERRAND, new DragonFavours.Errand(night, 4, "minecraft:gold_ingot", 3, 0, false));
        p.getInventory().add(new ItemStack(Items.GOLD_INGOT, 5));
        DragonFavours.respond(p, d, DragonFavours.REPORT);
        check(
                "errand_fetch_takes_items_and_pays",
                DragonFavours.carried(p, Items.GOLD_INGOT) == 2 && DragonFavours.carried(p, ModItems.SPARK) >= 3,
                DragonFavours.carried(p, Items.GOLD_INGOT));
        p.getInventory().clearContent();
        d.discard();
    }

    private static void testMoons() {
        var one = Cleanse.mix(RandomSource.create(4), 1, 12);
        var ten = Cleanse.mix(RandomSource.create(4), 10, 40);
        check(
                "cleanse_mix_grows_with_shards",
                one.size() == 12
                        && one.stream().noneMatch(t -> t == ModEntities.GIANT_ZOMBIE || t == ModEntities.EMBER_SKELETON)
                        && ten.size() == 40
                        && ten.contains(ModEntities.GIANT_ZOMBIE)
                        && ten.contains(ModEntities.VAMPIRE_ZOMBIE)
                        && Cleanse.size(1, 1) == 16
                        && Cleanse.size(1, 10) == 52
                        && Cleanse.size(2, 1) == 24,
                one.size() + "/" + ten.size());
    }

    private static void testAwakening(FakePlayer p, List<Entity> spawned) {
        var tome = new ItemStack(ModItems.TOMES.get(Spell.FIREBALL));
        p.setItemSlot(EquipmentSlot.OFFHAND, tome);
        var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        var pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
        spawned.add(zombie);
        spawned.add(pig);
        TomeAwakening.killed(p, pig, Spell.FIREBALL);
        TomeAwakening.killed(p, zombie, Spell.ARCANE_BOLT);
        boolean ignored = TomeAwakening.kills(p.getOffhandItem()) == 0;
        for (int i = 0; i < Balance.TOME_AWAKEN_KILLS - 1; i++) TomeAwakening.killed(p, zombie, Spell.FIREBALL);
        boolean notYet = !TomeAwakening.awake(p, Spell.FIREBALL);
        TomeAwakening.killed(p, zombie, Spell.FIREBALL);
        check(
                "tome_awakens_on_its_own_monster_kills",
                ignored && notYet && TomeAwakening.awake(p, Spell.FIREBALL),
                TomeAwakening.kills(p.getOffhandItem()));
        var frost = new ItemStack(ModItems.TOMES.get(Spell.FROST_NOVA));
        p.setItemSlot(EquipmentSlot.OFFHAND, frost);
        for (int i = 0; i < Balance.TOME_AWAKEN_KILLS; i++) TomeAwakening.killed(p, zombie, Spell.FROST_NOVA);
        check("only_chosen_tomes_awaken", !TomeAwakening.awake(frost) && TomeAwakening.kills(frost) == 0, null);
        p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    private static void testNest(FakePlayer p, BlockPos core, List<BlockPos> placed) {
        long latest = Hordes.MoonNight.read(level).latestHunt();
        var owner = p.getUUID();
        boolean stages = new DarkNests.Nest(core, core, owner, latest, 0).stage(level) == 1
                && new DarkNests.Nest(core, core, owner, latest - 16, 0).stage(level) == 3
                && new DarkNests.Nest(core, core, owner, latest - 80, 0).stage(level) == 4;
        check("dark_nest_grows_each_dark_moon", stages, null);
        for (var pos : BlockPos.betweenClosed(core.offset(-5, -1, -5), core.offset(5, -1, 5))) {
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            placed.add(pos.immutable());
        }
        // A pool of still water set into the floor, and bare stone beside it (owner, 2026-11-18: goo and blackstone).
        var pond = core.offset(3, -1, 0);
        var rock = core.offset(-4, -1, -4);
        level.setBlockAndUpdate(pond.below(), Blocks.STONE.defaultBlockState());
        placed.add(pond.below());
        level.setBlockAndUpdate(pond, Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(core, DarkNests.NEST.defaultBlockState().setValue(DarkNests.STAGE, 2));
        placed.add(core);
        DarkLand.spread(level, core, 2);
        boolean darkened = level.getBlockState(pond).is(DarkNests.GOO)
                && level.getBlockState(rock).is(Blocks.BLACKSTONE)
                && DarkLand.darkRadius(2) == 34
                && DarkNests.MAX_PER_HOME == 3
                && DarkNests.PLANT_MAX == 300;
        int growth = 0;
        for (var pos : BlockPos.betweenClosed(core.offset(-5, 0, -5), core.offset(5, 0, 5)))
            if (level.getBlockState(pos).is(DarkNests.GROWTH)) growth++;
        level.setBlockAndUpdate(core, Blocks.AIR.defaultBlockState());
        DarkNests.cleared(level, p, core, 2);
        DarkLand.healNow(level, core); // the 15-second healing wave, run at once
        int left = 0;
        for (var pos : BlockPos.betweenClosed(core.offset(-5, 0, -5), core.offset(5, 0, 5)))
            if (level.getBlockState(pos).is(DarkNests.GROWTH)) left++;
        var drops =
                level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(core).inflate(3));
        boolean paid = drops.stream()
                .anyMatch(i -> i.getItem().is(ModItems.SPARK)
                        && i.getItem().getCount() >= 12
                        && i.getItem().getCount() <= 16);
        check("dark_nest_spreads_and_clears_with_pay", growth > 0 && left == 0 && paid, growth + "/" + left);
        boolean healed = level.getBlockState(pond).is(Blocks.WATER)
                && level.getBlockState(rock).is(Blocks.STONE);
        check(
                "dark_land_turns_water_to_goo_and_stone_black_and_heals",
                darkened && healed,
                level.getBlockState(pond) + " / " + level.getBlockState(rock));
        level.setBlockAndUpdate(pond, Blocks.STONE.defaultBlockState());
        drops.forEach(Entity::discard);
    }

    /**
     * Each fountain fits its place (owner, 2026-11-20): the Dark's guards are only its own monsters, an Overworld
     * fountain's about half its biome's, and no world boss, mount or summon ever guards. The Hollow has none
     * (2026-11-21).
     */
    private static void testFountainPools() {
        var server = level.getServer();
        var nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
        var end = server.getLevel(net.minecraft.world.level.Level.END);
        var at = BlockPos.ZERO;
        var netherPool = DarkNests.pool(nether, at, false);
        var endPool = DarkNests.pool(end, at, false);
        var base = DarkNests.pool(level, at, false);
        java.util.function.BiPredicate<List<Souls.Page>, String> has =
                (pool, id) -> pool.stream().anyMatch(pg -> pg.id().equals(id));
        boolean own = has.test(netherPool, "minecraft:piglin")
                && has.test(netherPool, "minecraft:blaze")
                && !has.test(netherPool, "minecraft:zombie")
                && endPool.isEmpty()
                && has.test(base, "minecraft:zombie")
                && !has.test(base, "minecraft:blaze")
                && DarkNests.pool(nether, at, true).isEmpty();
        boolean noneOdd = true;
        for (var pool : List.of(netherPool, endPool, base))
            for (var id : List.of(
                    "minecraft:wither",
                    "minecraft:ender_dragon",
                    "minecraft:giant",
                    "minecraft:vex",
                    "minecraft:creaking",
                    "minecraft:camel_husk",
                    "minecraft:guardian",
                    "minecraft:warden")) noneOdd &= !has.test(pool, id);
        var random = RandomSource.create(7);
        boolean inPool = true;
        for (int i = 0; i < 60; i++) {
            var kind = DarkNests.guardKind(random, 4, 4, netherPool, List.of());
            var champion = DarkNests.championKind(random, 4, netherPool);
            inPool &= netherPool.stream().anyMatch(pg -> pg.type().get() == kind)
                    && netherPool.stream().anyMatch(pg -> pg.type().get() == champion && pg.weight() >= 3);
        }
        var snowy = Souls.PAGES.stream()
                .filter(pg -> Souls.monster(pg.id()).guards().contains("snowy")
                        && pg.type().get() != null)
                .toList();
        int themed = 0;
        for (int i = 0; i < 400; i++) {
            var kind = DarkNests.guardKind(random, 1, 1, base, snowy);
            if (snowy.stream().anyMatch(pg -> pg.type().get() == kind)) themed++;
        }
        check(
                "fountain_guards_fit_their_place",
                own && noneOdd && inPool && themed > 140 && themed < 260 && has.test(snowy, "minecraft:stray"),
                "own=" + own + " odd=" + !noneOdd + " inPool=" + inPool + " themed=" + themed + "/400");
    }

    /**
     * A Nether fountain's dark is sculk and deepslate on the Nether's own floor, and the healing wave gives back that
     * floor, never touching the Nether's own soul sand beyond it (owner, 2026-11-20). Fountains root on a cavern floor
     * the player can reach, never on the roof.
     */
    private static void testNetherFountain() {
        var nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        nether.getChunk(0, 0);
        nether.getChunk(2, 0);
        var saved = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        java.util.function.BiConsumer<BlockPos, net.minecraft.world.level.block.Block> put = (pos, block) -> {
            saved.putIfAbsent(pos.immutable(), nether.getBlockState(pos));
            nether.setBlock(pos, block.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        };
        try {
            // A cavern pocket for floor(): netherrack floor at 99, air 100 to 102, netherrack 103 to 124.
            int px = 4, pz = 4;
            put.accept(new BlockPos(px, 99, pz), Blocks.NETHERRACK);
            for (int y = 100; y <= 102; y++) put.accept(new BlockPos(px, y, pz), Blocks.AIR);
            for (int y = 103; y <= 124; y++) put.accept(new BlockPos(px, y, pz), Blocks.NETHERRACK);
            var floor = DarkNests.floor(nether, px, pz, 100);
            // A pad above the roof for the dark: netherrack floor round the core, open air over it.
            var core = new BlockPos(8, 200, 8);
            for (var pos : BlockPos.betweenClosed(core.offset(-6, -1, -6), core.offset(6, -1, 6)))
                put.accept(pos, Blocks.NETHERRACK);
            var near = core.offset(3, -1, 0);
            var far = core.offset(30, -1, 0); // past the stage-1 dark, inside the heal wave
            put.accept(near, Blocks.SOUL_SAND);
            put.accept(far, Blocks.SOUL_SAND);
            put.accept(core, Blocks.AIR);
            DarkLand.spread(nether, core, 1);
            boolean dark = nether.getBlockState(near).is(Blocks.SCULK);
            DarkLand.healNow(nether, core);
            var back = DarkLand.awayFloor(nether, near);
            boolean healed = nether.getBlockState(near).is(back)
                    && nether.getBlockState(far).is(Blocks.SOUL_SAND)
                    && !nether.getBlockState(near.above()).is(DarkNests.GROWTH);
            check(
                    "nether_fountain_darkens_and_heals_to_its_own_floor",
                    dark
                            && healed
                            && new BlockPos(px, 100, pz).equals(floor)
                            && DarkNests.floor(nether, 8, 8, 200) == null,
                    nether.getBlockState(near) + " far=" + nether.getBlockState(far) + " floor=" + floor);
        } finally {
            for (var pos : BlockPos.betweenClosed(new BlockPos(-20, 199, -20), new BlockPos(40, 203, 40)))
                if (nether.getBlockState(pos).is(DarkNests.GROWTH))
                    nether.setBlock(
                            pos, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            var order = new ArrayList<>(saved.entrySet());
            java.util.Collections.reverse(order);
            for (var e : order)
                nether.setBlock(e.getKey(), e.getValue(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    /**
     * The Dark (owner, 2026-11-20): the Nether's seas are Dark Goo; a player sinks in it and drowns, and can only
     * climb out at a wall; monsters float in it as in water and never drown; striders walk and are born on it; the
     * Dark's own kinds spawn there; its rock holds Dark Shard ore; its fountains start stronger and pay more; and the
     * Dragon's sixth chapter tells where the Dark comes from.
     */
    private static void testTheDark(FakePlayer p) {
        var server = level.getServer();
        var dark = server.getLevel(net.minecraft.world.level.Level.NETHER);
        var gooState = DarkGoo.BLOCK.defaultBlockState();
        boolean registered = gooState.getFluidState().is(DarkGoo.TAG)
                && gooState.getFluidState().isSource()
                && ((net.minecraft.world.level.block.LiquidBlock) DarkGoo.BLOCK)
                        .pickupBlock(p, level, BlockPos.ZERO, gooState)
                        .isEmpty();
        var noise = server.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS)
                .getValueOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.NETHER);
        int goo = 0, lava = 0;
        for (int cx = 0; cx < 3; cx++)
            for (int cz = 0; cz < 3; cz++) {
                var chunk = dark.getChunk(cx * 4, cz * 4);
                for (int x = 0; x < 16; x++)
                    for (int z = 0; z < 16; z++)
                        for (int y = 10; y <= 31; y++) {
                            var f = chunk.getFluidState(new BlockPos(
                                    chunk.getPos().getMinBlockX() + x,
                                    y,
                                    chunk.getPos().getMinBlockZ() + z));
                            if (f.is(DarkGoo.TAG)) goo++;
                            else if (f.is(net.minecraft.tags.FluidTags.LAVA)) lava++;
                        }
            }
        check(
                "the_dark_seas_are_goo",
                registered && noise.defaultFluid().is(DarkGoo.BLOCK) && goo > 0 && lava * 20 < goo,
                "goo=" + goo + " lava=" + lava);

        // A pool of goo four deep on a stone floor, in the test field.
        var pool = p.blockPosition().offset(40, 0, 40);
        var placed = new ArrayList<BlockPos>();
        for (var pos : BlockPos.betweenClosed(pool.offset(-3, -1, -3), pool.offset(3, 4, 3))) {
            boolean edge = Math.abs(pos.getX() - pool.getX()) == 3 || Math.abs(pos.getZ() - pool.getZ()) == 3;
            level.setBlock(
                    pos,
                    pos.getY() == pool.getY() - 1 || edge ? Blocks.STONE.defaultBlockState() : gooState,
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            placed.add(pos.immutable());
        }
        var diver = FakePlayer.get(
                level, new GameProfile(UUID.fromString("7d1c4a2e-9b3f-4e8a-a6c1-3f5e2d9b0c44"), "GooTester"));
        try {
            diver.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            diver.setPos(pool.getX() + .5, pool.getY(), pool.getZ() + .5);
            int air = diver.getAirSupply();
            for (int i = 0; i < 10; i++) diver.baseTick();
            boolean drowning = diver.isEyeInFluid(DarkGoo.TAG) && diver.getAirSupply() < air;
            diver.setDeltaMovement(0, .42, 0); // a jump
            diver.horizontalCollision = false;
            DarkGoo.sink(diver);
            boolean sinks = DarkGoo.sinks(diver) && diver.getDeltaMovement().y <= DarkGoo.SINK_SLOW;
            diver.horizontalCollision = true; // pressed against the pool's wall
            DarkGoo.sink(diver);
            boolean climbs = diver.getDeltaMovement().y == DarkGoo.CLIMB;
            check(
                    "goo_sinks_and_drowns_players_who_climb_out_at_walls",
                    drowning && sinks && climbs,
                    "drowning=" + drowning + " sinks=" + sinks + " climbs=" + climbs);

            var spider = EntityTypes.SPIDER.create(level, EntitySpawnReason.COMMAND);
            spider.setPos(pool.getX() + .5, pool.getY() + 1, pool.getZ() + .5);
            level.addFreshEntity(spider);
            int spiderAir = spider.getAirSupply();
            for (int i = 0; i < 40; i++) spider.baseTick();
            boolean floats = DarkGoo.swimsIn(spider)
                    && new net.minecraft.world.entity.ai.goal.FloatGoal(spider).canUse()
                    && spider.getAirSupply() == spiderAir
                    && !DarkGoo.sinks(spider);
            spider.discard();
            check("monsters_float_in_goo_and_never_drown", floats, spider.getAirSupply() + "/" + spiderAir);

            var strider = EntityTypes.STRIDER.create(level, EntitySpawnReason.COMMAND);
            var top = pool.offset(0, 4, 0);
            boolean stands = strider.canStandOnFluid(gooState.getFluidState());
            boolean born = net.minecraft.world.entity.SpawnPlacements.isSpawnPositionOk(EntityTypes.STRIDER, level, top)
                    && net.minecraft.world.entity.monster.Strider.checkStriderSpawnRules(
                            EntityTypes.STRIDER, level, EntitySpawnReason.NATURAL, pool, level.getRandom());
            strider.setPos(top.getX() + .5, top.getY() + .5, top.getZ() + .5);
            level.addFreshEntity(strider);
            for (int i = 0; i < 3; i++) strider.tick();
            boolean warm = !strider.isSuffocating();
            strider.discard();
            check("striders_walk_and_are_born_on_goo", stands && born && warm, stands + "/" + born + "/" + warm);
        } finally {
            diver.discard();
            for (var pos : placed)
                level.setBlock(
                        pos, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }

        // The Dark's own kinds spawn there; its rock holds Dark Shard ore, which drops shards to an iron pickaxe.
        dark.getChunk(0, 0);
        var spawns = dark
                .environmentAttributes()
                .getValue(
                        net.minecraft.world.attribute.EnvironmentAttributes.NATURAL_MOB_SPAWNS, new BlockPos(8, 64, 8))
                .getMobsInCategory(net.minecraft.world.entity.MobCategory.MONSTER)
                .unwrap()
                .stream()
                .map(w -> w.value().type())
                .toList();
        boolean kinds = spawns.contains(ModEntities.DARKNESS_ZOMBIE) && spawns.contains(ModEntities.EMBER_SKELETON);
        var orePlaced = server.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE)
                .containsKey(ModItems.id("ore_dark_shard"));
        var pick = new ItemStack(Items.IRON_PICKAXE);
        var drops = net.minecraft.world.level.block.Block.getDrops(
                DarkRealm.SHARD_ORE.defaultBlockState(), level, p.blockPosition(), null, p, pick);
        boolean shards = drops.stream().anyMatch(s -> s.is(ModItems.DARK_SHARD));
        check(
                "the_dark_has_its_kinds_and_shard_ore",
                kinds && orePlaced && shards,
                "kinds=" + kinds + " ore=" + orePlaced + " shards=" + shards);

        // Its fountains start at stage 2, grow two a Dark Moon and pay half again.
        long latest = Hordes.MoonNight.read(level).latestHunt();
        var core = new BlockPos(8, 210, 8);
        boolean stronger = new DarkNests.Nest(core, core, p.getUUID(), latest, 0).stage(dark) == 2
                && new DarkNests.Nest(core, core, p.getUUID(), latest - 8, 0).stage(dark) == 4;
        DarkNests.cleared(dark, p, core, 2);
        var paid = dark.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(core).inflate(3));
        boolean more = paid.stream()
                .anyMatch(i -> i.getItem().is(ModItems.SPARK)
                        && i.getItem().getCount() >= 18
                        && i.getItem().getCount() <= 24); // 12 to 16, half again
        paid.forEach(Entity::discard);
        // Dark Phantoms of the Dark never meet a dawn.
        var phantom = DarkPhantom.TYPE.create(dark, EntitySpawnReason.COMMAND);
        phantom.setPos(8.5, 215, 8.5);
        dark.addFreshEntity(phantom);
        phantom.aiStep();
        boolean noDawn = phantom.isAlive();
        phantom.discard();
        check(
                "the_dark_fountains_are_stronger_and_its_phantoms_stay",
                stronger
                        && more
                        && noDawn
                        && DragonStory.TITLES.get(5).equals("The Dark Below")
                        && DragonStory.TEXT.get(5).contains("down into the Dark"),
                "stronger=" + stronger + " paid=" + more + " noDawn=" + noDawn);
    }

    /**
     * The Hollow (owner, 2026-11-21): its Ender kinds go by their dark names only there; the void pulls a player near an
     * island's edge, not one in the middle, and a crouching player is held at the edge; any fountain that rooted there
     * crumbles; its stone holds rare Spark Ore, which pays Sparks; and the Dragon's seventh chapter tells of it.
     */
    private static void testTheHollow() {
        var server = level.getServer();
        var hollow = server.getLevel(net.minecraft.world.level.Level.END);
        java.util.function.Function<Entity, String> key =
                e -> e.getName().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                        ? t.getKey()
                        : "";
        var darkman = EntityTypes.ENDERMAN.create(hollow, EntitySpawnReason.COMMAND);
        var shulker = EntityTypes.SHULKER.create(hollow, EntitySpawnReason.COMMAND);
        var enderman = EntityTypes.ENDERMAN.create(level, EntitySpawnReason.COMMAND);
        boolean names = key.apply(darkman).equals("entity.lightborn.darkman")
                && key.apply(shulker).equals("entity.lightborn.dark_shulker")
                && key.apply(enderman).equals("entity.minecraft.enderman");
        check("the_hollow_kinds_go_by_dark_names_only_there", names, key.apply(darkman) + " / " + key.apply(enderman));

        // A five-block stone shelf high over the main island, with the void all round it.
        var mid = new BlockPos(8, 150, 8);
        hollow.getChunk(0, 0);
        var placed = new ArrayList<BlockPos>();
        for (var pos : BlockPos.betweenClosed(mid.offset(-2, 0, -2), mid.offset(2, 0, 2))) {
            hollow.setBlock(
                    pos, Blocks.STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            placed.add(pos.immutable());
        }
        var walker = FakePlayer.get(
                hollow, new GameProfile(UUID.fromString("2b8e6f1a-7c4d-4a3e-9f2b-5d1c8e7a6b30"), "HollowTester"));
        try {
            walker.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            walker.setPos(mid.getX() + .5, mid.getY() + 1, mid.getZ() + .5);
            walker.setOnGround(true);
            walker.setDeltaMovement(Vec3.ZERO);
            boolean middle = Hollow.pulled(walker)
                    && Hollow.pull(walker) == 0
                    && walker.getDeltaMovement().equals(Vec3.ZERO);
            walker.setPos(mid.getX() + 2.5, mid.getY() + 1, mid.getZ() + .5); // on the shelf's east block
            walker.setOnGround(true);
            walker.setDeltaMovement(Vec3.ZERO);
            double strength = Hollow.pull(walker);
            var v = walker.getDeltaMovement();
            boolean edge = strength == 1 && v.x > Hollow.PULL * .9 && Math.abs(v.z) < 1e-6;
            walker.setShiftKeyDown(true);
            walker.setOnGround(true);
            walker.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(.6, 0, 0));
            boolean held = walker.getX() < mid.getX() + 3.3;
            check(
                    "the_void_pulls_at_edges_and_crouching_holds",
                    middle && edge && held,
                    "middle=" + middle + " edge=" + strength + "/" + v + " held=" + walker.getX());

            // A fountain that rooted in the Hollow before crumbles, crystal and all.
            var core = mid.above();
            hollow.setBlockAndUpdate(core, DarkNests.NEST.defaultBlockState());
            DarkNests.crystal(hollow, core, 1);
            hollow.setAttached(
                    DarkNests.NESTS,
                    List.of(new DarkNests.Nest(
                            core,
                            core,
                            walker.getUUID(),
                            Hordes.MoonNight.read(level).latestHunt(),
                            0)));
            DarkNests.unroot(hollow);
            // Its crystal is an entity: the core crumbles once the chunk's entities have loaded, and waits till then.
            boolean ready = hollow.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(core));
            var waiting = hollow.getAttachedOrElse(DarkNests.CEASED, List.of());
            boolean records = DarkNests.nests(hollow).isEmpty()
                    && DarkNests.pool(hollow, core, false).isEmpty();
            boolean crumbled = ready
                    ? waiting.isEmpty()
                            && !hollow.getBlockState(core).is(DarkNests.NEST)
                            && DarkNests.crystals(hollow, core).stream().allMatch(Entity::isRemoved)
                    : waiting.equals(List.of(core.asLong()))
                            && hollow.getBlockState(core).is(DarkNests.NEST);
            check(
                    "the_hollow_has_no_fountains",
                    records && crumbled,
                    "records=" + records + " entitiesLoaded=" + ready + " crumbled=" + crumbled);
            hollow.setAttached(DarkNests.CEASED, List.of());
            hollow.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class, new AABB(core).inflate(1))
                    .forEach(Entity::discard);
            hollow.setBlockAndUpdate(core, Blocks.AIR.defaultBlockState());
        } finally {
            walker.discard();
            for (var pos : placed)
                hollow.setBlock(
                        pos, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }

        // Spark Ore in the main island's stone: rare, and it pays a Spark to an iron pickaxe.
        int ore = 0, stone = 0;
        for (int cx = -2; cx <= 2; cx++)
            for (int cz = -2; cz <= 2; cz++) {
                var chunk = hollow.getChunk(cx, cz);
                for (int x = 0; x < 16; x++)
                    for (int z = 0; z < 16; z++)
                        for (int y = 20; y <= 80; y++) {
                            var s = chunk.getBlockState(new BlockPos(
                                    chunk.getPos().getMinBlockX() + x,
                                    y,
                                    chunk.getPos().getMinBlockZ() + z));
                            if (s.is(Hollow.SPARK_ORE)) ore++;
                            else if (s.is(Blocks.END_STONE)) stone++;
                        }
            }
        var drops = net.minecraft.world.level.block.Block.getDrops(
                Hollow.SPARK_ORE.defaultBlockState(),
                level,
                BlockPos.ZERO,
                null,
                null,
                new ItemStack(Items.IRON_PICKAXE));
        boolean pays = drops.stream().anyMatch(s -> s.is(ModItems.SPARK));
        check(
                "the_hollow_holds_rare_spark_ore",
                ore >= 12 && ore < 100 && pays,
                ore + " ore in 25 island chunks (" + stone + " end stone), pays=" + pays);
        check(
                "the_dragon_tells_of_the_hollow",
                DragonStory.TITLES.get(6).equals("The Hollow")
                        && DragonStory.TEXT.get(6).contains("Hollow"),
                DragonStory.TITLES.get(6));
    }
}
