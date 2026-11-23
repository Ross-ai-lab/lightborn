package lightborn.mixin;

import lightborn.BossTrophies;
import lightborn.DragonForm;
import lightborn.Freeze;
import lightborn.Monsters;
import lightborn.Souls;
import lightborn.StoryBosses;
import lightborn.WardCombat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * One choke point for every hit in the game: levelled monsters hit harder (melee, arrows, explosions),
 * bosses past the health cap take proportionally less, Mana Shield soaks part of what a player takes.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    /** A broken Light shield blocks nothing until it is mended (owner, 2026-11-20). */
    @Inject(method = "getItemBlockingWith", at = @At("RETURN"), cancellable = true)
    private void lightborn$brokenShield(CallbackInfoReturnable<net.minecraft.world.item.ItemStack> result) {
        var s = result.getReturnValue();
        if (s != null && lightborn.LightGear.broken(s)) result.setReturnValue(null);
    }

    /** The giants speak low and loud (the Giant Zombie sets its own voice). */
    @Inject(method = "getVoicePitch", at = @At("RETURN"), cancellable = true)
    private void lightborn$giantVoice(CallbackInfoReturnable<Float> result) {
        if (lightborn.Giants.is(this) && !((Object) this instanceof lightborn.GiantZombie))
            result.setReturnValue(result.getReturnValue() * .6f);
    }

    @Inject(method = "getSoundVolume", at = @At("RETURN"), cancellable = true)
    private void lightborn$giantVolume(CallbackInfoReturnable<Float> result) {
        if (lightborn.Giants.is(this) && !((Object) this instanceof lightborn.GiantZombie))
            result.setReturnValue(result.getReturnValue() * 1.5f);
    }

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void lightborn$noSanctuaryDamage(
            ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> result) {
        if (WardCombat.prevents((LivingEntity) (Object) this, source)) result.setReturnValue(false);
    }

    @Inject(
            method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void lightborn$noSanctuaryPoison(
            MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> result) {
        if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL
                && (WardCombat.blocksEffect((LivingEntity) (Object) this, source)
                        || lightborn.NatureSpells.warded((LivingEntity) (Object) this))) result.setReturnValue(false);
    }

    /** Frozen solid ({@link Freeze}): vanilla's immobile path skips the whole AI step but keeps knockback and gravity. */
    @Inject(method = "isImmobile", at = @At("RETURN"), cancellable = true)
    private void lightborn$frozenSolid(CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValueZ() && Freeze.frozen((LivingEntity) (Object) this)) result.setReturnValue(true);
    }

    /** Spider Form climbs any wall it walks into (both sides: the client moves its own player). */
    @Inject(method = "onClimbable", at = @At("RETURN"), cancellable = true)
    private void lightborn$spiderClimbs(CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValueZ() && lightborn.FormSpells.climbs((LivingEntity) (Object) this))
            result.setReturnValue(true);
    }

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void lightborn$noSummonFarming(ServerLevel level, DamageSource source, CallbackInfo ci) {
        if (Souls.summoned((LivingEntity) (Object) this)) ci.cancel();
    }

    @Inject(method = "canAttack", at = @At("HEAD"), cancellable = true)
    private void lightborn$eclipseTarget(LivingEntity target, CallbackInfoReturnable<Boolean> result) {
        if (Monsters.isMonster((LivingEntity) (Object) this) && BossTrophies.concealed(target))
            result.setReturnValue(false);
    }

    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
    private float lightborn$scaleDamage(float amount, ServerLevel level, DamageSource source) {
        if (DragonForm.safeFall((LivingEntity) (Object) this, source)) return 0;
        LivingEntity self = (LivingEntity) (Object) this;
        float a = Monsters.modifyIncomingDamage(self, level, source, amount);
        // Sentinel's chill, Freedom, the Workbench's guard, Weaken, Fright, Shrinking, Doom, the spider and the blaze
        a = lightborn.SpellSchools.incoming(self, source, a);
        return StoryBosses.incoming(self, source, a);
    }
}
