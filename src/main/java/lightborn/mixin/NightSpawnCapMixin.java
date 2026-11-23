package lightborn.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.*;
import lightborn.NightSpawns;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(NaturalSpawner.SpawnState.class)
abstract class NightSpawnCapMixin implements NightSpawns.Context {
    @Unique
    private ServerLevel lightborn$level;

    public void lightborn$spawnLevel(ServerLevel l) {
        lightborn$level = l;
    }

    @ModifyExpressionValue(
            method = "canSpawnForCategoryGlobal",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/MobCategory;getMaxInstancesPerChunk()I"))
    private int lightborn$nightGlobalCap(int normal, MobCategory category) {
        return NightSpawns.cap(normal, lightborn$level, category);
    }

    @Mixin(LocalMobCapCalculator.class)
    abstract static class Local implements NightSpawns.Context {
        @Unique
        private ServerLevel lightborn$level;

        @Shadow
        @Final
        private Map<ServerPlayer, ?> playerMobCounts;

        @Shadow
        private List<ServerPlayer> getPlayersNear(ChunkPos pos) {
            throw new AssertionError();
        }

        public void lightborn$spawnLevel(ServerLevel l) {
            lightborn$level = l;
        }

        @Inject(method = "canSpawn", at = @At("HEAD"), cancellable = true)
        private void lightborn$nightLocalCap(
                MobCategory category, ChunkPos pos, CallbackInfoReturnable<Boolean> result) {
            if (category != MobCategory.MONSTER || !NightSpawns.active(lightborn$level)) return;
            int limit = NightSpawns.cap(category.getMaxInstancesPerChunk(), lightborn$level, category);
            for (var player : getPlayersNear(pos)) {
                var counts = playerMobCounts.get(player);
                if (counts == null || ((Counts) counts).lightborn$counts().getInt(category) < limit) {
                    result.setReturnValue(true);
                    return;
                }
            }
            result.setReturnValue(false);
        }
    }

    @Mixin(targets = "net.minecraft.world.level.LocalMobCapCalculator$MobCounts")
    interface Counts {
        @Accessor("counts")
        Object2IntMap<MobCategory> lightborn$counts();
    }
}
