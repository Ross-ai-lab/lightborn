package lightborn.client;

import java.util.HashMap;
import java.util.Map;
import lightborn.FormSpells;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * Draws a player in a beast form ({@link lightborn.FormSpells}) as that beast. Each such player has a stand-in mob that is never
 * added to the world (nothing can hit it, save it or tick it); just before the player is drawn, the stand-in takes the
 * player's place, turn, steps and swing, and is drawn instead (`FormRenderMixin`). So the beast moves exactly as the
 * player does, with the same interpolation, and the player's own body is not drawn at all.
 */
public final class FormView {
    private FormView() {}

    private static final Map<Integer, LivingEntity> STAND_INS = new HashMap<>();

    private static EntityType<? extends LivingEntity> type(FormSpells.Form f) {
        return switch (f) {
            case WOLF -> EntityTypes.WOLF;
            case BEAR -> EntityTypes.POLAR_BEAR;
            case SPIDER -> EntityTypes.SPIDER;
            case BLAZE -> EntityTypes.BLAZE;
            case WARDEN -> EntityTypes.WARDEN;
            default -> null;
        };
    }

    /** The mob to draw in {@code e}'s place, set to its pose; null to draw {@code e} itself. */
    public static Entity standIn(Entity e) {
        if (!(e instanceof Player p)) return null;
        var type = type(FormSpells.form(p));
        if (type == null) {
            if (!STAND_INS.isEmpty()) STAND_INS.remove(p.getId());
            return null;
        }
        LivingEntity d = STAND_INS.get(p.getId());
        if (d == null || d.getType() != type || d.level() != p.level()) {
            d = type.create(p.level(), EntitySpawnReason.LOAD);
            if (d == null) return null;
            STAND_INS.put(p.getId(), d);
        }
        copy(p, d);
        return d;
    }

    private static void copy(Player p, LivingEntity d) {
        // The player's own id: the renderer asks for one, and anything that looks the drawn state up by id finds the
        // player.
        d.setId(p.getId());
        d.setPos(p.getX(), p.getY(), p.getZ());
        d.xo = p.xo;
        d.yo = p.yo;
        d.zo = p.zo;
        d.xOld = p.xOld;
        d.yOld = p.yOld;
        d.zOld = p.zOld;
        d.setYRot(p.getYRot());
        d.yRotO = p.yRotO;
        d.setXRot(p.getXRot());
        d.xRotO = p.xRotO;
        d.yBodyRot = p.yBodyRot;
        d.yBodyRotO = p.yBodyRotO;
        d.yHeadRot = p.yHeadRot;
        d.yHeadRotO = p.yHeadRotO;
        d.tickCount = p.tickCount;
        d.setOnGround(p.onGround());
        d.hurtTime = p.hurtTime;
        d.hurtDuration = p.hurtDuration;
        d.deathTime = p.deathTime;
        d.setInvisible(p.isInvisible());
        d.setShiftKeyDown(false);
        walk(p.walkAnimation, d.walkAnimation);
        var scale = d.getAttribute(Attributes.SCALE);
        if (scale != null && scale.getBaseValue() != p.getScale()) scale.setBaseValue(p.getScale());
    }

    private static void walk(WalkAnimationState from, WalkAnimationState to) {
        to.speedOld = from.speedOld;
        to.speed = from.speed;
        to.position = from.position;
        to.positionScale = from.positionScale;
    }

    public static void clear() {
        STAND_INS.clear();
    }
}
