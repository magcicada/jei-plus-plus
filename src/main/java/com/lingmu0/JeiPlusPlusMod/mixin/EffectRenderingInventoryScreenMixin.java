package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.CreativeTabGridCompat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Do not draw inventory potion effects over the category selector. */
@Mixin(EffectRenderingInventoryScreen.class)
public abstract class EffectRenderingInventoryScreenMixin {
    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void jeiPlusPlus$hideEffectsWhileSelectorOpen(
            GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (CreativeTabGridCompat.isAnySelectorOpen())
            ci.cancel();
    }
}
