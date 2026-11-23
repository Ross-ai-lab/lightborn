package lightborn;

import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Moving home: a small home (chest, a barrel of diamonds, a torch, a sheep, a name) is lifted onto a lower platform
 * 200 blocks away and everything must arrive, nothing may drop, the ward and its owner must follow, and grass must
 * close the old site. Also: the chest cannot be broken, only its owner may move it, and the site finder picks level,
 * dry ground.
 */
final class SelfTestHomeMove {
    private SelfTestHomeMove() {}

    static void check(String name, boolean ok, Object detail) {
        SelfTest.check("home_move_" + name, ok, detail);
    }

    private static void platform(ServerLevel l, BlockPos centre, int r) {
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) {
                l.setBlockAndUpdate(centre.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 12; y++)
                    l.setBlockAndUpdate(centre.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
    }

    private static void load(ServerLevel l, BlockPos centre, int r) {
        for (int cx = (centre.getX() - r) >> 4; cx <= (centre.getX() + r) >> 4; cx++)
            for (int cz = (centre.getZ() - r) >> 4; cz <= (centre.getZ() + r) >> 4; cz++) l.getChunk(cx, cz);
    }

    static void run(ServerLevel l, BlockPos at) {
        var owner = FakePlayer.get(l, new GameProfile(UUID.randomUUID(), "HomeMover"));
        var stranger = FakePlayer.get(l, new GameProfile(UUID.randomUUID(), "HomeStranger"));
        int radius = 6;
        BlockPos from = at.offset(320, 0, 320), site = from.offset(200, -10, 0);
        load(l, from, 24);
        load(l, site, 24);
        platform(l, from, radius + 2);
        platform(l, site, radius + 2);
        l.setBlockAndUpdate(from, ModItems.LIGHT_CHEST.defaultBlockState());
        LightWards.add(l, from);
        var owners = new HashMap<>(l.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of()));
        owners.put(Long.toString(from.asLong()), new LightWards.Owner(owner.getUUID(), radius));
        l.setAttached(ModData.LIGHT_WARD_OWNERS, Map.copyOf(owners));
        l.setBlockAndUpdate(from.east(2), Blocks.BARREL.defaultBlockState());
        if (l.getBlockEntity(from.east(2)) instanceof BarrelBlockEntity barrel)
            barrel.setItem(0, new ItemStack(Items.DIAMOND, 5));
        l.setBlockAndUpdate(from.west(2), Blocks.TORCH.defaultBlockState());
        var sheep = EntityTypes.SHEEP.create(l, EntitySpawnReason.COMMAND);
        sheep.snapTo(from.getX() + .5, from.getY(), from.getZ() - 1.5, 0, 0);
        sheep.setNoAi(true);
        l.addFreshEntity(sheep);
        owner.setPos(from.getX() + 1.5, from.getY(), from.getZ() + 1.5);
        stranger.setPos(from.getX() + 1.5, from.getY(), from.getZ() + 2.5);

        check(
                "chest_unbreakable",
                ModItems.LIGHT_CHEST.defaultBlockState().getDestroySpeed(l, from) < 0
                        && ModItems.LIGHT_CHEST.getExplosionResistance() > 1000,
                ModItems.LIGHT_CHEST.defaultBlockState().getDestroySpeed(l, from));
        String strangerWhy = HomeMove.problem(stranger, from);
        check(
                "owner_only",
                strangerWhy != null && strangerWhy.contains("owner") && HomeMove.problem(owner, from) == null,
                strangerWhy);

        BlockPos to = HomeMove.relocate(l, from, site.getX(), site.getZ(), radius);
        check(
                "chest_arrives_on_new_ground",
                l.getBlockState(to).is(ModItems.LIGHT_CHEST)
                        && !l.getBlockState(from).is(ModItems.LIGHT_CHEST)
                        && to.getY() == site.getY(),
                to.toShortString());
        boolean barrel = l.getBlockEntity(to.east(2)) instanceof BarrelBlockEntity b
                && b.getItem(0).is(Items.DIAMOND)
                && b.getItem(0).getCount() == 5;
        int dropped = l.getEntitiesOfClass(ItemEntity.class, new AABB(from).inflate(radius + 4))
                .size();
        check(
                "contents_travel_and_nothing_drops",
                barrel
                        && l.getBlockState(to.west(2)).is(Blocks.TORCH)
                        && dropped == 0
                        && l.getBlockState(from.east(2)).isAir(),
                "barrel=" + barrel + " dropped=" + dropped);
        String key = Long.toString(to.asLong());
        var moved = l.getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of()).get(key);
        check(
                "ward_owner_follows",
                LightWards.registered(l, to)
                        && !LightWards.registered(l, from)
                        && moved != null
                        && moved.player().equals(owner.getUUID())
                        && moved.radius() == radius,
                moved);
        check(
                "creatures_and_people_travel",
                // Only real world entities ride along; a test FakePlayer is not one (a real player is found the same
                // way).
                sheep.distanceToSqr(to.getX() + .5, to.getY(), to.getZ() - 1.5) < 1,
                sheep.position() + " / " + owner.position());
        check(
                "old_site_closed_with_grass",
                l.getBlockState(from.below()).is(Blocks.GRASS_BLOCK)
                        && l.getBlockState(from).isAir(),
                l.getBlockState(from.below()));

        BlockPos found = HomeMove.findSite(l, at, Balance.LIGHT_WARD_RADIUS);
        check(
                "site_is_level_dry_and_far",
                found != null
                        && HomeMove.level(l, found.getX(), found.getZ(), Balance.LIGHT_WARD_RADIUS)
                        && Math.hypot(found.getX() - at.getX(), found.getZ() - at.getZ()) >= 1000,
                found);

        // Tidy up: the ward, the sheep and both platforms.
        LightWards.remove(l, to);
        LightDragons.remove(l, to);
        ChestGlow.remove(l, to);
        sheep.discard();
        for (var centre : new BlockPos[] {from, to})
            for (int x = -radius - 2; x <= radius + 2; x++)
                for (int z = -radius - 2; z <= radius + 2; z++)
                    for (int y = -1; y < 12; y++)
                        l.setBlockAndUpdate(centre.offset(x, y, z), Blocks.AIR.defaultBlockState());
        owner.discard();
        stranger.discard();
    }
}
