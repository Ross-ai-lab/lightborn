package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Rank of one monster: normal or boss. Saved with the entity and synced to clients. Monsters have no levels (owner,
 * 2026-11-16: a zombie is always a zombie, a mutant always a mutant); a "level" saved by older worlds is ignored.
 */
public record MonsterInfo(int rank) {
    public static final int NORMAL = 0;
    /**
     * Champions were removed (owner, 2026-11-09). Monsters saved as one still carry this rank and are turned back
     * into plain monsters when they load ({@link Monsters#onLoad}). Never assign it.
     */
    public static final int OLD_CHAMPION = 1;

    public static final int BOSS = 2;

    // Old saves also carry "level" and "mods" fields; the codec ignores both.
    public static final Codec<MonsterInfo> CODEC = RecordCodecBuilder.create(
            i -> i.group(Codec.INT.fieldOf("rank").forGetter(MonsterInfo::rank)).apply(i, MonsterInfo::new));

    public static final StreamCodec<ByteBuf, MonsterInfo> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(MonsterInfo::new, MonsterInfo::rank);
}
