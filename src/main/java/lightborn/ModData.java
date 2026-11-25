package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Data the mod stores on entities. Fabric saves and syncs it. */
public final class ModData {
    private ModData() {}

    /** Current mana. Synced to its own player (a few times a second, see Mana). */
    public static final AttachmentType<Float> MANA = AttachmentRegistry.<Float>create(
            id("mana"),
            b -> b.persistent(Codec.FLOAT)
                    .copyOnDeath()
                    .syncWith(ByteBufCodecs.FLOAT, AttachmentSyncPredicate.targetOnly()));

    /** Level / champion / boss info on monsters. Synced to everyone so the target frame can show it. */
    public static final AttachmentType<MonsterInfo> MONSTER = AttachmentRegistry.<MonsterInfo>create(
            id("monster"),
            b -> b.persistent(MonsterInfo.CODEC).syncWith(MonsterInfo.STREAM_CODEC, AttachmentSyncPredicate.all()));

    /** On the overworld: true once the spawn hut is built, false for a world that predates it (never build). */
    public static final AttachmentType<Boolean> HUT =
            AttachmentRegistry.<Boolean>create(id("spawn_hut"), b -> b.persistent(Codec.BOOL));

    /** On a player: every material given to a Chest of Light (item ids). Saved, kept through death, synced to its player. */
    public static final AttachmentType<List<String>> COLLECTION = AttachmentRegistry.<List<String>>create(
            id("collection"),
            b -> b.persistent(Codec.STRING.listOf())
                    .copyOnDeath()
                    .syncWith(
                            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                            AttachmentSyncPredicate.targetOnly()));

    static final AttachmentType<List<Integer>> JOURNAL_READ = AttachmentRegistry.<List<Integer>>create(
            id("journal_read"), b -> b.persistent(Codec.INT.listOf()).copyOnDeath());
    /** How many ward-size steps this player has bought (0 = the starting radius). */
    public static final AttachmentType<Integer> WARD_TIER = AttachmentRegistry.<Integer>create(
            id("ward_tier"),
            b -> b.persistent(Codec.INT)
                    .copyOnDeath()
                    .syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly()));

    static final AttachmentType<Long> HUNTS_CLEARED = AttachmentRegistry.<Long>create(
            id("hunts_cleared"), b -> b.persistent(Codec.LONG).copyOnDeath());
    /** Lightbrand burns on this player: their held weapon spills light like the Light Sword (synced to all). */
    public static final AttachmentType<Boolean> BRANDED = AttachmentRegistry.<Boolean>create(
            id("lightbrand"), b -> b.syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.all()));

    static final AttachmentType<Boolean> LIGHT_COMPLETE = AttachmentRegistry.<Boolean>create(
            id("light_complete"), b -> b.persistent(Codec.BOOL).copyOnDeath());
    static final AttachmentType<Map<String, LightWards.Owner>> LIGHT_WARD_OWNERS = AttachmentRegistry.create(
            id("light_ward_owners"), b -> b.persistent(Codec.unboundedMap(Codec.STRING, LightWards.Owner.CODEC)));
    static final AttachmentType<List<CollectionLight.Placed>> COLLECTION_LIGHTS = AttachmentRegistry.create(
            id("collection_lights"), b -> b.persistent(CollectionLight.Placed.CODEC.listOf()));

    private static final Codec<ItemStack> COUNTED_ITEM = RecordCodecBuilder.create(i -> i.group(
                    ItemStack.CODEC.fieldOf("item").forGetter(stack -> stack.copyWithCount(1)),
                    Codec.intRange(1, Collection.STORAGE_LIMIT)
                            .fieldOf("quantity")
                            .forGetter(ItemStack::getCount))
            .apply(i, ItemStack::copyWithCount));

    /** Counts in the chest can exceed the same item's physical stack limit. */
    static final StreamCodec<RegistryFriendlyByteBuf, ItemStack> CHEST_STACK = StreamCodec.composite(
            ItemStack.STREAM_CODEC,
            stack -> stack.copyWithCount(1),
            ByteBufCodecs.VAR_INT,
            ItemStack::getCount,
            ItemStack::copyWithCount);

    /** Chest items are stored per player and survive death. The old stack format still decodes. */
    public static final AttachmentType<List<ItemStack>> STORAGE = AttachmentRegistry.<List<ItemStack>>create(
            id("chest_storage"),
            b -> b.persistent(
                            Codec.withAlternative(COUNTED_ITEM, ItemStack.CODEC).listOf())
                    .copyOnDeath()
                    .syncWith(CHEST_STACK.apply(ByteBufCodecs.list()), AttachmentSyncPredicate.targetOnly()));

    /** On a player: has been welcomed into the hut. */
    public static final AttachmentType<Boolean> ARRIVED = AttachmentRegistry.<Boolean>create(
            id("arrived"), b -> b.persistent(Codec.BOOL).copyOnDeath());

    static final AttachmentType<Bosses.Encounter> BOSS_IDENTITY =
            AttachmentRegistry.<Bosses.Encounter>create(id("boss_identity"), b -> b.persistent(Bosses.Encounter.CODEC));
    static final AttachmentType<Boolean> BOSS_REVIVED =
            AttachmentRegistry.<Boolean>create(id("boss_revived"), b -> b.persistent(Codec.BOOL));
    static final AttachmentType<Boolean> BOSS_ENRAGED =
            AttachmentRegistry.<Boolean>create(id("boss_enraged"), b -> b.persistent(Codec.BOOL));
    static final AttachmentType<Bosses.Grudge> BOSS_GRUDGE = AttachmentRegistry.<Bosses.Grudge>create(
            id("boss_grudge"), b -> b.persistent(Bosses.Grudge.CODEC).copyOnDeath());
    static final AttachmentType<List<BossKind>> BOSS_KILLS = AttachmentRegistry.<List<BossKind>>create(
            id("boss_kills"), b -> b.persistent(BossKind.CODEC.listOf()).copyOnDeath());
    static final AttachmentType<Long> ECLIPSE_UNTIL = AttachmentRegistry.create(id("eclipse_until"));
    static final AttachmentType<Long> PLATE_NEXT = AttachmentRegistry.create(id("plate_next"));
    static final AttachmentType<Boolean> PLAYER_DEATH_SETTLED = AttachmentRegistry.create(id("player_death_settled"));
    public static final AttachmentType<Boolean> SPAWNER_MONSTER =
            AttachmentRegistry.create(id("spawner_monster"), b -> b.persistent(Codec.BOOL));
    static final AttachmentType<Integer> FOOD_TIMER =
            AttachmentRegistry.create(id("food_timer"), b -> b.persistent(Codec.INT));
    public static final AttachmentType<Map<String, Integer>> SOUL_COUNTS = AttachmentRegistry.create(
            id("soul_counts"),
            b -> b.persistent(Codec.unboundedMap(Codec.STRING, Codec.INT))
                    .copyOnDeath()
                    .syncWith(
                            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT),
                            AttachmentSyncPredicate.targetOnly()));
    static final AttachmentType<String> SOUL_KIND =
            AttachmentRegistry.create(id("soul_kind"), b -> b.persistent(Codec.STRING));
    static final AttachmentType<Integer> SOUL_RANK =
            AttachmentRegistry.create(id("soul_rank"), b -> b.persistent(Codec.INT));
    public static final AttachmentType<Souls.Spirits> SPIRITS = AttachmentRegistry.create(
            id("spirits"),
            b -> b.persistent(Souls.Spirits.CODEC)
                    .copyOnDeath()
                    .syncWith(Souls.Spirits.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));
    static final AttachmentType<Boolean> SOULS_BOOK_GIVEN = AttachmentRegistry.create(
            id("souls_book_given"), b -> b.persistent(Codec.BOOL).copyOnDeath());
    static final AttachmentType<UUID> PREVIOUS_BOUND_ID =
            AttachmentRegistry.create(id("previous_bound_id"), b -> b.persistent(UUIDUtil.CODEC));
    static final AttachmentType<Map<String, List<UUID>>> DEAD_COMPANIONS = AttachmentRegistry.create(
            id("dead_companions"), b -> b.persistent(Codec.unboundedMap(Codec.STRING, UUIDUtil.CODEC.listOf())));
    static final AttachmentType<List<Command.Bond>> COMMAND_ROSTER = AttachmentRegistry.create(
            id("command_roster"),
            b -> b.persistent(Command.Bond.CODEC.listOf())
                    .copyOnDeath()
                    .syncWith(
                            Command.Bond.STREAM_CODEC.apply(ByteBufCodecs.list()),
                            AttachmentSyncPredicate.targetOnly()));
    static final AttachmentType<Long> RAISED_UNTIL =
            AttachmentRegistry.<Long>create(id("raised_until"), b -> b.persistent(Codec.LONG));
    static final AttachmentType<Cleanse.Called> CLEANSE =
            AttachmentRegistry.<Cleanse.Called>create(id("cleanse"), b -> b.persistent(Cleanse.Called.CODEC));
    static final AttachmentType<Hordes.ScoutNight> SCOUT_NIGHT = AttachmentRegistry.<Hordes.ScoutNight>create(
            id("scout_night"), b -> b.persistent(Hordes.ScoutNight.CODEC).copyOnDeath());
    static final AttachmentType<List<BlockPos>> LIGHT_WARDS = AttachmentRegistry.create(
            id("light_wards"), b -> b.persistent(BlockPos.CODEC.listOf()).initializer(List::of));

    /** On a thrall (a spirit or a Bonecaller skeleton): the player it serves. Saved, so a thrall stays bound through a reload. */
    /** Set on every minion while it serves: clients draw it lit from within (see the client's MinionLightMixin). */
    public static final AttachmentType<Boolean> MINION = AttachmentRegistry.<Boolean>create(
            id("minion"), b -> b.persistent(Codec.BOOL).syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.all()));

    /** Stoneskin's Rooted: when the knockback immunity of the latest cast ends (not saved: the modifier is not). */
    static final AttachmentType<Long> STONE_ROOTED_UNTIL = AttachmentRegistry.create(id("stone_rooted_until"));

    public static final AttachmentType<UUID> THRALL_MASTER =
            AttachmentRegistry.<UUID>create(id("thrall_master"), b -> b.persistent(UUIDUtil.CODEC));

    /**
     * On a player: their thralls and spirit wolves, oldest first. Saved and kept through death. A bound creature that
     * loads (or is seen) while its master is online but is missing from this list was replaced while away, and lets go.
     */
    public static final AttachmentType<List<UUID>> BOUND_THRALLS = AttachmentRegistry.<List<UUID>>create(
            id("bound_thralls"), b -> b.persistent(UUIDUtil.CODEC.listOf()).copyOnDeath());

    public static final AttachmentType<List<UUID>> BOUND_WOLVES = AttachmentRegistry.<List<UUID>>create(
            id("bound_wolves"), b -> b.persistent(UUIDUtil.CODEC.listOf()).copyOnDeath());

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Lightborn.MOD_ID, path);
    }

    public static void init() {}
}
