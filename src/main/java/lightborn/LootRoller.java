package lightborn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import lightborn.ModItems.Kind;
import lightborn.ModItems.Tier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Rolls Path-of-Exile-style gear: a base (kind x tier picked by the monster's level), a rarity, then random
 * affixes stored as attribute modifiers on the stack, so vanilla's tooltip lists them. Rarity alone sets how strong the
 * affixes are; a stronger monster only makes the rarer kinds likelier (owner, 2026-11-16: no item level shown or
 * scaled).
 */
public final class LootRoller {
    private LootRoller() {}

    /**
     * The one rarity ladder (owner, 2026-11-22: "all rarities, gear, spell, enemies, souls, in those 5 with the same
     * names"): Basic, Uncommon, Rare, Legendary, Mythic. Gear rolls it; a spell's tier, a monster's tier and its soul's
     * tier are shown as the rung of the same number ({@link #ofTier}). Magic was renamed Uncommon and Mythic added on
     * top, 2026-11-22. {@code saveId} is what a rolled item keeps in {@code lightborn_rarity}, which the tooltip frames
     * and item borders match, so the old ids stay: PLAIN for Basic, MAGIC for Uncommon.
     */
    public enum Rarity {
        PLAIN("Basic", "PLAIN", ChatFormatting.WHITE, 0, 0, 1),
        UNCOMMON("Uncommon", "MAGIC", ChatFormatting.BLUE, 1, 1, 1.25),
        RARE("Rare", "RARE", ChatFormatting.YELLOW, 2, 2, 1.5),
        LEGENDARY("Legendary", "LEGENDARY", ChatFormatting.GOLD, 3, 3, 2),
        MYTHIC("Mythic", "MYTHIC", ChatFormatting.RED, 4, 4, 2.5);

        public final String label;
        public final String saveId;
        public final ChatFormatting color;
        public final int minAffixes;
        public final int maxAffixes;
        final double power;

        Rarity(String label, String saveId, ChatFormatting color, int minAffixes, int maxAffixes, double power) {
            this.label = label;
            this.saveId = saveId;
            this.color = color;
            this.minAffixes = minAffixes;
            this.maxAffixes = maxAffixes;
            this.power = power;
        }

        /** The rung that a tier of 1 to 5 (a spell's, a monster's, a soul's) is shown as. */
        public static Rarity ofTier(int tier) {
            return values()[Math.clamp(tier, 1, 5) - 1];
        }

        static Rarity bySaveId(String id) {
            for (var r : values()) if (r.saveId.equals(id)) return r;
            return null;
        }

        /** Legendary and Mythic: the glint, the outlined beam, the chat line. */
        public boolean top() {
            return ordinal() >= LEGENDARY.ordinal();
        }
    }

    /**
     * How a value is applied and shown. FLAT adds, rounded to halves; STEADY adds, rounded to tenths; PCT multiplies the
     * base value (only for stats whose base is not 0); CHANCE adds a fraction shown as a percentage.
     */
    enum Style {
        FLAT,
        STEADY,
        PCT,
        CHANCE
    }

    /**
     * One possible rolled stat. CHANCE stats are plain additions shown as percentages
     * (vanilla would print "+0.05"), so they carry their own tooltip text.
     */
    record Affix(String key, String label, Holder<Attribute> attribute, double min, double max, Style style) {
        AttributeModifier.Operation op() {
            return style == Style.PCT
                    ? AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                    : AttributeModifier.Operation.ADD_VALUE;
        }
    }

    private static Affix a(String key, String label, Holder<Attribute> attr, double min, double max, Style style) {
        return new Affix(key, label, attr, min, max, style);
    }

    // Shared affixes
    private static final Affix HEALTH = a("max_health", "Max Health", Attributes.MAX_HEALTH, 2, 4, Style.FLAT);
    private static final Affix SPEED =
            a("move_speed", "Movement Speed", Attributes.MOVEMENT_SPEED, 0.03, 0.06, Style.PCT);
    private static final Affix LUCK = a("luck", "Luck", Attributes.LUCK, 1, 2, Style.STEADY);
    private static final Affix FIND = a("item_find", "Item Find", ModAttributes.ITEM_FIND, 0.05, 0.12, Style.PCT);
    private static final Affix LEECH =
            a("life_leech", "Life Leech", ModAttributes.LIFE_LEECH, 0.01, 0.025, Style.CHANCE);
    private static final Affix SPELL =
            a("spell_power", "Spell Power", ModAttributes.SPELL_POWER, 0.05, 0.12, Style.PCT);
    private static final Affix MANA = a("max_mana", "Max Mana", ModAttributes.MAX_MANA, 3, 7, Style.FLAT);
    private static final Affix REGEN =
            a("mana_regen", "Mana Regeneration", ModAttributes.MANA_REGEN, 0.08, 0.18, Style.PCT);
    private static final Affix HASTE =
            a("cooldown_speed", "Cooldown Speed", ModAttributes.COOLDOWN_SPEED, 0.04, 0.09, Style.PCT);

    static final List<Affix> MELEE_AFFIXES = List.of(
            a("damage", "Attack Damage", Attributes.ATTACK_DAMAGE, 1, 2.5, Style.FLAT),
            LUCK,
            FIND,
            LEECH,
            a("knockback", "Knockback", Attributes.ATTACK_KNOCKBACK, 0.4, 1.0, Style.STEADY),
            a("life_on_kill", "Life on Kill", ModAttributes.LIFE_ON_KILL, 1, 2, Style.FLAT));

    static final List<Affix> STAFF_AFFIXES = List.of(
            SPELL,
            MANA,
            REGEN,
            HASTE,
            LEECH,
            a("spell_crit", "Spell Crit Chance", ModAttributes.SPELL_CRIT, 0.02, 0.05, Style.CHANCE),
            // Rolls scaled with the base multiplier (1.5 to 3.0, owner 2026-11-17); the bonus now counts for weapon
            // crits too.
            a("crit_damage", "Crit Damage", ModAttributes.CRIT_DAMAGE, 0.16, 0.40, Style.CHANCE));

    static final List<Affix> ARMOR_AFFIXES = List.of(
            HEALTH,
            SPEED,
            LUCK,
            FIND,
            a("armor", "Armor", Attributes.ARMOR, 1, 2, Style.STEADY),
            a("toughness", "Armor Toughness", Attributes.ARMOR_TOUGHNESS, 0.5, 1.5, Style.STEADY),
            a("knockback_resist", "Knockback Resistance", Attributes.KNOCKBACK_RESISTANCE, 0.04, 0.10, Style.CHANCE),
            a("life_regen", "Life Regeneration", ModAttributes.LIFE_REGEN, 0.1, 0.25, Style.STEADY),
            a("item_rarity", "Item Rarity", ModAttributes.ITEM_RARITY, 0.05, 0.12, Style.PCT),
            a("spell_power", "Spell Power", ModAttributes.SPELL_POWER, 0.03, 0.06, Style.PCT),
            MANA,
            REGEN,
            HASTE);

    public static Rarity rollRarity(RandomSource random, double rarityMultiplier) {
        double r = random.nextDouble();
        double mythic = Balance.MYTHIC_CHANCE * rarityMultiplier,
                legendary = mythic + Balance.LEGENDARY_CHANCE * rarityMultiplier,
                rare = legendary + Balance.RARE_CHANCE * rarityMultiplier;
        if (r < mythic) return Rarity.MYTHIC;
        if (r < legendary) return Rarity.LEGENDARY;
        if (r < rare) return Rarity.RARE;
        return r < Math.min(1, rare) + .35 ? Rarity.UNCOMMON : Rarity.PLAIN;
    }

    private static final Kind[] KIND_WEIGHTS = {
        Kind.SWORD,
        Kind.SWORD,
        Kind.AXE,
        Kind.STAFF,
        Kind.STAFF,
        Kind.HELMET,
        Kind.CHESTPLATE,
        Kind.LEGGINGS,
        Kind.BOOTS
    };

    /** A random kind of the given material ({@link OreTiers#pick} chooses the material for monster drops). */
    public static ItemStack roll(RandomSource random, Tier tier, double rarityMultiplier) {
        return roll(random, tier, rollRarity(random, rarityMultiplier));
    }

    public static ItemStack roll(RandomSource random, Tier tier, Rarity rarity) {
        return roll(random, rarity, tier, KIND_WEIGHTS[random.nextInt(KIND_WEIGHTS.length)]);
    }

    /** Rolls affixes onto a chosen base item (quest rewards and the like). */
    public static ItemStack roll(RandomSource random, Rarity rarity, Tier tier, Kind kind) {
        return roll(random, rarity, tier, kind, false);
    }

    /** A plain casting tool: no implicit attributes, affixes or rarity roll. */
    public static ItemStack starterStaff(RandomSource random) {
        return new ItemStack(ModItems.STARTER_STAFF);
    }

    private static ItemStack roll(RandomSource random, Rarity rarity, Tier tier, Kind kind, boolean noMana) {
        // About 3 staff drops in 10 are the tier's specialist staff, when it has one (never the starter).
        Item baseItem = ModItems.GEAR.get(tier).get(kind);
        boolean specialist = false;
        if (kind == Kind.STAFF && !noMana && ModItems.SPECIALIST_OF.containsKey(tier) && random.nextFloat() < 0.3f) {
            baseItem = ModItems.SPECIALIST_OF.get(tier);
            specialist = true;
        }
        ItemStack stack = new ItemStack(baseItem);
        EquipmentSlotGroup slot = slotOf(kind);

        List<Affix> pool = new ArrayList<>(
                switch (kind) {
                    case SWORD, AXE -> MELEE_AFFIXES;
                    case STAFF -> STAFF_AFFIXES;
                    default -> ARMOR_AFFIXES;
                });
        if (noMana) pool.remove(MANA);
        if (kind != Kind.BOOTS) pool.remove(SPEED);
        // Start from the item's own stats (base damage, armour, staff spell power) so they are kept. An affix never
        // lands
        // on one of them (owner, 2026-11-18): folded into the base line it would hide, and a Rare must show two new
        // lines, a Legendary three. So armour rolls no extra Armor, a sword no extra Attack Damage, a staff no extra
        // Spell Power or Max Mana as an affix.
        ItemAttributeModifiers base =
                stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        EquipmentSlotGroup slotGroup = slot;
        pool.removeIf(affix -> affix.style() != Style.CHANCE
                && base.modifiers().stream()
                        .anyMatch(e -> e.attribute().equals(affix.attribute())
                                && e.slot() == slotGroup
                                && e.modifier().operation() == affix.op()));
        Collections.shuffle(pool, new Random(random.nextLong()));
        int count =
                Math.min(pool.size(), rarity.minAffixes + random.nextInt(rarity.maxAffixes - rarity.minAffixes + 1));
        List<Affix> chosen = pool.subList(0, count);

        List<ItemAttributeModifiers.Entry> entries = new ArrayList<>(base.modifiers());
        List<ItemAttributeModifiers.Entry> rolled = new ArrayList<>();
        String slotKey = slot.getSerializedName();
        for (Affix affix : chosen) {
            double value = (affix.min() + random.nextDouble() * (affix.max() - affix.min())) * rarity.power;
            value = affix.style() == Style.FLAT
                    ? Math.round(value * 2.0) / 2.0
                    : affix.style() == Style.STEADY
                            ? Math.round(value * 10.0) / 10.0
                            : Math.round(value * 1000.0) / 1000.0;
            Identifier id = Identifier.fromNamespaceAndPath(Lightborn.MOD_ID, "affix_" + affix.key() + "_" + slotKey);
            AttributeModifier mod = new AttributeModifier(id, value, affix.op());
            if (affix.style() == Style.CHANCE) {
                Component text = Component.literal("+" + trim(value * 100) + "% " + affix.label())
                        .withStyle(ChatFormatting.BLUE);
                rolled.add(new ItemAttributeModifiers.Entry(
                        affix.attribute(), mod, slot, ItemAttributeModifiers.Display.override(text)));
            } else {
                rolled.add(new ItemAttributeModifiers.Entry(
                        affix.attribute(), mod, slot, null)); // null: the default tooltip line
            }
        }
        // A staff's rarity adds Max Mana outside the affix count, joined into the staff's one Max Mana line (owner,
        // 2026-11-16: each stat shows once).
        int rarityMana = rarityMana(kind, rarity);
        if (rarityMana > 0) {
            boolean joined = false;
            for (List<ItemAttributeModifiers.Entry> list : List.of(entries, rolled))
                for (int i = 0; i < list.size() && !joined; i++) {
                    var e = list.get(i);
                    if (!e.attribute().equals(ModAttributes.MAX_MANA)
                            || e.slot() != slot
                            || e.modifier().operation() != AttributeModifier.Operation.ADD_VALUE) continue;
                    var sum = new AttributeModifier(
                            e.modifier().id(),
                            e.modifier().amount() + rarityMana,
                            e.modifier().operation());
                    list.set(i, new ItemAttributeModifiers.Entry(e.attribute(), sum, e.slot(), e.display()));
                    joined = true;
                }
            if (!joined)
                rolled.add(new ItemAttributeModifiers.Entry(
                        ModAttributes.MAX_MANA,
                        new AttributeModifier(
                                Identifier.fromNamespaceAndPath(Lightborn.MOD_ID, "rarity_max_mana_" + slotKey),
                                rarityMana,
                                AttributeModifier.Operation.ADD_VALUE),
                        slot,
                        null));
        }
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        entries.addAll(rolled);
        for (var e : entries)
            if (e.display() == null) builder.add(e.attribute(), e.modifier(), e.slot());
            else builder.add(e.attribute(), e.modifier(), e.slot(), e.display());
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());

        stack.set(DataComponents.CUSTOM_NAME, name(rarity, stack));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString("lightborn_rarity", rarity.saveId);
            tag.putBoolean("affixes_rebalanced", true);
        });
        if (rarity.top()) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    /** Max Mana a staff's rarity adds on top of its affixes (Basic 0, Uncommon 10, Rare 20, Legendary 35, Mythic 55). */
    public static int rarityMana(Kind kind, Rarity rarity) {
        return kind == Kind.STAFF ? Balance.STAFF_RARITY_MANA[rarity.ordinal()] : 0;
    }

    private static final int[] OLD_STAFF_MANA = {0, 25, 50, 100, 150};

    static Rarity rarity(ItemStack stack) {
        var rarity = Rarity.bySaveId(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getStringOr("lightborn_rarity", ""));
        return rarity == null ? Rarity.PLAIN : rarity;
    }

    static boolean refresh(ItemStack stack) {
        if (stack.isEmpty()) return false;
        boolean changed = relabel(stack);
        changed |= stripDisallowedAffixes(stack);
        var tag =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        boolean starter = stack.is(ModItems.STARTER_STAFF);
        var rarity = Rarity.bySaveId(tag.getStringOr("lightborn_rarity", ""));
        if (starter || rarity != null && !tag.getBooleanOr("affixes_rebalanced", false)) {
            var original = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
            var builder = ItemAttributeModifiers.builder();
            boolean staff = ModItems.isStaff(stack);
            int kind = LightGearTree.kind(stack);
            boolean weapon = staff
                    || kind == LightGearTree.WEAPON
                    || kind == LightGearTree.AXE
                    || kind == LightGearTree.SPEAR
                    || kind == LightGearTree.TRIDENT
                    || kind == LightGearTree.MACE
                    || kind == LightGearTree.RANGED;
            boolean manaTrimmed = false;
            for (var entry : original.modifiers()) {
                var modifier = entry.modifier();
                var display = entry.display();
                String path = modifier.id().getPath();
                boolean ours = modifier.id().getNamespace().equals(Lightborn.MOD_ID);
                if (starter && entry.attribute().equals(ModAttributes.MAX_MANA) && !path.startsWith("light_tree_"))
                    continue;
                if (rarity != null && ours) {
                    if (weapon && (path.startsWith("affix_move_speed_") || path.startsWith("affix_attack_speed_")))
                        continue;
                    double amount = modifier.amount();
                    if (entry.attribute().equals(ModAttributes.MAX_MANA) && !path.startsWith("light_tree_")) {
                        int oldBonus = staff && !manaTrimmed ? OLD_STAFF_MANA[rarity.ordinal()] : 0;
                        int newBonus = staff && !manaTrimmed ? rarityMana(Kind.STAFF, rarity) : 0;
                        amount = path.startsWith("affix_max_mana_")
                                ? Math.round((amount - oldBonus)) * .5 + newBonus
                                : amount - oldBonus + newBonus;
                        manaTrimmed = true;
                    } else if (path.startsWith("affix_crit_damage_")) {
                        amount = Math.round(amount * 800) / 1000.0;
                        display = ItemAttributeModifiers.Display.override(
                                Component.literal("+" + trim(amount * 100) + "% Crit Damage")
                                        .withStyle(ChatFormatting.BLUE));
                    }
                    modifier = new AttributeModifier(modifier.id(), amount, modifier.operation());
                }
                if (display == null) builder.add(entry.attribute(), modifier, entry.slot());
                else builder.add(entry.attribute(), modifier, entry.slot(), display);
            }
            var next = builder.build();
            if (!original.equals(next)) {
                stack.set(DataComponents.ATTRIBUTE_MODIFIERS, next);
                changed = true;
            }
            if (!starter) {
                CustomData.update(
                        DataComponents.CUSTOM_DATA, stack, data -> data.putBoolean("affixes_rebalanced", true));
                changed = true;
            }
        }
        return LightGearTree.refresh(stack) || changed;
    }

    private static boolean stripDisallowedAffixes(ItemStack stack) {
        var original = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (original == null) return false;
        int kind = LightGearTree.kind(stack);
        boolean weapon = switch (kind) {
            case LightGearTree.WEAPON,
                    LightGearTree.STAFF,
                    LightGearTree.AXE,
                    LightGearTree.SPEAR,
                    LightGearTree.TRIDENT,
                    LightGearTree.MACE,
                    LightGearTree.RANGED -> true;
            default -> false;
        };
        if (original.modifiers().stream().noneMatch(entry -> disallowedAffix(entry, kind, weapon))) return false;
        var builder = ItemAttributeModifiers.builder();
        for (var entry : original.modifiers()) {
            if (disallowedAffix(entry, kind, weapon)) continue;
            if (entry.display() == null) builder.add(entry.attribute(), entry.modifier(), entry.slot());
            else builder.add(entry.attribute(), entry.modifier(), entry.slot(), entry.display());
        }
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
        return true;
    }

    private static boolean disallowedAffix(ItemAttributeModifiers.Entry entry, int kind, boolean weapon) {
        var id = entry.modifier().id();
        return id.getNamespace().equals(Lightborn.MOD_ID)
                && (kind != LightGearTree.BOOTS && id.getPath().startsWith("affix_move_speed_")
                        || weapon && id.getPath().startsWith("affix_max_health_"));
    }

    static String trim(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        return r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    /**
     * Renames a rolled item named under the old ladder ("Magic Iron Sword") to the new one ("Uncommon Iron Sword"),
     * only when its name is still exactly the rolled one, so a name given at an anvil stays. True when it changed.
     */
    public static boolean relabel(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        var current = stack.get(DataComponents.CUSTOM_NAME);
        if (data == null || current == null) return false;
        var rarity = Rarity.bySaveId(data.copyTag().getStringOr("lightborn_rarity", ""));
        if (rarity == null) return false;
        var wanted = name(rarity, stack);
        if (current.equals(wanted)) return false;
        String base = stack.getItem().getName(stack).getString(), now = current.getString();
        for (String old : List.of("Magic", rarity.label))
            if (now.equals(old + " " + base)) {
                stack.set(DataComponents.CUSTOM_NAME, wanted);
                return true;
            }
        return false;
    }

    /** The rarity, then the item's own plain name, coloured by rarity: "Uncommon Apprentice Staff". */
    private static Component name(Rarity rarity, ItemStack stack) {
        MutableComponent name =
                Component.literal(rarity.label + " ").append(stack.getItem().getName(stack));
        return name.withStyle(s -> s.withColor(rarity.color).withItalic(false));
    }

    static EquipmentSlotGroup slotOf(Kind kind) {
        return switch (kind) {
            case HELMET -> EquipmentSlotGroup.HEAD;
            case CHESTPLATE -> EquipmentSlotGroup.CHEST;
            case LEGGINGS -> EquipmentSlotGroup.LEGS;
            case BOOTS -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.MAINHAND;
        };
    }
}
