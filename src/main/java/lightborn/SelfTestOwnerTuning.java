package lightborn;

import java.util.ArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.LevelSettings;

/** Owner tuning regressions through the existing self-test entry point. */
final class SelfTestOwnerTuning {
    static void run(ServerLevel level, ServerPlayer player) {
        SelfTestLightUpdate.run(level, player.blockPosition().above(8));
        SelfTestExpansion.run(level, player.blockPosition().above(12));
        var server = level.getServer();
        var settings = new LevelSettings.DifficultySettings(Difficulty.HARD, true, false);
        SelfTest.check(
                "difficulty_creation_normal_locked",
                settings.difficulty() == Difficulty.NORMAL && !settings.hardcore() && settings.locked(),
                settings);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.setDifficultyLocked(false);
        SelfTest.check(
                "difficulty_runtime_stays_normal",
                level.getDifficulty() == Difficulty.NORMAL
                        && server.getWorldData().isDifficultyLocked(),
                level.getDifficulty());
        ItemStack staff = LootRoller.starterStaff(level.getRandom());
        SelfTest.check(
                "starter_plain_no_buffs",
                staff.is(ModItems.STARTER_STAFF)
                        && ModItems.isStaff(staff)
                        && staff.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                                .modifiers()
                                .isEmpty()
                        && !staff.has(DataComponents.CUSTOM_NAME)
                        && !staff.has(DataComponents.CUSTOM_DATA),
                staff);
        // Tomes last forever (owner, 2026-11-16): no durability, so nothing can wear or break them.
        ItemStack tome = new ItemStack(ModItems.TOMES.get(Spell.ARCANE_BOLT));
        SelfTest.check(
                "tome_has_no_durability",
                !tome.isDamageableItem() && !tome.has(DataComponents.MAX_DAMAGE) && tome.getMaxStackSize() == 1,
                tome);
        var category = Collection.categoryOf("minecraft:dirt");
        SelfTest.check(
                "collection_quarter_actual_reward",
                Collection.rewardAmount(category, 1).getString().equals("+0.5 hearts")
                        && Collection.rewardAmount(category, 2).getString().equals("+1 heart")
                        && Collection.rewardAmount(category, 3).getString().equals("+1.5 hearts"),
                Collection.rewardAmount(category, 1).getString());
        boolean naturalist = FabricLoader.getInstance().isModLoaded("naturalist");
        boolean watching = FabricLoader.getInstance().isModLoaded("watching");
        SelfTest.check("animal_and_stalker_mods_loaded", naturalist && watching, naturalist + "/" + watching);
        var missing = new ArrayList<String>();
        int animals = 0;
        for (var type : BuiltInRegistries.ENTITY_TYPE)
            if (BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace().equals("naturalist")
                    && type.getCategory() != MobCategory.MISC) animals++;
        for (var item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            if (id.getNamespace().equals("naturalist")
                    && !(item instanceof SpawnEggItem)
                    && Collection.categoryOf(id.toString()) == null
                    && !CuratedCollection.RETIRED.contains(Collection.companionItemGroup(new ItemStack(item))))
                missing.add(id.toString());
        }
        SelfTest.check("naturalist_wildlife_roster", animals >= 20, animals);
        Lightborn.LOG.info("NATURALIST CATALOGUE missing {}: {}", missing.size(), missing);
        SelfTest.check("animal_items_in_collection", missing.isEmpty(), missing);
        var options = server.getCommandStorage()
                .get(Identifier.fromNamespaceAndPath("lunareclipse.watching", "config_options"))
                .getCompoundOrEmpty("options");
        SelfTest.check(
                "stalker_no_initiated_attacks",
                options.getString("sneaky_strike").orElse("").equals("false")
                        && options.getString("give_him_control").orElse("").equals("false"),
                options.getString("sneaky_strike"));
        SelfTest.check(
                "stalker_preserves_home_and_sleep",
                options.getString("burning_base").orElse("").equals("false")
                        && options.getString("disappearing_torches").orElse("").equals("false")
                        && options.getString("no_sleep").orElse("").equals("false"),
                null);
        var prop = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
        prop.addTag("watching.herobrine");
        prop.setPos(player.position().add(8, 0, 0));
        Monsters.onLoad(prop, level);
        SelfTest.check(
                "stalker_props_not_rpg_monsters",
                Monsters.info(prop) == null && !Monsters.isMonster(prop) && !Spells.canHitDirect(player, prop),
                null);
    }
}
