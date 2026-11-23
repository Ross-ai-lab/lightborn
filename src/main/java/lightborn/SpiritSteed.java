package lightborn;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The horse the Light Steed tome calls (owner, 2026-11-21; renamed from Spirit Steed 2026-11-22): a tame, saddled horse of light that only its caster may
 * ride. It takes no fall and no player's blow, cannot breed, opens no inventory (so its saddle can never be taken), and
 * drops nothing. It is never saved with the world (a spell's tag would have the load hook discard it the moment it
 * is spawned); {@link LightSpells} sends it away.
 */
public class SpiritSteed extends Horse {
    public SpiritSteed(EntityType<? extends Horse> type, Level level) {
        super(type, level);
    }

    /**
     * Always saddled: the saddle set on the server never reached the client, which then would not let its rider steer
     * (owner, 2026-11-22: "I can mount it but can't control it"). A spell's horse has no other state.
     */
    @Override
    public boolean isSaddled() {
        return true;
    }

    @Override
    public boolean canMate(Animal other) {
        return false;
    }

    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (source.getEntity() instanceof Player) return false;
        return super.hurtServer(level, source, amount);
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player == getOwner() && !isVehicle() && !player.isSecondaryUseActive()) {
            if (!level().isClientSide()) player.startRiding(this);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void openCustomInventoryScreen(Player player) {}

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void dropEquipment(ServerLevel level) {}

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide() && random.nextInt(3) == 0)
            level().addParticle(ParticleTypes.SOUL_FIRE_FLAME, getRandomX(.7), getRandomY(), getRandomZ(.7), 0, .02, 0);
    }

    static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder attributes() {
        return AbstractHorse.createBaseHorseAttributes();
    }
}
