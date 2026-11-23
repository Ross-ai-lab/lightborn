package lightborn.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class NewWorldMixin {
    @Inject(method = "setInitialSpawn", at = @At("RETURN"))
    private static void lightborn$keepGear(
            ServerLevel level,
            ServerLevelData data,
            boolean bonusChest,
            boolean debug,
            LevelLoadListener listener,
            CallbackInfo ci) {
        if (!debug) level.getGameRules().set(GameRules.KEEP_INVENTORY, true, level.getServer());
    }
}
