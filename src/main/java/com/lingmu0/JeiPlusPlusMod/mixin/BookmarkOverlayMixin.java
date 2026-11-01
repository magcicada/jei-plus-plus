package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import com.lingmu0.JeiPlusPlusMod.client.*;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.bookmarks.BookmarkList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.lang.reflect.*;
import java.util.*;

/** Recipe-tree sidebar button integrated with JEI 15.74 input areas. */
@Mixin(targets = "mezz.jei.gui.overlay.bookmarks.BookmarkOverlay", remap = false)
public abstract class BookmarkOverlayMixin implements JeiExtraInputArea {
    @Shadow @Final private BookmarkList bookmarkList;
    @Unique private ImmutableRect2i jeiPlusPlus$area = ImmutableRect2i.EMPTY;

    @Unique private boolean jeiPlusPlus$visible() {
        Screen screen = Minecraft.getInstance().screen;
        return JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get() && (screen instanceof RecipeTreeScreen
            || screen instanceof AbstractContainerScreen<?> || screen instanceof mezz.jei.gui.recipes.RecipesGui);
    }

    @Override
    public boolean jeiPlusPlus$containsExtraInput(double x, double y) {
        if (!jeiPlusPlus$visible()) return false;
        jeiPlusPlus$place();
        return jeiPlusPlus$area.contains(x, y);
    }

    @Unique private void jeiPlusPlus$place() {
        RecipeTreeFavorites.bind(bookmarkList);
        Object button = JeiReflectionCompat.readField(this, "historyButton");
        Object bounds = button == null ? null : JeiReflectionCompat.tryInvoke(button, "getArea");
        if (!(bounds instanceof ImmutableRect2i)) bounds = JeiReflectionCompat.readField(button, "area");
        if (Minecraft.getInstance().screen instanceof RecipeTreeScreen) {
            jeiPlusPlus$area = new ImmutableRect2i(6, Minecraft.getInstance().getWindow().getGuiScaledHeight() - 26, 20, 20);
        } else if (bounds instanceof ImmutableRect2i area && !area.isEmpty()) {
            jeiPlusPlus$area = area.moveRight(44);
        } else {
            // Follow JEI when the sidebar has no room; do not leave a floating hit box.
            jeiPlusPlus$area = ImmutableRect2i.EMPTY;
        }
    }

    @Inject(method = "isListDisplayed", at = @At("RETURN"), cancellable = true, remap = false)
    private void jeiPlusPlus$list(CallbackInfoReturnable<Boolean> cir) {
        if (Minecraft.getInstance().screen instanceof RecipeTreeScreen) cir.setReturnValue(false);
        else if (jeiPlusPlus$visible() && RecipeTreeFavorites.isActive()) cir.setReturnValue(true);
    }

    @Inject(method = "drawForeground", at = @At("TAIL"), remap = false, require = 0)
    private void jeiPlusPlus$draw(Minecraft mc, GuiGraphics graphics, int x, int y, float partial, CallbackInfo ci) {
        jeiPlusPlus$render(graphics, x, y);
    }

    @Unique private void jeiPlusPlus$render(GuiGraphics graphics, int x, int y) {
        if (!jeiPlusPlus$visible()) return;
        jeiPlusPlus$place();
        if (jeiPlusPlus$area.isEmpty()) return;
        RecipeTreeIcons.drawGlobalButton(graphics, jeiPlusPlus$area,
            !CreativeTabGridCompat.isAnySelectorOpen() && jeiPlusPlus$area.contains(x, y),
            RecipeTreeSession.tree() != null);
    }

    @Inject(method = "drawTooltips", at = @At("HEAD"), cancellable = true, remap = false)
    private void jeiPlusPlus$tooltip(Minecraft mc, GuiGraphics graphics, int x, int y, CallbackInfo ci) {
        if (CreativeTabGridCompat.isAnySelectorOpen()) { ci.cancel(); return; }
        if (jeiPlusPlus$visible()) jeiPlusPlus$place();
        if (jeiPlusPlus$visible() && jeiPlusPlus$area.contains(x, y)) {
            graphics.renderTooltip(mc.font, List.of(Component.translatable("jei_plus_plus.recipe_tree.global_button"),
                Component.translatable(RecipeTreeSession.tree() == null
                    ? "jei_plus_plus.recipe_tree.empty_hint"
                    : "jei_plus_plus.recipe_tree.global_button.clear")), Optional.empty(), x, y);
        }
    }

    @ModifyArg(method = "createInputArea", at = @At(value = "INVOKE",
        target = "Lmezz/jei/gui/input/InputArea$Builder;controls(Lmezz/jei/common/input/IInputTarget;)Lmezz/jei/gui/input/InputArea$Builder;"), index = 0, remap = false)
    private mezz.jei.common.input.IInputTarget jeiPlusPlus$input(mezz.jei.common.input.IInputTarget original) {
        return new mezz.jei.common.input.handlers.InputGroup("JEI++ bookmarks", new mezz.jei.common.input.IInputTarget() {
            @Override
            public Optional<mezz.jei.common.input.interaction.IInputInteraction> beginInput(
                    Screen screen, mezz.jei.common.input.UserInput input,
                    mezz.jei.common.input.IInternalKeyMappings keys) {
                if (!jeiPlusPlus$containsExtraInput(input.getMouseX(), input.getMouseY())
                    || !input.isMouseInput() || input.getKey().getValue() > 1) return Optional.empty();
                int button = input.getKey().getValue();
                return Optional.of(new mezz.jei.common.input.interaction.InputAction(release -> {
                    if (button == 1) RecipeTreeSession.clear();
                    if (screen instanceof RecipeTreeScreen treeScreen) treeScreen.onClose();
                    else if (button == 0) RecipeTreeScreen.openCurrent(screen);
                }));
            }
        }, original);
    }
}
