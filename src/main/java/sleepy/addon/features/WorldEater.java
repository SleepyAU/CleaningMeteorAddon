package sleepy.addon.features;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import sleepy.addon.SleepyAddon;
import sleepy.addon.events.UpdateEvent;
import sleepy.addon.manager.PlacementManager;
import sleepy.addon.util.BaritoneSelectionHelper;
import sleepy.addon.util.RangeUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WorldEater extends Module {
    private static final double EDGE_EPS = 1e-4;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Range to mine blocks.")
        .defaultValue(5.0)
        .min(1.0)
        .sliderMax(7.0)
        .build()
    );

    private final Setting<LayerMode> layerMode = sgGeneral.add(new EnumSetting.Builder<LayerMode>()
        .name("mode")
        .description("Flat = feet Y and above, All = any Y, Below = under feet Y only.")
        .defaultValue(LayerMode.Flat)
        .build()
    );

    private final Setting<OrderMode> orderMode = sgGeneral.add(new EnumSetting.Builder<OrderMode>()
        .name("order")
        .description("Pick which block to mine next.")
        .defaultValue(OrderMode.Closest)
        .build()
    );

    private final Setting<Boolean> baritoneArea = sgGeneral.add(new BoolSetting.Builder()
        .name("baritone-area")
        .description("Only mine blocks inside the current Baritone selection bounds.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> downPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("down-place")
        .description("Place a support block two blocks below your feet when empty.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> downPlaceBlockName = sgGeneral.add(new StringSetting.Builder()
        .name("down-place-block")
        .description("Block ID used by down-place.")
        .defaultValue("minecraft:obsidian")
        .build()
    );

    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();
    private String lastDownPlaceResolved = "";
    private Block resolvedDownPlaceBlock = Blocks.OBSIDIAN;
    private long lastWarnMs;

    public WorldEater() {
        super(SleepyAddon.CATEGORY, "world-eater", "Mine every block in range using SilentMine.");
    }

    @Override
    public void onActivate() {
        resolveDownPlaceBlock(false);
    }

    @Override
    public void onDeactivate() {
        SilentMine silentMine = Modules.get().get(SilentMine.class);
        if (silentMine != null) {
            silentMine.setAllowRebreakLoop(true);
        }
    }

    @EventHandler
    private void onTick(UpdateEvent event) {
        if (mc.player == null || mc.world == null) return;

        placeDownSupportIfNeeded();

        SilentMine silentMine = Modules.get().get(SilentMine.class);
        if (silentMine == null || !silentMine.isActive()) return;

        silentMine.setAllowRebreakLoop(false);

        List<BaritoneSelectionHelper.SelectionBounds> selectionBounds = List.of();
        if (baritoneArea.get()) {
            selectionBounds = BaritoneSelectionHelper.getSelectionBounds();
            if (selectionBounds.isEmpty()) return;
        }

        int available = getAvailableSlots(silentMine);
        if (available <= 0) return;

        Set<BlockPos> attempted = new HashSet<>();
        int maxAttempts = Math.max(available * 4, 4);

        for (int attempts = 0; attempts < maxAttempts && getAvailableSlots(silentMine) > 0; attempts++) {
            BlockPos next = findNextTarget(silentMine, attempted, selectionBounds);
            if (next == null) break;

            attempted.add(next);
            silentMine.silentBreakBlock(next, pickHitDirection(next), 100.0);
        }
    }

    private int getAvailableSlots(SilentMine silentMine) {
        int slots = 0;
        if (!silentMine.hasDelayedDestroy()) slots++;
        if (!silentMine.hasRebreakBlock()) slots++;
        return slots;
    }

    private BlockPos findNextTarget(SilentMine silentMine, Set<BlockPos> exclude,
                                    List<BaritoneSelectionHelper.SelectionBounds> selectionBounds) {
        if (mc.player == null || mc.world == null) return null;

        Vec3d eye = mc.player.getEyePos();
        BlockPos origin = mc.player.getBlockPos();
        int feetY = mc.player.getBlockY();
        double maxRange = range.get();
        double maxRangeSq = maxRange * maxRange;
        int search = MathHelper.ceil(maxRange);

        BlockPos best = null;
        double bestDistSq = 0.0;
        int bestY = Integer.MIN_VALUE;

        for (int dx = -search; dx <= search; dx++) {
            for (int dy = -search; dy <= search; dy++) {
                for (int dz = -search; dz <= search; dz++) {
                    scanPos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!passesLayerFilter(scanPos.getY(), feetY)) continue;
                    if (!selectionBounds.isEmpty() && !isWithinBaritoneSelection(scanPos, selectionBounds)) continue;
                    if (exclude != null && exclude.contains(scanPos)) continue;

                    BlockState state = mc.world.getBlockState(scanPos);
                    if (state == null || state.isAir()) continue;
                    if (!BlockUtils.canBreak(scanPos, state)) continue;
                    if (!silentMine.inBreakRange(scanPos)) continue;

                    double distSq = RangeUtil.distanceSqToBox(eye, new Box(scanPos));
                    if (distSq > maxRangeSq) continue;
                    if (silentMine.alreadyBreaking(scanPos)) continue;

                    if (isBetterCandidate(scanPos, distSq, best, bestDistSq, bestY)) {
                        best = scanPos.toImmutable();
                        bestDistSq = distSq;
                        bestY = scanPos.getY();
                    }
                }
            }
        }

        return best;
    }

    private boolean isWithinBaritoneSelection(BlockPos pos,
                                              List<BaritoneSelectionHelper.SelectionBounds> selectionBounds) {
        if (pos == null || selectionBounds == null || selectionBounds.isEmpty()) return false;
        for (BaritoneSelectionHelper.SelectionBounds bounds : selectionBounds) {
            if (bounds != null && bounds.contains(pos)) return true;
        }
        return false;
    }

    private boolean passesLayerFilter(int y, int feetY) {
        return switch (layerMode.get()) {
            case All -> true;
            case Flat -> y >= feetY;
            case Below -> y < feetY;
        };
    }

    private boolean isBetterCandidate(BlockPos pos, double distSq,
                                      BlockPos best, double bestDistSq, int bestY) {
        int y = pos.getY();
        if (best == null) return true;

        return switch (orderMode.get()) {
            case Closest -> distSq < bestDistSq;
            case Furthest -> distSq > bestDistSq;
            case ClosestUp -> y > bestY || (y == bestY && distSq < bestDistSq);
            case FurthestUp -> y > bestY || (y == bestY && distSq > bestDistSq);
        };
    }

    private Direction pickHitDirection(BlockPos target) {
        if (mc.player == null) return Direction.UP;
        Vec3d eye = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);
        Vec3d delta = center.subtract(eye);
        return Direction.getFacing(delta.x, delta.y, delta.z);
    }

    private void placeDownSupportIfNeeded() {
        if (!downPlace.get() || mc.player == null || mc.world == null) return;

        Block block = resolveDownPlaceBlock(true);
        if (block == null || block == Blocks.AIR) return;

        List<BlockPos> targets = collectDownPlaceTargets();
        if (targets.isEmpty()) return;

        int quota = PlacementManager.get().getRemainingQuota();
        if (quota <= 0) return;
        if (targets.size() > quota) {
            targets = new ArrayList<>(targets.subList(0, quota));
        }

        PlacementManager.get().placeMany(targets, block);
    }

    private List<BlockPos> collectDownPlaceTargets() {
        BlockPos feet = computePrimaryFeetCell();
        BlockPos target = feet.down(2);
        BlockState state = mc.world.getBlockState(target);
        if (!state.isAir() && !state.isReplaceable()) return List.of();
        return List.of(target.toImmutable());
    }

    private BlockPos computePrimaryFeetCell() {
        int y = mc.player.getBlockPos().getY();
        Box bb = mc.player.getBoundingBox().contract(EDGE_EPS, 0.0, EDGE_EPS);

        int x0 = MathHelper.floor(bb.minX);
        int x1 = MathHelper.floor(bb.maxX);
        int z0 = MathHelper.floor(bb.minZ);
        int z1 = MathHelper.floor(bb.maxZ);

        BlockPos best = null;
        double bestArea = -1.0;
        double bestCenterDistSq = Double.POSITIVE_INFINITY;

        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                double ox = Math.min(bb.maxX, cx + 1.0) - Math.max(bb.minX, cx);
                double oz = Math.min(bb.maxZ, cz + 1.0) - Math.max(bb.minZ, cz);
                if (ox <= 0.0 || oz <= 0.0) continue;

                double area = ox * oz;
                double dx = mc.player.getX() - (cx + 0.5);
                double dz = mc.player.getZ() - (cz + 0.5);
                double centerDistSq = dx * dx + dz * dz;

                if (area > bestArea || (Math.abs(area - bestArea) <= 1e-6 && centerDistSq < bestCenterDistSq)) {
                    best = new BlockPos(cx, y, cz);
                    bestArea = area;
                    bestCenterDistSq = centerDistSq;
                }
            }
        }

        return best != null ? best : mc.player.getBlockPos();
    }

    private Block resolveDownPlaceBlock(boolean verbose) {
        String name = downPlaceBlockName.get();
        if (name == null || name.isBlank()) {
            name = "minecraft:obsidian";
            downPlaceBlockName.set(name);
        }

        if (name.equals(lastDownPlaceResolved) && resolvedDownPlaceBlock != null) {
            return resolvedDownPlaceBlock;
        }

        Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (id == null) {
            if (verbose) throttleWarn("WorldEater down-place: invalid block id '" + name + "'.");
            resolvedDownPlaceBlock = Blocks.OBSIDIAN;
            lastDownPlaceResolved = name;
            return resolvedDownPlaceBlock;
        }

        Block lookedUp = lookupBlock(id);
        if (lookedUp == null || lookedUp == Blocks.AIR) {
            if (verbose) throttleWarn("WorldEater down-place: unknown block '" + id + "'.");
            resolvedDownPlaceBlock = Blocks.OBSIDIAN;
        } else {
            resolvedDownPlaceBlock = lookedUp;
            downPlaceBlockName.set(id.toString());
        }

        lastDownPlaceResolved = downPlaceBlockName.get();
        return resolvedDownPlaceBlock;
    }

    private void throttleWarn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 1000L) return;
        lastWarnMs = now;
        warning(msg);
    }

    private Block lookupBlock(Identifier id) {
        Block block = Registries.BLOCK.get(id);
        Identifier registered = Registries.BLOCK.getId(block);
        boolean missing = registered.equals(Registries.BLOCK.getDefaultId()) && !id.equals(Registries.BLOCK.getDefaultId());
        return missing ? null : block;
    }

    public enum LayerMode {
        Flat,
        All,
        Below
    }

    public enum OrderMode {
        Closest,
        ClosestUp,
        Furthest,
        FurthestUp
    }
}
