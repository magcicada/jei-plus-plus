package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IRecipesGui;
import mezz.jei.gui.overlay.elements.IngredientElement;
import mezz.jei.gui.util.FocusUtil;

import java.util.List;

/** JEI's clickable element with a directory action for recipe lookups. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class DirectoryIngredientElement extends IngredientElement<Object> {
    private final List<ITypedIngredient<?>> ingredients;

    public DirectoryIngredientElement(ITypedIngredient<?> displayedIngredient, List<ITypedIngredient<?>> ingredients) {
        super((ITypedIngredient<Object>) (ITypedIngredient) displayedIngredient);
        this.ingredients = List.copyOf(ingredients);
    }

    @Override
    public void show(IRecipesGui recipesGui, FocusUtil focusUtil, List<RecipeIngredientRole> roles) {
        if (JeiPlusPlusConfig.RECIPE_INGREDIENT_DIRECTORY_ENABLED.get()
                && roles.contains(RecipeIngredientRole.OUTPUT)) {
            DirectoryViewer.show(recipesGui, ingredients);
        } else {
            super.show(recipesGui, focusUtil, roles);
        }
    }
}
