package sleepy.addon.features;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GlowBerryPlacer extends Module {
    private static final int SEARCH_RADIUS = 5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders recent glow berry placements.")
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
        .defaultValue(new SettingColor(255, 255, 0, 40))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline color for placement renders.")
        .defaultValue(new SettingColor(255, 255, 0, 255))
        .build()
    );

    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();
    private final PlacementRenderTrail renderTrail = new PlacementRenderTrail();

    public GlowBerryPlacer() {
        super(SleepyAddon.CATEGORY, "glow-berry-placer", "Places glow berries under valid overhead blocks within reach.");
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

        GlowBerryPlacer self = Modules.get().get(GlowBerryPlacer.class);
        MossPlacer mossPlacer = Modules.get().get(MossPlacer.class);
        boolean splitWithMoss = self != null && self.isActive() && mossPlacer != null && mossPlacer.isActive();
        int budget = PlacerQuotaCoordinator.getBudget(
            mc.player.age,
            PlacementManager.get().getRemainingQuota(),
            PlacerQuotaCoordinator.Kind.GlowBerry,
            splitWithMoss,
            true
        );
        if (budget <= 0) return;
        if (targets.size() > budget) targets = new ArrayList<>(targets.subList(0, budget));

        List<BlockPos> placed = PlacementManager.get().placeMany(targets, Blocks.CAVE_VINES, Direction.DOWN);
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
                    if (!isValidTarget(scanPos, eye)) continue;
                    targets.add(scanPos.toImmutable());
                }
            }
        }

        List<BlockPos> sorted = new ArrayList<>(targets);
        sorted.sort(Comparator.comparingDouble(pos -> RangeUtil.distanceSqToBox(eye, new Box(pos))));
        return sorted;
    }

    private boolean isValidTarget(BlockPos pos, Vec3d eye) {
        if (!mc.world.isInBuildLimit(pos) || !RangeUtil.withinPlaceRange(eye, pos)) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (!state.isAir() && !state.isReplaceable()) return false;
        if (state.isOf(Blocks.CAVE_VINES) || state.isOf(Blocks.CAVE_VINES_PLANT)) return false;

        BlockPos support = pos.up();
        if (!mc.world.isInBuildLimit(support)) return false;

        BlockState supportState = mc.world.getBlockState(support);
        if (supportState.isAir()) return false;
        if (supportState.isOf(Blocks.CAVE_VINES) || supportState.isOf(Blocks.CAVE_VINES_PLANT)) return false;
        if (!Blocks.CAVE_VINES.getDefaultState().canPlaceAt(mc.world, pos)) return false;

        return PlacementManager.get().checkPlacement(pos, Blocks.CAVE_VINES).placeable();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        renderTrail.render(event, render.get(), fadeTime.get(), sideColor.get(), lineColor.get());
    }
}
