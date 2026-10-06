package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.Ae2PatternClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The ordinary encode click is left to AE2; only modifier clicks are intercepted. */
@Pseudo
@Mixin(targets = "appeng.menu.me.items.PatternEncodingTermMenu", remap = false)
public abstract class Ae2PatternEncodeMixin {
    @Inject(method = "encode", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void jeiPlusPlus$encodeTree(CallbackInfo ci) {
        if (Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.containerMenu == (AbstractContainerMenu) (Object) this
                && Minecraft.getInstance().screen != null
                && Minecraft.getInstance().screen.getClass().getName().equals(
                    "appeng.client.gui.me.items.PatternEncodingTermScreen")
                && Ae2PatternClient.onEncode()) ci.cancel();
    }
}
