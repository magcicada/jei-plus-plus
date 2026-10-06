package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeFavorites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Re-applies priority after Integrated Terminals receives or filters storage data. */
@Pseudo
@Mixin(targets = "org.cyclops.integratedterminals.core.terminalstorage.TerminalStorageTabIngredientComponentClient", remap = false)
public abstract class IntegratedTerminalStorageMixin {
    @Inject(method = {"onChange", "resetFilteredIngredientsViews", "setInstanceFilter",
            "handleActiveIngredientUpdate"}, at = @At("TAIL"), remap = false, require = 0)
    private void jeiPlusPlus$prioritizeAfterStorageUpdate(CallbackInfo ci) {
        RecipeTreeFavorites.applyCurrentNetworkPriority();
    }
}
