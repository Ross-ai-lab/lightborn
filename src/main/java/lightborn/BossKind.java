package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

/** Fixed roster. The order is the scheduled Hunt progression. */
enum BossKind {
    GORATH("gorath", "Gorath the Unbroken", "unbroken_plate", () -> ModEntities.GIANT_ZOMBIE),
    VEXMORA("vexmora", "Vexmora, Bonecaller", "bonecallers_staff", () -> ModEntities.DEADEYE_SKELETON),
    MAELIS("maelis", "Maelis the Weaver", "silkstep_boots", () -> ModEntities.WEAVER_SPIDER),
    SYRITH("syrith", "Syrith the Undying", "syriths_fang", () -> ModEntities.VAMPIRE_ZOMBIE),
    OZRIK("ozrik", "Ozrik Ashbringer", "ashbringer", () -> ModEntities.EMBER_SKELETON),
    NYX("nyx", "Nyx, Herald of the Dark Moon", "tome_eclipse", () -> ModEntities.DARKNESS_ZOMBIE);

    static final List<BossKind> ROSTER = List.of(values());
    static final Codec<BossKind> CODEC = Codec.STRING.comapFlatMap(
            id -> ROSTER.stream()
                    .filter(kind -> kind.id.equals(id))
                    .findFirst()
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unknown Lightborn boss " + id)),
            kind -> kind.id);
    final String id, title, trophyId;
    private final Supplier<EntityType<? extends Mob>> body;

    BossKind(String id, String title, String trophyId, Supplier<EntityType<? extends Mob>> body) {
        this.id = id;
        this.title = title;
        this.trophyId = trophyId;
        this.body = body;
    }

    EntityType<? extends Mob> body() {
        return body.get();
    }

    static BossKind random(RandomSource random) {
        return ROSTER.get(random.nextInt(ROSTER.size()));
    }

    static BossKind scheduled(int faced, RandomSource random) {
        int index = faced - 2;
        return index >= 0 && index < ROSTER.size() ? ROSTER.get(index) : random(random);
    }
}
