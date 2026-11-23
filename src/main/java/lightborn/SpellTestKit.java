package lightborn;

import static lightborn.SelfTest.base;
import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.level;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * What every school's self-test shares (owner, 2026-11-23: the tests grouped by school, as the spells are): the test
 * player on the spell field facing east, casting from a fresh tome with full mana, test monsters that stand still,
 * and the clean slate between tests.
 */
final class SpellTestKit {
    private SpellTestKit() {}

    static final List<LivingEntity> SPAWNED = new ArrayList<>();

    static boolean cast(Spell spell) {
        return cast(spell, null);
    }

    /** Casts with the given upgrade picks (one per tier, -1 for none), or a bare tome for null. */
    static boolean cast(Spell spell, int[] picks) {
        Spells.forget(caster);
        Progression.setMana(caster, (float) Progression.maxMana(caster));
        var tome = new ItemStack(ModItems.TOMES.get(spell));
        if (picks != null) SpellUpgrades.write(tome, picks);
        caster.setItemInHand(InteractionHand.OFF_HAND, tome);
        return Spells.cast(caster, caster.getOffhandItem());
    }

    /** Casts {@code spell} from a tome with these upgrades in use (one node per tier). */
    static boolean castWith(Spell spell, int[] upgrades) {
        return cast(spell, upgrades);
    }

    static <T extends Mob> T mob(EntityType<T> type, double dx, double dz) {
        T m = type.create(level, EntitySpawnReason.COMMAND);
        m.snapTo(caster.getX() + dx, caster.getY(), caster.getZ() + dz, 90f, 0f);
        m.setNoAi(true);
        m.setPersistenceRequired();
        level.addFreshEntity(m);
        SPAWNED.add(m);
        return m;
    }

    /** A zombie that takes a lot of killing, so a test can read how much it lost. */
    static Mob tough(double dx, double dz) {
        var z = mob(EntityTypes.ZOMBIE, dx, dz);
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500);
        z.setHealth(500);
        return z;
    }

    /** The test player on its mark, facing east, standing still. */
    static void face() {
        caster.setPos(base.getX() - 6.5, base.getY(), base.getZ() + 6.5);
        caster.setYRot(-90f); // east
        caster.setYHeadRot(-90f);
        caster.setXRot(0f);
        caster.setDeltaMovement(Vec3.ZERO);
    }

    /** On its mark at full health. */
    static void faceHealed() {
        face();
        caster.setHealth(caster.getMaxHealth());
    }

    /** On its mark at full health with no effects. */
    static void faceFresh() {
        faceHealed();
        caster.removeAllEffects();
    }

    /** Looks down at the ground {@code ahead} blocks east of the caster's feet. */
    static void lookDown(double ahead) {
        caster.setXRot((float) Math.toDegrees(Math.atan2(caster.getEyeHeight(), ahead)));
    }

    /** The conjured blocks (Stone Wall, Light Bridge) round a spot. */
    static List<BlockPos> conjured(BlockPos around, int r) {
        var out = new ArrayList<BlockPos>();
        for (var c : BlockPos.betweenClosed(around.offset(-r, -3, -r), around.offset(r, 4, r)))
            if (level.getBlockState(c).getBlock() instanceof ConjuredBlock) out.add(c.immutable());
        return out;
    }

    /** A clean slate: test monsters gone, every spell effect ended, thralls and forms let go, the player plain. */
    static void clean() {
        SPAWNED.forEach(LivingEntity::discard);
        SPAWNED.clear();
        FormSpells.clearAll();
        SpellSchools.clear();
        LightSpells.instant = false;
        SummonSpells.THRALLS.keySet().forEach(Mob::discard);
        SummonSpells.THRALLS.clear();
        caster.removeAttached(ModData.BOUND_THRALLS);
        caster.removeAttached(ModData.COMMAND_ROSTER);
        if (DragonForm.active(caster)) DragonForm.end(caster);
        caster.removeAllEffects();
        caster.horizontalCollision = false;
        if (caster.gameMode() != GameType.SURVIVAL) caster.setGameMode(GameType.SURVIVAL);
    }
}
