package lightborn.mixin;

import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The giant creeper's wider blast (Giants). */
@Mixin(Creeper.class)
public interface CreeperAccessor {
    @Accessor("explosionRadius")
    void lightborn$setExplosionRadius(int radius);
}
