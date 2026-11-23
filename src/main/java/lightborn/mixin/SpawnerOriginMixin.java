package lightborn.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import lightborn.ModData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Mark after custom spawner NBT loads, before the entity-load callback can promote a wild boss. */
@Mixin(BaseSpawner.class)
public abstract class SpawnerOriginMixin {
    @WrapOperation(
            method = "serverTick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ServerLevel;tryAddFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean lightborn$markSpawner(ServerLevel level, Entity entity, Operation<Boolean> original) {
        entity.getSelfAndPassengers().forEach(e -> e.setAttached(ModData.SPAWNER_MONSTER, true));
        return original.call(level, entity);
    }
}
