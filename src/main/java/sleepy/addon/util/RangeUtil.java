package sleepy.addon.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Centralized reach/range helpers (ported from Syntaxia).
 * Distances are computed as squared distance from the eye to the closest point on the target box.
 */
public final class RangeUtil {
    private RangeUtil() {}

    public static final double MINE_RANGE      = 5.5;
    public static final double PLACE_RANGE     = 4.5;
    public static final double ATTACK_RANGE    = 3.0;

    public static final double MINE_RANGE_SQ   = MINE_RANGE * MINE_RANGE;
    public static final double PLACE_RANGE_SQ  = PLACE_RANGE * PLACE_RANGE;
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

    public static boolean withinAttackRange(Vec3d eye, Entity entity) {
        if (entity == null) return false;
        return withinRange(eye, entity.getBoundingBox(), ATTACK_RANGE_SQ);
    }
}
