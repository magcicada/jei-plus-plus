package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.CreativeTabGridCompat;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** Adds a directory action to cycling/multi-ingredient recipe slots. */
@Mixin(value = RecipeGuiLayouts.class, remap = false)
public abstract class RecipeGuiLayoutsMixin {
    @Inject(method = "drawTooltips", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void jeiPlusPlus$hideRecipeTooltipsWhileSelectorOpen(
        GuiGraphics graphics,
        int mouseX,
        int mouseY,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci
    ) {
        if (CreativeTabGridCompat.isAnySelectorOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "getWidth", at = @At("RETURN"), cancellable = true, remap = false)
    private void jeiPlusPlus$reserveRecipeTreeWidth(CallbackInfoReturnable<Integer> cir) {
        int extra = com.lingmu0.JeiPlusPlusMod.client.RecipeTreeOverlay.extraWidth(
            (RecipeGuiLayouts) (Object) this,
            cir.getReturnValue()
        );
        if (extra > 0) {
            cir.setReturnValue(cir.getReturnValue() + extra);
        }
    }

    @Inject(method = "draw", at = @At("TAIL"), remap = false)
    private void jeiPlusPlus$drawRecipeTreeButton(
        GuiGraphics guiGraphics,
        int mouseX,
        int mouseY,
        CallbackInfoReturnable<Optional<IRecipeLayoutDrawable<?>>> cir
    ) {
        com.lingmu0.JeiPlusPlusMod.client.RecipeTreeOverlay.draw((RecipeGuiLayouts) (Object) this, guiGraphics, mouseX, mouseY);
    }
}
