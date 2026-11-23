package lightborn;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class DuneSkeleton extends Skeleton {
    public DuneSkeleton(EntityType<? extends Skeleton> type, Level level) {
        super(type, level);
    }

    @Override
    protected AbstractArrow getArrow(ItemStack arrows, float power, ItemStack bow) {
        AbstractArrow arrow = super.getArrow(arrows, power, bow);
        if (arrow instanceof Arrow tipped)
            tipped.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, Balance.DUNE_BLIND_TICKS, 0));
        return arrow;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * .7f;
    }
}
