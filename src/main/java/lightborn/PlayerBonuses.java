package lightborn;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The player's lasting attribute bonuses: the mana each level adds and the collection sets' rewards. Re-applied on
 * join, respawn, level-up and each finished set. The passive skill tree that used to add character stats here is gone
 * (owner, 2026-11-15: upgrades belong to spells, see {@link SpellUpgrades}); its saved modifiers are stripped.
 */
public final class PlayerBonuses {
    private PlayerBonuses() {}

    /** One attribute change of a collection reward, at full strength. */
    public record Effect(Holder<Attribute> attribute, double perRank, Operation op) {}

    /** Every attribute the retired tree ever touched, so its saved {@code tree_*} modifiers can be removed. */
    private static final List<Holder<Attribute>> TREE_STATS = List.of(
            Attributes.ARMOR,
            Attributes.ARMOR_TOUGHNESS,
            Attributes.ATTACK_DAMAGE,
            Attributes.ATTACK_SPEED,
            Attributes.KNOCKBACK_RESISTANCE,
            Attributes.LUCK,
            Attributes.MAX_HEALTH,
            Attributes.MOVEMENT_SPEED,
            ModAttributes.COOLDOWN_SPEED,
            ModAttributes.CRIT_DAMAGE,
            ModAttributes.ITEM_FIND,
            ModAttributes.ITEM_RARITY,
            ModAttributes.LIFE_LEECH,
            ModAttributes.LIFE_ON_KILL,
            ModAttributes.LIFE_REGEN,
            ModAttributes.MANA_REGEN,
            ModAttributes.MAX_MANA,
            ModAttributes.SPELL_CRIT,
            ModAttributes.SPELL_POWER);

    public static void apply(ServerPlayer player) {
        for (var stat : TREE_STATS) {
            AttributeInstance inst = player.getAttribute(stat);
            if (inst == null) continue;
            for (AttributeModifier m : List.copyOf(inst.getModifiers()))
                if (m.id().getNamespace().equals(Lightborn.MOD_ID)
                        && m.id().getPath().startsWith("tree_")) inst.removeModifier(m.id());
        }
        // A player saved before crits went to triple (owner, 2026-11-17) keeps the old 1.5 base in the save.
        AttributeInstance critDamage = player.getAttribute(ModAttributes.CRIT_DAMAGE);
        if (critDamage != null && critDamage.getBaseValue() == 1.5) critDamage.setBaseValue(3.0);
        AttributeInstance mana = player.getAttribute(ModAttributes.MAX_MANA);
        if (mana != null) {
            // Levels are gone (owner, 2026-11-16): older saves still carry the per-level mana modifier.
            mana.removeModifier(Identifier.fromNamespaceAndPath(Lightborn.MOD_ID, "level_mana"));
            mana.setBaseValue(Balance.BASE_MANA);
        }
        Collection.applyRewards(player);
        if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
    }
}
