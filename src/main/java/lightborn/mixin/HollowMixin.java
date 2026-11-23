package lightborn.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import lightborn.Hollow;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Hollow's hold on things ({@link Hollow}): the void's pull at its edges and its kinds' dark names. */
@Mixin(LivingEntity.class)
public abstract class HollowMixin {
    @Inject(method = "travel", at = @At("HEAD"))
    private void lightborn$voidPull(Vec3 input, CallbackInfo ci) {
        var self = (LivingEntity) (Object) this;
        if (Hollow.pulled(self)) Hollow.pull((Player) self);
    }

    /** An Ender kind standing in the Hollow is called by its dark name (death messages, name tags, the Book). */
    @Mixin(Entity.class)
    public abstract static class Names {
        @ModifyReturnValue(method = "getTypeName", at = @At("RETURN"))
        private Component lightborn$darkName(Component name) {
            var dark = Hollow.darkName((Entity) (Object) this);
            return dark == null ? name : dark;
        }
    }

    /** A Darkman trails faint black smoke where an Enderman sheds portal sparks. */
    @Mixin(Enderman.class)
    public abstract static class Smoke {
        @WrapOperation(
                method = "aiStep",
                at =
                        @At(
                                value = "INVOKE",
                                target =
                                        "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
        private void lightborn$smoke(
                Level level,
                ParticleOptions particle,
                double x,
                double y,
                double z,
                double dx,
                double dy,
                double dz,
                Operation<Void> original) {
            if (Hollow.in(level)) original.call(level, ParticleTypes.SMOKE, x, y, z, dx * .02, .01, dz * .02);
            else original.call(level, particle, x, y, z, dx, dy, dz);
        }
    }

    /** A Darkmite sheds the same faint smoke. */
    @Mixin(net.minecraft.world.entity.monster.Endermite.class)
    public abstract static class MiteSmoke {
        @WrapOperation(
                method = "aiStep",
                at =
                        @At(
                                value = "INVOKE",
                                target =
                                        "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
        private void lightborn$smoke(
                Level level,
                ParticleOptions particle,
                double x,
                double y,
                double z,
                double dx,
                double dy,
                double dz,
                Operation<Void> original) {
            if (Hollow.in(level)) original.call(level, ParticleTypes.SMOKE, x, y, z, dx * .02, .01, dz * .02);
            else original.call(level, particle, x, y, z, dx, dy, dz);
        }
    }

    /** The Dark Dragon's boss bar is bone-white, as its land, not the Ender Dragon's pink. */
    @Mixin(net.minecraft.world.level.dimension.end.EnderDragonFight.class)
    public abstract static class Bar {
        @org.spongepowered.asm.mixin.injection.ModifyArg(
                method = "init",
                at =
                        @At(
                                value = "INVOKE",
                                target =
                                        "Lnet/minecraft/server/level/ServerBossEvent;<init>(Ljava/util/UUID;Lnet/minecraft/network/chat/Component;Lnet/minecraft/world/BossEvent$BossBarColor;Lnet/minecraft/world/BossEvent$BossBarOverlay;)V"),
                index = 2)
        private net.minecraft.world.BossEvent.BossBarColor lightborn$white(
                net.minecraft.world.BossEvent.BossBarColor color) {
            return net.minecraft.world.BossEvent.BossBarColor.WHITE;
        }
    }
}
