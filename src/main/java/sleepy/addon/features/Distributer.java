package sleepy.addon.features;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.Nullable;
import sleepy.addon.SleepyAddon;
import sleepy.addon.events.UpdateEvent;
import sleepy.addon.manager.PlacementManager;
import sleepy.addon.util.RangeUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Distributer extends Module {
    private static final long PENDING_COUNT_MS = 5000L;
    private static final long PENDING_INSERT_MS = 2500L;
    private static final long DEFAULT_RETRY_DELAY_MS = 300L;
    private static final int ROTATION_PRIORITY = 60;
    private static final Direction[] PLACEMENT_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> mapsPerChunk = sgGeneral.add(new IntSetting.Builder()
        .name("maps-per-chunk")
        .description("Maximum number of filled map frames the module will place in one chunk.")
        .defaultValue(4)
        .range(1, 16)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Integer> minMapSpacing = sgGeneral.add(new IntSetting.Builder()
        .name("min-map-spacing")
        .description("Minimum block spacing between filled map frames.")
        .defaultValue(5)
        .range(1, 12)
        .sliderRange(1, 12)
        .build()
    );

    private final Setting<Boolean> reuseEmptyFrames = sgGeneral.add(new BoolSetting.Builder()
        .name("reuse-empty-frames")
        .description("Uses nearby empty item frames before placing new ones.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> retryDelayMs = sgGeneral.add(new IntSetting.Builder()
        .name("retry-delay-ms")
        .description("Delay between retry attempts while waiting for real item frame entities.")
        .defaultValue(300)
        .range(0, 500)
        .sliderRange(0, 500)
        .build()
    );

    private final PlacementManager placementManager = PlacementManager.get();
    private final List<PendingInsert> pendingInserts = new CopyOnWriteArrayList<>();
    private final Map<Long, List<Long>> pendingChunkCounts = new ConcurrentHashMap<>();

    public Distributer() {
        super(SleepyAddon.CATEGORY, "distributer", "Distributes filled maps into visible item frames.");
    }

    @Override
    public void onActivate() {
        clearState();
    }

    @Override
    public void onDeactivate() {
        clearState();
        InvUtils.swapBack();
    }

    @EventHandler
    private void onTick(UpdateEvent event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        pruneState(now);

        if (retryPendingInserts(now) || !pendingInserts.isEmpty()) return;

        FindItemResult mapItem = findMapItem();
        if (!mapItem.found()) return;

        if (reuseEmptyFrames.get() && tryFillReusableFrame(mapItem)) return;
        if (!findFrameItem().found()) return;
        if (placementManager.getRemainingQuota() <= 0) return;

        PlacementTarget target = findPlacementTarget();
        if (target == null) return;

        placeFrameAndQueueMap(target, now);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive() || mc.player == null || mc.world == null) return;
        if (!(event.packet instanceof EntitySpawnS2CPacket spawn)) return;
        if (spawn.getEntityType() != EntityType.ITEM_FRAME) return;

        BlockPos pos = BlockPos.ofFloored(spawn.getX(), spawn.getY(), spawn.getZ());
        Direction facing = decodeFrameFacing(spawn.getEntityData());
        PendingInsert pending = findPendingInsert(pos, facing);
        if (pending == null) return;

        ItemFrameEntity frame = new ItemFrameEntity(mc.world, pos, facing);
        frame.setId(spawn.getEntityId());
        pending.nextRetryMs = System.currentTimeMillis() + Math.max(0L, retryDelayMs.get());

        FindItemResult mapItem = findMapItem();
        if (mapItem.found() && tryInsertMap(frame, mapItem)) {
            pendingInserts.remove(pending);
        }
    }

    private void clearState() {
        pendingInserts.clear();
        pendingChunkCounts.clear();
    }

    private void pruneState(long now) {
        pendingInserts.removeIf(pending -> now - pending.createdAtMs > PENDING_INSERT_MS);
        for (Iterator<Map.Entry<Long, List<Long>>> it = pendingChunkCounts.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, List<Long>> entry = it.next();
            entry.getValue().removeIf(time -> now - time > PENDING_COUNT_MS);
            if (entry.getValue().isEmpty()) it.remove();
        }
    }

    private boolean retryPendingInserts(long now) {
        FindItemResult mapItem = findMapItem();
        boolean scheduled = false;

        for (PendingInsert pending : pendingInserts) {
            if (now < pending.nextRetryMs) continue;

            ItemFrameEntity frame = findRealFrame(pending.framePos, pending.facing);
            if (frame != null && !frame.getHeldItemStack().isEmpty()) {
                pendingInserts.remove(pending);
                continue;
            }

            pending.nextRetryMs = now + Math.max(0L, retryDelayMs.get());
            if (frame != null && mapItem.found()) {
                if (tryInsertMap(frame, mapItem)) {
                    pendingInserts.remove(pending);
                }
                scheduled = true;
            }
        }

        return scheduled;
    }

    private boolean tryFillReusableFrame(FindItemResult mapItem) {
        ItemFrameEntity frame = findReusableFrame();
        return frame != null && tryInsertMap(frame, mapItem);
    }

    private boolean placeFrameAndQueueMap(PlacementTarget target, long now) {
        if (target == null) return false;
        return runWithImmediateSnapRotation(target.hit.getPos(), () -> {
            if (placementManager.airInteractManyHits(List.of(target.hit), Items.ITEM_FRAME).isEmpty()) {
                return false;
            }
            pendingInserts.add(new PendingInsert(target.framePos.toImmutable(), target.facing, now));
            addPendingChunkCount(target.chunkKey, now);
            return true;
        });
    }

    private boolean tryInsertMap(ItemFrameEntity frame, FindItemResult mapItem) {
        if (frame == null || !frame.isAlive() || frame.isRemoved()) return false;
        if (!mapItem.found() || !frame.getHeldItemStack().isEmpty()) return false;

        Vec3d eye = mc.player.getEyePos();
        if (!isWithinMapInteractRange(eye, frame)) return false;
        BlockPos framePos = frame.getAttachedBlockPos();
        boolean pendingPlacedFrame = isPendingFrame(framePos, frame.getHorizontalFacing());
        if (!pendingPlacedFrame && placementManager.isOnCooldown(framePos)) return false;
        if (placementManager.getRemainingQuota() <= 0) return false;

        FrameInteractTarget interactTarget = resolveFrameInteractTarget(frame);
        if (interactTarget == null) return false;

        boolean success = runWithImmediateSnapRotation(interactTarget.point, () -> withItem(mapItem, hand -> {
            ActionResult result = mc.interactionManager.interactEntityAtLocation(mc.player, frame, interactTarget.hit, hand);
            if (result == null || !result.isAccepted()) {
                result = mc.interactionManager.interactEntity(mc.player, frame, hand);
            }
            if (result == null || !result.isAccepted()) return false;

            return true;
        }));
        if (success) {
            placementManager.markCooldownFor(framePos);
        }
        return success;
    }

    private boolean withItem(FindItemResult item, HandAction action) {
        if (!item.found() || action == null) return false;
        if (item.isOffhand()) return action.run(Hand.OFF_HAND);
        if (!item.isHotbar()) return false;
        if (item.isMainHand()) return action.run(Hand.MAIN_HAND);

        if (!InvUtils.swap(item.slot(), true)) return false;
        try {
            return action.run(Hand.MAIN_HAND);
        } finally {
            InvUtils.swapBack();
        }
    }

    private boolean runWithImmediateSnapRotation(Vec3d target, RotationAction action) {
        if (mc.player == null || mc.getNetworkHandler() == null || target == null || action == null) return false;

        float yaw = computeYaw(target);
        float pitch = computePitch(target);

        float prevYaw = mc.player.getYaw();
        float prevPitch = mc.player.getPitch();
        float prevHeadYaw = mc.player.getHeadYaw();
        float prevBodyYaw = mc.player.bodyYaw;

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        mc.player.setHeadYaw(yaw);
        mc.player.bodyYaw = yaw;

        try {
            sendImmediateLookPacket(yaw, pitch);
            return action.run();
        } finally {
            mc.player.setYaw(prevYaw);
            mc.player.setPitch(prevPitch);
            mc.player.setHeadYaw(prevHeadYaw);
            mc.player.bodyYaw = prevBodyYaw;
        }
    }

    private void sendImmediateLookPacket(float yaw, float pitch) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        Packet<?> packet = createImmediateLookPacket(yaw, pitch);
        if (packet != null) {
            mc.getNetworkHandler().sendPacket(packet);
        }
    }

    private Packet<?> createImmediateLookPacket(float yaw, float pitch) {
        try {
            Class<?> packetClass = Class.forName("net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket$LookAndOnGround");
            try {
                return (Packet<?>) packetClass
                    .getConstructor(float.class, float.class, boolean.class, boolean.class)
                    .newInstance(yaw, pitch, mc.player.isOnGround(), false);
            } catch (NoSuchMethodException ignored) {
                return (Packet<?>) packetClass
                    .getConstructor(float.class, float.class, boolean.class)
                    .newInstance(yaw, pitch, mc.player.isOnGround());
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private float computeYaw(Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        return (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    private float computePitch(Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
    }

    @Nullable
    private PlacementTarget findPlacementTarget() {
        if (mc.player == null || mc.world == null) return null;

        int scan = MathHelper.ceil(RangeUtil.PLACE_RANGE) + 1;
        BlockPos origin = mc.player.getBlockPos();
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f).normalize();
        int spacing = Math.max(1, minMapSpacing.get());
        List<MapAnchor> spacingAnchors = collectSpacingAnchors(origin, scan + spacing + 2);

        PlacementTarget best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int dx = -scan; dx <= scan; dx++) {
            for (int dy = -scan; dy <= scan; dy++) {
                for (int dz = -scan; dz <= scan; dz++) {
                    BlockPos support = origin.add(dx, dy, dz);
                    if (!mc.world.isInBuildLimit(support)) continue;

                    BlockState supportState = mc.world.getBlockState(support);
                    if (!supportState.isSolid()) continue;

                    for (Direction side : PLACEMENT_DIRECTIONS) {
                        BlockPos framePos = support.offset(side);
                        if (!isValidFrameSpace(framePos, side)) continue;
                        if (hasSameMapAnchor(framePos, side, spacingAnchors)) continue;
                        if (!hasMinimumMapSpacing(framePos, spacingAnchors, spacing)) continue;

                        long chunkKey = ChunkPos.toLong(framePos);
                        int chunkCount = countMapsInChunk(chunkKey);
                        if (chunkCount >= Math.max(1, mapsPerChunk.get())) continue;

                        Vec3d center = frameCenter(framePos, side);
                        Vec3d toCenter = center.subtract(eye);
                        if (!isWithinMapInteractRange(eye, framePos, side)) continue;
                        if (!isInVisibleSight(eye, look, center, side)) continue;

                        BlockHitResult hit = blockHit(support, side);
                        double score = scoreCandidate(toCenter, look, side, chunkCount);
                        if (score < bestScore) {
                            bestScore = score;
                            best = new PlacementTarget(framePos.toImmutable(), side, hit, chunkKey);
                        }
                    }
                }
            }
        }

        return best;
    }

    private boolean isValidFrameSpace(BlockPos framePos, Direction facing) {
        if (mc.world == null || framePos == null || facing == null) return false;
        if (!mc.world.isInBuildLimit(framePos)) return false;

        BlockState state = mc.world.getBlockState(framePos);
        if (!state.isAir() && !state.isReplaceable()) return false;
        if (findRealFrame(framePos, facing) != null) return false;

        Box box = frameBox(framePos, facing).expand(0.01);
        return mc.world.getOtherEntities(null, box, entity -> entity != null && entity.isAlive() && !entity.isRemoved()).isEmpty();
    }

    private boolean isInVisibleSight(Vec3d eye, Vec3d look, Vec3d center, Direction facing) {
        if (eye == null || look == null || center == null || facing == null) return false;

        Vec3d toCenter = center.subtract(eye);
        if (toCenter.lengthSquared() <= 1.0e-6) return false;
        Vec3d normalized = toCenter.normalize();
        if (look.dotProduct(normalized) < 0.25D) return false;

        Vec3d facingVector = Vec3d.of(facing.getVector()).normalize();
        Vec3d fromCenter = eye.subtract(center);
        if (fromCenter.lengthSquared() <= 1.0e-6) return false;
        return fromCenter.normalize().dotProduct(facingVector) >= 0.15D;
    }

    private double scoreCandidate(Vec3d toCenter, Vec3d look, Direction facing, int chunkCount) {
        double distSq = toCenter.lengthSquared();
        double centerDot = distSq <= 1.0e-6 ? 0.0D : look.dotProduct(toCenter.normalize());
        double verticalPenalty = facing.getAxis().isVertical() ? 1.75D : 0.0D;
        return distSq - centerDot * 4.0D + verticalPenalty + chunkCount * 1.25D;
    }

    private List<MapAnchor> collectSpacingAnchors(BlockPos origin, int radius) {
        ArrayList<MapAnchor> anchors = new ArrayList<>();
        if (mc.world != null && origin != null) {
            Box search = new Box(origin).expand(radius);
            for (ItemFrameEntity frame : mc.world.getEntitiesByClass(ItemFrameEntity.class, search, this::isFilledMapFrame)) {
                addAnchorIfAbsent(anchors, frame.getAttachedBlockPos(), frame.getHorizontalFacing());
            }
        }

        for (PendingInsert pending : pendingInserts) {
            addAnchorIfAbsent(anchors, pending.framePos, pending.facing);
        }
        return anchors;
    }

    private boolean isFilledMapFrame(ItemFrameEntity frame) {
        return frame != null
            && frame.isAlive()
            && !frame.isRemoved()
            && (frame.containsMap() || frame.getHeldItemStack().isOf(Items.FILLED_MAP));
    }

    private void addAnchorIfAbsent(List<MapAnchor> anchors, BlockPos framePos, Direction facing) {
        if (anchors == null || framePos == null || facing == null) return;
        MapAnchor anchor = new MapAnchor(framePos.toImmutable(), facing);
        if (!anchors.contains(anchor)) anchors.add(anchor);
    }

    private boolean hasSameMapAnchor(BlockPos framePos, Direction facing, List<MapAnchor> anchors) {
        if (anchors == null || anchors.isEmpty()) return false;
        for (MapAnchor anchor : anchors) {
            if (anchor.facing == facing && anchor.framePos.equals(framePos)) return true;
        }
        return false;
    }

    private boolean hasMinimumMapSpacing(BlockPos framePos, List<MapAnchor> anchors, int minSpacing) {
        if (anchors == null || anchors.isEmpty()) return true;
        int minSpacingSq = minSpacing * minSpacing;
        for (MapAnchor anchor : anchors) {
            if (framePos.getSquaredDistance(anchor.framePos) < minSpacingSq) return false;
        }
        return true;
    }

    @Nullable
    private ItemFrameEntity findReusableFrame() {
        if (mc.player == null || mc.world == null) return null;

        int scan = MathHelper.ceil(RangeUtil.PLACE_RANGE) + 1;
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f).normalize();
        Box search = new Box(mc.player.getBlockPos()).expand(scan);

        ItemFrameEntity best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (ItemFrameEntity frame : mc.world.getEntitiesByClass(ItemFrameEntity.class, search,
            candidate -> candidate != null && candidate.isAlive() && !candidate.isRemoved() && candidate.getHeldItemStack().isEmpty())) {
            BlockPos framePos = frame.getAttachedBlockPos();
            Direction facing = frame.getHorizontalFacing();
            if (framePos == null || isPendingFrame(framePos, facing)) continue;
            if (placementManager.isOnCooldown(framePos)) continue;

            Vec3d center = frameCenter(framePos, facing);
            Vec3d toCenter = center.subtract(eye);
            if (!isWithinMapInteractRange(eye, frame)) continue;
            if (!isInVisibleSight(eye, look, center, facing)) continue;

            double score = scoreCandidate(toCenter, look, facing, countMapsInChunk(ChunkPos.toLong(framePos)));
            if (score < bestScore) {
                bestScore = score;
                best = frame;
            }
        }

        return best;
    }

    @Nullable
    private FrameInteractTarget resolveFrameInteractTarget(ItemFrameEntity frame) {
        if (mc.player == null || mc.world == null || frame == null) return null;
        Vec3d eye = mc.player.getEyePos();
        if (!isWithinMapInteractRange(eye, frame)) return null;

        for (Vec3d sample : buildFrameInteractSamples(frame, eye)) {
            if (!hasFrameLineOfSight(eye, sample)) continue;
            return new FrameInteractTarget(sample, new EntityHitResult(frame, sample));
        }

        return null;
    }

    private List<Vec3d> buildFrameInteractSamples(ItemFrameEntity frame, Vec3d eye) {
        ArrayList<Vec3d> samples = new ArrayList<>(9);
        Box box = frame.getBoundingBox();
        Direction facing = frame.getHorizontalFacing();

        double insetX = Math.min(0.18, Math.max(0.02, (box.maxX - box.minX) * 0.22));
        double insetY = Math.min(0.18, Math.max(0.02, (box.maxY - box.minY) * 0.22));
        double insetZ = Math.min(0.18, Math.max(0.02, (box.maxZ - box.minZ) * 0.22));
        double frontInset = 0.01;

        double centerX = (box.minX + box.maxX) * 0.5;
        double centerY = (box.minY + box.maxY) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;

        double sampleX = centerX;
        double sampleY = Math.max(box.minY + insetY, Math.min(eye.y, box.maxY - insetY));
        double sampleZ = centerZ;

        if (facing.getAxis() == Direction.Axis.X) {
            sampleX = facing == Direction.EAST ? box.maxX - frontInset : box.minX + frontInset;
            addFrameInteractSample(samples, new Vec3d(sampleX, sampleY, sampleZ));
            addFrameInteractSample(samples, new Vec3d(sampleX, centerY, sampleZ));
            addFrameInteractSample(samples, new Vec3d(sampleX, box.maxY - insetY, sampleZ));
            double[] ys = {box.minY + insetY, box.maxY - insetY};
            double[] zs = {box.minZ + insetZ, box.maxZ - insetZ};
            for (double y : ys) {
                for (double z : zs) {
                    addFrameInteractSample(samples, new Vec3d(sampleX, y, z));
                }
            }
        } else if (facing.getAxis() == Direction.Axis.Y) {
            sampleY = facing == Direction.UP ? box.maxY - frontInset : box.minY + frontInset;
            sampleX = Math.max(box.minX + insetX, Math.min(eye.x, box.maxX - insetX));
            sampleZ = Math.max(box.minZ + insetZ, Math.min(eye.z, box.maxZ - insetZ));
            addFrameInteractSample(samples, new Vec3d(sampleX, sampleY, sampleZ));
            addFrameInteractSample(samples, new Vec3d(centerX, sampleY, centerZ));
            double[] xs = {box.minX + insetX, box.maxX - insetX};
            double[] zs = {box.minZ + insetZ, box.maxZ - insetZ};
            for (double x : xs) {
                for (double z : zs) {
                    addFrameInteractSample(samples, new Vec3d(x, sampleY, z));
                }
            }
        } else {
            sampleZ = facing == Direction.SOUTH ? box.maxZ - frontInset : box.minZ + frontInset;
            addFrameInteractSample(samples, new Vec3d(sampleX, sampleY, sampleZ));
            addFrameInteractSample(samples, new Vec3d(sampleX, centerY, sampleZ));
            addFrameInteractSample(samples, new Vec3d(sampleX, box.maxY - insetY, sampleZ));
            double[] xs = {box.minX + insetX, box.maxX - insetX};
            double[] ys = {box.minY + insetY, box.maxY - insetY};
            for (double x : xs) {
                for (double y : ys) {
                    addFrameInteractSample(samples, new Vec3d(x, y, sampleZ));
                }
            }
        }

        return samples;
    }

    private void addFrameInteractSample(List<Vec3d> samples, Vec3d sample) {
        if (samples == null || sample == null) return;
        for (Vec3d existing : samples) {
            if (existing.squaredDistanceTo(sample) <= 1.0e-6) return;
        }
        samples.add(sample);
    }

    private boolean hasFrameLineOfSight(Vec3d eye, Vec3d target) {
        if (mc.world == null || mc.player == null || eye == null || target == null) return false;
        HitResult result = mc.world.raycast(new RaycastContext(
            eye,
            target,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));
        return result == null || result.getType() == HitResult.Type.MISS;
    }

    @Nullable
    private PendingInsert findPendingInsert(BlockPos framePos, Direction facing) {
        for (PendingInsert pending : pendingInserts) {
            if (pending.framePos.equals(framePos) && pending.facing == facing) return pending;
        }
        return null;
    }

    private boolean isPendingFrame(BlockPos framePos, Direction facing) {
        return findPendingInsert(framePos, facing) != null;
    }

    @Nullable
    private ItemFrameEntity findRealFrame(BlockPos framePos, Direction facing) {
        if (mc.world == null || framePos == null || facing == null) return null;
        Box search = frameBox(framePos, facing).expand(0.08);
        List<ItemFrameEntity> frames = mc.world.getEntitiesByClass(ItemFrameEntity.class, search,
            frame -> frame != null
                && frame.isAlive()
                && !frame.isRemoved()
                && framePos.equals(frame.getAttachedBlockPos())
                && frame.getHorizontalFacing() == facing);
        return frames.isEmpty() ? null : frames.get(0);
    }

    private boolean isWithinMapInteractRange(Vec3d eye, BlockPos framePos, Direction facing) {
        if (eye == null || framePos == null || facing == null) return false;
        return RangeUtil.distanceSqToBox(eye, frameBox(framePos, facing)) <= RangeUtil.ENTITY_RANGE_SQ;
    }

    private boolean isWithinMapInteractRange(Vec3d eye, ItemFrameEntity frame) {
        if (eye == null || frame == null) return false;
        return RangeUtil.distanceSqToBox(eye, frame.getBoundingBox()) <= RangeUtil.ENTITY_RANGE_SQ;
    }

    private FindItemResult findMapItem() {
        return InvUtils.findInHotbar(Items.FILLED_MAP);
    }

    private FindItemResult findFrameItem() {
        return InvUtils.findInHotbar(Items.ITEM_FRAME);
    }

    private int countMapsInChunk(long chunkKey) {
        int count = pendingChunkCount(chunkKey);
        if (mc.world == null) return count;

        ChunkPos chunk = new ChunkPos(chunkKey);
        int minY = mc.world.getBottomY();
        int maxY = minY + mc.world.getHeight();
        Box chunkBox = new Box(chunk.getStartX(), minY, chunk.getStartZ(), chunk.getEndX() + 1, maxY, chunk.getEndZ() + 1);
        for (ItemFrameEntity frame : mc.world.getEntitiesByClass(ItemFrameEntity.class, chunkBox,
            candidate -> candidate != null && candidate.isAlive() && !candidate.isRemoved())) {
            if (frame.containsMap() || frame.getHeldItemStack().isOf(Items.FILLED_MAP)) {
                count++;
            }
        }
        return count;
    }

    private int pendingChunkCount(long chunkKey) {
        List<Long> entries = pendingChunkCounts.get(chunkKey);
        return entries == null ? 0 : entries.size();
    }

    private void addPendingChunkCount(long chunkKey, long now) {
        pendingChunkCounts.computeIfAbsent(chunkKey, ignored -> new CopyOnWriteArrayList<>()).add(now);
    }

    private BlockHitResult blockHit(BlockPos supportPos, Direction side) {
        Vec3d hit = Vec3d.ofCenter(supportPos).add(Vec3d.of(side.getVector()).multiply(0.5D));
        return new BlockHitResult(hit, side, supportPos, false);
    }

    private Direction decodeFrameFacing(int entityData) {
        Direction[] directions = Direction.values();
        return directions[Math.floorMod(entityData, directions.length)];
    }

    private Vec3d frameCenter(BlockPos framePos, Direction facing) {
        return Vec3d.ofCenter(framePos).offset(facing, -0.46875D);
    }

    private Box frameBox(BlockPos framePos, Direction facing) {
        Vec3d center = frameCenter(framePos, facing);
        Direction.Axis axis = facing.getAxis();
        double x = axis == Direction.Axis.X ? 0.0625D : 0.75D;
        double y = axis == Direction.Axis.Y ? 0.0625D : 0.75D;
        double z = axis == Direction.Axis.Z ? 0.0625D : 0.75D;
        return Box.of(center, x, y, z);
    }

    private record PlacementTarget(BlockPos framePos, Direction facing, BlockHitResult hit, long chunkKey) {}

    private record MapAnchor(BlockPos framePos, Direction facing) {}

    private record FrameInteractTarget(Vec3d point, EntityHitResult hit) {}

    private static final class PendingInsert {
        private final BlockPos framePos;
        private final Direction facing;
        private final long createdAtMs;
        private long nextRetryMs;

        private PendingInsert(BlockPos framePos, Direction facing, long createdAtMs) {
            this.framePos = framePos;
            this.facing = facing;
            this.createdAtMs = createdAtMs;
            this.nextRetryMs = createdAtMs + DEFAULT_RETRY_DELAY_MS;
        }
    }

    @FunctionalInterface
    private interface HandAction {
        boolean run(Hand hand);
    }

    @FunctionalInterface
    private interface RotationAction {
        boolean run();
    }
}
