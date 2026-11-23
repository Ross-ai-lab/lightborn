package lightborn;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;

/** Shared presentation for the Dragon's guide and the two fixed Keeper notes. */
public final class Lore {
    private Lore() {}

    public static final String AUTHOR = "the Dragon";

    static void init() {}

    public static void signByDragon(ItemStack book) {
        book.set(
                DataComponents.TOOLTIP_DISPLAY,
                TooltipDisplay.DEFAULT.withHidden(DataComponents.WRITTEN_BOOK_CONTENT, true));
        book.set(
                DataComponents.LORE,
                new ItemLore(List.of(Component.literal("by " + AUTHOR)
                        .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))));
    }

    static List<String> split(String text, int firstLimit) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int limit = firstLimit;
        for (String w : text.split(" ")) {
            if (cur.length() + w.length() + 1 > limit && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
                limit = 250;
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
