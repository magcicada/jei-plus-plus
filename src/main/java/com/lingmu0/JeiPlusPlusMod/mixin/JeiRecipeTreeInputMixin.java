package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.JeiReflectionCompat;
import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeOverlay;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;

/** Routes the legacy JEI 15 tree controls through the new press/release API. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.recipes.RecipeGuiLayouts", remap = false)
public abstract class JeiRecipeTreeInputMixin {
    @Inject(method = "beginInput", at = @At("HEAD"), cancellable = true, remap = false)
    private void jeiPlusPlus$treeInput(Screen screen, @Coerce Object input, @Coerce Object keys,
                                     CallbackInfoReturnable<Optional<Object>> cir) {
        InputConstants.Key key = JeiReflectionCompat.inputKey(input);
        RecipeGuiLayouts layouts = (RecipeGuiLayouts) (Object) this;
        if (key.getType() != InputConstants.Type.MOUSE || key.getValue() != 0
            || !RecipeTreeOverlay.canClick(layouts, JeiReflectionCompat.inputMouseX(input), JeiReflectionCompat.inputMouseY(input))) return;
        cir.setReturnValue(Optional.of(JeiReflectionCompat.inputAction(release ->
            RecipeTreeOverlay.click(layouts, JeiReflectionCompat.inputMouseX(release), JeiReflectionCompat.inputMouseY(release), 0))));
    }
}
