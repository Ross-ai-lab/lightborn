package lightborn;

import com.mojang.serialization.Codec;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;

/** A thirty-second transformation, with original abilities restored on expiry, death, logout and server stop. */
public final class DragonForm {
    private DragonForm() {}

    static void init() {}

    public static final AttachmentType<Long> UNTIL = AttachmentRegistry.create(
            ModItems.id("dragon_form_until"),
            b -> b.persistent(Codec.LONG)
                    .initializer(() -> 0L)
                    .syncWith(ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all()));
    static final AttachmentType<Long> COOLDOWN = DragonStory.saved("dragon_form_cooldown", Codec.LONG);
    static final AttachmentType<Boolean> MAY_FLY = DragonStory.saved("form_old_mayfly", Codec.BOOL),
            FLYING = DragonStory.saved("form_old_flying", Codec.BOOL),
            INVISIBLE = DragonStory.saved("form_old_invisible", Codec.BOOL);
    static final AttachmentType<Boolean> SAFE_LANDING =
            AttachmentRegistry.create(ModItems.id("dragon_safe_landing"), b -> b.persistent(Codec.BOOL));
    private static final Map<UUID, LightDragon> AVATARS = new HashMap<>();
    private static final Map<UUID, Long> BREATH = new HashMap<>();

    public static boolean active(LivingEntity p) {
        return p.getAttachedOrElse(UNTIL, 0L) > p.level().getGameTime();
    }

    public static boolean visual(LivingEntity p) {
        return active(p) || ChestTravel.transformed(p);
    }

    static boolean activate(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || ChestTravel.flying(p) || DragonStory.ending(p.level()) != 3)
            return false;
        long now = p.level().getGameTime();
        if (active(p)) return true;
        if (p.getAttachedOrElse(COOLDOWN, 0L) > now) {
            p.sendOverlayMessage(
                    Component.literal("Dragon Form returns in " + ((p.getAttached(COOLDOWN) - now) / 20) + "s"));
            return false;
        }
        p.setAttached(MAY_FLY, p.getAbilities().mayfly);
        p.setAttached(FLYING, p.getAbilities().flying);
        p.setAttached(INVISIBLE, p.isInvisible() && !p.hasEffect(MobEffects.INVISIBILITY));
        p.setAttached(UNTIL, now + 600);
        p.setAttached(COOLDOWN, now + 6000);
        p.getAbilities().mayfly = true;
        p.getAbilities().flying = true;
        p.onUpdateAbilities();
        p.setInvisible(true);
        p.level().playSound(null, p.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, .8f, .8f);
        return true;
    }

    public static void end(ServerPlayer p) {
        if (p.hasAttached(MAY_FLY)) {
            boolean creative = p.gameMode.getGameModeForPlayer() == GameType.CREATIVE || p.isSpectator();
            p.getAbilities().mayfly = creative || p.getAttachedOrElse(MAY_FLY, false);
            p.getAbilities().flying = p.getAbilities().mayfly && p.getAttachedOrElse(FLYING, false);
            p.onUpdateAbilities();
            if (p.getAttachedOrElse(INVISIBLE, false)) p.setInvisible(true);
            p.removeAttached(MAY_FLY);
            p.removeAttached(FLYING);
            p.removeAttached(INVISIBLE);
            p.setAttached(SAFE_LANDING, true);
            p.fallDistance = 0;
        }
        p.setAttached(UNTIL, 0L);
        var avatar = AVATARS.remove(p.getUUID());
        if (avatar != null) avatar.discard();
        BREATH.remove(p.getUUID());
    }

    static void tick(MinecraftServer server) {
        for (var p : server.getPlayerList().getPlayers()) {
            if (!active(p) || !p.isAlive()) {
                if (p.hasAttached(MAY_FLY) || AVATARS.containsKey(p.getUUID())) end(p);
                if (p.onGround()) p.removeAttached(SAFE_LANDING);
                continue;
            }
            // Vanilla turns creative-style flight off on landing. The Dragon remains airborne for the whole form.
            if (!p.getAbilities().flying) {
                p.getAbilities().flying = true;
                p.getAbilities().mayfly = true;
                p.onUpdateAbilities();
                if (p.onGround()) {
                    p.push(0, .15, 0);
                    p.connection.send(new ClientboundSetEntityMotionPacket(p));
                }
            }
            var a = AVATARS.get(p.getUUID());
            if (a == null || a.isRemoved() || a.level() != p.level()) {
                if (a != null) a.discard();
                a = StoryBosses.apparition(p, 3, p.position());
                AVATARS.put(p.getUUID(), a);
            }
            a.setPos(p.position().add(0, -.5, 0));
            a.setYRot(p.getYRot());
            a.setYBodyRot(p.getYRot());
            a.setYHeadRot(p.getYHeadRot());
            p.setInvisible(true);
            p.fallDistance = 0;
        }
    }

    static void breathe(ServerPlayer p) {
        if (!active(p) || BREATH.getOrDefault(p.getUUID(), 0L) > p.level().getGameTime()) return;
        BREATH.put(p.getUUID(), p.level().getGameTime() + 30);
        StoryBosses.breath(
                p, p.getEyePosition(), p.getLookAngle(), 18, 10 * p.getAttributeValue(ModAttributes.SPELL_POWER));
    }

    public static boolean safeFall(LivingEntity e, DamageSource source) {
        if (e instanceof ServerPlayer p
                && p.getAttachedOrElse(SAFE_LANDING, false)
                && source.is(DamageTypeTags.IS_FALL)) {
            p.removeAttached(SAFE_LANDING);
            return true;
        }
        return false;
    }

    static void clear(MinecraftServer s) {
        if (s.getPlayerList() != null) for (var p : s.getPlayerList().getPlayers()) end(p);
        AVATARS.values().forEach(LightDragon::discard);
        AVATARS.clear();
    }
}
