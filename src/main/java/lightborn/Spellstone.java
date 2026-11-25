package lightborn;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * The Spellstone (owner, 2026-11-18; first called the Codex): a stone that holds up to five tomes. Held in the off hand
 * with a staff in the main hand, it casts all five: the first slot with the staff's right-click, the others with R, Q, V
 * and C (rebindable: the Dragon Form, Drop and Dragon Breath keys, which keep their own jobs whenever a staff and
 * Spellstone are not both held). It fills like a bundle: click a tome onto it, right-click it to take one out, and
 * scroll over it to pick the slot either one uses; hovering it shows its five slots. Used from the main hand it also
 * opens on its slots. Each tome inside keeps its upgrades, its awakening and its own cooldown; a cast writes it back.
 */
public final class Spellstone {
    private Spellstone() {}

    public static final int SLOTS = 5;

    /** Each slot's casting key, as its key-mapping name: right-click, R, Q and V by default. */
    public static final String[] KEYS = {
        "key.use",
        "key.lightborn.story_power",
        "key.drop",
        "key.lightborn.dragon_breath",
        "key.lightborn.spellstone_five"
    };

    public static Item ITEM;
    public static MenuType<Menu> MENU;

    /**
     * The slot each player picked by scrolling over a Spellstone: {menu slot it sits in, slot 0 to 4}. The client sets
     * it as it scrolls and tells the server, so a click reads the same pick on both sides.
     */
    private static final Map<UUID, int[]> PICKED = new ConcurrentHashMap<>();

    /** Client to server: cast the tome in slot {@code slot} (1 to 4); {@code charge} is Updraft's held ticks. */
    public record Cast(int slot, int charge) implements CustomPacketPayload {
        public static final Type<Cast> TYPE = new Type<>(ModItems.id("spellstone_cast"));
        static final StreamCodec<RegistryFriendlyByteBuf, Cast> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Cast::slot, ByteBufCodecs.VAR_INT, Cast::charge, Cast::new);

        @Override
        public Type<Cast> type() {
            return TYPE;
        }
    }

    /** Client to server: the slot picked over the Spellstone in menu slot {@code menuSlot}; -1 forgets the pick. */
    public record Pick(int menuSlot, int slot) implements CustomPacketPayload {
        public static final Type<Pick> TYPE = new Type<>(ModItems.id("spellstone_pick"));
        static final StreamCodec<RegistryFriendlyByteBuf, Pick> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Pick::menuSlot, ByteBufCodecs.VAR_INT, Pick::slot, Pick::new);

        @Override
        public Type<Pick> type() {
            return TYPE;
        }
    }

    /** What its tooltip draws: the tomes in its five slots (an empty slot is an empty stack). */
    public record Slots(List<ItemStack> tomes) implements TooltipComponent {}

    static void init() {
        ITEM = ModItems.register(
                "spellstone",
                SpellstoneItem::new,
                new Item.Properties()
                        .stacksTo(1)
                        .rarity(Rarity.RARE)
                        .component(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                        // Its own slot panel lists the tomes; the container's plain list would repeat them.
                        .component(
                                DataComponents.TOOLTIP_DISPLAY,
                                TooltipDisplay.DEFAULT.withHidden(DataComponents.CONTAINER, true)));
        MENU = Registry.register(
                BuiltInRegistries.MENU, ModItems.id("spellstone"), new MenuType<>(Menu::new, FeatureFlags.VANILLA_SET));
        PayloadTypeRegistry.serverboundPlay().register(Cast.TYPE, Cast.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Pick.TYPE, Pick.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Cast.TYPE, (c, ctx) -> cast(ctx.player(), c.slot(), c.charge()));
        ServerPlayNetworking.registerGlobalReceiver(Pick.TYPE, (c, ctx) -> pick(ctx.player(), c.menuSlot(), c.slot()));
    }

    public static boolean is(ItemStack s) {
        return ITEM != null && s.is(ITEM);
    }

    /** The five slots, copies (an empty slot is an empty stack). */
    public static NonNullList<ItemStack> tomes(ItemStack stone) {
        var list = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        stone.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .copyInto(list);
        return list;
    }

    public static void store(ItemStack stone, List<ItemStack> tomes) {
        stone.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(tomes));
    }

    /**
     * Where a tome is, for the chest's Upgrade Spells tab (owner, 2026-11-18: it must see the tomes in the
     * Spellstone): below this an inventory slot; from it, {@code IN_STONE + inventorySlot * SLOTS + stoneSlot}.
     */
    public static final int IN_STONE = 1000;

    public static int inStone(int inventorySlot, int stoneSlot) {
        return IN_STONE + inventorySlot * SLOTS + stoneSlot;
    }

    /** The tome at an address: the stack itself in the inventory, a copy when it sits in a Spellstone. */
    public static ItemStack tomeAt(Player p, int address) {
        var inv = p.getInventory();
        if (address < 0) return ItemStack.EMPTY;
        if (address < IN_STONE) return address < inv.getContainerSize() ? inv.getItem(address) : ItemStack.EMPTY;
        int slot = (address - IN_STONE) / SLOTS, index = (address - IN_STONE) % SLOTS;
        if (slot >= inv.getContainerSize() || !is(inv.getItem(slot))) return ItemStack.EMPTY;
        return tomes(inv.getItem(slot)).get(index);
    }

    /** Writes a changed tome back to its address (needed only inside a Spellstone, which holds copies). */
    static void putTomeAt(Player p, int address, ItemStack tome) {
        if (address < IN_STONE) return;
        var inv = p.getInventory();
        int slot = (address - IN_STONE) / SLOTS, index = (address - IN_STONE) % SLOTS;
        if (slot >= inv.getContainerSize() || !is(inv.getItem(slot))) return;
        var stone = inv.getItem(slot);
        var list = tomes(stone);
        if (TomeItem.spellOf(list.get(index)) != TomeItem.spellOf(tome)) return;
        list.set(index, tome);
        store(stone, list);
    }

    /** Whether this player casts from a Spellstone: a staff in the main hand, a Spellstone in the off hand. */
    public static boolean casting(Player p) {
        return p.getMainHandItem().getItem() instanceof StaffItem && is(p.getOffhandItem());
    }

    /** The tome a staff's right-click casts: the off-hand tome, or the Spellstone's first slot. */
    public static ItemStack rightClick(Player p) {
        var off = p.getOffhandItem();
        return is(off) ? tomes(off).get(0) : off;
    }

    /**
     * The tome of this spell the player casts from: the off-hand tome itself, or a copy of the Spellstone slot holding
     * it. Read-only; change it through {@link #update}.
     */
    public static ItemStack armed(Player p, Spell spell) {
        var off = p.getOffhandItem();
        if (TomeItem.spellOf(off) == spell) return off;
        if (is(off)) for (var t : tomes(off)) if (TomeItem.spellOf(t) == spell) return t;
        return ItemStack.EMPTY;
    }

    /** Changes the armed tome of this spell where it lives: the off hand, or its Spellstone slot. */
    static void update(Player p, Spell spell, Consumer<ItemStack> change) {
        var off = p.getOffhandItem();
        if (TomeItem.spellOf(off) == spell) {
            change.accept(off);
            return;
        }
        if (!is(off)) return;
        var list = tomes(off);
        for (int i = 0; i < SLOTS; i++)
            if (TomeItem.spellOf(list.get(i)) == spell) {
                change.accept(list.get(i));
                store(off, list);
                return;
            }
    }

    /** Writes a slot's tome back after a cast (a cast may have changed it). */
    static void writeBack(Player p, int slot, ItemStack tome) {
        var off = p.getOffhandItem();
        if (!is(off)) return;
        var list = tomes(off);
        if (TomeItem.spellOf(list.get(slot)) != TomeItem.spellOf(tome)) return;
        list.set(slot, tome);
        store(off, list);
    }

    /** A key cast of slots 1 to 4 (slot 0 is the staff's right-click, {@link StaffItem}). */
    static void cast(ServerPlayer p, int slot, int charge) {
        if (slot < 1 || slot >= SLOTS || !casting(p) || !p.isAlive() || p.isSpectator() || ChestTravel.flying(p))
            return;
        var tome = tomes(p.getOffhandItem()).get(slot);
        Spell spell = TomeItem.spellOf(tome);
        if (spell == null || p.getCooldowns().isOnCooldown(tome)) return;
        if (spell == Spell.UPDRAFT)
            TravelSpells.updraftCharge = Math.clamp(charge / (float) StaffItem.UPDRAFT_CHARGE_TICKS, .15f, 1f);
        try {
            if (Spells.cast(p, tome)) {
                writeBack(p, slot, tome);
            }
        } finally {
            TravelSpells.updraftCharge = 1f;
        }
    }

    // ---- The bag ----

    /** Remembers (or with {@code slot} -1 forgets) the slot picked over the Spellstone in this menu slot. */
    public static void pick(Player p, int menuSlot, int slot) {
        if (slot < 0 || slot >= SLOTS) PICKED.remove(p.getUUID());
        else PICKED.put(p.getUUID(), new int[] {menuSlot, slot});
    }

    /** The slot picked over the Spellstone in this menu slot, or -1. */
    public static int picked(Player p, Slot menuSlot) {
        int[] v = PICKED.get(p.getUUID());
        return v != null && v[0] == menuSlot.index ? v[1] : -1;
    }

    /**
     * Puts one tome in: into the picked slot when one is picked (handing back the tome it held, if any), else into the
     * first empty slot. Returns what comes back out (EMPTY for nothing), or null when there is no room.
     */
    public static ItemStack insert(List<ItemStack> slots, ItemStack tome, int picked) {
        if (picked >= 0 && picked < SLOTS) {
            var old = slots.get(picked);
            slots.set(picked, tome);
            return old;
        }
        for (int i = 0; i < SLOTS; i++)
            if (slots.get(i).isEmpty()) {
                slots.set(i, tome);
                return ItemStack.EMPTY;
            }
        return null;
    }

    /** Takes one tome out: the picked slot's, else the last filled slot's. EMPTY when there is none. */
    public static ItemStack takeOut(List<ItemStack> slots, int picked) {
        int i = picked;
        if (i < 0 || i >= SLOTS) for (i = SLOTS - 1; i >= 0 && slots.get(i).isEmpty(); i--) {}
        if (i < 0 || slots.get(i).isEmpty()) return ItemStack.EMPTY;
        var out = slots.get(i);
        slots.set(i, ItemStack.EMPTY);
        return out;
    }

    private static void sound(Player p, SoundEvent sound) {
        p.playSound(sound, .8f, .8f + p.level().getRandom().nextFloat() * .4f);
    }

    /** Lets an open menu see the changed stone at once, as the bundle does. */
    private static void changed(Player p) {
        AbstractContainerMenu menu = p.containerMenu;
        if (menu != null) menu.slotsChanged(p.getInventory());
    }

    /** The Spellstone item: a bag for five tomes; opens on its slots from the main hand. */
    public static final class SpellstoneItem extends Item {
        public SpellstoneItem(Properties props) {
            super(props);
        }

        /** A tome on the cursor clicked onto the stone goes in; right-click with an empty cursor takes one out. */
        @Override
        public boolean overrideOtherStackedOnMe(
                ItemStack stone, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess carried) {
            if (action == ClickAction.PRIMARY && other.isEmpty()) {
                pick(player, slot.index, -1); // picked up as usual
                return false;
            }
            if (action == ClickAction.PRIMARY && TomeItem.spellOf(other) != null) {
                var slots = tomes(stone);
                var back = slot.allowModification(player)
                        ? insert(slots, other.copyWithCount(1), picked(player, slot))
                        : null;
                if (back == null) {
                    sound(player, SoundEvents.BUNDLE_INSERT_FAIL);
                    return true;
                }
                other.shrink(1); // tomes stack to one, so the cursor is now free for a swapped-out tome
                store(stone, slots);
                if (!back.isEmpty()) carried.set(back);
                sound(player, SoundEvents.AMETHYST_BLOCK_CHIME);
                changed(player);
                return true;
            }
            if (action == ClickAction.SECONDARY && other.isEmpty() && slot.allowModification(player)) {
                var slots = tomes(stone);
                var out = takeOut(slots, picked(player, slot));
                if (out.isEmpty()) return false; // an empty stone is picked up as usual
                store(stone, slots);
                carried.set(out);
                sound(player, SoundEvents.AMETHYST_BLOCK_HIT);
                changed(player);
                return true;
            }
            return false; // anything that isn't a tome swaps with it as usual
        }

        /** The stone on the cursor: click a tome to pull it in, right-click an empty slot to set a tome down there. */
        @Override
        public boolean overrideStackedOnOther(ItemStack stone, Slot slot, ClickAction action, Player player) {
            var there = slot.getItem();
            if (action == ClickAction.PRIMARY && TomeItem.spellOf(there) != null) {
                var slots = tomes(stone);
                int free = -1;
                for (int i = 0; i < SLOTS && free < 0; i++) if (slots.get(i).isEmpty()) free = i;
                if (free < 0) {
                    sound(player, SoundEvents.BUNDLE_INSERT_FAIL);
                    return true;
                }
                var taken = slot.safeTake(1, 1, player);
                if (taken.isEmpty()) return true;
                slots.set(free, taken);
                store(stone, slots);
                sound(player, SoundEvents.AMETHYST_BLOCK_CHIME);
                changed(player);
                return true;
            }
            if (action == ClickAction.SECONDARY && there.isEmpty()) {
                var slots = tomes(stone);
                var out = takeOut(slots, -1);
                if (out.isEmpty()) return false; // an empty stone is set down as usual
                if (!slot.mayPlace(out)) return true;
                slot.safeInsert(out);
                store(stone, slots);
                sound(player, SoundEvents.AMETHYST_BLOCK_HIT);
                changed(player);
                return true;
            }
            return false;
        }

        @Override
        public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
            return Optional.of(new Slots(List.copyOf(tomes(stack))));
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS; // the off hand casts, via the staff
            var stone = player.getItemInHand(hand);
            if (player instanceof ServerPlayer sp) {
                int locked = sp.getInventory().getSelectedSlot();
                sp.openMenu(new SimpleMenuProvider(
                        (id, inv, pl) -> new Menu(id, inv, stone, locked), stone.getHoverName()));
            }
            return InteractionResult.SUCCESS;
        }

        @Override
        public void appendHoverText(
                ItemStack stack,
                TooltipContext context,
                TooltipDisplay display,
                Consumer<Component> out,
                TooltipFlag flag) {
            out.accept(
                    Component.literal("Off hand, with a staff: casts all five").withStyle(ChatFormatting.LIGHT_PURPLE));
            out.accept(Component.literal("Click tomes onto it; right-click takes one out")
                    .withStyle(ChatFormatting.DARK_GRAY));
            out.accept(Component.literal("Scroll over it to pick a slot").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * The Spellstone's slots as a screen: five tome-only slots over the player's inventory. The slot holding the open
     * stone itself is locked, so it cannot be moved out from under its own slots.
     */
    public static final class Menu extends AbstractContainerMenu {
        /** Where the five slots sit in the screen: 26 px apart, centred. */
        public static final int SLOT_X = 28, SLOT_STEP = 26, SLOT_Y = 20;

        private final SimpleContainer held;
        private final ItemStack stone;
        private final int lockedSlot;

        /** The client's menu; the server fills it. */
        public Menu(int id, Inventory inv) {
            this(id, inv, ItemStack.EMPTY, -1);
        }

        Menu(int id, Inventory inv, ItemStack stone, int locked) {
            super(MENU, id);
            this.stone = stone;
            this.lockedSlot = locked;
            // Every change to a slot is written into the stone at once (the client's menu has no stone to write).
            held = new SimpleContainer(SLOTS) {
                @Override
                public void setChanged() {
                    super.setChanged();
                    if (!Menu.this.stone.isEmpty()) save();
                }
            };
            var tomes = tomes(stone);
            for (int i = 0; i < SLOTS; i++) held.setItem(i, tomes.get(i));
            held.startOpen(inv.player);
            for (int i = 0; i < SLOTS; i++)
                addSlot(new Slot(held, i, SLOT_X + i * SLOT_STEP, SLOT_Y) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return TomeItem.spellOf(stack) != null;
                    }

                    @Override
                    public int getMaxStackSize() {
                        return 1;
                    }
                });
            addStandardInventorySlots(inv, 8, 51);
        }

        private void save() {
            store(stone, List.copyOf(held.getItems()));
        }

        /** The menu slot showing the hotbar slot that holds the open stone. */
        private boolean locked(int menuSlot) {
            return lockedSlot >= 0 && menuSlot == SLOTS + 27 + lockedSlot;
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (locked(slot) || input == ContainerInput.SWAP && button == lockedSlot) return;
            super.clicked(slot, button, input, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            if (locked(index)) return ItemStack.EMPTY;
            Slot slot = slots.get(index);
            if (!slot.hasItem()) return ItemStack.EMPTY;
            ItemStack stack = slot.getItem();
            ItemStack before = stack.copy();
            if (index < SLOTS) {
                if (!moveItemStackTo(stack, SLOTS, slots.size(), true)) return ItemStack.EMPTY;
            } else if (TomeItem.spellOf(stack) == null || !moveItemStackTo(stack, 0, SLOTS, false))
                return ItemStack.EMPTY;
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
            else slot.setChanged();
            return before;
        }

        @Override
        public boolean stillValid(Player player) {
            return stone.isEmpty() || player.getMainHandItem() == stone;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            held.stopOpen(player);
            if (!stone.isEmpty()) save();
        }
    }
}
