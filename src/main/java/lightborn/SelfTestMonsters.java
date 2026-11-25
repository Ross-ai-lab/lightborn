package lightborn;

import static lightborn.SelfTest.TARGETS;
import static lightborn.SelfTest.base;
import static lightborn.SelfTest.caster;
import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Self-test checks for monster ranks and natural strength, Lightborn and regional kinds, and companion mods. */
final class SelfTestMonsters {
    private SelfTestMonsters() {}

    static void testMonsters() {
        // Check the current range for each tier, including the fallback for unlisted monsters.
        var sparkBad = new java.util.ArrayList<String>();
        for (int t = 1; t <= 5; t++) {
            final int tier = t;
            var page = Souls.PAGES.stream().filter(pg -> pg.weight() == tier).findFirst();
            if (page.isEmpty()) {
                sparkBad.add("no tier " + t);
                continue;
            }
            var mob = page.get().type().get().create(level, EntitySpawnReason.COMMAND);
            for (int i = 0; i < 20 && mob != null; i++) {
                int n = Lightborn.killSparks(mob);
                if (Lightborn.monsterTier(mob) != t || n != Balance.KILL_SPARKS[t])
                    sparkBad.add(page.get().id() + "=" + n);
            }
        }
        var unlisted = EntityTypes.SLIME.create(level, EntitySpawnReason.COMMAND);
        int slimeSparks = Lightborn.killSparks(unlisted);
        if (Lightborn.monsterTier(unlisted) != 1 || slimeSparks != Balance.KILL_SPARKS[1])
            sparkBad.add("slime=" + slimeSparks);
        check("every_monster_pays_sparks_by_tier", sparkBad.isEmpty(), sparkBad);
        // Dark Phantoms vanish by day, a slept-through night included (owner, 2026-11-20); a spirit phantom stays.
        var clock = level.registryAccess().getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
        long oldTime = level.getOverworldClockTime();
        level.clockManager().setTotalTicks(clock, 6000);
        var wild = DarkPhantom.TYPE.create(level, EntitySpawnReason.COMMAND);
        wild.setPos(base.getX() + 4, base.getY() + 30, base.getZ() + 4);
        level.addFreshEntity(wild);
        wild.aiStep();
        var spirit = DarkPhantom.TYPE.create(level, EntitySpawnReason.COMMAND);
        spirit.setPos(base.getX() - 4, base.getY() + 30, base.getZ() - 4);
        spirit.setAttached(ModData.THRALL_MASTER, java.util.UUID.randomUUID());
        level.addFreshEntity(spirit);
        spirit.aiStep();
        boolean spiritStays = !spirit.isRemoved();
        spirit.discard();
        level.clockManager().setTotalTicks(clock, oldTime);
        check("dark_phantoms_vanish_by_day", wild.isRemoved() && spiritStays, wild.isRemoved() + " " + spiritStays);
        Husk near = husk(base.offset(6, 0, 0));
        MonsterInfo info = Monsters.info(near);
        // No levels (owner, 2026-11-16): a husk is always a husk, with its own natural health.
        check(
                "monster_natural_strength",
                info != null
                        && info.rank() == MonsterInfo.NORMAL
                        && Math.abs(near.getMaxHealth()
                                        - near.getAttribute(Attributes.MAX_HEALTH)
                                                .getBaseValue())
                                < 0.01,
                info + " " + near.getMaxHealth());
        // A giant zombie: the big kill of an ordinary night, without any name banner.
        Mob champ = ModEntities.GIANT_ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        champ.setPos(base.getX() + 20, base.getY(), base.getZ() + 8);
        level.addFreshEntity(champ);
        Monsters.setRank(champ, MonsterInfo.NORMAL);
        check(
                "giant_zombie",
                !champ.hasCustomName()
                        && !champ.isBaby()
                        && champ.getBbHeight() > 3f
                        && champ.getMaxHealth() >= 50 - 0.5,
                champ.getMaxHealth() + " hp, " + champ.getBbHeight() + " tall");
        Mob boss = Bosses.spawn(level, base.offset(25, 0, -8), level.getRandom());
        double bossBase =
                boss == null ? 0 : boss.getAttribute(Attributes.MAX_HEALTH).getBaseValue();
        check(
                "boss_spawn_fixed_strength",
                boss != null
                        && Lightborn.isBoss(boss)
                        && Bosses.liveCount() >= 1
                        && Math.abs(boss.getMaxHealth() * Monsters.overflow(boss) - bossBase * Balance.BOSS_HEALTH)
                                < 0.5,
                boss == null ? null : Math.round(boss.getMaxHealth() * Monsters.overflow(boss)) + " of " + bossBase);
        // A boss whose body would pass the 1024 health cap: hits on it must shrink by the same factor.
        Mob big = Bosses.spawn(level, base.offset(25, 0, 8), level.getRandom());
        big.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        Monsters.setRank(big, MonsterInfo.BOSS);
        double over = Monsters.overflow(big);
        // No crit on this hit (5% by default since crits came in): the check is the cap's scaling, not the dice.
        var crit = caster.getAttribute(ModAttributes.WEAPON_CRIT);
        double critBase = crit.getBaseValue();
        crit.setBaseValue(0);
        float taken =
                Monsters.modifyIncomingDamage(big, level, level.damageSources().playerAttack(caster), 100f);
        crit.setBaseValue(critBase);
        check(
                "boss_health_cap",
                over > 1.5 && Math.abs(taken - 100f / over) < 0.01,
                String.format("x%.2f -> %.1f", over, taken));
        big.discard();
        var hit = level.damageSources().mobAttack(champ);
        var pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
        pig.setPos(base.getX() + 20, base.getY(), base.getZ() + 10);
        float scaled = Monsters.modifyIncomingDamage(pig, level, hit, 4f);
        float bossHit =
                Monsters.modifyIncomingDamage(pig, level, level.damageSources().mobAttack(boss), 4f);
        pig.discard();
        check(
                "monster_damage_natural_boss_fixed",
                Math.abs(scaled - 4f) < 0.01 && Math.abs(bossHit - 4f * Balance.BOSS_DAMAGE) < 0.01,
                scaled + " / " + bossHit);

        // Gear drops only in the materials the killer has mined: this killer has mined all three.
        int mined = caster.getAttachedOrElse(OreTiers.MINED, 0);
        caster.setAttached(OreTiers.MINED, 7);
        int empty = 0;
        for (int roll = 0; roll < 20; roll++)
            if (Lightborn.dropLoot(level, champ, Monsters.info(champ), caster).isEmpty()) empty++;
        check("giant_drops_are_optional", empty > 0 && empty < 20, empty);
        var bossDrops = Lightborn.dropLoot(level, boss, Monsters.info(boss), caster);
        check(
                "boss_drops",
                bossDrops.size() >= Balance.BOSS_DROPS
                        && bossDrops.size() <= Balance.BOSS_DROPS + 1
                        && java.util.Set.of(LootRoller.Rarity.LEGENDARY, LootRoller.Rarity.UNCOMMON)
                                .contains(LootRoller.rarity(bossDrops.getFirst()))
                        && bossDrops.stream().allMatch(stack -> LightGearTree.level(stack) == 1),
                bossDrops.size());
        caster.setAttached(OreTiers.MINED, mined);
        champ.discard();
    }

    static Husk husk(BlockPos pos) {
        Husk h = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
        h.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        h.setNoAi(true);
        level.addFreshEntity(h);
        TARGETS.add(h);
        return h;
    }

    /**
     * The zombie kinds in place of champions: none of 200 fresh monsters becomes a champion or wears a name banner; a
     * champion saved before the change loads back as a plain monster; a darkness zombie's hit slows, a vampire's heals it.
     */
    static void testZombieKinds() {
        int champions = 0;
        for (int i = 0; i < 200; i++) {
            Mob z = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            z.setPos(base.getX() - 30, base.getY(), base.getZ() - 30);
            Monsters.onLoad(z, level);
            MonsterInfo mi = Monsters.info(z);
            if (mi.rank() == MonsterInfo.OLD_CHAMPION || z.isCustomNameVisible()) champions++;
            z.discard();
        }
        Mob old = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        old.setPos(base.getX() - 30, base.getY(), base.getZ() - 30);
        old.setAttached(ModData.MONSTER, new MonsterInfo(MonsterInfo.OLD_CHAMPION));
        Monsters.modifier(
                old, Attributes.SCALE, "champion_scale", 0.6, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        old.setCustomName(Component.literal("Giant Zombie").withStyle(ChatFormatting.YELLOW));
        old.setCustomNameVisible(true);
        Monsters.onLoad(old, level);
        boolean migrated = Monsters.info(old).rank() == MonsterInfo.NORMAL
                && !old.hasCustomName()
                && !old.isCustomNameVisible()
                && Math.abs(old.getScale() - 1f) < 0.01f;
        old.discard();
        check(
                "no_champions",
                champions == 0 && migrated,
                champions + " champions of 200, old champion migrated=" + migrated);

        var dark = ModEntities.DARKNESS_ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        var vamp = ModEntities.VAMPIRE_ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        dark.snapTo(base.getX() + 6.5, base.getY(), base.getZ() + 6.5, 0f, 0f);
        vamp.snapTo(base.getX() + 7.5, base.getY(), base.getZ() + 6.5, 0f, 0f);
        level.addFreshEntity(dark);
        level.addFreshEntity(vamp);
        var victim = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
        victim.snapTo(base.getX() + 7, base.getY(), base.getZ() + 5.5, 0f, 0f);
        level.addFreshEntity(victim);
        Monsters.afterDamage(victim, level.damageSources().mobAttack(dark), 2f);
        boolean chilled = victim.hasEffect(MobEffects.SLOWNESS);
        vamp.setHealth(10f);
        Monsters.afterDamage(victim, level.damageSources().mobAttack(vamp), 5f);
        boolean fed = Math.abs(vamp.getHealth() - 12f) < 0.01f;
        check(
                "zombie_traits",
                chilled && fed,
                "darkness slows=" + chilled + ", vampire " + vamp.getHealth() + " (10 + 40% of 5)");
        victim.discard();
        dark.discard();
        vamp.discard();
    }

    // ---- The skeleton and spider kinds ----

    static void testMonsterKinds() {
        var pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
        pig.snapTo(base.getX() + 12.5, base.getY(), base.getZ() + 6.5, 0f, 0f);
        level.addFreshEntity(pig);
        // Ember skeletons loose burning arrows; deadeyes barely miss (uncertainty 1 against vanilla's 6 to 10).
        var ember = ModEntities.EMBER_SKELETON.create(level, EntitySpawnReason.COMMAND);
        ember.snapTo(base.getX() + 4.5, base.getY(), base.getZ() + 6.5, 0f, 0f);
        ember.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        level.addFreshEntity(ember);
        ember.performRangedAttack(pig, 1.0f);
        var arrows = level.getEntitiesOfClass(
                AbstractArrow.class, ember.getBoundingBox().inflate(3));
        boolean burning = !arrows.isEmpty() && arrows.stream().allMatch(a -> a.getRemainingFireTicks() > 0);
        var deadeye = ModEntities.DEADEYE_SKELETON.create(level, EntitySpawnReason.COMMAND);
        var plain = EntityTypes.SKELETON.create(level, EntitySpawnReason.COMMAND);
        boolean aimed = deadeye.rangedAttackUncertainty(level) == 1f
                && plain.rangedAttackUncertainty(level) > 1f
                && deadeye.getAttributeBaseValue(Attributes.FOLLOW_RANGE) == 24;
        check(
                "skeleton_kinds",
                burning && aimed,
                "arrows=" + arrows.size() + " burning=" + burning + " deadeye=" + aimed);
        for (var a : arrows) a.discard();
        ember.discard();
        deadeye.discard();
        plain.discard();
        // A weaver's bite spins a web into the air at the victim's feet, and it melts away again.
        BlockPos webAt = base.offset(12, 0, 8);
        boolean spun =
                WeaverSpider.spin(level, webAt) && level.getBlockState(webAt).is(Blocks.COBWEB);
        WeaverSpider.clearWebs();
        boolean gone = level.getBlockState(webAt).isAir();
        // A hunter spider leaps at prey 4 to 10 blocks off.
        HunterSpider hunter = ModEntities.HUNTER_SPIDER.create(level, EntitySpawnReason.COMMAND);
        hunter.snapTo(base.getX() + 5.5, base.getY(), base.getZ() + 6.5, 0f, 0f);
        level.addFreshEntity(hunter);
        hunter.setTarget(pig);
        boolean leapt = hunter.pounce();
        var v = hunter.getDeltaMovement();
        boolean toward = leapt && v.y > 0.5 && v.x > 0.5;
        check(
                "spider_kinds",
                spun && gone && toward,
                "web=" + spun + "/" + gone + " pounce=" + String.format("%.2f,%.2f,%.2f", v.x, v.y, v.z));
        hunter.discard();
        pig.discard();
        // Each biome's skeleton or spider weight splits 55/25/20, keeping the same total.
        boolean split = true;
        for (int total : new int[] {1, 3, 20, 80, 100, 101}) {
            int[] parts = ModEntities.splitWeight(total);
            split &= parts[0] + parts[1] + parts[2] == total || total < 3;
        }
        int[] hundred = ModEntities.splitWeight(100);
        check(
                "monster_kind_spawns",
                split && hundred[0] == 55 && hundred[1] == 25 && hundred[2] == 20,
                Arrays.toString(hundred));
    }

    /**
     * Every hostile mob another mod adds must get a Lightborn level when it appears. Also logs a census
     * (id, base health, size) so balance rules for the companion mods rest on real numbers.
     */
    static void testModdedMonsters() {
        StringBuilder census = new StringBuilder();
        int total = 0, levelled = 0, greatTypes = 0;
        boolean greatTracked = true;
        List<String> missed = new ArrayList<>();
        BlockPos at = base.offset(-10, 0, 10);
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (key.getNamespace().equals("minecraft") || key.getNamespace().equals(Lightborn.MOD_ID)) continue;
            Entity e;
            try {
                e = type.create(level, EntitySpawnReason.COMMAND);
            } catch (RuntimeException ex) {
                continue;
            }
            if (!(e instanceof Mob mob) || !Monsters.isMonster(e)) continue;
            total++;
            double hp = mob.getAttribute(Attributes.MAX_HEALTH) == null
                    ? 0
                    : mob.getAttribute(Attributes.MAX_HEALTH).getBaseValue();
            census.append(String.format(
                    " %s[hp=%.0f w=%.1f h=%.1f%s]",
                    key, hp, e.getBbWidth(), e.getBbHeight(), Monsters.isHeavy(mob) ? " heavy" : ""));
            mob.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            mob.setNoAi(true);
            boolean heavy = Monsters.isHeavy(mob);
            int trackedBefore = Wilds.greatCount();
            level.addFreshEntity(mob);
            Monsters.onLoad(mob, level); // the load event itself fires a tick later; run its handler now
            if (heavy) {
                greatTypes++;
                greatTracked &= Wilds.greatCount() > trackedBefore;
            }
            MonsterInfo info = Monsters.info(mob);
            if (info != null && (!Monsters.isHeavy(mob) || info.rank() == MonsterInfo.NORMAL)) levelled++;
            else missed.add(key.toString());
            mob.discard();
        }
        Lightborn.LOG.info("LIGHTBORN CENSUS {} modded monsters:{}", total, census);
        check("great_monsters_tracked", greatTypes >= 5 && greatTracked, greatTypes + " heavy kinds tracked");
        check(
                "modded_monsters_levelled",
                total > 0 && levelled == total,
                levelled + "/" + total + (missed.isEmpty() ? "" : " missed " + missed));
        var loader = FabricLoader.getInstance();
        check(
                "remaining_companion_roster_loaded",
                List.of("mutantmonsters", "friendsandfoes", "variantsandventures", "naturalist", "watching").stream()
                        .allMatch(loader::isModLoaded),
                null);
        check(
                "removed_companion_mods_absent",
                !loader.isModLoaded("graveyard") && !loader.isModLoaded("enderzoology"),
                null);
    }
}
