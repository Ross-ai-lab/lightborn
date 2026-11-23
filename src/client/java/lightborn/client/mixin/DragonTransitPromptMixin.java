package lightborn.client.mixin;

import lightborn.LightDragon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(ClientPacketListener.class)
public abstract class DragonTransitPromptMixin {
    @ModifyArg(
            method = "handleSetEntityPassengersPacket",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"),
            index = 0)
    private String lightborn$flightHasNoDismount(String key) {
        var p = Minecraft.getInstance().player;
        return p != null && p.getVehicle() instanceof LightDragon ? "lightborn.flight.onboard" : key;
    }
}
