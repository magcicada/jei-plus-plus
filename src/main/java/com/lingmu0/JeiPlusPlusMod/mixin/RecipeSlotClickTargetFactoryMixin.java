package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import com.lingmu0.JeiPlusPlusMod.client.DirectoryIngredientElement;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.gui.input.ClickableIngredientInternal;
import mezz.jei.gui.input.IClickableIngredientInternal;
import mezz.jei.common.input.IMouseOverable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/**
 * JEI 15.56+ and 19.x moved recipe-slot click target creation out of
 * {@code RecipeGuiLayouts}. Keep the directory action on that new path while
 * retaining the older mixin for JEI versions that still have
 * {@code getClickedIngredient}.
 */
@Pseudo
@Mixin(targets = "mezz.jei.gui.recipes.RecipeSlotClickTargetFactory", remap = false)
public abstract class RecipeSlotClickTargetFactoryMixin {
    @Inject(
            method = "create(Lmezz/jei/api/gui/inputs/RecipeSlotUnderMouse;Lmezz/jei/common/input/IMouseOverable;)Ljava/util/Optional;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void jeiPlusPlus$directoryClick(
        RecipeSlotUnderMouse slotUnderMouse,
        IMouseOverable mouseOverable,
        CallbackInfoReturnable<Optional<IClickableIngredientInternal<?>>> cir
    ) {
        if (!JeiPlusPlusConfig.RECIPE_INGREDIENT_DIRECTORY_ENABLED.get()) {
            return;
        }
        List<ITypedIngredient<?>> ingredients = slotUnderMouse.slot().getAllIngredients().toList();
        if (ingredients.size() <= 1) {
            return;
        }

        slotUnderMouse.slot().getDisplayedIngredient().ifPresent(displayed -> {
            cir.setReturnValue(Optional.of(new ClickableIngredientInternal<>(
                new DirectoryIngredientElement(displayed, ingredients),
                mouseOverable,
                false,
                true
            )));
        });
    }
}
