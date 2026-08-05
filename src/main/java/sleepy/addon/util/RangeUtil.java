package sleepy.addon.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import sleepy.addon.features.AntiCheat;

/**
 * Centralized reach/range helpers (ported from Syntaxia).
 * Distances are computed as squared distance from the eye to the closest point on the target box.
 */
public final class RangeUtil {
    private RangeUtil() {}

    public static final double MINE_RANGE      = 5.5;
    public static final double PLACE_RANGE     = 4.5;
    public static final double ENTITY_RANGE    = 3.0;
    public static final double ATTACK_RANGE    = 3.0;

    public static final double MINE_RANGE_SQ   = MINE_RANGE * MINE_RANGE;
    public static final double PLACE_RANGE_SQ  = PLACE_RANGE * PLACE_RANGE;
    public static final double ENTITY_RANGE_SQ = ENTITY_RANGE * ENTITY_RANGE;
    public static final double ATTACK_RANGE_SQ = ATTACK_RANGE * ATTACK_RANGE;

    /** Squared distance from a point to the closest point on a box (0 if inside). */
    public static double distanceSqToBox(Vec3d point, Box box) {
        if (point == null || box == null) return Double.POSITIVE_INFINITY;
        double dx = 0.0;
        if (point.x < box.minX) dx = box.minX - point.x;
        else if (point.x > box.maxX) dx = point.x - box.maxX;

        double dy = 0.0;
        if (point.y < box.minY) dy = box.minY - point.y;
        else if (point.y > box.maxY) dy = point.y - box.maxY;

        double dz = 0.0;
        if (point.z < box.minZ) dz = box.minZ - point.z;
        else if (point.z > box.maxZ) dz = point.z - box.maxZ;

        return dx * dx + dy * dy + dz * dz;
    }

    public static boolean withinRange(Vec3d eye, Box box, double maxRangeSq) {
        if (maxRangeSq < 0) return false;
        return distanceSqToBox(eye, box) <= maxRangeSq;
    }

    public static boolean withinMineRange(Vec3d eye, BlockPos pos) {
        return withinRange(eye, new Box(pos), MINE_RANGE_SQ);
    }

    public static boolean withinPlaceRange(Vec3d eye, BlockPos pos) {
        return withinRange(eye, new Box(pos), PLACE_RANGE_SQ);
    }

    public static boolean withinEntityRange(Vec3d eye, Entity entity) {
        if (entity == null) return false;
        return withinRange(eye, entity.getBoundingBox(), ENTITY_RANGE_SQ);
    }

    public static boolean withinAttackRange(Vec3d eye, Entity entity) {
        if (entity == null) return false;
        return withinRange(eye, entity.getBoundingBox(), ATTACK_RANGE_SQ);
    }

    public static boolean isGrimDirectionEnabled() {
        AntiCheat antiCheat = AntiCheat.get();
        return antiCheat != null
            && antiCheat.GrimDirection != null
            && Boolean.TRUE.equals(antiCheat.GrimDirection.get());
    }

    public static Direction bestMineDirectionToBlock(Vec3d eye, BlockPos pos) {
        return bestHitToBlock(eye, pos).direction();
    }

    public static BlockHitTarget bestHitToBlock(Vec3d eye, BlockPos pos) {
        if (eye == null || pos == null) {
            return new BlockHitTarget(Direction.UP, Vec3d.ZERO, Double.POSITIVE_INFINITY);
        }

        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = minX + 1.0D;
        double maxY = minY + 1.0D;
        double maxZ = minZ + 1.0D;

        return closestHitToBox(eye.x, eye.y, eye.z, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static BlockHitTarget closestHitToBox(double x,
                                                  double y,
                                                  double z,
                                                  double minX,
                                                  double minY,
                                                  double minZ,
                                                  double maxX,
                                                  double maxY,
                                                  double maxZ) {
        BlockHitTarget best = null;
        for (Direction direction : Direction.values()) {
            BlockHitTarget candidate = hitOnFace(x, y, z, minX, minY, minZ, maxX, maxY, maxZ, direction);
            if (best == null || candidate.distanceSq() < best.distanceSq()) {
                best = candidate;
            }
        }
        return best == null ? new BlockHitTarget(Direction.UP, Vec3d.ZERO, Double.POSITIVE_INFINITY) : best;
    }

    private static BlockHitTarget hitOnFace(double x,
                                            double y,
                                            double z,
                                            double minX,
                                            double minY,
                                            double minZ,
                                            double maxX,
                                            double maxY,
                                            double maxZ,
                                            Direction side) {
        Direction resolvedSide = side == null ? Direction.UP : side;
        double inset = 0.001D;
        double clampInset = 0.05D;
        double hitX;
        double hitY;
        double hitZ;

        switch (resolvedSide) {
            case DOWN -> {
                hitX = clamp(x, minX + clampInset, maxX - clampInset);
                hitY = minY + inset;
                hitZ = clamp(z, minZ + clampInset, maxZ - clampInset);
            }
            case UP -> {
                hitX = clamp(x, minX + clampInset, maxX - clampInset);
                hitY = maxY - inset;
                hitZ = clamp(z, minZ + clampInset, maxZ - clampInset);
            }
            case NORTH -> {
                hitX = clamp(x, minX + clampInset, maxX - clampInset);
                hitY = clamp(y, minY + clampInset, maxY - clampInset);
                hitZ = minZ + inset;
            }
            case SOUTH -> {
                hitX = clamp(x, minX + clampInset, maxX - clampInset);
                hitY = clamp(y, minY + clampInset, maxY - clampInset);
                hitZ = maxZ - inset;
            }
            case WEST -> {
                hitX = minX + inset;
                hitY = clamp(y, minY + clampInset, maxY - clampInset);
                hitZ = clamp(z, minZ + clampInset, maxZ - clampInset);
            }
            case EAST -> {
                hitX = maxX - inset;
                hitY = clamp(y, minY + clampInset, maxY - clampInset);
                hitZ = clamp(z, minZ + clampInset, maxZ - clampInset);
            }
            default -> {
                hitX = clamp(x, minX + clampInset, maxX - clampInset);
                hitY = maxY - inset;
                hitZ = clamp(z, minZ + clampInset, maxZ - clampInset);
            }
        }

        double dx = x - hitX;
        double dy = y - hitY;
        double dz = z - hitZ;
        return new BlockHitTarget(resolvedSide, new Vec3d(hitX, hitY, hitZ), dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    public record BlockHitTarget(Direction direction, Vec3d hitPos, double distanceSq) {
    }
}
