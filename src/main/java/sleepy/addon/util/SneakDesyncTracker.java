package sleepy.addon.util;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;

/** Tracks the local player's server pose flags independently of any normal module. */
public final class SneakDesyncTracker {
    private static final int ENTITY_FLAGS_TRACKER_INDEX = 0;
    private static final byte SNEAKING_FLAG_MASK = 0x02;
    private static final int GLIDING_FLAG_MASK = 0x80;
    private static final MinecraftClient MC = MinecraftClient.getInstance();

    private static volatile boolean serverSneaking;
    private static volatile boolean serverGliding;
    private static volatile boolean hasServerSneakState;
    private static volatile boolean hasServerGlideState;
    private static volatile boolean clientSentSneaking;

    private SneakDesyncTracker() {
    }

    public static boolean isDesynced() {
        if (MC.player == null) return false;
        boolean localSneakIntent = MC.options.sneakKey.isPressed() || clientSentSneaking;
        boolean appliedSneakState = MC.player.isSneaking();
        boolean serverReportedSneak = hasServerSneakState && serverSneaking;
        return (serverReportedSneak || appliedSneakState) && !localSneakIntent;
    }

    public static boolean hasLocalSneakIntent() {
        return MC.player != null && (MC.options.sneakKey.isPressed() || clientSentSneaking);
    }

    public static boolean isServerGliding() {
        return MC.player != null && hasServerGlideState && serverGliding;
    }

    @EventHandler
    private static void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntityTrackerUpdateS2CPacket packet)) return;
        MC.execute(() -> updateSelfSneakState(packet));
    }

    @EventHandler
    private static void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof PlayerInputC2SPacket packet) {
            clientSentSneaking = packet.input().sneak();
        }
    }

    @EventHandler
    private static void onGameJoined(GameJoinedEvent event) {
        reset();
    }

    @EventHandler
    private static void onGameLeft(GameLeftEvent event) {
        reset();
    }

    private static void updateSelfSneakState(EntityTrackerUpdateS2CPacket packet) {
        if (MC.player == null || packet.id() != MC.player.getId()) return;
        for (DataTracker.SerializedEntry<?> entry : packet.trackedValues()) {
            if (entry.id() != ENTITY_FLAGS_TRACKER_INDEX) continue;
            if (entry.value() instanceof Byte flags) {
                serverSneaking = (flags & SNEAKING_FLAG_MASK) != 0;
                serverGliding = (flags & GLIDING_FLAG_MASK) != 0;
                hasServerSneakState = true;
                hasServerGlideState = true;
            }
            return;
        }
    }

    private static void reset() {
        serverSneaking = false;
        serverGliding = false;
        hasServerSneakState = false;
        hasServerGlideState = false;
        clientSentSneaking = false;
    }
}
