package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.Internal;
import mezz.jei.common.config.RecipeSorterStage;
import mezz.jei.common.input.IUserInputHandler;
import mezz.jei.common.input.UserInput;
import mezz.jei.common.input.handlers.SameElementInputHandler;
import mezz.jei.common.transfer.RecipeTransferService;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.gui.input.handlers.BookmarkInputHandler;
import mezz.jei.gui.overlay.elements.RecipeBookmarkElement;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Integrates JEI++'s output-slot recipe bookmark preference with JEI 15.62.
 *
 * <p>When JEI++'s preference and JEI's BOOKMARKED recipe sorter are both
 * enabled, an output-slot bookmark becomes a recipe bookmark and uses the
 * exact output ingredient under the mouse. Input slots are left entirely to
 * JEI's normal ingredient-bookmark path.</p>
 */
@Mixin(value = BookmarkInputHandler.class, remap = false)
public abstract class BookmarkInputHandlerMixin {
    @Shadow @Final private BookmarkList bookmarkList;
    @Shadow @Final private RecipesGui recipesGui;

    /**
     * JEI calls this before {@code handleIngredientBookmark}. We only take over
     * output slots when JEI++ explicitly prefers recipe bookmarks.
     *
     * <p>If JEI's BOOKMARKED sorting stage is disabled, return empty so JEI's
     * own {@code handleUserInput} naturally falls through to
     * {@code handleIngredientBookmark}. This preserves normal ingredient
     * bookmarks without reimplementing JEI's bookmark list logic.</p>
     */
    @Inject(
            method = "handleRecipeBookmark",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void jeiPlusPlus$handlePreferredRecipeBookmark(
            UserInput input,
            CallbackInfoReturnable<Optional<IUserInputHandler>> cir
    ) {
        if (!JeiPlusPlusConfig.PREFER_RECIPE_BOOKMARK_ON_OUTPUT.get()) {
            return;
        }

        if (!jeiPlusPlus$isBookmarkedRecipeSortingEnabled()) {
            // Returning empty makes JEI continue into handleIngredientBookmark.
            cir.setReturnValue(Optional.empty());
            return;
        }

        double mouseX = input.getMouseX();
        double mouseY = input.getMouseY();

        Optional<IRecipeLayoutWithButtons<?>> layoutWithButtonsOptional =
                recipesGui.getRecipeLayoutUnderMouse(mouseX, mouseY);
        if (layoutWithButtonsOptional.isEmpty()) {
            return;
        }

        IRecipeLayoutWithButtons<?> layoutWithButtons = layoutWithButtonsOptional.get();
        IRecipeLayoutDrawable<?> layout = layoutWithButtons.getRecipeLayout();
        Optional<RecipeSlotUnderMouse> slotUnderMouse = layout.getSlotUnderMouse(mouseX, mouseY);

        // Input slots must remain normal JEI ingredient bookmarks.
        if (slotUnderMouse.isEmpty()
                || slotUnderMouse.get().slot().getRole() != RecipeIngredientRole.OUTPUT) {
            cir.setReturnValue(Optional.empty());
            return;
        }

        Optional<ITypedIngredient<?>> hoveredOutput = slotUnderMouse.get().slot().getDisplayedIngredient()
                .or(() -> slotUnderMouse.get().slot().getAllIngredients().findFirst());
        if (hoveredOutput.isEmpty()) {
            cir.setReturnValue(Optional.empty());
            return;
        }

        RecipeBookmark<?, ?> templateBookmark = layoutWithButtons.getRecipeBookmark();
        if (templateBookmark == null) {
            // If JEI cannot create a recipe bookmark for this layout, leave its
            // normal ingredient bookmark path available instead.
            cir.setReturnValue(Optional.empty());
            return;
        }

        RecipeBookmark<?, ?> bookmark = jeiPlusPlus$createBookmarkForHoveredOutput(
                layout,
                hoveredOutput.get(),
                templateBookmark
        );
        if (bookmark == null) {
            cir.setReturnValue(Optional.empty());
            return;
        }

        if (!input.isSimulate()) {
            bookmarkList.toggleBookmark(bookmark);
        }

        IUserInputHandler currentHandler = (IUserInputHandler) (Object) this;
        cir.setReturnValue(Optional.of(
                new SameElementInputHandler(currentHandler, layout::isMouseOver)
        ));
    }

    /**
     * Builds a JEI 15.62 recipe bookmark with the exact output under the mouse.
     * The existing bookmark supplies the RecipeTransferService that JEI attached
     * to this recipe layout, so transfer behavior remains identical to JEI's
     * native bookmark.
     */
    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RecipeBookmark<?, ?> jeiPlusPlus$createBookmarkForHoveredOutput(
            IRecipeLayoutDrawable<?> layout,
            ITypedIngredient<?> hoveredOutput,
            RecipeBookmark<?, ?> templateBookmark
    ) {
        IRecipeCategory category = layout.getRecipeCategory();
        Object recipe = layout.getRecipe();
        ResourceLocation recipeUid = category.getRegistryName(recipe);
        if (recipeUid == null) {
            return null;
        }

        IIngredientManager ingredientManager = Internal.getJeiRuntime().getIngredientManager();
        ITypedIngredient<?> normalized = ingredientManager.normalizeTypedIngredient(hoveredOutput);

        Object element = templateBookmark.getElement();
        if (!(element instanceof RecipeBookmarkElement<?, ?>)) {
            return null;
        }

        RecipeTransferService recipeTransferService =
                ((RecipeBookmarkElementAccessor) element).jeiPlusPlus$getRecipeTransferService();

        return new RecipeBookmark(
                category,
                recipe,
                recipeUid,
                normalized,
                RecipeIngredientRole.OUTPUT,
                recipeTransferService
        );
    }

    /** Uses JEI 15.62's own mapping from the BOOKMARKED stage to its config. */
    @Unique
    private static boolean jeiPlusPlus$isBookmarkedRecipeSortingEnabled() {
        try {
            return RecipeSorterStage.BOOKMARKED.isEnabled(
                    Internal.getClientConfigs().getClientConfig()
            );
        } catch (RuntimeException ignored) {
            // Fail closed: normal ingredient bookmarks are safer if JEI's
            // client config is temporarily unavailable.
            return false;
        }
    }
}
