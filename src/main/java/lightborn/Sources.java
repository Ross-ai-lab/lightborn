package lightborn;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Where an item no hand can make comes from, as a short hint for the chest's item panel (owner, 2026-11-18: "found in
 * the world" said too little; say dropped by monsters, by bosses, and so on). Lightborn's own items say where the
 * mod hands them out. Everything else is read from the game's loot tables, the mods' included: which monsters drop
 * it, which blocks it is mined from, which chests hold it, fishing, bartering, brushing. Built once per server.
 */
public final class Sources {
    private Sources() {}

    /** Item id to the loot tables (by id) that can give it. */
    private static Map<String, Set<Identifier>> tables;

    static synchronized void clear() {
        tables = null;
    }

    /** The hint for an item without a recipe; never null. */
    public static Component hint(ServerLevel level, String id) {
        Component own = lightborn(id);
        if (own != null) return own;
        var from = index(level).getOrDefault(id, Set.of());
        List<Identifier> mobs = new ArrayList<>(),
                blocks = new ArrayList<>(),
                chests = new ArrayList<>(),
                shears = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (Identifier t : from) {
            String path = t.getPath();
            if (path.startsWith("entities/")) mobs.add(Identifier.fromNamespaceAndPath(t.getNamespace(), last(path)));
            else if (path.startsWith("blocks/"))
                blocks.add(Identifier.fromNamespaceAndPath(t.getNamespace(), last(path)));
            else if (path.startsWith("chests/")) chests.add(t);
            else if (path.startsWith("shearing/")) shears.add(t);
            else if (path.startsWith("gameplay/fishing")) add(other, "caught by fishing");
            else if (path.startsWith("gameplay/piglin_bartering")) add(other, "bartered from piglins");
            else if (path.startsWith("archaeology/")) add(other, "brushed from suspicious sand or gravel");
            else if (path.startsWith("gameplay/sniffer_digging")) add(other, "dug up by a sniffer");
            else if (path.startsWith("gameplay/hero_of_the_village")) add(other, "a gift to a Hero of the Village");
            else if (path.startsWith("gameplay/cat_morning_gift")) add(other, "a cat's morning gift");
            else if (path.startsWith("spawners/") || path.startsWith("equipment/trial"))
                add(other, "from trial spawners");
            else if (path.startsWith("gameplay/")) add(other, "from creatures in the world");
        }
        List<Component> parts = new ArrayList<>();
        if (!mobs.isEmpty()) parts.add(names("Dropped by ", mobs, true));
        if (!blocks.isEmpty()) {
            Identifier self = Identifier.parse(id);
            parts.add(
                    blocks.size() == 1 && blocks.getFirst().equals(self)
                            ? Component.literal("Dug or mined in the world")
                            : names("Mined from ", blocks, false));
        }
        if (!chests.isEmpty()) parts.add(Component.literal("Found in " + chestNames(chests)));
        if (!shears.isEmpty()) parts.add(Component.literal("Sheared from animals"));
        for (String o : other) parts.add(Component.literal(Character.toUpperCase(o.charAt(0)) + o.substring(1)));
        if (parts.isEmpty()) return Component.literal("Found in the world, never made by hand");
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size() && i < 3; i++) {
            if (i > 0) out.append("; ");
            out.append(parts.get(i));
        }
        return out;
    }

    /** Where the mod itself hands an item out, or null for one the loot tables must answer. */
    private static Component lightborn(String id) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        if (item instanceof SpawnEggItem) return Component.literal("Creative mode only");
        if (!id.startsWith(Lightborn.MOD_ID + ":")) return null;
        String path = id.substring(Lightborn.MOD_ID.length() + 1);
        for (BossKind boss : BossKind.ROSTER)
            if (BossTrophies.item(boss) == item) return Component.literal("Dropped by " + boss.title + ", a boss");
        if (item == ModItems.DARK_SHARD)
            return Component.literal("Paid by Dark Fountains; found in chests and sold in the shop");
        if (item == ModItems.SPARK)
            return Component.literal("Earned from kills, Dark Fountains, the Cleanse and gifts to the Light");
        if (item == ModItems.STARTER_STAFF) return Component.literal("Your first staff, given at the start");
        if (ModItems.SPECIALIST_TIER.containsKey(item))
            return Component.literal("Dropped by monsters, in place of some " + word(ModItems.SPECIALIST_TIER.get(item))
                    + " staffs; sold in the shop");
        if (LightGear.ITEMS.contains(item)) return Component.literal("A gift from the Dragon as his story unfolds");
        if (ModItems.tierOf(item) != null)
            return Component.literal("Dropped by monsters as rolled gear, once you have mined its ore; bosses and"
                    + " Dark Fountains drop the best");
        if (item == DragonStory.SCALE) return Component.literal("Given by the Dragon in his sixth chapter");
        if (item == DragonStory.LIGHT_KEY || item == DragonStory.SCRIBE_SEAL)
            return Component.literal("Won by defeating the Last Scribe");
        Spell spell = TomeItem.spellOf(new net.minecraft.world.item.ItemStack(item));
        if (spell != null)
            return Component.literal(
                    spell.drops
                            ? "Dropped by " + spell.rarity().label + " monsters and stronger, and by bosses; in"
                                    + " chests and the shop"
                            : "Only given, never found");
        return Component.literal("A Lightborn item, handed out by the game itself");
    }

    private static String word(ModItems.Tier tier) {
        String n = tier.name().toLowerCase();
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static void add(List<String> list, String s) {
        if (!list.contains(s)) list.add(s);
    }

    private static String last(String path) {
        return path.substring(path.indexOf('/') + 1);
    }

    /** Up to three names (entity or block), then how many more. */
    private static Component names(String lead, List<Identifier> ids, boolean entities) {
        var seen = new LinkedHashSet<Identifier>(ids);
        MutableComponent out = Component.literal(lead);
        int i = 0;
        for (Identifier id : seen) {
            if (i == 3) {
                out.append(" and " + (seen.size() - 3) + " more");
                break;
            }
            if (i > 0) out.append(i == seen.size() - 1 ? " and " : ", ");
            String key = entities
                    ? BuiltInRegistries.ENTITY_TYPE
                            .getOptional(id)
                            .map(t -> t.getDescriptionId())
                            .orElse(null)
                    : BuiltInRegistries.BLOCK
                            .getOptional(id)
                            .map(b -> b.getDescriptionId())
                            .orElse(null);
            out.append(key == null ? Component.literal(id.getPath().replace('_', ' ')) : Component.translatable(key));
            i++;
        }
        return out;
    }

    /** "chests in desert pyramids and shipwrecks", from the chest tables' names. */
    private static String chestNames(List<Identifier> tables) {
        var places = new LinkedHashSet<String>();
        for (Identifier t : tables) {
            String p = last(t.getPath());
            int slash = p.indexOf('/');
            if (slash > 0) p = p.substring(0, slash);
            places.add(p.replace("_", " "));
        }
        var list = new ArrayList<>(places);
        if (list.size() > 2)
            return "chests (" + list.get(0) + ", " + list.get(1) + " and " + (list.size() - 2) + " more)";
        return "chests (" + String.join(" and ", list) + ")";
    }

    private static synchronized Map<String, Set<Identifier>> index(ServerLevel level) {
        if (tables != null) return tables;
        var out = new HashMap<String, Set<Identifier>>();
        var server = level.getServer();
        var ops = server.reloadableRegistries().lookup().createSerializationContext(JsonOps.INSTANCE);
        server.reloadableRegistries()
                .lookup()
                .lookupOrThrow(Registries.LOOT_TABLE)
                .listElements()
                .forEach(ref -> {
                    var json = LootTable.DIRECT_CODEC
                            .encodeStart(ops, ref.value())
                            .result()
                            .orElse(null);
                    if (json == null) return;
                    var items = new LinkedHashSet<String>();
                    collect(json, items);
                    for (String item : items)
                        out.computeIfAbsent(item, k -> new LinkedHashSet<>())
                                .add(ref.key().identifier());
                });
        tables = out;
        return out;
    }

    /** Every item an entry of the table names ({@code "type": "minecraft:item", "name": ...}). */
    private static void collect(JsonElement e, Set<String> out) {
        if (e.isJsonArray()) for (var c : e.getAsJsonArray()) collect(c, out);
        else if (e.isJsonObject()) {
            var o = e.getAsJsonObject();
            if (o.has("type")
                    && o.get("type").isJsonPrimitive()
                    && o.get("type").getAsString().equals("minecraft:item")
                    && o.has("name")
                    && o.get("name").isJsonPrimitive()) {
                String name = o.get("name").getAsString();
                out.add(name.contains(":") ? name : "minecraft:" + name);
            }
            for (var entry : o.entrySet()) collect(entry.getValue(), out);
        }
    }
}
