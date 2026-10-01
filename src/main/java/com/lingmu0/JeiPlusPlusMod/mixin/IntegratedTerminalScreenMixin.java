package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeFavorites;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps recipe-tree highlights below Integrated Terminals' foreground tooltips. */
@Pseudo
@Mixin(targets = "org.cyclops.integratedterminals.client.gui.container.ContainerScreenTerminalStorage", remap = false)
public abstract class IntegratedTerminalScreenMixin {
    @Inject(method = {"renderLabels", "m_280003_"}, at = @At("HEAD"), remap = false, require = 0)
    private void jeiPlusPlus$renderHighlightsBeforeTooltip(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            CallbackInfo ci) {
        RecipeTreeFavorites.renderIntegratedTerminalHighlightsBeforeTooltip(
                graphics,
                (AbstractContainerScreen<?>) (Object) this);
    }
}
