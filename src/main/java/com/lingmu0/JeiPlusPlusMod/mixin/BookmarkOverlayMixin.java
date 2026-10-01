package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import com.lingmu0.JeiPlusPlusMod.client.CreativeTabGridCompat;
import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeFavorites;
import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeRightClickHandler;
import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeScreen;
import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeSession;
import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeSidebarButton;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.elements.GuiIconToggleButton;
import mezz.jei.common.input.IUserInputHandler;
import mezz.jei.common.input.handlers.CombinedInputHandler;
import mezz.jei.gui.input.handlers.ProxyInputHandler;
import mezz.jei.gui.overlay.ScreenPropertiesCache;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

@Mixin(value = BookmarkOverlay.class, remap = false)
public abstract class BookmarkOverlayMixin {
    @Shadow @Final private ScreenPropertiesCache screenPropertiesCache;

    @Shadow @Final private BookmarkList bookmarkList;

    @Shadow
    public abstract boolean hasRoom();

    @Unique private RecipeTreeSidebarButton jeiPlusPlus$treeButton;
    @Unique private static volatile Field jeiPlusPlus$historyButtonField;
    @Unique private static volatile boolean jeiPlusPlus$historyButtonFieldResolved;
    @Unique private static volatile Field jeiPlusPlus$toggleAreaField;
    @Unique private static volatile boolean jeiPlusPlus$toggleAreaFieldResolved;

    /**
     * JEI's BookmarkOverlay constructor is not a stable extension point. JEI
     * 15.20 has five parameters while 15.21 adds the lookup-history overlay
     * and client config. Creating our button lazily keeps this mixin valid for
     * both signatures (and for later JEI patch releases).
     */
    @Unique
    private void jeiPlusPlus$ensureTreeButton() {
        RecipeTreeFavorites.bind(bookmarkList);
        if (jeiPlusPlus$treeButton == null) {
            jeiPlusPlus$treeButton = new RecipeTreeSidebarButton();
        }
    }

    @Inject(method = "updateBounds", at = @At("TAIL"), remap = false)
    private void jeiPlusPlus$placeTreeButton(IGuiProperties guiProperties, CallbackInfo ci) {
        jeiPlusPlus$ensureTreeButton();
        if (!JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get()) {
            jeiPlusPlus$treeButton.updateBounds(ImmutableRect2i.EMPTY);
            return;
        }
        int leftWidth = Math.max(0, guiProperties.getGuiLeft());
        ImmutableRect2i bookmarkArea = new ImmutableRect2i(0, 0, leftWidth, guiProperties.getScreenHeight())
                .insetBy(6);
        bookmarkArea = bookmarkArea.keepBottom(20).keepLeft(20);
        GuiIconToggleButton historyButton = jeiPlusPlus$historyButton();
        ImmutableRect2i historyArea = jeiPlusPlus$buttonArea(historyButton);
        if (!historyArea.isEmpty()) {
            // JEI 15.21 added lookup history between the bookmark and the
            // sidebar. Place our button after whichever built-in controls are
            // actually present instead of assuming the old two-button layout.
            jeiPlusPlus$treeButton.updateBounds(historyArea.moveRight(22));
        } else {
            jeiPlusPlus$treeButton.updateBounds(bookmarkArea.moveRight(22));
        }
    }

    @Inject(method = "isListDisplayed", at = @At("RETURN"), cancellable = true, remap = false)
    private void jeiPlusPlus$showTreeFavorites(CallbackInfoReturnable<Boolean> cir) {
        // The recipe-tree screen owns the whole canvas. Keep JEI's bookmark
        // grid, its page controls, tooltips, and hit boxes out of this screen;
        // the separate recipe-tree sidebar button is still drawn below.
        if (Minecraft.getInstance().screen instanceof RecipeTreeScreen) {
            cir.setReturnValue(false);
            return;
        }
        if (JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get()
                && RecipeTreeFavorites.isActive()
                && screenPropertiesCache.hasValidScreen()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "drawScreen", at = @At("TAIL"), remap = false)
    private void jeiPlusPlus$drawTreeButton(Minecraft minecraft, GuiGraphics graphics, int mouseX, int mouseY,
                                            float partialTicks, CallbackInfo ci) {
        if (!jeiPlusPlus$isTreeButtonScreen()) {
            return;
        }
        jeiPlusPlus$ensureTreeButton();
        if (Minecraft.getInstance().screen instanceof RecipeTreeScreen) {
            jeiPlusPlus$treeButton.updateBounds(new ImmutableRect2i(6, minecraft.getWindow().getGuiScaledHeight() - 26, 20, 20));
        }
        RecipeTreeFavorites.refreshThrottled();
        jeiPlusPlus$treeButton.tick();
        jeiPlusPlus$treeButton.draw(graphics, mouseX, mouseY, partialTicks);
    }

    @Inject(method = "drawTooltips", at = @At("TAIL"), remap = false)
    private void jeiPlusPlus$drawTreeButtonTooltip(Minecraft minecraft, GuiGraphics graphics, int mouseX, int mouseY,
                                                   CallbackInfo ci) {
        if (jeiPlusPlus$isTreeButtonScreen()) {
            jeiPlusPlus$ensureTreeButton();
            jeiPlusPlus$treeButton.drawTooltips(graphics, mouseX, mouseY);
        }
    }

    @Inject(method = "drawTooltips", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void jeiPlusPlus$hideBookmarkTooltipsWhileSelectorOpen(
            Minecraft minecraft,
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            CallbackInfo ci
    ) {
        if (CreativeTabGridCompat.isAnySelectorOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "createInputHandler", at = @At("RETURN"), cancellable = true, remap = false)
    private void jeiPlusPlus$addTreeButtonInput(CallbackInfoReturnable<IUserInputHandler> cir) {
        jeiPlusPlus$ensureTreeButton();
        IUserInputHandler original = cir.getReturnValue();
        IUserInputHandler treeButtonInput = new CombinedInputHandler(
                "JeiPlusPlusRecipeTreeButton",
                new RecipeTreeRightClickHandler(jeiPlusPlus$treeButton),
                jeiPlusPlus$treeButton.createInputHandler()
        );
        IUserInputHandler normalScreenInput = new CombinedInputHandler(
                "JeiPlusPlusRecipeTreeAndBookmarks",
                treeButtonInput,
                original
        );
        cir.setReturnValue(new ProxyInputHandler(() -> {
            if (!JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get()) {
                return original;
            }
            if (Minecraft.getInstance().screen instanceof RecipeTreeScreen) {
                return treeButtonInput;
            }
            return screenPropertiesCache.hasValidScreen() ? normalScreenInput : original;
        }));
    }

    @Unique
    private boolean jeiPlusPlus$isTreeButtonScreen() {
        return JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get()
                && (screenPropertiesCache.hasValidScreen() || Minecraft.getInstance().screen instanceof RecipeTreeScreen);
    }

    @Unique
    private GuiIconToggleButton jeiPlusPlus$historyButton() {
        if (!jeiPlusPlus$historyButtonFieldResolved) {
            synchronized (BookmarkOverlayMixin.class) {
                if (!jeiPlusPlus$historyButtonFieldResolved) {
                    Field history = null;
                    Field lastButton = null;
                    int buttonCount = 0;
                    for (Field field : BookmarkOverlay.class.getDeclaredFields()) {
                        // Mixin fields are copied onto the target class. Do
                        // not mistake JEI++'s own sidebar button for a JEI
                        // history button when the latter is obfuscated.
                        if (field.getName().contains("jeiPlusPlus")) {
                            continue;
                        }
                        if (!GuiIconToggleButton.class.isAssignableFrom(field.getType())) {
                            continue;
                        }
                        buttonCount++;
                        lastButton = field;
                        if (field.getName().toLowerCase(java.util.Locale.ROOT).contains("history")) {
                            history = field;
                        }
                    }
                    if (history == null && buttonCount > 1) {
                        // Obfuscated builds may lose the field name, but the
                        // history button is still the second JEI toggle.
                        history = lastButton;
                    }
                    if (history != null) {
                        try {
                            if (!history.trySetAccessible()) {
                                history = null;
                            }
                        } catch (RuntimeException ignored) {
                            history = null;
                        }
                    }
                    jeiPlusPlus$historyButtonField = history;
                    jeiPlusPlus$historyButtonFieldResolved = true;
                }
            }
        }
        Field history = jeiPlusPlus$historyButtonField;
        if (history == null) {
            return null;
        }
        try {
            Object value = history.get(this);
            return value == jeiPlusPlus$treeButton ? null : (GuiIconToggleButton) value;
        } catch (IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    @Unique
    private ImmutableRect2i jeiPlusPlus$buttonArea(GuiIconToggleButton button) {
        if (button == null) {
            return ImmutableRect2i.EMPTY;
        }
        if (!jeiPlusPlus$toggleAreaFieldResolved) {
            synchronized (BookmarkOverlayMixin.class) {
                if (!jeiPlusPlus$toggleAreaFieldResolved) {
                    Field area = null;
                    for (Field field : GuiIconToggleButton.class.getDeclaredFields()) {
                        if (ImmutableRect2i.class.isAssignableFrom(field.getType())) {
                            area = field;
                            break;
                        }
                    }
                    if (area != null) {
                        try {
                            if (!area.trySetAccessible()) {
                                area = null;
                            }
                        } catch (RuntimeException ignored) {
                            area = null;
                        }
                    }
                    jeiPlusPlus$toggleAreaField = area;
                    jeiPlusPlus$toggleAreaFieldResolved = true;
                }
            }
        }
        Field area = jeiPlusPlus$toggleAreaField;
        if (area == null) {
            return ImmutableRect2i.EMPTY;
        }
        try {
            Object value = area.get(button);
            return value instanceof ImmutableRect2i immutableArea ? immutableArea : ImmutableRect2i.EMPTY;
        } catch (IllegalAccessException | RuntimeException ignored) {
            return ImmutableRect2i.EMPTY;
        }
    }


}
