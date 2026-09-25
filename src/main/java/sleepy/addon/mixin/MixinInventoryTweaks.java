package sleepy.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sleepy.addon.util.CursorSafeChestSwap;

@Mixin(value = InventoryTweaks.class, remap = false)
public abstract class MixinInventoryTweaks {
    @Inject(method = "armorStorage", at = @At("HEAD"), cancellable = true, remap = false)
    private void sleepy$allowRealArmorSwap(CallbackInfoReturnable<Boolean> cir) {
        if (CursorSafeChestSwap.isBypassingMeteorArmorStorage()) cir.setReturnValue(false);
    }
}
