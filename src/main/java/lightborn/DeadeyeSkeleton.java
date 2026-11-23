package lightborn;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A deadeye skeleton (owner, 2026-11-09, the skeleton kinds): a marksman. Its arrows barely stray (vanilla
 * skeletons scatter by 6 to 10 on Normal and Easy, this one by 1) and hit half as hard again, and it spots you from
 * further off (24 blocks against 16). Drawn in ash-blue bone with pale eyes that glow in the dark. Otherwise a
 * skeleton.
 */
public class DeadeyeSkeleton extends Skeleton {
    public DeadeyeSkeleton(EntityType<? extends Skeleton> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Skeleton.createAttributes().add(Attributes.FOLLOW_RANGE, 24);
    }

    @Override
    public float rangedAttackUncertainty(Level level) {
        return 1f;
    }

    @Override
    protected AbstractArrow getArrow(ItemStack arrows, float power, ItemStack bow) {
        AbstractArrow arrow = super.getArrow(arrows, power, bow);
        arrow.setBaseDamageFromMob(power * 1.5f); // vanilla sets it from power the same way, half as hard again here
        return arrow;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 1.15f;
    }
}
