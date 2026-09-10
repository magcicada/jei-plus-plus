package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.common.transfer.RecipeTransferService;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.UserInput;
import mezz.jei.gui.input.handlers.BookmarkInputHandler;
import mezz.jei.gui.input.handlers.SameElementInputHandler;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import mezz.jei.gui.recipes.RecipeTransferButtonController;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

/** Uses the preferred recipe only for output-slot bookmarks. */
@Mixin(value = BookmarkInputHandler.class, remap = false)
public abstract class BookmarkInputHandlerMixin {
    @Shadow @Final private BookmarkList bookmarkList;

    /**
     * JEI 15.x handles an output-slot bookmark in a separate
     * {@code handleRecipeBookmark} method before it reaches the ingredient
     * handler below.  If JEI's BOOKMARKED recipe-sort stage is off, route that
     * click through JEI's normal ingredient path instead of allowing the
     * recipe bookmark to be created.
     */
    @Inject(
        method = "handleRecipeBookmark",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void jeiPlusPlus$gateRecipeBookmark(
        UserInput input,
        CallbackInfoReturnable<java.util.Optional<IUserInputHandler>> cir
    ) {
        if (JeiPlusPlusConfig.PREFER_RECIPE_BOOKMARK_ON_OUTPUT.get()
            && !isJeiBookmarkedRecipeSortingEnabled()) {
            cir.setReturnValue(java.util.Optional.empty());
        }
    }

    @Inject(
        method = {"handleBookmark", "handleIngredientBookmark"},
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void jeiPlusPlus$preferRecipeBookmark(
        UserInput input,
        IInternalKeyMappings keyBindings,
        CallbackInfoReturnable<java.util.Optional<IUserInputHandler>> cir
    ) {
        IJeiRuntime runtime = Internal.getJeiRuntime();
        if (!(runtime.getRecipesGui() instanceof RecipesGui recipesGui)
            || Minecraft.getInstance().screen != recipesGui) {
            // RecipesGui remains alive after it is closed. Do not let its last
            // layout handle bookmarks clicked in the player's inventory.
            return;
        }

        RecipeGuiLayouts layouts = ((RecipesGuiAccessor) (Object) recipesGui).jeiPlusPlus$getLayouts();
        for (IRecipeLayoutWithButtons<?> layoutWithButtons :
            ((RecipeGuiLayoutsAccessor) (Object) layouts).jeiPlusPlus$getRecipeLayoutsWithButtons()) {
            IRecipeLayoutDrawable<?> layout = layoutWithButtons.getRecipeLayout();
            java.util.Optional<RecipeSlotUnderMouse> under = layout.getSlotUnderMouse(input.getMouseX(), input.getMouseY());
            if (under.isEmpty() || under.get().slot().isEmpty()) {
                continue;
            }
            // Do not turn an ingredient in an input slot into a recipe bookmark.
            if (under.get().slot().getRole() != RecipeIngredientRole.OUTPUT) {
                return;
            }
            java.util.Optional<ITypedIngredient<?>> output = under.get().slot().getDisplayedIngredient()
                .or(() -> under.get().slot().getAllIngredients().findFirst());
            if (output.isEmpty()) {
                continue;
            }
            if (!input.isSimulate()) {
                if (JeiPlusPlusConfig.PREFER_RECIPE_BOOKMARK_ON_OUTPUT.get()
                    && isJeiBookmarkedRecipeSortingEnabled()) {
                    RecipeBookmark<?, ?> bookmark = createBookmarkForHoveredOutput(layout, output, runtime);
                    if (bookmark != null) {
                        bookmarkList.toggleBookmark(bookmark);
                    } else {
                        toggleIngredientBookmark(bookmarkList,
                            runtime.getIngredientManager().normalizeTypedIngredient(output.get()));
                    }
                } else {
                    toggleIngredientBookmark(bookmarkList,
                        runtime.getIngredientManager().normalizeTypedIngredient(output.get()));
                }
            }
            IUserInputHandler currentHandler = (IUserInputHandler) (Object) this;
            cir.setReturnValue(java.util.Optional.of(new SameElementInputHandler(currentHandler, layout::isMouseOver)));
            return;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RecipeBookmark<?, ?> createBookmarkForHoveredOutput(
        IRecipeLayoutDrawable<?> layout,
        java.util.Optional<ITypedIngredient<?>> output,
        IJeiRuntime runtime
    ) {
        if (output.isEmpty()) {
            return null;
        }
        ResourceLocation recipeUid = ((mezz.jei.api.recipe.category.IRecipeCategory) layout.getRecipeCategory())
            .getRegistryName(layout.getRecipe());
        if (recipeUid == null) {
            return null;
        }
        ITypedIngredient<?> normalized = runtime.getIngredientManager().normalizeTypedIngredient(output.get());
        RecipeTransferService recipeTransferService = new RecipeTransferService(runtime.getRecipeTransferManager());
        return new RecipeBookmark(layout.getRecipeCategory(), layout.getRecipe(), recipeUid, normalized, true,recipeTransferService);
    }

    /**
     * JEI changed the public helper used to create ingredient bookmarks between
     * the 15.x and 19.x lines. Resolve it reflectively so the output-slot
     * behavior remains identical on both supported versions.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void toggleIngredientBookmark(
        BookmarkList bookmarks,
        ITypedIngredient<?> ingredient
    ) {
        IBookmark existing = null;
        for (Object value : bookmarks.getElements()) {
            if (!(value instanceof mezz.jei.gui.overlay.elements.IElement<?> element)) {
                continue;
            }
            Optional<IBookmark> bookmark = element.getBookmark();
            if (bookmark.isEmpty() || !isIngredientBookmark(bookmark.get())) {
                continue;
            }
            if (sameIngredient(ingredient, element.getTypedIngredient())) {
                existing = bookmark.get();
                break;
            }
        }
        if (existing != null) {
            bookmarks.remove(existing);
            return;
        }
        try {
            Method add = bookmarks.getClass().getMethod("addIngredientBookmark", ITypedIngredient.class);
            add.invoke(bookmarks, ingredient);
            return;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // JEI 19.27 and older do not expose addIngredientBookmark.
        }
        try {
            IBookmark bookmark = createIngredientBookmark(bookmarks, ingredient);
            if (bookmark != null) {
                bookmarks.toggleBookmark(bookmark);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A missing helper should not break JEI's normal input handling.
        }
    }

    /**
     * JEI 19.27 creates ingredient bookmarks through BookmarkFactory, while
     * later versions expose the same operation directly on BookmarkList.
     * Resolve the old factory from BookmarkList so its serialized ingredient
     * id is generated exactly as JEI expects.
     */
    private static IBookmark createIngredientBookmark(
        BookmarkList bookmarks,
        ITypedIngredient<?> ingredient
    ) throws ReflectiveOperationException {
        Field factoryField = findField(bookmarks.getClass(), "bookmarkFactory");
        if (factoryField == null) {
            return null;
        }
        factoryField.setAccessible(true);
        Object factory = factoryField.get(bookmarks);
        for (Method method : factory.getClass().getMethods()) {
            if (!method.getName().equals("create") || method.getParameterCount() != 1
                || !method.getParameterTypes()[0].isAssignableFrom(ingredient.getClass())) {
                continue;
            }
            Object value = method.invoke(factory, ingredient);
            return value instanceof IBookmark bookmark ? bookmark : null;
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        while (type != null) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static boolean isIngredientBookmark(IBookmark bookmark) {
        return bookmark.getClass().getName().endsWith("IngredientBookmark");
    }

    private static boolean sameIngredient(ITypedIngredient<?> first, ITypedIngredient<?> second) {
        if (first == null || second == null || !Objects.equals(first.getType(), second.getType())) {
            return false;
        }
        Object a = first.getIngredient();
        Object b = second.getIngredient();
        if (a instanceof net.minecraft.world.item.ItemStack firstStack
            && b instanceof net.minecraft.world.item.ItemStack secondStack) {
            return net.minecraft.world.item.ItemStack.matches(firstStack, secondStack);
        }
        return Objects.equals(a, b);
    }

    /**
     * Recipe bookmarks only make sense when JEI's own BOOKMARKED sorter is
     * enabled. When the player disables that stage, keep normal ingredient
     * bookmark behavior even if the JEI++ preference is enabled.
     */
    private static boolean isJeiBookmarkedRecipeSortingEnabled() {
        try {
            Object config = Internal.getJeiClientConfigs().getClientConfig();
            Object stages;
            try {
                // JEI 15.x exposes getRecipeSorterStages() directly.
                stages = invokeNoArg(config, "getRecipeSorterStages");
            } catch (ReflectiveOperationException ignored) {
                // JEI 19.x exposes recipeSorterStages().getValue().
                Object value = invokeNoArg(config, "recipeSorterStages");
                stages = invokeNoArg(value, "getValue");
            }
            if (stages instanceof java.util.Collection<?> collection) {
                return collection.stream().anyMatch(BookmarkInputHandlerMixin::isBookmarkedStage);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // If a future JEI hides this config, fail closed: an ingredient
            // bookmark is safer than unexpectedly creating a recipe bookmark.
        }
        return false;
    }

    private static boolean isBookmarkedStage(Object value) {
        if (value instanceof Enum<?> enumValue) {
            return "BOOKMARKED".equals(enumValue.name());
        }
        return "BOOKMARKED".equals(String.valueOf(value));
    }

    private static Object invokeNoArg(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            throw new NoSuchMethodException(name);
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

}
