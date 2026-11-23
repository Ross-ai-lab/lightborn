package lightborn;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Fishing pays more (owner, 2026-11-22, when Angler's Call was deleted: "make fishing more rewarding"). Every real
 * catch on a rod may bring up something of Lightborn's besides the fish, thrown to the angler with it: Sparks, a sunken
 * chest's treasure, a piece of rolled gear, a tome, or a secret (a map to a place no one has found). Luck of the Sea
 * makes each likelier.
 */
public final class Fishing {
    private Fishing() {}

    /** Chances per catch, before luck: Sparks, treasure, gear, a tome, a secret map. */
    static final double SPARKS = .20, TREASURE = .05, GEAR = .03, TOME = .02, SECRET = .01;
    /** Each level of Luck of the Sea (and the luck effect) adds this share to every chance. */
    static final double LUCK_STEP = .25;

    /** A catch was reeled in at {@code at}: roll the extras and throw them to the angler. */
    public static void caught(ServerPlayer p, Vec3 at, int luck) {
        var level = p.level();
        var found = rewards(p, at, luck, level.getRandom(), true);
        if (found.isEmpty()) return;
        boolean rare = false;
        for (var reward : found) {
            var item = new ItemEntity(level, at.x, at.y, at.z, reward.stack());
            Vec3 d = p.position().subtract(at);
            item.setDeltaMovement(d.x * .1, d.y * .1 + Math.sqrt(d.length()) * .08, d.z * .1);
            level.addFreshEntity(item);
            if (reward.rarity() != null) LootBeams.onDrop(item, reward.rarity());
            rare |= reward.rare();
        }
        if (rare) {
            p.sendSystemMessage(
                    Component.literal("Something glints on the line").withStyle(ChatFormatting.GOLD), true);
            level.playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.4f);
        }
    }

    record Reward(ItemStack stack, LootRoller.Rarity rarity, boolean rare) {}

    /** What one catch brings up besides the fish (often nothing); {@code maps} false skips the map search. */
    static List<Reward> rewards(ServerPlayer p, Vec3 at, int luck, RandomSource random, boolean maps) {
        var level = p.level();
        double boost = 1 + LUCK_STEP * Math.max(0, luck + p.getLuck());
        var out = new ArrayList<Reward>();
        if (random.nextDouble() < SPARKS * boost)
            out.add(new Reward(new ItemStack(ModItems.SPARK, 1 + random.nextInt(3)), null, false));
        if (random.nextDouble() < TREASURE * boost) {
            var table = level.getServer()
                    .reloadableRegistries()
                    .getLootTable(
                            random.nextBoolean()
                                    ? BuiltInLootTables.SHIPWRECK_TREASURE
                                    : BuiltInLootTables.BURIED_TREASURE);
            var params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, at)
                    .withLuck((float) (luck + p.getLuck()))
                    .create(LootContextParamSets.CHEST);
            var loot = table.getRandomItems(params);
            // A whole chest is too much for one cast: two of its stacks.
            for (int i = 0; i < Math.min(2, loot.size()); i++) out.add(new Reward(loot.get(i), null, true));
        }
        if (random.nextDouble() < GEAR * boost) {
            var tier = OreTiers.pick(random, p, 2); // pays like a tier-2 monster's drop
            if (tier != null) {
                var rarity = LootRoller.rollRarity(random, 1);
                out.add(new Reward(LootRoller.roll(random, tier, rarity), rarity, true));
            }
        }
        if (random.nextDouble() < TOME * boost) out.add(new Reward(TomeItem.roll(random, 3), null, true));
        if (random.nextDouble() < SECRET * boost && maps) {
            var map = secret(p, random);
            if (map != null) out.add(new Reward(map, null, true));
        }
        return out;
    }

    /**
     * A map to a place no one has found yet, as the shop draws them, searched within 40 chunks (the shop searches 100)
     * so a lucky cast never stalls the game; null in other worlds or when none lies near.
     */
    private static ItemStack secret(ServerPlayer p, RandomSource random) {
        if (!(p.level() instanceof ServerLevel)
                || p.level() != p.level().getServer().overworld()) return null;
        var target = LightShop.MAPS.get(random.nextInt(LightShop.MAPS.size()));
        return LightShop.drawMap(p, p.blockPosition(), target, 40);
    }
}
