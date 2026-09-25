package sleepy.addon.mixin;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.player.AutoReplenish;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Replaces Meteor Auto Replenish's cursor-based merge with one cursor-safe SWAP click. */
@Mixin(value = AutoReplenish.class, remap = false)
public abstract class MixinAutoReplenish {
    @Shadow @Final private Setting<Integer> threshold;
    @Shadow @Final private Setting<Boolean> unstackable;
    @Shadow @Final private Setting<List<Item>> excludedItems;
    @Shadow @Final private ItemStack[] items;

    @Shadow
    private int findItem(ItemStack lookForStack, int excludedSlot, int goodEnoughCount) {
        throw new AssertionError();
    }

    @Inject(method = "checkSlot", at = @At("HEAD"), cancellable = true, remap = false)
    private void sleepy$cursorSafeReplenish(int slot, ItemStack stack, CallbackInfo ci) {
        // Meteor 0.5.8 passes SlotUtils.OFFHAND (screen slot 45) here, while the
        // ten-entry history array stores the offhand at index 9.
        int historySlot = slot == SlotUtils.OFFHAND ? 9 : slot;
        if (historySlot < 0 || historySlot >= items.length) {
            ci.cancel();
            return;
        }

        ItemStack previous = items[historySlot];
        items[historySlot] = stack.copy();

        if (excludedItems.get().contains(stack.getItem())
            || excludedItems.get().contains(previous.getItem())) {
            ci.cancel();
            return;
        }

        int source = -1;
        int excludedSourceSlot = slot == SlotUtils.OFFHAND ? -1 : slot;
        if (stack.isStackable() && !stack.isEmpty() && stack.getCount() <= threshold.get()) {
            source = findItem(stack, excludedSourceSlot, threshold.get() - stack.getCount() + 1);
        }
        if (previous.isStackable() && stack.isEmpty() && !previous.isEmpty()) {
            source = findItem(previous, excludedSourceSlot, threshold.get() + 1);
        }
        if (unstackable.get() && !previous.isStackable() && stack.isEmpty() && !previous.isEmpty()) {
            source = findItem(previous, excludedSourceSlot, 1);
        }

        cursorSafeSwap(source, historySlot);
        ci.cancel();
    }

    private static void cursorSafeSwap(int sourceInventorySlot, int target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null || sourceInventorySlot < 0
            || sourceInventorySlot >= 36 || target < 0 || target > 9
            || mc.player.currentScreenHandler != mc.player.playerScreenHandler
            || !mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;

        ItemStack source = mc.player.getInventory().getStack(sourceInventorySlot);
        ItemStack destination = target == 9
            ? mc.player.getOffHandStack()
            : mc.player.getInventory().getStack(target);
        // A SWAP cannot merge two partial stacks. Only exchange them when it actually improves
        // the replenished slot, otherwise Auto Replenish would oscillate the same two stacks.
        if (source.isEmpty() || (!destination.isEmpty() && source.getCount() <= destination.getCount())) return;

        int sourceScreenSlot = sourceInventorySlot < 9 ? 36 + sourceInventorySlot : sourceInventorySlot;
        int swapButton = target == 9 ? 40 : target;
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, sourceScreenSlot,
            swapButton, SlotActionType.SWAP, mc.player);
    }
}
