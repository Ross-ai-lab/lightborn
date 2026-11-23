package lightborn;

import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

public class DrownedCaptain extends Drowned {
    public DrownedCaptain(EntityType<? extends Drowned> type, Level level) {
        super(type, level);
        arm();
    }

    private void arm() {
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        setDropChance(EquipmentSlot.MAINHAND, .085f);
    }

    @Override
    public void setBaby(boolean baby) {
        super.setBaby(false);
    }

    @Override
    protected void populateDefaultEquipmentSlots(RandomSource random, DifficultyInstance difficulty) {
        super.populateDefaultEquipmentSlots(random, difficulty);
        arm();
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * .65f;
    }

    public static void pull(Entity victim, Entity owner) {
        var toward = owner.position().subtract(victim.position()).multiply(1, 0, 1);
        if (toward.lengthSqr() > .01) {
            victim.push(toward.normalize().scale(Balance.CAPTAIN_PULL).add(0, .2, 0));
            victim.needsSync = true;
        }
    }
}
