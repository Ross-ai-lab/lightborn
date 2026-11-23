package lightborn;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A giant zombie (owner, 2026-11-09): a zombie twice the size (about four blocks tall, so it cannot follow you through
 * a door), slower, with three times the health and half again the hit (owner, 2026-11-17), hard to knock back, and it steps up whole
 * blocks without jumping. Never a baby, never calls other giants for help and does not turn into a drowned. The
 * biggest kill of the ordinary night, so it pays like one (Balance.GIANT_*). The size is the entity's real size
 * (hitbox, eyes, spawn checks), not only how it is drawn, so it never spawns into a two-block cave and chokes.
 */
public class GiantZombie extends Zombie implements Giants.Giant {
    public static final float SIZE = 2f;

    public GiantZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 60)
                .add(Attributes.ATTACK_DAMAGE, 4.5)
                .add(Attributes.MOVEMENT_SPEED, 0.19)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    public void setBaby(boolean baby) {
        super.setBaby(false);
    }

    @Override
    protected void randomizeReinforcementsChance() {
        var chance = getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
        if (chance != null) chance.setBaseValue(0);
    }

    @Override
    protected boolean convertsInWater() {
        return false;
    }

    // Deeper (owner): a low, loud voice and footfalls you hear coming.
    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.55f;
    }

    @Override
    protected float getSoundVolume() {
        return 1.5f;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.IRON_GOLEM_STEP, 0.7f, 0.65f + random.nextFloat() * 0.1f);
    }
}
