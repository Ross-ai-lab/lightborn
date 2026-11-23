package lightborn.client.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A key the game has no name for (GLFW's -1: a laptop's brightness or media keys, such as Fn + Down) reaches the game
 * as "unknown", the same key every unbound control sits on, so pressing it fired them all: Entity Culling's two debug
 * toggles printed "Culling on / Debug Cullboxes off" (owner, 2026-11-23). Such a key is dropped before anything sees it.
 */
@Mixin(KeyboardHandler.class)
abstract class UnknownKeyMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void lightborn$dropUnknownKey(long handle, int action, KeyEvent event, CallbackInfo ci) {
        if (event.key() == -1 || event.keycode() == -1) ci.cancel();
    }
}
