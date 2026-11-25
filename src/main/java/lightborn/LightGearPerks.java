package lightborn;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;

/**
 * The tier III perks of Upgrade Gear ({@link LightGearTree}): each one works while its piece is worn, or held for the
 * held kinds (weapon, staff, bow, tool, shield), on the Dragon's Light gear and on any other gear alike.
 */
final class LightGearPerks {
    private LightGearPerks() {}

    private static final int HELM = LightGearTree.HELM,
            SWORD = LightGearTree.WEAPON,
            CHEST = LightGearTree.CHEST,
            GREAVES = LightGearTree.LEGS,
            BOOTS = LightGearTree.BOOTS,
            STAFF = LightGearTree.STAFF,
            WINGS = LightGearTree.WINGS,
            RANGED = LightGearTree.RANGED,
            TOOL = LightGearTree.TOOL,
            SHIELD = LightGearTree.SHIELD,
            AXE = LightGearTree.AXE,
            SPEAR = LightGearTree.SPEAR,
            MACE = LightGearTree.MACE,
            TRIDENT = LightGearTree.TRIDENT;

    /** The piece of that kind as the player wears or holds it, or EMPTY. */
    static ItemStack piece(LivingEntity p, int kind) {
        EquipmentSlot[] where = switch (kind) {
            case 0 -> new EquipmentSlot[] {EquipmentSlot.HEAD};
            case 2, 6 -> new EquipmentSlot[] {EquipmentSlot.CHEST};
            case 3 -> new EquipmentSlot[] {EquipmentSlot.LEGS};
            case 4 -> new EquipmentSlot[] {EquipmentSlot.FEET};
            case 7, 8, 10, 11, 12, 13 -> new EquipmentSlot[] {EquipmentSlot.MAINHAND};
            default -> new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
        };
        for (var slot : where) {
            var s = p.getItemBySlot(slot);
            if (LightGearTree.kind(s) == kind && !LightGear.broken(s)) return s; // a broken piece does nothing
        }
        return ItemStack.EMPTY;
    }

    static boolean has(LivingEntity p, int index, String perk) {
        var s = piece(p, index);
        return !s.isEmpty() && LightGearTree.perk(s, perk);
    }

    private static void keep(
            ServerPlayer p, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> fx, int amp) {
        p.addEffect(new MobEffectInstance(fx, 40, amp, true, false, true));
    }

    /** Once a second (gliding ones twice): the worn perks that keep an effect up or watch the player's state. */
    static void tick(ServerPlayer p) {
        if (has(p, WINGS, "Star Glide") && p.isFallFlying() && p.tickCount % 10 == 0) {
            p.setDeltaMovement(p.getDeltaMovement().add(p.getLookAngle().scale(.18)));
            p.connection.send(new ClientboundSetEntityMotionPacket(p));
        }
        if (p.tickCount % 5 == 0) lastLight(p, p.level());
        if (p.tickCount % 20 != 0) return;
        ServerLevel level = p.level();
        if (has(p, CHEST, "Bulwark") && p.getHealth() < p.getMaxHealth() / 2) keep(p, MobEffects.RESISTANCE, 0);
        if (has(p, GREAVES, "Swift by Night") && LightDragons.night(level)) keep(p, MobEffects.SPEED, 0);
        if (has(p, GREAVES, "Mender's Gait") && p.isSprinting() && p.tickCount % 60 == 0) p.heal(1);
        if (has(p, BOOTS, "Tide Runner") && p.isInWater()) keep(p, MobEffects.DOLPHINS_GRACE, 0);
        if (has(p, BOOTS, "Spring Step")) keep(p, MobEffects.JUMP_BOOST, 1);
        if (p.isFallFlying() && has(p, WINGS, "Tireless Wings")) {
            var wings = piece(p, WINGS);
            wings.setDamageValue(Math.max(0, wings.getDamageValue() - 2));
        }
    }

    /** Web Walker (boots, tier II): cobwebs do not hold the wearer. */
    static boolean webWalker(LivingEntity e) {
        var boots = e.getItemBySlot(EquipmentSlot.FEET);
        return LightGearTree.kind(boots) == BOOTS
                && !LightGear.broken(boots)
                && LightGearTree.inUse(boots, 1, "Web Walker");
    }

    /** Sage's Crown: a kill gives back mana. */
    static void onKill(ServerPlayer p) {
        if (has(p, HELM, "Sage's Crown")) Perks.mana(p, 5);
    }

    private static final Map<UUID, Long> LAST_LIGHT = new ConcurrentHashMap<>();

    /** Last Light: dropping below a third of health throws back the monsters round the wearer, once a minute. */
    private static void lastLight(ServerPlayer p, ServerLevel level) {
        if (!p.isAlive() || p.getHealth() >= p.getMaxHealth() / 3 || !has(p, HELM, "Last Light")) return;
        long now = level.getGameTime();
        if (now - LAST_LIGHT.getOrDefault(p.getUUID(), -2400L) < 1200) return;
        LAST_LIGHT.put(p.getUUID(), now);
        for (Mob m : level.getEntitiesOfClass(
                Mob.class, p.getBoundingBox().inflate(5), m -> m instanceof Enemy && m.distanceTo(p) <= 5)) {
            var away = new net.minecraft.world.phys.Vec3(m.getX() - p.getX(), 0, m.getZ() - p.getZ())
                    .normalize()
                    .scale(1.6);
            m.push(away.x, .5, away.z);
        }
        Bosses.ring(level, p.position().add(0, .2, 0), 5, ParticleTypes.END_ROD, 40);
        level.sendParticles(
                net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF4C0),
                p.getX(),
                p.getY() + 1,
                p.getZ(),
                1,
                0,
                0,
                0,
                0);
        level.playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1f, 1.6f);
    }

    /** Clear Mind keeps these off the wearer. */
    static boolean immune(LivingEntity p, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> fx) {
        return (fx == MobEffects.NAUSEA || fx == MobEffects.DARKNESS || fx == MobEffects.MINING_FATIGUE)
                && has(p, HELM, "Clear Mind");
    }

    /** Every hit, before armour: the sword's extra damage, Featherfall and Soft Landing. */
    static float modify(LivingEntity target, DamageSource source, float amount) {
        if (target instanceof ServerPlayer p) {
            boolean fall = source.is(DamageTypes.FALL);
            if (fall && has(p, BOOTS, "Featherfall")) return 0;
            if ((fall || source.is(DamageTypes.FLY_INTO_WALL)) && has(p, WINGS, "Soft Landing")) return 0;
            if (fall && has(p, MACE, "Meteor Fall")) return 0;
        }
        if (source.getEntity() instanceof ServerPlayer p
                && source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.ThrownTrident t
                && LightGearTree.perk(t.getWeaponItem(), "Tidecaller")
                && p.isInWaterOrRain()) amount *= 1.5f;
        if (source.getEntity() instanceof ServerPlayer p
                && source.getDirectEntity() == p
                && !source.is(DamageTypeTags.IS_PROJECTILE)) {
            var sword = p.getMainHandItem();
            int kind = LightGearTree.kind(sword);
            if (kind == SPEAR && LightGearTree.perk(sword, "Charging Thrust") && p.isSprinting()) amount *= 1.5f;
            if (kind == TRIDENT && LightGearTree.perk(sword, "Tidecaller") && p.isInWaterOrRain()) amount *= 1.5f;
            if (kind != SWORD) return amount;
            if (LightGearTree.perk(sword, "Sunfire Edge")
                    && target.getType().builtInRegistryHolder().is(EntityTypeTags.UNDEAD)) amount *= 1.5f;
            if (LightGearTree.perk(sword, "Executioner") && target.getHealth() < target.getMaxHealth() / 3)
                amount *= 1.5f;
        }
        return amount;
    }

    private static boolean sweeping;

    /** After a hit landed: Sunfire Edge's flame, Radiant Sweep's wave, Thornlight's sting, Frost Arrow's chill. */
    static void afterDamage(LivingEntity hurt, DamageSource source, float taken) {
        if (taken <= 0 || sweeping || !(hurt.level() instanceof ServerLevel level)) return;
        if (source.getDirectEntity() instanceof AbstractArrow arrow
                && source.getEntity() instanceof ServerPlayer
                && hurt.isAlive()
                && LightGearTree.perk(arrow.getWeaponItem(), "Frost Arrow")) {
            hurt.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
            // The cold bites too: frostbite in three arrows, like the Sentinel's shards (owner, 2026-11-23).
            Frostbite.add(hurt, (ServerPlayer) source.getEntity(), 34);
            level.sendParticles(
                    ParticleTypes.SNOWFLAKE, hurt.getX(), hurt.getY() + 1, hurt.getZ(), 10, .3, .5, .3, .02);
        }
        if (source.getEntity() instanceof ServerPlayer p
                && source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.ThrownTrident t)
            thrownTrident(level, p, t.getWeaponItem(), hurt);
        if (source.getEntity() instanceof ServerPlayer p && source.getDirectEntity() == p)
            heavyWeapons(level, p, p.getMainHandItem(), hurt, taken);
        if (source.getEntity() instanceof ServerPlayer p
                && source.getDirectEntity() == p
                && LightGearTree.kind(p.getMainHandItem()) == SWORD) {
            var sword = p.getMainHandItem();
            if (LightGearTree.perk(sword, "Sunfire Edge") && hurt.isAlive()) hurt.igniteForSeconds(4);
            if (LightGearTree.perk(sword, "Radiant Sweep")) {
                sweeping = true;
                try {
                    Bosses.ring(level, hurt.position().add(0, .3, 0), 3, ParticleTypes.END_ROD, 24);
                    for (LivingEntity e : level.getEntitiesOfClass(
                            LivingEntity.class,
                            hurt.getBoundingBox().inflate(3),
                            e -> e != hurt && e instanceof Enemy && Spells.canHitArea(p, e))) {
                        e.damageCooldownTime = 0;
                        e.hurtServer(level, p.damageSources().playerAttack(p), taken / 2);
                    }
                } finally {
                    sweeping = false;
                }
            }
        }
        if (hurt instanceof ServerPlayer p
                && source.getEntity() instanceof LivingEntity attacker
                && attacker != p
                && attacker.distanceTo(p) < 4
                && has(p, CHEST, "Thornlight")) {
            sweeping = true;
            try {
                attacker.damageCooldownTime = 0;
                attacker.hurtServer(level, p.damageSources().thorns(p), 3);
                attacker.igniteForSeconds(3);
                level.sendParticles(
                        ParticleTypes.END_ROD,
                        attacker.getX(),
                        attacker.getY() + 1,
                        attacker.getZ(),
                        8,
                        .3,
                        .4,
                        .3,
                        .05);
            } finally {
                sweeping = false;
            }
        }
    }

    /** The trident's thrown perks: Storm Caller's lightning, Harpoon's pull. */
    private static void thrownTrident(ServerLevel level, ServerPlayer p, ItemStack trident, LivingEntity hurt) {
        if (trident == null || LightGearTree.kind(trident) != TRIDENT) return;
        if (LightGearTree.perk(trident, "Storm Caller")) {
            var bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
            if (bolt != null) {
                bolt.setPos(hurt.position());
                bolt.setVisualOnly(true); // its damage is ours; no fires round the home
                level.addFreshEntity(bolt);
            }
            if (hurt.isAlive()) {
                sweeping = true;
                try {
                    hurt.damageCooldownTime = 0;
                    hurt.hurtServer(level, level.damageSources().lightningBolt(), 5);
                } finally {
                    sweeping = false;
                }
            }
        }
        if (LightGearTree.perk(trident, "Harpoon") && hurt.isAlive() && !(hurt instanceof ServerPlayer)) {
            var pull = p.position().subtract(hurt.position());
            var v = pull.normalize().scale(Math.min(2.2, .25 + pull.length() * .09));
            hurt.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            hurt.push(v.x, Math.max(.35, v.y + .3), v.z);
        }
    }

    /** The axe's, spear's and mace's hit perks, after a melee blow landed. */
    private static void heavyWeapons(
            ServerLevel level, ServerPlayer p, ItemStack weapon, LivingEntity hurt, float taken) {
        int kind = LightGearTree.kind(weapon);
        if (kind != AXE && kind != SPEAR && kind != MACE) return;
        if (LightGearTree.perk(weapon, "Sunder") && hurt.isAlive()) {
            var armor = hurt.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
            var id = ModItems.id("sunder");
            if (armor != null && !armor.hasModifier(id)) {
                armor.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                        id, -4, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
                Scheduler.later(100, () -> armor.removeModifier(id));
                level.sendParticles(ParticleTypes.CRIT, hurt.getX(), hurt.getY() + 1, hurt.getZ(), 10, .3, .4, .3, .2);
            }
        }
        if (LightGearTree.perk(weapon, "Pinning Strike") && hurt.isAlive())
            hurt.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 2), p);
        if (LightGearTree.perk(weapon, "Concussion") && hurt.isAlive()) {
            hurt.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 3), p);
            hurt.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0), p);
            level.sendParticles(
                    ParticleTypes.WAX_OFF, hurt.getX(), hurt.getEyeY() + .3, hurt.getZ(), 6, .3, .1, .3, .05);
        }
        boolean cleave = LightGearTree.perk(weapon, "Cleave"),
                skewer = LightGearTree.perk(weapon, "Skewer"),
                shock = LightGearTree.perk(weapon, "Shockwave");
        if (!cleave && !skewer && !shock) return;
        sweeping = true;
        try {
            var look = p.getLookAngle().multiply(1, 0, 1).normalize();
            if (cleave)
                for (LivingEntity e : level.getEntitiesOfClass(
                        LivingEntity.class,
                        hurt.getBoundingBox().inflate(2.5, .5, 2.5),
                        e -> e != hurt && e instanceof Enemy && Spells.canHitArea(p, e))) {
                    var to = e.position().subtract(p.position()).multiply(1, 0, 1);
                    if (to.lengthSqr() < 1e-4 || to.normalize().dot(look) < .2) continue; // only in front
                    e.damageCooldownTime = 0;
                    e.hurtServer(level, p.damageSources().playerAttack(p), taken * .6f);
                    level.sendParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), e.getY() + 1, e.getZ(), 1, 0, 0, 0, 0);
                }
            if (skewer) {
                LivingEntity next = null;
                double best = 1e9;
                for (LivingEntity e : level.getEntitiesOfClass(
                        LivingEntity.class,
                        hurt.getBoundingBox().expandTowards(look.scale(3)).inflate(.6),
                        e -> e != hurt && e instanceof Enemy && Spells.canHitArea(p, e))) {
                    var off = e.position().subtract(hurt.position()).multiply(1, 0, 1);
                    double along = off.dot(look),
                            side = off.subtract(look.scale(along)).length();
                    if (along <= 0 || along > 3.5 || side > 1.2 || along >= best) continue;
                    best = along;
                    next = e;
                }
                if (next != null) {
                    next.damageCooldownTime = 0;
                    next.hurtServer(level, p.damageSources().playerAttack(p), taken * .75f);
                    level.sendParticles(
                            ParticleTypes.CRIT, next.getX(), next.getY() + 1, next.getZ(), 10, .2, .3, .2, .3);
                }
            }
            if (shock) {
                for (LivingEntity e : level.getEntitiesOfClass(
                        LivingEntity.class,
                        hurt.getBoundingBox().inflate(3),
                        e -> e != hurt && e instanceof Enemy && Spells.canHitArea(p, e) && e.distanceTo(hurt) <= 3)) {
                    e.damageCooldownTime = 0;
                    e.hurtServer(level, p.damageSources().playerAttack(p), 2);
                    var away = new net.minecraft.world.phys.Vec3(e.getX() - hurt.getX(), 0, e.getZ() - hurt.getZ())
                            .normalize()
                            .scale(1.1);
                    e.push(away.x, .35, away.z);
                }
                Bosses.ring(level, hurt.position().add(0, .1, 0), 3, ParticleTypes.CLOUD, 20);
                level.playSound(
                        null, hurt.blockPosition(), SoundEvents.MACE_SMASH_GROUND, SoundSource.PLAYERS, .8f, 1f);
            }
        } finally {
            sweeping = false;
        }
    }

    /** A blow the player's shield took (AFTER_DAMAGE with blocked set): Spiked Rim, Repulse, Restoring Guard. */
    static void blocked(LivingEntity hurt, DamageSource source) {
        if (sweeping || !(hurt instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level)) return;
        var shield = p.getItemBlockingWith();
        if (shield == null || LightGearTree.kind(shield) != SHIELD) return;
        if (LightGearTree.perk(shield, "Restoring Guard")) p.heal(1);
        // The Light Shield's own gift: at night a blow it takes flashes back and sets the striker alight.
        if (LightGear.ITEMS.size() > LightGear.SHIELD
                && shield.is(LightGear.ITEMS.get(LightGear.SHIELD))
                && LightDragons.night(level)
                && source.getEntity() instanceof LivingEntity striker
                && striker != p
                && striker.distanceTo(p) < 6) {
            striker.igniteForSeconds(3);
            level.sendParticles(
                    ParticleTypes.END_ROD, striker.getX(), striker.getY() + 1, striker.getZ(), 12, .3, .5, .3, .08);
        }
        if (!(source.getEntity() instanceof LivingEntity attacker)
                || attacker == p
                || source.getDirectEntity() != attacker) return;
        if (LightGearTree.perk(shield, "Spiked Rim")) {
            sweeping = true;
            try {
                attacker.damageCooldownTime = 0;
                attacker.hurtServer(level, p.damageSources().thorns(p), 4);
            } finally {
                sweeping = false;
            }
            level.sendParticles(
                    ParticleTypes.CRIT, attacker.getX(), attacker.getY() + 1, attacker.getZ(), 8, .3, .4, .3, .1);
        }
        if (LightGearTree.perk(shield, "Repulse")) {
            var away = new net.minecraft.world.phys.Vec3(attacker.getX() - p.getX(), 0, attacker.getZ() - p.getZ())
                    .normalize()
                    .scale(1.4);
            attacker.push(away.x, .4, away.z);
            if (attacker instanceof ServerPlayer other)
                other.connection.send(new ClientboundSetEntityMotionPacket(other));
            level.playSound(null, p.blockPosition(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.PLAYERS, 1f, .6f);
        }
    }

    // ---------------------------------------------------------------- the bow

    /** Marks an arrow already seen by {@link #shot}: a twin, or one reloaded with its chunk. */
    private static final String SEEN = "lightborn_shot";

    /** A player's arrow coming into the world: Twin Shot's twin and Quiver of Light's returned arrow. */
    static void shot(AbstractArrow arrow, ServerLevel level) {
        if (arrow instanceof net.minecraft.world.entity.projectile.arrow.ThrownTrident
                || arrow.entityTags().contains(SEEN)
                || !(arrow.getOwner() instanceof ServerPlayer p)) return;
        arrow.addTag(SEEN);
        var bow = arrow.getWeaponItem();
        if (bow == null || LightGearTree.kind(bow) != RANGED) return;
        if (LightGearTree.perk(bow, "Quiver of Light")
                && arrow.pickup == AbstractArrow.Pickup.ALLOWED
                && p.getRandom().nextInt(3) == 0) {
            // The arrow it would leave behind comes straight back instead.
            arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            var back = arrow.getPickupItemStackOrigin().copyWithCount(1);
            if (!p.getInventory().add(back)) p.spawnAtLocation(level, back);
        }
        if (LightGearTree.perk(bow, "Twin Shot")) {
            var twin = new net.minecraft.world.entity.projectile.arrow.Arrow(
                    level, p, new ItemStack(net.minecraft.world.item.Items.ARROW), bow);
            twin.addTag(SEEN);
            var v = arrow.getDeltaMovement();
            var side =
                    new net.minecraft.world.phys.Vec3(-v.z, 0, v.x).normalize().scale(.35);
            twin.setPos(arrow.position().add(side));
            twin.setDeltaMovement(v.add(side.scale(.04)));
            twin.setYRot(arrow.getYRot());
            twin.setXRot(arrow.getXRot());
            twin.setCritArrow(arrow.isCritArrow());
            twin.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            level.addFreshEntity(twin);
        }
    }

    // ---------------------------------------------------------------- the tool

    private static boolean veining;

    /** After a block is broken: Miner's Fervour's haste and Vein Miner's chain through the rest of an ore. */
    static void broke(
            ServerPlayer p, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        var tool = p.getMainHandItem();
        if (LightGearTree.kind(tool) == AXE
                && LightGearTree.perk(tool, "Tree Feller")
                && state.is(net.minecraft.tags.BlockTags.LOGS)) {
            chain(p, tool, pos, state, 64);
            return;
        }
        if (LightGearTree.kind(tool) != TOOL) return;
        if (LightGearTree.perk(tool, "Miner's Fervour")) p.addEffect(new MobEffectInstance(MobEffects.HASTE, 60, 1));
        if (veining
                || !LightGearTree.perk(tool, "Vein Miner")
                || !state.is(net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags.ORES)) return;
        chain(p, tool, pos, state, 8);
    }

    /** Breaks up to {@code left} more blocks of {@code state}'s kind touching the one broken (Vein Miner, Tree Feller). */
    private static void chain(
            ServerPlayer p,
            ItemStack tool,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,
            int left) {
        if (veining) return;
        veining = true;
        try {
            var queue = new java.util.ArrayDeque<net.minecraft.core.BlockPos>();
            var seen = new java.util.HashSet<net.minecraft.core.BlockPos>();
            queue.add(pos);
            seen.add(pos);
            while (!queue.isEmpty() && left > 0) {
                var at = queue.poll();
                for (var next : net.minecraft.core.BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                    var n = next.immutable();
                    if (left <= 0 || !seen.add(n) || !p.level().getBlockState(n).is(state.getBlock())) continue;
                    if (p.getMainHandItem() != tool || tool.isEmpty()) return; // the tool broke
                    if (p.gameMode.destroyBlock(n)) {
                        left--;
                        queue.add(n);
                    }
                }
            }
        } finally {
            veining = false;
        }
    }

    /** Smelting Touch: a block's drops come out as the furnace would give them. */
    static void smelt(net.minecraft.world.level.storage.loot.LootContext context, java.util.List<ItemStack> drops) {
        if (!context.hasParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE))
            return;
        var tool = context.getOptional(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL);
        if (!(tool instanceof ItemStack held)
                || LightGearTree.kind(held) != TOOL
                || !LightGearTree.perk(held, "Smelting Touch")) return;
        var level = context.getLevel();
        drops.replaceAll(stack -> {
            var input = new net.minecraft.world.item.crafting.SingleRecipeInput(stack);
            return level.recipeAccess()
                    .getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMELTING, input, level)
                    .map(r -> r.value()
                            .assemble(input)
                            .copyWithCount(r.value().assemble(input).getCount() * stack.getCount()))
                    .filter(s -> !s.isEmpty())
                    .orElse(stack);
        });
    }

    /** Second Dawn: when its last use was this long ago, a killing blow is turned aside (10 minutes, owner 2026-11-17). */
    static final int SECOND_DAWN_TICKS = 12000;

    private static final Map<UUID, Long> DAWN = new ConcurrentHashMap<>();

    /** ALLOW_DEATH: Second Dawn leaves the wearer on 4 health instead, once every 10 minutes. */
    static boolean allowDeath(LivingEntity dead, DamageSource source, float amount) {
        if (!(dead instanceof ServerPlayer p) || !has(p, CHEST, "Second Dawn")) return true;
        long now = p.level().getGameTime();
        if (now - DAWN.getOrDefault(p.getUUID(), -SECOND_DAWN_TICKS * 2L) < SECOND_DAWN_TICKS) return true;
        DAWN.put(p.getUUID(), now);
        p.setHealth(4);
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
        ServerLevel level = p.level();
        level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 60, .5, .9, .5, .15);
        level.sendParticles(
                net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF4C0),
                p.getX(),
                p.getY() + 1,
                p.getZ(),
                1,
                0,
                0,
                0,
                0);
        level.playSound(null, p.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, .8f, 1.3f);
        return false;
    }

    // ---------------------------------------------------------------- the staff, around a cast

    private static final String ELEMENT_CASTS = "staff_element_casts";

    record SpellCharge(int next, SpellElement element) {
        void commit(ItemStack staff) {
            net.minecraft.world.item.component.CustomData.update(
                    DataComponents.CUSTOM_DATA, staff, tag -> tag.putInt(ELEMENT_CASTS, next));
        }
    }

    static SpellCharge spellCharge(ItemStack staff) {
        boolean frost = LightGearTree.perk(staff, "Spellfrost");
        if (!frost && !LightGearTree.perk(staff, "Spellfire")) return null;
        int previous = staff.getOrDefault(
                        DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY)
                .copyTag()
                .getIntOr(ELEMENT_CASTS, 0);
        int next = (Math.floorMod(previous, 4) + 1) % 4;
        return new SpellCharge(next, next == 0 ? new SpellElement(frost) : null);
    }

    /** One charged cast can affect each monster it hits once, even if its damage repeats. */
    static final class SpellElement {
        final boolean frost;
        final java.util.Set<java.util.UUID> struck = new java.util.HashSet<>();

        SpellElement(boolean frost) {
            this.frost = frost;
        }
    }

    /** After a cast: true when Echoing Staff spares the tome its cooldown. */
    static boolean afterCast(ServerPlayer p) {
        return has(p, STAFF, "Echoing Staff") && p.getRandom().nextInt(8) == 0;
    }

    static void spellHit(ServerPlayer p, net.minecraft.world.entity.LivingEntity target) {
        var cast = CastContext.current();
        if (cast == null
                || cast.player() != p
                || cast.element() == null
                || !target.isAlive()
                || !(target instanceof net.minecraft.world.entity.monster.Enemy)) return;
        var element = cast.element();
        if (!element.struck.add(target.getUUID())) return;
        if (element.frost) Freeze.freeze(p.level(), p, target, 40);
        else target.igniteForSeconds(Math.max(2, target.getRemainingFireTicks() / 20f));
    }
}
