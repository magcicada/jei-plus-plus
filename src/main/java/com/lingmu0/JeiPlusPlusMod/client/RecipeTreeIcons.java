package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlus;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.common.Internal;
import mezz.jei.common.util.ImmutableRect2i;
import com.mojang.blaze3d.systems.RenderSystem;
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

    /** Use the installed JEI skin for normal, hovered and saved-tree selected states. */
    public static void drawGlobalButton(GuiGraphics graphics, ImmutableRect2i area, boolean hovered, boolean selected) {
        if (area.isEmpty()) return;
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        Internal.getTextures().getButtonForState(selected, true, hovered)
            .draw(graphics, area.getX(), area.getY(), area.getWidth(), area.getHeight());
        drawGlobalTree(graphics, area.getX() + (area.getWidth() - 18) / 2,
            area.getY() + (area.getHeight() - 18) / 2);
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
