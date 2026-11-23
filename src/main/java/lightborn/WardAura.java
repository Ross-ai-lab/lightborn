package lightborn;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;

/** One non-ticking-physics render anchor per chest, not a cloud of particles or invisible wall blocks. */
public final class WardAura extends Entity {
    private static final EntityDataAccessor<Integer> RADIUS =
            SynchedEntityData.defineId(WardAura.class, EntityDataSerializers.INT);
    /** A Cleanse runs here: the ward still shines but no longer shields (the client drops the threshold). */
    private static final EntityDataAccessor<Boolean> SUSPENDED =
            SynchedEntityData.defineId(WardAura.class, EntityDataSerializers.BOOLEAN);

    /** Cracks a Cleanse has put in the dome; they heal one by one after dawn (owner, 2026-11-16). */
    private static final EntityDataAccessor<Integer> SCARS =
            SynchedEntityData.defineId(WardAura.class, EntityDataSerializers.INT);

    /** Raised by the Ward spell, not a chest: drawn and lit the same, but no membrane holds you crossing it. */
    private static final EntityDataAccessor<Boolean> SPELL =
            SynchedEntityData.defineId(WardAura.class, EntityDataSerializers.BOOLEAN);

    public static final int MAX_SCARS = 12;

    private BlockPos chest;
    /** Client: the cracks drawn, easing toward {@link #scars()} so each one runs open, or closes, over a second. */
    private float shownScars, lastShownScars;

    public static void init() {}

    public WardAura(EntityType<?> type, Level l) {
        super(type, l);
        noPhysics = true;
        setNoGravity(true);
    }

    public int radius() {
        return entityData.get(RADIUS);
    }

    public boolean spell() {
        return entityData.get(SPELL);
    }

    /** A Ward spell's dome round {@code at}; the spell takes it away. */
    void spell(net.minecraft.world.phys.Vec3 at, int radius) {
        setPos(at.x, at.y, at.z);
        entityData.set(RADIUS, radius);
        entityData.set(SPELL, true);
    }

    public boolean suspended() {
        return entityData.get(SUSPENDED);
    }

    public int scars() {
        return entityData.get(SCARS);
    }

    void scars(int n) {
        entityData.set(SCARS, Math.clamp(n, 0, MAX_SCARS));
    }

    public float shownScars(float partial) {
        return lastShownScars + (shownScars - lastShownScars) * partial;
    }

    void bind(BlockPos p, int radius, boolean suspended) {
        chest = p.immutable();
        setPos(p.getX() + .5, p.getY() + .5, p.getZ() + .5);
        entityData.set(RADIUS, radius);
        entityData.set(SUSPENDED, suspended);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(RADIUS, 18);
        b.define(SUSPENDED, false);
        b.define(SCARS, 0);
        b.define(SPELL, false);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {}

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {}

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel l, DamageSource s, float n) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 128 * 128;
    }

    @Override
    public void tick() {
        super.tick();
        lastShownScars = shownScars;
        shownScars += Math.clamp(scars() - shownScars, -.03f, .05f);
        if (spell()) {
            if (!level().isClientSide() && tickCount > 20 * 60 * 5) discard(); // a stray the spell lost
            return;
        }
        if (level() instanceof ServerLevel l
                && (chest == null
                        || !l.hasChunkAt(chest)
                        || !l.getBlockState(chest).is(ModItems.LIGHT_CHEST))) discard();
    }
}
