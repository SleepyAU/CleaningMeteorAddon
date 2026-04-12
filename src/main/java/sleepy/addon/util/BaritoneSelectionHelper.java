package sleepy.addon.util;

import net.minecraft.util.math.BlockPos;
import sleepy.addon.SleepyAddon;

import java.util.ArrayList;
import java.util.List;

public final class BaritoneSelectionHelper {
    private static boolean presentChecked;
    private static boolean present;
    private static boolean warnedSelectionAccessFailure;

    private BaritoneSelectionHelper() {
    }

    public static List<SelectionBounds> getSelectionBounds() {
        if (!isBaritonePresent()) return List.of();

        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            if (provider == null) return List.of();

            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            if (baritone == null) return List.of();

            Object selectionManager = baritone.getClass().getMethod("getSelectionManager").invoke(baritone);
            if (selectionManager == null) return List.of();

            Object rawSelections = selectionManager.getClass().getMethod("getSelections").invoke(selectionManager);
            if (!(rawSelections instanceof Object[] selections) || selections.length == 0) return List.of();

            List<SelectionBounds> bounds = new ArrayList<>(selections.length);
            for (Object selection : selections) {
                if (selection == null) continue;

                Object minObj = selection.getClass().getMethod("min").invoke(selection);
                Object maxObj = selection.getClass().getMethod("max").invoke(selection);
                if (!(minObj instanceof BlockPos minPos) || !(maxObj instanceof BlockPos maxPos)) continue;

                bounds.add(new SelectionBounds(
                    new BlockPos(
                        Math.min(minPos.getX(), maxPos.getX()),
                        Math.min(minPos.getY(), maxPos.getY()),
                        Math.min(minPos.getZ(), maxPos.getZ())
                    ),
                    new BlockPos(
                        Math.max(minPos.getX(), maxPos.getX()),
                        Math.max(minPos.getY(), maxPos.getY()),
                        Math.max(minPos.getZ(), maxPos.getZ())
                    )
                ));
            }

            return bounds.isEmpty() ? List.of() : List.copyOf(bounds);
        } catch (Throwable throwable) {
            if (!warnedSelectionAccessFailure) {
                warnedSelectionAccessFailure = true;
                SleepyAddon.LOG.warn("Failed to read Baritone selections", throwable);
            }
            return List.of();
        }
    }

    private static boolean isBaritonePresent() {
        if (presentChecked) return present;
        presentChecked = true;

        try {
            Class.forName("baritone.api.BaritoneAPI", false, BaritoneSelectionHelper.class.getClassLoader());
            present = true;
        } catch (Throwable ignored) {
            present = false;
        }

        return present;
    }

    public record SelectionBounds(BlockPos min, BlockPos max) {
        public boolean contains(BlockPos pos) {
            if (pos == null || min == null || max == null) return false;
            return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }
    }
}
