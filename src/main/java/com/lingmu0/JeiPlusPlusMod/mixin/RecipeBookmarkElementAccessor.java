package com.lingmu0.JeiPlusPlusMod.mixin;

import mezz.jei.common.transfer.RecipeTransferService;
import mezz.jei.gui.overlay.elements.RecipeBookmarkElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the transfer service already owned by JEI's recipe bookmark element. */
@Mixin(value = RecipeBookmarkElement.class, remap = false)
public interface RecipeBookmarkElementAccessor {
    @Accessor("recipeTransferService")
    RecipeTransferService jeiPlusPlus$getRecipeTransferService();
}
