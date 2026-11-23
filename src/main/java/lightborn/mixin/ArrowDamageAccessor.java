package lightborn.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** An arrow's base damage, read so the giant skeletons can make theirs heavier (Giants). */
@Mixin(AbstractArrow.class)
public interface ArrowDamageAccessor {
    @Accessor("baseDamage")
    double lightborn$baseDamage();
}
