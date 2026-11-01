package com.lingmu0.JeiPlusPlusMod.mixin;

import mezz.jei.gui.recipes.RecipeGuiLayouts;
import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RecipesGui.class, remap = false)
public interface RecipesGuiAccessor {
    @Accessor("layouts")
    RecipeGuiLayouts jeiPlusPlus$getLayouts();

    @Accessor("logic")
    IRecipeGuiLogic jeiPlusPlus$getLogic();



}
