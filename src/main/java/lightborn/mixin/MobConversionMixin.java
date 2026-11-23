package lightborn.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import lightborn.ModData;
import lightborn.Monsters;
import lightborn.SummonSpells;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Converting a zombie or splitting a slime cannot wash off its spawner origin. */
@Mixin(Mob.class)
public abstract class MobConversionMixin {
    @WrapOperation(
            method = "convertTo",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean lightborn$keepOrigin(ServerLevel level, Entity converted, Operation<Boolean> original) {
        if (Monsters.fromSpawner((Entity) (Object) this)) converted.setAttached(ModData.SPAWNER_MONSTER, true);
        if (converted instanceof Mob mob) SummonSpells.converted((Mob) (Object) this, mob);
        return original.call(level, converted);
    }
}
