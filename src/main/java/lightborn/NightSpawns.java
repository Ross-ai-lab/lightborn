package lightborn;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MobSpawnSettings;

/** Raise vanilla's existing night budgets; no duplicate spawn loop or per-tick mob scans. */
public final class NightSpawns {
    private NightSpawns() {}

    public interface Context {
        void lightborn$spawnLevel(ServerLevel level);
    }

    private static final Map<EntityType<?>, Boolean> HEAVY = new IdentityHashMap<>();
    private static final Map<WeightedList<MobSpawnSettings.SpawnerData>, WeightedList<MobSpawnSettings.SpawnerData>>
            POOLS = new LinkedHashMap<>(32, .75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<
                                        WeightedList<MobSpawnSettings.SpawnerData>,
                                        WeightedList<MobSpawnSettings.SpawnerData>>
                                e) {
                    return size() > 256;
                }
            };

    public static boolean active(ServerLevel l) {
        return l != null && l.dimension() == Level.OVERWORLD && LightDragons.night(l);
    }

    public static int cap(int normal, ServerLevel l, MobCategory category) {
        return category == MobCategory.MONSTER && active(l) ? Math.round(normal * 1.5f) : normal;
    }

    @SuppressWarnings("unchecked")
    public static boolean heavy(EntityType<?> type) {
        return HEAVY.computeIfAbsent(type, t -> {
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
            if (id.getNamespace().equals("mutantmonsters") || id.getNamespace().equals("mutantszombies")) return true;
            if (!DefaultAttributes.hasSupplier(t)) return false;
            var attrs = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) t);
            return attrs.hasAttribute(Attributes.MAX_HEALTH)
                    && attrs.getBaseValue(Attributes.MAX_HEALTH) >= Monsters.HEAVY_HEALTH;
        });
    }

    /** Heavy monsters' night spawn weight: the mutants and their like (owner, 2026-11-15: four times the old 2x). */
    static final int HEAVY_NIGHT_WEIGHT = 8;
    /** Every other companion-mod monster, the exotics a night should show besides zombies, skeletons and spiders. */
    static final int EXOTIC_NIGHT_WEIGHT = 4;

    /**
     * How much likelier a monster kind is at night. Vanilla's, Lightborn's own and Mob Variants' reskins of vanilla
     * kinds keep their weight; heavy kinds and the companion mods' own creatures are boosted.
     */
    static int nightWeight(EntityType<?> type) {
        if (heavy(type)) return HEAVY_NIGHT_WEIGHT;
        String ns = BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace();
        return ns.equals("minecraft") || ns.equals("lightborn") || ns.equals("frycmobvariants")
                ? 1
                : EXOTIC_NIGHT_WEIGHT;
    }

    public static WeightedList<MobSpawnSettings.SpawnerData> pool(
            ServerLevel l, MobCategory category, WeightedList<MobSpawnSettings.SpawnerData> original) {
        if (category != MobCategory.MONSTER || !active(l)) return original;
        return POOLS.computeIfAbsent(original, source -> {
            if (source.unwrap().stream().allMatch(w -> nightWeight(w.value().type()) == 1)) return source;
            var b = WeightedList.<MobSpawnSettings.SpawnerData>builder();
            for (var entry : source.unwrap())
                b.add(entry.value(), entry.weight() * nightWeight(entry.value().type()));
            return b.build();
        });
    }

    private static final net.minecraft.resources.Identifier MUTANT_CREEPER =
            net.minecraft.resources.Identifier.parse("mutantmonsters:mutant_creeper");

    /** Natural nighttime mutants are rare within 96 horizontal blocks of a home. */
    public static boolean rejectHomeMutant(ServerLevel level, Mob mob) {
        return active(level)
                && BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).equals(MUTANT_CREEPER)
                && LightWards.nearHome(level, mob.blockPosition(), 96)
                && mob.getRandom().nextInt(12) != 0;
    }

    static void init() {
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((s, r, ok) -> clear());
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> clear());
    }

    private static void clear() {
        HEAVY.clear();
        POOLS.clear();
    }
}
