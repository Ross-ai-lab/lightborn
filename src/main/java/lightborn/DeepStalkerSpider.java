package lightborn;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.Level;

public class DeepStalkerSpider extends Spider {
    public DeepStalkerSpider(EntityType<? extends Spider> type, Level level) {
        super(type, level);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide()) return;
        boolean bound = getAttached(ModData.THRALL_MASTER) != null;
        var nearby = level().getNearestPlayer(getX(), getY(), getZ(), Balance.STALKER_REVEAL, false);
        setInvisible(!bound && nearby == null);
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * .55f;
    }
}
