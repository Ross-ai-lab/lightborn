package lightborn;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Upgrade Gear (owner, 2026-11-17: first the Light gear's steps became a tree like Upgrade Spells', then the tab opened
 * to every piece of gear carried). A piece's {@link #kind} picks its tree: helm, melee weapon, chestplate, leggings,
 * boots, staff, wings, bow or crossbow, tool (pickaxe, shovel, hoe), shield. Each tree has three tiers of three nodes.
 * Tiers I and II are stat boosts, never two of one kind in a tier; tier III is a perk, a new thing the piece does. A
 * node costs its tier's ore, never Sparks; a tier opens once a node of the tier above is bought; one node per tier is in
 * use and a click switches between bought ones for free. On the Dragon's Light gear every tier opened also raises the
 * piece's own armour or damage a level, as a step used to; other gear keeps its own stats and gains the nodes'.
 */
public final class LightGearTree {
    private LightGearTree() {}

    /** A node: a stat boost (an attribute modifier on the piece's slot), or a perk when {@code attr} is null. */
    public record Node(
            String name, String text, Holder<Attribute> attr, double amount, AttributeModifier.Operation op) {
        public boolean perk() {
            return attr == null;
        }
    }

    public static final int TIERS = 3, CHOICES = 3;
    /**
     * How much of its tier's ore each node costs. Gear never costs Sparks (owner, 2026-11-23: "sparks are rare and the
     * fact that both spells and tools need it is too much"); Sparks are for spells.
     */
    public static final int[] ORE = {3, 3, 3};

    /**
     * The ore a tier asks for, the same for every piece (owner, 2026-11-23): tier I copper ingots, tier II lapis
     * lazuli, tier III redstone dust.
     */
    public static Item ore(int tier) {
        return switch (tier) {
            case 0 -> Items.COPPER_INGOT;
            case 1 -> Items.LAPIS_LAZULI;
            default -> Items.REDSTONE;
        };
    }

    static final String KEY = "light_tree", OWNED = "light_tree_owned";

    /** The trees, by kind; the first seven match {@link LightGear#ITEMS}' order. */
    public static final int HELM = 0,
            WEAPON = 1,
            CHEST = 2,
            LEGS = 3,
            BOOTS = 4,
            STAFF = 5,
            WINGS = 6,
            RANGED = 7,
            TOOL = 8,
            SHIELD = 9,
            AXE = 10,
            SPEAR = 11,
            MACE = 12,
            TRIDENT = 13;
    /** Kind names in modifier ids: a piece's nodes never share an id with another worn or held piece's. */
    private static final String[] KEYS = {
        "helm", "weapon", "chest", "legs", "boots", "staff", "wings", "ranged", "tool", "shield", "axe", "spear",
        "mace", "trident"
    };

    /** Which tree a piece of gear grows, or -1 for anything that is not gear. */
    public static int kind(ItemStack s) {
        if (s.isEmpty()) return -1;
        int light = LightGear.ITEMS.indexOf(s.getItem());
        if (light == LightGear.SHIELD) return SHIELD;
        if (light >= 0) return light;
        if (ModItems.isStaff(s)) return STAFF;
        if (s.has(DataComponents.GLIDER)) return WINGS;
        var equip = s.get(DataComponents.EQUIPPABLE);
        if (equip != null && has(s, Attributes.ARMOR, null)) {
            switch (equip.slot()) {
                case HEAD -> {
                    return HELM;
                }
                case CHEST -> {
                    return CHEST;
                }
                case LEGS -> {
                    return LEGS;
                }
                case FEET -> {
                    return BOOTS;
                }
                default -> {}
            }
        }
        if (s.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem) return RANGED;
        if (s.has(DataComponents.BLOCKS_ATTACKS)) return SHIELD;
        if (s.is(ItemTags.PICKAXES) || s.is(ItemTags.SHOVELS) || s.is(ItemTags.HOES)) return TOOL;
        // Each weapon kind grows its own tree (owner, 2026-11-17); a modded weapon of no known kind takes the sword's.
        if (s.is(ItemTags.AXES)) return AXE;
        if (s.is(ItemTags.SPEARS)) return SPEAR;
        if (s.is(Items.MACE)) return MACE;
        if (s.is(Items.TRIDENT)) return TRIDENT;
        if (s.is(ItemTags.SWORDS) || has(s, Attributes.ATTACK_DAMAGE, Item.BASE_ATTACK_DAMAGE_ID)) return WEAPON;
        return -1;
    }

    private static boolean has(ItemStack s, Holder<Attribute> attr, net.minecraft.resources.Identifier id) {
        return s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers().stream()
                .anyMatch(e -> e.attribute().equals(attr)
                        && (id == null || e.modifier().is(id)));
    }

    /** Where a kind's nodes count: worn in its slot, or held (the main hand; a shield in either hand). */
    static EquipmentSlotGroup slots(int kind) {
        return switch (kind) {
            case HELM -> EquipmentSlotGroup.HEAD;
            case CHEST, WINGS -> EquipmentSlotGroup.CHEST;
            case LEGS -> EquipmentSlotGroup.LEGS;
            case BOOTS -> EquipmentSlotGroup.FEET;
            case SHIELD -> EquipmentSlotGroup.HAND;
            default -> EquipmentSlotGroup.MAINHAND;
        };
    }

    private static Node add(String name, String text, Holder<Attribute> attr, double amount) {
        return new Node(name, text, attr, amount, AttributeModifier.Operation.ADD_VALUE);
    }

    private static Node mult(String name, String text, Holder<Attribute> attr, double amount) {
        return new Node(name, text, attr, amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }

    private static Node perk(String name, String text) {
        return new Node(name, text, null, 0, AttributeModifier.Operation.ADD_VALUE);
    }

    /** By {@link #kind}: helm, sword, chestplate ... trident; nine nodes each, tier by tier. */
    // Owner, 2026-11-23: branches are named for what they do, and no stat is raised in both tier I and tier II (one
    // aspect per tree, as with spells). The staff's tree is all attack: no health or defence (owner, same day).
    private static final List<List<List<Node>>> TABLE = List.of(
            tree(
                    add("More Armour", "+1 armour.", Attributes.ARMOR, 1),
                    add("More Mana", "+10 mana.", ModAttributes.MAX_MANA, 10),
                    add("Spell Crits", "+3% spell crit chance.", ModAttributes.SPELL_CRIT, .03),
                    add("More Toughness", "+1 armour toughness.", Attributes.ARMOR_TOUGHNESS, 1),
                    add("More Health", "+2 health.", Attributes.MAX_HEALTH, 2),
                    add("Shorter Cooldowns", "Spells ready 5% sooner.", ModAttributes.COOLDOWN_SPEED, .05),
                    // Nothing may light the night for you (owner, 2026-11-17: the dark is the game), so no Night
                    // Vision and no monsters seen through walls.
                    perk("Sage's Crown", "Every monster you slay gives back 5 mana."),
                    perk(
                            "Last Light",
                            "Once a minute, falling below a third of your health throws back monsters within 5 blocks."),
                    perk("Clear Mind", "Nausea, Darkness and Mining Fatigue cannot touch you.")),
            tree(
                    add("More Damage", "+1 attack damage.", Attributes.ATTACK_DAMAGE, 1),
                    add("Faster Swings", "Swings 10% faster.", Attributes.ATTACK_SPEED, .24),
                    add("Life Steal", "Heals you 3% of the damage you deal.", ModAttributes.LIFE_LEECH, .03),
                    add("Longer Reach", "Reaches half a block farther.", Attributes.ENTITY_INTERACTION_RANGE, .5),
                    // Every hit has a 5% base chance to crit for triple (owner, 2026-11-17).
                    add("More Crits", "+5% crit chance (10% in all).", ModAttributes.WEAPON_CRIT, .05),
                    add("Harder Crits", "Crits hit 10% harder.", ModAttributes.CRIT_DAMAGE, .30),
                    perk(
                            "Sunfire Edge",
                            "Hits set the target on fire for 4 seconds, and deal 50% more damage to the undead."),
                    perk(
                            "Radiant Sweep",
                            "Every hit sends a wave of light into all monsters within 3 blocks for half."),
                    perk("Executioner", "Hits deal 50% more damage to monsters below a third of their health.")),
            tree(
                    add("More Armour", "+1 armour.", Attributes.ARMOR, 1),
                    add("More Health", "+2 health.", Attributes.MAX_HEALTH, 2),
                    add("More Toughness", "+1 armour toughness.", Attributes.ARMOR_TOUGHNESS, 1),
                    add("More Mana", "+10 mana.", ModAttributes.MAX_MANA, 10),
                    mult("Less Burning", "Fire burns you 30% shorter.", Attributes.BURNING_TIME, -.30),
                    add("Less Knockback", "10% less knockback.", Attributes.KNOCKBACK_RESISTANCE, .1),
                    perk("Bulwark", "Below half health you take less damage: Resistance I."),
                    perk("Thornlight", "Monsters that strike you in reach take 3 damage and catch fire."),
                    perk(
                            "Second Dawn",
                            "Once every 10 minutes, a killing blow leaves you on 4 health in a burst of light.")),
            tree(
                    add("More Armour", "+1 armour.", Attributes.ARMOR, 1),
                    mult("Faster Walking", "You move 5% faster.", Attributes.MOVEMENT_SPEED, .05),
                    add("More Toughness", "+1 armour toughness.", Attributes.ARMOR_TOUGHNESS, 1),
                    mult("Higher Jumps", "You jump 10% higher.", Attributes.JUMP_STRENGTH, .10),
                    add("More Health", "+2 health.", Attributes.MAX_HEALTH, 2),
                    add("Less Knockback", "10% less knockback.", Attributes.KNOCKBACK_RESISTANCE, .1),
                    perk("Swift by Night", "At night you run fast: Speed I."),
                    perk("Mender's Gait", "While you sprint, you mend 1 health every 3 seconds."),
                    perk("Steadfast", "Nothing can knock you back.")),
            tree(
                    add("More Armour", "+1 armour.", Attributes.ARMOR, 1),
                    mult("Faster Walking", "You move 4% faster.", Attributes.MOVEMENT_SPEED, .04),
                    add("More Toughness", "+1 armour toughness.", Attributes.ARMOR_TOUGHNESS, 1),
                    // Silkstep Boots' gift, moved here (owner, 2026-11-17).
                    perk("Web Walker", "You walk through cobwebs at full speed."),
                    mult("Safer Falls", "Falls hurt you 25% less.", Attributes.FALL_DAMAGE_MULTIPLIER, -.25),
                    add("Step Up", "You step up half a block higher without jumping.", Attributes.STEP_HEIGHT, .5),
                    perk("Featherfall", "Falls never hurt you."),
                    perk("Tide Runner", "You swim like a dolphin: Dolphin's Grace in water."),
                    perk("Spring Step", "You jump higher: Jump Boost II.")),
            tree(
                    mult("More Spell Damage", "+5% spell power.", ModAttributes.SPELL_POWER, .05),
                    add("More Mana", "+10 mana.", ModAttributes.MAX_MANA, 10),
                    add("Spell Crits", "+3% spell crit chance.", ModAttributes.SPELL_CRIT, .03),
                    mult("Faster Mana", "Mana comes back 20% faster.", ModAttributes.MANA_REGEN, .20),
                    add("Shorter Cooldowns", "Spells ready 5% sooner.", ModAttributes.COOLDOWN_SPEED, .05),
                    add("Harder Crits", "Crits hit 10% harder.", ModAttributes.CRIT_DAMAGE, .30),
                    perk("Mana Font", "One spell in five costs no mana."),
                    perk("Spellfire", "Your spells set the monsters they strike alight for 2 seconds."),
                    perk("Echoing Staff", "One spell in eight leaves its tome ready again at once.")),
            tree(
                    add("More Armour", "+1 armour.", Attributes.ARMOR, 1),
                    add("More Toughness", "+1 armour toughness.", Attributes.ARMOR_TOUGHNESS, 1),
                    add("More Health", "+2 health.", Attributes.MAX_HEALTH, 2),
                    mult("Faster Walking", "You move 4% faster.", Attributes.MOVEMENT_SPEED, .04),
                    add("More Mana", "+10 mana.", ModAttributes.MAX_MANA, 10),
                    add("Less Knockback", "10% less knockback.", Attributes.KNOCKBACK_RESISTANCE, .1),
                    perk("Tireless Wings", "While you glide, the wings mend themselves."),
                    perk("Star Glide", "While you glide, the wind keeps pushing you onward."),
                    perk("Soft Landing", "Flying into walls and falling never hurt you.")),
            tree(
                    mult("Arrow Damage", "Arrows hit 10% harder.", ModAttributes.RANGED_POWER, .10),
                    add("More Crits", "+3% crit chance.", ModAttributes.WEAPON_CRIT, .03),
                    mult("Faster Walking", "You move 4% faster.", Attributes.MOVEMENT_SPEED, .04),
                    add("Harder Crits", "Crits hit 10% harder.", ModAttributes.CRIT_DAMAGE, .30),
                    add("More Mana", "+10 mana.", ModAttributes.MAX_MANA, 10),
                    add("More Health", "+2 health.", Attributes.MAX_HEALTH, 2),
                    perk("Twin Shot", "Every arrow flies with a twin beside it (the twin cannot be picked up)."),
                    perk("Frost Arrow", "Arrows slow what they hit: Slowness II for 3 seconds, and the cold bites."),
                    perk("Quiver of Light", "One arrow in three is not used up.")),
            tree(
                    mult("Faster Mining", "Mines 15% faster.", Attributes.BLOCK_BREAK_SPEED, .15),
                    add("Longer Reach", "Reaches blocks half a block farther.", Attributes.BLOCK_INTERACTION_RANGE, .5),
                    add("Longer Breath", "You hold your breath longer underwater.", Attributes.OXYGEN_BONUS, 1),
                    mult("Safer Falls", "Falls hurt you 25% less.", Attributes.FALL_DAMAGE_MULTIPLIER, -.25),
                    mult("Faster Walking", "You move 4% faster.", Attributes.MOVEMENT_SPEED, .04),
                    add(
                            "Underwater Mining",
                            "Mining underwater is barely slowed.",
                            Attributes.SUBMERGED_MINING_SPEED,
                            .6),
                    perk("Vein Miner", "Breaking an ore also breaks up to 8 more of it touching it."),
                    perk("Smelting Touch", "What you mine drops already smelted."),
                    perk("Miner's Fervour", "Each block you break gives you Haste II for 3 seconds.")),
            tree(
                    add("More Armour", "+1 armour.", Attributes.ARMOR, 1),
                    add("More Health", "+2 health.", Attributes.MAX_HEALTH, 2),
                    add("Less Knockback", "10% less knockback.", Attributes.KNOCKBACK_RESISTANCE, .1),
                    mult("Less Burning", "Fire burns you 30% shorter.", Attributes.BURNING_TIME, -.30),
                    add(
                            "Blast Steady",
                            "Explosions throw you 30% less.",
                            Attributes.EXPLOSION_KNOCKBACK_RESISTANCE,
                            .3),
                    add("More Toughness", "+1 armour toughness.", Attributes.ARMOR_TOUGHNESS, 1),
                    perk("Spiked Rim", "Blows you block cost the attacker 4 health."),
                    perk("Repulse", "Blocking a blow throws the attacker back."),
                    perk("Restoring Guard", "Each blow you block mends 1 health.")),
            tree(
                    add("More Damage", "+1 attack damage.", Attributes.ATTACK_DAMAGE, 1),
                    mult("Faster Chopping", "Chops and mines 20% faster.", Attributes.BLOCK_BREAK_SPEED, .20),
                    add("Less Knockback", "10% less knockback.", Attributes.KNOCKBACK_RESISTANCE, .1),
                    add("More Crits", "+5% crit chance.", ModAttributes.WEAPON_CRIT, .05),
                    add("Harder Crits", "Crits hit 10% harder.", ModAttributes.CRIT_DAMAGE, .30),
                    add("More Health", "+2 health.", Attributes.MAX_HEALTH, 2),
                    perk("Cleave", "Every hit carries on into monsters beside the target for 60%."),
                    perk("Tree Feller", "Chopping one log of a tree fells it, up to 64 logs."),
                    perk("Sunder", "Hits strip 4 armour from the target for 5 seconds.")),
            tree(
                    add("Longer Reach", "Reaches half a block farther.", Attributes.ENTITY_INTERACTION_RANGE, .5),
                    add("More Damage", "+1 attack damage.", Attributes.ATTACK_DAMAGE, 1),
                    mult("Faster Walking", "You move 4% faster.", Attributes.MOVEMENT_SPEED, .04),
                    add("Faster Thrusts", "Thrusts 10% faster.", Attributes.ATTACK_SPEED, .24),
                    add("Harder Crits", "Crits hit 10% harder.", ModAttributes.CRIT_DAMAGE, .30),
                    add("More Crits", "+5% crit chance.", ModAttributes.WEAPON_CRIT, .05),
                    perk("Skewer", "Hits run through to the next monster behind the target for 75%."),
                    perk("Pinning Strike", "Hits pin the target: Slowness III for 2 seconds."),
                    perk("Charging Thrust", "Hits while sprinting deal 50% more damage.")),
            tree(
                    add("More Damage", "+1 attack damage.", Attributes.ATTACK_DAMAGE, 1),
                    add("More Health", "+2 health.", Attributes.MAX_HEALTH, 2),
                    add("More Armour", "+1 armour.", Attributes.ARMOR, 1),
                    add("Harder Crits", "Crits hit 10% harder.", ModAttributes.CRIT_DAMAGE, .30),
                    add("Less Knockback", "10% less knockback.", Attributes.KNOCKBACK_RESISTANCE, .1),
                    add("More Crits", "+5% crit chance.", ModAttributes.WEAPON_CRIT, .05),
                    perk("Concussion", "Hits stun the target: Slowness IV and Weakness for 2 seconds."),
                    perk("Shockwave", "Every hit throws monsters within 3 blocks of the target back for 2 damage."),
                    perk("Meteor Fall", "While you hold it, falls never hurt you.")),
            tree(
                    add("More Damage", "+1 attack damage.", Attributes.ATTACK_DAMAGE, 1),
                    add("Longer Breath", "You hold your breath longer underwater.", Attributes.OXYGEN_BONUS, 1),
                    add("Faster Swimming", "You swim 20% faster.", Attributes.WATER_MOVEMENT_EFFICIENCY, .2),
                    add("Harder Crits", "Crits hit 10% harder.", ModAttributes.CRIT_DAMAGE, .30),
                    add("More Crits", "+5% crit chance.", ModAttributes.WEAPON_CRIT, .05),
                    add("Faster Throws", "Thrusts and throws 10% faster.", Attributes.ATTACK_SPEED, .24),
                    perk("Storm Caller", "A thrown trident calls down lightning on what it hits for 5 more damage."),
                    perk("Harpoon", "A thrown trident drags what it hits back to you."),
                    perk("Tidecaller", "In water or rain, its hits deal 50% more damage.")));

    private static List<List<Node>> tree(Node... nine) {
        var out = new ArrayList<List<Node>>();
        for (int t = 0; t < TIERS; t++) out.add(List.of(nine[t * 3], nine[t * 3 + 1], nine[t * 3 + 2]));
        return List.copyOf(out);
    }

    /** The tree of a {@link #kind}. */
    public static List<List<Node>> tiers(int kind) {
        return TABLE.get(Math.clamp(kind, 0, TABLE.size() - 1));
    }

    // ---------------------------------------------------------------- on the piece

    private static int[] read(ItemStack s, String key) {
        int[] saved = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getIntArray(key)
                .orElse(null);
        return saved;
    }

    /** The node in use per tier ({@code -1}: none). A piece from the step days gets the first node of each step paid. */
    public static int[] chosen(ItemStack s) {
        int[] saved = read(s, KEY), out = {-1, -1, -1};
        if (saved == null) {
            for (int t = 0; t < LightGear.step(s) - 1 && t < TIERS; t++) out[t] = 0;
            return out;
        }
        for (int t = 0; t < TIERS && t < saved.length; t++)
            out[t] = saved[t] >= 0 && saved[t] < CHOICES ? saved[t] : -1;
        return out;
    }

    /** Nodes bought, a bit mask per tier; the node in use always counts. */
    public static int[] owned(ItemStack s) {
        int[] saved = read(s, OWNED), chosen = chosen(s), out = new int[TIERS];
        for (int t = 0; t < TIERS; t++) {
            out[t] = (saved != null && t < saved.length ? saved[t] : 0) & 7;
            if (chosen[t] >= 0) out[t] |= 1 << chosen[t];
        }
        return out;
    }

    /** Whether any node of the piece's tree has been bought. */
    public static boolean upgraded(ItemStack s) {
        if (s.isEmpty() || !s.isDamageableItem()) return false;
        for (int o : owned(s)) if (o != 0) return true;
        return false;
    }

    public static boolean owns(int[] owned, int tier, int choice) {
        return (owned[tier] & 1 << choice) != 0;
    }

    /** The piece's level: 1, and one more for each tier with a node bought (up to 4). */
    public static int level(ItemStack s) {
        int[] owned = owned(s);
        int n = 1;
        for (int t = 0; t < TIERS; t++) if (owned[t] != 0) n++;
        return n;
    }

    /** Makes a piece a Relic: one random node of each tier bought and in use (owner, 2026-11-20). */
    public static void relic(ItemStack s, net.minecraft.util.RandomSource random) {
        int kind = kind(s);
        if (kind < 0) return;
        int[] chosen = new int[TIERS], owned = new int[TIERS];
        for (int t = 0; t < TIERS; t++) {
            chosen[t] = random.nextInt(CHOICES);
            owned[t] = 1 << chosen[t];
        }
        write(s, chosen, owned);
        s.set(DataComponents.ATTRIBUTE_MODIFIERS, stats(kind, s));
    }

    static void write(ItemStack s, int[] chosen, int[] owned) {
        CustomData.update(DataComponents.CUSTOM_DATA, s, tag -> {
            tag.putIntArray(KEY, chosen.clone());
            tag.putIntArray(OWNED, owned.clone());
        });
        if (LightGear.ITEMS.contains(s.getItem())) LightGear.setStep(s, level(s));
    }

    /**
     * The piece's attributes plus the stat nodes in use. Light gear's base is its ore strength's own armour or damage
     * ({@link LightGear#oreLevel}); any other gear keeps what it has (its own stats, rolled affixes), less the nodes
     * written before.
     */
    static ItemAttributeModifiers stats(int kind, ItemStack s) {
        int index = LightGear.ITEMS.indexOf(s.getItem());
        boolean light = index >= 0;
        if (light) kind = kind(s); // a Light piece's index is not always its kind (the shield)
        var base = light
                ? LightGear.stats(index, LightGear.oreLevel(s))
                : s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        int[] chosen = chosen(s);
        var b = ItemAttributeModifiers.builder();
        for (var e : base.modifiers()) if (light || !ours(e.modifier())) b.add(e.attribute(), e.modifier(), e.slot());
        var slot = light && kind != SHIELD ? EquipmentSlotGroup.bySlot(LightGear.SLOTS[index]) : slots(kind);
        for (int t = 0; t < TIERS; t++) {
            if (chosen[t] < 0) continue;
            Node n = tiers(kind).get(t).get(chosen[t]);
            // Steadfast is a perk carried by an attribute: full knockback resistance.
            if (n.name().equals("Steadfast"))
                b.add(
                        Attributes.KNOCKBACK_RESISTANCE,
                        new AttributeModifier(
                                ModItems.id("light_tree_steadfast"), 1, AttributeModifier.Operation.ADD_VALUE),
                        slot);
            if (n.perk()) continue;
            b.add(
                    n.attr(),
                    new AttributeModifier(ModItems.id("light_tree_" + KEYS[kind] + "_" + t), n.amount(), n.op()),
                    slot);
        }
        return b.build();
    }

    private static boolean ours(AttributeModifier m) {
        return m.id().getNamespace().equals(Lightborn.MOD_ID)
                && m.id().getPath().startsWith("light_tree_");
    }

    /** Whether the piece in that hand or slot has the perk {@code name} in use (tier III). */
    public static boolean perk(ItemStack s, String name) {
        return inUse(s, 2, name);
    }

    /** Whether the node {@code name} is the one in use in that tier of the piece's tree. */
    public static boolean inUse(ItemStack s, int tier, String name) {
        int i = kind(s);
        if (i < 0) return false;
        int c = chosen(s)[tier];
        return c >= 0 && tiers(i).get(tier).get(c).name().equals(name);
    }

    /** Why clicking this node does nothing, or null (the ore is checked by the caller). */
    public static String blockReason(int[] chosen, int[] owned, int tier, int choice) {
        if (tier < 0 || tier >= TIERS || choice < 0 || choice >= CHOICES) return "No such upgrade";
        if (chosen[tier] == choice) return "Already in use";
        if (owns(owned, tier, choice)) return null;
        if (tier > 0 && owned[tier - 1] == 0) return "Buy a tier " + SpellUpgrades.roman(tier - 1) + " upgrade first";
        return null;
    }

    /** Clicks one node of the gear in that inventory slot: a bought node switches in, a new one is bought. */
    static String choose(ServerPlayer p, int slot, int tier, int choice) {
        if (slot < 0 || slot >= p.getInventory().getContainerSize()) return refuse(p, "No such slot.");
        var s = p.getInventory().getItem(slot);
        int i = kind(s);
        if (i < 0) return refuse(p, "That cannot be upgraded.");
        int[] chosen = chosen(s), owned = owned(s);
        boolean free = p.hasInfiniteMaterials();
        String reason = blockReason(chosen, owned, tier, choice);
        if (reason != null) return refuse(p, reason);
        boolean switching = owns(owned, tier, choice);
        if (!switching && !free) {
            Item oreItem = ore(tier);
            int need = ORE[tier];
            String oreId = BuiltInRegistries.ITEM.getKey(oreItem).toString();
            if (need > 0 && Collection.stored(p, oreId).getCount() + Enchanting.count(p, oreItem) < need)
                return refuse(
                        p,
                        "Needs " + need + " "
                                + new ItemStack(oreItem).getHoverName().getString() + ".");
            if (need > 0) Enchanting.consume(p, oreItem, need - Collection.spendStored(p, oreId, need));
        }
        chosen[tier] = choice;
        owned[tier] |= 1 << choice;
        write(s, chosen, owned);
        s.set(DataComponents.ATTRIBUTE_MODIFIERS, stats(i, s));
        p.getInventory().setChanged();
        p.containerMenu.broadcastChanges();
        var n = tiers(i).get(tier).get(choice);
        p.level().playSound(null, p.blockPosition(), SoundEvents.SMITHING_TABLE_USE, SoundSource.PLAYERS, 1f, 1f);
        p.level()
                .playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1f, 1.2f);
        if (!switching)
            p.level().sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1.2, p.getZ(), 30, .5, .7, .5, .03);
        p.sendOverlayMessage(Component.literal(n.name()
                        + (switching
                                ? " is in use again."
                                : " is worked into the " + s.getHoverName().getString() + "."))
                .withStyle(ChatFormatting.GOLD));
        return null;
    }

    private static String refuse(ServerPlayer p, String why) {
        p.sendOverlayMessage(Component.literal(why).withStyle(ChatFormatting.RED));
        return why;
    }

    public record Choose(BlockPos chest, int slot, int tier, int choice) implements CustomPacketPayload {
        public static final Type<Choose> TYPE = new Type<>(ModItems.id("light_tree_choose"));
        static final StreamCodec<RegistryFriendlyByteBuf, Choose> CODEC = StreamCodec.composite(
                        BlockPos.STREAM_CODEC,
                        Choose::chest,
                        ByteBufCodecs.VAR_INT,
                        Choose::slot,
                        ByteBufCodecs.VAR_INT,
                        Choose::tier,
                        ByteBufCodecs.VAR_INT,
                        Choose::choice,
                        Choose::new)
                .cast();

        @Override
        public Type<Choose> type() {
            return TYPE;
        }
    }

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Choose.TYPE, Choose.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Choose.TYPE, (u, ctx) -> {
            if (LightShop.near(ctx.player(), u.chest())) choose(ctx.player(), u.slot(), u.tier(), u.choice());
        });
    }

    /** A lost piece's tree, packed for the lost-gear record: chosen 2 bits a tier (3 = none), owned 3 bits a tier. */
    static int pack(ItemStack s) {
        int[] chosen = chosen(s), owned = owned(s);
        int v = 0;
        for (int t = 0; t < TIERS; t++) v |= (chosen[t] < 0 ? 3 : chosen[t]) << (2 * t) | owned[t] << (6 + 3 * t);
        return v;
    }

    static void unpack(ItemStack s, int v) {
        int[] chosen = new int[TIERS], owned = new int[TIERS];
        for (int t = 0; t < TIERS; t++) {
            int c = v >> (2 * t) & 3;
            chosen[t] = c == 3 ? -1 : c;
            owned[t] = v >> (6 + 3 * t) & 7;
        }
        write(s, chosen, owned);
    }
}
