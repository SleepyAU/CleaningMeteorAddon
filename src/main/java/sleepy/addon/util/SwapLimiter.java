package sleepy.addon.util;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;

public final class SwapLimiter {
    private static final int MAX_PER_WINDOW = 76;
    private static final long WINDOW_MS = 4000L;
    private static long windowStartMs = 0L;
    private static int sentInWindow = 0;

    private SwapLimiter() {
    }

    public static boolean canSend(int needed) {
        if (needed <= 0) return true;
        synchronized (SwapLimiter.class) {
            rotateWindowIfNeeded();
            if (windowStartMs == 0L) return needed <= MAX_PER_WINDOW;
            return sentInWindow + needed <= MAX_PER_WINDOW;
        }
    }

    public static int remaining() {
        synchronized (SwapLimiter.class) {
            rotateWindowIfNeeded();
            if (windowStartMs == 0L) return MAX_PER_WINDOW;
            return Math.max(0, MAX_PER_WINDOW - sentInWindow);
        }
    }

    public static int used() {
        synchronized (SwapLimiter.class) {
            rotateWindowIfNeeded();
            if (windowStartMs == 0L) return 0;
            return sentInWindow;
        }
    }

    @EventHandler
    private static void onPacketSend(PacketEvent.Send event) {
        if (!(event.packet instanceof ClickSlotC2SPacket)) return;
        synchronized (SwapLimiter.class) {
            rotateWindowIfNeeded();
            if (windowStartMs == 0L) {
                windowStartMs = System.currentTimeMillis();
                sentInWindow = 0;
            }
            if (sentInWindow >= MAX_PER_WINDOW) {
                event.cancel();
                return;
            }
            sentInWindow++;
        }
    }

    private static void rotateWindowIfNeeded() {
        long now = System.currentTimeMillis();
        if (windowStartMs == 0L) return;
        if (now - windowStartMs >= WINDOW_MS) {
            windowStartMs = 0L;
            sentInWindow = 0;
        }
    }
}
