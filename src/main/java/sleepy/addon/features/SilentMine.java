package sleepy.addon.features;

import sleepy.addon.SleepyAddon;
import sleepy.addon.events.SilentMineFinishedEvent;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.OperatorBlock;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import sleepy.addon.util.RangeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SilentMine extends Module {
    private static final long INIT_TIME = System.nanoTime();
    private static final double PRIMARY_BREAK_PROGRESS = 0.7D;
    private static final double INSTANT_LANE_PROGRESS_THRESHOLD = 1.0D;
    private static final double EPSILON = 1.0E-4D;
    private static final long START_STOP_MINE_DELAY_NANOS = 300_000_000L;
    private static final int MAX_STAGED_MINE_REQUESTS = 2;
    private static final int GRIM_MINE_SENTINEL_Y_OFFSET = 1420;
    private static final double FAST_PAIR_MIN_STRONG_DELTA = 0.5D;
    private static final int FAST_PAIR_SETUP_SAMPLE_TICKS = 2;
    private static final int FAST_PAIR_MAX_SELECTION_TICKS = 10;
    private static final int FAST_PAIR_SURVIVAL_MARGIN_TICKS = 3;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Range to activate use at.")
        .defaultValue(5.4)
        .min(0.0)
        .sliderMax(7.0)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Automatically switches to the best tool.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-swap")
        .description("Spoofs the mining tool server-side instead of visibly changing the selected hotbar slot.")
        .defaultValue(false)
        .visible(autoSwitch::get)
        .build()
    );

    public final Setting<Boolean> antiRubberband = sgGeneral.add(new BoolSetting.Builder()
        .name("strict-anti-rubberband")
        .description("Attempts to prevent you from rubberbanding extra hard. May result in kicks.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> preSwitchSinglebreak = sgGeneral.add(new BoolSetting.Builder()
        .name("pre-switch-single-break")
        .description("Pre-switches to your pickaxe when the singlebreak block is almost done, for more responsive breaking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predictRebreakAir = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-rebreak-air")
        .description("Sets rebroken blocks to air client-side immediately after sending the break packet.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("do-render")
        .description("Renders the blocks in queue to be broken.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderBlock = sgRender.add(new BoolSetting.Builder()
        .name("render-block")
        .description("Whether to render the block being broken.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(renderBlock::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the rendering.")
        .defaultValue(new SettingColor(255, 180, 255, 15))
        .visible(() -> renderBlock.get() && shapeMode.get().sides())
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the rendering.")
        .defaultValue(new SettingColor(255, 255, 255, 60))
        .visible(() -> renderBlock.get() && shapeMode.get().lines())
        .build()
    );

    private final Setting<SettingColor> queuedSideColor = sgRender.add(new ColorSetting.Builder()
        .name("queued-side-color")
        .description("The side color of queued mine renders.")
        .defaultValue(new SettingColor(255, 64, 64, 20))
        .visible(() -> renderBlock.get() && shapeMode.get().sides())
        .build()
    );

    private final Setting<SettingColor> queuedLineColor = sgRender.add(new ColorSetting.Builder()
        .name("queued-line-color")
        .description("The line color of queued mine renders.")
        .defaultValue(new SettingColor(255, 64, 64, 90))
        .visible(() -> renderBlock.get() && shapeMode.get().lines())
        .build()
    );

    private final Setting<Boolean> debugRenderPrimary = sgRender.add(new BoolSetting.Builder()
        .name("debug-render-primary")
        .description("Render the primary block differently for debugging.")
        .defaultValue(false)
        .build()
    );

    private SilentMineBlock rebreakBlock;
    private SilentMineBlock delayedDestroyBlock;

    private double currentGameTickCalculated = 0;
    private boolean needSwapBack = false;
    private boolean needHiddenSwapBack = false;
    private int hiddenSwapOriginalSlot = -1;
    private int hiddenSwapSlot = -1;
    private boolean allowRebreakLoop = true;
    private boolean suppressAntiRubberbandAbort;
    private long lastMiningStopNanos;
    private final List<PendingMineRequest> stagedMineRequests = new ArrayList<>(MAX_STAGED_MINE_REQUESTS);
    private FastPairPhase fastPairPhase = FastPairPhase.NONE;
    private BlockPos fastPairFirstPos;
    private int fastPairStrongSlot = -1;
    private int fastPairWeakSlot = -1;
    private int fastPairClientSlot = -1;
    private boolean fastPairSpoofActive;
    private int fastPairSpoofOriginalSlot = -1;
    private int fastPairSpoofSlot = -1;
    private long fastPairResumeAtNanos;

    public SilentMine() {
        super(SleepyAddon.CATEGORY, "silent-mine", "Allows you to mine blocks without holding a pickaxe.");
        currentGameTickCalculated = getCurrentGameTickCalculated();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        currentGameTickCalculated = getCurrentGameTickCalculated();

        if (hasDelayedDestroy() && (mc.world.getBlockState(delayedDestroyBlock.blockPos).isAir()
            || !BlockUtils.canBreak(delayedDestroyBlock.blockPos))) {
            MeteorClient.EVENT_BUS.post(new SilentMineFinishedEvent.Post(delayedDestroyBlock.blockPos, false));
            removeDelayedDestroy(false);
        }

        if (rebreakBlock != null && (mc.world.getBlockState(rebreakBlock.blockPos).isAir()
            || !BlockUtils.canBreak(rebreakBlock.blockPos))) {
            rebreakBlock.beenAir = true;
        }

        if (rebreakBlock != null && rebreakBlock.beenAir && !allowRebreakLoop) {
            rebreakBlock = null;
        }

        if (hasRebreakBlock() && rebreakBlock.timesSendBreakPacket > 10 && !canRebreakRebreakBlock()) {
            rebreakBlock.cancelBreaking();
            rebreakBlock = null;
        }

        if (tickFastPair(mc.player.isUsingItem())) {
            if (canSwapBack()) {
                swapBackTools();
            }
            return;
        }

        if (hasDelayedDestroy() && delayedDestroyBlock.ticksHeldPickaxe <= 15) {
            BlockState blockState = mc.world.getBlockState(delayedDestroyBlock.blockPos);

            if (!blockState.isAir()) {
                FindItemResult slot = InvUtils.findFastestTool(blockState);

                if (delayedDestroyBlock.isReady(false) && !mc.player.isUsingItem()) {
                    autoSwitchToTool(slot, false);

                    MeteorClient.EVENT_BUS.post(
                        new SilentMineFinishedEvent.Pre(delayedDestroyBlock.blockPos, false)
                    );
                }

                if (delayedDestroyBlock.isReady(false)) {
                    if (!slot.found() || isToolHeld(slot.slot())) {
                        delayedDestroyBlock.ticksHeldPickaxe++;
                    }
                }
            }
        }

        if (rebreakBlock != null) {
            BlockState blockState = mc.world.getBlockState(rebreakBlock.blockPos);

            if (!blockState.isAir()) {
                FindItemResult slot = InvUtils.findFastestTool(blockState);

                if (rebreakBlock.isReady(true) && !mc.player.isUsingItem()) {
                    if (inBreakRange(rebreakBlock.blockPos)) {
                        autoSwitchToTool(slot, true);

                        MeteorClient.EVENT_BUS.post(
                            new SilentMineFinishedEvent.Pre(rebreakBlock.blockPos, true)
                        );

                        rebreakBlock.tryBreak();
                        if (predictRebreakAir.get() && rebreakBlock != null && rebreakBlock.beenAir) {
                            applyClientPredictedAir(rebreakBlock.blockPos);
                        }
                    } else {
                        rebreakBlock.cancelBreaking();
                        rebreakBlock = null;
                    }
                }
            }
        }

        if (hasDelayedDestroy() && delayedDestroyBlock.ticksHeldPickaxe > 15) {
            if (inBreakRange(delayedDestroyBlock.blockPos)) {
                if (delayedDestroyBlock.quietPackets) {
                    delayedDestroyBlock.tryBreak();
                } else {
                    delayedDestroyBlock.startBreaking(true);
                }
            } else {
                delayedDestroyBlock.cancelBreaking();
                delayedDestroyBlock = null;
            }
        }

        drainStagedMineRequests();

        if (canSwapBack()) {
            swapBackTools();
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            if (canRebreakRebreakBlock() && packet.getPos().equals(rebreakBlock.blockPos)) {
                BlockState blockState = packet.getState();

                if (!blockState.isAir()) {
                    FindItemResult slot = InvUtils.findFastestTool(blockState);

                    rebreakBlock.resetStopPacket();
                    boolean switched = autoSwitchToTool(slot, true);

                    MeteorClient.EVENT_BUS.post(
                        new SilentMineFinishedEvent.Pre(rebreakBlock.blockPos, true)
                    );

                    rebreakBlock.tryBreak();
                    if (predictRebreakAir.get() && rebreakBlock != null && rebreakBlock.beenAir) {
                        applyClientPredictedAir(rebreakBlock.blockPos);
                    }

                    if (switched) {
                        swapBackTools();
                    }
                }
            }
        }
    }

    public void silentBreakBlock(BlockPos pos, double priority) {
        silentBreakBlock(pos, Direction.UP, priority, false, MineRequestSource.Module);
    }

    public void silentBreakBlockQuiet(BlockPos pos, double priority) {
        silentBreakBlock(pos, Direction.UP, priority, true, MineRequestSource.Module);
    }

    public void silentBreakBlock(BlockPos blockPos, Direction direction, double priority) {
        silentBreakBlock(blockPos, direction, priority, false, MineRequestSource.Module);
    }

    private boolean silentBreakBlock(BlockPos blockPos, Direction direction, double priority, boolean quietPackets,
                                     MineRequestSource source) {
        if (!isActive()) return false;
        if (mc.world == null || mc.player == null) return false;
        if (blockPos == null) return false;

        blockPos = blockPos.toImmutable();
        if (source == MineRequestSource.Player) {
            prioritizePlayerMine(blockPos);
        } else if (hasPlayerOwnedMine()) {
            return false;
        }

        if (alreadyBreaking(blockPos)) return false;

        Direction resolvedDirection = resolvePacketDirection(blockPos, direction);
        BlockState state = mc.world.getBlockState(blockPos);
        if (!BlockUtils.canBreak(blockPos, state)) return false;
        if (!inBreakRange(blockPos)) return false;

        if (isInstantBreak(blockPos, state)) {
            return instantMineBlock(blockPos, resolvedDirection, state);
        }

        if (fastPairPhase != FastPairPhase.NONE) {
            return queueFastPairSecond(blockPos, resolvedDirection, priority, quietPackets, source);
        }

        if (shouldDelayNewMineStart()) {
            return stageMineRequest(blockPos, resolvedDirection, priority, quietPackets, source);
        }

        if (!hasDelayedDestroy()) {
            boolean willResetPrimary = rebreakBlock != null && !canRebreakRebreakBlock();

            if (willResetPrimary && !canReplaceMine(rebreakBlock, priority, source)) {
                return false;
            }

            currentGameTickCalculated -= 0.1;
            delayedDestroyBlock = new SilentMineBlock(blockPos, resolvedDirection, priority, quietPackets, source);
            int fastPairStrong = shouldStartFastPairCandidate(blockPos, state, quietPackets);
            if (fastPairStrong >= 0) {
                delayedDestroyBlock.activeOnlySetup = true;
                delayedDestroyBlock.forcedSetupToolSlot = fastPairStrong;
                fastPairPhase = FastPairPhase.WAITING_FOR_SECOND;
                fastPairFirstPos = blockPos;
                fastPairStrongSlot = fastPairStrong;
                fastPairClientSlot = selectedSlot();
            }

            if (!delayedDestroyBlock.startBreaking(true)) {
                delayedDestroyBlock = null;
                clearFastPairState(true, true);
                return false;
            }

            if (willResetPrimary && !isTimedStartDelayed()) {
                rebreakBlock.startBreaking(false);
            }
        }

        if (alreadyBreaking(blockPos)) return true;

        if (rebreakBlock != null && delayedDestroyBlock != null
            && (canReplaceMine(rebreakBlock, priority, source) || canRebreakRebreakBlock())) {
            if (delayedDestroyBlock.getBreakProgress() <= 0.8) {
                rebreakBlock = null;
            }
        }

        if (rebreakBlock == null) {
            if (shouldDelayNewMineStart()) {
                return stageMineRequest(blockPos, resolvedDirection, priority, quietPackets, source);
            }

            rebreakBlock = new SilentMineBlock(blockPos, resolvedDirection, priority, quietPackets, source);
            if (!rebreakBlock.startBreaking(false)) {
                rebreakBlock = null;
                return false;
            }
            return true;
        }

        return false;
    }

    @EventHandler
    public void onStartBreakingBlock(StartBreakingBlockEvent event) {
        event.cancel();
        silentBreakBlock(event.blockPos, event.direction, 100f, false, MineRequestSource.Player);
    }

    public boolean canSwapBack() {
        boolean result = needSwapBack || needHiddenSwapBack;

        if (hasDelayedDestroy() && delayedDestroyBlock.isReady(false)) {
            result = false;
        }

        return result;
    }

    public boolean hasDelayedDestroy() {
        return delayedDestroyBlock != null;
    }

    public boolean hasRebreakBlock() {
        return rebreakBlock != null && !rebreakBlock.beenAir;
    }

    public void removeDelayedDestroy(boolean sendAbort) {
        if (hasDelayedDestroy()) {
            if (sendAbort) {
                delayedDestroyBlock.cancelBreaking();
            }
            delayedDestroyBlock = null;
        }
    }

    public BlockPos getDelayedDestroyBlockPos() {
        if (delayedDestroyBlock == null) return null;
        return delayedDestroyBlock.blockPos;
    }

    public double getDelayedDestroyProgress() {
        if (delayedDestroyBlock == null) return 0;
        return delayedDestroyBlock.getBreakProgress();
    }

    public BlockPos getRebreakBlockPos() {
        if (rebreakBlock == null) return null;
        return rebreakBlock.blockPos;
    }

    public double getRebreakBlockProgress() {
        if (rebreakBlock == null) return 0;
        return rebreakBlock.getBreakProgress();
    }

    public boolean canRebreakRebreakBlock() {
        if (!allowRebreakLoop || rebreakBlock == null) return false;
        return rebreakBlock.beenAir;
    }

    public void setAllowRebreakLoop(boolean allowRebreakLoop) {
        this.allowRebreakLoop = allowRebreakLoop;
        if (!allowRebreakLoop && rebreakBlock != null && rebreakBlock.beenAir) {
            rebreakBlock = null;
        }
    }

    public boolean inBreakRange(BlockPos blockPos) {
        return (new Box(blockPos)).squaredMagnitude(mc.player.getEyePos()) <= range.get() * range.get();
    }

    public boolean alreadyBreaking(BlockPos blockPos) {
        if (blockPos == null) return false;
        return (rebreakBlock != null && blockPos.equals(rebreakBlock.blockPos))
            || (delayedDestroyBlock != null && blockPos.equals(delayedDestroyBlock.blockPos))
            || isStaged(blockPos);
    }

    public int getImmediateSlots() {
        if (!isActive() || mc.player == null || mc.world == null) return 0;
        if (shouldDelayNewMineStart()) return 0;
        if (hasDelayedDestroy()) return (rebreakBlock == null || canRebreakRebreakBlock()) ? 1 : 0;
        if (rebreakBlock == null || canRebreakRebreakBlock()) return MAX_STAGED_MINE_REQUESTS;
        return 1;
    }

    private boolean stageMineRequest(BlockPos pos, Direction direction, double priority, boolean quietPackets,
                                     MineRequestSource source) {
        if (pos == null || direction == null) return false;

        BlockPos immutablePos = pos.toImmutable();
        for (int i = 0; i < stagedMineRequests.size(); i++) {
            PendingMineRequest existing = stagedMineRequests.get(i);
            if (existing != null && immutablePos.equals(existing.pos())) {
                if (!canReplaceRequest(existing, priority, source)) return false;
                stagedMineRequests.set(i, new PendingMineRequest(immutablePos, direction, priority, quietPackets, source));
                return true;
            }
        }

        if (stagedMineRequests.size() >= MAX_STAGED_MINE_REQUESTS) {
            PendingMineRequest existing = stagedMineRequests.get(stagedMineRequests.size() - 1);
            if (!canReplaceRequest(existing, priority, source)) return false;
            stagedMineRequests.set(stagedMineRequests.size() - 1,
                new PendingMineRequest(immutablePos, direction, priority, quietPackets, source));
            return true;
        }

        stagedMineRequests.add(new PendingMineRequest(immutablePos, direction, priority, quietPackets, source));
        return true;
    }

    private void drainStagedMineRequests() {
        if (stagedMineRequests.isEmpty() || mc.player == null || mc.world == null) return;
        pruneStagedMineRequests();
        if (stagedMineRequests.isEmpty()) return;
        if (mc.player.isUsingItem() || shouldDelayNewMineStart()) return;

        while (!stagedMineRequests.isEmpty() && !shouldDelayNewMineStart() && hasImmediateMineCapacity()) {
            PendingMineRequest request = stagedMineRequests.remove(0);
            if (request == null || alreadyActive(request.pos())) continue;

            boolean started = silentBreakBlock(
                request.pos(),
                request.direction(),
                request.priority(),
                request.quietPackets(),
                request.source()
            );

            if (!started && !alreadyActive(request.pos())) {
                stagedMineRequests.add(0, request);
                return;
            }
        }
    }

    private void pruneStagedMineRequests() {
        if (mc.world == null) {
            stagedMineRequests.clear();
            return;
        }

        stagedMineRequests.removeIf(request -> {
            if (request == null || alreadyActive(request.pos())) return true;
            BlockState state = mc.world.getBlockState(request.pos());
            return !BlockUtils.canBreak(request.pos(), state) || !inBreakRange(request.pos());
        });
    }

    private boolean alreadyActive(BlockPos blockPos) {
        if (blockPos == null) return false;
        return (rebreakBlock != null && blockPos.equals(rebreakBlock.blockPos))
            || (delayedDestroyBlock != null && blockPos.equals(delayedDestroyBlock.blockPos));
    }

    private boolean isStaged(BlockPos blockPos) {
        if (blockPos == null) return false;
        for (PendingMineRequest request : stagedMineRequests) {
            if (request != null && blockPos.equals(request.pos())) return true;
        }
        return false;
    }

    private boolean hasPlayerOwnedMine() {
        if (delayedDestroyBlock != null && delayedDestroyBlock.source == MineRequestSource.Player) return true;
        if (rebreakBlock != null && rebreakBlock.source == MineRequestSource.Player) return true;
        for (PendingMineRequest request : stagedMineRequests) {
            if (request != null && request.source() == MineRequestSource.Player) return true;
        }
        return false;
    }

    private void prioritizePlayerMine(BlockPos requestedPos) {
        if (requestedPos == null) return;

        stagedMineRequests.removeIf(request -> request != null
            && request.source() == MineRequestSource.Module);

        if (fastPairPhase != FastPairPhase.NONE && isFastPairModuleOwned()) {
            clearFastPairState(true, true);
        }

        if (delayedDestroyBlock != null && delayedDestroyBlock.source == MineRequestSource.Module) {
            if (requestedPos.equals(delayedDestroyBlock.blockPos) && !delayedDestroyBlock.activeOnlySetup) {
                delayedDestroyBlock.source = MineRequestSource.Player;
            } else {
                delayedDestroyBlock.cancelBreaking();
                delayedDestroyBlock = null;
            }
        }

        if (rebreakBlock != null && rebreakBlock.source == MineRequestSource.Module) {
            if (requestedPos.equals(rebreakBlock.blockPos) && !rebreakBlock.activeOnlySetup) {
                rebreakBlock.source = MineRequestSource.Player;
            } else {
                rebreakBlock.cancelBreaking();
                rebreakBlock = null;
            }
        }
    }

    private boolean isFastPairModuleOwned() {
        return (delayedDestroyBlock != null && delayedDestroyBlock.activeOnlySetup
            && delayedDestroyBlock.source == MineRequestSource.Module)
            || (rebreakBlock != null && rebreakBlock.activeOnlySetup
            && rebreakBlock.source == MineRequestSource.Module);
    }

    private boolean canReplaceMine(SilentMineBlock existing, double priority, MineRequestSource source) {
        if (existing == null) return true;
        if (source == MineRequestSource.Player && existing.source == MineRequestSource.Module) return true;
        if (source == MineRequestSource.Module && existing.source == MineRequestSource.Player) return false;
        return priority >= existing.priority;
    }

    private boolean canReplaceRequest(PendingMineRequest existing, double priority, MineRequestSource source) {
        if (existing == null) return true;
        if (source == MineRequestSource.Player && existing.source() == MineRequestSource.Module) return true;
        if (source == MineRequestSource.Module && existing.source() == MineRequestSource.Player) return false;
        return priority >= existing.priority();
    }

    private boolean autoSwitchToTool(FindItemResult slot, boolean requireNoPendingSwap) {
        if (!autoSwitch.get() || slot == null || !slot.found()) return false;
        if (isToolHeld(slot.slot())) return false;
        if (requireNoPendingSwap && (needSwapBack || needHiddenSwapBack)) return false;

        if (hideSwap.get() && slot.slot() >= 0 && slot.slot() <= 8) {
            hiddenSwapOriginalSlot = selectedSlot();
            hiddenSwapSlot = slot.slot();
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hiddenSwapSlot));
            needHiddenSwapBack = true;
        } else {
            InvUtils.swap(slot.slot(), true);
            needSwapBack = true;
        }

        return true;
    }

    private boolean isToolHeld(int slot) {
        if (mc.player == null) return false;
        if (selectedSlot() == slot) return true;
        return needHiddenSwapBack && hiddenSwapSlot == slot;
    }

    private void swapBackTools() {
        if (needHiddenSwapBack) {
            if (hiddenSwapOriginalSlot >= 0 && hiddenSwapOriginalSlot <= 8) {
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hiddenSwapOriginalSlot));
            }
            needHiddenSwapBack = false;
            hiddenSwapOriginalSlot = -1;
            hiddenSwapSlot = -1;
        }

        if (needSwapBack) {
            InvUtils.swapBack();
            needSwapBack = false;
        }
    }

    private boolean isTimedStartDelayed() {
        if (lastMiningStopNanos == 0L) return false;
        return System.nanoTime() - lastMiningStopNanos < START_STOP_MINE_DELAY_NANOS;
    }

    private boolean shouldDelayNewMineStart() {
        return isTimedStartDelayed();
    }

    private boolean hasImmediateMineCapacity() {
        if (!hasDelayedDestroy()) return true;
        return rebreakBlock == null || canRebreakRebreakBlock();
    }

    private int shouldStartFastPairCandidate(BlockPos pos, BlockState state, boolean quietPackets) {
        if (quietPackets) return -1;
        if (fastPairPhase != FastPairPhase.NONE || hasDelayedDestroy() || rebreakBlock != null || !stagedMineRequests.isEmpty()) {
            return -1;
        }
        if (needSwapBack || needHiddenSwapBack || fastPairSpoofActive) return -1;

        int strongSlot = findBestHotbarToolSlot(pos, state);
        if (strongSlot < 0 || !isFastPairBlock(pos, state, strongSlot)) return -1;

        int requiredTicks = fastPairRequiredParkTicks(pos, state, strongSlot);
        if (requiredTicks < 0) return -1;
        return findFastPairWeakSlot(pos, state, strongSlot, -1, requiredTicks) >= 0 ? strongSlot : -1;
    }

    private boolean queueFastPairSecond(BlockPos pos, Direction direction, double priority, boolean quietPackets,
                                        MineRequestSource source) {
        if (quietPackets || pos == null || direction == null || mc.world == null) return false;
        if (fastPairPhase != FastPairPhase.WAITING_FOR_SECOND
            && fastPairPhase != FastPairPhase.WAITING_TO_PARK
            && fastPairPhase != FastPairPhase.PARKED_WEAK) {
            return false;
        }
        if (delayedDestroyBlock != null && !canReplaceMine(delayedDestroyBlock, priority, source)) return false;
        if (!isValidFastPairFirst() || pos.equals(fastPairFirstPos)) return false;

        BlockState secondState = mc.world.getBlockState(pos);
        int secondStrong = findBestHotbarToolSlot(pos, secondState);
        if (secondStrong < 0 || !isFastPairBlock(pos, secondState, secondStrong)) return false;

        BlockState firstState = mc.world.getBlockState(fastPairFirstPos);
        int requiredTicks = fastPairRequiredParkTicks(fastPairFirstPos, firstState, fastPairStrongSlot);
        if (requiredTicks < 0) return false;
        if (findFastPairWeakSlot(
            fastPairFirstPos,
            firstState,
            fastPairStrongSlot,
            secondStrong,
            Math.max(requiredTicks, currentGameTickCalculated - delayedDestroyBlock.destroyProgressStart)
        ) < 0) {
            return false;
        }

        if (!stageMineRequest(pos, direction, priority, false, source)) return false;
        if (fastPairPhase == FastPairPhase.WAITING_FOR_SECOND) {
            fastPairPhase = FastPairPhase.WAITING_TO_PARK;
        }
        return true;
    }

    private boolean tickFastPair(boolean usingItem) {
        if (fastPairPhase == FastPairPhase.NONE) return false;
        if (mc.player == null || mc.world == null || usingItem) {
            clearFastPairState(true, true);
            return false;
        }

        if (fastPairPhase == FastPairPhase.WAITING_FOR_SECOND) {
            if (!isValidFastPairFirst()) {
                clearFastPairState(true, true);
                return false;
            }

            double elapsed = Math.max(0.0D, currentGameTickCalculated - delayedDestroyBlock.destroyProgressStart);
            int requiredTicks = delayedDestroyBlock.getGrimRequiredTicks();
            int selectionTicks = Math.min(Math.max(FAST_PAIR_SETUP_SAMPLE_TICKS, requiredTicks), FAST_PAIR_MAX_SELECTION_TICKS);
            if (elapsed + EPSILON < selectionTicks) return true;

            clearFastPairTracking(true);
            return false;
        }

        if (fastPairPhase == FastPairPhase.WAITING_TO_PARK) {
            if (!isValidFastPairFirst() || stagedMineRequests.isEmpty()) {
                clearFastPairState(true, false);
                return false;
            }

            double elapsed = Math.max(0.0D, currentGameTickCalculated - delayedDestroyBlock.destroyProgressStart);
            int requiredTicks = delayedDestroyBlock.getGrimRequiredTicks();
            if (requiredTicks < 0) {
                clearFastPairState(true, true);
                return false;
            }
            if (elapsed + EPSILON < requiredTicks) return true;

            PendingMineRequest second = firstStagedMineRequest();
            if (second == null || !canBreak(second.pos()) || !inBreakRange(second.pos())) {
                clearFastPairState(true, true);
                return false;
            }

            BlockState firstState = mc.world.getBlockState(delayedDestroyBlock.blockPos);
            BlockState secondState = mc.world.getBlockState(second.pos());
            int secondStrong = findBestHotbarToolSlot(second.pos(), secondState);
            int weakSlot = findFastPairWeakSlot(delayedDestroyBlock.blockPos, firstState, fastPairStrongSlot, secondStrong, elapsed);
            if (weakSlot < 0) {
                clearFastPairState(true, true);
                return false;
            }

            return parkFastPairFirst(weakSlot);
        }

        if (fastPairPhase == FastPairPhase.PARKED_WEAK) {
            if (!isParkedFastPairFirstValid() || stagedMineRequests.isEmpty()) {
                clearFastPairState(true, false);
                return false;
            }
            if (System.nanoTime() < fastPairResumeAtNanos || isTimedStartDelayed()) return true;

            PendingMineRequest second = firstStagedMineRequest();
            if (second == null || !canBreak(second.pos()) || !inBreakRange(second.pos())) {
                clearFastPairState(true, true);
                return false;
            }

            BlockState secondState = mc.world.getBlockState(second.pos());
            int secondStrong = findBestHotbarToolSlot(second.pos(), secondState);
            if (secondStrong < 0 || secondStrong == fastPairWeakSlot) {
                clearFastPairState(true, true);
                return false;
            }

            stagedMineRequests.remove(second);
            beginFastPairSpoof(secondStrong);
            fastPairStrongSlot = secondStrong;
            rebreakBlock = new SilentMineBlock(second.pos(), second.direction(), second.priority(), false, second.source());
            rebreakBlock.activeOnlySetup = true;
            rebreakBlock.forcedSetupToolSlot = secondStrong;
            if (!rebreakBlock.startBreaking(false)) {
                rebreakBlock = null;
                clearFastPairState(true, true);
                return false;
            }

            fastPairPhase = FastPairPhase.ACTIVE_SECOND;
            return true;
        }

        if (fastPairPhase == FastPairPhase.ACTIVE_SECOND) {
            if (rebreakBlock == null || !rebreakBlock.activeOnlySetup || rebreakBlock.stopPacketSent) {
                clearFastPairTracking(true);
                return false;
            }

            double elapsed = Math.max(0.0D, currentGameTickCalculated - rebreakBlock.destroyProgressStart);
            if (elapsed + EPSILON < FAST_PAIR_SETUP_SAMPLE_TICKS) return true;

            clearFastPairTracking(true);
            return false;
        }

        return false;
    }

    private boolean isValidFastPairFirst() {
        return delayedDestroyBlock != null
            && delayedDestroyBlock.activeOnlySetup
            && !delayedDestroyBlock.stopPacketSent
            && fastPairFirstPos != null
            && fastPairFirstPos.equals(delayedDestroyBlock.blockPos)
            && canBreak(delayedDestroyBlock.blockPos)
            && inBreakRange(delayedDestroyBlock.blockPos);
    }

    private boolean isParkedFastPairFirstValid() {
        return delayedDestroyBlock != null
            && !delayedDestroyBlock.activeOnlySetup
            && delayedDestroyBlock.stopPacketSent
            && fastPairFirstPos != null
            && fastPairFirstPos.equals(delayedDestroyBlock.blockPos)
            && canBreak(delayedDestroyBlock.blockPos)
            && inBreakRange(delayedDestroyBlock.blockPos);
    }

    private boolean parkFastPairFirst(int weakSlot) {
        if (delayedDestroyBlock == null || mc.player == null) return false;

        beginFastPairSpoof(weakSlot);
        fastPairWeakSlot = weakSlot;

        delayedDestroyBlock.activeOnlySetup = false;
        delayedDestroyBlock.tryBreak();
        if (!delayedDestroyBlock.stopPacketSent) {
            clearFastPairState(true, true);
            return false;
        }

        fastPairPhase = FastPairPhase.PARKED_WEAK;
        fastPairResumeAtNanos = System.nanoTime() + START_STOP_MINE_DELAY_NANOS;
        return true;
    }

    private PendingMineRequest firstStagedMineRequest() {
        for (PendingMineRequest request : stagedMineRequests) {
            if (request != null) return request;
        }
        return null;
    }

    private boolean canBreak(BlockPos pos) {
        return pos != null && mc.world != null && BlockUtils.canBreak(pos, mc.world.getBlockState(pos));
    }

    private boolean isFastPairBlock(BlockPos pos, BlockState state, int strongSlot) {
        if (pos == null || state == null || state.isAir() || strongSlot < 0 || strongSlot > 8) return false;
        double strongDelta = getBreakingDeltaForSlot(pos, state, strongSlot);
        return Double.isFinite(strongDelta)
            && strongDelta + EPSILON >= FAST_PAIR_MIN_STRONG_DELTA
            && strongDelta < INSTANT_LANE_PROGRESS_THRESHOLD - EPSILON;
    }

    private int fastPairRequiredParkTicks(BlockPos pos, BlockState state, int strongSlot) {
        if (pos == null || state == null || strongSlot < 0 || strongSlot > 8) return -1;
        double strongDelta = getBreakingDeltaForSlot(pos, state, strongSlot);
        if (!Double.isFinite(strongDelta)
            || strongDelta <= EPSILON
            || strongDelta >= INSTANT_LANE_PROGRESS_THRESHOLD - EPSILON) {
            return -1;
        }
        int required = roundedGrimBreakTicks(strongDelta);
        return required < 0 ? -1 : Math.max(FAST_PAIR_SETUP_SAMPLE_TICKS, required);
    }

    private int roundedGrimBreakTicks(double blockDamage) {
        if (!Double.isFinite(blockDamage) || blockDamage <= EPSILON) return -1;
        double required = Math.ceil(1.0D / blockDamage);
        if (!Double.isFinite(required) || required > Integer.MAX_VALUE) return -1;
        return Math.max(1, (int) required);
    }

    private int findFastPairWeakSlot(BlockPos pos, BlockState state, int firstStrongSlot, int secondStrongSlot, double elapsedTicks) {
        if (pos == null || state == null || mc.player == null) return -1;

        int requiredTicks = fastPairRequiredParkTicks(pos, state, firstStrongSlot);
        if (requiredTicks < 0) return -1;
        double stopElapsed = Math.max(requiredTicks, elapsedTicks) + 1.0D;
        double survivalElapsed = stopElapsed + fastPairRestartTicks() + FAST_PAIR_SURVIVAL_MARGIN_TICKS;
        int selected = selectedSlot();
        int bestSlot = -1;
        double bestDelta = Double.POSITIVE_INFINITY;

        for (int slot = 0; slot < 9; slot++) {
            if (slot == firstStrongSlot || slot == secondStrongSlot) continue;
            double delta = getBreakingDeltaForSlot(pos, state, slot);
            if (delta * stopElapsed >= PRIMARY_BREAK_PROGRESS - EPSILON) continue;
            if (delta * survivalElapsed >= INSTANT_LANE_PROGRESS_THRESHOLD - EPSILON) continue;

            if (delta < bestDelta - EPSILON
                || (Math.abs(delta - bestDelta) <= EPSILON && slot == selected)) {
                bestSlot = slot;
                bestDelta = delta;
            }
        }

        return bestSlot;
    }

    private int fastPairRestartTicks() {
        return (int) Math.ceil(START_STOP_MINE_DELAY_NANOS / 50_000_000.0D);
    }

    private int findBestHotbarToolSlot(BlockPos pos, BlockState state) {
        if (mc.player == null || state == null || state.isAir()) return -1;

        int bestSlot = -1;
        double bestDelta = 0.0D;
        for (int slot = 0; slot < 9; slot++) {
            double delta = getBreakingDeltaForSlot(pos, state, slot);
            if (delta > bestDelta) {
                bestDelta = delta;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private double getBreakingDeltaForSlot(BlockPos pos, BlockState state, int slot) {
        if (slot < 0 || slot > 8 || state == null || state.isAir()) return 0.0D;
        return getBreakDelta(getBlockBreakingSpeed(slot, state), state, pos);
    }

    private void beginFastPairSpoof(int slot) {
        if (mc.player == null || mc.getNetworkHandler() == null || slot < 0 || slot > 8) return;
        if (!fastPairSpoofActive) {
            fastPairSpoofOriginalSlot = selectedSlot();
        }
        fastPairSpoofSlot = slot;
        fastPairSpoofActive = true;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private void endFastPairSpoof(boolean restore) {
        if (!fastPairSpoofActive) return;
        if (restore && fastPairSpoofOriginalSlot >= 0 && fastPairSpoofOriginalSlot <= 8 && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(fastPairSpoofOriginalSlot));
        }
        fastPairSpoofActive = false;
        fastPairSpoofOriginalSlot = -1;
        fastPairSpoofSlot = -1;
    }

    private void clearFastPairState(boolean restoreSpoof, boolean clearQueuedSecond) {
        if (clearQueuedSecond) stagedMineRequests.clear();
        clearFastPairTracking(restoreSpoof);
    }

    private void clearFastPairTracking(boolean restoreSpoof) {
        if (restoreSpoof) endFastPairSpoof(true);
        fastPairPhase = FastPairPhase.NONE;
        fastPairFirstPos = null;
        fastPairStrongSlot = -1;
        fastPairWeakSlot = -1;
        fastPairClientSlot = -1;
        fastPairResumeAtNanos = 0L;
    }

    private Direction resolvePacketDirection(BlockPos pos, Direction direction) {
        if (RangeUtil.isGrimDirectionEnabled() && mc.player != null && pos != null) {
            return RangeUtil.bestMineDirectionToBlock(mc.player.getEyePos(), pos);
        }
        if (direction != null) return direction;
        if (mc.player == null || pos == null) return Direction.UP;

        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d eye = mc.player.getEyePos();
        Direction resolved = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        return resolved == null ? Direction.UP : resolved;
    }

    private boolean sendMineAction(PlayerActionC2SPacket.Action action, BlockPos pos, Direction direction) {
        return sendMineAction(action, pos, direction, true);
    }

    private boolean sendMineAction(PlayerActionC2SPacket.Action action, BlockPos pos, Direction direction,
                                   boolean countsForMineDelay) {
        if (mc.getNetworkHandler() == null || pos == null) return false;

        suppressAntiRubberbandAbort = true;
        try {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(action, pos, direction, getSeq()));
        } finally {
            suppressAntiRubberbandAbort = false;
        }

        if (countsForMineDelay
            && (action == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK
            || action == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK)) {
            lastMiningStopNanos = System.nanoTime();
        }
        return true;
    }

    private boolean sendAbort(BlockPos pos, Direction direction) {
        if (mc.getNetworkHandler() == null || pos == null) return false;
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
            pos,
            Direction.DOWN
        ));
        return true;
    }

    private boolean sendStartStopLaneSetup(BlockPos pos, Direction direction) {
        Direction packetDirection = resolvePacketDirection(pos, direction);
        BlockPos sentinel = pos.up(GRIM_MINE_SENTINEL_Y_OFFSET);

        return sendMineAction(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, packetDirection)
            && sendMineAction(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, sentinel, Direction.DOWN)
            && sendMineAction(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, packetDirection);
    }

    private boolean isInstantBreak(BlockPos pos, BlockState state) {
        return getBestBreakingDelta(pos, state) >= INSTANT_LANE_PROGRESS_THRESHOLD - EPSILON;
    }

    private double getBestBreakingDelta(BlockPos pos, BlockState state) {
        if (mc.player == null || mc.world == null || pos == null || state == null || state.isAir()) return 0.0D;

        FindItemResult slot = InvUtils.findFastestTool(state);
        int toolSlot = slot.found() ? slot.slot() : selectedSlot();
        return getBreakDelta(getBlockBreakingSpeed(toolSlot, state), state, pos);
    }

    private boolean instantMineBlock(BlockPos pos, Direction direction, BlockState state) {
        if (pos == null || state == null || !BlockUtils.canBreak(pos, state) || !inBreakRange(pos)) return false;

        FindItemResult slot = InvUtils.findFastestTool(state);
        boolean switched = autoSwitchToTool(slot, true);
        MeteorClient.EVENT_BUS.post(new SilentMineFinishedEvent.Pre(pos, true));
        boolean sent = sendMineAction(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, resolvePacketDirection(pos, direction), false);
        if (sent) {
            applyVanillaPredictedBreak(pos, slot.found() ? slot.slot() : -1);
            MeteorClient.EVENT_BUS.post(new SilentMineFinishedEvent.Post(pos, true));
        }
        if (switched) {
            swapBackTools();
        }
        return sent;
    }

    private void applyVanillaPredictedBreak(BlockPos pos, int toolSlot) {
        if (pos == null) return;
        if (!mc.isOnThread()) {
            BlockPos taskPos = pos.toImmutable();
            mc.execute(() -> applyVanillaPredictedBreak(taskPos, toolSlot));
            return;
        }
        if (mc.player == null || mc.world == null) return;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) return;

        ItemStack miningStack = toolSlot >= 0 && toolSlot <= 8
            ? mc.player.getInventory().getStack(toolSlot)
            : mc.player.getMainHandStack();
        if (state.isToolRequired() && !miningStack.isSuitableFor(state)) return;

        Block block = state.getBlock();
        if (block instanceof OperatorBlock && !mc.player.isCreativeLevelTwoOp()) return;

        block.onBreak(mc.world, pos, state, mc.player);
        FluidState fluidState = mc.world.getFluidState(pos);
        if (mc.world.setBlockState(pos, fluidState.getBlockState(), 11)) {
            block.onBroken(mc.world, pos, state);
            clearAttachedPistonHead(pos, state);
        }
    }

    private boolean applyClientPredictedAir(BlockPos pos) {
        if (pos == null) return false;
        if (!mc.isOnThread()) {
            BlockPos taskPos = pos.toImmutable();
            mc.execute(() -> applyClientPredictedAir(taskPos));
            return false;
        }
        if (mc.world == null) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) return true;

        BlockState replacement = mc.world.getFluidState(pos).getBlockState();
        boolean replaced = mc.world.setBlockState(pos, replacement, 11);
        if (replaced) {
            clearAttachedPistonHead(pos, state);
        }
        return replaced;
    }

    private void clearAttachedPistonHead(BlockPos pistonPos, BlockState pistonState) {
        if (mc.world == null || pistonPos == null || pistonState == null) return;
        if (!isPistonBaseState(pistonState) || !pistonState.contains(Properties.FACING)) return;
        if (pistonState.contains(Properties.EXTENDED) && !pistonState.get(Properties.EXTENDED)) return;

        BlockPos headPos = pistonPos.offset(pistonState.get(Properties.FACING));
        BlockState headState = mc.world.getBlockState(headPos);
        if (!headState.isOf(Blocks.PISTON_HEAD) && !headState.isOf(Blocks.MOVING_PISTON)) return;

        BlockState replacement = mc.world.getFluidState(headPos).getBlockState();
        mc.world.setBlockState(headPos, replacement, 11);
    }

    private boolean isPistonBaseState(BlockState state) {
        return state != null && (state.isOf(Blocks.PISTON)
            || state.isOf(Blocks.STICKY_PISTON)
            || state.isOf(Blocks.MOVING_PISTON));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (render.get()) {
            double calculatedDrawGameTick = getCurrentGameTickCalculated();

            if (rebreakBlock != null) {
                rebreakBlock.render(event, calculatedDrawGameTick, true);
            }

            if (delayedDestroyBlock != null) {
                delayedDestroyBlock.render(event, calculatedDrawGameTick, false);
            }

            for (PendingMineRequest request : stagedMineRequests) {
                if (request != null && request.pos() != null) {
                    event.renderer.box(request.pos(), queuedSideColor.get(), queuedLineColor.get(), shapeMode.get(), 0);
                }
            }
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (event.packet instanceof PlayerActionC2SPacket packet
            && packet.getAction() == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
            && antiRubberband.get()
            && !suppressAntiRubberbandAbort
            && (packet.getPos().equals(getRebreakBlockPos())
                || packet.getPos().equals(getDelayedDestroyBlockPos()))) {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, packet.getPos(), Direction.DOWN
            ));
        }
    }

    private int getSeq() {
        return mc.world.getPendingUpdateManager().incrementSequence().getSequence();
    }

    private static double getCurrentGameTickCalculated() {
        return (double) (System.nanoTime() - INIT_TIME)
            / (double) TimeUnit.MILLISECONDS.toNanos(50L);
    }

    private double getBreakDelta(double breakingSpeed, BlockState state, BlockPos pos) {
        float hardness = state.getHardness(mc.world, pos);
        if (hardness == -1) return 0;
        return breakingSpeed / hardness / 30;
    }

    private double getBlockBreakingSpeed(int slot, BlockState block) {
        double speed = mc.player.getInventory().main.get(slot).getMiningSpeedMultiplier(block);

        if (speed > 1) {
            ItemStack tool = mc.player.getInventory().getStack(slot);
            int efficiency = Utils.getEnchantmentLevel(tool, Enchantments.EFFICIENCY);
            if (efficiency > 0 && !tool.isEmpty()) speed += efficiency * efficiency + 1;
        }

        if (StatusEffectUtil.hasHaste(mc.player)) {
            speed *= 1 + (StatusEffectUtil.getHasteAmplifier(mc.player) + 1) * 0.2F;
        }

        if (mc.player.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
            float k = switch (mc.player.getStatusEffect(StatusEffects.MINING_FATIGUE).getAmplifier()) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };

            speed *= k;
        }

        if (mc.player.isSubmergedIn(FluidTags.WATER)) {
            speed *= mc.player.getAttributeValue(EntityAttributes.SUBMERGED_MINING_SPEED);
        }

        if (!mc.player.isOnGround()) {
            speed /= 5.0F;
        }

        return speed;
    }

    private int selectedSlot() {
        return mc.player.getInventory().selectedSlot;
    }

    private record PendingMineRequest(BlockPos pos, Direction direction, double priority, boolean quietPackets,
                                      MineRequestSource source) {
    }

    private enum MineRequestSource {
        Player,
        Module
    }

    private enum FastPairPhase {
        NONE,
        WAITING_FOR_SECOND,
        WAITING_TO_PARK,
        PARKED_WEAK,
        ACTIVE_SECOND
    }

    class SilentMineBlock {
        public BlockPos blockPos;
        public Direction direction;
        public boolean started = false;
        public int timesSendBreakPacket = 0;
        public int ticksHeldPickaxe = 0;
        public boolean beenAir = false;
        public boolean stopPacketSent = false;
        public boolean activeOnlySetup = false;
        public int forcedSetupToolSlot = -1;
        public MineRequestSource source;
        private final boolean quietPackets;
        private double destroyProgressStart = 0;
        private double grimStartDelta = 0.0D;
        private double priority = 0;

        public SilentMineBlock(BlockPos blockPos, Direction direction, double priority) {
            this(blockPos, direction, priority, false, MineRequestSource.Module);
        }

        public SilentMineBlock(BlockPos blockPos, Direction direction, double priority, boolean quietPackets,
                               MineRequestSource source) {
            this.blockPos = blockPos;
            this.direction = direction;
            this.priority = priority;
            this.quietPackets = quietPackets;
            this.source = source == null ? MineRequestSource.Module : source;
        }

        public boolean isReady(boolean isRebreak) {
            double breakProgressSingleTick = getBreakProgressSingleTick();
            boolean instantLane = breakProgressSingleTick >= INSTANT_LANE_PROGRESS_THRESHOLD - EPSILON;
            if (activeOnlySetup) {
                return getGrimDeadlineProgress(currentGameTickCalculated) >= 1.0D - EPSILON
                    || timesSendBreakPacket > 0;
            }

            double threshold = isRebreak ? (instantLane ? 1.0D : PRIMARY_BREAK_PROGRESS)
                : 1.0 - (preSwitchSinglebreak.get() ? (breakProgressSingleTick / 2.0) : 0.0);

            return getBreakProgress() >= threshold || timesSendBreakPacket > 0;
        }

        public boolean startBreaking(boolean isDelayedDestroy) {
            ticksHeldPickaxe = 0;
            timesSendBreakPacket = 0;
            stopPacketSent = false;
            this.destroyProgressStart = currentGameTickCalculated;

            if (isDelayedDestroy && canRebreakRebreakBlock()) {
                rebreakBlock = null;
            }

            if (quietPackets) {
                started = sendQuietBreakPulse();
                return started;
            }

            FindItemResult slot = InvUtils.findFastestTool(mc.world.getBlockState(blockPos));
            int setupToolSlot = forcedSetupToolSlot >= 0 ? forcedSetupToolSlot : (slot.found() ? slot.slot() : -1);
            if (forcedSetupToolSlot >= 0) {
                beginFastPairSpoof(forcedSetupToolSlot);
            } else {
                autoSwitchToTool(slot, false);
            }
            grimStartDelta = setupToolSlot >= 0 && setupToolSlot <= 8
                ? getBreakingDeltaForSlot(blockPos, mc.world.getBlockState(blockPos), setupToolSlot)
                : getBestBreakingDelta(blockPos, mc.world.getBlockState(blockPos));

            boolean setupSent = activeOnlySetup
                ? sendMineAction(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, blockPos, direction)
                : sendStartStopLaneSetup(blockPos, direction);
            if (!setupSent) {
                started = false;
                return false;
            }

            stopPacketSent = !activeOnlySetup;
            started = true;
            return true;
        }

        public void tryBreak() {
            boolean countsForMineDelay = !isSingleTickBreak();
            if (quietPackets) {
                if (sendQuietStop(countsForMineDelay)) stopPacketSent = true;
                timesSendBreakPacket++;
                return;
            }

            if (!sendMineAction(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, countsForMineDelay)) return;
            stopPacketSent = true;
            timesSendBreakPacket++;
        }

        private boolean sendQuietBreakPulse() {
            suppressAntiRubberbandAbort = true;
            try {
                boolean sent = sendStartStopLaneSetup(blockPos, direction);
                stopPacketSent = sent;
                return sent;
            } finally {
                suppressAntiRubberbandAbort = false;
            }
        }

        private boolean sendQuietStop(boolean countsForMineDelay) {
            suppressAntiRubberbandAbort = true;
            try {
                return sendMineAction(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, countsForMineDelay);
            } finally {
                suppressAntiRubberbandAbort = false;
            }
        }

        private boolean isSingleTickBreak() {
            return getBreakProgressSingleTick() >= INSTANT_LANE_PROGRESS_THRESHOLD - EPSILON;
        }

        public void cancelBreaking() {
            sendAbort(blockPos, direction);
        }

        public void resetStopPacket() {
            stopPacketSent = false;
        }

        public double getBreakProgress() {
            return getBreakProgress(currentGameTickCalculated);
        }

        public double getBreakProgress(double gameTick) {
            BlockState state = mc.world.getBlockState(blockPos);
            FindItemResult slot = InvUtils.findFastestTool(state);

            int toolSlot = slot.found() ? slot.slot() : selectedSlot();
            double breakingSpeed = getBlockBreakingSpeed(toolSlot, state);

            return Math.min(
                getBreakDelta(breakingSpeed, state, blockPos) * (gameTick - destroyProgressStart),
                1.0
            );
        }

        public double getBreakProgressSingleTick() {
            return getBreakProgress(destroyProgressStart + 1);
        }

        public int getGrimRequiredTicks() {
            return roundedGrimBreakTicks(grimStartDelta);
        }

        public double getGrimDeadlineProgress(double gameTick) {
            int requiredTicks = getGrimRequiredTicks();
            if (requiredTicks < 0) return 0.0D;
            double elapsed = Math.max(0.0D, gameTick - destroyProgressStart);
            return MathHelper.clamp(elapsed / requiredTicks, 0.0D, 1.0D);
        }

        public double getPriority() {
            return priority;
        }

        public void render(Render3DEvent event, double renderTick, boolean isPrimary) {
            VoxelShape shape = mc.world.getBlockState(blockPos).getOutlineShape(mc.world, blockPos);
            if (shape == null || shape.isEmpty()) {
                event.renderer.box(blockPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                return;
            }

            Box orig = shape.getBoundingBox();
            double progress = activeOnlySetup ? getGrimDeadlineProgress(renderTick) : getBreakProgress(renderTick);

            double shrinkFactor = 1d - MathHelper.clamp(
                isPrimary && !activeOnlySetup ? progress * (1 / PRIMARY_BREAK_PROGRESS) : progress,
                0,
                1
            );
            BlockPos pos = blockPos;

            Box box = orig.shrink(orig.getLengthX() * shrinkFactor,
                orig.getLengthY() * shrinkFactor, orig.getLengthZ() * shrinkFactor);

            double xShrink = (orig.getLengthX() * shrinkFactor) / 2;
            double yShrink = (orig.getLengthY() * shrinkFactor) / 2;
            double zShrink = (orig.getLengthZ() * shrinkFactor) / 2;

            double x1 = pos.getX() + box.minX + xShrink;
            double y1 = pos.getY() + box.minY + yShrink;
            double z1 = pos.getZ() + box.minZ + zShrink;
            double x2 = pos.getX() + box.maxX + xShrink;
            double y2 = pos.getY() + box.maxY + yShrink;
            double z2 = pos.getZ() + box.maxZ + zShrink;

            Color color = sideColor.get();

            if (debugRenderPrimary.get() && isPrimary) {
                color = Color.ORANGE.a(40);
            }

            event.renderer.box(x1, y1, z1, x2, y2, z2, color, lineColor.get(), shapeMode.get(), 0);
        }
    }
}
