package sleepy.addon.util;

public final class PlacerQuotaCoordinator {
    public enum Kind {
        GlowBerry,
        Moss
    }

    private static long currentTickId = Long.MIN_VALUE;
    private static int tickStartQuota;
    private static boolean glowHasTargets;
    private static boolean mossHasTargets;

    private PlacerQuotaCoordinator() {}

    public static int getBudget(long tickId, int remainingQuota, Kind kind, boolean otherEnabled, boolean hasTargets) {
        ensureTick(tickId, remainingQuota);

        return switch (kind) {
            case GlowBerry -> {
                glowHasTargets = hasTargets;
                if (!otherEnabled || !hasTargets) yield remainingQuota;
                yield (tickStartQuota + 1) / 2;
            }
            case Moss -> {
                mossHasTargets = hasTargets;
                if (!otherEnabled || !hasTargets || !glowHasTargets) yield remainingQuota;
                yield tickStartQuota / 2;
            }
        };
    }

    private static void ensureTick(long tickId, int remainingQuota) {
        if (tickId == currentTickId) return;

        currentTickId = tickId;
        tickStartQuota = Math.max(0, remainingQuota);
        glowHasTargets = false;
        mossHasTargets = false;
    }
}
