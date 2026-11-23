package lightborn;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A hunter spider (owner, 2026-11-09, the spider kinds): banded ochre and brown, quicker than a spider, and it
 * pounces: when its prey is 4 to 10 blocks off and in sight, it leaps the gap in one bound (every 3 seconds at most).
 * Otherwise a spider.
 */
public class HunterSpider extends Spider {
    static final int POUNCE_COOLDOWN = 60;
    private int pounce = POUNCE_COOLDOWN;

    public HunterSpider(EntityType<? extends Spider> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Spider.createAttributes().add(Attributes.MOVEMENT_SPEED, 0.36);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide() || --pounce > 0) return;
        LivingEntity prey = getTarget();
        if (prey == null || !prey.isAlive() || !onGround() || !hasLineOfSight(prey)) return;
        double d = distanceTo(prey);
        if (d < 4 || d > 10) return;
        pounce();
    }

    /** One bound at the target: a jump whose length matches the gap. */
    boolean pounce() {
        LivingEntity prey = getTarget();
        if (prey == null) return false;
        Vec3 gap = prey.position().subtract(position());
        Vec3 flat = new Vec3(gap.x, 0, gap.z);
        if (flat.lengthSqr() < 1e-4) return false;
        double reach = Math.min(1.6, flat.length() * 0.16);
        setDeltaMovement(flat.normalize().scale(reach).add(0, 0.55 + Math.max(0, gap.y) * 0.08, 0));
        needsSync = true;
        pounce = POUNCE_COOLDOWN;
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 1.2f, 1.6f);
        return true;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.8f;
    }
}
