package lightborn.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The point a phantom circles, which the Dark Phantom slides round a ward's ring (DarkPhantom). */
@Mixin(Phantom.class)
public interface PhantomAccessor {
    @Accessor("anchorPoint")
    BlockPos lightborn$anchor();

    @Accessor("anchorPoint")
    void lightborn$setAnchor(BlockPos at);
}
