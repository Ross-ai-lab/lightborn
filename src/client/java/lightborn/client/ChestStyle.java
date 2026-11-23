package lightborn.client;

/** The Chest of Light's shared sizes and colours ({@link CollectionScreen} and its tabs). */
final class ChestStyle {
    private ChestStyle() {}

    // The tab column, 40% slimmer (owner, 2026-11-17: it was mostly empty).
    static final int TAB_W = 90, CELL = 18, HEAD = 28, DETAIL = 41, HELP = 14;
    static final int GOLD = 0xFFFFD86B,
            IVORY = 0xFFFFF4D6,
            DIM = 0xFF8A8270,
            SLOT = 0xFF2A2418,
            SLOT_IN = 0xFF17130C,
            GOLD_TEXT = 0xFFFFC94A;
    static final int SHOP_BLUE = 0xFF69B8FF, QUEST_RED = 0xFFFF6565, TRAVEL_GREEN = 0xFF72DC83;
    static final int SPIRIT_PURPLE = 0xFFC58BFF, CLEANSE_VIOLET = 0xFFB07CFF, LIGHT_GOLD = 0xFFFFE9A8;
    static final int BTN_W = 52, BTN_H = 16, RECIPE_W = 130, RECIPE_CELL = 13, ITEM_BTN_H = 12;

    /** The tabs' screen codes (kept from the one-file screen; the tests select tabs by them). */
    static final int SHOP = -2, CLEANSE = -3, TRAVEL = -4, UPGRADE_SPELLS = -5, SPIRITS = -6, UPGRADE_GEAR = -7;

    static String sparks(int n) {
        return n + (n == 1 ? " Spark" : " Sparks");
    }
}
