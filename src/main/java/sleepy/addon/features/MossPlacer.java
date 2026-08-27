package sleepy.addon.features;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import meteordevelopment.meteorclient.systems.modules.Modules;
import sleepy.addon.SleepyAddon;
import sleepy.addon.events.UpdateEvent;
import sleepy.addon.manager.PlacementManager;
import sleepy.addon.util.PlacerQuotaCoordinator;
import sleepy.addon.util.PlacementRenderTrail;
import sleepy.addon.util.RangeUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MossPlacer extends Module {
    private static final int SEARCH_RADIUS = 5;
    private static final int VISIBILITY_PROBE_RADIUS = 4;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> depth = sgGeneral.add(new IntSetting.Builder()
        .name("depth")
        .description("How many moss blocks to stack above each solid block.")
        .defaultValue(1)
        .range(1, 5)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Boolean> sides = sgGeneral.add(new BoolSetting.Builder()
        .name("sides")
        .description("Also places moss into valid air spaces on the four horizontal sides of blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> whitelist = sgGeneral.add(new BoolSetting.Builder()
        .name("whitelist")
        .description("Only places moss on selected base blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Block>> whitelistBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("whitelist-blocks")
        .description("Base blocks MossPlacer is allowed to place on when Whitelist is enabled.")
        .visible(whitelist::get)
        .build()
    );

    private final Setting<Boolean> ignoreSpreadable = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-spreadable")
        .description("Skips blocks in the moss-replaceable tag since bonemeal can spread moss onto them.")
        .defaultValue(true)
        .visible(() -> !whitelist.get())
        .build()
    );

    private final Setting<Boolean> ignoreEnclosed = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-enclosed")
        .description("Skips moss placements inside sealed or very small hidden air pockets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders recent moss placements.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> fadeTime = sgRender.add(new IntSetting.Builder()
        .name("fade-time")
        .description("How long placement renders fade for in milliseconds.")
        .defaultValue(300)
        .range(0, 1000)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill color for placement renders.")
        .defaultValue(new SettingColor(0, 255, 0, 40))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline color for placement renders.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .build()
    );

    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();
    private final PlacementRenderTrail renderTrail = new PlacementRenderTrail();

    public MossPlacer() {
        super(SleepyAddon.CATEGORY, "moss-placer", "Places moss above solid blocks within reach.");
    }

    @Override
    public void onDeactivate() {
        renderTrail.clear();
    }

    @EventHandler
    private void onTick(UpdateEvent event) {
        if (mc.player == null || mc.world == null) return;

        List<BlockPos> targets = collectTargets();
        if (targets.isEmpty()) return;

        MossPlacer self = Modules.get().get(MossPlacer.class);
        GlowBerryPlacer glowBerryPlacer = Modules.get().get(GlowBerryPlacer.class);
        boolean splitWithGlowBerries = self != null && self.isActive()
            && glowBerryPlacer != null && glowBerryPlacer.isActive();
        int budget = PlacerQuotaCoordinator.getBudget(
            mc.player.age,
            PlacementManager.get().getRemainingQuota(),
            PlacerQuotaCoordinator.Kind.Moss,
            splitWithGlowBerries,
            true
        );
        if (budget <= 0) return;
        if (targets.size() > budget) targets = new ArrayList<>(targets.subList(0, budget));

        List<BlockPos> placed = PlacementManager.get().placeMany(targets, Blocks.MOSS_BLOCK);
        if (!placed.isEmpty()) renderTrail.record(placed);
    }

    private List<BlockPos> collectTargets() {
        Vec3d eye = mc.player.getEyePos();
        BlockPos origin = mc.player.getBlockPos();
        Set<BlockPos> targets = new LinkedHashSet<>();

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    scanPos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!isValidBase(scanPos)) continue;

                    for (int offset = 1; offset <= depth.get(); offset++) {
                        BlockPos targetPos = scanPos.up(offset).toImmutable();
                        if (!tryAddTarget(targets, eye, targetPos) && isSolidOccupant(targetPos)) break;
                    }

                    if (!sides.get()) continue;

                    for (Direction direction : Direction.Type.HORIZONTAL) {
                        BlockPos targetPos = scanPos.offset(direction).toImmutable();
                        tryAddTarget(targets, eye, targetPos);
                    }
                }
            }
        }

        List<BlockPos> sorted = new ArrayList<>(targets);
        sorted.sort(Comparator.comparingDouble(pos -> RangeUtil.distanceSqToBox(eye, new Box(pos))));
        return sorted;
    }

    private boolean isValidBase(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir() || state.isOf(Blocks.MOSS_BLOCK) || state.isReplaceable()) return false;
        if (whitelist.get()) return whitelistBlocks.get().contains(state.getBlock());
        if (ignoreSpreadable.get() && state.isIn(BlockTags.MOSS_REPLACEABLE)) return false;
        return !state.getCollisionShape(mc.world, pos).isEmpty();
    }

    private boolean tryAddTarget(Set<BlockPos> targets, Vec3d eye, BlockPos targetPos) {
        if (targets.contains(targetPos)) return true;
        if (!RangeUtil.withinPlaceRange(eye, targetPos)) return false;

        BlockState targetState = mc.world.getBlockState(targetPos);
        if (targetState.isOf(Blocks.MOSS_BLOCK)) return false;
        if (ignoreEnclosed.get() && isHiddenPlacement(targetPos)) return false;

        PlacementManager.PlacementCheck check = PlacementManager.get().checkPlacement(targetPos, Blocks.MOSS_BLOCK);
        if (!check.placeable()) return false;

        targets.add(targetPos);
        return true;
    }

    private boolean isHiddenPlacement(BlockPos targetPos) {
        if (isFullyEnclosed(targetPos)) return true;
        return isSmallSealedAirPocket(targetPos);
    }

    private boolean isFullyEnclosed(BlockPos targetPos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = targetPos.offset(direction);
            if (!blocksVisibility(neighbor, direction.getOpposite())) return false;
        }

        return true;
    }

    private boolean isSmallSealedAirPocket(BlockPos targetPos) {
        if (!isVisibilitySpace(targetPos)) return false;

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();

        BlockPos start = targetPos.toImmutable();
        visited.add(start);
        open.add(start);

        while (!open.isEmpty()) {
            BlockPos pos = open.removeFirst();
            if (isClearlyOpen(targetPos, pos)) return false;

            for (Direction direction : Direction.values()) {
                BlockPos next = pos.offset(direction);
                if (!withinVisibilityProbe(targetPos, next) || visited.contains(next)) continue;

                BlockState nextState = mc.world.getBlockState(next);
                if (!isVisibilitySpace(nextState)) {
                    if (!blocksVisibility(nextState, next, direction.getOpposite())) return false;
                    continue;
                }

                BlockPos immutableNext = next.toImmutable();
                visited.add(immutableNext);
                open.add(immutableNext);
            }
        }

        return true;
    }

    private boolean isClearlyOpen(BlockPos origin, BlockPos pos) {
        return reachesVisibilityProbeEdge(origin, pos) || mc.world.isSkyVisible(pos);
    }

    private boolean reachesVisibilityProbeEdge(BlockPos origin, BlockPos pos) {
        return Math.abs(pos.getX() - origin.getX()) >= VISIBILITY_PROBE_RADIUS
            || Math.abs(pos.getY() - origin.getY()) >= VISIBILITY_PROBE_RADIUS
            || Math.abs(pos.getZ() - origin.getZ()) >= VISIBILITY_PROBE_RADIUS;
    }

    private boolean withinVisibilityProbe(BlockPos origin, BlockPos pos) {
        return Math.abs(pos.getX() - origin.getX()) <= VISIBILITY_PROBE_RADIUS
            && Math.abs(pos.getY() - origin.getY()) <= VISIBILITY_PROBE_RADIUS
            && Math.abs(pos.getZ() - origin.getZ()) <= VISIBILITY_PROBE_RADIUS;
    }

    private boolean isVisibilitySpace(BlockPos pos) {
        return isVisibilitySpace(mc.world.getBlockState(pos));
    }

    private boolean isVisibilitySpace(BlockState state) {
        return state.isAir() || state.isReplaceable();
    }

    private boolean blocksVisibility(BlockPos pos, Direction sideTowardTarget) {
        BlockState state = mc.world.getBlockState(pos);
        return blocksVisibility(state, pos, sideTowardTarget);
    }

    private boolean blocksVisibility(BlockState state, BlockPos pos, Direction sideTowardTarget) {
        return state.shouldSuffocate(mc.world, pos) && state.isSideSolidFullSquare(mc.world, pos, sideTowardTarget);
    }

    private boolean isSolidOccupant(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return !state.isAir() && !state.isReplaceable() && !state.isOf(Blocks.MOSS_BLOCK);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        renderTrail.render(event, render.get(), fadeTime.get(), sideColor.get(), lineColor.get());
    }
}
