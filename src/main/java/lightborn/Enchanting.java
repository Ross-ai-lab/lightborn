package lightborn;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Resource prices shared by the native menus and their client presentation. */
public final class Enchanting {
    private Enchanting() {}

    public static int lapis(int slot) {
        return Balance.TABLE_LAPIS_PER_TIER * (slot + 1);
    }

    public static int count(Player player, Item item) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    /** Enchanting and the anvil are paid in lapis alone: Dark Shards are only for the Cleanse (owner, 2026-11-16). */
    public static boolean canPay(Player player, int lapis) {
        return player.hasInfiniteMaterials() || count(player, Items.LAPIS_LAZULI) >= lapis;
    }

    public static void consume(Player player, Item item, int amount) {
        if (player.hasInfiniteMaterials()) return;
        for (int i = 0; i < player.getInventory().getContainerSize() && amount > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(item)) continue;
            int used = Math.min(amount, stack.getCount());
            stack.shrink(used);
            amount -= used;
        }
        player.getInventory().setChanged();
    }
    /** This data slot travels with the vanilla anvil's result and cost. */
    public interface AnvilPrice {
        int lightborn$lapis();
    }
}
