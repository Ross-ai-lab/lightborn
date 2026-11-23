package lightborn.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container's panel sits, for the Workbench's arrows (`BenchView`). */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("leftPos")
    int lightborn$left();

    @Accessor("topPos")
    int lightborn$top();

    @Accessor("imageWidth")
    int lightborn$width();
}
