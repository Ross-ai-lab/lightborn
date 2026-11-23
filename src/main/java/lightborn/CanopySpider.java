package lightborn;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class CanopySpider extends Spider {
    private int cooldown;
    private boolean rising;
    private boolean diving;

    public CanopySpider(EntityType<? extends Spider> type, Level level) {
        super(type, level);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide()) return;
        var prey = getTarget();
        if (prey == null || !prey.isAlive() || !hasLineOfSight(prey)) {
            rising = false;
            return;
        }
        if (cooldown > 0) cooldown--;
        if (onGround()) diving = false;
        double distance = distanceTo(prey);
        if (onGround() && cooldown == 0 && distance > 3 && distance < 9) {
            Vec3 flat = prey.position().subtract(position()).multiply(1, 0, 1).normalize();
            setDeltaMovement(flat.scale(.35).add(0, 1.1, 0));
            needsSync = true;
            rising = true;
            cooldown = Balance.CANOPY_DIVE_COOLDOWN;
        }
        if (!diving
                && (rising || getY() > prey.getY() + 2)
                && getY() > prey.getY() + 1.5
                && getDeltaMovement().y < .1
                && distance < 9) dive();
    }

    boolean dive() {
        var prey = getTarget();
        if (prey == null) return false;
        Vec3 flat = prey.position().subtract(position()).multiply(1, 0, 1).normalize();
        setDeltaMovement(flat.scale(.85).add(0, -.8, 0));
        needsSync = true;
        rising = false;
        diving = true;
        cooldown = Balance.CANOPY_DIVE_COOLDOWN;
        level().playSound(null, blockPosition(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 1.1f, 1.6f);
        return true;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 1.4f;
    }
}
