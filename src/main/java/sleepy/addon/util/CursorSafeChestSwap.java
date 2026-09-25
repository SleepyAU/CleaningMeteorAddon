package sleepy.addon.util;

import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/** Cursor-free replacement for Meteor Chest Swap's PICKUP-based inventory move. */
public final class CursorSafeChestSwap {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private static final int PLAYER_CHEST_SCREEN_SLOT = 6;

    private CursorSafeChestSwap() {
    }

    public static boolean swap(ChestSwap.Chestplate preference) {
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
        if (!canSwap() || inventorySlot < 0 || inventorySlot >= 36) return false;

        ScreenHandler handler = MC.player.playerScreenHandler;
        if (inventorySlot < 9) {
            MC.interactionManager.clickSlot(handler.syncId, PLAYER_CHEST_SCREEN_SLOT,
                inventorySlot, SlotActionType.SWAP, MC.player);
            return true;
        }

        int stagingHotbar = chooseStagingHotbar(preferredStagingHotbar);
        MC.interactionManager.clickSlot(handler.syncId, inventorySlot,
            stagingHotbar, SlotActionType.SWAP, MC.player);
        MC.interactionManager.clickSlot(handler.syncId, PLAYER_CHEST_SCREEN_SLOT,
            stagingHotbar, SlotActionType.SWAP, MC.player);
        MC.interactionManager.clickSlot(handler.syncId, inventorySlot,
            stagingHotbar, SlotActionType.SWAP, MC.player);
        return true;
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
            if (isGlider(MC.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private static int findPreferredChestplate(ChestSwap.Chestplate preference) {
        int diamond = -1;
        int netherite = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = MC.player.getInventory().getStack(i);
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
        int selected = MC.player.getInventory().getSelectedSlot();
        if (preferred >= 0 && preferred < 9 && preferred != selected
            && MC.player.getInventory().getStack(preferred).isEmpty()) return preferred;

        for (int i = 0; i < 9; i++) {
            if (i != selected && MC.player.getInventory().getStack(i).isEmpty()) return i;
        }
        if (preferred >= 0 && preferred < 9 && preferred != selected) return preferred;
        for (int i = 0; i < 9; i++) if (i != selected) return i;
        return selected;
    }
}
