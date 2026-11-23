package lightborn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import net.fabricmc.fabric.api.loot.v3.FabricLootTableBuilder;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootPool;

/**
 * A spell tome. Held in the off hand, it is the spell a staff casts on right-click. Tomes last forever (owner,
 * 2026-11-16): no wear, no repair.
 */
public class TomeItem extends Item {
    public final Spell spell;
    /**
     * Damage the viewing player would deal (level, Spell Power, masteries), set by the client; null on a server,
     * where the tooltip falls back to the level-1 base.
     */
    public static ToDoubleFunction<Spell> viewerDamage;

    public static Supplier<Player> viewerPlayer;

    public TomeItem(Spell spell, Properties props) {
        super(props);
        this.spell = spell;
    }

    public static Spell spellOf(ItemStack stack) {
        return stack.getItem() instanceof TomeItem tome ? tome.spell : null;
    }

    /** Right-clicking the tome itself only explains how it is used. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp) {
            String hint = hand == InteractionHand.MAIN_HAND
                    ? "Hold the tome in your off hand, and a staff in your main hand"
                    : "Hold a staff in your main hand to cast";
            sp.sendSystemMessage(Component.literal(hint).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        }
        return InteractionResult.PASS;
    }

    /** A tome's name takes its spell's rarity colour, as rolled gear's does. */
    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack).copy().withStyle(spell.rarity().color);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            TooltipDisplay display,
            Consumer<Component> out,
            TooltipFlag flag) {
        out.accept(Component.literal(spell.rarity().label + " spell").withStyle(spell.rarity().color));
        for (String line : wrap(spell.description))
            out.accept(Component.literal(line).withStyle(ChatFormatting.DARK_AQUA));
        if (spell.baseDamage > 0) {
            // Shield and heal scale exactly like damage (level, Spell Power), so one number covers all three.
            double powerValue =
                    viewerDamage != null ? viewerDamage.applyAsDouble(spell) : spell.baseDamage * Balance.SPELL_DAMAGE;
            powerValue *=
                    Math.max(.1, 1 + SpellUpgrades.total(spell, SpellUpgrades.chosen(stack), SpellUpgrades.Stat.POWER));
            String power = oneDecimal(powerValue);
            Component powerLine = switch (spell) {
                case STONESKIN ->
                    Component.literal("Shield of " + power + " health").withStyle(ChatFormatting.GOLD);
                case MENDING_LIGHT ->
                    Component.literal("Heals " + power + " health").withStyle(ChatFormatting.GREEN);
                case MIRROR_WARD ->
                    Component.literal("Throws back 150% of each hit, plus " + power)
                            .withStyle(ChatFormatting.GOLD);
                default ->
                    Component.literal(power + " damage"
                                    + switch (spell) {
                                        case FIREBALL -> " in the blast";
                                        case FROST_NOVA -> " to all around you";
                                        case CHAIN_LIGHTNING -> " to each target";
                                        case METEOR -> " in the impact circle";
                                        case GRAVITY_WELL ->
                                            " as it collapses, and 30% of that each half second inside";
                                        case GLACIAL_SPIKE -> ", triple on a chilled target";
                                        case WATER_SPLASH ->
                                            " to each in the wave, double to fire and water-shy creatures";
                                        case EARTHSHATTER -> " to all on the line";
                                        case SIPHON -> " a pulse to each, half healed back";
                                        case CONTAGION -> " a second for 8 s";
                                        case SPIRIT_WOLVES -> " a bite";
                                        default -> "";
                                    })
                            .withStyle(ChatFormatting.RED);
            };
            // Long lines wrap, keeping the line's colour on every piece.
            for (String line : wrap(powerLine.getString()))
                out.accept(Component.literal(line).withStyle(powerLine.getStyle()));
        }
        int[] chosen = SpellUpgrades.chosen(stack);
        double mana = spell.mana * Math.max(.1, 1 + SpellUpgrades.total(spell, chosen, SpellUpgrades.Stat.COST));
        double cooldown = spell.cooldownTicks
                * Math.max(.1, 1 + SpellUpgrades.total(spell, chosen, SpellUpgrades.Stat.COOLDOWN))
                / 20.0;
        out.accept(Component.literal(trim(Math.round(mana * 10) / 10.0) + " mana  ·  "
                        + trim(Math.round(cooldown * 100) / 100.0) + " s base cooldown")
                .withStyle(ChatFormatting.BLUE));
        TomeAwakening.tooltip(stack, out);
        SpellUpgrades.tooltip(stack, out);
        out.accept(Component.literal("Off hand + staff: right-click to cast").withStyle(ChatFormatting.DARK_GRAY));
        Player viewer = viewerPlayer == null ? null : viewerPlayer.get();
        if (spell == Spell.SOULS && viewer != null)
            for (Component line : Souls.tooltip(viewer, stack)) out.accept(line);
    }

    /** Tooltip lines wider than this many characters wrap: vanilla never wraps them, and at large GUI scales they run off screen. */
    static final int WRAP = 40;

    /** {@code text} split at spaces into lines of at most {@link #WRAP} characters (a longer single word stays whole). */
    static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > WRAP) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    private static String oneDecimal(double v) {
        return String.format(Locale.ROOT, "%.1f", v).replace(".0", "");
    }

    private static String trim(double v) {
        return v == Math.floor(v)
                ? String.valueOf((long) v)
                : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    // ---------------------------------------------------------------- loot

    /** Any droppable tome, every tier open (tests, and sources with no tier of their own). */
    public static ItemStack roll(RandomSource random) {
        return roll(random, 5);
    }

    /**
     * A tome from a source of power tier {@code maxTier} (owner, 2026-11-18): only spells of that tier or below, each
     * weighted by its own tier ({@link Spell#dropWeight}), so a tier-1 zombie never drops a tier-2 spell and the
     * strongest spells stay rare even where they can drop.
     */
    public static ItemStack roll(RandomSource random, int maxTier) {
        int total = 0;
        for (Spell s : Spell.values()) if (s.tier <= maxTier) total += s.dropWeight;
        if (total == 0) return ItemStack.EMPTY;
        int pick = random.nextInt(total);
        for (Spell s : Spell.values()) {
            if (s.tier > maxTier) continue;
            pick -= s.dropWeight;
            if (pick < 0) return new ItemStack(ModItems.TOMES.get(s));
        }
        return ItemStack.EMPTY;
    }

    /** A tome of exactly that tier, by drop weight (a boss's tier 5 spell); any lower tier when none drops. */
    public static ItemStack rollTier(RandomSource random, int tier) {
        int total = 0;
        for (Spell s : Spell.values()) if (s.tier == tier) total += s.dropWeight;
        if (total == 0) return roll(random, tier);
        int pick = random.nextInt(total);
        for (Spell s : Spell.values()) {
            if (s.tier != tier) continue;
            pick -= s.dropWeight;
            if (pick < 0) return new ItemStack(ModItems.TOMES.get(s));
        }
        return roll(random, tier);
    }

    /** Every chest loot table gets one roll at TOME_CHEST_CHANCE, weighted like monster drops. */
    static void initLoot() {
        LootTableEvents.MODIFY.register((key, builder, source, registries) -> {
            if (!key.identifier().getPath().startsWith("chests/")) return;
            ((FabricLootTableBuilder) builder).pool(chestPool(registries));
            ((FabricLootTableBuilder) builder).pool(shardPool(registries));
        });
    }

    /** Dark shards for the Cleanse: one roll per chest at SHARD_CHEST_CHANCE, one to SHARD_CHEST_MAX shards. */
    static LootPool shardPool(HolderLookup.Provider registries) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        JsonObject count = new JsonObject();
        count.addProperty("type", "minecraft:uniform");
        count.addProperty("min", 1);
        count.addProperty("max", Balance.SHARD_CHEST_MAX);
        JsonObject setCount = new JsonObject();
        setCount.addProperty("function", "minecraft:set_count");
        setCount.add("count", count);
        JsonArray functions = new JsonArray();
        functions.add(setCount);
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "minecraft:item");
        entry.addProperty("name", Lightborn.MOD_ID + ":dark_shard");
        entry.add("functions", functions);
        JsonArray entries = new JsonArray();
        entries.add(entry);
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1);
        pool.add("condition", ChestLootRoll.json("dark_shard", Balance.SHARD_CHEST_CHANCE));
        pool.add("entries", entries);
        return LootPool.CODEC.parse(ops, pool).getOrThrow();
    }

    static LootPool chestPool(HolderLookup.Provider registries) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        JsonArray entries = new JsonArray();
        for (Spell s : Spell.values()) {
            if (s.dropWeight == 0) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "minecraft:item");
            entry.addProperty("name", Lightborn.MOD_ID + ":" + s.tomeId());
            entry.addProperty("weight", s.dropWeight);
            entries.add(entry);
        }
        JsonObject chance = ChestLootRoll.json("tome", Balance.TOME_CHEST_CHANCE);
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1);
        pool.add("condition", chance);
        pool.add("entries", entries);
        return LootPool.CODEC.parse(ops, pool).getOrThrow();
    }
}
