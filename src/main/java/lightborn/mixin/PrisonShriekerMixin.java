package lightborn.mixin;

import lightborn.DragonStory;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SculkShriekerBlockEntity.class)
public abstract class PrisonShriekerMixin {
    @Inject(method = "tryShriek", at = @At("HEAD"), cancellable = true)
    private void lightborn$keepWardenAsleep(ServerLevel l, ServerPlayer p, CallbackInfo ci) {
        if (DragonStory.sleepingWarden(l, ((SculkShriekerBlockEntity) (Object) this).getBlockPos())) ci.cancel();
    }
}
