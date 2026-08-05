package sleepy.addon.features;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import sleepy.addon.SleepyAddon;
import sleepy.addon.events.UpdateEvent;
import sleepy.addon.manager.PlacementManager;
import sleepy.addon.util.MossUtil;
import sleepy.addon.util.RangeUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MossSpreader extends Module {
    private static final int SCAN_RADIUS = 6;
    private static final int MOSS_CONTEXT_RADIUS = 5;
    private static final int LOCAL_SOURCE_SIMULATION_BUDGET = 96;
    private static final int MOSS_EDGE_PREFILTER_RADIUS = 2;
    private static final double MOSS_PATCH_EDGE_COLUMN_CHANCE = 0.75D;
    private static final int MOSS_PATCH_MIN_RADIUS = 2;
    private static final int MOSS_PATCH_MAX_RADIUS = 3;
    private static final long SPREAD_TARGET_CACHE_MS = 250L;
    private static final int SPREAD_TARGET_CACHE_MAX_SIZE = 2048;
    private static final int USED_SOURCE_MIN_RETRY_TARGETS = 3;
    private static final long USED_SOURCE_MEMORY_MS = 120_000L;
    private static final int USED_SOURCE_MEMORY_MAX_SIZE = 4096;
    private static final int MOSS_INTERACTS_PER_BURST = 1;
    private static final int AZALEA_INTERACTS_PER_BURST = 4;
    private static final int TREE_SPACING = 7;
    private static final int TREE_VERTICAL_DOWN = 2;
    private static final int TREE_VERTICAL_UP = 9;
    private static final int MOSS_COOLDOWN_MIN_OFFSET = -1;
    private static final int MOSS_COOLDOWN_MAX_OFFSET = 2;
    private static final long MOSS_COOLDOWN_MS = 500L;
    private static final double SILENT_MINE_PRIORITY = 100.0D;
    private static final long CRAFT_COOLDOWN_MS = 100L;
    private static final long INVENTORY_CLEANSE_COOLDOWN_MS = 50L;
    private static final long INVENTORY_BASELINE_JOIN_DELAY_MS = 500L;
    private static final long INVENTORY_BASELINE_RETRY_DELAY_MS = 500L;
    private static final long SLOT_LOCK_SWAP_RESPONSE_MS = 650L;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAutomation = settings.createGroup("Automation");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> clearSnow = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-snow")
        .description("Clears snow layers blocking moss spread targets. Requires a shovel in the hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> growAzaleas = sgGeneral.add(new BoolSetting.Builder()
        .name("grow-azaleas")
        .description("Bonemeals azaleas when nearby oak logs are far enough away.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mossFallback = sgGeneral.add(new BoolSetting.Builder()
        .name("moss-fallback")
        .description("Places one starter moss block when no usable moss spread target exists nearby.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y")
        .description("Ignores moss, fallback, and azalea targets below this Y level.")
        .defaultValue(30)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Integer> spreadChance = sgGeneral.add(new IntSetting.Builder()
        .name("spread-chance")
        .description("Minimum estimated percent chance that bonemealing a moss source can spread moss.")
        .defaultValue(100)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> craftBonemeal = sgAutomation.add(new BoolSetting.Builder()
        .name("craft-bonemeal")
        .description("Crafts bone blocks into bone meal when bone meal is needed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> inventoryCleanser = sgAutomation.add(new BoolSetting.Builder()
        .name("inventory-cleanser")
        .description("Drops junk before it fills slots needed for bone meal crafting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> slotLock = sgAutomation.add(new BoolSetting.Builder()
        .name("slot-lock")
        .description("Keeps bone meal in the configured hotbar slot when MossSpreader is using it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> slot = sgAutomation.add(new IntSetting.Builder()
        .name("slot")
        .description("Hotbar slot used for bone meal when SlotLock is enabled.")
        .defaultValue(2)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(slotLock::get)
        .build()
    );

    private final Setting<Boolean> debugRender = sgRender.add(new BoolSetting.Builder()
        .name("debug-render")
        .description("Renders the selected moss, azalea, and obstruction targets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> mossSideColor = sgRender.add(new ColorSetting.Builder()
        .name("moss-side-color")
        .description("Fill color for the selected moss target.")
        .defaultValue(new SettingColor(70, 210, 95, 45))
        .visible(debugRender::get)
        .build()
    );

    private final Setting<SettingColor> mossLineColor = sgRender.add(new ColorSetting.Builder()
        .name("moss-line-color")
        .description("Outline color for the selected moss target.")
        .defaultValue(new SettingColor(90, 255, 125, 190))
        .visible(debugRender::get)
        .build()
    );

    private final Setting<SettingColor> azaleaSideColor = sgRender.add(new ColorSetting.Builder()
        .name("azalea-side-color")
        .description("Fill color for the selected azalea target.")
        .defaultValue(new SettingColor(245, 210, 70, 45))
        .visible(debugRender::get)
        .build()
    );

    private final Setting<SettingColor> azaleaLineColor = sgRender.add(new ColorSetting.Builder()
        .name("azalea-line-color")
        .description("Outline color for the selected azalea target.")
        .defaultValue(new SettingColor(255, 230, 95, 190))
        .visible(debugRender::get)
        .build()
    );

    private final Setting<SettingColor> obstructionSideColor = sgRender.add(new ColorSetting.Builder()
        .name("obstruction-side-color")
        .description("Fill color for the obstruction target.")
        .defaultValue(new SettingColor(255, 95, 70, 45))
        .visible(debugRender::get)
        .build()
    );

    private final Setting<SettingColor> obstructionLineColor = sgRender.add(new ColorSetting.Builder()
        .name("obstruction-line-color")
        .description("Outline color for the obstruction target.")
        .defaultValue(new SettingColor(255, 120, 95, 190))
        .visible(debugRender::get)
        .build()
    );

    private final PlacementManager placementManager = PlacementManager.get();
    private final Map<SpreadCacheKey, CachedSpreadTargets> spreadTargetCache = new HashMap<>();
    private final Map<BlockPos, Long> mossFootprintCooldowns = new HashMap<>();
    private final Map<BlockPos, Long> usedMossSources = new HashMap<>();
    private final Map<BlockPos, Long> predictedInstantGrassClears = new HashMap<>();
    private final boolean[] inventoryProtectedSlots = new boolean[36];
    private final ItemStack[] inventoryBaselineStacks = new ItemStack[36];

    private BlockPos activeMossTarget;
    private BlockPos renderMossTarget;
    private BlockPos renderAzaleaTarget;
    private BlockPos renderObstructionTarget;
    private long lastFallbackPlaceMs;
    private long lastCraftAttemptMs;
    private long lastInventoryCleanseMs;
    private long inventoryBaselineReadyAtMs;
    private long slotLockSwapWaitUntilMs;
    private Object inventoryBaselineWorld;
    private int inventoryBaselinePlayerId = Integer.MIN_VALUE;
    private int slotLockPendingSourceSlot = -1;
    private int slotLockPendingTargetSlot = -1;
    private boolean inventoryBaselineValid;
    private boolean autoEnabledSilentMine;

    public MossSpreader() {
        super(SleepyAddon.CATEGORY, "moss-spreader", "Bonemeals useful moss edges and spaced azaleas.");
    }

    @Override
    public void onActivate() {
        resetInventoryBaseline();
        resetSlotLockSwapState();
        if (mc.player != null && mc.world != null && hasLoadedInventoryForBaseline()) {
            captureInventoryBaseline();
        } else {
            syncInventoryBaseline();
        }
    }

    @Override
    public void onDeactivate() {
        activeMossTarget = null;
        clearRenderTargets();
        spreadTargetCache.clear();
        mossFootprintCooldowns.clear();
        usedMossSources.clear();
        predictedInstantGrassClears.clear();
        resetInventoryBaseline();
        resetSlotLockSwapState();
        restoreAutoEnabledSilentMine();
    }

    @EventHandler
    private void onTick(UpdateEvent event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            activeMossTarget = null;
            clearRenderTargets();
            spreadTargetCache.clear();
            mossFootprintCooldowns.clear();
            usedMossSources.clear();
            predictedInstantGrassClears.clear();
            resetInventoryBaseline();
            resetSlotLockSwapState();
            restoreAutoEnabledSilentMine();
            return;
        }

        syncInventoryBaseline();

        long now = System.currentTimeMillis();
        pruneSpreadTargetCache(now);
        prunePredictedInstantGrassClears();
        pruneMossFootprintCooldowns(now);
        pruneUsedMossSources(now);

        if (recoverBaselineProtectedItems()) return;
        if (maybeCleanseInventoryProactively()) return;
        if (clearSnowLayerForMossSpread()) return;

        List<Target> mossTargets = collectMossTargets();
        List<Target> azaleaTargets = growAzaleas.get() ? collectAzaleaTargets() : List.of();
        List<Target> readyTargets = collectReadyTargets(mossTargets, azaleaTargets);

        Target activeTarget = resolveActiveMossTarget(mossTargets);
        if (activeTarget != null) {
            Target betterTarget = betterFastTargetFor(activeTarget, mossTargets);
            if (betterTarget != null) {
                activeMossTarget = betterTarget.obstructionPos() == null ? null : betterTarget.pos();
                activeTarget = betterTarget;
            }

            updateRenderTargets(List.of(activeTarget), azaleaTargets);
            if (activeTarget.obstructionPos() == null) {
                if (useBoneMeal(List.of(activeTarget))) activeMossTarget = null;
            } else {
                runActiveMossTarget(activeTarget);
            }
            return;
        }

        if (mossTargets.isEmpty() && mossFallback.get() && !isFallbackPlaceOnCooldown()) {
            BlockPos fallbackPos = findFallbackMossPlacement();
            if (fallbackPos != null) {
                renderMossTarget = fallbackPos;
                renderAzaleaTarget = firstTargetPos(azaleaTargets);
                renderObstructionTarget = null;
                if (placeFallbackMoss(fallbackPos)) return;
            }
        }

        if (mossTargets.isEmpty() && azaleaTargets.isEmpty()) {
            clearRenderTargets();
            return;
        }

        Target bestMoss = mossTargets.isEmpty() ? null : mossTargets.get(0);
        if (!readyTargets.isEmpty() && !hasBoneMeal()) {
            prepareBoneMealSupply();
            return;
        }

        if (!readyTargets.isEmpty()) {
            if (useBoneMeal(readyTargets)) return;
        }

        if (bestMoss != null && bestMoss.obstructionPos() != null) {
            activeMossTarget = bestMoss.pos();
            updateRenderTargets(List.of(bestMoss), azaleaTargets);
            runActiveMossTarget(bestMoss);
            return;
        }

        updateRenderTargets(mossTargets, azaleaTargets);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!debugRender.get()) return;

        if (renderMossTarget != null) {
            event.renderer.box(renderMossTarget, mossSideColor.get(), mossLineColor.get(), ShapeMode.Both, 0);
        }
        if (renderAzaleaTarget != null) {
            event.renderer.box(renderAzaleaTarget, azaleaSideColor.get(), azaleaLineColor.get(), ShapeMode.Both, 0);
        }
        if (renderObstructionTarget != null) {
            event.renderer.box(renderObstructionTarget, obstructionSideColor.get(), obstructionLineColor.get(), ShapeMode.Both, 0);
        }
    }

    private List<Target> collectMossTargets() {
        if (mc.player == null || mc.world == null) return List.of();

        Vec3d eye = mc.player.getEyePos();
        BlockPos origin = mc.player.getBlockPos();
        List<BlockPos> sources = new ArrayList<>();
        List<Target> candidates = new ArrayList<>();

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (isBelowMinY(pos)) continue;
                    if (!mc.world.isChunkLoaded(pos)) continue;
                    if (!RangeUtil.withinPlaceRange(eye, pos)) continue;
                    if (placementManager.isOnCooldown(pos) || isOnMossFootprintCooldown(pos)) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    if (!MossUtil.isMossBlock(state)) continue;
                    if (!hasFreshSpreadTargetCache(pos, false)
                        && !MossUtil.isNearMossSpreadEdge(mc.world, pos, MOSS_EDGE_PREFILTER_RADIUS)) {
                        continue;
                    }
                    if (!passesSpreadChance(pos, cachedSpreadTargets(pos, false))) continue;

                    sources.add(pos.toImmutable());
                }
            }
        }

        sources.sort(Comparator.comparingDouble(pos -> distanceSqToBlock(eye, pos)));

        int freshSimulations = 0;
        for (BlockPos pos : sources) {
            if (!hasFreshSpreadTargetCache(pos, false)) {
                if (freshSimulations >= LOCAL_SOURCE_SIMULATION_BUDGET) break;
                freshSimulations++;
            }

            MossScore score = scoreMossTarget(pos, eye);
            if (!score.valid()) continue;

            BlockPos obstruction = findSafeObstructionAbove(pos);
            BlockState aboveState = mc.world.getBlockState(pos.up());
            if (isPredictedInstantGrassClear(pos, aboveState)) {
                obstruction = null;
                aboveState = Blocks.AIR.getDefaultState();
            }
            if (!aboveState.isAir() && obstruction == null) continue;

            double targetScore = score.value() + mossAbovePenalty(aboveState);
            candidates.add(new Target(pos.toImmutable(), TargetKind.MOSS, targetScore, obstruction));
        }

        candidates.sort(TARGET_ORDER);
        boolean hasDirectEdgeCandidate = false;
        for (Target candidate : candidates) {
            if (isPreferredDirectEdgeCandidate(candidate)) {
                hasDirectEdgeCandidate = true;
                break;
            }
        }

        boolean hasNonCarpetCandidate = false;
        for (Target candidate : candidates) {
            if (!isMossCarpetObstructedTarget(candidate)) {
                hasNonCarpetCandidate = true;
                break;
            }
        }

        Set<BlockPos> plannedCooldownFootprint = new HashSet<>();
        List<Target> selected = new ArrayList<>(candidates.size());
        boolean keptCarpetTarget = false;
        for (Target candidate : candidates) {
            if (hasDirectEdgeCandidate && !isPreferredDirectEdgeCandidate(candidate)) continue;
            if (isMossCarpetObstructedTarget(candidate)) {
                if (hasNonCarpetCandidate || keptCarpetTarget) continue;
                keptCarpetTarget = true;
            }
            if (candidate.obstructionPos() == null) {
                if (overlapsMossCooldownFootprint(plannedCooldownFootprint, candidate.pos())) continue;
                addMossCooldownFootprint(plannedCooldownFootprint, candidate.pos());
            }
            selected.add(candidate);
        }

        return selected;
    }

    private List<Target> collectAzaleaTargets() {
        if (mc.player == null || mc.world == null) return List.of();

        Vec3d eye = mc.player.getEyePos();
        BlockPos origin = mc.player.getBlockPos();
        List<Target> targets = new ArrayList<>();

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (isBelowMinY(pos)) continue;
                    if (!mc.world.isChunkLoaded(pos)) continue;
                    if (!RangeUtil.withinPlaceRange(eye, pos)) continue;
                    if (placementManager.isOnCooldown(pos)) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    if (!MossUtil.isAzalea(state)) continue;
                    if (!MossUtil.canAzaleaTreeGrowAt(mc.world, pos)) continue;
                    if (MossUtil.hasOakLogWithin(mc.world, pos, TREE_SPACING, TREE_VERTICAL_DOWN, TREE_VERTICAL_UP)) continue;

                    targets.add(new Target(pos.toImmutable(), TargetKind.AZALEA, distanceSqToBlock(eye, pos), null));
                }
            }
        }

        targets.sort(TARGET_ORDER);
        return targets;
    }

    private boolean clearSnowLayerForMossSpread() {
        if (!clearSnow.get() || !hasHotbarShovel() || mc.player == null || mc.world == null) return false;

        for (BlockPos snowPos : collectSnowLayerClearTargets()) {
            if (requestObstructionBreak(snowPos)) return true;
        }
        return false;
    }

    private List<BlockPos> collectSnowLayerClearTargets() {
        if (mc.player == null || mc.world == null) return List.of();

        Vec3d eye = mc.player.getEyePos();
        BlockPos origin = mc.player.getBlockPos();
        Set<BlockPos> snowTargets = new HashSet<>();

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    BlockPos mossPos = origin.add(dx, dy, dz);
                    if (isBelowMinY(mossPos)) continue;
                    if (!mc.world.isChunkLoaded(mossPos)) continue;
                    if (!MossUtil.isMossBlock(mc.world.getBlockState(mossPos))) continue;
                    if (!MossUtil.isNearMossSpreadEdge(mc.world, mossPos, MOSS_EDGE_PREFILTER_RADIUS)) continue;
                    if (!MossUtil.canUseMossSource(mc.world, mossPos)) continue;

                    for (BlockPos snowPos : MossUtil.findMossPatchSnowLayerTargets(mc.world, mossPos)) {
                        if (snowPos == null || isBelowMinY(snowPos)) continue;
                        if (!mc.world.isChunkLoaded(snowPos)) continue;
                        if (!RangeUtil.withinMineRange(eye, snowPos)) continue;

                        BlockState snowState = mc.world.getBlockState(snowPos);
                        if (!MossUtil.isSnowLayer(snowState)) continue;
                        if (!BlockUtils.canBreak(snowPos, snowState)) continue;
                        snowTargets.add(snowPos.toImmutable());
                    }
                }
            }
        }

        List<BlockPos> sorted = new ArrayList<>(snowTargets);
        sorted.sort(Comparator.comparingDouble(pos -> distanceSqToBlock(eye, pos)));
        return sorted;
    }

    private boolean useBoneMeal(List<Target> targets) {
        if (targets == null || targets.isEmpty() || mc.player == null || mc.world == null) return false;

        int sourceSlot = getBoneMealSlotForUse();
        boolean offhandOnly = sourceSlot == -1 && mc.player.getOffHandStack().isOf(Items.BONE_MEAL);
        if (sourceSlot == -1 && !offhandOnly) return false;

        ItemStack stack = offhandOnly ? mc.player.getOffHandStack() : mc.player.getInventory().getStack(sourceSlot);
        int remainingItems = stack == null ? 0 : stack.getCount();
        if (remainingItems <= 0) return false;

        List<BlockPos> positions = new ArrayList<>();
        Map<BlockPos, TargetKind> requestKinds = new HashMap<>();
        Set<BlockPos> plannedMossCooldownFootprint = new HashSet<>();

        for (Target target : targets) {
            if (target == null || remainingItems <= 0 || positions.size() >= MOSS_INTERACTS_PER_BURST) break;
            BlockPos pos = target.pos();
            if (pos == null) continue;
            if (target.kind() != TargetKind.MOSS) continue;
            if (placementManager.isOnCooldown(pos) || isOnMossFootprintCooldown(pos)) continue;
            if (target.obstructionPos() != null) continue;
            if (overlapsMossCooldownFootprint(plannedMossCooldownFootprint, pos)) continue;

            positions.add(pos);
            requestKinds.put(pos, TargetKind.MOSS);
            addMossCooldownFootprint(plannedMossCooldownFootprint, pos);
            remainingItems--;
        }

        if (positions.isEmpty()) {
            for (Target target : targets) {
                if (target == null || remainingItems <= 0) break;
                BlockPos pos = target.pos();
                if (pos == null) continue;
                if (target.kind() != TargetKind.AZALEA) continue;
                if (placementManager.isOnCooldown(pos)) continue;

                int azaleaUses = Math.min(AZALEA_INTERACTS_PER_BURST, remainingItems);
                for (int i = 0; i < azaleaUses; i++) {
                    positions.add(pos);
                }
                requestKinds.put(pos, TargetKind.AZALEA);
                break;
            }
        }

        if (positions.isEmpty()) return false;

        List<BlockPos> sent = offhandOnly
            ? placementManager.airInteractMany(positions, Items.BONE_MEAL)
            : airInteractFromInventoryAwareSlot(positions, sourceSlot);
        if (sent.isEmpty()) return false;

        Set<BlockPos> marked = new HashSet<>();
        for (BlockPos sentPos : sent) {
            if (sentPos == null || !marked.add(sentPos)) continue;

            TargetKind kind = requestKinds.get(sentPos);
            if (kind == TargetKind.MOSS) {
                predictedInstantGrassClears.remove(sentPos);
                markMossSourceUsed(sentPos);
                markMossCooldownFootprint(sentPos);
                placementManager.markCooldownFor(sentPos);
            } else if (kind == TargetKind.AZALEA) {
                placementManager.markCooldownFor(sentPos);
            }
        }
        return true;
    }

    private void runActiveMossTarget(Target target) {
        if (target == null || target.kind() != TargetKind.MOSS) {
            activeMossTarget = null;
            return;
        }

        if (!hasBoneMeal()) {
            prepareBoneMealSupply();
            return;
        }

        if (slotLock.get()) ensureLockedBoneMealSlot();

        if (target.obstructionPos() != null) {
            boolean requested = requestObstructionBreak(target.obstructionPos());
            if (requested && markPredictedInstantGrassClear(target)) {
                Target predictedReady = new Target(target.pos(), target.kind(), target.score(), null);
                if (useBoneMeal(List.of(predictedReady))) activeMossTarget = null;
            }
            return;
        }

        if (useBoneMeal(List.of(target))) activeMossTarget = null;
    }

    private boolean requestObstructionBreak(BlockPos pos) {
        if (pos == null || mc.world == null || mc.player == null) return false;

        SilentMine silentMine = ensureSilentMine();
        if (silentMine == null || !silentMine.isActive()) return false;
        if (silentMine.alreadyBreaking(pos)) return true;

        silentMine.silentBreakBlock(pos, closestMineDirection(pos), SILENT_MINE_PRIORITY);
        return silentMine.alreadyBreaking(pos);
    }

    private SilentMine ensureSilentMine() {
        SilentMine silentMine = Modules.get().get(SilentMine.class);
        if (silentMine == null) return null;
        if (!silentMine.isActive()) {
            silentMine.toggle();
            autoEnabledSilentMine = true;
        }
        return silentMine;
    }

    private void restoreAutoEnabledSilentMine() {
        if (!autoEnabledSilentMine) return;
        SilentMine silentMine = Modules.get().get(SilentMine.class);
        if (silentMine != null && silentMine.isActive()) {
            silentMine.toggle();
        }
        autoEnabledSilentMine = false;
    }

    private Direction closestMineDirection(BlockPos pos) {
        if (mc.player == null || pos == null) return Direction.UP;
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d eye = mc.player.getEyePos();
        Direction direction = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        return direction == null ? Direction.UP : direction;
    }

    private BlockPos findFallbackMossPlacement() {
        if (mc.player == null || mc.world == null) return null;

        Block fallbackBlock = findAvailableMossBlock();
        if (fallbackBlock == null) return null;

        Vec3d eye = mc.player.getEyePos();
        BlockPos origin = mc.player.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    BlockPos groundPos = origin.add(dx, dy, dz);
                    if (isBelowMinY(groundPos)) continue;
                    if (!MossUtil.canFallbackMossStartAt(mc.world, groundPos)) continue;

                    BlockPos placePos = groundPos.up();
                    if (isBelowMinY(placePos)) continue;
                    if (!RangeUtil.withinPlaceRange(eye, placePos)) continue;
                    if (placementManager.isOnCooldown(placePos)) continue;
                    if (!placementManager.checkPlacement(placePos, fallbackBlock).placeable()) continue;

                    MossScore score = scoreVirtualMossTarget(placePos, eye);
                    if (!score.valid()) continue;

                    double value = score.value() + distanceSqToBlock(eye, placePos);
                    if (best == null || value < bestScore) {
                        best = placePos.toImmutable();
                        bestScore = value;
                    }
                }
            }
        }

        return best;
    }

    private boolean placeFallbackMoss(BlockPos pos) {
        if (pos == null || mc.player == null) return false;

        int sourceSlot = findMossBlockSlot();
        if (sourceSlot == -1) return false;
        Block mossBlock = mossBlockForSlot(sourceSlot);
        if (mossBlock == null) return false;

        boolean placed = sourceSlot <= 8
            ? !placementManager.placeManyFromHotbarSlot(List.of(pos), mossBlock, sourceSlot, null).isEmpty()
            : altSilentSwapPlaceBlock(pos, sourceSlot, mossBlock);
        if (placed) {
            lastFallbackPlaceMs = System.currentTimeMillis();
            placementManager.markCooldownFor(pos);
        }
        return placed;
    }

    private BlockPos findSafeObstructionAbove(BlockPos mossPos) {
        BlockPos above = mossPos.up();
        if (!mc.world.isChunkLoaded(above)) return null;

        BlockState aboveState = mc.world.getBlockState(above);
        if (aboveState.isAir()) return null;
        if (!MossUtil.isSafeMossObstruction(aboveState)) return null;
        if (MossUtil.isSnowLayer(aboveState) && !hasHotbarShovel()) return null;
        return BlockUtils.canBreak(above, aboveState) ? above.toImmutable() : null;
    }

    private Target resolveActiveMossTarget(List<Target> mossTargets) {
        if (activeMossTarget == null) return null;
        if (mossTargets != null) {
            for (Target target : mossTargets) {
                if (target != null && activeMossTarget.equals(target.pos())) {
                    return target;
                }
            }
        }
        activeMossTarget = null;
        return null;
    }

    private Target betterFastTargetFor(Target activeTarget, List<Target> mossTargets) {
        if (activeTarget == null || activeTarget.obstructionPos() == null) return null;
        if (!isMossCarpetAbove(activeTarget.pos()) || mossTargets == null) return null;

        for (Target target : mossTargets) {
            if (target == null || !isFastMossTarget(target)) continue;
            if (TARGET_ORDER.compare(target, activeTarget) < 0) {
                return target;
            }
        }

        return null;
    }

    private boolean isFastMossTarget(Target target) {
        if (target == null || target.pos() == null || mc.world == null) return false;
        BlockPos above = target.pos().up();
        if (!mc.world.isChunkLoaded(above)) return false;

        BlockState aboveState = mc.world.getBlockState(above);
        return aboveState.isAir() || MossUtil.isGrassPlant(aboveState);
    }

    private boolean isMossCarpetAbove(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        BlockPos above = pos.up();
        return mc.world.isChunkLoaded(above) && MossUtil.isMossCarpet(mc.world.getBlockState(above));
    }

    private List<Target> collectReadyTargets(List<Target> mossTargets, List<Target> azaleaTargets) {
        List<Target> readyMoss = new ArrayList<>(mossTargets == null ? 0 : mossTargets.size());

        if (mossTargets != null) {
            for (Target target : mossTargets) {
                if (target != null && target.obstructionPos() == null) {
                    readyMoss.add(target);
                }
            }
        }

        List<Target> readyAzaleas = azaleaTargets == null ? List.of() : azaleaTargets;
        List<Target> ready = new ArrayList<>(readyMoss.size() + readyAzaleas.size());
        int mossIndex = 0;
        int azaleaIndex = 0;

        while (mossIndex < readyMoss.size() || azaleaIndex < readyAzaleas.size()) {
            if (mossIndex < readyMoss.size()) ready.add(readyMoss.get(mossIndex++));
            if (azaleaIndex < readyAzaleas.size()) ready.add(readyAzaleas.get(azaleaIndex++));
        }

        return ready;
    }

    private boolean isPreferredDirectEdgeCandidate(Target target) {
        return target != null
            && target.kind() == TargetKind.MOSS
            && !isMossCarpetObstructedTarget(target)
            && isDirectMossEdgeSource(target.pos(), false);
    }

    private boolean isMossCarpetObstructedTarget(Target target) {
        if (target == null || target.obstructionPos() == null || mc.world == null) return false;
        BlockPos obstruction = target.obstructionPos();
        return mc.world.isChunkLoaded(obstruction)
            && MossUtil.isMossCarpet(mc.world.getBlockState(obstruction));
    }

    private boolean isDirectMossEdgeSource(BlockPos pos, boolean assumeSourceMoss) {
        if (pos == null) return false;
        for (BlockPos target : cachedSpreadTargets(pos, assumeSourceMoss)) {
            if (isDirectMossSpreadTarget(pos, target)) return true;
        }
        return false;
    }

    private boolean isDirectMossSpreadTarget(BlockPos source, BlockPos target) {
        if (source == null || target == null) return false;
        return Math.max(Math.abs(target.getX() - source.getX()), Math.abs(target.getZ() - source.getZ())) <= 1
            && Math.abs(target.getY() - source.getY()) <= 1;
    }

    private MossScore scoreMossTarget(BlockPos pos, Vec3d eye) {
        return scoreMossTarget(pos, eye, false);
    }

    private MossScore scoreVirtualMossTarget(BlockPos pos, Vec3d eye) {
        return scoreMossTarget(pos, eye, true);
    }

    private MossScore scoreMossTarget(BlockPos pos, Vec3d eye, boolean assumeSourceMoss) {
        Set<BlockPos> spreadTargets = cachedSpreadTargets(pos, assumeSourceMoss);
        int replaceableEdges = spreadTargets.size();
        int adjacentGrass = 0;
        int nearestSpreadDistanceSq = Integer.MAX_VALUE;
        if (!assumeSourceMoss && isLowYieldUsedMossSource(pos, replaceableEdges)) {
            return MossScore.invalid();
        }

        for (BlockPos target : spreadTargets) {
            if (mc.world.getBlockState(target).isOf(Blocks.GRASS_BLOCK)) {
                adjacentGrass++;
            }
            nearestSpreadDistanceSq = Math.min(nearestSpreadDistanceSq, mossSpreadDistanceSq(pos, target));
        }

        if (replaceableEdges <= 0) return MossScore.invalid();

        int nearestGrassSq = MossUtil.nearestGrassBlockDistanceSq(mc.world, pos, MOSS_CONTEXT_RADIUS);
        boolean hasGrass = nearestGrassSq != Integer.MAX_VALUE;
        double playerDistSq = distanceSqToBlock(eye, pos);
        double score = (hasGrass ? 0.0D : 10_000.0D)
            + (hasGrass ? nearestGrassSq * 100.0D : 0.0D)
            - adjacentGrass * 40.0D
            - replaceableEdges * 6.0D
            + nearestSpreadDistanceSq * 1_000.0D
            + playerDistSq;

        return new MossScore(true, score);
    }

    private boolean passesSpreadChance(BlockPos source, Set<BlockPos> spreadTargets) {
        return estimatedSpreadChance(source, spreadTargets) >= spreadChance.get();
    }

    private int estimatedSpreadChance(BlockPos source, Set<BlockPos> spreadTargets) {
        if (source == null) return 0;
        if (spreadTargets == null || spreadTargets.isEmpty()) return 0;

        double noSpreadChance = 1.0D;
        for (BlockPos target : spreadTargets) {
            noSpreadChance *= 1.0D - mossPatchColumnChance(source, target);
            if (noSpreadChance <= 0.0D) return 100;
        }

        return Math.min(100, (int) Math.round((1.0D - noSpreadChance) * 100.0D));
    }

    private double mossPatchColumnChance(BlockPos source, BlockPos target) {
        if (source == null || target == null) return 0.0D;

        int absX = Math.abs(target.getX() - source.getX());
        int absZ = Math.abs(target.getZ() - source.getZ());
        if (absX > MOSS_PATCH_MAX_RADIUS || absZ > MOSS_PATCH_MAX_RADIUS) return 0.0D;

        double totalChance = 0.0D;
        int samples = 0;
        for (int radiusX = MOSS_PATCH_MIN_RADIUS; radiusX <= MOSS_PATCH_MAX_RADIUS; radiusX++) {
            for (int radiusZ = MOSS_PATCH_MIN_RADIUS; radiusZ <= MOSS_PATCH_MAX_RADIUS; radiusZ++) {
                samples++;
                totalChance += mossPatchColumnChanceForRadius(absX, absZ, radiusX, radiusZ);
            }
        }
        return totalChance / samples;
    }

    private double mossPatchColumnChanceForRadius(int absX, int absZ, int radiusX, int radiusZ) {
        if (absX > radiusX || absZ > radiusZ) return 0.0D;

        boolean onXEdge = absX == radiusX;
        boolean onZEdge = absZ == radiusZ;
        if (onXEdge && onZEdge) return 0.0D;
        if (onXEdge || onZEdge) return MOSS_PATCH_EDGE_COLUMN_CHANCE;
        return 1.0D;
    }

    private int mossSpreadDistanceSq(BlockPos source, BlockPos target) {
        if (source == null || target == null) return Integer.MAX_VALUE;
        int dx = target.getX() - source.getX();
        int dy = target.getY() - source.getY();
        int dz = target.getZ() - source.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private double mossAbovePenalty(BlockState aboveState) {
        if (aboveState == null || aboveState.isAir()) return 0.0D;
        if (MossUtil.isGrassPlant(aboveState)) return 1.0D;
        if (MossUtil.isSnowLayer(aboveState)) return 2.0D;
        if (MossUtil.isMossCarpet(aboveState)) return 100_000.0D;
        if (MossUtil.isAzalea(aboveState)) return 900_000.0D;
        return 1_000_000.0D;
    }

    private Set<BlockPos> cachedSpreadTargets(BlockPos source, boolean assumeSourceMoss) {
        if (source == null || mc.world == null) return Set.of();

        long now = System.currentTimeMillis();
        SpreadCacheKey key = new SpreadCacheKey(source.asLong(), assumeSourceMoss);
        CachedSpreadTargets cached = spreadTargetCache.get(key);
        if (cached != null && cached.expiresAtMs() >= now) {
            return cached.targets();
        }

        Set<BlockPos> targets = Set.copyOf(MossUtil.findMossPatchSpreadTargets(mc.world, source, assumeSourceMoss));
        if (spreadTargetCache.size() >= SPREAD_TARGET_CACHE_MAX_SIZE) {
            pruneSpreadTargetCache(now);
            if (spreadTargetCache.size() >= SPREAD_TARGET_CACHE_MAX_SIZE) {
                spreadTargetCache.clear();
            }
        }
        spreadTargetCache.put(key, new CachedSpreadTargets(targets, now + SPREAD_TARGET_CACHE_MS));
        return targets;
    }

    private boolean hasFreshSpreadTargetCache(BlockPos source, boolean assumeSourceMoss) {
        if (source == null) return false;
        CachedSpreadTargets cached = spreadTargetCache.get(new SpreadCacheKey(source.asLong(), assumeSourceMoss));
        return cached != null && cached.expiresAtMs() >= System.currentTimeMillis();
    }

    private void pruneSpreadTargetCache(long now) {
        if (spreadTargetCache.isEmpty()) return;
        spreadTargetCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() < now);
    }

    private void markMossCooldownFootprint(BlockPos center) {
        if (center == null) return;

        long expiresAt = System.currentTimeMillis() + MOSS_COOLDOWN_MS;
        for (int dx = MOSS_COOLDOWN_MIN_OFFSET; dx <= MOSS_COOLDOWN_MAX_OFFSET; dx++) {
            for (int dz = MOSS_COOLDOWN_MIN_OFFSET; dz <= MOSS_COOLDOWN_MAX_OFFSET; dz++) {
                mossFootprintCooldowns.put(center.add(dx, 0, dz).toImmutable(), expiresAt);
            }
        }
    }

    private boolean isOnMossFootprintCooldown(BlockPos pos) {
        if (pos == null || mossFootprintCooldowns.isEmpty()) return false;

        Long expiresAt = mossFootprintCooldowns.get(pos);
        if (expiresAt == null) return false;
        if (expiresAt >= System.currentTimeMillis()) return true;

        mossFootprintCooldowns.remove(pos);
        return false;
    }

    private void pruneMossFootprintCooldowns(long now) {
        if (mossFootprintCooldowns.isEmpty()) return;
        mossFootprintCooldowns.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private void addMossCooldownFootprint(Set<BlockPos> positions, BlockPos center) {
        if (positions == null || center == null) return;
        for (int dx = MOSS_COOLDOWN_MIN_OFFSET; dx <= MOSS_COOLDOWN_MAX_OFFSET; dx++) {
            for (int dz = MOSS_COOLDOWN_MIN_OFFSET; dz <= MOSS_COOLDOWN_MAX_OFFSET; dz++) {
                positions.add(center.add(dx, 0, dz).toImmutable());
            }
        }
    }

    private boolean overlapsMossCooldownFootprint(Set<BlockPos> positions, BlockPos center) {
        if (positions == null || positions.isEmpty() || center == null) return false;
        for (int dx = MOSS_COOLDOWN_MIN_OFFSET; dx <= MOSS_COOLDOWN_MAX_OFFSET; dx++) {
            for (int dz = MOSS_COOLDOWN_MIN_OFFSET; dz <= MOSS_COOLDOWN_MAX_OFFSET; dz++) {
                if (positions.contains(center.add(dx, 0, dz))) return true;
            }
        }
        return false;
    }

    private void markMossSourceUsed(BlockPos pos) {
        if (pos == null) return;
        long now = System.currentTimeMillis();
        if (usedMossSources.size() >= USED_SOURCE_MEMORY_MAX_SIZE) {
            pruneUsedMossSources(now);
            if (usedMossSources.size() >= USED_SOURCE_MEMORY_MAX_SIZE) {
                usedMossSources.clear();
            }
        }
        usedMossSources.put(pos.toImmutable(), now + USED_SOURCE_MEMORY_MS);
    }

    private boolean isLowYieldUsedMossSource(BlockPos pos, int spreadTargetCount) {
        if (pos == null || spreadTargetCount >= USED_SOURCE_MIN_RETRY_TARGETS) return false;

        Long expiresAt = usedMossSources.get(pos);
        if (expiresAt == null) return false;
        if (expiresAt >= System.currentTimeMillis()) return true;

        usedMossSources.remove(pos);
        return false;
    }

    private void pruneUsedMossSources(long now) {
        if (usedMossSources.isEmpty()) return;
        usedMossSources.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private boolean markPredictedInstantGrassClear(Target target) {
        if (target == null || target.pos() == null || target.obstructionPos() == null || mc.world == null) return false;
        if (!mc.world.isChunkLoaded(target.obstructionPos())) return false;

        BlockState obstructionState = mc.world.getBlockState(target.obstructionPos());
        if (!MossUtil.isGrassPlant(obstructionState)) return false;

        predictedInstantGrassClears.put(target.pos().toImmutable(), System.currentTimeMillis());
        return true;
    }

    private boolean isPredictedInstantGrassClear(BlockPos mossPos, BlockState aboveState) {
        if (mossPos == null || !MossUtil.isGrassPlant(aboveState)) return false;
        return predictedInstantGrassClears.containsKey(mossPos);
    }

    private void prunePredictedInstantGrassClears() {
        if (predictedInstantGrassClears.isEmpty()) return;

        long now = System.currentTimeMillis();
        long maxAgeMs = MOSS_COOLDOWN_MS * 4L;
        predictedInstantGrassClears.entrySet().removeIf(entry -> {
            BlockPos mossPos = entry.getKey();
            if (mossPos == null || mc.world == null || !mc.world.isChunkLoaded(mossPos.up())) return true;
            if (mc.world.getBlockState(mossPos.up()).isAir()) return true;
            return now - entry.getValue() > maxAgeMs;
        });
    }

    private void updateRenderTargets(List<Target> mossTargets, List<Target> azaleaTargets) {
        renderMossTarget = firstTargetPos(mossTargets);
        renderAzaleaTarget = firstTargetPos(azaleaTargets);
        renderObstructionTarget = firstObstructionPos(mossTargets);
    }

    private void clearRenderTargets() {
        renderMossTarget = null;
        renderAzaleaTarget = null;
        renderObstructionTarget = null;
    }

    private BlockPos firstTargetPos(List<Target> targets) {
        if (targets == null) return null;
        for (Target target : targets) {
            if (target != null && target.pos() != null) {
                return target.pos();
            }
        }
        return null;
    }

    private BlockPos firstObstructionPos(List<Target> targets) {
        if (targets == null) return null;
        for (Target target : targets) {
            if (target != null && target.obstructionPos() != null) {
                return target.obstructionPos();
            }
        }
        return null;
    }

    private List<BlockPos> airInteractFromInventoryAwareSlot(List<BlockPos> positions, int sourceSlot) {
        if (sourceSlot < 0 || sourceSlot >= 36) return List.of();
        if (sourceSlot <= 8) {
            return placementManager.airInteractManyFromSlot(positions, Items.BONE_MEAL, sourceSlot, false);
        }
        return altSilentSwapAirInteract(positions, sourceSlot);
    }

    private List<BlockPos> altSilentSwapAirInteract(List<BlockPos> positions, int sourceSlot) {
        if (positions == null || positions.isEmpty()) return List.of();
        if (!canClickPlayerInventory()) return List.of();

        int selected = mc.player.getInventory().getSelectedSlot();
        ScreenHandler handler = mc.player.currentScreenHandler;
        int sourceScreenSlot = toScreenSlot(sourceSlot);

        clickSlot(handler, sourceScreenSlot, selected, SlotActionType.SWAP);
        List<BlockPos> sent = mc.player.getInventory().getStack(selected).isOf(Items.BONE_MEAL)
            ? placementManager.airInteractManyFromSlot(positions, Items.BONE_MEAL, selected, false)
            : List.of();
        clickSlot(handler, sourceScreenSlot, selected, SlotActionType.SWAP);
        return sent;
    }

    private boolean altSilentSwapPlaceBlock(BlockPos pos, int sourceSlot, Block block) {
        if (pos == null || block == null || sourceSlot < 9 || sourceSlot >= 36) return false;
        if (!canClickPlayerInventory()) return false;

        int selected = mc.player.getInventory().getSelectedSlot();
        ScreenHandler handler = mc.player.currentScreenHandler;
        int sourceScreenSlot = toScreenSlot(sourceSlot);

        clickSlot(handler, sourceScreenSlot, selected, SlotActionType.SWAP);
        boolean placed = blockForSlot(selected) == block
            && !placementManager.placeManyFromHotbarSlot(List.of(pos), block, selected, null).isEmpty();
        clickSlot(handler, sourceScreenSlot, selected, SlotActionType.SWAP);
        return placed;
    }

    private boolean prepareBoneMealSupply() {
        return craftBonemeal.get() && prepareBoneMealCrafting();
    }

    private boolean prepareBoneMealCrafting() {
        if (!craftBonemeal.get()) return false;
        if (mc.player == null || mc.interactionManager == null) return false;
        if (isCraftOnCooldown()) return false;
        if (!canClickPlayerInventory()) return false;

        if (inventoryCleanser.get() && cleanseInventoryForBoneMeal(true)) return true;

        int sourceSlot = findBoneBlockSlot();
        if (sourceSlot == -1) return false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        int inputSlot = findEmptyCraftingInputSlot(handler);
        if (inputSlot == -1) return false;

        ItemStack sourceStack = mc.player.getInventory().getStack(sourceSlot);
        if (sourceStack.isEmpty()) return false;

        int stagingHotbar = sourceSlot <= 8 ? sourceSlot : mc.player.getInventory().getSelectedSlot();
        if (sourceSlot > 8 && !canUseSelectedSlotForAltSilentSwap()) return false;

        boolean crafted = canBulkCraftBoneBlocks(sourceSlot, stagingHotbar, sourceStack.getCount())
            ? craftBoneBlockStackWithSwap(handler, sourceSlot, inputSlot, stagingHotbar)
            : craftOneBoneBlockWithPickup(handler, sourceSlot, inputSlot);
        if (crafted) markCraftCooldown();
        return crafted;
    }

    private boolean craftBoneBlockStackWithSwap(ScreenHandler handler, int sourceSlot, int inputSlot, int stagingHotbar) {
        if (handler == null) return false;

        if (sourceSlot <= 8) {
            clickSlot(handler, inputSlot, sourceSlot, SlotActionType.SWAP);
            clickSlot(handler, 0, 0, SlotActionType.QUICK_MOVE);
            return true;
        }

        int sourceScreenSlot = toScreenSlot(sourceSlot);
        clickSlot(handler, sourceScreenSlot, stagingHotbar, SlotActionType.SWAP);
        clickSlot(handler, inputSlot, stagingHotbar, SlotActionType.SWAP);
        clickSlot(handler, 0, 0, SlotActionType.QUICK_MOVE);
        clickSlot(handler, sourceScreenSlot, stagingHotbar, SlotActionType.SWAP);
        return true;
    }

    private boolean craftOneBoneBlockWithPickup(ScreenHandler handler, int sourceSlot, int inputSlot) {
        if (handler == null || boneMealCapacity() < 9) return false;

        int sourceScreenSlot = toScreenSlot(sourceSlot);
        clickSlot(handler, sourceScreenSlot, 0, SlotActionType.PICKUP);
        clickSlot(handler, inputSlot, 1, SlotActionType.PICKUP);
        clickSlot(handler, sourceScreenSlot, 0, SlotActionType.PICKUP);
        clickSlot(handler, 0, 0, SlotActionType.QUICK_MOVE);
        return true;
    }

    private int findBoneBlockSlot() {
        if (mc.player == null) return -1;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Blocks.BONE_BLOCK.asItem())) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Blocks.BONE_BLOCK.asItem())) return i;
        }
        return -1;
    }

    private int findEmptyCraftingInputSlot(ScreenHandler handler) {
        if (handler == null) return -1;

        int emptySlot = -1;
        for (int slot = 1; slot <= 4; slot++) {
            if (!handler.getSlot(slot).getStack().isEmpty()) return -1;
            if (emptySlot == -1) emptySlot = slot;
        }
        return emptySlot;
    }

    private boolean canBulkCraftBoneBlocks(int sourceSlot, int stagingHotbar, int boneBlockCount) {
        if (boneBlockCount <= 0) return false;
        long requiredCapacity = (long) boneBlockCount * 9L;
        return boneMealCapacityAfterPreparingBulkCraft(sourceSlot, stagingHotbar) >= requiredCapacity;
    }

    private int boneMealCapacityAfterPreparingBulkCraft(int sourceSlot, int stagingHotbar) {
        int capacity = 0;

        for (int i = 0; i < 36; i++) {
            if (sourceSlot <= 8 && i == sourceSlot) {
                capacity += Items.BONE_MEAL.getMaxCount();
                continue;
            }

            if (sourceSlot >= 9 && sourceSlot <= 35) {
                if (i == sourceSlot) {
                    capacity += boneMealCapacityForStack(mc.player.getInventory().getStack(stagingHotbar));
                    continue;
                }
                if (i == stagingHotbar) {
                    capacity += Items.BONE_MEAL.getMaxCount();
                    continue;
                }
            }

            capacity += boneMealCapacityForStack(mc.player.getInventory().getStack(i));
        }

        return capacity;
    }

    private int boneMealCapacity() {
        if (mc.player == null) return 0;

        int capacity = 0;
        for (int i = 0; i < 36; i++) {
            capacity += boneMealCapacityForStack(mc.player.getInventory().getStack(i));
        }
        return capacity;
    }

    private int boneMealCapacityForStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Items.BONE_MEAL.getMaxCount();
        if (stack.isOf(Items.BONE_MEAL)) return Math.max(0, stack.getMaxCount() - stack.getCount());
        return 0;
    }

    private boolean isCraftOnCooldown() {
        return lastCraftAttemptMs > 0L && System.currentTimeMillis() - lastCraftAttemptMs < CRAFT_COOLDOWN_MS;
    }

    private void markCraftCooldown() {
        lastCraftAttemptMs = System.currentTimeMillis();
    }

    private boolean canUseSelectedSlotForAltSilentSwap() {
        if (mc.player == null) return false;
        int selected = mc.player.getInventory().getSelectedSlot();
        return selected >= 0 && selected <= 8;
    }

    private boolean canClickPlayerInventory() {
        return mc.player != null
            && mc.interactionManager != null
            && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler == mc.player.playerScreenHandler
            && mc.player.currentScreenHandler.getCursorStack().isEmpty();
    }

    private void clickSlot(ScreenHandler handler, int slotId, int button, SlotActionType actionType) {
        if (handler == null || mc.interactionManager == null || mc.player == null) return;
        mc.interactionManager.clickSlot(handler.syncId, slotId, button, actionType, mc.player);
    }

    private int toScreenSlot(int inventorySlot) {
        return inventorySlot <= 8 ? 36 + inventorySlot : inventorySlot;
    }

    private void swapInventorySlotWithHotbar(int sourceSlot, int hotbarSlot) {
        if (!canClickPlayerInventory()) return;
        clickSlot(mc.player.currentScreenHandler, toScreenSlot(sourceSlot), hotbarSlot, SlotActionType.SWAP);
    }

    private void syncInventoryBaseline() {
        if (mc.player == null || mc.world == null) {
            resetInventoryBaseline();
            return;
        }

        long now = System.currentTimeMillis();
        if (inventoryBaselineWorld != mc.world || inventoryBaselinePlayerId != mc.player.getId()) {
            scheduleInventoryBaselineCapture(now);
            return;
        }
        if (inventoryBaselineValid && hasLoadedInventoryForBaseline()) return;
        if (inventoryBaselineReadyAtMs <= 0L) {
            inventoryBaselineReadyAtMs = now + INVENTORY_BASELINE_JOIN_DELAY_MS;
            return;
        }
        if (now < inventoryBaselineReadyAtMs) return;
        if (!hasLoadedInventoryForBaseline()) {
            inventoryBaselineReadyAtMs = now + INVENTORY_BASELINE_RETRY_DELAY_MS;
            return;
        }

        captureInventoryBaseline();
    }

    private void captureInventoryBaseline() {
        if (mc.player == null) {
            resetInventoryBaseline();
            return;
        }
        if (!hasLoadedInventoryForBaseline()) {
            inventoryBaselineValid = false;
            inventoryBaselineReadyAtMs = System.currentTimeMillis() + INVENTORY_BASELINE_RETRY_DELAY_MS;
            return;
        }

        for (int i = 0; i < inventoryProtectedSlots.length; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            inventoryBaselineStacks[i] = stack == null ? ItemStack.EMPTY : stack.copy();
            inventoryProtectedSlots[i] = shouldProtectBaselineStack(i, stack);
        }
        inventoryBaselineWorld = mc.world;
        inventoryBaselinePlayerId = mc.player.getId();
        inventoryBaselineValid = true;
        inventoryBaselineReadyAtMs = 0L;
    }

    private void resetInventoryBaseline() {
        clearInventoryBaselineContents();
        inventoryBaselineWorld = null;
        inventoryBaselinePlayerId = Integer.MIN_VALUE;
        inventoryBaselineReadyAtMs = 0L;
    }

    private void scheduleInventoryBaselineCapture(long now) {
        clearInventoryBaselineContents();
        inventoryBaselineWorld = mc.world;
        inventoryBaselinePlayerId = mc.player.getId();
        inventoryBaselineReadyAtMs = now + INVENTORY_BASELINE_JOIN_DELAY_MS;
    }

    private void clearInventoryBaselineContents() {
        for (int i = 0; i < inventoryProtectedSlots.length; i++) {
            inventoryProtectedSlots[i] = false;
            inventoryBaselineStacks[i] = ItemStack.EMPTY;
        }
        inventoryBaselineValid = false;
    }

    private boolean hasLoadedInventoryForBaseline() {
        return mc.player != null
            && mc.player.getInventory() != null
            && mc.player.currentScreenHandler != null
            && mc.player.playerScreenHandler != null;
    }

    private boolean shouldProtectBaselineStack(int slot, ItemStack stack) {
        if (isSlotLockSlot(slot)) return false;
        return stack != null && !stack.isEmpty() && !stack.isOf(Items.BONE_MEAL);
    }

    private boolean recoverBaselineProtectedItems() {
        if (!inventoryBaselineValid || mc.player == null || mc.interactionManager == null) return false;
        if (isInventoryCleanseOnCooldown()) return false;
        if (!canClickPlayerInventory()) return false;

        for (int targetSlot = 0; targetSlot < inventoryProtectedSlots.length; targetSlot++) {
            if (isSlotLockSlot(targetSlot)) continue;
            if (!inventoryProtectedSlots[targetSlot]) continue;

            ItemStack baseline = inventoryBaselineStacks[targetSlot];
            if (baseline == null || baseline.isEmpty()) continue;

            ItemStack current = stackAtInventorySlot(targetSlot);
            if (matchesBaselineProtectedStack(current, baseline)) {
                if (current.getCount() > baseline.getCount()) {
                    if (dropInventorySlot(targetSlot, false)) {
                        lastInventoryCleanseMs = System.currentTimeMillis();
                        return true;
                    }
                }
                continue;
            }

            int sourceSlot = findDisplacedBaselineStackSlot(baseline, targetSlot);
            if (sourceSlot == -1) {
                if (!current.isEmpty() && canOverwriteForBaselineRecovery(current)) {
                    if (dropInventorySlot(targetSlot, true)) {
                        lastInventoryCleanseMs = System.currentTimeMillis();
                        return true;
                    }
                }
                continue;
            }

            if (!canOverwriteForBaselineRecovery(current)) continue;

            if (swapInventorySlots(sourceSlot, targetSlot)) {
                lastInventoryCleanseMs = System.currentTimeMillis();
                return true;
            }
        }

        return false;
    }

    private boolean maybeCleanseInventoryProactively() {
        if (!inventoryCleanser.get()) return false;
        if (!inventoryBaselineValid || mc.player == null || mc.interactionManager == null) return false;
        if (!canClickPlayerInventory()) return false;

        List<Integer> slots = collectInventoryCleanseSlots();
        if (slots.isEmpty()) return false;
        return cleanseInventoryForBoneMeal(false);
    }

    private boolean cleanseInventoryForBoneMeal() {
        return cleanseInventoryForBoneMeal(false);
    }

    private boolean cleanseInventoryForBoneMeal(boolean emergency) {
        if (!inventoryCleanser.get()) return false;
        if (isInventoryCleanseOnCooldown() || !canClickPlayerInventory()) return false;
        if (!inventoryBaselineValid) return false;

        List<Integer> slots = collectInventoryCleanseSlots();
        if (slots.isEmpty() && emergency) slots = collectEmergencyInventoryCleanseSlots();
        if (slots.isEmpty()) return false;

        int invSlot = slots.get(0);
        if (!isInventoryCleanseDropCandidate(stackAtInventorySlot(invSlot))) return false;

        if (!dropInventorySlot(invSlot, true)) return false;
        lastInventoryCleanseMs = System.currentTimeMillis();
        return true;
    }

    private List<Integer> collectInventoryCleanseSlots() {
        if (mc.player == null) return List.of();

        List<Integer> slots = new ArrayList<>();
        collectInventoryCleanseSlots(slots, 9, 36, true);
        collectInventoryCleanseSlots(slots, 0, 9, true);
        return slots;
    }

    private List<Integer> collectEmergencyInventoryCleanseSlots() {
        if (mc.player == null) return List.of();

        List<Integer> slots = new ArrayList<>();
        collectInventoryCleanseSlots(slots, 9, 36, false);
        collectInventoryCleanseSlots(slots, 0, 9, true);
        return slots;
    }

    private void collectInventoryCleanseSlots(List<Integer> slots, int start, int end, boolean baselineOnly) {
        for (int i = start; i < end && i < inventoryProtectedSlots.length; i++) {
            if (baselineOnly && inventoryProtectedSlots[i]) continue;
            if (baselineOnly && !isInventoryCleanseDisposableSlot(i)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isInventoryCleanseDropCandidate(stack)) slots.add(i);
        }
    }

    private boolean isInventoryCleanseDisposableSlot(int slot) {
        if (slot < 0 || slot >= inventoryBaselineStacks.length) return false;
        if (isSlotLockSlot(slot)) return true;
        ItemStack baseline = inventoryBaselineStacks[slot];
        return baseline == null || baseline.isEmpty() || baseline.isOf(Items.BONE_MEAL);
    }

    private boolean isInventoryCleanseDropCandidate(ItemStack stack) {
        return stack != null && !stack.isEmpty() && !isInventoryCleanseProtectedStack(stack);
    }

    private boolean isInventoryCleanseProtectedStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        if (stack.isOf(Items.BONE_MEAL) || stack.isOf(Blocks.BONE_BLOCK.asItem())) return true;
        if (stack.isOf(Blocks.MOSS_BLOCK.asItem()) || stack.isOf(Blocks.PALE_MOSS_BLOCK.asItem())) return true;
        if (stack.isOf(Items.ENDER_CHEST)
            || stack.isOf(Items.ENDER_PEARL)
            || stack.isOf(Items.TOTEM_OF_UNDYING)
            || stack.isOf(Items.EXPERIENCE_BOTTLE)
            || stack.isOf(Items.GOLDEN_APPLE)
            || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)
            || stack.isOf(Items.FIREWORK_ROCKET)
            || stack.isOf(Items.OBSIDIAN)) {
            return true;
        }
        if (stack.isDamageable()) return true;
        if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) return true;
        if (stack.hasEnchantments()) return true;
        ItemEnchantmentsComponent storedEnchantments = stack.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        if (storedEnchantments != null && !storedEnchantments.isEmpty()) return true;
        return stack.contains(DataComponentTypes.CONTAINER)
            || stack.contains(DataComponentTypes.BLOCK_ENTITY_DATA)
            || stack.contains(DataComponentTypes.CUSTOM_NAME)
            || stack.contains(DataComponentTypes.DEATH_PROTECTION);
    }

    private int findDisplacedBaselineStackSlot(ItemStack baseline, int targetSlot) {
        if (mc.player == null || baseline == null || baseline.isEmpty()) return -1;

        for (int i = 0; i < inventoryProtectedSlots.length; i++) {
            if (i == targetSlot || inventoryProtectedSlots[i]) continue;
            if (!isInventoryCleanseDisposableSlot(i)) continue;
            if (matchesBaselineProtectedStack(stackAtInventorySlot(i), baseline)) return i;
        }
        return -1;
    }

    private boolean canOverwriteForBaselineRecovery(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        if (stack.isOf(Items.BONE_MEAL)) return true;
        return isInventoryCleanseDropCandidate(stack);
    }

    private boolean matchesBaselineProtectedStack(ItemStack stack, ItemStack baseline) {
        return stack != null
            && baseline != null
            && !stack.isEmpty()
            && !baseline.isEmpty()
            && ItemStack.areItemsAndComponentsEqual(stack, baseline);
    }

    private boolean dropInventorySlot(int inventorySlot, boolean fullStack) {
        if (!canClickPlayerInventory()) return false;

        ItemStack stack = stackAtInventorySlot(inventorySlot);
        if (stack.isOf(Items.BONE_MEAL) || stack.isOf(Blocks.BONE_BLOCK.asItem())) return false;

        clickSlot(mc.player.currentScreenHandler, toScreenSlot(inventorySlot), fullStack ? 1 : 0, SlotActionType.THROW);
        return true;
    }

    private boolean swapInventorySlots(int sourceSlot, int targetSlot) {
        if (sourceSlot == targetSlot) return true;
        if (!canClickPlayerInventory()) return false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (targetSlot >= 0 && targetSlot <= 8) {
            clickSlot(handler, toScreenSlot(sourceSlot), targetSlot, SlotActionType.SWAP);
            return true;
        }
        if (sourceSlot >= 0 && sourceSlot <= 8) {
            clickSlot(handler, toScreenSlot(targetSlot), sourceSlot, SlotActionType.SWAP);
            return true;
        }

        int sourceScreenSlot = toScreenSlot(sourceSlot);
        int targetScreenSlot = toScreenSlot(targetSlot);
        clickSlot(handler, sourceScreenSlot, 0, SlotActionType.PICKUP);
        clickSlot(handler, targetScreenSlot, 0, SlotActionType.PICKUP);
        clickSlot(handler, sourceScreenSlot, 0, SlotActionType.PICKUP);
        return true;
    }

    private ItemStack stackAtInventorySlot(int slot) {
        if (mc.player == null || slot < 0 || slot >= 36) return ItemStack.EMPTY;
        ItemStack stack = mc.player.getInventory().getStack(slot);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    private boolean isInventoryCleanseOnCooldown() {
        return lastInventoryCleanseMs > 0L
            && System.currentTimeMillis() - lastInventoryCleanseMs < INVENTORY_CLEANSE_COOLDOWN_MS;
    }

    private boolean hasBoneMeal() {
        if (mc.player == null) return false;
        return mc.player.getOffHandStack().isOf(Items.BONE_MEAL) || findBoneMealSlot() != -1;
    }

    private int findBoneMealHotbarSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.BONE_MEAL)) return i;
        }
        return -1;
    }

    private int findBoneMealSlot() {
        if (mc.player == null) return -1;
        int hotbarSlot = findBoneMealHotbarSlot();
        if (hotbarSlot != -1) return hotbarSlot;
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.BONE_MEAL)) return i;
        }
        return -1;
    }

    private int getBoneMealSlotForUse() {
        if (!slotLock.get()) {
            resetSlotLockSwapState();
            return findBoneMealSlot();
        }
        return ensureLockedBoneMealSlot() ? lockedHotbarSlot() : -1;
    }

    private boolean ensureLockedBoneMealSlot() {
        if (mc.player == null || mc.interactionManager == null) return false;

        int lockedSlot = lockedHotbarSlot();
        ItemStack lockedStack = mc.player.getInventory().getStack(lockedSlot);
        if (lockedStack.isOf(Items.BONE_MEAL)) {
            resetSlotLockSwapState();
            return true;
        }

        if (isSlotLockSwapPending(lockedSlot)) return false;

        int sourceSlot = findBoneMealSlotExcept(lockedSlot);
        if (sourceSlot == -1) return false;
        if (!canClickPlayerInventory()) return false;

        swapInventorySlotWithHotbar(sourceSlot, lockedSlot);
        markSlotLockSwapPending(sourceSlot, lockedSlot);
        return false;
    }

    private boolean isSlotLockSwapPending(int lockedSlot) {
        if (slotLockSwapWaitUntilMs <= 0L) return false;
        if (slotLockPendingTargetSlot != lockedSlot) {
            resetSlotLockSwapState();
            return false;
        }
        if (System.currentTimeMillis() < slotLockSwapWaitUntilMs) return true;

        resetSlotLockSwapState();
        return false;
    }

    private void markSlotLockSwapPending(int sourceSlot, int lockedSlot) {
        slotLockPendingSourceSlot = sourceSlot;
        slotLockPendingTargetSlot = lockedSlot;
        slotLockSwapWaitUntilMs = System.currentTimeMillis() + SLOT_LOCK_SWAP_RESPONSE_MS;
    }

    private void resetSlotLockSwapState() {
        slotLockPendingSourceSlot = -1;
        slotLockPendingTargetSlot = -1;
        slotLockSwapWaitUntilMs = 0L;
    }

    private boolean isSlotLockSlot(int slot) {
        return slotLock.get() && slot == lockedHotbarSlot();
    }

    private int findBoneMealSlotExcept(int excludedSlot) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 36; i++) {
            if (i == excludedSlot) continue;
            if (mc.player.getInventory().getStack(i).isOf(Items.BONE_MEAL)) return i;
        }
        return -1;
    }

    private int lockedHotbarSlot() {
        return Math.max(0, Math.min(8, slot.get() - 1));
    }

    private int findMossBlockSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Blocks.MOSS_BLOCK.asItem()) || stack.isOf(Blocks.PALE_MOSS_BLOCK.asItem())) {
                return i;
            }
        }
        return -1;
    }

    private Block findAvailableMossBlock() {
        return mossBlockForSlot(findMossBlockSlot());
    }

    private Block blockForSlot(int slot) {
        if (mc.player == null || slot < 0 || slot >= 36) return null;
        ItemStack stack = mc.player.getInventory().getStack(slot);
        if (stack.getItem() instanceof BlockItem blockItem) return blockItem.getBlock();
        return null;
    }

    private Block mossBlockForSlot(int slot) {
        Block block = blockForSlot(slot);
        if (block == Blocks.MOSS_BLOCK || block == Blocks.PALE_MOSS_BLOCK) return block;
        return null;
    }

    private boolean hasHotbarShovel() {
        if (mc.player == null) return false;
        for (int slot = 0; slot < 9; slot++) {
            if (isShovel(mc.player.getInventory().getStack(slot))) return true;
        }
        return false;
    }

    private boolean isShovel(ItemStack stack) {
        return stack != null && (stack.isOf(Items.WOODEN_SHOVEL)
            || stack.isOf(Items.STONE_SHOVEL)
            || stack.isOf(Items.IRON_SHOVEL)
            || stack.isOf(Items.GOLDEN_SHOVEL)
            || stack.isOf(Items.DIAMOND_SHOVEL)
            || stack.isOf(Items.NETHERITE_SHOVEL));
    }

    private boolean isFallbackPlaceOnCooldown() {
        return lastFallbackPlaceMs > 0L && System.currentTimeMillis() - lastFallbackPlaceMs < fallbackPlaceCooldownMs();
    }

    private long fallbackPlaceCooldownMs() {
        return MOSS_COOLDOWN_MS * 4L;
    }

    private boolean isBelowMinY(BlockPos pos) {
        return pos != null && pos.getY() < minY.get();
    }

    private double distanceSqToBlock(Vec3d eye, BlockPos pos) {
        return RangeUtil.distanceSqToBox(eye, new Box(pos));
    }

    private static final Comparator<Target> TARGET_ORDER = Comparator
        .comparingDouble(Target::score)
        .thenComparingInt(target -> target.pos().getY())
        .thenComparingInt(target -> target.pos().getX())
        .thenComparingInt(target -> target.pos().getZ());

    private enum TargetKind {
        MOSS,
        AZALEA
    }

    private record Target(BlockPos pos, TargetKind kind, double score, BlockPos obstructionPos) {
    }

    private record SpreadCacheKey(long packedPos, boolean assumeSourceMoss) {
    }

    private record CachedSpreadTargets(Set<BlockPos> targets, long expiresAtMs) {
    }

    private record MossScore(boolean valid, double value) {
        private static MossScore invalid() {
            return new MossScore(false, Double.POSITIVE_INFINITY);
        }
    }
}
