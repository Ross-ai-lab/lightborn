package lightborn.client.mixin;

import lightborn.FormSpells;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class SpiritScopeMixin {
    @Inject(method = "isScoping", at = @At("RETURN"), cancellable = true)
    private void lightborn$spiritScope(CallbackInfoReturnable<Boolean> result) {
        var game = Minecraft.getInstance();
        if ((Object) this == game.player
                && FormSpells.spiritSight(game.player)
                && game.gui.screen() == null
                && game.options.keyUse.isDown()) result.setReturnValue(true);
    }
}
