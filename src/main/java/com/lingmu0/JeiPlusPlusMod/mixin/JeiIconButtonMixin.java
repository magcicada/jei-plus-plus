package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import com.lingmu0.JeiPlusPlusMod.client.JeiReflectionCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** JEI 15's replacement for GuiIconToggleButton. Absent on older builds. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.elements.IconButton", remap = false)
public abstract class JeiIconButtonMixin {
    @Inject(method = "isVisible", at = @At("HEAD"), cancellable = true, remap = false)
    private void jeiPlusPlus$hideRecipeBookmark(CallbackInfoReturnable<Boolean> cir) {
        Object controller = JeiReflectionCompat.readField(this, "controller");
        if (JeiPlusPlusConfig.HIDE_RECIPE_BOOKMARK_BUTTON.get() && controller != null
            && controller.getClass().getName().equals("mezz.jei.gui.recipes.RecipeBookmarkButtonController")) {
            cir.setReturnValue(false);
        }
    }
}
