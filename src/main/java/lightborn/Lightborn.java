package lightborn;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.fabric.api.util.EventResult;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Lightborn implements ModInitializer {
    public static final String MOD_ID = "lightborn";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Each rules pack overrides one companion mod's world generation, so it loads only beside that mod (a run
        // without the companions, tools\build.ps1 -Lean, would otherwise fail on the unbound structures).
        for (String[] pack : new String[][] {{"fog_rules", "watching"}, {"tavern_rules", "mr_dungeons_andtaverns"}}) {
            if (!FabricLoader.getInstance().isModLoaded(pack[1])) continue;
            ResourceLoader.registerBuiltinPack(
                    Identifier.fromNamespaceAndPath("lightborn", pack[0]),
                    FabricLoader.getInstance().getModContainer("lightborn").orElseThrow(),
                    PackActivationType.ALWAYS_ENABLED);
        }
        // Nothing hurts a caged monster (owner, 2026-11-18).
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register(
                (entity, source, amount) -> !Cage.harmless(entity, source) && Taunt.allow(entity, source));
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, source, dealt, taken, blocked) -> Taunt.struck(entity, source));
        ModAttributes.init();
        ModData.init();
        ChestTabs.init();
        SurfaceLights.init();
        WardCombat.init();
        NightSpawns.init();
        RadiantPulse.init();
        ChestGlow.init();
        ModItems.init();
        ModEntities.init();
        DarkRealm.initWorld();
        Hollow.initWorld();
        SpellSchools.init();
        Workbench.init();
        NightSounds.init();
        DarkPhantom.init();
        LightDragon.init();
        DragonForm.init();
        StoryBosses.init();
        StoryGlow.init();
        Hordes.init();
        Cleanse.init();
        Net.init();
        ChestTravel.init();
        OreTiers.init();
        LightGear.initNet();
        Lighthouse.init();
        GameMusic.init();
        Lore.init();
        ChestLootRoll.init();
        TomeItem.initLoot();
        // Owner rule: more zombies. Wherever zombies already spawn, a second entry of weight 190 (vanilla's is 95) in
        // packs of 4, so about 40% of monster spawns there are zombies instead of about 18%.
        BiomeModifications.addSpawn(
                BiomeSelectors.spawnsOneOf(EntityTypes.ZOMBIE), MobCategory.MONSTER, EntityTypes.ZOMBIE, 95, 4, 4);
        UseBlockCallback.EVENT.register(LightChestBlock::onUse);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            // A blow is loud: the Dark Phantoms hear it.
            if (player instanceof ServerPlayer fighter) DarkPhantom.heard(fighter, 2f);
            if (!(entity instanceof LightDragon dragon) || dragon.role() == 1 || player.isSpectator())
                return InteractionResult.PASS;
            if (player.distanceToSqr(dragon) <= 36) {
                if (player instanceof ServerPlayer) dragon.refract();
                else dragon.showRefraction();
            }
            return InteractionResult.SUCCESS;
        });
        EntitySleepEvents.ALLOW_SLEEPING.register(Cleanse::sleepProblem);
        EntitySleepEvents.ALLOW_NEARBY_MONSTERS.register((player, pos, vanilla) -> EventResult.ALLOW);
        // Waking from a bed (owner, 2026-11-17): a bed never moves where you wake after a fall.
        EntitySleepEvents.STOP_SLEEPING.register((entity, pos) -> {
            if (entity instanceof net.minecraft.server.level.ServerPlayer p)
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "Your spawn is always tied to the Light chest. A bed never changes it.")
                        .withStyle(net.minecraft.ChatFormatting.GOLD));
        });
        ServerChunkEvents.CHUNK_LOAD.register(LightWards::onChunkLoad);
        ServerChunkEvents.CHUNK_LOAD.register((l, c, generated) -> SurfaceLights.loaded(l, c));
        ServerChunkEvents.CHUNK_UNLOAD.register(SurfaceLights::unloaded);
        ServerChunkEvents.CHUNK_LOAD.register(CollectionLight::onChunkLoad);
        ServerChunkEvents.CHUNK_LOAD.register((l, c, generated) -> StoryGlow.loaded(l, c));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            server.setDifficulty(Difficulty.NORMAL, true);
            server.setDifficultyLocked(true);
            // From The Fog's watcher shows himself only at night (owner, 2026-11-16), older worlds included: its
            // option storage is plain command storage, so setting it here wins over a world's saved choice.
            if (FabricLoader.getInstance().isModLoaded("watching"))
                server.getCommands()
                        .performPrefixedCommand(
                                server.createCommandSourceStack().withSuppressedOutput(),
                                "data modify storage lunareclipse.watching:config_options options.sighting_window set"
                                        + " value \"night\"");
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Warden && DragonStory.sleepingWarden(level, entity.blockPosition())) {
                entity.discard();
                return;
            }
            if (entity instanceof Display.TextDisplay && entity.entityTags().contains(DamageNumbers.TAG)) {
                if (!DamageNumbers.isLive(entity)) entity.discard();
                return;
            }
            if (entity instanceof Display.BlockDisplay
                    && (entity.entityTags().contains(FireSpells.ROCK_TAG)
                            || entity.entityTags().contains(WorkSpells.ORE_TAG))) {
                if (!FireSpells.isLiveDisplay(entity)) entity.discard();
                return;
            }
            Cage.onLoad(entity);
            if (SpellSchools.onLoad(entity)) return;
            if (Bosses.onAddLoad(entity)) return;
            Cleanse.onLoad(entity);
            LootBeams.onLoad(entity);
            Monsters.onLoad(entity, level);
        });

        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof ItemEntity item && item.getY() < level.getMinY() - 16) LightGear.destroyed(item);
            if (entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow)
                LightGearPerks.shot(arrow, level);
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (player instanceof ServerPlayer p) LightGearPerks.broke(p, pos, state);
        });
        net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY_DROPS.register(
                (table, context, drops) -> LightGearPerks.smelt(context, drops));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            Monsters.afterDamage(entity, source, taken);
            ArcaneSpells.afterDamage(entity, source, base, blocked);
            Perks.afterDamage(entity, source, taken);
            LightGearPerks.afterDamage(entity, source, taken);
            if (blocked) LightGearPerks.blocked(entity, source);
            BossTrophies.afterHit(entity, source, taken);
            if (source.getEntity() instanceof ServerPlayer striker
                    && entity != striker
                    && BossTrophies.concealed(striker)) BossTrophies.reveal(striker);
            LightGear.hit(entity, source, taken);
            if (entity instanceof ServerPlayer player && taken > 0) ChestTravel.damaged(player);
            if (taken > 0
                    && source.getEntity() instanceof ServerPlayer player
                    && entity != player
                    && entity.level() instanceof ServerLevel level) {
                double leech = player.getAttributeValue(ModAttributes.LIFE_LEECH);
                if (leech > 0) player.heal((float) (taken * leech));
                DamageNumbers.spawn(level, entity, taken, Spells.critHit || Crits.landed(entity));
            }
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register(Bosses::allowDeath);
        ServerLivingEntityEvents.ALLOW_DEATH.register(LightGearPerks::allowDeath);
        ServerLivingEntityEvents.AFTER_DEATH.register(Lightborn::onDeath);
        // Dying in a nest fight resets the fight (owner, 2026-11-17).
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p) DarkNests.playerDied(p);
            else if (entity instanceof net.minecraft.world.entity.Mob m) DarkNests.guardDied(m, source);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Scheduler.tick();
            Frostbite.tick();
            LightButterfly.tick(server);
            Spells.tickProjectiles();
            WorkSpells.tickGlows();
            SpellSchools.tick(server);
            Hordes.tick(server);
            DarkNests.tick(server);
            DarkPhantom.tick(server);
            NightSounds.tick(server);
            Cage.tick(server);
            Freeze.tick(server);
            Taunt.tick(server);
            RainGrowth.tick(server.overworld());
            DamageNumbers.tick();
            Progression.tick(server);
            // Rolled gear named under the old ladder ("Magic ...") takes its new name in a player's hands and pack.
            if (server.getTickCount() % 100 == 0)
                for (var p : server.getPlayerList().getPlayers()) {
                    for (var stack : p.getInventory()) LootRoller.relabel(stack);
                    for (var stack : p.getEnderChestInventory()) LootRoller.relabel(stack);
                }
            ChestTabs.tick(server);
            NightHearing.tick(server);
            SteadyFood.tick(server);
            Command.tick(server);
            CollectionLight.tick(server);
            LightDragons.tick(server);
            LightWards.tick(server);
            for (var level : server.getAllLevels()) SurfaceLights.tick(level);
            DragonTalk.tick(server);
            DragonStory.tick(server);
            ChestTravel.tick(server);
            HomeMove.tick(server);
            LongNights.tick(server);
            Refugee.tick(server);
            GameMusic.tick(server);
            Bosses.tick(server);
            LootBeams.tick();
            Wilds.tick(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            DragonForm.clear(server);
            StoryBosses.clear();
            StoryGlow.clear();
            ChestTravel.clear();
            HomeMove.clear();
            Refugee.clear();
            Cleanse.clear();
            ChestTabs.clear();
            CollectionLight.clear();
            LightDragons.clear();
            LightWards.clear();
            Bosses.clear();
            DamageNumbers.clear();
            FireSpells.clearDisplays();
            SpellSchools.clear();
            WeaverSpider.clearWebs();
            Collection.clearRecipes();
            Sources.clear();
            Scheduler.clear();
            LootBeams.clear();
            Wilds.clear();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlayerBonuses.apply(handler.player);
            clearExperience(handler.player);
            SpawnHut.onJoin(handler.player);
            retireHomeBuffs(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DragonForm.end(handler.player);
            ChestTravel.forget(handler.player);
            TomeCycle.forget(handler.player);
            CollectionLight.release(handler.player);
            Cleanse.onDisconnect(handler.player);
            StaffFlight.onDisconnect(handler.player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            PlayerBonuses.apply(newPlayer);
            clearExperience(newPlayer);
            newPlayer.setHealth(newPlayer.getMaxHealth());
            Progression.setMana(newPlayer, (float) Progression.maxMana(newPlayer));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
                dispatcher.register(Commands.literal(MOD_ID)
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("roll")
                                .executes(ctx -> give(ctx.getSource(), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 36))
                                        .executes(ctx ->
                                                give(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
                        .then(Commands.literal("boss").executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            return Bosses.summonNear(p.level(), p) ? 1 : 0;
                        }))));

        if (Boolean.getBoolean("lightborn.selftest")) {
            ServerLifecycleEvents.SERVER_STARTED.register(SelfTest::start);
        }
        LOG.info("Lightborn loaded: {} items", ModItems.MOD_ITEMS.size());
    }

    /** Who gets credit for a kill: the direct killer, the master of the thrall that killed it, or whoever hit it last. */
    static ServerPlayer killer(LivingEntity dead, DamageSource source) {
        if (source.getEntity() instanceof ServerPlayer p) return p;
        ServerPlayer master = SummonSpells.thrallOwner(source.getEntity());
        if (master != null) return master;
        Player last = dead.getLastHurtByPlayer();
        return last instanceof ServerPlayer p ? p : null;
    }

    public static void onPlayerDeath(ServerPlayer player, DamageSource source) {
        if (Boolean.TRUE.equals(player.getAttached(ModData.PLAYER_DEATH_SETTLED))) return;
        player.setAttached(ModData.PLAYER_DEATH_SETTLED, true);
        DragonForm.end(player);
        ChestTravel.forget(player);
        Progression.setMana(player, (float) Progression.maxMana(player));
        CollectionLight.release(player);
        dropSparks(player);
        Bosses.rememberDeath(player, source);
        Cleanse.onDeath(player);
        HexSpells.onDeath(player);
    }

    private static void onDeath(LivingEntity dead, DamageSource source) {
        DragonStory.killed(dead, source);
        if (StoryBosses.died(dead, source)) return;
        DragonTalk.killed(dead);
        if (dead instanceof ServerPlayer || !(dead.level() instanceof ServerLevel level)) return;
        if (Monsters.isAtmosphericProp(dead)) return;
        Perks.died(dead, source);
        Command.died(dead);
        Cleanse.onDeath(dead);
        HexSpells.onDeath(dead);
        if (dead.entityTags().contains(Bosses.ADD_TAG)) return;
        if (Souls.summoned(dead) || dead.getAttached(ModData.THRALL_MASTER) != null) return;
        MonsterInfo info = Monsters.info(dead);
        ServerPlayer killer = killer(dead, source);
        if (killer != null) Souls.killed(killer, dead);
        if (info != null && info.rank() == MonsterInfo.BOSS) {
            if (dead instanceof Mob mob) Bosses.untrack(mob);
            dead.spawnAtLocation(level, new ItemStack(ModItems.DARK_SHARD, Balance.BOSS_SHARDS));
            var identity = Bosses.identity(dead);
            dead.spawnAtLocation(
                    level,
                    new ItemStack(
                            ModItems.SPARK, Balance.BOSS_SPARKS + (identity != null ? Balance.NAMED_BOSS_SPARKS : 0)));
            if (identity != null) {
                if (killer != null) BossTrophies.awardKill(killer, dead, identity, level);
                else if (identity.revenge().isPresent()) BossTrophies.dropTrophy(dead, identity.kind(), level, null);
            }
        }
        if (killer == null || info == null) return;
        if (dead.entityTags().contains(Hordes.SCOUT_TAG)
                && level.getRandom().nextDouble() < Balance.SCOUT_SHARD_CHANCE) {
            dead.spawnAtLocation(level, new ItemStack(ModItems.DARK_SHARD));
        }
        // Every monster pays Sparks by its tier (owner, 2026-11-20); a spawner's never do.
        if (!Monsters.fromSpawner(dead)) dead.spawnAtLocation(level, new ItemStack(ModItems.SPARK, killSparks(dead)));
        double onKill = killer.getAttributeValue(ModAttributes.LIFE_ON_KILL);
        if (onKill > 0) killer.heal((float) onKill);
        LightGearPerks.onKill(killer);

        dropLoot(level, dead, info, killer);
        BossTrophies.onMonsterKill(killer, dead, level);
        DragonFavours.onMonsterKill(killer, dead);
    }

    static List<ItemStack> dropLoot(ServerLevel level, LivingEntity dead, MonsterInfo info, ServerPlayer killer) {
        if (Monsters.fromSpawner(dead)) return List.of();
        var random = level.getRandom();
        double find = killer.getAttributeValue(ModAttributes.ITEM_FIND);
        double rarity = killer.getAttributeValue(ModAttributes.ITEM_RARITY);
        int count = 0;
        LootRoller.Rarity floor = null;
        boolean giant = Giants.is(dead);
        if (info.rank() == MonsterInfo.BOSS) {
            count = Balance.BOSS_DROPS;
            floor = LootRoller.Rarity.RARE;
        } else if (giant || monsterTier(dead) >= 3) {
            count = 1 + (random.nextDouble() < Balance.GIANT_SECOND_DROP * find ? 1 : 0);
        } else {
            count = random.nextDouble() < Balance.DROP_CHANCE * find ? 1 : 0;
        }
        List<ItemStack> dropped = new ArrayList<>();
        int strength = info.rank() == MonsterInfo.BOSS ? OreTiers.BOSS : monsterTier(dead);
        for (int i = 0; i < count; i++) {
            ModItems.Tier tier = OreTiers.pick(random, killer, strength);
            // A strong kind's gear is certain, and a boss's Relic: before any ore is mined it is the first tier.
            boolean bossRelic = info.rank() == MonsterInfo.BOSS && i == 0;
            if (tier == null && (monsterTier(dead) >= 3 || bossRelic)) tier = ModItems.Tier.values()[0];
            if (tier == null) continue; // no ore mined yet that this monster could carry
            LootRoller.Rarity r = LootRoller.rollRarity(random, rarity);
            if (floor != null && r == LootRoller.Rarity.UNCOMMON) r = floor;
            if (bossRelic) r = LootRoller.Rarity.LEGENDARY;
            ItemStack stack = LootRoller.roll(random, tier, r);
            // Every boss drops at least one tier 5 piece, a Relic (owner, 2026-11-20: "bosses always drop cool gear").
            if (bossRelic) LightGearTree.relic(stack, random);
            LootBeams.onDrop(dead.spawnAtLocation(level, stack), r);
            dropped.add(stack);
            if (r.top()) {
                killer.sendSystemMessage(Component.literal("✦ " + r.label + " drop: ")
                        .withStyle(r.color)
                        .append(stack.getOrDefault(DataComponents.CUSTOM_NAME, stack.getHoverName())));
            }
        }
        // Spell tomes: rare from common monsters, likelier from a giant zombie, always one from a boss.
        double tomeChance = info.rank() == MonsterInfo.BOSS
                ? 1.0
                : giant ? Balance.TOME_FROM_GIANT : Balance.TOME_DROP_CHANCE * find;
        if (random.nextDouble() < tomeChance) {
            // Only spells up to the monster's own tier (owner, 2026-11-18); a boss's is always a tier 5 spell
            // (2026-11-20).
            ItemStack tome = info.rank() == MonsterInfo.BOSS
                    ? TomeItem.rollTier(random, 5)
                    : TomeItem.roll(random, Souls.tierOf(dead));
            if (!tome.isEmpty()) dead.spawnAtLocation(level, tome);
        }
        // A Relic now and then from a tier 5 monster (owner, 2026-11-20): Legendary, all three upgrade tiers bought.
        var best = OreTiers.best(killer);
        if (info.rank() != MonsterInfo.BOSS
                && monsterTier(dead) == 5
                && best != null
                && random.nextDouble() < Balance.RELIC_CHANCE * find) {
            ItemStack relic = LootRoller.roll(random, best, LootRoller.Rarity.LEGENDARY);
            LightGearTree.relic(relic, random);
            LootBeams.onDrop(dead.spawnAtLocation(level, relic), LootRoller.Rarity.LEGENDARY);
            dropped.add(relic);
        }
        return dropped;
    }

    /** Test command: rolled gear of any material, ignoring the ore the player has mined. */
    private static int give(CommandSourceStack source, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var random = player.level().getRandom();
        for (int i = 0; i < count; i++) {
            ItemStack stack = LootRoller.roll(
                    random,
                    ModItems.Tier.values()[random.nextInt(ModItems.Tier.values().length)],
                    player.getAttributeValue(ModAttributes.ITEM_RARITY));
            if (!player.getInventory().add(stack)) player.spawnAtLocation(player.level(), stack);
        }
        return count;
    }

    /**
     * There is no experience at all (owner, 2026-11-16). A save from before still carries vanilla levels and points;
     * they are cleared so nothing reads them.
     */
    static void clearExperience(ServerPlayer player) {
        if (player.experienceLevel == 0 && player.totalExperience == 0 && player.experienceProgress == 0) return;
        player.experienceLevel = 0;
        player.totalExperience = 0;
        player.experienceProgress = 0;
    }

    /**
     * The Sparks a strong kind always pays, by its Book of Souls tier (owner, 2026-11-18): tier 3 two, tier 4 three,
     * tier 5 five; 0 for tiers 1 and 2.
     */
    /**
     * A monster's power tier, 1 to 5: its Book of Souls page's, a giant the book does not list counts 3, and any other
     * unlisted kind 1. Tier 3 and up (giants, mutants, the Drowned Captain) always drop gear (owner, 2026-11-17).
     */
    static int monsterTier(LivingEntity dead) {
        var page = Souls.page(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(dead.getType())
                .toString());
        return page == null ? (Giants.is(dead) ? 3 : 1) : Math.clamp(page.weight(), 1, 5);
    }

    /** The Sparks one kill pays ({@link Balance#KILL_SPARKS_MIN}). */
    static int killSparks(LivingEntity dead) {
        int t = monsterTier(dead);
        return Balance.KILL_SPARKS_MIN[t]
                + dead.getRandom().nextInt(Balance.KILL_SPARKS_MAX[t] - Balance.KILL_SPARKS_MIN[t] + 1);
    }

    /**
     * On death half the Sparks the player carries burn away for good (rounded up) and the rest drop where they fell,
     * kept on the ground until picked up (owner, 2026-11-16). Sparks stored in the Chest of Light are safe. Gear is
     * kept as before; without kept gear vanilla drops the rest of the inventory. Returns the Sparks dropped.
     */
    static int dropSparks(ServerPlayer player) {
        ServerLevel level = player.level();
        var inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.is(ModItems.SPARK)) continue;
            total += stack.getCount();
            inventory.setItem(i, ItemStack.EMPTY);
        }
        if (total == 0) return 0;
        int lost = (int) Math.ceil(total * Balance.DEATH_SPARK_LOSS), dropped = total - lost;
        for (int left = dropped; left > 0; ) {
            int n = Math.min(left, ModItems.SPARK.getDefaultMaxStackSize());
            ItemEntity drop = new ItemEntity(
                    level, player.getX(), player.getY() + 0.5, player.getZ(), new ItemStack(ModItems.SPARK, n));
            drop.setUnlimitedLifetime();
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
            left -= n;
        }
        level.sendParticles(
                ParticleTypes.SMALL_FLAME, player.getX(), player.getY() + 1, player.getZ(), 20, .4, .6, .4, .02);
        player.sendSystemMessage(Component.literal(lost + (lost == 1 ? " Spark" : " Sparks") + " burned away"
                        + (dropped > 0 ? "; " + dropped + " lie where you fell." : "."))
                .withStyle(ChatFormatting.GOLD));
        return dropped;
    }

    /** Mob helper used by tests and commands. */
    static boolean isBoss(Mob mob) {
        MonsterInfo info = Monsters.info(mob);
        return info != null && info.rank() == MonsterInfo.BOSS;
    }

    /** Rested and Well Fed were removed: older saves still carry their permanent attribute modifiers. */
    static void retireHomeBuffs(ServerPlayer player) {
        for (var entry : java.util.List.of(
                java.util.Map.entry(ModAttributes.MANA_REGEN, "rested_mana"),
                java.util.Map.entry(
                        net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, "well_fed_health"))) {
            var instance = player.getAttribute(entry.getKey());
            if (instance != null) instance.removeModifier(ModItems.id(entry.getValue()));
        }
        if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
    }
}
