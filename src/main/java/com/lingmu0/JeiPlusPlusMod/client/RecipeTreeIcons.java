package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlus;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Shared bitmap icons for the global tree, recipe-tree and default-recipe buttons. */
public final class RecipeTreeIcons {
    private static IDrawable globalTree;
    private static IDrawable recipeTree;
    private static IDrawable defaultRecipe;

    private RecipeTreeIcons() {
    }

    public static void initialize(IGuiHelper guiHelper) {
        globalTree = guiHelper.drawableBuilder(texture("recipe_tree_global.png"), 0, 0, 18, 18)
                .setTextureSize(18, 18)
                .build();
        recipeTree = guiHelper.drawableBuilder(texture("recipe_tree.png"), 0, 0, 10, 10)
                .setTextureSize(10, 10)
                .build();
        defaultRecipe = guiHelper.drawableBuilder(texture("recipe_default.png"), 0, 0, 10, 10)
                .setTextureSize(10, 10)
                .build();
    }

    public static void drawGlobalTree(GuiGraphics graphics, int x, int y) {
        if (globalTree != null) {
            globalTree.draw(graphics, x, y);
        }
    }

    public static void drawTree(GuiGraphics graphics, int x, int y) {
        if (recipeTree != null) {
            recipeTree.draw(graphics, x, y);
        }
    }

    public static void drawDefault(GuiGraphics graphics, int x, int y) {
        if (defaultRecipe != null) {
            defaultRecipe.draw(graphics, x, y);
        }
    }

    private static ResourceLocation texture(String name) {
        return new ResourceLocation(JeiPlusPlus.MODID, "textures/gui/" + name);
    }
}
