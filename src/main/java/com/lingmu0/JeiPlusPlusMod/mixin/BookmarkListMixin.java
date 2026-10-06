package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeFavorites;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.overlay.elements.IElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = BookmarkList.class, remap = false)
public abstract class BookmarkListMixin {
    @Inject(method = "notifyListenersOfChange", at = @At("HEAD"), remap = false)
    private void jeiPlusPlus$alignTreeRowsBeforeBookmarkLayout(CallbackInfo ci) {
        RecipeTreeFavorites.bind((BookmarkList) (Object) this);
        RecipeTreeFavorites.refreshBeforeBookmarkListNotification();
    }

    @Inject(method = "getElements", at = @At("RETURN"), cancellable = true, remap = false)
    private void jeiPlusPlus$appendRecipeTreeEntries(CallbackInfoReturnable<List<IElement<?>>> cir) {
        RecipeTreeFavorites.bind((BookmarkList) (Object) this);
        List<IElement<?>> synthetic = RecipeTreeFavorites.elements();
        if (synthetic.isEmpty())
            return;
        List<IElement<?>> combined = new ArrayList<>(cir.getReturnValue().size() + synthetic.size());
        combined.addAll(cir.getReturnValue());
        combined.addAll(synthetic);
        cir.setReturnValue(List.copyOf(combined));
    }

    @Inject(method = "isEmpty", at = @At("RETURN"), cancellable = true, remap = false)
    private void jeiPlusPlus$keepSyntheticEntriesVisible(CallbackInfoReturnable<Boolean> cir) {
        if (!RecipeTreeFavorites.elements().isEmpty())
            cir.setReturnValue(false);
    }
}
