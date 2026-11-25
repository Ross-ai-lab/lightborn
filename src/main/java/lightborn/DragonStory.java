package lightborn;

import com.mojang.serialization.Codec;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

/** Ordered personal chapters and a shared, irreversible world ending. All advancement happens on the server. */
public final class DragonStory {
    private DragonStory() {}

    public static final AttachmentType<Integer> CHAPTERS = AttachmentRegistry.create(
            ModItems.id("chapters"),
            b -> b.persistent(Codec.intRange(0, 9))
                    .copyOnDeath()
                    .initializer(() -> 0)
                    .syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly()));
    static final AttachmentType<Long> LAST_NIGHT = AttachmentRegistry.create(
            ModItems.id("chapter_night"),
            b -> b.persistent(Codec.LONG).copyOnDeath().initializer(() -> Long.MIN_VALUE));
    static final AttachmentType<Integer> FLAGS = AttachmentRegistry.create(
            ModItems.id("story_flags"),
            b -> b.persistent(Codec.INT).copyOnDeath().initializer(() -> 0));
    /** The world's ending: 0 none yet, 1 Dragon-friend, 2 the fight under way, 3 Dragon-heart. Synced to clients. */
    public static final AttachmentType<Integer> ENDING = AttachmentRegistry.create(
            ModItems.id("world_ending"),
            b -> b.persistent(Codec.INT)
                    .initializer(() -> 0)
                    .syncWith(
                            net.minecraft.network.codec.ByteBufCodecs.VAR_INT,
                            net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate.all()));

    /**
     * The Dragon-friend ending lifts the night's weight (owner, 2026-11-19): the moon's chains break with his, nights
     * are lit as in an ordinary game, and the night no longer drags at you or carries your sound to the monsters. Night
     * monsters still come.
     */
    public static boolean nightLifted(net.minecraft.world.level.Level level) {
        return level.getAttachedOrElse(ENDING, 0) == 1;
    }

    static final AttachmentType<BlockPos> PRISON =
            AttachmentRegistry.create(ModItems.id("prison_gate"), b -> b.persistent(BlockPos.CODEC));
    static final AttachmentType<BlockPos> CITY =
            AttachmentRegistry.create(ModItems.id("prison_city"), b -> b.persistent(BlockPos.CODEC));
    static final AttachmentType<BoundingBox> CITY_BOUNDS =
            AttachmentRegistry.create(ModItems.id("prison_city_bounds"), b -> b.persistent(BoundingBox.CODEC));
    static final AttachmentType<BlockPos> ARENA =
            AttachmentRegistry.create(ModItems.id("prison_arena"), b -> b.persistent(BlockPos.CODEC));
    static final AttachmentType<GlobalPos> TARGET = AttachmentRegistry.create(
            ModItems.id("story_target"), b -> b.persistent(GlobalPos.CODEC).copyOnDeath());
    static final AttachmentType<Long> CALL_NIGHT = AttachmentRegistry.create(
            ModItems.id("friend_call_night"), b -> b.persistent(Codec.LONG).copyOnDeath());
    static final AttachmentType<Long> REPLACED = AttachmentRegistry.create(
            ModItems.id("gift_replacement_night"), b -> b.persistent(Codec.LONG).copyOnDeath());
    public static Item SCALE, LIGHT_KEY, SCRIBE_SEAL;
    public static final List<String> TITLES = List.of(
            "Whoever You Are",
            "The Wards",
            "The Dark",
            "The Ash Lantern",
            "Orvane",
            "The Dark Below",
            "The Hollow",
            "Come Down",
            "The Choice");
    public static final List<String> TEXT = List.of(
            // Rewritten plainer (owner, 2026-11-16); the Light gear starts at chapter 2, a chapter later than before.
            "Whoever you were before the hearth found you, you're here now. I'm the Dragon under this world, or what's left of him. The Keepers chained me, but a little of my light slipped through the links, and it runs in you now. That's what Lightborn means. A staff gives it somewhere to go; a tome gives it a voice. You already carry Homeward: give it ten quiet seconds and I'll bring you back to this chest.",
            "You called the dark down on your own hearth and sent it running. Well done. The wards are part of my chain, you know. They soften the night for you and hold me down, both at once. Here, a helm. It was made from the light I had before the wards.",
            "So you've brought down a giant. That's my blood in the ground, leaking out wherever my chain has worn thin. Down there it turns dark, and the dark swells whatever it touches. I didn't choose what it does to the dead. Take this sword. It remembers the light from before the wards, and the bones still guarding them remember it too.",
            "You're strong enough now to hear about the Ash Lantern. These lands were theirs. They kept the hearths and the wards and carried news between them. When the first Keepers vanished, the Lantern went looking for answers until the dark swallowed every light they had. I remember all of them. Wear this: the dark's poison won't take hold through it.",
            "You've brought down one of the great ones, so you should hear about Orvane. He wrote the first oath of my chain, then wanted my power for himself. He copied his own name into the chain until there was more writing than man. Now he's the Last Scribe. These greaves will keep the dead from holding your feet.",
            // Rewritten for the Dark (owner, 2026-11-20): it is where the Dark comes from.
            "So you've been down into the Dark. That's where it all ends up. The blood that leaks through my chain sinks down there and turns, and every fountain up here is a root of it, reaching for the light. Mind the goo: it pulls you under, and only a wall will get you out. Orvane is down there, still writing, in a fortress. He'll know when you walk in. His Ink Script warns you before it burns. His Copy throws back the last spell you hit him with. Blink breaks his Ink Chains. Take my Scale: use it in the Dark and it points you to him. And these boots, for the withering things on his road.",
            // Rewritten for the Hollow (owner, 2026-11-21): what the End became and why.
            "You found the stronghold. Its portal opens on the Hollow, where the Keepers bled the light out of the land to forge my chain. Nothing grew back. It's bone-white and silent, and every edge still pulls toward the void, so keep back from the drops; crouch if you must. Its Darkmen and its Dark Dragon were emptied too. The dragon is only the Keepers' seal in my shape; its death opens nothing. Dig its stone for Sparks. Orvane carries the Light Key: hear what he leaves behind, then bring it under the deepslate. This staff is for the road: a little of its night-fire stays wherever your spells land.",
            "Orvane's gone. Hear his side anyway; he earned that much. I used to shine without end. The Keepers gave the world night by chaining me, and they were afraid night would go if I did. My Scale points to an ancient city now. I'll keep its Warden asleep until you've decided. Take these wings, cut from my own light. And wear the full set down there: it holds off the Darkness.",
            "This is the gate. The Keepers' tablet is beside it; read it before you answer. I won't call all of it lies. They were frightened, and some of what frightened them was real. You can free me and ask me to keep the night, or face me and take the light for yourself. Either way, the life you built up there carries on.");

    static <T> AttachmentType<T> saved(String name, Codec<T> codec) {
        return AttachmentRegistry.create(
                ModItems.id(name), b -> b.persistent(codec).copyOnDeath());
    }

    public static final int RETURN_SPIRIT = 4;

    public record Power(int action) implements CustomPacketPayload {
        public static final Type<Power> TYPE = new Type<>(ModItems.id("story_power"));
        static final StreamCodec<RegistryFriendlyByteBuf, Power> CODEC =
                ByteBufCodecs.VAR_INT.map(Power::new, Power::action).cast();

        public Type<Power> type() {
            return TYPE;
        }
    }

    static void init() {
        SCALE = ModItems.register(
                "dragon_scale",
                props -> new Item(props) {
                    @Override
                    public InteractionResult use(Level l, Player p, InteractionHand h) {
                        if (p instanceof ServerPlayer s) {
                            if (ending(s.level()) == 3) DragonForm.activate(s);
                            else if (p.isShiftKeyDown() && ending(s.level()) == 1) call(s);
                            else locate(s);
                        }
                        return InteractionResult.SUCCESS;
                    }
                },
                new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
        LIGHT_KEY = ModItems.register(
                "light_key",
                Item::new,
                new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant());
        SCRIBE_SEAL = ModItems.register(
                "scribe_seal", Item::new, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
        PayloadTypeRegistry.serverboundPlay().register(Power.TYPE, Power.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Power.TYPE, (a, c) -> {
            if (a.action == 1) {
                if (ending(c.player().level()) == 1) call(c.player());
                else DragonForm.activate(c.player());
            } else if (a.action == 2) DragonForm.breathe(c.player());
            // Right-click in a beast form is its power (owner, 2026-11-23: not V), sent by FormUseMixin.
            else if (a.action == 3) FormSpells.power(c.player());
            else if (a.action == RETURN_SPIRIT) FormSpells.returnSpirit(c.player());
        });
        UseBlockCallback.EVENT.register(
                (p, l, h, hit) -> p instanceof ServerPlayer s ? gate(s, h, hit.getBlockPos()) : InteractionResult.PASS);
    }

    public static int chapters(LivingEntity p) {
        return p.getAttachedOrElse(CHAPTERS, 0);
    }

    public static int ending(ServerLevel l) {
        return l.getServer().overworld().getAttachedOrElse(ENDING, 0);
    }

    static long night(ServerPlayer p) {
        return LightShop.day(p.level());
    }

    static void flag(ServerPlayer p, int f) {
        p.setAttached(FLAGS, p.getAttachedOrElse(FLAGS, 0) | f);
    }

    static boolean hasFlag(ServerPlayer p, int f) {
        return (p.getAttachedOrElse(FLAGS, 0) & f) != 0;
    }

    static TagKey<Structure> tag(String name) {
        return TagKey.create(Registries.STRUCTURE, ModItems.id(name));
    }

    static boolean inStructure(ServerPlayer p, String name) {
        return p.level()
                .structureManager()
                .getStructureWithPieceAt(p.blockPosition(), tag(name))
                .isValid();
    }

    static boolean inStructureBox(ServerPlayer p, String name) {
        return p.level()
                .structureManager()
                .getStructureAt(
                        p.blockPosition(),
                        p.level()
                                .registryAccess()
                                .lookupOrThrow(Registries.STRUCTURE)
                                .getOrThrow(tag(name)))
                .isValid();
    }

    static boolean unlocked(ServerPlayer p, int chapter) {
        var spawn = p.level().getServer().overworld().getRespawnData().pos();
        double distance = Math.sqrt(Math.pow(p.getX() - spawn.getX(), 2) + Math.pow(p.getZ() - spawn.getZ(), 2));
        return switch (chapter) {
            case 1 -> true;
            case 2 -> hasFlag(p, 32);
            case 3 -> hasFlag(p, 1);
            case 4 -> hasFlag(p, 64);
            case 5 -> hasFlag(p, 2);
            case 6 -> hasFlag(p, 4);
            case 7 -> hasFlag(p, 8);
            case 8 -> hasFlag(p, 16);
            case 9 -> {
                var gate = p.level().getServer().overworld().getAttached(PRISON);
                yield p.level().dimension() == Level.OVERWORLD
                        && gate != null
                        && p.blockPosition().closerThan(gate, 16);
            }
            default -> false;
        };
    }

    static boolean canTell(ServerPlayer p) {
        int n = chapters(p) + 1;
        return n <= 9
                && LightDragons.night(p.level())
                && p.getAttachedOrElse(LAST_NIGHT, Long.MIN_VALUE) != night(p)
                && unlocked(p, n);
    }

    static DragonTalk.Reply tell(ServerPlayer p, int dragon) {
        int n = chapters(p) + 1;
        if (!canTell(p)) return null;
        p.setAttached(CHAPTERS, n);
        p.setAttached(LAST_NIGHT, night(p));
        // Homeward is a tier-2 tome found like any other, never a chapter gift (owner, 2026-11-18).
        for (int i = 0; i < LightGear.GIFT_CHAPTERS.length; i++)
            if (LightGear.GIFT_CHAPTERS[i] == n && i < LightGear.ITEMS.size()) {
                LightGear.give(p, LightGear.gift(i, 1));
                if (i == LightGear.SHIELD) p.setAttached(LightGear.SHIELD_GIVEN, true);
            }
        if (n == 6) LightGear.give(p, new ItemStack(SCALE));
        if (n == 8) {
            p.removeAttached(TARGET);
            locate(p);
        }
        p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1, .7f);
        // At the gate the answer is the choice itself; by the chest it is whatever the player can ask next.
        return new DragonTalk.Reply(
                dragon,
                // Only his own words (owner, 2026-11-17: a chapter heading broke the spell).
                TEXT.get(n - 1),
                dragon == -2 ? CHOICE : DragonTalk.menu(p));
    }

    static final List<DragonTalk.Choice> CHOICE = List.of(
            new DragonTalk.Choice("Free him. Keep the night.", 911),
            new DragonTalk.Choice("Fight him for his power.", 912));

    static DragonTalk.Reply talk(ServerPlayer p, LightDragon d, int action) {
        return action == 0 ? tell(p, d.getId()) : null;
    }

    static void killed(LivingEntity dead, DamageSource source) {
        var p = Lightborn.killer(dead, source);
        if (p == null) return;
        if (dead instanceof GiantZombie) flag(p, 1);
        if (Bosses.identity(dead) != null) flag(p, 2);
    }

    static void tick(MinecraftServer server) {
        DragonForm.tick(server);
        StoryBosses.tick(server);
        for (var p : server.getPlayerList().getPlayers()) {
            if (!p.isAlive()) continue;
            LightGear.tick(p);
            if (server.getTickCount() % 20 != 0) continue;
            DarkShards.tick(p);
            Lighthouse.tick(p);
            DarkNests.smoke(p);
            if (p.level().dimension() == Level.NETHER) flag(p, 4);
            if (p.getAttachedOrElse(ModData.HUNTS_CLEARED, 0L) > 0) flag(p, 32);
            if (p.getAttachedOrElse(ModData.HUNTS_CLEARED, 0L) >= Balance.STORY_ASH_LANTERN_CLEANSES) flag(p, 64);
            if (inStructure(p, "strongholds")) flag(p, 8);
            if (chapters(p) >= 6
                    && !hasFlag(p, 16)
                    && p.level().dimension() == Level.NETHER
                    && inStructure(p, "fortresses")) StoryBosses.scribe(p);
            if (chapters(p) >= 8 && ending(p.level()) == 0 && p.level().dimension() == Level.OVERWORLD) prepareGate(p);
            if (LightDragons.night(p.level())
                    && ending(p.level()) != 3
                    && chapters(p) > 0
                    && p.getAttachedOrElse(REPLACED, Long.MIN_VALUE) != night(p)) {
                var d = p.level()
                        .getEntitiesOfClass(
                                LightDragon.class,
                                p.getBoundingBox().inflate(8),
                                e -> e.chest() != null && !e.departing());
                if (!d.isEmpty()) {
                    p.setAttached(REPLACED, night(p));
                    LightGear.replaceMissing(p);
                    if (hasFlag(p, 16) && !LightGear.has(p, LIGHT_KEY)) LightGear.give(p, new ItemStack(LIGHT_KEY));
                    if (chapters(p) >= 6 && !LightGear.has(p, SCALE)) LightGear.give(p, new ItemStack(SCALE));
                }
            }
        }
    }

    /** The prison city the Scale points to from chapter 8 (its gate, else its city), found and kept on first ask. */
    static BlockPos storyCity(ServerPlayer p) {
        var world = p.level().getServer().overworld();
        var at = world.getAttached(PRISON);
        if (at == null) at = world.getAttached(CITY);
        if (at == null) {
            at = world.findNearestMapStructure(tag("cities"), p.blockPosition(), 256, false);
            if (at == null) return null;
            world.setAttached(CITY, at);
            p.setAttached(TARGET, GlobalPos.of(Level.OVERWORLD, at));
        }
        captureCity(world, at);
        return at;
    }

    /** The Nether fortress the Scale points to in chapters 6 and 7, found and kept on first ask. */
    static BlockPos scaleFortress(ServerPlayer p) {
        var nether = p.level().getServer().getLevel(Level.NETHER);
        if (nether == null) return null;
        var target = p.getAttached(TARGET);
        if (target != null && target.dimension() == Level.NETHER) return target.pos();
        var from = p.level().dimension() == Level.NETHER
                ? p.blockPosition()
                : new BlockPos(p.getBlockX() / 8, 64, p.getBlockZ() / 8);
        var pos = nether.findNearestMapStructure(tag("fortresses"), from, 256, false);
        if (pos != null) p.setAttached(TARGET, GlobalPos.of(Level.NETHER, pos));
        return pos;
    }

    static void locate(ServerPlayer p) {
        var destLevel = p.level().getServer().getLevel(chapters(p) >= 8 ? Level.OVERWORLD : Level.NETHER);
        if (chapters(p) < 6) {
            p.sendOverlayMessage(Component.literal("The Scale is still quiet."));
            return;
        }
        var target = p.getAttached(TARGET);
        var prison = destLevel.getAttached(PRISON);
        if (prison == null) prison = destLevel.getAttached(CITY);
        if (chapters(p) >= 8 && prison != null) target = GlobalPos.of(Level.OVERWORLD, prison);
        if (target == null || target.dimension() != destLevel.dimension()) {
            var pos = destLevel.findNearestMapStructure(
                    tag(chapters(p) >= 8 ? "cities" : "fortresses"), p.blockPosition(), 256, false);
            if (pos == null) {
                p.sendSystemMessage(
                        Component.literal("No story site found nearby. Travel farther and ask the Scale again."));
                return;
            }
            target = GlobalPos.of(destLevel.dimension(), pos);
            p.setAttached(TARGET, target);
            if (chapters(p) >= 8) destLevel.setAttached(CITY, pos);
        }
        if (chapters(p) >= 8) captureCity(destLevel, target.pos());
        p.sendSystemMessage(Component.literal((chapters(p) >= 8 ? "Ancient city" : "Fortress in the Dark") + " · "
                + (target.dimension() == Level.NETHER ? "Nether" : "Overworld") + " · X "
                + target.pos().getX() + ", Z " + target.pos().getZ()
                + ". The Scale points to the destination, not unexplored map terrain."));
        if (p.level() == destLevel) {
            var v = Vec3.atCenterOf(target.pos()).subtract(p.position()).normalize();
            for (int i = 3; i < 14; i++)
                p.level()
                        .sendParticles(
                                ParticleTypes.END_ROD,
                                p.getX() + v.x * i,
                                p.getY() + 1 + v.y * i,
                                p.getZ() + v.z * i,
                                1,
                                0,
                                0,
                                0,
                                0);
        }
    }

    static void prepareGate(ServerPlayer p) {
        var world = p.level();
        var target = p.getAttached(TARGET);
        var gate = world.getAttached(PRISON);
        if (gate != null) return;
        if (target == null
                || target.dimension() != Level.OVERWORLD
                || !p.blockPosition().closerThan(target.pos(), 160)) return;
        for (var at : BlockPos.betweenClosed(
                p.blockPosition().offset(-24, -16, -24), p.blockPosition().offset(24, 16, 24)))
            if (world.hasChunkAt(at) && world.getBlockState(at).is(Blocks.REINFORCED_DEEPSLATE)) {
                rememberGate(p, at.immutable());
                return;
            }
    }

    static void rememberGate(ServerPlayer p, BlockPos pos) {
        var world = p.level().getServer().overworld();
        if (world.getAttached(PRISON) != null) return;
        var city = world.getAttached(CITY);
        if (city != null && !city.closerThan(pos, 160)) return;
        world.setAttached(PRISON, pos);
        captureCity(world, pos);
        var lectern = p.blockPosition().east(3);
        while (!world.getBlockState(lectern).isAir() && lectern.getY() < pos.getY() + 12) lectern = lectern.above();
        world.setBlockAndUpdate(lectern.below(), Blocks.DEEPSLATE_TILES.defaultBlockState());
        world.setBlockAndUpdate(lectern, Blocks.LECTERN.defaultBlockState());
        LecternBlock.tryPlaceBook(p, world, lectern, world.getBlockState(lectern), keeperNote(false));
    }

    static InteractionResult gate(ServerPlayer p, InteractionHand hand, BlockPos pos) {
        if (StoryBosses.exit(p, pos)) return InteractionResult.SUCCESS_SERVER;
        if (chapters(p) < 8
                || p.level().dimension() != Level.OVERWORLD
                || !p.level().getBlockState(pos).is(Blocks.REINFORCED_DEEPSLATE)
                || !inStructure(p, "cities")) return InteractionResult.PASS;
        if (p.level().getAttached(PRISON) == null) rememberGate(p, pos);
        var gate = p.level().getAttached(PRISON);
        if (gate == null || !gate.closerThan(pos, 120)) return InteractionResult.PASS;
        if (!p.getItemInHand(hand).is(LIGHT_KEY)) return InteractionResult.PASS;
        var chapter = chapters(p) < 9 ? tell(p, -2) : null;
        if (chapter != null) {
            StoryBosses.enterPrison(p);
            ServerPlayNetworking.send(p, chapter);
        } else if (chapters(p) >= 9) {
            StoryBosses.enterPrison(p);
            ServerPlayNetworking.send(p, prisonReply(p, 0));
        } else
            p.sendSystemMessage(Component.literal(
                    "The Dragon asks for one quiet night between chapters. Return after the next dusk."));
        return InteractionResult.SUCCESS_SERVER;
    }

    static boolean validGate(ServerPlayer p) {
        var at = p.level().getServer().overworld().getAttached(PRISON);
        return p.isAlive()
                && p.level().dimension() == Level.OVERWORLD
                && at != null
                && p.blockPosition().closerThan(at, 160)
                && chapters(p) >= 9
                && LightGear.has(p, LIGHT_KEY);
    }

    static DragonTalk.Reply prisonReply(ServerPlayer p, int action) {
        if (!validGate(p))
            return new DragonTalk.Reply(-2, "The gate's too far, or you've lost the Light Key.", List.of());
        if (action == 911 && ending(p.level()) == 0) {
            setEnding(p, 1);
            return new DragonTalk.Reply(
                    -2,
                    "I'm free, and the moon's chains broke with mine: your nights will be lit again. The dark's creatures still walk them, so call me with R once a night and I'll be at your side. Your hearth, your Cleanses, your collections: all still yours.",
                    List.of());
        }
        if (action == 912 && (ending(p.level()) == 0 || ending(p.level()) == 2)) {
            p.level().getServer().overworld().setAttached(ENDING, 2);
            StoryBosses.prisonFight(p);
            return new DragonTalk.Reply(
                    -2,
                    "Then face me as I really am. I gather my light before every breath. Watch for it and you might win. If you fall or run, bring the Key back to the gate and we'll begin again.",
                    List.of());
        }
        if (ending(p.level()) == 1 || ending(p.level()) == 3)
            return new DragonTalk.Reply(-2, "It's decided. The world up there is waiting for you.", List.of());
        return new DragonTalk.Reply(
                -2,
                "Read the Keepers' tablet and Orvane's diary before you answer. Free, I may change. Take my power, and I'll never visit again. Whatever you choose shapes this world, and I can't undo it for you. So: free me, or fight me?",
                CHOICE);
    }

    static void setEnding(ServerPlayer p, int end) {
        p.level().getServer().overworld().setAttached(ENDING, end);
        LightDragons.clear();
        if (end == 1) StoryBosses.release(p);
        p.connection.send(
                new ClientboundSetTitleTextPacket(Component.literal(end == 1 ? "Dragon-friend" : "Dragon-heart")));
        p.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal(end == 1 ? "The night is a promise" : "His light is yours to carry")));
        p.level().sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 2, p.getZ(), 180, 5, 5, 5, .05);
    }

    public static ItemStack keeperNote(boolean diary) {
        var s = new ItemStack(Items.WRITTEN_BOOK);
        String title = diary ? "Orvane's diary" : "The Keepers' tablet";
        String text = diary
                ? "I wrote the first oath of the chain. Before it there was no night, no sleep, no seasons. His light would not end. We chained him so the world could rest. Then I wanted what I had feared. I wrote myself into his chain. Do not mistake my hunger for proof that every Keeper was wrong. Hear him. Hear us. Choose for the living."
                : "We ask whoever stands here not to free the Dragon. His light once filled every hour. We gave the world its dark. Yet we were afraid of him more than certain he was cruel. We leave that uncertainty here, where a command would have been easier. Orvane holds the Light Key. The choice, at last, belongs to someone else.";
        var pages = new ArrayList<Filterable<Component>>();
        for (var part : Lore.split(text, 225)) pages.add(Filterable.passThrough(Component.literal(part)));
        s.set(
                DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(
                        Filterable.passThrough(title), diary ? "Orvane" : "The Keepers", 0, pages, true));
        return s;
    }

    private static void captureCity(ServerLevel l, BlockPos at) {
        if (l.hasAttached(CITY_BOUNDS)) return;
        var cities = l.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(tag("cities"));
        var start = l.structureManager().getStructureAt(new BlockPos(at.getX(), -40, at.getZ()), cities);
        if (start.isValid()) l.setAttached(CITY_BOUNDS, start.getBoundingBox());
    }

    public static boolean sleepingWarden(ServerLevel l, BlockPos pos) {
        var root = l.getServer().overworld();
        if (l.dimension() != Level.OVERWORLD || ending(l) != 0) return false;
        var box = root.getAttached(CITY_BOUNDS);
        if (box != null) return box.isInside(pos);
        var gate = root.getAttached(PRISON);
        var city = gate != null ? gate : root.getAttached(CITY);
        return city != null && city.closerThan(pos, 160);
    }

    static void call(ServerPlayer p) {
        if (ending(p.level()) != 1 || !LightDragons.night(p.level())) {
            p.sendOverlayMessage(Component.literal("Your friend answers at night."));
            return;
        }
        if (p.getAttachedOrElse(CALL_NIGHT, Long.MIN_VALUE) == night(p)) {
            p.sendOverlayMessage(Component.literal("He has answered this night. Rest until the next."));
            return;
        }
        p.setAttached(CALL_NIGHT, night(p));
        StoryBosses.friend(p);
    }
}
