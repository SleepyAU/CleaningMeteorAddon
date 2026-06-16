package sleepy.addon.manager;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.consume.UseAction;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import sleepy.addon.features.AntiCheat;
import sleepy.addon.util.RangeUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central placement manager (blocks and item air-interacts).
 * Burst placement: select hotbar slot once → OFF_HAND swap → N air-interacts → swap back → restore slot.
 * Rate-limited by the shared action limiter (default 9 per 300 ms).
 * Hotbar-only (no container swaps).
 */
public final class PlacementManager {
    public static final int BLOCK_INTERACT_LIMIT = 9;
    public static final int BLOCK_INTERACT_WINDOW_MS = 300;
    public static final int APPROX_BLOCK_INTERACTS_PER_SECOND = BLOCK_INTERACT_LIMIT * 1000 / BLOCK_INTERACT_WINDOW_MS;
    private static final int HUD_SECOND_WINDOW_MS = 1000;

    public enum PlacementDenyReason {
        NONE,
        INVALID_INPUT,
        NOT_REPLACEABLE,
        OUT_OF_RANGE,
        BLOCKED_BY_ENTITY,
        WORLD_REJECTED,
        EATING
    }

    public record PlacementCheck(boolean placeable, PlacementDenyReason reason) {
        public static PlacementCheck allow() {
            return new PlacementCheck(true, PlacementDenyReason.NONE);
        }

        public static PlacementCheck deny(PlacementDenyReason reason) {
            return new PlacementCheck(false, reason == null ? PlacementDenyReason.INVALID_INPUT : reason);
        }

        public boolean blockedByEntity() {
            return reason == PlacementDenyReason.BLOCKED_BY_ENTITY;
        }
    }

    private static final PlacementManager INSTANCE = new PlacementManager();
    public static PlacementManager get() { return INSTANCE; }

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Deque<Long> blockInteractPackets = new ArrayDeque<>();

    /* ── Block-interact packet limiter: 9 packets per rolling 300 ms window ───────────────── */
    public void configureRate(int maxPerWindow, int windowMs) {
        // Fixed by design: 9 block-interact packets per 300 ms.
    }

    /** Remaining action tokens in the current window. */
    public int getRemainingQuota() {
        long now = System.currentTimeMillis();
        synchronized (blockInteractPackets) {
            pruneOldPacketsLocked(now);
            return Math.max(0, BLOCK_INTERACT_LIMIT - countPacketsSinceLocked(now, BLOCK_INTERACT_WINDOW_MS));
        }
    }

    public int getUsedQuota() {
        long now = System.currentTimeMillis();
        synchronized (blockInteractPackets) {
            pruneOldPacketsLocked(now);
            return countPacketsSinceLocked(now, BLOCK_INTERACT_WINDOW_MS);
        }
    }

    public int getPlacedLastSecond() {
        long now = System.currentTimeMillis();
        synchronized (blockInteractPackets) {
            pruneOldPacketsLocked(now);
            return countPacketsSinceLocked(now, HUD_SECOND_WINDOW_MS);
        }
    }

    public boolean canSendBlockInteractPacket() {
        return getRemainingQuota() > 0;
    }

    public boolean tryConsumeBlockInteractPacket() {
        long now = System.currentTimeMillis();
        synchronized (blockInteractPackets) {
            pruneOldPacketsLocked(now);
            if (countPacketsSinceLocked(now, BLOCK_INTERACT_WINDOW_MS) >= BLOCK_INTERACT_LIMIT) return false;
            blockInteractPackets.addLast(now);
            return true;
        }
    }

    /* ── Per-position cooldown (configurable; NOT cleared by block updates) ── */
    private final Map<BlockPos, Long> posCooldown = new ConcurrentHashMap<>();

    /** Intentionally does not clear cooldowns; packets must not bypass per-pos cooldown. */
    public void notifyBlockUpdate(BlockPos pos, BlockState newState) {
        // No-op by design.
    }

    /** Allow specific positions to skip the per-pos cooldown once. */
    public void clearCooldownFor(BlockPos pos) {
        if (pos != null) posCooldown.remove(pos);
    }

    /** Batch variant. */
    public void clearCooldownFor(Collection<BlockPos> poses) {
        if (poses == null) return;
        for (BlockPos p : poses) clearCooldownFor(p);
    }

    public boolean isOnCooldown(BlockPos pos) {
        if (pos == null) return true;
        Long last = posCooldown.get(pos);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < perPosCooldownMs();
    }

    public void markCooldownFor(BlockPos pos) {
        if (pos != null) posCooldown.put(pos.toImmutable(), System.currentTimeMillis());
    }

    private int perPosCooldownMs() {
        AntiCheat ac = AntiCheat.get();
        if (ac == null || ac.BlockPlaceCooldown == null) return 40;
        Integer v = ac.BlockPlaceCooldown.get();
        return Math.max(0, v != null ? v : 40);
    }


    /* ── Multi-place API (air place only; OFF_HAND) ──────────────────────── */

    /**
     * Places up to the current rolling-window quota in one OFF_HAND burst.
     * Hotbar-only: the block must be present in 0..8.
     * Internal safety:
     * - hard caps place range to PLACE_RANGE (eye-pos → block AABB, squared)
     * - skips positions that fail canPlace / entity-occupancy checks
     * - per-position cooldown backed by AntiCheat.BlockPlaceCooldown
     *
     * @return positions we actually sent OFF_HAND interacts for (server acceptance still depends on server).
     */
    public List<BlockPos> placeMany(List<BlockPos> positions, Block block) {
        if (mc.player == null || mc.world == null || positions == null || positions.isEmpty()) {
            return List.of();
        }
        if (block == null) return List.of();

        int hb = findHotbarSlot(block);
        if (hb == -1) return List.of();

        int allowed = Math.max(0, getRemainingQuota());
        if (allowed == 0) return List.of();
        if (shouldStopForEating()) return List.of();

        List<BlockPos> candidates = collectPlaceablePositions(positions, block, allowed);
        if (candidates.isEmpty()) return List.of();

        // Snapshot selection/offhand
        int originalSlot = mc.player.getInventory().getSelectedSlot();
        ItemStack offhandSnapshot = mc.player.getOffHandStack().copy();
        boolean slotChanged = originalSlot != hb;

        // Select the block slot if needed.
        if (slotChanged) {
            mc.player.getInventory().setSelectedSlot(hb);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hb));
        }

        // Swap mainhand <-> offhand once at burst start.
        sendOffhandSwap();

        List<BlockPos> placed = new ArrayList<>(Math.min(candidates.size(), allowed));

        for (BlockPos pos : candidates) {
            if (getRemainingQuota() <= 0) break;
            // For offhand airplace, match Syntaxia semantics: UP face, hit at center.
            Direction face = Direction.UP;
            BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(pos), face, pos, false);

            if (!sendSequencedInteract(Hand.OFF_HAND, bhr)) break;

            posCooldown.put(pos, System.currentTimeMillis());

            placed.add(pos);
        }

        // Swap back to restore offhand/mainhand.
        sendOffhandSwap();

        // Restore previous selected hotbar slot.
        if (slotChanged && mc.player.getInventory().getSelectedSlot() != originalSlot) {
            mc.player.getInventory().setSelectedSlot(originalSlot);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
        }

        // If the offhand snapshot wasn't restored, attempt one more swap to heal.
        if (!stacksSameItem(mc.player.getOffHandStack(), offhandSnapshot)) {
            sendOffhandSwap();
            if (slotChanged && mc.player.getInventory().getSelectedSlot() != originalSlot) {
                mc.player.getInventory().setSelectedSlot(originalSlot);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
            }
        }

        return placed;
    }

    /** Back-compat single placement; internally uses the burst. */
    public boolean tryPlace(BlockPos pos, Block block) {
        List<BlockPos> r = placeMany(Collections.singletonList(pos), block);
        return !r.isEmpty();
    }

    /* ================================ helpers =============================== */

    /** Returns the placement status for this position/block using the manager's safety checks. */
    public PlacementCheck checkPlacement(BlockPos pos, Block block) {
        if (mc.world == null || mc.player == null || pos == null || block == null) {
            return PlacementCheck.deny(PlacementDenyReason.INVALID_INPUT);
        }

        if (shouldStopForEating()) {
            return PlacementCheck.deny(PlacementDenyReason.EATING);
        }

        BlockState st = mc.world.getBlockState(pos);
        boolean isFluidBlock = st.getBlock() instanceof FluidBlock;
        if (!(st.isAir() || st.isReplaceable() || isFluidBlock)) {
            return PlacementCheck.deny(PlacementDenyReason.NOT_REPLACEABLE);
        }

        if (!inPlaceRange(pos)) {
            return PlacementCheck.deny(PlacementDenyReason.OUT_OF_RANGE);
        }

        if (isBlockedByEntity(pos)) {
            return PlacementCheck.deny(PlacementDenyReason.BLOCKED_BY_ENTITY);
        }

        if (!mc.world.canPlace(block.getDefaultState(), pos, ShapeContext.absent())) {
            return PlacementCheck.deny(PlacementDenyReason.WORLD_REJECTED);
        }

        return PlacementCheck.allow();
    }

    /**
     * Basic placeability check:
     * - world not null
     * - block at pos is air or replaceable
     * - within MAX_PLACE_RANGE from eye (squared distance to AABB)
     * - no non-spectator, alive entity occupying the block AABB
     * - world.canPlace(...) says yes
     */
    private boolean basicPlaceableCheck(BlockPos pos, Block block) {
        return checkPlacement(pos, block).placeable();
    }

    private List<BlockPos> collectPlaceablePositions(List<BlockPos> positions, Block block, int limit) {
        if (positions == null || positions.isEmpty() || block == null || limit <= 0) return List.of();

        List<BlockPos> candidates = new ArrayList<>(Math.min(positions.size(), limit));
        final int cooldownMs = perPosCooldownMs();
        long now = System.currentTimeMillis();

        for (BlockPos raw : positions) {
            if (candidates.size() >= limit) break;
            if (raw == null) continue;

            BlockPos pos = raw.toImmutable();

            Long last = posCooldown.get(pos);
            if (last != null && (now - last) < cooldownMs) continue;
            if (!basicPlaceableCheck(pos, block)) continue;

            candidates.add(pos);
        }

        return candidates;
    }

    /** Hard range check: distance^2 from player eye position to the target block's AABB. */
    private boolean inPlaceRange(BlockPos pos) {
        if (mc.player == null || pos == null) return false;
        return RangeUtil.withinPlaceRange(mc.player.getEyePos(), pos);
    }

    /**
     * Reject placing into an AABB currently occupied by collidable entities
     * (living, crystal, boats, minecarts).
     */
    public boolean isBlockedByEntity(BlockPos pos) {
        if (mc.world == null || pos == null) return true;
        Box box = new Box(pos);
        List<Entity> list = mc.world.getOtherEntities(
            null,
            box,
            this::isBlockingEntity
        );
        return !list.isEmpty();
    }

    private boolean isBlockingEntity(Entity e) {
        if (e == null) return false;
        if (e.isSpectator() || !e.isAlive() || e.isRemoved()) return false;
        return (e instanceof net.minecraft.entity.LivingEntity)
            || (e instanceof net.minecraft.entity.decoration.EndCrystalEntity)
            || (e instanceof net.minecraft.entity.vehicle.BoatEntity)
            || (e instanceof net.minecraft.entity.vehicle.AbstractMinecartEntity);
    }

    /* ── Helpers ─────────────────────────────────────────────────────────── */
    /** Returns 0..8 if found in hotbar, else -1. */
    private int findHotbarSlot(Block block) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() == block) return i;
        }
        return -1;
    }

    private void sendOffhandSwap() {
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
    }

    private boolean sendSequencedInteract(Hand hand, BlockHitResult bhr) {
        if (mc.getNetworkHandler() == null || hand == null) return false;
        if (!canSendBlockInteractPacket()) return false;
        mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(hand, bhr, 0));
        return true;
    }

    /* ── Hit face helper (kept for possible non-airplace use) ─────────────── */
    /**
     * Chooses a face pointing roughly toward the player.
     * Currently unused for OFF_HAND airplace (we hardcode UP there), but left for potential vanilla-style paths.
     */
    private Direction faceTowardPlayer(BlockPos pos) {
        if (mc.player == null) return Direction.DOWN;
        Vec3d eye = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d delta = center.subtract(eye);
        Direction dir = Direction.getFacing(delta.x, delta.y, delta.z);
        return dir == null ? Direction.DOWN : dir;
    }

    private boolean stacksSameItem(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.isEmpty() && b.isEmpty()) return true;
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.getItem() == b.getItem();
    }

    private boolean shouldStopForEating() {
        AntiCheat ac = AntiCheat.get();
        if (ac == null || ac.StopPlaceOnEat == null || !Boolean.TRUE.equals(ac.StopPlaceOnEat.get())) {
            return false;
        }
        if (mc.player == null) return false;
        if (!mc.player.isUsingItem()) return false;
        ItemStack active = mc.player.getActiveItem();
        if (active == null || active.isEmpty()) return false;
        UseAction action = active.getUseAction();
        if (action == UseAction.EAT || action == UseAction.DRINK) return true;
        return active.contains(DataComponentTypes.FOOD);
    }

    /* ── Optional vanilla fallback (unused under your “no fallback” policy) ─ */
    public boolean vanillaPlaceAgainstAnyNeighbor(BlockPos target, Block block) {
        if (shouldStopForEating()) return false;
        if (mc.world == null) return false;
        for (Direction side : Direction.values()) {
            if (vanillaPlaceAgainst(target, side, block)) return true;
        }
        return false;
    }

    private boolean vanillaPlaceAgainst(BlockPos target, Direction side, Block block) {
        if (shouldStopForEating()) return false;
        if (mc.player == null || mc.interactionManager == null || mc.world == null) return false;

        BlockPos neighbor = target.offset(side.getOpposite()); // click neighbor face that points to target
        BlockState ns = mc.world.getBlockState(neighbor);
        if (ns.getCollisionShape(mc.world, neighbor).isEmpty()) return false; // need something solid to click

        Vec3d hit = Vec3d.ofCenter(target).add(Vec3d.of(side.getVector()).multiply(0.5));
        BlockHitResult bhr = new BlockHitResult(hit, side, neighbor, false);

        var res = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        return res.isAccepted();
    }

    /**
     * Air-interact at given positions using the provided item (hotbar/offhand).
     * Respects action limit and place range; performs the same offhand-swap flow as placeMany.
     */
    public List<BlockPos> airInteractMany(List<BlockPos> positions, Item item) {
        if (positions == null || positions.isEmpty()) return List.of();
        List<BlockHitResult> hits = new ArrayList<>(positions.size());
        for (BlockPos raw : positions) {
            if (raw == null) continue;
            hits.add(new BlockHitResult(Vec3d.ofCenter(raw), Direction.UP, raw.toImmutable(), false));
        }
        return airInteractManyHits(hits, item);
    }

    public List<BlockPos> airInteractManyHits(List<BlockHitResult> hits, Item item) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return List.of();
        if (hits == null || hits.isEmpty() || item == null) return List.of();
        if (shouldStopForEating()) return List.of();

        Hand useHand = null;
        int slot = -1;

        // Prefer offhand if already holding the item
        ItemStack off = mc.player.getOffHandStack();
        ItemStack main = mc.player.getMainHandStack();
        if (off.isOf(item)) {
            useHand = Hand.OFF_HAND;
        } else if (main.isOf(item)) {
            slot = mc.player.getInventory().getSelectedSlot();
            useHand = Hand.OFF_HAND; // we'll swap to offhand
        } else {
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isOf(item)) {
                    slot = i;
                    useHand = Hand.OFF_HAND;
                    break;
                }
            }
        }

        if (useHand == null) return List.of();

        int allowed = Math.max(0, getRemainingQuota());
        if (allowed == 0) return List.of();

        // Snapshot selection/offhand similar to placeMany
        int originalSlot = mc.player.getInventory().getSelectedSlot();
        ItemStack offhandSnapshot = mc.player.getOffHandStack().copy();
        boolean slotChanged = slot != -1 && originalSlot != slot;

        if (slotChanged) {
            mc.player.getInventory().setSelectedSlot(slot);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }

        boolean swapped = false;
        if (useHand == Hand.OFF_HAND && !off.isOf(item)) {
            sendOffhandSwap();
            swapped = true;
        }

        List<BlockPos> sent = new ArrayList<>(Math.min(hits.size(), allowed));
        for (BlockHitResult hit : hits) {
            if (sent.size() >= allowed || getRemainingQuota() <= 0) break;
            if (hit == null) continue;

            BlockPos pos = hit.getBlockPos();
            if (pos == null) continue;
            pos = pos.toImmutable();
            if (!mc.world.isInBuildLimit(pos) || !inPlaceRange(pos)) continue;

            if (!sendSequencedInteract(useHand, hit)) break;
            sent.add(pos);
        }

        if (swapped) sendOffhandSwap();

        if (slotChanged && mc.player.getInventory().getSelectedSlot() != originalSlot) {
            mc.player.getInventory().setSelectedSlot(originalSlot);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
        }

        if (swapped && !stacksSameItem(mc.player.getOffHandStack(), offhandSnapshot)) {
            sendOffhandSwap();
            if (slotChanged && mc.player.getInventory().getSelectedSlot() != originalSlot) {
                mc.player.getInventory().setSelectedSlot(originalSlot);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
            }
        }

        return sent;
    }

    private void pruneOldPacketsLocked(long now) {
        long cutoff = now - HUD_SECOND_WINDOW_MS;
        while (!blockInteractPackets.isEmpty() && blockInteractPackets.peekFirst() <= cutoff) {
            blockInteractPackets.removeFirst();
        }
    }

    private int countPacketsSinceLocked(long now, int windowMs) {
        long cutoff = now - windowMs;
        int count = 0;
        for (long packetTime : blockInteractPackets) {
            if (packetTime > cutoff) count++;
        }
        return count;
    }
}
