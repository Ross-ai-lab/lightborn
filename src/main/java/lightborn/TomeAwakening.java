package lightborn;

import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * A few attack tomes awaken after slaying {@link Balance#TOME_AWAKEN_KILLS} monsters with their own spell. Only
 * monsters count (not animals, not casts), and only kills by that very tome: the count lives on the stack.
 */
final class TomeAwakening {
    private TomeAwakening() {}

    record Awakened(String name, String effect) {}

    static final Map<Spell, Awakened> FORMS = Map.of(
            Spell.FIREBALL, new Awakened("Inferno", "Its blast is 50% wider and burns twice as long."),
            Spell.CHAIN_LIGHTNING, new Awakened("Stormcaller", "The lightning leaps to three more creatures."),
            Spell.ARCANE_BOLT, new Awakened("Arcane Lance", "The bolt pierces up to three creatures."));

    static final String KILLS = "awakening_kills", AWAKE = "awakened";

    static int kills(ItemStack tome) {
        return tome.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getIntOr(KILLS, 0);
    }

    static boolean awake(ItemStack tome) {
        return FORMS.containsKey(TomeItem.spellOf(tome))
                && tome.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .copyTag()
                        .getBooleanOr(AWAKE, false);
    }

    /** Whether the player's tome of this spell is awakened (the one casting: off hand, or a Spellstone slot there). */
    static boolean awake(ServerPlayer p, Spell spell) {
        var tome = Spellstone.armed(p, spell);
        return TomeItem.spellOf(tome) == spell && awake(tome);
    }

    /** Called when a spell hit has just killed {@code dead}. */
    static void killed(ServerPlayer caster, LivingEntity dead, Spell spell) {
        if (spell == null || !FORMS.containsKey(spell) || !Monsters.isMonster(dead) || Monsters.fromSpawner(dead))
            return;
        var tome = Spellstone.armed(caster, spell);
        if (TomeItem.spellOf(tome) != spell || awake(tome)) return;
        int n = kills(tome) + 1;
        Spellstone.update(caster, spell, t -> {
            CustomData.update(DataComponents.CUSTOM_DATA, t, (CompoundTag tag) -> tag.putInt(KILLS, n));
            if (n >= Balance.TOME_AWAKEN_KILLS) awaken(caster, t, spell);
        });
    }

    static void awaken(ServerPlayer p, ItemStack tome, Spell spell) {
        var form = FORMS.get(spell);
        CustomData.update(DataComponents.CUSTOM_DATA, tome, (CompoundTag tag) -> tag.putBoolean(AWAKE, true));
        tome.set(DataComponents.ITEM_NAME, Component.literal("Tome of " + form.name()));
        tome.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        var l = p.level();
        l.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1.2, p.getZ(), 60, .6, .9, .6, .08);
        l.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1.5, p.getZ(), 120, .8, 1, .8, .6);
        l.playSound(null, p.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1, .7f);
        l.playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, .8f, 1.2f);
        p.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal(form.name()).withStyle(ChatFormatting.LIGHT_PURPLE)));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Your tome has awakened")));
        p.sendSystemMessage(
                Component.literal("Your " + spell.label + " tome has awakened as " + form.name() + ". " + form.effect())
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    /** Tooltip lines: progress toward awakening, or the awakened form. */
    static void tooltip(ItemStack tome, java.util.function.Consumer<Component> out) {
        var form = FORMS.get(TomeItem.spellOf(tome));
        if (form == null) return;
        if (awake(tome)) {
            out.accept(Component.literal("Awakened: " + form.name()).withStyle(ChatFormatting.LIGHT_PURPLE));
            for (String line : TomeItem.wrap(form.effect()))
                out.accept(Component.literal(line).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else
            for (String line : TomeItem.wrap(
                    "Awakening: " + kills(tome) + " / " + Balance.TOME_AWAKEN_KILLS + " monsters slain with this tome"))
                out.accept(Component.literal(line).withStyle(ChatFormatting.DARK_PURPLE));
    }
}
