package lightborn.mixin;

import lightborn.CollectionLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LecternBlock.class)
abstract class LecternReadMixin {
    @Inject(method = "useWithoutItem", at = @At("RETURN"))
    private void lightborn$readJournal(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> result) {
        if (player instanceof ServerPlayer serverPlayer
                && state.getValue(BlockStateProperties.HAS_BOOK)
                && result.getReturnValue().consumesAction()
                && level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)
            CollectionLedger.read(serverPlayer, lectern.getBook());
    }
}
