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
import net.minecraft.block.BlockState;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;

import java.util.concurrent.TimeUnit;

public class SilentMine extends Module {
    private static final long INIT_TIME = System.nanoTime();

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
    private boolean allowRebreakLoop = true;

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

        if (hasDelayedDestroy() && delayedDestroyBlock.ticksHeldPickaxe <= 15) {
            BlockState blockState = mc.world.getBlockState(delayedDestroyBlock.blockPos);

            if (!blockState.isAir()) {
                FindItemResult slot = InvUtils.findFastestTool(blockState);

                if (delayedDestroyBlock.isReady(false) && !mc.player.isUsingItem()) {
                    if (autoSwitch.get() && slot.found()
                        && mc.player.getInventory().selectedSlot != slot.slot()) {
                        InvUtils.swap(slot.slot(), true);
                        needSwapBack = true;
                    }

                    MeteorClient.EVENT_BUS.post(
                        new SilentMineFinishedEvent.Pre(delayedDestroyBlock.blockPos, false)
                    );
                }

                if (delayedDestroyBlock.isReady(false)) {
                    if (!slot.found() || mc.player.getInventory().selectedSlot == slot.slot()) {
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
                        if (autoSwitch.get() && slot.found()
                            && mc.player.getInventory().selectedSlot != slot.slot()
                            && !needSwapBack) {
                            InvUtils.swap(slot.slot(), true);
                            needSwapBack = true;
                        }

                        MeteorClient.EVENT_BUS.post(
                            new SilentMineFinishedEvent.Pre(rebreakBlock.blockPos, true)
                        );

                        rebreakBlock.tryBreak();
                    } else {
                        rebreakBlock.cancelBreaking();
                        rebreakBlock = null;
                    }
                }
            }
        }

        if (hasDelayedDestroy() && delayedDestroyBlock.ticksHeldPickaxe > 15) {
            if (inBreakRange(delayedDestroyBlock.blockPos)) {
                delayedDestroyBlock.startBreaking(true);
            } else {
                delayedDestroyBlock.cancelBreaking();
                delayedDestroyBlock = null;
            }
        }

        if (canSwapBack()) {
            InvUtils.swapBack();
            needSwapBack = false;
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            if (canRebreakRebreakBlock() && packet.getPos().equals(rebreakBlock.blockPos)) {
                BlockState blockState = packet.getState();

                if (!blockState.isAir()) {
                    FindItemResult slot = InvUtils.findFastestTool(blockState);

                    if (autoSwitch.get() && slot.found()
                        && mc.player.getInventory().selectedSlot != slot.slot()
                        && !needSwapBack) {
                        InvUtils.swap(slot.slot(), true);
                    }

                    MeteorClient.EVENT_BUS.post(
                        new SilentMineFinishedEvent.Pre(rebreakBlock.blockPos, true)
                    );

                    rebreakBlock.tryBreak();

                    if (autoSwitch.get() && slot.found()) {
                        InvUtils.swapBack();
                    }
                }
            }
        }
    }

    public void silentBreakBlock(BlockPos pos, double priority) {
        silentBreakBlock(pos, Direction.UP, priority);
    }

    public void silentBreakBlock(BlockPos blockPos, Direction direction, double priority) {
        if (!isActive()) return;
        if (blockPos == null || alreadyBreaking(blockPos)) return;
        if (!BlockUtils.canBreak(blockPos, mc.world.getBlockState(blockPos))) return;
        if (!inBreakRange(blockPos)) return;

        if (!hasDelayedDestroy()) {
            boolean willResetPrimary = rebreakBlock != null && !canRebreakRebreakBlock();

            if (willResetPrimary && rebreakBlock.priority < priority) {
                return;
            }

            currentGameTickCalculated -= 0.1;
            delayedDestroyBlock = new SilentMineBlock(blockPos, direction, priority);

            delayedDestroyBlock.startBreaking(true);

            if (willResetPrimary) {
                rebreakBlock.startBreaking(false);
            }
        }

        if (alreadyBreaking(blockPos)) return;

        if (rebreakBlock != null && delayedDestroyBlock != null
            && (priority >= rebreakBlock.priority || canRebreakRebreakBlock())) {
            if (delayedDestroyBlock.getBreakProgress() <= 0.8) {
                rebreakBlock = null;
            }
        }

        if (rebreakBlock == null) {
            rebreakBlock = new SilentMineBlock(blockPos, direction, priority);
            rebreakBlock.startBreaking(false);
        }
    }

    @EventHandler
    public void onStartBreakingBlock(StartBreakingBlockEvent event) {
        event.cancel();
        silentBreakBlock(event.blockPos, event.direction, 100f);
    }

    public boolean canSwapBack() {
        boolean result = needSwapBack;

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
        return (rebreakBlock != null && blockPos.equals(rebreakBlock.blockPos))
            || (delayedDestroyBlock != null && blockPos.equals(delayedDestroyBlock.blockPos));
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
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (event.packet instanceof PlayerActionC2SPacket packet
            && packet.getAction() == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
            && antiRubberband.get()
            && (packet.getPos().equals(getRebreakBlockPos())
                || packet.getPos().equals(getDelayedDestroyBlockPos()))) {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, packet.getPos(), packet.getDirection()
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

    class SilentMineBlock {
        public BlockPos blockPos;
        public Direction direction;
        public boolean started = false;
        public int timesSendBreakPacket = 0;
        public int ticksHeldPickaxe = 0;
        public boolean beenAir = false;
        private double destroyProgressStart = 0;
        private double priority = 0;

        public SilentMineBlock(BlockPos blockPos, Direction direction, double priority) {
            this.blockPos = blockPos;
            this.direction = direction;
            this.priority = priority;
        }

        public boolean isReady(boolean isRebreak) {
            double breakProgressSingleTick = getBreakProgressSingleTick();
            double threshold = isRebreak ? 0.7
                : 1.0 - (preSwitchSinglebreak.get() ? (breakProgressSingleTick / 2.0) : 0.0);

            return getBreakProgress() >= threshold || timesSendBreakPacket > 0;
        }

        public void startBreaking(boolean isDelayedDestroy) {
            ticksHeldPickaxe = 0;
            timesSendBreakPacket = 0;
            this.destroyProgressStart = currentGameTickCalculated;

            if (isDelayedDestroy && canRebreakRebreakBlock()) {
                rebreakBlock = null;
            }

            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, getSeq()
            ));

            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, blockPos, direction, getSeq()
            ));

            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, getSeq()
            ));

            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, getSeq()
            ));

            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, blockPos, direction, getSeq()
            ));

            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, getSeq()
            ));

            if (!antiRubberband.get()) {
                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction
                ));

                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction
                ));
            }

            started = true;
        }

        public void tryBreak() {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, getSeq()
            ));

            if (!antiRubberband.get()) {
                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction
                ));

                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction
                ));
            }

            timesSendBreakPacket++;
        }

        public void cancelBreaking() {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction
            ));
        }

        public double getBreakProgress() {
            return getBreakProgress(currentGameTickCalculated);
        }

        public double getBreakProgress(double gameTick) {
            BlockState state = mc.world.getBlockState(blockPos);
            FindItemResult slot = InvUtils.findFastestTool(state);

            int toolSlot = slot.found() ? slot.slot() : mc.player.getInventory().selectedSlot;
            double breakingSpeed = getBlockBreakingSpeed(toolSlot, state);

            return Math.min(
                getBreakDelta(breakingSpeed, state, blockPos) * (gameTick - destroyProgressStart),
                1.0
            );
        }

        public double getBreakProgressSingleTick() {
            return getBreakProgress(destroyProgressStart + 1);
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

            double shrinkFactor = 1d - MathHelper.clamp(
                isPrimary ? getBreakProgress(renderTick) * (1 / 0.7) : getBreakProgress(renderTick),
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
