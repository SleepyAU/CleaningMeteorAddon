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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central placement manager (blocks and item air-interacts).
 * One-tick burst: select hotbar slot once → OFF_HAND swap → N air-interacts (fresh seq each) → swap back → restore slot.
 * Rate-limited by the shared action limiter (default 9 per 300 ms).
 * Hotbar-only (no container swaps).
 */
public final class PlacementManager {
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
    private final int[] placedPerTick = new int[20];
    private long lastCountTick = -1L;
    private long lastPlaceTick = -1L;

    /* ── Placement rate proxy (hard limited to 1 placement per tick) ───────────────────────── */
    public void configureRate(int maxPerWindow, int windowMs) {
        // No-op: hard limited to 1 placement per client tick.
    }

    /** Remaining action tokens in the current window. */
    public int getRemainingQuota() {
        if (mc.player == null) return 0;
        return mc.player.age == lastPlaceTick ? 0 : 1;
    }

    public int getPlacedLastSecond() {
        if (mc.player == null) return 0;
        syncPlacementWindow(mc.player.age);
        int sum = 0;
        for (int v : placedPerTick) sum += v;
        return sum;
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

    private int perPosCooldownMs() {
        AntiCheat ac = AntiCheat.get();
        if (ac == null || ac.BlockPlaceCooldown == null) return 40;
        Integer v = ac.BlockPlaceCooldown.get();
        return Math.max(0, v != null ? v : 40);
    }


    /* ── One-tick multi-place API (air place only; OFF_HAND) ──────────────── */

    /**
     * Places up to the current quota positions this tick in ONE OFF_HAND burst.
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

        long tick = mc.player.age;
        if (tick == lastPlaceTick) return List.of();

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

        int placedCount = 0;
        List<BlockPos> placed = new ArrayList<>(Math.min(positions.size(), allowed));
        final int cooldownMs = perPosCooldownMs();

        for (BlockPos raw : positions) {
            if (placedCount >= allowed) break;
            if (raw == null) continue;

            BlockPos pos = raw.toImmutable();

            // Global place-range cap (distance^2 from eye to block AABB)
            if (!inPlaceRange(pos)) continue;

            long now = System.currentTimeMillis();

            // Per-position cooldown gate
            Long last = posCooldown.get(pos);
            if (last != null && (now - last) < cooldownMs) continue;

            // Check world state, replaceable, entity occupancy & canPlace
            if (!basicPlaceableCheck(pos, block)) continue;

            // For offhand airplace, match Syntaxia semantics: UP face, hit at center.
            Direction face = Direction.UP;
            BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(pos), face, pos, false);

            sendSequencedInteract(Hand.OFF_HAND, bhr);

            posCooldown.put(pos, now);

            placed.add(pos);
            placedCount++;
            lastPlaceTick = tick;
            recordPlacementTick(tick);
            break;
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

    private void sendSequencedInteract(Hand hand, BlockHitResult bhr) {
        if (mc.getNetworkHandler() == null || hand == null) return;
        mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(hand, bhr, 0));
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
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return List.of();
        if (positions == null || positions.isEmpty() || item == null) return List.of();
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

        List<BlockPos> sent = new ArrayList<>(Math.min(positions.size(), allowed));
        for (BlockPos raw : positions) {
            if (sent.size() >= allowed) break;
            if (raw == null) continue;

            BlockPos pos = raw.toImmutable();
            if (!inPlaceRange(pos)) continue;

            BlockHitResult bhr = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            sendSequencedInteract(useHand, bhr);
            sent.add(pos);
            lastPlaceTick = mc.player.age;
            break;
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

    private void recordPlacementTick(long tick) {
        syncPlacementWindow(tick);
        placedPerTick[(int) (tick % placedPerTick.length)]++;
    }

    private void syncPlacementWindow(long tick) {
        if (lastCountTick == -1L) {
            Arrays.fill(placedPerTick, 0);
            lastCountTick = tick;
            return;
        }
        if (tick <= lastCountTick) return;
        long diff = tick - lastCountTick;
        if (diff >= placedPerTick.length) {
            Arrays.fill(placedPerTick, 0);
        } else {
            for (long i = 1; i <= diff; i++) {
                placedPerTick[(int) ((lastCountTick + i) % placedPerTick.length)] = 0;
            }
        }
        lastCountTick = tick;
    }
}
