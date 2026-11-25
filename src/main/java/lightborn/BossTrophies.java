package lightborn;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.phys.Vec3;

/** Boss rewards and their powers. Trophy collection and first-kill history are independent. */
public final class BossTrophies {
    private BossTrophies() {}

    private static final Map<BossKind, Item> ITEMS = new EnumMap<>(BossKind.class);

    static void init() {
        ITEMS.put(
                BossKind.GORATH,
                armor(
                        BossKind.GORATH,
                        ArmorType.CHESTPLATE,
                        8,
                        1f,
                        "Stand your ground. A hit grants Stoneskin once every 20 s."));
        // Maelis, Syrith and Nyx have no trophy of their own (owner, 2026-11-17: Silkstep Boots and Syrith's Fang
        // removed; 2026-11-22: Eclipse became an ordinary Legendary tome): they drop a legendary piece of gear in its
        // place (dropTrophy).
        ITEMS.put(
                BossKind.VEXMORA,
                staff(BossKind.VEXMORA, 0.35, "15% of your kills rise as skeleton thralls for 30 s."));
        ITEMS.put(BossKind.OZRIK, staff(BossKind.OZRIK, 0.60, "Fireball and Comet deal 25% more damage."));
    }

    static Item item(BossKind kind) {
        return ITEMS.get(kind);
    }

    private static Item armor(BossKind boss, ArmorType slot, int armor, float resistance, String lore) {
        var material = new ArmorMaterial(
                37,
                Map.of(slot, armor),
                15,
                SoundEvents.ARMOR_EQUIP_NETHERITE,
                3f,
                resistance,
                ItemTags.REPAIRS_NETHERITE_ARMOR,
                ResourceKey.create(EquipmentAssets.ROOT_ID, ModItems.id(boss.trophyId)));
        return ModItems.register(boss.trophyId, Item::new, properties(lore).humanoidArmor(material, slot));
    }

    private static Item staff(BossKind boss, double power, String lore) {
        return ModItems.registerTrophyStaff(
                boss.trophyId,
                properties(lore)
                        .stacksTo(1)
                        .attributes(weapon(6, -2.8, ModAttributes.SPELL_POWER, power * .5, boss.id + "_power")));
    }

    private static ItemAttributeModifiers weapon(
            double damage, double speed, Holder<Attribute> bonus, double amount, String id) {
        return ItemAttributeModifiers.builder()
                .add(
                        Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(
                                Item.BASE_ATTACK_DAMAGE_ID, damage, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(
                        Attributes.ATTACK_SPEED,
                        new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, speed, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(
                        bonus,
                        new AttributeModifier(
                                ModItems.id(id),
                                amount,
                                bonus == ModAttributes.SPELL_POWER
                                        ? AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                                        : AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .build();
    }

    private static Item.Properties properties(String lore) {
        return new Item.Properties()
                .rarity(Rarity.EPIC)
                .fireResistant()
                .component(
                        DataComponents.LORE,
                        new ItemLore(List.of(Component.literal(lore)
                                .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.LIGHT_PURPLE)))));
    }

    static boolean holds(LivingEntity entity, BossKind kind) {
        return item(kind) != null && entity.getMainHandItem().is(item(kind));
    }

    static boolean awardKill(ServerPlayer player, LivingEntity boss, Bosses.Encounter encounter, ServerLevel level) {
        var history = player.getAttachedOrElse(ModData.BOSS_KILLS, List.of());
        boolean first = !history.contains(encounter.kind());
        boolean owed =
                first || encounter.revenge().isPresent() || level.getRandom().nextDouble() < Balance.BOSS_TROPHY_REPEAT;
        boolean awarded = owed && dropTrophy(boss, encounter.kind(), level, player);
        if (first) BossHeads.dropFirstKillHead(boss, encounter.kind(), level, player);
        if (first && awarded) {
            var updated = new ArrayList<>(history);
            updated.add(encounter.kind());
            player.setAttached(ModData.BOSS_KILLS, List.copyOf(updated));
        }
        return awarded;
    }

    static boolean dropTrophy(LivingEntity boss, BossKind kind, ServerLevel level, ServerPlayer fallback) {
        ItemStack trophy;
        var rarity = LootRoller.Rarity.LEGENDARY;
        if (item(kind) != null) trophy = new ItemStack(item(kind));
        else {
            var tier = fallback == null ? null : OreTiers.pick(level.getRandom(), fallback, OreTiers.BOSS);
            if (tier == null) trophy = new ItemStack(Items.DIAMOND, 3);
            else {
                rarity = LootRoller.combatRarity(level.getRandom(), rarity);
                trophy = LootRoller.roll(level.getRandom(), tier, rarity);
            }
        }
        var drop = boss.spawnAtLocation(level, trophy);
        LootBeams.onDrop(drop, rarity);
        return drop != null || (fallback != null && fallback.getInventory().add(trophy));
    }

    static void onMonsterKill(ServerPlayer player, LivingEntity dead, ServerLevel level) {
        if (!holds(player, BossKind.VEXMORA) || level.getRandom().nextDouble() >= Balance.BONECALLER_CHANCE) return;
        raise(player, dead.position(), level);
    }

    static Mob raise(ServerPlayer player, Vec3 at, ServerLevel level) {
        if (!Command.hasRoom(player, Balance.COMMAND_THRALL)) return null;
        var skeleton = VariantCompat.keepKind(EntityTypes.SKELETON.create(level, EntitySpawnReason.MOB_SUMMONED));
        if (skeleton == null) return null;
        skeleton.setPos(at);
        if (level.getBlockCollisions(skeleton, skeleton.getBoundingBox())
                .iterator()
                .hasNext()) {
            skeleton.discard();
            return null;
        }
        Monsters.setRank(skeleton, MonsterInfo.NORMAL);
        skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        skeleton.setDropChance(EquipmentSlot.MAINHAND, 0);
        skeleton.setAttached(ModData.RAISED_UNTIL, level.getGameTime() + Balance.BONECALLER_TICKS);
        if (!level.addFreshEntity(skeleton)) {
            skeleton.discard();
            return null;
        }
        SummonSpells.bindThrall(level, player, skeleton, false);
        return skeleton;
    }

    static void afterHit(LivingEntity entity, DamageSource source, float damage) {
        if (!(entity instanceof ServerPlayer player)
                || !player.isAlive()
                || damage <= 0
                || source.getEntity() == null
                || !player.getItemBySlot(EquipmentSlot.CHEST).is(item(BossKind.GORATH))) return;
        long now = player.level().getGameTime();
        if (now < player.getAttachedOrElse(ModData.PLATE_NEXT, 0L)) return;
        player.setAttached(ModData.PLATE_NEXT, now + Balance.PLATE_COOLDOWN);
        EarthSpells.stoneskin(player.level(), player, Balance.PLATE_SHIELD, Balance.PLATE_SHIELD_TICKS);
    }

    /** Boots with Web Walker in use (Upgrade Gear, boots tier II) pass through cobwebs at full speed. */
    public static boolean webWalker(Entity entity) {
        return entity instanceof LivingEntity living && LightGearPerks.webWalker(living);
    }

    public static boolean concealed(Entity entity) {
        return entity instanceof ServerPlayer player
                && player.level().getGameTime() < player.getAttachedOrElse(ModData.ECLIPSE_UNTIL, 0L);
    }

    /** Ends Eclipse: striking anything does (owner, 2026-11-22); building, mining and the rest do not. */
    static void reveal(ServerPlayer player) {
        player.setAttached(ModData.ECLIPSE_UNTIL, 0L);
        player.removeEffect(MobEffects.INVISIBILITY);
        if (player.level() instanceof ServerLevel level)
            level.sendParticles(
                    ParticleTypes.SOUL, player.getX(), player.getY() + 1, player.getZ(), 20, .3, .6, .3, .05);
    }

    static void eclipse(ServerLevel level, ServerPlayer player) {
        int duration = SpellUpgrades.scaleInt(Spell.ECLIPSE, SpellUpgrades.Stat.DURATION, Balance.ECLIPSE_TICKS);
        player.setAttached(ModData.ECLIPSE_UNTIL, level.getGameTime() + duration);
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, duration, 0, false, false, true));
        if (SpellUpgrades.perk(player, Spell.ECLIPSE, "Night Runner"))
            player.addEffect(new MobEffectInstance(MobEffects.SPEED, duration, 1, false, false, true));
        if (SpellUpgrades.perk(player, Spell.ECLIPSE, "Dark Mend"))
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, duration, 0, false, false, true));
        for (Mob m : level.getEntitiesOfClass(
                Mob.class,
                player.getBoundingBox().inflate(Balance.HUNTER_FOLLOW_RANGE),
                m -> Monsters.isMonster(m) && m.getTarget() == player)) {
            m.setTarget(null);
            m.getNavigation().stop();
        }
        Bosses.ring(level, player.position(), 1.8, ParticleTypes.SOUL, 28);
        level.playSound(null, player.blockPosition(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1, 0.8f);
    }
}
