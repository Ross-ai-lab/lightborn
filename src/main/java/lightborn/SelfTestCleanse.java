package lightborn;

import static lightborn.SelfTest.check;
import static lightborn.SelfTest.level;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level.ExplosionInteraction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Self-test checks for moon nights, the Cleanse, named bosses and the Chest of Light ward. */
final class SelfTestCleanse {
    private SelfTestCleanse() {}

    static void testMoonNights(BlockPos center) {
        var clock = level.registryAccess().getOrThrow(WorldClocks.OVERWORLD);
        long originalTime = level.getOverworldClockTime();
        level.clockManager().setTotalTicks(clock, Balance.DUSK_TICK);
        LongNights.tick(level.getServer());
        float nightRate = level.clockManager().getInstance(clock).rate();
        level.clockManager().setTotalTicks(clock, Balance.DAWN_TICK);
        LongNights.tick(level.getServer());
        float dayRate = level.clockManager().getInstance(clock).rate();
        double nightTicks = Balance.DAWN_TICK - Balance.DUSK_TICK;
        double ratio = (nightTicks / nightRate) / ((Hordes.DAY_TICKS - nightTicks) / dayRate);
        check("night_matches_day", dayRate == 1 && nightRate == 1 && Math.abs(ratio - 1) < 0.0001, ratio);
        level.clockManager().setTotalTicks(clock, originalTime);
        LongNights.tick(level.getServer());
        var box = new AABB(center).inflate(55, 12, 55);
        int[] scoutCounts = {0, 1, 1, 3, 0, 0, 0, 0};
        boolean schedule = true;
        for (int day = 0; day <= 8; day++) {
            level.clockManager().setTotalTicks(clock, day * Hordes.DAY_TICKS + 18_000);
            var night = Hordes.MoonNight.read(level);
            schedule &= night.day() == day
                    && night.phase().index() == day % 8
                    && night.darkHours()
                    && Hordes.scoutPacks(night.phase()) == scoutCounts[day % 8]
                    && night.latestHunt() == (day < 4 ? -4 : 4);
        }
        check("moon_night_table", schedule, null);

        for (int x = -38; x <= 38; x++)
            for (int z = -38; z <= 38; z++) {
                level.setBlockAndUpdate(center.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
        var first = FakePlayer.get(
                level, new GameProfile(UUID.fromString("1c639f33-3645-43e0-8f36-b92eb25a4069"), "MoonTester"));
        var second = FakePlayer.get(
                level, new GameProfile(UUID.fromString("1c639f33-3645-43e0-8f36-b92eb25a4070"), "MoonPartner"));
        first.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        second.setPos(first.getX() + 4, first.getY(), first.getZ());
        var chest = center.offset(0, 0, 2);
        try {
            var pack = Hordes.scoutPack(level, first, false);
            check(
                    "moon_scout_spawn",
                    pack.size() >= 2
                            && pack.size() <= 3
                            && pack.stream()
                                    .allMatch(m -> m.distanceTo(first) >= Balance.HUNTER_SPAWN_MIN
                                            && m.distanceTo(first) <= Balance.HUNTER_SPAWN_MAX
                                            && m.entityTags().contains(Hordes.SCOUT_TAG)
                                            && m.getTarget() == first
                                            && m.getAttributeValue(Attributes.FOLLOW_RANGE)
                                                    >= Balance.HUNTER_FOLLOW_RANGE),
                    pack.size());
            pack.forEach(Mob::discard);
            boolean eve = true;
            int window = (Balance.DAWN_TICK - Balance.SCOUT_START_TICK) / Balance.EVE_PACKS;
            for (int slot = 0; slot < Balance.EVE_PACKS; slot++) {
                var eveNight = moonAt(3, Balance.SCOUT_START_TICK + (slot + 1) * window - 1);
                Hordes.scoutTick(level, first, eveNight);
                var scouts = level.getEntitiesOfClass(
                        Mob.class, box, m -> m.entityTags().contains(Hordes.SCOUT_TAG));
                eve &= scouts.size() >= Balance.EVE_MIN && scouts.size() <= Balance.EVE_MAX;
                scouts.forEach(Mob::discard);
                Hordes.scoutTick(level, first, eveNight);
                eve &= level.getEntitiesOfClass(
                                Mob.class, box, m -> m.entityTags().contains(Hordes.SCOUT_TAG))
                        .isEmpty();
            }
            check("moon_eve_three_packs", eve, null);
            level.setBlockAndUpdate(chest, ModItems.LIGHT_CHEST.defaultBlockState());
            check(
                    "moon_ward_scout_veto",
                    LightWards.covers(level, first.blockPosition())
                            && Hordes.scoutPack(level, first, true).isEmpty()
                            && LightWards.covers(level, chest.east(Balance.LIGHT_WARD_RADIUS))
                            && !LightWards.covers(level, chest.east(Balance.LIGHT_WARD_RADIUS + 1)),
                    null);
            first.setAttached(ModData.WARD_TIER, Balance.WARD_RADII.length - 1); // the largest ward, bought with Sparks
            LightWards.claim(level, chest, first);
            for (int x = 45; x <= 56; x++)
                for (int z = -30; z <= 30; z++) {
                    level.setBlockAndUpdate(center.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                }
            var approach = Hordes.prepare(level, first.position(), EntityTypes.ZOMBIE, 0, true, null);
            check(
                    "hunt_reaches_largest_ward",
                    approach != null
                            && approach.blockPosition().distSqr(chest) > 48 * 48
                            && !LightWards.covers(level, approach.blockPosition())
                            && approach.getAttributeValue(Attributes.FOLLOW_RANGE) > approach.distanceTo(first),
                    approach == null ? "no approach" : approach.position());
            if (approach != null) approach.discard();
            first.removeAttached(ModData.WARD_TIER); // back to the starting ward
            LightWards.updateOwned(first);

            long now = Scheduler.now();
            level.setBlockAndUpdate(chest, ModItems.LIGHT_CHEST.defaultBlockState());
            LightWards.claim(level, chest, first);
            testCleanse(chest, box, first, second, now);
            // The roster is fought from the arena's centre: no ward may stand there (a player inside one cannot
            // strike).
            level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
            testBossRoster(center, box, first, now);
            level.setBlockAndUpdate(chest, ModItems.LIGHT_CHEST.defaultBlockState());
            // The boundary checks below are laid out for an 18-block ward: the owner has bought it up to there.
            first.setAttached(ModData.WARD_TIER, 2);
            LightWards.claim(level, chest, first);
            testWard(chest, first);
        } finally {
            Cleanse.clear();
            level.removeAttached(ModData.CLEANSE);
            LightWards.suspend(level, chest, false);
            level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
            check("moon_ward_removed", !LightWards.covers(level, first.blockPosition()), null);
            level.clockManager().setTotalTicks(clock, originalTime);
        }
    }

    private static void testBossRoster(BlockPos center, AABB box, FakePlayer hunter, long now) {
        var collector = FakePlayer.get(
                level, new GameProfile(UUID.fromString("92780c4a-cc50-4331-a9fb-208a4bd42f7e"), "RosterTester"));
        collector.setPos(Vec3.atBottomCenterOf(center));
        boolean roster = true, guaranteed = true, revived = false, enrage = false, order = true;
        for (BossKind kind : BossKind.ROSTER) {
            int index = kind.ordinal();
            order &= BossKind.scheduled(index + 2, level.getRandom()) == kind;
            double angle = index * Math.PI / 3;
            var at = center.offset((int) Math.round(19 * Math.cos(angle)), 0, (int) Math.round(19 * Math.sin(angle)));
            var boss = Bosses.spawn(level, at, Bosses.Encounter.normal(kind));
            if (boss == null) {
                roster = false;
                continue;
            }
            roster &= boss.getType() == kind.body()
                    && boss.getName().getString().equals(kind.title)
                    && !boss.isCustomNameVisible()
                    && boss.getAttributeValue(Attributes.SCALE) >= 2;
            if (kind == BossKind.NYX) {
                Bosses.rememberDeath(hunter, level.damageSources().mobAttack(boss));
                BossAbilities.cast(kind, 1, level, boss, collector);
                var shadows = level.getEntitiesOfClass(
                        Mob.class, new AABB(at).inflate(8), m -> m.entityTags().contains(Bosses.SHADOW_TAG));
                boolean oneHit = shadows.size() == 3;
                for (Mob shadow : shadows) {
                    oneHit &= shadow.getMaxHealth() == 1;
                    shadow.hurtServer(level, level.damageSources().playerAttack(collector), 1);
                    oneHit &= !shadow.isAlive();
                }
                check("nyx_three_one_hit_shadows", oneHit, shadows.size());
            }
            if (kind == BossKind.GORATH) {
                boss.setHealth(boss.getMaxHealth() * 0.49f);
                Bosses.tick(level.getServer());
                enrage = Bosses.enraged(boss)
                        && boss.getAttribute(Attributes.MAX_HEALTH).getBaseValue() == Balance.GORATH_BASE_HEALTH
                        && Math.abs(Bosses.modifyDamage(boss, 100) - 100 * Balance.GORATH_ENRAGED_DAMAGE) < 0.01
                        && level.getEntitiesOfClass(
                                                GiantZombie.class,
                                                new AABB(at).inflate(8),
                                                m -> m.entityTags().contains(Bosses.ADD_TAG))
                                        .size()
                                == Balance.GORATH_ENRAGE_GIANTS;
            }
            int before = trophyCount(box, kind);
            boss.hurtServer(level, level.damageSources().playerAttack(collector), Float.MAX_VALUE);
            if (kind == BossKind.SYRITH) {
                revived = boss.isAlive()
                        && Math.abs(boss.getHealth() / boss.getMaxHealth() - Balance.SYRITH_REVIVE_HEALTH) < 0.01
                        && !collector
                                .getAttachedOrElse(ModData.BOSS_KILLS, List.of())
                                .contains(kind);
                boss.damageCooldownTime = 0;
                boss.hurtServer(level, level.damageSources().playerAttack(collector), Float.MAX_VALUE);
            }
            // Maelis, Syrith and Nyx drop a rolled legendary in place of a trophy of their own.
            guaranteed &= BossTrophies.item(kind) == null || trophyCount(box, kind) == before + 1;
        }
        check("named_boss_roster_and_rotation", roster && order, null);
        check("boss_phase_triggers", revived && enrage, "Syrith revived=" + revived + ", Gorath enraged=" + enrage);
        var history = collector.getAttachedOrElse(ModData.BOSS_KILLS, List.of());
        check(
                "boss_first_trophies",
                guaranteed && history.size() == 6,
                "dropped=" + guaranteed + ", first kills=" + history);
        testBossSpawnSources(center, collector);
        testTrophyPowers(center, collector);

        hunter.setAttached(ModData.BOSS_KILLS, List.of(BossKind.NYX));
        var night = moonAt(36, Balance.DUSK_TICK);
        var chest = center.offset(0, 0, 2);
        level.setBlockAndUpdate(chest, ModItems.LIGHT_CHEST.defaultBlockState());
        LightWards.claim(level, chest, hunter);
        hunter.getInventory().add(new ItemStack(ModItems.DARK_SHARD, Balance.CLEANSE_BOSS_SHARDS));
        boolean begun =
                Cleanse.begin(hunter, chest, Balance.CLEANSE_BOSS_SHARDS).isEmpty();
        var solo = List.<ServerPlayer>of(hunter);
        boolean revenge = false;
        int bosses = 0;
        String bossSeen = "none";
        for (int elapsed = 0; elapsed < 6_000 && Cleanse.running(); elapsed += Hordes.POLL_TICKS) {
            Cleanse.tick(level, night, solo, now + elapsed);
            for (Mob m : hunters(box)) {
                var identity = Bosses.identity(m);
                if (identity != null) {
                    bosses++;
                    revenge |= identity.kind() == BossKind.NYX
                            && identity.revenge().isPresent()
                            && Monsters.info(m).rank() == MonsterInfo.BOSS;
                    bossSeen =
                            identity.kind() + " revenge=" + identity.revenge().isPresent() + " rank="
                                    + Monsters.info(m).rank();
                }
                m.damageCooldownTime = 0; // the world does not tick here, so hit immunity would never wear off
                m.hurtServer(level, level.damageSources().playerAttack(hunter), Float.MAX_VALUE);
            }
        }
        check(
                "boss_grudge_next_cleanse",
                begun
                        && revenge
                        && bosses == 1
                        && !Cleanse.running()
                        && hunter.getAttached(ModData.BOSS_GRUDGE) == null,
                "begun=" + begun + " revenge=" + revenge + " bosses=" + bosses + " seen=" + bossSeen + " grudge="
                        + hunter.getAttached(ModData.BOSS_GRUDGE));
        level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
    }

    private static int trophyCount(AABB box, BossKind kind) {
        if (BossTrophies.item(kind) == null) return 0;
        return level.getEntitiesOfClass(ItemEntity.class, box, e -> e.getItem().is(BossTrophies.item(kind))).stream()
                .mapToInt(e -> e.getItem().getCount())
                .sum();
    }

    private static void testBossSpawnSources(BlockPos center, FakePlayer player) {
        player.setPos(Vec3.atBottomCenterOf(center));
        player.setYRot(-90);
        player.setXRot(0);
        boolean cast = Bosses.summonNear(level, player);
        var summoned = level.getEntitiesOfClass(
                Mob.class,
                new AABB(center).inflate(10),
                m -> m.isAlive() && Bosses.identity(m) != null && m.getTarget() == player);
        check(
                "boss_summon_named",
                cast
                        && summoned.size() == 1
                        && summoned.getFirst().getType()
                                == Bosses.identity(summoned.getFirst()).kind().body(),
                summoned.size());
        summoned.forEach(Mob::discard);

        var original = EntityTypes.HUSK.create(level, EntitySpawnReason.NATURAL);
        var position = center.west(9);
        original.setPos(Vec3.atBottomCenterOf(position));
        Monsters.setRank(original, MonsterInfo.NORMAL);
        level.addFreshEntity(original);
        boolean replaced = Bosses.replaceWild(level, original, level.getRandom());
        var wild = level.getEntitiesOfClass(
                Mob.class, new AABB(position).inflate(3), m -> m.isAlive() && Bosses.identity(m) != null);
        check(
                "boss_wild_named",
                replaced
                        && original.isRemoved()
                        && wild.size() == 1
                        && wild.getFirst().getType()
                                == Bosses.identity(wild.getFirst()).kind().body(),
                wild.size());
        wild.forEach(Mob::discard);
    }

    private static void testTrophyPowers(BlockPos center, FakePlayer player) {
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(BossTrophies.item(BossKind.GORATH)));
        var dummy = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        dummy.setPos(Vec3.atBottomCenterOf(center.north(2)));
        Monsters.setRank(dummy, MonsterInfo.NORMAL);
        level.addFreshEntity(dummy);
        BossTrophies.afterHit(player, level.damageSources().mobAttack(dummy), 1);
        check(
                "boss_trophy_armor",
                player.hasEffect(MobEffects.RESISTANCE) && player.getAbsorptionAmount() >= Balance.PLATE_SHIELD,
                null);
        var raised = BossTrophies.raise(player, Vec3.atBottomCenterOf(center.west(3)), level);
        boolean timed = raised != null
                && SummonSpells.thrallOwner(raised) == player
                && raised.getAttachedOrElse(ModData.RAISED_UNTIL, 0L) == level.getGameTime() + Balance.BONECALLER_TICKS;
        if (raised != null) {
            raised.setAttached(ModData.RAISED_UNTIL, level.getGameTime());
            SpellSchools.tick(level.getServer());
            timed &= raised.isRemoved();
        }
        check("boss_trophy_raised_thrall", timed, null);
        var ash = new ItemStack(BossTrophies.item(BossKind.OZRIK));
        player.setItemInHand(InteractionHand.MAIN_HAND, ash);
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.TOMES.get(Spell.ECLIPSE)));
        dummy.setTarget(player);
        boolean cast = Spells.cast(player, player.getOffhandItem());
        dummy.setTarget(player);
        check(
                "boss_trophy_eclipse",
                cast
                        && BossTrophies.concealed(player)
                        && dummy.getTarget() == null
                        && !dummy.canAttack(player)
                        && player.getCooldowns().isOnCooldown(player.getOffhandItem())
                        && BossTrophies.item(BossKind.NYX) == null,
                null);
        // Striking anything ends Eclipse (owner, 2026-11-22); a block placed or broken does not.
        boolean kept = BossTrophies.concealed(player);
        dummy.damageCooldownTime = 0;
        dummy.hurtServer(level, level.damageSources().playerAttack(player), 1);
        check("eclipse_ends_on_a_strike", kept && !BossTrophies.concealed(player), "before=" + kept);
        double baseDamage =
                Spell.FIREBALL.baseDamage * Balance.SPELL_DAMAGE * player.getAttributeValue(ModAttributes.SPELL_POWER);
        check(
                "boss_trophy_ashbringer",
                ModItems.isStaff(ash) && Math.abs(Spells.damage(player, Spell.FIREBALL) - baseDamage * 1.25) < 0.001,
                null);
        dummy.discard();
    }

    private static void testWard(BlockPos chest, FakePlayer player) {
        var mutantType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("mutantmonsters:mutant_creeper"));
        var mutant = (Mob) mutantType.create(level, EntitySpawnReason.NATURAL);
        // Make the underlying vanilla checks pass so this comparison isolates the actual mixin veto.
        var candidate = new Zombie(EntityTypes.ZOMBIE, level) {
            @Override
            public boolean checkSpawnRules(LevelAccessor world, EntitySpawnReason reason) {
                return true;
            }

            @Override
            public boolean checkSpawnObstruction(LevelReader world) {
                return true;
            }
        };
        try {
            var naturalCheck = NaturalSpawner.class.getDeclaredMethod(
                    "isValidPositionForMob", ServerLevel.class, Mob.class, double.class);
            naturalCheck.setAccessible(true);
            var inside = Vec3.atBottomCenterOf(chest.east(18));
            var outside = Vec3.atBottomCenterOf(chest.east(19));
            candidate.setPos(inside);
            boolean ordinaryInside = (boolean) naturalCheck.invoke(null, level, candidate, 1024.0);
            candidate.getAttribute(Attributes.MAX_HEALTH).setBaseValue(Monsters.HEAVY_HEALTH);
            boolean heavyVeto = !(boolean) naturalCheck.invoke(null, level, candidate, 1024.0);
            candidate.setPos(outside);
            boolean heavyOutside = (boolean) naturalCheck.invoke(null, level, candidate, 1024.0);
            mutant.setPos(inside);
            boolean mutantVeto = LightWards.preventsNaturalSpawn(level, mutant);
            var rim = Vec3.atCenterOf(chest).add(19, 3, 0);
            candidate.snapTo(rim.x, rim.y, rim.z);
            candidate.tickCount = 30;
            candidate.setPos(rim.add(-12, -6, 0));
            var diagonal = new Vec3(-12, -.25, 1);
            var stopped = LightWards.limitMovement(candidate, diagonal);
            check(
                    "ward_rejects_direct_positions_without_pushing_mobs_down",
                    candidate.position().distanceToSqr(rim) < .0001 && stopped.y >= diagonal.y - .0001,
                    candidate.position() + " " + stopped);
            Entity fromEgg = mutantType.spawn(level, chest.west(15), EntitySpawnReason.SPAWN_ITEM_USE);
            check(
                    "ward_natural_hostile_boundary",
                    heavyVeto
                            && heavyOutside
                            && !ordinaryInside
                            && mutantVeto
                            && fromEgg != null
                            && !Bosses.canSpawnWild(level, chest),
                    "heavy inside veto=" + heavyVeto + ", heavy outside=" + heavyOutside + ", ordinary inside="
                            + ordinaryInside + ", mutant veto=" + mutantVeto + ", egg=" + (fromEgg != null));
            if (fromEgg != null) fromEgg.discard();

            var minionType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("mutantmonsters:creeper_minion"));
            var hostile =
                    (net.minecraft.world.entity.TamableAnimal) minionType.create(level, EntitySpawnReason.MOB_SUMMONED);
            hostile.setOwner(mutant);
            hostile.setPos(inside);
            var pet =
                    (net.minecraft.world.entity.TamableAnimal) minionType.create(level, EntitySpawnReason.MOB_SUMMONED);
            pet.setOwner(player);
            pet.setPos(inside);
            level.addFreshEntity(hostile);
            check(
                    "ward_vetoes_mutant_minion_before_aggro",
                    hostile.isRemoved() && !LightWards.rejectsCreeperMinion(level, pet),
                    null);
            pet.discard();

            var creeper = EntityTypes.CREEPER.create(level, EntitySpawnReason.COMMAND);
            var protectedBlock = chest.east(18);
            var exposedBlock = chest.east(19);
            Vec3 blast = Vec3.atCenterOf(exposedBlock).add(-0.55, 0, -1.25);
            var pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
            pig.setPos(Vec3.atBottomCenterOf(chest.offset(21, 0, -2)));
            pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
            pig.setHealth(pig.getMaxHealth());
            level.addFreshEntity(pig);
            var shelteredPig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
            shelteredPig.setPos(Vec3.atBottomCenterOf(chest.offset(16, 0, -2)));
            level.addFreshEntity(shelteredPig);
            level.setBlockAndUpdate(protectedBlock, Blocks.GLASS.defaultBlockState());
            level.setBlockAndUpdate(exposedBlock, Blocks.GLASS.defaultBlockState());
            explodeForWard(creeper, blast, false, ExplosionInteraction.MOB);
            check(
                    "ward_creeper_blast_boundary",
                    level.getBlockState(protectedBlock).is(Blocks.GLASS)
                            && level.getBlockState(exposedBlock).isAir(),
                    null);
            check(
                    "ward_blast_entity_damage",
                    pig.getHealth() < pig.getMaxHealth()
                            && pig.getDeltaMovement().lengthSqr() > 0
                            && shelteredPig.getHealth() == shelteredPig.getMaxHealth(),
                    pig.getHealth() + "/" + pig.getMaxHealth() + " outside, " + shelteredPig.getHealth()
                            + " sheltered");
            pig.discard();
            shelteredPig.discard();
            creeper.discard();

            var ghast = EntityTypes.GHAST.create(level, EntitySpawnReason.COMMAND);
            var fireball = new LargeFireball(level, ghast, new Vec3(1, 0, 0), 4);
            var ghastWall = chest.north(12);
            level.setBlockAndUpdate(ghastWall, Blocks.GLASS.defaultBlockState());
            var fireOrigin = ghastWall.east();
            explodeForWard(fireball, Vec3.atCenterOf(fireOrigin), true, ExplosionInteraction.MOB);
            boolean noFire = true;
            for (BlockPos at : BlockPos.betweenClosed(fireOrigin.offset(-5, 0, -5), fireOrigin.offset(5, 3, 5))) {
                if (LightWards.covers(level, at))
                    noFire &= !level.getBlockState(at).is(BlockTags.FIRE);
            }
            check("ward_ghast_blast_and_fire", level.getBlockState(ghastWall).is(Blocks.GLASS) && noFire, null);
            fireball.discard();
            ghast.discard();

            var wither = EntityTypes.WITHER.create(level, EntitySpawnReason.COMMAND);
            var skull = new WitherSkull(level, wither, new Vec3(1, 0, 0));
            var skullWall = chest.west(13);
            level.setBlockAndUpdate(skullWall, Blocks.GLASS.defaultBlockState());
            explodeForWard(skull, Vec3.atCenterOf(skullWall.south()), false, ExplosionInteraction.MOB);
            check("ward_wither_skull", level.getBlockState(skullWall).is(Blocks.GLASS), null);
            skull.discard();
            wither.discard();

            var mutantWall = chest.south(13);
            level.setBlockAndUpdate(mutantWall, Blocks.GLASS.defaultBlockState());
            mutant.setPos(Vec3.atCenterOf(mutantWall.east()));
            var mutantBlast = Class.forName("fuzs.mutantmonsters.common.world.level.MutatedExplosionHelper")
                    .getMethod("explode", Entity.class, float.class, boolean.class, ExplosionInteraction.class);
            mutantBlast.invoke(null, mutant, 4f, true, ExplosionInteraction.MOB);
            check("ward_real_mutant_blast", level.getBlockState(mutantWall).is(Blocks.GLASS), null);

            var tntWall = chest.east(12);
            level.setBlockAndUpdate(tntWall, Blocks.GLASS.defaultBlockState());
            blast = Vec3.atCenterOf(tntWall.south());
            var tnt = new PrimedTnt(level, blast.x, blast.y, blast.z, player);
            explodeForWard(tnt, blast, false, ExplosionInteraction.TNT);
            check("ward_player_tnt_unfiltered", level.getBlockState(tntWall).isAir(), null);
            tnt.discard();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not run the ward tests through the game hooks", e);
        } finally {
            mutant.discard();
            candidate.discard();
        }
    }

    private static void explodeForWard(Entity source, Vec3 at, boolean fire, ExplosionInteraction type) {
        level.explode(source, at.x, at.y, at.z, 4, fire, type);
    }

    /** The Cleanse at a claimed chest: gates, the fee, the suspended ward, the wave, the cache, resume, dawn, defeat. */
    private static void testCleanse(BlockPos chest, AABB box, FakePlayer player, FakePlayer bystander, long now) {
        var solo = List.<ServerPlayer>of(player);
        // The cache's gear comes in the materials the caller has mined: this caller has mined all three.
        player.setAttached(OreTiers.MINED, 7);
        var day = moonAt(29, 1000);
        boolean gates = Cleanse.problem(level, chest, day) != null
                && Cleanse.problem(level, chest.east(3), moonAt(29, Balance.DUSK_TICK)) != null;
        var night = moonAt(29, Balance.DUSK_TICK);
        gates &= Cleanse.problem(level, chest, night) == null;
        // The Cleanse opens with the first Dark Fountain beaten (owner, 2026-11-18).
        player.removeAttached(ChestTabs.FOUNTAINS_BEATEN);
        String early = Cleanse.begin(player, chest, 1);
        gates &= early.contains("Dark Fountain") && !ChestTabs.cleanse(player);
        ChestTabs.fountainBeaten(player);
        gates &= ChestTabs.cleanse(player) && !ChestTabs.travel(player);
        player.getInventory().add(new ItemStack(ModItems.DARK_SHARD, 2));
        String short_ = Cleanse.begin(player, chest, 3);
        gates &= short_.startsWith("You carry only 2") && !Cleanse.running();
        check("cleanse_gates", gates, early + " / " + short_);
        check(
                "cleanse_beaten_at_nine_in_ten",
                Cleanse.beatenAt(20) == 18 && Cleanse.beatenAt(10) == 9 && Cleanse.beatenAt(30) == 27,
                Cleanse.beatenAt(20));

        player.getInventory().add(new ItemStack(ModItems.DARK_SHARD, 1));
        String began = Cleanse.begin(player, chest, 3);
        var inside = Vec3.atCenterOf(chest).add(3, 0, 0);
        check(
                "cleanse_starts_and_lifts_shield",
                began.isEmpty()
                        && Cleanse.running()
                        && player.getInventory().countItem(ModItems.DARK_SHARD) == 0
                        && LightWards.suspended(level, chest)
                        && !LightWards.contains(level, inside)
                        && LightWards.covers(level, BlockPos.containing(inside))
                        && Cleanse.problem(level, chest, night) != null,
                began);

        Cleanse.tick(level, night, solo, now);
        var opening = hunters(box);
        check(
                "cleanse_wave_from_beyond_the_ward",
                !opening.isEmpty()
                        && opening.size() <= Balance.HUNT_PACK_MAX
                        && opening.stream()
                                .allMatch(m -> chest.equals(m.getHomePosition())
                                        && Monsters.info(m).rank() == MonsterInfo.NORMAL
                                        && m.getTarget() == player
                                        && !LightWards.covers(level, m.blockPosition())),
                opening.size());
        var drops = existing(box);
        long cleared = player.getAttachedOrElse(ModData.HUNTS_CLEARED, 0L);
        int[] killed = killAll(player, night, solo, box, now);
        var prizes = level.getEntitiesOfClass(ItemEntity.class, box, e -> !drops.contains(e.getUUID()));
        long gear = prizes.stream()
                .filter(e -> LootBeams.rarityOf(e) >= 0 && e.distanceToSqr(Vec3.atCenterOf(chest)) < 9)
                .count();
        check(
                "cleanse_one_wave_cleared_pays",
                killed[0] >= Cleanse.beatenAt(Cleanse.size(1, 3))
                        && killed[0] <= Cleanse.size(1, 3)
                        && killed[1] == 0
                        && gear == Balance.cleanseItems(3)
                        && !Cleanse.running()
                        && !LightWards.suspended(level, chest)
                        && LightWards.contains(level, inside)
                        && level.getAttached(ModData.CLEANSE) == null
                        && player.getAttachedOrElse(ModData.HUNTS_CLEARED, 0L) == cleared + 1,
                "killed=" + killed[0] + "/" + Cleanse.size(1, 3) + " bosses=" + killed[1] + " gear=" + gear
                        + " running=" + Cleanse.running() + " suspended=" + LightWards.suspended(level, chest)
                        + " inside=" + LightWards.contains(level, inside) + " saved="
                        + (level.getAttached(ModData.CLEANSE) != null) + " cleared="
                        + (player.getAttachedOrElse(ModData.HUNTS_CLEARED, 0L) - cleared));

        player.getInventory().add(new ItemStack(ModItems.DARK_SHARD, Balance.CLEANSE_MAX_SHARDS));
        Cleanse.begin(player, chest, Balance.CLEANSE_MAX_SHARDS);
        var before = existing(box);
        killed = killAll(player, night, solo, box, now);
        prizes = level.getEntitiesOfClass(ItemEntity.class, box, e -> !before.contains(e.getUUID()));
        gear = prizes.stream()
                .filter(e -> LootBeams.rarityOf(e) >= 0 && e.distanceToSqr(Vec3.atCenterOf(chest)) < 9)
                .count();
        long legendary = prizes.stream()
                .filter(e -> LootBeams.rarityOf(e) == LootRoller.Rarity.LEGENDARY.ordinal()
                        && e.distanceToSqr(Vec3.atCenterOf(chest)) < 9)
                .count();
        check(
                "cleanse_ten_shards_one_boss",
                killed[1] == 1
                        && killed[0] >= Cleanse.beatenAt(Cleanse.size(1, Balance.CLEANSE_MAX_SHARDS)) + 1
                        && killed[0] <= Cleanse.size(1, Balance.CLEANSE_MAX_SHARDS) + 1
                        && gear == Balance.cleanseItems(Balance.CLEANSE_MAX_SHARDS)
                        && legendary >= 1
                        && !Cleanse.running(),
                "killed=" + killed[0] + "/" + (Cleanse.size(1, Balance.CLEANSE_MAX_SHARDS) + 1) + " bosses=" + killed[1]
                        + " gear=" + gear + "/" + Balance.cleanseItems(Balance.CLEANSE_MAX_SHARDS) + " legendary="
                        + legendary + " running=" + Cleanse.running() + " " + Cleanse.debug());

        player.getInventory().add(new ItemStack(ModItems.DARK_SHARD, 1));
        Cleanse.begin(player, chest, 1);
        Cleanse.tick(level, night, solo, now);
        var first = hunters(box);
        var saved = level.getAttached(ModData.CLEANSE);
        Cleanse.clear();
        boolean retained = saved != null
                && saved.equals(level.getAttached(ModData.CLEANSE))
                && !Cleanse.running()
                && first.stream().allMatch(Mob::isRemoved);
        Cleanse.tick(level, night, solo, now + 1);
        check(
                "cleanse_resumes_after_restart",
                retained
                        && Cleanse.active(player)
                        && LightWards.suspended(level, chest)
                        && !hunters(box).isEmpty(),
                null);

        var alive = hunters(box);
        int sparks = player.getInventory().countItem(ModItems.SPARK);
        Cleanse.tick(level, moonAt(29, Balance.DAWN_TICK), solo, now + 2);
        check(
                "cleanse_dawn_ends_it",
                !alive.isEmpty()
                        && !Cleanse.running()
                        && level.getAttached(ModData.CLEANSE) == null
                        && !LightWards.suspended(level, chest)
                        && player.getInventory().countItem(ModItems.DARK_SHARD) == 0
                        && player.getInventory().countItem(ModItems.SPARK) == sparks + Balance.HUNT_SURVIVE_SPARKS
                        && alive.stream().noneMatch(m -> m.entityTags().contains(Hordes.HUNTER_TAG)),
                "alive=" + alive.size() + " running=" + Cleanse.running() + " saved="
                        + (level.getAttached(ModData.CLEANSE) != null) + " suspended="
                        + LightWards.suspended(level, chest) + " shards="
                        + player.getInventory().countItem(ModItems.DARK_SHARD) + " sparks+"
                        + (player.getInventory().countItem(ModItems.SPARK) - sparks) + "/"
                        + Balance.HUNT_SURVIVE_SPARKS);
        alive.forEach(Mob::discard);

        night = moonAt(37, Balance.DUSK_TICK);
        player.getInventory().add(new ItemStack(ModItems.DARK_SHARD, 1));
        Cleanse.begin(player, chest, 1);
        Cleanse.tick(level, night, solo, now);
        var defeat = hunters(box);
        Cleanse.onDeath(player); // FakePlayer refuses damage, so exercise the same death event directly.
        boolean paused = Cleanse.running();
        Cleanse.onDeath(player);
        check(
                "cleanse_lost_after_two_deaths",
                paused && !Cleanse.running() && !LightWards.suspended(level, chest),
                null);
        defeat.forEach(Mob::discard);
        check("cleanse_bystander_untouched", !Cleanse.active(bystander), null);
    }

    private static java.util.Set<UUID> existing(AABB box) {
        return level.getEntitiesOfClass(ItemEntity.class, box).stream()
                .map(Entity::getUUID)
                .collect(Collectors.toSet());
    }

    /** Kills every monster the Cleanse sends until it ends: {monsters killed, bosses among them}. */
    private static int[] killAll(
            FakePlayer killer, Hordes.MoonNight night, List<ServerPlayer> players, AABB box, long now) {
        int[] killed = new int[2];
        var seen = new java.util.HashSet<UUID>();
        for (int elapsed = 0; elapsed < 12_000 && Cleanse.running(); elapsed += Hordes.POLL_TICKS) {
            Cleanse.tick(level, night, players, now + elapsed);
            for (Mob m : hunters(box)) {
                if (seen.add(m.getUUID())) {
                    killed[0]++;
                    if (Lightborn.isBoss(m)) killed[1]++;
                }
                // The world does not tick here, so hit immunity never wears off: without this a Syrith boss, who
                // revives once, survives the second blow and the Cleanse never ends (a flaky failure, 2026-11-17).
                m.damageCooldownTime = 0;
                m.hurtServer(level, level.damageSources().playerAttack(killer), Float.MAX_VALUE);
            }
        }
        return killed;
    }

    private static Hordes.MoonNight moonAt(int day, int tick) {
        var clock = level.registryAccess().getOrThrow(WorldClocks.OVERWORLD);
        level.clockManager().setTotalTicks(clock, day * Hordes.DAY_TICKS + tick);
        return Hordes.MoonNight.read(level);
    }

    private static List<Mob> hunters(AABB box) {
        return level.getEntitiesOfClass(
                Mob.class, box, m -> m.isAlive() && m.entityTags().contains(Hordes.HUNTER_TAG));
    }
}
