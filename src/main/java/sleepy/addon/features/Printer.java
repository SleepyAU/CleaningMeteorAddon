package sleepy.addon.features;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import fi.dy.masa.malilib.util.IntBoundingBox;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import java.util.Map;
import net.minecraft.util.math.Vec3d;
import sleepy.addon.SleepyAddon;
import sleepy.addon.events.UpdateEvent;
import sleepy.addon.manager.PlacementManager;
import sleepy.addon.util.RangeUtil;

public class Printer extends Module {
    private static final double MAX_RANGE = 4.5;
    private static final double MINE_RANGE = RangeUtil.MINE_RANGE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

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

    private final Setting<MineMode> mineMode = sgGeneral.add(new EnumSetting.Builder<MineMode>()
        .name("mine-mode")
        .description("Target selection mode for mining.")
        .defaultValue(MineMode.FurthestUp)
        .build()
    );

    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();

    public Printer() {
        super(SleepyAddon.CATEGORY, "printer", "Simple Litematica printer.");
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

        SilentMine silentMine = Modules.get().get(SilentMine.class);
        if (silentMine != null) {
            silentMine.setAllowRebreakLoop(!(place.get() && mine.get()));
        }

        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (placement == null || schematicWorld == null) return;

        if (place.get()) {
            BlockPos target = findClosestPlacement(schematicWorld);
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
                    BlockPos mineTarget = findMineTarget(placement, schematicWorld, mineMode.get(), silentMine);
                    if (mineTarget != null) {
                        silentMine.silentBreakBlock(mineTarget, 100.0);
                    }
                }
            }
        }
    }

    private BlockPos findClosestPlacement(WorldSchematic schematicWorld) {
        if (mc.player == null || mc.world == null) return null;

        Vec3d eye = mc.player.getEyePos();
        double bestDist = Double.POSITIVE_INFINITY;
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

                    BlockState schematicState = schematicWorld.getBlockState(scanPos);
                    if (schematicState == null || schematicState.isAir()) continue;

                    BlockState worldState = mc.world.getBlockState(scanPos);
                    if (!worldState.isAir() && !worldState.isReplaceable()) continue;
                    if (worldState.equals(schematicState)) continue;
                    if (PlacementManager.get().isOnCooldown(scanPos)) continue;

                    double dist = new Box(scanPos).squaredMagnitude(eye);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = scanPos.toImmutable();
                    }
                }
            }
        }

        return best;
    }

    private BlockPos findMineTarget(SchematicPlacement placement, WorldSchematic schematicWorld, MineMode mode, SilentMine silentMine) {
        if (mc.player == null || mc.world == null || schematicWorld == null) return null;

        Vec3d eye = mc.player.getEyePos();
        BlockPos best = null;
        double bestDist = (mode == MineMode.Closest) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
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
                    if (!BlockUtils.canBreak(scanPos, worldState)) continue;
                    if (silentMine != null && silentMine.alreadyBreaking(scanPos)) continue;

                    BlockState schematicState = schematicWorld.getBlockState(scanPos);
                    boolean schematicAir = schematicState == null || schematicState.isAir();
                    boolean wrongBlock = mineWrong.get() && !schematicAir && !worldState.equals(schematicState);
                    boolean extraBlock = mineExtra.get() && schematicAir;
                    if (!wrongBlock && !extraBlock) continue;

                    double dist = RangeUtil.distanceSqToBox(eye, new Box(scanPos));
                    int y = scanPos.getY();

                    if (mode == MineMode.Closest) {
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = scanPos.toImmutable();
                        }
                    } else if (mode == MineMode.Furthest) {
                        if (dist > bestDist) {
                            bestDist = dist;
                            best = scanPos.toImmutable();
                        }
                    } else if (mode == MineMode.FurthestUp) {
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

    private boolean withinRange(BlockPos pos, Vec3d eye) {
        double maxSq = MAX_RANGE * MAX_RANGE;
        return new Box(pos).squaredMagnitude(eye) <= maxSq;
    }

    private int findSlotForBlock(BlockState state) {
        if (mc.player == null || state == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() == state.getBlock()) return i;
        }
        return -1;
    }

    private enum MineMode {
        Closest,
        Furthest,
        FurthestUp
    }
}
