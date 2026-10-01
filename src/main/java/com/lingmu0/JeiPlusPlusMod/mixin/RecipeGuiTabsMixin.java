package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.RecipeGuiTabScrollInputHandler;
import mezz.jei.common.input.IUserInputHandler;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.PageNavigation;
import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.RecipeGuiTabs;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds category navigation to the row of recipe-category icons. */
@Mixin(value = RecipeGuiTabs.class, remap = false)
public abstract class RecipeGuiTabsMixin {
    @Shadow
    @Final
    private IRecipeGuiLogic recipeGuiLogic;

    @Shadow
    private ImmutableRect2i area;

    @Shadow
    @Final
    private PageNavigation pageNavigation;

    @Shadow
    public abstract boolean nextPage();

    @Shadow
    public abstract boolean previousPage();

    @Inject(method = "createInputHandler", at = @At("RETURN"), cancellable = true, remap = false)
    private void jeiPlusPlus$wrapTabInput(
            CallbackInfoReturnable<IUserInputHandler> cir) {
        IUserInputHandler delegate = cir.getReturnValue();
        cir.setReturnValue(new RecipeGuiTabScrollInputHandler(
                delegate,
                this::jeiPlusPlus$handleMouseScrolled));
    }

    /**
     * Handles JEI++'s additional scrolling behavior.
     *
     * @return true when JEI++ consumed the scroll event
     */
    @Unique
    private boolean jeiPlusPlus$handleMouseScrolled(
            double mouseX,
            double mouseY,
            double scrollDelta) {
        if (scrollDelta == 0) {
            return false;
        }

        if (jeiPlusPlus$isPageNavigationBand(mouseX, mouseY)) {
            if (scrollDelta < 0) {
                nextPage();
            } else {
                previousPage();
            }
            return true;
        }

        if (area.contains(mouseX, mouseY)) {
            if (scrollDelta < 0) {
                recipeGuiLogic.nextRecipeCategory();
            } else {
                recipeGuiLogic.previousRecipeCategory();
            }
            return true;
        }

        return false;
    }

    /**
     * JEI's top page-number strip belongs to PageNavigation, not to the
     * RecipesGui page buttons. Use its actual button bounds and include the
     * unbuttoned number area between them.
     */
    @Unique
    private boolean jeiPlusPlus$isPageNavigationBand(
            double mouseX,
            double mouseY) {
        ImmutableRect2i back = pageNavigation.getBackButtonArea();
        ImmutableRect2i next = pageNavigation.getNextButtonArea();

        if (back.isEmpty() || next.isEmpty()) {
            return false;
        }

        int left = Math.min(back.getX(), next.getX()) - 2;
        int right = Math.max(
                back.getX() + back.getWidth(),
                next.getX() + next.getWidth()) + 2;

        int top = Math.min(back.getY(), next.getY()) - 2;
        int bottom = Math.max(
                back.getY() + back.getHeight(),
                next.getY() + next.getHeight()) + 2;

        return mouseX >= left
                && mouseX <= right
                && mouseY >= top
                && mouseY <= bottom;
    }
}
