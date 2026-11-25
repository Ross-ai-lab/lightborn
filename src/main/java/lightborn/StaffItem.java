package lightborn;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * A mage staff: right-click casts the spell of the tome in the off hand, or of the first slot of a {@link Spellstone} held
 * there (owner, 2026-11-18). Holding the button repeats the cast, except
 * for Updraft, which gathers wind while held and throws you on release (owner, 2026-11-16): the longer the hold, up
 * to {@link #UPDRAFT_CHARGE_TICKS}, the higher the throw.
 */
public class StaffItem extends Item {
    /** A full Updraft charge: 1.5 s. Held that long, it throws at once. */
    public static final int UPDRAFT_CHARGE_TICKS = 30;

    private static final int USE_FOREVER = 72_000;

    public StaffItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            TooltipDisplay display,
            Consumer<Component> out,
            TooltipFlag flag) {
        if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(ModItems.id("bone_staff")))
            out.accept(Component.literal("While carried: +2 Command").withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        ItemStack off = player.getOffhandItem();
        boolean stone = Spellstone.is(off);
        ItemStack tome = Spellstone.rightClick(player);
        if (TomeItem.spellOf(tome) == null) {
            // Off hand busy with something else (shield, torch): let that item act. Empty: say what is missing.
            if ((off.isEmpty() || stone) && player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(
                        Component.literal(
                                        stone
                                                ? "The Spellstone's first slot is empty"
                                                : "Hold a spell tome in your off hand to cast")
                                .withStyle(ChatFormatting.LIGHT_PURPLE),
                        true);
            }
            return stone ? InteractionResult.FAIL : InteractionResult.PASS;
        }
        if (TomeItem.spellOf(tome) == Spell.UPDRAFT) {
            if (player.getCooldowns().isOnCooldown(tome)) return InteractionResult.FAIL;
            player.startUsingItem(hand);
            return InteractionResult.CONSUME;
        }
        if (player instanceof ServerPlayer sp) {
            if (!Spells.cast(sp, tome)) return InteractionResult.FAIL;
            if (stone) Spellstone.writeBack(sp, 0, tome);
            return InteractionResult.SUCCESS_SERVER;
        }
        return InteractionResult.SUCCESS;
    }

    private static boolean chargesUpdraft(LivingEntity e) {
        return e instanceof Player p && TomeItem.spellOf(Spellstone.rightClick(p)) == Spell.UPDRAFT;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return chargesUpdraft(entity) ? USE_FOREVER : 0;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.NONE;
    }

    /** The wind gathers at your feet, tighter and higher-pitched as the charge fills; full, it throws at once. */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int left) {
        int held = USE_FOREVER - left;
        if (level instanceof ServerLevel sl && held % 3 == 0 && held <= UPDRAFT_CHARGE_TICKS) {
            float charge = Math.min(1f, held / (float) UPDRAFT_CHARGE_TICKS);
            Bosses.ring(
                    sl,
                    entity.position().add(0, 0.15, 0),
                    2.2 - 1.4 * charge,
                    ParticleTypes.SMALL_GUST,
                    6 + (int) (8 * charge));
            sl.playSound(
                    null,
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    SoundEvents.BREEZE_INHALE,
                    SoundSource.PLAYERS,
                    0.5f + 0.4f * charge,
                    0.7f + 0.8f * charge);
        }
        if (held >= UPDRAFT_CHARGE_TICKS) entity.releaseUsingItem();
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int left) {
        if (!(entity instanceof ServerPlayer p) || !chargesUpdraft(p)) return false;
        int held = USE_FOREVER - left;
        TravelSpells.updraftCharge = Math.clamp(held / (float) UPDRAFT_CHARGE_TICKS, 0.15f, 1f);
        try {
            var tome = Spellstone.rightClick(p);
            boolean cast = Spells.cast(p, tome);
            if (cast) Spellstone.writeBack(p, 0, tome);
            return cast;
        } finally {
            TravelSpells.updraftCharge = 1f;
        }
    }
}
