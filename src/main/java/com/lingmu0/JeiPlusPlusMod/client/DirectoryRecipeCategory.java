package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlus;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/**
 * A compact, paged directory used when a recipe slot contains many
 * alternatives. JEI provides the outer page navigation for the chunks.
 */
public final class DirectoryRecipeCategory implements IRecipeCategory<DirectoryRecipe> {
    public static final RecipeType<DirectoryRecipe> TYPE = RecipeType.create(JeiPlusPlus.MODID, "ingredient_directory",
            DirectoryRecipe.class);

    private static final int COLUMNS = 9;
    private static final int ROWS = 9;
    private static final int SLOT_SPACING = 18;
    private static final int WIDTH = COLUMNS * SLOT_SPACING + 6;
    private static final int HEIGHT = ROWS * SLOT_SPACING + 8;

    private final IDrawable background;
    private final IDrawable icon;

    public DirectoryRecipeCategory(IGuiHelper guiHelper) {
        this.background = guiHelper.createBlankDrawable(WIDTH, HEIGHT);
        this.icon = guiHelper.createDrawableItemStack(Items.BOOK.getDefaultInstance());
    }

    @Override
    public RecipeType<DirectoryRecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei_plus_plus.category.ingredient_directory");
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, DirectoryRecipe recipe, IFocusGroup focuses) {
        var ingredients = recipe.ingredients();
        int limit = Math.min(ingredients.size(), COLUMNS * ROWS);
        for (int i = 0; i < limit; i++) {
            int x = 3 + (i % COLUMNS) * SLOT_SPACING;
            int y = 3 + (i / COLUMNS) * SLOT_SPACING;
            builder.addSlot(RecipeIngredientRole.INPUT, x, y)
                    .addTypedIngredient(ingredients.get(i))
                    .setStandardSlotBackground();
        }
    }

    @Override
    public void draw(DirectoryRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX,
            double mouseY) {
        // The category intentionally consists only of the standard JEI slots.
    }
}
