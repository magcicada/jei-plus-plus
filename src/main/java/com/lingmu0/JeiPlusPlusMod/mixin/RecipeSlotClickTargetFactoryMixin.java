package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import com.lingmu0.JeiPlusPlusMod.client.DirectoryIngredientElement;
import com.lingmu0.JeiPlusPlusMod.client.JeiReflectionCompat;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.gui.input.IClickableIngredientInternal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/** Ingredient directory click targets for JEI 15.74. */
@Mixin(targets = "mezz.jei.gui.recipes.RecipeSlotClickTargetFactory", remap = false)
public abstract class RecipeSlotClickTargetFactoryMixin {
    @Inject(method = "create(Lmezz/jei/api/gui/inputs/RecipeSlotUnderMouse;Lmezz/jei/common/input/IMouseOverable;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void jeiPlusPlus$directoryClick(
            RecipeSlotUnderMouse slotUnderMouse,
            @Coerce Object mouseOverable,
            CallbackInfoReturnable<Optional<IClickableIngredientInternal<?>>> cir) {
        if (!JeiPlusPlusConfig.RECIPE_INGREDIENT_DIRECTORY_ENABLED.get()) {
            return;
        }
        List<ITypedIngredient<?>> ingredients = slotUnderMouse.slot().getAllIngredients().toList();
        if (ingredients.size() <= 1) {
            return;
        }

        slotUnderMouse.slot().getDisplayedIngredient().ifPresent(displayed -> {
            cir.setReturnValue(Optional.of(JeiReflectionCompat.clickableIngredient(
                    new DirectoryIngredientElement(displayed, ingredients),
                    JeiReflectionCompat.mouseOverPredicate(mouseOverable),
                    false,
                    true)));
        });
    }
}
