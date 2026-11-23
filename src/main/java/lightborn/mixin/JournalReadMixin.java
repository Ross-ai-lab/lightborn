package lightborn.mixin;

import lightborn.CollectionLedger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class JournalReadMixin {
    @Inject(method = "openItemGui", at = @At("RETURN"))
    private void lightborn$readJournal(ItemStack book, InteractionHand hand, CallbackInfo ci) {
        CollectionLedger.read((ServerPlayer) (Object) this, book);
    }
}
