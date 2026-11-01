package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.JeiInputAreaCompat;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces the removed RecipesGui.mouseScrolled hook before native smooth scrolling. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.recipes.RecipesGui$RecipeCategoryScrollInputHandler", remap = false)
public abstract class JeiRecipeNavigationMixin {
    @Shadow @Final private RecipesGui recipesGui;

    @Inject(method = "scroll", at = @At("HEAD"), cancellable = true, remap = false)
    private void jeiPlusPlus$navigation(double x, double y, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (recipesGui.isOpen() && JeiInputAreaCompat.scrollRecipeNavigation(recipesGui, x, y, dy)) {
            cir.setReturnValue(true);
        }
    }
}
