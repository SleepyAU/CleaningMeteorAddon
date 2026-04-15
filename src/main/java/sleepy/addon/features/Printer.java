package sleepy.addon.features;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BlockSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import fi.dy.masa.malilib.util.IntBoundingBox;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import java.util.List;
import java.util.Map;
import net.minecraft.util.math.Vec3d;
import sleepy.addon.SleepyAddon;
import sleepy.addon.events.UpdateEvent;
import sleepy.addon.manager.PlacementManager;
import sleepy.addon.util.BaritoneSelectionHelper;
import sleepy.addon.util.RangeUtil;

public class Printer extends Module {
    private static final double MAX_RANGE = 4.5;
    private static final double MINE_RANGE = RangeUtil.MINE_RANGE;
    private static final long PATH_REFRESH_MS = 750L;
    private static final long PATH_STUCK_MS = 2500L;
    private static final long MAX_BARITONE_SCAN_VOLUME = 750_000L;
    private static final int[] PATH_Y_OFFSETS = {0, 1, 2, 3, 4};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBaritone = settings.createGroup("Baritone");

    private final Setting<PrinterMode> mode = sgGeneral.add(new EnumSetting.Builder<PrinterMode>()
        .name("mode")
        .description("Use a Litematica schematic or a Baritone selection fill command as the target source.")
        .defaultValue(PrinterMode.Litematica)
        .visible(BaritoneSelectionHelper::isBaritoneAvailable)
        .onChanged(value -> {
            if (value != PrinterMode.Baritone) stopBaritoneFill();
        })
        .build()
    );

    private final Setting<Boolean> place = sgGeneral.add(new BoolSetting.Builder()
        .name("place")
        .description("Enable placement logic.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mine = sgGeneral.add(new BoolSetting.Builder()
        .name("mine")
        .description("Enable mining logic.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mineWrong = sgGeneral.add(new BoolSetting.Builder()
        .name("wrong-block")
        .description("Mine blocks that don't match the schematic.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mineExtra = sgGeneral.add(new BoolSetting.Builder()
        .name("extra-block")
        .description("Mine extra blocks that should be air in the schematic.")
        .defaultValue(true)
        .build()
    );

    private final Setting<TargetMode> targetMode = sgGeneral.add(new EnumSetting.Builder<TargetMode>()
        .name("target-mode")
        .description("Target selection mode for mining and placing.")
        .defaultValue(TargetMode.FurthestUp)
        .build()
    );

    private final Setting<PlaceSelection> placeSelection = sgGeneral.add(new EnumSetting.Builder<PlaceSelection>()
        .name("place-selection")
        .description("Restricts placements to below or above your feet.")
        .defaultValue(PlaceSelection.All)
        .build()
    );

    private final Setting<Block> baritoneFillBlock = sgBaritone.add(new BlockSetting.Builder()
        .name("fill-block")
        .description("Block used by Baritone mode Start.")
        .defaultValue(Blocks.OBSIDIAN)
        .visible(() -> mode.get() == PrinterMode.Baritone && BaritoneSelectionHelper.isBaritoneAvailable())
        .build()
    );

    private final Setting<Keybind> startKeybind = sgBaritone.add(new KeybindSetting.Builder()
        .name("start-keybind")
        .description("Starts Printer Baritone fill.")
        .visible(() -> mode.get() == PrinterMode.Baritone && BaritoneSelectionHelper.isBaritoneAvailable())
        .action(this::startBaritoneFill)
        .build()
    );

    private final Setting<Keybind> stopKeybind = sgBaritone.add(new KeybindSetting.Builder()
        .name("stop-keybind")
        .description("Stops Printer Baritone fill.")
        .visible(() -> mode.get() == PrinterMode.Baritone && BaritoneSelectionHelper.isBaritoneAvailable())
        .action(this::stopBaritoneFill)
        .build()
    );

    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();
    private Block baritoneBlock = Blocks.OBSIDIAN;
    private boolean baritoneFillActive;
    private BlockPos lastWorkTarget;
    private BlockPos lastPathTarget;
    private long lastPathRefreshMs;
    private long lastPathAttemptMs;
    private long lastPathProgressMs;
    private double lastPathBestDistSq = Double.POSITIVE_INFINITY;
    private long lastWarnMs;
    private boolean baritonePathScanPending;
    private boolean anchoredAtPathTarget;
    private boolean lastPathExact;
    private int pathYOffsetIndex;

    public Printer() {
        super(SleepyAddon.CATEGORY, "printer", "Simple Litematica printer.");
    }

    @Override
    public void onActivate() {
        if (mode.get() == PrinterMode.Baritone && !BaritoneSelectionHelper.isBaritoneAvailable()) {
            mode.set(PrinterMode.Litematica);
        }
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        if (mode.get() != PrinterMode.Baritone || !BaritoneSelectionHelper.isBaritoneAvailable()) return null;

        WVerticalList list = theme.verticalList();
        WHorizontalList buttons = list.add(theme.horizontalList()).expandX().widget();

        WButton start = buttons.add(theme.button("Start")).expandCellX().widget();
        start.action = this::startBaritoneFill;

        WButton stop = buttons.add(theme.button("Stop")).expandCellX().widget();
        stop.action = this::stopBaritoneFill;

        return list;
    }

    @Override
    public void onDeactivate() {
        SilentMine silentMine = Modules.get().get(SilentMine.class);
        if (silentMine != null) {
            silentMine.setAllowRebreakLoop(true);
        }

        stopBaritoneFill();
    }

    @EventHandler
    private void onTick(UpdateEvent event) {
        if (mc.player == null || mc.world == null) return;

        SilentMine silentMine = Modules.get().get(SilentMine.class);
        if (silentMine != null) {
            silentMine.setAllowRebreakLoop(!(place.get() && mine.get()));
        }

        if (mode.get() == PrinterMode.Baritone && BaritoneSelectionHelper.isBaritoneAvailable()) {
            onBaritoneTick(silentMine);
            return;
        }

        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (placement == null || schematicWorld == null) return;

        if (place.get()) {
            BlockPos target = findPlacementTarget(schematicWorld, targetMode.get(), placeSelection.get());
            if (target != null) {
                BlockState targetState = schematicWorld.getBlockState(target);
                if (targetState != null && !targetState.isAir() && findSlotForBlock(targetState) != -1) {
                    PlacementManager.get().tryPlace(target, targetState.getBlock());
                }
            }
        }

        if (mine.get()) {
            if (!mineWrong.get() && !mineExtra.get()) return;
            if (silentMine != null && silentMine.isActive()) {
                if (!silentMine.hasRebreakBlock()) {
                    BlockPos mineTarget = findMineTarget(placement, schematicWorld, targetMode.get(), silentMine);
                    if (mineTarget != null) {
                        silentMine.silentBreakBlock(mineTarget, 100.0);
                    }
                }
            }
        }
    }

    private void startBaritoneFill() {
        if (!isActive()) {
            warning("Printer must be active to start Baritone fill.");
            return;
        }
        if (mode.get() != PrinterMode.Baritone || !BaritoneSelectionHelper.isBaritoneAvailable()) return;

        Block block = baritoneFillBlock.get();
        if (block == null || block == Blocks.AIR) {
            warning("Printer Baritone mode: fill block must be a real block.");
            return;
        }

        if (BaritoneSelectionHelper.getSelectionBounds().isEmpty()) {
            warning("Printer Baritone mode: no Baritone selection found.");
            return;
        }

        baritoneBlock = block;
        baritoneFillActive = true;
        clearCommittedBaritoneTarget();
        baritonePathScanPending = false;
        BaritoneSelectionHelper.enforcePathingOnly();
        BaritoneSelectionHelper.cancelPathing();

        Identifier id = Registries.BLOCK.getId(block);
        info("Using Baritone selection fill target: %s.", id);
    }

    private void stopBaritoneFill() {
        if (!baritoneFillActive && lastWorkTarget == null && lastPathTarget == null) {
            BaritoneSelectionHelper.restoreBuilderActions();
            return;
        }

        baritoneFillActive = false;
        clearCommittedBaritoneTarget();
        baritonePathScanPending = false;
        BaritoneSelectionHelper.cancelPathing();
        BaritoneSelectionHelper.restoreBuilderActions();
    }

    private void onBaritoneTick(SilentMine silentMine) {
        if (!baritoneFillActive) return;

        List<BaritoneSelectionHelper.SelectionBounds> selectionBounds = BaritoneSelectionHelper.getSelectionBounds();
        if (selectionBounds.isEmpty()) {
            throttleWarn("Printer Baritone mode: no Baritone selection found.");
            return;
        }

        Block targetBlock = baritoneBlock;
        if (targetBlock == null) return;

        BaritoneSelectionHelper.enforcePathingOnly();

        if (anchoredAtPathTarget) {
            BaritoneSelectionHelper.cancelPathing();
            if (runAnchoredBaritoneWork(selectionBounds, targetBlock, silentMine)) return;

            clearCommittedBaritoneTarget();
        }

        if (lastWorkTarget != null) {
            if (hasArrivedAtPathTarget()) {
                anchoredAtPathTarget = true;
                BaritoneSelectionHelper.cancelPathing();
                if (runAnchoredBaritoneWork(selectionBounds, targetBlock, silentMine)) return;

                clearCommittedBaritoneTarget();
            } else {
                if (shouldTryHigherPathTarget()) {
                    advancePathYOffset();
                }

                pathToCommittedTarget(lastWorkTarget);
                return;
            }
        }

        BlockPos pathTarget = findBaritonePathTarget(selectionBounds, targetBlock, silentMine);
        if (pathTarget == null) {
            if (baritonePathScanPending) return;
            info("Baritone selection fill complete.");
            stopBaritoneFill();
            return;
        }

        pathToCommittedTarget(pathTarget);
    }

    private boolean runAnchoredBaritoneWork(List<BaritoneSelectionHelper.SelectionBounds> selectionBounds,
                                            Block targetBlock,
                                            SilentMine silentMine) {
        BlockPos localPlaceTarget = null;
        BlockPos localMineTarget = null;

        if (place.get() && targetBlock != Blocks.AIR) {
            BlockState targetState = targetBlock.getDefaultState();
            if (findSlotForBlock(targetState) != -1) {
                BlockPos target = findBaritonePlacementTarget(selectionBounds, targetState, TargetMode.Closest, placeSelection.get());
                if (target != null) {
                    localPlaceTarget = target;
                    PlacementManager.get().tryPlace(target, targetBlock);
                }
            }
        }

        if (mine.get()) {
            if (silentMine != null && silentMine.isActive() && !silentMine.hasRebreakBlock()) {
                BlockPos mineTarget = findBaritoneMineTarget(selectionBounds, targetBlock, TargetMode.Closest, silentMine);
                if (mineTarget != null) {
                    localMineTarget = mineTarget;
                    silentMine.silentBreakBlockQuiet(mineTarget, 100.0);
                }
            }
        }

        return localPlaceTarget != null
            || localMineTarget != null
            || hasSilentMineWork(silentMine)
            || hasReachableBaritoneWork(selectionBounds, targetBlock, silentMine);
    }

    private void pathToCommittedTarget(BlockPos pathTarget) {
        if (pathTarget == null || mc.player == null) return;

        if (!pathTarget.equals(lastWorkTarget)) {
            lastWorkTarget = pathTarget;
            pathYOffsetIndex = findInitialPathYOffsetIndex(pathTarget);
            lastPathTarget = null;
            lastPathExact = false;
            lastPathAttemptMs = 0L;
            lastPathProgressMs = 0L;
            lastPathBestDistSq = Double.POSITIVE_INFINITY;
        }

        MovementTarget movementTarget = getMovementTargetForWorkTarget(pathTarget);
        if (hasArrivedAtMovementTarget(movementTarget)) {
            lastPathTarget = movementTarget.pos();
            lastPathExact = movementTarget.exact();
            lastPathRefreshMs = 0L;
            anchoredAtPathTarget = true;
            BaritoneSelectionHelper.cancelPathing();
            return;
        }

        long now = System.currentTimeMillis();
        if (!movementTarget.pos().equals(lastPathTarget) || movementTarget.exact() != lastPathExact
            || now - lastPathRefreshMs >= PATH_REFRESH_MS) {
            if (BaritoneSelectionHelper.pathTo(movementTarget.pos(), movementTarget.exact())) {
                if (!movementTarget.pos().equals(lastPathTarget) || movementTarget.exact() != lastPathExact) {
                    lastPathAttemptMs = now;
                    lastPathProgressMs = now;
                    lastPathBestDistSq = distanceSqToPathTarget(movementTarget.pos());
                }

                lastPathTarget = movementTarget.pos();
                lastPathExact = movementTarget.exact();
                lastPathRefreshMs = now;
                anchoredAtPathTarget = false;
            }
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!baritoneFillActive || mode.get() != PrinterMode.Baritone) return;
        if (isFromPrinterAction()) return;

        if (event.packet instanceof PlayerInteractBlockC2SPacket || event.packet instanceof PlayerInteractItemC2SPacket) {
            event.cancel();
            return;
        }

        if (event.packet instanceof PlayerActionC2SPacket packet && isDestroyAction(packet.getAction())) {
            event.cancel();
        }
    }

    private boolean isDestroyAction(PlayerActionC2SPacket.Action action) {
        return action == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK
            || action == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
            || action == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK;
    }

    private boolean isFromPrinterAction() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String className = frame.getClassName();
            if ("sleepy.addon.manager.PlacementManager".equals(className)
                || "sleepy.addon.features.SilentMine".equals(className)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasArrivedAtPathTarget() {
        if (mc.player == null || lastPathTarget == null) return false;
        if (lastPathExact) return mc.player.getBlockPos().equals(lastPathTarget);

        double dx = mc.player.getX() - (lastPathTarget.getX() + 0.5);
        double dz = mc.player.getZ() - (lastPathTarget.getZ() + 0.5);
        double dy = Math.abs(mc.player.getY() - lastPathTarget.getY());
        return dx * dx + dz * dz <= 2.25 && dy <= 2.5;
    }

    private boolean hasArrivedAtMovementTarget(MovementTarget movementTarget) {
        if (mc.player == null || movementTarget == null || movementTarget.pos() == null) return false;
        if (movementTarget.exact()) return mc.player.getBlockPos().equals(movementTarget.pos());

        double dx = mc.player.getX() - (movementTarget.pos().getX() + 0.5);
        double dz = mc.player.getZ() - (movementTarget.pos().getZ() + 0.5);
        double dy = Math.abs(mc.player.getY() - movementTarget.pos().getY());
        return dx * dx + dz * dz <= 2.25 && dy <= 2.5;
    }

    private boolean shouldTryHigherPathTarget() {
        if (mc.player == null || lastWorkTarget == null || lastPathTarget == null) return false;
        if (pathYOffsetIndex >= PATH_Y_OFFSETS.length - 1) return false;

        long now = System.currentTimeMillis();
        if (lastPathAttemptMs == 0L || now - lastPathAttemptMs < PATH_STUCK_MS) return false;

        double distSq = distanceSqToPathTarget(lastPathTarget);
        if (distSq + 0.25 < lastPathBestDistSq) {
            lastPathBestDistSq = distSq;
            lastPathProgressMs = now;
            return false;
        }

        if (!BaritoneSelectionHelper.isPathing()) return true;
        return lastPathProgressMs != 0L && now - lastPathProgressMs >= PATH_STUCK_MS;
    }

    private void advancePathYOffset() {
        if (pathYOffsetIndex >= PATH_Y_OFFSETS.length - 1) return;

        pathYOffsetIndex++;
        lastPathTarget = null;
        lastPathExact = false;
        lastPathRefreshMs = 0L;
        lastPathAttemptMs = 0L;
        lastPathProgressMs = 0L;
        lastPathBestDistSq = Double.POSITIVE_INFINITY;
        BaritoneSelectionHelper.cancelPathing();
    }

    private MovementTarget getMovementTargetForWorkTarget(BlockPos workTarget) {
        if (isPlaceWorkTarget(workTarget)) {
            BlockPos standTarget = findPlacementStandTarget(workTarget);
            if (standTarget != null) return new MovementTarget(standTarget, true);
        }

        int yOffset = PATH_Y_OFFSETS[Math.max(0, Math.min(pathYOffsetIndex, PATH_Y_OFFSETS.length - 1))];
        return new MovementTarget(yOffset == 0 ? workTarget : workTarget.up(yOffset), false);
    }

    private int findInitialPathYOffsetIndex(BlockPos workTarget) {
        if (workTarget == null || mc.world == null) return 0;

        for (int i = 0; i < PATH_Y_OFFSETS.length; i++) {
            if (!isMovementLevelBlocked(workTarget, PATH_Y_OFFSETS[i])) return i;
        }

        return PATH_Y_OFFSETS.length - 1;
    }

    private boolean isMovementLevelBlocked(BlockPos workTarget, int yOffset) {
        int y = workTarget.getY() + yOffset;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                scanPos.set(workTarget.getX() + dx, y, workTarget.getZ() + dz);
                if (hasBodyClearance(scanPos)) return false;
            }
        }

        return true;
    }

    private boolean hasBodyClearance(BlockPos pos) {
        return isPathPassable(pos) && isPathPassable(pos.up());
    }

    private boolean isPlaceWorkTarget(BlockPos workTarget) {
        if (mc.world == null || workTarget == null || baritoneBlock == null) return false;
        return isBaritonePlaceTarget(mc.world.getBlockState(workTarget), baritoneBlock);
    }

    private BlockPos findPlacementStandTarget(BlockPos workTarget) {
        if (mc.player == null || mc.world == null || workTarget == null) return null;

        int r = (int) Math.ceil(MAX_RANGE);
        double eyeOffset = mc.player.getEyeY() - mc.player.getY();
        Vec3d playerEye = mc.player.getEyePos();
        BlockPos best = null;
        double bestPlayerDist = Double.POSITIVE_INFINITY;
        double bestTargetDist = Double.POSITIVE_INFINITY;

        for (int dy = -2; dy <= 4; dy++) {
            int y = workTarget.getY() + dy;

            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    scanPos.set(workTarget.getX() + dx, y, workTarget.getZ() + dz);
                    if (scanPos.equals(workTarget)) continue;
                    if (!isStandableFeetPos(scanPos)) continue;
                    if (scanPos.equals(mc.player.getBlockPos())
                        && !PlacementManager.get().checkPlacement(workTarget, baritoneBlock).placeable()) continue;

                    Vec3d standEye = new Vec3d(scanPos.getX() + 0.5, scanPos.getY() + eyeOffset, scanPos.getZ() + 0.5);
                    if (!RangeUtil.withinPlaceRange(standEye, workTarget)) continue;

                    double playerDist = RangeUtil.distanceSqToBox(playerEye, new Box(scanPos));
                    double targetDist = RangeUtil.distanceSqToBox(standEye, new Box(workTarget));
                    if (best == null || playerDist < bestPlayerDist
                        || (playerDist == bestPlayerDist && targetDist < bestTargetDist)) {
                        best = scanPos.toImmutable();
                        bestPlayerDist = playerDist;
                        bestTargetDist = targetDist;
                    }
                }
            }
        }

        return best;
    }

    private boolean isStandableFeetPos(BlockPos feetPos) {
        return hasBodyClearance(feetPos) && isStandableFloor(feetPos.down());
    }

    private boolean isStandableFloor(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        BlockState state = mc.world.getBlockState(pos);
        return state != null && !state.getCollisionShape(mc.world, pos).isEmpty();
    }

    private boolean isPathPassable(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        BlockState state = mc.world.getBlockState(pos);
        return state == null || state.getCollisionShape(mc.world, pos).isEmpty();
    }

    private double distanceSqToPathTarget(BlockPos pathTarget) {
        if (mc.player == null || pathTarget == null) return Double.POSITIVE_INFINITY;

        double dx = mc.player.getX() - (pathTarget.getX() + 0.5);
        double dy = mc.player.getY() - pathTarget.getY();
        double dz = mc.player.getZ() - (pathTarget.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private void clearCommittedBaritoneTarget() {
        lastWorkTarget = null;
        lastPathTarget = null;
        lastPathExact = false;
        lastPathRefreshMs = 0L;
        lastPathAttemptMs = 0L;
        lastPathProgressMs = 0L;
        lastPathBestDistSq = Double.POSITIVE_INFINITY;
        anchoredAtPathTarget = false;
        pathYOffsetIndex = 0;
    }

    private boolean hasSilentMineWork(SilentMine silentMine) {
        return silentMine != null && (silentMine.hasRebreakBlock() || silentMine.hasDelayedDestroy());
    }

    private BlockPos findBaritonePlacementTarget(List<BaritoneSelectionHelper.SelectionBounds> selectionBounds,
                                                 BlockState targetState,
                                                 TargetMode mode,
                                                 PlaceSelection selection) {
        return findBaritonePlacementTarget(selectionBounds, targetState, mode, selection, true);
    }

    private BlockPos findBaritonePlacementTarget(List<BaritoneSelectionHelper.SelectionBounds> selectionBounds,
                                                 BlockState targetState,
                                                 TargetMode mode,
                                                 PlaceSelection selection,
                                                 boolean respectCooldown) {
        if (mc.player == null || mc.world == null || targetState == null || targetState.isAir()) return null;

        Vec3d eye = mc.player.getEyePos();
        double bestDist = (mode == TargetMode.Closest) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        int bestY = Integer.MIN_VALUE;
        BlockPos best = null;

        int r = (int) Math.ceil(MAX_RANGE);
        int baseX = mc.player.getBlockPos().getX();
        int baseY = mc.player.getBlockPos().getY();
        int baseZ = mc.player.getBlockPos().getZ();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    scanPos.set(baseX + dx, baseY + dy, baseZ + dz);
                    if (!withinRange(scanPos, eye)) continue;
                    if (!matchesPlaceSelection(selection, baseY, scanPos.getY())) continue;
                    if (!isWithinBaritoneSelection(scanPos, selectionBounds)) continue;

                    BlockState worldState = mc.world.getBlockState(scanPos);
                    if (worldState.isOf(targetState.getBlock())) continue;
                    if (!worldState.isAir() && !worldState.isReplaceable()) continue;
                    if (respectCooldown && PlacementManager.get().isOnCooldown(scanPos)) continue;
                    if (!PlacementManager.get().checkPlacement(scanPos, targetState.getBlock()).placeable()) continue;

                    double dist = new Box(scanPos).squaredMagnitude(eye);
                    if (isBetterCandidate(scanPos, dist, best, bestDist, bestY, mode)) {
                        bestDist = dist;
                        bestY = scanPos.getY();
                        best = scanPos.toImmutable();
                    }
                }
            }
        }

        return best;
    }

    private boolean hasReachableBaritoneWork(List<BaritoneSelectionHelper.SelectionBounds> selectionBounds,
                                             Block targetBlock,
                                             SilentMine silentMine) {
        if (targetBlock == null) return false;

        if (place.get() && targetBlock != Blocks.AIR) {
            BlockState targetState = targetBlock.getDefaultState();
            if (findSlotForBlock(targetState) != -1
                && findBaritonePlacementTarget(selectionBounds, targetState, TargetMode.Closest, placeSelection.get(), false) != null) {
                return true;
            }
        }

        if (mine.get() && silentMine != null && silentMine.isActive()
            && findBaritoneMineTarget(selectionBounds, targetBlock, TargetMode.Closest, silentMine) != null) {
            return true;
        }

        return false;
    }

    private BlockPos findBaritoneMineTarget(List<BaritoneSelectionHelper.SelectionBounds> selectionBounds,
                                            Block targetBlock,
                                            TargetMode mode,
                                            SilentMine silentMine) {
        if (mc.player == null || mc.world == null || targetBlock == null) return null;
        if (!shouldMineBaritoneTarget(targetBlock)) return null;

        Vec3d eye = mc.player.getEyePos();
        BlockPos best = null;
        double bestDist = (mode == TargetMode.Closest) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        int bestY = Integer.MIN_VALUE;

        int r = (int) Math.ceil(MINE_RANGE);
        int baseX = mc.player.getBlockPos().getX();
        int baseY = mc.player.getBlockPos().getY();
        int baseZ = mc.player.getBlockPos().getZ();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    scanPos.set(baseX + dx, baseY + dy, baseZ + dz);
                    if (!RangeUtil.withinMineRange(eye, scanPos)) continue;
                    if (!isWithinBaritoneSelection(scanPos, selectionBounds)) continue;

                    BlockState worldState = mc.world.getBlockState(scanPos);
                    if (!isBaritoneMineTarget(scanPos, worldState, targetBlock, silentMine)) continue;

                    double dist = RangeUtil.distanceSqToBox(eye, new Box(scanPos));
                    if (isBetterCandidate(scanPos, dist, best, bestDist, bestY, mode)) {
                        bestDist = dist;
                        bestY = scanPos.getY();
                        best = scanPos.toImmutable();
                    }
                }
            }
        }

        return best;
    }

    private BlockPos findBaritonePathTarget(List<BaritoneSelectionHelper.SelectionBounds> selectionBounds,
                                            Block targetBlock,
                                            SilentMine silentMine) {
        if (mc.player == null || mc.world == null || targetBlock == null) return null;

        boolean canPathToPlace = place.get() && targetBlock != Blocks.AIR && findSlotForBlock(targetBlock.getDefaultState()) != -1;
        boolean canPathToMine = mine.get() && silentMine != null && silentMine.isActive() && shouldMineBaritoneTarget(targetBlock);
        baritonePathScanPending = false;
        if (!canPathToPlace && !canPathToMine) {
            baritonePathScanPending = true;
            return null;
        }

        if (lastWorkTarget != null && isWithinBaritoneSelection(lastWorkTarget, selectionBounds)) {
            BlockState lastState = mc.world.getBlockState(lastWorkTarget);
            if (isBaritonePathTarget(lastWorkTarget, lastState, targetBlock, silentMine, canPathToMine, canPathToPlace)) {
                return lastWorkTarget;
            }
        }

        long volume = totalSelectionVolume(selectionBounds);
        if (volume > MAX_BARITONE_SCAN_VOLUME) {
            throttleWarn("Printer Baritone mode: selection is too large to scan for path targets.");
            baritonePathScanPending = true;
            return lastWorkTarget;
        }

        Vec3d eye = mc.player.getEyePos();
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;

        for (BaritoneSelectionHelper.SelectionBounds bounds : selectionBounds) {
            if (bounds == null) continue;

            for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++) {
                for (int y = bounds.min().getY(); y <= bounds.max().getY(); y++) {
                    for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++) {
                        scanPos.set(x, y, z);
                        BlockState worldState = mc.world.getBlockState(scanPos);

                        if (!isBaritonePathTarget(scanPos, worldState, targetBlock, silentMine, canPathToMine, canPathToPlace)) continue;

                        double dist = RangeUtil.distanceSqToBox(eye, new Box(scanPos));
                        if (best == null || dist < bestDist) {
                            bestDist = dist;
                            best = scanPos.toImmutable();
                        }
                    }
                }
            }
        }

        return best;
    }

    private boolean isBaritonePathTarget(BlockPos pos, BlockState worldState, Block targetBlock,
                                         SilentMine silentMine, boolean canPathToMine, boolean canPathToPlace) {
        if (canPathToMine && isBaritoneMineTarget(pos, worldState, targetBlock, silentMine)) return true;
        return canPathToPlace && isBaritonePlaceTarget(worldState, targetBlock);
    }

    private BlockPos findPlacementTarget(WorldSchematic schematicWorld, TargetMode mode, PlaceSelection selection) {
        if (mc.player == null || mc.world == null) return null;

        Vec3d eye = mc.player.getEyePos();
        double bestDist = (mode == TargetMode.Closest) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        int bestY = Integer.MIN_VALUE;
        BlockPos best = null;

        int r = (int) Math.ceil(MAX_RANGE);
        int baseX = mc.player.getBlockPos().getX();
        int baseY = mc.player.getBlockPos().getY();
        int baseZ = mc.player.getBlockPos().getZ();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    scanPos.set(baseX + dx, baseY + dy, baseZ + dz);
                    if (!withinRange(scanPos, eye)) continue;
                    if (!matchesPlaceSelection(selection, baseY, scanPos.getY())) continue;

                    BlockState schematicState = schematicWorld.getBlockState(scanPos);
                    if (schematicState == null || schematicState.isAir()) continue;

                    BlockState worldState = mc.world.getBlockState(scanPos);
                    if (!worldState.isAir() && !worldState.isReplaceable()) continue;
                    if (worldState.equals(schematicState)) continue;
                    if (PlacementManager.get().isOnCooldown(scanPos)) continue;

                    double dist = new Box(scanPos).squaredMagnitude(eye);
                    int y = scanPos.getY();

                    if (mode == TargetMode.Closest) {
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = scanPos.toImmutable();
                        }
                    } else if (mode == TargetMode.Furthest) {
                        if (dist > bestDist) {
                            bestDist = dist;
                            best = scanPos.toImmutable();
                        }
                    } else if (mode == TargetMode.FurthestUp) {
                        if (y > bestY || (y == bestY && dist > bestDist)) {
                            bestDist = dist;
                            bestY = y;
                            best = scanPos.toImmutable();
                        }
                    }
                }
            }
        }

        return best;
    }

    private BlockPos findMineTarget(SchematicPlacement placement, WorldSchematic schematicWorld, TargetMode mode, SilentMine silentMine) {
        if (mc.player == null || mc.world == null || schematicWorld == null) return null;

        Vec3d eye = mc.player.getEyePos();
        BlockPos best = null;
        double bestDist = (mode == TargetMode.Closest) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        int bestY = Integer.MIN_VALUE;

        int r = (int) Math.ceil(MINE_RANGE);
        int baseX = mc.player.getBlockPos().getX();
        int baseY = mc.player.getBlockPos().getY();
        int baseZ = mc.player.getBlockPos().getZ();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    scanPos.set(baseX + dx, baseY + dy, baseZ + dz);
                    if (!RangeUtil.withinMineRange(eye, scanPos)) continue;
                    if (!isWithinPlacement(placement, scanPos)) continue;

                    BlockState worldState = mc.world.getBlockState(scanPos);
                    if (worldState == null || worldState.isAir()) continue;
                    if (!worldState.getFluidState().isEmpty()) continue;
                    if (!BlockUtils.canBreak(scanPos, worldState)) continue;
                    if (silentMine != null && silentMine.alreadyBreaking(scanPos)) continue;

                    BlockState schematicState = schematicWorld.getBlockState(scanPos);
                    boolean schematicAir = schematicState == null || schematicState.isAir();
                    boolean wrongBlock = mineWrong.get() && !schematicAir && !worldState.equals(schematicState);
                    boolean extraBlock = mineExtra.get() && schematicAir;
                    if (!wrongBlock && !extraBlock) continue;

                    double dist = RangeUtil.distanceSqToBox(eye, new Box(scanPos));
                    int y = scanPos.getY();

                    if (mode == TargetMode.Closest) {
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = scanPos.toImmutable();
                        }
                    } else if (mode == TargetMode.Furthest) {
                        if (dist > bestDist) {
                            bestDist = dist;
                            best = scanPos.toImmutable();
                        }
                    } else if (mode == TargetMode.FurthestUp) {
                        if (y > bestY || (y == bestY && dist > bestDist)) {
                            bestDist = dist;
                            bestY = y;
                            best = scanPos.toImmutable();
                        }
                    }
                }
            }
        }

        return best;
    }

    private boolean isWithinPlacement(SchematicPlacement placement, BlockPos pos) {
        if (placement == null || pos == null) return false;
        ChunkPos chunkPos = new ChunkPos(pos);
        Map<String, IntBoundingBox> boxes = placement.getBoxesWithinChunk(chunkPos.x, chunkPos.z);
        if (boxes == null || boxes.isEmpty()) return false;
        for (IntBoundingBox box : boxes.values()) {
            if (pos.getX() < box.minX || pos.getX() > box.maxX) continue;
            if (pos.getY() < box.minY || pos.getY() > box.maxY) continue;
            if (pos.getZ() < box.minZ || pos.getZ() > box.maxZ) continue;
            return true;
        }
        return false;
    }

    private boolean isWithinBaritoneSelection(BlockPos pos,
                                              List<BaritoneSelectionHelper.SelectionBounds> selectionBounds) {
        if (pos == null || selectionBounds == null || selectionBounds.isEmpty()) return false;
        for (BaritoneSelectionHelper.SelectionBounds bounds : selectionBounds) {
            if (bounds != null && bounds.contains(pos)) return true;
        }
        return false;
    }

    private boolean shouldMineBaritoneTarget(Block targetBlock) {
        if (targetBlock == Blocks.AIR) return mineExtra.get();
        return mineWrong.get();
    }

    private boolean isBaritoneMineTarget(BlockPos pos, BlockState worldState, Block targetBlock, SilentMine silentMine) {
        if (pos == null || worldState == null || targetBlock == null) return false;
        if (worldState.isAir()) return false;
        if (!worldState.getFluidState().isEmpty()) return false;
        if (targetBlock != Blocks.AIR && worldState.isOf(targetBlock)) return false;
        if (!BlockUtils.canBreak(pos, worldState)) return false;
        return silentMine == null || !silentMine.alreadyBreaking(pos);
    }

    private boolean isBaritonePlaceTarget(BlockState worldState, Block targetBlock) {
        if (worldState == null || targetBlock == null || targetBlock == Blocks.AIR) return false;
        if (worldState.isOf(targetBlock)) return false;
        return worldState.isAir() || worldState.isReplaceable();
    }

    private long totalSelectionVolume(List<BaritoneSelectionHelper.SelectionBounds> selectionBounds) {
        if (selectionBounds == null || selectionBounds.isEmpty()) return 0L;

        long total = 0L;
        for (BaritoneSelectionHelper.SelectionBounds bounds : selectionBounds) {
            if (bounds == null) continue;

            long x = (long) bounds.max().getX() - bounds.min().getX() + 1L;
            long y = (long) bounds.max().getY() - bounds.min().getY() + 1L;
            long z = (long) bounds.max().getZ() - bounds.min().getZ() + 1L;
            total += Math.max(0L, x) * Math.max(0L, y) * Math.max(0L, z);
            if (total > MAX_BARITONE_SCAN_VOLUME) return total;
        }
        return total;
    }

    private boolean isBetterCandidate(BlockPos pos, double dist, BlockPos best, double bestDist,
                                      int bestY, TargetMode mode) {
        if (best == null) return true;

        int y = pos.getY();
        if (mode == TargetMode.Closest) return dist < bestDist;
        if (mode == TargetMode.Furthest) return dist > bestDist;
        return y > bestY || (y == bestY && dist > bestDist);
    }

    private void throttleWarn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 1000L) return;
        lastWarnMs = now;
        warning(msg);
    }

    private boolean withinRange(BlockPos pos, Vec3d eye) {
        double maxSq = MAX_RANGE * MAX_RANGE;
        return new Box(pos).squaredMagnitude(eye) <= maxSq;
    }

    private boolean matchesPlaceSelection(PlaceSelection selection, int playerY, int posY) {
        if (selection == PlaceSelection.Below) return posY < playerY;
        if (selection == PlaceSelection.Above) return posY >= playerY;
        return true;
    }

    private int findSlotForBlock(BlockState state) {
        if (mc.player == null || state == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() == state.getBlock()) return i;
        }
        return -1;
    }

    private enum TargetMode {
        Closest,
        Furthest,
        FurthestUp
    }

    private enum PlaceSelection {
        All,
        Below,
        Above
    }

    private enum PrinterMode {
        Litematica,
        Baritone
    }

    private record MovementTarget(BlockPos pos, boolean exact) {
    }
}
