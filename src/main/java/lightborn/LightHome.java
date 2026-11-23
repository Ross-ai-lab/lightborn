package lightborn;

import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.server.level.*;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.*;

/** Both death and Homeward return to the placed hub; beds remain sleep furniture. */
public final class LightHome {
    private LightHome() {}

    public static TeleportTransition destination(ServerPlayer p, TeleportTransition.PostTeleportTransition after) {
        var overworld = p.level().getServer().overworld();
        var first = overworld.getRespawnData();
        for (var l : p.level().getServer().getAllLevels())
            for (var chest : l.getAttachedOrElse(ModData.LIGHT_WARDS, List.of())) {
                l.getChunkAt(chest);
                if (!l.getBlockState(chest).is(ModItems.LIGHT_CHEST)) continue;
                for (int radius = 1; radius <= 4; radius++)
                    for (int dx = -radius; dx <= radius; dx++)
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                            for (int dy : new int[] {0, 1, -1, 2, -2}) {
                                var at = chest.offset(dx, dy, dz);
                                var v = Vec3.atBottomCenterOf(at);
                                if (l.getBlockState(at.below()).isFaceSturdy(l, at.below(), Direction.UP)
                                        && l.getFluidState(at).isEmpty()
                                        && l.noCollision(
                                                p, new AABB(v.x - .3, v.y, v.z - .3, v.x + .3, v.y + 1.8, v.z + .3)))
                                    return new TeleportTransition(l, v, Vec3.ZERO, first.yaw(), 0, after);
                            }
                        }
            }
        var start = first.pos();
        overworld.getChunkAt(start);
        for (int radius = 0; radius <= 4; radius++)
            for (int dx = -radius; dx <= radius; dx++)
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = 0; dy <= 8; dy++) {
                        var at = start.offset(dx, dy, dz);
                        var v = Vec3.atBottomCenterOf(at);
                        if (overworld.getBlockState(at.below()).isFaceSturdy(overworld, at.below(), Direction.UP)
                                && overworld.getFluidState(at).isEmpty()
                                && overworld.noCollision(
                                        p, new AABB(v.x - .3, v.y, v.z - .3, v.x + .3, v.y + 1.8, v.z + .3)))
                            return new TeleportTransition(overworld, v, Vec3.ZERO, first.yaw(), first.pitch(), after);
                    }
                }
        return new TeleportTransition(
                overworld, Vec3.atBottomCenterOf(start), Vec3.ZERO, first.yaw(), first.pitch(), after);
    }
}
