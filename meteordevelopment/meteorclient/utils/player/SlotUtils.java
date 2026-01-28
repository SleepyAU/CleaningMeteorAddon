/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player;

import meteordevelopment.meteorclient.mixin.CreativeInventoryScreenAccessor;
import meteordevelopment.meteorclient.mixin.HorseScreenHandlerAccessor;
import net.minecraft.class_1492;
import net.minecraft.class_1496;
import net.minecraft.class_1498;
import net.minecraft.class_1501;
import net.minecraft.class_1506;
import net.minecraft.class_1507;
import net.minecraft.class_1703;
import net.minecraft.class_1704;
import net.minecraft.class_1706;
import net.minecraft.class_1707;
import net.minecraft.class_1708;
import net.minecraft.class_1714;
import net.minecraft.class_1716;
import net.minecraft.class_1718;
import net.minecraft.class_1722;
import net.minecraft.class_1723;
import net.minecraft.class_1724;
import net.minecraft.class_1726;
import net.minecraft.class_1728;
import net.minecraft.class_1733;
import net.minecraft.class_3705;
import net.minecraft.class_3706;
import net.minecraft.class_3803;
import net.minecraft.class_3858;
import net.minecraft.class_3910;
import net.minecraft.class_3916;
import net.minecraft.class_3971;
import net.minecraft.class_481;
import net.minecraft.class_7706;
import net.minecraft.class_7923;
import net.minecraft.screen.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class SlotUtils {
    public static final int HOTBAR_START = 0;
    public static final int HOTBAR_END = 8;

    public static final int OFFHAND = 45;

    public static final int MAIN_START = 9;
    public static final int MAIN_END = 35;

    public static final int ARMOR_START = 36;
    public static final int ARMOR_END = 39;

    private SlotUtils() {
    }

    public static int indexToId(int i) {
        if (mc.field_1724 == null) return -1;
        class_1703 handler = mc.field_1724.field_7512;

        if (handler instanceof class_1723) return survivalInventory(i);
        if (handler instanceof class_481.class_483) return creativeInventory(i);
        if (handler instanceof class_1707 genericContainerScreenHandler) return genericContainer(i, genericContainerScreenHandler.method_17388());
        if (handler instanceof class_1714) return craftingTable(i);
        if (handler instanceof class_3858) return furnace(i);
        if (handler instanceof class_3705) return furnace(i);
        if (handler instanceof class_3706) return furnace(i);
        if (handler instanceof class_1716) return generic3x3(i);
        if (handler instanceof class_1718) return enchantmentTable(i);
        if (handler instanceof class_1708) return brewingStand(i);
        if (handler instanceof class_1728) return villager(i);
        if (handler instanceof class_1704) return beacon(i);
        if (handler instanceof class_1706) return anvil(i);
        if (handler instanceof class_1722) return hopper(i);
        if (handler instanceof class_1733) return genericContainer(i, 3);
        if (handler instanceof class_1724) return horse(handler, i);
        if (handler instanceof class_3910) return cartographyTable(i);
        if (handler instanceof class_3803) return grindstone(i);
        if (handler instanceof class_3916) return lectern();
        if (handler instanceof class_1726) return loom(i);
        if (handler instanceof class_3971) return stonecutter(i);

        return -1;
    }

    private static int survivalInventory(int i) {
        if (isHotbar(i)) return 36 + i;
        if (isArmor(i)) return 5 + (i - 36);
        return i;
    }

    private static int creativeInventory(int i) {
        if (!(mc.field_1755 instanceof class_481) || CreativeInventoryScreenAccessor.getSelectedTab() != class_7923.field_44687.method_29107(class_7706.field_40206))
            return -1;
        return survivalInventory(i);
    }

    private static int genericContainer(int i, int rows) {
        if (isHotbar(i)) return (rows + 3) * 9 + i;
        if (isMain(i)) return rows * 9 + (i - 9);
        return -1;
    }

    private static int craftingTable(int i) {
        if (isHotbar(i)) return 37 + i;
        if (isMain(i)) return i + 1;
        return -1;
    }

    private static int furnace(int i) {
        if (isHotbar(i)) return 30 + i;
        if (isMain(i)) return 3 + (i - 9);
        return -1;
    }

    private static int generic3x3(int i) {
        if (isHotbar(i)) return 36 + i;
        if (isMain(i)) return i;
        return -1;
    }

    private static int enchantmentTable(int i) {
        if (isHotbar(i)) return 29 + i;
        if (isMain(i)) return 2 + (i - 9);
        return -1;
    }

    private static int brewingStand(int i) {
        if (isHotbar(i)) return 32 + i;
        if (isMain(i)) return 5 + (i - 9);
        return -1;
    }

    private static int villager(int i) {
        if (isHotbar(i)) return 30 + i;
        if (isMain(i)) return 3 + (i - 9);
        return -1;
    }

    private static int beacon(int i) {
        if (isHotbar(i)) return 28 + i;
        if (isMain(i)) return 1 + (i - 9);
        return -1;
    }

    private static int anvil(int i) {
        if (isHotbar(i)) return 30 + i;
        if (isMain(i)) return 3 + (i - 9);
        return -1;
    }

    private static int hopper(int i) {
        if (isHotbar(i)) return 32 + i;
        if (isMain(i)) return 5 + (i - 9);
        return -1;
    }

    private static int horse(class_1703 handler, int i) {
        class_1496 entity = ((HorseScreenHandlerAccessor) handler).getEntity();

        if (entity instanceof class_1501 llamaEntity) {
            int strength = llamaEntity.method_6803();
            if (isHotbar(i)) return (2 + 3 * strength) + 28 + i;
            if (isMain(i)) return (2 + 3 * strength) + 1 + (i - 9);
        } else if (entity instanceof class_1498 || entity instanceof class_1506 || entity instanceof class_1507) {
            if (isHotbar(i)) return 29 + i;
            if (isMain(i)) return 2 + (i - 9);
        } else if (entity instanceof class_1492 abstractDonkeyEntity) {
            boolean chest = abstractDonkeyEntity.method_6703();
            if (isHotbar(i)) return (chest ? 44 : 29) + i;
            if (isMain(i)) return (chest ? 17 : 2) + (i - 9);
        }

        return -1;
    }

    private static int cartographyTable(int i) {
        if (isHotbar(i)) return 30 + i;
        if (isMain(i)) return 3 + (i - 9);
        return -1;
    }

    private static int grindstone(int i) {
        if (isHotbar(i)) return 30 + i;
        if (isMain(i)) return 3 + (i - 9);
        return -1;
    }

    private static int lectern() {
        return -1;
    }

    private static int loom(int i) {
        if (isHotbar(i)) return 31 + i;
        if (isMain(i)) return 4 + (i - 9);
        return -1;
    }

    private static int stonecutter(int i) {
        if (isHotbar(i)) return 29 + i;
        if (isMain(i)) return 2 + (i - 9);
        return -1;
    }

    // Utils

    public static boolean isHotbar(int i) {
        return i >= HOTBAR_START && i <= HOTBAR_END;
    }

    public static boolean isMain(int i) {
        return i >= MAIN_START && i <= MAIN_END;
    }

    public static boolean isArmor(int i) {
        return i >= ARMOR_START && i <= ARMOR_END;
    }
}
