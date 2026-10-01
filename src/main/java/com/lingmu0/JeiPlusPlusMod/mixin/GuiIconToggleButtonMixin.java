package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.elements.GuiIconButton;
import mezz.jei.gui.elements.GuiIconToggleButton;
import mezz.jei.gui.recipes.RecipeBookmarkButton;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hides JEI's native recipe-bookmark button from recipe layouts. */
@Mixin(value = GuiIconToggleButton.class, remap = false)
public abstract class GuiIconToggleButtonMixin {
    @Shadow
    @Final
    protected GuiIconButton button;
    @Shadow
    private ImmutableRect2i area;

    @Inject(method = "updateBounds(Lmezz/jei/common/util/ImmutableRect2i;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void jeiPlusPlus$hideRecipeBookmarkBounds(ImmutableRect2i ignored, CallbackInfo ci) {
        if (jeiPlusPlus$isRecipeBookmark()) {
            button.updateBounds(ImmutableRect2i.EMPTY);
            area = ImmutableRect2i.EMPTY;
            ci.cancel();
        }
    }

    @Inject(method = "updateBounds(Lnet/minecraft/client/renderer/Rect2i;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void jeiPlusPlus$hideRecipeBookmarkRectBounds(Rect2i ignored, CallbackInfo ci) {
        if (jeiPlusPlus$isRecipeBookmark()) {
            button.updateBounds(ImmutableRect2i.EMPTY);
            area = ImmutableRect2i.EMPTY;
            ci.cancel();
        }
    }

    @Inject(method = "isVisible", at = @At("HEAD"), cancellable = true, remap = false)
    private void jeiPlusPlus$hideRecipeBookmark(CallbackInfoReturnable<Boolean> cir) {
        if (jeiPlusPlus$isRecipeBookmark()) {
            cir.setReturnValue(false);
        }
    }

    private boolean jeiPlusPlus$isRecipeBookmark() {
        return JeiPlusPlusConfig.HIDE_RECIPE_BOOKMARK_BUTTON.get()
                && (Object) this instanceof RecipeBookmarkButton;
    }
}
