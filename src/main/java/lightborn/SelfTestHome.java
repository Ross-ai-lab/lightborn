package lightborn;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.*;

/** Persistent furniture, restricted slots, travel authorization and the live companion budgets. */
final class SelfTestHome {
    static void check(String name, boolean ok, Object detail) {
        SelfTest.check("home_" + name, ok, detail);
    }

    static void run(ServerLevel l, BlockPos at) {
        var p = FakePlayer.get(l, new GameProfile(UUID.randomUUID(), "HomeTest"));
        p.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        var partner = FakePlayer.get(l, new GameProfile(UUID.randomUUID(), "HomePartner"));
        int recipes = 0;
        for (var holder : l.getServer().getRecipeManager().getRecipes())
            if (Arrays.stream(Furniture.Kind.values())
                    .anyMatch(k -> holder.id().identifier().equals(ModItems.id(k.id())))) recipes++;
        check("eight_furniture_recipes", recipes == 8, recipes);
        var fridge = at.east(2);
        l.setBlockAndUpdate(fridge, Furniture.BLOCKS.get(Furniture.Kind.FRIDGE).defaultBlockState());
        var storage = (Furniture.Storage) l.getBlockEntity(fridge);
        var menu = storage.createMenu(1, p.getInventory(), p);
        check(
                "fridge_food_only_slots",
                menu.getSlot(0).mayPlace(new ItemStack(Items.APPLE))
                        && !menu.getSlot(0).mayPlace(new ItemStack(Items.COBBLESTONE))
                        && storage.getContainerSize() == 27,
                null);
        storage.setItem(0, new ItemStack(Items.APPLE, 7));
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, l.registryAccess());
        storage.saveAdditional(out);
        var copy = new Furniture.Storage(fridge, storage.getBlockState());
        copy.loadAdditional(TagValueInput.create(ProblemReporter.DISCARDING, l.registryAccess(), out.buildResult()));
        check(
                "storage_save_reload",
                copy.getItem(0).is(Items.APPLE) && copy.getItem(0).getCount() == 7,
                null);
        var wardrobe = at.west(2);
        var lower = Furniture.BLOCKS.get(Furniture.Kind.WARDROBE).defaultBlockState();
        l.setBlockAndUpdate(wardrobe, lower);
        lower.getBlock().setPlacedBy(l, wardrobe, lower, p, new ItemStack(lower.getBlock()));
        check(
                "wardrobe_two_blocks_one_inventory",
                l.getBlockState(wardrobe.above()).getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                == DoubleBlockHalf.UPPER
                        && l.getBlockEntity(wardrobe) instanceof Furniture.Storage
                        && l.getBlockEntity(wardrobe.above()) == null,
                null);
        check(
                "wardrobe_accepts_nonfood",
                ((Furniture.Storage) l.getBlockEntity(wardrobe))
                        .createMenu(2, p.getInventory(), p)
                        .getSlot(0)
                        .mayPlace(new ItemStack(Items.DIAMOND_SWORD)),
                null);
        var lamp = at.north(2);
        l.setBlockAndUpdate(lamp, Furniture.BLOCKS.get(Furniture.Kind.LAMP).defaultBlockState());
        check("lamp_light_14", l.getBlockState(lamp).getLightEmission() == 14, null);
        var chair = at.south(2);
        l.setBlockAndUpdate(chair, Furniture.BLOCKS.get(Furniture.Kind.CHAIR).defaultBlockState());
        Furniture.use(
                p,
                l,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(chair), Direction.NORTH, chair, false));
        check(
                "chair_creates_seat_mount",
                !l.getEntitiesOfClass(Furniture.Seat.class, new AABB(chair).inflate(1))
                        .isEmpty(),
                null);
        p.stopRiding();
        var table = at.north(3);
        l.setBlockAndUpdate(table, Furniture.BLOCKS.get(Furniture.Kind.TABLE).defaultBlockState());
        var shelf = at.east(3);
        l.setBlockAndUpdate(shelf, Furniture.BLOCKS.get(Furniture.Kind.SHELF).defaultBlockState());
        var items = (Furniture.Storage) l.getBlockEntity(shelf);
        for (int i = 0; i < 3; i++)
            items.setItem(i, new ItemStack(i == 0 ? Items.APPLE : i == 1 ? Items.BOOK : Items.AMETHYST_SHARD));
        items.displays();
        check(
                "shelf_displays_three_items",
                l.getEntitiesOfClass(
                                        Display.ItemDisplay.class,
                                        new AABB(shelf).inflate(2),
                                        e -> e.entityTags().contains("lightborn_shelf_display"))
                                .size()
                        == 3,
                null);
        var a = at.south(4);
        var b = at.east(5);
        for (var source : List.of(a, b)) {
            for (var floor : BlockPos.betweenClosed(source.offset(-1, -1, -1), source.offset(1, -1, 1)))
                l.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            l.setBlockAndUpdate(source, ModItems.LIGHT_CHEST.defaultBlockState());
        }
        LightWards.claim(l, a, p);
        LightWards.claim(l, b, partner);
        p.setPos(a.getX() + .5, a.getY(), a.getZ() + 1.5);
        // Flights go to notable structures, each kind priced (owner, 2026-11-16): every tag must name real ones.
        var structureLookup = l.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var emptySites = ChestTravel.SITES.stream()
                .filter(s ->
                        structureLookup.get(s.tag()).map(h -> h.size() == 0).orElse(true))
                .map(ChestTravel.Site::name)
                .toList();
        check(
                "travel_ten_priced_sites",
                ChestTravel.SITES.size() == 10
                        && emptySites.isEmpty()
                        && ChestTravel.SITES.stream().allMatch(s -> s.price() > 0),
                emptySites);
        p.setAttached(DragonStory.CHAPTERS, 0);
        Progression.setMana(p, 100);
        check(
                "travel_starts_channel",
                ChestTravel.begin(p, a, b.above(), ChestTravel.SITES.getFirst()).startsWith("Calling")
                        && ChestTravel.channeling(p),
                null);
        ChestTravel.damaged(p);
        check("damage_cancels_flight", !ChestTravel.channeling(p) && Progression.mana(p) == 100, null);
        check(
                "combat_blocks_travel_ten_seconds",
                ChestTravel.begin(p, a, b.above(), ChestTravel.SITES.getFirst()).contains("ten seconds"),
                null);
        var packet = new ChestTravel.Show(a, true, 12, 3, "Test");
        var bytes = new RegistryFriendlyByteBuf(Unpooled.buffer(), l.registryAccess());
        try {
            ChestTravel.Show.CODEC.encode(bytes, packet);
            check("travel_packet_round_trip", packet.equals(ChestTravel.Show.CODEC.decode(bytes)), null);
        } finally {
            bytes.release();
        }
        check("eleven_music_situations", GameMusic.EVENTS.size() == 11, null);
        var structures = l.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var sets = l.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        var generating = Collections.newSetFromMap(new IdentityHashMap<Structure, Boolean>());
        for (var set : sets)
            for (var selection : set.structures())
                if (selection.weight() > 0
                        && selection.structure().value().biomes().size() > 0)
                    generating.add(selection.structure().value());
        var counts = new TreeMap<String, Integer>();
        for (var structure : generating)
            counts.merge(structures.getKey(structure).getNamespace(), 1, Integer::sum);
        Lightborn.LOG.info("GENERATING STRUCTURE NAMESPACES {}", (Object) counts);
        for (var pos : List.of(fridge, wardrobe, lamp, chair, table, shelf, a, b))
            l.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        ChestTravel.forget(p);
        p.discard();
        partner.discard();
    }
}
