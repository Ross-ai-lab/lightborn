package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.chest;
import static lightborn.SpellKit.sound;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.AREA;
import static lightborn.SpellUpgrades.Stat.COUNT;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.SpellUpgrades.Stat.REACH;
import static lightborn.Spells.around;
import static lightborn.Spells.hit;
import static lightborn.Spells.ring;
import static lightborn.Spells.sound;

import com.mojang.math.Transformation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Brightness;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Work spells: farming, mining and the household. Trees, Reap, Felling, Herd Call, Quickening, Excavate, Ore
 * Sight, Forge Fire, Transmute, Light Bore, Wayglow, Gathering, Ender Pocket, Workbench, Skies and Owl Sight
 * (Radiant Pulse and Treasure Thread live in their own classes).
 */
public final class WorkSpells {
    private WorkSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        boolean special = SpellUpgrades.special(spell);
        switch (spell) {
            case VERDANT_SURGE -> trees(level, p, verdantRadius());
            case REAP -> reap(level, p, reapRadius());
            case HERD_CALL -> herdCall(level, p, special);
            case GATHERING -> gathering(level, p, gatherRadius());
            case FELLING -> {
                return felling(level, p, special);
            }
            case QUICKENING -> {
                return quickening(level, p);
            }
            case EXCAVATE -> {
                return excavate(level, p, excavateDepth());
            }
            case ORE_SIGHT -> {
                return oreSight(level, p, oreSightRadius(), SpellUpgrades.scaleInt(spell, DURATION, ORE_SIGHT_TICKS));
            }
            case FORGE_FIRE -> {
                return forgeFire(level, p, forgeBudget());
            }
            case TRANSMUTE -> {
                return transmute(level, p);
            }
            case LIGHT_BORE -> {
                return lightBore(level, p);
            }
            case WAYGLOW -> {
                return wayglow(level, p);
            }
            case ENDER_POCKET -> {
                return pocket(level, p);
            }
            case WORKBENCH -> {
                return Workbench.cast(level, p);
            }
            case SKIES -> {
                return skies(level, p, special);
            }
            case OWL_SIGHT -> {
                return owlSight(level, p);
            }
            case RADIANT_PULSE -> {
                return RadiantPulse.cast(p);
            }
            case TREASURE_THREAD -> {
                return TreasureThread.cast(p);
            }
            default -> throw new IllegalArgumentException(spell + " is not a work spell");
        }
        return true;
    }

    static void init() {
        PayloadTypeRegistry.clientboundPlay().register(OwlSight.TYPE, OwlSight.CODEC);
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (player instanceof ServerPlayer p
                    && state.is(GlowMote.MOTE)
                    && SpellUpgrades.perk(p, Spell.WAYGLOW, "Kindled Stride")) stride(p);
        });
    }

    static void tick(MinecraftServer server) {
        tickOwls();
        tickMotes();
    }

    static void clear() {
        OWLS.clear();
        MOTES.clear();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "owls" -> OWLS.size();
            case "motes" -> MOTES.size();
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- Gathering

    static int gatherRadius() {
        return SpellUpgrades.scaleInt(Spell.GATHERING, SpellUpgrades.Stat.AREA, 16);
    }

    /** Every dropped item within the radius lands at the caster's feet. Always casts. */
    static void gathering(ServerLevel level, ServerPlayer p, int r) {
        int n = 0;
        boolean smelt = SpellUpgrades.perk(p, Spell.GATHERING, "Smelter's Pull"),
                pack = SpellUpgrades.perk(p, Spell.GATHERING, "Pack Mule");
        AABB box = p.getBoundingBox().inflate(r);
        List<Entity> loose = new ArrayList<>(level.getEntitiesOfClass(ItemEntity.class, box));
        for (Entity e : loose) {
            if (e.distanceToSqr(p) > r * r) continue;
            pull(level, p, (ItemEntity) e, smelt, pack);
            n++;
        }
        if (SpellUpgrades.perk(p, Spell.GATHERING, "Lingering Pull"))
            for (int t = 20; t <= 600; t += 20)
                Scheduler.later(t, () -> {
                    if (p.isRemoved() || !p.isAlive() || p.level() != level) return;
                    for (ItemEntity item : level.getEntitiesOfClass(
                            ItemEntity.class, p.getBoundingBox().inflate(6)))
                        if (item.distanceToSqr(p) > 2.25) pull(level, p, item, false, false);
                });
        if (n == 0)
            p.sendSystemMessage(Component.literal("Nothing lies loose here").withStyle(ChatFormatting.GRAY), true);
        ring(level, p.position().add(0, 0.3, 0), r, ParticleTypes.REVERSE_PORTAL);
        sound(level, p, SoundEvents.ITEM_PICKUP, 1.0f, 0.6f);
        sound(level, p, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6f, 1.2f);
    }

    /**
     * Brings one dropped item to the caster along a trail of motes: smelted on the way (Smelter's Pull), and into the
     * pack (Pack Mule) when there is room.
     */
    private static void pull(ServerLevel level, ServerPlayer p, ItemEntity e, boolean smelt, boolean pack) {
        Vec3 from = e.position();
        for (int k = 1; k < 6; k++) {
            Vec3 at = from.lerp(p.position(), k / 6.0);
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 0.3, at.z, 1, 0, 0, 0, 0);
        }
        if (smelt) {
            var done = WorkSpells.smelted(level, e.getItem());
            if (!done.isEmpty()) e.setItem(done);
        }
        if (pack) {
            var stack = e.getItem().copy();
            p.getInventory().add(stack);
            if (stack.isEmpty()) {
                e.discard();
                return;
            }
            e.setItem(stack);
        }
        e.teleportTo(p.getX(), p.getY() + 0.2, p.getZ());
        e.setDeltaMovement(Vec3.ZERO);
    }

    // ---------------------------------------------------------------- Skies

    /**
     * Turns the weather: rain (or a storm) clears for 10 minutes, a clear sky brings 5 minutes of rain; with Stormcaller, that
     * rain comes as a thunderstorm. Only under an open sky (not in the Nether or the End).
     */
    static boolean skies(ServerLevel level, ServerPlayer p, boolean storm) {
        if (!level.dimensionType().hasSkyLight()
                || level.dimensionType().hasCeiling()
                || level.dimension() != Level.OVERWORLD) {
            p.sendSystemMessage(
                    Component.literal("There is no sky here to turn").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        var server = level.getServer();
        double longer = Math.max(.1, 1 + SpellUpgrades.sum(Spell.SKIES, SpellUpgrades.Stat.DURATION));
        if (level.getWeatherData().isRaining()) {
            server.setWeatherParameters((int) (12000 * longer), 0, false, false);
            p.sendSystemMessage(Component.literal("The sky clears").withStyle(ChatFormatting.GOLD), true);
            level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 2, p.getZ(), 60, 1.5, 3, 1.5, 0.15);
            sound(level, p, SoundEvents.BEACON_ACTIVATE, 1.0f, 1.4f);
        } else {
            server.setWeatherParameters(0, (int) (6000 * longer), true, storm);
            p.sendSystemMessage(
                    Component.literal(storm ? "A storm gathers" : "Rain gathers")
                            .withStyle(ChatFormatting.AQUA),
                    true);
            level.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 3, p.getZ(), 80, 2, 1.5, 2, 0.05);
            level.sendParticles(ParticleTypes.FALLING_WATER, p.getX(), p.getY() + 3, p.getZ(), 60, 2, 0.5, 2, 0);
            sound(level, p, SoundEvents.WEATHER_RAIN_ABOVE, 1.0f, 0.8f);
            if (storm) sound(level, p, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.6f, 1.0f);
        }
        if (SpellUpgrades.perk(p, Spell.SKIES, "Sunblessed")) {
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, 2400, 0, false, false, true));
            p.addEffect(new MobEffectInstance(MobEffects.HASTE, 2400, 0, false, false, true));
        }
        if (SpellUpgrades.perk(p, Spell.SKIES, "Harvest Rain"))
            for (int t = 0; t <= 1200; t += 200)
                Scheduler.later(t, () -> {
                    if (p.isAlive() && p.level() == level) WorkSpells.growStage(level, p, 12);
                });
        if (SpellUpgrades.perk(p, Spell.SKIES, "Storm Lord"))
            for (int t = 20; t <= 600; t += 100)
                Scheduler.later(t, () -> {
                    if (!p.isAlive() || p.level() != level) return;
                    var near = Perks.nearestMonsters(p, null, p.position(), 16, 1);
                    if (near.isEmpty()) return;
                    LivingEntity e = near.getFirst();
                    var bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(
                            level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
                    if (bolt != null) {
                        bolt.setVisualOnly(true);
                        bolt.setPos(e.getX(), e.getY(), e.getZ());
                        level.addFreshEntity(bolt);
                    }
                    Spells.hit(p, e, Perks.flat(p, 16), Spell.SKIES);
                    e.igniteForSeconds(3);
                });
        return true;
    }

    // ---------------------------------------------------------------- Trees

    static int verdantRadius() {
        return SpellUpgrades.scaleInt(Spell.VERDANT_SURGE, SpellUpgrades.Stat.AREA, 15);
    }

    /** Plants a farmer tends: crops, stems, saplings, berries, cocoa, bamboo. Never grass, moss or nylium (they spread). */
    private static boolean isPlant(BlockState state) {
        Block b = state.getBlock();
        return state.is(BlockTags.CROPS)
                || state.is(BlockTags.SAPLINGS)
                || b instanceof SweetBerryBushBlock
                || b instanceof CocoaBlock
                || b instanceof BambooStalkBlock
                || b instanceof BambooSaplingBlock;
    }

    /**
     * Trees: one bone-meal pulse on every plant around (always succeeds, unlike a sapling's 45%), nether wart gains a
     * stage, and a sapling keeps growing until it is a full tree. Always casts, showing its reach as a green ring.
     */
    static void trees(ServerLevel level, ServerPlayer p, int r) {
        int grown = 0;
        boolean ripen = SpellUpgrades.perk(p, Spell.VERDANT_SURGE, "Full Ripeness"),
                sow = SpellUpgrades.perk(p, Spell.VERDANT_SURGE, "Sower"),
                flowers = SpellUpgrades.perk(p, Spell.VERDANT_SURGE, "Wildflowers");
        int bloomed = 0;
        for (BlockPos pos : around(p, r)) {
            if (!level.mayInteract(p, pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (sow
                    && state.is(Blocks.FARMLAND)
                    && level.getBlockState(pos.above()).isAir()) {
                BlockState crop = seedFromPack(p);
                if (crop != null) {
                    level.setBlock(pos.above(), crop, Block.UPDATE_ALL);
                    level.sendParticles(
                            ParticleTypes.HAPPY_VILLAGER,
                            pos.getX() + .5,
                            pos.getY() + 1.3,
                            pos.getZ() + .5,
                            3,
                            .2,
                            .1,
                            .2,
                            0);
                    grown++;
                }
                continue;
            }
            if (flowers
                    && bloomed < WILDFLOWERS
                    && state.is(Blocks.GRASS_BLOCK)
                    && level.getBlockState(pos.above()).isAir()
                    && level.getRandom().nextInt(3) == 0) {
                level.setBlock(pos.above(), wildflower(level), Block.UPDATE_ALL);
                level.sendParticles(
                        ParticleTypes.CHERRY_LEAVES,
                        pos.getX() + .5,
                        pos.getY() + 1.4,
                        pos.getZ() + .5,
                        2,
                        .2,
                        .2,
                        .2,
                        0);
                bloomed++;
                continue;
            }
            if (ripen && state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                if (SeasonCompat.allows(level, pos, state)) {
                    level.setBlock(pos, crop.getStateForAge(crop.getMaxAge()), Block.UPDATE_CLIENTS);
                    grown++;
                    level.sendParticles(
                            ParticleTypes.HAPPY_VILLAGER,
                            pos.getX() + .5,
                            pos.getY() + .5,
                            pos.getZ() + .5,
                            6,
                            .3,
                            .3,
                            .3,
                            0);
                }
                continue;
            }
            if (state.getBlock() instanceof NetherWartBlock) {
                int age = state.getValue(NetherWartBlock.AGE);
                if (age >= NetherWartBlock.MAX_AGE) continue;
                level.setBlock(pos, state.setValue(NetherWartBlock.AGE, age + 1), Block.UPDATE_CLIENTS);
            } else if (isPlant(state)
                    && state.getBlock() instanceof BonemealableBlock meal
                    && meal.isValidBonemealTarget(level, pos, state, BonemealSource.INTERACTION)) {
                if (SeasonCompat.allows(level, pos, state))
                    meal.performBonemeal(level, level.getRandom(), pos, state, BonemealSource.INTERACTION);
                // A sapling's first pulse only readies it; keep pulsing (a few times at most) until the tree stands.
                for (int i = 0; i < 4 && state.is(BlockTags.SAPLINGS); i++) {
                    BlockState now = level.getBlockState(pos);
                    if (!now.is(BlockTags.SAPLINGS)
                            || !(now.getBlock() instanceof BonemealableBlock m)
                            || !m.isValidBonemealTarget(level, pos, now, BonemealSource.INTERACTION)) break;
                    if (SeasonCompat.allows(level, pos, now))
                        m.performBonemeal(level, level.getRandom(), pos, now, BonemealSource.INTERACTION);
                }
            } else continue;
            grown++;
            level.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    6,
                    0.3,
                    0.3,
                    0.3,
                    0);
        }
        if (grown == 0 && bloomed == 0)
            p.sendSystemMessage(Component.literal("Nothing here can grow").withStyle(ChatFormatting.GRAY), true);
        sound(level, p, SoundEvents.BONE_MEAL_USE, 1.2f, 0.8f);
        sound(level, p, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6f, 0.7f);
        ring(level, p.position().add(0, 0.3, 0), r, ParticleTypes.HAPPY_VILLAGER);
        ring(level, p.position().add(0, 0.3, 0), r * 0.5, ParticleTypes.COMPOSTER);
        level.sendParticles(ParticleTypes.COMPOSTER, p.getX(), p.getY() + 0.3, p.getZ(), 60, r * 0.4, 0.2, r * 0.4, 0);
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, p.getX(), p.getY() + 2.5, p.getZ(), 30, 1.5, 0.8, 1.5, 0);
    }

    /** Harvest Rain: every growing crop and wart within {@code r} grows one stage, quietly. */
    static void growStage(ServerLevel level, ServerPlayer p, int r) {
        for (BlockPos pos : around(p, r)) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                if (!SeasonCompat.allows(level, pos, state)) continue;
                level.setBlock(pos, crop.getStateForAge(crop.getAge(state) + 1), Block.UPDATE_CLIENTS);
            } else if (state.getBlock() instanceof NetherWartBlock
                    && state.getValue(NetherWartBlock.AGE) < NetherWartBlock.MAX_AGE) {
                level.setBlock(
                        pos,
                        state.setValue(NetherWartBlock.AGE, state.getValue(NetherWartBlock.AGE) + 1),
                        Block.UPDATE_CLIENTS);
            } else continue;
            level.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 3, .3, .3, .3, 0);
        }
    }

    /** Wildflowers: at most this many bloom in one cast, so a meadow stays a meadow. */
    static final int WILDFLOWERS = 40;

    private static final List<Block> FLOWERS = List.of(
            Blocks.SHORT_GRASS,
            Blocks.SHORT_GRASS,
            Blocks.POPPY,
            Blocks.DANDELION,
            Blocks.CORNFLOWER,
            Blocks.OXEYE_DAISY,
            Blocks.AZURE_BLUET,
            Blocks.ALLIUM);

    private static BlockState wildflower(ServerLevel level) {
        return FLOWERS.get(level.getRandom().nextInt(FLOWERS.size())).defaultBlockState();
    }

    /** Sower: takes one seed from the pack and gives the crop it grows, or null when the pack has none. */
    private static BlockState seedFromPack(ServerPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Block crop = s.is(Items.WHEAT_SEEDS)
                    ? Blocks.WHEAT
                    : s.is(Items.CARROT)
                            ? Blocks.CARROTS
                            : s.is(Items.POTATO)
                                    ? Blocks.POTATOES
                                    : s.is(Items.BEETROOT_SEEDS) ? Blocks.BEETROOTS : null;
            if (crop == null) continue;
            s.shrink(1);
            return crop.defaultBlockState();
        }
        return null;
    }

    // ---------------------------------------------------------------- Reap

    static int reapRadius() {
        return SpellUpgrades.scaleInt(Spell.REAP, SpellUpgrades.Stat.AREA, 8);
    }

    /**
     * Harvests every ripe crop around and replants it: the drops (minus the one seed it replants) land at the
     * caster's feet. Ripe = crop at max age, nether wart 3, cocoa 2, sweet berries 2 or more.
     */
    static void reap(ServerLevel level, ServerPlayer p, int r) {
        List<ItemStack> haul = new ArrayList<>();
        int reaped = 0;
        boolean rich = SpellUpgrades.perk(p, Spell.REAP, "Rich Harvest"),
                pack = SpellUpgrades.perk(p, Spell.REAP, "Straight to the Pack"),
                second = SpellUpgrades.perk(p, Spell.REAP, "Second Crop");
        for (BlockPos pos : around(p, r)) {
            if (!level.mayInteract(p, pos)) continue;
            BlockState state = level.getBlockState(pos);
            Block b = state.getBlock();
            BlockState replant;
            if (b instanceof CropBlock crop && crop.isMaxAge(state))
                replant = crop.getStateForAge(second ? crop.getMaxAge() / 2 : 0);
            else if (b instanceof NetherWartBlock && state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE)
                replant = state.setValue(NetherWartBlock.AGE, 0);
            else if (b instanceof CocoaBlock && state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE)
                replant = state.setValue(CocoaBlock.AGE, 0);
            else if (b instanceof SweetBerryBushBlock && state.getValue(SweetBerryBushBlock.AGE) >= 2)
                replant = state.setValue(SweetBerryBushBlock.AGE, 1);
            else continue;
            int before = haul.size();
            if (b instanceof SweetBerryBushBlock) {
                // Picking a bush (not breaking it) drops 1-2 berries, one more when fully grown, like a right-click.
                int n = 1 + level.getRandom().nextInt(2) + (state.getValue(SweetBerryBushBlock.AGE) == 3 ? 1 : 0);
                haul.add(new ItemStack(Items.SWEET_BERRIES, n));
            } else {
                boolean seedKept = false;
                for (ItemStack drop : Block.getDrops(state, level, pos, null, p, ItemStack.EMPTY)) {
                    if (!seedKept && drop.is(b.asItem())) {
                        drop.shrink(1);
                        seedKept = true;
                    }
                    if (!drop.isEmpty()) haul.add(drop);
                }
            }
            if (rich && level.getRandom().nextInt(3) == 0) // Rich Harvest: this crop gives its drops again
            for (int k = before, end = haul.size(); k < end; k++)
                    haul.add(haul.get(k).copy());
            level.levelEvent(2001, pos, Block.getId(state)); // break particles and sound, as if broken
            level.setBlock(pos, replant, Block.UPDATE_CLIENTS);
            reaped++;
        }
        if (reaped == 0)
            p.sendSystemMessage(Component.literal("Nothing here is ripe").withStyle(ChatFormatting.GRAY), true);
        for (ItemStack stack : merge(haul)) {
            if (pack) p.getInventory().add(stack);
            if (!stack.isEmpty()) Block.popResource(level, p.blockPosition(), stack);
        }
        sound(level, p, SoundEvents.CROP_BREAK, 1.2f, 0.9f);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6f, 0.7f);
        level.sendParticles(
                ParticleTypes.SWEEP_ATTACK, p.getX(), p.getY() + 0.4, p.getZ(), 6, r * 0.4, 0.1, r * 0.4, 0);
        ring(level, p.position().add(0, 0.3, 0), r, ParticleTypes.CRIT);
    }

    // ---------------------------------------------------------------- Felling

    /** The axe Felling breaks logs with, made on first use: an ItemStack cannot be made before items are bound. */
    private static ItemStack fellingTool;

    private static ItemStack fellingTool() {
        if (fellingTool == null) fellingTool = new ItemStack(Items.IRON_AXE);
        return fellingTool;
    }

    private static final int FELL_MAX_LOGS = 256, FELL_MAX_LEAVES = 1200;

    /**
     * Fells the tree looked at (6 blocks reach): every log touching it (diagonals too, up to 256) breaks and drops at
     * the caster's feet as an iron axe would. Only a living tree: the logs must touch natural leaves, so a log
     * cabin wall is never felled. Upgraded, the tree's leaves fall too, with their saplings, sticks and apples.
     */
    static boolean felling(ServerLevel level, ServerPlayer p, boolean leavesToo) {
        if (!p.mayBuild()) return false;
        Vec3 eye = p.getEyePosition();
        int reach = SpellUpgrades.scaleInt(Spell.FELLING, SpellUpgrades.Stat.REACH, 6);
        BlockHitResult hr = level.clip(new ClipContext(
                eye, eye.add(p.getLookAngle().scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (hr.getType() != HitResult.Type.BLOCK
                || !level.getBlockState(hr.getBlockPos()).is(BlockTags.LOGS)) {
            p.sendSystemMessage(
                    Component.literal("Look at a tree trunk within " + reach + " blocks")
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return false;
        }
        int felled = fell(level, p, hr.getBlockPos(), leavesToo);
        if (felled == 0) {
            p.sendSystemMessage(Component.literal("That is not a living tree").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        // Forest Sweep: up to four more living trees standing within 5 blocks of the first.
        if (SpellUpgrades.perk(p, Spell.FELLING, "Forest Sweep")) {
            BlockPos at = hr.getBlockPos();
            int more = 0;
            for (BlockPos pos : BlockPos.betweenClosed(at.offset(-5, -3, -5), at.offset(5, 3, 5))) {
                if (more >= 4) break;
                if (!level.getBlockState(pos).is(BlockTags.LOGS) || !level.mayInteract(p, pos)) continue;
                int n = fell(level, p, pos.immutable(), leavesToo);
                if (n > 0) {
                    felled += n;
                    more++;
                }
            }
        }
        level.playSound(null, hr.getBlockPos(), SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.4f, 0.6f);
        level.playSound(null, hr.getBlockPos(), SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.BLOCKS, 0.5f, 0.8f);
        sound(level, p, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6f, 0.6f);
        p.sendSystemMessage(Component.literal("Felled " + felled + " logs").withStyle(ChatFormatting.GOLD), true);
        return true;
    }

    /** Fells the living tree whose trunk holds {@code start}: its logs drop at the caster's feet. Logs felled, or 0. */
    private static int fell(ServerLevel level, ServerPlayer p, BlockPos start, boolean leavesToo) {
        Set<BlockPos> logs = new LinkedHashSet<>();
        Set<BlockPos> leaves = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(start));
        logs.add(start);
        while (!queue.isEmpty() && logs.size() < FELL_MAX_LOGS) {
            BlockPos at = queue.poll();
            for (BlockPos n : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                BlockState s = level.getBlockState(n);
                if (s.is(BlockTags.LOGS)) {
                    if (logs.size() < FELL_MAX_LOGS && level.mayInteract(p, n) && logs.add(n.immutable()))
                        queue.add(n.immutable());
                } else if (naturalLeaves(s)) {
                    leaves.add(n.immutable());
                }
            }
        }
        if (leaves.size() < 3) return 0;
        List<ItemStack> haul = new ArrayList<>();
        int i = 0;
        boolean charcoal = SpellUpgrades.perk(p, Spell.FELLING, "Charcoal Kiln");
        BlockPos base = logs.stream()
                .min(java.util.Comparator.comparingInt(BlockPos::getY))
                .orElse(start);
        Block sapling = sapling(level.getBlockState(base));
        for (BlockPos pos : logs) {
            BlockState state = level.getBlockState(pos);
            if (charcoal && i % 4 == 3) haul.add(new ItemStack(Items.CHARCOAL));
            else haul.addAll(Block.getDrops(state, level, pos, level.getBlockEntity(pos), p, fellingTool()));
            if (i++ % 3 == 0) level.levelEvent(2001, pos, Block.getId(state));
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (leavesToo) {
            // Leaves hang up to 6 blocks from a log; walk out from the ones the trunk touches.
            ArrayDeque<BlockPos> lq = new ArrayDeque<>(leaves);
            Set<BlockPos> seen = new HashSet<>(leaves);
            int fallen = 0;
            while (!lq.isEmpty() && fallen < FELL_MAX_LEAVES) {
                BlockPos pos = lq.poll();
                BlockState state = level.getBlockState(pos);
                if (!naturalLeaves(state) || !level.mayInteract(p, pos)) continue;
                haul.addAll(Block.getDrops(state, level, pos, null, p, ItemStack.EMPTY));
                if (fallen++ % 6 == 0) level.levelEvent(2001, pos, Block.getId(state));
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                for (Direction d : Direction.values()) {
                    BlockPos n = pos.relative(d);
                    if (seen.add(n) && naturalLeaves(level.getBlockState(n))) lq.add(n);
                }
            }
        }
        for (ItemStack stack : merge(haul)) Block.popResource(level, p.blockPosition(), stack);
        // Replant: a sapling of the same tree where the trunk stood, on soil that can hold it.
        if (sapling != null && SpellUpgrades.perk(p, Spell.FELLING, "Replant")) {
            BlockState young = sapling.defaultBlockState();
            if (level.getBlockState(base).isAir() && young.canSurvive(level, base)) {
                level.setBlock(base, young, Block.UPDATE_ALL);
                level.sendParticles(
                        ParticleTypes.HAPPY_VILLAGER,
                        base.getX() + .5,
                        base.getY() + .5,
                        base.getZ() + .5,
                        8,
                        .3,
                        .3,
                        .3,
                        0);
            }
        }
        return logs.size();
    }

    /** The sapling a log's tree grows from ({@code oak_log} to {@code oak_sapling}), or null for one with none. */
    private static Block sapling(BlockState log) {
        String id = BuiltInRegistries.BLOCK.getKey(log.getBlock()).toString();
        if (!id.endsWith("_log") && !id.endsWith("_stem")) return null;
        String name = id.substring(0, id.lastIndexOf('_'))
                + (id.contains("crimson") || id.contains("warped") ? "_fungus" : "_sapling");
        if (id.contains("mangrove")) name = "minecraft:mangrove_propagule";
        var key = net.minecraft.resources.Identifier.parse(name);
        return BuiltInRegistries.BLOCK.containsKey(key) ? BuiltInRegistries.BLOCK.getValue(key) : null;
    }

    private static boolean naturalLeaves(BlockState s) {
        return s.is(BlockTags.LEAVES)
                && s.hasProperty(BlockStateProperties.PERSISTENT)
                && !s.getValue(BlockStateProperties.PERSISTENT);
    }

    /** Same items folded into full stacks, so a felled tree drops a few stacks instead of hundreds of single logs. */
    static List<ItemStack> merge(List<ItemStack> drops) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack d : drops) {
            ItemStack left = d.copy();
            for (ItemStack o : out) {
                if (left.isEmpty()) break;
                if (ItemStack.isSameItemSameComponents(o, left) && o.getCount() < o.getMaxStackSize()) {
                    int move = Math.min(left.getCount(), o.getMaxStackSize() - o.getCount());
                    o.grow(move);
                    left.shrink(move);
                }
            }
            if (!left.isEmpty()) out.add(left);
        }
        return out;
    }

    // ---------------------------------------------------------------- Herd Call

    static final int HERD_RADIUS = 10;

    /** Every grown animal within 10 blocks falls in love, as if fed; with Shepherd, babies grow up on the spot. Always casts. */
    static void herdCall(ServerLevel level, ServerPlayer p, boolean growBabies) {
        int loved = 0;
        int r = SpellUpgrades.scaleInt(Spell.HERD_CALL, SpellUpgrades.Stat.AREA, HERD_RADIUS);
        boolean shear = SpellUpgrades.perk(p, Spell.HERD_CALL, "Shearer"),
                eggs = SpellUpgrades.perk(p, Spell.HERD_CALL, "Henhouse");
        if (SpellUpgrades.perk(p, Spell.HERD_CALL, "Herd Lure"))
            for (Animal a : level.getEntitiesOfClass(
                    Animal.class, p.getBoundingBox().inflate(32), a -> a.isAlive() && a.distanceTo(p) > 3))
                a.getNavigation().moveTo(p, 1.2);
        for (Animal a : level.getEntitiesOfClass(
                Animal.class, p.getBoundingBox().inflate(r), a -> a.isAlive() && a.distanceToSqr(p) <= r * r)) {
            if (shear && a instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep && sheep.readyForShearing())
                sheep.shear(level, SoundSource.PLAYERS, ItemStack.EMPTY);
            if (eggs && a instanceof net.minecraft.world.entity.animal.chicken.Chicken && !a.isBaby())
                a.spawnAtLocation(level, new ItemStack(Items.EGG));
            if (!a.isBaby() && a.getAge() == 0 && a.canFallInLove()) {
                a.setInLove(p);
                loved++;
            } else if (a.isBaby() && growBabies) {
                a.setAge(0);
                level.sendParticles(
                        ParticleTypes.HAPPY_VILLAGER,
                        a.getX(),
                        a.getY() + a.getBbHeight(),
                        a.getZ(),
                        8,
                        0.3,
                        0.3,
                        0.3,
                        0);
            }
        }
        if (loved == 0)
            p.sendSystemMessage(
                    Component.literal("No animal here is ready to breed").withStyle(ChatFormatting.GRAY), true);
        ring(level, p.position().add(0, 0.3, 0), r, ParticleTypes.HEART);
        sound(level, p, SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f, 1.2f);
        sound(level, p, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6f, 1.6f);
    }

    // ---------------------------------------------------------------- Excavate

    static int excavateDepth() {
        return SpellUpgrades.scaleInt(Spell.EXCAVATE, SpellUpgrades.Stat.REACH, 3);
    }

    /**
     * The pickaxe Excavate mines with: drops and which blocks it may break follow an iron pickaxe. Made on first use:
     * this class loads at start-up, before an ItemStack can be made.
     */
    private static ItemStack excavateTool;

    private static ItemStack excavateTool() {
        if (excavateTool == null) excavateTool = new ItemStack(Items.IRON_PICKAXE);
        return excavateTool;
    }

    /**
     * Breaks a 3 by 3 face, {@code depth} deep (3, more with its upgrades), into the block looked at (6 blocks reach). Skips what an iron pickaxe could not
     * harvest, very hard blocks (obsidian, ancient debris), fluids and anything holding a block entity (chests, spawners).
     * Drops land at the caster's feet.
     */
    static boolean excavate(ServerLevel level, ServerPlayer p, int depth) {
        if (!p.mayBuild()) return false;
        Vec3 eye = p.getEyePosition();
        BlockHitResult hr = level.clip(new ClipContext(
                eye, eye.add(p.getLookAngle().scale(6)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (hr.getType() != HitResult.Type.BLOCK) {
            p.sendSystemMessage(
                    Component.literal("Look at a block within 6 blocks").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        Direction in = hr.getDirection().getOpposite();
        Direction a = in.getAxis() == Direction.Axis.Y
                ? Direction.EAST
                : in.getAxis() == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
        Direction b = in.getAxis() == Direction.Axis.Y ? Direction.SOUTH : Direction.UP;
        List<ItemStack> haul = new ArrayList<>();
        int broken = 0;
        int face = SpellUpgrades.perk(p, Spell.EXCAVATE, "Wide Bore") ? 2 : 1;
        boolean vein = SpellUpgrades.perk(p, Spell.EXCAVATE, "Vein Follower");
        List<BlockPos> ores = new ArrayList<>();
        List<BlockState> oreKinds = new ArrayList<>();
        for (int d = 0; d < depth; d++)
            for (int u = -face; u <= face; u++)
                for (int v = -face; v <= face; v++) {
                    BlockPos pos =
                            hr.getBlockPos().relative(in, d).relative(a, u).relative(b, v);
                    BlockState state = level.getBlockState(pos);
                    if (vein && state.is(ConventionalBlockTags.ORES)) {
                        ores.add(pos.immutable());
                        oreKinds.add(state);
                    }
                    if (dig(level, p, pos, haul)) broken++;
                }
        // Vein Follower: the rest of each ore's vein, the same ore touching it, up to 32 blocks in all.
        if (vein) {
            int left = VEIN_MAX;
            for (int o = 0; o < ores.size(); o++) {
                BlockPos start = ores.get(o);
                BlockState ore = oreKinds.get(o); // the dug block is air now: its kind came from the scan
                ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(start));
                Set<BlockPos> seen = new HashSet<>(queue);
                while (!queue.isEmpty() && left > 0) {
                    BlockPos at = queue.poll();
                    for (BlockPos n : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                        BlockPos next = n.immutable();
                        if (!seen.add(next) || !level.getBlockState(next).is(ore.getBlock())) continue;
                        if (dig(level, p, next, haul)) {
                            broken++;
                            left--;
                            queue.add(next);
                        }
                    }
                }
            }
        }
        if (broken == 0) {
            p.sendSystemMessage(Component.literal("Too hard to excavate").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        boolean smelt = SpellUpgrades.perk(p, Spell.EXCAVATE, "Smelting Bore");
        for (ItemStack stack : WorkSpells.merge(haul)) {
            ItemStack done = smelt ? smelted(level, stack, true) : ItemStack.EMPTY;
            Block.popResource(level, p.blockPosition(), done.isEmpty() ? stack : done);
        }
        Vec3 hit = hr.getLocation();
        level.sendParticles(ParticleTypes.EXPLOSION, hit.x, hit.y, hit.z, 1, 0, 0, 0, 0);
        level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 0.5f, 1.5f);
        sound(level, p, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6f, 0.5f);
        return true;
    }

    /** Vein Follower: blocks of vein it follows past the bore, per cast. */
    static final int VEIN_MAX = 32;

    /** Breaks one block as Excavate's iron pickaxe would, its drops into {@code haul}; false when it may not. */
    static boolean dig(ServerLevel level, ServerPlayer p, BlockPos pos, List<ItemStack> haul) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null) return false;
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0 || hardness >= 30 || !level.mayInteract(p, pos)) return false;
        if (state.requiresCorrectToolForDrops() && !excavateTool().isCorrectToolForDrops(state)) return false;
        haul.addAll(Block.getDrops(state, level, pos, null, p, excavateTool()));
        state.spawnAfterBreak(level, pos, excavateTool(), true);
        level.destroyBlock(pos, false, p);
        OreTiers.mined(p, state);
        return true;
    }

    // ---------------------------------------------------------------- Ore Sight

    static int oreSightRadius() {
        return SpellUpgrades.scaleInt(Spell.ORE_SIGHT, SpellUpgrades.Stat.AREA, 15);
    }

    /** Ten minutes, the owner's number; the glow also goes out the moment its ore is mined. */
    static final int ORE_SIGHT_TICKS = 12000;

    /** Marks an ore glow, so one saved into a chunk is removed when it loads again. */
    static final String ORE_TAG = "lightborn_ore_glow";
    /** Live glows, oldest first, with where they sit, what ore they mark and when they go out. */
    static final LinkedHashMap<Display.BlockDisplay, Glow> GLOWS = new LinkedHashMap<>();

    private record Glow(ServerLevel level, BlockPos pos, BlockState ore, long until) {}

    private static final int ORE_SIGHT_MAX = 120;
    /** All players' glows together; past this the oldest go out first (each is a glowing entity the client draws). */
    private static final int GLOWS_MAX = 400;

    /** Once a second: a glow goes out when its time is up or its ore is gone (mined, exploded). */
    static void tickGlows() {
        if (Scheduler.now() % 20 != 0 || GLOWS.isEmpty()) return;
        var it = GLOWS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Glow g = e.getValue();
            boolean gone = e.getKey().isRemoved()
                    || g.level().getGameTime() >= g.until()
                    || g.level().isLoaded(g.pos())
                            && !g.level().getBlockState(g.pos()).is(g.ore().getBlock());
            if (gone) {
                e.getKey().discard();
                it.remove();
            }
        }
    }

    static boolean glowing(ServerLevel level, BlockPos pos) {
        for (Glow g : GLOWS.values()) if (g.level() == level && g.pos().equals(pos)) return true;
        return false;
    }

    /**
     * Lights the nearest ores (up to 120) within {@code r} for {@code ticks}: a glowing copy of each ore block sits over it, and
     * the glow outline shows through stone in the ore's own colour. Refuses when there is no ore in range.
     */
    static boolean oreSight(ServerLevel level, ServerPlayer p, int r, int ticks) {
        BlockPos c = p.blockPosition();
        List<BlockPos> ores = new ArrayList<>();
        boolean treasure = SpellUpgrades.perk(p, Spell.ORE_SIGHT, "Treasure Sense");
        int treasures = 0;
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
            if (pos.distSqr(c) > r * r) continue;
            BlockState state = level.getBlockState(pos);
            if (state.is(ConventionalBlockTags.ORES) || state.is(Blocks.ANCIENT_DEBRIS)) ores.add(pos.immutable());
            else if (treasure) {
                // Treasure Sense: unopened loot gold, spawners red, budding amethyst purple.
                int colour = state.is(Blocks.SPAWNER)
                        ? 0xFF3030
                        : state.is(Blocks.BUDDING_AMETHYST)
                                ? 0xB060FF
                                : state.hasBlockEntity()
                                                && level.getBlockEntity(pos)
                                                        instanceof
                                                        net.minecraft.world.level.block.entity
                                                                        .RandomizableContainerBlockEntity
                                                                box
                                                && box.getLootTable() != null
                                        ? TreasureThread.GOLD_GLOW
                                        : 0;
                if (colour != 0) {
                    glow(level, pos.immutable(), state, colour, ticks);
                    treasures++;
                }
            }
        }
        if (SpellUpgrades.perk(p, Spell.ORE_SIGHT, "Miner's Haste"))
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.HASTE, 1200, 1, false, false, true));
        if (ores.isEmpty() && treasures > 0) {
            p.sendSystemMessage(
                    Component.literal("No ore, but " + treasures + " treasures within " + r + " blocks")
                            .withStyle(ChatFormatting.GOLD),
                    true);
            return true;
        }
        if (ores.isEmpty()) {
            p.sendSystemMessage(
                    Component.literal("No ore within " + r + " blocks").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        ores.sort(Comparator.comparingDouble(pos -> pos.distSqr(c)));
        for (BlockPos pos : ores.subList(0, Math.min(ORE_SIGHT_MAX, ores.size()))) {
            BlockState state = level.getBlockState(pos);
            glow(level, pos, state, oreColour(state), ticks);
        }
        sound(level, p, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0f, 0.5f);
        sound(level, p, SoundEvents.BEACON_POWER_SELECT, 0.4f, 0.6f);
        level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 30, 0.2, 0.4, 0.2, 0.25);
        p.sendSystemMessage(
                Component.literal(ores.size() + " ore blocks within " + r + " blocks")
                        .withStyle(ChatFormatting.GOLD),
                true);
        if (SpellUpgrades.perk(p, Spell.ORE_SIGHT, "Ore Ledger")) {
            var tally = new java.util.TreeMap<String, Integer>();
            for (BlockPos pos : ores)
                tally.merge(level.getBlockState(pos).getBlock().getName().getString(), 1, Integer::sum);
            var line = new StringBuilder("Ore within " + r + " blocks: ");
            tally.forEach((name, n) -> line.append(n).append(" ").append(name).append(", "));
            p.sendSystemMessage(
                    Component.literal(line.substring(0, line.length() - 2)).withStyle(ChatFormatting.GOLD));
        }
        return true;
    }

    /**
     * One x-ray glow: a glowing copy of the block sits over it, its outline showing through stone in {@code colour},
     * for {@code ticks} or until the block is gone. Ore Sight and the Treasure Thread both use it. A block drawn by its
     * block entity (a chest) has no model a display can show, so a glass shell the chest's size carries its glow.
     */
    static void glow(ServerLevel level, BlockPos pos, BlockState state, int colour, int ticks) {
        if (glowing(level, pos)) return;
        Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (d == null) return;
        boolean model = state.getRenderShape() == RenderShape.MODEL;
        d.setBlockState(model ? state : Blocks.GLASS.defaultBlockState());
        d.setBrightnessOverride(new Brightness(15, 15));
        d.setGlowingTag(true);
        d.setGlowColorOverride(colour);
        // A hair larger than the block so it never flickers against the real one.
        d.setTransformation(
                model
                        ? new Transformation(
                                new Vector3f(-0.01f), new Quaternionf(), new Vector3f(1.02f), new Quaternionf())
                        : new Transformation(
                                new Vector3f(.055f, -.005f, .055f),
                                new Quaternionf(),
                                new Vector3f(.89f, .885f, .89f),
                                new Quaternionf()));
        d.setPos(pos.getX(), pos.getY(), pos.getZ());
        d.addTag(ORE_TAG);
        GLOWS.put(d, new Glow(level, pos, state, level.getGameTime() + ticks));
        level.addFreshEntity(d);
        while (GLOWS.size() > GLOWS_MAX) {
            var oldest = GLOWS.keySet().iterator().next();
            oldest.discard();
            GLOWS.remove(oldest);
        }
    }

    /** A guide's arrival can extend an existing Treasure Thread marker on the same block. */
    static void highlight(ServerLevel level, BlockPos pos, BlockState state, int colour, int ticks) {
        var iterator = GLOWS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Glow old = entry.getValue();
            if (old.level() != level || !old.pos().equals(pos)) continue;
            if (entry.getKey().isRemoved() || !state.is(old.ore().getBlock())) {
                entry.getKey().discard();
                iterator.remove();
                break;
            }
            entry.getKey().setGlowColorOverride(colour);
            entry.setValue(new Glow(level, pos, state, Math.max(old.until(), level.getGameTime() + ticks)));
            return;
        }
        glow(level, pos, state, colour, ticks);
    }

    /** Glow colour that names the ore at a glance. */
    private static int oreColour(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (id.contains("diamond")) return 0x4AEDD9;
        if (id.contains("emerald")) return 0x17DD62;
        if (id.contains("gold")) return 0xFFD83D;
        if (id.contains("redstone")) return 0xFF2A2A;
        if (id.contains("lapis")) return 0x3060FF;
        if (id.contains("copper")) return 0xE8804F;
        if (id.contains("iron")) return 0xE2B89A;
        if (id.contains("coal")) return 0x6A6A6A;
        if (id.contains("quartz")) return 0xF2ECE4;
        if (id.contains("debris")) return 0xA0664C;
        return 0xFFFFFF;
    }

    // ---------------------------------------------------------------- Forge Fire

    static int forgeBudget() {
        return SpellUpgrades.add(Spell.FORGE_FIRE, SpellUpgrades.Stat.COUNT, 32);
    }

    /**
     * Smelts what a furnace would from the raw ores, ore blocks, raw meat and fish and potatoes carried, up to
     * {@code budget} items. Logs, sand and the rest are never touched (charcoal by surprise
     * would eat a stack of wood). Refuses when nothing carried qualifies.
     */
    static boolean forgeFire(ServerLevel level, ServerPlayer p, int budget) {
        var inv = p.getInventory();
        int smelted = 0;
        boolean kiln = SpellUpgrades.perk(p, Spell.FORGE_FIRE, "Kiln"),
                rich = SpellUpgrades.perk(p, Spell.FORGE_FIRE, "Rich Ore");
        List<ItemStack> made = new ArrayList<>();
        for (int i = 0; i < 36 && smelted < budget; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !(forgeable(stack) || kiln && kilnable(stack))) continue;
            var input = new SingleRecipeInput(stack);
            var recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, input, level);
            if (recipe.isEmpty()) continue;
            ItemStack result = recipe.get().value().assemble(input);
            if (result.isEmpty()) continue;
            int n = Math.min(stack.getCount(), budget - smelted);
            int extra = 0;
            if (rich && stack.is(ConventionalItemTags.RAW_MATERIALS))
                for (int k = 0; k < n; k++) if (level.getRandom().nextInt(3) == 0) extra++;
            made.add(result.copyWithCount(result.getCount() * (n + extra)));
            stack.shrink(n);
            smelted += n;
        }
        if (smelted > 0 && SpellUpgrades.perk(p, Spell.FORGE_FIRE, "Ember Heart"))
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 1200, 0, false, false, true));
        if (smelted == 0) {
            p.sendSystemMessage(
                    Component.literal("Nothing you carry needs smelting").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        for (ItemStack stack : WorkSpells.merge(made))
            if (!inv.add(stack)) Block.popResource(level, p.blockPosition(), stack);
        level.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 40, 0.5, 0.6, 0.5, 0.03);
        level.sendParticles(ParticleTypes.LAVA, p.getX(), p.getY() + 1, p.getZ(), 8, 0.4, 0.4, 0.4, 0);
        sound(level, p, SoundEvents.BLASTFURNACE_FIRE_CRACKLE, 1.5f, 1.0f);
        sound(level, p, SoundEvents.FIRECHARGE_USE, 0.6f, 1.2f);
        p.sendSystemMessage(
                Component.literal("Smelted " + smelted + (smelted == 1 ? " thing" : " things"))
                        .withStyle(ChatFormatting.GOLD),
                true);
        return true;
    }

    /** What a furnace makes of this whole stack (raw ore, raw food), or EMPTY when it is not something to smelt. */
    static ItemStack smelted(ServerLevel level, ItemStack stack) {
        return smelted(level, stack, false);
    }

    /** As {@link #smelted(ServerLevel, ItemStack)}; {@code any}: whatever a furnace takes (cobblestone, sand too). */
    static ItemStack smelted(ServerLevel level, ItemStack stack, boolean any) {
        if (stack.isEmpty() || !any && !forgeable(stack)) return ItemStack.EMPTY;
        var input = new SingleRecipeInput(stack);
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, input, level);
        if (recipe.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = recipe.get().value().assemble(input);
        return result.isEmpty() ? ItemStack.EMPTY : result.copyWithCount(result.getCount() * stack.getCount());
    }

    /** Kiln: what the forge also fires with the perk. */
    private static boolean kilnable(ItemStack s) {
        return s.is(net.minecraft.tags.ItemTags.SMELTS_TO_GLASS)
                || s.is(Items.COBBLESTONE)
                || s.is(Items.CLAY_BALL)
                || s.is(net.minecraft.tags.ItemTags.LOGS_THAT_BURN);
    }

    private static boolean forgeable(ItemStack s) {
        return s.is(ConventionalItemTags.RAW_MATERIALS)
                || s.is(ConventionalItemTags.ORES)
                || s.is(ConventionalItemTags.RAW_MEAT_FOODS)
                || s.is(ConventionalItemTags.RAW_FISH_FOODS)
                || s.is(Items.POTATO)
                || s.is(Items.ANCIENT_DEBRIS);
    }

    // ---------------------------------------------------------------- Owl Sight

    static final int OWL_TICKS = 600;
    static final double OWL_RANGE = 32;

    /** The monsters an Owl Sight shows its caster, sent each half second; empty when it ends. */
    public record OwlSight(List<Integer> ids) implements CustomPacketPayload {
        public static final Type<OwlSight> TYPE = new Type<>(ModItems.id("owl_sight"));
        static final StreamCodec<RegistryFriendlyByteBuf, OwlSight> CODEC = ByteBufCodecs.VAR_INT
                .apply(ByteBufCodecs.list())
                .map(OwlSight::new, OwlSight::ids)
                .cast();

        @Override
        public Type<OwlSight> type() {
            return TYPE;
        }
    }

    static final class Owl {
        int ticks;
        final double range;
        final Set<LivingEntity> hunting = new HashSet<>();
        final Set<LivingEntity> shown = new HashSet<>();

        Owl(int ticks, double range) {
            this.ticks = ticks;
            this.range = range;
        }
    }

    static final Map<ServerPlayer, Owl> OWLS = new IdentityHashMap<>();

    /** Night vision, and every monster within 32 blocks glows through walls, for you alone, for 30 seconds. */
    static boolean owlSight(ServerLevel level, ServerPlayer p) {
        int ticks = SpellUpgrades.scaleInt(Spell.OWL_SIGHT, DURATION, OWL_TICKS);
        OWLS.put(p, new Owl(ticks, SpellUpgrades.scale(Spell.OWL_SIGHT, AREA, OWL_RANGE)));
        p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, ticks + 220, 0, false, false, true));
        if (SpellUpgrades.perk(p, Spell.OWL_SIGHT, "Night Stalker"))
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 0, false, false, true));
        if (SpellUpgrades.perk(p, Spell.OWL_SIGHT, "Owl's Glide"))
            p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, ticks, 0, false, false, true));
        if (SpellUpgrades.perk(p, Spell.OWL_SIGHT, "Owl's Calm"))
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, 0, false, false, true));
        sound(level, p.getEyePosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
        level.sendParticles(ParticleTypes.ENCHANT, p.getX(), p.getY() + .4, p.getZ(), 30, .5, .2, .5, .4);
        tickOwl(p, OWLS.get(p));
        return true;
    }

    static boolean sees(ServerPlayer p, LivingEntity e) {
        var owl = OWLS.get(p);
        return owl != null && owl.shown.contains(e);
    }

    static void tickOwls() {
        for (var it = OWLS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var p = entry.getKey();
            var owl = entry.getValue();
            if (--owl.ticks < 0 || p.isRemoved()) {
                if (!p.isRemoved()) send(p, new OwlSight(List.of()));
                it.remove();
                continue;
            }
            if (owl.ticks % 10 == 0) tickOwl(p, owl);
        }
    }

    private static void tickOwl(ServerPlayer p, Owl owl) {
        var level = p.level();
        var seen = level.getEntitiesOfClass(
                LivingEntity.class,
                p.getBoundingBox().inflate(owl.range),
                e -> e instanceof Enemy && e.isAlive() && e.distanceToSqr(p) <= owl.range * owl.range);
        owl.shown.clear();
        owl.shown.addAll(seen);
        var ids = new ArrayList<Integer>();
        for (var e : seen) ids.add(e.getId());
        send(p, new OwlSight(ids));
        if (SpellUpgrades.perk(p, Spell.OWL_SIGHT, "Watchful")) {
            for (var e : seen)
                if (e instanceof Mob m && m.getTarget() == p && owl.hunting.add(e))
                    p.level()
                            .playSound(
                                    null,
                                    p.getX(),
                                    p.getY(),
                                    p.getZ(),
                                    SoundEvents.NOTE_BLOCK_BELL.value(),
                                    SoundSource.PLAYERS,
                                    .9f,
                                    1.7f);
            owl.hunting.removeIf(e -> !(e instanceof Mob m) || m.getTarget() != p || !e.isAlive());
        }
    }

    /** Only to a player whose game can take it (never the self-test's fake player). */
    private static void send(ServerPlayer p, OwlSight list) {
        if (ServerPlayNetworking.canSend(p, OwlSight.TYPE)) ServerPlayNetworking.send(p, list);
    }

    /** Hunter's Mark: a monster your Owl Sight shows takes 15% more from your spells. */
    static double beforeHit(ServerPlayer caster, LivingEntity target, double amount) {
        return sees(caster, target) && Perks.has(caster, Spell.OWL_SIGHT, "Hunter's Mark") ? amount * 1.15 : amount;
    }

    // ---------------------------------------------------------------- Wayglow

    /** Kindled Stride: each mote broken adds a second of Speed I to what is left (up to a minute). */
    static void stride(ServerPlayer p) {
        var now = p.getEffect(MobEffects.SPEED);
        int left = now != null && now.getAmplifier() == 0 ? now.getDuration() : 0;
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, Math.min(1200, left + 20), 0, false, false, true));
    }

    static final double MOTE_REACH = 32, MOTE_SPEED = 1.6;

    static final class Mote {
        final ServerPlayer owner;
        final ServerLevel level;
        Vec3 pos;
        final Vec3 dir;
        final double reach;
        double travelled;

        Mote(ServerPlayer owner, Vec3 pos, Vec3 dir, double reach) {
            this.owner = owner;
            this.level = owner.level();
            this.pos = pos;
            this.dir = dir;
            this.reach = reach;
        }
    }

    static final List<Mote> MOTES = new ArrayList<>();

    /** Throws a mote of light that sticks where it lands (or hangs at the end of its flight) and lights it for good. */
    static boolean wayglow(ServerLevel level, ServerPlayer p) {
        int n = SpellUpgrades.add(Spell.WAYGLOW, COUNT, 1);
        double reach = SpellUpgrades.scale(Spell.WAYGLOW, REACH, MOTE_REACH);
        Vec3 look = p.getLookAngle(), from = hand(p);
        for (int i = 0; i < n; i++) {
            double turn = n == 1 ? 0 : (i - (n - 1) / 2.0) * .14;
            MOTES.add(new Mote(p, from, look.yRot((float) turn), reach));
        }
        if (SpellUpgrades.perk(p, Spell.WAYGLOW, "Gentle Light")) p.heal(2);
        if (SpellUpgrades.perk(p, Spell.WAYGLOW, "Feather Fall"))
            p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 100, 0, false, false, true));
        sound(level, from, SoundEvents.AMETHYST_BLOCK_CHIME, .8f, 1.9f);
        return true;
    }

    static void tickMotes() {
        for (Iterator<Mote> it = MOTES.iterator(); it.hasNext(); ) {
            var m = it.next();
            if (m.owner.isRemoved() || m.owner.level() != m.level) {
                it.remove();
                continue;
            }
            Vec3 next = m.pos.add(m.dir.scale(MOTE_SPEED));
            BlockHitResult hit = m.level.clip(
                    new ClipContext(m.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, m.owner));
            BlockPos cell = null;
            if (hit.getType() != HitResult.Type.MISS) {
                cell = hit.getBlockPos().relative(hit.getDirection());
                m.pos = hit.getLocation();
            } else {
                m.pos = next;
                m.travelled += MOTE_SPEED;
                if (m.travelled >= m.reach) cell = BlockPos.containing(m.pos);
            }
            if (m.pos.distanceToSqr(m.owner.getEyePosition()) > 2.5) {
                m.level.sendParticles(
                        new DustParticleOptions(0xFFE08A, 1.2f), m.pos.x, m.pos.y, m.pos.z, 2, .05, .05, .05, 0);
                m.level.sendParticles(ParticleTypes.END_ROD, m.pos.x, m.pos.y, m.pos.z, 1, 0, 0, 0, 0);
            }
            if (cell == null) continue;
            it.remove();
            land(m, cell);
        }
    }

    private static void land(Mote m, BlockPos cell) {
        BlockState there = m.level.getBlockState(cell);
        boolean room =
                (there.isAir() || there.canBeReplaced() && there.getFluidState().isEmpty())
                        && !there.is(GlowMote.MOTE)
                        && m.level.mayInteract(m.owner, cell);
        Vec3 c = Vec3.atCenterOf(cell);
        boolean bright = SpellUpgrades.perk(m.owner, Spell.WAYGLOW, "Bright Motes");
        if (room)
            m.level.setBlockAndUpdate(cell, GlowMote.MOTE.defaultBlockState().setValue(GlowMote.LARGE, bright));
        m.level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, room ? 14 : 5, .2, .2, .2, .06);
        sound(m.level, c, SoundEvents.AMETHYST_BLOCK_PLACE, .9f, 1.7f);
        if (SpellUpgrades.perk(m.owner, Spell.WAYGLOW, "Flare"))
            for (var e : m.level.getEntitiesOfClass(
                    LivingEntity.class,
                    new AABB(cell).inflate(16),
                    e -> e instanceof net.minecraft.world.entity.monster.Enemy))
                e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false));
    }

    // ---------------------------------------------------------------- Quickening

    static final double QUICK_AREA = 12;

    static final int GROW_REACH = 8;

    /**
     * Quickening's plants: Quick Saplings grows each sapling within 8 into its tree, Sprouting moves each crop on a
     * stage. Returns how many it touched.
     */
    private static int grow(ServerLevel level, ServerPlayer p, double area, boolean saplings, boolean crops) {
        if (!saplings && !crops) return 0;
        int r = (int) Math.min(area, GROW_REACH), touched = 0;
        BlockPos c = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-r, -2, -r), c.offset(r, 2, r))) {
            var state = level.getBlockState(pos);
            if (!level.mayInteract(p, pos)) continue;
            if (saplings && state.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock sapling) {
                sapling.advanceTree(
                        level,
                        pos.immutable(),
                        state.setValue(net.minecraft.world.level.block.SaplingBlock.STAGE, 1),
                        level.getRandom());
                touched++;
            } else if (crops
                    && state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop
                    && !crop.isMaxAge(state)) {
                level.setBlock(pos, crop.getStateForAge(crop.getAge(state) + 1), Block.UPDATE_CLIENTS);
                touched++;
            } else continue;
            level.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER, pos.getX() + .5, pos.getY() + .6, pos.getZ() + .5, 4, .3, .3, .3, 0);
        }
        return touched;
    }

    /** Every baby animal and villager in reach grows up at once (and, upgraded, more). */
    static boolean quickening(ServerLevel level, ServerPlayer p) {
        double area = SpellUpgrades.scale(Spell.QUICKENING, AREA, QUICK_AREA);
        boolean again = SpellUpgrades.perk(p, Spell.QUICKENING, "Ready Again");
        boolean wool = SpellUpgrades.perk(p, Spell.QUICKENING, "Shepherd's Bounty");
        boolean hearty = SpellUpgrades.perk(p, Spell.QUICKENING, "Hearty Herd");
        boolean mood = SpellUpgrades.perk(p, Spell.QUICKENING, "Breeding Mood");
        int touched = grow(
                level,
                p,
                area,
                SpellUpgrades.perk(p, Spell.QUICKENING, "Quick Saplings"),
                SpellUpgrades.perk(p, Spell.QUICKENING, "Sprouting"));
        for (var e : level.getEntitiesOfClass(
                AgeableMob.class,
                p.getBoundingBox().inflate(area),
                e -> e.isAlive() && !(e instanceof Enemy) && e.distanceToSqr(p) <= area * area)) {
            boolean grew = false;
            if (e.isBaby() && !e.isAgeLocked()) {
                e.setAge(0);
                grew = true;
            } else if (again && e.getAge() > 0) {
                e.setAge(0);
                grew = true;
            }
            if (wool && e instanceof Sheep sheep && sheep.isSheared()) {
                sheep.setSheared(false);
                grew = true;
            }
            if (mood
                    && !e.isBaby()
                    && e instanceof net.minecraft.world.entity.animal.Animal animal
                    && animal.canFallInLove()) {
                animal.setInLove(p);
                grew = true;
            }
            if (hearty && e.getHealth() < e.getMaxHealth()) {
                e.setHealth(e.getMaxHealth());
                e.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 1200, 0));
                grew = true;
            }
            if (!grew) continue;
            touched++;
            level.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    e.getX(),
                    e.getY() + e.getBbHeight() * .6,
                    e.getZ(),
                    8,
                    .4,
                    .4,
                    .4,
                    0);
        }
        if (touched == 0) {
            say(p, "No young ones in reach");
            return false;
        }
        atFeet(p, new DustParticleOptions(0xB8F07A, 1f), ParticleTypes.HAPPY_VILLAGER, 12);
        Spells.ring(level, p.position().add(0, .2, 0), Math.min(area, 8), new DustParticleOptions(0xD8FF9A, 1f));
        sound(level, p.position(), SoundEvents.BONE_MEAL_USE, 1f, .8f);
        sound(level, p.position(), SoundEvents.AMETHYST_BLOCK_CHIME, .8f, 1.7f);
        return true;
    }

    // ---------------------------------------------------------------- Light Bore

    static final int BORE_LENGTH = 8;
    // half the first 16 (owner, 2026-11-22)

    /** A beam bores a tunnel two high straight ahead, level with your feet; what it digs lands at your feet. */
    static boolean lightBore(ServerLevel level, ServerPlayer p) {
        if (!p.mayBuild()) return false;
        Direction ahead = p.getDirection();
        int length = SpellUpgrades.scaleInt(Spell.LIGHT_BORE, REACH, BORE_LENGTH);
        boolean snatch = SpellUpgrades.perk(p, Spell.LIGHT_BORE, "Ore Snatcher");
        boolean torches = SpellUpgrades.perk(p, Spell.LIGHT_BORE, "Torchlit Bore");
        boolean seal = SpellUpgrades.perk(p, Spell.LIGHT_BORE, "Sealed Walls");
        boolean wide = SpellUpgrades.perk(p, Spell.LIGHT_BORE, "Wide Bore");
        boolean smelt = SpellUpgrades.perk(p, Spell.LIGHT_BORE, "Smelting Bore");
        BlockPos feet = p.blockPosition();
        var haul = new ArrayList<ItemStack>();
        int broken = 0;
        Direction left = ahead.getCounterClockWise();
        for (int d = 1; d <= length; d++) {
            BlockPos low = feet.relative(ahead, d), high = low.above();
            for (BlockPos cell : List.of(low, high)) if (WorkSpells.dig(level, p, cell, haul)) broken++;
            if (wide)
                for (BlockPos cell : List.of(
                        low.relative(left), high.relative(left), low.relative(left, -1), high.relative(left, -1)))
                    if (WorkSpells.dig(level, p, cell, haul)) broken++;
            if (seal)
                for (BlockPos cell : List.of(low, high))
                    for (Direction side : Direction.values()) {
                        BlockPos n = cell.relative(side);
                        if (side == ahead.getOpposite() || n.equals(low) || n.equals(high)) continue;
                        if (!level.getFluidState(n).isEmpty() && level.mayInteract(p, n))
                            level.setBlockAndUpdate(n, Blocks.STONE.defaultBlockState());
                    }
            if (snatch)
                for (int u = -2; u <= 2; u++)
                    for (int v = -2; v <= 3; v++) {
                        if (u == 0 && (v == 0 || v == 1)) continue;
                        BlockPos n = low.relative(left, u).above(v);
                        if (level.getBlockState(n).is(ConventionalBlockTags.ORES) && WorkSpells.dig(level, p, n, haul))
                            broken++;
                    }
            if (torches && d % 8 == 0 && level.getBlockState(high).isAir())
                level.setBlockAndUpdate(high, GlowMote.MOTE.defaultBlockState());
        }
        if (broken == 0) {
            say(p, "Too hard to bore");
            return false;
        }
        for (ItemStack stack : WorkSpells.merge(haul)) {
            ItemStack out = stack;
            if (smelt) {
                ItemStack done = WorkSpells.smelted(level, stack);
                if (!done.isEmpty()) out = done;
            }
            Block.popResource(level, p.blockPosition(), out);
        }
        if (SpellUpgrades.perk(p, Spell.LIGHT_BORE, "Swift Digger"))
            p.addEffect(new MobEffectInstance(MobEffects.HASTE, 600, 1, false, false, true));
        Vec3 from = Vec3.atBottomCenterOf(feet.relative(ahead, 1)).add(0, 1, 0);
        Vec3 to = Vec3.atBottomCenterOf(feet.relative(ahead, length)).add(0, 1, 0);
        SpellKit.line(level, from, to, new DustParticleOptions(0xFFE08A, 1f), .4);
        level.sendParticles(ParticleTypes.END_ROD, to.x, to.y, to.z, 16, .3, .5, .3, .05);
        sound(level, from, SoundEvents.BEACON_DEACTIVATE, .7f, 1.8f);
        sound(level, from, SoundEvents.STONE_BREAK, 1f, .7f);
        return true;
    }

    // ---------------------------------------------------------------- Transmute

    static final double TRANSMUTE_RADIUS = 10;

    /**
     * The ores Transmute can make, each with its weight against coal's 6 once its upgrade is in use (owner,
     * 2026-11-22: coal alone at first; "upgrades introduce mix, not replace all to just one ore").
     */
    record Vein(String perk, Block ore, Block deep, int weight) {}

    static final List<Vein> VEINS = List.of(
            new Vein("Iron Vein", Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 4),
            new Vein("Copper Vein", Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 4),
            new Vein("Redstone Vein", Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 4),
            new Vein("Gold Vein", Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 3),
            new Vein("Lapis Vein", Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 3),
            new Vein("Emerald Vein", Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, 3),
            new Vein("Diamond Vein", Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 2),
            new Vein("Ancient Vein", Blocks.ANCIENT_DEBRIS, Blocks.ANCIENT_DEBRIS, 1));

    static final int COAL_WEIGHT = 6;
    /** Mother Lode: a little of every ore of the first two tiers, weight 1 each. */
    static final String MOTHER_LODE = "Mother Lode";

    /** The ore mix this player's Transmute makes: coal, then each vein its upgrades bring in. */
    static List<Vein> mix(ServerPlayer p) {
        List<Vein> mix =
                new ArrayList<>(List.of(new Vein("", Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, COAL_WEIGHT)));
        boolean lode = SpellUpgrades.perk(p, Spell.TRANSMUTE, MOTHER_LODE);
        for (int i = 0; i < VEINS.size(); i++) {
            var v = VEINS.get(i);
            if (SpellUpgrades.perk(p, Spell.TRANSMUTE, v.perk())) mix.add(v);
            else if (lode && i < 6) mix.add(new Vein(v.perk(), v.ore(), v.deep(), 1));
        }
        return mix;
    }

    /**
     * Every solid block within 10 blocks of you turns to ore (owner, 2026-11-23: "any block, not just stone ... 10 blocks
     * around you"): coal, and whatever ores the upgrades add to the mix. Left alone: air and liquids, blocks that are not
     * full cubes, ores already there, chests and other blocks that hold things, unbreakable blocks, and anything inside a
     * ward of light, so a home is never turned to ore.
     */
    static boolean transmute(ServerLevel level, ServerPlayer p) {
        BlockPos heart = p.blockPosition();
        double r = TRANSMUTE_RADIUS;
        var mix = mix(p);
        int total = 0;
        for (var v : mix) total += v.weight();
        var random = level.getRandom();
        int changed = 0, ri = (int) Math.ceil(r);
        for (BlockPos pos : BlockPos.betweenClosed(heart.offset(-ri, -ri, -ri), heart.offset(ri, ri, ri))) {
            if (pos.distSqr(heart) > r * r + .01) continue;
            BlockState state = level.getBlockState(pos);
            if (!transmutable(level, p, pos, state)) continue;
            boolean deep = state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF) || pos.getY() < 0;
            int roll = random.nextInt(total), pick = 0;
            while (roll >= mix.get(pick).weight()) roll -= mix.get(pick++).weight();
            var v = mix.get(pick);
            level.setBlockAndUpdate(pos, (deep ? v.deep() : v.ore()).defaultBlockState());
            changed++;
            if (random.nextInt(12) == 0)
                level.sendParticles(
                        ParticleTypes.WAX_ON, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 4, .4, .4, .4, .5);
        }
        if (changed == 0) {
            say(p, "Nothing round you to change");
            return false;
        }
        Vec3 c = p.position().add(0, 1, 0);
        level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 60, r * .4, r * .3, r * .4, .05);
        sound(level, c, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.4f, .6f);
        sound(level, c, SoundEvents.BREWING_STAND_BREW, 1.2f, .7f);
        return true;
    }

    /** Whether Transmute may turn the block at {@code pos} to ore. */
    static boolean transmutable(ServerLevel level, ServerPlayer p, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (!state.isCollisionShapeFullBlock(level, pos)) return false;
        if (state.is(net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags.ORES)
                || state.is(Blocks.ANCIENT_DEBRIS)) return false;
        if (level.getBlockEntity(pos) != null || state.getDestroySpeed(level, pos) < 0) return false;
        if (!level.mayInteract(p, pos)) return false;
        return !LightWards.contains(level, Vec3.atCenterOf(pos));
    }

    // ---------------------------------------------------------------- Ender Pocket

    /** Opens your ender chest; its upgrades also stow things in it first. */
    static boolean pocket(ServerLevel level, ServerPlayer p) {
        var chest = p.getEnderChestInventory();
        int stowed = 0;
        if (perk(p, Spell.ENDER_POCKET, "Gathering Pocket"))
            for (var item : level.getEntitiesOfClass(
                    ItemEntity.class, p.getBoundingBox().inflate(8))) {
                if (!item.isAlive() || item.hasPickUpDelay()) continue;
                ItemStack left = chest.addItem(item.getItem().copy());
                stowed += item.getItem().getCount() - left.getCount();
                if (left.isEmpty()) item.discard();
                else item.setItem(left);
            }
        boolean plants = perk(p, Spell.ENDER_POCKET, "Stow Plants"),
                food = perk(p, Spell.ENDER_POCKET, "Stow Food"),
                blocks = perk(p, Spell.ENDER_POCKET, "Stow Blocks"),
                ores = perk(p, Spell.ENDER_POCKET, "Stow Ores"),
                loot = perk(p, Spell.ENDER_POCKET, "Stow Monster Loot"),
                all = perk(p, Spell.ENDER_POCKET, "Stow All"),
                potions = perk(p, Spell.ENDER_POCKET, "Stow Potions");
        Inventory inv = p.getInventory();
        // The pack only, never the hotbar, armour or off hand: what you hold in reach stays with you.
        for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || TomeItem.spellOf(stack) != null) continue;
            boolean go = all
                    || blocks && stack.getItem() instanceof BlockItem
                    || plants && plantItem(stack)
                    || food && stack.has(net.minecraft.core.component.DataComponents.FOOD)
                    || ores && isOre(stack)
                    || loot && isMonsterLoot(stack)
                    || potions && stack.has(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (!go) continue;
            int before = stack.getCount();
            ItemStack left = chest.addItem(stack.copy());
            stowed += before - left.getCount();
            inv.setItem(i, left);
        }
        if (stowed > 0) say(p, stowed + " things stowed in your ender chest");
        p.openMenu(new SimpleMenuProvider(
                (id, playerInv, pl) -> ChestMenu.threeRows(id, playerInv, chest),
                Component.translatable("container.enderchest")));
        sound(level, p.position(), SoundEvents.ENDER_CHEST_OPEN, .8f, 1.2f);
        level.sendParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 20, .4, .5, .4, .3);
        return true;
    }

    private static boolean plantItem(ItemStack s) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(s.getItem())
                .getPath();
        return s.is(ItemTags.SAPLINGS)
                || s.is(net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags.FLOWERS)
                || s.is(ItemTags.VILLAGER_PLANTABLE_SEEDS)
                || id.endsWith("_seeds")
                || List.of(
                                "wheat",
                                "sugar_cane",
                                "cactus",
                                "bamboo",
                                "kelp",
                                "pumpkin",
                                "melon",
                                "nether_wart",
                                "cocoa_beans")
                        .contains(id);
    }

    private static boolean isOre(ItemStack s) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(s.getItem())
                .getPath();
        return id.endsWith("_ore")
                || id.startsWith("raw_")
                || id.endsWith("_ingot")
                || s.is(ItemTags.COALS)
                || List.of(
                                "diamond",
                                "emerald",
                                "lapis_lazuli",
                                "redstone",
                                "quartz",
                                "amethyst_shard",
                                "ancient_debris")
                        .contains(id);
    }

    private static boolean isMonsterLoot(ItemStack s) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(s.getItem())
                .getPath();
        return List.of(
                        "rotten_flesh",
                        "bone",
                        "string",
                        "spider_eye",
                        "gunpowder",
                        "arrow",
                        "ender_pearl",
                        "slime_ball",
                        "phantom_membrane",
                        "blaze_rod",
                        "magma_cream",
                        "ghast_tear",
                        "prismarine_shard",
                        "prismarine_crystals")
                .contains(id);
    }
}
