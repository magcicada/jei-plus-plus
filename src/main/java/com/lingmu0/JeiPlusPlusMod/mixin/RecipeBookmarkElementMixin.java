package com.lingmu0.JeiPlusPlusMod.mixin;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IRecipesGui;
import mezz.jei.gui.overlay.elements.RecipeBookmarkElement;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.gui.util.FocusUtil;
import com.lingmu0.JeiPlusPlusMod.client.RecipeBookmarkNavigationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

/**
 * A recipe bookmark opens the complete cross-category lookup for its output or
 * input. JEI's bookmark sorter keeps the bookmarked recipe at the front while
 * retaining every matching recipe from every work block.
 */
@Mixin(value = RecipeBookmarkElement.class, remap = false)
public abstract class RecipeBookmarkElementMixin {
    @Inject(method = "show", at = @At("HEAD"), cancellable = true, remap = false)
    private void jeiPlusPlus$showAllRecipes(
        IRecipesGui recipesGui,
        FocusUtil focusUtil,
        List<RecipeIngredientRole> roles,
        CallbackInfo ci
    ) {
        RecipeBookmarkElement<?, ?> element = (RecipeBookmarkElement<?, ?>) (Object) this;
        ITypedIngredient<?> ingredient = element.getTypedIngredient();
        Optional<IBookmark> elementBookmark = element.getBookmark();
        List<RecipeIngredientRole> lookupRoles = jeiPlusPlus$bookmarkRoles(elementBookmark, roles);
        List<IFocus<?>> focuses = focusUtil.createFocuses(ingredient, lookupRoles);
        showAllMatchingRecipes(recipesGui, focuses, element);
        ci.cancel();
    }

    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void showAllMatchingRecipes(
        IRecipesGui recipesGui,
        List<IFocus<?>> focuses,
        RecipeBookmarkElement<?, ?> element
    ) {
        // show(focuses) is deliberately used instead of showRecipes(category,
        // ...): the latter restricts the page to the machine category that
        // owns the bookmark. JEI's normal BOOKMARKED sorter then places this
        // bookmark's recipe first in the complete cross-category result.
        Optional<IBookmark> bookmark = element.getBookmark();
        if (bookmark.orElse(null) instanceof RecipeBookmark<?, ?> recipeBookmark) {
            RecipeBookmarkNavigationContext.showInCategoryFirst(
                recipeBookmark.getRecipeCategory(),
                () -> recipesGui.show(focuses)
            );
        } else {
            recipesGui.show(focuses);
        }
    }

    private static List<RecipeIngredientRole> jeiPlusPlus$bookmarkRoles(
        Optional<IBookmark> bookmark,
        List<RecipeIngredientRole> fallback
    ) {
        Object value = bookmark.orElse(null);
        if (!(value instanceof RecipeBookmark<?, ?>)) {
            return fallback.isEmpty() ? List.of(RecipeIngredientRole.OUTPUT) : fallback;
        }
        try {
            Object output = value.getClass().getMethod("isDisplayIsOutput").invoke(value);
            if (output instanceof Boolean isOutput) {
                return List.of(isOutput ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Older JEI recipe bookmarks do not expose the output flag.
        }
        return List.of(RecipeIngredientRole.OUTPUT);
    }
}
