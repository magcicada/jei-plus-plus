package com.lingmu0.JeiPlusPlusMod.mixin;
import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;

/** The new JEI command path already falls back to the hovered ingredient when no recipe bookmark exists. */
@Pseudo
@Mixin(targets="mezz.jei.gui.input.handlers.BookmarkCommands", remap=false)
public abstract class JeiBookmarkCommandsMixin {
    @Inject(method="prepareRecipeBookmarkAction", at=@At("HEAD"), cancellable=true, remap=false)
    private void jeiPlusPlus$preference(@Coerce Object input, CallbackInfoReturnable<Optional<?>> cir) {
        if (!JeiPlusPlusConfig.PREFER_RECIPE_BOOKMARK_ON_OUTPUT.get()) cir.setReturnValue(Optional.empty());
    }
}
