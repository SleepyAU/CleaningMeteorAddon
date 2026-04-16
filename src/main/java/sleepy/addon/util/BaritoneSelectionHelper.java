package sleepy.addon.util;

import net.minecraft.util.math.BlockPos;
import sleepy.addon.SleepyAddon;

import java.util.ArrayList;
import java.util.List;

public final class BaritoneSelectionHelper {
    private static boolean presentChecked;
    private static boolean present;
    private static boolean warnedSelectionAccessFailure;
    private static boolean warnedPathingAccessFailure;
    private static boolean warnedSettingsAccessFailure;
    private static boolean actionsSuppressed;
    private static Object savedAllowBreak;
    private static Object savedAllowPlace;
    private static Boolean savedBuilderPaused;

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

    public static boolean isBaritoneAvailable() {
        return isBaritonePresent();
    }

    public static boolean pathTo(BlockPos target) {
        return pathTo(target, false);
    }

    public static boolean pathTo(BlockPos target, boolean exact) {
        if (target == null || !isBaritonePresent()) return false;

        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return false;

            Object customGoalProcess = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);
            if (customGoalProcess == null) return false;

            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> goalGetToBlockClass = Class.forName("baritone.api.pathing.goals.GoalGetToBlock");
            Class<?> goalBlockClass = Class.forName("baritone.api.pathing.goals.GoalBlock");
            Object goal = (exact ? goalBlockClass : goalGetToBlockClass).getConstructor(BlockPos.class).newInstance(target);

            customGoalProcess.getClass().getMethod("setGoalAndPath", goalClass).invoke(customGoalProcess, goal);
            return true;
        } catch (Throwable throwable) {
            if (!warnedPathingAccessFailure) {
                warnedPathingAccessFailure = true;
                SleepyAddon.LOG.warn("Failed to send Baritone path goal", throwable);
            }
            return false;
        }
    }

    public static void cancelPathing() {
        if (!isBaritonePresent()) return;

        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return;

            Object pathingBehavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            if (pathingBehavior != null) {
                pathingBehavior.getClass().getMethod("cancelEverything").invoke(pathingBehavior);
            }
        } catch (Throwable throwable) {
            if (!warnedPathingAccessFailure) {
                warnedPathingAccessFailure = true;
                SleepyAddon.LOG.warn("Failed to cancel Baritone pathing", throwable);
            }
        }
    }

    public static boolean isPathing() {
        if (!isBaritonePresent()) return false;

        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return false;

            Object pathingBehavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            if (pathingBehavior == null) return false;

            Object pathing = pathingBehavior.getClass().getMethod("isPathing").invoke(pathingBehavior);
            return pathing instanceof Boolean value && value;
        } catch (Throwable throwable) {
            if (!warnedPathingAccessFailure) {
                warnedPathingAccessFailure = true;
                SleepyAddon.LOG.warn("Failed to read Baritone pathing state", throwable);
            }
            return false;
        }
    }

    public static boolean suppressBuilderActions() {
        return suppressBuilderActions(false);
    }

    public static boolean suppressBuilderActions(boolean allowBreak) {
        if (!isBaritonePresent()) return false;

        try {
            if (!actionsSuppressed) {
                savedAllowBreak = getSettingValue("allowBreak");
                savedAllowPlace = getSettingValue("allowPlace");
                savedBuilderPaused = isBuilderPaused();
                actionsSuppressed = true;
            }

            setSettingValue("allowBreak", allowBreak);
            setSettingValue("allowPlace", Boolean.FALSE);
            pauseBuilderProcess();
            releaseClickInputs();
            return true;
        } catch (Throwable throwable) {
            if (!warnedSettingsAccessFailure) {
                warnedSettingsAccessFailure = true;
                SleepyAddon.LOG.warn("Failed to suppress Baritone break/place settings", throwable);
            }
            return false;
        }
    }

    public static void enforcePathingOnly() {
        enforcePathingOnly(false);
    }

    public static void enforcePathingOnly(boolean allowBreak) {
        suppressBuilderActions(allowBreak);
        pauseBuilderProcess();
        releaseClickInputs();
    }

    public static void restoreBuilderActions() {
        if (!actionsSuppressed || !isBaritonePresent()) return;

        try {
            if (savedAllowBreak != null) setSettingValue("allowBreak", savedAllowBreak);
            if (savedAllowPlace != null) setSettingValue("allowPlace", savedAllowPlace);
            if (Boolean.FALSE.equals(savedBuilderPaused)) resumeBuilderProcess();
        } catch (Throwable throwable) {
            if (!warnedSettingsAccessFailure) {
                warnedSettingsAccessFailure = true;
                SleepyAddon.LOG.warn("Failed to restore Baritone break/place settings", throwable);
            }
        } finally {
            savedAllowBreak = null;
            savedAllowPlace = null;
            savedBuilderPaused = null;
            actionsSuppressed = false;
        }
    }

    private static Object getPrimaryBaritone() throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
        Object provider = apiClass.getMethod("getProvider").invoke(null);
        if (provider == null) return null;
        return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
    }

    private static Object getSettings() throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
        return apiClass.getMethod("getSettings").invoke(null);
    }

    private static Object getSetting(String name) throws ReflectiveOperationException {
        Object settings = getSettings();
        if (settings == null) return null;
        return settings.getClass().getField(name).get(settings);
    }

    private static Object getSettingValue(String name) throws ReflectiveOperationException {
        Object setting = getSetting(name);
        if (setting == null) return null;
        return setting.getClass().getField("value").get(setting);
    }

    private static void setSettingValue(String name, Object value) throws ReflectiveOperationException {
        Object setting = getSetting(name);
        if (setting != null) {
            setting.getClass().getField("value").set(setting, value);
        }
    }

    private static void pauseBuilderProcess() {
        if (!isBaritonePresent()) return;

        try {
            Object builderProcess = getBuilderProcess();
            if (builderProcess != null) {
                builderProcess.getClass().getMethod("pause").invoke(builderProcess);
            }
        } catch (Throwable throwable) {
            if (!warnedPathingAccessFailure) {
                warnedPathingAccessFailure = true;
                SleepyAddon.LOG.warn("Failed to pause Baritone builder", throwable);
            }
        }
    }

    private static void resumeBuilderProcess() {
        if (!isBaritonePresent()) return;

        try {
            Object builderProcess = getBuilderProcess();
            if (builderProcess != null) {
                builderProcess.getClass().getMethod("resume").invoke(builderProcess);
            }
        } catch (Throwable throwable) {
            if (!warnedPathingAccessFailure) {
                warnedPathingAccessFailure = true;
                SleepyAddon.LOG.warn("Failed to resume Baritone builder", throwable);
            }
        }
    }

    private static Boolean isBuilderPaused() {
        if (!isBaritonePresent()) return null;

        try {
            Object builderProcess = getBuilderProcess();
            if (builderProcess == null) return null;

            Object paused = builderProcess.getClass().getMethod("isPaused").invoke(builderProcess);
            return paused instanceof Boolean value ? value : null;
        } catch (Throwable throwable) {
            if (!warnedPathingAccessFailure) {
                warnedPathingAccessFailure = true;
                SleepyAddon.LOG.warn("Failed to read Baritone builder pause state", throwable);
            }
            return null;
        }
    }

    private static Object getBuilderProcess() throws ReflectiveOperationException {
        Object baritone = getPrimaryBaritone();
        if (baritone == null) return null;
        return baritone.getClass().getMethod("getBuilderProcess").invoke(baritone);
    }

    private static void releaseClickInputs() {
        if (!isBaritonePresent()) return;

        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return;

            Object inputOverrideHandler = baritone.getClass().getMethod("getInputOverrideHandler").invoke(baritone);
            if (inputOverrideHandler == null) return;

            Class<?> inputClass = Class.forName("baritone.api.utils.input.Input");
            Object clickLeft = inputClass.getMethod("valueOf", String.class).invoke(null, "CLICK_LEFT");
            Object clickRight = inputClass.getMethod("valueOf", String.class).invoke(null, "CLICK_RIGHT");

            inputOverrideHandler.getClass().getMethod("setInputForceState", inputClass, boolean.class).invoke(inputOverrideHandler, clickLeft, false);
            inputOverrideHandler.getClass().getMethod("setInputForceState", inputClass, boolean.class).invoke(inputOverrideHandler, clickRight, false);
        } catch (Throwable throwable) {
            if (!warnedSettingsAccessFailure) {
                warnedSettingsAccessFailure = true;
                SleepyAddon.LOG.warn("Failed to release Baritone click inputs", throwable);
            }
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
