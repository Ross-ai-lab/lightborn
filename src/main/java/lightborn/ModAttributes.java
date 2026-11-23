package lightborn;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

/**
 * The mod's own player stats. They are real attributes, so gear, skill-tree nodes and keystones all
 * stack through vanilla's modifier maths, and item tooltips list them for free.
 * Registered from the class initialiser because {@code PlayerMixin} may touch this class during bootstrap.
 */
public final class ModAttributes {
    private ModAttributes() {}

    /** Multiplier on all spell damage. 1.0 = 100%. */
    public static final Holder<Attribute> SPELL_POWER = register("spell_power", 1.0, 0, 1000);

    public static final Holder<Attribute> MAX_MANA = register("max_mana", Balance.BASE_MANA, 0, 100000);
    /** Multiplier on mana regeneration. */
    public static final Holder<Attribute> MANA_REGEN = register("mana_regen", 1.0, 0, 1000);
    /** Multiplier on how fast cooldowns recover. */
    public static final Holder<Attribute> COOLDOWN_SPEED = register("cooldown_speed", 1.0, 0.1, 100);
    /** Chance (0..1) for a spell to crit. */
    public static final Holder<Attribute> SPELL_CRIT = register("spell_crit", 0.05, 0, 1);
    /** Damage multiplier on a crit, spell or weapon: triple at base (owner, 2026-11-17; was 1.5 for spells). */
    public static final Holder<Attribute> CRIT_DAMAGE = register("crit_damage", 3.0, 1, 100);
    /** Chance (0..1) for a melee hit, arrow or thrown trident to crit ({@link Crits}). */
    public static final Holder<Attribute> WEAPON_CRIT = register("weapon_crit", 0.05, 0, 1);
    /** Multiplier on arrow damage, read from the bow or crossbow that fired the arrow. */
    public static final Holder<Attribute> RANGED_POWER = register("ranged_power", 1.0, 0, 100);
    /** Fraction of damage dealt returned as life. */
    public static final Holder<Attribute> LIFE_LEECH = register("life_leech", 0, 0, 1);

    public static final Holder<Attribute> LIFE_ON_KILL = register("life_on_kill", 0, 0, 1000);
    /** Life regenerated per second. */
    public static final Holder<Attribute> LIFE_REGEN = register("life_regen", 0, 0, 1000);
    /** Multiplier on the chance that monsters drop gear. */
    public static final Holder<Attribute> ITEM_FIND = register("item_find", 1.0, 0, 100);
    /** Multiplier on the chance of Rare and Legendary drops. */
    public static final Holder<Attribute> ITEM_RARITY = register("item_rarity", 1.0, 0, 100);

    public static final List<Holder<Attribute>> ALL = List.of(
            SPELL_POWER,
            MAX_MANA,
            MANA_REGEN,
            COOLDOWN_SPEED,
            SPELL_CRIT,
            CRIT_DAMAGE,
            WEAPON_CRIT,
            RANGED_POWER,
            LIFE_LEECH,
            LIFE_ON_KILL,
            LIFE_REGEN,
            ITEM_FIND,
            ITEM_RARITY);

    private static Holder<Attribute> register(String name, double base, double min, double max) {
        Attribute attribute = new RangedAttribute("attribute.name.lightborn." + name, base, min, max).setSyncable(true);
        return Registry.registerForHolder(
                BuiltInRegistries.ATTRIBUTE, Identifier.fromNamespaceAndPath(Lightborn.MOD_ID, name), attribute);
    }

    /** Forces class initialisation (registration) from the mod initialiser. */
    public static void init() {}
}
