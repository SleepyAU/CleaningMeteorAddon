package sleepy.addon.mixin;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sleepy.addon.events.UpdateEvent;

@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntity {
    @Inject(method = "tick", at = @At("HEAD"))
    private void sleepy$preTick(CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(new UpdateEvent());
    }
}
