package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.CreativeTabGridCompat;
import mezz.jei.gui.overlay.IngredientListOverlay;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draw the picker after recipes, containers, overlays and native interactive tooltip layers. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.events.GuiEventHandler", remap = false)
public abstract class JeiModalRenderMixin {
    @Shadow
    @Final
    private IngredientListOverlay ingredientListOverlay;

    @Inject(method = "onDrawScreenPost", at = @At("TAIL"), remap = false)
    private void jeiPlusPlus$modal(Screen screen, GuiGraphics graphics, int x, int y, CallbackInfo ci) {
        CreativeTabGridCompat.drawModal(ingredientListOverlay, graphics, x, y);
    }
}
