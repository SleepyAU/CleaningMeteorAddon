package sleepy.addon.util;

import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import sleepy.addon.SleepyAddon;
import sleepy.addon.events.UpdateEvent;

import java.lang.reflect.Method;

/** Cursor-free replacement for Meteor Chest Swap's PICKUP-based inventory move. */
public final class CursorSafeChestSwap {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private static final int PLAYER_CHEST_SCREEN_SLOT = 6;
    private static final long STEP_DELAY_MS = 75L;
    private static final long UNKNOWN_STATE_TIMEOUT_MS = 5000L;

    private static boolean syntaxiaQuotaChecked;
    private static Method syntaxiaPreconsumeSwaps;
    private static final ThreadLocal<Boolean> BYPASS_METEOR_ARMOR_STORAGE =
        ThreadLocal.withInitial(() -> false);
    private static PendingSwap pending;

    private CursorSafeChestSwap() {
    }

    public static boolean swap(ChestSwap.Chestplate preference) {
        if (pending != null) return true;
        if (!canSwap()) return false;

        int sourceSlot;
        if (isGliderEquipped()) {
            sourceSlot = findPreferredChestplate(preference);
        } else if (isChestplateEquipped()) {
            sourceSlot = findGlider();
        } else {
            sourceSlot = findPreferredChestplate(preference);
            if (sourceSlot == -1) sourceSlot = findGlider();
        }

        return sourceSlot != -1 && swapWithInventorySlot(sourceSlot, -1);
    }

    public static boolean swapWithInventorySlot(int inventorySlot, int preferredStagingHotbar) {
        if (pending != null) return true;
        if (!canSwap() || inventorySlot < 0 || inventorySlot >= 36) return false;

        ItemStack source = inventoryStack(inventorySlot).copy();
        if (source.isEmpty()) return false;

        ItemStack chest = chestStack().copy();
        long now = System.currentTimeMillis();
        if (inventorySlot < 9) {
            PendingSwap transaction = new PendingSwap(MC.player, inventorySlot, inventorySlot,
                source, chest, ItemStack.EMPTY, Phase.AWAIT_DIRECT, now);
            if (!sendSwapClick(PLAYER_CHEST_SCREEN_SLOT, inventorySlot)) return false;
            pending = transaction;
            return true;
        }

        int stagingHotbar = chooseStagingHotbar(preferredStagingHotbar);
        PendingSwap transaction = new PendingSwap(MC.player, inventorySlot, stagingHotbar,
            source, chest, inventoryStack(stagingHotbar).copy(), Phase.AWAIT_STAGE, now);
        if (!sendSwapClick(inventorySlot, stagingHotbar)) return false;
        pending = transaction;
        return true;
    }

    /**
     * Touches an inventory slot without moving it. This deliberately uses the
     * PICKUP/PICKUP pair requested for recovering a server-backed ghost stack.
     */
    public static boolean refreshInventorySlot(int inventorySlot) {
        if (pending != null || !canSwap() || inventorySlot < 0 || inventorySlot >= 36
            || inventoryStack(inventorySlot).isEmpty() || !reserveClickSlots(2)) return false;

        int screenSlot = inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
        ScreenHandler handler = MC.player.playerScreenHandler;
        MC.interactionManager.clickSlot(handler.syncId, screenSlot, 0, SlotActionType.PICKUP, MC.player);
        MC.interactionManager.clickSlot(handler.syncId, screenSlot, 0, SlotActionType.PICKUP, MC.player);
        return handler.getCursorStack().isEmpty();
    }

    @EventHandler
    private static void onUpdate(UpdateEvent event) {
        advancePendingSwap();
    }

    private static void advancePendingSwap() {
        PendingSwap transaction = pending;
        if (transaction == null) return;

        if (MC.player == null || MC.player != transaction.player) {
            pending = null;
            return;
        }
        if (!canSwap()) return;

        long now = System.currentTimeMillis();
        if (now - transaction.lastClickAtMs < STEP_DELAY_MS) return;

        if (isFinalState(transaction)) {
            pending = null;
            return;
        }

        // A server inventory resync can roll the complete optimistic client-side
        // sequence back. Recognize that state and safely restart at click one.
        if (isInitialState(transaction)) {
            if (transaction.sourceSlot < 9) {
                transaction.phase = Phase.AWAIT_DIRECT;
                tryClick(transaction, PLAYER_CHEST_SCREEN_SLOT, transaction.sourceSlot);
            } else {
                transaction.phase = Phase.AWAIT_STAGE;
                tryClick(transaction, transaction.sourceSlot, transaction.stagingHotbar);
            }
            return;
        }

        switch (transaction.phase) {
            case AWAIT_DIRECT -> handleUnknownState(transaction, now);
            case AWAIT_STAGE -> {
                if (isStagedState(transaction)) {
                    transaction.phase = Phase.AWAIT_CHEST;
                    tryClick(transaction, PLAYER_CHEST_SCREEN_SLOT, transaction.stagingHotbar);
                } else {
                    handleUnknownState(transaction, now);
                }
            }
            case AWAIT_CHEST -> {
                if (isChestSwappedState(transaction)) {
                    transaction.phase = Phase.AWAIT_RESTORE;
                    tryClick(transaction, transaction.sourceSlot, transaction.stagingHotbar);
                } else if (isStagedState(transaction)) {
                    tryClick(transaction, PLAYER_CHEST_SCREEN_SLOT, transaction.stagingHotbar);
                } else {
                    handleUnknownState(transaction, now);
                }
            }
            case AWAIT_RESTORE -> {
                if (isChestSwappedState(transaction)) {
                    tryClick(transaction, transaction.sourceSlot, transaction.stagingHotbar);
                } else if (isStagedState(transaction)) {
                    transaction.phase = Phase.AWAIT_CHEST;
                    tryClick(transaction, PLAYER_CHEST_SCREEN_SLOT, transaction.stagingHotbar);
                } else {
                    handleUnknownState(transaction, now);
                }
            }
        }
    }

    private static void tryClick(PendingSwap transaction, int screenSlot, int hotbarButton) {
        if (!sendSwapClick(screenSlot, hotbarButton)) return;
        transaction.lastClickAtMs = System.currentTimeMillis();
        transaction.lastKnownStateAtMs = transaction.lastClickAtMs;
    }

    private static void handleUnknownState(PendingSwap transaction, long now) {
        if (now - transaction.lastKnownStateAtMs < UNKNOWN_STATE_TIMEOUT_MS) return;
        SleepyAddon.LOG.warn("Abandoning cursor-safe chest swap after an unexpected inventory change.");
        pending = null;
    }

    private static boolean sendSwapClick(int screenSlot, int hotbarButton) {
        if (!canSwap() || !reserveSwapClick()) return false;
        ScreenHandler handler = MC.player.playerScreenHandler;
        BYPASS_METEOR_ARMOR_STORAGE.set(true);
        try {
            MC.interactionManager.clickSlot(handler.syncId, screenSlot,
                hotbarButton, SlotActionType.SWAP, MC.player);
        } finally {
            BYPASS_METEOR_ARMOR_STORAGE.remove();
        }
        return true;
    }

    /** True only while this class is issuing its own validated armor-slot SWAP. */
    public static boolean isBypassingMeteorArmorStorage() {
        return BYPASS_METEOR_ARMOR_STORAGE.get();
    }

    private static boolean reserveSwapClick() {
        return reserveClickSlots(1);
    }

    private static boolean reserveClickSlots(int clicks) {
        if (!SwapLimiter.canSend(clicks)) return false;

        if (!syntaxiaQuotaChecked) {
            syntaxiaQuotaChecked = true;
            try {
                Class<?> context = Class.forName("syntaxia.util.Network.SwapQuotaContext");
                syntaxiaPreconsumeSwaps = context.getMethod("preconsumeSwaps", int.class);
            } catch (ReflectiveOperationException ignored) {
                syntaxiaPreconsumeSwaps = null;
            }
        }
        if (syntaxiaPreconsumeSwaps == null) return true;

        try {
            return Boolean.TRUE.equals(syntaxiaPreconsumeSwaps.invoke(null, clicks));
        } catch (ReflectiveOperationException ignored) {
            syntaxiaPreconsumeSwaps = null;
            return true;
        }
    }

    public static boolean isGlider(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.isOf(Items.ELYTRA) || stack.contains(DataComponentTypes.GLIDER));
    }

    public static boolean isGliderEquipped() {
        return anyChestViewMatches(CursorSafeChestSwap::isGlider);
    }

    public static boolean isChestplate(ItemStack stack) {
        if (stack == null || stack.isEmpty() || isGlider(stack)) return false;
        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.CHEST;
    }

    public static boolean isChestplateEquipped() {
        return anyChestViewMatches(CursorSafeChestSwap::isChestplate);
    }

    private static boolean canSwap() {
        return MC.player != null && MC.interactionManager != null
            && MC.player.currentScreenHandler == MC.player.playerScreenHandler
            && MC.player.playerScreenHandler.getCursorStack().isEmpty();
    }

    private static boolean anyChestViewMatches(java.util.function.Predicate<ItemStack> predicate) {
        if (MC.player == null) return false;
        if (predicate.test(MC.player.getEquippedStack(EquipmentSlot.CHEST))) return true;

        int inventoryChestSlot = EquipmentSlot.CHEST.getOffsetEntitySlotId(36);
        if (inventoryChestSlot >= 0 && inventoryChestSlot < MC.player.getInventory().size()
            && predicate.test(MC.player.getInventory().getStack(inventoryChestSlot))) return true;

        ScreenHandler handler = MC.player.playerScreenHandler;
        return handler != null && handler.slots.size() > PLAYER_CHEST_SCREEN_SLOT
            && predicate.test(handler.getSlot(PLAYER_CHEST_SCREEN_SLOT).getStack());
    }

    private static int findGlider() {
        for (int i = 0; i < 36; i++) {
            if (isGlider(inventoryStack(i))) return i;
        }
        return -1;
    }

    private static int findPreferredChestplate(ChestSwap.Chestplate preference) {
        int diamond = -1;
        int netherite = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventoryStack(i);
            if (stack.isOf(Items.DIAMOND_CHESTPLATE) && diamond == -1) diamond = i;
            else if (stack.isOf(Items.NETHERITE_CHESTPLATE) && netherite == -1) netherite = i;
        }

        return switch (preference) {
            case Diamond -> diamond;
            case Netherite -> netherite;
            case PreferDiamond -> diamond != -1 ? diamond : netherite;
            case PreferNetherite -> netherite != -1 ? netherite : diamond;
        };
    }

    private static int chooseStagingHotbar(int preferred) {
        if (preferred >= 0 && preferred < 9 && inventoryStack(preferred).isEmpty()) return preferred;
        for (int i = 0; i < 9; i++) {
            if (inventoryStack(i).isEmpty()) return i;
        }
        if (preferred >= 0 && preferred < 9) return preferred;

        int selected = MC.player.getInventory().selectedSlot;
        if (selected >= 0 && selected < 9) return selected;
        return 0;
    }

    private static ItemStack inventoryStack(int inventorySlot) {
        return MC.player.getInventory().getStack(inventorySlot);
    }

    private static ItemStack chestStack() {
        return MC.player.playerScreenHandler.getSlot(PLAYER_CHEST_SCREEN_SLOT).getStack();
    }

    private static boolean isInitialState(PendingSwap transaction) {
        return same(inventoryStack(transaction.sourceSlot), transaction.sourceStack)
            && same(chestStack(), transaction.chestStack)
            && (transaction.sourceSlot < 9
                || same(inventoryStack(transaction.stagingHotbar), transaction.stagingStack));
    }

    private static boolean isStagedState(PendingSwap transaction) {
        return transaction.sourceSlot >= 9
            && same(inventoryStack(transaction.sourceSlot), transaction.stagingStack)
            && same(inventoryStack(transaction.stagingHotbar), transaction.sourceStack)
            && same(chestStack(), transaction.chestStack);
    }

    private static boolean isChestSwappedState(PendingSwap transaction) {
        return transaction.sourceSlot >= 9
            && same(inventoryStack(transaction.sourceSlot), transaction.stagingStack)
            && same(inventoryStack(transaction.stagingHotbar), transaction.chestStack)
            && same(chestStack(), transaction.sourceStack);
    }

    private static boolean isFinalState(PendingSwap transaction) {
        if (transaction.sourceSlot < 9) {
            return same(inventoryStack(transaction.sourceSlot), transaction.chestStack)
                && same(chestStack(), transaction.sourceStack);
        }
        return same(inventoryStack(transaction.sourceSlot), transaction.chestStack)
            && same(inventoryStack(transaction.stagingHotbar), transaction.stagingStack)
            && same(chestStack(), transaction.sourceStack);
    }

    private static boolean same(ItemStack actual, ItemStack expected) {
        return ItemStack.areEqual(actual, expected);
    }

    private enum Phase {
        AWAIT_DIRECT,
        AWAIT_STAGE,
        AWAIT_CHEST,
        AWAIT_RESTORE
    }

    private static final class PendingSwap {
        private final ClientPlayerEntity player;
        private final int sourceSlot;
        private final int stagingHotbar;
        private final ItemStack sourceStack;
        private final ItemStack chestStack;
        private final ItemStack stagingStack;
        private Phase phase;
        private long lastClickAtMs;
        private long lastKnownStateAtMs;

        private PendingSwap(ClientPlayerEntity player, int sourceSlot, int stagingHotbar,
                            ItemStack sourceStack, ItemStack chestStack, ItemStack stagingStack,
                            Phase phase, long now) {
            this.player = player;
            this.sourceSlot = sourceSlot;
            this.stagingHotbar = stagingHotbar;
            this.sourceStack = sourceStack;
            this.chestStack = chestStack;
            this.stagingStack = stagingStack;
            this.phase = phase;
            this.lastClickAtMs = now;
            this.lastKnownStateAtMs = now;
        }
    }
}
