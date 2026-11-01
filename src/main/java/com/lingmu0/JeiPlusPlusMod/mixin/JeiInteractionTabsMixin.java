package com.lingmu0.JeiPlusPlusMod.mixin;
import com.lingmu0.JeiPlusPlusMod.client.JeiReflectionCompat;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.recipes.IRecipeGuiLogic;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native interaction-based JEI category-row scrolling. */
@Pseudo
@Mixin(targets="mezz.jei.gui.recipes.RecipeGuiTabs", remap=false)
public abstract class JeiInteractionTabsMixin {
    @Shadow @Final private IRecipeGuiLogic recipeGuiLogic;
    @Shadow private ImmutableRect2i area;
    @Shadow public abstract boolean nextPage();
    @Shadow public abstract boolean previousPage();

    @Inject(method="scroll", at=@At("HEAD"), cancellable=true, remap=false)
    private void jeiPlusPlus$scroll(double x, double y, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (com.lingmu0.JeiPlusPlusMod.client.JeiInputAreaCompat.scrollRecipeTabs(this, x, y, dy)) {
            cir.setReturnValue(true);
        }
    }
}
