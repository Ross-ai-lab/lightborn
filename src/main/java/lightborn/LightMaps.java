package lightborn;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.saveddata.maps.*;

/** A normal terrain map with a quest marker, made self-lit only in the hand renderer. */
public final class LightMaps {
    private LightMaps() {}

    public static boolean isLightMap(ItemStack s) {
        return s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getBoolean("lightborn_light_map")
                .orElse(false);
    }

    public static void give(ServerPlayer p, BlockPos target) {
        var stack = MapItem.create(p.level(), target.getX(), target.getZ(), (byte) 1, true, true);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Light Map").withStyle(ChatFormatting.GOLD));
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, d -> {
            var tag = d.copyTag();
            tag.putBoolean("lightborn_light_map", true);
            return CustomData.of(tag);
        });
        MapItemSavedData.addTargetDecoration(stack, target, "light_target", MapDecorationTypes.RED_X);
        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
            if (isLightMap(p.getInventory().getItem(i))) {
                p.getInventory().setItem(i, stack);
                return;
            }
        if (!p.getInventory().add(stack)) p.spawnAtLocation(p.level(), stack);
    }
}
