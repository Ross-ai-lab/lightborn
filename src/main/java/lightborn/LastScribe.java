package lightborn;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;

/** A deliberate fortress encounter; never participates in natural spawning or Hunt boss selection. */
public final class LastScribe extends Zombie {
    public LastScribe(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
        setNoAi(true);
        setNoGravity(true);
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
