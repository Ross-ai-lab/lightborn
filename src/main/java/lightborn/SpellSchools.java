package lightborn;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Every spell belongs to one school, and each school keeps its spells' code in its own class (owner, 2026-11-23: the
 * shelves the spells were added in "look chaotic"): {@link ArcaneSpells}, {@link FireSpells}, {@link StormSpells},
 * {@link FrostSpells}, {@link EarthSpells}, {@link NatureSpells}, {@link LightSpells}, {@link HexSpells},
 * {@link TravelSpells}, {@link FormSpells}, {@link SummonSpells} and {@link WorkSpells}, with the helpers they share in
 * {@link SpellKit}. A few spells with whole systems of their own keep their own class and are dispatched from their
 * school (Radiant Pulse, Treasure Thread, Flight, Cage, Taunt, Workbench, the Book of Souls). A new spell goes into the
 * school it fits, and {@link #school} will not compile until it is placed.
 * <p>
 * This class fans the lifecycle out to the schools: casting, the server tick, clearing every effect, chunk loads, and
 * the damage hooks that several schools share, in the order they ran before the move.
 */
public final class SpellSchools {
    private SpellSchools() {}

    enum School {
        ARCANE,
        FIRE,
        STORM,
        FROST,
        EARTH,
        NATURE,
        LIGHT,
        HEX,
        TRAVEL,
        FORM,
        SUMMON,
        WORK
    }

    /** The school a spell belongs to. */
    static School school(Spell spell) {
        return switch (spell) {
            case ARCANE_BOLT, GRAVITY_WELL, RUNE_SNARE, MIRROR_WARD, REWIND, TWINCAST, WELLSPRING, TIME_STOP ->
                School.ARCANE;
            case FIREBALL, METEOR, EMBERWALK -> School.FIRE;
            case CHAIN_LIGHTNING, BALL_LIGHTNING, TEMPEST, CYCLONE, WATER_SPLASH -> School.STORM;
            case FROST_NOVA, GLACIAL_SPIKE, BLIZZARD, SENTINEL, FROSTWALK -> School.FROST;
            case STONESKIN, STONE_WALL, EARTHSHATTER, BOULDER, CRYSTAL_SHELL -> School.EARTH;
            case THORNFIELD, LIFEBLOOM, RENEWAL, PURIFY -> School.NATURE;
            case MENDING_LIGHT,
                    SUNBEAM,
                    WISPS,
                    HALO_DISC,
                    STARFALL,
                    SPLINTER,
                    BANISH,
                    DAWNBREAK,
                    LIGHTBRAND,
                    BLADE_RING,
                    SANCTUARY,
                    LIGHT_BRIDGE,
                    SPIRIT_STEED -> School.LIGHT;
            case SIPHON, CONTAGION, SOUL_LINK, DOOM, ENFEEBLE, FRIGHT, ECLIPSE, CAGE, TAUNT -> School.HEX;
            case BLINK, TETHER, TRANSPOSE, MOONGATE, HOMEWARD, UPDRAFT, COMET_DASH, FLIGHT -> School.TRAVEL;
            case WOLF_FORM, BEAR_FORM, SPIDER_FORM, BLAZE_FORM, WARDEN_FORM, SPIRIT_WALK, SHRINKING, GILLS, ASCENSION ->
                School.FORM;
            case SPIRIT_WOLVES, COLOSSUS, SOULS -> School.SUMMON;
            case VERDANT_SURGE,
                    REAP,
                    FELLING,
                    HERD_CALL,
                    QUICKENING,
                    EXCAVATE,
                    ORE_SIGHT,
                    FORGE_FIRE,
                    TRANSMUTE,
                    LIGHT_BORE,
                    WAYGLOW,
                    GATHERING,
                    ENDER_POCKET,
                    WORKBENCH,
                    SKIES,
                    OWL_SIGHT,
                    RADIANT_PULSE,
                    TREASURE_THREAD -> School.WORK;
        };
    }

    /** Casts a spell through its school; true when it took effect. */
    static boolean perform(ServerLevel level, ServerPlayer p, Spell spell, double dmg) {
        return switch (school(spell)) {
            case ARCANE -> ArcaneSpells.perform(level, p, spell, dmg);
            case FIRE -> FireSpells.perform(level, p, spell, dmg);
            case STORM -> StormSpells.perform(level, p, spell, dmg);
            case FROST -> FrostSpells.perform(level, p, spell, dmg);
            case EARTH -> EarthSpells.perform(level, p, spell, dmg);
            case NATURE -> NatureSpells.perform(level, p, spell, dmg);
            case LIGHT -> LightSpells.perform(level, p, spell, dmg);
            case HEX -> HexSpells.perform(level, p, spell, dmg);
            case TRAVEL -> TravelSpells.perform(level, p, spell, dmg);
            case FORM -> FormSpells.perform(level, p, spell, dmg);
            case SUMMON -> SummonSpells.perform(level, p, spell, dmg);
            case WORK -> WorkSpells.perform(level, p, spell, dmg);
        };
    }

    static void init() {
        WorkSpells.init(); // Owl Sight's packet, Wayglow's Kindled Stride
        ServerLivingEntityEvents.ALLOW_DEATH.register(ArcaneSpells::allowDeath); // Rewind
        ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, source, base, taken, blocked) -> LightSpells.afterDamage(entity, source, taken)); // Lightbrand
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(EarthSpells::allowDamage); // Crystal Shell
        ServerLivingEntityEvents.AFTER_DAMAGE.register(FireSpells::afterDamage); // Emberwalk
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (taken <= 0) return;
            ArcaneSpells.storeBlow(entity, taken); // Time Stop
            struck(entity, source, taken);
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register((e, source, amount) -> {
            if (!(e instanceof Player) && amount > 0) struck(e, source, amount);
            FrostSpells.shatter(e); // the Sentinel's chill
            return true;
        });
        ServerEntityEvents.ENTITY_LOAD.register((e, level) -> ArcaneSpells.wakeHeld(e)); // Time Stop
        FormSpells.init();
    }

    static void tick(MinecraftServer server) {
        ArcaneSpells.tick(server);
        FireSpells.tick(server);
        StormSpells.tick(server);
        FrostSpells.tick(server);
        EarthSpells.tick(server);
        NatureSpells.tick(server);
        LightSpells.tick(server);
        HexSpells.tick(server);
        TravelSpells.tick(server);
        FormSpells.tick(server);
        SummonSpells.tick(server);
        WorkSpells.tick(server);
        SpellKit.tick(server);
    }

    /** Server stopping, or a self-test's clean slate: every running spell effect ends. */
    public static void clear() {
        SpellKit.clear();
        ArcaneSpells.clear();
        FireSpells.clear();
        StormSpells.clear();
        FrostSpells.clear();
        EarthSpells.clear();
        NatureSpells.clear();
        LightSpells.clear();
        HexSpells.clear();
        TravelSpells.clear();
        FormSpells.clear();
        SummonSpells.clear();
        WorkSpells.clear();
    }

    /** Chunk load; true when the entity needs nothing more from the load hook. */
    static boolean onLoad(Entity e) {
        return SpellKit.onLoad(e) || SummonSpells.onLoad(e);
    }

    /** Every hit before armour, in the order the schools' hooks ran before the move. */
    public static float incoming(LivingEntity target, DamageSource source, float amount) {
        if (amount <= 0) return amount;
        amount = FrostSpells.sentryIncoming(target, amount);
        amount = ArcaneSpells.freedomIncoming(target, source, amount);
        if (amount <= 0) return amount;
        amount = Workbench.incoming(target, source, amount);
        if (amount <= 0) return amount;
        amount = HexSpells.weakenIncoming(target, source, amount);
        if (amount <= 0) return amount;
        amount = FormSpells.smallIncoming(target, source, amount);
        if (amount <= 0) return amount;
        amount = HexSpells.doomIncoming(target, source, amount);
        return FormSpells.incoming(target, source, amount);
    }

    /**
     * A blow that lands, killing or not (AFTER_DAMAGE skips a killing blow, so ALLOW_DEATH calls this too): Soul Link
     * shares it and Gigantify's blows shake the monsters round the target.
     */
    private static void struck(LivingEntity entity, DamageSource source, float taken) {
        ServerPlayer by = source.getEntity() == null ? null : WardCombat.owner(source.getEntity());
        if (by == null) return;
        HexSpells.soulLinkStruck(entity, by, taken);
        FormSpells.giantStruck(entity, source, by, taken);
    }
}
