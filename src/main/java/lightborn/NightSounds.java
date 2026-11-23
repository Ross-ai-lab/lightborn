package lightborn;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

/**
 * The night's own voice (owner, 2026-11-17; more of it 2026-11-18): outside a ward after dark, every 30 to 180 seconds
 * something roars, wails, stirs, screams, howls, batters a door, shrieks, tolls, breathes like the wither, rumbles in
 * the deep or cries out, far off, from a real direction 50 to 90 blocks away, so the dark sounds full of things you
 * cannot see. Inside a ward it is quiet. The sounds and the Dark Phantom's voice are vanilla recordings pitched and spread far in the mod's
 * sounds.json (attenuation 128 blocks), so they read as huge and distant rather than as a monster beside you.
 */
public final class NightSounds {
    private NightSounds() {}

    public static SoundEvent ROAR, WAIL, STIR, SCREAM, HOWL, KNOCK, SHRIEK, TOLL, WITHER, DEEP, CRY;

    /** Every far sound, each as likely as the next. */
    private static SoundEvent[] far;

    public static SoundEvent PHANTOM_AMBIENT, PHANTOM_HURT, PHANTOM_DEATH, PHANTOM_FLAP, PHANTOM_SWOOP, PHANTOM_HEAR;

    private static SoundEvent register(String name) {
        Identifier id = ModItems.id(name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    static void init() {
        ROAR = register("night.roar");
        WAIL = register("night.wail");
        STIR = register("night.stir");
        SCREAM = register("night.scream");
        HOWL = register("night.howl");
        KNOCK = register("night.knock");
        SHRIEK = register("night.shriek");
        TOLL = register("night.toll");
        WITHER = register("night.wither");
        DEEP = register("night.deep");
        CRY = register("night.cry");
        far = new SoundEvent[] {ROAR, WAIL, STIR, SCREAM, HOWL, KNOCK, SHRIEK, TOLL, WITHER, DEEP, CRY};
        PHANTOM_AMBIENT = register("dark_phantom.ambient");
        PHANTOM_HURT = register("dark_phantom.hurt");
        PHANTOM_DEATH = register("dark_phantom.death");
        PHANTOM_FLAP = register("dark_phantom.flap");
        PHANTOM_SWOOP = register("dark_phantom.swoop");
        PHANTOM_HEAR = register("dark_phantom.hear");
    }

    /** The game tick each player hears the next far sound; set on first sight. */
    private static final Map<ServerPlayer, Long> NEXT = new WeakHashMap<>();

    /** Every 30 to 180 seconds per player outside a ward at night in the Overworld: one far sound, for them alone. */
    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        var level = server.overworld();
        if (!LightDragons.night(level)) return;
        long now = level.getGameTime();
        var random = level.getRandom();
        for (var p : level.players()) {
            if (p.isSpectator() || p.level().dimension() != Level.OVERWORLD) continue;
            long next = NEXT.computeIfAbsent(p, k -> now + 600 + random.nextInt(3000));
            if (now < next) continue;
            NEXT.put(p, now + 600 + random.nextInt(3000)); // 30 to 180 seconds (owner, 2026-11-18)
            if (LightWards.covers(level, p.blockPosition())) continue;
            double a = random.nextDouble() * Math.PI * 2, d = 50 + random.nextDouble() * 40;
            double x = p.getX() + Math.cos(a) * d,
                    z = p.getZ() + Math.sin(a) * d,
                    y = p.getY() + 6 + random.nextInt(14);
            SoundEvent sound = far[random.nextInt(far.length)];
            float pitch = .85f + random.nextFloat() * .3f;
            p.connection.send(new ClientboundSoundPacket(
                    BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                    SoundSource.HOSTILE,
                    x,
                    y,
                    z,
                    1f,
                    pitch,
                    random.nextLong()));
        }
    }
}
