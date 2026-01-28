package sleepy.addon.util;

import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import sleepy.addon.util.traits.Util;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

import java.util.Set;

/**
 * Port of Syntaxia's InventoryUtil to SleepyAddon.
 * Implements Util to access the MinecraftClient instance.
 */
public class InventoryUtil implements Util {
    // Fastutil map used for cached slot lookups in some helpers.
    private static final Object2IntMap<Item> ITEM_SLOT_CACHE = Object2IntMaps.synchronize(new Object2IntArrayMap<>());

    // InventoryUtil.java — add inside class InventoryUtil
    public static final class SwapSession {
        private static final ThreadLocal<SwapSession> CURRENT = new ThreadLocal<>();

        private boolean active;
        private boolean didSilentSwap;
        private int prevSelected = -1;     // for hotbar select restore
        private int silentInvIndex = -1;   // for SWAP-back
        private boolean ownedByCaller;     // if we created it in getOrBegin()

        private SwapSession() {}

        /** Returns the active session for this thread, or null. */
        public static SwapSession current() { return CURRENT.get(); }

        /**
         * Acquire the current session, or create a new one targeting the given inventory index.
         */
        public static SwapSession getOrBegin(int desiredSlot) {
            SwapSession s = CURRENT.get();
            if (s != null && s.active) return s;
            return beginForDesiredSlot(desiredSlot);
        }

        /** True if a session is active on this thread. */
        public static boolean isActive() { return CURRENT.get() != null && CURRENT.get().active; }

        /** Begin a session targeting a specific inventory index (0..35). */
        public static SwapSession beginForDesiredSlot(int desiredSlot) {
            if (mc.player == null) return null;
            SwapSession existing = CURRENT.get();
            if (existing != null && existing.active) return existing;

            int sel = mc.player.getInventory().selectedSlot;
            SwapSession s = new SwapSession();
            s.ownedByCaller = true;

            if (desiredSlot >= 0 && desiredSlot <= 8) {
                // Hotbar: select client+server, remember previous
                if (sel != desiredSlot) {
                    s.prevSelected = sel;
                    mc.player.getInventory().setSelectedSlot(desiredSlot);
                    mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(desiredSlot));
                }
            } else if (desiredSlot >= 9 && desiredSlot <= 35) {
                // Main inventory: silent SWAP_ITEM_WITH_OFFHAND pattern (inventory index)
                s.silentInvIndex = desiredSlot;
            }

            s.active = true;
            CURRENT.set(s);
            return s;
        }

        /** Close the session and restore previous state. */
        public void close() {
            if (!active) return;
            active = false;

            boolean ownedByCaller = this.ownedByCaller;

            try {
                if (mc.player == null) return;

                if (silentInvIndex >= 0) {
                    // TODO: if you later add the SWAP_ITEM_WITH_OFFHAND offhand swapping here, mirror Syntaxia logic.
                    // For now, just restore via selected slot if needed; the actual swap logic is still in your modules.
                } else if (prevSelected != -1) {
                    if (mc.player.getInventory().selectedSlot != prevSelected) {
                        mc.player.getInventory().setSelectedSlot(prevSelected);
                        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(prevSelected));
                    }
                }
            } finally {
                if (ownedByCaller && CURRENT.get() == this) CURRENT.remove();
            }
        }
    }

    /** Find an Item anywhere in 0..35. */
    public static int getSlotWithItem(Item item) {
        if (mc.player == null) return -1;

        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    /** Find Elytra in hotbar (0..8). */
    public static int findElytraHotbar() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.ELYTRA) return i;
        }
        return -1;
    }

    /**
     * Get the first hotbar slot with a block that matches the given predicate.
     */
    public static int findBlockInHotbar(Set<Block> blocks) {
        if (mc.player == null) return -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof BlockItem bi && blocks.contains(bi.getBlock())) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Get the best sword slot based on enchantments (Damage, Sharpness, etc).
     * Uses the 1.20+ DataComponentTypes.ENCHANTMENTS API.
     */
    public static int getBestSwordSlot() {
        if (mc.player == null) return -1;

        int bestSlot = -1;
        double bestScore = -1.0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();

            // Simple example: prefer any sword item; you can expand with enchant scoring.
            if (!item.getTranslationKey().toLowerCase().contains("sword")) continue;

            double score = scoreWeapon(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    /**
     * Example scoring for a weapon stack based on enchantments.
     */
    private static double scoreWeapon(ItemStack stack) {
        var enchantsComp = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchantsComp == null) return 0.0;

        double score = 0.0;

        for (var entry : enchantsComp.getEnchantmentEntries()) {
            RegistryEntry<Enchantment> enchEntry = entry.getKey();
            int level = entry.getIntValue();

            RegistryKey<Enchantment> key = enchEntry.getKey().orElse(null);
            if (key == null) continue;

            String path = key.getValue().getPath();

            if (path.contains("sharpness")) score += level * 2.0;
            if (path.contains("smite")) score += level * 1.5;
            if (path.contains("bane_of_arthropods")) score += level * 1.5;
            if (path.contains("fire_aspect")) score += level * 1.0;
            if (path.contains("knockback")) score += level * 0.5;
        }

        return score;
    }

    /** Current client-selected hotbar index (0..8). */
    public static int clientHotbarIndex() {
        if (mc.player == null) return -1;
        return mc.player.getInventory().selectedSlot;
    }

    /**
     * Server-only hotbar spoof: does NOT change the client's selected slot.
     * Useful to keep using/eating server-side without moving the client highlight.
     */
    public static void spoofHotbarServer(int slot) {
        if (mc.player == null) return;
        if (slot >= 0 && slot <= 8) {
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    /**
     * Select or move an item into hand (MAIN or OFF), returning which Hand would be used.
     * Slot is in "inventory-space" (0..35 for player inventory, 36..44 hotbar, 40 offhand).
     */
    public static Hand swap(int slot) {
        if (mc.player == null || mc.interactionManager == null) return Hand.MAIN_HAND;

        if (slot >= 0 && slot <= 35) {
            // Move from inventory to currently selected hotbar slot (strictly client-side slot moving)
            if (mc.player.currentScreenHandler != null) {
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId, slot, 0,
                    SlotActionType.PICKUP, mc.player
                );
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId, 36 + mc.player.getInventory().selectedSlot, 0,
                    SlotActionType.PICKUP, mc.player
                );
                mc.interactionManager.clickSlot(
                    mc.player.currentScreenHandler.syncId, slot, 0,
                    SlotActionType.PICKUP, mc.player
                );
                return Hand.MAIN_HAND;
            }
        } else if (slot == 40) {
            return Hand.OFF_HAND;
        }

        return Hand.MAIN_HAND;
    }

    // public static Hand strictSwap(int slot) {}
}
