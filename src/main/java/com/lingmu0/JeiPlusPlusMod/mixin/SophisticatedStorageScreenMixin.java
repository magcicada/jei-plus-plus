package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeFavorites;
import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeTransfer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase", remap = false)
public abstract class SophisticatedStorageScreenMixin {
    @Inject(method = {"render", "m_88315_"},
        at = @At("HEAD"), remap = false)
    private void jeiPlusPlus$tickCrafting(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        RecipeTreeFavorites.refreshThrottled();
        RecipeTreeTransfer.tick((Screen) (Object) this);
    }

    @Inject(method = {"renderSlot", "m_280092_"},
        at = @At("TAIL"), remap = false)
    private void jeiPlusPlus$highlightSlot(GuiGraphics graphics, Slot slot, CallbackInfo ci) {
        RecipeTreeFavorites.renderSlotHighlight(graphics, slot);
    }
}
