package lightborn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The Book of Souls, given to every player at the start and never crafted, dropped or worn out. Slaying enough
 * monsters of a kind earns its spirit. Every kind has a tier, 1 to 5 (owner, 2026-11-18), set by its real power:
 * 40 kills of a tier 1 kind, 30 of tier 2, 20 of tier 3, 10 of tier 4, 3 of tier 5 (owner, 2026-11-20). The Chest of Light's Spirits tab
 * hands it over, ranks it up for Sparks and places it in the book. The book starts with two slots (three more bought
 * with Sparks), and a spirit takes as many slots as its tier, so a tier 5 spirit fills a whole bought-out book. One
 * cast raises every placed spirit at once, stronger with each rank. No boss forms.
 */
public final class Souls {
    private Souls() {}

    /** A kind of spirit: its entity id, its name, its body, and how many of the book's slots it takes. */
    public record Page(String id, String label, Supplier<EntityType<? extends Mob>> type, int weight) {}

    /** A monster looked up by id (a companion mod's, or vanilla's); null when that mod is not installed. */
    @SuppressWarnings("unchecked")
    private static Supplier<EntityType<? extends Mob>> companion(String id) {
        return () -> {
            var key = net.minecraft.resources.Identifier.parse(id);
            return BuiltInRegistries.ENTITY_TYPE.containsKey(key)
                    ? (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE.getValue(key)
                    : null;
        };
    }

    /**
     * One monster's line in {@code lightborn/monsters.json} (owner, 2026-11-20: every mob's health, attack, armour and
     * abilities in one small file, so the tiers can be judged again by editing it): its numbers, what it does, and
     * where it may guard a Dark Fountain ("night" every Overworld fountain, "overworld" too, a biome group such as
     * "snowy", or "nether" / "end"; none: it never guards).
     */
    public record Monster(
            String id,
            String name,
            double health,
            double armor,
            double hit,
            int power,
            String does,
            List<String> guards) {
        /**
         * Its tier, 1 to 5 (owner, 2026-11-20: "judge for real all"): a health band from health times (1 + armour /
         * 20), cut at 25, 50, 90 and 150, and a hit band from one Normal hit, cut at 4, 8, 13 and 20 (no attack 0; a
         * creeper's one blast counts 1, 2 when twice as wide). Their average rounded half up, plus {@code power} for a
         * fight-changing power (summons, a second life, being unkillable, volleys). Poison, slowness and fire stay in
         * {@code does}: they make a kind a little stronger, not a tier stronger.
         */
        public int tier() {
            int healthBand = band(health * (1 + armor / 20), 25, 50, 90, 150);
            int hitBand = hit <= 0 ? 0 : band(hit, 4, 8, 13, 20);
            return Math.clamp((int) Math.floor((healthBand + hitBand) / 2.0 + .5) + power, 1, 5);
        }

        private static int band(double v, double... cuts) {
            for (int i = 0; i < cuts.length; i++) if (v <= cuts[i]) return i + 1;
            return cuts.length + 1;
        }
    }

    /** Every monster in the game, read once from {@code lightborn/monsters.json}. */
    public static final List<Monster> MONSTERS = loadMonsters();

    private static List<Monster> loadMonsters() {
        try (var in = Souls.class.getResourceAsStream("/lightborn/monsters.json")) {
            var out = new ArrayList<Monster>();
            for (var e : com.google.gson.JsonParser.parseReader(
                            new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonArray()) {
                var o = e.getAsJsonObject();
                var guards = new ArrayList<String>();
                for (var g : o.getAsJsonArray("guards")) guards.add(g.getAsString());
                out.add(new Monster(
                        o.get("id").getAsString(),
                        o.get("name").getAsString(),
                        o.get("health").getAsDouble(),
                        o.get("armor").getAsDouble(),
                        o.get("hit").getAsDouble(),
                        o.get("power").getAsInt(),
                        o.get("does").getAsString(),
                        List.copyOf(guards)));
            }
            return List.copyOf(out);
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalStateException("lightborn/monsters.json is missing or broken", e);
        }
    }

    public static Monster monster(String id) {
        for (Monster m : MONSTERS) if (m.id().equals(id)) return m;
        return null;
    }

    /**
     * Every kind, a page each, in tier order (owner, 2026-11-20: "all must be tiered", each a page). Not filtered by
     * installed mods here: this class can load before the companion mods register their monsters, so a missing
     * companion's kind is skipped where it is raised instead. {@code every_monster_has_a_page} fails on any monster
     * left out of the file.
     */
    public static final List<Page> PAGES = MONSTERS.stream()
            .sorted(java.util.Comparator.comparingInt(Monster::tier))
            .map(m -> new Page(m.id(), m.name(), companion(m.id()), m.tier()))
            .toList();

    /** The night's own kinds (the 48 of every Overworld night), the core of every Overworld fountain's guard. */
    public static final List<Page> NIGHT_PAGES = PAGES.stream()
            .filter(p -> monster(p.id()).guards().contains("night"))
            .toList();

    /** Kills of a kind that earn its spirit, by how strong it is (its slot weight). */
    public static int killsNeeded(Page page) {
        return Balance.SPIRIT_KILLS_BY_WEIGHT[Math.clamp(page.weight(), 1, Balance.SPIRIT_KILLS_BY_WEIGHT.length - 1)];
    }

    /** A player's spirits: claimed kinds and their rank (1 to 5), the kinds placed in the book, and its slot count. */
    public record Spirits(Map<String, Integer> ranks, List<String> slots, int slotCount) {
        public static final Spirits NEW = new Spirits(Map.of(), List.of(), Balance.SPIRIT_START_SLOTS);
        static final Codec<Spirits> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Codec.unboundedMap(Codec.STRING, Codec.INT)
                                .fieldOf("ranks")
                                .forGetter(Spirits::ranks),
                        Codec.STRING.listOf().fieldOf("slots").forGetter(Spirits::slots),
                        Codec.INT.fieldOf("slot_count").forGetter(Spirits::slotCount))
                .apply(i, Spirits::new));
        static final StreamCodec<ByteBuf, Spirits> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT),
                s -> new HashMap<>(s.ranks()),
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                Spirits::slots,
                ByteBufCodecs.VAR_INT,
                Spirits::slotCount,
                (ranks, slots, count) -> new Spirits(Map.copyOf(ranks), List.copyOf(slots), count));

        public int rank(String kind) {
            return ranks.getOrDefault(kind, 0);
        }

        /** Slots the placed spirits take. */
        public int used() {
            int n = 0;
            for (String kind : slots) {
                Page page = page(kind);
                n += page == null ? 1 : page.weight();
            }
            return n;
        }

        /**
         * These spirits with the book never overfilled: placed spirits that no longer fit (a kind whose tier grew,
         * owner 2026-11-18) leave the book, last placed first. They stay claimed, at their rank.
         */
        Spirits fitted() {
            if (used() <= slotCount) return this;
            var kept = new ArrayList<>(slots);
            while (!kept.isEmpty() && new Spirits(ranks, kept, slotCount).used() > slotCount) kept.removeLast();
            return new Spirits(ranks, List.copyOf(kept), slotCount);
        }
    }

    // ---------------------------------------------------------------- chest packets

    public static final int CLAIM = 0, UPGRADE = 1, SLOT = 2, BUY_SLOT = 3;

    /** Client → server, from the chest's Spirits tab. */
    public record Act(BlockPos chest, int action, String kind) implements CustomPacketPayload {
        public static final Type<Act> TYPE = new Type<>(ModItems.id("spirit_act"));
        static final StreamCodec<RegistryFriendlyByteBuf, Act> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                Act::chest,
                ByteBufCodecs.VAR_INT,
                Act::action,
                ByteBufCodecs.STRING_UTF8,
                Act::kind,
                Act::new);

        @Override
        public Type<Act> type() {
            return TYPE;
        }
    }

    /** Server → client: the outcome line under the Spirits tab. */
    public record Note(BlockPos chest, String message) implements CustomPacketPayload {
        public static final Type<Note> TYPE = new Type<>(ModItems.id("spirit_note"));
        static final StreamCodec<RegistryFriendlyByteBuf, Note> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Note::chest, ByteBufCodecs.STRING_UTF8, Note::message, Note::new);

        @Override
        public Type<Note> type() {
            return TYPE;
        }
    }

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Act.TYPE, Act.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Note.TYPE, Note.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Act.TYPE, (a, ctx) -> {
            var p = ctx.player();
            if (!LightShop.near(p, a.chest())) return;
            String message = act(p, a.action(), a.kind());
            ctx.responseSender().sendPacket(new Note(a.chest(), message));
        });
    }

    static String act(ServerPlayer p, int action, String kind) {
        return switch (action) {
            case CLAIM -> claim(p, kind);
            case UPGRADE -> upgrade(p, kind);
            case SLOT -> toggleSlot(p, kind);
            case BUY_SLOT -> buySlot(p);
            default -> "";
        };
    }

    // ---------------------------------------------------------------- state

    public static Spirits spirits(Player p) {
        return p.getAttachedOrElse(ModData.SPIRITS, Spirits.NEW).fitted();
    }

    static void set(ServerPlayer p, Spirits s) {
        p.setAttached(ModData.SPIRITS, s);
    }

    public static Page page(String id) {
        for (Page page : PAGES) if (page.id().equals(id)) return page;
        return null;
    }

    /**
     * A monster's power tier, 1 to 5: its page's tier, or 1 for a kind the book does not list (owner, 2026-11-18: the
     * tier also caps the spell tomes it may drop).
     */
    public static int tierOf(net.minecraft.world.entity.Entity e) {
        Page page = page(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(e.getType())
                .toString());
        return page == null ? 1 : page.weight();
    }

    public static int kills(Player p, Page page) {
        return p.getAttachedOrElse(ModData.SOUL_COUNTS, Map.<String, Integer>of())
                .getOrDefault(page.id(), 0);
    }

    /** Slain often enough: the spirit is there to claim. */
    public static boolean earned(Player p, Page page) {
        return kills(p, page) >= killsNeeded(page);
    }

    public static int rank(Player p, Page page) {
        return spirits(p).rank(page.id());
    }

    /** What the next rank costs, or -1 at the top. Rank 2 is the first upgrade. */
    public static int upgradeCost(int rank) {
        return rank >= 1 && rank < Balance.SPIRIT_MAX_RANK ? Balance.SPIRIT_RANK_COSTS[rank - 1] : -1;
    }

    /** What the next slot costs, or -1 when the book is full. */
    public static int slotCost(int slotCount) {
        int i = slotCount - Balance.SPIRIT_START_SLOTS;
        return i >= 0 && i < Balance.SPIRIT_SLOT_COSTS.length ? Balance.SPIRIT_SLOT_COSTS[i] : -1;
    }

    public static String roman(int rank) {
        return switch (rank) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> "";
        };
    }

    /** Extra health and damage at this rank, as a fraction (rank I = 0). */
    public static double bonus(int rank) {
        return Balance.SPIRIT_RANK_BONUS * Math.max(0, rank - 1);
    }

    public static boolean summoned(Entity e) {
        return e.getAttached(ModData.SOUL_KIND) != null || e.getAttached(ModData.RAISED_UNTIL) != null;
    }

    static void killed(ServerPlayer p, Entity dead) {
        if (summoned(dead) || dead.getAttached(ModData.THRALL_MASTER) != null) return;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType()).toString();
        Page page = page(id);
        if (page == null) return;
        var counts = new LinkedHashMap<>(p.getAttachedOrElse(ModData.SOUL_COUNTS, Map.<String, Integer>of()));
        int count = Math.min(Balance.SPIRIT_KILL_COUNT_CAP, counts.getOrDefault(id, 0) + 1);
        counts.put(id, count);
        p.setAttached(ModData.SOUL_COUNTS, Map.copyOf(counts));
        if (count == killsNeeded(page) && rank(p, page) == 0)
            p.sendSystemMessage(
                    Component.literal("A " + page.label() + " spirit is yours: claim it at the Chest of Light.")
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    // ---------------------------------------------------------------- the chest's Spirits tab

    static String claim(ServerPlayer p, String kind) {
        Page page = page(kind);
        if (page == null) return "";
        if (!earned(p, page))
            return "Slay " + (killsNeeded(page) - kills(p, page)) + " more " + page.label() + " first.";
        Spirits s = spirits(p);
        if (s.rank(kind) > 0) return "The " + page.label() + " spirit is already yours.";
        var ranks = new HashMap<>(s.ranks());
        ranks.put(kind, 1);
        var slots = new ArrayList<>(s.slots());
        boolean placed = s.used() + page.weight() <= s.slotCount();
        if (placed) slots.add(kind);
        set(p, new Spirits(Map.copyOf(ranks), List.copyOf(slots), s.slotCount()));
        chime(p, 1.2f);
        return "The " + page.label() + " spirit joins you"
                + (placed
                        ? ", placed in the book."
                        : ". It needs " + page.weight() + " free slot" + (page.weight() > 1 ? "s" : "")
                                + " in the book.");
    }

    static String upgrade(ServerPlayer p, String kind) {
        Page page = page(kind);
        if (page == null) return "";
        Spirits s = spirits(p);
        int rank = s.rank(kind);
        if (rank == 0) return "Claim the " + page.label() + " spirit first.";
        int cost = upgradeCost(rank);
        if (cost < 0) return "The " + page.label() + " spirit is at its strongest.";
        if (!LightShop.pay(p, cost)) return "You need " + cost + " sparks.";
        var ranks = new HashMap<>(s.ranks());
        ranks.put(kind, rank + 1);
        set(p, new Spirits(Map.copyOf(ranks), s.slots(), s.slotCount()));
        // Raised spirits of this kind take the new rank within a second (Command.tick).
        chime(p, 1.5f);
        return page.label() + " rises to rank " + roman(rank + 1) + ".";
    }

    static String toggleSlot(ServerPlayer p, String kind) {
        Page page = page(kind);
        if (page == null) return "";
        Spirits s = spirits(p);
        if (s.rank(kind) == 0) return "Claim the " + page.label() + " spirit first.";
        var slots = new ArrayList<>(s.slots());
        String message;
        if (slots.remove(kind)) message = page.label() + " leaves the book.";
        else if (s.used() + page.weight() > s.slotCount())
            return "The " + page.label() + " needs " + page.weight() + " free slot" + (page.weight() > 1 ? "s" : "")
                    + ": take a spirit out first.";
        else {
            slots.add(kind);
            message = page.label() + " is placed in the book.";
        }
        set(p, new Spirits(s.ranks(), List.copyOf(slots), s.slotCount()));
        chime(p, 1f);
        return message;
    }

    static String buySlot(ServerPlayer p) {
        Spirits s = spirits(p);
        int cost = slotCost(s.slotCount());
        if (cost < 0) return "The book has all its slots.";
        if (!LightShop.pay(p, cost)) return "You need " + cost + " sparks.";
        set(p, new Spirits(s.ranks(), s.slots(), s.slotCount() + 1));
        chime(p, 0.8f);
        return "The book gains a slot (" + (s.slotCount() + 1) + ").";
    }

    private static void chime(ServerPlayer p, float pitch) {
        p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, .7f, pitch);
    }

    // ---------------------------------------------------------------- the book

    /**
     * Raises every spirit placed in the book in a fan before the player. Those already raised by an earlier cast
     * return to the book first, so a cast always brings the whole book. Fails only when none of them has room.
     */
    static boolean cast(ServerPlayer p) {
        var placed = spirits(p).slots();
        if (placed.isEmpty()) {
            p.sendOverlayMessage(
                    Component.literal("No spirit in the book: slay a monster, then claim its spirit at the Chest")
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
            return false;
        }
        var level = p.level();
        for (Mob old : level.getEntitiesOfClass(
                Mob.class,
                p.getBoundingBox().inflate(160),
                m -> m.getAttached(ModData.SOUL_KIND) != null
                        && p.getUUID().equals(m.getAttached(ModData.THRALL_MASTER)))) {
            level.sendParticles(ParticleTypes.SOUL, old.getX(), old.getY() + .8, old.getZ(), 10, .3, .5, .3, .02);
            old.discard();
        }
        Vec3 look = p.getLookAngle().multiply(1, 0, 1);
        look = look.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : look.normalize();
        boolean soulbinder = SpellUpgrades.special(Spell.SOULS);
        int raised = 0, n = placed.size();
        for (int i = 0; i < n; i++) {
            Page page = page(placed.get(i));
            if (page == null) continue;
            var type = page.type().get();
            Mob mob = type == null ? null : VariantCompat.keepKind(type.create(level, EntitySpawnReason.MOB_SUMMONED));
            if (mob == null) continue;
            float spread = n == 1 ? 0 : (float) ((i - (n - 1) / 2.0) * .55);
            boolean room = false;
            for (double reach : new double[] {2.5, 1.5, 3.5}) {
                Vec3 at = p.position().add(look.yRot(spread).scale(reach));
                mob.setPos(at.x, p.getY(), at.z);
                if (level.noCollision(mob) && level.getWorldBorder().isWithinBounds(mob.blockPosition())) {
                    room = true;
                    break;
                }
            }
            if (!room) continue;
            mob.setAttached(ModData.SOUL_KIND, page.id());
            Monsters.setRank(mob, MonsterInfo.NORMAL);
            if (mob instanceof Zombie zombie) zombie.setBaby(false);
            if (mob instanceof Skeleton) mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            applyRank(p, mob);
            SummonSpells.bindThrall(level, p, mob, soulbinder);
            if (soulbinder) mob.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 400, 0));
            if (SpellUpgrades.perk(p, Spell.SOULS, "Swift Dead")) {
                var speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null && !speed.hasModifier(SWIFT_DEAD))
                    speed.addPermanentModifier(
                            new AttributeModifier(SWIFT_DEAD, .3, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
            level.addFreshEntity(mob);
            raised++;
        }
        if (raised == 0) {
            p.sendOverlayMessage(
                    Component.literal("There is no room to summon here").withStyle(ChatFormatting.GRAY));
            return false;
        }
        return true;
    }

    /** Swift Dead's speed on a raised spirit. */
    static final net.minecraft.resources.Identifier SWIFT_DEAD = ModItems.id("swift_dead");

    /** Gives a raised spirit its owner's current rank for its kind: extra health here, extra damage in Monsters. */
    static void applyRank(ServerPlayer p, Mob mob) {
        String kind = mob.getAttached(ModData.SOUL_KIND);
        Page page = kind == null ? null : page(kind);
        if (page == null) return;
        setRank(mob, Math.max(1, rank(p, page)));
    }

    static void setRank(Mob mob, int rank) {
        var hp = mob.getAttribute(Attributes.MAX_HEALTH);
        var id = ModItems.id("soul_rank_health");
        if (mob.getAttachedOrElse(ModData.SOUL_RANK, 0) == rank && (hp == null || hp.hasModifier(id) == rank > 1))
            return;
        if (hp != null) {
            double wasFull = mob.getHealth() / Math.max(1, mob.getMaxHealth());
            hp.removeModifier(id);
            if (rank > 1)
                hp.addPermanentModifier(
                        new AttributeModifier(id, bonus(rank), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            mob.setHealth((float) (mob.getMaxHealth() * wasFull));
        }
        mob.setAttached(ModData.SOUL_RANK, rank);
    }

    public static List<Component> tooltip(Player player, ItemStack tome) {
        List<Component> lines = new ArrayList<>();
        Spirits s = spirits(player);
        lines.add(Component.literal("Raises every placed spirit · " + s.used() + " / " + s.slotCount() + " slots")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        for (String kind : s.slots()) {
            Page page = page(kind);
            if (page == null) continue;
            lines.add(Component.literal("  " + page.label() + " " + roman(s.rank(kind)) + " · " + page.weight()
                            + (page.weight() == 1 ? " slot" : " slots"))
                    .withStyle(ChatFormatting.AQUA));
        }
        if (s.slots().isEmpty())
            lines.add(Component.literal("  The book is empty").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("Spirits are earned by kills: 40 plain, 20 variant,")
                .withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("10 giant or mutant, 5 huge mutant. Claim, rank up")
                .withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("and place them at the Chest of Light.").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }
}
