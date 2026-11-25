package lightborn;

import static lightborn.SpellKit.*;
import static lightborn.SpellKit.chest;
import static lightborn.SpellKit.sound;
import static lightborn.SpellUpgrades.Stat.*;
import static lightborn.SpellUpgrades.Stat.AREA;
import static lightborn.SpellUpgrades.Stat.DURATION;
import static lightborn.SpellUpgrades.Stat.REACH;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Forms: spells that change your body. Wolf, Bear, Spider, Blaze and Warden Form, Spirit Walk, Shrinking, Gills
 * and Gigantify.
 */
public final class FormSpells {
    private FormSpells() {}

    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case WOLF_FORM, BEAR_FORM, SPIDER_FORM, BLAZE_FORM, WARDEN_FORM, SPIRIT_WALK ->
                castForm(level, p, spell, dmg);
            case SHRINKING -> shrink(level, p);
            case GILLS -> gills(level, p);
            case ASCENSION -> gigantify(level, p);
            default -> throw new IllegalArgumentException(spell + " is not a form");
        };
    }

    static void init() {
        initForms();
    }

    static void tick(MinecraftServer server) {
        tickAscents();
        tickSmall();
        tickForms(server);
    }

    static void clear() {
        new ArrayList<>(ASCENTS.keySet()).forEach(FormSpells::descend);
        ASCENTS.clear();
        for (var p : new ArrayList<>(SMALL.keySet())) unshrink(p);
        clearAll();
    }

    /** How many of each effect are running, for the self-test. */
    static int live(String what) {
        return switch (what) {
            case "ascents" -> ASCENTS.size();
            case "small" -> SMALL.size();
            default -> -1;
        };
    }

    /** Gigantify: every blow you land shakes the monsters round your target. */
    static void giantStruck(LivingEntity entity, DamageSource source, ServerPlayer by, float taken) {
        if (source.is(DamageTypes.PLAYER_ATTACK) && source.getEntity() == by && ascended(by) && entity != by)
            for (var e : Perks.nearestMonsters(by, entity, entity.position(), 3, 16)) {
                Spells.hit(by, e, taken * .5, Spell.ASCENSION);
                throwFrom(e, entity.position(), .6, .3);
                by.level().sendParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), chest(e).y, e.getZ(), 1, 0, 0, 0, 0);
            }
    }

    /** Before armour: Light as a Mote spares a small player falls. */
    static float smallIncoming(LivingEntity target, DamageSource source, float amount) {
        if (target instanceof ServerPlayer p && source.is(DamageTypes.FALL)) {
            var s = SMALL.get(p);
            if (s != null && s.mote()) return 0;
        }
        return amount;
    }

    // ---------------------------------------------------------------- Gigantify

    static final int ASCENT_TICKS = 600;
    static final double GIANT_SCALE = 3;
    // owner, 2026-11-23: was 5
    static final net.minecraft.resources.Identifier SCALE_ID = ModItems.id("ascension_scale"),
            REACH_ID = ModItems.id("ascension_reach"),
            BLOCKS_ID = ModItems.id("ascension_block_reach"),
            GIANT_STRENGTH_ID = ModItems.id("ascension_strength"),
            STEP_ID = ModItems.id("ascension_step"),
            JUMP_ID = ModItems.id("ascension_jump"),
            FALL_ID = ModItems.id("ascension_fall"),
            SPEED_ID = ModItems.id("ascension_speed");

    record Ascent(long until, boolean trample) {}

    static final Map<ServerPlayer, Ascent> ASCENTS = new IdentityHashMap<>();

    /**
     * Whether a player standing where {@code p} stands, {@code scale} times their natural size, would touch no block.
     */
    static boolean roomFor(ServerPlayer p, double scale) {
        double w = .6 * scale / 2, h = 1.8 * scale;
        var box = new AABB(p.getX() - w, p.getY() + .01, p.getZ() - w, p.getX() + w, p.getY() + h, p.getZ() + w);
        return p.level().noCollision(p, box);
    }

    /**
     * Sets {@code p} to {@code scale} times their natural size with the reach, step, jump and fall a body that size
     * needs; scale 1 takes it all away again.
     */
    static void resize(ServerPlayer p, double scale) {
        double grow = scale - 1;
        var ids = List.of(SCALE_ID, REACH_ID, BLOCKS_ID, STEP_ID, JUMP_ID, FALL_ID, SPEED_ID);
        var attrs = List.of(
                Attributes.SCALE,
                Attributes.ENTITY_INTERACTION_RANGE,
                Attributes.BLOCK_INTERACTION_RANGE,
                Attributes.STEP_HEIGHT,
                Attributes.JUMP_STRENGTH,
                Attributes.SAFE_FALL_DISTANCE,
                Attributes.MOVEMENT_SPEED);
        if (Math.abs(grow) < 1e-6) {
            for (int i = 0; i < ids.size(); i++) {
                AttributeInstance inst = p.getAttribute(attrs.get(i));
                if (inst != null) inst.removeModifier(ids.get(i));
            }
            return;
        }
        modifyScale(p, Attributes.SCALE, SCALE_ID, grow, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        if (grow > 0) {
            // A giant reaches, steps, jumps and falls in proportion to its size.
            modifyScale(
                    p,
                    Attributes.ENTITY_INTERACTION_RANGE,
                    REACH_ID,
                    grow * 2.25,
                    AttributeModifier.Operation.ADD_VALUE);
            modifyScale(
                    p,
                    Attributes.BLOCK_INTERACTION_RANGE,
                    BLOCKS_ID,
                    grow * 2.25,
                    AttributeModifier.Operation.ADD_VALUE);
            modifyScale(p, Attributes.STEP_HEIGHT, STEP_ID, grow * .6, AttributeModifier.Operation.ADD_VALUE);
            modifyScale(
                    p, Attributes.JUMP_STRENGTH, JUMP_ID, grow * .18, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
            modifyScale(p, Attributes.SAFE_FALL_DISTANCE, FALL_ID, grow * 3, AttributeModifier.Operation.ADD_VALUE);
            modifyScale(
                    p,
                    Attributes.MOVEMENT_SPEED,
                    SPEED_ID,
                    grow * .15,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        }
    }

    /** You grow three times as tall for a while (as far as the space allows): reach, a double blow, less harm. */
    static boolean gigantify(ServerLevel level, ServerPlayer p) {
        int ticks = SpellUpgrades.scaleInt(Spell.ASCENSION, DURATION, ASCENT_TICKS);
        FormSpells.unshrink(p);
        descend(p);
        double scale = 0;
        for (double s = GIANT_SCALE; s >= 2; s -= 1)
            if (roomFor(p, s)) {
                scale = s;
                break;
            }
        if (scale == 0) {
            say(p, "No room to grow here: step out into the open");
            return false;
        }
        resize(p, scale);
        modifyScale(
                p, Attributes.ATTACK_DAMAGE, GIANT_STRENGTH_ID, 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        p.addEffect(new MobEffectInstance(
                MobEffects.RESISTANCE,
                ticks,
                SpellUpgrades.perk(p, Spell.ASCENSION, "Thick Skin") ? 1 : 0,
                false,
                true,
                true));
        if (SpellUpgrades.perk(p, Spell.ASCENSION, "Fire Hide"))
            p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, ticks, 0, false, true, true));
        if (SpellUpgrades.perk(p, Spell.ASCENSION, "Giant Heart"))
            p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ticks, 1, false, true, true));
        if (SpellUpgrades.perk(p, Spell.ASCENSION, "Titan's Stride")) {
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1, false, true, true));
            p.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, ticks, 1, false, true, true));
        }
        if (SpellUpgrades.perk(p, Spell.ASCENSION, "Radiant Form"))
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, 0, false, true, true));
        ASCENTS.put(p, new Ascent(level.getGameTime() + ticks, SpellUpgrades.perk(p, Spell.ASCENSION, "Trample")));
        if (scale < GIANT_SCALE) say(p, "Only room to grow " + Math.round(scale) + " times as tall here");
        atFeet(p, new DustParticleOptions(0xFFE27A, 1.5f), ParticleTypes.END_ROD, 30);
        forOthers(p, ParticleTypes.END_ROD, p.position().add(0, 1.5, 0), 40, .8, .1);
        sound(level, p.position(), SoundEvents.BEACON_POWER_SELECT, 1.4f, .5f);
        sound(level, p.position(), SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, 1f, .6f);
        return true;
    }

    static void modifyScale(
            ServerPlayer p,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
            net.minecraft.resources.Identifier id,
            double amount,
            AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attribute);
        if (inst == null) return;
        inst.removeModifier(id);
        inst.addTransientModifier(new AttributeModifier(id, amount, op));
    }

    static boolean ascended(ServerPlayer p) {
        return ASCENTS.containsKey(p);
    }

    static void descend(ServerPlayer p) {
        boolean was = ASCENTS.remove(p) != null;
        AttributeInstance strength = p.getAttribute(Attributes.ATTACK_DAMAGE);
        if (strength != null) strength.removeModifier(GIANT_STRENGTH_ID);
        if (was
                || p.getAttribute(Attributes.SCALE) != null
                        && p.getAttribute(Attributes.SCALE).getModifier(SCALE_ID) != null) resize(p, 1);
    }

    static void tickAscents() {
        for (var p : new ArrayList<>(ASCENTS.keySet())) {
            var a = ASCENTS.get(p);
            if (p.isRemoved() || !p.isAlive() || p.level().getGameTime() >= a.until()) {
                descend(p);
                if (!p.isRemoved() && p.isAlive()) {
                    p.level()
                            .sendParticles(
                                    ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 20, .4, .6, .4, .05);
                    sound(p.level(), p.position(), SoundEvents.BEACON_DEACTIVATE, 1f, .8f);
                }
                continue;
            }
            long now = p.level().getGameTime();
            if ((now + p.getId()) % 5 == 0)
                p.level()
                        .sendParticles(
                                new DustParticleOptions(0xFFE27A, 1.6f),
                                p.getX(),
                                p.getY() + .2,
                                p.getZ(),
                                4,
                                1.2,
                                .1,
                                1.2,
                                0);
            // Trample: what stands at a giant's feet is struck and thrown aside.
            if (a.trample() && now % 10 == 0) {
                double r = p.getBbWidth() / 2 + 1;
                for (var e : Perks.nearestMonsters(p, null, p.position(), r, 16))
                    if (e.getY() < p.getY() + 2) {
                        Spells.hit(p, e, Perks.flat(p, 4), Spell.ASCENSION);
                        throwFrom(e, p.position(), .9, .4);
                    }
            }
        }
    }

    // ---------------------------------------------------------------- Shrinking

    static final int SHRINK_TICKS = 600;
    static final double SMALL_SCALE = 1 / 6.0;
    // owner, 2026-11-23: six times smaller, was five

    record Small(
            long until,
            boolean unnoticed,
            boolean mote,
            List<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> gifts) {}

    static final Map<ServerPlayer, Small> SMALL = new IdentityHashMap<>();

    /** Six times smaller for a while; you grow back only where a full-size body fits. */
    static boolean shrink(ServerLevel level, ServerPlayer p) {
        int ticks = SpellUpgrades.scaleInt(Spell.SHRINKING, DURATION, SHRINK_TICKS);
        FormSpells.descend(p); // a giant cannot also be small
        FormSpells.resize(p, SMALL_SCALE);
        SMALL.put(
                p,
                new Small(
                        level.getGameTime() + ticks,
                        perk(p, Spell.SHRINKING, "Unnoticed"),
                        perk(p, Spell.SHRINKING, "Light as a Mote"),
                        smallGifts(p, ticks)));
        if (perk(p, Spell.SHRINKING, "Quick Feet"))
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1, false, false, true));
        SpellKit.forOthers(p, ParticleTypes.POOF, p.position().add(0, .5, 0), 16, .3, .02);
        sound(level, p.position(), SoundEvents.PUFFER_FISH_BLOW_OUT, 1.2f, 1.8f);
        return true;
    }

    /** Shrinking's tier II gifts, given for the whole shrink: Hard to Hit, Small Leaps, Small Mend. */
    private static List<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> smallGifts(
            ServerPlayer p, int ticks) {
        List<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> gifts = new ArrayList<>();
        if (perk(p, Spell.SHRINKING, "Hard to Hit")) gifts.add(MobEffects.RESISTANCE);
        if (perk(p, Spell.SHRINKING, "Small Leaps")) gifts.add(MobEffects.JUMP_BOOST);
        if (perk(p, Spell.SHRINKING, "Small Mend")) gifts.add(MobEffects.REGENERATION);
        for (var g : gifts)
            p.addEffect(new MobEffectInstance(g, ticks + 200, g == MobEffects.JUMP_BOOST ? 1 : 0, false, false, true));
        return gifts;
    }

    static boolean small(ServerPlayer p) {
        return SMALL.containsKey(p);
    }

    /** Back to full size at once (another size spell, or the end). */
    public static void unshrink(ServerPlayer p) {
        var s = SMALL.remove(p);
        if (s == null) return;
        FormSpells.resize(p, 1);
        p.removeEffect(MobEffects.SPEED);
        s.gifts().forEach(p::removeEffect);
    }

    static void tickSmall() {
        for (var p : new ArrayList<>(SMALL.keySet())) {
            var s = SMALL.get(p);
            long now = p.level().getGameTime();
            if (p.isRemoved() || !p.isAlive()) {
                unshrink(p);
                continue;
            }
            if (now >= s.until()) {
                // Never grow back into a wall: wait, small, until there is room.
                if (FormSpells.roomFor(p, 1)) {
                    unshrink(p);
                    SpellKit.forOthers(p, ParticleTypes.POOF, p.position().add(0, .9, 0), 16, .3, .02);
                    sound(p.level(), p.position(), SoundEvents.PUFFER_FISH_BLOW_UP, 1f, .8f);
                } else if (now % 20 == 0) say(p, "No room to grow back here: find more space");
                continue;
            }
            if (s.unnoticed() && now % 10 == 0)
                for (var m : p.level()
                        .getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(48)))
                    if (m.getTarget() == p && m.distanceTo(p) > 4) m.setTarget(null);
        }
    }

    // ---------------------------------------------------------------- Gills

    static final int GILL_TICKS = 2400;

    /** Breathe, see and dig under water as on land (Conduit Power) and swim fast (Dolphin's Grace). */
    static boolean gills(ServerLevel level, ServerPlayer p) {
        int ticks = SpellUpgrades.scaleInt(Spell.GILLS, DURATION, GILL_TICKS);
        for (var pl : withFriends(p, perk(p, Spell.GILLS, "Shared Gills"))) {
            pl.addEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER, ticks, 0, false, false, true));
            pl.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, ticks, 0, false, false, true));
        }
        TIDAL.put(
                p,
                new Gill(
                        level.getGameTime() + ticks,
                        perk(p, Spell.GILLS, "Tidal Strength"),
                        perk(p, Spell.GILLS, "Tide Healing"),
                        perk(p, Spell.GILLS, "Pressure Ward"),
                        perk(p, Spell.GILLS, "Sonar"),
                        perk(p, Spell.GILLS, "Undertow")));
        level.sendParticles(ParticleTypes.BUBBLE_POP, p.getX(), p.getY() + .3, p.getZ(), 20, .4, .2, .4, .05);
        flourish(p, 0x4FC3D9);
        sound(level, p.position(), SoundEvents.CONDUIT_ACTIVATE, .8f, 1.6f);
        return true;
    }

    /**
     * The gills' gifts in water while they last: Tidal Strength (Strength I), Tide Healing (a heart every 4 s),
     * Pressure Ward (Resistance I) and Sonar (monsters in the water within 24 glow).
     */
    record Gill(long until, boolean tidal, boolean healing, boolean pressure, boolean sonar, boolean undertow) {}

    static final Map<ServerPlayer, Gill> TIDAL = new IdentityHashMap<>();
    static final double SONAR = 24;

    // ---------------------------------------------------------------- The forms

    /** Which shape a player is in; NONE is 0, so the attachment's default is no form. */
    public enum Form {
        NONE,
        WOLF,
        BEAR,
        SPIDER,
        BLAZE,
        SPIRIT,
        WARDEN
    }

    public static final AttachmentType<Integer> FORM = AttachmentRegistry.create(
            ModItems.id("form"),
            b -> b.initializer(() -> 0).syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.all()));

    /** Where a walking spirit's body stands and the game mode to give back: saved, so a crash still returns you. */
    record Home(String dim, double x, double y, double z, float yaw, int mode) {
        static final Codec<Home> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Codec.STRING.fieldOf("dim").forGetter(Home::dim),
                        Codec.DOUBLE.fieldOf("x").forGetter(Home::x),
                        Codec.DOUBLE.fieldOf("y").forGetter(Home::y),
                        Codec.DOUBLE.fieldOf("z").forGetter(Home::z),
                        Codec.FLOAT.fieldOf("yaw").forGetter(Home::yaw),
                        Codec.INT.fieldOf("mode").forGetter(Home::mode))
                .apply(i, Home::new));
    }

    static final AttachmentType<Home> SPIRIT_HOME =
            AttachmentRegistry.create(ModItems.id("spirit_home"), b -> b.persistent(Home.CODEC));

    static final String BODY_TAG = "lightborn_spirit_body";
    static final double POUNCE_REACH = 12, ROAR_RADIUS = 5, WEB_REACH = 24, BOOM_REACH = 16, BOOM_WIDTH = 1.5;
    static final int WOLF_TICKS = 600, BEAR_TICKS = 600, SPIDER_TICKS = 900, BLAZE_TICKS = 600, SPIRIT_TICKS = 600;
    static final int WARDEN_TICKS = 600;
    static final int POUNCE_EVERY = 60, ROAR_EVERY = 80, WEB_EVERY = 60, VOLLEY_EVERY = 30, BOOM_EVERY = 60;
    /** Swift Spirit's flying speed (vanilla's is 0.05), and Sculk Sense's reach. */
    static final float SWIFT_SPIRIT = .1f, FLY_SPEED = .05f;

    static final double SCULK_SENSE = 16;
    private static final Identifier STRENGTH_ID = ModItems.id("form_strength"),
            HEALTH_ID = ModItems.id("form_health"),
            STEADY_ID = ModItems.id("form_steady");

    /** One player's shape: its spell's power and perks, read once when it was cast. */
    static final class Shape {
        final Form form;
        final long until;
        final double dmg, reach, area;
        final Set<String> perks;
        long ready;
        /** Wolf: the monster a pounce is flying at, and how long it may still land. */
        LivingEntity prey;

        int preyTicks;
        Mannequin body;

        Shape(Form form, long until, double dmg, double reach, double area, Set<String> perks) {
            this.form = form;
            this.until = until;
            this.dmg = dmg;
            this.reach = reach;
            this.area = area;
            this.perks = perks;
        }

        boolean has(String perk) {
            return perks.contains(perk);
        }
    }

    static final Map<ServerPlayer, Shape> SHAPES = new IdentityHashMap<>();
    /**
     * Webs Spider Form has shot: the display over each pinned monster, the monster and the spider. A web holds as long
     * as its spider stays a spider (owner, 2026-11-23).
     */
    record Web(LivingEntity caught, ServerPlayer spider) {}

    private static final Map<Display.BlockDisplay, Web> WEBS = new IdentityHashMap<>();
    /** Feral Bite: monsters bleeding, who made them bleed and until when. */
    private static final Map<LivingEntity, Map.Entry<ServerPlayer, Long>> BLEEDING = new IdentityHashMap<>();

    static void initForms() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((e, source, base, taken, blocked) -> bitten(e, source, taken));
        ServerLivingEntityEvents.ALLOW_DEATH.register((e, source, amount) -> {
            if (!(e instanceof ServerPlayer)) bitten(e, source, amount);
            return true;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> end(h.player));
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> comeHome(h.player));
        // A body left by a crash is not anyone's any more.
        ServerEntityEvents.ENTITY_LOAD.register((e, level) -> {
            if (e.entityTags().contains(BODY_TAG) && SHAPES.values().stream().noneMatch(sh -> sh.body == e))
                e.discard();
        });
    }

    public static Form form(Entity e) {
        int i = e.getAttachedOrElse(FORM, 0);
        return i > 0 && i < Form.values().length ? Form.values()[i] : Form.NONE;
    }

    public static boolean in(Entity e, Form f) {
        return form(e) == f;
    }

    // ---------------------------------------------------------------- The forms: casting

    static boolean castForm(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (spell) {
            case WOLF_FORM -> begin(level, p, Form.WOLF, spell, dmg, WOLF_TICKS, POUNCE_REACH, 0);
            case BEAR_FORM -> begin(level, p, Form.BEAR, spell, dmg, BEAR_TICKS, 0, ROAR_RADIUS);
            case SPIDER_FORM -> begin(level, p, Form.SPIDER, spell, dmg, SPIDER_TICKS, WEB_REACH, 0);
            case BLAZE_FORM -> begin(level, p, Form.BLAZE, spell, dmg, BLAZE_TICKS, 0, 0);
            case SPIRIT_WALK -> begin(level, p, Form.SPIRIT, spell, dmg, SPIRIT_TICKS, 0, 0);
            case WARDEN_FORM -> begin(level, p, Form.WARDEN, spell, dmg, WARDEN_TICKS, BOOM_REACH, 0);
            default -> false;
        };
    }

    private static boolean begin(
            ServerLevel level,
            ServerPlayer p,
            Form form,
            Spell spell,
            double dmg,
            int ticks,
            double reach,
            double area) {
        if (form == Form.SPIRIT && (p.isSpectator() || p.isPassenger() || ChestTravel.flying(p))) {
            say(p, "Your spirit cannot leave your body just now");
            return false;
        }
        end(p);
        if (DragonForm.active(p)) DragonForm.end(p);
        Set<String> perks = new java.util.HashSet<>();
        for (var tier : SpellUpgrades.tiers(spell))
            for (var up : tier)
                if (up.stat() == SpellUpgrades.Stat.PERK && SpellUpgrades.perk(p, spell, up.name()))
                    perks.add(up.name());
        int t = SpellUpgrades.scaleInt(spell, DURATION, ticks);
        var sh = new Shape(
                form,
                level.getGameTime() + t,
                dmg,
                SpellUpgrades.scale(spell, REACH, reach),
                SpellUpgrades.scale(spell, AREA, area),
                perks);
        SHAPES.put(p, sh);
        p.setAttached(FORM, form.ordinal());
        switch (form) {
            case WOLF -> {
                effect(p, MobEffects.SPEED, t, 1);
                effect(p, MobEffects.JUMP_BOOST, t, 1);
                modify(p, Attributes.ATTACK_DAMAGE, STRENGTH_ID, .5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
                if (sh.has("Moon Wolf")) {
                    effect(p, MobEffects.NIGHT_VISION, t, 0);
                    effect(p, MobEffects.STRENGTH, t, 0);
                }
                sound(level, p.position(), SoundEvents.WOLF_GROWL_BABY.value(), 1.4f, .5f);
            }
            case BEAR -> {
                modify(p, Attributes.MAX_HEALTH, HEALTH_ID, 20, AttributeModifier.Operation.ADD_VALUE);
                modify(p, Attributes.KNOCKBACK_RESISTANCE, STEADY_ID, 1, AttributeModifier.Operation.ADD_VALUE);
                modify(p, Attributes.ATTACK_DAMAGE, STRENGTH_ID, 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
                p.heal(20);
                effect(p, MobEffects.RESISTANCE, t, sh.has("Thick Hide") ? 1 : 0);
                sound(level, p.position(), SoundEvents.POLAR_BEAR_WARNING, 1.4f, .8f);
            }
            case SPIDER -> {
                effect(p, MobEffects.NIGHT_VISION, t, 0);
                if (sh.has("Skitter")) effect(p, MobEffects.SPEED, t, 1);
                if (sh.has("Spider Leap")) effect(p, MobEffects.JUMP_BOOST, t, 1);
                sound(level, p.position(), SoundEvents.SPIDER_AMBIENT, 1.4f, .8f);
            }
            case BLAZE -> {
                effect(p, MobEffects.FIRE_RESISTANCE, t, 0);
                sound(level, p.position(), SoundEvents.BLAZE_AMBIENT, 1.4f, .9f);
            }
            case SPIRIT -> walkOut(level, p, sh);
            case WARDEN -> {
                modify(p, Attributes.MAX_HEALTH, HEALTH_ID, 40, AttributeModifier.Operation.ADD_VALUE);
                modify(p, Attributes.KNOCKBACK_RESISTANCE, STEADY_ID, 1, AttributeModifier.Operation.ADD_VALUE);
                modify(p, Attributes.ATTACK_DAMAGE, STRENGTH_ID, 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
                p.heal(40);
                effect(p, MobEffects.RESISTANCE, t, sh.has("Ancient Hide") ? 1 : 0);
                sound(level, p.position(), SoundEvents.WARDEN_EMERGE, 1.2f, 1f);
                sound(level, p.position(), SoundEvents.WARDEN_ROAR, 1f, .9f);
            }
            default -> {}
        }
        // The puff is for the players round you; at your own body it would fill your view.
        SpellKit.forOthers(p, ParticleTypes.POOF, p.position().add(0, .8, 0), 30, .5, .04);
        level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + .2, p.getZ(), 16, .5, .1, .5, .03);
        return true;
    }

    private static void effect(ServerPlayer p, net.minecraft.core.Holder<MobEffect> e, int ticks, int amp) {
        p.addEffect(new MobEffectInstance(e, ticks, amp, false, false, true));
    }

    private static void modify(
            ServerPlayer p,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
            Identifier id,
            double amount,
            AttributeModifier.Operation op) {
        FormSpells.modifyScale(p, attribute, id, amount, op);
    }

    /** Ends {@code p}'s shape, if any: the beast's gifts go and a walking spirit returns to its body. */
    public static void end(ServerPlayer p) {
        var sh = SHAPES.remove(p);
        if (p.getAttachedOrElse(FORM, 0) != 0) p.setAttached(FORM, 0);
        for (var id : List.of(STRENGTH_ID, HEALTH_ID, STEADY_ID))
            for (var a : List.of(Attributes.ATTACK_DAMAGE, Attributes.MAX_HEALTH, Attributes.KNOCKBACK_RESISTANCE)) {
                AttributeInstance inst = p.getAttribute(a);
                if (inst != null) inst.removeModifier(id);
            }
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
        if (p.getAbilities().getFlyingSpeed() != FLY_SPEED) {
            p.getAbilities().setFlyingSpeed(FLY_SPEED);
            p.onUpdateAbilities();
        }
        if (sh == null) {
            comeHome(p);
            return;
        }
        var gifts = switch (sh.form) {
            case WOLF -> List.of(MobEffects.SPEED, MobEffects.JUMP_BOOST, MobEffects.NIGHT_VISION, MobEffects.STRENGTH);
            case BEAR, WARDEN -> List.of(MobEffects.RESISTANCE);
            case SPIDER -> List.of(MobEffects.NIGHT_VISION, MobEffects.SPEED, MobEffects.JUMP_BOOST);
            case BLAZE -> List.of(MobEffects.FIRE_RESISTANCE, MobEffects.LEVITATION, MobEffects.SLOW_FALLING);
            default -> List.<net.minecraft.core.Holder<MobEffect>>of();
        };
        for (var g : gifts) {
            var on = p.getEffect(g);
            // Only what the form gave: one that outlasts the form came from somewhere else.
            if (on != null && on.getDuration() <= sh.until - p.level().getGameTime() + 2 || g == MobEffects.LEVITATION)
                p.removeEffect(g);
        }
        if (sh.form == Form.BLAZE && !p.onGround())
            p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 100, 0, false, false, true));
        if (sh.form == Form.SPIRIT) {
            comeHome(p);
            if (!p.isRemoved() && p.isAlive()) {
                if (sh.has("Haunting"))
                    for (var e : Perks.nearestMonsters(p, null, p.position(), 6, 32)) {
                        Spells.hit(p, e, Perks.flat(p, 8), Spell.SPIRIT_WALK);
                        HexSpells.frighten(p, e, 60, false, false);
                    }
                if (sh.has("Unseen Return")) {
                    for (var m : p.level()
                            .getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32)))
                        if (m.getTarget() == p) m.setTarget(null);
                    p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 100, 0, false, false, true));
                }
            }
        }
        if (sh.body != null) sh.body.discard();
        if (sh.form == Form.SPIDER) unweb(p);
        if (!p.isRemoved() && p.level() instanceof ServerLevel level)
            SpellKit.forOthers(p, ParticleTypes.POOF, p.position().add(0, .8, 0), 20, .4, .03);
    }

    static void clearForms(MinecraftServer server) {
        if (server.getPlayerList() != null) for (var p : server.getPlayerList().getPlayers()) end(p);
        for (var p : new ArrayList<>(SHAPES.keySet())) end(p);
        WEBS.keySet().forEach(SpellKit::drop);
        WEBS.clear();
        BLEEDING.clear();
    }

    /** Only for the self-test, which drives a player the player list does not hold. */
    static void clearAll() {
        for (var p : new ArrayList<>(SHAPES.keySet())) end(p);
        WEBS.keySet().forEach(SpellKit::drop);
        WEBS.clear();
        BLEEDING.clear();
    }

    // ---------------------------------------------------------------- The forms: the spirit's walk

    private static void walkOut(ServerLevel level, ServerPlayer p, Shape sh) {
        p.setAttached(
                SPIRIT_HOME,
                new Home(
                        level.dimension().identifier().toString(),
                        p.getX(),
                        p.getY(),
                        p.getZ(),
                        p.getYRot(),
                        p.gameMode().getId()));
        // The body stays where you stood, wearing your face, while the spirit goes where it likes.
        var body = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (body != null) {
            body.snapTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0);
            body.setProfile(ResolvableProfile.createResolved(p.getGameProfile()));
            body.setHideDescription(true);
            body.setPermanentlyInvulnerable(true);
            body.addTag(BODY_TAG);
            sh.body = body;
            level.addFreshEntity(body);
        }
        p.setGameMode(GameType.SPECTATOR);
        sound(level, p.position(), SoundEvents.SOUL_ESCAPE.value(), 1.4f, .8f);
        SpellKit.forOthers(p, ParticleTypes.SOUL, p.position().add(0, 1, 0), 20, .3, .03);
    }

    /** A walking spirit goes back into its body: where it stood, in its own game mode. */
    static void comeHome(ServerPlayer p) {
        var home = p.getAttached(SPIRIT_HOME);
        if (home == null) return;
        p.removeAttached(SPIRIT_HOME);
        var server = p.level().getServer();
        ServerLevel level = server == null
                ? p.level()
                : server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(home.dim())));
        if (level == null) level = p.level();
        p.setGameMode(GameType.byId(home.mode()));
        p.teleportTo(level, home.x(), home.y(), home.z(), Set.of(), home.yaw(), p.getXRot(), false);
        p.fallDistance = 0;
        p.setDeltaMovement(Vec3.ZERO);
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
        SpellKit.forOthers(p, ParticleTypes.SOUL, new Vec3(home.x(), home.y() + 1, home.z()), 20, .3, .03);
        sound(level, new Vec3(home.x(), home.y(), home.z()), SoundEvents.SOUL_ESCAPE.value(), 1.2f, 1.3f);
    }

    // ---------------------------------------------------------------- The forms: each tick

    static void tickForms(MinecraftServer server) {
        for (var p : new ArrayList<>(SHAPES.keySet())) {
            Shape sh = SHAPES.get(p);
            long now = p.level().getGameTime();
            if (p.isRemoved() || !p.isAlive() || now >= sh.until) {
                end(p);
                if (!p.isRemoved() && p.isAlive())
                    p.sendSystemMessage(
                            Component.literal("You are yourself again").withStyle(ChatFormatting.GRAY), true);
                continue;
            }
            long left = sh.until - now;
            if (left % 20 == 0 && left <= 100)
                p.sendSystemMessage(
                        Component.literal(label(sh.form) + " · " + left / 20 + " s")
                                .withStyle(ChatFormatting.GRAY),
                        true);
            switch (sh.form) {
                case WOLF -> tickPounce(p, sh);
                case BEAR -> {
                    if (sh.has("Mother Bear") && now % 20 == 0)
                        for (var other : p.level()
                                .getEntitiesOfClass(
                                        ServerPlayer.class, p.getBoundingBox().inflate(8)))
                            if (other != p && other.isAlive())
                                other.addEffect(
                                        new MobEffectInstance(MobEffects.RESISTANCE, 40, 0, false, false, true));
                }
                case BLAZE -> hover(p, now);
                case WARDEN -> {
                    if (sh.has("Sculk Sense") && now % 20 == 0)
                        for (var e : Perks.nearestMonsters(p, null, p.position(), SCULK_SENSE, 64))
                            e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false, false));
                }
                case SPIRIT -> {
                    if (!p.isSpectator()) p.setGameMode(GameType.SPECTATOR);
                    if (sh.has("Restful Spirit") && now % 40 == 0) p.heal(2);
                    if (sh.has("Mana Well") && now % 20 == 0) Perks.mana(p, 2);
                    if (sh.has("Spirit Sense") && now % 20 == 0)
                        for (var e : Perks.nearestMonsters(p, null, p.position(), 16, 64))
                            e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false, false));
                    if (sh.has("Swift Spirit") && p.getAbilities().getFlyingSpeed() < SWIFT_SPIRIT) {
                        p.getAbilities().setFlyingSpeed(SWIFT_SPIRIT);
                        p.onUpdateAbilities();
                    }
                    if (now % 4 == 0)
                        SpellKit.forOthers(p, ParticleTypes.SOUL, p.position().add(0, .9, 0), 1, .2, .01);
                }
                default -> {}
            }
        }
        for (var it = WEBS.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            var w = en.getValue();
            if (en.getKey().isRemoved()
                    || !w.caught().isAlive()
                    || w.caught().isRemoved()
                    || !in(w.spider(), Form.SPIDER)) {
                SpellKit.drop(en.getKey());
                it.remove();
            }
        }
        for (var it = BLEEDING.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            LivingEntity e = en.getKey();
            ServerPlayer by = en.getValue().getKey();
            long now = e.level().getGameTime();
            if (e.isRemoved()
                    || !e.isAlive()
                    || by.isRemoved()
                    || now >= en.getValue().getValue()) {
                it.remove();
                continue;
            }
            if ((now + e.getId()) % 20 == 0) {
                Spells.hit(by, e, Perks.flat(by, 1), Spell.WOLF_FORM);
                if (e.level() instanceof ServerLevel l)
                    l.sendParticles(
                            new DustParticleOptions(0x8A0E0E, 1f), e.getX(), chest(e).y, e.getZ(), 4, .2, .3, .2, 0);
            }
        }
    }

    static String label(Form f) {
        return switch (f) {
            case WOLF -> "Wolf Form";
            case BEAR -> "Bear Form";
            case SPIDER -> "Spider Form";
            case BLAZE -> "Blaze Form";
            case SPIRIT -> "Spirit Walk";
            case WARDEN -> "Warden Form";
            default -> "";
        };
    }

    /**
     * A blaze drifts: slow falling in the air, rising while jump is held (Levitation, which the client's own physics
     * plays smoothly), and it cannot burn.
     */
    private static void hover(ServerPlayer p, long now) {
        p.clearFire();
        boolean rise = p.getLastClientInput().jump() && !p.onGround()
                || p.getLastClientInput().jump() && p.isInWater();
        if (rise) {
            p.removeEffect(MobEffects.SLOW_FALLING);
            if (!p.hasEffect(MobEffects.LEVITATION))
                p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 1000, 2, false, false, false));
        } else {
            if (p.hasEffect(MobEffects.LEVITATION)) p.removeEffect(MobEffects.LEVITATION);
            if (!p.onGround() && !p.hasEffect(MobEffects.SLOW_FALLING))
                p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 1000, 0, false, false, false));
            if (p.onGround() && p.hasEffect(MobEffects.SLOW_FALLING)) p.removeEffect(MobEffects.SLOW_FALLING);
        }
        if (now % 3 == 0 && p.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY() + .3, p.getZ(), 2, .3, .2, .3, .01);
            if (now % 9 == 0)
                level.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + .2, p.getZ(), 1, .2, .1, .2, .01);
        }
    }

    // ---------------------------------------------------------------- The forms: right-click powers

    /** Right-click in a form: its power, if it is ready. True when a form took the click. */
    public static boolean power(ServerPlayer p) {
        Shape sh = SHAPES.get(p);
        if (sh == null) return false;
        long now = p.level().getGameTime();
        if (sh.form == Form.SPIRIT) {
            end(p);
            return true;
        }
        if (now < sh.ready) return true;
        ServerLevel level = p.level();
        boolean used = switch (sh.form) {
            case WOLF -> pounce(level, p, sh);
            case BEAR -> roar(level, p, sh);
            case SPIDER -> web(level, p, sh);
            case BLAZE -> volley(level, p, sh);
            case WARDEN -> sonicBoom(level, p, sh);
            default -> false;
        };
        if (used)
            sh.ready = now
                    + switch (sh.form) {
                        case WOLF -> POUNCE_EVERY;
                        case BEAR -> ROAR_EVERY;
                        case SPIDER -> WEB_EVERY;
                        case WARDEN -> BOOM_EVERY;
                        default -> VOLLEY_EVERY;
                    };
        return true;
    }

    /** Wolf: leaps at the monster you look at, and bites when it reaches it. */
    private static boolean pounce(ServerLevel level, ServerPlayer p, Shape sh) {
        LivingEntity target = Spells.lookTarget(p, sh.reach);
        if (target == null || !SpellKit.monster(p, target)) {
            say(p, "Look at a monster to pounce on it");
            return false;
        }
        Vec3 to = target.position().subtract(p.position());
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        Vec3 dir = new Vec3(to.x, 0, to.z).normalize();
        double speed = Math.min(1.8, .5 + flat * .12);
        p.setDeltaMovement(dir.scale(speed).add(0, .35 + Math.max(0, to.y) * .08, 0));
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
        p.fallDistance = 0;
        sh.prey = target;
        sh.preyTicks = 25;
        sound(level, p.position(), SoundEvents.WOLF_GROWL_BABY.value(), 1.2f, .6f);
        return true;
    }

    private static void tickPounce(ServerPlayer p, Shape sh) {
        if (sh.prey == null) return;
        if (--sh.preyTicks < 0 || !sh.prey.isAlive()) {
            sh.prey = null;
            return;
        }
        if (p.distanceTo(sh.prey) > 2.6) return;
        var level = p.level();
        var prey = sh.prey;
        sh.prey = null;
        p.fallDistance = 0;
        List<LivingEntity> bitten = new ArrayList<>(List.of(prey));
        if (sh.has("Rending Pounce")) bitten.addAll(Perks.nearestMonsters(p, prey, prey.position(), 2.5, 16));
        for (var e : bitten) {
            Spells.hit(p, e, sh.dmg, Spell.WOLF_FORM);
            if (sh.has("Feral Bite")) bleed(p, e);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), chest(e).y, e.getZ(), 1, 0, 0, 0, 0);
        }
        if (sh.has("Healing Bite")) p.heal(2);
        sound(level, prey.position(), SoundEvents.WOLF_GROWL_BABY.value(), 1.4f, .5f);
        sound(level, prey.position(), SoundEvents.PLAYER_ATTACK_CRIT, 1f, .8f);
    }

    private static void bleed(ServerPlayer by, LivingEntity e) {
        bleed(by, e, 100);
    }

    /** {@code e} bleeds for {@code ticks}, one harm a second, as a blow of {@code by}'s. */
    static void bleed(ServerPlayer by, LivingEntity e, int ticks) {
        if (e.isAlive()) BLEEDING.put(e, Map.entry(by, e.level().getGameTime() + ticks));
    }

    /** Bear: a roar that strikes and throws back every monster near. */
    private static boolean roar(ServerLevel level, ServerPlayer p, Shape sh) {
        for (var e : Perks.nearestMonsters(p, null, p.position(), sh.area, 32)) {
            Spells.hit(p, e, sh.dmg, Spell.BEAR_FORM);
            if (!(e instanceof Mob m && Cage.mighty(m))) {
                Vec3 away = e.position().subtract(p.position()).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? p.getLookAngle() : away.normalize();
                double keep = 1 - SpellKit.knockResist(e);
                e.setDeltaMovement(away.scale(1.4 * keep).add(0, .45 * keep, 0));
                e.needsSync = true;
            }
            if (sh.has("Thunderous Roar"))
                e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 2, false, true, true));
        }
        if (sh.has("Bear's Heart")) p.heal(2);
        Spells.ring(level, p.position().add(0, .2, 0), sh.area, new DustParticleOptions(0xE8E2D0, 1.4f));
        level.sendParticles(
                ParticleTypes.CLOUD, p.getX(), p.getY() + .4, p.getZ(), 30, sh.area / 3, .2, sh.area / 3, .1);
        sound(level, p.position(), SoundEvents.POLAR_BEAR_WARNING, 2f, .6f);
        sound(level, p.position(), SoundEvents.RAVAGER_ROAR, .8f, 1.2f);
        return true;
    }

    /** Spider: a web that pins the monster you look at. */
    private static boolean web(ServerLevel level, ServerPlayer p, Shape sh) {
        LivingEntity target = Spells.lookTarget(p, sh.reach);
        if (target == null || !SpellKit.monster(p, target)) {
            say(p, "Look at a monster to web it");
            return false;
        }
        List<LivingEntity> caught = new ArrayList<>(List.of(target));
        if (sh.has("Web Nest")) caught.addAll(Perks.nearestMonsters(p, target, target.position(), 3, 16));
        SpellKit.line(
                level,
                p.getEyePosition().add(p.getLookAngle().scale(2.5)),
                chest(target),
                ParticleTypes.ITEM_COBWEB,
                .5);
        // Held for the rest of the form; the web and its hold go when the spider is itself again (unweb).
        int hold = (int) Math.max(20, sh.until - level.getGameTime()) + 20;
        for (var e : caught) {
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, hold, 5, false, true, true));
            e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, hold, 1, false, true, true));
            if (sh.has("Venom")) e.addEffect(new MobEffectInstance(MobEffects.POISON, 120, 1));
            var d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (d != null) {
                d.setBlockState(Blocks.COBWEB.defaultBlockState());
                float size = (float) Math.max(1, Math.max(e.getBbWidth(), e.getBbHeight()) * 1.1);
                SpellKit.centred(d, size, new Quaternionf(), 0);
                WEBS.put(SpellKit.track(level, d, e.position().add(0, size / 2, 0)), new Web(e, p));
            }
        }
        sound(level, target.position(), SoundEvents.SPIDER_STEP, 1.4f, .6f);
        sound(level, p.position(), SoundEvents.LLAMA_SPIT, 1f, .6f);
        return true;
    }

    /** The spider is itself again: its webs fall and the monsters in them are free. */
    private static void unweb(ServerPlayer p) {
        for (var it = WEBS.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (en.getValue().spider() != p) continue;
            var e = en.getValue().caught();
            if (e.isAlive()) {
                e.removeEffect(MobEffects.SLOWNESS);
                e.removeEffect(MobEffects.WEAKNESS);
            }
            SpellKit.drop(en.getKey());
            it.remove();
        }
    }

    /**
     * Warden: a sonic boom along your gaze, as the warden's own, through walls: every monster within 1.5 blocks of the
     * line is struck and thrown on. Echoing Boom strikes the 2 monsters nearest its end for half; Deep Dark slows the
     * struck hard.
     */
    private static boolean sonicBoom(ServerLevel level, ServerPlayer p, Shape sh) {
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle(), end = eye.add(look.scale(sh.reach));
        List<LivingEntity> struck = new ArrayList<>();
        for (var e : level.getEntitiesOfClass(
                LivingEntity.class,
                new net.minecraft.world.phys.AABB(eye, end).inflate(BOOM_WIDTH + 1),
                e -> SpellKit.monster(p, e))) {
            Vec3 c = chest(e);
            double along = c.subtract(eye).dot(look);
            if (along < 0 || along > sh.reach + e.getBbWidth() / 2) continue;
            if (c.distanceTo(eye.add(look.scale(along))) > BOOM_WIDTH + e.getBbWidth() / 2) continue;
            struck.add(e);
        }
        for (var e : struck) {
            Spells.hit(p, e, sh.dmg, Spell.WARDEN_FORM);
            if (!(e instanceof Mob m && Cage.mighty(m))) {
                double keep = 1 - SpellKit.knockResist(e);
                e.setDeltaMovement(
                        e.getDeltaMovement().add(look.scale(1.2 * keep)).add(0, .3 * keep, 0));
                e.needsSync = true;
            }
            if (sh.has("Deep Dark")) e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 3, false, true, true));
        }
        if (sh.has("Echoing Boom"))
            Perks.nearestMonsters(p, null, end, 6, 32).stream()
                    .filter(e -> !struck.contains(e))
                    .limit(2)
                    .forEach(e -> {
                        Spells.hit(p, e, sh.dmg / 2, Spell.WARDEN_FORM);
                        level.sendParticles(ParticleTypes.SONIC_BOOM, e.getX(), chest(e).y, e.getZ(), 1, 0, 0, 0, 0);
                    });
        // The rings start a little ahead of you, so they never fill your own view.
        for (double d = 2.5; d <= sh.reach; d += 1.2) {
            Vec3 at = eye.add(look.scale(d)).add(0, -.2, 0);
            level.sendParticles(ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
        sound(level, p.position(), SoundEvents.WARDEN_SONIC_BOOM, 1f, 1.5f);
        return true;
    }

    /** Blaze: three fireballs (five with Blazing Volley, or a ring with Fire Ring) where you look. */
    private static boolean volley(ServerLevel level, ServerPlayer p, Shape sh) {
        Vec3 look = p.getLookAngle(), eye = p.getEyePosition();
        double burst = sh.has("Bursting Flame") ? 2.5 : 0;
        if (sh.has("Fire Ring")) {
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
                Spells.shoot(
                        p,
                        Spell.BLAZE_FORM,
                        p.position().add(0, 1, 0).add(dir.scale(.8)),
                        dir.scale(1.1),
                        sh.dmg,
                        burst,
                        20);
            }
        } else {
            int n = sh.has("Blazing Volley") ? 5 : 3;
            Vec3 side = look.cross(new Vec3(0, 1, 0));
            side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
            for (int i = 0; i < n; i++) {
                double off = i - (n - 1) / 2.0;
                Vec3 dir = look.add(side.scale(off * .07)).normalize();
                Spells.shoot(p, Spell.BLAZE_FORM, eye.add(look.scale(.8)), dir.scale(1.3), sh.dmg, burst, 30);
            }
        }
        sound(level, p.position(), SoundEvents.BLAZE_SHOOT, 1.2f, 1f);
        return true;
    }

    // ---------------------------------------------------------------- The forms: blows and harm

    /** A blow a form's body lands with its own hand: the spider's poisons, the wolf's Feral Bite makes it bleed. */
    private static void bitten(LivingEntity e, DamageSource source, float taken) {
        if (taken <= 0 || !(source.getEntity() instanceof ServerPlayer p) || source.getDirectEntity() != p) return;
        if (!source.is(DamageTypes.PLAYER_ATTACK) || e == p) return;
        Shape sh = SHAPES.get(p);
        if (sh == null) return;
        if (sh.form == Form.SPIDER && e.isAlive())
            e.addEffect(
                    sh.has("Venom")
                            ? new MobEffectInstance(MobEffects.POISON, 120, 1)
                            : new MobEffectInstance(MobEffects.POISON, 60, 0));
        if (sh.form == Form.WOLF && sh.has("Feral Bite")) bleed(p, e);
        if (sh.form == Form.SPIDER && sh.has("Sticky Bite") && e.isAlive())
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, true, true));
        if (sh.form == Form.BLAZE && sh.has("Searing Touch") && e.isAlive()) e.igniteForSeconds(4);
    }

    /** Every hit before armour: a spider never takes fall damage, a blaze never burns. */
    public static float incoming(LivingEntity target, DamageSource source, float amount) {
        if (!(target instanceof ServerPlayer p) || amount <= 0) return amount;
        Form f = form(p);
        Shape sh = SHAPES.get(p);
        if (f == Form.BLAZE
                && sh != null
                && sh.has("Heat Haze")
                && source.getEntity() instanceof LivingEntity attacker
                && attacker != p
                && source.getDirectEntity() == attacker) attacker.igniteForSeconds(3);
        if (f == Form.SPIDER && source.is(DamageTypes.FALL)) return 0;
        if (f == Form.BLAZE && (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || source.is(DamageTypes.FALL)))
            return 0;
        return amount;
    }

    /** A spider climbs any wall it walks into (read on both sides: the client moves its own player). */
    public static boolean climbs(LivingEntity e) {
        return e.horizontalCollision && in(e, Form.SPIDER);
    }

    static int liveForms() {
        return SHAPES.size();
    }

    static Shape shape(ServerPlayer p) {
        return SHAPES.get(p);
    }

    static int webs() {
        return WEBS.size();
    }

    /** For the self-test: lands a pending wolf pounce as if the leap had reached its prey. */
    static void tickForTest(ServerPlayer p) {
        Shape sh = SHAPES.get(p);
        if (sh != null && sh.form == Form.WOLF) tickPounce(p, sh);
    }
}
