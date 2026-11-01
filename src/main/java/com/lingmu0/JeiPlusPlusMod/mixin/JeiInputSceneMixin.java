package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.JeiInputAreaCompat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;
import java.util.Optional;

/** New JEI rejects events outside native overlays before our control wrappers run. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.input.GuiInputScene", remap = false)
public abstract class JeiInputSceneMixin {
    @Shadow @Final private List<?> inputAreas;

    @Inject(method = "findTarget(DD)Ljava/util/Optional;", at = @At("RETURN"), cancellable = true, remap = false)
    private void jeiPlusPlus$extraAreas(double x, double y, CallbackInfoReturnable<Optional<?>> cir) {
        cir.setReturnValue(JeiInputAreaCompat.selectTarget(inputAreas, cir.getReturnValue(), x, y));
    }
}
