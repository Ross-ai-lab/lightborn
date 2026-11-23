package lightborn;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Verify companion content through the same registries, recipes and buffs used during play. */
final class SelfTestExpansion {
    static void run(ServerLevel level, BlockPos at) {
        var loader = FabricLoader.getInstance();
        SelfTest.check(
                "delight_and_sound_physics_stay_removed",
                !loader.isModLoaded("farmersdelight") && !loader.isModLoaded("sound_physics_remastered"),
                null);
        SelfTest.check(
                "expansion_common_mods_loaded",
                List.of("sit-oth3r", "visualworkbench", "terralith").stream().allMatch(loader::isModLoaded),
                null);
        String[][] samples = {{"naturalist:cooked_crab_meat", "food_and_drinks"}};
        var missing = new ArrayList<String>();
        for (var sample : samples) {
            var group = Collection.categoryOf(sample[0]);
            if (group == null || !group.key().equals(sample[1]))
                missing.add(sample[0] + " -> " + (group == null ? "missing" : group.key()));
        }
        SelfTest.check("expansion_items_in_collection_groups", missing.isEmpty(), missing);
        var p = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "ExpansionTest"));
        p.setPos(at.getX(), at.getY(), at.getZ());
        level.setBlockAndUpdate(
                at, ((BlockItem) Items.CRAFTING_TABLE).getBlock().defaultBlockState());
        SelfTest.check(
                "visual_workbench_has_persistent_inventory",
                level.getBlockEntity(at) instanceof Container,
                String.valueOf(level.getBlockEntity(at)));
        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
        var biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        int terra = 0;
        for (var biome : biomes) {
            String ns = biomes.getKey(biome).getNamespace();
            if (ns.equals("terralith")) terra++;
        }
        SelfTest.check("terralith_biomes_registered", terra > 50, terra);
        p.discard();
        SelfTestStory.run(level, at.above(12));
        SelfTestHome.run(level, at.above(6));
        SelfTestHomeMove.run(level, at.above(6));
        SelfTestRefugee.run(level, at.above(6));
    }
}
