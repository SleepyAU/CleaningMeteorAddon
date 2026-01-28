/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player;

import meteordevelopment.meteorclient.mixininterface.IClientPlayerInteractionManager;
import net.minecraft.class_1703;
import net.minecraft.class_1713;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_2680;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class InvUtils {
    private static final Action ACTION = new Action();
    public static int previousSlot = -1;

    private InvUtils() {
    }

    // Predicates

    public static boolean testInMainHand(Predicate<class_1799> predicate) {
        return predicate.test(mc.field_1724.method_6047());
    }

    public static boolean testInMainHand(class_1792... items) {
        return testInMainHand(itemStack -> {
            for (var item : items) if (itemStack.method_31574(item)) return true;
            return false;
        });
    }

    public static boolean testInOffHand(Predicate<class_1799> predicate) {
        return predicate.test(mc.field_1724.method_6079());
    }

    public static boolean testInOffHand(class_1792... items) {
        return testInOffHand(itemStack -> {
            for (var item : items) if (itemStack.method_31574(item)) return true;
            return false;
        });
    }

    public static boolean testInHands(Predicate<class_1799> predicate) {
        return testInMainHand(predicate) || testInOffHand(predicate);
    }

    public static boolean testInHands(class_1792... items) {
        return testInMainHand(items) || testInOffHand(items);
    }

    public static boolean testInHotbar(Predicate<class_1799> predicate) {
        if (testInHands(predicate)) return true;

        for (int i = SlotUtils.HOTBAR_START; i < SlotUtils.HOTBAR_END; i++) {
            class_1799 stack = mc.field_1724.method_31548().method_5438(i);
            if (predicate.test(stack)) return true;
        }

        return false;
    }

    public static boolean testInHotbar(class_1792... items) {
        return testInHotbar(itemStack -> {
            for (var item : items) if (itemStack.method_31574(item)) return true;
            return false;
        });
    }

    // Finding items

    public static FindItemResult findEmpty() {
        return find(class_1799::method_7960);
    }

    public static FindItemResult findInHotbar(class_1792... items) {
        return findInHotbar(itemStack -> {
            for (class_1792 item : items) {
                if (itemStack.method_7909() == item) return true;
            }
            return false;
        });
    }

    public static FindItemResult findInHotbar(Predicate<class_1799> isGood) {
        if (testInOffHand(isGood)) {
            return new FindItemResult(SlotUtils.OFFHAND, mc.field_1724.method_6079().method_7947());
        }

        if (testInMainHand(isGood)) {
            return new FindItemResult(mc.field_1724.method_31548().field_7545, mc.field_1724.method_6047().method_7947());
        }

        return find(isGood, 0, 8);
    }

    public static FindItemResult find(class_1792... items) {
        return find(itemStack -> {
            for (class_1792 item : items) {
                if (itemStack.method_7909() == item) return true;
            }
            return false;
        });
    }

    public static FindItemResult find(Predicate<class_1799> isGood) {
        if (mc.field_1724 == null) return new FindItemResult(0, 0);
        return find(isGood, 0, mc.field_1724.method_31548().method_5439());
    }

    public static FindItemResult find(Predicate<class_1799> isGood, int start, int end) {
        if (mc.field_1724 == null) return new FindItemResult(0, 0);

        int slot = -1, count = 0;

        for (int i = start; i <= end; i++) {
            class_1799 stack = mc.field_1724.method_31548().method_5438(i);

            if (isGood.test(stack)) {
                if (slot == -1) slot = i;
                count += stack.method_7947();
            }
        }

        return new FindItemResult(slot, count);
    }

    public static FindItemResult findFastestTool(class_2680 state) {
        float bestScore = 1;
        int slot = -1;

        for (int i = 0; i < 9; i++) {
            class_1799 stack = mc.field_1724.method_31548().method_5438(i);
            if (!stack.method_7951(state)) continue;

            float score = stack.method_7924(state);
            if (score > bestScore) {
                bestScore = score;
                slot = i;
            }
        }

        return new FindItemResult(slot, 1);
    }

    // Interactions

    public static boolean swap(int slot, boolean swapBack) {
        if (slot == SlotUtils.OFFHAND) return true;
        if (slot < 0 || slot > 8) return false;
        if (swapBack && previousSlot == -1) previousSlot = mc.field_1724.method_31548().field_7545;
        else if (!swapBack) previousSlot = -1;

        mc.field_1724.method_31548().field_7545 = slot;
        ((IClientPlayerInteractionManager) mc.field_1761).meteor$syncSelected();
        return true;
    }

    public static boolean swapBack() {
        if (previousSlot == -1) return false;

        boolean return_ = swap(previousSlot, false);
        previousSlot = -1;
        return return_;
    }

    public static Action move() {
        ACTION.type = class_1713.field_7790;
        ACTION.two = true;
        return ACTION;
    }

    public static Action click() {
        ACTION.type = class_1713.field_7790;
        return ACTION;
    }

    /**
     * When writing code with quickSwap, both to and from should provide the ID of a slot, not the index.
     * From should be the slot in the hotbar, to should be the slot you're switching an item from.
     */
    public static Action quickSwap() {
        ACTION.type = class_1713.field_7791;
        return ACTION;
    }

    public static Action shiftClick() {
        ACTION.type = class_1713.field_7794;
        return ACTION;
    }

    public static Action drop() {
        ACTION.type = class_1713.field_7795;
        ACTION.data = 1;
        return ACTION;
    }

    public static void dropHand() {
        if (!mc.field_1724.field_7512.method_34255().method_7960()) mc.field_1761.method_2906(mc.field_1724.field_7512.field_7763, class_1703.field_30730, 0, class_1713.field_7790, mc.field_1724);
    }

    public static class Action {
        private class_1713 type = null;
        private boolean two = false;
        private int from = -1;
        private int to = -1;
        private int data = 0;

        private boolean isRecursive = false;

        private Action() {
        }

        // From

        public Action fromId(int id) {
            from = id;
            return this;
        }

        public Action from(int index) {
            return fromId(SlotUtils.indexToId(index));
        }

        public Action fromHotbar(int i) {
            return from(SlotUtils.HOTBAR_START + i);
        }

        public Action fromOffhand() {
            return from(SlotUtils.OFFHAND);
        }

        public Action fromMain(int i) {
            return from(SlotUtils.MAIN_START + i);
        }

        public Action fromArmor(int i) {
            return from(SlotUtils.ARMOR_START + (3 - i));
        }

        // To

        public void toId(int id) {
            to = id;
            run();
        }

        public void to(int index) {
            toId(SlotUtils.indexToId(index));
        }

        public void toHotbar(int i) {
            to(SlotUtils.HOTBAR_START + i);
        }

        public void toOffhand() {
            to(SlotUtils.OFFHAND);
        }

        public void toMain(int i) {
            to(SlotUtils.MAIN_START + i);
        }

        public void toArmor(int i) {
            to(SlotUtils.ARMOR_START + (3 - i));
        }

        // Slot

        public void slotId(int id) {
            from = to = id;
            run();
        }

        public void slot(int index) {
            slotId(SlotUtils.indexToId(index));
        }

        public void slotHotbar(int i) {
            slot(SlotUtils.HOTBAR_START + i);
        }

        public void slotOffhand() {
            slot(SlotUtils.OFFHAND);
        }

        public void slotMain(int i) {
            slot(SlotUtils.MAIN_START + i);
        }

        public void slotArmor(int i) {
            slot(SlotUtils.ARMOR_START + (3 - i));
        }

        // Other

        private void run() {
            boolean hadEmptyCursor = mc.field_1724.field_7512.method_34255().method_7960();

            if (type == class_1713.field_7791) {
                data = from;
                from = to;
            }

            if (type != null && from != -1 && to != -1) {
                click(from);
                if (two) click(to);
            }

            class_1713 preType = type;
            boolean preTwo = two;
            int preFrom = from;
            int preTo = to;

            type = null;
            two = false;
            from = -1;
            to = -1;
            data = 0;

            if (!isRecursive && hadEmptyCursor && preType == class_1713.field_7790 && preTwo && (preFrom != -1 && preTo != -1) && !mc.field_1724.field_7512.method_34255().method_7960()) {
                isRecursive = true;
                InvUtils.click().slotId(preFrom);
                isRecursive = false;
            }
        }

        private void click(int id) {
            mc.field_1761.method_2906(mc.field_1724.field_7512.field_7763, id, data, type, mc.field_1724);
        }
    }
}
