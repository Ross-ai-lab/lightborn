package lightborn;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/** One chance per generated chest, even when a structure mod nests or copies modified chest tables. */
public record ChestLootRoll(String kind, float chance) implements LootItemCondition {
    public static final MapCodec<ChestLootRoll> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.STRING.fieldOf("kind").forGetter(ChestLootRoll::kind),
                    Codec.FLOAT.fieldOf("chance").forGetter(ChestLootRoll::chance))
            .apply(i, ChestLootRoll::new));
    private static final ThreadLocal<Map<LootContext, Set<String>>> SEEN = ThreadLocal.withInitial(WeakHashMap::new);

    static void init() {
        Registry.register(BuiltInRegistries.LOOT_CONDITION_TYPE, ModItems.id("chest_roll"), MAP_CODEC);
    }

    @Override
    public MapCodec<? extends LootItemCondition> codec() {
        return MAP_CODEC;
    }

    @Override
    public boolean test(LootContext context) {
        return SEEN.get().computeIfAbsent(context, c -> new HashSet<>()).add(kind)
                && context.getRandom().nextFloat() < chance;
    }

    static JsonObject json(String kind, double chance) {
        var json = new JsonObject();
        json.addProperty("type", "lightborn:chest_roll");
        json.addProperty("kind", kind);
        json.addProperty("chance", chance);
        return json;
    }
}
