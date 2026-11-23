package lightborn;

import com.mojang.serialization.Codec;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Projectile;

/** Sanctuary protects the player, but cannot be used as an invulnerable firing position. */
public final class WardCombat {
    private WardCombat() {}

    private static final AttachmentType<Boolean> PROTECTED_ORIGIN =
            AttachmentRegistry.create(ModItems.id("ward_attack_origin"), b -> b.persistent(Codec.BOOL));
    private static final Map<UUID, Long> WARNED = new HashMap<>();

    public static void init() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register(
                (e, source, amount) -> !SummonSpells.friendlyFire(e, source));
        AttackEntityCallback.EVENT.register((p, l, h, target, hit) -> {
            if (p instanceof ServerPlayer s && LightWards.blocked(target) && inside(s)) {
                warn(s);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((e, l) -> {
            if (e instanceof TraceableEntity t) mark(e, t.getOwner());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> WARNED.clear());
    }

    public static boolean inside(ServerPlayer p) {
        return LightWards.contains(p.level(), p.getBoundingBox().getCenter());
    }

    public static ServerPlayer owner(Entity e) {
        for (int i = 0; e != null && i < 5; i++) {
            if (e instanceof ServerPlayer p) return p;
            if (!(e.level() instanceof ServerLevel l)) return null;
            var bound = e.getAttached(ModData.THRALL_MASTER);
            if (bound != null) return l.getServer().getPlayerList().getPlayer(bound);
            if (e instanceof OwnableEntity pet && pet.getOwnerReference() != null)
                return l.getServer()
                        .getPlayerList()
                        .getPlayer(pet.getOwnerReference().getUUID());
            if (e instanceof TraceableEntity trace) {
                var next = trace.getOwner();
                if (next == e) return null;
                e = next;
            } else return null;
        }
        return null;
    }

    public static void mark(Entity attack, Entity source) {
        var p = owner(source);
        if (source != null && Boolean.TRUE.equals(source.getAttached(PROTECTED_ORIGIN)) || p != null && inside(p))
            attack.setAttached(PROTECTED_ORIGIN, true);
    }

    public static boolean prevents(LivingEntity target, DamageSource damage) {
        if (target.level() instanceof ServerLevel l
                && LightWards.contains(l, target.getBoundingBox().getCenter())) {
            var attacker = damage.getEntity();
            var direct = damage.getDirectEntity();
            if (LightWards.blocked(attacker)
                    || LightWards.blocked(direct)
                    || direct instanceof Projectile p && LightWards.hostileProjectile(p)) return true;
        }
        if (!LightWards.blocked(target)) return false;
        var direct = damage.getDirectEntity();
        var attacker = damage.getEntity();
        if (direct != null && Boolean.TRUE.equals(direct.getAttached(PROTECTED_ORIGIN))) return true;
        if (attacker != null && Boolean.TRUE.equals(attacker.getAttached(PROTECTED_ORIGIN))) return true;
        var p = owner(attacker);
        if (p == null) p = owner(direct);
        if (p != null && inside(p)) {
            warn(p);
            return true;
        }
        return false;
    }

    public static boolean blocksEffect(LivingEntity target, Entity source) {
        if (source == null || !LightWards.blocked(target)) return false;
        var p = owner(source);
        return Boolean.TRUE.equals(source.getAttached(PROTECTED_ORIGIN)) || p != null && inside(p);
    }

    public static void warn(ServerPlayer p) {
        long now = p.level().getGameTime(), previous = WARNED.getOrDefault(p.getUUID(), Long.MIN_VALUE / 2);
        if (now - previous < 40) return;
        WARNED.put(p.getUUID(), now);
        p.sendOverlayMessage(
                Component.literal("Step outside the ward to fight.").withStyle(ChatFormatting.GOLD));
    }
}
