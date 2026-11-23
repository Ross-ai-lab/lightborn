package lightborn;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * A weaver spider (owner, 2026-11-09, the spider kinds): pale as silk, and its bite spins a cobweb at your feet that
 * holds you for 3 seconds, then falls apart. Only into empty air, never over a block, and every web it spun is
 * cleared when the server stops, so none are left lying around. Otherwise a spider.
 */
public class WeaverSpider extends Spider {
    public static final int WEB_TICKS = 60;
    /** Webs spun and not yet gone, so a server stop can clear them. */
    private static final List<Web> WEBS = new ArrayList<>();

    private record Web(ServerLevel level, BlockPos pos) {}

    public WeaverSpider(EntityType<? extends Spider> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        boolean hit = super.doHurtTarget(level, target);
        if (hit && target instanceof LivingEntity living && living.isAlive()) spin(level, living.blockPosition());
        return hit;
    }

    /** A cobweb at {@code pos} if it is air, gone again after WEB_TICKS. */
    static boolean spin(ServerLevel level, BlockPos pos) {
        return spin(level, pos, WEB_TICKS);
    }

    static boolean spin(ServerLevel level, BlockPos pos, int ticks) {
        if (!level.getBlockState(pos).isAir()) return false;
        level.setBlockAndUpdate(pos, Blocks.COBWEB.defaultBlockState());
        level.playSound(null, pos, SoundEvents.COBWEB_PLACE, SoundSource.HOSTILE, 1f, 0.8f);
        Web web = new Web(level, pos.immutable());
        WEBS.add(web);
        Scheduler.later(ticks, () -> unspin(web));
        return true;
    }

    private static void unspin(Web web) {
        WEBS.remove(web);
        if (web.level.getBlockState(web.pos).is(Blocks.COBWEB)) web.level.removeBlock(web.pos, false);
    }

    /** Server stopping: no web of a weaver's stays in the world. */
    static void clearWebs() {
        for (Web web : List.copyOf(WEBS)) unspin(web);
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 1.3f;
    }
}
