package sleepy.addon.mixin;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sleepy.addon.util.CursorSafeChestSwap;

@Mixin(value = ChestSwap.class, remap = false)
public abstract class MixinChestSwap {
    @Shadow @Final private Setting<ChestSwap.Chestplate> chestplate;
    @Shadow @Final private Setting<Boolean> closeInventory;

    @Inject(method = "swap", at = @At("HEAD"), cancellable = true, remap = false)
    private void sleepy$cursorSafeSwap(CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (CursorSafeChestSwap.swap(chestplate.get()) && closeInventory.get()
            && mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(0));
        }

        // Never fall through to Meteor's InvUtils.move(), which uses PICKUP clicks.
        ci.cancel();
    }
}
