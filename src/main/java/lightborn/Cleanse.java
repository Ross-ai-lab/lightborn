package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The Cleanse (owner, 2026-11-15; it replaced the Dark Moon Hunts and the Hunt Brazier). Dark shards found in the
 * world are dissolved in the Chest of Light, and the dark comes for whoever did it: one continuous wave from beyond
 * the ward, as big as the shards fed (one to ten), and on the biggest feeds a single boss once the wave is beaten.
 * While a Cleanse runs that chest's ward still shines but no longer shields: monsters walk in, and the players inside
 * may fight. Started after dusk from the chest's Cleanse tab; dawn ends it, and fed shards are never returned.
 */
public final class Cleanse {
    private Cleanse() {}

    /** A running Cleanse, saved on the Overworld so a restart resumes it the same night. */
    record Called(BlockPos anchor, int shards, long night, boolean boss, Map<UUID, Integer> deaths) {
        static final Codec<Called> CODEC = RecordCodecBuilder.create(i -> i.group(
                        BlockPos.CODEC.fieldOf("anchor").forGetter(Called::anchor),
                        Codec.intRange(1, Balance.CLEANSE_MAX_SHARDS)
                                .fieldOf("shards")
                                .forGetter(Called::shards),
                        Codec.LONG.fieldOf("night").forGetter(Called::night),
                        Codec.BOOL.fieldOf("boss").forGetter(Called::boss),
                        Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT)
                                .fieldOf("deaths")
                                .forGetter(Called::deaths))
                .apply(i, Called::new));

        Called withDeaths(UUID player, int count) {
            var next = new HashMap<>(deaths);
            next.put(player, count);
            return new Called(anchor, shards, night, boss, Map.copyOf(next));
        }
    }

    // ---------------------------------------------------------------- chest tab

    public record Ask(BlockPos chest) implements CustomPacketPayload {
        public static final Type<Ask> TYPE = new Type<>(ModItems.id("cleanse_ask"));
        static final StreamCodec<RegistryFriendlyByteBuf, Ask> CODEC =
                BlockPos.STREAM_CODEC.map(Ask::new, Ask::chest).cast();

        @Override
        public Type<Ask> type() {
            return TYPE;
        }
    }

    public record Start(BlockPos chest, int shards) implements CustomPacketPayload {
        public static final Type<Start> TYPE = new Type<>(ModItems.id("cleanse_start"));
        static final StreamCodec<RegistryFriendlyByteBuf, Start> CODEC = StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Start::chest, ByteBufCodecs.VAR_INT, Start::shards, Start::new)
                .cast();

        @Override
        public Type<Start> type() {
            return TYPE;
        }
    }

    /**
     * The tab's state: why a Cleanse cannot start now ("" when it can), how many monsters are left in a running one
     * here (-1 when none runs here), its total, and a line to show (the answer to the last Start).
     */
    public record Show(BlockPos chest, String problem, int left, int total, String message)
            implements CustomPacketPayload {
        public static final Type<Show> TYPE = new Type<>(ModItems.id("cleanse_show"));
        static final StreamCodec<RegistryFriendlyByteBuf, Show> CODEC = StreamCodec.composite(
                        BlockPos.STREAM_CODEC,
                        Show::chest,
                        ByteBufCodecs.STRING_UTF8,
                        Show::problem,
                        ByteBufCodecs.VAR_INT,
                        Show::left,
                        ByteBufCodecs.VAR_INT,
                        Show::total,
                        ByteBufCodecs.STRING_UTF8,
                        Show::message,
                        Show::new)
                .cast();

        @Override
        public Type<Show> type() {
            return TYPE;
        }
    }

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Ask.TYPE, Ask.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Start.TYPE, Start.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Show.TYPE, Show.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Ask.TYPE, (p, ctx) -> {
            if (LightShop.near(ctx.player(), p.chest()))
                ctx.responseSender().sendPacket(show(ctx.player(), p.chest(), ""));
        });
        ServerPlayNetworking.registerGlobalReceiver(Start.TYPE, (p, ctx) -> {
            if (!LightShop.near(ctx.player(), p.chest())) return;
            String message = begin(ctx.player(), p.chest(), p.shards());
            ctx.responseSender().sendPacket(show(ctx.player(), p.chest(), message));
        });
        zombies = List.of(EntityTypes.ZOMBIE, EntityTypes.ZOMBIE, EntityTypes.HUSK, ModEntities.DARKNESS_ZOMBIE);
        skeletons = List.of(ModEntities.EMBER_SKELETON, ModEntities.DEADEYE_SKELETON, EntityTypes.SKELETON);
        spiders = List.of(ModEntities.HUNTER_SPIDER, ModEntities.WEAVER_SPIDER, EntityTypes.SPIDER);
    }

    static Show show(ServerPlayer p, BlockPos chest, String message) {
        var level = p.level();
        Run run = current;
        boolean here = run != null && run.level == level && run.called.anchor.equals(chest);
        String problem =
                problem(level, chest, Hordes.MoonNight.read(level.getServer().overworld()));
        return new Show(chest, problem == null ? "" : problem, here ? run.left() : -1, here ? run.total : 0, message);
    }

    /** How many of a wave of {@code total} must fall for it to count as beaten: nine in ten, rounded up. */
    static int beatenAt(int total) {
        return (int) Math.ceil(total * Balance.CLEANSE_BEATEN_SHARE);
    }

    /** Why a Cleanse cannot begin at this chest now, or null. */
    static String problem(ServerLevel level, BlockPos chest, Hordes.MoonNight clock) {
        if (level != level.getServer().overworld()) return "The dark answers only in the Overworld.";
        if (!Hordes.enabled(level)) return "A Cleanse needs monsters to be enabled.";
        if (current != null || level.getAttached(ModData.CLEANSE) != null) return "A Cleanse is already under way.";
        if (!LightWards.registered(level, chest)) return "This chest holds no ward yet.";
        if (!clock.darkHours()) return "The dark comes only at night. Begin after dusk.";
        return null;
    }

    /** Dissolves {@code shards} dark shards from the player's inventory and calls the dark. Returns a line to show. */
    static String begin(ServerPlayer p, BlockPos chest, int shards) {
        var level = p.level();
        var clock = Hordes.MoonNight.read(level.getServer().overworld());
        String problem = problem(level, chest, clock);
        if (problem != null) return problem;
        if (!ChestTabs.cleanse(p)) return "Beat a Dark Fountain first.";
        shards = Math.clamp(shards, 1, Balance.CLEANSE_MAX_SHARDS);
        if (!p.isCreative() && Enchanting.count(p, ModItems.DARK_SHARD) < shards)
            return "You carry only " + Enchanting.count(p, ModItems.DARK_SHARD) + " dark shards.";
        if (!p.isCreative()) Enchanting.consume(p, ModItems.DARK_SHARD, shards);
        call(level, p, chest, shards, clock);
        return "";
    }

    static void call(ServerLevel level, ServerPlayer caller, BlockPos anchor, int shards, Hordes.MoonNight clock) {
        var called = new Called(
                anchor.immutable(),
                shards,
                clock.day(),
                shards >= Balance.CLEANSE_BOSS_SHARDS,
                Map.of(caller.getUUID(), 0));
        level.setAttached(ModData.CLEANSE, called);
        var run = new Run(level, called);
        current = run;
        LightWards.suspend(level, anchor, true);
        run.grudge = Bosses.revengeFor(caller);
        var at = Vec3.atCenterOf(anchor);
        level.sendParticles(ParticleTypes.SQUID_INK, at.x, at.y + .6, at.z, 40, .3, .4, .3, .05);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1, at.z, 60, .4, .6, .4, .1);
        level.playSound(null, anchor, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.2f, .6f);
        run.join(caller);
    }

    // ---------------------------------------------------------------- engine

    private static Run current;
    private static final Map<UUID, Run> HUNTERS = new HashMap<>();
    private static final String ARENA_TAG = "lightborn_arena_hunter";
    private static List<EntityType<? extends Mob>> zombies, skeletons, spiders;

    /** True while a Cleanse holds this player (they are in its arena and still in it). */
    static boolean active(ServerPlayer player) {
        Run run = current;
        return run != null && run.party.contains(player.getUUID()) && run.inRange(player);
    }

    static boolean running() {
        return current != null;
    }

    /** True while a Cleanse is being fought at this ward chest. */
    static boolean at(ServerLevel level, BlockPos chest) {
        Run run = current;
        return run != null && run.level == level && run.called.anchor.equals(chest);
    }

    static boolean sleepBlocked(Player player) {
        return player instanceof ServerPlayer p && active(p);
    }

    static Player.BedSleepingProblem sleepProblem(Player player, BlockPos ignored) {
        if (!sleepBlocked(player)) return null;
        ((ServerPlayer) player)
                .sendOverlayMessage(Component.literal("The dark is coming. You cannot rest.")
                        .withStyle(ChatFormatting.DARK_RED));
        return Player.BedSleepingProblem.OTHER_PROBLEM;
    }

    /** One wave's size for a Cleanse: a Hunt wave for this party, times the shards' weight. */
    public static int size(int party, int shards) {
        int base = Balance.HUNT_SIZE_BASE;
        double players = 1 + Balance.HUNT_EXTRA_PLAYER_SIZE * (Math.max(1, party) - 1);
        return (int) Math.ceil(base * players * Balance.cleanseWeight(shards));
    }

    static void tick(ServerLevel level, Hordes.MoonNight night) {
        tick(level, night, level.getServer().getPlayerList().getPlayers(), Scheduler.now());
    }

    static void tick(ServerLevel level, Hordes.MoonNight night, List<ServerPlayer> players, long now) {
        boolean enabled = Hordes.enabled(level);
        var saved = level.getAttached(ModData.CLEANSE);
        if (saved != null && current == null) {
            if (enabled && night.day() == saved.night && night.darkHours() && level.isLoaded(saved.anchor)) {
                current = new Run(level, saved);
                LightWards.suspend(level, saved.anchor, true);
            } else if (!night.darkHours() || night.day() != saved.night || !enabled) {
                level.removeAttached(ModData.CLEANSE);
                LightWards.suspend(level, saved.anchor, false);
            }
        }
        Run run = current;
        if (run == null || run.level != level) return;
        if (!enabled || night.day() < run.called.night) {
            run.close();
            return;
        }
        if (night.day() > run.called.night || night.time() >= Balance.DAWN_TICK) {
            run.dawn(players);
            return;
        }
        run.advance(players, now);
    }

    /** The running Cleanse's state, for the self-test's failure lines. */
    static String debug() {
        Run run = current;
        if (run == null) return "none";
        return "queued=" + run.queued.size() + " hunters=" + run.hunters.size() + " boss=" + run.called.boss + " sent="
                + run.bossSent + " down=" + run.bossDown + " started=" + run.started;
    }

    static void onLoad(Entity entity) {
        if (!(entity instanceof Mob mob) || !mob.entityTags().contains(Hordes.HUNTER_TAG)) return;
        Run run = HUNTERS.get(mob.getUUID());
        if (run == null) release(mob);
        else run.hunters.get(mob.getUUID()).mob = mob;
    }

    static void onDeath(LivingEntity dead) {
        Run run = current;
        if (run == null) return;
        if (dead instanceof ServerPlayer p) {
            if (run.party.contains(p.getUUID()) && run.inRange(p)) run.death(p, Scheduler.now());
            return;
        }
        if (HUNTERS.remove(dead.getUUID()) == null) return;
        Hunter hunter = run.hunters.remove(dead.getUUID());
        if (hunter != null && hunter.boss != null) run.bossDown = true;
        if (run.cleared()) run.finish();
    }

    /** Server stop or reload: the saved Cleanse resumes on return, so clear its loaded wave before it can stack. */
    static void clear() {
        Run run = current;
        if (run == null) return;
        for (Hunter hunter : run.hunters.values()) if (!hunter.mob.isRemoved()) hunter.mob.discard();
        run.detach();
    }

    static void onDisconnect(ServerPlayer player) {
        if (current != null) current.bar.removePlayer(player);
    }

    private static void release(Mob mob) {
        mob.removeTag(Hordes.HUNTER_TAG);
        if (mob.removeTag(ARENA_TAG)) mob.clearHome();
        mob.setTarget(null);
        mob.getNavigation().stop();
    }

    private static final class Hunter {
        Mob mob;
        final EntityType<? extends Mob> type;
        final Bosses.Encounter boss;

        Hunter(Mob mob, EntityType<? extends Mob> type, Bosses.Encounter boss) {
            this.mob = mob;
            this.type = type;
            this.boss = boss;
        }
    }

    private static final class Run {
        final ServerLevel level;
        Called called;
        final ServerBossEvent bar = new ServerBossEvent(
                UUID.randomUUID(),
                Component.literal("The Cleanse"),
                BossEvent.BossBarColor.PURPLE,
                BossEvent.BossBarOverlay.NOTCHED_10);
        final Set<UUID> party = new LinkedHashSet<>();
        final Map<UUID, Hunter> hunters = new HashMap<>();
        final ArrayDeque<EntityType<? extends Mob>> queued = new ArrayDeque<>();
        final double arena;
        Bosses.Encounter grudge, boss;
        int total, approach, sides;
        double direction;
        long nextPack, pauseUntil;
        boolean started, bossSent, bossDown;

        Run(ServerLevel level, Called called) {
            this.level = level;
            this.called = called;
            arena = LightWards.radiusAt(level, called.anchor) + Balance.CLEANSE_ARENA_MARGIN;
        }

        /**
         * The wave is beaten once nine in ten of it are down (owner, 2026-11-18): this many stragglers may still stand.
         * They stay behind as ordinary monsters when the Cleanse ends.
         */
        int stragglers() {
            return total - beatenAt(total);
        }

        /** The wave's monsters still to come or still standing, its boss apart. */
        int waveLeft() {
            int alive = 0;
            for (var h : hunters.values()) if (h.boss == null) alive++;
            return queued.size() + alive;
        }

        boolean waveBeaten() {
            return started && queued.isEmpty() && waveLeft() <= stragglers();
        }

        /** What must still fall: the wave down to its stragglers, then the boss when one was called. */
        int left() {
            return Math.max(0, waveLeft() - stragglers()) + (called.boss && !bossDown ? 1 : 0);
        }

        boolean inRange(ServerPlayer p) {
            return p.level() == level && Vec3.atCenterOf(called.anchor).distanceToSqr(p.position()) <= arena * arena;
        }

        void join(ServerPlayer p) {
            if (!called.deaths.containsKey(p.getUUID())) save(called.withDeaths(p.getUUID(), 0));
            party.add(p.getUUID());
            if (p.isSleeping()) p.stopSleepInBed(true, true);
            p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 30));
            p.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal("The dark answers").withStyle(ChatFormatting.DARK_PURPLE)));
            p.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal("Your ward shines, but it no longer shields you")
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
            Hordes.horn(p);
        }

        void save(Called next) {
            called = next;
            level.setAttached(ModData.CLEANSE, called);
        }

        void death(ServerPlayer p, long now) {
            int deaths = called.deaths.getOrDefault(p.getUUID(), 0) + 1;
            save(called.withDeaths(p.getUUID(), deaths));
            pauseUntil = Math.max(pauseUntil, now + Balance.HUNT_DEATH_PAUSE_TICKS);
            if (deaths < Balance.HUNT_DEATH_LIMIT) return;
            party.remove(p.getUUID());
            bar.removePlayer(p);
            p.sendSystemMessage(
                    Component.literal("The dark has beaten you this night.").withStyle(ChatFormatting.DARK_RED));
            if (party.isEmpty() && called.deaths.values().stream().allMatch(n -> n >= Balance.HUNT_DEATH_LIMIT)) {
                for (var hunter : hunters.values()) release(hunter.mob);
                close();
            }
        }

        /** The players the last tick looked at, so a finish set off by a kill still knows who was there. */
        private List<ServerPlayer> seen = List.of();

        void advance(List<ServerPlayer> players, long now) {
            seen = players;
            for (ServerPlayer p : players) {
                if (Hordes.huntable(p)
                        && inRange(p)
                        && !party.contains(p.getUUID())
                        && called.deaths.getOrDefault(p.getUUID(), 0) < Balance.HUNT_DEATH_LIMIT) join(p);
            }
            List<ServerPlayer> targets = players.stream()
                    .filter(p -> party.contains(p.getUUID()) && Hordes.huntable(p) && inRange(p))
                    .toList();
            for (ServerPlayer p : List.copyOf(bar.getPlayers())) if (!targets.contains(p)) bar.removePlayer(p);
            for (ServerPlayer p : targets) bar.addPlayer(p);
            if (!started) {
                started = true;
                total = size(Math.max(1, targets.size()), called.shards);
                queued.addAll(mix(level.getRandom(), called.shards, total));
                direction = level.getRandom().nextDouble() * Math.PI * 2;
                sides = Hordes.between(level.getRandom(), Balance.HUNT_APPROACH_MIN, Balance.HUNT_APPROACH_MAX);
                nextPack = now;
            }
            recoverMissing(targets);
            if (cleared()) {
                finish();
                return;
            }
            if (now >= pauseUntil && now >= nextPack && hunters.size() < Balance.HUNT_ALIVE_CAP) {
                if (!queued.isEmpty()) {
                    sendPack(targets);
                    nextPack = now + Balance.HUNT_PACK_TICKS;
                } else if (called.boss && !bossSent && waveBeaten()) {
                    sendBoss(targets);
                    nextPack = now + Balance.HUNT_PACK_TICKS;
                }
            }
            int all = total + (called.boss ? 1 : 0);
            // The dome cracks as the Cleanse wears on: two at the start, the rest as the Dark's packs fall.
            LightWards.scar(
                    level,
                    called.anchor,
                    2 + (int) Math.ceil((WardAura.MAX_SCARS - 2) * (all - left()) / (double) Math.max(1, all)));
            bar.setName(Component.literal(bossSent && !bossDown ? bossName() : "The Cleanse · " + left() + " left"));
            bar.setProgress(Math.clamp(left() / (float) Math.max(1, all), 0f, 1f));
        }

        String bossName() {
            return boss == null ? "The Cleanse" : boss.kind().title;
        }

        void sendPack(List<ServerPlayer> targets) {
            var random = level.getRandom();
            int size = Math.min(
                    Balance.HUNT_ALIVE_CAP - hunters.size(),
                    Hordes.between(random, Balance.HUNT_PACK_MIN, Balance.HUNT_PACK_MAX));
            double angle = direction + 2 * Math.PI * (approach % sides) / sides;
            approach++;
            for (int i = 0, tries = Math.min(size, queued.size()); i < tries; i++) {
                var type = queued.removeFirst();
                if (!add(targets, type, angle, null)) queued.addLast(type);
            }
        }

        /** After the wave is beaten: one boss, the caller's grudge if they owe one, else a random named boss. */
        void sendBoss(List<ServerPlayer> targets) {
            if (boss == null)
                boss = grudge != null ? grudge : Bosses.Encounter.normal(BossKind.random(level.getRandom()));
            double angle = direction + level.getRandom().nextDouble() * Math.PI * 2;
            if (!add(targets, boss.kind().body(), angle, boss)) return;
            bossSent = true;
            for (ServerPlayer p : targets) {
                Hordes.horn(p);
                p.sendOverlayMessage(Component.literal(boss.kind().title + " answers for the dark.")
                        .withStyle(ChatFormatting.DARK_RED));
            }
        }

        boolean add(
                List<ServerPlayer> targets, EntityType<? extends Mob> type, double angle, Bosses.Encounter encounter) {
            Mob mob = Hordes.prepare(level, Vec3.atBottomCenterOf(called.anchor), type, angle, true, encounter);
            if (mob == null) return false;
            mob.addTag(Hordes.HUNTER_TAG);
            mob.addTag(ARENA_TAG);
            mob.setHomeTo(called.anchor, (int) arena);
            hunters.put(mob.getUUID(), new Hunter(mob, type, encounter));
            HUNTERS.put(mob.getUUID(), this);
            if (level.addFreshEntity(mob)) {
                aim(mob, visible(mob, targets));
                return true;
            }
            hunters.remove(mob.getUUID());
            HUNTERS.remove(mob.getUUID());
            mob.discard();
            return false;
        }

        void recoverMissing(List<ServerPlayer> targets) {
            var iterator = hunters.entrySet().iterator();
            var anchor = Vec3.atCenterOf(called.anchor);
            while (iterator.hasNext()) {
                var entry = iterator.next();
                Hunter hunter = entry.getValue();
                Mob mob = hunter.mob;
                boolean near = mob.level() == level && mob.position().distanceToSqr(anchor) <= 4 * arena * arena;
                if (near && !mob.isRemoved()) {
                    var nearest = targets.stream()
                            .min(Comparator.comparingDouble(mob::distanceToSqr))
                            .orElse(null);
                    aim(mob, nearest != null && !BossTrophies.concealed(nearest) ? nearest : visible(mob, targets));
                    continue;
                }
                // A briefly unloaded mob can reattach. Replace one left far behind, or one that despawned.
                if (near
                        && mob.getRemovalReason() != null
                        && mob.getRemovalReason().shouldSave()) continue;
                release(mob);
                if (!mob.isRemoved()) mob.discard();
                iterator.remove();
                HUNTERS.remove(entry.getKey());
                if (hunter.boss != null) bossSent = false;
                else queued.addLast(hunter.type);
            }
        }

        ServerPlayer visible(Mob mob, List<ServerPlayer> targets) {
            return targets.stream()
                    .filter(p -> !BossTrophies.concealed(p))
                    .min(Comparator.comparingDouble(mob::distanceToSqr))
                    .orElse(null);
        }

        void aim(Mob mob, ServerPlayer target) {
            if (target != null) {
                Hordes.aim(mob, target);
                return;
            }
            if (mob.getTarget() != null) {
                mob.setTarget(null);
                mob.getNavigation().stop();
            }
            if (mob.getNavigation().isDone()) {
                var pos = Vec3.atBottomCenterOf(called.anchor);
                mob.getNavigation().moveTo(pos.x, pos.y, pos.z, 2, 1.0);
            }
        }

        boolean cleared() {
            return waveBeaten() && (!called.boss || bossSent && bossDown);
        }

        void finish() {
            var present = java.util.stream.Stream.concat(
                            level.getServer().getPlayerList().getPlayers().stream(), seen.stream())
                    .distinct()
                    .filter(p -> party.contains(p.getUUID()) && inRange(p))
                    .toList();
            dropCache(present.isEmpty() ? null : present.getFirst());
            var at = Vec3.atCenterOf(called.anchor);
            level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 1, at.z, 80, .5, 1.2, .5, .08);
            level.playSound(null, called.anchor, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.2f, 1.2f);
            for (ServerPlayer p : present) {
                p.setAttached(ModData.HUNTS_CLEARED, p.getAttachedOrElse(ModData.HUNTS_CLEARED, 0L) + 1);
                if (boss != null) Bosses.settleGrudge(p, boss);
                p.sendSystemMessage(Component.literal("The Cleanse is done. The dark lets go of this place.")
                        .withStyle(ChatFormatting.GOLD));
            }
            close();
        }

        void dawn(List<ServerPlayer> players) {
            for (ServerPlayer p : players) {
                if (!party.contains(p.getUUID()) || !inRange(p)) continue;
                ItemStack sparks = new ItemStack(ModItems.SPARK, Balance.HUNT_SURVIVE_SPARKS);
                if (!p.isAlive() || !p.getInventory().add(sparks)) p.spawnAtLocation(level, sparks);
                p.sendSystemMessage(Component.literal("Dawn breaks and the dark draws back, the Cleanse unfinished.")
                        .withStyle(ChatFormatting.GOLD));
            }
            close();
        }

        void dropCache(ServerPlayer player) {
            var random = level.getRandom();
            double rarityBonus = (player == null ? 1 : player.getAttributeValue(ModAttributes.ITEM_RARITY))
                    * Balance.cleanseRarity(called.shards);
            int items = Balance.cleanseItems(called.shards);
            for (int i = 0; i < items; i++) {
                var rarity = LootRoller.rollRarity(random, rarityBonus);
                if (i == 0) {
                    if (called.shards >= Balance.CLEANSE_LEGENDARY_SHARDS) rarity = LootRoller.Rarity.LEGENDARY;
                    else if (rarity == LootRoller.Rarity.UNCOMMON) rarity = LootRoller.Rarity.RARE;
                }
                // The caller's mined ore decides the material; a bigger Cleanse pays like a stronger monster.
                var tier = OreTiers.pick(
                        random,
                        player,
                        called.shards >= Balance.CLEANSE_LEGENDARY_SHARDS
                                ? OreTiers.BOSS
                                : called.shards >= 4 ? 3 : 2); // pays like a tier 3, or a tier 2, monster
                if (tier != null) LootBeams.onDrop(drop(LootRoller.roll(random, tier, rarity)), rarity);
            }
            // The Cleanse's tome is as strong as its reward tier: 3, 4 with four shards, 5 for a legendary one.
            drop(TomeItem.roll(
                    random, called.shards >= Balance.CLEANSE_LEGENDARY_SHARDS ? 5 : called.shards >= 4 ? 4 : 3));
            drop(new ItemStack(ModItems.SPARK, Balance.CLEANSE_SPARKS_PER_ITEM * items));
        }

        ItemEntity drop(ItemStack stack) {
            var at = called.anchor;
            ItemEntity item = new ItemEntity(level, at.getX() + .5, at.getY() + 1.2, at.getZ() + .5, stack);
            item.setDeltaMovement(
                    (level.getRandom().nextDouble() - .5) * .25,
                    .3,
                    (level.getRandom().nextDouble() - .5) * .25);
            item.setDefaultPickUpDelay();
            level.addFreshEntity(item);
            return item;
        }

        void close() {
            level.removeAttached(ModData.CLEANSE);
            LightWards.suspend(level, called.anchor, false);
            detach();
        }

        void detach() {
            bar.removeAllPlayers();
            for (var entry : hunters.entrySet()) {
                HUNTERS.remove(entry.getKey());
                release(entry.getValue().mob);
            }
            hunters.clear();
            party.clear();
            if (current == this) current = null;
        }
    }

    /** The wave's mix: zombies always; ranged from two shards, spiders and vampires from three, giants from five. */
    static List<EntityType<? extends Mob>> mix(RandomSource random, int shards, int size) {
        var types = new ArrayList<EntityType<? extends Mob>>();
        int ranged = Math.max(1, size / Balance.HUNT_RANGED_DIVISOR);
        if (shards >= 2) add(types, skeletons, ranged, size, random);
        if (shards >= 3) add(types, spiders, ranged, size, random);
        if (shards >= 3) add(types, List.of(ModEntities.VAMPIRE_ZOMBIE), Math.max(1, size / 10), size, random);
        if (shards >= 5)
            add(types, List.of(ModEntities.GIANT_ZOMBIE), Math.max(1, size / Balance.HUNT_GIANT_DIVISOR), size, random);
        add(types, zombies, size - types.size(), size, random);
        Collections.shuffle(types, new java.util.Random(random.nextLong()));
        return types;
    }

    private static void add(
            List<EntityType<? extends Mob>> into,
            List<EntityType<? extends Mob>> pool,
            int count,
            int size,
            RandomSource random) {
        for (int i = 0; i < count && into.size() < size; i++) into.add(Hordes.pick(pool, random));
    }
}
