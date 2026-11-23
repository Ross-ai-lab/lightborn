package lightborn.mixin;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every game event is a sound the Dark Phantoms may hear (DarkPhantom). */
@Mixin(ServerLevel.class)
public abstract class GameEventNoiseMixin {
    @Inject(method = "gameEvent", at = @At("HEAD"))
    private void lightborn$noise(Holder<GameEvent> event, Vec3 at, GameEvent.Context context, CallbackInfo ci) {
        lightborn.DarkPhantom.gameEvent((ServerLevel) (Object) this, event, at, context);
    }
}
