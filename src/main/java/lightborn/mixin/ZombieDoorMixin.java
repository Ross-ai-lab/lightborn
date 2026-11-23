package lightborn.mixin;

import lightborn.DoorPoundGoal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Zombie.class)
public abstract class ZombieDoorMixin extends Monster {
    protected ZombieDoorMixin(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void lightborn$pound(CallbackInfo ci) {
        goalSelector.addGoal(1, new DoorPoundGoal((Zombie) (Object) this));
    }

    @ModifyVariable(method = "setCanBreakDoors", at = @At("HEAD"), argsOnly = true)
    private boolean lightborn$neverBreak(boolean value) {
        return false;
    }
}
