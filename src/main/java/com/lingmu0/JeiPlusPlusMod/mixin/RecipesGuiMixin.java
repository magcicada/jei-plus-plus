package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeOverlay;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.elements.GuiIconButton;
import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds JEI++ recipe-tree interaction and independent mouse-wheel
 * navigation to JEI's recipe GUI.
 */
@Mixin(value = RecipesGui.class, remap = false)
public abstract class RecipesGuiMixin {
    @Shadow
    @Final
    private IRecipeGuiLogic logic;

    @Shadow
    @Final
    private GuiIconButton nextRecipeCategory;

    @Shadow
    @Final
    private GuiIconButton previousRecipeCategory;

    @Inject(
            method = "mouseClicked",
            at = @At("HEAD"),
            cancellable = true,
            remap = true
    )
    private void jeiPlusPlus$clickRecipeTreeButton(
            double mouseX,
            double mouseY,
            int button,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (RecipeTreeOverlay.click(
                ((RecipesGuiAccessor) this).jeiPlusPlus$getLayouts(),
                mouseX,
                mouseY,
                button
        )) {
            cir.setReturnValue(true);
        }
    }

    @Inject(
            method = "mouseScrolled",
            at = @At("HEAD"),
            cancellable = true,
            remap = true
    )
    private void jeiPlusPlus$scrollNavigation(
            double mouseX,
            double mouseY,
            double scrollDelta,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (
                scrollDelta == 0 ||
                        !insideBand(
                                mouseX,
                                mouseY,
                                previousRecipeCategory,
                                nextRecipeCategory
                        )
        ) {
            return;
        }

        if (scrollDelta < 0) {
            logic.nextRecipeCategory();
        } else {
            logic.previousRecipeCategory();
        }

        cir.setReturnValue(true);
    }

    @Unique
    private static boolean insideBand(
            double mouseX,
            double mouseY,
            GuiIconButton left,
            GuiIconButton right
    ) {
        ImmutableRect2i leftArea = left.getArea();
        ImmutableRect2i rightArea = right.getArea();

        int leftEdge = leftArea.getX();
        int rightEdge = rightArea.getX() + rightArea.getWidth();
        int top = leftArea.getY() - 2;
        int bottom = leftArea.getY() + leftArea.getHeight() + 2;

        return mouseX >= leftEdge &&
                mouseX <= rightEdge &&
                mouseY >= top &&
                mouseY <= bottom;
    }
}
