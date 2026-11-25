package lightborn;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Isolated end-to-end regressions appended to the existing server self-test. */
final class SelfTestPhaseSix {
    private final ServerLevel level;
    private final BlockPos at;
    private final FakePlayer p;
    private final List<Mob> made = new ArrayList<>();

    private SelfTestPhaseSix(ServerLevel level, BlockPos at) {
        this.level = level;
        this.at = at;
        p = FakePlayer.get(
                level, new GameProfile(UUID.fromString("642b6e63-3e46-4e84-a600-000000000006"), "PhaseSixTester"));
        p.setGameMode(GameType.SURVIVAL);
        p.setPos(Vec3.atBottomCenterOf(at));
        p.setYRot(-90);
        p.setYHeadRot(-90);
        p.setXRot(0);
        PlayerBonuses.apply(p);
        p.getAttribute(ModAttributes.SPELL_CRIT).setBaseValue(0);
        p.getInventory().clearContent();
        p.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(ModItems.GEAR.get(ModItems.Tier.IRON).get(ModItems.Kind.STAFF)));
        for (int x = -12; x <= 24; x++)
            for (int z = -12; z <= 12; z++) {
                level.setBlockAndUpdate(at.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 7; y++) level.setBlockAndUpdate(at.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
    }

    static void run(ServerLevel level, BlockPos at, Runnable done) {
        SelfTestPhaseSix test = new SelfTestPhaseSix(level, at);
        try {
            test.foodAndGifts();
            SelfTestOwnerTuning.run(level, test.p);
            test.holdStaff();
            test.soulsAndCommand();
            test.regional();
            test.castBasics(done);
        } catch (Exception e) {
            Lightborn.LOG.error("Phase six self-test failed", e);
            check("phase6_no_crash", false, e.toString());
            test.clean();
            done.run();
        }
    }

    private static void check(String key, boolean ok, Object detail) {
        SelfTest.check(key, ok, detail);
    }

    private <T extends Mob> T mob(EntityType<T> type, int x, int z) {
        T mob = type.create(level, EntitySpawnReason.COMMAND);
        mob.setPos(at.getX() + x + .5, at.getY(), at.getZ() + z + .5);
        mob.setNoAi(true);
        Monsters.setRank(mob, MonsterInfo.NORMAL);
        made.add(mob);
        level.addFreshEntity(mob);
        return mob;
    }

    private ItemStack tome(Spell spell) {
        return new ItemStack(ModItems.TOMES.get(spell));
    }

    private boolean cast(Spell spell) {
        Spells.forget(p);
        Progression.setMana(p, 100);
        p.setItemInHand(InteractionHand.OFF_HAND, tome(spell));
        return Spells.cast(p, p.getOffhandItem());
    }

    private void foodAndGifts() {
        Collection.ensure(p);
        var group = Collection.categoryOf("minecraft:torch");
        var first = new LinkedHashSet<String>();
        first.add("minecraft:torch");
        first.add("minecraft:stone");
        String feedback = Collection.giftFeedback(List.of("minecraft:torch"), group, first)
                .getString();
        check(
                "gift_feedback_subgroup",
                feedback.contains("(" + Collection.count(first, group) + " / "
                                + group.items().size() + ")")
                        && !feedback.contains("/ " + Collection.all().size() + ")"),
                feedback);
        var food = p.getFoodData();
        food.setFoodLevel(20);
        food.setSaturation(8);
        p.setHealth(p.getMaxHealth());
        for (int i = 0; i < 200; i++) {
            p.causeFoodExhaustion(10);
            food.addExhaustion(20);
            food.tick(p);
        }
        check(
                "food_activity_no_extra_drain",
                food.getFoodLevel() == 20 && food.getSaturationLevel() == 8,
                food.getFoodLevel());
        p.setHealth(p.getMaxHealth() - 8);
        p.heal(8);
        check("recovering_health_spends_food", food.getFoodLevel() == 19, food.getFoodLevel());
        food.setFoodLevel(20);
        p.setAttached(ModData.FOOD_TIMER, Balance.FOOD_DRAIN_TICKS - 2);
        SteadyFood.tick(p);
        check("food_fixed_timer_before_boundary", food.getFoodLevel() == 20, food.getFoodLevel());
        SteadyFood.tick(p);
        check(
                "food_fixed_timer_exact_boundary",
                food.getFoodLevel() == 19 && p.getAttached(ModData.FOOD_TIMER) == 0,
                food.getFoodLevel());
        p.setAttached(ModData.FOOD_TIMER, Balance.FOOD_DRAIN_TICKS - 1);
        p.setGameMode(GameType.CREATIVE);
        SteadyFood.tick(p);
        check("food_creative_no_drain", food.getFoodLevel() == 19, food.getFoodLevel());
        p.setGameMode(GameType.SURVIVAL);
        p.setAttached(ModData.FOOD_TIMER, 0);
        food.setFoodLevel(20);
    }

    private void holdStaff() {
        p.getInventory().clearContent();
        p.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(ModItems.GEAR.get(ModItems.Tier.IRON).get(ModItems.Kind.STAFF)));
    }

    private void soulsAndCommand() {
        p.removeAttached(ModData.SOUL_COUNTS);
        p.removeAttached(ModData.SPIRITS);
        p.removeAttached(ChestTabs.SOULS_FOUND);
        p.getInventory().clearContent();
        var zombie = Souls.PAGES.getFirst();
        var husk = Souls.page("minecraft:husk");
        // The Book of Souls is a tier-3 tome found like any other (owner, 2026-11-18): it drops, never wears, and the
        // chest's Spirits tab opens once one has been carried.
        ItemStack book = new ItemStack(ModItems.TOMES.get(Spell.SOULS));
        check("souls_book_never_wears", book.getDamageValue() == 0 && !book.isDamageableItem(), null);
        boolean dropped = false;
        var random = RandomSource.create(7);
        for (int i = 0; i < 4000 && !dropped; i++) dropped = TomeItem.spellOf(TomeItem.roll(random, 3)) == Spell.SOULS;
        boolean notFromWeak = true;
        for (int i = 0; i < 2000; i++) notFromWeak &= TomeItem.spellOf(TomeItem.roll(random, 2)) != Spell.SOULS;
        boolean hidden = !ChestTabs.spirits(p);
        p.getInventory().add(book);
        ChestTabs.tick(level.getServer()); // a tick that falls on a whole second, or the next
        boolean found = ChestTabs.carriesSouls(p);
        if (found) p.setAttached(ChestTabs.SOULS_FOUND, true);
        check(
                "souls_book_tier_3_found",
                Spell.SOULS.tier == 3 && Spell.SOULS.drops && dropped && notFromWeak && hidden && ChestTabs.spirits(p),
                "dropped=" + dropped + " notFromWeak=" + notFromWeak + " hidden=" + hidden + " found=" + found);
        p.getInventory().clearContent();
        p.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(ModItems.GEAR.get(ModItems.Tier.IRON).get(ModItems.Kind.STAFF)));
        // Forty kills of a tier 1 kind earn its spirit (owner, 2026-11-20: 40/30/20/10/3); the chest hands it over and
        // places it.
        var firstKill = mob(EntityTypes.ZOMBIE, 10, 7);
        firstKill.hurtServer(level, p.damageSources().playerAttack(p), 10000);
        boolean notYet = Souls.kills(p, zombie) == 1 && !Souls.earned(p, zombie);
        p.setAttached(ModData.SOUL_COUNTS, java.util.Map.of(zombie.id(), 39));
        mob(EntityTypes.ZOMBIE, 10, 7).hurtServer(level, p.damageSources().playerAttack(p), 10000);
        var brute = Souls.page("mutantmonsters:mutant_zombie");
        check(
                "souls_forty_kills_earn_spirit",
                notYet
                        && Souls.kills(p, zombie) == 40
                        && Souls.earned(p, zombie)
                        && !Souls.earned(p, husk)
                        && brute != null
                        && Souls.killsNeeded(brute) == 3
                        && brute.type().get() != null
                        && Souls.killsNeeded(Souls.page("lightborn:giant_zombie")) == 20
                        && Souls.killsNeeded(Souls.page("lightborn:giant_enderman")) == 10
                        && Souls.killsNeeded(Souls.page("minecraft:enderman")) == 30,
                "first=" + notYet + " kills=" + Souls.kills(p, zombie) + " mutant=" + (brute != null));
        check("souls_nothing_until_claimed", Souls.spirits(p).slots().isEmpty() && Souls.rank(p, zombie) == 0, null);
        check("souls_claim_needs_kill", Souls.claim(p, husk.id()).startsWith("Slay"), null);
        String claimed = Souls.claim(p, zombie.id());
        check(
                "souls_claim_places_in_book",
                Souls.rank(p, zombie) == 1 && Souls.spirits(p).slots().equals(List.of(zombie.id())),
                claimed);
        // The cast costs 100 mana (owner, 2026-11-16); the helper fills the bar to 100 first.
        check("souls_native_cast", cast(Spell.SOULS) && Progression.mana(p) == 0, Progression.mana(p));
        var list = p.getAttachedOrElse(ModData.BOUND_THRALLS, List.<UUID>of());
        Mob raised = list.isEmpty() ? null : (Mob) level.getEntity(list.getFirst());
        check(
                "souls_rank_and_binding",
                raised != null
                        && raised.getType() == EntityTypes.ZOMBIE
                        && Monsters.info(raised).rank() == MonsterInfo.NORMAL
                        && SummonSpells.isThrall(raised)
                        && Boolean.TRUE.equals(raised.getAttached(ModData.MINION))
                        && raised.getCustomName() == null
                        && Command.used(p) == 1,
                raised);
        p.getInventory().setItem(8, new ItemStack(ModItems.SPARK, 64));
        p.getInventory().setItem(9, new ItemStack(ModItems.SPARK, 64));
        p.getInventory().setItem(10, new ItemStack(ModItems.SPARK, 64));
        if (raised != null) {
            made.add(raised);
            double hp = raised.getMaxHealth(), damage = Monsters.damageMultiplier(raised);
            int sparks = LightShop.sparks(p);
            String up = Souls.upgrade(p, zombie.id());
            Souls.applyRank(p, raised);
            check(
                    "souls_rank_up_costs_sparks_and_strengthens",
                    Souls.rank(p, zombie) == 2
                            && LightShop.sparks(p) == sparks - Balance.SPIRIT_RANK_COSTS[0]
                            && Math.abs(raised.getMaxHealth() / hp - 1.2) < .001
                            && Math.abs(Monsters.damageMultiplier(raised) / damage - 1.2) < .001,
                    up + " hp " + raised.getMaxHealth());
            UUID original = raised.getUUID();
            Mob evolved = raised.convertTo(
                    EntityTypes.DROWNED,
                    ConversionParams.single(raised, true, true),
                    EntitySpawnReason.CONVERSION,
                    m -> {});
            check(
                    "souls_conversion_keeps_owner_rank_and_command",
                    evolved != null
                            && SummonSpells.isThrall(evolved)
                            && evolved.getAttachedOrElse(ModData.SOUL_RANK, 0) == 2
                            && zombie.id().equals(evolved.getAttached(ModData.SOUL_KIND))
                            && Command.used(p) == 1
                            && p.getAttachedOrElse(ModData.BOUND_THRALLS, List.of())
                                    .contains(evolved.getUUID())
                            && !p.getAttachedOrElse(ModData.BOUND_THRALLS, List.of())
                                    .contains(original),
                    null);
            if (evolved != null) {
                raised = evolved;
                made.add(evolved);
            }
            float health = raised.getHealth();
            raised.hurtServer(level, p.damageSources().playerAttack(p), 5);
            check("souls_master_cannot_hurt_minion", raised.getHealth() == health, raised.getHealth());
            raised.removeAttached(ModData.THRALL_MASTER); // freed, so the farming checks below can kill it
            int kills = Souls.kills(p, zombie);
            raised.hurtServer(level, p.damageSources().playerAttack(p), 10000);
            check(
                    "souls_no_summon_xp_or_soul_farming",
                    p.totalExperience == 0 && Souls.kills(p, zombie) == kills,
                    null);
            check(
                    "souls_no_summon_ingredient_drops",
                    level.getEntitiesOfClass(
                                    ItemEntity.class, raised.getBoundingBox().inflate(1))
                            .isEmpty(),
                    null);
        }
        p.getInventory().setItem(11, new ItemStack(ModItems.SPARK, 64));
        p.getInventory().setItem(12, new ItemStack(ModItems.SPARK, 64));
        p.getInventory().setItem(13, new ItemStack(ModItems.SPARK, 64));
        for (int r = Souls.rank(p, zombie); r < Balance.SPIRIT_MAX_RANK; r++) Souls.upgrade(p, zombie.id());
        check(
                "souls_rank_tops_at_five",
                Souls.rank(p, zombie) == 5 && Souls.upgrade(p, zombie.id()).contains("strongest"),
                Souls.rank(p, zombie));
        // Two slots to start; three more are bought; a spirit takes as many slots as it is strong.
        p.setAttached(
                ModData.SOUL_COUNTS,
                Map.of(
                        zombie.id(),
                        83,
                        husk.id(),
                        80,
                        "minecraft:skeleton",
                        80,
                        "minecraft:spider",
                        80,
                        "lightborn:giant_zombie",
                        20));
        Souls.claim(p, husk.id());
        String full = Souls.claim(p, "minecraft:skeleton");
        check(
                "souls_book_starts_with_two_slots",
                Souls.spirits(p).slotCount() == 2
                        && Souls.spirits(p).slots().size() == 2
                        && full.contains("free slot")
                        && Souls.toggleSlot(p, "minecraft:skeleton").contains("free slot"),
                full);
        int purse = LightShop.sparks(p);
        String bought = Souls.buySlot(p);
        Souls.toggleSlot(p, "minecraft:skeleton");
        check(
                "souls_slot_bought_with_sparks",
                Souls.spirits(p).slotCount() == 3
                        && LightShop.sparks(p) == purse - Balance.SPIRIT_SLOT_COSTS[0]
                        && Souls.spirits(p).slots().contains("minecraft:skeleton"),
                bought);
        Souls.buySlot(p);
        Souls.buySlot(p);
        check(
                "souls_book_tops_at_five_slots",
                Souls.spirits(p).slotCount() == 5 && Souls.buySlot(p).contains("all its slots"),
                Souls.spirits(p).slotCount());
        String giantNote = Souls.claim(p, "lightborn:giant_zombie");
        check(
                "souls_strong_kinds_take_more_slots",
                Souls.page("lightborn:giant_zombie").weight() == 3
                        && Souls.page("minecraft:enderman").weight() == 2
                        && Souls.page(zombie.id()).weight() == 1
                        && Souls.spirits(p).used() == 3
                        && giantNote.contains("3 free slots"),
                giantNote);
        Souls.toggleSlot(p, husk.id());
        Souls.toggleSlot(p, "minecraft:skeleton");
        Souls.toggleSlot(p, "lightborn:giant_zombie");
        check(
                "souls_take_out_and_fill_by_weight",
                !Souls.spirits(p).slots().contains(husk.id())
                        && Souls.spirits(p).slots().contains("lightborn:giant_zombie")
                        && Souls.spirits(p).used() == 4,
                Souls.spirits(p));
        Progression.setMana(p, 1000);
        boolean all = Souls.cast(p);
        var risen = level.getEntitiesOfClass(
                Mob.class,
                p.getBoundingBox().inflate(16),
                m -> m.getAttached(ModData.SOUL_KIND) != null
                        && p.getUUID().equals(m.getAttached(ModData.THRALL_MASTER)));
        made.addAll(risen);
        check(
                "souls_cast_raises_every_placed_spirit",
                all && risen.size() == Souls.spirits(p).slots().size(),
                risen.size() + " of " + Souls.spirits(p).slots());
        var encoded = ModData.SPIRITS.persistenceCodec();
        var data = p.getAttached(ModData.SPIRITS);
        check(
                "souls_persistent_spirits_roundtrip",
                encoded.parse(
                                JsonOps.INSTANCE,
                                encoded.encodeStart(JsonOps.INSTANCE, data).getOrThrow())
                        .getOrThrow()
                        .equals(data),
                null);
        // Sneak + scroll: the off-hand tome turns through the carried tomes, both ways, in a steady order.
        p.getInventory().clearContent();
        p.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(ModItems.GEAR.get(ModItems.Tier.IRON).get(ModItems.Kind.STAFF)));
        p.setItemInHand(InteractionHand.OFF_HAND, tome(Spell.ARCANE_BOLT));
        p.getInventory().setItem(3, tome(Spell.FIREBALL));
        p.getInventory().setItem(7, tome(Spell.FROST_NOVA));
        TomeCycle.forget(p);
        List<Spell> seen = new ArrayList<>();
        for (int dir : new int[] {1, 1, -1, -1, -1}) {
            TomeCycle.cycle(p, dir);
            seen.add(TomeItem.spellOf(p.getOffhandItem()));
        }
        var strip = TomeCycle.strip(p);
        check(
                "tome_cycle_turns_both_ways",
                seen.equals(List.of(
                                Spell.FIREBALL, Spell.FROST_NOVA, Spell.FIREBALL, Spell.ARCANE_BOLT, Spell.FROST_NOVA))
                        && strip.tomes().size() == 3
                        && TomeItem.spellOf(strip.tomes().get(strip.held())) == Spell.FROST_NOVA,
                seen);
        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        check(
                "tome_cycle_keeps_shield",
                TomeCycle.cycle(p, 1) == null && p.getOffhandItem().is(Items.SHIELD),
                null);
        TomeCycle.forget(p);
        p.getInventory().clearContent();
        p.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(ModItems.GEAR.get(ModItems.Tier.IRON).get(ModItems.Kind.STAFF)));
        clearBonds();
        var giant = mob(ModEntities.GIANT_ZOMBIE, 10, -6);
        SummonSpells.bindThrall(level, p, giant, false);
        for (int i = 0; i < 2; i++) SummonSpells.bindThrall(level, p, mob(EntityTypes.HUSK, 12 + i * 2, -6), false);
        check("command_giant_uses_four", Command.used(p) == 8, Command.used(p));
        SummonSpells.bindThrall(level, p, mob(EntityTypes.HUSK, 17, -6), false);
        check(
                "command_releases_oldest_within_budget",
                Command.used(p) <= 8 && !SummonSpells.isThrall(giant),
                Command.used(p));
        clearBonds();
        cast(Spell.SPIRIT_WOLVES);
        for (var id : p.getAttachedOrElse(ModData.BOUND_WOLVES, List.<UUID>of()))
            if (level.getEntity(id) instanceof Mob wolf) {
                wolf.setNoAi(true);
                made.add(wolf);
            }
        for (int i = 0; i < 3; i++) SummonSpells.bindThrall(level, p, mob(EntityTypes.HUSK, 10 + i * 2, -8), false);
        check(
                "command_wolves_and_three_thralls_fit",
                Command.used(p) == 8
                        && p.getAttachedOrElse(ModData.BOUND_WOLVES, List.of()).size() == 2,
                Command.used(p));
        check(
                "command_passive_raise_cannot_displace_army",
                BossTrophies.raise(p, Vec3.atCenterOf(at.offset(10, 0, 9)), level) == null && Command.used(p) == 8,
                null);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.SPECIALIST_OF.get(ModItems.Tier.DIAMOND)));
        check("command_bone_staff", Command.capacity(p) == 10, Command.capacity(p));
        p.getInventory().setItem(5, new ItemStack(ModItems.SPECIALIST_OF.get(ModItems.Tier.DIAMOND)));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
        check("command_eating_and_tools_keep_bone_bonus", Command.capacity(p) == 10, Command.capacity(p));
        SummonSpells.bindThrall(level, p, mob(EntityTypes.HUSK, 10, 10), false);
        check("command_expanded_capacity_holds_army", Command.used(p) == 10, Command.used(p));
        p.getInventory().setItem(5, ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        Command.enforce(p);
        check("command_capacity_drop_is_enforced", Command.used(p) == 8, Command.used(p));
        clearBonds();
        var dying = mob(EntityTypes.HUSK, 16, 10);
        SummonSpells.bindThrall(level, p, dying, false);
        dying.hurtServer(level, level.damageSources().generic(), 10000); // its master cannot hurt it
        var pending = level.getServer().overworld().getAttached(ModData.DEAD_COMPANIONS);
        var deathsCodec = ModData.DEAD_COMPANIONS.persistenceCodec();
        check(
                "command_offline_death_nbt_roundtrip",
                pending != null
                        && deathsCodec
                                .parse(
                                        NbtOps.INSTANCE,
                                        deathsCodec
                                                .encodeStart(NbtOps.INSTANCE, pending)
                                                .getOrThrow())
                                .getOrThrow()
                                .equals(pending),
                null);
        check(
                "command_offline_death_frees_saved_capacity",
                Command.used(p) == 0
                        && p.getAttachedOrElse(ModData.BOUND_THRALLS, List.of()).isEmpty(),
                Command.used(p));
        clearBonds();
        PlayerBonuses.apply(p);
        p.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(ModItems.GEAR.get(ModItems.Tier.IRON).get(ModItems.Kind.STAFF)));
    }

    private void clearBonds() {
        for (Mob mob : made) if (mob.getAttached(ModData.THRALL_MASTER) != null) SummonSpells.releaseCompanion(mob);
        for (var id : p.getAttachedOrElse(ModData.BOUND_WOLVES, List.<UUID>of()))
            if (level.getEntity(id) != null) level.getEntity(id).discard();
        p.removeAttached(ModData.BOUND_THRALLS);
        p.removeAttached(ModData.BOUND_WOLVES);
        p.removeAttached(ModData.COMMAND_ROSTER);
    }

    private void regional() throws Exception {
        var victim = mob(EntityTypes.COW, 6, 6);
        victim.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500);
        victim.setHealth(500);
        var frost = mob(ModEntities.FROSTBITTEN_ZOMBIE, 4, 6);
        victim.damageCooldownTime = 0;
        boolean frostHit = frost.doHurtTarget(level, victim);
        check(
                "regional_frostbite_hit",
                frostHit
                        && victim.getTicksFrozen() >= victim.getTicksRequiredToFreeze()
                        && victim.hasEffect(MobEffects.SLOWNESS),
                victim.getTicksFrozen());
        var mire = mob(ModEntities.MIRE_ZOMBIE, 4, 7);
        victim.damageCooldownTime = 0;
        boolean poison = mire.doHurtTarget(level, victim);
        check("regional_mire_poison", poison && victim.hasEffect(MobEffects.POISON), null);
        var dune = mob(ModEntities.DUNE_SKELETON, 3, 6);
        var arrow = dune.getArrow(new ItemStack(Items.ARROW), 1, new ItemStack(Items.BOW));
        arrow.setDeltaMovement(1, 0, 0);
        var arrowHit = AbstractArrow.class.getDeclaredMethod("onHitEntity", EntityHitResult.class);
        arrowHit.setAccessible(true);
        victim.damageCooldownTime = 0;
        arrowHit.invoke(arrow, new EntityHitResult(victim));
        check(
                "regional_dune_arrow_blinds",
                victim.hasEffect(MobEffects.BLINDNESS)
                        && victim.getEffect(MobEffects.BLINDNESS).getDuration() == 40,
                null);
        var canopy = mob(ModEntities.CANOPY_SPIDER, 3, 6);
        canopy.setPos(canopy.position().add(0, 4, 0));
        canopy.setTarget(victim);
        check(
                "regional_canopy_dives",
                canopy.dive() && canopy.getDeltaMovement().y < -.5 && canopy.getDeltaMovement().x > 0,
                canopy.getDeltaMovement());
        var stalker = mob(ModEntities.DEEP_STALKER_SPIDER, 16, 8);
        stalker.aiStep();
        check("regional_stalker_hidden_at_distance", stalker.isInvisible(), null);
        stalker.setAttached(ModData.THRALL_MASTER, p.getUUID());
        stalker.aiStep();
        check("regional_bound_stalker_visible", !stalker.isInvisible(), null);
        stalker.removeAttached(ModData.THRALL_MASTER);
        var captain = mob(ModEntities.DROWNED_CAPTAIN, 1, 6);
        var trident = new ThrownTrident(level, captain, new ItemStack(Items.TRIDENT));
        trident.setDeltaMovement(1, 0, 0);
        var tridentHit = trident.getClass().getDeclaredMethod("onHitEntity", EntityHitResult.class);
        tridentHit.setAccessible(true);
        victim.damageCooldownTime = 0;
        victim.setDeltaMovement(Vec3.ZERO);
        tridentHit.invoke(trident, new EntityHitResult(victim));
        check(
                "regional_captain_trident_pulls",
                victim.getDeltaMovement().x < 0 && captain.getMainHandItem().is(Items.TRIDENT),
                victim.getDeltaMovement());
        var original = WeightedList.<MobSpawnSettings.SpawnerData>builder()
                .add(new MobSpawnSettings.SpawnerData(EntityTypes.SPIDER, UniformInt.of(2, 4)), 100)
                .build();
        var cave = RegionalSpawns.belowZero(original);
        check(
                "regional_cave_pool_preserves_weight_and_validation",
                cave.unwrap().stream().mapToInt(w -> w.weight()).sum() == 100
                        && cave.unwrap().stream()
                                .anyMatch(w -> w.value().type() == ModEntities.DEEP_STALKER_SPIDER && w.weight() == 20)
                        && cave.unwrap().stream()
                                .allMatch(
                                        w -> RegionalSpawns.belowZero(original).contains(w.value())),
                cave.unwrap());
        var biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        var locations = List.of(Biomes.SNOWY_PLAINS, Biomes.SWAMP, Biomes.DESERT, Biomes.JUNGLE, Biomes.OCEAN);
        var kinds = List.of(
                ModEntities.FROSTBITTEN_ZOMBIE,
                ModEntities.MIRE_ZOMBIE,
                ModEntities.DUNE_SKELETON,
                ModEntities.CANOPY_SPIDER,
                ModEntities.DROWNED_CAPTAIN);
        for (int i = 0; i < locations.size(); i++) {
            var kind = kinds.get(i);
            var attribute = EnvironmentAttributes.NATURAL_MOB_SPAWNS;
            var entries = biomes.getOrThrow(locations.get(i))
                    .value()
                    .getAttributes()
                    .applyModifier(attribute, attribute.defaultValue())
                    .getMobsToSpawn(MobCategory.MONSTER)
                    .unwrap();
            check(
                    "regional_biome_" + i,
                    entries.stream().anyMatch(w -> w.value().type() == kind && w.weight() > 0),
                    entries);
        }
        boolean tables = true;
        for (String kind : ModEntities.EGGS.keySet())
            try (var stream = SelfTestPhaseSix.class.getResourceAsStream(
                    "/data/lightborn/loot_table/entities/" + kind + ".json")) {
                tables &= stream != null
                        && JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                                        .getAsJsonObject()
                                        .getAsJsonArray("pools")
                                        .size()
                                > 0;
            }
        check(
                "regional_and_legacy_ingredient_tables",
                tables && ModEntities.EGGS.size() == 26, // + the Dark Phantom, - the Spider Zombie (owner, 2026-11-18)
                ModEntities.EGGS.size() + " eggs, tables " + tables);
    }

    private void castBasics(Runnable done) {
        // Keep the blast fixture apart from all regional targets and companions.
        for (Mob mob : made) mob.discard();
        made.clear();
        var target = mob(EntityTypes.HUSK, 3, 0);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500);
        target.getAttribute(Attributes.ARMOR).setBaseValue(0);
        target.setHealth(500);
        boolean base = cast(Spell.FROST_NOVA);
        ItemStack worn = p.getOffhandItem();
        try {
            check(
                    "cast_pays_mana_and_cooldown_no_wear",
                    base
                            && 500 - target.getHealth() > 0
                            && Math.abs(Progression.mana(p) - (100 - Spell.FROST_NOVA.mana)) < .001
                            && p.getCooldowns().isOnCooldown(worn)
                            && worn.getDamageValue() == 0,
                    (500 - target.getHealth()) + "/" + Progression.mana(p));
        } finally {
            clean();
            done.run();
        }
    }

    private void clean() {
        clearBonds();
        for (Mob mob : made) mob.discard();
        p.getAttribute(ModAttributes.SPELL_POWER).setBaseValue(1);
    }
}
