package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import mezz.jei.common.input.IInputTarget;

/** Register the reserved category row and give the modal picker first refusal on input. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.overlay.IngredientListOverlay", remap = false)
public abstract class JeiIngredientOverlayInputMixin implements JeiExtraInputArea {
    @Override
    public boolean jeiPlusPlus$containsExtraInput(double x, double y) {
        return Boolean.TRUE.equals(JeiReflectionCompat.tryInvoke(this, "isListDisplayed"))
            && CreativeTabGridCompat.containsExtraInput(JeiReflectionCompat.readField(this, "contents"), x, y);
    }

    @Override
    public boolean jeiPlusPlus$isModalInput() {
        return CreativeTabGridCompat.ownsSelector(JeiReflectionCompat.readField(this, "contents"));
    }

    @ModifyArg(
        method = "createInputArea",
        at = @At(
            value = "INVOKE",
            target = "Lmezz/jei/gui/input/InputArea$Builder;controls(Lmezz/jei/common/input/IInputTarget;)Lmezz/jei/gui/input/InputArea$Builder;"
        ),
        index = 0,
        remap = false
    )
    private IInputTarget jeiPlusPlus$priorityInput(IInputTarget original) {
        return (IInputTarget) CreativeTabGridCompat.wrapInput(
            JeiReflectionCompat.readField(this, "contents"), original);
    }
}
