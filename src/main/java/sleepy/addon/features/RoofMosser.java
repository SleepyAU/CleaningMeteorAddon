package sleepy.addon.features;

import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AutoLog;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.PlayerInput;
import org.lwjgl.glfw.GLFW;
import sleepy.addon.SleepyAddon;
import sleepy.addon.events.UpdateEvent;
import sleepy.addon.manager.PlacementManager;
import sleepy.addon.util.BaritoneSelectionHelper;
import sleepy.addon.util.CursorSafeChestSwap;
import sleepy.addon.util.RangeUtil;
import sleepy.addon.util.RoofMossCoordinationClient;
import sleepy.addon.util.SneakDesyncTracker;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Covers the irregular upper surface of an old-height roof one claimed 16x16 chunk at a time. */
public final class RoofMosser extends Module {
    private static final int CHUNK_SIZE = 16;
    private static final int REQUIRED_CLEAN_PASSES = 2;
    private static final int LOCAL_SCAN_RADIUS = 5;
    private static final double ROUTE_WORK_RADIUS = 3.75D;
    private static final int ROOF_SCAN_BELOW = 2;
    private static final int ROOF_SCAN_ABOVE = 4;
    private static final double NEARBY_WALK_HORIZONTAL = 10.0D;
    private static final double NEARBY_WALK_VERTICAL = 3.0D;
    private static final long PATH_REFRESH_MS = 4_000L;
    private static final long PATH_STUCK_MS = 30_000L;
    private static final long ELYTRA_STUCK_MS = 90_000L;
    private static final long ENDPOINT_SETTLE_MS = 650L;
    private static final long CHUNK_VERIFICATION_MS = 600L;
    private static final long INVENTORY_CLICK_MS = 175L;
    private static final long INVENTORY_RESPONSE_MS = 1_250L;
    private static final long SHULKER_PLACEMENT_CONFIRM_MS = 5_000L;
    private static final long SHULKER_BURST_CONFIRM_MS = 300L;
    private static final long ELYTRA_EQUIP_SETTLE_MS = 300L;
    private static final long ELYTRA_GHOST_REFRESH_SETTLE_MS = 300L;
    private static final long ELYTRA_GHOST_REFRESH_RETRY_MS = 5_000L;
    private static final long ELYTRA_TAKEOFF_TIMEOUT_MS = 5_000L;
    private static final long ELYTRA_TAKEOFF_SLOWDOWN_TIMEOUT_MS = 8_000L;
    private static final int STRICT_GLIDE_RETRY_TICKS = 4;
    private static final long ELYTRA_ROCKET_RETRY_MS = 1_500L;
    private static final double ELYTRA_TAKEOFF_TIMER = 0.1D;
    private static final long SNEAK_REPAIR_PRESS_MS = 100L;
    private static final long SNEAK_REPAIR_CONFIRM_MS = 300L;
    private static final long SNEAK_REPAIR_RETRY_MS = 1_000L;
    private static final long CONTAINER_OPEN_RETRY_MS = 1_000L;
    private static final long API_RETRY_MS = 10_000L;
    private static final long LEASE_SAFETY_MARGIN_MS = 15_000L;
    private static final long RESCAN_MS = 30_000L;
    private static final long PICKUP_WARNING_MS = 15_000L;
    private static final double DIRECT_PICKUP_RANGE = 6.0D;
    private static final double PICKUP_STOP_DISTANCE = 0.45D;
    private static final long PICKUP_PATH_REFRESH_MS = 2_000L;
    private static final long WARNING_COOLDOWN_MS = 10_000L;
    private static final double SILENT_MINE_PRIORITY = 100.0D;
    private static final String COORDINATION_API_URL = "https://mossapi.sleepyfemboy.dev";
    private static final String COORDINATION_PROJECT = "2b2t-spawn-roof";
    private static final int COORDINATION_LEASE_SECONDS = 15 * 60;
    private static final int[][] OVERHEAD_OFFSETS = {
        {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgCoordination = settings.createGroup("Coordination API");
    private final SettingGroup sgRegear = settings.createGroup("Shulker Regear");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> roofY = sgGeneral.add(new IntSetting.Builder()
        .name("roof-y").description("Reference Y level for the roof surface; nearby height damage is handled automatically.")
        .defaultValue(255).range(-64, 319).sliderRange(-64, 319).build());
    private final Setting<Integer> centerX = sgGeneral.add(new IntSetting.Builder()
        .name("center-x").description("Center of the full job boundary, not its starting point.")
        .defaultValue(0).range(-30_000_000, 30_000_000).sliderRange(-10_000, 10_000).build());
    private final Setting<Integer> centerZ = sgGeneral.add(new IntSetting.Builder()
        .name("center-z").description("Center of the full job boundary, not its starting point.")
        .defaultValue(0).range(-30_000_000, 30_000_000).sliderRange(-10_000, 10_000).build());
    private final Setting<Integer> areaSize = sgGeneral.add(new IntSetting.Builder()
        .name("area-size").description("Full square job width/depth; work starts at the nearest unfinished chunk inside it.")
        .defaultValue(10_000).range(CHUNK_SIZE, 20_000).sliderRange(1_000, 20_000).build());
    private final Setting<Integer> laneSpacing = sgGeneral.add(new IntSetting.Builder()
        .name("lane-spacing").description("Distance between verification lanes inside each chunk.")
        .defaultValue(5).range(3, 6).sliderRange(3, 6).build());
    private final Setting<Integer> mossSlot = sgGeneral.add(new IntSetting.Builder()
        .name("moss-slot").description("Hotbar slot kept supplied with loose moss blocks.")
        .defaultValue(1).range(1, 9).sliderRange(1, 9).build());

    private final Setting<Boolean> autoEat = sgSafety.add(new BoolSetting.Builder()
        .name("auto-eat").description("Temporarily enables Meteor Auto Eat and pauses Roof Mosser while eating.")
        .defaultValue(true).build());
    private final Setting<Boolean> totemLog = sgSafety.add(new BoolSetting.Builder()
        .name("totem-log").description("Temporarily configures Meteor Auto Log to disconnect after one totem pop only.")
        .defaultValue(true).build());

    private final Setting<String> apiKey = sgCoordination.add(new StringSetting.Builder()
        .name("api-key").description("Shared project key supplied by the organizer.")
        .defaultValue("").build());

    private final Setting<Boolean> autoRegear = sgRegear.add(new BoolSetting.Builder()
        .name("auto-regear").description("Unloads moss shulkers and exchanges empties at recorded stations.")
        .defaultValue(true).build());
    private final Setting<Integer> lowMoss = sgRegear.add(new IntSetting.Builder()
        .name("low-moss").description("Opens another shulker at or below this loose moss count.")
        .defaultValue(32).range(0, 576).sliderRange(0, 576).visible(autoRegear::get).build());
    private final Setting<Integer> shulkerSlot = sgRegear.add(new IntSetting.Builder()
        .name("shulker-slot").description("Temporary hotbar slot used to place a moss shulker.")
        .defaultValue(9).range(1, 9).sliderRange(1, 9).visible(autoRegear::get).build());
    private final Setting<String> stationData = sgRegear.add(new StringSetting.Builder()
        .name("recorded-stations").description("Persisted regear container positions.")
        .defaultValue("").visible(() -> false).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Renders the current work, station, or shulker target.")
        .defaultValue(true).build());
    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("target-color").description("Color of the current target.")
        .defaultValue(new SettingColor(80, 255, 110, 180)).visible(render::get).build());

    private final Set<ChunkPos> queuedChunks = new HashSet<>();
    private final ArrayDeque<ChunkPos> deferredChunks = new ArrayDeque<>();
    private final List<BlockPos> chunkRoute = new ArrayList<>();
    private final List<Station> stationCandidates = new ArrayList<>();
    private final Map<ChunkPos, PendingVerification> pendingVerifications = new LinkedHashMap<>();
    private final Map<ChunkPos, PendingCompletion> pendingCompletions = new LinkedHashMap<>();

    private BlockPos sectorMin, sectorMax;
    private int activeRoofY;
    private String activeDimension = "";
    private ChunkPos currentChunk;
    private ChunkPos plannedNextChunk;
    private int completedChunkCount, totalChunkCount;
    private int waypointIndex, chunkPass = 1;
    private WorkState state = WorkState.SelectingChunk;

    private BlockPos activePathTarget;
    private long lastPathRefreshMs, pathProgressMs, lastPlacementMs;
    private double pathBestDistanceSq = Double.POSITIVE_INFINITY;
    private boolean usingElytraTravel;
    private boolean elytraPathPrewarming;
    private long elytraEquipRequestedAtMs, elytraGhostRefreshRequestedAtMs;
    private long lastElytraGhostRefreshMs, manualTakeoffStartedAtMs;
    private long lastManualRocketMs;
    private int strictLastGlideAttemptAge = Integer.MIN_VALUE;
    private boolean manualTakeoffJumped, strictNeedsAirborneStart;
    private double manualTakeoffJumpStartY = Double.NaN;
    private boolean manualTakeoffJumpRendered;
    private boolean restoreChestplateAfterFlight, chestRestorePending;
    private int flightEquipmentSwapSlot = -1;
    private boolean takeoffTimerOverrideActive;
    private long takeoffTimerStartedAtMs;
    private boolean takeoffPitchHoldActive;
    private float takeoffPitchBeforeHold;
    private Method boostedEntityMethod;
    private boolean boostedEntityMethodUnavailable;
    private long lastInventoryClickMs, lastContainerOpenAttemptMs, stateStartedMs, lastWarningMs;

    private RoofMossCoordinationClient coordination;
    private String activeWorkerId = "";
    private CompletableFuture<RoofMossCoordinationClient.Reply> apiRequest, renewRequest;
    private CompletableFuture<RoofMossCoordinationClient.Reply> prefetchedClaimRequest;
    private CompletableFuture<Void> prefetchedClaimCleanup = CompletableFuture.completedFuture(null);
    private ApiAction apiAction = ApiAction.None;
    private ChunkPos apiChunk, prefetchedClaimChunk;
    private WorkState stateAfterApiWait = WorkState.SelectingChunk;
    private long apiRetryAtMs, lastRenewAttemptMs, leaseValidUntilMs, activeLeaseDurationMs;
    private boolean leasePaused;

    private boolean stationCaptureMode, activatedForCapture;
    private final Set<String> capturedStations = new LinkedHashSet<>();
    private Station currentStation;
    private BlockPos workReturnPos;
    private boolean initialWorkTransitPending;
    private int pendingDepositCount = -1, pendingTakeCount = -1;
    private boolean moduleOpenedContainer, unloadAfterReturn;

    private BlockPos deployedShulkerPos;
    private Block deployedShulkerBlock;
    private int deployedShulkerHotbar = -1, shulkersBeforeMine;
    private long shulkerPlacementSentAtMs;
    private int returnableShulkerSlot = -1;
    private boolean recoveredShulkerNeedsReturn;
    private final Set<Integer> shulkerInventorySlotsBeforeMine = new HashSet<>();
    private final Set<Integer> shulkerItemEntitiesBeforeMine = new HashSet<>();
    private int droppedShulkerEntityId = -1;
    private boolean pickupMovementActive;
    private long lastPickupPathRefreshMs;
    private String blockAfterShulkerPickup;
    private long unloadBatchSentAtMs;
    private boolean unloadBatchPending;
    private boolean autoEnabledSilentMine;

    private boolean safetyModulesConfigured, autoEatManaged, autoLogManaged, autoEatWasActive, autoLogWasActive;
    private final Map<String, Object> previousAutoLogSettings = new LinkedHashMap<>();
    private SneakRepairStage sneakRepairStage = SneakRepairStage.Idle;
    private long sneakRepairStageAtMs;

    public RoofMosser() {
        super(SleepyAddon.CATEGORY, "roof-mosser", "Coordinates and mosses an irregular roof surface one complete chunk at a time.");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WHorizontalList row = list.add(theme.horizontalList()).expandX().widget();
        WButton set = row.add(theme.button("Set Regear Stations")).expandCellX().widget();
        set.action = this::beginStationCapture;
        WButton clear = row.add(theme.button("Clear Stations")).expandCellX().widget();
        clear.action = this::clearStations;
        return list;
    }

    @Override
    public void onActivate() {
        clearAutomationState();
        if (stationCaptureMode) {
            state = WorkState.StationCapture;
            promptStationCapture();
            return;
        }
        if (mc.player == null || mc.world == null) {
            error("Join a world before enabling Roof Mosser."); toggle(); return;
        }
        if (!BaritoneSelectionHelper.isBaritoneAvailable()) {
            error("Roof Mosser requires Baritone."); toggle(); return;
        }
        activeRoofY = roofY.get();
        int targetY = activeRoofY + 1;
        if (!mc.world.isInBuildLimit(new BlockPos(mc.player.getBlockX(), targetY, mc.player.getBlockZ()))) {
            error("Moss Y %d is outside this world's build limit.", targetY); toggle(); return;
        }
        activeDimension = mc.world.getRegistryKey().getValue().toString();
        int requestedSize = Math.max(CHUNK_SIZE, areaSize.get());
        int chunksPerAxis = Math.floorDiv(requestedSize + CHUNK_SIZE - 1, CHUNK_SIZE);
        long centerChunkX = Math.floorDiv(centerX.get(), CHUNK_SIZE);
        long centerChunkZ = Math.floorDiv(centerZ.get(), CHUNK_SIZE);
        long minChunkX = centerChunkX - chunksPerAxis / 2L;
        long minChunkZ = centerChunkZ - chunksPerAxis / 2L;
        long minX = minChunkX * CHUNK_SIZE;
        long minZ = minChunkZ * CHUNK_SIZE;
        long maxX = (minChunkX + chunksPerAxis) * CHUNK_SIZE - 1L;
        long maxZ = (minChunkZ + chunksPerAxis) * CHUNK_SIZE - 1L;
        if (minX < Integer.MIN_VALUE || minZ < Integer.MIN_VALUE
            || maxX > Integer.MAX_VALUE || maxZ > Integer.MAX_VALUE) {
            error("Configured roof area exceeds valid block coordinates."); toggle(); return;
        }
        sectorMin = new BlockPos((int) minX, targetY, (int) minZ);
        sectorMax = new BlockPos((int) maxX, targetY, (int) maxZ);
        buildChunkQueue();
        configureCoordination();
        if (!isActive()) return;
        configureSafetyModules();
        BaritoneSelectionHelper.suppressBuilderActions(false);
        warnAboutConflictingModules();
        if (autoRegear.get() && !BaritoneSelectionHelper.isModernElytraAvailable()) {
            warning("Installed Baritone lacks the updated Overworld Elytra process; regear travel will walk.");
        }
        if (autoRegear.get() && stationsForCurrentDimension().isEmpty()) {
            warning("No regear stations recorded. Use Set Regear Stations before AFKing.");
        }
        int actualSize = chunksPerAxis * CHUNK_SIZE;
        info("Roof job: %dx%d blocks centered on the chunk at %d,%d; %d chunks at Y %d (shared API).",
            actualSize, actualSize, centerX.get(), centerZ.get(), totalChunkCount, activeRoofY);
    }

    @Override
    public void onDeactivate() {
        releaseCurrentClaim();
        releasePendingVerificationClaims();
        abandonPrefetchedClaim();
        cancelSneakRepair();
        BaritoneSelectionHelper.stopOwnedElytra();
        releaseManualTakeoffInput();
        closeOwnedContainer();
        restoreFlightChestplate(true);
        BaritoneSelectionHelper.cancelPathing();
        BaritoneSelectionHelper.restoreBuilderActions();
        restoreSilentMine();
        restoreSafetyModules();
        clearAutomationState();
        stationCaptureMode = false;
        activatedForCapture = false;
    }

    @Override
    public String getInfoString() {
        if (state == WorkState.Working && currentChunk != null) {
            return "chunk " + currentChunk.x + "," + currentChunk.z + " • pass " + chunkPass;
        }
        return state.label;
    }

    @EventHandler
    private void onTick(UpdateEvent event) {
        if (state == WorkState.StationCapture) {
            if (mc.player != null && mc.world != null && currentChunk != null) {
                pollApiRequest();
                tickLeaseRenewal();
            }
            return;
        }
        if (mc.player == null || mc.world == null || mc.interactionManager == null || sectorMin == null) return;
        restoreFlightChestplate(false);
        BaritoneSelectionHelper.enforcePathingOnly(false);
        pollApiRequest();
        pollPendingVerifications();
        pollCompletionRequests();
        tickLeaseRenewal();
        if (leasePaused) {
            BaritoneSelectionHelper.stopOwnedElytra(); BaritoneSelectionHelper.cancelPathing();
            scheduleFlightChestRestore(); return;
        }
        if (isAutoEating()) {
            BaritoneSelectionHelper.cancelPathing(); resetPathState(); return;
        }

        if (state == WorkState.ApiWaiting) {
            if (System.currentTimeMillis() >= apiRetryAtMs) state = stateAfterApiWait;
            return;
        }
        if (state == WorkState.SelectingChunk) { selectNextChunk(); return; }
        if (state == WorkState.ClaimingChunk) { tickClaimPrepositioning(); return; }
        if (isRegearState(state)) { tickRegear(); return; }
        if (state == WorkState.Blocked) { BaritoneSelectionHelper.cancelPathing(); return; }
        if (state != WorkState.Working || currentChunk == null) return;

        if (hasExternalScreenHandler()) { BaritoneSelectionHelper.cancelPathing(); return; }
        int mossCount = countLooseMoss();
        if (autoRegear.get() && mossCount <= lowMoss.get()) {
            beginMossRefill();
            return;
        }
        if (initialWorkTransitPending) {
            if (beginInitialWorkTransit()) return;
            initialWorkTransitPending = false;
        }
        if (!isOnRoofSurface()) {
            blockAutomation("The player left the upper roof surface; placement stopped safely."); return;
        }
        HotbarStatus hotbar = ensureMossHotbarSlot();
        if (hotbar == HotbarStatus.Moved) return;
        if (hotbar == HotbarStatus.Empty) {
            BaritoneSelectionHelper.cancelPathing();
            if (autoRegear.get()) {
                beginMossRefill();
            } else throttleWarning("No loose moss; add moss or enable Auto Regear.");
            return;
        }
        int movementQuota = PlacementManager.get().getRemainingQuota();
        placeLocalMoss();
        if (movementQuota == 0) {
            BaritoneSelectionHelper.suppressPathingMovementForTick();
            pathProgressMs = System.currentTimeMillis();
            return;
        }
        tickChunkSweep();
    }

    /* Chunk scheduler and verification. */

    private void buildChunkQueue() {
        queuedChunks.clear(); deferredChunks.clear();
        int minX = Math.floorDiv(sectorMin.getX(), CHUNK_SIZE), maxX = Math.floorDiv(sectorMax.getX(), CHUNK_SIZE);
        int minZ = Math.floorDiv(sectorMin.getZ(), CHUNK_SIZE), maxZ = Math.floorDiv(sectorMax.getZ(), CHUNK_SIZE);
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) chunks.add(new ChunkPos(x, z));
        queuedChunks.addAll(chunks);
        totalChunkCount = chunks.size();
    }

    private void selectNextChunk() {
        if (apiRequest != null) return;
        if (queuedChunks.isEmpty()) {
            if (!deferredChunks.isEmpty()) {
                for (ChunkPos deferred : deferredChunks) {
                    queuedChunks.add(deferred);
                }
                deferredChunks.clear();
                waitForApi(WorkState.SelectingChunk, RESCAN_MS,
                    "All remaining chunks are claimed by other bots; checking again shortly.");
                return;
            }
            if (!pendingVerifications.isEmpty() || !pendingCompletions.isEmpty()) return;
            info("Roof sector complete: %d/%d chunks confirmed.", completedChunkCount, totalChunkCount);
            toggle(); return;
        }
        ChunkPos candidate = takePlannedOrNearestChunk();
        if (coordination == null) { startChunk(candidate); return; }
        prepareChunk(candidate, WorkState.ClaimingChunk);
        apiChunk = candidate;
        apiAction = ApiAction.Claim;
        if (Objects.equals(candidate, prefetchedClaimChunk) && prefetchedClaimRequest != null) {
            apiRequest = prefetchedClaimRequest;
            prefetchedClaimChunk = null;
            prefetchedClaimRequest = null;
        } else {
            abandonPrefetchedClaim();
            apiRequest = coordination.claim(candidate.x, candidate.z, leaseSeconds());
        }
        // A prefetched claim will normally already be complete. Consume it immediately so the
        // next normal tick can place into this chunk even while still approaching its first lane.
        pollApiRequest();
        if (state == WorkState.ClaimingChunk) tickClaimPrepositioning();
    }

    private void startChunk(ChunkPos chunk) {
        prepareChunk(chunk, WorkState.Working);
        info("Working chunk %d, %d (%d/%d complete).", chunk.x, chunk.z, completedChunkCount, totalChunkCount);
    }

    private void prepareChunk(ChunkPos chunk, WorkState nextState) {
        currentChunk = chunk;
        buildChunkRoute(); planNextChunk();
        waypointIndex = 0; chunkPass = 1;
        resetPathState(); state = nextState;
    }

    private void activatePreparedChunk(ChunkPos chunk) {
        if (!Objects.equals(currentChunk, chunk)) prepareChunk(chunk, WorkState.Working);
        else state = WorkState.Working;
        info("Working chunk %d, %d (%d/%d complete).", chunk.x, chunk.z, completedChunkCount, totalChunkCount);
    }

    /** Walks toward the nearest route entrance while the chunk claim is in flight. */
    private void tickClaimPrepositioning() {
        if (currentChunk == null || chunkRoute.isEmpty()) return;
        tickPrepositionPath(chunkRoute.get(0));
    }

    private void tickPrepositionPath(BlockPos target) {
        long now = System.currentTimeMillis();
        double distance = distanceSq(target);
        if (!target.equals(activePathTarget)) beginPathTo(target, distance, now);
        if (distance <= 3.0D) {
            BaritoneSelectionHelper.cancelPathing();
            return;
        }
        updatePathProgress(distance, now);
        if (now - pathProgressMs >= PATH_STUCK_MS) {
            BaritoneSelectionHelper.cancelPathing();
            return;
        }
        if (lastPathRefreshMs == 0L || now - lastPathRefreshMs >= PATH_REFRESH_MS) {
            if (BaritoneSelectionHelper.pathTo(target)) lastPathRefreshMs = now;
        }
    }

    private void buildChunkRoute() {
        chunkRoute.clear();
        List<Integer> zs = axisPoints(currentChunk.getStartZ(), currentChunk.getEndZ(), Math.max(3, laneSpacing.get()));
        int left = currentChunk.getStartX() + 2, right = currentChunk.getEndX() - 2, y = activeRoofY + 2;
        BlockPos loadProbe = new BlockPos(currentChunk.getStartX(), activeRoofY, currentChunk.getStartZ());
        if (!mc.world.isChunkLoaded(loadProbe)) {
            int entryX = MathHelper.clamp(mc.player.getBlockX(), left, right);
            int entryZ = MathHelper.clamp(mc.player.getBlockZ(), currentChunk.getStartZ() + 2,
                currentChunk.getEndZ() - 2);
            chunkRoute.add(new BlockPos(entryX, y, entryZ));
            return;
        }

        int[] minX = new int[zs.size()];
        int[] maxX = new int[zs.size()];
        Arrays.fill(minX, Integer.MAX_VALUE);
        Arrays.fill(maxX, Integer.MIN_VALUE);
        for (int x = currentChunk.getStartX(); x <= currentChunk.getEndX(); x++) {
            for (int z = currentChunk.getStartZ(); z <= currentChunk.getEndZ(); z++) {
                if (findMissingRoofTarget(x, z) == null) continue;
                int row = closestLaneIndex(zs, z);
                minX[row] = Math.min(minX[row], x);
                maxX[row] = Math.max(maxX[row], x);
            }
        }

        List<RouteLane> routeLanes = new ArrayList<>();
        for (int row = 0; row < zs.size(); row++) {
            if (minX[row] == Integer.MAX_VALUE) continue;
            int z = zs.get(row);
            int rowLeft = MathHelper.clamp(minX[row], left, right);
            int rowRight = MathHelper.clamp(maxX[row], left, right);
            routeLanes.add(new RouteLane(z, rowLeft, rowRight));
        }

        List<BlockPos> bestRoute = List.of();
        double bestEntryDistance = Double.POSITIVE_INFINITY;
        for (boolean reverseRows : new boolean[] {false, true}) {
            for (boolean startLeft : new boolean[] {true, false}) {
                List<BlockPos> candidate = buildRouteCandidate(routeLanes, y, reverseRows, startLeft);
                if (candidate.isEmpty()) continue;
                double entryDistance = horizontalDistanceSq(candidate.get(0));
                if (entryDistance < bestEntryDistance) {
                    bestEntryDistance = entryDistance;
                    bestRoute = candidate;
                }
            }
        }
        chunkRoute.addAll(bestRoute);
    }

    private List<BlockPos> buildRouteCandidate(List<RouteLane> lanes, int y, boolean reverseRows,
                                                boolean startLeft) {
        List<BlockPos> route = new ArrayList<>();
        for (int index = 0; index < lanes.size(); index++) {
            int laneIndex = reverseRows ? lanes.size() - 1 - index : index;
            RouteLane lane = lanes.get(laneIndex);
            boolean leftToRight = (index & 1) == 0 ? startLeft : !startLeft;
            int firstX = leftToRight ? lane.left() : lane.right();
            int secondX = leftToRight ? lane.right() : lane.left();
            route.add(new BlockPos(firstX, y, lane.z()));
            // Keep every lane as an entry/exit pair, including one-block-wide remnants. The
            // scheduler relies on these pairs so it can skip or reverse a whole lane atomically.
            route.add(new BlockPos(secondX, y, lane.z()));
        }
        return route;
    }

    private int closestLaneIndex(List<Integer> lanes, int z) {
        int closest = 0, closestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < lanes.size(); i++) {
            int distance = Math.abs(lanes.get(i) - z);
            if (distance < closestDistance) {
                closest = i;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private List<Integer> axisPoints(int min, int max, int spacing) {
        int first = min + 2, last = max - 2;
        List<Integer> points = new ArrayList<>();
        for (int value = first; value <= last; value += spacing) points.add(value);
        if (points.get(points.size() - 1) != last) points.add(last);
        return points;
    }

    private void tickChunkSweep() {
        // Hide the next API round trip under the final two lanes instead of waiting at the chunk
        // boundary after the current sweep has already finished.
        if (waypointIndex >= Math.max(0, chunkRoute.size() - 4)) prefetchPlannedClaim();

        // A route lane is always an entry/exit pair. Process completed and already-reached
        // waypoints in one call so the next Baritone goal is issued in this same tick.
        int handoffBudget = chunkRoute.size() + 1;
        while (handoffBudget-- > 0) {
            if (waypointIndex >= chunkRoute.size()) { finishChunkPass(); return; }

            if ((waypointIndex & 1) == 0) {
                orientCurrentLaneFromPlayer();
                if (!currentRouteLaneHasWork()) {
                    waypointIndex += 2;
                    resetPathState();
                    continue;
                }
            }

            BlockPos target = chunkRoute.get(waypointIndex);
            long now = System.currentTimeMillis();
            double distance = distanceSq(target);
            if (!target.equals(activePathTarget)) beginPathTo(target, distance, now);
            if (distance <= 3.0D) {
                waypointIndex++;
                resetPathState();
                continue;
            }
            updatePathProgress(distance, now);
            if (now - pathProgressMs >= PATH_STUCK_MS) {
                throttleWarning("Skipping unreachable point %d/%d in chunk %d,%d; the final scan will verify it.",
                    waypointIndex + 1, chunkRoute.size(), currentChunk.x, currentChunk.z);
                waypointIndex++;
                resetPathState();
                continue;
            }
            if (lastPathRefreshMs == 0L || now - lastPathRefreshMs >= PATH_REFRESH_MS) {
                if (BaritoneSelectionHelper.pathTo(target)) lastPathRefreshMs = now;
            }
            return;
        }
    }

    private void orientCurrentLaneFromPlayer() {
        if (waypointIndex + 1 >= chunkRoute.size()) return;
        BlockPos first = chunkRoute.get(waypointIndex);
        BlockPos second = chunkRoute.get(waypointIndex + 1);
        if (horizontalDistanceSq(second) >= horizontalDistanceSq(first)) return;
        chunkRoute.set(waypointIndex, second);
        chunkRoute.set(waypointIndex + 1, first);
    }

    private boolean currentRouteLaneHasWork() {
        if (currentChunk == null) return false;
        if (waypointIndex + 1 >= chunkRoute.size()) return true;
        BlockPos loadProbe = new BlockPos(currentChunk.getStartX(), activeRoofY, currentChunk.getStartZ());
        if (!mc.world.isChunkLoaded(loadProbe)) return true;

        BlockPos entry = chunkRoute.get(waypointIndex);
        BlockPos exit = chunkRoute.get(waypointIndex + 1);
        double startX = entry.getX() + 0.5D, startZ = entry.getZ() + 0.5D;
        double endX = exit.getX() + 0.5D, endZ = exit.getZ() + 0.5D;
        double radiusSq = ROUTE_WORK_RADIUS * ROUTE_WORK_RADIUS;
        for (int x = currentChunk.getStartX(); x <= currentChunk.getEndX(); x++) {
            for (int z = currentChunk.getStartZ(); z <= currentChunk.getEndZ(); z++) {
                if (findMissingRoofTarget(x, z) == null) continue;
                if (horizontalDistanceSqToSegment(x + 0.5D, z + 0.5D, startX, startZ, endX, endZ) <= radiusSq) {
                    return true;
                }
            }
        }
        return false;
    }

    private double horizontalDistanceSqToSegment(double x, double z, double startX, double startZ,
                                                  double endX, double endZ) {
        double dx = endX - startX, dz = endZ - startZ;
        double lengthSq = dx * dx + dz * dz;
        if (lengthSq <= 1.0E-6D) {
            double offsetX = x - startX, offsetZ = z - startZ;
            return offsetX * offsetX + offsetZ * offsetZ;
        }
        double t = MathHelper.clamp(((x - startX) * dx + (z - startZ) * dz) / lengthSq, 0.0D, 1.0D);
        double closestX = startX + t * dx, closestZ = startZ + t * dz;
        double offsetX = x - closestX, offsetZ = z - closestZ;
        return offsetX * offsetX + offsetZ * offsetZ;
    }

    private void finishChunkPass() {
        BaritoneSelectionHelper.cancelPathing();
        resetPathState();
        prefetchPlannedClaim();
        if (currentChunk == null) return;
        ChunkPos finished = currentChunk;
        long firstScanAt = Math.max(System.currentTimeMillis(), lastPlacementMs) + CHUNK_VERIFICATION_MS;
        pendingVerifications.putIfAbsent(finished, new PendingVerification(finished, firstScanAt));
        info("Chunk %d, %d swept; verification is continuing in the background.", finished.x, finished.z);
        currentChunk = null; chunkRoute.clear(); resetPathState(); clearLeaseState();
        state = WorkState.SelectingChunk;
        // Forward work starts immediately. Background verification may defer this old chunk for
        // cleanup, but it never takes movement control back from the new chunk.
        selectNextChunk();
    }

    private void placeLocalMoss() {
        int quota = PlacementManager.get().getRemainingQuota();
        if (quota <= 0) return;
        List<BlockPos> targets = collectLocalTargets(quota);
        if (targets.isEmpty()) return;
        // Anchor only to the block directly below. PlacementManager keeps this synthetic when
        // that block is a container, preventing adjacent or underlying containers from opening.
        List<BlockPos> placed = PlacementManager.get().placeMany(targets, Blocks.MOSS_BLOCK, Direction.UP);
        if (!placed.isEmpty()) lastPlacementMs = System.currentTimeMillis();
    }

    private List<BlockPos> collectLocalTargets(int limit) {
        Vec3d eye = mc.player.getEyePos();
        int centerX = mc.player.getBlockX(), centerZ = mc.player.getBlockZ();
        List<BlockPos> targets = new ArrayList<>();
        for (int dx = -LOCAL_SCAN_RADIUS; dx <= LOCAL_SCAN_RADIUS; dx++) {
            for (int dz = -LOCAL_SCAN_RADIUS; dz <= LOCAL_SCAN_RADIUS; dz++) {
                int x = centerX + dx, z = centerZ + dz;
                if (!insideCurrentChunk(x, z)) continue;
                BlockPos target = findMissingRoofTarget(x, z);
                if (target == null || !RangeUtil.withinPlaceRange(eye, target)) continue;
                if (PlacementManager.get().isOnCooldown(target)) continue;
                if (!PlacementManager.get().checkPlacement(target, Blocks.MOSS_BLOCK).placeable()) continue;
                targets.add(target);
            }
        }
        targets.sort(Comparator.comparingDouble(pos -> RangeUtil.distanceSqToBox(eye, new Box(pos))));
        return targets.size() > limit ? new ArrayList<>(targets.subList(0, limit)) : targets;
    }

    private boolean chunkHasMissingMoss(ChunkPos chunk) {
        if (chunk == null) return true;
        if (!mc.world.isChunkLoaded(new BlockPos(chunk.getStartX(), activeRoofY, chunk.getStartZ()))) return true;
        for (int x = chunk.getStartX(); x <= chunk.getEndX(); x++) {
            for (int z = chunk.getStartZ(); z <= chunk.getEndZ(); z++) {
                if (findMissingRoofTarget(x, z) != null) return true;
            }
        }
        return false;
    }

    /** Returns the one exposed target for a roof column, ignoring cavities and tall off-plane structures. */
    private BlockPos findMissingRoofTarget(int x, int z) {
        int minY = activeRoofY - ROOF_SCAN_BELOW;
        int maxY = activeRoofY + ROOF_SCAN_ABOVE;
        for (int y = maxY; y >= minY; y--) {
            BlockPos basePos = new BlockPos(x, y, z);
            if (!mc.world.isInBuildLimit(basePos) || !mc.world.isChunkLoaded(basePos)) continue;
            BlockState base = mc.world.getBlockState(basePos);
            if (base.isAir() || base.isReplaceable()) continue;

            // Existing moss is already covered. Fluids do not provide a stable roof surface.
            // Container columns are valid: PlacementManager keeps their click synthetic so the
            // server places above them rather than opening them.
            if (base.isOf(Blocks.MOSS_BLOCK) || !base.getFluidState().isEmpty()) return null;
            if (base.getCollisionShape(mc.world, basePos).isEmpty()) return null;

            BlockPos target = basePos.up();
            if (!mc.world.isInBuildLimit(target)) return null;
            BlockState targetState = mc.world.getBlockState(target);
            if (targetState.isOf(Blocks.MOSS_BLOCK)) return null;
            if (!targetState.isAir() && !targetState.isReplaceable()) return null;
            if (!mc.world.canPlace(Blocks.MOSS_BLOCK.getDefaultState(), target, ShapeContext.absent())) return null;
            return target;
        }
        return null;
    }

    /* Shared API: fail closed, renew claims, and retry transient failures. */

    private void configureCoordination() {
        coordination = null;
        if (apiKey.get().isBlank()) {
            error("Enter the shared project key in Coordination API > API Key."); toggle(); return;
        }
        activeWorkerId = mc.player.getGameProfile().getName();
        if (activeWorkerId == null || activeWorkerId.isBlank()) {
            error("Could not read the logged-in Minecraft username."); toggle(); return;
        }
        coordination = new RoofMossCoordinationClient(
            COORDINATION_API_URL, COORDINATION_PROJECT, activeDimension, activeWorkerId, apiKey.get()
        );
        info("Coordination worker: %s", activeWorkerId);
    }

    private void pollApiRequest() {
        if (apiRequest == null || !apiRequest.isDone()) return;
        RoofMossCoordinationClient.Reply reply = apiRequest.getNow(null);
        ApiAction action = apiAction; ChunkPos chunk = apiChunk;
        apiRequest = null; apiAction = ApiAction.None; apiChunk = null;
        if (reply == null) { handleApiFailure(action, chunk, "no response"); return; }
        if (action == ApiAction.Claim) {
            if (reply.status() == RoofMossCoordinationClient.Status.Complete) {
                completedChunkCount++;
                clearPreparedChunk(chunk);
                state = WorkState.SelectingChunk;
            } else if (reply.claimedBy(activeWorkerId)) {
                beginLease(reply); activatePreparedChunk(chunk);
            } else if (reply.status() == RoofMossCoordinationClient.Status.Claimed) {
                deferredChunks.addLast(chunk);
                clearPreparedChunk(chunk);
                state = WorkState.SelectingChunk;
            } else handleApiFailure(action, chunk, reply.message());
        }
    }

    private void handleApiFailure(ApiAction action, ChunkPos chunk, String message) {
        throttleWarning("Coordination API unavailable: %s. Pausing to prevent duplicate work.", message);
        if (action == ApiAction.Claim && chunk != null) {
            clearPreparedChunk(chunk);
            queuedChunks.add(chunk);
            waitForApi(WorkState.SelectingChunk, API_RETRY_MS, null);
        }
    }

    private void pollPendingVerifications() {
        if (pendingVerifications.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<ChunkPos, PendingVerification>> iterator = pendingVerifications.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingVerification pending = iterator.next().getValue();
            if (now < pending.nextScanAtMs) continue;

            BlockPos loadProbe = new BlockPos(pending.chunk.getStartX(), activeRoofY, pending.chunk.getStartZ());
            if (!mc.world.isChunkLoaded(loadProbe) || chunkHasMissingMoss(pending.chunk)) {
                iterator.remove();
                if (coordination != null) coordination.release(pending.chunk.x, pending.chunk.z);
                if (!deferredChunks.contains(pending.chunk)) deferredChunks.addLast(pending.chunk);
                info("Chunk %d, %d needs cleanup; deferred without interrupting forward work.",
                    pending.chunk.x, pending.chunk.z);
                continue;
            }

            pending.cleanPasses++;
            if (pending.cleanPasses < REQUIRED_CLEAN_PASSES) {
                pending.nextScanAtMs = now + CHUNK_VERIFICATION_MS;
                continue;
            }

            iterator.remove();
            if (coordination == null) {
                completedChunkCount++;
                info("Chunk %d, %d complete.", pending.chunk.x, pending.chunk.z);
            } else {
                pendingCompletions.putIfAbsent(pending.chunk,
                    new PendingCompletion(pending.chunk,
                        coordination.complete(pending.chunk.x, pending.chunk.z), 0L));
                info("Chunk %d, %d locally complete; publishing in the background.",
                    pending.chunk.x, pending.chunk.z);
            }
        }
    }

    private void pollCompletionRequests() {
        if (pendingCompletions.isEmpty() || coordination == null) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<ChunkPos, PendingCompletion>> iterator = pendingCompletions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkPos, PendingCompletion> entry = iterator.next();
            PendingCompletion pending = entry.getValue();
            if (pending.request == null) {
                if (now < pending.retryAtMs) continue;
                pending.request = coordination.complete(pending.chunk.x, pending.chunk.z);
                continue;
            }
            if (!pending.request.isDone()) continue;

            RoofMossCoordinationClient.Reply reply = pending.request.getNow(null);
            if (reply != null && reply.status() == RoofMossCoordinationClient.Status.Complete) {
                iterator.remove();
                completedChunkCount++;
                info("Chunk %d, %d complete.", pending.chunk.x, pending.chunk.z);
                continue;
            }

            if (reply == null || reply.status() == RoofMossCoordinationClient.Status.Error
                || reply.claimedBy(activeWorkerId)) {
                pending.request = null;
                pending.retryAtMs = now + API_RETRY_MS;
                throttleWarning("Chunk %d,%d completion is retrying in the background: %s",
                    pending.chunk.x, pending.chunk.z, reply == null ? "no response" : reply.message());
                continue;
            }

            // The old lease was no longer ours. Defer it instead of allowing a completion race to
            // reverse the active forward route toward a chunk behind the player.
            iterator.remove();
            if (!Objects.equals(currentChunk, pending.chunk) && !deferredChunks.contains(pending.chunk)) {
                deferredChunks.addLast(pending.chunk);
            }
            throttleWarning("Chunk %d,%d completion was rejected; it has been deferred for rechecking.",
                pending.chunk.x, pending.chunk.z);
        }
    }

    private void clearPreparedChunk(ChunkPos chunk) {
        if (!Objects.equals(currentChunk, chunk)) return;
        currentChunk = null;
        chunkRoute.clear();
        resetPathState();
        clearLeaseState();
    }

    private void tickLeaseRenewal() {
        if (coordination == null || currentChunk == null || activeLeaseDurationMs <= 0L
            || leaseValidUntilMs <= 0L) return;
        long now = System.currentTimeMillis();
        if (renewRequest != null && renewRequest.isDone()) {
            RoofMossCoordinationClient.Reply reply = renewRequest.getNow(null); renewRequest = null;
            if (reply != null && reply.claimedBy(activeWorkerId)) {
                beginLease(reply);
            } else if (reply != null && reply.status() == RoofMossCoordinationClient.Status.Complete) {
                currentChunk = null; chunkRoute.clear(); clearLeaseState(); completedChunkCount++; state = WorkState.SelectingChunk; return;
            } else if (reply != null && reply.status() == RoofMossCoordinationClient.Status.Claimed) {
                throttleWarning("Chunk claim was lost to another bot; abandoning local work safely.");
                deferredChunks.addLast(currentChunk); currentChunk = null; chunkRoute.clear(); clearLeaseState(); state = WorkState.SelectingChunk; return;
            } else if (now >= leaseValidUntilMs) {
                leasePaused = true; throttleWarning("Chunk lease expired while the API was unavailable; placement is paused.");
            } else {
                long normal = renewalIntervalMs();
                lastRenewAttemptMs = now - normal + API_RETRY_MS;
                throttleWarning("Chunk lease renewal failed; retrying before the lease expires.");
            }
        }
        long normal = renewalIntervalMs();
        long interval = leasePaused ? API_RETRY_MS : normal;
        if (renewRequest == null && now - lastRenewAttemptMs >= interval) {
            renewRequest = coordination.claim(currentChunk.x, currentChunk.z, leaseSeconds()); lastRenewAttemptMs = now;
        }
        if (now >= leaseValidUntilMs) leasePaused = true;
    }

    private void beginLease(RoofMossCoordinationClient.Reply reply) {
        long now = System.currentTimeMillis();
        int actualSeconds = reply != null && reply.leaseSeconds() > 0
            ? Math.min(leaseSeconds(), reply.leaseSeconds()) : leaseSeconds();
        activeLeaseDurationMs = Math.max(60_000L, actualSeconds * 1_000L);
        leaseValidUntilMs = now + Math.max(API_RETRY_MS, activeLeaseDurationMs - LEASE_SAFETY_MARGIN_MS);
        lastRenewAttemptMs = now; leasePaused = false;
    }

    private long renewalIntervalMs() {
        long duration = activeLeaseDurationMs > 0L ? activeLeaseDurationMs : leaseSeconds() * 1_000L;
        return Math.max(API_RETRY_MS, duration / 3L);
    }

    private void releaseCurrentClaim() {
        if (coordination != null && currentChunk != null) coordination.release(currentChunk.x, currentChunk.z);
    }

    private void releasePendingVerificationClaims() {
        if (coordination == null) return;
        for (ChunkPos chunk : pendingVerifications.keySet()) coordination.release(chunk.x, chunk.z);
    }

    /** Claims the already-planned successor while the current chunk's final lanes are still running. */
    private void prefetchPlannedClaim() {
        if (coordination == null || plannedNextChunk == null || prefetchedClaimRequest != null) return;
        if (!queuedChunks.contains(plannedNextChunk)) return;
        RoofMossCoordinationClient client = coordination;
        CompletableFuture<Void> priorCleanup = prefetchedClaimCleanup;
        prefetchedClaimChunk = plannedNextChunk;
        ChunkPos chunk = prefetchedClaimChunk;
        // If this same successor was speculatively claimed by a failed pass, wait until its
        // release has reached the server before reclaiming it. Otherwise a late release could
        // accidentally delete the newer lease because API ownership is keyed by worker name.
        prefetchedClaimRequest = priorCleanup
            .handle((ignored, throwable) -> null)
            .thenCompose(ignored -> client.claim(chunk.x, chunk.z, leaseSeconds()));
    }

    /**
     * Drops a speculative successor claim safely. Chaining release after the request also closes
     * the race where cleanup happens before the server has finished granting the claim.
     */
    private void abandonPrefetchedClaim() {
        ChunkPos chunk = prefetchedClaimChunk;
        CompletableFuture<RoofMossCoordinationClient.Reply> request = prefetchedClaimRequest;
        RoofMossCoordinationClient client = coordination;
        String worker = activeWorkerId;
        prefetchedClaimChunk = null;
        prefetchedClaimRequest = null;
        if (chunk == null || request == null || client == null) return;
        prefetchedClaimCleanup = request
            .handle((reply, throwable) -> throwable == null ? reply : null)
            .thenCompose(reply -> reply != null && reply.claimedBy(worker)
                ? client.release(chunk.x, chunk.z).thenApply(ignored -> (Void) null)
                : CompletableFuture.completedFuture(null));
    }

    private void waitForApi(WorkState afterward, long delay, String message) {
        if (message != null) info(message);
        stateAfterApiWait = afterward; apiRetryAtMs = System.currentTimeMillis() + delay;
        state = WorkState.ApiWaiting; BaritoneSelectionHelper.cancelPathing();
    }

    private int leaseSeconds() { return COORDINATION_LEASE_SECONDS; }
    private void clearLeaseState() {
        renewRequest = null; lastRenewAttemptMs = 0L; leaseValidUntilMs = 0L;
        activeLeaseDurationMs = 0L; leasePaused = false;
    }

    /* Shulker regear state machine. */

    private void beginMossRefill() {
        if (workReturnPos == null) workReturnPos = preferredWorkReturnPosition();
        if (hasDeployedShulker()) { beginDeployedShulkerTrip(); return; }
        if (findMossShulkerSlot() != -1) { beginShulkerUnload(); return; }
        beginStationTrip();
    }

    private BlockPos preferredWorkReturnPosition() {
        if (initialWorkTransitPending && currentChunk != null && !chunkRoute.isEmpty()
            && (!insideCurrentChunk(mc.player.getBlockX(), mc.player.getBlockZ()) || !isOnRoofSurface())) {
            return chunkRoute.get(0).toImmutable();
        }
        return mc.player.getBlockPos().toImmutable();
    }

    private boolean beginInitialWorkTransit() {
        if (currentChunk == null || chunkRoute.isEmpty()) return false;
        if (insideCurrentChunk(mc.player.getBlockX(), mc.player.getBlockZ()) && isOnRoofSurface()) return false;

        BlockPos target = chunkRoute.get(0);
        if (distanceSq(target) <= 4.0D) return false;
        workReturnPos = target.toImmutable();
        unloadAfterReturn = false;
        state = WorkState.ReturningToWork;
        resetPathState();
        info("Traveling from the starting position to working chunk %d, %d.", currentChunk.x, currentChunk.z);
        return true;
    }

    private boolean hasDeployedShulker() {
        if (deployedShulkerPos == null) return false;
        if (!mc.world.isChunkLoaded(deployedShulkerPos)) return true;
        if (mc.world.getBlockState(deployedShulkerPos).getBlock() instanceof ShulkerBoxBlock) return true;
        clearDeployedShulker();
        return false;
    }

    private void beginDeployedShulkerTrip() {
        if (workReturnPos == null) workReturnPos = mc.player.getBlockPos().toImmutable();
        unloadAfterReturn = false;
        state = WorkState.ToDeployedShulker; stateStartedMs = System.currentTimeMillis(); resetPathState();
        info("Returning to the partially used moss shulker.");
    }

    private void beginStationTrip() {
        if (workReturnPos == null) workReturnPos = mc.player.getBlockPos().toImmutable();
        rebuildStationCandidates();
        if (stationCandidates.isEmpty()) { blockAutomation("No recorded regear container exists in this dimension."); return; }
        currentStation = null; state = WorkState.ToStation; resetPathState();
        info("No usable moss shulker remains; visiting regear containers.");
    }

    private void tickRegear() {
        switch (state) {
            case ToStation -> tickTravelToStation();
            case OpeningStation -> tickOpenStation();
            case DepositingEmpties -> tickDepositEmpties();
            case TakingMossShulker -> tickTakeMossShulker();
            case ReturningToWork -> tickReturnToWork();
            case ToDeployedShulker -> tickTravelToDeployedShulker();
            case PreparingShulker -> tickPrepareShulker();
            case PlacingShulker -> tickPlaceShulker();
            case OpeningShulker -> tickOpenDeployedShulker();
            case UnloadingShulker -> tickUnloadDeployedShulker();
            case MiningShulker -> tickMineDeployedShulker();
            case AwaitingPickup -> tickAwaitShulkerPickup();
            case WaitingForSupply -> tickWaitingForSupply();
            default -> { }
        }
    }

    private void tickTravelToStation() {
        if (currentStation == null) {
            if (stationCandidates.isEmpty()) {
                state = WorkState.WaitingForSupply; stateStartedMs = System.currentTimeMillis();
                throttleWarning("No recorded container supplied a moss shulker; retrying in 30 seconds."); return;
            }
            currentStation = stationCandidates.remove(0); resetPathState();
        }
        BlockPos target = currentStation.pos();
        if (RangeUtil.withinPlaceRange(mc.player.getEyePos(), target)
            && (!usingElytraTravel || mc.player.isOnGround())) {
            BaritoneSelectionHelper.cancelPathing(); resetPathState(); state = WorkState.OpeningStation;
            stateStartedMs = System.currentTimeMillis(); tryOpenContainer(target); return;
        }
        if (!tickPathToBlock(target)) moveToNextStation();
    }

    private void tickOpenStation() {
        if (hasExternalScreenHandler()) {
            moduleOpenedContainer = true; pendingDepositCount = -1; state = WorkState.DepositingEmpties; return;
        }
        long now = System.currentTimeMillis();
        if (now - lastContainerOpenAttemptMs >= CONTAINER_OPEN_RETRY_MS) {
            if (now - stateStartedMs >= PATH_STUCK_MS) moveToNextStation(); else tryOpenContainer(currentStation.pos());
        }
    }

    private void tickDepositEmpties() {
        if (!hasExternalScreenHandler()) { moveToNextStation(); return; }
        long now = System.currentTimeMillis(); int returnable = countReturnableShulkers();
        if (pendingDepositCount >= 0) {
            if (returnable < pendingDepositCount) pendingDepositCount = -1;
            else if (now - lastInventoryClickMs < INVENTORY_RESPONSE_MS) return;
            else { moveToNextStation(); return; }
        }
        Slot spent = findPlayerReturnableShulkerScreenSlot();
        if (spent == null) { state = WorkState.TakingMossShulker; pendingTakeCount = -1; return; }
        if (now - lastInventoryClickMs >= INVENTORY_CLICK_MS) {
            pendingDepositCount = returnable;
            clickQuickMove(spent);
        }
    }

    private void tickTakeMossShulker() {
        if (!hasExternalScreenHandler()) { moveToNextStation(); return; }
        long now = System.currentTimeMillis(); int mossShulkerCount = countMossShulkers();
        if (pendingTakeCount >= 0) {
            if (mossShulkerCount > pendingTakeCount) {
                pendingTakeCount = -1; closeOwnedContainer(); currentStation = null;
                beginReturnToWork(true); return;
            }
            if (now - lastInventoryClickMs < INVENTORY_RESPONSE_MS) return;
            moveToNextStation(); return;
        }
        Slot mossShulker = findContainerLeastMossShulkerSlot();
        if (mossShulker == null || !canAcceptOneItem()) { moveToNextStation(); return; }
        if (now - lastInventoryClickMs >= INVENTORY_CLICK_MS) {
            pendingTakeCount = mossShulkerCount;
            clickQuickMove(mossShulker);
        }
    }

    private void tickReturnToWork() {
        if (workReturnPos == null || (distanceSq(workReturnPos) <= 4.0D
            && (!usingElytraTravel || mc.player.isOnGround()))) {
            BaritoneSelectionHelper.cancelPathing(); resetPathState();
            boolean shouldUnload = unloadAfterReturn;
            unloadAfterReturn = false; workReturnPos = null;
            initialWorkTransitPending = false;
            if (shouldUnload) beginShulkerUnload();
            else { state = WorkState.Working; info("Returned to the saved work position."); }
            return;
        }
        if (!tickPathToBlock(workReturnPos)) blockAutomation("Baritone could not return to the saved work position.");
    }

    private void beginReturnToWork(boolean unloadOnArrival) {
        unloadAfterReturn = unloadOnArrival;
        state = WorkState.ReturningToWork; resetPathState();
    }

    private void tickTravelToDeployedShulker() {
        if (deployedShulkerPos == null) { beginMossRefill(); return; }
        if (mc.world.isChunkLoaded(deployedShulkerPos)
            && !(mc.world.getBlockState(deployedShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            warning("The partially used moss shulker at %d, %d, %d is gone.",
                deployedShulkerPos.getX(), deployedShulkerPos.getY(), deployedShulkerPos.getZ());
            clearDeployedShulker(); beginMossRefill(); return;
        }
        if (RangeUtil.withinPlaceRange(mc.player.getEyePos(), deployedShulkerPos)
            && (!usingElytraTravel || mc.player.isOnGround())) {
            BaritoneSelectionHelper.cancelPathing(); resetPathState(); state = WorkState.OpeningShulker;
            stateStartedMs = System.currentTimeMillis(); tryOpenContainer(deployedShulkerPos, Direction.DOWN); return;
        }
        if (!tickPathToBlock(deployedShulkerPos)) {
            blockAutomation("Baritone could not return to the partially used moss shulker.");
        }
    }

    private void beginShulkerUnload() {
        int slot = findMossShulkerSlot();
        if (slot == -1) { beginStationTrip(); return; }
        resetStagedShulkerPlacement();
        state = WorkState.PreparingShulker; stateStartedMs = System.currentTimeMillis(); resetPathState();
    }

    private void tickPrepareShulker() {
        if (!canInteractSafely()) return;
        long now = System.currentTimeMillis();
        // Do not expose a staged shulker to another inventory module while waiting for the
        // placement cooldown. Once staged, send its placement in this same update tick.
        if (now - lastPlacementMs < ENDPOINT_SETTLE_MS) return;

        int hotbar = Math.max(0, Math.min(8, shulkerSlot.get() - 1));
        ItemStack stack = mc.player.getInventory().getStack(hotbar);
        if (!isMossShulker(stack)) {
            int source = findMossShulkerSlot();
            if (source == -1) { beginStationTrip(); return; }
            if (now - lastInventoryClickMs < INVENTORY_CLICK_MS) return;
            swapInventoryToHotbar(source, hotbar);
            stack = mc.player.getInventory().getStack(hotbar);
            if (!isMossShulker(stack)) return;
        }

        deployedShulkerHotbar = hotbar;
        deployedShulkerBlock = ((BlockItem) stack.getItem()).getBlock();
        deployedShulkerPos = findOverheadShulkerPlacement(deployedShulkerBlock);
        if (deployedShulkerPos == null) {
            blockAutomation("No safe air space exists above the player for a shulker.");
            return;
        }
        shulkerPlacementSentAtMs = 0L;
        state = WorkState.PlacingShulker;
        stateStartedMs = now;
        tickPlaceShulker();
    }

    private void tickPlaceShulker() {
        if (!canInteractSafely()) return;
        long now = System.currentTimeMillis();
        if (deployedShulkerPos == null || deployedShulkerBlock == null) {
            restageShulkerPlacement(now);
            return;
        }
        if (mc.world.getBlockState(deployedShulkerPos).getBlock() instanceof ShulkerBoxBlock) {
            shulkerPlacementSentAtMs = 0L;
            state = WorkState.OpeningShulker; stateStartedMs = System.currentTimeMillis();
            tryOpenContainer(deployedShulkerPos, Direction.DOWN); return;
        }

        // A sent interaction is awaiting a block update. Do not infer this from the total
        // number of shulkers in inventory: a player may carry several of them.
        if (shulkerPlacementSentAtMs != 0L) {
            if (now - shulkerPlacementSentAtMs >= SHULKER_PLACEMENT_CONFIRM_MS) {
                restageShulkerPlacement(now);
            }
            return;
        }

        // Auto Replenish or another inventory action may have reclaimed the temporary slot.
        // Re-stage instead of asking PlacementManager to use a block that is no longer there
        // forever (its correct response in that situation is an empty send list).
        if (!isStagedShulkerReady()) {
            restageShulkerPlacement(now);
            return;
        }
        if (now - lastPlacementMs < ENDPOINT_SETTLE_MS) return;
        List<BlockPos> sent = PlacementManager.get().placeManyFromHotbarSlot(
            List.of(deployedShulkerPos), deployedShulkerBlock, deployedShulkerHotbar, Direction.DOWN);
        if (!sent.isEmpty()) {
            shulkerPlacementSentAtMs = now;
            lastPlacementMs = now;
        } else if (now - stateStartedMs >= INVENTORY_RESPONSE_MS) {
            restageShulkerPlacement(now);
        }
    }

    private boolean isStagedShulkerReady() {
        if (deployedShulkerHotbar < 0 || deployedShulkerHotbar > 8 || deployedShulkerBlock == null) return false;
        ItemStack stack = mc.player.getInventory().getStack(deployedShulkerHotbar);
        return isMossShulker(stack)
            && stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() == deployedShulkerBlock;
    }

    private void restageShulkerPlacement(long now) {
        resetStagedShulkerPlacement();
        state = WorkState.PreparingShulker;
        stateStartedMs = now;
    }

    private void resetStagedShulkerPlacement() {
        deployedShulkerPos = null;
        deployedShulkerBlock = null;
        deployedShulkerHotbar = -1;
        shulkerPlacementSentAtMs = 0L;
    }

    private void tickOpenDeployedShulker() {
        if (hasExternalScreenHandler()) {
            moduleOpenedContainer = true; unloadBatchPending = false; unloadBatchSentAtMs = 0L;
            state = WorkState.UnloadingShulker; return;
        }
        long now = System.currentTimeMillis();
        if (now - lastContainerOpenAttemptMs >= CONTAINER_OPEN_RETRY_MS) {
            if (now - stateStartedMs >= PATH_STUCK_MS) blockAutomation("The deployed shulker could not be opened.");
            else tryOpenContainer(deployedShulkerPos, Direction.DOWN);
        }
    }

    private void tickUnloadDeployedShulker() {
        if (!hasExternalScreenHandler()) { blockAutomation("The shulker screen closed before unloading finished."); return; }
        long now = System.currentTimeMillis();
        if (unloadBatchPending) {
            if (now - unloadBatchSentAtMs < SHULKER_BURST_CONFIRM_MS) return;
            unloadBatchPending = false;
        }

        List<Slot> mossSlots = findContainerMossSlots();
        if (mossSlots.isEmpty()) {
            recoveredShulkerNeedsReturn = true;
            beginDeployedShulkerRecovery(null); return;
        }
        int maxMoves = Math.max(0, countEmptyInventorySlots() - 1);
        if (maxMoves == 0) {
            if (countLooseMoss() > lowMoss.get()) {
                info("Loose moss inventory filled; recovering the partially used shulker and resuming work.");
                beginDeployedShulkerRecovery(null);
            } else {
                beginDeployedShulkerRecovery(
                    "No inventory room is available for moss, and the loose supply is still below the refill threshold.");
            }
            return;
        }
        int moves = Math.min(maxMoves, mossSlots.size());
        for (int i = 0; i < moves; i++) clickQuickMove(mossSlots.get(i));
        unloadBatchPending = true; unloadBatchSentAtMs = now;
    }

    private void tickMineDeployedShulker() {
        if (!(mc.world.getBlockState(deployedShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            state = WorkState.AwaitingPickup; stateStartedMs = System.currentTimeMillis(); return;
        }
        SilentMine mine = ensureSilentMine();
        if (mine == null || !mine.isActive()) { blockAutomation("Silent Mine is unavailable; the shulker was not broken."); return; }
        mine.setAllowRebreakLoop(false);
        if (!mine.alreadyBreaking(deployedShulkerPos)) mine.silentBreakBlock(deployedShulkerPos, Direction.DOWN, SILENT_MINE_PRIORITY);
    }

    private void tickAwaitShulkerPickup() {
        if (countShulkers() > shulkersBeforeMine) {
            int recoveredSlot = findRecoveredShulkerInventorySlot();
            if (recoveredShulkerNeedsReturn && recoveredSlot != -1) returnableShulkerSlot = recoveredSlot;
            recoveredShulkerNeedsReturn = false;
            shulkerInventorySlotsBeforeMine.clear();
            releasePickupMovement();
            BaritoneSelectionHelper.cancelPathing();
            clearDeployedShulker(); restoreSilentMine();
            info("Moss shulker recovered and returned to the inventory.");
            if (blockAfterShulkerPickup != null) {
                String reason = blockAfterShulkerPickup; blockAfterShulkerPickup = null;
                blockAutomation(reason); return;
            }
            if (workReturnPos != null && distanceSq(workReturnPos) > 4.0D) beginReturnToWork(false);
            else { workReturnPos = null; unloadAfterReturn = false; state = WorkState.Working; }
            return;
        }

        ItemEntity droppedShulker = findDroppedShulkerItem();
        if (droppedShulker != null) moveTowardDroppedShulker(droppedShulker);
        else {
            releasePickupMovement();
            BaritoneSelectionHelper.cancelPathing();
        }

        if (System.currentTimeMillis() - stateStartedMs >= PICKUP_WARNING_MS) {
            throttleWarning("Still moving toward the dropped shulker; one inventory slot remains reserved.");
        }
    }

    private void beginDeployedShulkerRecovery(String blockReason) {
        unloadBatchPending = false; unloadBatchSentAtMs = 0L;
        closeOwnedContainer(); shulkersBeforeMine = countShulkers();
        shulkerInventorySlotsBeforeMine.clear();
        for (int i = 0; i < 36; i++) {
            if (isShulker(mc.player.getInventory().getStack(i))) shulkerInventorySlotsBeforeMine.add(i);
        }
        shulkerItemEntitiesBeforeMine.clear();
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemEntity item && isShulker(item.getStack())) {
                shulkerItemEntitiesBeforeMine.add(item.getId());
            }
        }
        droppedShulkerEntityId = -1; releasePickupMovement(); lastPickupPathRefreshMs = 0L;
        blockAfterShulkerPickup = blockReason;
        state = WorkState.MiningShulker; stateStartedMs = System.currentTimeMillis();
    }

    private int findRecoveredShulkerInventorySlot() {
        for (int i = 0; i < 36; i++) {
            if (!shulkerInventorySlotsBeforeMine.contains(i)
                && isShulker(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private ItemEntity findDroppedShulkerItem() {
        if (deployedShulkerPos == null) return null;
        if (droppedShulkerEntityId != -1) {
            Entity tracked = mc.world.getEntityById(droppedShulkerEntityId);
            if (tracked instanceof ItemEntity item && item.isAlive() && isShulker(item.getStack())) return item;
            droppedShulkerEntityId = -1;
        }

        ItemEntity nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity item) || !item.isAlive() || !isShulker(item.getStack())) continue;
            if (shulkerItemEntitiesBeforeMine.contains(item.getId())) continue;
            if (deployedShulkerBlock != null
                && item.getStack().getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() != deployedShulkerBlock) continue;
            double distance = item.squaredDistanceTo(Vec3d.ofCenter(deployedShulkerPos));
            if (distance < nearestDistance) {
                nearest = item;
                nearestDistance = distance;
            }
        }
        if (nearest != null) droppedShulkerEntityId = nearest.getId();
        return nearest;
    }

    private void moveTowardDroppedShulker(ItemEntity item) {
        double dx = item.getX() - mc.player.getX();
        double dz = item.getZ() - mc.player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double verticalDistance = Math.abs(item.getY() - mc.player.getY());
        if (horizontalDistance <= PICKUP_STOP_DISTANCE && verticalDistance <= 1.5D) {
            releasePickupMovement();
            BaritoneSelectionHelper.cancelPathing();
            return;
        }

        if (horizontalDistance > DIRECT_PICKUP_RANGE || verticalDistance > 2.0D) {
            releasePickupMovement();
            long now = System.currentTimeMillis();
            if (now - lastPickupPathRefreshMs >= PICKUP_PATH_REFRESH_MS) {
                if (BaritoneSelectionHelper.pathTo(item.getBlockPos())) lastPickupPathRefreshMs = now;
            }
            return;
        }

        BaritoneSelectionHelper.cancelPathing();
        double yaw = Math.toRadians(mc.player.getYaw());
        double forward = dx * -Math.sin(yaw) + dz * Math.cos(yaw);
        double left = dx * Math.cos(yaw) + dz * Math.sin(yaw);
        mc.options.forwardKey.setPressed(forward > 0.15D);
        mc.options.backKey.setPressed(forward < -0.15D);
        mc.options.leftKey.setPressed(left > 0.15D);
        mc.options.rightKey.setPressed(left < -0.15D);
        pickupMovementActive = true;
    }

    private void releasePickupMovement() {
        if (!pickupMovementActive || mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        pickupMovementActive = false;
    }

    private void clearDeployedShulker() {
        resetStagedShulkerPlacement();
        shulkerItemEntitiesBeforeMine.clear(); droppedShulkerEntityId = -1;
    }

    private void tickWaitingForSupply() {
        if (System.currentTimeMillis() - stateStartedMs < RESCAN_MS) return;
        rebuildStationCandidates(); stateStartedMs = System.currentTimeMillis();
        if (!stationCandidates.isEmpty()) { currentStation = null; state = WorkState.ToStation; }
    }

    private void moveToNextStation() {
        closeOwnedContainer(); currentStation = null; pendingDepositCount = -1; pendingTakeCount = -1;
        state = WorkState.ToStation; resetPathState();
    }

    private void rebuildStationCandidates() {
        stationCandidates.clear(); stationCandidates.addAll(stationsForCurrentDimension());
        stationCandidates.sort(Comparator.comparingDouble(station -> distanceSq(station.pos())));
    }

    /* In-game regear-station recorder. */

    private void beginStationCapture() {
        if (mc.player == null || mc.world == null) { error("Join a world before recording stations."); return; }
        if (stationCaptureMode) return;
        if (isActive()) {
            warning("Disable Roof Mosser before recording regear stations so its current chunk claim can close cleanly.");
            return;
        }
        stationCaptureMode = true; activatedForCapture = true; capturedStations.clear();
        capturedStations.addAll(serializedStationEntries());
        toggle();
    }

    private void promptStationCapture() {
        info("Right-click every regear chest, barrel, or shulker, then press Shift when finished.");
    }

    private void finishStationCapture() {
        stationData.set(String.join(";", capturedStations)); stationCaptureMode = false;
        info("Saved %d regear container%s.", capturedStations.size(), capturedStations.size() == 1 ? "" : "s");
        if (activatedForCapture && isActive()) toggle();
        activatedForCapture = false;
    }

    private void clearStations() {
        stationData.set(""); capturedStations.clear(); info("Cleared all recorded Roof Mosser regear containers.");
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!stationCaptureMode || mc.world == null || !(event.packet instanceof PlayerInteractBlockC2SPacket packet)) return;
        BlockPos pos = packet.getBlockHitResult().getBlockPos();
        event.cancel();
        if (!isSupportedStationBlock(mc.world.getBlockState(pos).getBlock())) {
            warning("That is not a chest, trapped chest, barrel, or shulker box."); return;
        }
        String entry = serializeStation(mc.world.getRegistryKey().getValue().toString(), pos);
        if (capturedStations.add(entry)) info("Added regear container at %d, %d, %d (%d recorded).",
            pos.getX(), pos.getY(), pos.getZ(), capturedStations.size());
        else info("That regear container was already recorded.");
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (!stationCaptureMode || event.action != KeyAction.Press) return;
        if (event.key == GLFW.GLFW_KEY_LEFT_SHIFT || event.key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            event.cancel(); finishStationCapture();
        }
    }

    /* Inventory and item helpers. */

    private HotbarStatus ensureMossHotbarSlot() {
        int hotbar = Math.max(0, Math.min(8, mossSlot.get() - 1));
        if (isMoss(mc.player.getInventory().getStack(hotbar))) return HotbarStatus.Ready;
        int source = findLooseMossSlot(hotbar);
        if (source == -1) return HotbarStatus.Empty;
        if (System.currentTimeMillis() - lastInventoryClickMs < INVENTORY_CLICK_MS) return HotbarStatus.Moved;
        swapInventoryToHotbar(source, hotbar); return HotbarStatus.Moved;
    }

    private void swapInventoryToHotbar(int source, int hotbar) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        int screenSlot = source <= 8 ? 36 + source : source;
        mc.interactionManager.clickSlot(handler.syncId, screenSlot, hotbar, SlotActionType.SWAP, mc.player);
        lastInventoryClickMs = System.currentTimeMillis();
    }

    private void clickQuickMove(Slot slot) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
        lastInventoryClickMs = System.currentTimeMillis();
    }

    private List<Slot> findContainerMossSlots() {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : mc.player.currentScreenHandler.slots) {
            if (slot.inventory != mc.player.getInventory() && isMoss(slot.getStack())) slots.add(slot);
        }
        return slots;
    }

    private Slot findContainerLeastMossShulkerSlot() {
        Slot leastFilled = null;
        int leastMoss = Integer.MAX_VALUE;
        for (Slot slot : mc.player.currentScreenHandler.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;
            int moss = mossInShulker(slot.getStack());
            if (moss > 0 && moss < leastMoss) {
                leastMoss = moss;
                leastFilled = slot;
            }
        }
        return leastFilled;
    }

    private Slot findPlayerReturnableShulkerScreenSlot() {
        for (Slot slot : mc.player.currentScreenHandler.slots) {
            if (slot.inventory != mc.player.getInventory()) continue;
            int inventorySlot = slot.getIndex();
            if (inventorySlot < 0 || inventorySlot >= 36) continue;
            if (isEmptyShulker(slot.getStack())
                || (inventorySlot == returnableShulkerSlot && isSpentMossShulker(slot.getStack()))) return slot;
        }
        return null;
    }

    private int findLooseMossSlot(int excluded) {
        for (int i = 0; i < 36; i++) if (i != excluded && isMoss(mc.player.getInventory().getStack(i))) return i;
        return -1;
    }
    private int findMossShulkerSlot() {
        int leastFilledSlot = -1;
        int leastMoss = Integer.MAX_VALUE;
        for (int i = 0; i < 36; i++) {
            int moss = mossInShulker(mc.player.getInventory().getStack(i));
            if (moss > 0 && moss < leastMoss) {
                leastMoss = moss;
                leastFilledSlot = i;
            }
        }
        return leastFilledSlot;
    }
    private int countLooseMoss() {
        int count = 0;
        for (int i = 0; i < 36; i++) { ItemStack stack = mc.player.getInventory().getStack(i); if (isMoss(stack)) count += stack.getCount(); }
        return count;
    }
    private int countMossShulkers() {
        int count = 0; for (int i = 0; i < 36; i++) if (isMossShulker(mc.player.getInventory().getStack(i))) count++; return count;
    }
    private int countEmptyShulkers() {
        int count = 0; for (int i = 0; i < 36; i++) if (isEmptyShulker(mc.player.getInventory().getStack(i))) count++; return count;
    }
    private int countReturnableShulkers() {
        int count = countEmptyShulkers();
        if (returnableShulkerSlot < 0 || returnableShulkerSlot >= 36) {
            returnableShulkerSlot = -1;
            return count;
        }
        ItemStack tracked = mc.player.getInventory().getStack(returnableShulkerSlot);
        if (!isShulker(tracked)) {
            returnableShulkerSlot = -1;
        } else if (!isEmptyShulker(tracked) && isSpentMossShulker(tracked)) {
            count++;
        }
        return count;
    }
    private int countShulkers() {
        int count = 0; for (int i = 0; i < 36; i++) if (isShulker(mc.player.getInventory().getStack(i))) count++; return count;
    }
    private int countEmptyInventorySlots() {
        int count = 0; for (int i = 0; i < 36; i++) if (mc.player.getInventory().getStack(i).isEmpty()) count++; return count;
    }
    private boolean canAcceptOneItem() { return countEmptyInventorySlots() > 0; }
    private boolean isMoss(ItemStack stack) { return stack != null && stack.isOf(Blocks.MOSS_BLOCK.asItem()); }
    private boolean isShulker(ItemStack stack) {
        return stack != null && stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock;
    }
    private boolean isEmptyShulker(ItemStack stack) {
        if (!isShulker(stack)) return false;
        return stack.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT).streamNonEmpty().findAny().isEmpty();
    }
    private boolean isMossShulker(ItemStack stack) { return mossInShulker(stack) > 0; }
    private boolean isSpentMossShulker(ItemStack stack) { return isShulker(stack) && mossInShulker(stack) == 0; }
    private int mossInShulker(ItemStack stack) {
        if (!isShulker(stack)) return -1;
        int count = 0;
        for (ItemStack contained : stack.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT).iterateNonEmpty()) {
            if (isMoss(contained)) count += contained.getCount();
        }
        return count;
    }

    /* Path, interaction, persistence, and lifecycle helpers. */

    private boolean tickPathToBlock(BlockPos target) {
        long now = System.currentTimeMillis(); double distance = distanceSq(target);
        if (!target.equals(activePathTarget)) beginPathTo(target, distance, now);
        updatePathProgress(distance, now);
        maintainTakeoffPitchHold();

        if (usingElytraTravel) {
            if (takeoffPitchHoldActive && BaritoneSelectionHelper.isOwnedElytraPathReady()) {
                handOffTakeoffPitch();
            }
            if (tickTakeoffSlowdown(now)) {
                restartElytraAttempt(now, distance,
                    "The Elytra boost was not confirmed; retrying the takeoff instead of walking.", true);
                return true;
            }
            if (now - pathProgressMs >= ELYTRA_STUCK_MS) {
                restartElytraAttempt(now, distance, "Elytra regear travel stalled; recalculating the flight.", false);
                return true;
            } else if (BaritoneSelectionHelper.isOwnedElytraActive()) {
                return true;
            } else {
                BaritoneSelectionHelper.stopOwnedElytra();
                usingElytraTravel = false; elytraPathPrewarming = false;
                stopTakeoffSlowdown();
                releaseManualTakeoffInput();
                pathProgressMs = now; pathBestDistanceSq = distance;
            }
        }

        // A launch can already own the Timer before Baritone accepts the path. Keep validating
        // and retrying that launch even while the Elytra process itself is not active yet.
        if (!usingElytraTravel && takeoffTimerOverrideActive && tickTakeoffSlowdown(now)) {
            restartElytraAttempt(now, distance,
                "The Elytra boost was not confirmed; retrying the takeoff instead of walking.", true);
            return true;
        }

        if (shouldUseElytra(target)) {
            if (!ensureFlightEquipmentReady(now)) return true;
            if (!hasTakeoffRocket()) {
                throttleWarning("No firework rocket is available for Elytra takeoff; waiting and retrying.");
                return true;
            }

            if (!elytraPathPrewarming) {
                BaritoneSelectionHelper.cancelPathing();
                if (BaritoneSelectionHelper.startElytraPath(target, false)) {
                    elytraPathPrewarming = true;
                    pathProgressMs = now; pathBestDistanceSq = distance;
                    info("Calculating the Baritone Elytra path before takeoff.");
                } else {
                    throttleWarning("Baritone Elytra did not start; retaining the Elytra and retrying.");
                }
                return true;
            }

            if (!BaritoneSelectionHelper.isOwnedElytraActive()) {
                restartElytraAttempt(now, distance,
                    "Baritone stopped while calculating the Elytra path; retrying.", false);
                return true;
            }
            if (!BaritoneSelectionHelper.isOwnedElytraPathReady()) {
                neutralizeTakeoffMovementInput();
                if (now - pathProgressMs >= PATH_STUCK_MS) {
                    restartElytraAttempt(now, distance,
                        "Baritone did not calculate an Elytra path in time; recalculating.", false);
                }
                return true;
            }

            ElytraPreparation preparation = prepareElytraForFlight(now);
            if (preparation == ElytraPreparation.Waiting) return true;
            if (preparation == ElytraPreparation.Ready) {
                elytraPathPrewarming = false;
                usingElytraTravel = true; lastPathRefreshMs = now;
                info("Handing the precomputed Elytra path to Baritone after takeoff.");
            }
            return true;
        }

        if (now - pathProgressMs >= PATH_STUCK_MS) return false;
        if (lastPathRefreshMs == 0L || now - lastPathRefreshMs >= PATH_REFRESH_MS) {
            if (BaritoneSelectionHelper.pathTo(target)) lastPathRefreshMs = now;
        }
        return true;
    }

    private void restartElytraAttempt(long now, double distance, String message, boolean keepCalculatedPath) {
        boolean retainPath = keepCalculatedPath
            && BaritoneSelectionHelper.isOwnedElytraActive()
            && BaritoneSelectionHelper.isOwnedElytraPathReady();
        if (!retainPath) BaritoneSelectionHelper.stopOwnedElytra();
        usingElytraTravel = false; elytraPathPrewarming = retainPath;
        stopTakeoffSlowdown(); releaseManualTakeoffInput();
        pathProgressMs = now; pathBestDistanceSq = distance;
        throttleWarning(message);
    }
    private boolean shouldUseElytra(BlockPos target) {
        double verticalDistance = Math.abs(mc.player.getY() - (target.getY() + 1.0D));
        return horizontalDistanceSq(target) > NEARBY_WALK_HORIZONTAL * NEARBY_WALK_HORIZONTAL
            || verticalDistance > NEARBY_WALK_VERTICAL;
    }

    private boolean ensureFlightEquipmentReady(long now) {
        if (!isGliderEquipped()) {
            if (elytraEquipRequestedAtMs != 0L) {
                if (now - elytraEquipRequestedAtMs < ELYTRA_EQUIP_SETTLE_MS) return false;
                elytraEquipRequestedAtMs = 0L;
                throttleWarning("The safe SWAP click did not equip the Elytra; retrying.");
                return false;
            }
            int gliderSlot = findInventoryGliderSlot();
            if (gliderSlot == -1 && elytraGhostRefreshRequestedAtMs != 0L) {
                if (now - elytraGhostRefreshRequestedAtMs < ELYTRA_GHOST_REFRESH_SETTLE_MS) return false;
                elytraGhostRefreshRequestedAtMs = 0L;
                gliderSlot = findInventoryGliderSlot();
            }
            if (gliderSlot == -1
                && now - lastElytraGhostRefreshMs >= ELYTRA_GHOST_REFRESH_RETRY_MS
                && refreshPossibleGhostElytra()) {
                lastElytraGhostRefreshMs = now;
                elytraGhostRefreshRequestedAtMs = now;
                return false;
            }
            if (gliderSlot == -1) {
                throttleWarning("No inventory Elytra is available for the required regear flight; waiting and retrying.");
                return false;
            }
            if (!swapChestEquipmentWithSlot(gliderSlot)) {
                throttleWarning("The Elytra could not be equipped with a safe SWAP click; retrying.");
                return false;
            }
            flightEquipmentSwapSlot = gliderSlot;
            restoreChestplateAfterFlight = true; chestRestorePending = false;
            elytraEquipRequestedAtMs = now;
            return false;
        }
        restoreChestplateAfterFlight = true;
        if (flightEquipmentSwapSlot == -1) flightEquipmentSwapSlot = findInventoryChestplateSlot();
        if (elytraEquipRequestedAtMs != 0L && now - elytraEquipRequestedAtMs < ELYTRA_EQUIP_SETTLE_MS) {
            return false;
        }
        elytraEquipRequestedAtMs = 0L;
        return true;
    }

    private ElytraPreparation prepareElytraForFlight(long now) {
        if (!ensureFlightEquipmentReady(now)) return ElytraPreparation.Waiting;
        neutralizeTakeoffMovementInput();

        if (!hasTakeoffRocket()) {
            throttleWarning("No firework rocket is available for Elytra takeoff; waiting and retrying.");
            return ElytraPreparation.Waiting;
        }

        if (manualTakeoffStartedAtMs == 0L) manualTakeoffStartedAtMs = now;
        if (now - manualTakeoffStartedAtMs >= ELYTRA_TAKEOFF_TIMEOUT_MS) {
            stopTakeoffSlowdown();
            releaseManualTakeoffInput();
            throttleWarning("Grim-safe Elytra takeoff timed out; restarting from the jump stage.");
            return ElytraPreparation.Waiting;
        }

        // Syntaxia Normal + Grim Strict: the grounded tick only performs a vanilla jump.
        // START_FALL_FLYING must wait for a later tick that observes real airborne state.
        // Never send a glide start merely because an older client briefly reports airborne:
        // this launch must first have issued and observed its own grounded jump.
        if (!manualTakeoffJumped && !mc.player.isOnGround()) {
            return ElytraPreparation.Waiting;
        }
        if (mc.player.isOnGround()) {
            stopTakeoffSlowdown();
            strictLastGlideAttemptAge = Integer.MIN_VALUE;
            if (!manualTakeoffJumped) {
                manualTakeoffJumpStartY = mc.player.getY();
                manualTakeoffJumpRendered = false;
                mc.player.jump();
                manualTakeoffJumped = true;
                strictNeedsAirborneStart = true;
            }
            mc.options.jumpKey.setPressed(false);
            return ElytraPreparation.Waiting;
        }

        // In 1.21.8 Meteor's Timer changes the render-tick accumulator. A client frame can
        // therefore contain both the grounded jump tick and the following airborne tick.
        // Do not slow the client or start gliding until a render frame has actually shown
        // displacement from our vanilla jump. This keeps jump() as the source of movement
        // and prevents the takeoff from looking like (and being checked like) a position step.
        if (!manualTakeoffJumpRendered) return ElytraPreparation.Waiting;

        if (mc.player.isGliding()) {
            if (!hasActiveBoostRocket() && now - lastManualRocketMs >= ELYTRA_ROCKET_RETRY_MS) {
                if (useTakeoffRocket(now)) beginTakeoffSlowdown(now);
            }
            mc.options.jumpKey.setPressed(false);
            return ElytraPreparation.Ready;
        }

        int age = mc.player.age;
        boolean retryReady = strictLastGlideAttemptAge == Integer.MIN_VALUE
            || age < strictLastGlideAttemptAge
            || age - strictLastGlideAttemptAge >= STRICT_GLIDE_RETRY_TICKS;
        if (strictNeedsAirborneStart || retryReady) {
            sendStrictAirborneGlideStart();
            strictNeedsAirborneStart = false;
            strictLastGlideAttemptAge = age;
            if (useTakeoffRocket(now)) beginTakeoffSlowdown(now);
        }
        return ElytraPreparation.Waiting;
    }

    private void neutralizeTakeoffMovementInput() {
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

    private void sendStrictAirborneGlideStart() {
        beginTakeoffPitchHold();
        mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
            mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        mc.player.startGliding();
    }

    private void beginTakeoffPitchHold() {
        if (!takeoffPitchHoldActive) {
            takeoffPitchBeforeHold = mc.player.getPitch();
            takeoffPitchHoldActive = true;
        }
        mc.player.setPitch(-90.0F);
    }

    private void maintainTakeoffPitchHold() {
        if (takeoffPitchHoldActive && mc.player != null) mc.player.setPitch(-90.0F);
    }

    private void handOffTakeoffPitch() {
        // Leave the last safe upward pitch in place; Baritone replaces it with the first solved
        // path rotation. Restoring the old pitch here would reintroduce a one-tick floor dive.
        takeoffPitchHoldActive = false;
    }

    private void cancelTakeoffPitchHold() {
        if (!takeoffPitchHoldActive) return;
        if (mc.player != null) mc.player.setPitch(takeoffPitchBeforeHold);
        takeoffPitchHoldActive = false;
    }

    private boolean hasTakeoffRocket() {
        if (mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) return true;
        return findInventoryRocketSlot() != -1;
    }

    private int findInventoryRocketSlot() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) return i;
        }
        return -1;
    }

    private boolean useTakeoffRocket(long now) {
        if (!mc.player.isGliding() || mc.world == null || mc.interactionManager == null) return false;
        if (mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
            sendSequencedRocketUse(Hand.OFF_HAND);
            lastManualRocketMs = now;
            return true;
        }

        int rocketSlot = findInventoryRocketSlot();
        if (rocketSlot == -1 || mc.player.currentScreenHandler != mc.player.playerScreenHandler
            || !mc.player.playerScreenHandler.getCursorStack().isEmpty()) return false;

        int selected = mc.player.getInventory().getSelectedSlot();
        boolean swapped = rocketSlot != selected;
        int screenSlot = rocketSlot < 9 ? 36 + rocketSlot : rocketSlot;
        if (swapped) {
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, screenSlot,
                selected, SlotActionType.SWAP, mc.player);
        }
        try {
            sendSequencedRocketUse(Hand.MAIN_HAND);
            lastManualRocketMs = now;
            return true;
        } finally {
            if (swapped) {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, screenSlot,
                    selected, SlotActionType.SWAP, mc.player);
                lastInventoryClickMs = now;
            }
        }
    }

    private void sendSequencedRocketUse(Hand hand) {
        mc.interactionManager.sendSequencedPacket(mc.world, sequence ->
            new PlayerInteractItemC2SPacket(hand, sequence, mc.player.getYaw(), mc.player.getPitch()));
    }
    private boolean isGliderEquipped() {
        return CursorSafeChestSwap.isGliderEquipped();
    }
    private int findInventoryGliderSlot() {
        for (int i = 0; i < 36; i++) {
            if (CursorSafeChestSwap.isGlider(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }
    private int findInventoryChestplateSlot() {
        for (int i = 0; i < 36; i++) {
            if (isChestplate(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }
    private boolean isChestplate(ItemStack stack) {
        return CursorSafeChestSwap.isChestplate(stack);
    }
    private boolean refreshPossibleGhostElytra() {
        List<Integer> inventoryChestplates = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            if (isChestplate(mc.player.getInventory().getStack(i))) inventoryChestplates.add(i);
        }
        int visibleChestplates = inventoryChestplates.size() + (CursorSafeChestSwap.isChestplateEquipped() ? 1 : 0);
        if (visibleChestplates < 2) return false;

        boolean refreshed = false;
        for (int slot : inventoryChestplates) {
            if (CursorSafeChestSwap.refreshInventorySlot(slot)) refreshed = true;
        }
        if (refreshed) lastInventoryClickMs = System.currentTimeMillis();
        return refreshed;
    }
    private boolean swapChestEquipmentWithSlot(int inventorySlot) {
        if (!CursorSafeChestSwap.swapWithInventorySlot(
            inventorySlot, Math.max(0, Math.min(8, shulkerSlot.get() - 1)))) return false;
        lastInventoryClickMs = System.currentTimeMillis();
        return true;
    }
    private void releaseManualTakeoffInput() {
        if (mc.options != null) mc.options.jumpKey.setPressed(false);
        cancelTakeoffPitchHold();
        manualTakeoffStartedAtMs = 0L; lastManualRocketMs = 0L;
        strictLastGlideAttemptAge = Integer.MIN_VALUE;
        manualTakeoffJumped = false; strictNeedsAirborneStart = false;
        manualTakeoffJumpStartY = Double.NaN; manualTakeoffJumpRendered = false;
    }
    private void scheduleFlightChestRestore() {
        releaseManualTakeoffInput();
        stopTakeoffSlowdown();
        if (restoreChestplateAfterFlight) chestRestorePending = true;
    }
    private void restoreFlightChestplate(boolean force) {
        if (!restoreChestplateAfterFlight || (!force && (!chestRestorePending || !mc.player.isOnGround()))) return;
        releaseManualTakeoffInput();
        if (isGliderEquipped()) {
            int chestplateSlot = flightEquipmentSwapSlot;
            if (chestplateSlot < 0 || chestplateSlot >= 36
                || !isChestplate(mc.player.getInventory().getStack(chestplateSlot))) {
                chestplateSlot = findInventoryChestplateSlot();
            }
            if (chestplateSlot != -1 && !swapChestEquipmentWithSlot(chestplateSlot)) return;
        }
        restoreChestplateAfterFlight = false; chestRestorePending = false; elytraEquipRequestedAtMs = 0L;
        flightEquipmentSwapSlot = -1;
    }
    private void beginTakeoffSlowdown(long now) {
        if (takeoffTimerOverrideActive) return;
        Timer timer = Modules.get().get(Timer.class);
        if (timer == null) return;
        timer.setOverride(ELYTRA_TAKEOFF_TIMER);
        takeoffTimerOverrideActive = true; takeoffTimerStartedAtMs = now;
    }
    private boolean tickTakeoffSlowdown(long now) {
        if (!takeoffTimerOverrideActive) return false;
        if (SneakDesyncTracker.isServerGliding() && hasActiveBoostRocket()) {
            stopTakeoffSlowdown();
            return false;
        } else if (now - takeoffTimerStartedAtMs >= ELYTRA_TAKEOFF_SLOWDOWN_TIMEOUT_MS) {
            stopTakeoffSlowdown();
            return true;
        }

        if (mc.player.isOnGround()) return true;
        if (!mc.player.isGliding()) {
            int age = mc.player.age;
            if (strictLastGlideAttemptAge == Integer.MIN_VALUE
                || age < strictLastGlideAttemptAge
                || age - strictLastGlideAttemptAge >= STRICT_GLIDE_RETRY_TICKS) {
                sendStrictAirborneGlideStart();
                strictLastGlideAttemptAge = age;
                strictNeedsAirborneStart = false;
                useTakeoffRocket(now);
            }
        } else if (!hasActiveBoostRocket() && now - lastManualRocketMs >= ELYTRA_ROCKET_RETRY_MS) {
            useTakeoffRocket(now);
        }
        return false;
    }
    private boolean hasActiveBoostRocket() {
        if (mc.world == null || mc.player == null || boostedEntityMethodUnavailable) return false;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof FireworkRocketEntity rocket)) continue;
            try {
                if (boostedEntityMethod == null) {
                    boostedEntityMethod = rocket.getClass().getMethod("getBoostedEntity");
                }
                if (boostedEntityMethod.invoke(rocket) == mc.player) return true;
            } catch (ReflectiveOperationException ignored) {
                boostedEntityMethodUnavailable = true;
                return false;
            }
        }
        return false;
    }
    private void stopTakeoffSlowdown() {
        if (!takeoffTimerOverrideActive) return;
        Timer timer = Modules.get().get(Timer.class);
        if (timer != null) timer.setOverride(Timer.OFF);
        takeoffTimerOverrideActive = false; takeoffTimerStartedAtMs = 0L;
    }
    private void beginPathTo(BlockPos target, double distance, long now) {
        BaritoneSelectionHelper.stopOwnedElytra();
        scheduleFlightChestRestore();
        activePathTarget = target; lastPathRefreshMs = 0L; pathProgressMs = now;
        pathBestDistanceSq = distance;
        usingElytraTravel = false; elytraPathPrewarming = false;
    }
    private void updatePathProgress(double distance, long now) {
        if (distance + 0.25D < pathBestDistanceSq) { pathBestDistanceSq = distance; pathProgressMs = now; }
    }
    private void resetPathState() {
        BaritoneSelectionHelper.stopOwnedElytra();
        scheduleFlightChestRestore();
        activePathTarget = null; lastPathRefreshMs = 0L; pathProgressMs = 0L;
        pathBestDistanceSq = Double.POSITIVE_INFINITY;
        usingElytraTravel = false; elytraPathPrewarming = false;
    }
    private void planNextChunk() {
        plannedNextChunk = null;
        if (currentChunk == null || queuedChunks.isEmpty()) return;

        double bestCost = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                ChunkPos candidate = new ChunkPos(currentChunk.x + dx, currentChunk.z + dz);
                if (!queuedChunks.contains(candidate)) continue;
                double cost = transitionCost(candidate);
                if (cost < bestCost) {
                    plannedNextChunk = candidate;
                    bestCost = cost;
                }
            }
        }

        if (plannedNextChunk == null) {
            plannedNextChunk = nearestChunkTo(currentChunk.getStartX() + 8.0D, currentChunk.getStartZ() + 8.0D);
        }
    }

    private ChunkPos takePlannedOrNearestChunk() {
        ChunkPos selected = plannedNextChunk;
        plannedNextChunk = null;
        if (selected != null && queuedChunks.remove(selected)) return selected;

        selected = nearestChunkTo(mc.player.getX(), mc.player.getZ());
        if (selected == null) throw new NoSuchElementException("No queued roof chunk remains");
        queuedChunks.remove(selected);
        return selected;
    }

    private ChunkPos nearestChunkTo(double x, double z) {
        ChunkPos nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (ChunkPos chunk : queuedChunks) {
            double dx = x - (chunk.getStartX() + 8.0D);
            double dz = z - (chunk.getStartZ() + 8.0D);
            double distance = dx * dx + dz * dz;
            if (distance < nearestDistance) {
                nearest = chunk;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private double transitionCost(ChunkPos candidate) {
        if (chunkRoute.isEmpty()) {
            double dx = mc.player.getX() - (candidate.getStartX() + 8.0D);
            double dz = mc.player.getZ() - (candidate.getStartZ() + 8.0D);
            return dx * dx + dz * dz;
        }
        BlockPos first = chunkRoute.get(0);
        BlockPos last = chunkRoute.get(chunkRoute.size() - 1);
        double forward = horizontalDistanceSq(first) + distanceSq(last, candidate);
        double reverse = horizontalDistanceSq(last) + distanceSq(first, candidate);
        return Math.min(forward, reverse);
    }

    private double distanceSq(BlockPos routeEnd, ChunkPos nextChunk) {
        if (nextChunk == null) return 0.0D;
        double dx = routeEnd.getX() + 0.5D - (nextChunk.getStartX() + 8.0D);
        double dz = routeEnd.getZ() + 0.5D - (nextChunk.getStartZ() + 8.0D);
        return dx * dx + dz * dz;
    }
    private BlockPos findOverheadShulkerPlacement(Block block) {
        BlockPos origin = mc.player.getBlockPos().up(3);
        for (int[] offset : OVERHEAD_OFFSETS) {
            BlockPos pos = origin.add(offset[0], 0, offset[1]);
            if (RangeUtil.withinPlaceRange(mc.player.getEyePos(), pos)
                && PlacementManager.get().checkPlacement(pos, block).placeable()) return pos;
        }
        return null;
    }
    private void tryOpenContainer(BlockPos pos) { tryOpenContainer(pos, Direction.UP); }
    private void tryOpenContainer(BlockPos pos, Direction side) {
        if (!canInteractSafely()) return;
        if (!RangeUtil.withinPlaceRange(mc.player.getEyePos(), pos)) return;
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(pos), side, pos, false));
        mc.player.swingHand(Hand.MAIN_HAND); lastContainerOpenAttemptMs = System.currentTimeMillis();
    }
    private SilentMine ensureSilentMine() {
        SilentMine mine = Modules.get().get(SilentMine.class);
        if (mine != null && !mine.isActive()) { mine.toggle(); autoEnabledSilentMine = true; }
        return mine;
    }
    private void restoreSilentMine() {
        SilentMine mine = Modules.get().get(SilentMine.class);
        if (mine != null) {
            mine.setAllowRebreakLoop(true);
            if (autoEnabledSilentMine && mine.isActive()) mine.toggle();
        }
        autoEnabledSilentMine = false;
    }
    private boolean tickSneakDesyncGuard() {
        if (mc.options.sneakKey.isPressed()) return true;
        long now = System.currentTimeMillis();

        if (sneakRepairStage == SneakRepairStage.Idle) {
            if (!SneakDesyncTracker.isDesynced()) return false;
            info("Sneak desync detected; repairing the server pose before interacting.");
            sendSneakCommand(true);
            sneakRepairStage = SneakRepairStage.PressSent; sneakRepairStageAtMs = now;
            return true;
        }

        if (sneakRepairStage == SneakRepairStage.PressSent) {
            if (now - sneakRepairStageAtMs < SNEAK_REPAIR_PRESS_MS) return true;
            sendSneakCommand(false);
            sneakRepairStage = SneakRepairStage.AwaitingConfirmation; sneakRepairStageAtMs = now;
            return true;
        }

        if (now - sneakRepairStageAtMs < SNEAK_REPAIR_CONFIRM_MS) return true;
        if (!SneakDesyncTracker.isDesynced()) {
            sneakRepairStage = SneakRepairStage.Idle; sneakRepairStageAtMs = 0L;
            info("Sneak desync cleared; resuming Roof Mosser.");
            return false;
        }
        if (now - sneakRepairStageAtMs >= SNEAK_REPAIR_RETRY_MS) {
            sendSneakCommand(true);
            sneakRepairStage = SneakRepairStage.PressSent; sneakRepairStageAtMs = now;
            throttleWarning("Sneak pose is still desynced; retrying the repair sequence.");
        }
        return true;
    }
    private boolean canInteractSafely() {
        return !tickSneakDesyncGuard();
    }
    private void sendSneakCommand(boolean sneaking) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(new PlayerInput(
                mc.options.forwardKey.isPressed(),
                mc.options.backKey.isPressed(),
                mc.options.leftKey.isPressed(),
                mc.options.rightKey.isPressed(),
                mc.options.jumpKey.isPressed(),
                sneaking,
                mc.options.sprintKey.isPressed()
            )));
        }
    }
    private void cancelSneakRepair() {
        if (sneakRepairStage == SneakRepairStage.PressSent) {
            sendSneakCommand(false);
        }
        sneakRepairStage = SneakRepairStage.Idle; sneakRepairStageAtMs = 0L;
    }
    private void configureSafetyModules() {
        safetyModulesConfigured = true;

        if (autoEat.get()) {
            AutoEat module = Modules.get().get(AutoEat.class);
            if (module != null) {
                autoEatManaged = true;
                autoEatWasActive = module.isActive();
                if (!module.isActive()) module.toggle();
            } else warning("Meteor Auto Eat is unavailable; automatic eating could not be enabled.");
        }

        if (totemLog.get()) {
            AutoLog module = Modules.get().get(AutoLog.class);
            if (module != null) {
                autoLogManaged = true;
                autoLogWasActive = module.isActive();
                previousAutoLogSettings.clear();
                overrideAutoLogSetting(module, "health", 0);
                overrideAutoLogSetting(module, "predict-incoming-damage", false);
                overrideAutoLogSetting(module, "totem-pops", 1);
                overrideAutoLogSetting(module, "only-trusted", false);
                overrideAutoLogSetting(module, "32K", false);
                overrideAutoLogSetting(module, "smart-toggle", false);
                overrideAutoLogSetting(module, "toggle-off", false);
                overrideAutoLogSetting(module, "toggle-auto-reconnect", true);
                overrideAutoLogSetting(module, "entities", Set.of());
                if (!module.isActive()) module.toggle();
            } else warning("Meteor Auto Log is unavailable; Totem Log could not be enabled.");
        }
    }
    private void restoreSafetyModules() {
        if (!safetyModulesConfigured) return;

        if (autoEatManaged) {
            AutoEat module = Modules.get().get(AutoEat.class);
            if (module != null && module.isActive() != autoEatWasActive) module.toggle();
        }

        if (autoLogManaged) {
            AutoLog module = Modules.get().get(AutoLog.class);
            if (module != null) {
                for (Map.Entry<String, Object> entry : previousAutoLogSettings.entrySet()) {
                    restoreModuleSetting(module, entry.getKey(), entry.getValue());
                }
                if (module.isActive() != autoLogWasActive) module.toggle();
            }
        }

        safetyModulesConfigured = false;
        autoEatManaged = false; autoLogManaged = false;
        autoEatWasActive = false; autoLogWasActive = false;
        previousAutoLogSettings.clear();
    }
    private boolean isAutoEating() {
        if (!autoEatManaged) return false;
        AutoEat module = Modules.get().get(AutoEat.class);
        return module != null && module.isActive() && module.eating;
    }
    @SuppressWarnings("unchecked")
    private <T> void overrideAutoLogSetting(AutoLog module, String name, T value) {
        Setting<?> raw = module.settings.get(name);
        if (raw == null) return;
        previousAutoLogSettings.put(name, raw.get());
        ((Setting<T>) raw).set(value);
    }
    @SuppressWarnings("unchecked")
    private void restoreModuleSetting(Module module, String name, Object value) {
        Setting<?> raw = module.settings.get(name);
        if (raw != null) ((Setting<Object>) raw).set(value);
    }
    private boolean hasExternalScreenHandler() {
        return mc.player != null && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler != mc.player.playerScreenHandler;
    }
    private void closeOwnedContainer() {
        if (moduleOpenedContainer && mc.player != null && hasExternalScreenHandler()) mc.player.closeHandledScreen();
        moduleOpenedContainer = false;
    }
    private boolean insideCurrentChunk(int x, int z) {
        return currentChunk != null && Math.floorDiv(x, CHUNK_SIZE) == currentChunk.x
            && Math.floorDiv(z, CHUNK_SIZE) == currentChunk.z;
    }
    private boolean isOnRoofSurface() {
        if (mc.player == null) return false;
        double y = mc.player.getY();
        return y >= activeRoofY - ROOF_SCAN_BELOW + 0.75D
            && y <= activeRoofY + ROOF_SCAN_ABOVE + 2.25D;
    }
    private boolean isRegearState(WorkState value) {
        return switch (value) {
            case ToStation, OpeningStation, DepositingEmpties, TakingMossShulker, ReturningToWork, ToDeployedShulker,
                 PreparingShulker, PlacingShulker, OpeningShulker, UnloadingShulker, MiningShulker,
                 AwaitingPickup, WaitingForSupply -> true;
            default -> false;
        };
    }
    private void blockAutomation(String message) {
        closeOwnedContainer(); releasePickupMovement(); resetPathState(); BaritoneSelectionHelper.cancelPathing();
        state = WorkState.Blocked; warning(message);
    }
    private void warnAboutConflictingModules() {
        MossPlacer moss = Modules.get().get(MossPlacer.class);
        if (moss != null && moss.isActive()) warning("Disable Moss Placer; Roof Mosser enforces chunk boundaries itself.");
        Printer printer = Modules.get().get(Printer.class);
        if (printer != null && printer.isActive()) warning("Disable Printer while Roof Mosser owns Baritone movement.");
    }
    private double horizontalDistanceSq(BlockPos pos) {
        double dx = mc.player.getX() - (pos.getX() + 0.5), dz = mc.player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }
    private double distanceSq(BlockPos pos) { return RangeUtil.distanceSqToBox(mc.player.getEyePos(), new Box(pos)); }
    private void throttleWarning(String message, Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastWarningMs < WARNING_COOLDOWN_MS) return;
        lastWarningMs = now; warning(message, args);
    }
    private boolean isSupportedStationBlock(Block block) {
        return block instanceof ChestBlock || block instanceof TrappedChestBlock
            || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
    }
    private String serializeStation(String dimension, BlockPos pos) {
        return dimension + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
    private Set<String> serializedStationEntries() {
        Set<String> entries = new LinkedHashSet<>();
        if (stationData.get() == null || stationData.get().isBlank()) return entries;
        for (String raw : stationData.get().split(";")) if (!raw.trim().isEmpty()) entries.add(raw.trim());
        return entries;
    }
    private List<Station> stationsForCurrentDimension() {
        List<Station> stations = new ArrayList<>();
        for (String raw : serializedStationEntries()) {
            try {
                int at = raw.lastIndexOf('@'); String dimension = raw.substring(0, at);
                String[] coords = raw.substring(at + 1).split(",");
                if (!dimension.equals(activeDimension) || coords.length != 3) continue;
                stations.add(new Station(dimension, new BlockPos(Integer.parseInt(coords[0]),
                    Integer.parseInt(coords[1]), Integer.parseInt(coords[2]))));
            } catch (Throwable ignored) { }
        }
        return stations;
    }
    private void clearAutomationState() {
        releasePickupMovement();
        queuedChunks.clear(); deferredChunks.clear(); chunkRoute.clear(); stationCandidates.clear();
        pendingVerifications.clear(); pendingCompletions.clear();
        sectorMin = null; sectorMax = null; activeRoofY = roofY.get(); activeDimension = ""; currentChunk = null;
        plannedNextChunk = null;
        completedChunkCount = 0; totalChunkCount = 0; waypointIndex = 0; chunkPass = 1;
        state = WorkState.SelectingChunk;
        resetPathState(); lastPlacementMs = 0L; lastInventoryClickMs = 0L; lastContainerOpenAttemptMs = 0L;
        stateStartedMs = 0L; lastWarningMs = 0L; coordination = null; activeWorkerId = ""; apiRequest = null;
        prefetchedClaimRequest = null; prefetchedClaimChunk = null;
        apiAction = ApiAction.None; apiChunk = null; apiRetryAtMs = 0L; clearLeaseState();
        currentStation = null; workReturnPos = null; initialWorkTransitPending = true;
        pendingDepositCount = -1; pendingTakeCount = -1;
        moduleOpenedContainer = false; unloadAfterReturn = false; deployedShulkerPos = null; deployedShulkerBlock = null;
        deployedShulkerHotbar = -1; shulkerPlacementSentAtMs = 0L; shulkersBeforeMine = 0; returnableShulkerSlot = -1;
        recoveredShulkerNeedsReturn = false; blockAfterShulkerPickup = null;
        shulkerInventorySlotsBeforeMine.clear(); shulkerItemEntitiesBeforeMine.clear(); droppedShulkerEntityId = -1;
        pickupMovementActive = false; lastPickupPathRefreshMs = 0L;
        unloadBatchSentAtMs = 0L; unloadBatchPending = false;
        elytraEquipRequestedAtMs = 0L; elytraGhostRefreshRequestedAtMs = 0L;
        lastElytraGhostRefreshMs = 0L; releaseManualTakeoffInput(); stopTakeoffSlowdown();
        restoreChestplateAfterFlight = false; chestRestorePending = false;
        flightEquipmentSwapSlot = -1;
        sneakRepairStage = SneakRepairStage.Idle; sneakRepairStageAtMs = 0L;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        if (manualTakeoffJumped && !manualTakeoffJumpRendered
            && !Double.isNaN(manualTakeoffJumpStartY) && !mc.player.isOnGround()
            && mc.player.getLerpedPos(event.tickDelta).y > manualTakeoffJumpStartY + 0.01D) {
            manualTakeoffJumpRendered = true;
        }
        if (!render.get()) return;
        BlockPos target = switch (state) {
            case Working -> waypointIndex < chunkRoute.size() ? chunkRoute.get(waypointIndex).down() : null;
            case ToStation, OpeningStation, DepositingEmpties, TakingMossShulker -> currentStation == null ? null : currentStation.pos();
            case ReturningToWork -> workReturnPos;
            case ToDeployedShulker -> deployedShulkerPos;
            case PlacingShulker, OpeningShulker, UnloadingShulker, MiningShulker, AwaitingPickup -> deployedShulkerPos;
            default -> null;
        };
        if (target != null) event.renderer.box(target, targetColor.get(), targetColor.get(), ShapeMode.Lines, 0);
    }

    private enum HotbarStatus { Ready, Moved, Empty }
    private enum ElytraPreparation { Ready, Waiting }
    private enum SneakRepairStage { Idle, PressSent, AwaitingConfirmation }
    private enum ApiAction { None, Claim }
    private enum WorkState {
        SelectingChunk("selecting chunk"), ClaimingChunk("claiming chunk"), Working("working"),
        ApiWaiting("API retry"), ToStation("to regear"),
        OpeningStation("opening station"), DepositingEmpties("depositing empties"),
        TakingMossShulker("taking shulker"), ReturningToWork("returning"),
        ToDeployedShulker("to deployed shulker"),
        PreparingShulker("preparing shulker"), PlacingShulker("placing shulker"),
        OpeningShulker("opening shulker"), UnloadingShulker("unloading shulker"),
        MiningShulker("mining shulker"), AwaitingPickup("collecting shulker"),
        WaitingForSupply("waiting for supply"), StationCapture("recording stations"), Blocked("needs attention");
        private final String label;
        WorkState(String label) { this.label = label; }
    }
    private static final class PendingVerification {
        private final ChunkPos chunk;
        private long nextScanAtMs;
        private int cleanPasses;

        private PendingVerification(ChunkPos chunk, long nextScanAtMs) {
            this.chunk = chunk;
            this.nextScanAtMs = nextScanAtMs;
        }
    }
    private static final class PendingCompletion {
        private final ChunkPos chunk;
        private CompletableFuture<RoofMossCoordinationClient.Reply> request;
        private long retryAtMs;

        private PendingCompletion(ChunkPos chunk,
                                  CompletableFuture<RoofMossCoordinationClient.Reply> request,
                                  long retryAtMs) {
            this.chunk = chunk;
            this.request = request;
            this.retryAtMs = retryAtMs;
        }
    }
    private record RouteLane(int z, int left, int right) { }
    private record Station(String dimension, BlockPos pos) { }
}
