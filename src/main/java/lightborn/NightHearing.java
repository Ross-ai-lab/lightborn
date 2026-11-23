package lightborn;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * At night the monsters hear you from far off (owner, 2026-11-19: "dramatically", even inside the ward). Every two
 * seconds, in the Overworld from dusk to dawn, each monster within {@link #EARSHOT} blocks of a player with nothing to
 * chase turns on that player, walls or no walls, and its follow range grows by {@link #REACH} so it keeps coming. A
 * player inside a ward is heard too: the dark gathers at its edge. Neutral kinds (endermen, piglins) are left be,
 * creative and spectator players are never heard, and the Dragon-friend ending lifts it with the rest of the night's
 * weight ({@link DragonStory#nightLifted}).
 */
public final class NightHearing {
    private NightHearing() {}

    static final int EARSHOT = 48, REACH = 48;
    private static final Identifier ID = ModItems.id("night_hearing");

    static void tick(MinecraftServer server) {
        if (Scheduler.now() % 40 != 0) return;
        var level = server.overworld();
        boolean night = LightDragons.night(level) && !DragonStory.nightLifted(level);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension() != Level.OVERWORLD) continue;
            boolean heard = night && p.isAlive() && !p.isCreative() && !p.isSpectator();
            var box = p.getBoundingBox().inflate(heard ? EARSHOT : EARSHOT + 16);
            for (Monster m : p.level().getEntitiesOfClass(Monster.class, box, Monster::isAlive)) {
                if (m instanceof NeutralMob) continue;
                var follow = m.getAttribute(Attributes.FOLLOW_RANGE);
                if (follow != null) {
                    if (night && !follow.hasModifier(ID))
                        follow.addOrUpdateTransientModifier(
                                new AttributeModifier(ID, REACH, AttributeModifier.Operation.ADD_VALUE));
                    else if (!night) follow.removeModifier(ID);
                }
                if (heard
                        && m.getTarget() == null
                        && !Freeze.frozen(m)
                        && m.distanceToSqr(p) <= EARSHOT * EARSHOT
                        && m.canAttack(p)) m.setTarget(p);
            }
        }
    }
}
