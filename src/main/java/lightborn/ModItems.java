package lightborn;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** Gear tiers, mage staffs, spell tomes and the Lightborn materials. */
public final class ModItems {
    private ModItems() {}

    /** Six gear tiers. The first three reuse vanilla items. */
    /** Gear materials; which one a monster drops is {@link OreTiers}' call (ore mined, monster strength). */
    public enum Tier {
        IRON("Iron"),
        DIAMOND("Diamond"),
        NETHERITE("Netherite");

        public final String label;

        Tier(String label) {
            this.label = label;
        }
    }

    public enum Kind {
        SWORD,
        AXE,
        STAFF,
        HELMET,
        CHESTPLATE,
        LEGGINGS,
        BOOTS
    }

    /** tier -> kind -> item. */
    public static final Map<Tier, Map<Kind, Item>> GEAR = new EnumMap<>(Tier.class);

    public static final List<Item> MOD_ITEMS = new ArrayList<>();
    public static Item DARK_SHARD;
    /** The Light's own currency: the Chest of Light's shop takes nothing else. */
    public static Item SPARK;

    public static Item STARTER_STAFF;
    /** The Chest of Light (the Light's collection) and its item. */
    public static Block LIGHT_CHEST;

    public static Item LIGHT_CHEST_ITEM;
    /** spell -> its tome. */
    public static final Map<Spell, Item> TOMES = new EnumMap<>(Spell.class);

    // Staff implicit stats per tier: melee damage bonus, spell power, max mana.
    // Found Apprentice staffs have a spell-power implicit; the separate Simple Staff starter has no attributes.
    private static final String[] STAFF_NAMES = {"apprentice_staff", "adept_staff", "archmage_staff"};
    private static final double[] STAFF_POWER = {0.15, 0.25, 0.40};
    private static final double[] STAFF_MANA = {0, 20, 30};
    private static final Set<Item> STAFFS = new HashSet<>();

    /**
     * Specialist staffs (owner, 2026-11-09): one per tier, a little stronger than that
     * tier's plain staff and carrying one signature stat of its own. Loot picks one for about 3 staff drops in 10.
     */
    public record Specialist(
            String name,
            Tier tier,
            double power,
            double mana,
            Holder<Attribute> stat,
            double value,
            AttributeModifier.Operation op,
            String text) {}

    public static final List<Specialist> SPECIALISTS = List.of(
            new Specialist(
                    "thornwood_staff",
                    Tier.IRON,
                    0.20,
                    10,
                    ModAttributes.MANA_REGEN,
                    0.25,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE,
                    "+25% Mana Regeneration"),
            new Specialist(
                    "bone_staff",
                    Tier.DIAMOND,
                    0.30,
                    20,
                    ModAttributes.LIFE_LEECH,
                    0.04,
                    AttributeModifier.Operation.ADD_VALUE,
                    "+4% Life Leech"),
            new Specialist(
                    "storm_staff",
                    Tier.NETHERITE,
                    0.45,
                    30,
                    ModAttributes.COOLDOWN_SPEED,
                    0.15,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE,
                    "+15% Cooldown Speed"));
    /** specialist staff item -> its tier. */
    public static final Map<Item, Tier> SPECIALIST_TIER = new HashMap<>();
    /** tier -> its specialist staff (absent for the tiers without one). */
    public static final Map<Tier, Item> SPECIALIST_OF = new EnumMap<>(Tier.class);

    /** Spells can only be cast with one of these in the main hand. */
    public static boolean isStaff(ItemStack stack) {
        return !stack.isEmpty() && STAFFS.contains(stack.getItem());
    }

    public static void init() {
        // Vanilla tiers
        GEAR.put(
                Tier.IRON,
                kinds(
                        Items.IRON_SWORD,
                        Items.IRON_AXE,
                        Items.IRON_HELMET,
                        Items.IRON_CHESTPLATE,
                        Items.IRON_LEGGINGS,
                        Items.IRON_BOOTS));
        GEAR.put(
                Tier.DIAMOND,
                kinds(
                        Items.DIAMOND_SWORD,
                        Items.DIAMOND_AXE,
                        Items.DIAMOND_HELMET,
                        Items.DIAMOND_CHESTPLATE,
                        Items.DIAMOND_LEGGINGS,
                        Items.DIAMOND_BOOTS));
        GEAR.put(
                Tier.NETHERITE,
                kinds(
                        Items.NETHERITE_SWORD,
                        Items.NETHERITE_AXE,
                        Items.NETHERITE_HELMET,
                        Items.NETHERITE_CHESTPLATE,
                        Items.NETHERITE_LEGGINGS,
                        Items.NETHERITE_BOOTS));

        Tier[] tiers = Tier.values();
        for (int i = 0; i < tiers.length; i++) {
            ItemAttributeModifiers mods = ItemAttributeModifiers.builder()
                    .add(
                            Attributes.ATTACK_DAMAGE,
                            new AttributeModifier(
                                    Item.BASE_ATTACK_DAMAGE_ID, 2 + i, AttributeModifier.Operation.ADD_VALUE),
                            EquipmentSlotGroup.MAINHAND)
                    .add(
                            Attributes.ATTACK_SPEED,
                            new AttributeModifier(
                                    Item.BASE_ATTACK_SPEED_ID, -2.8, AttributeModifier.Operation.ADD_VALUE),
                            EquipmentSlotGroup.MAINHAND)
                    .add(
                            ModAttributes.SPELL_POWER,
                            new AttributeModifier(
                                    id("staff_spell_power"),
                                    STAFF_POWER[i],
                                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
                            EquipmentSlotGroup.MAINHAND)
                    .add(
                            ModAttributes.MAX_MANA,
                            new AttributeModifier(
                                    id("staff_max_mana"), STAFF_MANA[i], AttributeModifier.Operation.ADD_VALUE),
                            EquipmentSlotGroup.MAINHAND)
                    .build();
            Item.Properties props = new Item.Properties()
                    .stacksTo(1)
                    .attributes(mods)
                    .rarity(i >= 3 ? (i == 5 ? Rarity.EPIC : Rarity.RARE) : Rarity.UNCOMMON);
            if (i >= 2) props.fireResistant();
            Item staff = register(STAFF_NAMES[i], StaffItem::new, props);
            GEAR.get(tiers[i]).put(Kind.STAFF, staff);
            STAFFS.add(staff);
        }
        for (Specialist sp : SPECIALISTS) {
            int i = sp.tier().ordinal();
            ItemAttributeModifiers mods = ItemAttributeModifiers.builder()
                    .add(
                            Attributes.ATTACK_DAMAGE,
                            new AttributeModifier(
                                    Item.BASE_ATTACK_DAMAGE_ID, 2 + i, AttributeModifier.Operation.ADD_VALUE),
                            EquipmentSlotGroup.MAINHAND)
                    .add(
                            Attributes.ATTACK_SPEED,
                            new AttributeModifier(
                                    Item.BASE_ATTACK_SPEED_ID, -2.8, AttributeModifier.Operation.ADD_VALUE),
                            EquipmentSlotGroup.MAINHAND)
                    .add(
                            ModAttributes.SPELL_POWER,
                            new AttributeModifier(
                                    id("staff_spell_power"),
                                    sp.power(),
                                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
                            EquipmentSlotGroup.MAINHAND)
                    .add(
                            ModAttributes.MAX_MANA,
                            new AttributeModifier(
                                    id("staff_max_mana"), sp.mana(), AttributeModifier.Operation.ADD_VALUE),
                            EquipmentSlotGroup.MAINHAND)
                    .add(
                            sp.stat(),
                            new AttributeModifier(id("staff_signature"), sp.value(), sp.op()),
                            EquipmentSlotGroup.MAINHAND,
                            ItemAttributeModifiers.Display.override(
                                    Component.literal(sp.text()).withStyle(ChatFormatting.DARK_GREEN)))
                    .build();
            Item.Properties props =
                    new Item.Properties().stacksTo(1).attributes(mods).rarity(i >= 3 ? Rarity.RARE : Rarity.UNCOMMON);
            if (i >= 2) props.fireResistant();
            Item staff = register(sp.name(), StaffItem::new, props);
            STAFFS.add(staff);
            SPECIALIST_TIER.put(staff, sp.tier());
            SPECIALIST_OF.put(sp.tier(), staff);
        }

        // One tome per spell. Tomes never wear out (owner, 2026-11-16): no durability, no repair.
        // Not enchantable, so no Mending loop.
        for (Spell s : Spell.values()) {
            TOMES.put(s, register(s.tomeId(), props -> new TomeItem(s, props), new Item.Properties().stacksTo(1)));
        }
        // Spectral Blades became Water Splash (owner, 2026-11-10). Its old item id stays registered, so a tome already
        // in
        // a world does not vanish: the moment it sits in an inventory it turns into a Water Splash tome, wear and all.
        // Kept out of MOD_ITEMS, so it is in no creative tab and no collection.
        ResourceKey<Item> oldBlades = ResourceKey.create(Registries.ITEM, id("tome_spectral_blades"));
        Registry.register(
                BuiltInRegistries.ITEM,
                oldBlades,
                new Item(new Item.Properties().stacksTo(1).setId(oldBlades)) {
                    @Override
                    public void inventoryTick(ItemStack stack, ServerLevel level, Entity holder, EquipmentSlot slot) {
                        if (!(holder instanceof Player p)) return;
                        ItemStack fresh = stack.transmuteCopy(TOMES.get(Spell.WATER_SPLASH));
                        var inv = p.getInventory();
                        for (int i = 0; i < inv.getContainerSize(); i++) {
                            if (inv.getItem(i) == stack) {
                                inv.setItem(i, fresh);
                                return;
                            }
                        }
                    }
                });

        DARK_SHARD = register("dark_shard", Item::new, new Item.Properties().rarity(Rarity.RARE));
        SPARK = register("spark", Item::new, new Item.Properties().rarity(Rarity.UNCOMMON));
        Spellstone.init();
        STARTER_STAFF = registerTrophyStaff("simple_staff", new Item.Properties().stacksTo(1));
        BossTrophies.init();
        BossHeads.init();
        DarkNests.init();
        DarkGoo.init();
        DarkRealm.init();
        Hollow.init();
        ConjuredBlock.init();
        GlowMote.init();
        EmberCrust.init();
        LightGear.init();
        DragonStory.init();
        Furniture.init();
        ResourceKey<Block> chestKey = ResourceKey.create(Registries.BLOCK, id("light_chest"));
        LIGHT_CHEST = Registry.register(
                BuiltInRegistries.BLOCK,
                chestKey,
                new LightChestBlock(BlockBehaviour.Properties.of()
                        .setId(chestKey)
                        .mapColor(MapColor.GOLD)
                        .strength(-1f, 3_600_000f)
                        .pushReaction(PushReaction.IMMOVEABLE)
                        .sound(SoundType.AMETHYST)
                        // Its own light dies at the smallest ward's wall (owner, 2026-11-16: nothing outside the
                        // ward is lit); the client's ward glow lights the inside.
                        .lightLevel(s -> Balance.LIGHT_WARD_RADIUS + 1)
                        .noOcclusion()));
        LIGHT_CHEST_ITEM = register(
                "light_chest",
                props -> new BlockItem(LIGHT_CHEST, props),
                new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.RARE));

        Registry.register(
                BuiltInRegistries.CREATIVE_MODE_TAB,
                id("items"),
                FabricCreativeModeTab.builder()
                        .title(Component.literal("Lightborn"))
                        .icon(() -> new ItemStack(GEAR.get(Tier.NETHERITE).get(Kind.STAFF)))
                        .displayItems((params, output) -> {
                            for (Item item : MOD_ITEMS) output.accept(item);
                        })
                        .build());
    }

    private static Map<Kind, Item> kinds(Item sword, Item axe, Item helmet, Item chest, Item legs, Item boots) {
        Map<Kind, Item> map = new EnumMap<>(Kind.class);
        map.put(Kind.SWORD, sword);
        map.put(Kind.AXE, axe);
        map.put(Kind.HELMET, helmet);
        map.put(Kind.CHESTPLATE, chest);
        map.put(Kind.LEGGINGS, legs);
        map.put(Kind.BOOTS, boots);
        return map;
    }

    static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties props) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
        Item item = factory.apply(props.setId(key));
        Registry.register(BuiltInRegistries.ITEM, key, item);
        MOD_ITEMS.add(item);
        return item;
    }

    static Item registerTrophyStaff(String name, Item.Properties properties) {
        Item staff = register(name, StaffItem::new, properties);
        STAFFS.add(staff);
        return staff;
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Lightborn.MOD_ID, path);
    }

    public static Tier tierOf(Item item) {
        for (var e : GEAR.entrySet()) if (e.getValue().containsValue(item)) return e.getKey();
        return SPECIALIST_TIER.get(item);
    }
}
