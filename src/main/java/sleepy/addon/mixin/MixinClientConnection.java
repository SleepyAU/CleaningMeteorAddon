package sleepy.addon.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sleepy.addon.manager.PlacementManager;

@Mixin(ClientConnection.class)
public abstract class MixinClientConnection {
    @Inject(method = "sendInternal", at = @At("HEAD"), cancellable = true)
    private void sleepy$limitBlockInteractPackets(Packet<?> packet, PacketCallbacks callbacks, boolean flush, CallbackInfo ci) {
        if (packet instanceof PlayerInteractBlockC2SPacket
            && !PlacementManager.get().tryConsumeBlockInteractPacket()) {
            ci.cancel();
        }
    }
}
