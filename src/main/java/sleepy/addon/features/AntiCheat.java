package sleepy.addon.features;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import sleepy.addon.SleepyAddon;
import sleepy.addon.manager.PlacementManager;

public final class AntiCheat extends Module {
    private static AntiCheat INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> BlockPlaceCooldown = sgGeneral.add(new IntSetting.Builder()
        .name("block-place-cooldown")
        .description("Cooldown before retrying the same BlockPos.")
        .defaultValue(400)
        .min(10)
        .sliderMax(500)
        .build()
    );

    public final Setting<Boolean> StopPlaceOnEat = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-place-on-eat")
        .description("Block placements while eating to avoid cancelling food use.")
        .defaultValue(true)
        .build()
    );

    public AntiCheat() {
        super(SleepyAddon.CATEGORY, "anti-cheat", "Global anti-cheat config.");
        INSTANCE = this;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc == null || mc.world == null) return;

        if (event.packet instanceof BlockUpdateS2CPacket bu) {
            PlacementManager.get().notifyBlockUpdate(bu.getPos(), bu.getState());
            return;
        }

        if (event.packet instanceof ChunkDeltaUpdateS2CPacket cd) {
            cd.visitUpdates((pos, state) -> PlacementManager.get().notifyBlockUpdate(pos, state));
        }
    }

    public static AntiCheat get() { return INSTANCE; }
}
