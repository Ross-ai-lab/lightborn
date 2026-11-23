package lightborn;

import com.mojang.serialization.Codec;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Unit;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.*;

/**
 * The Dragon's gifts: fixed properties, never rolled affixes. Each piece grows at the Chest of Light's Upgrade Light
 * Gear tab, a tree like Upgrade Spells' ({@link LightGearTree}); its level (1 to 4) is one more than the tiers
 * bought, kept on the item as its step.
 */
public final class LightGear {
    private LightGear() {}

    /** The level a piece has reached, kept on the item ({@link LightGearTree#level} writes it). */
    static final String STEP = "light_step";

    public static final int MAX_STEP = 4;

    public static final List<Item> ITEMS = new ArrayList<>();
    static final AttachmentType<Map<String, Integer>> LOST = AttachmentRegistry.create(
            ModItems.id("lost_light_gear"), b -> b.persistent(Codec.unboundedMap(Codec.STRING, Codec.INT)));
    /**
     * The chapter that gives each piece: helm, sword, chestplate, greaves, boots, staff, wings (one later, 2026-11-16),
     * and the shield, in chapter 2 (owner, 2026-11-23: it came with the sword, and chapter 2 was left with no gift
     * once the helm moved to the first night, at the meeting).
     */
    static final int[] GIFT_CHAPTERS = {1, 3, 4, 5, 6, 7, 8, 2};

    static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.HEAD,
        EquipmentSlot.MAINHAND,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND,
        EquipmentSlot.CHEST,
        EquipmentSlot.OFFHAND
    };

    static void init() {
        var material = new ArmorMaterial(
                33,
                Map.of(
                        ArmorType.HELMET,
                        2,
                        ArmorType.CHESTPLATE,
                        6,
                        ArmorType.LEGGINGS,
                        5,
                        ArmorType.BOOTS,
                        2,
                        ArmorType.BODY,
                        6),
                15,
                SoundEvents.ARMOR_EQUIP_NETHERITE,
                0,
                0,
                ItemTags.REPAIRS_IRON_ARMOR,
                ResourceKey.create(EquipmentAssets.ROOT_ID, ModItems.id("light")));
        var names =
                List.of("light_helm", "light_sword", "light_chestplate", "light_greaves", "light_boots", "light_staff");
        for (int i = 0; i < names.size(); i++) {
            var props = new Item.Properties().rarity(Rarity.RARE).repairable(Items.GLOWSTONE_DUST);
            props = switch (i) {
                case 0 -> props.humanoidArmor(material, ArmorType.HELMET);
                case 2 -> props.humanoidArmor(material, ArmorType.CHESTPLATE);
                case 3 -> props.humanoidArmor(material, ArmorType.LEGGINGS);
                case 4 -> props.humanoidArmor(material, ArmorType.BOOTS);
                case 1 -> props.sword(ToolMaterial.IRON, 3, -2.4f).durability(1800);
                default -> props.durability(1600);
            };
            props.repairable(Items.GLOWSTONE_DUST);
            ITEMS.add(
                    i == 5
                            ? ModItems.registerTrophyStaff(names.get(i), props)
                            : ModItems.register(names.get(i), Item::new, props));
        }
        // Chapter 7: an ivory elytra. Worn in the chest slot, it stands in for the chestplate in the set's
        // radiance and Darkness ward, but not for the chestplate's poison immunity.
        ITEMS.add(ModItems.register(
                "light_wings",
                Item::new,
                new Item.Properties()
                        .durability(1200)
                        .rarity(Rarity.EPIC)
                        .component(DataComponents.GLIDER, Unit.INSTANCE)
                        .component(
                                DataComponents.EQUIPPABLE,
                                Equippable.builder(EquipmentSlot.CHEST)
                                        .setEquipSound(SoundEvents.ARMOR_EQUIP_ELYTRA)
                                        .setAsset(
                                                ResourceKey.create(EquipmentAssets.ROOT_ID, ModItems.id("light_wings")))
                                        .setDamageOnHurt(false)
                                        .build())
                        .repairable(Items.GLOWSTONE_DUST)));
        // The Light Shield (owner, 2026-11-17): a shield that comes up faster and shrugs off an axe sooner than an oak
        // one, and at night burns what strikes it (LightGearPerks.blocked).
        ITEMS.add(ModItems.register(
                "light_shield",
                Item::new,
                new Item.Properties()
                        .durability(1600)
                        .rarity(Rarity.RARE)
                        .repairable(Items.GLOWSTONE_DUST)
                        .equippableUnswappable(EquipmentSlot.OFFHAND)
                        .delayedComponent(
                                DataComponents.BLOCKS_ATTACKS,
                                lookup -> new net.minecraft.world.item.component.BlocksAttacks(
                                        .15f,
                                        .5f,
                                        List.of(new net.minecraft.world.item.component.BlocksAttacks.DamageReduction(
                                                90f, Optional.empty(), 0f, 1f)),
                                        new net.minecraft.world.item.component.BlocksAttacks.ItemDamageFunction(
                                                3f, 1f, 1f),
                                        Optional
                                                .<net.minecraft.core.HolderSet<
                                                                net.minecraft.world.damagesource.DamageType>>
                                                        of(lookup.lookupOrThrow(
                                                        net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                                                .getOrThrow(net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD)),
                                        Optional.<Holder<net.minecraft.sounds.SoundEvent>>of(SoundEvents.SHIELD_BLOCK),
                                        Optional.<Holder<net.minecraft.sounds.SoundEvent>>of(SoundEvents.SHIELD_BREAK)))
                        .component(DataComponents.BREAK_SOUND, SoundEvents.SHIELD_BREAK)));
    }

    public static final int WINGS = 6, SHIELD = 7;

    static boolean chestPiece(ItemStack s) {
        return s.is(ITEMS.get(2)) || ITEMS.size() > WINGS && s.is(ITEMS.get(WINGS));
    }

    /**
     * Where the Light Wings light the ground while their wearer glides (owner, 2026-11-17, in place of a trail of
     * motes): the open block on the first ground up to 28 below, or null when not gliding in them or nothing is below.
     */
    static net.minecraft.core.BlockPos groundLight(ServerPlayer p) {
        if (ITEMS.size() <= WINGS
                || !p.isFallFlying()
                || !p.getItemBySlot(EquipmentSlot.CHEST).is(ITEMS.get(WINGS))) return null;
        var at = p.blockPosition().mutable();
        for (int i = 0; i < 28 && at.getY() > p.level().getMinY(); i++, at.move(0, -1, 0)) {
            var below = p.level().getBlockState(at.below());
            if (below.isAir() || below.canBeReplaced() && below.getFluidState().isEmpty()) continue;
            var here = p.level().getBlockState(at);
            return here.isAir() || here.is(net.minecraft.world.level.block.Blocks.WATER) ? at.immutable() : null;
        }
        return null;
    }

    /** A soft pool of motes on the lit ground under gliding Light Wings. */
    static void wingTrail(ServerPlayer p) {
        if (p.tickCount % 3 != 0) return;
        var at = groundLight(p);
        if (at == null) return;
        p.level()
                .sendParticles(
                        ParticleTypes.END_ROD, at.getX() + .5, at.getY() + .1, at.getZ() + .5, 3, 1.4, .05, 1.4, .004);
    }

    /** A piece's step, 0 when it has none yet (taken from the creative menu): it counts as step 1. */
    public static int step(ItemStack s) {
        return s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getIntOr(STEP, 0);
    }

    static final String ORE = "light_ore";

    /**
     * How strong a Light piece is (owner, 2026-11-20: it grows with your ore, and says so): 1 iron, 2 diamond, 3
     * netherite, the best ore its holder has mined ({@link OreTiers#lightLevel}); kept on the piece so its tooltip can
     * name it. Upgrades no longer raise it; they only add their own nodes.
     */
    public static int oreLevel(ItemStack s) {
        return Math.clamp(
                s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .copyTag()
                        .getIntOr(ORE, 1),
                1,
                3);
    }

    /** Brings every Light piece the player carries to their best ore's strength (once a second). */
    static void refresh(ServerPlayer p) {
        int want = OreTiers.lightLevel(p);
        var inv = p.getInventory();
        boolean changed = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (!ITEMS.contains(s.getItem())) {
                if (LightGearTree.upgraded(s)) changed |= hideStatsWhileBroken(s); // upgraded gear breaks and stays too
                continue;
            }
            changed |= hideStatsWhileBroken(s);
            boolean stored = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                    .copyTag()
                    .contains(ORE);
            if (stored && oreLevel(s) == want) continue;
            CustomData.update(DataComponents.CUSTOM_DATA, s, tag -> tag.putInt(ORE, want));
            s.set(DataComponents.ATTRIBUTE_MODIFIERS, LightGearTree.stats(LightGearTree.kind(s), s));
            changed = true;
        }
        if (changed) inv.setChanged();
    }

    /**
     * A broken piece's armour and damage lines are hidden, since they do nothing until it is mended (owner,
     * 2026-11-20); mended, they show again. True when the tooltip changed.
     */
    public static boolean hideStatsWhileBroken(ItemStack s) {
        var display = s.getOrDefault(
                DataComponents.TOOLTIP_DISPLAY, net.minecraft.world.item.component.TooltipDisplay.DEFAULT);
        boolean hidden = !display.shows(DataComponents.ATTRIBUTE_MODIFIERS);
        if (hidden == s.isBroken()) return false;
        s.set(DataComponents.TOOLTIP_DISPLAY, display.withHidden(DataComponents.ATTRIBUTE_MODIFIERS, s.isBroken()));
        return true;
    }

    static void setStep(ItemStack s, int step) {
        CustomData.update(DataComponents.CUSTOM_DATA, s, tag -> tag.putInt(STEP, step));
    }

    static ItemAttributeModifiers stats(int index, int step) {
        int t = Math.clamp(step, 1, MAX_STEP);
        var b = ItemAttributeModifiers.builder();
        var slot = EquipmentSlotGroup.bySlot(SLOTS[index]);
        if (index == WINGS) {
            // Wings trade the chestplate's armour for flight: 3/4/5/6 armour, 1/2/3/4 toughness by level.
            b.add(
                    Attributes.ARMOR,
                    new AttributeModifier(ModItems.id("light_armor"), 2 + t, AttributeModifier.Operation.ADD_VALUE),
                    slot);
            b.add(
                    Attributes.ARMOR_TOUGHNESS,
                    new AttributeModifier(ModItems.id("light_toughness"), t, AttributeModifier.Operation.ADD_VALUE),
                    slot);
        } else if (index == SHIELD) {
            // Held in either hand: 1 to 4 armour by level.
            b.add(
                    Attributes.ARMOR,
                    new AttributeModifier(ModItems.id("light_armor_shield"), t, AttributeModifier.Operation.ADD_VALUE),
                    EquipmentSlotGroup.HAND);
        } else if (index != 1 && index != 5) {
            int part = switch (index) {
                case 0 -> 0;
                case 2 -> 1;
                case 3 -> 2;
                default -> 3;
            };
            int[][] armor = {{2, 6, 5, 2}, {3, 8, 6, 3}, {3, 8, 6, 3}, {4, 9, 7, 4}};
            b.add(
                    Attributes.ARMOR,
                    new AttributeModifier(
                            ModItems.id("light_armor_" + SLOTS[index].getName()),
                            armor[t - 1][part],
                            AttributeModifier.Operation.ADD_VALUE),
                    slot);
            b.add(
                    Attributes.ARMOR_TOUGHNESS,
                    new AttributeModifier(
                            ModItems.id("light_toughness_" + SLOTS[index].getName()),
                            new int[] {0, 2, 3, 4}[t - 1],
                            AttributeModifier.Operation.ADD_VALUE),
                    slot);
            if (t >= 3)
                b.add(
                        Attributes.KNOCKBACK_RESISTANCE,
                        new AttributeModifier(
                                ModItems.id("light_resistance_" + SLOTS[index].getName()),
                                t == 3 ? .1 : .15,
                                AttributeModifier.Operation.ADD_VALUE),
                        slot);
        } else {
            b.add(
                    Attributes.ATTACK_DAMAGE,
                    new AttributeModifier(
                            Item.BASE_ATTACK_DAMAGE_ID,
                            index == 1 ? 4 + t : 2 + t,
                            AttributeModifier.Operation.ADD_VALUE),
                    slot);
            b.add(
                    Attributes.ATTACK_SPEED,
                    new AttributeModifier(
                            Item.BASE_ATTACK_SPEED_ID, index == 1 ? -2.4 : -2.8, AttributeModifier.Operation.ADD_VALUE),
                    slot);
            if (index == 5) {
                b.add(
                        ModAttributes.SPELL_POWER,
                        new AttributeModifier(
                                ModItems.id("light_power"),
                                new double[] {.15, .25, .4, .55}[t - 1],
                                AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
                        slot);
                b.add(
                        ModAttributes.MAX_MANA,
                        new AttributeModifier(
                                ModItems.id("light_mana"), (t - 1) * 15, AttributeModifier.Operation.ADD_VALUE),
                        slot);
            }
        }
        return b.build();
    }

    /** A piece at the given step (the Dragon's gifts start at step 1). */
    public static ItemStack gift(int index, int step) {
        var s = new ItemStack(ITEMS.get(index));
        step = Math.clamp(step, 1, MAX_STEP);
        setStep(s, step);
        s.set(DataComponents.ATTRIBUTE_MODIFIERS, LightGearTree.stats(LightGearTree.kind(s), s));
        return s;
    }

    static void initNet() {
        LightGearTree.init();
    }

    static boolean has(ServerPlayer p, Item item) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
            if (p.getInventory().getItem(i).is(item)) return true;
        return false;
    }

    static void replaceMissing(ServerPlayer p) {
        int chapters = DragonStory.chapters(p);
        for (int i = 0; i < ITEMS.size(); i++)
            if (chapters >= GIFT_CHAPTERS[i] && (lost(p) & (1 << i)) != 0) {
                int step = lostStep(p, i);
                Integer tree = lostTree(p, i);
                clearLost(p, i);
                var s = gift(i, step);
                if (tree != null) LightGearTree.unpack(s, tree);
                s.set(DataComponents.ATTRIBUTE_MODIFIERS, LightGearTree.stats(i, s));
                give(p, s);
            }
    }

    static void give(ServerPlayer p, ItemStack s) {
        if (ITEMS.contains(s.getItem()))
            CustomData.update(
                    DataComponents.CUSTOM_DATA, s, tag -> tag.store("light_owner", UUIDUtil.CODEC, p.getUUID()));
        if (!p.getInventory().add(s)) p.spawnAtLocation(p.level(), s);
    }

    /** Bits 0-7: pieces lost for good, by index. */
    static int lost(ServerPlayer p) {
        return lostBits(p) & 0xFF;
    }

    /** The step a lost piece had reached, so its replacement keeps what was paid for (2 bits per piece from bit 8). */
    static int lostStep(ServerPlayer p, int i) {
        return Math.max(1, lostBits(p) >> (8 + 2 * i) & 3);
    }

    /** A lost piece's tree ({@link LightGearTree#pack}), kept beside the lost bits; null for one lost before trees. */
    private static Integer lostTree(ServerPlayer p, int i) {
        return p.level()
                .getServer()
                .overworld()
                .getAttachedOrElse(LOST, Map.of())
                .get(p.getUUID() + "#tree" + i);
    }

    private static int lostBits(ServerPlayer p) {
        return p.level()
                .getServer()
                .overworld()
                .getAttachedOrElse(LOST, Map.of())
                .getOrDefault(p.getUUID().toString(), 0);
    }

    static void clearLost(ServerPlayer p, int i) {
        var l = p.level().getServer().overworld();
        var map = new HashMap<>(l.getAttachedOrElse(LOST, Map.of()));
        map.put(p.getUUID().toString(), lostBits(p) & ~(1 << i) & ~(3 << (8 + 2 * i)));
        map.remove(p.getUUID() + "#tree" + i);
        l.setAttached(LOST, Map.copyOf(map));
    }

    public static void destroyed(ItemEntity item) {
        int i = ITEMS.indexOf(item.getItem().getItem());
        if (i < 0 || !(item.level() instanceof ServerLevel server)) return;
        var owner = item.getItem()
                .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .read("light_owner", UUIDUtil.CODEC);
        if (owner.isEmpty()) return;
        // The 2-bit step field keeps the old record readable; the tree rides beside it.
        int bits = 1 << i | Math.min(3, Math.max(1, step(item.getItem()))) << (8 + 2 * i);
        var l = server.getServer().overworld();
        var map = new HashMap<>(l.getAttachedOrElse(LOST, Map.of()));
        map.merge(owner.get().toString(), bits, (a, b) -> a & ~(3 << (8 + 2 * i)) | b);
        map.put(owner.get() + "#tree" + i, LightGearTree.pack(item.getItem()));
        l.setAttached(LOST, Map.copyOf(map));
    }

    static void update(ServerPlayer p) {
        for (int j = 0; j < p.getInventory().getContainerSize(); j++) {
            var s = p.getInventory().getItem(j);
            int i = ITEMS.indexOf(s.getItem());
            if (i >= 0) {
                if (step(s) == 0) setStep(s, 1);
                var wanted = LightGearTree.stats(i, s);
                if (!wanted.equals(s.get(DataComponents.ATTRIBUTE_MODIFIERS)))
                    s.set(DataComponents.ATTRIBUTE_MODIFIERS, wanted);
            } else if (p.tickCount % 20 == 0 && !s.isEmpty()) {
                // Other upgraded gear keeps its nodes' stats in step with its tree (a kind given its own tree).
                int kind = LightGearTree.kind(s);
                if (kind >= 0
                        && s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                                .copyTag()
                                .contains(LightGearTree.KEY)) {
                    var wanted = LightGearTree.stats(kind, s);
                    if (!wanted.equals(s.get(DataComponents.ATTRIBUTE_MODIFIERS)))
                        s.set(DataComponents.ATTRIBUTE_MODIFIERS, wanted);
                }
            }
        }
    }

    public static int pieces(LivingEntity p) {
        if (ITEMS.size() < 6) return 0;
        int n = chestPiece(p.getItemBySlot(EquipmentSlot.CHEST)) ? 1 : 0;
        for (int i : new int[] {0, 3, 4}) if (p.getItemBySlot(SLOTS[i]).is(ITEMS.get(i))) n++;
        for (var hand : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
            var s = p.getItemBySlot(hand);
            if (s.is(ITEMS.get(1)) || s.is(ITEMS.get(5)) || ITEMS.size() > SHIELD && s.is(ITEMS.get(SHIELD))) n++;
        }
        return n;
    }

    public static boolean immune(LivingEntity entity, Holder<MobEffect> effect) {
        if (!(entity instanceof ServerPlayer p) || ITEMS.isEmpty()) return false;
        if (LightGearPerks.immune(p, effect)) return true;
        if (effect == MobEffects.BLINDNESS) return working(p.getItemBySlot(EquipmentSlot.HEAD), 0);
        if (effect == MobEffects.POISON) return working(p.getItemBySlot(EquipmentSlot.CHEST), 2);
        if (effect == MobEffects.SLOWNESS) return working(p.getItemBySlot(EquipmentSlot.LEGS), 3);
        if (effect == MobEffects.WITHER) return working(p.getItemBySlot(EquipmentSlot.FEET), 4);
        return effect == MobEffects.DARKNESS && DragonStory.chapters(p) >= 8 && fullSet(p);
    }

    static boolean fullSet(LivingEntity p) {
        var chest = p.getItemBySlot(EquipmentSlot.CHEST);
        return working(p.getItemBySlot(EquipmentSlot.HEAD), 0)
                && chestPiece(chest)
                && !broken(chest)
                && working(p.getItemBySlot(EquipmentSlot.LEGS), 3)
                && working(p.getItemBySlot(EquipmentSlot.FEET), 4);
    }

    /**
     * A Light piece worn to its last point (owner, 2026-11-20): it never vanishes, it breaks and stays until glowstone
     * dust mends it at an anvil ({@code LightGearUnbreakingMixin}). Broken, it does nothing: no stats (vanilla skips a
     * broken worn piece), no flight, no perks, no immunity, no casting, no blocking.
     */
    public static boolean broken(ItemStack s) {
        return keeps(s) && s.isBroken();
    }

    /**
     * Whether a piece breaks but stays when worn out: the Dragon's Light gear (owner, 2026-11-20), and any gear given
     * an upgrade at the Chest of Light (owner, 2026-11-23: "does not vanish when depleting but break").
     */
    public static boolean keeps(ItemStack s) {
        return ITEMS.contains(s.getItem()) || LightGearTree.upgraded(s);
    }

    /** That Light piece, there and not broken. */
    private static boolean working(ItemStack s, int index) {
        return s.is(ITEMS.get(index)) && !s.isBroken();
    }

    /** Gives a piece added after its chapter (the shield) to a player already past that chapter, once. */
    static void late(ServerPlayer p) {
        if (ITEMS.size() <= SHIELD
                || DragonStory.chapters(p) < GIFT_CHAPTERS[SHIELD]
                || p.getAttachedOrElse(SHIELD_GIVEN, false)) return;
        p.setAttached(SHIELD_GIVEN, true);
        if (!has(p, ITEMS.get(SHIELD))) give(p, gift(SHIELD, 1));
    }

    static final AttachmentType<Boolean> SHIELD_GIVEN = AttachmentRegistry.create(
            ModItems.id("light_shield_given"), b -> b.persistent(Codec.BOOL).copyOnDeath());

    static void tick(ServerPlayer p) {
        if (p.tickCount % 100 == 0) late(p);
        update(p);
        wingTrail(p);
        LightGearPerks.tick(p);
        if (p.tickCount % 20 == 0) refresh(p);
        for (var e : List.copyOf(p.getActiveEffects())) if (immune(p, e.getEffect())) p.removeEffect(e.getEffect());
    }

    static boolean bursting;

    static void hit(LivingEntity victim, DamageSource source, float amount) {
        if (bursting
                || amount <= 0
                || !(source.getEntity() instanceof ServerPlayer p)
                || !p.getMainHandItem().is(ITEMS.get(1))
                || !(victim instanceof AbstractSkeleton)
                || p.getRandom().nextFloat() >= .2) return;
        bursting = true;
        try {
            Bosses.ring(p.level(), victim.position(), 3, ParticleTypes.END_ROD, 24);
            for (var e : p.level()
                    .getEntitiesOfClass(
                            AbstractSkeleton.class, victim.getBoundingBox().inflate(3)))
                if (e.distanceToSqr(victim) <= 9) {
                    e.damageCooldownTime = 0;
                    e.hurtServer(p.level(), p.damageSources().playerAttack(p), amount * 2);
                }
        } finally {
            bursting = false;
        }
    }

    static int radiance(ServerPlayer p) {
        if (!LightDragons.night(p.level())) return 0;
        int n = pieces(p) + (LightSpells.shining(p) ? 1 : 0) + (LightSpells.riding(p) ? 1 : 0);
        return n == 0 ? 0 : Math.min(13, 2 + n * 2);
    }
}
