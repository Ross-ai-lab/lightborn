package lightborn.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import lightborn.LightWards;
import lightborn.NightSpawns;
import lightborn.RegionalSpawns;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Inject(method = "createState", at = @At("RETURN"))
    private static void lightborn$bindNightBudget(
            int chunks,
            ServerLevel level,
            NaturalSpawner.ChunkGetter getter,
            LocalMobCapCalculator local,
            CallbackInfoReturnable<NaturalSpawner.SpawnState> result) {
        ((NightSpawns.Context) result.getReturnValue()).lightborn$spawnLevel(level);
        if (local != null) ((NightSpawns.Context) local).lightborn$spawnLevel(level);
    }

    @ModifyReturnValue(method = "mobsAt", at = @At("RETURN"))
    private static WeightedList<MobSpawnSettings.SpawnerData> lightborn$cavePool(
            WeightedList<MobSpawnSettings.SpawnerData> pool,
            ServerLevel level,
            StructureManager structures,
            ChunkGenerator generator,
            MobCategory category,
            BlockPos pos) {
        var regional = level.dimension() == Level.OVERWORLD && category == MobCategory.MONSTER && pos.getY() < 0
                ? RegionalSpawns.belowZero(pool)
                : pool;
        return NightSpawns.pool(level, category, regional);
    }

    @Inject(method = "isValidPositionForMob", at = @At("HEAD"), cancellable = true)
    private static void lightborn$protectWard(
            ServerLevel level, Mob mob, double distanceSquared, CallbackInfoReturnable<Boolean> result) {
        if (LightWards.preventsNaturalSpawn(level, mob)) result.setReturnValue(false);
    }
}
