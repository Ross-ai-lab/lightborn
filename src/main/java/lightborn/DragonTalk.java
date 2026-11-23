package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** A conversation before a voluntary adventure. Quest state belongs to the player, never to an ephemeral Dragon. */
public final class DragonTalk {
    private DragonTalk() {}
    // 0 greeting, 1 introduction, 2 acquainted, 3 heard offer, 4 seeking, 5 ambush, 6 returning, 7 completed.
    public record Journey(int stage, BlockPos site, BlockPos home, List<UUID> enemies) {
        static final Journey NEW = new Journey(0, BlockPos.ZERO, BlockPos.ZERO, List.of());
        static final Codec<Journey> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Codec.intRange(0, 7).fieldOf("stage").forGetter(Journey::stage),
                                BlockPos.CODEC.fieldOf("site").forGetter(Journey::site),
                        BlockPos.CODEC.fieldOf("home").forGetter(Journey::home),
                                UUIDUtil.CODEC.listOf().fieldOf("enemies").forGetter(Journey::enemies))
                .apply(i, Journey::new));

        Journey stage(int next) {
            return new Journey(next, site, home, enemies);
        }
    }

    public static final AttachmentType<Journey> JOURNEY = AttachmentRegistry.create(
            ModItems.id("dragon_journey"),
            b -> b.persistent(Journey.CODEC).copyOnDeath().initializer(() -> Journey.NEW));

    public enum Disposition {
        FRIENDLY,
        NEUTRAL,
        GUARDED
    }

    public static final AttachmentType<Integer> DISPOSITION = AttachmentRegistry.create(
            ModItems.id("dragon_disposition"),
            b -> b.persistent(Codec.intRange(1, 3))
                    .copyOnDeath()
                    .syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly()));
    public static final AttachmentType<List<BlockPos>> SURVEY = AttachmentRegistry.create(
            ModItems.id("dragon_survey"),
            b -> b.persistent(BlockPos.CODEC.listOf()).copyOnDeath());

    public static Disposition disposition(ServerPlayer p) {
        return Disposition.values()[p.getAttachedOrElse(DISPOSITION, 1) - 1];
    }

    public static String questTitle(ServerPlayer p) {
        return switch (disposition(p)) {
            case FRIENDLY -> "The Fallen Star";
            case NEUTRAL -> "The Quiet Boundary";
            case GUARDED -> "Your Own Light";
        };
    }

    public static ItemStack reward(ServerPlayer p) {
        return switch (disposition(p)) {
            case FRIENDLY -> new ItemStack(ModItems.TOMES.get(Spell.BLINK));
            case NEUTRAL -> new ItemStack(ModItems.SPARK, 12);
            case GUARDED -> new ItemStack(ModItems.SPARK, 12);
        };
    }

    static final AttachmentType<UUID> QUEST_OWNER =
            AttachmentRegistry.create(ModItems.id("dragon_quest_owner"), b -> b.persistent(UUIDUtil.CODEC));
    private static final Map<UUID, Integer> MISSING = new HashMap<>();

    public record Choice(String label, int action) {
        static final StreamCodec<RegistryFriendlyByteBuf, Choice> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Choice::label, ByteBufCodecs.VAR_INT, Choice::action, Choice::new);
    }

    /**
     * One page of talk. {@code speaker} is empty for the Dragon (titled "The Dragon", with his hearts); anyone else who
     * uses the box (a Refugee) names themself, and {@code dragon} is then their entity id.
     */
    public record Reply(int dragon, String text, List<Choice> choices, String speaker) implements CustomPacketPayload {
        public static final Type<Reply> TYPE = new Type<>(ModItems.id("dragon_reply"));
        static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                Reply::dragon,
                ByteBufCodecs.STRING_UTF8,
                Reply::text,
                Choice.CODEC.apply(ByteBufCodecs.list()),
                Reply::choices,
                ByteBufCodecs.STRING_UTF8,
                Reply::speaker,
                Reply::new);

        public Reply(int dragon, String text, List<Choice> choices) {
            this(dragon, text, choices, "");
        }

        @Override
        public Type<Reply> type() {
            return TYPE;
        }
    }

    public record Answer(int dragon, int action) implements CustomPacketPayload {
        public static final Type<Answer> TYPE = new Type<>(ModItems.id("dragon_answer"));
        static final StreamCodec<RegistryFriendlyByteBuf, Answer> CODEC = StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Answer::dragon, ByteBufCodecs.VAR_INT, Answer::action, Answer::new)
                .cast();

        @Override
        public Type<Answer> type() {
            return TYPE;
        }
    }

    static void init() {
        DragonFavours.init();
        PayloadTypeRegistry.serverboundPlay().register(Answer.TYPE, Answer.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Reply.TYPE, Reply.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Answer.TYPE, (packet, ctx) -> {
            if (packet.dragon() == -2) {
                send(ctx.player(), DragonStory.prisonReply(ctx.player(), packet.action()));
                return;
            }
            Entity e = ctx.player().level().getEntity(packet.dragon());
            if (e instanceof Refugee r && r.talkable(ctx.player())) {
                send(ctx.player(), r.respond(ctx.player(), packet.action()));
                return;
            }
            if (e instanceof LightDragon d && valid(ctx.player(), d))
                send(ctx.player(), respond(ctx.player(), d, packet.action()));
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            UUID owner = entity.getAttached(QUEST_OWNER);
            if (owner == null) return;
            var p = level.getServer().getPlayerList().getPlayer(owner);
            if (p != null && !p.getAttachedOrCreate(JOURNEY).enemies.contains(entity.getUUID())) entity.discard();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var q = handler.player.getAttachedOrCreate(JOURNEY);
            for (var l : server.getAllLevels())
                for (var e : l.getAllEntities())
                    if (handler.player.getUUID().equals(e.getAttached(QUEST_OWNER)) && !q.enemies.contains(e.getUUID()))
                        e.discard();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> MISSING.clear());
    }

    static boolean valid(ServerPlayer p, LightDragon d) {
        return p.isAlive()
                && !p.isSpectator()
                && d.isAlive()
                && !d.isRemoved()
                && !d.departing()
                && p.distanceToSqr(d) <= 64
                && d.chest() != null
                && (LightDragons.night(p.level()) || DragonStory.ending(p.level()) == 1)
                && p.level().getBlockState(d.chest()).is(ModItems.LIGHT_CHEST);
    }

    public static void open(ServerPlayer p, LightDragon d) {
        if (valid(p, d)) send(p, respond(p, d, 0));
    }

    static void send(ServerPlayer p, Reply reply) {
        ServerPlayNetworking.send(p, reply);
    }

    static Reply page(LightDragon d, String text, Choice... options) {
        return new Reply(d.getId(), text, List.of(options));
    }

    static Choice c(String label, int action) {
        return new Choice(label, action);
    }

    /** Story flags for the two questions about himself, each asked once. */
    static final int ASKED_CHEST = 128, ASKED_BOUND = 256;

    /**
     * What the player can say next: at most three choices and never a goodbye, since Escape closes the talk (owner,
     * 2026-11-16). In order of weight: a waiting chapter, the quest, tonight's errand, then one question about him.
     */
    static List<Choice> menu(ServerPlayer p) {
        Journey q = p.getAttachedOrCreate(JOURNEY);
        var list = new ArrayList<Choice>();
        if (q.stage >= 2 && DragonStory.canTell(p)) list.add(c("Tell me more about yourself.", 0));
        if (q.stage <= 3)
            list.add(c(
                    switch (disposition(p)) {
                        case FRIENDLY -> "Can I help you somehow?";
                        case NEUTRAL -> "Got any work for me?";
                        case GUARDED -> "Give me something to fight.";
                    },
                    4));
        else if (q.stage == 6) list.add(c("It's done.", 6));
        else if (q.stage <= 5)
            list.add(c(
                    switch (disposition(p)) {
                        case FRIENDLY -> "Where is the star again?";
                        case NEUTRAL -> "Where's the next mark?";
                        case GUARDED -> "Where are they waiting?";
                    },
                    7));
        var shard = DarkShards.menuChoice(p);
        if (shard != null) list.add(shard);
        var errand = DragonFavours.menuChoice(p);
        if (errand != null) list.add(errand);
        if (!DragonStory.hasFlag(p, ASKED_CHEST)) list.add(c("What is this chest, really?", 3));
        else if (!DragonStory.hasFlag(p, ASKED_BOUND)) list.add(c("What keeps you here?", 8));
        return List.copyOf(list.subList(0, Math.min(3, list.size())));
    }

    /** A page of his words followed by whatever the player can ask next. */
    static Reply say(ServerPlayer p, LightDragon d, String text) {
        return new Reply(d.getId(), text, menu(p));
    }

    private static final List<List<String>> HELLO = List.of(
            List.of(
                    "There you are. Sit with me a while.",
                    "Good to see you. The night's quieter with company.",
                    "You look tired. Rest here a moment."),
            List.of("Evening. What do you need?", "Another night. What's on your mind?", "You're back. Ask away."),
            List.of("You came. Ask what you need.", "Still keeping your distance? Fine by me. Ask.", "I'm listening."));

    public static Reply respond(ServerPlayer p, LightDragon d, int action) {
        Journey q = p.getAttachedOrCreate(JOURNEY);
        if (q.stage == 1) { // an older save's middle step: the introduction is over
            q = q.stage(2);
            p.setAttached(JOURNEY, q);
        }
        if (q.stage == 0) {
            if (action < 11 || action > 13)
                return page(
                        d,
                        "So you can see me. Good. Don't be alarmed: I'm only an echo of a Dragon, and this chest is"
                                + " all that keeps me here. I'd like to stay, if you'll have me.",
                        c("Stay as long as you like.", 11),
                        c("An echo of what, exactly?", 12),
                        c("Why my chest?", 13));
            p.setAttached(DISPOSITION, action - 10);
            q = q.stage(2);
            p.setAttached(JOURNEY, q);
            return say(
                    p,
                    d,
                    switch (disposition(p)) {
                        case FRIENDLY ->
                            "Kind of you. Most people reach for a sword. I'll keep the dark off your doorstep while"
                                    + " I'm here, and I won't ask anything for it.";
                        case NEUTRAL ->
                            "Of a Dragon chained deep under this world. The real me is still down there. What"
                                    + " reaches you is about as solid as candlelight.";
                        case GUARDED ->
                            "It isn't you I'm tied to, it's the chest. Move it and I follow it, not you. Keep your"
                                    + " distance if you like.";
                    });
        }
        var chapter = DragonStory.talk(p, d, action);
        if (chapter != null) return chapter;
        if (action == DarkShards.ASK && DragonStory.hasFlag(p, DarkShards.SEEN))
            return say(p, d, DarkShards.explain(p));
        if (action == 3) {
            DragonStory.flag(p, ASKED_CHEST);
            return say(
                    p,
                    d,
                    "A memory, mostly. Give it one of everything you find and it remembers a little more of my light."
                            + " That light holds the ward up around your home. Sparks are small change from the same"
                            + " purse, and the chest's shop takes nothing else.");
        }
        if (action == 8) {
            DragonStory.flag(p, ASKED_BOUND);
            return say(
                    p,
                    d,
                    "Old promises. The Keepers bound me with written oaths and tied the night to them. Their story is"
                            + " still scattered across this world. Each time you find another piece of it, I can tell"
                            + " you a little more.");
        }
        if (action == 4 && q.stage <= 3) {
            q = q.stage(3);
            p.setAttached(JOURNEY, q);
            String offer = switch (disposition(p)) {
                case FRIENDLY ->
                    "There is one thing. A star of my old light fell outside the ward, and something hungry found it"
                            + " first. Follow the gold motes after dark, deal with whatever's guarding it, and bring"
                            + " it home. I'll teach you Blink for it, and there are Sparks in it too.";
                case NEUTRAL ->
                    "A fair trade, then. Three marks lie past the ward. Walk to each of them at night and tell me"
                            + " what's there. Nothing of mine will test you. Twenty Sparks when you're back.";
                case GUARDED ->
                    "There's a pack gathering at a mark past the ward. Go and break it, your way. Twenty Sparks when"
                            + " it's done. You won't owe me a thing.";
            };
            String answer = switch (disposition(p)) {
                case FRIENDLY -> "I'll bring it home.";
                case NEUTRAL -> "Deal.";
                case GUARDED -> "Consider it broken.";
            };
            return page(d, offer, c(answer, 5)); // no "not yet": Escape declines
        }
        if (action == 5 && q.stage == 3) {
            BlockPos site = findSite(p, d.chest());
            if (site == null)
                return say(
                        p,
                        d,
                        "There's nowhere safe to start from here. Move your home to open ground (the chest's Travel"
                                + " tab) and ask me again.");
            var marks = new ArrayList<BlockPos>();
            marks.add(site);
            if (disposition(p) == Disposition.NEUTRAL)
                for (int i = 1; i < 3; i++) {
                    var next = findSite(p, d.chest(), i * 5, marks);
                    if (next == null)
                        return say(
                                p,
                                d,
                                "There aren't enough safe marks around this chest. Move your home to open ground and"
                                        + " ask me again.");
                    marks.add(next);
                }
            LightMaps.give(p, site);
            q = new Journey(4, site, d.chest(), List.of());
            p.setAttached(JOURNEY, q);
            p.setAttached(SURVEY, List.copyOf(marks));
            return say(
                    p,
                    d,
                    disposition(p) == Disposition.NEUTRAL
                            ? "Here, a Light Map. It shows the first mark, and it'll show the others as you go. The"
                                    + " marks only wake at night."
                            : "Here, a Light Map. The star's marked on it. Go at night, and expect company.");
        }
        if (action == 6 && q.stage == 6) {
            // Record first: a second packet, death or re-open can never claim the reward twice.
            p.setAttached(JOURNEY, q.stage(7));
            give(p, reward(p));
            give(p, new ItemStack(ModItems.SPARK, Balance.QUEST_SPARKS));
            p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, .7f, .8f);
            return say(
                    p,
                    d,
                    switch (disposition(p)) {
                        case FRIENDLY ->
                            "You brought a piece of me home. Blink is yours, and the Sparks. If you're ever"
                                    + " surrounded, remember there's a way out that isn't through them.";
                        case NEUTRAL -> "Three marks, and your account matches the land. Twenty Sparks, as agreed.";
                        case GUARDED -> "That was your strength, not mine. Twenty Sparks, as promised.";
                    });
        }
        if (action == 7 && q.stage >= 4 && q.stage <= 5) {
            String text = q.stage == 4
                    ? (disposition(p) == Disposition.NEUTRAL
                                    ? "You've walked "
                                            + (3
                                                    - p.getAttachedOrElse(SURVEY, List.of())
                                                            .size()) + " of three. The next one is "
                                    : "It's ")
                            + bearing(p, q.site) + ". Go at night; it only wakes after dark."
                    : "There are still " + q.enemies.size() + " of them, " + bearing(p, q.site) + ".";
            return say(p, d, text);
        }
        var errand = DragonFavours.respond(p, d, action);
        if (errand != null) return errand;
        var lines = HELLO.get(disposition(p).ordinal());
        String hello = q.stage == 7 && disposition(p) == Disposition.FRIENDLY
                ? "There you are. The hearth's been brighter since you brought the star home."
                : lines.get((int) Math.floorMod(DragonStory.night(p), lines.size()));
        return say(p, d, hello);
    }

    static void give(ServerPlayer p, ItemStack stack) {
        if (!p.getInventory().add(stack)) p.spawnAtLocation(p.level(), stack);
    }

    private static String bearing(ServerPlayer p, BlockPos site) {
        double dx = site.getX() - p.getX(), dz = site.getZ() - p.getZ();
        String direction = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");
        return Math.round(Math.sqrt(dx * dx + dz * dz)) + " blocks to the " + direction;
    }

    static BlockPos findSite(ServerPlayer p, BlockPos chest) {
        return findSite(p, chest, 0, List.of());
    }

    private static BlockPos findSite(ServerPlayer p, BlockPos chest, int start, List<BlockPos> excluded) {
        ServerLevel l = p.level();
        if (l.dimension() != Level.OVERWORLD) return null;
        double distance = LightWards.radiusFor(p) + 18;
        for (int i = 0; i < 16; i++) {
            double angle = (i + start) * Math.PI / 8;
            int x = chest.getX() + (int) Math.round(Math.cos(angle) * distance),
                    z = chest.getZ() + (int) Math.round(Math.sin(angle) * distance);
            l.getChunk(x >> 4, z >> 4);
            var site = new BlockPos(x, l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (safe(l, site)
                    && !LightWards.covers(l, site)
                    && excluded.stream().noneMatch(old -> old.distSqr(site) < 16 * 16)) return site;
        }
        return null;
    }

    private static boolean safe(ServerLevel l, BlockPos p) {
        return l.getBlockState(p.below()).isSolidRender()
                && l.getFluidState(p.below()).isEmpty()
                && l.getBlockState(p).getCollisionShape(l, p).isEmpty()
                && l.getBlockState(p.above()).getCollisionShape(l, p.above()).isEmpty()
                && l.getFluidState(p).isEmpty();
    }

    static void beginAmbush(ServerPlayer p, Journey q) {
        spawnAmbush(p, q, List.of(), 3);
    }

    private static void spawnAmbush(ServerPlayer p, Journey q, List<UUID> survivors, int count) {
        var l = p.level();
        var mobs = new ArrayList<Mob>();
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2 / 3;
            int x = q.site.getX() + (int) Math.round(Math.cos(angle) * 6),
                    z = q.site.getZ() + (int) Math.round(Math.sin(angle) * 6);
            BlockPos pos = new BlockPos(x, l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!safe(l, pos) || pos.distToCenterSqr(p.position()) < 9) pos = q.site.offset(-5 + i * 2, 0, 5);
            if (!safe(l, pos)) continue;
            Mob mob = i == 2
                    ? VariantCompat.keepKind(EntityTypes.SKELETON.create(l, EntitySpawnReason.MOB_SUMMONED))
                    : VariantCompat.keepKind(EntityTypes.ZOMBIE.create(l, EntitySpawnReason.MOB_SUMMONED));
            mob.setPos(Vec3.atBottomCenterOf(pos));
            mob.setPersistenceRequired();
            mob.setAttached(QUEST_OWNER, p.getUUID());
            mob.setAttached(ModData.SPAWNER_MONSTER, true);
            Monsters.setRank(mob, MonsterInfo.NORMAL);
            mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            mob.setDropChance(EquipmentSlot.HEAD, 0);
            if (i == 2) {
                mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                mob.setDropChance(EquipmentSlot.MAINHAND, 0);
            }
            mob.setTarget(p);
            mobs.add(mob);
        }
        if (mobs.isEmpty()) {
            p.sendSystemMessage(
                    Component.literal("The star is hidden by the terrain. Clear space around its light."), true);
            return;
        }
        var ids = new ArrayList<>(survivors);
        ids.addAll(mobs.stream().map(Entity::getUUID).toList());
        p.setAttached(JOURNEY, new Journey(5, q.site, q.home, List.copyOf(ids)));
        for (var mob : mobs) l.addFreshEntity(mob);
        p.sendSystemMessage(
                Component.literal(
                        disposition(p) == Disposition.FRIENDLY
                                ? "The star flares. Something has followed its light!"
                                : "The night answers. Stand your ground beyond the ward."),
                false);
        l.playSound(null, q.site, SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, .7f, 1.1f);
    }

    static void killed(LivingEntity mob) {
        UUID owner = mob.getAttached(QUEST_OWNER);
        if (owner == null || !(mob.level() instanceof ServerLevel l)) return;
        var p = l.getServer().getPlayerList().getPlayer(owner);
        if (p == null) return;
        Journey q = p.getAttachedOrCreate(JOURNEY);
        if (q.stage != 5 || !q.enemies.contains(mob.getUUID())) return;
        var remaining =
                q.enemies.stream().filter(id -> !id.equals(mob.getUUID())).toList();
        p.setAttached(JOURNEY, new Journey(remaining.isEmpty() ? 6 : 5, q.site, q.home, remaining));
        if (remaining.isEmpty()) {
            p.sendSystemMessage(
                    Component.literal(questTitle(p) + " is complete. Return to the Dragon at night."), false);
            l.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 30, .5, .8, .5, .02);
            l.playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, .7f, 1.3f);
        }
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) return;
        for (var p : server.getPlayerList().getPlayers()) {
            Journey q = p.getAttachedOrCreate(JOURNEY);
            if (q.stage < 4 || q.stage > 6 || p.level().dimension() != Level.OVERWORLD) continue;
            var l = p.level();
            if (q.stage == 4 || q.stage == 5) {
                double distance = q.site.distToCenterSqr(p.position());
                if (distance < 64 * 64 && l.isLoaded(q.site)) {
                    for (int n = 0; n < 8; n++) {
                        double angle = n * Math.PI / 4 + server.getTickCount() * .04;
                        l.sendParticles(
                                ParticleTypes.END_ROD,
                                q.site.getX() + .5 + Math.cos(angle) * .65,
                                q.site.getY() + .4 + n * .22,
                                q.site.getZ() + .5 + Math.sin(angle) * .65,
                                1,
                                0,
                                .02,
                                0,
                                .002);
                    }
                }

                if (q.stage == 4 && distance < 25 && LightDragons.night(l)) {
                    if (disposition(p) == Disposition.NEUTRAL) survey(p, q);
                    else beginAmbush(p, q);
                }
                if (q.stage == 5)
                    for (UUID id : q.enemies)
                        if (l.getEntity(id) instanceof Mob mob) {
                            mob.setPersistenceRequired();
                            if (p.isAlive() && mob.distanceToSqr(p) < 64 * 64) mob.setTarget(p);
                        }
                if (q.stage == 5
                        && distance < 32 * 32
                        && l.areEntitiesActuallyLoadedAndTicking(
                                new ChunkPos(q.site.getX() >> 4, q.site.getZ() >> 4))) {
                    var live = q.enemies.stream()
                            .filter(id -> l.getEntity(id) instanceof Mob mob && mob.isAlive())
                            .toList();
                    if (live.size() < q.enemies.size()) {
                        int ticks = MISSING.getOrDefault(p.getUUID(), 0) + 10;
                        MISSING.put(p.getUUID(), ticks);
                        if (ticks >= 60) {
                            MISSING.remove(p.getUUID());
                            spawnAmbush(p, q, live, q.enemies.size() - live.size());
                        }
                    } else MISSING.remove(p.getUUID());
                } else MISSING.remove(p.getUUID());
            }
        }
    }

    static void survey(ServerPlayer p, Journey q) {
        var marks = p.getAttachedOrElse(SURVEY, List.of());
        if (marks.isEmpty()) return;
        var remaining = List.copyOf(marks.subList(1, marks.size()));
        p.setAttached(SURVEY, remaining);
        p.setAttached(
                JOURNEY,
                new Journey(
                        remaining.isEmpty() ? 6 : 4,
                        remaining.isEmpty() ? q.site : remaining.getFirst(),
                        q.home,
                        List.of()));
        if (!remaining.isEmpty()) LightMaps.give(p, remaining.getFirst());
        p.sendSystemMessage(
                Component.literal(
                        remaining.isEmpty()
                                ? "The boundary survey is complete. Return to the Dragon at night."
                                : "Boundary mark recorded · " + (3 - remaining.size()) + " / 3"),
                false);
        p.level().playSound(null, q.site, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, .6f, 1.2f);
    }
}
