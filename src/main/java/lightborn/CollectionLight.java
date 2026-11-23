package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** One managed light per completed player, with shared ownership and saved crash cleanup. */
final class CollectionLight {
    private CollectionLight() {}

    record Placed(BlockPos pos, BlockState original) {
        static final Codec<Placed> CODEC = RecordCodecBuilder.create(i -> i.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(Placed::pos),
                        BlockState.CODEC.fieldOf("original").forGetter(Placed::original))
                .apply(i, Placed::new));
    }

    private record Site(ServerLevel level, BlockPos pos) {}

    private static final Map<UUID, Site> LEASES = new HashMap<>();
    private static final Map<UUID, Integer> STRENGTHS = new HashMap<>();

    static void awardIfComplete(ServerPlayer player) {
        if (Boolean.TRUE.equals(player.getAttached(ModData.LIGHT_COMPLETE))
                || !Collection.allComplete(Collection.collected(player))) return;
        player.sendSystemMessage(Component.literal(
                DragonStory.ending(player.level()) == 3
                        ? "I am with you in every little piece that has found its place. Carry my last light well."
                        : DragonStory.ending(player.level()) == 1
                                ? "You gathered the world without taking its night. I am proud to call you my friend. You are the Light."
                                : "You gathered every little piece of my scattered light. You are the Light."));
        player.setAttached(ModData.LIGHT_COMPLETE, true);
        player.connection.send(new ClientboundSetTitlesAnimationPacket(20, 100, 35));
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal("You are the Light").withStyle(ChatFormatting.GOLD)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal("Every little piece has found its place").withStyle(ChatFormatting.LIGHT_PURPLE)));
        player.level()
                .playSound(
                        null,
                        player.blockPosition(),
                        SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                        SoundSource.PLAYERS,
                        1,
                        1);
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8;
            player.level()
                    .sendParticles(
                            ParticleTypes.END_ROD,
                            player.getX() + Math.cos(angle) * 3,
                            player.getY() + 0.9,
                            player.getZ() + Math.sin(angle) * 3,
                            2,
                            0.1,
                            0.5,
                            0.1,
                            0.01);
        }
    }

    static void tick(MinecraftServer server) {
        var online = new java.util.HashSet<UUID>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            online.add(groundKey(player.getUUID()));
            tickPlayer(player);
        }
        // A player who left takes their lights with them.
        for (var key : List.copyOf(LEASES.keySet())) if (!online.contains(key)) release(key);
    }

    /** The second light a player can hold: the ground under gliding Light Wings (LightGear#groundLight). */
    static UUID groundKey(UUID player) {
        return new UUID(player.getMostSignificantBits() ^ 0x6C69676874L, player.getLeastSignificantBits());
    }

    private static void tickGround(ServerPlayer player) {
        UUID key = groundKey(player.getUUID());
        var at = player.isAlive() && !player.isSpectator() ? LightGear.groundLight(player) : null;
        if (at == null) {
            release(key);
            return;
        }
        Site site = new Site(player.level(), at);
        if (!site.equals(LEASES.get(key)) || !site.level.getBlockState(site.pos).is(Blocks.LIGHT)) {
            release(key);
            STRENGTHS.put(key, 15);
            place(key, site);
        }
    }

    static void tickPlayer(ServerPlayer player) {
        tickGround(player);
        int light = Boolean.TRUE.equals(player.getAttached(ModData.LIGHT_COMPLETE)) ? 15 : LightGear.radiance(player);
        if (!player.isAlive() || player.isSpectator() || light <= 0) {
            release(player.getUUID());
            return;
        }
        Site site = new Site(
                player.level(), BlockPos.containing(player.getEyePosition()).immutable());
        Site old = LEASES.get(player.getUUID());
        if (!site.equals(old)
                || STRENGTHS.getOrDefault(player.getUUID(), 0) != light
                || !site.level.getBlockState(site.pos).is(Blocks.LIGHT)) {
            release(player.getUUID());
            STRENGTHS.put(player.getUUID(), light);
            place(player.getUUID(), site);
        }
        if (light == 15 && Scheduler.now() % 10 == 0) {
            var level = player.level();
            double angle = level.getRandom().nextDouble() * Math.PI * 2;
            double x = Math.cos(angle) * 2.8, z = Math.sin(angle) * 2.8;
            level.sendParticles(
                    ParticleTypes.END_ROD,
                    player.getX() + x,
                    player.getY() + 0.8,
                    player.getZ() + z,
                    1,
                    0.03,
                    0.4,
                    0.03,
                    0.003);
            level.sendParticles(
                    ParticleTypes.WAX_ON,
                    player.getX() - x,
                    player.getY() + 0.5,
                    player.getZ() - z,
                    1,
                    0.03,
                    0.2,
                    0.03,
                    0.003);
        }
    }

    private static void place(UUID player, Site site) {
        var chunk = site.level.getChunkAt(site.pos);
        var records = chunk.getAttachedOrElse(ModData.COLLECTION_LIGHTS, List.of());
        var existing =
                records.stream().filter(record -> record.pos.equals(site.pos)).findFirst();
        if (existing.isPresent() && isManaged(site.level.getBlockState(site.pos), existing.get())) {
            LEASES.put(player, site);
            refresh(site);
            return;
        }
        BlockState before = site.level.getBlockState(site.pos);
        if (!before.isAir() && !before.is(Blocks.WATER)) return;
        var light = Blocks.LIGHT
                .defaultBlockState()
                .setValue(LightBlock.LEVEL, STRENGTHS.getOrDefault(player, 15))
                .setValue(LightBlock.WATERLOGGED, before.is(Blocks.WATER));
        var next = new ArrayList<>(
                records.stream().filter(record -> !record.pos.equals(site.pos)).toList());
        next.add(new Placed(site.pos, before));
        chunk.setAttached(ModData.COLLECTION_LIGHTS, List.copyOf(next));
        if (site.level.setBlock(site.pos, light, Block.UPDATE_ALL)) LEASES.put(player, site);
        else remove(site);
    }

    static void release(ServerPlayer player) {
        release(player.getUUID());
        release(groundKey(player.getUUID()));
    }

    private static void release(UUID key) {
        Site old = LEASES.remove(key);
        STRENGTHS.remove(key);
        if (old != null) {
            if (!LEASES.containsValue(old)) remove(old);
            else refresh(old);
        }
    }

    private static void refresh(Site site) {
        int strength = LEASES.entrySet().stream()
                .filter(e -> e.getValue().equals(site))
                .mapToInt(e -> STRENGTHS.getOrDefault(e.getKey(), 15))
                .max()
                .orElse(0);
        var current = site.level.getBlockState(site.pos);
        if (strength > 0 && current.is(Blocks.LIGHT))
            site.level.setBlock(site.pos, current.setValue(LightBlock.LEVEL, strength), Block.UPDATE_ALL);
    }

    private static boolean isManaged(BlockState current, Placed record) {
        return current.is(Blocks.LIGHT) && current.getValue(LightBlock.WATERLOGGED) == record.original.is(Blocks.WATER);
    }

    private static void remove(Site site) {
        remove(site, site.level.getChunkAt(site.pos));
    }

    private static void remove(Site site, LevelChunk chunk) {
        var records = chunk.getAttachedOrElse(ModData.COLLECTION_LIGHTS, List.of());
        for (Placed record : records) {
            if (record.pos.equals(site.pos) && isManaged(site.level.getBlockState(site.pos), record)) {
                site.level.setBlock(site.pos, record.original, Block.UPDATE_ALL);
            }
        }
        chunk.setAttached(
                ModData.COLLECTION_LIGHTS,
                records.stream().filter(record -> !record.pos.equals(site.pos)).toList());
    }

    static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
        var keep = new ArrayList<Placed>();
        for (Placed record : chunk.getAttachedOrElse(ModData.COLLECTION_LIGHTS, List.of())) {
            var site = new Site(level, record.pos);
            if (LEASES.containsValue(site)) {
                keep.add(record);
                continue;
            }
            // A world lookup here waits for this same chunk's load callback to finish. Use the supplied chunk.
            if (isManaged(chunk.getBlockState(record.pos), record)) {
                chunk.setBlockState(record.pos, record.original, 0);
                level.getChunkSource().getLightEngine().checkBlock(record.pos);
            }
        }
        chunk.setAttached(ModData.COLLECTION_LIGHTS, List.copyOf(keep));
    }

    static void clear() {
        var sites = List.copyOf(LEASES.values());
        LEASES.clear();
        STRENGTHS.clear();
        sites.forEach(CollectionLight::remove);
    }
}
