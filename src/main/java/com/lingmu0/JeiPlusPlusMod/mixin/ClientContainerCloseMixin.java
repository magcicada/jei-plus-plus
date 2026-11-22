package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeTransfer;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class ClientContainerCloseMixin {
    @Inject(method = "closeContainer", at = @At("HEAD"))
    private void jeiPlusPlus$restoreCraftingUpgradeSettings(CallbackInfo ci) {
        RecipeTreeTransfer.beforeContainerClose(((LocalPlayer) (Object) this).containerMenu);
    }
}
