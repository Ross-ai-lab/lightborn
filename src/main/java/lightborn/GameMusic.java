package lightborn;

import java.util.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.MoonPhase;

/** The server chooses the situation; the client selects a whole track from that situation's resource pool. */
public final class GameMusic {
    private GameMusic() {}

    public static final List<String> MOODS = List.of(
            "hearth", "wilds", "night", "stirring", "hunt", "boss", "nether", "end", "underground", "dragon", "finale");
    public static final Map<String, SoundEvent> EVENTS = new HashMap<>();

    public record Mood(String name) implements CustomPacketPayload {
        public static final Type<Mood> TYPE = new Type<>(ModItems.id("music_mood"));
        static final StreamCodec<RegistryFriendlyByteBuf, Mood> CODEC =
                ByteBufCodecs.STRING_UTF8.map(Mood::new, Mood::name).cast();

        public Type<Mood> type() {
            return TYPE;
        }
    }

    static void init() {
        for (String mood : MOODS) {
            var id = ModItems.id("music." + mood);
            EVENTS.put(
                    mood,
                    Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id)));
        }
        PayloadTypeRegistry.clientboundPlay().register(Mood.TYPE, Mood.CODEC);
    }

    static String mood(ServerPlayer p) {
        if (ChestTravel.flying(p)) return "dragon";
        var arena = p.level().getServer().overworld().getAttached(DragonStory.ARENA);
        var gate = p.level().getServer().overworld().getAttached(DragonStory.PRISON);
        if (p.level().dimension() == Level.OVERWORLD
                && DragonStory.chapters(p) >= 8
                && (arena != null && p.blockPosition().closerThan(arena, 32)
                        || gate != null && p.blockPosition().closerThan(gate, 45))) return "finale";
        if (!p.level()
                .getEntitiesOfClass(
                        Mob.class,
                        p.getBoundingBox().inflate(48),
                        m -> Monsters.info(m) != null && Monsters.info(m).rank() == MonsterInfo.BOSS)
                .isEmpty()) return "boss";
        if (Cleanse.active(p)) return "hunt";
        if (p.level().dimension() == Level.NETHER) return "nether";
        if (p.level().dimension() == Level.END) return "end";
        if (p.getY() < 0) return "underground";
        var moon = Hordes.MoonNight.read(p.level().getServer().overworld());
        if (moon.darkHours()) {
            var phase = moon.phase();
            return phase == MoonPhase.WANING_CRESCENT || phase == MoonPhase.THIRD_QUARTER ? "stirring" : "night";
        }
        return LightWards.covers(p.level(), p.blockPosition()) ? "hearth" : "wilds";
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 == 0)
            for (var p : server.getPlayerList().getPlayers()) ServerPlayNetworking.send(p, new Mood(mood(p)));
    }
}
