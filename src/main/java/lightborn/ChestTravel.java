package lightborn;

import com.mojang.serialization.Codec;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Dragon flights from a Chest of Light to a notable structure the player picks (owner, 2026-11-16): each kind has its
 * own price in Sparks, and the Dragon flies to the nearest one of that kind no trip or explorer map has led to yet.
 * The way home is the Homeward spell. A damage-cancellable three-second channel comes first; the Sparks are taken when
 * the player climbs on.
 */
public final class ChestTravel {
    private ChestTravel() {}

    /**
     * A kind of place the Dragon flies to: its name, its structure tag, its price, whether it lies underground, and
     * whether it lies in the Nether (the flight crosses over through the cloud veil).
     */
    public record Site(String name, String id, int price, boolean underground, boolean nether) {
        Site(String name, String id, int price, boolean underground) {
            this(name, id, price, underground, false);
        }

        public TagKey<Structure> tag() {
            return TagKey.create(net.minecraft.core.registries.Registries.STRUCTURE, ModItems.id("travel/" + id));
        }
    }

    /** The flight list, cheapest first (owner's prices, 2026-11-16). */
    public static final List<Site> SITES = List.of(
            new Site("Village", "village", 8, false),
            new Site("Witch Hut", "witch_hut", 10, false),
            new Site("Igloo", "igloo", 10, false),
            new Site("Desert Pyramid", "desert_pyramid", 12, false),
            new Site("Jungle Temple", "jungle_temple", 12, false),
            new Site("Pillager Outpost", "pillager_outpost", 12, false),
            new Site("Trial Chambers", "trial_chambers", 20, true),
            new Site("Woodland Mansion", "woodland_mansion", 30, false),
            new Site("Ancient City", "ancient_city", 30, true),
            // The fortress the Dragon's Scale points at, from chapter 6 (owner, 2026-11-16).
            new Site("Scale's Fortress", "fortress", 40, false, true));

    static final AttachmentType<Long> LAST_DAMAGE = AttachmentRegistry.create(
            ModItems.id("travel_last_damage"), b -> b.persistent(Codec.LONG).copyOnDeath());

    private static final class Channel {
        /** The world the flight is in: the start's, until a Nether flight crosses over in the cloud veil. */
        ServerLevel level;

        final ServerLevel origin, dest;
        final BlockPos source, landing;
        final Vec3 from, to;
        final boolean transformed, longTrip;
        final int cruise, price;
        final String site;
        LightDragon dragon;
        int age, resume;
        boolean boarded;
        boolean oldInvisible, oldPhysics, oldGravity;

        Channel(ServerPlayer p, BlockPos source, BlockPos landing, Site site, ServerLevel dest) {
            level = origin = p.level();
            this.dest = dest;
            this.source = source.immutable();
            this.landing = landing.immutable();
            this.price = site.price();
            this.site = site.name();
            from = p.position();
            to = Vec3.atBottomCenterOf(landing);
            transformed = DragonStory.ending(level) == 3;
            double distance = from.distanceTo(to);
            longTrip = distance > 600 || dest != origin;
            cruise = longTrip ? 300 : Math.max(1, (int) Math.ceil(distance / 1.5));
        }
    }

    private static final Map<UUID, Channel> CHANNELS = new HashMap<>();
    public static final AttachmentType<Integer> FLIGHT = AttachmentRegistry.create(
            ModItems.id("dragon_transit"),
            b -> b.initializer(() -> 0).syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.all()));
    public static final AttachmentType<Integer> CLOUD = AttachmentRegistry.create(
            ModItems.id("dragon_transit_cloud"),
            b -> b.initializer(() -> 0).syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly()));
    private static final AttachmentType<GlobalPos> RETURN =
            AttachmentRegistry.create(ModItems.id("dragon_transit_return"), b -> b.persistent(GlobalPos.CODEC));
    private static boolean releasing;

    public static boolean flying(LivingEntity p) {
        return p.getAttachedOrElse(FLIGHT, 0) > 0;
    }

    public static boolean transformed(LivingEntity p) {
        return p.getAttachedOrElse(FLIGHT, 0) == 2;
    }

    public static boolean lockDismount(Entity p) {
        return !releasing && !p.level().isClientSide() && p instanceof LivingEntity l && flying(l);
    }

    public record Ask(BlockPos source) implements CustomPacketPayload {
        public static final Type<Ask> TYPE = new Type<>(ModItems.id("travel_ask"));
        static final StreamCodec<RegistryFriendlyByteBuf, Ask> CODEC =
                BlockPos.STREAM_CODEC.map(Ask::new, Ask::source).cast();

        public Type<Ask> type() {
            return TYPE;
        }
    }

    /** Fly from this chest to the site at this index of {@link #SITES}. */
    public record Go(BlockPos source, int site) implements CustomPacketPayload {
        public static final Type<Go> TYPE = new Type<>(ModItems.id("travel_go"));
        static final StreamCodec<RegistryFriendlyByteBuf, Go> CODEC = StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Go::source, ByteBufCodecs.VAR_INT, Go::site, Go::new)
                .cast();

        public Type<Go> type() {
            return TYPE;
        }
    }

    /** {@code cleanses}: how many Cleanses the player has won, which opens the flights ({@link #cleansesFor}). */
    public record Show(BlockPos source, boolean own, int sparks, int cleanses, String message)
            implements CustomPacketPayload {
        public static final Type<Show> TYPE = new Type<>(ModItems.id("travel_show"));
        static final StreamCodec<RegistryFriendlyByteBuf, Show> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                Show::source,
                ByteBufCodecs.BOOL,
                Show::own,
                ByteBufCodecs.VAR_INT,
                Show::sparks,
                ByteBufCodecs.VAR_INT,
                Show::cleanses,
                ByteBufCodecs.STRING_UTF8,
                Show::message,
                Show::new);

        public Type<Show> type() {
            return TYPE;
        }
    }

    static void init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, s, a) -> !flying(e));
        UseItemCallback.EVENT.register((p, l, h) -> flying(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((p, l, h, hit) -> flying(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register(
                (p, l, h, e, hit) -> flying(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register(
                (p, l, h, e, hit) -> flying(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((l, p, pos, state, be) -> !flying(p));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var p = handler.player;
            var back = p.getAttached(RETURN);
            if (back != null) {
                var l = server.getLevel(back.dimension());
                if (l != null)
                    p.teleportTo(
                            l,
                            back.pos().getX() + .5,
                            back.pos().getY(),
                            back.pos().getZ() + .5,
                            Set.of(),
                            p.getYRot(),
                            0,
                            false);
                p.removeAttached(RETURN);
                p.setAttached(FLIGHT, 0);
                p.setAttached(CLOUD, 0);
                p.setNoGravity(false);
                p.noPhysics = false;
                p.onUpdateAbilities();
            }
        });
        PayloadTypeRegistry.serverboundPlay().register(Ask.TYPE, Ask.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Go.TYPE, Go.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Show.TYPE, Show.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Ask.TYPE, (a, c) -> {
            if (LightShop.near(c.player(), a.source)) reply(c.player(), a.source, "");
        });
        ServerPlayNetworking.registerGlobalReceiver(
                Go.TYPE,
                (a, c) -> reply(
                        c.player(),
                        a.source,
                        ChestTabs.travel(c.player()) ? start(c.player(), a.source, a.site) : ChestTabs.TRAVEL_LOCKED));
    }

    static void reply(ServerPlayer p, BlockPos source, String message) {
        if (!LightShop.near(p, source)) return;
        var owner =
                p.level().getAttachedOrElse(ModData.LIGHT_WARD_OWNERS, Map.of()).get(Long.toString(source.asLong()));
        boolean own = owner != null && owner.player().equals(p.getUUID());
        ServerPlayNetworking.send(p, new Show(source, own, LightShop.sparks(p), cleanses(p), message));
    }

    /**
     * Cleanses a flight needs: one destination opens per Cleanse won, easiest to find first (owner, 2026-11-17: the
     * Village after the first, the Ancient City after the ninth, Scale's Fortress after the tenth).
     */
    public static int cleansesFor(int index) {
        return index + 1;
    }

    static int cleanses(ServerPlayer p) {
        return (int) Math.min(Integer.MAX_VALUE, p.getAttachedOrElse(ModData.HUNTS_CLEARED, 0L));
    }

    /** Why this player cannot fly from this chest now, or null. */
    static String problem(ServerPlayer p, BlockPos source) {
        if (!LightShop.near(p, source)) return "Return to the Chest of Light.";
        if (CHANNELS.containsKey(p.getUUID())) return "The Dragon is already coming for you.";
        if (!p.isAlive() || p.isSpectator() || p.isPassenger()) return "Stand beside the chest to call the Dragon.";
        if (p.level().dimension() != Level.OVERWORLD) return "The Dragon flies only over the Overworld.";
        if (Cleanse.active(p)) return "Travel waits until the Cleanse is over.";
        if (p.level().getGameTime() - p.getAttachedOrElse(LAST_DAMAGE, -1000L) < 200)
            return "Wait ten seconds after taking damage.";
        return null;
    }

    /** Finds the nearest unvisited site of the chosen kind and calls the Dragon to fly there. */
    static String start(ServerPlayer p, BlockPos source, int index) {
        if (index < 0 || index >= SITES.size()) return "";
        String why = problem(p, source);
        if (why != null) return why;
        var site = SITES.get(index);
        int need = cleansesFor(index), done = cleanses(p);
        if (done < need)
            return site.name() + " opens after " + need + (need == 1 ? " Cleanse" : " Cleanses") + " (" + done
                    + " so far).";
        if (LightShop.sparks(p) < site.price()) return "This flight costs " + site.price() + " Sparks.";
        if (site.nether()) {
            if (DragonStory.chapters(p) < 6) return "The Dragon flies there once his Scale points the way (chapter 6).";
            var nether = p.level().getServer().getLevel(Level.NETHER);
            BlockPos fort = nether == null ? null : DragonStory.scaleFortress(p);
            if (fort == null) return "The Scale finds no fortress within reach.";
            var land = netherGround(nether, fort);
            if (land == null) return "The Dragon found no safe ground at the fortress. Try again.";
            return begin(p, source, land, site, nether);
        }
        // From chapter 8 the Ancient City flight goes to the prison city the Scale points at (owner, 2026-11-16).
        BlockPos at =
                site.id().equals("ancient_city") && DragonStory.chapters(p) >= 8 ? DragonStory.storyCity(p) : null;
        // Like an explorer map: the nearest one no trip or map has led to yet, so every flight goes somewhere new.
        if (at == null) at = p.level().findNearestMapStructure(site.tag(), source, 100, true);
        if (at == null) return "The Dragon knows of no " + site.name() + " left within reach.";
        var land = ground(p.level(), at.getX(), at.getZ());
        if (land == null) return "The Dragon found no safe ground there. Try again.";
        return begin(p, source, land, site);
    }

    /** Calls the Dragon to fly this player to a known landing (the self-test calls it directly). */
    static String begin(ServerPlayer p, BlockPos source, BlockPos land, Site site) {
        return begin(p, source, land, site, p.level());
    }

    /** As above, landing in {@code dest}: a Nether flight crosses over in the cloud veil. */
    static String begin(ServerPlayer p, BlockPos source, BlockPos land, Site site, ServerLevel dest) {
        String why = problem(p, source);
        if (why != null) return why;
        var ch = new Channel(p, source, land, site, dest);
        ch.dragon = StoryBosses.apparition(p, 6, p.position().add(-6, 12, -6));
        ch.dragon.arrive();
        CHANNELS.put(p.getUUID(), ch);
        p.closeContainer();
        p.sendOverlayMessage(Component.literal("The Dragon is coming. Stay in the light for three seconds."));
        return "Calling the Dragon... stay in the light.";
    }

    static void damaged(ServerPlayer p) {
        p.setAttached(LAST_DAMAGE, p.level().getGameTime());
        var ch = CHANNELS.get(p.getUUID());
        if (ch != null && !ch.boarded) {
            forget(p);
            p.sendOverlayMessage(Component.literal("Damage interrupted the Dragon's approach."));
        }
    }

    static boolean channeling(ServerPlayer p) {
        return CHANNELS.containsKey(p.getUUID());
    }

    static void forget(ServerPlayer p) {
        var ch = CHANNELS.remove(p.getUUID());
        if (ch != null) {
            if (ch.boarded) release(p, ch, false);
            if (ch.dragon != null) ch.dragon.discard();
        }
    }

    static void tick(MinecraftServer server) {
        for (var pair : List.copyOf(CHANNELS.entrySet())) {
            var p = server.getPlayerList().getPlayer(pair.getKey());
            var ch = pair.getValue();
            if (p == null) {
                ch.dragon.discard();
                CHANNELS.remove(pair.getKey());
                continue;
            }
            if (!p.isAlive()
                    || p.level() != ch.level
                    || (!ch.boarded
                            && (!LightShop.near(p, ch.source)
                                    || !ch.level.getBlockState(ch.source).is(ModItems.LIGHT_CHEST)
                                    || Cleanse.active(p)))) {
                forget(p);
                continue;
            }
            if (ch.dragon.isRemoved()) {
                forget(p);
                continue;
            }
            ch.age++;
            if (ch.boarded && !p.isPassenger() && ch.resume > 0 && ch.age >= ch.resume) {
                p.startRiding(ch.dragon, true, true);
                if (p.isPassenger()) {
                    ch.resume = 0;
                    p.connection.send(new ClientboundSetPassengersPacket(ch.dragon));
                }
            }
            if (ch.age <= 60) {
                double t = ch.age / 60d;
                move(ch, ch.from.add(-6 * (1 - t), .8 + 12 * (1 - t) * (1 - t), -6 * (1 - t)));
                if (ch.age == 60) {
                    if (!board(p, ch)) forget(p);
                    else if (!LightShop.pay(p, ch.price)) {
                        forget(p);
                        p.sendOverlayMessage(Component.literal("Not enough Sparks for the flight."));
                    } else
                        p.sendSystemMessage(Component.literal(
                                        "-" + ch.price + " Sparks. The Dragon carries you to the " + ch.site + ".")
                                .withStyle(net.minecraft.ChatFormatting.GOLD));
                }
                continue;
            }
            int t = ch.age - 60, total = 80 + ch.cruise;
            if (t >= total) {
                release(p, ch, true);
                ch.dragon.depart();
                CHANNELS.remove(pair.getKey());
                continue;
            }
            Vec3 at;
            if (t < 40) {
                double f = smooth(t / 40d);
                at = ch.from.lerp(new Vec3(ch.from.x, altitude(ch.level, ch.from.x, ch.from.z), ch.from.z), f);
            } else if (t < 40 + ch.cruise) {
                int c = t - 40;
                double f = c / (double) ch.cruise;
                if (!ch.longTrip) {
                    var point = ch.from.lerp(ch.to, f);
                    at = new Vec3(point.x, altitude(ch.level, point.x, point.z), point.z);
                } else {
                    var direction = ch.to.subtract(ch.from).normalize();
                    if (c < 120) {
                        var point = ch.from.add(direction.scale(c * 1.5));
                        at = new Vec3(
                                point.x,
                                Math.max(altitude(ch.level, point.x, point.z), cloudY(ch.level) * smooth(c / 120d)),
                                point.z);
                    } else if (c < 180) {
                        double v = (c - 120) / 60d;
                        p.setAttached(CLOUD, (int) (100 * Math.min(1, Math.min(v / .3, (1 - v) / .3))));
                        var point = c < 150 ? ch.from.add(direction.scale(180)) : ch.to.subtract(direction.scale(180));
                        at = new Vec3(point.x, cloudY(ch.level), point.z);
                        if (c == 150 && ch.dest != ch.level) {
                            // A Nether flight: deep in the veil the Dragon dives through, and sets the rider down
                            // at the fortress.
                            arriveAcross(p, ch);
                            CHANNELS.remove(pair.getKey());
                            continue;
                        }
                        if (c == 150) transfer(p, ch, at);
                    } else {
                        var point = ch.to.subtract(direction.scale((300 - c) * 1.5));
                        at = new Vec3(
                                point.x,
                                Math.max(
                                        altitude(ch.level, point.x, point.z),
                                        cloudY(ch.level) * (1 - smooth((c - 180) / 120d))),
                                point.z);
                        p.setAttached(CLOUD, 0);
                    }
                }
            } else {
                double f = smooth((t - 40 - ch.cruise) / 40d);
                at = new Vec3(ch.to.x, altitude(ch.level, ch.to.x, ch.to.z), ch.to.z).lerp(ch.to, f);
            }
            move(ch, at.add(0, .8, 0));
            p.fallDistance = 0;
            p.noPhysics = true;
            p.setDeltaMovement(Vec3.ZERO);
            if (ch.transformed) p.setInvisible(true);
        }
    }

    private static double smooth(double x) {
        x = Math.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    // Every flight is flown over the Overworld (problem() refuses any other start; a Nether flight only lands across),
    // so the cruise heights know one sky.
    private static double cloudY(ServerLevel l) {
        return Math.min(l.getMaxY() - 12, 250);
    }

    private static double altitude(ServerLevel l, double x, double z) {
        l.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        int ground = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
        return Math.clamp(Math.max(l.getMinY() + 45, ground + 40), l.getMinY() + 5, l.getMaxY() - 8);
    }

    private static void move(Channel ch, Vec3 at) {
        Vec3 d = at.subtract(ch.dragon.position());
        ch.level.getChunkAt(BlockPos.containing(at));
        ch.dragon.setPos(at);
        if (d.horizontalDistanceSqr() > .001) {
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            ch.dragon.setYRot(yaw);
            ch.dragon.setYBodyRot(yaw);
            ch.dragon.setYHeadRot(yaw);
        }
        ch.dragon.setXRot(0);
    }

    private static boolean board(ServerPlayer p, Channel ch) {
        DragonForm.end(p);
        CollectionLight.release(p);
        p.stopUsingItem();
        ch.oldInvisible = p.isInvisible();
        ch.oldPhysics = p.noPhysics;
        ch.oldGravity = p.isNoGravity();
        p.setNoGravity(true);
        p.setAttached(RETURN, GlobalPos.of(ch.level.dimension(), BlockPos.containing(ch.from)));
        p.setAttached(FLIGHT, ch.transformed ? 2 : 1);
        p.setAttached(CLOUD, 0);
        ch.dragon.role(ch.transformed ? 7 : 6);
        var direction = ch.to.subtract(ch.from);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        ch.dragon.setYRot(yaw);
        ch.dragon.setYBodyRot(yaw);
        ch.dragon.setYHeadRot(yaw);
        if (!p.startRiding(ch.dragon, true, true)) {
            p.setAttached(FLIGHT, 0);
            p.setNoGravity(ch.oldGravity);
            p.removeAttached(RETURN);
            return false;
        }
        ch.boarded = true;
        return true;
    }

    private static void transfer(ServerPlayer p, Channel ch, Vec3 at) {
        releasing = true;
        try {
            p.stopRiding();
            ch.dragon.discard();
            ch.level.getChunkAt(BlockPos.containing(at));
            p.teleportTo(ch.level, at.x, at.y, at.z, Set.of(), p.getYRot(), 0, false);
            ch.dragon = StoryBosses.apparition(p, ch.transformed ? 7 : 6, at);
            ch.resume = ch.age + 5;
        } finally {
            releasing = false;
        }
    }

    private static void arriveAcross(ServerPlayer p, Channel ch) {
        var old = ch.dragon;
        ch.level = ch.dest;
        release(p, ch, true);
        old.discard();
        p.level().playSound(null, p.blockPosition(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 1.2f, .7f);
        p.sendSystemMessage(Component.literal("The Dragon dives through the veil and out of the Dark's black sky. "
                        + "The fortress the Scale points at is here.")
                .withStyle(net.minecraft.ChatFormatting.GOLD));
    }

    private static void release(ServerPlayer p, Channel ch, boolean arrived) {
        releasing = true;
        try {
            p.stopRiding();
            p.setAttached(FLIGHT, 0);
            p.setAttached(CLOUD, 0);
            p.removeAttached(RETURN);
            p.noPhysics = ch.oldPhysics;
            p.setNoGravity(ch.oldGravity);
            p.onUpdateAbilities();
            if (ch.oldInvisible) p.setInvisible(true);
            p.fallDistance = 0;
            p.setAttached(DragonForm.SAFE_LANDING, true);
            BlockPos land = !arrived
                    ? null
                    : ch.level.dimension() == Level.NETHER
                            ? netherGround(ch.level, ch.landing)
                            : ground(ch.level, ch.landing.getX(), ch.landing.getZ());
            if (arrived && land == null && ch.level == ch.origin)
                p.sendSystemMessage(
                        Component.literal("The landing changed during flight. The Dragon returns you safely."));
            // Across worlds there is no start to fall back to there: the landing checked at take-off stands.
            var at = land != null
                    ? Vec3.atBottomCenterOf(land)
                    : ch.level == ch.origin ? ch.from : Vec3.atBottomCenterOf(ch.landing);
            p.teleportTo(ch.level, at.x, at.y, at.z, Set.of(), p.getYRot(), 0, false);
        } finally {
            releasing = false;
        }
    }

    /**
     * Safe standing room on the surface at or near x, z (the open ground over an underground site): the first column,
     * spiralling out to {@link Balance#TRAVEL_LANDING_REACH} blocks, whose top is solid, dry and two blocks clear.
     */
    static BlockPos ground(ServerLevel level, int x, int z) {
        for (int r = 0; r <= Balance.TRAVEL_LANDING_REACH; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    level.getChunk((x + dx) >> 4, (z + dz) >> 4);
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                    var at = new BlockPos(x + dx, y, z + dz);
                    if (stand(level, at)) return at;
                }
        return null;
    }

    /**
     * Standing room at a Nether fortress: the nearest spot on its nether-brick bridges and halls within 32 blocks, else
     * any safe pocket there. The Nether has a roof, so the surface heightmap is no use.
     */
    static BlockPos netherGround(ServerLevel level, BlockPos fort) {
        BlockPos any = null;
        for (int r = 0; r <= 32; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    level.getChunk((fort.getX() + dx) >> 4, (fort.getZ() + dz) >> 4);
                    for (int y = 100; y >= 32; y--) {
                        var at = new BlockPos(fort.getX() + dx, y, fort.getZ() + dz);
                        if (!level.getBlockState(at).isAir() || !stand(level, at)) continue;
                        if (level.getBlockState(at.below()).is(Blocks.NETHER_BRICKS)) return at;
                        if (any == null) any = at;
                    }
                }
        return any;
    }

    private static boolean stand(ServerLevel level, BlockPos at) {
        return level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)
                && !level.getBlockState(at.below()).is(BlockTags.LOGS) // the leafless heightmap tops out on trunks
                && level.getBlockState(at.below()).getFluidState().isEmpty()
                && level.getBlockState(at).getFluidState().isEmpty()
                && level.getBlockState(at.above()).getFluidState().isEmpty()
                && level.noCollision(new AABB(
                        at.getX() + .2, at.getY(), at.getZ() + .2, at.getX() + .8, at.getY() + 1.8, at.getZ() + .8));
    }

    static void clear() {
        for (var ch : CHANNELS.values()) {
            for (var passenger : List.copyOf(ch.dragon.getPassengers()))
                if (passenger instanceof ServerPlayer p) release(p, ch, false);
            ch.dragon.discard();
        }
        CHANNELS.clear();
    }
}
